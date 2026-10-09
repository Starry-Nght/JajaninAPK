-- 202610080002: Perbaikan trigger notify_new_order.
--
-- Bug: resolusi schema pg_net memakai pg_extension.extnamespace, yang pada
-- project ini melapor 'public' padahal fungsi http_post/http_get berada di
-- schema 'net'. Akibatnya dynamic SQL 'public.http_post(...)' melempar error
-- yang ditelan exception handler (hanya raise warning) — order baru TIDAK
-- pernah mengirim HTTP ke edge function.
--
-- Fix: resolusi schema diambil dari keberadaan fungsi http_post di pg_proc.

create or replace function public.notify_new_order()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
declare
    target_secret text;
    target_url text;
    net_schema text;
    request_id bigint;
begin
    begin
        select secret into target_secret
        from vault.decrypted_secrets
        where name = 'notify_secret'
        order by created_at desc
        limit 1;

        if target_secret is null or target_secret = '' then
            return new;
        end if;

        -- Schema diambil dari lokasi fungsi http_post yang benar-benar ada,
        -- bukan dari extnamespace pg_net (yang menyimpang pada project ini).
        select n.nspname into net_schema
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where p.proname = 'http_post'
        limit 1;

        if net_schema is null then
            return new;
        end if;

        target_url := nullif(current_setting('app.notify_function_url', true), '');
        if target_url is null then
            target_url := 'https://yezxyhpczfnkcndfpuht.supabase.co/functions/v1/notify-order';
        end if;

        execute format(
            'select %I.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 10000)',
            net_schema
        )
        into request_id
        using
            target_url,
            jsonb_build_object('order_id', new.id),
            jsonb_build_object(
                'x-notify-secret', target_secret,
                'Content-Type', 'application/json'
            );
    exception when others then
        raise warning 'notify_new_order failed for order %: %', new.id, sqlerrm;
    end;

    return new;
end;
$function$;

grant execute on function public.notify_new_order() to authenticated;

drop trigger if exists orders_notify_admin on public.orders;
create trigger orders_notify_admin after insert on public.orders
for each row execute function public.notify_new_order();
