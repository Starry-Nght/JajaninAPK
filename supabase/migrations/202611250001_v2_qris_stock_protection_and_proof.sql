-- Jajanin: QRIS Stock Protection, Mandatory Payment Proof Photo,
-- 10-Minute Auto-Expire, Seller QRIS Verification Lock & Smart Out-of-Stock Cancel

-- 1. Storage bucket for QRIS payment proof screenshots
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'payment-proofs',
    'payment-proofs',
    true,
    5242880,
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Public read payment proofs" on storage.objects;
create policy "Public read payment proofs"
    on storage.objects for select
    using (bucket_id = 'payment-proofs');

drop policy if exists "Buyers upload own payment proofs" on storage.objects;
create policy "Buyers upload own payment proofs"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'payment-proofs'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

drop policy if exists "Buyers update own payment proofs" on storage.objects;
create policy "Buyers update own payment proofs"
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'payment-proofs'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    )
    with check (
        bucket_id = 'payment-proofs'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

drop policy if exists "Buyers delete own payment proofs" on storage.objects;
create policy "Buyers delete own payment proofs"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'payment-proofs'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

-- 2. Add QRIS proof, payment expiry, and stock deduction tracking to public.orders
alter table public.orders
    add column if not exists payment_proof_url text,
    add column if not exists payment_expires_at timestamptz,
    add column if not exists stock_deducted boolean not null default true;

grant update (payment_proof_url) on public.orders to authenticated;

update public.orders
   set stock_deducted = false
 where status = 'cancelled'
   and stock_deducted = true;

create index if not exists idx_orders_qris_expiry
    on public.orders (payment_expires_at)
    where status = 'pending'
      and lower(coalesce(payment_method, '')) = 'qris'
      and payment_status in ('unpaid', 'rejected')
      and payment_expires_at is not null;

create index if not exists idx_orders_buyer_unpaid_qris
    on public.orders (user_id)
    where status = 'pending'
      and lower(coalesce(payment_method, '')) = 'qris'
      and payment_status in ('unpaid', 'rejected', 'waiting_verification');

-- 3. Auto-expire unpaid/rejected QRIS orders after 10 minutes
create or replace function public.expire_unpaid_qris_orders()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_count integer := 0;
begin
    if auth.uid() is null and not public.is_system_or_service_context() then
        return 0;
    end if;

    perform set_config('app.qris_expiry_sweep', 'on', true);

    update public.orders o
       set status = 'cancelled',
           payment_reference = 'Otomatis dibatalkan (batas bayar QRIS 10 menit habis)',
           payment_expires_at = null,
           updated_at = now()
     where o.status = 'pending'
       and lower(coalesce(o.payment_method, '')) = 'qris'
       and o.payment_status in ('unpaid', 'rejected')
       and o.payment_expires_at is not null
       and o.payment_expires_at <= now();

    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

revoke all on function public.expire_unpaid_qris_orders() from public, anon;
grant execute on function public.expire_unpaid_qris_orders() to authenticated;

-- 4. Update guard_order_mutation
create or replace function public.guard_order_mutation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    wib_date date := (now() at time zone 'Asia/Jakarta')::date;
    order_seller boolean;
    v_item record;
    v_did_deduct boolean := false;
    v_proof_prefix constant text := 'https://yezxyhpczfnkcndfpuht.supabase.co/storage/v1/object/public/payment-proofs/';
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

    -- Internal sweep from expire_unpaid_qris_orders()
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
            update public.menu_items mi
               set stock = mi.stock + oi.quantity
              from public.order_items oi
             where oi.order_id = new.id
               and oi.menu_item_id = mi.id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
        return new;
    end if;

    -- Internal daily reset from developer
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
            new.payment_expires_at := coalesce(new.payment_expires_at, now() + interval '10 minutes');
        else
            new.payment_expires_at := null;
        end if;
        return new;
    end if;

    order_seller := public.is_seller() and public.is_order_for_seller(new.id);

    if order_seller and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    -- ================= BUYER MUTATIONS =================
    if not order_seller then
        if old.user_id = uid and old.order_mode = 'pre_order' and new.status = 'cancelled' then
            raise exception 'Pesanan Pre-Order tidak dapat dibatalkan oleh pembeli. Hubungi penjual lewat chat jika ada kendala.';
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
                update public.menu_items mi
                   set stock = mi.stock + oi.quantity
                  from public.order_items oi
                 where oi.order_id = new.id
                   and oi.menu_item_id = mi.id
                   and mi.stock is not null
                   and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
            end if;
            new.stock_deducted := false;
            new.payment_expires_at := null;
            return new;
        end if;

        if old.user_id = uid and (
            new.payment_status is distinct from old.payment_status
            or new.payment_proof_url is distinct from old.payment_proof_url
        ) then
            if lower(coalesce(old.payment_method, '')) <> 'qris' then
                raise exception 'Hanya pesanan QRIS yang bisa mengunggah bukti bayar.';
            end if;
            if old.status <> 'pending' then
                raise exception 'Pesanan ini tidak lagi menunggu pembayaran.';
            end if;
            if not (old.payment_status in ('unpaid', 'rejected')
                    and new.payment_status = 'waiting_verification') then
                raise exception 'Perubahan status pembayaran tidak diizinkan.';
            end if;
            if old.payment_expires_at is not null and now() > old.payment_expires_at then
                raise exception 'Batas waktu bayar QRIS (10 menit) telah habis. Silakan buat pesanan baru.';
            end if;
            if new.payment_proof_url is null
               or trim(new.payment_proof_url) = ''
               or left(trim(new.payment_proof_url), char_length(v_proof_prefix)) <> v_proof_prefix then
                raise exception 'Wajib mengunggah foto bukti transfer QRIS terlebih dahulu.';
            end if;
            new.payment_proof_url := trim(new.payment_proof_url);
            new.payment_expires_at := null;
        end if;

        if new.status is distinct from old.status
            or new.total_idr is distinct from old.total_idr
            or new.payment_method is distinct from old.payment_method
            or new.user_id is distinct from old.user_id
            or new.pickup_code is distinct from old.pickup_code
            or new.payment_reference is distinct from old.payment_reference
            or new.paid_at is distinct from old.paid_at
            or new.verified_by is distinct from old.verified_by
            or new.stock_deducted is distinct from old.stock_deducted
            or (new.payment_expires_at is distinct from old.payment_expires_at
                and not (old.payment_status in ('unpaid', 'rejected') and new.payment_status = 'waiting_verification')) then
            raise exception 'Hanya penjual yang boleh mengubah order.';
        end if;
        return new;
    end if;

    -- ================= SELLER MUTATIONS =================
    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        if old.status = 'cancelled' or new.status = 'cancelled' then
            raise exception 'Pesanan yang dibatalkan tidak bisa ditandai lunas.';
        end if;

        if not old.stock_deducted then
            for v_item in
                select oi.menu_item_id, oi.quantity, mi.name, mi.stock, mi.order_schedule
                  from public.order_items oi
                  join public.menu_items mi on mi.id = oi.menu_item_id
                 where oi.order_id = new.id
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

    if new.payment_status = 'rejected' and old.payment_status is distinct from 'rejected' then
        new.payment_proof_url := null;
        new.payment_expires_at := now() + interval '10 minutes';
    end if;

    if new.status is distinct from old.status then
        if lower(coalesce(old.payment_method, '')) = 'qris'
           and new.status in ('confirmed', 'preparing', 'processing', 'ready', 'completed')
           and coalesce(new.payment_status, old.payment_status) <> 'paid' then
            raise exception 'Verifikasi bukti bayar QRIS dulu sebelum menerima atau memproses pesanan.';
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
            update public.menu_items mi
               set stock = mi.stock + oi.quantity
              from public.order_items oi
             where oi.order_id = new.id
               and oi.menu_item_id = mi.id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
    end if;

    return new;
end;
$$;

-- 5. Update place_order_v3
create or replace function public.place_order_v3(
    p_items jsonb,
    p_payment_method text,
    p_order_mode text default 'same_day'::text,
    p_pickup_session text default 'Istirahat 1'::text,
    p_pickup_date text default null::text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    method text := lower(trim(coalesce(p_payment_method, '')));
    v_mode text := lower(trim(coalesce(p_order_mode, 'same_day')));
    v_session text := trim(coalesce(p_pickup_session, 'Istirahat 1'));
    v_raw_date text := trim(coalesce(p_pickup_date, ''));
    order_id uuid := gen_random_uuid();
    total integer := 1000;
    it jsonb;
    it_row record;
    pid uuid;
    qty integer;
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
    v_allowed_dates text[];
    v_stall_id uuid := null;
    v_stall_owner uuid := null;
    v_stall_qris text := null;
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
    v_waiting_qris_count integer;
    v_expires_at timestamptz := null;
    v_any_stock_deducted boolean := false;
    stall_order_num integer;
    new_pickup_code text;
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
        if v_unpaid_qris_count >= 1 then
            raise exception 'Kamu masih punya pesanan QRIS yang belum dibayar. Unggah bukti bayar atau batalkan dulu sebelum memesan lagi.';
        end if;

        select count(*)::integer
          into v_waiting_qris_count
          from public.orders o
         where o.user_id = uid
           and o.status = 'pending'
           and lower(coalesce(o.payment_method, '')) = 'qris'
           and o.payment_status = 'waiting_verification';
        if v_waiting_qris_count >= 2 then
            raise exception 'Kamu masih punya 2 pesanan QRIS menunggu verifikasi penjual. Tunggu diverifikasi dulu.';
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
            v_requires_qris_reason := 'Pre-Order lebih dari 3 hari wajib pembayaran QRIS di muka.';
        end if;
    end if;

    if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        raise exception 'Keranjang masih kosong.';
    end if;
    if jsonb_array_length(p_items) > 30 then
        raise exception 'Maksimal 30 jenis menu dalam satu pesanan.';
    end if;

    for it in select * from jsonb_array_elements(p_items) loop
        begin
            pid := (it ->> 'menu_item_id')::uuid;
            qty := (it ->> 'quantity')::integer;
        exception when others then
            raise exception 'Format item tidak valid.';
        end;
        if qty is null or qty < 1 or qty > 99 then
            raise exception 'Jumlah per menu harus antara 1 sampai 99.';
        end if;
        select mi.stall_id, mi.name, mi.price_idr, mi.is_available, mi.stock,
               mi.order_schedule, coalesce(mi.po_dates, ''),
               mi.po_cutoff_hour, mi.po_daily_quota, mi.po_require_qris, mi.po_allow_weekend
          into prod_stall_id, prod_name, prod_price, prod_available, prod_stock,
               prod_schedule, prod_po_dates,
               prod_cutoff_hour, prod_daily_quota, prod_require_qris, prod_allow_weekend
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

            if prod_daily_quota is not null then
                perform pg_advisory_xact_lock(hashtext('po_quota:' || pid::text || ':' || v_pickup_date::text)::bigint);
                select coalesce(sum(oi.quantity), 0)::integer
                  into v_used_quota
                  from public.order_items oi
                  join public.orders o on o.id = oi.order_id
                 where oi.menu_item_id = pid
                   and o.order_mode = 'pre_order'
                   and o.pickup_date = v_pickup_date
                   and o.status <> 'cancelled';
                if (v_used_quota + qty) > prod_daily_quota then
                    raise exception 'Kuota PO % pada tanggal % tidak cukup (tersisa % porsi).',
                        prod_name, v_pickup_date::text, greatest(0, prod_daily_quota - v_used_quota);
                end if;
            end if;

            if prod_require_qris then
                v_requires_qris := true;
                if v_requires_qris_reason = '' then
                    v_requires_qris_reason := 'Menu PO ' || prod_name || ' mewajibkan pembayaran QRIS di muka.';
                end if;
            end if;
        end if;

        if prod_stock is not null and (v_mode = 'same_day' or prod_schedule = 'next_day') then
            if prod_stock < qty then
                raise exception 'Stok % tidak mencukupi (tersisa %).', prod_name, prod_stock;
            end if;
            if method = 'cash' then
                v_any_stock_deducted := true;
            end if;
        end if;

        total := total + prod_price * qty;
    end loop;

    select s.owner_id, nullif(trim(coalesce(s.qris_image_url, '')), ''), coalesce(s.is_open, true)
      into v_stall_owner, v_stall_qris, v_stall_open
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

    if v_mode = 'pre_order' and v_requires_qris then
        if v_stall_qris is null then
            raise exception 'Pre-Order ini wajib pembayaran QRIS, tetapi warung belum memasang QRIS. Pilih tanggal 1-3 hari atau hubungi penjual.';
        end if;
        if method <> 'qris' then
            raise exception '%', v_requires_qris_reason;
        end if;
    end if;

    if method = 'qris' then
        if v_stall_qris is null then
            raise exception 'Warung ini belum memasang kode QRIS. Silakan pilih bayar Tunai.';
        end if;
        v_expires_at := now() + interval '10 minutes';
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
        order_id, uid, v_stall_id, 'pending', total, method, 'unpaid',
        new_pickup_code, v_mode, v_pickup_date, v_session,
        v_expires_at, v_any_stock_deducted
    );

    for it_row in
        select (el ->> 'menu_item_id')::uuid as menu_item_id,
               (el ->> 'quantity')::integer as quantity
          from jsonb_array_elements(p_items) as el
         order by (el ->> 'menu_item_id')::uuid
    loop
        pid := it_row.menu_item_id;
        qty := it_row.quantity;
        select mi.name, mi.price_idr, mi.stock, mi.order_schedule
          into prod_name, prod_price, prod_stock, prod_schedule
          from public.menu_items mi
         where mi.id = pid
           for update;
        insert into public.order_items (order_id, menu_item_id, item_name, unit_price_idr, quantity)
        values (order_id, pid, prod_name, prod_price, qty);

        if method = 'cash'
           and prod_stock is not null
           and (v_mode = 'same_day' or prod_schedule = 'next_day') then
            update public.menu_items
               set stock = stock - qty,
                   updated_at = now()
             where id = pid and stock >= qty;
            if not found then
                raise exception 'Stok % berubah saat memesan, coba lagi.', prod_name;
            end if;
        end if;
    end loop;

    return jsonb_build_object(
        'id', order_id,
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
$$;

-- 6. Seller atomic cancel RPC with explicit stock restoration vs mark-out-of-stock control
create or replace function public.cancel_seller_order_v2(
    p_order_id uuid,
    p_reason text,
    p_restore_stock boolean default true,
    p_mark_out_of_stock boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    v_reason text := trim(coalesce(p_reason, ''));
    v_order record;
    v_restore boolean := coalesce(p_restore_stock, true) and not coalesce(p_mark_out_of_stock, false);
begin
    if uid is null then
        raise exception 'Sesi berakhir. Silakan masuk ulang.';
    end if;
    if v_reason = '' then
        raise exception 'Alasan pembatalan wajib diisi.';
    end if;
    if not public.is_seller() or not public.is_order_for_seller(p_order_id) then
        raise exception 'Gagal membatalkan pesanan. Pastikan ini pesanan warungmu.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    select id, status, stall_id, stock_deducted
      into v_order
      from public.orders
     where id = p_order_id
       for update;
    if not found then
        raise exception 'Pesanan tidak ditemukan.';
    end if;
    if v_order.status in ('completed', 'cancelled') then
        raise exception 'Pesanan ini sudah selesai atau dibatalkan.';
    end if;

    if not v_restore then
        perform set_config('app.skip_stock_restore', 'on', true);
    end if;

    update public.orders
       set status = 'cancelled',
           payment_reference = v_reason,
           updated_at = now()
     where id = p_order_id;

    if coalesce(p_mark_out_of_stock, false) then
        update public.menu_items mi
           set stock = 0,
               updated_at = now()
          from public.order_items oi
         where oi.order_id = p_order_id
           and oi.menu_item_id = mi.id
           and mi.stock is not null
           and (v_order.stall_id is null or mi.stall_id = v_order.stall_id);
    end if;

    return jsonb_build_object(
        'ok', true,
        'order_id', p_order_id,
        'stock_restored', (v_order.stock_deducted and v_restore),
        'marked_out_of_stock', coalesce(p_mark_out_of_stock, false)
    );
end;
$$;

revoke all on function public.cancel_seller_order_v2(uuid, text, boolean, boolean) from public, anon;
grant execute on function public.cancel_seller_order_v2(uuid, text, boolean, boolean) to authenticated;

-- 7. Keep stall_availability & po_slot_availability clean wrt expired QRIS orders
create or replace function public.stall_availability(p_stall_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    cnt integer := 0;
begin
    if p_stall_id is null then
        raise exception 'Warung tidak valid.';
    end if;
    perform public.expire_unpaid_qris_orders();
    select count(*) into cnt
      from public.orders o
     where o.status in ('pending', 'confirmed', 'preparing', 'processing')
       and (
            o.stall_id = p_stall_id
            or (o.stall_id is null and exists (
                select 1
                  from public.order_items oi
                  join public.menu_items mi on mi.id = oi.menu_item_id
                 where oi.order_id = o.id
                   and mi.stall_id = p_stall_id
            ))
       );
    return jsonb_build_object(
        'stall_id', p_stall_id,
        'active_orders', cnt,
        'capacity', 10,
        'is_full', cnt >= 10
    );
end;
$$;

create or replace function public.po_slot_availability(p_menu_item_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    mi record;
    wib_now timestamp := now() at time zone 'Asia/Jakarta';
    wib_date date := (now() at time zone 'Asia/Jakarta')::date;
    v_dates date[];
    v_d date;
    v_used integer;
    v_remaining integer;
    v_cutoff timestamp;
    v_closed boolean;
    v_reason text;
    v_result jsonb := '{}'::jsonb;
begin
    if auth.uid() is null then
        raise exception 'Masuk dulu untuk melihat ketersediaan PO.';
    end if;

    select id, order_schedule, coalesce(po_dates, '') as po_dates,
           po_cutoff_hour, po_daily_quota, po_require_qris, po_allow_weekend
      into mi
      from public.menu_items
     where id = p_menu_item_id;

    if not found then
        return jsonb_build_object('dates', '{}'::jsonb);
    end if;

    if trim(mi.po_dates) <> '' then
        select array_agg(distinct (trim(d))::date order by (trim(d))::date)
          into v_dates
          from unnest(string_to_array(mi.po_dates, ',')) as d
         where trim(d) ~ '^\d{4}-\d{2}-\d{2}$'
           and (trim(d))::date between (wib_date + 1) and (wib_date + 30);
    else
        select array_agg((wib_date + gs)::date order by gs)
          into v_dates
          from generate_series(1, 14) as gs;
    end if;

    if v_dates is not null then
        foreach v_d in array v_dates loop
            v_cutoff := (v_d - 1)::timestamp + make_interval(hours => mi.po_cutoff_hour);
            v_closed := false;
            v_reason := null;

            if not mi.po_allow_weekend and extract(isodow from v_d) in (6, 7) then
                v_closed := true;
                v_reason := 'Hari libur (Sabtu/Minggu)';
            elsif wib_now >= v_cutoff then
                v_closed := true;
                v_reason := 'Lewat batas pesan H-1 ' || lpad(mi.po_cutoff_hour::text, 2, '0') || ':00 WIB';
            end if;

            select coalesce(sum(oi.quantity), 0)::integer
              into v_used
              from public.order_items oi
              join public.orders o on o.id = oi.order_id
             where oi.menu_item_id = mi.id
               and o.order_mode = 'pre_order'
               and o.pickup_date = v_d
               and o.status <> 'cancelled'
               and not (
                   lower(coalesce(o.payment_method, '')) = 'qris'
                   and o.payment_status in ('unpaid', 'rejected')
                   and o.payment_expires_at is not null
                   and o.payment_expires_at <= now()
               );

            if mi.po_daily_quota is not null then
                v_remaining := greatest(0, mi.po_daily_quota - v_used);
                if v_remaining <= 0 and not v_closed then
                    v_closed := true;
                    v_reason := 'Kuota penuh';
                end if;
            else
                v_remaining := null;
            end if;

            v_result := v_result || jsonb_build_object(
                v_d::text,
                jsonb_build_object(
                    'used', v_used,
                    'quota', mi.po_daily_quota,
                    'remaining', v_remaining,
                    'closed', v_closed,
                    'close_reason', v_reason
                )
            );
        end loop;
    end if;

    return jsonb_build_object(
        'cutoff_hour', mi.po_cutoff_hour,
        'daily_quota', mi.po_daily_quota,
        'require_qris', mi.po_require_qris,
        'allow_weekend', mi.po_allow_weekend,
        'dates', v_result
    );
end;
$$;
