-- =============================================================================
-- Migration: 202611280002_v2_rating_only_on_completed.sql
-- Restrict order ratings so buyers can only submit ratings after the seller
-- marks the order as `completed` (Selesai), not immediately upon payment.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.submit_order_rating(
    p_order_id uuid,
    p_rating_store integer,
    p_rating_duration integer,
    p_rating_taste integer,
    p_comment text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_ord public.orders%rowtype;
    v_stall_id uuid;
    v_comment text := left(trim(coalesce(p_comment, '')), 400);
    v_row public.order_ratings%rowtype;
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk memberi rating.';
    end if;
    if p_order_id is null then
        raise exception 'Pesanan tidak valid.';
    end if;
    if p_rating_store is null or p_rating_store < 1 or p_rating_store > 5
       or p_rating_duration is null or p_rating_duration < 1 or p_rating_duration > 5
       or p_rating_taste is null or p_rating_taste < 1 or p_rating_taste > 5 then
        raise exception 'Rating Toko, Durasi, dan Rasa harus antara 1 sampai 5 bintang.';
    end if;

    select *
      into v_ord
      from public.orders o
     where o.id = p_order_id;

    if not found then
        raise exception 'Pesanan tidak ditemukan.';
    end if;
    if v_ord.user_id <> uid then
        raise exception 'Kamu hanya bisa memberi rating untuk pesanan milik sendiri.';
    end if;
    if lower(coalesce(v_ord.status, '')) = 'cancelled' then
        raise exception 'Pesanan yang dibatalkan tidak dapat diberi rating.';
    end if;
    if lower(coalesce(v_ord.status, '')) <> 'completed' then
        raise exception 'Rating hanya bisa diberikan setelah penjual menyelesaikan pesanan.';
    end if;

    v_stall_id := v_ord.stall_id;
    if v_stall_id is null then
        select mi.stall_id
          into v_stall_id
          from public.order_items oi
          join public.menu_items mi on mi.id = oi.menu_item_id
         where oi.order_id = v_ord.id
           and mi.stall_id is not null
         limit 1;
    end if;
    if v_stall_id is null then
        raise exception 'Warung pesanan tidak ditemukan.';
    end if;

    insert into public.order_ratings (
        order_id, stall_id, buyer_id,
        rating_store, rating_duration, rating_taste, comment
    )
    values (
        v_ord.id, v_stall_id, uid,
        p_rating_store::smallint, p_rating_duration::smallint, p_rating_taste::smallint, v_comment
    )
    on conflict (order_id)
    do update set
        rating_store = excluded.rating_store,
        rating_duration = excluded.rating_duration,
        rating_taste = excluded.rating_taste,
        comment = excluded.comment,
        updated_at = now()
    returning * into v_row;

    return jsonb_build_object(
        'id', v_row.id,
        'order_id', v_row.order_id,
        'stall_id', v_row.stall_id,
        'buyer_id', v_row.buyer_id,
        'rating_store', v_row.rating_store,
        'rating_duration', v_row.rating_duration,
        'rating_taste', v_row.rating_taste,
        'rating_overall', v_row.rating_overall::double precision,
        'comment', v_row.comment,
        'created_at', v_row.created_at
    );
end;
$function$;
