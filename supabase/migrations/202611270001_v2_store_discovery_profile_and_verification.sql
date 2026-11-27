-- Jajanin: Store Discovery, Public Store Profile & Automatic Store Verification (20+ Completed Orders)

-- 1. Extend public.stalls with public profile metadata & server-managed verification fields
alter table public.stalls
    add column if not exists logo_url text,
    add column if not exists banner_url text,
    add column if not exists store_category text not null default 'Makanan & Minuman',
    add column if not exists public_bio text not null default '',
    add column if not exists completed_orders_count integer not null default 0,
    add column if not exists is_verified boolean not null default false,
    add column if not exists verified_at timestamptz,
    add column if not exists verification_revoked boolean not null default false,
    add column if not exists verification_revoked_by uuid references auth.users(id) on delete set null,
    add column if not exists verification_revoked_at timestamptz,
    add column if not exists verification_revoked_reason text;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.stalls'::regclass
          and conname = 'stalls_completed_orders_count_chk'
    ) then
        alter table public.stalls
            add constraint stalls_completed_orders_count_chk
            check (completed_orders_count >= 0);
    end if;
end $$;

-- 2. Column-level privileges & RLS hardening on public.stalls
revoke insert, update, delete on public.stalls from anon;
grant select on public.stalls to authenticated;
grant update (logo_url, banner_url, store_category, public_bio) on public.stalls to authenticated;
revoke update (
    completed_orders_count,
    is_verified,
    verified_at,
    verification_revoked,
    verification_revoked_by,
    verification_revoked_at,
    verification_revoked_reason
) on public.stalls from authenticated, anon;

-- Allow buyers to view both Open and Closed public store profiles (excluding suspended accounts)
drop policy if exists "Stalls select policy" on public.stalls;
create policy "Stalls select policy"
    on public.stalls for select
    to authenticated
    using (
        owner_id = (select auth.uid())
        or (select public.is_developer())
        or not exists (
            select 1
              from public.profiles p
             where p.id = stalls.owner_id
               and p.account_status = 'suspended'
        )
    );

-- 3. Search & sorting indexes for store discovery and verification
create index if not exists idx_stalls_lower_name
    on public.stalls (lower(name));

create index if not exists idx_stalls_discovery_sort
    on public.stalls (is_open desc, is_verified desc, completed_orders_count desc, created_at desc);

create index if not exists idx_menu_items_stall_avail_cat
    on public.menu_items (stall_id, is_available, category);

create index if not exists idx_orders_stall_completed
    on public.orders (stall_id)
    where status = 'completed';

-- 4. Deduplicated order credit ledger & verification audit log
create table if not exists public.stall_verified_order_credits (
    order_id uuid primary key references public.orders(id) on delete cascade,
    stall_id uuid not null references public.stalls(id) on delete cascade,
    credited_at timestamptz not null default now()
);

create index if not exists idx_stall_verified_credits_stall
    on public.stall_verified_order_credits (stall_id);

alter table public.stall_verified_order_credits enable row level security;
revoke all on public.stall_verified_order_credits from public, anon, authenticated;
grant select on public.stall_verified_order_credits to authenticated;

drop policy if exists "Developers view stall order credits" on public.stall_verified_order_credits;
create policy "Developers view stall order credits"
    on public.stall_verified_order_credits for select
    to authenticated
    using ((select public.is_developer()));

create table if not exists public.stall_verification_audit_logs (
    id uuid primary key default gen_random_uuid(),
    stall_id uuid not null references public.stalls(id) on delete cascade,
    actor_id uuid references auth.users(id) on delete set null,
    action text not null check (action in ('auto_verified', 'developer_revoked', 'developer_restored')),
    reason text not null default '',
    completed_orders_snapshot integer not null default 0,
    created_at timestamptz not null default now()
);

create index if not exists idx_stall_verif_audit_stall
    on public.stall_verification_audit_logs (stall_id, created_at desc);

alter table public.stall_verification_audit_logs enable row level security;
revoke all on public.stall_verification_audit_logs from public, anon, authenticated;
grant select on public.stall_verification_audit_logs to authenticated;

