ALTER TABLE public.conversations
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS product_id uuid REFERENCES public.menu_items(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS conversation_type text NOT NULL DEFAULT 'order',
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS product_snapshot_name text,
    ADD COLUMN IF NOT EXISTS product_snapshot_price_idr integer,
    ADD COLUMN IF NOT EXISTS product_snapshot_image_url text;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'conversations_type_check'
          AND conrelid = 'public.conversations'::regclass
    ) THEN
        ALTER TABLE public.conversations
            ADD CONSTRAINT conversations_type_check CHECK (
                (conversation_type = 'order' AND order_id IS NOT NULL) OR
                (conversation_type = 'product_inquiry')
            );
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS conversations_preorder_product_uidx
    ON public.conversations (buyer_id, seller_id, product_id)
    WHERE order_id IS NULL AND product_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_product_id
    ON public.conversations (product_id)
    WHERE product_id IS NOT NULL;

ALTER TABLE public.chat_messages
    ADD COLUMN IF NOT EXISTS message_type text NOT NULL DEFAULT 'text',
    ADD COLUMN IF NOT EXISTS read_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_chat_messages_unread
    ON public.chat_messages (conversation_id, sender_id)
    WHERE is_read = false;

REVOKE ALL ON FUNCTION public.notify_chat_message() FROM PUBLIC, anon, authenticated;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'chat_messages_notify'
          AND tgrelid = 'public.chat_messages'::regclass
    ) THEN
        CREATE TRIGGER chat_messages_notify
            AFTER INSERT ON public.chat_messages
            FOR EACH ROW
            EXECUTE FUNCTION public.notify_chat_message();
    END IF;
END $$;

