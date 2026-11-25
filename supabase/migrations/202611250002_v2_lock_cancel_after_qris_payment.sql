-- Jajanin: Hard anti-scam lock — Seller CANNOT cancel any order once paid
-- or once buyer has uploaded QRIS payment proof (waiting_verification / paid / rejected).
-- Buyer also cannot cancel once QRIS proof is uploaded / paid.

create or replace function public.guard_order_mutation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    wib_date date := (now() at time zone 'Asia/Jakarta')::date;
    order_seller boolean;
    v_item record;
    v_did_deduct boolean := false;
    v_proof_prefix constant text := 'https://yezxyhpczfnkcndfpuht.supabase.co/storage/v1/object/public/payment-proofs/';
begin
    if public.is_system_or_service_context() then
        if TG_OP = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    if uid is null then
        raise exception 'Sesi berakhir. Silakan masuk ulang.';
    end if;

    -- Internal sweep from expire_unpaid_qris_orders()
    if coalesce(current_setting('app.qris_expiry_sweep', true), '') = 'on'
       and TG_OP = 'UPDATE'
       and old.status = 'pending'
       and lower(coalesce(old.payment_method, '')) = 'qris'
       and old.payment_status in ('unpaid', 'rejected')
       and old.payment_expires_at is not null
       and old.payment_expires_at <= now()
       and new.status = 'cancelled'
       and new.user_id is not distinct from old.user_id
       and new.total_idr is not distinct from old.total_idr then
        if old.stock_deducted then
            update public.menu_items mi
               set stock = mi.stock + oi.quantity
              from public.order_items oi
             where oi.order_id = new.id
               and oi.menu_item_id = mi.id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
        return new;
    end if;

    -- Internal daily reset from developer
    if coalesce(current_setting('app.daily_reset_active', true), '') = 'on'
       and public.is_developer()
       and TG_OP = 'UPDATE'
       and old.status in ('pending', 'confirmed', 'preparing', 'processing', 'ready')
       and new.status = 'completed'
       and new.user_id is not distinct from old.user_id
       and new.total_idr is not distinct from old.total_idr then
        return new;
    end if;

    if TG_OP = 'INSERT' then
        new.payment_status := 'unpaid';
        new.paid_at := null;
        new.verified_by := null;
        new.payment_reference := null;
        new.payment_proof_url := null;
        if new.payment_method is not null
           and lower(new.payment_method) not in ('cash', 'qris') then
            raise exception 'Metode bayar tidak valid.';
        end if;
        if lower(coalesce(new.payment_method, '')) = 'qris' then
            new.stock_deducted := false;
            new.payment_expires_at := coalesce(new.payment_expires_at, now() + interval '10 minutes');
        else
            new.payment_expires_at := null;
        end if;
        return new;
    end if;

    order_seller := public.is_seller() and public.is_order_for_seller(new.id);

    if order_seller and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    -- ================= BUYER MUTATIONS =================
    if not order_seller then
        if old.user_id = uid and new.status = 'cancelled' then
            if old.order_mode = 'pre_order' then
                raise exception 'Pesanan Pre-Order tidak dapat dibatalkan oleh pembeli. Hubungi penjual lewat chat jika ada kendala.';
            end if;
            if old.payment_status in ('waiting_verification', 'paid')
               or (lower(coalesce(old.payment_method, '')) = 'qris'
                   and nullif(trim(coalesce(old.payment_proof_url, '')), '') is not null) then
                raise exception 'Pesanan yang sudah dibayar / mengunggah bukti QRIS tidak dapat dibatalkan.';
            end if;
        end if;

        if old.user_id = uid
           and old.order_mode <> 'pre_order'
           and old.status = 'pending'
           and new.status = 'cancelled'
           and new.total_idr is not distinct from old.total_idr
           and new.payment_method is not distinct from old.payment_method
           and new.user_id is not distinct from old.user_id
           and new.pickup_code is not distinct from old.pickup_code
           and new.paid_at is not distinct from old.paid_at
           and new.verified_by is not distinct from old.verified_by
           and new.stock_deducted is not distinct from old.stock_deducted then
            if old.stock_deducted then
                update public.menu_items mi
                   set stock = mi.stock + oi.quantity
                  from public.order_items oi
                 where oi.order_id = new.id
                   and oi.menu_item_id = mi.id
                   and mi.stock is not null
                   and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
            end if;
            new.stock_deducted := false;
            new.payment_expires_at := null;
            return new;
        end if;

        if old.user_id = uid and (
            new.payment_status is distinct from old.payment_status
            or new.payment_proof_url is distinct from old.payment_proof_url
        ) then
            if lower(coalesce(old.payment_method, '')) <> 'qris' then
                raise exception 'Hanya pesanan QRIS yang bisa mengunggah bukti bayar.';
            end if;
            if old.status <> 'pending' then
                raise exception 'Pesanan ini tidak lagi menunggu pembayaran.';
            end if;
            if not (old.payment_status in ('unpaid', 'rejected')
                    and new.payment_status = 'waiting_verification') then
                raise exception 'Perubahan status pembayaran tidak diizinkan.';
            end if;
            if old.payment_expires_at is not null and now() > old.payment_expires_at then
                raise exception 'Batas waktu bayar QRIS (10 menit) telah habis. Silakan buat pesanan baru.';
            end if;
            if new.payment_proof_url is null
               or trim(new.payment_proof_url) = ''
               or left(trim(new.payment_proof_url), char_length(v_proof_prefix)) <> v_proof_prefix then
                raise exception 'Wajib mengunggah foto bukti transfer QRIS terlebih dahulu.';
            end if;
            new.payment_proof_url := trim(new.payment_proof_url);
            new.payment_expires_at := null;
        end if;

        if new.status is distinct from old.status
            or new.total_idr is distinct from old.total_idr
            or new.payment_method is distinct from old.payment_method
            or new.user_id is distinct from old.user_id
            or new.pickup_code is distinct from old.pickup_code
            or new.payment_reference is distinct from old.payment_reference
            or new.paid_at is distinct from old.paid_at
            or new.verified_by is distinct from old.verified_by
            or new.stock_deducted is distinct from old.stock_deducted
            or (new.payment_expires_at is distinct from old.payment_expires_at
                and not (old.payment_status in ('unpaid', 'rejected') and new.payment_status = 'waiting_verification')) then
            raise exception 'Hanya penjual yang boleh mengubah order.';
        end if;
        return new;
    end if;

    -- ================= SELLER MUTATIONS =================
    -- 0. HARD ANTI-SCAM LOCK: Seller CANNOT cancel once paid OR once QRIS proof submitted
    if new.status = 'cancelled' and old.status is distinct from 'cancelled' then
        if old.payment_status = 'paid' then
            raise exception 'Pesanan yang sudah lunas tidak dapat dibatalkan oleh penjual.';
        end if;
        if lower(coalesce(old.payment_method, '')) = 'qris' then
            if old.payment_status = 'waiting_verification'
               or nullif(trim(coalesce(old.payment_proof_url, '')), '') is not null then
                raise exception 'Pembeli sudah mengunggah bukti bayar QRIS. Penjual tidak dapat membatalkan pesanan — silakan verifikasi lunas atau tolak bukti jika tidak valid.';
            end if;
            if old.payment_status = 'rejected' then
                raise exception 'Pesanan QRIS yang bukti bayarnya ditolak tidak dapat dibatalkan manual oleh penjual (menunggu bukti ulang atau otomatis kedaluwarsa 10 menit).';
            end if;
        end if;
    end if;

    -- 1. Seller verifies payment as PAID
    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        if old.status = 'cancelled' or new.status = 'cancelled' then
            raise exception 'Pesanan yang dibatalkan tidak bisa ditandai lunas.';
        end if;

        if not old.stock_deducted then
            for v_item in
                select oi.menu_item_id, oi.quantity, mi.name, mi.stock, mi.order_schedule
                  from public.order_items oi
                  join public.menu_items mi on mi.id = oi.menu_item_id
                 where oi.order_id = new.id
                 order by mi.id
                   for update of mi
            loop
                if v_item.stock is not null
                   and (old.order_mode = 'same_day' or v_item.order_schedule = 'next_day') then
                    if v_item.stock < v_item.quantity then
                        raise exception 'Stok % tidak mencukupi (tersisa %). Perbarui stok menu atau batalkan pesanan ini.',
                            v_item.name, v_item.stock;
                    end if;
                    update public.menu_items
                       set stock = stock - v_item.quantity,
                           updated_at = now()
                     where id = v_item.menu_item_id;
                    v_did_deduct := true;
                end if;
            end loop;
            new.stock_deducted := v_did_deduct;
        end if;

        new.paid_at := now();
        new.verified_by := uid;
        new.payment_expires_at := null;
    end if;

    -- 2. Seller rejects QRIS proof -> clear proof & give buyer fresh 10-min window
    if new.payment_status = 'rejected' and old.payment_status is distinct from 'rejected' then
        new.payment_proof_url := null;
        new.payment_expires_at := now() + interval '10 minutes';
    end if;

    -- 3. Seller advancing order status checks
    if new.status is distinct from old.status then
        if lower(coalesce(old.payment_method, '')) = 'qris'
           and new.status in ('confirmed', 'preparing', 'processing', 'ready', 'completed')
           and coalesce(new.payment_status, old.payment_status) <> 'paid' then
            raise exception 'Verifikasi bukti bayar QRIS dulu sebelum menerima atau memproses pesanan.';
        end if;

        if old.order_mode = 'pre_order'
           and new.status in ('preparing', 'processing', 'ready', 'completed')
           and old.pickup_date is not null
           and wib_date < old.pickup_date then
            raise exception 'Pesanan PO untuk % baru bisa diproses/disiapkan pada hari pengambilan.', old.pickup_date::text;
        end if;
    end if;

    -- 4. Seller cancels eligible order (Cash unpaid or QRIS unpaid with no proof)
    if new.status = 'cancelled' and old.status is distinct from 'cancelled' then
        if old.stock_deducted
           and coalesce(current_setting('app.skip_stock_restore', true), '') <> 'on' then
            update public.menu_items mi
               set stock = mi.stock + oi.quantity
              from public.order_items oi
             where oi.order_id = new.id
               and oi.menu_item_id = mi.id
               and mi.stock is not null
               and (old.order_mode = 'same_day' or mi.order_schedule = 'next_day');
        end if;
        new.stock_deducted := false;
        new.payment_expires_at := null;
    end if;

    return new;
