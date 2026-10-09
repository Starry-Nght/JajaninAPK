-- 202610080011_fix_menu_delete.sql
--
-- BUG: guard_menu_mutation() diakhiri `return new`, padahal trigger
-- menu_items_guard mencakup DELETE. Untuk DELETE, NEW = NULL, dan
-- trigger BEFORE DELETE yang mengembalikan NULL diam-diam membatalkan
-- penghapusan baris (tanpa error). Akibat: seller tidak bisa hapus
-- produk, app menampilkan "Produk tidak dapat dihapus."
--
-- FIX: untuk TG_OP = 'DELETE', kembalikan OLD (baris yang dihapus).
-- Aturan lain tidak berubah: developer tetap dilarang mengelola produk,
-- akun suspended tetap diblokir.

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
    -- Kunci perbaikan: DELETE tidak punya NEW (NULL); kembalikan OLD
    -- supaya baris benar-benar terhapus. INSERT/UPDATE tetap return NEW.
    if TG_OP = 'DELETE' then
        return old;
    end if;
    return new;
end;
$function$;

commit;
