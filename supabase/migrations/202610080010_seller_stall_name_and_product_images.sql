-- 202610080010_seller_stall_name_and_product_images.sql
--
-- 1) Setiap penjual wajib punya nama toko/brand: kolom stalls.name boleh
--    di-update seller (sebelumnya grant update tidak mencakup `name`
--    setelah 202610080006), plus constraint minimal 3 karakter.
-- 2) Bucket publik `product-images`: upload gambar menu langsung dari
--    galeri (pengganti tempel URL). Pola sama seperti bucket `qris`.

begin;

-- Seller boleh mengubah nama warungnya sendiri (RLS "Sellers update their
-- stalls" sudah membatasi baris ke owner; ini menambahkan kolomnya).
grant update (name) on table public.stalls to authenticated;

-- Backfill nama yang terlalu pendek agar constraint baru tidak gagal.
update public.stalls
   set name = 'Kantin Penjual'
 where char_length(trim(both from coalesce(name, ''))) < 3;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'stalls_name_check'
    ) then
        alter table public.stalls add constraint stalls_name_check
            check (char_length(trim(both from name)) >= 3);
    end if;
end $$;

-- Bucket gambar produk (publik agar pembeli bisa melihat).
insert into storage.buckets (id, name, public)
values ('product-images', 'product-images', true)
on conflict (id) do nothing;

drop policy if exists "Public read product images" on storage.objects;
create policy "Public read product images" on storage.objects
for select using (bucket_id = 'product-images');

drop policy if exists "Sellers manage product images" on storage.objects;
create policy "Sellers manage product images" on storage.objects
for all to authenticated
using (bucket_id = 'product-images' and (owner = auth.uid() or public.is_admin()))
with check (bucket_id = 'product-images' and (owner = auth.uid() or public.is_admin()));

commit;
