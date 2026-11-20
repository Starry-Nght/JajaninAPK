ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS quota_reset_at timestamptz;

CREATE OR REPLACE FUNCTION public.protect_profile_role()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    if new.role is distinct from old.role then
        if old.role = 'buyer' and new.role = 'seller'
            and coalesce(current_setting('app.seller_promote', true), '') = 'on'
            and exists (
                select 1 from public.seller_invites
                where target_user_id = new.id and status = 'accepted'
            ) then
            null;
        else
            raise exception 'role tidak dapat diubah dari aplikasi';
        end if;
    end if;
    if (new.account_status is distinct from old.account_status
        or new.warning_count is distinct from old.warning_count
        or new.quota_reset_at is distinct from old.quota_reset_at) then
        if not public.is_developer() then
            raise exception 'Hanya developer yang dapat mengubah status atau kuota akun.';
        end if;
    end if;
    return new;
end;
$function$;

CREATE OR REPLACE FUNCTION public.ordering_status()
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    wib_time time := ((now() at time zone 'Asia/Jakarta'))::time;
    wib_date date := ((now() at time zone 'Asia/Jakarta'))::date;
    blocked boolean := false;
    block_msg text := null;
    cnt integer := 0;
    v_quota_reset_at timestamptz := null;
begin
    if wib_time >= time '09:00' and wib_time < time '10:00' then
        blocked := true;
        block_msg := 'Pesanan sementara ditutup pukul 09:00-10:00 untuk mengurangi antrean penjual.';
    elsif wib_time >= time '12:00' and wib_time < time '13:00' then
        blocked := true;
        block_msg := 'Pesanan sementara ditutup pukul 12:00-13:00 untuk mengurangi antrean penjual.';
    end if;

    if uid is not null then
        select p.quota_reset_at into v_quota_reset_at
          from public.profiles p
         where p.id = uid;

        select count(*) into cnt
          from public.orders o
         where o.user_id = uid
           and o.status <> 'cancelled'
           and (o.created_at at time zone 'Asia/Jakarta')::date = wib_date
           and (v_quota_reset_at is null or o.created_at > v_quota_reset_at);
    end if;

    return jsonb_build_object(
        'blocked', blocked,
        'block_message', block_msg,
        'orders_today', cnt,
        'remaining', greatest(0, 2 - cnt),
        'server_time', (now() at time zone 'Asia/Jakarta')::text
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.place_order(p_items jsonb, p_payment_method text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    method text := lower(trim(coalesce(p_payment_method, '')));
    order_id uuid := gen_random_uuid();
    total integer := 1000;
    it jsonb;
    pid uuid;
    qty integer;
    prod_name text;
    prod_price integer;
    prod_available boolean;
    prod_stock integer;
    wib_time time := ((now() at time zone 'Asia/Jakarta'))::time;
    wib_date date := ((now() at time zone 'Asia/Jakarta'))::date;
    today_count integer;
    v_quota_reset_at timestamptz := null;
    v_stall_id uuid;
    stall_active integer;
    stall_order_num integer;
    new_pickup_code text;
begin
    if uid is null then
        raise exception 'Masuk dulu sebelum memesan.';
    end if;

    perform pg_advisory_xact_lock(hashtext(uid::text)::bigint);

    if exists (select 1 from public.profiles p where p.id = uid and p.account_status = 'suspended') then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    if wib_time >= time '09:00' and wib_time < time '10:00' then
        raise exception 'Pesanan sementara ditutup pukul 09:00-10:00 untuk mengurangi antrean penjual.';
    end if;
    if wib_time >= time '12:00' and wib_time < time '13:00' then
        raise exception 'Pesanan sementara ditutup pukul 12:00-13:00 untuk mengurangi antrean penjual.';
    end if;

    if method not in ('cash', 'qris') then
        raise exception 'Metode bayar tidak valid.';
    end if;
    if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        raise exception 'Keranjang masih kosong.';
    end if;
    if jsonb_array_length(p_items) <> 1 then
        raise exception 'Satu pesanan hanya untuk satu menu.';
    end if;

    begin
        pid := ((p_items -> 0) ->> 'menu_item_id')::uuid;
    exception when others then
        raise exception 'Format item tidak valid.';
    end;
    select mi.stall_id into v_stall_id
      from public.menu_items mi where mi.id = pid;
    if not found then
        raise exception 'Menu tidak ditemukan.';
    end if;
    perform pg_advisory_xact_lock(hashtext('stall:' || v_stall_id::text)::bigint);

    for it in select * from jsonb_array_elements(p_items) loop
        begin
            pid := (it ->> 'menu_item_id')::uuid;
            qty := (it ->> 'quantity')::integer;
        exception when others then
            raise exception 'Format item tidak valid.';
        end;
        if qty is null or qty <> 1 then
            raise exception 'Jumlah pesanan harus tepat 1.';
        end if;
        select mi.name, mi.price_idr, mi.is_available, mi.stock
          into prod_name, prod_price, prod_available, prod_stock
          from public.menu_items mi where mi.id = pid;
        if not found then
            raise exception 'Menu tidak ditemukan.';
        end if;
        if not prod_available then
            raise exception 'Menu % sedang tidak tersedia.', prod_name;
        end if;
        if prod_stock is not null and prod_stock < qty then
            raise exception 'Stok % tidak mencukupi.', prod_name;
        end if;
        total := total + prod_price * qty;
    end loop;

    select p.quota_reset_at into v_quota_reset_at
      from public.profiles p
     where p.id = uid;

    select count(*) into today_count
      from public.orders o
     where o.user_id = uid
       and o.status <> 'cancelled'
       and (o.created_at at time zone 'Asia/Jakarta')::date = wib_date
       and (v_quota_reset_at is null or o.created_at > v_quota_reset_at);
    if today_count >= 2 then
        raise exception 'Batas pesanan harian tercapai (2 pesanan/hari). Coba lagi besok.';
    end if;

    select count(*) into stall_active
      from public.orders o
     where o.status in ('pending', 'confirmed', 'preparing', 'processing')
       and exists (
            select 1
              from public.order_items oi
              join public.menu_items mi on mi.id = oi.menu_item_id
             where oi.order_id = o.id
               and mi.stall_id = v_stall_id
       );
    if stall_active >= 10 then
        raise exception 'Warung sedang penuh. Maksimal 10 pesanan aktif. Silakan coba lagi beberapa saat.';
    end if;

    select count(*) + 1 into stall_order_num
      from public.orders o
     where exists (
          select 1 from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          where oi.order_id = o.id and mi.stall_id = v_stall_id
     );
    new_pickup_code := lpad(stall_order_num::text, 3, '0');
    while exists (select 1 from public.orders where pickup_code = new_pickup_code) loop
        stall_order_num := stall_order_num + 1;
        new_pickup_code := lpad(stall_order_num::text, 3, '0');
    end loop;

    insert into public.orders (id, user_id, status, total_idr, payment_method, payment_status, pickup_code)
    values (order_id, uid, 'pending', total, method, 'unpaid', new_pickup_code);

    for it in select * from jsonb_array_elements(p_items) loop
        pid := (it ->> 'menu_item_id')::uuid;
        qty := (it ->> 'quantity')::integer;
        select mi.name, mi.price_idr, mi.stock
          into prod_name, prod_price, prod_stock
          from public.menu_items mi where mi.id = pid;
        insert into public.order_items (order_id, menu_item_id, item_name, unit_price_idr, quantity)
        values (order_id, pid, prod_name, prod_price, qty);
        if prod_stock is not null then
            update public.menu_items
               set stock = stock - qty
             where id = pid and stock >= qty;
            if not found then
                raise exception 'Stok berubah saat memesan, coba lagi.';
            end if;
        end if;
    end loop;

    return jsonb_build_object(
        'id', order_id,
        'pickup_code', new_pickup_code,
        'total_idr', total,
        'payment_method', method,
        'payment_status', 'unpaid',
        'status', 'pending'
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.daily_reset_monitor()
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_orders integer := 0;
    v_warnings integer := 0;
    v_anns integer := 0;
    v_profiles integer := 0;
begin
    if not public.is_developer() then
        raise exception 'Hanya developer yang dapat mereset monitor.';
    end if;

    perform set_config('app.daily_reset_active', 'on', true);
    update public.orders o
       set status = 'completed'
     where o.status in ('pending', 'confirmed', 'preparing', 'processing', 'ready');
    get diagnostics v_orders = row_count;
    perform set_config('app.daily_reset_active', '', true);

    delete from public.developer_warnings;
    get diagnostics v_warnings = row_count;
    delete from public.developer_announcements;
    get diagnostics v_anns = row_count;

    update public.profiles p
       set warning_count = 0,
           account_status = case when p.account_status = 'warned' then 'active' else p.account_status end,
           quota_reset_at = now()
     where p.warning_count <> 0 or p.account_status = 'warned' or p.role = 'buyer';
    get diagnostics v_profiles = row_count;

    return jsonb_build_object(
        'completed_orders', v_orders,
        'cleared_warnings', v_warnings,
        'cleared_announcements', v_anns,
        'reset_profiles', v_profiles
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.dev_reset_buyer_quota(p_target_user_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_count integer := 0;
begin
    if not public.is_developer() then
        raise exception 'Hanya developer yang dapat mereset jatah pesanan.';
    end if;
    if p_target_user_id is null then
        raise exception 'Target pengguna tidak valid.';
    end if;

    update public.profiles p
       set quota_reset_at = now()
     where p.id = p_target_user_id;
    get diagnostics v_count = row_count;
    return v_count;
end;
$function$;

CREATE OR REPLACE FUNCTION public.dev_reset_all_quotas()
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_count integer := 0;
begin
    if not public.is_developer() then
        raise exception 'Hanya developer yang dapat mereset jatah pesanan.';
    end if;

    update public.profiles p
       set quota_reset_at = now()
     where p.role = 'buyer';
    get diagnostics v_count = row_count;
    return v_count;
end;
$function$;

REVOKE EXECUTE ON FUNCTION public.dev_reset_buyer_quota(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dev_reset_buyer_quota(uuid) TO authenticated;
REVOKE EXECUTE ON FUNCTION public.dev_reset_all_quotas() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dev_reset_all_quotas() TO authenticated;