drop policy if exists "Developers and owners view verification audit logs" on public.stall_verification_audit_logs;
create policy "Developers and owners view verification audit logs"
    on public.stall_verification_audit_logs for select
    to authenticated
    using (
        (select public.is_developer())
        or exists (
            select 1
              from public.stalls s
             where s.id = stall_verification_audit_logs.stall_id
               and s.owner_id = (select auth.uid())
        )
    );

-- 5. Harden guard_stall_mutation: block any manual tampering with verification columns
create or replace function public.guard_stall_mutation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if public.is_system_or_service_context() then
        if TG_OP = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    if TG_OP = 'DELETE' then
        return old;
    end if;

    if TG_OP = 'INSERT' then
        if coalesce(current_setting('app.stall_verification_sync', true), '') <> 'on' then
            if coalesce(new.completed_orders_count, 0) <> 0
               or coalesce(new.is_verified, false) = true
               or new.verified_at is not null
               or coalesce(new.verification_revoked, false) = true then
                raise exception 'Status verifikasi toko dikelola otomatis oleh server dan tidak dapat diubah manual.';
            end if;
        end if;
        new.completed_orders_count := 0;
        new.is_verified := false;
        new.verified_at := null;
        new.verification_revoked := false;
        new.verification_revoked_by := null;
        new.verification_revoked_at := null;
        new.verification_revoked_reason := null;
    elsif TG_OP = 'UPDATE' then
        if coalesce(current_setting('app.stall_verification_sync', true), '') <> 'on' then
            if new.completed_orders_count is distinct from old.completed_orders_count
               or new.is_verified is distinct from old.is_verified
               or new.verified_at is distinct from old.verified_at
               or new.verification_revoked is distinct from old.verification_revoked
               or new.verification_revoked_by is distinct from old.verification_revoked_by
               or new.verification_revoked_at is distinct from old.verification_revoked_at
               or new.verification_revoked_reason is distinct from old.verification_revoked_reason then
                raise exception 'Status verifikasi toko dikelola otomatis oleh server dan tidak dapat diubah manual.';
            end if;
        end if;

        if public.is_developer() and coalesce(current_setting('app.stall_verification_sync', true), '') <> 'on' then
            if new.id is distinct from old.id
               or new.owner_id is distinct from old.owner_id
               or new.name is distinct from old.name then
                raise exception 'Developer hanya boleh mengubah visibilitas warung.';
            end if;
            return new;
        end if;
    end if;

    -- Sanitize optional public store profile fields
    new.store_category := left(trim(coalesce(new.store_category, 'Makanan & Minuman')), 60);
    if new.store_category = '' then
        new.store_category := 'Makanan & Minuman';
    end if;
    new.public_bio := left(trim(coalesce(new.public_bio, '')), 500);

    if new.logo_url is not null and trim(new.logo_url) = '' then
        new.logo_url := null;
    end if;
    if new.logo_url is not null and left(trim(new.logo_url), 8) <> 'https://' then
        raise exception 'URL logo toko harus menggunakan https://';
    end if;

    if new.banner_url is not null and trim(new.banner_url) = '' then
        new.banner_url := null;
    end if;
    if new.banner_url is not null and left(trim(new.banner_url), 8) <> 'https://' then
        raise exception 'URL sampul toko harus menggunakan https://';
    end if;

    return new;
end;
$$;

revoke all on function public.guard_stall_mutation() from public, anon, authenticated;

drop trigger if exists stalls_guard on public.stalls;
create trigger stalls_guard
    before insert or update on public.stalls
    for each row
    execute function public.guard_stall_mutation();

-- 6. Automatic server-side verification trigger on orders reaching 'completed' (threshold = 20)
create or replace function public.sync_stall_verification_on_order_complete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_stall_id uuid;
    v_inserted integer := 0;
    v_total_completed integer := 0;
    v_stall record;
