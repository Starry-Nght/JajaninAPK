-- 202610080008_seller_overload.sql
--
-- Seller overload protection: each stall serves at most 10 ACTIVE orders at
-- the same time. The stall is derived server-side from the ordered menu
-- item; no client-provided seller_id is trusted. Enforced in
-- public.place_order(...), so direct RPC calls cannot bypass it.
--
-- Active = pending, confirmed, preparing (+ legacy processing).
-- Freed by: ready, completed, cancelled (payment rejected/paid do not
-- change the order status, so they never affect capacity).
--
-- Concurrency: per-buyer lock (existing) + per-stall advisory transaction
-- lock, always taken in that order, so two simultaneous orders for the last
-- slot cannot both pass the capacity check and independent stalls never
-- block each other.
--
-- Preserves: buyer daily limit, blackout hours, single-item/qty-1 rule,
-- cash/qris, DB-side pricing, stock + availability checks, suspended
-- protection, existing RLS (no policy touched here).

begin;

-- Support the capacity EXISTS query (stall -> items -> orders by status).
create index if not exists orders_status_idx
    on public.orders (status);
create index if not exists order_items_menu_item_idx
    on public.order_items (menu_item_id);

create or replace function public.place_order(p_items jsonb, p_payment_method text)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
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
    -- Explicit WIB server time: timestamptz is stored UTC, AT TIME ZONE
    -- converts to wall-clock Asia/Jakarta for day + blackout windows.
    wib_time time := ((now() at time zone 'Asia/Jakarta'))::time;
    wib_date date := ((now() at time zone 'Asia/Jakarta'))::date;
    today_count integer;
    v_stall_id uuid;
    stall_active integer;
begin
    if uid is null then
        raise exception 'Masuk dulu sebelum memesan.';
    end if;

    -- Serialize concurrent place_order calls of the SAME buyer: the lock is
    -- held until this transaction commits/rolls back, so the count below
    -- and the insert after it are atomic per buyer.
    perform pg_advisory_xact_lock(hashtext(uid::text)::bigint);

    if exists (select 1 from public.profiles p where p.id = uid and p.account_status = 'suspended') then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    -- Blackout hours (half-open intervals, WIB server time).
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

    -- Resolve the stall server-side from the single ordered item, then take
    -- the per-stall lock (always AFTER the buyer lock: fixed global order,
    -- so no deadlock between concurrent buyers/stalls).
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

    -- Daily limit on the Asia/Jakarta calendar day (resets at 00:00 WIB).
    select count(*) into today_count
      from public.orders o
     where o.user_id = uid
       and (o.created_at at time zone 'Asia/Jakarta')::date = wib_date;
    if today_count >= 2 then
        raise exception 'Batas pesanan harian tercapai (2 pesanan/hari). Coba lagi besok.';
    end if;

    -- Per-stall active-order capacity (max 10). Statuses ready / completed /
    -- cancelled no longer occupy a slot.
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

    insert into public.orders (id, user_id, status, total_idr, payment_method, payment_status)
    values (order_id, uid, 'pending', total, method, 'unpaid');

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
        'pickup_code', (select o.pickup_code from public.orders o where o.id = order_id),
        'total_idr', total,
        'payment_method', method,
        'payment_status', 'unpaid',
        'status', 'pending'
    );
end;
$function$;

revoke execute on function public.place_order(jsonb, text) from public, anon;
grant execute on function public.place_order(jsonb, text) to authenticated;

-- Read-only availability probe for the Android UI (aggregate only, no order
-- details leak). Never trusted for enforcement; place_order re-checks.
create or replace function public.stall_availability(p_stall_id uuid)
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
    cnt integer := 0;
begin
    if p_stall_id is null then
        raise exception 'Warung tidak valid.';
    end if;
    select count(*) into cnt
      from public.orders o
     where o.status in ('pending', 'confirmed', 'preparing', 'processing')
       and exists (
            select 1
              from public.order_items oi
              join public.menu_items mi on mi.id = oi.menu_item_id
             where oi.order_id = o.id
               and mi.stall_id = p_stall_id
       );
    return jsonb_build_object(
        'stall_id', p_stall_id,
        'active_orders', cnt,
        'capacity', 10,
        'is_full', cnt >= 10
    );
end;
$function$;

revoke execute on function public.stall_availability(uuid) from public, anon;
grant execute on function public.stall_availability(uuid) to authenticated;

commit;