end;
$$;

create or replace function public.cancel_seller_order_v2(
    p_order_id uuid,
    p_reason text,
    p_restore_stock boolean default true,
    p_mark_out_of_stock boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    v_reason text := trim(coalesce(p_reason, ''));
    v_order record;
    v_restore boolean := coalesce(p_restore_stock, true) and not coalesce(p_mark_out_of_stock, false);
begin
    if uid is null then
        raise exception 'Sesi berakhir. Silakan masuk ulang.';
    end if;
    if v_reason = '' then
        raise exception 'Alasan pembatalan wajib diisi.';
    end if;
    if not public.is_seller() or not public.is_order_for_seller(p_order_id) then
        raise exception 'Gagal membatalkan pesanan. Pastikan ini pesanan warungmu.';
    end if;
    if public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;

    select id, status, stall_id, stock_deducted, payment_method, payment_status, payment_proof_url
      into v_order
      from public.orders
     where id = p_order_id
       for update;
    if not found then
        raise exception 'Pesanan tidak ditemukan.';
    end if;
    if v_order.status in ('completed', 'cancelled') then
        raise exception 'Pesanan ini sudah selesai atau dibatalkan.';
    end if;

    -- Hard anti-scam lock
    if v_order.payment_status = 'paid' then
        raise exception 'Pesanan yang sudah lunas tidak dapat dibatalkan oleh penjual.';
    end if;
    if lower(coalesce(v_order.payment_method, '')) = 'qris' then
        if v_order.payment_status = 'waiting_verification'
           or nullif(trim(coalesce(v_order.payment_proof_url, '')), '') is not null then
            raise exception 'Pembeli sudah mengunggah bukti bayar QRIS. Penjual tidak dapat membatalkan pesanan — silakan verifikasi lunas atau tolak bukti jika tidak valid.';
        end if;
        if v_order.payment_status = 'rejected' then
            raise exception 'Pesanan QRIS yang bukti bayarnya ditolak tidak dapat dibatalkan manual oleh penjual (menunggu bukti ulang atau otomatis kedaluwarsa 10 menit).';
        end if;
    end if;

    if not v_restore then
        perform set_config('app.skip_stock_restore', 'on', true);
    end if;

    update public.orders
       set status = 'cancelled',
           payment_reference = v_reason,
           updated_at = now()
     where id = p_order_id;

    if coalesce(p_mark_out_of_stock, false) then
        update public.menu_items mi
           set stock = 0,
               updated_at = now()
          from public.order_items oi
         where oi.order_id = p_order_id
           and oi.menu_item_id = mi.id
           and mi.stock is not null
           and (v_order.stall_id is null or mi.stall_id = v_order.stall_id);
    end if;

    return jsonb_build_object(
        'ok', true,
        'order_id', p_order_id,
        'stock_restored', (v_order.stock_deducted and v_restore),
        'marked_out_of_stock', coalesce(p_mark_out_of_stock, false)
    );
end;
$$;
