create or replace function public.is_admin()
returns boolean
language sql
security definer
stable
set search_path = ''
as $$
    select exists (
        select 1
        from public.profiles
        where id = auth.uid()
          and role = 'seller'
    );
$$;

grant execute on function public.is_admin() to authenticated;

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

grant execute on function public.touch_updated_at() to authenticated;

create table if not exists public.admin_devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    fcm_token text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, fcm_token)
);

alter table public.admin_devices enable row level security;

grant select, insert, update, delete on public.admin_devices to authenticated;

drop policy if exists "Owners manage their devices" on public.admin_devices;
create policy "Owners manage their devices" on public.admin_devices
for all to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop trigger if exists admin_devices_touch on public.admin_devices;
create trigger admin_devices_touch before update on public.admin_devices
for each row execute function public.touch_updated_at();

create index if not exists orders_created_at_idx on public.orders(created_at desc);

drop policy if exists "Admins can read all orders" on public.orders;
create policy "Admins can read all orders" on public.orders
for select to authenticated
using (public.is_admin());

drop policy if exists "Admins can update order status" on public.orders;
create policy "Admins can update order status" on public.orders
for update to authenticated
using (public.is_admin())
with check (public.is_admin());

drop policy if exists "Admins can read all order items" on public.order_items;
create policy "Admins can read all order items" on public.order_items
for select to authenticated
using (public.is_admin());

drop policy if exists "Admins can read all profiles" on public.profiles;
create policy "Admins can read all profiles" on public.profiles
for select to authenticated
using (public.is_admin());

do $$
begin
    create extension if not exists pg_net;
exception when others then
    raise warning 'pg_net could not be enabled: %', sqlerrm;
end $$;

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'orders'
    ) then
        alter publication supabase_realtime add table public.orders;
    end if;
exception when undefined_object then
    create publication supabase_realtime;
    alter publication supabase_realtime add table public.orders;
end $$;

create or replace function public.notify_new_order()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    target_secret text;
    target_url text;
    net_schema text;
    request_id bigint;
begin
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
        from pg_extension e
        join pg_namespace n on n.oid = e.extnamespace
        where e.extname = 'pg_net';

        if net_schema is null then
            return new;
        end if;

        target_url := nullif(current_setting('app.notify_function_url', true), '');
        if target_url is null then
            target_url := 'https://yezxyhpczfnkcndfpuht.supabase.co/functions/v1/notify-order';
        end if;

        execute format(
            'select %I.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 10000)',
            net_schema
        )
        into request_id
        using
            target_url,
            jsonb_build_object('order_id', new.id),
            jsonb_build_object(
                'x-notify-secret', target_secret,
                'Content-Type', 'application/json'
            );
    exception when others then
        raise warning 'notify_new_order failed for order %: %', new.id, sqlerrm;
    end;

    return new;
end;
$$;

grant execute on function public.notify_new_order() to authenticated;

drop trigger if exists orders_notify_admin on public.orders;
create trigger orders_notify_admin after insert on public.orders
for each row execute function public.notify_new_order();

-- Pemilik boleh menghapus ordernya sendiri selama masih 'pending'.
-- Dipakai OrderRepository.createOrder untuk membersihkan order yang itemnya
-- gagal disisipkan (tidak ada baris order_items tersisa pada jalur itu).
drop policy if exists "Owners can delete their pending orders" on public.orders;
create policy "Owners can delete their pending orders" on public.orders
for delete to authenticated
using (auth.uid() = user_id and status = 'pending');

-- Pemilik boleh menghapus item ordernya sendiri selama order masih 'pending';
-- mencakup kasus sebagian di mana item sempat tersimpan lalu pembersihan gagal.
drop policy if exists "Owners can delete items in their pending orders" on public.order_items;
create policy "Owners can delete items in their pending orders" on public.order_items
for delete to authenticated
using (
    exists (
        select 1 from public.orders
        where orders.id = order_items.order_id
          and orders.user_id = auth.uid()
          and orders.status = 'pending'
    )
);
