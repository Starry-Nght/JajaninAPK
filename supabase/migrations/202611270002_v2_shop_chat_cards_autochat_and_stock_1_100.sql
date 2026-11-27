-- ============================================================================
-- JAJANIN v2.20 — Store Chat, Order/Product Chat Cards, Auto-Chat & Stock 1-100
-- Migration: 202611270002_v2_shop_chat_cards_autochat_and_stock_1_100.sql
-- ============================================================================

-- 1. Allow structured card & auto-reply message types in public.chat_messages
--    while keeping standard user text messages strictly 1..500 characters.
ALTER TABLE public.chat_messages
    DROP CONSTRAINT IF EXISTS chat_messages_body_check;

ALTER TABLE public.chat_messages
    ADD CONSTRAINT chat_messages_body_check
    CHECK (
        char_length(body) >= 1
        AND (
            (message_type = 'text' AND char_length(body) <= 500)
            OR (message_type <> 'text' AND char_length(body) <= 2000)
        )
    );

ALTER TABLE public.chat_messages
    DROP CONSTRAINT IF EXISTS chat_messages_type_check;

ALTER TABLE public.chat_messages
    ADD CONSTRAINT chat_messages_type_check
    CHECK (message_type IN ('text', 'auto_reply', 'order_card', 'product_card'));

-- 2. Skip raw JSON card rows in push-notification trigger; text & auto_reply still push.
CREATE OR REPLACE FUNCTION public.notify_chat_message()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    target_secret text;
    target_url text;
    net_schema text;
    request_id bigint;
begin
    if new.message_type in ('order_card', 'product_card') then
        return new;
    end if;

    begin
        select secret into target_secret
        from vault.decrypted_secrets
        where name = 'notify_secret'
        order by created_at desc
        limit 1;

        if target_secret is null or target_secret = '' then
            return new;
        end if;

        select n.nspname into net_schema
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where p.proname = 'http_post'
        limit 1;

        if net_schema is null then
            return new;
        end if;

        target_url := nullif(current_setting('app.notify_chat_url', true), '');
        if target_url is null then
            target_url := 'https://yezxyhpczfnkcndfpuht.supabase.co/functions/v1/notify-chat';
        end if;

        execute format(
            'select %I.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 10000)',
            net_schema
        )
        into request_id
        using
            target_url,
            jsonb_build_object('message_id', new.id),
            jsonb_build_object(
                'x-notify-secret', target_secret,
                'Content-Type', 'application/json'
            );
    exception when others then
        raise warning 'notify_chat_message failed for message %: %', new.id, sqlerrm;
    end;

    return new;
end;
$function$;

-- 3. Enforce mandatory Seller stock (1..100) before selling/creating/activating products,
--    while allowing internal order stock deductions & out-of-stock cancellations to reach 0.
CREATE OR REPLACE FUNCTION public.guard_menu_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_internal_stock_op boolean := (
        public.is_system_or_service_context()
        or pg_trigger_depth() > 1
        or coalesce(current_setting('app.internal_stock_op', true), '') = 'on'
        or coalesce(current_setting('app.skip_stock_restore', true), '') = 'on'
    );
begin
    if public.is_system_or_service_context() then
        if TG_OP = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    if public.is_developer() then
        raise exception 'Developer tidak mengelola produk.';
    end if;

    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    if TG_OP = 'DELETE' then
        return old;
    end if;

    if not v_internal_stock_op then
        if TG_OP = 'INSERT' then
            if new.stock is null or new.stock < 1 or new.stock > 100 then
                raise exception 'Stok wajib diisi antara 1 sampai 100 sebelum menjual produk.';
            end if;
        elsif TG_OP = 'UPDATE' then
            -- Izinkan penjual mematikan etalase (is_available = false) pada produk yang stoknya habis (0),
            -- tetapi setiap pembuatan/pengubahan untuk dijual wajib stok 1..100.
            if not (
                new.is_available = false
                and old.is_available = true
                and new.stock is not distinct from old.stock
            ) then
                if new.stock is null or new.stock < 1 or new.stock > 100 then
                    raise exception 'Stok wajib diisi antara 1 sampai 100 sebelum menjual produk.';
                end if;
            end if;
        end if;
    end if;

    return new;
