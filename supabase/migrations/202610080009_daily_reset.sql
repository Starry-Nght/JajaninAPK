-- 202610080009_daily_reset.sql
--
-- Developer-only "Reset harian" for the monitoring dashboard.
-- One RPC closes the day without deleting history:
--   1. Completes all ACTIVE orders (pending/confirmed/preparing/processing/
--      ready) -> 'completed', freeing per-stall capacity (max 10 active).
--   2. Clears developer_warnings + developer_announcements monitoring inbox.
--   3. Resets profiles.warning_count to 0 and warned -> active
--      (suspended accounts are left untouched on purpose).
--
-- History is preserved: completed/cancelled orders stay for finance,
-- "Hari ini" counters naturally restart at 00:00 WIB (WIB calendar day).
-- Enforcement lives in DB: only profiles.role = 'developer' may execute.

begin;

-- Allow the reset function to complete orders despite the seller-only
-- guard_order_mutation trigger: when app.daily_reset_active = 'on',
-- the guard lets the status flip to completed through.
create or replace function public.guard_order_mutation()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
declare
    admin boolean;
begin
    admin := public.is_admin();
    if admin and public.i_am_suspended() then
        raise exception 'Akun ditangguhkan. Hubungi developer Jajanin.';
    end if;
    -- Daily reset (developer RPC below): allow active -> completed flip.
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
        if new.payment_method is not null
           and lower(new.payment_method) not in ('cash', 'qris') then
            raise exception 'Metode bayar tidak valid.';
        end if;
        return new;
    end if;

    if not admin then
        if new.payment_status is distinct from old.payment_status then
            if lower(coalesce(old.payment_method, '')) <> 'qris' then
                raise exception 'Hanya pesanan QRIS yang bisa dikonfirmasi bayar.';
            end if;
            if not (old.payment_status in ('unpaid', 'rejected')
                    and new.payment_status = 'waiting_verification') then
                raise exception 'Perubahan pembayaran tidak diizinkan.';
            end if;
        end if;
        if new.status is distinct from old.status
            or new.total_idr is distinct from old.total_idr
            or new.payment_method is distinct from old.payment_method
            or new.user_id is distinct from old.user_id
            or new.pickup_code is distinct from old.pickup_code
            or new.payment_reference is distinct from old.payment_reference
            or new.paid_at is distinct from old.paid_at
            or new.verified_by is distinct from old.verified_by then
            raise exception 'Hanya penjual yang boleh mengubah order.';
        end if;
        return new;
    end if;

    if not exists (
        select 1
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
          join public.stalls s on s.id = mi.stall_id
         where oi.order_id = new.id
           and s.owner_id = auth.uid()
    ) then
        raise exception 'Bukan order warungmu.';
    end if;
    if new.payment_status = 'paid' and old.payment_status is distinct from 'paid' then
        new.paid_at := now();
        new.verified_by := auth.uid();
    end if;
    return new;
end;
$function$;

-- Developers need delete on monitoring tables for the reset path
-- (normal app code never deletes; only this RPC does, server-side).
grant delete on public.developer_warnings to authenticated;
grant delete on public.developer_announcements to authenticated;

drop policy if exists "Developers delete warnings" on public.developer_warnings;
create policy "Developers delete warnings" on public.developer_warnings
for delete to authenticated using (public.is_developer());

drop policy if exists "Developers delete announcements" on public.developer_announcements;
create policy "Developers delete announcements" on public.developer_announcements
for delete to authenticated using (public.is_developer());

create or replace function public.daily_reset_monitor()
returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
    v_orders integer := 0;
    v_warnings integer := 0;
    v_anns integer := 0;
    v_profiles integer := 0;
begin
    if not public.is_developer() then
        raise exception 'Hanya developer yang dapat mereset monitor.';
    end if;

    -- 1) Close all active orders -> completed (frees stall capacity).
    perform set_config('app.daily_reset_active', 'on', true);
    update public.orders o
       set status = 'completed'
     where o.status in ('pending', 'confirmed', 'preparing', 'processing', 'ready');
    get diagnostics v_orders = row_count;
    perform set_config('app.daily_reset_active', '', true);

    -- 2) Clear monitoring inbox (warnings + announcements history).
    delete from public.developer_warnings;
    get diagnostics v_warnings = row_count;
    delete from public.developer_announcements;
    get diagnostics v_anns = row_count;

    -- 3) Reset warning counters; warned -> active. Suspended stays suspended.
    update public.profiles p
       set warning_count = 0,
           account_status = case when p.account_status = 'warned' then 'active' else p.account_status end
     where p.warning_count <> 0 or p.account_status = 'warned';
    get diagnostics v_profiles = row_count;

    return jsonb_build_object(
        'completed_orders', v_orders,
        'cleared_warnings', v_warnings,
        'cleared_announcements', v_anns,
        'reset_profiles', v_profiles
    );
end;
$function$;

revoke execute on function public.daily_reset_monitor() from public, anon;
grant execute on function public.daily_reset_monitor() to authenticated;

commit;
