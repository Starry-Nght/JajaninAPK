-- 202611120001_fix_seller_order_and_menu_delete.sql
--
-- 1. Mengizinkan penjual (seller) dan developer menghapus pesanan warung mereka (orders & order_items).
-- 2. Memperbaiki guard_menu_delete agar pesanan aktif lama (>24 jam) tidak mengunci penghapusan menu.
-- 3. Memperbaiki guard_order_mutation agar order dengan item yang menu_item-nya sudah dihapus tetap bisa dikelola.

begin;

-- Policy DELETE pada orders untuk penjual (seller) dan developer
drop policy if exists "Sellers can delete stall orders" on public.orders;
create policy "Sellers can delete stall orders" on public.orders
for delete to authenticated
using (
    public.is_developer()
    or (
        public.is_seller() and (
            public.is_order_for_seller(id)
            or verified_by = auth.uid()
            or not exists (select 1 from public.order_items where order_id = orders.id)
            or exists (
                select 1 from public.stalls s where s.owner_id = auth.uid()
            )
        )
    )
    or (auth.uid() = user_id and status = 'pending')
);

-- Policy DELETE pada order_items untuk penjual dan developer
drop policy if exists "Sellers can delete order items" on public.order_items;
create policy "Sellers can delete order items" on public.order_items
for delete to authenticated
using (
    public.is_developer()
    or exists (
        select 1 from public.orders o
        where o.id = order_items.order_id
          and (
              public.is_developer()
              or (public.is_seller() and exists (
                  select 1 from public.stalls s where s.owner_id = auth.uid()
              ))
          )
    )
    or exists (
        select 1 from public.orders
        where orders.id = order_items.order_id
          and orders.user_id = auth.uid()
          and orders.status = 'pending'
    )
);

-- Perbaiki guard_menu_delete agar pesanan aktif lama (>24 jam) tidak mengunci penghapusan produk
create or replace function public.guard_menu_delete()
returns trigger
language plpgsql
security definer
set search_path to ''
as $func$
declare
    n integer := 0;
begin
    select count(distinct o.id) into n
      from public.order_items oi
      join public.orders o on o.id = oi.order_id
     where oi.menu_item_id = OLD.id
       and o.status in ('pending', 'confirmed', 'preparing', 'processing')
       and o.created_at >= (now() - interval '24 hours');
    if n > 0 then
        raise exception 'Menu "%" masih ada di % pesanan aktif hari ini. Selesaikan atau batalkan pesanan itu dulu.', OLD.name, n;
    end if;
    return old;
end;
$func$;

-- Perbaiki guard_order_mutation agar order dengan item yang menu_item-nya sudah dihapus tetap bisa di-update oleh seller
create or replace function public.guard_order_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $func$
declare
    admin boolean;
begin
    admin := public.is_admin();
    if admin and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;
    -- Daily reset (developer RPC below): allow active -> completed flip.
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

    -- Validasi order warung penjual
    if not (
        exists (
            select 1
              from public.order_items oi
              join public.menu_items mi on mi.id = oi.menu_item_id
              join public.stalls s on s.id = mi.stall_id
             where oi.order_id = new.id
               and s.owner_id = auth.uid()
        )
        or exists (
            select 1 from public.stalls s where s.owner_id = auth.uid()
        )
    ) then
        raise exception 'Bukan order warungmu.';
    end if;

    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        new.paid_at := now();
        new.verified_by := auth.uid();
    end if;
    return new;
end;
$func$;

commit;
