-- 202610080004: Complete buyer -> seller ordering flow.
-- Payment fields, per-stall seller scope, server-side place_order RPC,
-- mutation guards, buyer verification path, QRIS storage bucket.

begin;

-- 1) Payment columns on orders (payment_method already exists, nullable).
alter table public.orders
    add column if not exists payment_status text not null default 'unpaid',
    add column if not exists payment_reference text,
    add column if not exists paid_at timestamptz,
    add column if not exists verified_by uuid references auth.users(id) on delete set null;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'orders_payment_status_check'
    ) then
        alter table public.orders add constraint orders_payment_status_check
            check (payment_status in ('unpaid', 'waiting_verification', 'paid', 'rejected'));
    end if;
    if not exists (
        select 1 from pg_constraint where conname = 'orders_payment_method_check'
    ) then
        alter table public.orders add constraint orders_payment_method_check
            check (payment_method is null or lower(payment_method) in ('cash', 'qris'));
    end if;
end $$;

-- 2) QRIS image per stall (URL only, no credentials in client).
alter table public.stalls
    add column if not exists qris_image_url text;

-- 3) Server-side order placement: validates menu/availability/stock, prices
--    from DB, snapshots item_name + unit_price, decrements stock, inserts
--    order + items atomically. Client totals are never trusted.
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
begin
    if uid is null then
        raise exception 'Masuk dulu sebelum memesan.';
    end if;
    if method not in ('cash', 'qris') then
        raise exception 'Metode bayar tidak valid.';
    end if;
    if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        raise exception 'Keranjang masih kosong.';
    end if;

    for it in select * from jsonb_array_elements(p_items) loop
        begin
            pid := (it ->> 'menu_item_id')::uuid;
            qty := (it ->> 'quantity')::integer;
        exception when others then
            raise exception 'Format item tidak valid.';
        end;
        if qty is null or qty < 1 or qty > 99 then
            raise exception 'Jumlah tidak valid.';
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

-- 4) Mutation guard: buyers may only flip unpaid|rejected -> waiting_verification
--    on their own QRIS orders; sellers only touch own-stall orders; paid_at and
--    verified_by are stamped server-side, never from the client.
create or replace function public.guard_order_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
declare
    admin boolean;
begin
    admin := public.is_admin();
    if TG_OP = 'INSERT' then
        new.payment_status := 'unpaid';
        new.paid_at := null;
        new.verified_by := null;
        new.payment_reference := null;
        if new.payment_method is not null
           and lower(new.payment_method) not in ('cash', 'qris') then
            raise exception 'Metode bayar tidak valid.';
        end if;
        return new;
    end if;

    if not admin then
        if new.payment_status is distinct from old.payment_status then
            if lower(coalesce(old.payment_method, '')) <> 'qris' then
                raise exception 'Hanya pesanan QRIS yang bisa dikonfirmasi bayar.';
            end if;
            if not (old.payment_status in ('unpaid', 'rejected')
                    and new.payment_status = 'waiting_verification') then
                raise exception 'Perubahan pembayaran tidak diizinkan.';
            end if;
        end if;
        if new.status is distinct from old.status
            or new.total_idr is distinct from old.total_idr
            or new.payment_method is distinct from old.payment_method
            or new.user_id is distinct from old.user_id
            or new.pickup_code is distinct from old.pickup_code
            or new.payment_reference is distinct from old.payment_reference
            or new.paid_at is distinct from old.paid_at
            or new.verified_by is distinct from old.verified_by then
            raise exception 'Hanya penjual yang boleh mengubah order.';
        end if;
        return new;
    end if;

    if not exists (
        select 1
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = new.id
           and s.owner_id = auth.uid()
    ) then
        raise exception 'Bukan order warungmu.';
    end if;
    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        new.paid_at := now();
        new.verified_by := auth.uid();
    end if;
    return new;
end;
$function$;

grant execute on function public.guard_order_mutation() to authenticated;

drop trigger if exists orders_guard on public.orders;
create trigger orders_guard
before insert or update on public.orders
for each row execute function public.guard_order_mutation();

-- 5) Seller scope: a seller only sees and manages orders containing items
--    from their own stall (replaces the previous see-all-admin policies).
drop policy if exists "Admins can read all orders" on public.orders;
create policy "Sellers read own-stall orders" on public.orders
for select to authenticated
using (
    public.is_admin()
    and exists (
        select 1
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = public.orders.id
           and s.owner_id = auth.uid()
    )
);

drop policy if exists "Admins can read all order items" on public.order_items;
create policy "Sellers read own-stall order items" on public.order_items
for select to authenticated
using (
    public.is_admin()
    and exists (
        select 1
          from public.menu_items mi
          join public.stalls s on s.id = mi.stall_id
         where mi.id = public.order_items.menu_item_id
           and s.owner_id = auth.uid()
    )
);

drop policy if exists "Admins can update order status" on public.orders;
create policy "Sellers update own-stall orders" on public.orders
for update to authenticated
using (
    public.is_admin()
    and exists (
        select 1
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = public.orders.id
           and s.owner_id = auth.uid()
    )
)
with check (
    public.is_admin()
    and exists (
        select 1
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = public.orders.id
           and s.owner_id = auth.uid()
    )
);

-- 6) Buyer verification path: owners may flip their own QRIS order to
--    waiting_verification. Column grant + trigger keep everything else locked.
drop policy if exists "Buyers request payment verification" on public.orders;
create policy "Buyers request payment verification" on public.orders
for update to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

revoke update on table public.orders from authenticated;
grant update (status, payment_status) on table public.orders to authenticated;

-- 7) Public QRIS bucket: sellers upload their QR image, buyers read it.
insert into storage.buckets (id, name, public)
values ('qris', 'qris', true)
on conflict (id) do nothing;

drop policy if exists "Public read qris" on storage.objects;
create policy "Public read qris" on storage.objects
for select using (bucket_id = 'qris');

drop policy if exists "Sellers manage qris" on storage.objects;
create policy "Sellers manage qris" on storage.objects
for all to authenticated
using (bucket_id = 'qris' and (owner = auth.uid() or public.is_admin()))
with check (bucket_id = 'qris' and (owner = auth.uid() or public.is_admin()));

commit;
