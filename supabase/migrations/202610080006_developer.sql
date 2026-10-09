-- 202610080006_developer.sql
-- Developer role: platform monitoring, warnings, announcements, restrictions.
-- Rules: buyer/seller UI untouched in behavior; developer is READ-ONLY on
-- orders/finance; no permanent user deletes; enforcement lives in DB (RLS +
-- triggers), never trusted from the Android client.

begin;

-- 1) profiles: developer role + restriction status + warning counter.
alter table public.profiles drop constraint if exists profiles_role_check;
alter table public.profiles add constraint profiles_role_check
    check (role in ('buyer', 'seller', 'developer'));

alter table public.profiles
    add column if not exists account_status text not null default 'active',
    add column if not exists warning_count integer not null default 0;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'profiles_status_check'
    ) then
        alter table public.profiles add constraint profiles_status_check
            check (account_status in ('active', 'warned', 'suspended'));
    end if;
end $$;

-- 2) Helper predicates (SECURITY DEFINER so policies never recurse into RLS).
create or replace function public.is_developer()
returns boolean
language sql
security definer
stable
set search_path = ''
as $$
    select exists (
        select 1 from public.profiles
        where id = auth.uid() and role = 'developer'
    );
$$;

create or replace function public.my_role()
returns text
language sql
security definer
stable
set search_path = ''
as $$
    select role from public.profiles where id = auth.uid() limit 1;
$$;

create or replace function public.i_am_suspended()
returns boolean
language sql
security definer
stable
set search_path = ''
as $$
    select exists (
        select 1 from public.profiles
        where id = auth.uid() and account_status = 'suspended'
    );
$$;

grant execute on function public.is_developer() to authenticated;
grant execute on function public.my_role() to authenticated;
grant execute on function public.i_am_suspended() to authenticated;

-- 3) Warnings table (one row per targeted user).
create table if not exists public.developer_warnings (
    id uuid primary key default gen_random_uuid(),
    target_user_id uuid not null references auth.users(id) on delete cascade,
    created_by uuid references auth.users(id) on delete set null,
    title text not null,
    message text not null default '',
    severity text not null default 'info'
        check (severity in ('info', 'warning', 'critical')),
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);
create index if not exists dev_warnings_target_idx
    on public.developer_warnings(target_user_id, created_at desc);

-- 4) Announcements table (broadcast by role target).
create table if not exists public.developer_announcements (
    id uuid primary key default gen_random_uuid(),
    created_by uuid references auth.users(id) on delete set null,
    title text not null,
    message text not null default '',
    target_role text not null default 'all'
        check (target_role in ('all', 'buyers', 'sellers')),
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    is_active boolean not null default true
);
create index if not exists dev_announcements_active_idx
    on public.developer_announcements(is_active, created_at desc);

alter table public.developer_warnings enable row level security;
alter table public.developer_announcements enable row level security;

grant select, insert, update on public.developer_warnings to authenticated;
grant select, insert, update on public.developer_announcements to authenticated;

-- Developers: full manage on both tables.
drop policy if exists "Developers manage warnings" on public.developer_warnings;
create policy "Developers manage warnings" on public.developer_warnings
for all to authenticated
using (public.is_developer())
with check (public.is_developer());

-- Users: read-only on their own warnings (targeted rows only).
drop policy if exists "Users read own warnings" on public.developer_warnings;
create policy "Users read own warnings" on public.developer_warnings
for select to authenticated
using (auth.uid() = target_user_id);

-- Users: mark their own warnings read (nothing else may change).
drop policy if exists "Users mark own warnings read" on public.developer_warnings;
create policy "Users mark own warnings read" on public.developer_warnings
for update to authenticated
using (auth.uid() = target_user_id)
with check (auth.uid() = target_user_id);

-- Developers: full manage on announcements.
drop policy if exists "Developers manage announcements" on public.developer_announcements;
create policy "Developers manage announcements" on public.developer_announcements
for all to authenticated
using (public.is_developer())
with check (public.is_developer());

-- Users: read active, unexpired announcements targeted at them.
drop policy if exists "Users read announcements" on public.developer_announcements;
create policy "Users read announcements" on public.developer_announcements
for select to authenticated
using (
    is_active
    and (expires_at is null or expires_at > now())
    and (
        target_role = 'all'
        or (target_role = 'buyers' and public.my_role() = 'buyer')
        or (target_role = 'sellers' and public.my_role() = 'seller')
    )
);

-- Column-level lock: owners may only flip is_read on their own warning rows.
-- (Insert of warnings is developer-only via RLS above; update of other
-- columns by non-developers is rejected by the trigger below.)
create or replace function public.guard_warning_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
    if public.is_developer() then
        return new;
    end if;
    if TG_OP = 'INSERT' then
        raise exception 'Hanya developer yang dapat mengirim peringatan.';
    end if;
    if new.target_user_id is distinct from old.target_user_id
        or new.title is distinct from old.title
        or new.message is distinct from old.message
        or new.severity is distinct from old.severity
        or new.created_by is distinct from old.created_by
        or new.created_at is distinct from old.created_at then
        raise exception 'Peringatan hanya dapat ditandai dibaca.';
    end if;
    return new;
