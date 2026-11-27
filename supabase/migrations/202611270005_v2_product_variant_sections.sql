-- ============================================================================
-- JAJANIN v2.24 — Customizable Product Variant Sections (e.g. Rasa, Topping, Ukuran)
-- Migration: 202611270005_v2_product_variant_sections.sql
-- ============================================================================

-- 1. Add variant_sections jsonb column on public.menu_items
--    Format: [{"name": "Rasa", "options": ["Cokelat", "Stroberi", "Matcha"]}]
ALTER TABLE public.menu_items
    ADD COLUMN IF NOT EXISTS variant_sections jsonb NOT NULL DEFAULT '[]'::jsonb;

-- 2. Add variant_label column on public.order_items
ALTER TABLE public.order_items
    ADD COLUMN IF NOT EXISTS variant_label text NOT NULL DEFAULT '';

-- 3. Update public.get_public_store_profile to return variant_sections on each product
CREATE OR REPLACE FUNCTION public.get_public_store_profile(p_stall_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_stall jsonb;
    v_products jsonb := '[]'::jsonb;
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk melihat profil toko.';
    end if;
    if p_stall_id is null then
        raise exception 'Toko tidak ditemukan.';
    end if;

    select jsonb_build_object(
        'id', s.id,
        'name', s.name,
        'bio', public.clean_public_stall_bio(s.public_bio, s.description),
        'store_category', coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman'),
        'location', case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end,
        'is_open', s.is_open,
        'is_verified', s.is_verified,
        'verified_at', s.verified_at,
        'completed_orders_count', s.completed_orders_count,
        'logo_url', s.logo_url,
        'banner_url', s.banner_url,
        'has_qris', true,
        'owner_id', s.owner_id,
        'seller_name', public.clean_public_person_name(p.display_name),
        'created_at', s.created_at
    )
      into v_stall
      from public.stalls s
      left join public.profiles p on p.id = s.owner_id
     where s.id = p_stall_id
       and (
           s.owner_id = uid
           or public.is_developer()
           or coalesce(p.account_status, 'active') <> 'suspended'
       );

    if v_stall is null then
        raise exception 'Toko tidak ditemukan atau sedang tidak aktif.';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', mi.id,
                'name', mi.name,
                'description', mi.description,
                'category', mi.category,
                'price_idr', mi.price_idr,
                'image_url', mi.image_url,
                'is_available', mi.is_available,
                'stock', mi.stock,
                'stall_id', mi.stall_id,
                'ingredients', mi.ingredients,
                'order_schedule', mi.order_schedule,
                'pickup_sessions', mi.pickup_sessions,
                'po_dates', mi.po_dates,
                'po_cutoff_hour', mi.po_cutoff_hour,
                'po_daily_quota', mi.po_daily_quota,
                'po_require_qris', mi.po_require_qris,
                'po_allow_weekend', mi.po_allow_weekend,
                'variant_sections', coalesce(mi.variant_sections, '[]'::jsonb)
            )
            order by mi.is_available desc,
                     (mi.stock is null or mi.stock > 0) desc,
                     mi.created_at desc
        ),
        '[]'::jsonb
    )
      into v_products
      from public.menu_items mi
     where mi.stall_id = p_stall_id
       and mi.is_available = true
       and mi.name <> ''
       and mi.price_idr > 0;

    return jsonb_build_object(
        'stall', v_stall,
        'products', v_products
    );
end;
$function$;

