-- Applied live via Supabase MCP apply_migration:
-- 1) Exclude cancelled orders (o.status <> 'cancelled') from buyer daily quota in ordering_status() and place_order()
-- 2) Automatically restore menu_items.stock when an order is cancelled in guard_order_mutation()
-- 3) Allow buyers to cancel their own pending orders (status = 'cancelled') in guard_order_mutation()

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
           and o.status <> 'cancelled'
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
