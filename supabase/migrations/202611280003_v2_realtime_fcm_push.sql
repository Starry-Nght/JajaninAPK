-- JAJANIN v2.38: Real-Time FCM Push Notification Infrastructure (Buyer & Seller)
--
-- Fixes & enhancements:
-- 1. Upgrade `public.admin_devices` with device metadata (`platform`, `device_label`,
--    `last_seen_at`), unique index on `fcm_token`, and secure atomic RPC
--    `upsert_user_device_token` supporting multi-device per user while preventing
--    cross-account token leakage on shared phones.
-- 2. Add internal `public.push_notification_deliveries` table for server-side
--    deduplication (idempotency) and delivery auditing (service_role only via RLS).
-- 3. Fix critical Vault secret bug in Postgres push triggers: previously triggers
--    selected encrypted `secret` instead of plaintext `decrypted_secret` from
--    `vault.decrypted_secrets`, causing 100% HTTP 401 on `notify-order` and `notify-chat`.
-- 4. Skip `auto_reply` messages in `notify_chat_message()` to avoid duplicate pushes
--    when orders are placed or Midtrans payments settle.
-- 5. Add comprehensive DB triggers covering all Buyer, Seller, and Store Activity events:
--    - orders INSERT -> Buyer (`order_created`) + Stall Owner Seller (`new_order`)
--    - orders UPDATE (status / payment_status) -> Buyer & Seller status/cancel/payment updates
--    - chat_messages INSERT -> Recipient (`notify-chat`)
--    - stalls UPDATE (verification / admin visibility) -> Stall Owner (`store_activity`)
--    - developer_warnings INSERT -> Target user (`store_activity`)
--    - developer_announcements INSERT -> Target audience (`store_activity`)
--    - seller_reports INSERT / status UPDATE -> Developers / Reporter (`store_activity`)

-- ============================================================================
-- 1. Device Tokens Table Upgrade & Secure Atomic Upsert RPC
-- ============================================================================

alter table public.admin_devices
    add column if not exists platform text not null default 'android',
    add column if not exists device_label text not null default '',
    add column if not exists last_seen_at timestamptz not null default now();

create unique index if not exists idx_admin_devices_fcm_token_unique
    on public.admin_devices (fcm_token);

