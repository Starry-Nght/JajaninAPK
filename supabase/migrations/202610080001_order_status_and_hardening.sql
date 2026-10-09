-- 202610080001: Perbaikan status enum order + pengerasan keamanan admin.
-- Diterapkan via Supabase MCP pada project yezxyhpczfnkcndfpuht.

begin;

-- 1) Status enum: aplikasi menulis 'processing' (bukan 'paid'/'preparing' yang
--    lama), dan menambahkan 'cancelled'.
alter table public.orders drop constraint if exists orders_status_check;
alter table public.orders add constraint orders_status_check check (
    status in ('pending', 'processing', 'ready', 'completed', 'cancelled')
);

-- 2) Kolom-level: authenticated HANYA boleh meng-update kolom status
--    (bukan total_idr / pickup_code / user_id). RLS "Admins can update order
--    status" tetap membatasi barisnya ke admin.
revoke update on table public.orders from authenticated;
grant update (status) on table public.orders to authenticated;

-- 3) admin_devices: hanya admin (role seller) yang boleh mendaftarkan/menghapus
--    device-nya. Sebelumnya setiap pemilik akun bisa menulis baris sendiri.
drop policy if exists "Owners manage their devices" on public.admin_devices;
drop policy if exists "Admins manage their devices" on public.admin_devices;
create policy "Admins manage their devices" on public.admin_devices
for all to authenticated
using (auth.uid() = user_id and public.is_admin())
with check (auth.uid() = user_id and public.is_admin());

-- 4) Trigger proteksi: siswa/pembeli tidak boleh mengubah role akunnya sendiri
--    menjadi seller (promosi diri). Perubahan role hanya dari backend/service.
create or replace function public.protect_profile_role()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
begin
    if new.role is distinct from old.role and auth.uid() is not null then
        raise exception 'role tidak dapat diubah dari aplikasi';
    end if;
    return new;
end;
$function$;

grant execute on function public.protect_profile_role() to authenticated;

drop trigger if exists profiles_protect_role on public.profiles;
create trigger profiles_protect_role
before update on public.profiles
for each row execute function public.protect_profile_role();

-- 5) RPC pembaca konfigurasi untuk Edge Function (fallback env var vault).
--    HANYA service_role yang boleh mengeksekusi (anon/authenticated tidak).
create or replace function public.edge_config(p_key text)
returns text
language plpgsql
security definer
set search_path to ''
as $function$
declare
    v text;
begin
    select secret into v
    from vault.decrypted_secrets
    where name = p_key
    order by created_at desc
    limit 1;
    return coalesce(v, '');
end;
$function$;

revoke execute on function public.edge_config(text) from public, anon, authenticated;
grant execute on function public.edge_config(text) to service_role;

-- 6) Secret autentikasi trigger -> Edge Function. Nilai acak 64-hex tanpa
--    karakter newline (newline di header HTTP membuat curl menolak request).
do $$
begin
    if not exists (
        select 1 from vault.decrypted_secrets where name = 'notify_secret'
    ) then
        perform vault.create_secret(
            encode(gen_random_bytes(32), 'hex'),
            'notify_secret',
            'Shared secret: orders trigger -> notify-order edge function'
        );
    end if;
end $$;

-- 7) pg_net harus tersedia (dipakai trigger notify_new_order).
do $$
begin
    create extension if not exists pg_net;
exception when others then
    raise warning 'pg_net could not be enabled: %', sqlerrm;
end $$;

commit;