begin
    if new.status <> 'completed' then
        return new;
    end if;

    v_stall_id := new.stall_id;
    if v_stall_id is null then
        select mi.stall_id
          into v_stall_id
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
         where oi.order_id = new.id
           and mi.stall_id is not null
         limit 1;
    end if;

    if v_stall_id is null then
        return new;
    end if;

    -- Serialize concurrent completions for the same stall
    select id, is_verified, verified_at, verification_revoked
      into v_stall
      from public.stalls
     where id = v_stall_id
       for update;

    if not found then
        return new;
    end if;

    -- Exact-once deduplication by order_id primary key
    insert into public.stall_verified_order_credits (order_id, stall_id)
    values (new.id, v_stall_id)
    on conflict (order_id) do nothing;

    get diagnostics v_inserted = row_count;
    if v_inserted = 0 then
        return new;
    end if;

    select count(*)::integer
      into v_total_completed
      from public.stall_verified_order_credits c
     where c.stall_id = v_stall_id;

    perform set_config('app.stall_verification_sync', 'on', true);

    if v_total_completed >= 20 and not v_stall.is_verified and not v_stall.verification_revoked then
        update public.stalls
           set completed_orders_count = v_total_completed,
               is_verified = true,
               verified_at = coalesce(verified_at, now())
         where id = v_stall_id;

        insert into public.stall_verification_audit_logs (
            stall_id, actor_id, action, reason, completed_orders_snapshot
        ) values (
            v_stall_id, null, 'auto_verified', 'Mencapai minimal 20 pesanan selesai', v_total_completed
        );
    else
        update public.stalls
           set completed_orders_count = v_total_completed
         where id = v_stall_id;
    end if;

    return new;
end;
$$;

revoke all on function public.sync_stall_verification_on_order_complete() from public, anon, authenticated;

drop trigger if exists trg_sync_stall_verification_on_order on public.orders;
create trigger trg_sync_stall_verification_on_order
    after insert or update of status on public.orders
    for each row
    when (new.status = 'completed')
    execute function public.sync_stall_verification_on_order_complete();

-- Backfill historical completed orders idempotently
insert into public.stall_verified_order_credits (order_id, stall_id, credited_at)
select o.id,
       coalesce(
           o.stall_id,
           (
               select mi.stall_id
                 from public.order_items oi
                 join public.menu_items mi on mi.id = oi.menu_item_id
                where oi.order_id = o.id
                  and mi.stall_id is not null
                limit 1
           )
       ) as resolved_stall_id,
       coalesce(o.updated_at, o.created_at, now())
  from public.orders o
 where o.status = 'completed'
   and coalesce(
       o.stall_id,
       (
           select mi.stall_id
             from public.order_items oi
             join public.menu_items mi on mi.id = oi.menu_item_id
            where oi.order_id = o.id
              and mi.stall_id is not null
            limit 1
       )
   ) is not null
on conflict (order_id) do nothing;

do $$
declare
    r record;
begin
    perform set_config('app.stall_verification_sync', 'on', true);
    for r in
        select s.id,
               s.is_verified,
               s.verification_revoked,
               count(c.order_id)::integer as cnt
          from public.stalls s
          left join public.stall_verified_order_credits c on c.stall_id = s.id
         group by s.id, s.is_verified, s.verification_revoked
    loop
        update public.stalls
           set completed_orders_count = r.cnt,
               is_verified = case
                   when r.verification_revoked then false
                   when r.is_verified or r.cnt >= 20 then true
                   else false
               end,
               verified_at = case
                   when not r.verification_revoked and (r.is_verified or r.cnt >= 20)
                       then coalesce(verified_at, now())
                   else verified_at
               end
         where id = r.id;
    end loop;
end $$;