CREATE OR REPLACE FUNCTION public.format_conversation_json(p_convo_id uuid, p_viewer_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    c public.conversations%rowtype;
    v_counterparty_id uuid;
    v_counterparty_name text;
    v_counterparty_role text;
    v_stall_name text;
    v_unread integer := 0;
    v_last_msg jsonb := null;
    v_product_ctx jsonb := null;
    v_order_ctx jsonb := null;
    mi record;
    ord record;
    v_items_summary text;
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

    select s.name into v_stall_name
      from public.stalls s
     where s.owner_id = c.seller_id
     order by s.created_at asc
     limit 1;

    select count(*)::integer into v_unread
      from public.chat_messages m
     where m.conversation_id = c.id
       and m.sender_id <> p_viewer_id
       and m.is_read = false;

    select jsonb_build_object(
        'id', m.id,
        'sender_id', m.sender_id,
        'body', m.body,
        'message_type', m.message_type,
        'is_read', m.is_read,
        'read_at', m.read_at,
        'created_at', m.created_at
    )
    into v_last_msg
    from public.chat_messages m
    where m.conversation_id = c.id
    order by m.created_at desc, m.id desc
    limit 1;

    if c.conversation_type = 'product_inquiry' or c.product_id is not null or c.product_snapshot_name is not null then
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
        select o.id, o.pickup_code, o.status, o.total_idr, o.payment_method, o.payment_status
          into ord
          from public.orders o
         where o.id = c.order_id;

        if ord.id is not null then
            select coalesce(string_agg(oi.quantity::text || '× ' || oi.item_name, '  ·  '), '')
              into v_items_summary
              from public.order_items oi
             where oi.order_id = c.order_id;

            v_order_ctx := jsonb_build_object(
                'id', ord.id,
                'pickup_code', ord.pickup_code,
                'status', ord.status,
                'total_idr', ord.total_idr,
                'payment_method', ord.payment_method,
                'payment_status', ord.payment_status,
                'items_summary', coalesce(v_items_summary, '')
            );
        end if;
    end if;

    return jsonb_build_object(
        'id', c.id,
        'conversation_type', c.conversation_type,
        'order_id', c.order_id,
        'product_id', c.product_id,
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

REVOKE ALL ON FUNCTION public.format_conversation_json(uuid, uuid) FROM PUBLIC, anon, authenticated;

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
    v_seller uuid;
    seller_count integer := 0;
    convo public.conversations%rowtype;
begin
    if uid is null then
        raise exception 'Masuk dulu untuk membuka chat.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan.';
    end if;

    perform pg_advisory_xact_lock(hashtext('chat_order:' || coalesce(p_order_id::text, ''))::bigint);

    select * into convo from public.conversations where order_id = p_order_id;
    if convo.id is not null then
        if uid <> convo.buyer_id and uid <> convo.seller_id then
            raise exception 'Kamu tidak punya akses ke percakapan ini.';
        end if;
        return public.format_conversation_json(convo.id, uid);
    end if;

    select o.user_id, o.verified_by into o_user, o_verified_by
      from public.orders o
     where o.id = p_order_id;
    if o_user is null then
        raise exception 'Order tidak ditemukan.';
    end if;

    select s.owner_id into v_seller
      from public.order_items oi
      join public.menu_items mi on mi.id = oi.menu_item_id
      join public.stalls s on s.id = mi.stall_id
     where oi.order_id = p_order_id
       and s.owner_id is not null
     group by s.owner_id
     limit 1;

    select count(distinct s.owner_id) into seller_count
      from public.order_items oi
      join public.menu_items mi on mi.id = oi.menu_item_id
      join public.stalls s on s.id = mi.stall_id
     where oi.order_id = p_order_id
       and s.owner_id is not null;

    if v_seller is null then
        if o_verified_by is not null and o_verified_by <> o_user then
            v_seller := o_verified_by;
            seller_count := 1;
        elsif uid <> o_user and exists (select 1 from public.stalls s where s.owner_id = uid) then
            v_seller := uid;
            seller_count := 1;
        else
            select s.owner_id into v_seller
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

    insert into public.conversations (order_id, buyer_id, seller_id, conversation_type, updated_at)
    values (p_order_id, o_user, v_seller, 'order', now())
    on conflict (order_id) do nothing;

    select * into convo from public.conversations where order_id = p_order_id;
    return public.format_conversation_json(convo.id, uid);
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_or_create_product_conversation(p_product_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_seller uuid;
    v_name text;
    v_price integer;
    v_image text;
    convo public.conversations%rowtype;
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

    select s.owner_id, mi.name, mi.price_idr, mi.image_url
      into v_seller, v_name, v_price, v_image
      from public.menu_items mi
      join public.stalls s on s.id = mi.stall_id
     where mi.id = p_product_id;

    if v_seller is null then
        raise exception 'Produk atau warung penjual tidak ditemukan.';
    end if;
    if uid = v_seller then
        raise exception 'Kamu adalah penjual produk ini.';
    end if;

    perform pg_advisory_xact_lock(hashtext('chat_prod:' || uid::text || ':' || p_product_id::text)::bigint);

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
        now()
    )
    on conflict (buyer_id, seller_id, product_id)
    where order_id is null and product_id is not null
    do update set
        product_snapshot_name = excluded.product_snapshot_name,
        product_snapshot_price_idr = excluded.product_snapshot_price_idr,
        product_snapshot_image_url = excluded.product_snapshot_image_url,
        updated_at = now()
    returning * into convo;

    return public.format_conversation_json(convo.id, uid);
end;
$function$;

REVOKE ALL ON FUNCTION public.get_or_create_product_conversation(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_or_create_product_conversation(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.fetch_conversation(p_conversation_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    convo public.conversations%rowtype;
begin
    if uid is null then
        raise exception 'Masuk dulu.';
    end if;
    select * into convo from public.conversations where id = p_conversation_id;
    if convo.id is null then
        raise exception 'Percakapan tidak ditemukan.';
    end if;
    if uid <> convo.buyer_id and uid <> convo.seller_id then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;
    return public.format_conversation_json(convo.id, uid);
end;
$function$;

CREATE OR REPLACE FUNCTION public.list_my_conversations(
    p_limit integer DEFAULT 30,
    p_before timestamptz DEFAULT NULL
)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    result jsonb;
begin
    if uid is null then
        raise exception 'Masuk dulu untuk melihat daftar chat.';
    end if;

    select coalesce(jsonb_agg(item), '[]'::jsonb)
      into result
      from (
          select public.format_conversation_json(c.id, uid) as item
            from public.conversations c
           where (c.buyer_id = uid or c.seller_id = uid)
             and (p_before is null or coalesce(c.last_message_at, c.created_at) < p_before)
           order by coalesce(c.last_message_at, c.created_at) desc, c.id desc
           limit greatest(1, least(coalesce(p_limit, 30), 100))
      ) q
     where item is not null;

    return coalesce(result, '[]'::jsonb);
end;
$function$;

REVOKE ALL ON FUNCTION public.list_my_conversations(integer, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.list_my_conversations(integer, timestamptz) TO authenticated;

CREATE OR REPLACE FUNCTION public.fetch_messages(p_conversation_id uuid, p_limit integer DEFAULT 60)
 RETURNS SETOF public.chat_messages
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'Masuk dulu untuk membaca pesan.';
    end if;
    if not exists (
        select 1 from public.conversations c
        where c.id = p_conversation_id
          and (c.buyer_id = uid or c.seller_id = uid)
    ) then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;
    return query
    select * from (
        select m.* from public.chat_messages m
        where m.conversation_id = p_conversation_id
        order by m.created_at desc, m.id desc
        limit greatest(1, least(coalesce(p_limit, 60), 200))
    ) s order by s.created_at asc, s.id asc;
end;
$function$;

CREATE OR REPLACE FUNCTION public.fetch_messages_page(
    p_conversation_id uuid,
    p_limit integer DEFAULT 50,
    p_before timestamptz DEFAULT NULL
)
 RETURNS SETOF public.chat_messages
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'Masuk dulu untuk membaca pesan.';
    end if;
    if not exists (
        select 1 from public.conversations c
        where c.id = p_conversation_id
          and (c.buyer_id = uid or c.seller_id = uid)
    ) then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;
    return query
    select * from (
        select m.* from public.chat_messages m
        where m.conversation_id = p_conversation_id
          and (p_before is null or m.created_at < p_before)
        order by m.created_at desc, m.id desc
        limit greatest(1, least(coalesce(p_limit, 50), 200))
    ) s order by s.created_at asc, s.id asc;
end;
$function$;

REVOKE ALL ON FUNCTION public.fetch_messages_page(uuid, integer, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_messages_page(uuid, integer, timestamptz) TO authenticated;

CREATE OR REPLACE FUNCTION public.send_message(p_conversation_id uuid, p_body text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    convo public.conversations%rowtype;
    clean text := trim(coalesce(p_body, ''));
    msg public.chat_messages%rowtype;
begin
    if uid is null then
        raise exception 'Masuk dulu untuk mengirim pesan.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Tidak dapat mengirim pesan.';
    end if;
    if char_length(clean) < 1 or char_length(clean) > 500 then
        raise exception 'Pesan 1 sampai 500 karakter.';
    end if;
    select * into convo from public.conversations where id = p_conversation_id;
    if convo.id is null then
        raise exception 'Percakapan tidak ditemukan.';
    end if;
    if uid <> convo.buyer_id and uid <> convo.seller_id then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;
    insert into public.chat_messages (conversation_id, sender_id, body, message_type)
    values (p_conversation_id, uid, clean, 'text')
    returning * into msg;
    update public.conversations
       set last_message_at = msg.created_at,
           updated_at = msg.created_at
     where id = p_conversation_id;
    return jsonb_build_object(
        'id', msg.id,
        'conversation_id', msg.conversation_id,
        'sender_id', msg.sender_id,
        'body', msg.body,
        'message_type', msg.message_type,
        'is_read', msg.is_read,
        'read_at', msg.read_at,
        'created_at', msg.created_at
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.mark_conversation_read(p_conversation_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    n integer := 0;
begin
    if uid is null then
        raise exception 'Masuk dulu.';
    end if;
    if not exists (
        select 1 from public.conversations c
        where c.id = p_conversation_id
          and (c.buyer_id = uid or c.seller_id = uid)
    ) then
        raise exception 'Kamu tidak punya akses ke percakapan ini.';
    end if;
    update public.chat_messages
       set is_read = true,
           read_at = coalesce(read_at, now())
     where conversation_id = p_conversation_id
       and sender_id <> uid
       and is_read = false;
    get diagnostics n = row_count;
    return n;
end;
$function$;

CREATE OR REPLACE FUNCTION public.unread_by_order()
 RETURNS TABLE(order_id uuid, unread bigint)
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'Masuk dulu.';
    end if;
    return query
    select c.order_id, count(m.id) as unread
      from public.conversations c
      join public.chat_messages m on m.conversation_id = c.id
     where (c.buyer_id = uid or c.seller_id = uid)
       and c.order_id is not null
       and m.sender_id <> uid
       and m.is_read = false
     group by c.order_id;
end;
$function$;