create or replace function public.upsert_user_device_token(
    p_fcm_token text,
    p_platform text default 'android',
    p_device_label text default ''
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_token text := trim(coalesce(p_fcm_token, ''));
    v_platform text := lower(left(trim(coalesce(nullif(p_platform, ''), 'android')), 24));
    v_label text := left(trim(coalesce(p_device_label, '')), 120);
begin
    if v_uid is null then
        raise exception 'Sesi tidak valid. Silakan masuk ulang.';
    end if;
    if length(v_token) < 20 or length(v_token) > 4096 then
        raise exception 'Token perangkat FCM tidak valid.';
    end if;
    if v_platform not in ('android', 'ios', 'web') then
        v_platform := 'android';
    end if;

    -- One user can have multiple rows (multi-device per account), while each physical
    -- fcm_token maps to at most one active user_id so switching accounts on one phone
    -- atomically transfers token ownership and prevents cross-account push leaks.
    insert into public.admin_devices (
        user_id,
        fcm_token,
        platform,
        device_label,
        updated_at,
        last_seen_at
    )
    values (
        v_uid,
        v_token,
        v_platform,
        v_label,
        now(),
        now()
    )
    on conflict (fcm_token)
    do update set
        user_id = excluded.user_id,
        platform = excluded.platform,
        device_label = case
            when excluded.device_label <> '' then excluded.device_label
            else public.admin_devices.device_label
        end,
        updated_at = now(),
        last_seen_at = now();
end;
$function$;

-- ============================================================================
-- 2. Server-Side Push Deduplication & Delivery Log (service_role only)
-- ============================================================================

create table if not exists public.push_notification_deliveries (
    event_key text primary key,
    event_type text not null,
    channel_id text not null,
    recipient_count integer not null default 0,
    sent_count integer not null default 0,
    failed_count integer not null default 0,
    removed_tokens integer not null default 0,
    status text not null default 'processing',
    detail text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_push_deliveries_created_at
    on public.push_notification_deliveries (created_at desc);

alter table public.push_notification_deliveries enable row level security;

do $$
begin
    if not exists (
        select 1
          from pg_policies
         where schemaname = 'public'
           and tablename = 'push_notification_deliveries'
           and policyname = 'Push deliveries deny client access'
    ) then
        create policy "Push deliveries deny client access"
            on public.push_notification_deliveries
            for all
            to anon, authenticated
            using (false)
            with check (false);
    end if;
end
$$;

-- ============================================================================
-- 3. Internal Trigger Dispatcher (Fixes decrypted_secret Vault Bug)
-- ============================================================================

create or replace function public.dispatch_push_edge_function(
    p_function_name text,
    p_payload jsonb
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    target_secret text;
    base_url text;
    target_url text;
    net_schema text;
    request_id bigint;
begin
    if pg_trigger_depth() = 0 and not public.is_system_or_service_context() then
        raise exception 'Akses ditolak.';
    end if;
    if trim(coalesce(p_function_name, '')) = '' then
        return;
    end if;

    begin
        select trim(both from decrypted_secret)
          into target_secret
          from vault.decrypted_secrets
         where name = 'notify_secret'
         order by created_at desc
         limit 1;

        if target_secret is null or target_secret = '' then
            raise warning 'dispatch_push_edge_function: vault notify_secret is missing';
            return;
        end if;

        select n.nspname
          into net_schema
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where p.proname = 'http_post'
         limit 1;

        if net_schema is null then
            raise warning 'dispatch_push_edge_function: pg_net http_post not found';
            return;
        end if;

        base_url := nullif(trim(coalesce(current_setting('app.supabase_functions_base_url', true), '')), '');
        if base_url is null then
            base_url := 'https://yezxyhpczfnkcndfpuht.supabase.co/functions/v1';
        end if;
        target_url := rtrim(base_url, '/') || '/' || p_function_name;

        execute format(
            'select %I.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 10000)',
            net_schema
        )
        into request_id
        using
            target_url,
            coalesce(p_payload, '{}'::jsonb),
            jsonb_build_object(
                'x-notify-secret', target_secret,
                'Content-Type', 'application/json'
            );
    exception when others then
        raise warning 'dispatch_push_edge_function(%) failed: %', p_function_name, sqlerrm;
    end;
end;
$function$;

-- ============================================================================
-- 4. Order Triggers (INSERT + UPDATE)
-- ============================================================================

create or replace function public.notify_new_order()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    perform public.dispatch_push_edge_function(
        'notify-order',
        jsonb_build_object(
            'event_type', 'order_created',
            'order_id', new.id
        )
    );
    return new;
end;
$function$;

create or replace function public.notify_order_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_actor uuid := auth.uid();
    v_is_daily_reset boolean := coalesce(current_setting('app.daily_reset_active', true), '') = 'on';
    v_is_qris_sweep boolean := coalesce(current_setting('app.qris_expiry_sweep', true), '') = 'on';
    v_is_gateway_webhook boolean := coalesce(current_setting('app.gateway_webhook', true), '') = 'on';
    v_cancelled_by text := '';
begin
    if v_is_daily_reset then
        return new;
    end if;

    if new.status is distinct from old.status then
        if new.status = 'cancelled' then
            if v_is_qris_sweep or v_is_gateway_webhook then
                v_cancelled_by := 'system_expired';
            elsif v_actor is not null and v_actor = new.user_id then
                v_cancelled_by := 'buyer';
            else
                v_cancelled_by := 'seller';
            end if;

            if v_cancelled_by = 'buyer' then
                perform public.dispatch_push_edge_function(
                    'notify-order',
                    jsonb_build_object(
                        'event_type', 'order_cancelled_by_buyer',
                        'order_id', new.id,
                        'old_status', old.status,
                        'new_status', new.status,
                        'cancelled_by', v_cancelled_by,
                        'actor_id', v_actor
                    )
                );
            else
                perform public.dispatch_push_edge_function(
                    'notify-order',
                    jsonb_build_object(
                        'event_type', 'order_status',
                        'order_id', new.id,
                        'old_status', old.status,
                        'new_status', new.status,
                        'cancelled_by', v_cancelled_by,
                        'actor_id', v_actor
                    )
                );
            end if;
        elsif new.status in ('confirmed', 'preparing', 'processing', 'ready', 'completed') then
            perform public.dispatch_push_edge_function(
                'notify-order',
                jsonb_build_object(
                    'event_type', 'order_status',
                    'order_id', new.id,
                    'old_status', old.status,
                    'new_status', new.status,
                    'actor_id', v_actor
                )
            );
        end if;
    end if;

    if new.payment_status is distinct from old.payment_status
       and new.payment_status in ('paid', 'rejected') then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'order_payment_update',
                'order_id', new.id,
                'old_payment_status', old.payment_status,
                'payment_status', new.payment_status,
                'is_gateway_webhook', v_is_gateway_webhook,
                'actor_id', v_actor
            )
        );
    end if;

    return new;
end;
$function$;

create or replace trigger orders_notify_updates
    after update on public.orders
    for each row
    when (
        old.status is distinct from new.status
        or old.payment_status is distinct from new.payment_status
    )
    execute function public.notify_order_update();

-- ============================================================================
-- 5. Chat Message Trigger (Fixes decrypted_secret + Skips auto_reply)
-- ============================================================================

create or replace function public.notify_chat_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.message_type in ('order_card', 'product_card', 'auto_reply') then
        return new;
    end if;

    perform public.dispatch_push_edge_function(
        'notify-chat',
        jsonb_build_object('message_id', new.id)
    );
    return new;
end;
$function$;

-- ============================================================================
-- 6. Store Activity Triggers (Stalls, Warnings, Announcements, Reports)
-- ============================================================================

create or replace function public.notify_stall_activity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_actor uuid := auth.uid();
begin
    if new.is_verified is distinct from old.is_verified then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'stall_verification',
                'stall_id', new.id,
                'is_verified', new.is_verified,
                'verification_revoked', coalesce(new.verification_revoked, false),
                'reason', coalesce(new.verification_revoked_reason, '')
            )
        );
    end if;

    if new.is_open is distinct from old.is_open
       and (v_actor is null or v_actor <> new.owner_id) then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'stall_visibility',
                'stall_id', new.id,
                'is_open', new.is_open
            )
        );
    end if;

    return new;