end;
$function$;

-- 4. Enrich format_conversation_json with order product thumbnail, item metadata,
--    stall_id, clean previews for structured cards, and clean empty-store conversations.
CREATE OR REPLACE FUNCTION public.format_conversation_json(p_convo_id uuid, p_viewer_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    c public.conversations%rowtype;
    v_counterparty_id uuid;
    v_counterparty_name text;
    v_counterparty_role text;
    v_stall_id uuid;
    v_stall_name text;
    v_unread integer := 0;
    v_last_row record;
    v_last_preview text;
    v_last_msg jsonb := null;
    v_product_ctx jsonb := null;
    v_order_ctx jsonb := null;
    mi record;
    ord record;
    v_items_summary text;
    v_first_item_name text;
    v_first_product_id uuid;
    v_first_image_url text;
    v_item_count integer := 0;
    v_sched_label text := '';
    v_card_json jsonb;
begin
    select * into c from public.conversations where id = p_convo_id;
    if c.id is null then
        return null;
    end if;

    if p_viewer_id = c.buyer_id then
        v_counterparty_id := c.seller_id;
    else
        v_counterparty_id := c.buyer_id;
    end if;

    select coalesce(nullif(trim(p.display_name), ''), split_part(coalesce(p.email, ''), '@', 1), 'Pengguna'),
           coalesce(p.role, 'buyer')
      into v_counterparty_name, v_counterparty_role
      from public.profiles p
     where p.id = v_counterparty_id;

    select s.id, s.name
      into v_stall_id, v_stall_name
      from public.stalls s
     where s.owner_id = c.seller_id
     order by s.created_at asc
     limit 1;

    select count(*)::integer into v_unread
      from public.chat_messages m
     where m.conversation_id = c.id
       and m.sender_id <> p_viewer_id
       and m.is_read = false;

    select m.id, m.sender_id, m.body, m.message_type, m.is_read, m.read_at, m.created_at
      into v_last_row
      from public.chat_messages m
     where m.conversation_id = c.id
     order by m.created_at desc, m.id desc
     limit 1;

    if v_last_row.id is not null then
        v_last_preview := v_last_row.body;
        if v_last_row.message_type in ('order_card', 'product_card') then
            begin
                v_card_json := v_last_row.body::jsonb;
                if v_last_row.message_type = 'order_card' then
                    v_last_preview := '[Pesanan #' || coalesce(v_card_json ->> 'pickup_code', '') || '] '
                        || coalesce(nullif(v_card_json ->> 'items_summary', ''), v_card_json ->> 'title', 'Rincian Pesanan');
                else
                    if coalesce(v_card_json ->> 'pickup_code', '') <> '' then
                        v_last_preview := '[Pesanan #' || (v_card_json ->> 'pickup_code') || '] '
                            || coalesce(v_card_json ->> 'title', 'Produk');
                    else
                        v_last_preview := '[Tanya Produk] ' || coalesce(v_card_json ->> 'title', 'Menu Warung');
                    end if;
                end if;
            exception when others then
                v_last_preview := 'Rincian pesanan / produk';
            end;
        end if;

        v_last_msg := jsonb_build_object(
            'id', v_last_row.id,
            'sender_id', v_last_row.sender_id,
            'body', v_last_preview,
            'message_type', v_last_row.message_type,
            'is_read', v_last_row.is_read,
            'read_at', v_last_row.read_at,
            'created_at', v_last_row.created_at
        );
    end if;

    if c.product_id is not null or c.product_snapshot_name is not null then
        select m.id, m.name, m.price_idr, m.image_url, m.is_available
          into mi
          from public.menu_items m
         where m.id = c.product_id;

        v_product_ctx := jsonb_build_object(
            'id', c.product_id,
            'name', coalesce(mi.name, c.product_snapshot_name, 'Produk Jajanin'),
            'price_idr', coalesce(mi.price_idr, c.product_snapshot_price_idr, 0),
            'image_url', coalesce(mi.image_url, c.product_snapshot_image_url),
            'is_available', coalesce(mi.is_available, false),
            'is_deleted', (c.product_id is null or mi.id is null),
            'seller_name', coalesce(nullif(trim(v_stall_name), ''), v_counterparty_name, 'Warung Jajanin')
        );
    end if;

    if c.order_id is not null then
        select o.id, o.pickup_code, o.status, o.total_idr, o.payment_method, o.payment_status,
               o.order_mode, o.pickup_date, o.pickup_session, o.created_at
          into ord
          from public.orders o
         where o.id = c.order_id;

        if ord.id is not null then
            select coalesce(string_agg(oi.quantity::text || '× ' || oi.item_name, '  ·  '), ''),
                   coalesce(sum(oi.quantity), 0)::integer
              into v_items_summary, v_item_count
              from public.order_items oi
             where oi.order_id = c.order_id;

            select oi.item_name, oi.menu_item_id, m.image_url
              into v_first_item_name, v_first_product_id, v_first_image_url
              from public.order_items oi
              left join public.menu_items m on m.id = oi.menu_item_id
             where oi.order_id = c.order_id
             order by oi.id asc
             limit 1;

            if coalesce(ord.order_mode, 'same_day') = 'pre_order' then
                v_sched_label := 'PO ' || coalesce(ord.pickup_date::text, '') || ' · ' || coalesce(ord.pickup_session, 'Istirahat 1');
            else
                v_sched_label := 'Ambil Hari Ini · ' || coalesce(ord.pickup_session, 'Istirahat 1');
            end if;

            v_order_ctx := jsonb_build_object(
                'id', ord.id,
                'pickup_code', ord.pickup_code,
                'status', ord.status,
                'total_idr', ord.total_idr,
                'payment_method', ord.payment_method,
                'payment_status', ord.payment_status,
                'items_summary', coalesce(v_items_summary, ''),
                'first_item_name', coalesce(v_first_item_name, ''),
                'first_product_id', v_first_product_id,
                'image_url', v_first_image_url,
                'item_count', coalesce(v_item_count, 0),
                'schedule_label', v_sched_label,
                'created_at', ord.created_at
            );
        end if;
    end if;

    return jsonb_build_object(
        'id', c.id,
        'conversation_type', c.conversation_type,
        'order_id', c.order_id,
        'product_id', c.product_id,
        'stall_id', v_stall_id,
        'buyer_id', c.buyer_id,
        'seller_id', c.seller_id,
        'created_at', c.created_at,
        'updated_at', c.updated_at,
        'last_message_at', c.last_message_at,
        'counterparty_id', v_counterparty_id,
        'counterparty_name', coalesce(v_counterparty_name, 'Pengguna'),
        'counterparty_role', coalesce(v_counterparty_role, 'buyer'),
        'stall_name', coalesce(v_stall_name, ''),
        'unread_count', coalesce(v_unread, 0),
        'last_message', v_last_msg,
        'product_context', v_product_ctx,
        'order_context', v_order_ctx
    );
end;
$function$;

-- 5. Direct Store Chat RPC: allows Buyers to chat any Warung/Seller even when the store has 0 products.
CREATE OR REPLACE FUNCTION public.get_or_create_stall_conversation(p_stall_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_seller uuid;
    v_stall_name text;
    convo public.conversations%rowtype;
    v_msg_count integer := 0;
    v_auto_ts timestamptz;
begin
    if uid is null then
        raise exception 'Masuk dulu untuk menghubungi penjual.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan.';
    end if;
    if p_stall_id is null then
        raise exception 'Warung tidak valid.';
    end if;

    select s.owner_id, coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
      into v_seller, v_stall_name
      from public.stalls s
     where s.id = p_stall_id;

    if v_seller is null then
        raise exception 'Warung atau penjual tidak ditemukan.';
    end if;
    if uid = v_seller then
        raise exception 'Kamu adalah pemilik warung ini.';
    end if;

    perform pg_advisory_xact_lock(hashtext('chat_pair:' || uid::text || ':' || v_seller::text)::bigint);

    select * into convo
      from public.conversations c
     where c.buyer_id = uid
       and c.seller_id = v_seller
       and c.merged_into is null
     limit 1;

    if convo.id is null then
        insert into public.conversations (
            buyer_id,
            seller_id,
            conversation_type,
            updated_at
        )
        values (
            uid,
            v_seller,
            'product_inquiry',
            now()
        )
        returning * into convo;
    else
        update public.conversations
           set updated_at = now()
         where id = convo.id
        returning * into convo;
    end if;

    select count(*)::integer
      into v_msg_count
      from public.chat_messages m
     where m.conversation_id = convo.id;

    if v_msg_count = 0 then
        v_auto_ts := now();
        insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, read_at, created_at)
        values (
            convo.id,
            v_seller,
            'Hai! Selamat datang di ' || v_stall_name || '! Kami sangat senang Anda memilih warung kami. Kami siap melayani pertanyaan menu, stok, maupun pesanan Anda. Apa yang bisa kami bantu hari ini? Terima kasih.',
            'auto_reply',
            true,
            v_auto_ts,
            v_auto_ts
        );
        update public.conversations
           set last_message_at = v_auto_ts,
               updated_at = v_auto_ts
         where id = convo.id;
    end if;

    return public.format_conversation_json(convo.id, uid);
end;
$function$;

REVOKE ALL ON FUNCTION public.get_or_create_stall_conversation(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_or_create_stall_conversation(uuid) TO authenticated, service_role;

-- 6. Product Inquiry Chat RPC: attaches Shopee-style product showcase card + Warung Auto-Reply (deduplicated).
CREATE OR REPLACE FUNCTION public.get_or_create_product_conversation(p_product_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_seller uuid;
    v_stall_name text;
    v_name text;
    v_price integer;
    v_image text;
    v_schedule text;
    v_stock integer;
    v_sched_badge text;
    convo public.conversations%rowtype;
    v_already_sent boolean := false;
    v_card_body text;
    v_now timestamptz := now();
begin
    if uid is null then
        raise exception 'Masuk dulu untuk bertanya ke penjual.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan.';
    end if;
    if p_product_id is null then
        raise exception 'Produk tidak valid.';
    end if;

    select s.owner_id,
           coalesce(nullif(trim(s.name), ''), 'Warung Jajanin'),
           mi.name,
           mi.price_idr,
           mi.image_url,
           coalesce(mi.order_schedule, 'both'),
           mi.stock
      into v_seller, v_stall_name, v_name, v_price, v_image, v_schedule, v_stock
      from public.menu_items mi
      join public.stalls s on s.id = mi.stall_id
     where mi.id = p_product_id;

    if v_seller is null then
        raise exception 'Produk atau warung penjual tidak ditemukan.';
    end if;
    if uid = v_seller then
        raise exception 'Kamu adalah penjual produk ini.';
    end if;

    perform pg_advisory_xact_lock(hashtext('chat_pair:' || uid::text || ':' || v_seller::text)::bigint);

    insert into public.conversations (
        buyer_id,
        seller_id,
        product_id,
        conversation_type,
        product_snapshot_name,
        product_snapshot_price_idr,
        product_snapshot_image_url,
        updated_at
    )
    values (
        uid,
        v_seller,
        p_product_id,
        'product_inquiry',
        v_name,
        v_price,
        v_image,
        v_now
    )
    on conflict (buyer_id, seller_id) where merged_into is null
    do update set
        product_id = excluded.product_id,
        product_snapshot_name = excluded.product_snapshot_name,
        product_snapshot_price_idr = excluded.product_snapshot_price_idr,
        product_snapshot_image_url = excluded.product_snapshot_image_url,
        conversation_type = 'product_inquiry',
        updated_at = v_now
    returning * into convo;

    select exists (
        select 1
          from (
              select m.message_type, m.body
                from public.chat_messages m
               where m.conversation_id = convo.id
               order by m.created_at desc, m.id desc
               limit 3
          ) recent
         where recent.message_type = 'product_card'
           and recent.body like '%' || p_product_id::text || '%'
    ) into v_already_sent;

    if not v_already_sent then
        v_sched_badge := case v_schedule
            when 'next_day' then 'Pre-Order'
            when 'same_day' then 'Ambil Hari Ini'
            else 'Hari Ini / PO'
        end;

        v_card_body := jsonb_build_object(
            'order_id', '',
            'product_id', p_product_id::text,
            'pickup_code', '',
            'title', coalesce(v_name, 'Menu Warung'),
            'subtitle', case when v_stock is not null then 'Stok tersedia: ' || v_stock::text || ' porsi' else 'Tanyakan ketersediaan menu' end,
            'badge', v_sched_badge,
            'schedule', v_stall_name,
            'price_idr', coalesce(v_price, 0),
            'image_url', coalesce(v_image, ''),
            'cta_label', 'Lihat Detail Menu'
        )::text;

        insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, created_at)
        values (convo.id, uid, left(v_card_body, 1900), 'product_card', false, v_now);

        if not exists (
            select 1
              from public.chat_messages m
             where m.conversation_id = convo.id
               and m.message_type = 'auto_reply'
               and m.created_at >= (v_now - interval '6 hours')
        ) then
            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, read_at, created_at)
            values (
                convo.id,
                v_seller,
                'Hai! Selamat datang di ' || v_stall_name || '! Kami sangat senang Anda memilih warung kami. Kami berkomitmen untuk memberikan pelayanan terbaik dan produk yang berkualitas. Apa yang bisa kami bantu hari ini? Terima kasih.',
                'auto_reply',
                true,
                v_now + interval '1 millisecond',
                v_now + interval '1 millisecond'
            );
        end if;

        update public.conversations
           set last_message_at = v_now + interval '1 millisecond',
               updated_at = v_now + interval '1 millisecond'
         where id = convo.id;
    end if;

    return public.format_conversation_json(convo.id, uid);
end;
$function$;

-- 7. Order Chat RPC: when Buyer clicks "Chat Penjual" from their purchased Order page,
--    sends Shopee-style Purchased Product/Order Card + Warung Auto-Reply (deduplicated).
CREATE OR REPLACE FUNCTION public.get_or_create_conversation(p_order_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    o_user uuid;
    o_verified_by uuid;
    ord record;
    v_seller uuid;
    v_stall_name text := 'Warung Jajanin';
    seller_count integer := 0;
    convo public.conversations%rowtype;
    v_already_sent boolean := false;
    v_items_summary text := '';
    v_item_count integer := 0;
    v_first_name text := '';
    v_first_pid uuid := null;
    v_first_img text := '';
    v_status_badge text := 'Menunggu';
    v_sched_label text := '';
    v_card_body text;
    v_now timestamptz := now();
begin
    if uid is null then
        raise exception 'Masuk dulu untuk membuka chat.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan.';
    end if;

    select o.id, o.user_id, o.verified_by, o.stall_id, o.pickup_code, o.status,
           o.total_idr, o.order_mode, o.pickup_date, o.pickup_session
      into ord
      from public.orders o
     where o.id = p_order_id;

    if ord.id is null then
        raise exception 'Order tidak ditemukan.';
    end if;
    o_user := ord.user_id;
    o_verified_by := ord.verified_by;

    if ord.stall_id is not null then
        select s.owner_id, coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
          into v_seller, v_stall_name
          from public.stalls s
         where s.id = ord.stall_id;
        if v_seller is not null then
            seller_count := 1;
        end if;
    end if;

    if v_seller is null then
        select s.owner_id, coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
          into v_seller, v_stall_name
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = p_order_id
           and s.owner_id is not null
         group by s.owner_id, s.name
         limit 1;

        select count(distinct s.owner_id) into seller_count
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = p_order_id
           and s.owner_id is not null;
    end if;

    if v_seller is null then
        select c.seller_id into v_seller
          from public.conversations c
         where c.order_id = p_order_id
         limit 1;
        if v_seller is not null then
            seller_count := 1;
        elsif o_verified_by is not null and o_verified_by <> o_user then
            v_seller := o_verified_by;
            seller_count := 1;
        elsif uid <> o_user and exists (select 1 from public.stalls s where s.owner_id = uid) then
            v_seller := uid;
            seller_count := 1;
        else
            select s.owner_id, coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
              into v_seller, v_stall_name
              from public.stalls s
             where s.owner_id is not null and s.owner_id <> o_user
             order by s.created_at asc
             limit 1;
            if v_seller is not null then
                seller_count := 1;
            end if;
        end if;
    end if;

    if v_seller is null then
        raise exception 'Warung order ini tidak ditemukan.';
    end if;
    if seller_count > 1 then
        raise exception 'Order ini melibatkan beberapa warung.';
    end if;
    if uid <> o_user and uid <> v_seller then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;

    if v_stall_name = 'Warung Jajanin' then
        select coalesce(nullif(trim(s.name), ''), 'Warung Jajanin')
          into v_stall_name
          from public.stalls s
         where s.owner_id = v_seller
         order by s.created_at asc
         limit 1;
    end if;

    perform pg_advisory_xact_lock(hashtext('chat_pair:' || o_user::text || ':' || v_seller::text)::bigint);

    update public.conversations
       set order_id = null,
           conversation_type = 'product_inquiry'
     where order_id = p_order_id
       and (buyer_id <> o_user or seller_id <> v_seller or merged_into is not null);

    insert into public.conversations (order_id, buyer_id, seller_id, conversation_type, updated_at)
    values (p_order_id, o_user, v_seller, 'order', v_now)
    on conflict (buyer_id, seller_id) where merged_into is null
    do update set
        order_id = excluded.order_id,
        conversation_type = 'order',
        updated_at = v_now
    returning * into convo;

    -- Saat Pembeli menekan "Chat Penjual" dari halaman pesanan yang dibeli:
    -- tampilkan kartu produk pesanan ala Shopee + balasan sambutan otomatis warung.
    if uid = o_user then
        select exists (
            select 1
              from (
                  select m.message_type, m.body
                    from public.chat_messages m
                   where m.conversation_id = convo.id
                   order by m.created_at desc, m.id desc
                   limit 3
              ) recent
             where recent.message_type = 'product_card'
               and recent.body like '%' || p_order_id::text || '%'
        ) into v_already_sent;

        if not v_already_sent then
            select coalesce(string_agg(oi.quantity::text || '× ' || oi.item_name, ' · '), ''),
                   coalesce(sum(oi.quantity), 0)::integer
              into v_items_summary, v_item_count
              from public.order_items oi
             where oi.order_id = p_order_id;

            select oi.item_name, oi.menu_item_id, coalesce(m.image_url, '')
              into v_first_name, v_first_pid, v_first_img
              from public.order_items oi
              left join public.menu_items m on m.id = oi.menu_item_id
             where oi.order_id = p_order_id
             order by oi.id asc
             limit 1;

            v_status_badge := case lower(coalesce(ord.status, 'pending'))
                when 'confirmed' then 'Dikonfirmasi'
                when 'preparing' then 'Sedang Disiapkan'
                when 'processing' then 'Sedang Disiapkan'
                when 'ready' then 'Siap Diambil'
                when 'completed' then 'Selesai'
                when 'cancelled' then 'Dibatalkan'
                else 'Menunggu Konfirmasi'
            end;

            if coalesce(ord.order_mode, 'same_day') = 'pre_order' then
                v_sched_label := 'PO ' || coalesce(ord.pickup_date::text, '') || ' · ' || coalesce(ord.pickup_session, 'Istirahat 1');
            else
                v_sched_label := 'Ambil Hari Ini · ' || coalesce(ord.pickup_session, 'Istirahat 1');
            end if;

            v_card_body := jsonb_build_object(
                'order_id', p_order_id::text,
                'product_id', coalesce(v_first_pid::text, ''),
                'pickup_code', coalesce(ord.pickup_code, ''),
                'title', coalesce(nullif(v_items_summary, ''), nullif(v_first_name, ''), 'Pesanan #' || coalesce(ord.pickup_code, '')),
                'subtitle', 'Pesanan #' || coalesce(ord.pickup_code, '') || ' · ' || greatest(v_item_count, 1)::text || ' item',
                'badge', v_status_badge,
                'schedule', v_sched_label,
                'price_idr', coalesce(ord.total_idr, 0),
                'image_url', coalesce(v_first_img, ''),
                'cta_label', 'Lihat Rincian Pesanan'
            )::text;

            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, created_at)
            values (convo.id, uid, left(v_card_body, 1900), 'product_card', false, v_now);

            insert into public.chat_messages (conversation_id, sender_id, body, message_type, is_read, read_at, created_at)
            values (
                convo.id,
                v_seller,
                'Hai! Selamat datang di ' || v_stall_name || '! Kami sangat senang Anda memilih warung kami. Kami berkomitmen untuk memberikan pelayanan terbaik dan produk yang berkualitas. Apa yang bisa kami bantu untuk pesanan #' || coalesce(ord.pickup_code, '') || ' hari ini? Terima kasih.',
                'auto_reply',
                true,
                v_now + interval '1 millisecond',
                v_now + interval '1 millisecond'
            );

            update public.conversations
               set last_message_at = v_now + interval '1 millisecond',
                   updated_at = v_now + interval '1 millisecond'
             where id = convo.id;
        end if;
    end if;

    return public.format_conversation_json(convo.id, uid);
end;
$function$;

-- 8. Update place_order_v3:
--    - Marks internal stock deduction so guard_menu_mutation allows stock -> 0 on purchase
--    - Automatically creates Shopee-style Order Receipt Card + Seller Auto-Chat when Buyer places an order
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
    v_stall_name text := 'Warung Jajanin';
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

    select s.owner_id,
           coalesce(nullif(trim(s.name), ''), 'Warung Jajanin'),
           nullif(trim(coalesce(s.qris_image_url, '')), ''),
           coalesce(s.is_open, true)
      into v_stall_owner, v_stall_name, v_stall_qris, v_stall_open
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
        v_order_id, uid, v_stall_id, 'pending', total, method, 'unpaid',
        new_pickup_code, v_mode, v_pickup_date, v_session,
        v_expires_at, v_any_stock_deducted
    );

    perform set_config('app.internal_stock_op', 'on', true);

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
        values (v_order_id, pid, prod_name, prod_price, qty);

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

    -- Auto-Chat Konfirmasi Pesanan dari Penjual (Shopee-style Order Card + Pesan Otomatis Penjual)
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
                    then 'Silakan selesaikan pembayaran QRIS dan unggah bukti transfer agar pesanan segera kami siapkan sesuai jadwal (' || v_sched_text || ').'
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
