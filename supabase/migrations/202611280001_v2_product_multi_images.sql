-- =============================================================================
-- Migration: 202611280001_v2_product_multi_images.sql
-- Multi-photo product gallery (up to 5 photos per menu item).
-- Preserves `image_url` as the primary/cover image for full backwards compat.
-- =============================================================================

-- 1. Add jsonb `image_urls` column on public.menu_items (max 5 URLs)
ALTER TABLE public.menu_items
    ADD COLUMN IF NOT EXISTS image_urls jsonb NOT NULL DEFAULT '[]'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'menu_items_image_urls_max5_chk'
    ) THEN
        ALTER TABLE public.menu_items
            ADD CONSTRAINT menu_items_image_urls_max5_chk
            CHECK (jsonb_typeof(image_urls) = 'array' AND jsonb_array_length(image_urls) <= 5);
    END IF;
END $$;

-- 2. Backfill existing items with a single `image_url` into `image_urls`
UPDATE public.menu_items
   SET image_urls = jsonb_build_array(btrim(image_url))
 WHERE image_url IS NOT NULL
   AND btrim(image_url) <> ''
   AND jsonb_array_length(coalesce(image_urls, '[]'::jsonb)) = 0;

-- 3. Keep `get_public_store_profile` returning `image_urls` on every product
CREATE OR REPLACE FUNCTION public.get_public_store_profile(p_stall_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_stall jsonb;
    v_products jsonb := '[]'::jsonb;
    v_recent_ratings jsonb := '[]'::jsonb;
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk melihat profil toko.';
    end if;
    if p_stall_id is null then
        raise exception 'Toko tidak ditemukan.';
    end if;

    select jsonb_build_object(
        'id', s.id,
        'name', s.name,
        'bio', public.clean_public_stall_bio(s.public_bio, s.description),
        'store_category', coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman'),
        'location', case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end,
        'is_open', s.is_open,
        'is_verified', s.is_verified,
        'verified_at', s.verified_at,
        'completed_orders_count', s.completed_orders_count,
        'logo_url', s.logo_url,
        'banner_url', s.banner_url,
        'has_qris', true,
        'owner_id', s.owner_id,
        'seller_name', public.clean_public_person_name(p.display_name),
        'created_at', s.created_at,
        'avg_rating', coalesce(rs.avg_overall, 0)::double precision,
        'avg_rating_store', coalesce(rs.avg_store, 0)::double precision,
        'avg_rating_duration', coalesce(rs.avg_duration, 0)::double precision,
        'avg_rating_taste', coalesce(rs.avg_taste, 0)::double precision,
        'rating_count', coalesce(rs.rating_count, 0)::integer
    )
      into v_stall
      from public.stalls s
      left join public.profiles p on p.id = s.owner_id
      left join lateral (
          select round(avg(r.rating_overall)::numeric, 2) as avg_overall,
                 round(avg(r.rating_store)::numeric, 2) as avg_store,
                 round(avg(r.rating_duration)::numeric, 2) as avg_duration,
                 round(avg(r.rating_taste)::numeric, 2) as avg_taste,
                 count(*)::integer as rating_count
            from public.order_ratings r
           where r.stall_id = s.id
      ) rs on true
     where s.id = p_stall_id
       and (
           s.owner_id = uid
           or public.is_developer()
           or coalesce(p.account_status, 'active') <> 'suspended'
       );

    if v_stall is null then
        raise exception 'Toko tidak ditemukan atau sedang tidak aktif.';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', mi.id,
                'name', mi.name,
                'description', mi.description,
                'category', mi.category,
                'price_idr', mi.price_idr,
                'image_url', mi.image_url,
                'image_urls', coalesce(mi.image_urls, '[]'::jsonb),
                'is_available', mi.is_available,
                'stock', mi.stock,
                'stall_id', mi.stall_id,
                'ingredients', mi.ingredients,
                'order_schedule', mi.order_schedule,
                'pickup_sessions', mi.pickup_sessions,
                'po_dates', mi.po_dates,
                'po_cutoff_hour', mi.po_cutoff_hour,
                'po_daily_quota', mi.po_daily_quota,
                'po_require_qris', mi.po_require_qris,
                'po_allow_weekend', mi.po_allow_weekend,
                'variant_sections', coalesce(mi.variant_sections, '[]'::jsonb)
            )
            order by mi.is_available desc,
                     (mi.stock is null or mi.stock > 0) desc,
                     mi.created_at desc
        ),
        '[]'::jsonb
    )
      into v_products
      from public.menu_items mi
     where mi.stall_id = p_stall_id
       and mi.is_available = true
       and mi.name <> ''
       and mi.price_idr > 0;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', r.id,
                'buyer_name', coalesce(nullif(public.clean_public_person_name(bp.display_name), ''), 'Pembeli'),
                'rating_store', r.rating_store,
                'rating_duration', r.rating_duration,
                'rating_taste', r.rating_taste,
                'rating_overall', r.rating_overall::double precision,
                'comment', r.comment,
                'created_at', r.created_at
            )
            order by r.created_at desc
        ),
        '[]'::jsonb
    )
      into v_recent_ratings
      from (
          select *
            from public.order_ratings
           where stall_id = p_stall_id
           order by created_at desc
           limit 8
      ) r
      left join public.profiles bp on bp.id = r.buyer_id;

    return jsonb_build_object(
        'stall', v_stall,
        'products', v_products,
        'recent_ratings', v_recent_ratings
    );
end;
$function$;