end;
$function$;

create or replace trigger stalls_notify_activity
    after update on public.stalls
    for each row
    when (
        old.is_verified is distinct from new.is_verified
        or old.is_open is distinct from new.is_open
    )
    execute function public.notify_stall_activity();

create or replace function public.notify_developer_warning()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    perform public.dispatch_push_edge_function(
        'notify-order',
        jsonb_build_object(
            'event_type', 'developer_warning',
            'warning_id', new.id
        )
    );
    return new;
end;
$function$;

create or replace trigger warnings_notify_push
    after insert on public.developer_warnings
    for each row
    execute function public.notify_developer_warning();

create or replace function public.notify_developer_announcement()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if coalesce(new.is_active, true) then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'developer_announcement',
                'announcement_id', new.id
            )
        );
    end if;
    return new;
end;
$function$;

create or replace trigger announcements_notify_push
    after insert on public.developer_announcements
    for each row
    execute function public.notify_developer_announcement();

create or replace function public.notify_seller_report()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if tg_op = 'INSERT' then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'seller_report_created',
                'report_id', new.id
            )
        );
    elsif tg_op = 'UPDATE'
          and new.status is distinct from old.status
          and new.status in ('resolved', 'dismissed', 'reviewed') then
        perform public.dispatch_push_edge_function(
            'notify-order',
            jsonb_build_object(
                'event_type', 'seller_report_updated',
                'report_id', new.id,
                'new_status', new.status
            )
        );
    end if;
    return new;
end;
$function$;

create or replace trigger seller_reports_notify_push
    after insert or update of status on public.seller_reports
    for each row
    execute function public.notify_seller_report();

-- ============================================================================
-- 7. Tighten Function Execute Privileges (Defense-in-Depth)
-- ============================================================================

revoke execute on function public.upsert_user_device_token(text, text, text) from public, anon;
grant execute on function public.upsert_user_device_token(text, text, text) to authenticated;
revoke execute on function public.dispatch_push_edge_function(text, jsonb) from public, anon, authenticated;
revoke execute on function public.notify_new_order() from public, anon, authenticated;
revoke execute on function public.notify_order_update() from public, anon, authenticated;
revoke execute on function public.notify_chat_message() from public, anon, authenticated;
revoke execute on function public.notify_stall_activity() from public, anon, authenticated;
revoke execute on function public.notify_developer_warning() from public, anon, authenticated;
revoke execute on function public.notify_developer_announcement() from public, anon, authenticated;
revoke execute on function public.notify_seller_report() from public, anon, authenticated;
