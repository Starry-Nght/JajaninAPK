-- ============================================================================
-- JAJANIN v2.22 — Clean Reset of All Stalls/NIS Activity & Official Verified+Dev Status for NIS 11855
-- Migration: 202611270004_v2_reset_clean_and_verify_nis_11855.sql
-- ============================================================================

DO $$
BEGIN
    PERFORM set_config('app.system_migration', 'on', true);
    PERFORM set_config('app.stall_verification_sync', 'on', true);
    PERFORM set_config('app.internal_stock_op', 'on', true);

    -- 1. Bersihkan seluruh riwayat pesanan, transaksi Midtrans, saldo, chat, menu uji coba, dan log
    --    (TANPA menghapus akun user, data NIS siswa, maupun entitas warung).
    DELETE FROM public.chat_messages;
    DELETE FROM public.conversations;
    DELETE FROM public.payment_transactions;
    DELETE FROM public.seller_ledger;
    DELETE FROM public.stall_verified_order_credits;
    DELETE FROM public.stall_verification_audit_logs;
    DELETE FROM public.order_items;
    DELETE FROM public.orders;
    DELETE FROM public.menu_items;
    DELETE FROM public.developer_warnings;
    DELETE FROM public.developer_announcements;

    -- 2. Reset seluruh profil akun & NIS ke kondisi bersih (0 peringatan, kuota/EXP di-reset)
    UPDATE public.profiles
       SET warning_count = 0,
           account_status = 'active',
           quota_reset_at = now(),
           nis = CASE
               WHEN nis IS NULL AND display_name LIKE '%[NIS:11855]%' THEN '11855'
               WHEN nis IS NULL AND display_name LIKE '%[NIS:11883]%' THEN '11883'
               ELSE nis
           END;

    -- 3. Reset seluruh warung ke kondisi bersih (0 pesanan selesai),
    --    dan berikan status Verified permanen khusus untuk akun NIS 11855.
    UPDATE public.stalls s
       SET completed_orders_count = 0,
           qris_image_url = NULL,
           public_bio = '',
           is_open = true,
           verification_revoked = false,
           verification_revoked_by = NULL,
           verification_revoked_at = NULL,
           verification_revoked_reason = NULL,
           is_verified = EXISTS (
               SELECT 1
                 FROM public.profiles p
                WHERE p.id = s.owner_id
                  AND (p.nis = '11855' OR p.display_name LIKE '%11855%')
           ),
           verified_at = CASE
               WHEN EXISTS (
                   SELECT 1
                     FROM public.profiles p
                    WHERE p.id = s.owner_id
                      AND (p.nis = '11855' OR p.display_name LIKE '%11855%')
               ) THEN now()
               ELSE NULL
           END;
END $$;
