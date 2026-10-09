-- test_seller_overload.sql — verifikasi 202610080008_seller_overload.
-- Jalankan di Supabase SQL Editor SETELAH migrasi 202610080008 diterapkan,
-- di LUAR jam blackout (09:00-10:00, 12:00-13:00 WIB).
-- Aman diulang: semuanya dalam satu transaksi + ROLLBACK, tidak ada data
-- yang berubah. Hasil tiap case tampil sebagai NOTICE (PASS/FAIL).
--
-- CASE 4 (simultan, 1 slot tersisa) tidak bisa dibuat paralel dalam satu
-- skrip: ikuti langkah manual di bagian bawah file ini.

begin;

do $$
declare
    v_buyer uuid;
    v_other uuid;
    v_stall uuid;
    v_item uuid;
    v_stall2 uuid;
    v_item2 uuid;
    v_oid uuid;
    v_wib time := ((now() at time zone 'Asia/Jakarta'))::time;
    v_msg text;
begin
    if (v_wib >= time '09:00' and v_wib < time '10:00')
        or (v_wib >= time '12:00' and v_wib < time '13:00') then
        raise exception 'Tes dibatalkan: sedang blackout (jam server WIB %). Uji di luar 09-10 & 12-13 WIB.', v_wib;
    end if;

    -- Buyer segar: 0 order hari ini (agar daily-limit tidak mengganggu tes kapasitas).
    select p.id into v_buyer
      from public.profiles p
     where p.role = 'buyer'
       and not exists (
            select 1 from public.orders o
             where o.user_id = p.id
               and (o.created_at at time zone 'Asia/Jakarta')::date
                 = ((now() at time zone 'Asia/Jakarta'))::date
       )
     order by p.created_at limit 1;
    if v_buyer is null then
        raise exception 'Butuh 1 akun buyer dengan 0 order hari ini (WIB).';
    end if;
    select p.id into v_other from public.profiles p
     where p.id <> v_buyer order by p.created_at limit 1;
    if v_other is null then
        raise exception 'Butuh 2 akun pengguna berbeda untuk tes ini.';
    end if;

    -- Warung 1: punya item tersedia + 0 antrean aktif.
    select s.id, mi.id into v_stall, v_item
      from public.stalls s
      join public.menu_items mi on mi.stall_id = s.id
     where mi.is_available
       and not exists (
            select 1 from public.orders o
             where o.status in ('pending', 'confirmed', 'preparing', 'processing')
               and exists (
                    select 1 from public.order_items oi
                      join public.menu_items mi2 on mi2.id = oi.menu_item_id
                     where oi.order_id = o.id and mi2.stall_id = s.id
               )
       )
     order by s.created_at limit 1;
    if v_stall is null then
        raise exception 'Butuh 1 warung ber-item tersedia dengan 0 antrean aktif.';
    end if;
    -- Warung 2 (berbeda) untuk CASE 5.
    select s.id, mi.id into v_stall2, v_item2
      from public.stalls s
      join public.menu_items mi on mi.stall_id = s.id
     where mi.is_available and s.id <> v_stall
     order by s.created_at limit 1;
    if v_stall2 is null then
        raise exception 'Butuh 2 warung berbeda untuk CASE 5.';
    end if;

    -- Stok tak terbatas selama tes (di-rollback setelahnya).
    update public.menu_items set stock = null where id in (v_item, v_item2);

    -- Pura-pura sebagai buyer untuk RPC (tiga varian key lintas versi).
    perform set_config('request.jwt.claim.sub', v_buyer::text, true);
    perform set_config('request.jwt.claims', json_build_object('sub', v_buyer::text)::text, true);
    perform set_config('request.jwt.claim', json_build_object('sub', v_buyer::text)::text, true);

    -- Isi 9 antrean aktif warung 1 + 10 penuh warung 2 (insert langsung =
    -- bukan via RPC, jadi daily-limit buyer tidak tersentuh).
    for i in 1..9 loop
        insert into public.orders (user_id, status, total_idr, payment_method, payment_status)
        values (v_other, 'pending', 11000, 'cash', 'unpaid') returning id into v_oid;
        insert into public.order_items (order_id, menu_item_id, item_name, unit_price_idr, quantity)
        values (v_oid, v_item, 'Tes Kapasitas', 10000, 1);
    end loop;
    for i in 1..10 loop
        insert into public.orders (user_id, status, total_idr, payment_method, payment_status)
        values (v_other, 'pending', 11000, 'cash', 'unpaid') returning id into v_oid;
        insert into public.order_items (order_id, menu_item_id, item_name, unit_price_idr, quantity)
        values (v_oid, v_item2, 'Tes Kapasitas 2', 10000, 1);
    end loop;

    -- CASE 5a: warung 2 penuh -> order ke warung 2 DITOLAK.
    begin
        perform public.place_order(
            jsonb_build_array(jsonb_build_object('menu_item_id', v_item2, 'quantity', 1)), 'cash');
        raise notice 'CASE 5a FAIL: order ke warung penuh lolos (harus ditolak)';
    exception when others then
        if sqlerrm like '%Warung sedang penuh%' then
            raise notice 'CASE 5a PASS: warung penuh menolak (%).', left(sqlerrm, 60);
        else
            raise notice 'CASE 5a FAIL (pesan salah): %', sqlerrm;
        end if;
    end;

    -- CASE 1 + 5b: warung 1 masih 9 -> order DITERIMA (independen dari warung 2).
    begin
        perform public.place_order(
            jsonb_build_array(jsonb_build_object('menu_item_id', v_item, 'quantity', 1)), 'cash');
        raise notice 'CASE 1/5b PASS: order ke-10 warung 1 diterima, warung 2 penuh tidak berpengaruh';
    exception when others then
        raise notice 'CASE 1/5b FAIL: %', sqlerrm;
    end;

    -- CASE 2: warung 1 kini 10 -> order berikutnya DITOLAK.
    begin
        perform public.place_order(
            jsonb_build_array(jsonb_build_object('menu_item_id', v_item, 'quantity', 1)), 'cash');
        raise notice 'CASE 2 FAIL: order ke-11 lolos (harus ditolak)';
    exception when others then
        if sqlerrm like '%Warung sedang penuh%' then
            raise notice 'CASE 2 PASS: order ke-11 ditolak dengan pesan kapasitas';
        else
            raise notice 'CASE 2 FAIL (pesan salah): %', sqlerrm;
        end if;
    end;

    -- CASE 3: satu order diselesaikan -> slot bebas -> order DITERIMA.
    -- (Trigger guard dimatikan sesaat: update langsung oleh postgres bukan
    -- aksi seller; semuanya di-rollback setelah tes.)
    alter table public.orders disable trigger orders_guard;
    update public.orders set status = 'completed'
     where id = (
        select o.id from public.orders o
         where o.status = 'pending'
           and exists (
                select 1 from public.order_items oi
                  join public.menu_items mi on mi.id = oi.menu_item_id
                 where oi.order_id = o.id and mi.stall_id = v_stall
           )
         limit 1
     );
    alter table public.orders enable trigger orders_guard;
    begin
        perform public.place_order(
            jsonb_build_array(jsonb_build_object('menu_item_id', v_item, 'quantity', 1)), 'cash');
        raise notice 'CASE 3 PASS: setelah 1 order completed, order baru diterima';
    exception when others then
        raise notice 'CASE 3 FAIL: %', sqlerrm;
    end;
end $$;

rollback;

-- =====================================================================
-- CASE 4 MANUAL: dua buyer simultan saat tersisa 1 slot.
-- 1. Siapkan warung dengan tepat 9 antrean aktif (seperti skrip di atas,
--    tapi COMMIT, atau sisakan 9 dari data nyata).
-- 2. Buka DUA tab SQL Editor. Di tiap tab, set claims buyer BERBEDA
--    (masing-masing 0 order hari ini):
--      select set_config('request.jwt.claim.sub', '<BUYER_A>', true);
--      select set_config('request.jwt.claim', json_build_object('sub','<BUYER_A>')::text, true);
-- 3. Jalankan BERSAMAAN di kedua tab:
--      select public.place_order(
--        jsonb_build_array(jsonb_build_object('menu_item_id','<ITEM-UUID>','quantity',1)), 'cash');
-- 4. HASIL HARUS: satu tab sukses, satu tab gagal 'Warung sedang penuh'.
--    (Kunci advisory per-stall membuat hitungan + insert atomik.)
-- 5. Bersihkan order tes bila memakai data nyata.
-- =====================================================================
