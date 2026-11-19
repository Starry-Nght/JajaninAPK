-- In-app Android OTA release metadata + public APK storage bucket.
-- Clients read the latest release metadata anonymously/authenticated;
-- only developers (public.is_developer()) can publish or modify releases.

create table if not exists public.app_releases (
    id bigserial primary key,
    version_code integer not null check (version_code > 0),
    version_name text not null check (char_length(trim(version_name)) > 0),
    apk_url text not null check (char_length(trim(apk_url)) > 0),
    changelog text not null default '',
    mandatory boolean not null default false,
    sha256 text,
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now()
);

create index if not exists idx_app_releases_version_code
    on public.app_releases (version_code desc, created_at desc);

alter table public.app_releases enable row level security;

drop policy if exists "Anyone can read app releases" on public.app_releases;
create policy "Anyone can read app releases"
    on public.app_releases
    for select
    to anon, authenticated
    using (true);

drop policy if exists "Developers insert app releases" on public.app_releases;
create policy "Developers insert app releases"
    on public.app_releases
    for insert
    to authenticated
    with check ((select public.is_developer()));

drop policy if exists "Developers update app releases" on public.app_releases;
create policy "Developers update app releases"
    on public.app_releases
    for update
    to authenticated
    using ((select public.is_developer()))
    with check ((select public.is_developer()));

drop policy if exists "Developers delete app releases" on public.app_releases;
create policy "Developers delete app releases"
    on public.app_releases
    for delete
    to authenticated
    using ((select public.is_developer()));

grant select on table public.app_releases to anon, authenticated;
grant insert, update, delete on table public.app_releases to authenticated;
grant usage, select on sequence public.app_releases_id_seq to authenticated;

-- Public storage bucket for APK binaries (direct HTTPS download without cookie issues)
insert into storage.buckets (id, name, public)
values ('app-updates', 'app-updates', true)
on conflict (id) do update set public = true;

drop policy if exists "Public read app updates" on storage.objects;
create policy "Public read app updates"
    on storage.objects
    for select
    to anon, authenticated
    using (bucket_id = 'app-updates');

drop policy if exists "Developers insert app updates" on storage.objects;
create policy "Developers insert app updates"
    on storage.objects
    for insert
    to authenticated
    with check (bucket_id = 'app-updates' and (select public.is_developer()));

drop policy if exists "Developers update app updates" on storage.objects;
create policy "Developers update app updates"
    on storage.objects
    for update
    to authenticated
    using (bucket_id = 'app-updates' and (select public.is_developer()))
    with check (bucket_id = 'app-updates' and (select public.is_developer()));

drop policy if exists "Developers delete app updates" on storage.objects;
create policy "Developers delete app updates"
    on storage.objects
    for delete
    to authenticated
    using (bucket_id = 'app-updates' and (select public.is_developer()));