-- 4. Update place_order_v3 to support variant_label per cart line, aggregate stock/PO quota
--    across multiple flavors of the same product, and embed chosen variants into order_items.item_name
CREATE OR REPLACE FUNCTION public.place_order_v3(
    p_items jsonb,
    p_payment_method text,
    p_order_mode text DEFAULT 'same_day'::text,
    p_pickup_session text DEFAULT 'Istirahat 1'::text,
    p_pickup_date text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    method text := lower(trim(coalesce(p_payment_method, '')));
    v_mode text := lower(trim(coalesce(p_order_mode, 'same_day')));
    v_session text := trim(coalesce(p_pickup_session, 'Istirahat 1'));
    v_raw_date text := trim(coalesce(p_pickup_date, ''));
    v_order_id uuid := gen_random_uuid();
    total integer := 1000;
    it jsonb;
    it_row record;
    agg_row record;
    pid uuid;
    qty integer;
    v_variant text;
    v_display_item_name text;
    prod_name text;
    prod_price integer;
    prod_available boolean;
    prod_stock integer;
    prod_stall_id uuid;
    prod_schedule text;
    prod_po_dates text;
    prod_cutoff_hour smallint;
    prod_daily_quota integer;
    prod_require_qris boolean;
    prod_allow_weekend boolean;
    prod_variants jsonb;
    v_has_required_variants boolean;
    v_allowed_dates text[];
    v_stall_id uuid := null;
    v_stall_owner uuid := null;
    v_stall_name text := 'Warung Jajanin';
    v_stall_open boolean := true;
    v_requires_qris boolean := false;
    v_requires_qris_reason text := '';
    wib_now timestamp := now() at time zone 'Asia/Jakarta';
    wib_date date := (now() at time zone 'Asia/Jakarta')::date;
    v_pickup_date date;
    v_cutoff_ts timestamp;
    v_used_quota integer;
    v_buyer_po_count integer;
    v_unpaid_qris_count integer;
    v_expires_at timestamptz := null;
    v_any_stock_deducted boolean := false;
    stall_order_num integer;
    new_pickup_code text;
    v_convo_id uuid;
    v_items_summary text := '';
    v_total_qty integer := 0;
    v_first_title text := '';
    v_first_img text := '';
    v_sched_text text := '';
    v_order_card_json text;
    v_auto_msg text;
    v_chat_ts timestamptz;
begin
    if uid is null then
        raise exception 'Masuk dulu sebelum memesan.';
    end if;

    perform pg_advisory_xact_lock(hashtext(uid::text)::bigint);
    perform public.expire_unpaid_qris_orders();

    if exists (select 1 from public.profiles p where p.id = uid and p.account_status = 'suspended') then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    if method not in ('cash', 'qris') then
        raise exception 'Metode bayar tidak valid.';
    end if;
    if v_mode not in ('same_day', 'pre_order') then
        raise exception 'Jadwal pengambilan tidak valid.';
    end if;
    if v_session = '' then
        v_session := 'Istirahat 1';
    end if;
    if char_length(v_session) > 40 then
        raise exception 'Sesi pengambilan terlalu panjang.';
    end if;

    if method = 'qris' then
        select count(*)::integer
          into v_unpaid_qris_count
          from public.orders o
         where o.user_id = uid
           and o.status = 'pending'
           and lower(coalesce(o.payment_method, '')) = 'qris'
           and o.payment_status in ('unpaid', 'rejected');
        if v_unpaid_qris_count >= 2 then
            raise exception 'Kamu masih punya 2 pesanan Midtrans yang belum dibayar. Selesaikan atau batalkan dulu sebelum memesan lagi.';
        end if;
    end if;

    if v_mode = 'same_day' then
        v_pickup_date := wib_date;
    else
        if v_raw_date = '' then
            v_pickup_date := wib_date + 1;
        else
            begin
                v_pickup_date := v_raw_date::date;
            exception when others then
                raise exception 'Format tanggal pengambilan tidak valid (YYYY-MM-DD).';
            end;
        end if;
        if v_pickup_date < (wib_date + 1) then
            raise exception 'Tanggal Pre-Order minimal mulai besok.';
        end if;
        if v_pickup_date > (wib_date + 30) then
            raise exception 'Tanggal Pre-Order maksimal 30 hari ke depan.';
        end if;

        select count(*)::integer
          into v_buyer_po_count
          from public.orders o
         where o.user_id = uid
           and o.order_mode = 'pre_order'
           and o.pickup_date = v_pickup_date
           and o.status <> 'cancelled';
        if v_buyer_po_count >= 2 then
            raise exception 'Maksimal 2 pesanan Pre-Order aktif untuk tanggal %.', v_pickup_date::text;
        end if;

        if v_pickup_date > (wib_date + 3) then
            v_requires_qris := true;
            v_requires_qris_reason := 'Pre-Order lebih dari 3 hari wajib pembayaran Midtrans di muka.';
        end if;
    end if;

    if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        raise exception 'Keranjang masih kosong.';
    end if;
    if jsonb_array_length(p_items) > 30 then
        raise exception 'Maksimal 30 baris menu dalam satu pesanan.';
    end if;

    -- Pass 1: Validate each cart line (including variant presence) and accumulate total price
    for it in select * from jsonb_array_elements(p_items) loop
        begin
            pid := (it ->> 'menu_item_id')::uuid;
            qty := (it ->> 'quantity')::integer;
            v_variant := left(trim(coalesce(it ->> 'variant_label', '')), 120);
        exception when others then
            raise exception 'Format item tidak valid.';
        end;
        if qty is null or qty < 1 or qty > 99 then
            raise exception 'Jumlah per menu harus antara 1 sampai 99.';
        end if;
        select mi.stall_id, mi.name, mi.price_idr, mi.is_available, mi.stock,
               mi.order_schedule, coalesce(mi.po_dates, ''),
               mi.po_cutoff_hour, mi.po_daily_quota, mi.po_require_qris, mi.po_allow_weekend,
               coalesce(mi.variant_sections, '[]'::jsonb)
          into prod_stall_id, prod_name, prod_price, prod_available, prod_stock,
               prod_schedule, prod_po_dates,
               prod_cutoff_hour, prod_daily_quota, prod_require_qris, prod_allow_weekend,
               prod_variants
          from public.menu_items mi
         where mi.id = pid;
        if not found then
            raise exception 'Menu tidak ditemukan.';
        end if;
        if v_stall_id is null then
            v_stall_id := prod_stall_id;
        elsif v_stall_id <> prod_stall_id then
            raise exception 'Semua menu dalam satu pesanan harus dari warung yang sama.';
        end if;
        if not prod_available then
            raise exception 'Menu % sedang tidak tersedia.', prod_name;
        end if;

        select exists (
            select 1
              from jsonb_array_elements(prod_variants) sec
             where jsonb_typeof(sec -> 'options') = 'array'
               and jsonb_array_length(sec -> 'options') > 0
        ) into v_has_required_variants;

        if v_has_required_variants and v_variant = '' then
            raise exception 'Pilih varian / rasa terlebih dahulu untuk menu %.', prod_name;
        end if;

        if v_mode = 'same_day' and prod_schedule = 'next_day' then
            raise exception 'Menu % khusus Pre-Order (pilih tanggal ambil).', prod_name;
        end if;
        if v_mode = 'pre_order' and prod_schedule = 'same_day' then
            raise exception 'Menu % khusus ambil Hari Ini.', prod_name;
        end if;

        if v_mode = 'pre_order' then
            if not prod_allow_weekend and extract(isodow from v_pickup_date) in (6, 7) then
                raise exception 'Pre-Order hanya tersedia pada hari sekolah (Senin-Jumat).';
            end if;

            v_cutoff_ts := (v_pickup_date - 1)::timestamp + make_interval(hours => prod_cutoff_hour);
            if wib_now >= v_cutoff_ts then
                raise exception 'Batas pemesanan PO % untuk tanggal % sudah ditutup (maks. H-1 pukul %:00 WIB).',
                    prod_name, v_pickup_date::text, lpad(prod_cutoff_hour::text, 2, '0');
            end if;

            if prod_schedule = 'next_day' and trim(prod_po_dates) = '' then
                raise exception 'Penjual belum menentukan tanggal kalender PO untuk menu %.', prod_name;
            end if;
            if trim(prod_po_dates) <> '' then
                select array_agg(trim(d))
                  into v_allowed_dates
                  from unnest(string_to_array(prod_po_dates, ',')) as d
                 where trim(d) ~ '^\d{4}-\d{2}-\d{2}$';
                if v_allowed_dates is not null and array_length(v_allowed_dates, 1) > 0 then
                    if not (v_pickup_date::text = any(v_allowed_dates)) then
                        raise exception 'Tanggal PO % tidak tersedia untuk menu %.', v_pickup_date::text, prod_name;
                    end if;
                end if;
            end if;

            if prod_require_qris then
                v_requires_qris := true;
                if v_requires_qris_reason = '' then
                    v_requires_qris_reason := 'Menu PO ' || prod_name || ' mewajibkan pembayaran Midtrans di muka.';
                end if;
            end if;
        end if;

        total := total + prod_price * qty;
    end loop;

    -- Pass 2: Validate aggregated quantity per menu_item_id against stock & PO daily quota
    for agg_row in
        select (el ->> 'menu_item_id')::uuid as menu_item_id,
               sum((el ->> 'quantity')::integer)::integer as total_qty
          from jsonb_array_elements(p_items) as el
         group by (el ->> 'menu_item_id')::uuid
         order by (el ->> 'menu_item_id')::uuid
    loop
        select mi.name, mi.stock, mi.order_schedule, mi.po_daily_quota
          into prod_name, prod_stock, prod_schedule, prod_daily_quota
          from public.menu_items mi
         where mi.id = agg_row.menu_item_id;

        if v_mode = 'pre_order' and prod_daily_quota is not null then
            perform pg_advisory_xact_lock(hashtext('po_quota:' || agg_row.menu_item_id::text || ':' || v_pickup_date::text)::bigint);
            select coalesce(sum(oi.quantity), 0)::integer
              into v_used_quota
              from public.order_items oi
              join public.orders o on o.id = oi.order_id
             where oi.menu_item_id = agg_row.menu_item_id
               and o.order_mode = 'pre_order'
               and o.pickup_date = v_pickup_date
               and o.status <> 'cancelled';
            if (v_used_quota + agg_row.total_qty) > prod_daily_quota then
                raise exception 'Kuota PO % pada tanggal % tidak cukup (tersisa % porsi).',
                    prod_name, v_pickup_date::text, greatest(0, prod_daily_quota - v_used_quota);
            end if;
        end if;

        if prod_stock is not null and (v_mode = 'same_day' or prod_schedule = 'next_day') then
            if prod_stock < agg_row.total_qty then
                raise exception 'Stok % tidak mencukupi (tersisa %).', prod_name, prod_stock;
            end if;
            if method = 'cash' then
                v_any_stock_deducted := true;
            end if;
        end if;
    end loop;

    select s.owner_id,
           coalesce(nullif(trim(s.name), ''), 'Warung Jajanin'),
           coalesce(s.is_open, true)
      into v_stall_owner, v_stall_name, v_stall_open
      from public.stalls s
     where s.id = v_stall_id;
    if not found then
        raise exception 'Warung tidak ditemukan.';
    end if;
    if not v_stall_open then
        raise exception 'Warung sedang tutup dan belum menerima pesanan.';
    end if;
    if v_stall_owner is not null and v_stall_owner = uid then
        raise exception 'Kamu tidak bisa memesan menu dari warung milik sendiri.';
    end if;

    if v_mode = 'pre_order' and v_requires_qris and method <> 'qris' then
        raise exception '%', v_requires_qris_reason;
    end if;

    if method = 'qris' then
        v_expires_at := now() + interval '15 minutes';
        v_any_stock_deducted := false;
    end if;

    perform pg_advisory_xact_lock(hashtext('stall:' || v_stall_id::text)::bigint);

    select count(*) + 1 into stall_order_num
      from public.orders o
     where o.stall_id = v_stall_id
        or (o.stall_id is null and exists (
            select 1 from public.order_items oi
            join public.menu_items mi on mi.id = oi.menu_item_id
            where oi.order_id = o.id and mi.stall_id = v_stall_id
        ));
    new_pickup_code := lpad(stall_order_num::text, 3, '0');
    while exists (select 1 from public.orders where pickup_code = new_pickup_code) loop
        stall_order_num := stall_order_num + 1;
        new_pickup_code := lpad(stall_order_num::text, 3, '0');
    end loop;

    insert into public.orders (
        id, user_id, stall_id, status, total_idr, payment_method, payment_status,
        pickup_code, order_mode, pickup_date, pickup_session,
        payment_expires_at, stock_deducted
    )
    values (
        v_order_id, uid, v_stall_id, 'pending', total, method, 'unpaid',
        new_pickup_code, v_mode, v_pickup_date, v_session,
        v_expires_at, v_any_stock_deducted
    );

    perform set_config('app.internal_stock_op', 'on', true);

    -- Pass 3: Lock & deduct aggregated stock once per distinct menu_item_id (for cash)
    for agg_row in
        select (el ->> 'menu_item_id')::uuid as menu_item_id,
               sum((el ->> 'quantity')::integer)::integer as total_qty
          from jsonb_array_elements(p_items) as el
         group by (el ->> 'menu_item_id')::uuid
         order by (el ->> 'menu_item_id')::uuid
    loop
        select mi.name, mi.stock, mi.order_schedule
          into prod_name, prod_stock, prod_schedule
          from public.menu_items mi
         where mi.id = agg_row.menu_item_id
           for update;

        if method = 'cash'
           and prod_stock is not null
           and (v_mode = 'same_day' or prod_schedule = 'next_day') then
            update public.menu_items
               set stock = stock - agg_row.total_qty,
                   updated_at = now()
             where id = agg_row.menu_item_id and stock >= agg_row.total_qty;
            if not found then
                raise exception 'Stok % berubah saat memesan, coba lagi.', prod_name;
            end if;
        end if;
    end loop;

    -- Pass 4: Insert individual order_items rows preserving each chosen flavor/variant
    for it_row in
        select (el ->> 'menu_item_id')::uuid as menu_item_id,
               (el ->> 'quantity')::integer as quantity,
               left(trim(coalesce(el ->> 'variant_label', '')), 120) as variant_label
          from jsonb_array_elements(p_items) as el
    loop
        select mi.name, mi.price_idr
          into prod_name, prod_price
          from public.menu_items mi
         where mi.id = it_row.menu_item_id;

        v_display_item_name := case
            when it_row.variant_label <> '' then left(prod_name || ' (' || it_row.variant_label || ')', 140)
            else prod_name
        end;

        insert into public.order_items (order_id, menu_item_id, item_name, variant_label, unit_price_idr, quantity)
        values (v_order_id, it_row.menu_item_id, v_display_item_name, it_row.variant_label, prod_price, it_row.quantity);
    end loop;

    if v_stall_owner is not null and v_stall_owner <> uid then
        begin
            perform pg_advisory_xact_lock(hashtext('chat_pair:' || uid::text || ':' || v_stall_owner::text)::bigint);

            update public.conversations as conv
               set order_id = null,
                   conversation_type = 'product_inquiry'
             where conv.order_id = v_order_id
               and (conv.buyer_id <> uid or conv.seller_id <> v_stall_owner or conv.merged_into is not null);

            v_chat_ts := now();

            insert into public.conversations (order_id, buyer_id, seller_id, conversation_type, updated_at, last_message_at)
            values (v_order_id, uid, v_stall_owner, 'order', v_chat_ts + interval '1 millisecond', v_chat_ts + interval '1 millisecond')
            on conflict (buyer_id, seller_id) where merged_into is null
            do update set
                order_id = excluded.order_id,
                conversation_type = 'order',
                updated_at = v_chat_ts + interval '1 millisecond',
                last_message_at = v_chat_ts + interval '1 millisecond'
            returning id into v_convo_id;

            select coalesce(string_agg(oi.quantity::text || '× ' || oi.item_name, ', '), ''),
                   coalesce(sum(oi.quantity), 0)::integer
              into v_items_summary, v_total_qty
              from public.order_items oi
             where oi.order_id = v_order_id;

            select oi.item_name, coalesce(m.image_url, '')
              into v_first_title, v_first_img
              from public.order_items oi
              left join public.menu_items m on m.id = oi.menu_item_id
             where oi.order_id = v_order_id
             order by oi.id asc
             limit 1;

            if v_mode = 'pre_order' then
                v_sched_text := 'Pre-Order ' || v_pickup_date::text || ' · ' || v_session;
            else
                v_sched_text := 'Ambil Hari Ini · ' || v_session;
            end if;

            v_order_card_json := jsonb_build_object(
                'order_id', v_order_id::text,
                'pickup_code', new_pickup_code,
                'title', coalesce(nullif(v_first_title, ''), 'Pesanan #' || new_pickup_code),
                'items_summary', coalesce(v_items_summary, ''),
                'item_count', greatest(v_total_qty, 1),
                'total_idr', total,
                'status', 'pending',
                'schedule', v_sched_text,
                'order_time', to_char(wib_now, 'DD-MM-YYYY HH24:MI'),
                'image_url', coalesce(v_first_img, '')
            )::text;

            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, created_at)
            values (v_convo_id, uid, left(v_order_card_json, 1900), 'order_card', false, v_chat_ts);

            v_auto_msg := 'Terima kasih atas pembelian Anda di ' || v_stall_name
                || '. Nomor pesanan: #' || new_pickup_code
                || ' (' || coalesce(v_items_summary, 'Pesanan warung') || '). '
                || case when method = 'qris'
                    then 'Silakan selesaikan pembayaran melalui Midtrans agar pesanan segera kami siapkan sesuai jadwal (' || v_sched_text || ').'
                    else 'Kami akan menyiapkan pesanan Anda sesegera mungkin sesuai jadwal (' || v_sched_text || '). Silakan periksa kembali informasi pengambilan pesanan.'
                   end;

            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, created_at)
            values (v_convo_id, v_stall_owner, left(v_auto_msg, 500), 'auto_reply', false, v_chat_ts + interval '1 millisecond');
        exception when others then
            raise warning 'auto_chat on place_order_v3 failed for order %: %', v_order_id, sqlerrm;
        end;
    end if;

    return jsonb_build_object(
        'id', v_order_id,
        'pickup_code', new_pickup_code,
        'total_idr', total,
        'payment_method', method,
        'payment_status', 'unpaid',
        'status', 'pending',
        'order_mode', v_mode,
        'pickup_date', v_pickup_date::text,
        'pickup_session', v_session,
        'payment_expires_at', v_expires_at,
        'stock_deducted', v_any_stock_deducted
    );
