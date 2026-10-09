-- Seller dashboard: ingredients, payment, confirmed status and updated_at.
alter table public.menu_items
    add column if not exists ingredients text not null default '';
alter table public.menu_items
    add column if not exists updated_at timestamptz not null default now();

alter table public.orders
    add column if not exists payment_method text;

-- Extend status enum so the Seller can do Pending → Confirmed → Preparing → Ready → Completed / Cancelled.
-- Keep 'processing' for backwards compatibility with old rows.
alter table public.orders drop constraint if exists orders_status_check;
alter table public.orders add constraint orders_status_check
    check (status in ('pending','confirmed','preparing','processing','ready','completed','cancelled'));

-- Update one-shot legacy 'processing' rows from the previous hardening migration to the more explicit 'confirmed'.
-- If the DB already on the new enum this is a no-op for other statuses.
update public.orders set status = 'confirmed' where status = 'processing';

drop trigger if exists menu_items_touch on public.menu_items;
create trigger menu_items_touch
    before update on public.menu_items
    for each row execute function public.touch_updated_at();
