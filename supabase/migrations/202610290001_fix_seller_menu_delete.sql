-- 202610290001_fix_seller_menu_delete.sql
--
-- BUG: penjual tidak bisa menghapus produk. Trigger BEFORE DELETE yang
-- mengembalikan NULL membatalkan penghapusan secara diam-diam (PostgREST
-- membalas sukses tanpa baris terhapus), sehingga aplikasi menampilkan
-- "Produk gagal dihapus" padahal server tidak menghapus apa pun.
--
-- Perbaikan tahan-lama: guard penjual (developer/suspended) hanya dipasang
-- untuk INSERT dan UPDATE. DELETE tidak lagi melewati trigger BEFORE DELETE,
-- jadi tidak ada lagi jalur yang bisa mengembalikan NULL dan membatalkan
-- penghapusan. Fungsi guard tetap menangani DELETE dengan benar bila suatu
-- saat trigger dipasang ulang.
--
-- Idempoten: aman dijalankan berulang lewat Supabase SQL Editor.

begin;

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
    -- DELETE tidak punya NEW; kembalikan OLD agar baris benar-benar terhapus.
    if TG_OP = 'DELETE' then
        return old;
    end if;
    return new;
end;
$function$;

grant execute on function public.guard_menu_mutation() to authenticated;

-- Pasang ulang trigger TANPA DELETE: penghapusan produk tidak boleh
-- bergantung pada nilai balik trigger BEFORE DELETE.
drop trigger if exists menu_items_guard on public.menu_items;
create trigger menu_items_guard
before insert or update on public.menu_items
for each row execute function public.guard_menu_mutation();

-- Pastikan penjual tetap pemilik penuh atas menu warungnya.
drop policy if exists "Sellers manage their menu items" on public.menu_items;
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

grant select, insert, update, delete on table public.menu_items to authenticated;

commit;