-- 7. Audited Developer Verification Revoke / Restore RPC
create or replace function public.dev_set_stall_verification(
    p_stall_id uuid,
    p_revoke boolean,
    p_reason text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    v_reason text := trim(coalesce(p_reason, ''));
    v_stall record;
    v_next_verified boolean;
begin
    if uid is null or not public.is_developer() then
        raise exception 'Hanya developer yang berwenang mengelola status verifikasi warung.';
    end if;
    if char_length(v_reason) < 3 then
        raise exception 'Alasan tindakan audit wajib diisi (minimal 3 karakter).';
    end if;

    select id, completed_orders_count, is_verified, verified_at, verification_revoked
      into v_stall
      from public.stalls
     where id = p_stall_id
       for update;

    if not found then
        raise exception 'Warung tidak ditemukan.';
    end if;

    perform set_config('app.stall_verification_sync', 'on', true);

    if coalesce(p_revoke, true) then
        v_next_verified := false;
        update public.stalls
           set is_verified = false,
               verification_revoked = true,
               verification_revoked_by = uid,
               verification_revoked_at = now(),
               verification_revoked_reason = v_reason
         where id = p_stall_id;

        insert into public.stall_verification_audit_logs (
            stall_id, actor_id, action, reason, completed_orders_snapshot
        ) values (
            p_stall_id, uid, 'developer_revoked', v_reason, v_stall.completed_orders_count
        );
    else
        v_next_verified := (v_stall.completed_orders_count >= 20);
        update public.stalls
           set is_verified = v_next_verified,
               verified_at = case when v_next_verified then coalesce(verified_at, now()) else verified_at end,
               verification_revoked = false,
               verification_revoked_by = null,
               verification_revoked_at = null,
               verification_revoked_reason = null
         where id = p_stall_id;

        insert into public.stall_verification_audit_logs (
            stall_id, actor_id, action, reason, completed_orders_snapshot
        ) values (
            p_stall_id, uid, 'developer_restored', v_reason, v_stall.completed_orders_count
        );
    end if;

    return jsonb_build_object(
        'ok', true,
        'stall_id', p_stall_id,
        'is_verified', v_next_verified,
        'verification_revoked', coalesce(p_revoke, true),
        'completed_orders_count', v_stall.completed_orders_count
    );
end;
$$;

revoke all on function public.dev_set_stall_verification(uuid, boolean, text) from public, anon;
grant execute on function public.dev_set_stall_verification(uuid, boolean, text) to authenticated;

-- 8. Sanitizers for public store metadata (never leak NIS / phone / email in store discovery)
create or replace function public.clean_public_person_name(p_raw text)
returns text
language sql
immutable
set search_path = ''
as $$
    select trim(
        regexp_replace(
            regexp_replace(coalesce(p_raw, ''), '\[[^\]]*\]', '', 'g'),
            '\(NIS:[^)]*\)', '', 'gi'
        )
    );
$$;

revoke all on function public.clean_public_person_name(text) from public, anon, authenticated;

create or replace function public.clean_public_stall_bio(p_public_bio text, p_legacy_desc text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_bio text := trim(coalesce(p_public_bio, ''));
    v_desc text := trim(coalesce(p_legacy_desc, ''));
begin
    if v_bio <> '' then
        return v_bio;
    end if;
    if v_desc = '' then
        return '';
    end if;
    -- Strip embedded internal contact metadata ("Pemilik: ... | HP: +62...")
    v_desc := regexp_replace(v_desc, '(^|\|\s*)Pemilik:\s*[^|]+', '', 'gi');
    v_desc := regexp_replace(v_desc, '(^|\|\s*)HP:\s*[+0-9 -]+', '', 'gi');
    v_desc := trim(both ' |·-' from v_desc);
    return v_desc;
end;
$$;

revoke all on function public.clean_public_stall_bio(text, text) from public, anon, authenticated;

-- 9. Public Store Discovery & Paginated Search RPC
create or replace function public.search_public_stores(
    p_query text default '',
    p_category text default 'All',
    p_limit integer default 12,
    p_offset integer default 0
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_q text := lower(trim(coalesce(p_query, '')));
    v_cat text := lower(trim(coalesce(p_category, 'All')));
    v_limit integer := greatest(1, least(coalesce(p_limit, 12), 50));
    v_offset integer := greatest(0, coalesce(p_offset, 0));
    v_total integer := 0;
    v_items jsonb := '[]'::jsonb;
begin
    if auth.uid() is null then
        raise exception 'Masuk terlebih dahulu untuk menjelajahi toko.';
    end if;

    with base as (
        select s.id,
               s.name,
               public.clean_public_stall_bio(s.public_bio, s.description) as bio,
               coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman') as store_category,
               case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end as location,
               s.is_open,
               s.is_verified,
               s.verified_at,
               s.completed_orders_count,
               s.logo_url,
               s.banner_url,
               s.created_at,
               public.clean_public_person_name(p.display_name) as seller_name,
               coalesce(ms.active_products, 0)::integer as active_product_count,
               coalesce(ms.total_products, 0)::integer as total_product_count,
               ms.min_price_idr,
               coalesce(ms.has_food, false) as has_food,
               coalesce(ms.has_drinks, false) as has_drinks
          from public.stalls s
          left join public.profiles p on p.id = s.owner_id
          left join lateral (
              select count(*) filter (where mi.is_available) as active_products,
                     count(*) as total_products,
                     min(mi.price_idr) filter (where mi.is_available) as min_price_idr,
                     bool_or(mi.is_available and lower(mi.category) in ('food', 'makanan')) as has_food,
                     bool_or(mi.is_available and lower(mi.category) in ('drinks', 'drink', 'minuman')) as has_drinks,
                     bool_or(v_q <> '' and (lower(mi.name) like '%' || v_q || '%' or lower(mi.description) like '%' || v_q || '%')) as menu_matches_query
                from public.menu_items mi
               where mi.stall_id = s.id
          ) ms on true
         where coalesce(p.account_status, 'active') <> 'suspended'
           and (
               v_q = ''
               or lower(s.name) like '%' || v_q || '%'
               or lower(coalesce(s.store_category, '')) like '%' || v_q || '%'
               or lower(public.clean_public_stall_bio(s.public_bio, s.description)) like '%' || v_q || '%'
               or coalesce(ms.menu_matches_query, false)
           )
           and (
               v_cat in ('', 'all', 'semua')
               or (v_cat in ('verified', 'terverifikasi') and s.is_verified)
               or (v_cat in ('open', 'buka') and s.is_open)
               or (v_cat in ('food', 'makanan') and (coalesce(ms.has_food, false) or lower(s.store_category) like '%makanan%'))
               or (v_cat in ('drinks', 'drink', 'minuman') and (coalesce(ms.has_drinks, false) or lower(s.store_category) like '%minuman%'))
               or lower(s.store_category) = v_cat
           )
    )
    select count(*)::integer into v_total from base;

    with base as (
        select s.id,
               s.name,
               public.clean_public_stall_bio(s.public_bio, s.description) as bio,
               coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman') as store_category,
               case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end as location,
               s.is_open,
               s.is_verified,
               s.verified_at,
               s.completed_orders_count,
               s.logo_url,
               s.banner_url,
               s.created_at,
               public.clean_public_person_name(p.display_name) as seller_name,
               coalesce(ms.active_products, 0)::integer as active_product_count,
               coalesce(ms.total_products, 0)::integer as total_product_count,
               ms.min_price_idr,
               coalesce(ms.has_food, false) as has_food,
               coalesce(ms.has_drinks, false) as has_drinks
          from public.stalls s
          left join public.profiles p on p.id = s.owner_id
          left join lateral (
              select count(*) filter (where mi.is_available) as active_products,
                     count(*) as total_products,
                     min(mi.price_idr) filter (where mi.is_available) as min_price_idr,
                     bool_or(mi.is_available and lower(mi.category) in ('food', 'makanan')) as has_food,
                     bool_or(mi.is_available and lower(mi.category) in ('drinks', 'drink', 'minuman')) as has_drinks,
                     bool_or(v_q <> '' and (lower(mi.name) like '%' || v_q || '%' or lower(mi.description) like '%' || v_q || '%')) as menu_matches_query
                from public.menu_items mi
               where mi.stall_id = s.id
          ) ms on true
         where coalesce(p.account_status, 'active') <> 'suspended'
           and (
               v_q = ''
               or lower(s.name) like '%' || v_q || '%'
               or lower(coalesce(s.store_category, '')) like '%' || v_q || '%'
               or lower(public.clean_public_stall_bio(s.public_bio, s.description)) like '%' || v_q || '%'
               or coalesce(ms.menu_matches_query, false)
           )
           and (
               v_cat in ('', 'all', 'semua')
               or (v_cat in ('verified', 'terverifikasi') and s.is_verified)
               or (v_cat in ('open', 'buka') and s.is_open)
               or (v_cat in ('food', 'makanan') and (coalesce(ms.has_food, false) or lower(s.store_category) like '%makanan%'))
               or (v_cat in ('drinks', 'drink', 'minuman') and (coalesce(ms.has_drinks, false) or lower(s.store_category) like '%minuman%'))
               or lower(s.store_category) = v_cat
           )
         order by s.is_open desc, s.is_verified desc, s.completed_orders_count desc, lower(s.name) asc
         limit v_limit offset v_offset
    )
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', b.id,
                'name', b.name,
                'bio', b.bio,
                'store_category', b.store_category,
                'location', b.location,
                'is_open', b.is_open,
                'is_verified', b.is_verified,
                'verified_at', b.verified_at,
                'completed_orders_count', b.completed_orders_count,
                'logo_url', b.logo_url,
                'banner_url', b.banner_url,
                'seller_name', b.seller_name,
                'active_product_count', b.active_product_count,
                'min_price_idr', b.min_price_idr
            )
        ),
        '[]'::jsonb
    ) into v_items
    from base b;

    return jsonb_build_object(
        'total', v_total,
        'has_more', (v_offset + jsonb_array_length(v_items) < v_total),
        'stores', v_items
    );