end;
$function$;

grant execute on function public.guard_warning_mutation() to authenticated;

drop trigger if exists dev_warnings_guard on public.developer_warnings;
create trigger dev_warnings_guard
before insert or update on public.developer_warnings
for each row execute function public.guard_warning_mutation();

-- 5) Developer monitoring: platform-wide READ on profiles/stalls/menu/orders.
drop policy if exists "Developers read all profiles" on public.profiles;
create policy "Developers read all profiles" on public.profiles
for select to authenticated using (public.is_developer());

drop policy if exists "Developers read all orders" on public.orders;
create policy "Developers read all orders" on public.orders
for select to authenticated using (public.is_developer());

drop policy if exists "Developers read all order items" on public.order_items;
create policy "Developers read all order items" on public.order_items
for select to authenticated using (public.is_developer());

drop policy if exists "Developers read all stalls" on public.stalls;
create policy "Developers read all stalls" on public.stalls
for select to authenticated using (public.is_developer());

drop policy if exists "Developers read all menu items" on public.menu_items;
create policy "Developers read all menu items" on public.menu_items
for select to authenticated using (public.is_developer());

-- Developer stall visibility toggle (open/close only; ownership untouched).
drop policy if exists "Developers toggle stalls" on public.stalls;
create policy "Developers toggle stalls" on public.stalls
for update to authenticated
using (public.is_developer())
with check (public.is_developer());

create or replace function public.guard_stall_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
    if public.is_developer() then
        if new.id is distinct from old.id
            or new.owner_id is distinct from old.owner_id
            or new.name is distinct from old.name then
            raise exception 'Developer hanya boleh mengubah visibilitas warung.';
        end if;
        return new;
    end if;
    return new;
end;
$function$;

grant execute on function public.guard_stall_mutation() to authenticated;

drop trigger if exists stalls_guard on public.stalls;
create trigger stalls_guard
before update on public.stalls
for each row execute function public.guard_stall_mutation();

revoke update on table public.stalls from authenticated;
grant update (is_open, description, location, qris_image_url) on table public.stalls to authenticated;

-- 6) Restriction management: developers update account_status/warning_count.
-- protect_profile_role previously blocked ALL app-side profile writes; it now
-- allows developers to change restriction columns while still blocking role
-- self-promotion for everyone (including developers).
create or replace function public.protect_profile_role()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
    if new.role is distinct from old.role then
        raise exception 'role tidak dapat diubah dari aplikasi';
    end if;
    if (new.account_status is distinct from old.account_status
        or new.warning_count is distinct from old.warning_count) then
        if not public.is_developer() then
            raise exception 'Hanya developer yang dapat mengubah status akun.';
        end if;
    end if;
    return new;
end;
$function$;

drop policy if exists "Developers manage restrictions" on public.profiles;
create policy "Developers manage restrictions" on public.profiles
for update to authenticated
using (public.is_developer())
with check (public.is_developer());

revoke update on table public.profiles from authenticated;
grant update (display_name, account_status, warning_count) on table public.profiles to authenticated;

-- 7) Suspension enforcement (server-side, cannot be bypassed by client).
-- Suspended buyer: place_order refuses to create new orders.
-- Suspended seller: menu_items writes + order status/payment writes refused.
create or replace function public.guard_menu_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
    if public.is_developer() then
        raise exception 'Developer tidak mengelola produk.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;
    -- DELETE tidak punya NEW (NULL); trigger BEFORE DELETE yang
    -- mengembalikan NULL diam-diam membatalkan penghapusan, jadi
    -- kembalikan OLD agar baris benar-benar terhapus.
    if TG_OP = 'DELETE' then
        return old;
    end if;
    return new;
end;
$function$;

grant execute on function public.guard_menu_mutation() to authenticated;

drop trigger if exists menu_items_guard on public.menu_items;
create trigger menu_items_guard
before insert or update or delete on public.menu_items
for each row execute function public.guard_menu_mutation();

-- Extend existing order guard: suspended sellers cannot touch orders at all.
-- (Buyer suspension is enforced in place_order below; buyer verification path
-- keeps working only for non-suspended accounts.)
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
    if exists (select 1 from public.profiles p where p.id = uid and p.account_status = 'suspended') then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
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

-- 8) Realtime for developer + user inboxes.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'developer_warnings'
    ) then
        alter publication supabase_realtime add table public.developer_warnings;
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'developer_announcements'
    ) then
        alter publication supabase_realtime add table public.developer_announcements;
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'profiles'
    ) then
        alter publication supabase_realtime add table public.profiles;
    end if;
exception when others then
    raise warning 'realtime publication setup skipped: %', sqlerrm;
end $$;

commit;

-- 9) Suspended sellers cannot accept/manage orders either (trigger-level,
--    so no client can bypass it). Appends to the guard from 202610080004.
begin;
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
    if admin and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
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

commit;