end;
$function$;

-- 5. Aggregate stock restoration & deduction across multiple variant rows of the same menu_item_id
CREATE OR REPLACE FUNCTION public.expire_gateway_payment(
    p_gateway_order_id text,
    p_gateway_status text DEFAULT 'expired'::text,
    p_raw_notification jsonb DEFAULT NULL::jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_tx public.payment_transactions%rowtype;
    v_ord public.orders%rowtype;
    v_norm_status text := case lower(coalesce(p_gateway_status, 'expired'))
        when 'cancel' then 'cancelled'
        when 'cancelled' then 'cancelled'
        when 'deny' then 'failed'
        when 'failure' then 'failed'
        else 'expired'
    end;
    v_now timestamptz := now();
begin
    if not public.is_system_or_service_context() then
        raise exception 'Akses ditolak.';
    end if;

    select *
      into v_tx
      from public.payment_transactions
     where gateway_order_id = p_gateway_order_id
       for update;

    if not found then
        return jsonb_build_object('ok', false, 'error', 'transaction_not_found');
    end if;

    select *
      into v_ord
      from public.orders
     where id = v_tx.order_id
       for update;

    if not found then
        return jsonb_build_object('ok', false, 'error', 'order_not_found');
    end if;

    if v_ord.payment_status = 'paid' then
        return jsonb_build_object('ok', true, 'skipped', 'already_paid', 'order_id', v_ord.id);
    end if;

    perform set_config('app.gateway_webhook', 'on', true);

    update public.payment_transactions
       set status = v_norm_status,
           raw_notification = coalesce(p_raw_notification, raw_notification),
           updated_at = v_now
     where id = v_tx.id;

    if v_ord.status = 'pending' then
        if v_ord.stock_deducted then
            perform set_config('app.internal_stock_op', 'on', true);
            update public.menu_items mi
               set stock = mi.stock + agg.total_qty
              from (
                  select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                    from public.order_items oi
                   where oi.order_id = v_ord.id
                     and oi.menu_item_id is not null
                   group by oi.menu_item_id
              ) agg
             where mi.id = agg.menu_item_id
               and mi.stock is not null
               and (v_ord.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;

        update public.orders
           set status = 'cancelled',
               payment_reference = 'Pembayaran Midtrans ' || case v_norm_status when 'cancelled' then 'dibatalkan' when 'failed' then 'gagal' else 'kedaluwarsa' end,
               payment_expires_at = null,
               stock_deducted = false,
               updated_at = v_now
         where id = v_ord.id;
    end if;

    return jsonb_build_object(
        'ok', true,
        'order_id', v_ord.id,
        'status', v_norm_status
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.finalize_gateway_payment(
    p_gateway_order_id text,
    p_gateway_tx_id text DEFAULT NULL::text,
    p_raw_notification jsonb DEFAULT NULL::jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_tx public.payment_transactions%rowtype;
    v_ord public.orders%rowtype;
    v_stall_id uuid;
    v_stall_owner uuid;
    v_stall_name text;
    v_convo_id uuid;
    v_item record;
    v_did_deduct boolean := false;
    v_shortage_note text := '';
    v_fee integer := 0;
    v_net integer := 0;
    v_ref text;
    v_now timestamptz := now();
begin
    if not public.is_system_or_service_context() then
        raise exception 'Akses ditolak: hanya server payment gateway yang dapat menyelesaikan transaksi.';
    end if;

    select *
      into v_tx
      from public.payment_transactions
     where gateway_order_id = p_gateway_order_id
       for update;

    if not found then
        return jsonb_build_object('ok', false, 'error', 'transaction_not_found');
    end if;

    select *
      into v_ord
      from public.orders
     where id = v_tx.order_id
       for update;

    if not found then
        return jsonb_build_object('ok', false, 'error', 'order_not_found');
    end if;

    if v_ord.payment_status = 'paid' and v_tx.status = 'paid' then
        return jsonb_build_object(
            'ok', true,
            'already_paid', true,
            'order_id', v_ord.id,
            'payment_status', 'paid'
        );
    end if;

    perform set_config('app.gateway_webhook', 'on', true);
    perform set_config('app.internal_stock_op', 'on', true);

    v_did_deduct := coalesce(v_ord.stock_deducted, false);

    if not v_did_deduct then
        for v_item in
            select agg.menu_item_id, agg.total_qty as quantity, mi.name, mi.stock, mi.order_schedule
              from (
                  select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                    from public.order_items oi
                   where oi.order_id = v_ord.id
                     and oi.menu_item_id is not null
                   group by oi.menu_item_id
              ) agg
              join public.menu_items mi on mi.id = agg.menu_item_id
             order by mi.id
               for update of mi
        loop
            if v_item.stock is not null
               and (v_ord.order_mode = 'same_day' or v_item.order_schedule = 'next_day') then
                if v_item.stock >= v_item.quantity then
                    update public.menu_items
                       set stock = stock - v_item.quantity,
                           updated_at = v_now
                     where id = v_item.menu_item_id;
                    v_did_deduct := true;
                else
                    update public.menu_items
                       set stock = 0,
                           updated_at = v_now
                     where id = v_item.menu_item_id;
                    v_shortage_note := trim(both ' ' from v_shortage_note || ' [' || v_item.name || ': sisa ' || v_item.stock || ']');
                end if;
            end if;
        end loop;
    end if;

    v_fee := coalesce(v_tx.fee_idr, least(1000, v_ord.total_idr));
    v_net := greatest(0, v_ord.total_idr - v_fee);
    v_ref := 'Midtrans ' || upper(coalesce(v_tx.environment, 'sandbox')) || ' #' || coalesce(nullif(trim(p_gateway_tx_id), ''), p_gateway_order_id);
    if v_shortage_note <> '' then
        v_ref := left(v_ref || ' · Catatan stok:' || v_shortage_note, 220);
    end if;

    update public.orders
       set payment_status = 'paid',
           status = case when status = 'cancelled' then 'pending' else status end,
           paid_at = coalesce(paid_at, v_now),
           payment_reference = v_ref,
           payment_expires_at = null,
           stock_deducted = v_did_deduct,
           updated_at = v_now
     where id = v_ord.id;

    update public.payment_transactions
       set status = 'paid',
           gateway_transaction_id = coalesce(nullif(trim(p_gateway_tx_id), ''), gateway_transaction_id),
           paid_at = coalesce(paid_at, v_now),
           raw_notification = coalesce(p_raw_notification, raw_notification),
           updated_at = v_now
     where id = v_tx.id;

    select s.id, s.owner_id, coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
      into v_stall_id, v_stall_owner, v_stall_name
      from public.stalls s
     where s.id = v_ord.stall_id;

    if v_stall_id is not null and v_stall_owner is not null then
        insert into public.seller_ledger (
            stall_id,
            seller_id,
            order_id,
            gross_amount,
            fee_idr,
            net_amount,
            status
        )
        values (
            v_stall_id,
            v_stall_owner,
            v_ord.id,
            v_ord.total_idr,
            v_fee,
            v_net,
            'available'
        )
        on conflict (order_id) do nothing;

        select c.id
          into v_convo_id
          from public.conversations c
         where c.buyer_id = v_ord.user_id
           and c.seller_id = v_stall_owner
           and c.merged_into is null
         limit 1;

        if v_convo_id is not null and not exists (
            select 1
              from public.chat_messages m
             where m.conversation_id = v_convo_id
               and m.message_type = 'auto_reply'
               and m.body like '%#' || v_ord.pickup_code || '%lunas otomatis%'
        ) then
            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, created_at)
            values (
                v_convo_id,
                v_stall_owner,
                'Pembayaran Midtrans untuk pesanan #' || v_ord.pickup_code || ' telah lunas otomatis. Pesananmu sedang menunggu konfirmasi penjual di ' || v_stall_name || '.',
                'auto_reply',
                false,
                v_now
            );
            update public.conversations
               set last_message_at = v_now,
                   updated_at = v_now
             where id = v_convo_id;
        end if;
    end if;

    return jsonb_build_object(
        'ok', true,
        'already_paid', false,
        'order_id', v_ord.id,
        'payment_status', 'paid',
        'net_seller_amount', v_net
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.guard_order_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    wib_date date := (now() at time zone 'Asia/Jakarta')::date;
    order_seller boolean;
    v_item record;
    v_did_deduct boolean := false;
begin
    if public.is_system_or_service_context() then
        if TG_OP = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    if uid is null then
        raise exception 'Sesi berakhir. Silakan masuk ulang.';
    end if;

    if coalesce(current_setting('app.qris_expiry_sweep', true), '') = 'on'
       and TG_OP = 'UPDATE'
       and old.status = 'pending'
       and lower(coalesce(old.payment_method, '')) = 'qris'
       and old.payment_status in ('unpaid', 'rejected')
       and old.payment_expires_at is not null
       and old.payment_expires_at <= now()
       and new.status = 'cancelled'
       and new.user_id is not distinct from old.user_id
       and new.total_idr is not distinct from old.total_idr then
        if old.stock_deducted then
            perform set_config('app.internal_stock_op', 'on', true);
            update public.menu_items mi
               set stock = mi.stock + agg.total_qty
              from (
                  select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                    from public.order_items oi
                   where oi.order_id = new.id
                     and oi.menu_item_id is not null
                   group by oi.menu_item_id
              ) agg
             where mi.id = agg.menu_item_id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
        return new;
    end if;

    if coalesce(current_setting('app.daily_reset_active', true), '') = 'on'
       and public.is_developer()
       and TG_OP = 'UPDATE'
       and old.status in ('pending', 'confirmed', 'preparing', 'processing', 'ready')
       and new.status = 'completed'
       and new.user_id is not distinct from old.user_id
       and new.total_idr is not distinct from old.total_idr then
        return new;
    end if;

    if TG_OP = 'INSERT' then
        new.payment_status := 'unpaid';
        new.paid_at := null;
        new.verified_by := null;
        new.payment_reference := null;
        new.payment_proof_url := null;
        if new.payment_method is not null
           and lower(new.payment_method) not in ('cash', 'qris') then
            raise exception 'Metode bayar tidak valid.';
        end if;
        if lower(coalesce(new.payment_method, '')) = 'qris' then
            new.stock_deducted := false;
            new.payment_expires_at := coalesce(new.payment_expires_at, now() + interval '15 minutes');
        else
            new.payment_expires_at := null;
        end if;
        return new;
    end if;

    order_seller := public.is_seller() and public.is_order_for_seller(new.id);

    if order_seller and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    -- BUYER MUTATIONS
    if not order_seller then
        if old.user_id = uid and new.status = 'cancelled' then
            if old.order_mode = 'pre_order' then
                raise exception 'Pesanan Pre-Order tidak dapat dibatalkan oleh pembeli. Hubungi penjual lewat chat jika ada kendala.';
            end if;
            if old.payment_status in ('waiting_verification', 'paid') then
                raise exception 'Pesanan yang sudah dibayar tidak dapat dibatalkan.';
            end if;
        end if;

        if old.user_id = uid
           and old.order_mode <> 'pre_order'
           and old.status = 'pending'
           and new.status = 'cancelled'
           and new.total_idr is not distinct from old.total_idr
           and new.payment_method is not distinct from old.payment_method
           and new.user_id is not distinct from old.user_id
           and new.pickup_code is not distinct from old.pickup_code
           and new.paid_at is not distinct from old.paid_at
           and new.verified_by is not distinct from old.verified_by
           and new.stock_deducted is not distinct from old.stock_deducted then
            if old.stock_deducted then
                perform set_config('app.internal_stock_op', 'on', true);
                update public.menu_items mi
                   set stock = mi.stock + agg.total_qty
                  from (
                      select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                        from public.order_items oi
                       where oi.order_id = new.id
                         and oi.menu_item_id is not null
                       group by oi.menu_item_id
                  ) agg
                 where mi.id = agg.menu_item_id
                   and mi.stock is not null
                   and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
            end if;
            new.stock_deducted := false;
            new.payment_expires_at := null;
            return new;
        end if;

        if new.status is distinct from old.status
            or new.total_idr is distinct from old.total_idr
            or new.payment_method is distinct from old.payment_method
            or new.payment_status is distinct from old.payment_status
            or new.user_id is distinct from old.user_id
            or new.pickup_code is distinct from old.pickup_code
            or new.payment_reference is distinct from old.payment_reference
            or new.paid_at is distinct from old.paid_at
            or new.verified_by is distinct from old.verified_by
            or new.stock_deducted is distinct from old.stock_deducted
            or new.payment_expires_at is distinct from old.payment_expires_at then
            raise exception 'Pembayaran Midtrans diverifikasi otomatis oleh sistem.';
        end if;
        return new;
    end if;

    -- SELLER MUTATIONS
    if new.status = 'cancelled' and old.status is distinct from 'cancelled' then
        if old.payment_status = 'paid' then
            raise exception 'Pesanan yang sudah lunas tidak dapat dibatalkan oleh penjual.';
        end if;
        if old.payment_status = 'waiting_verification' then
            raise exception 'Pesanan menunggu konfirmasi pembayaran dan tidak dapat dibatalkan.';
        end if;
    end if;

    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        if lower(coalesce(old.payment_method, '')) = 'qris' then
            raise exception 'Pembayaran Midtrans diverifikasi otomatis oleh sistem setelah pembeli membayar.';
        end if;
        if old.status = 'cancelled' or new.status = 'cancelled' then
            raise exception 'Pesanan yang dibatalkan tidak bisa ditandai lunas.';
        end if;

        if not old.stock_deducted then
            perform set_config('app.internal_stock_op', 'on', true);
            for v_item in
                select agg.menu_item_id, agg.total_qty as quantity, mi.name, mi.stock, mi.order_schedule
                  from (
                      select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                        from public.order_items oi
                       where oi.order_id = new.id
                         and oi.menu_item_id is not null
                       group by oi.menu_item_id
                  ) agg
                  join public.menu_items mi on mi.id = agg.menu_item_id
                 order by mi.id
                   for update of mi
            loop
                if v_item.stock is not null
                   and (old.order_mode = 'same_day' or v_item.order_schedule = 'next_day') then
                    if v_item.stock < v_item.quantity then
                        raise exception 'Stok % tidak mencukupi (tersisa %). Perbarui stok menu atau batalkan pesanan ini.',
                            v_item.name, v_item.stock;
                    end if;
                    update public.menu_items
                       set stock = stock - v_item.quantity,
                           updated_at = now()
                     where id = v_item.menu_item_id;
                    v_did_deduct := true;
                end if;
            end loop;
            new.stock_deducted := v_did_deduct;
        end if;

        new.paid_at := now();
        new.verified_by := uid;
        new.payment_expires_at := null;
    end if;

    if new.status is distinct from old.status then
        if lower(coalesce(old.payment_method, '')) = 'qris'
           and new.status in ('confirmed', 'preparing', 'processing', 'ready', 'completed')
           and coalesce(new.payment_status, old.payment_status) <> 'paid' then
            raise exception 'Menunggu pembayaran Midtrans lunas otomatis sebelum memproses pesanan.';
        end if;

        if old.order_mode = 'pre_order'
           and new.status in ('preparing', 'processing', 'ready', 'completed')
           and old.pickup_date is not null
           and wib_date < old.pickup_date then
            raise exception 'Pesanan PO untuk % baru bisa diproses/disiapkan pada hari pengambilan.', old.pickup_date::text;
        end if;
    end if;

    if new.status = 'cancelled' and old.status is distinct from 'cancelled' then
        if old.stock_deducted
           and coalesce(current_setting('app.skip_stock_restore', true), '') <> 'on' then
            perform set_config('app.internal_stock_op', 'on', true);
            update public.menu_items mi
               set stock = mi.stock + agg.total_qty
              from (
                  select oi.menu_item_id, sum(oi.quantity)::integer as total_qty
                    from public.order_items oi
                   where oi.order_id = new.id
                     and oi.menu_item_id is not null
                   group by oi.menu_item_id
              ) agg
             where mi.id = agg.menu_item_id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
    end if;

    return new;
end;
$function$;
