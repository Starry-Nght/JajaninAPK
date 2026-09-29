create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    email text,
    role text not null default 'buyer' check (role in ('buyer', 'seller')),
    created_at timestamptz not null default now()
);

create table if not exists public.stalls (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    description text not null default '',
    location text not null default '',
    is_open boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists public.menu_items (
    id uuid primary key default gen_random_uuid(),
    stall_id uuid not null references public.stalls(id) on delete cascade,
    name text not null,
    description text not null default '',
    category text not null default 'Makanan',
    price_idr integer not null check (price_idr >= 0),
    image_url text,
    is_available boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists public.orders (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete restrict,
    status text not null default 'pending' check (status in ('pending', 'paid', 'preparing', 'ready', 'completed', 'cancelled')),
    total_idr integer not null check (total_idr >= 0),
    pickup_code text not null unique default upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 6)),
    created_at timestamptz not null default now()
);

create table if not exists public.order_items (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null references public.orders(id) on delete cascade,
    menu_item_id uuid references public.menu_items(id) on delete set null,
    item_name text not null,
    unit_price_idr integer not null check (unit_price_idr >= 0),
    quantity integer not null check (quantity > 0)
);

create index if not exists menu_items_stall_id_idx on public.menu_items(stall_id);
create index if not exists orders_user_created_idx on public.orders(user_id, created_at desc);
create index if not exists order_items_order_id_idx on public.order_items(order_id);

alter table public.profiles enable row level security;
alter table public.stalls enable row level security;
alter table public.menu_items enable row level security;
alter table public.orders enable row level security;
alter table public.order_items enable row level security;

create policy "Profiles are readable by their owner" on public.profiles for select to authenticated using (auth.uid() = id);
create policy "Users can create their profile" on public.profiles for insert to authenticated with check (auth.uid() = id);
create policy "Users can update their profile" on public.profiles for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

create policy "Open stalls are public" on public.stalls for select to anon, authenticated using (is_open);
create policy "Available menu items are public" on public.menu_items for select to anon, authenticated using (is_available);

create policy "Users can read their orders" on public.orders for select to authenticated using (auth.uid() = user_id);
create policy "Users can create their orders" on public.orders for insert to authenticated with check (auth.uid() = user_id);
create policy "Users can read items in their orders" on public.order_items for select to authenticated using (
    exists (select 1 from public.orders where orders.id = order_items.order_id and orders.user_id = auth.uid())
);
create policy "Users can add items to their orders" on public.order_items for insert to authenticated with check (
    exists (select 1 from public.orders where orders.id = order_items.order_id and orders.user_id = auth.uid())
);

create or replace function public.create_profile_for_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id, email, display_name)
    values (new.id, new.email, coalesce(new.raw_user_meta_data ->> 'name', new.raw_user_meta_data ->> 'full_name'))
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
for each row execute procedure public.create_profile_for_user();
