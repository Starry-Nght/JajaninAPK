-- 202610080005_fix_rls_recursion.sql
--
-- Memperbaiki error 500 "infinite recursion detected in policy" (42P17) yang
-- merusak SEMUA akses order/order_items untuk user asli via PostgREST.
--
-- Akar masalah: tiga policy dari 202610080004 memakai subquery EXISTS langsung
-- ke tabel ber-RLS lain (orders <-> order_items saling merujuk), sehingga
-- evaluasi policy tidak pernah berakhir. Penjual sudah dicover policy lama
-- berbasis fungsi SECURITY DEFINER (is_order_for_seller, tanpa rekursi),
-- jadi ketiga policy ini dihapus tanpa kehilangan cakupan.
--
-- Aturan ke depan: policy TIDAK BOLEH membaca tabel ber-RLS lain secara
-- langsung; selalu lewat fungsi SECURITY DEFINER.

drop policy if exists "Sellers read own-stall orders" on public.orders;
drop policy if exists "Sellers update own-stall orders" on public.orders;
drop policy if exists "Sellers read own-stall order items" on public.order_items;
