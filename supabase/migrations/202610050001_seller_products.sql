alter table public.stalls
    add column if not exists owner_id uuid references auth.users(id) on delete cascade;

alter table public.menu_items
    add column if not exists stock integer check (stock is null or stock >= 0);

drop policy if exists "Open stalls are public" on public.stalls;
create policy "Open or seller-owned stalls are readable" on public.stalls
for select to anon, authenticated
using (is_open or auth.uid() = owner_id);

drop policy if exists "Available menu items are public" on public.menu_items;
create policy "Available menu items are public" on public.menu_items
for select to anon, authenticated
using (is_available or exists (
    select 1 from public.stalls
    where stalls.id = menu_items.stall_id and stalls.owner_id = auth.uid()
));

create policy "Sellers create their stalls" on public.stalls
for insert to authenticated
with check (auth.uid() = owner_id);

create policy "Sellers update their stalls" on public.stalls
for update to authenticated
using (auth.uid() = owner_id)
with check (auth.uid() = owner_id);

create policy "Sellers manage their menu items" on public.menu_items
for all to authenticated
using (exists (
    select 1 from public.stalls
    where stalls.id = menu_items.stall_id and stalls.owner_id = auth.uid()
))
with check (exists (
    select 1 from public.stalls
    where stalls.id = menu_items.stall_id and stalls.owner_id = auth.uid()
));

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'menu_items'
    ) then
        alter publication supabase_realtime add table public.menu_items;
    end if;
exception when undefined_object then
    create publication supabase_realtime;
    alter publication supabase_realtime add table public.menu_items;
end $$;
