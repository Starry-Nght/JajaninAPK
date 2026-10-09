-- 202610080007_ordering_limits.sql
--
-- Server-side ordering restrictions (business timezone Asia/Jakarta, WIB).
-- DB is the source of truth: the Android client only mirrors these rules
-- for UX, every rule is re-checked here so a direct RPC call cannot bypass
-- them.
--
-- Rules enforced in public.place_order(...):
--   1. Max 2 orders per buyer per Asia/Jakarta calendar day.
--   2. Exactly ONE menu item per order, quantity exactly 1.
--   3. Blackout 09:00-10:00 and 12:00-13:00 WIB (server time, never device).
--   4. Atomic daily-limit check via per-buyer advisory transaction lock, so
--      two simultaneous requests cannot both slip under the limit.
--
-- Preserves: cash/qris methods, DB-side pricing, stock + availability checks,
-- suspended-account protection, existing RLS (no policy touched here).

begin;

-- Fast per-buyer daily counting on the WIB calendar day.
create index if not exists orders_user_wib_day_idx
    on public.orders (user_id, ((created_at at time zone 'Asia/Jakarta')::date));

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

-- Read-only status probe for the Android UI (server-time blackout flag +
-- today's order count). Never trusted for enforcement; place_order above
-- always re-checks.
create or replace function public.ordering_status()
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
    uid uuid := auth.uid();
    wib_time time := ((now() at time zone 'Asia/Jakarta'))::time;
    wib_date date := ((now() at time zone 'Asia/Jakarta'))::date;
    blocked boolean := false;
    block_msg text := null;
    cnt integer := 0;
begin
    if wib_time >= time '09:00' and wib_time < time '10:00' then
        blocked := true;
        block_msg := 'Pesanan sementara ditutup pukul 09:00-10:00 untuk mengurangi antrean penjual.';
    elsif wib_time >= time '12:00' and wib_time < time '13:00' then
        blocked := true;
        block_msg := 'Pesanan sementara ditutup pukul 12:00-13:00 untuk mengurangi antrean penjual.';
    end if;

    if uid is not null then
        select count(*) into cnt
          from public.orders o
         where o.user_id = uid
           and (o.created_at at time zone 'Asia/Jakarta')::date = wib_date;
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

revoke execute on function public.ordering_status() from public, anon;
grant execute on function public.ordering_status() to authenticated;

commit;