end;
$$;

revoke all on function public.search_public_stores(text, text, integer, integer) from public, anon;
grant execute on function public.search_public_stores(text, text, integer, integer) to authenticated;

-- 10. Public Store Profile Detail RPC (Store metadata + public menu items in 1 round-trip)
create or replace function public.get_public_store_profile(p_stall_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    v_stall jsonb;
    v_products jsonb := '[]'::jsonb;
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk melihat profil toko.';
    end if;
    if p_stall_id is null then
        raise exception 'Toko tidak ditemukan.';
    end if;

    select jsonb_build_object(
        'id', s.id,
        'name', s.name,
        'bio', public.clean_public_stall_bio(s.public_bio, s.description),
        'store_category', coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman'),
        'location', case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end,
        'is_open', s.is_open,
        'is_verified', s.is_verified,
        'verified_at', s.verified_at,
        'completed_orders_count', s.completed_orders_count,
        'logo_url', s.logo_url,
        'banner_url', s.banner_url,
        'has_qris', (nullif(trim(coalesce(s.qris_image_url, '')), '') is not null),
        'owner_id', s.owner_id,
        'seller_name', public.clean_public_person_name(p.display_name),
        'created_at', s.created_at
    )
      into v_stall
      from public.stalls s
      left join public.profiles p on p.id = s.owner_id
     where s.id = p_stall_id
       and (
           s.owner_id = uid
           or public.is_developer()
           or coalesce(p.account_status, 'active') <> 'suspended'
       );

    if v_stall is null then
        raise exception 'Toko tidak ditemukan atau sedang tidak aktif.';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', mi.id,
                'name', mi.name,
                'description', mi.description,
                'category', mi.category,
                'price_idr', mi.price_idr,
                'image_url', mi.image_url,
                'is_available', mi.is_available,
                'stock', mi.stock,
                'stall_id', mi.stall_id,
                'ingredients', mi.ingredients,
                'order_schedule', mi.order_schedule,
                'pickup_sessions', mi.pickup_sessions,
                'po_dates', mi.po_dates,
                'po_cutoff_hour', mi.po_cutoff_hour,
                'po_daily_quota', mi.po_daily_quota,
                'po_require_qris', mi.po_require_qris,
                'po_allow_weekend', mi.po_allow_weekend
            )
            order by mi.is_available desc,
                     (mi.stock is null or mi.stock > 0) desc,
                     mi.created_at desc
        ),
        '[]'::jsonb
    )
      into v_products
      from public.menu_items mi
     where mi.stall_id = p_stall_id
       and mi.is_available = true
       and mi.name <> ''
       and mi.price_idr > 0;

    return jsonb_build_object(
        'stall', v_stall,
        'products', v_products
    );
end;
$$;

revoke all on function public.get_public_store_profile(uuid) from public, anon;
grant execute on function public.get_public_store_profile(uuid) to authenticated;
