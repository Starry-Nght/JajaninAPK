-- ============================================================================
-- JAJANIN v2.26 — Buyer Profile Avatar, 3-Aspect Store Ratings (Toko, Durasi, Rasa),
--                 and Seller Reports (2 Mandatory Photo Proofs) -> Live Developer Section
-- Migration: 202611270006_v2_avatars_ratings_reports.sql
-- ============================================================================

-- 1. Profile avatar URL column + public Storage bucket `profile-avatars`
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS avatar_url text;

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'profile-avatars',
    'profile-avatars',
    true,
    5242880,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE
   SET public = EXCLUDED.public,
       file_size_limit = EXCLUDED.file_size_limit,
       allowed_mime_types = EXCLUDED.allowed_mime_types;

DROP POLICY IF EXISTS "Public read profile avatars" ON storage.objects;
CREATE POLICY "Public read profile avatars"
    ON storage.objects FOR SELECT
    USING (bucket_id = 'profile-avatars');

DROP POLICY IF EXISTS "Users upload own profile avatar" ON storage.objects;
CREATE POLICY "Users upload own profile avatar"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    );

DROP POLICY IF EXISTS "Users update own profile avatar" ON storage.objects;
CREATE POLICY "Users update own profile avatar"
    ON storage.objects FOR UPDATE
    USING (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    )
    WITH CHECK (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    );

DROP POLICY IF EXISTS "Users delete own profile avatar" ON storage.objects;
CREATE POLICY "Users delete own profile avatar"
    ON storage.objects FOR DELETE
    USING (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    );

CREATE OR REPLACE FUNCTION public.update_my_avatar(p_avatar_url text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_clean text := trim(coalesce(p_avatar_url, ''));
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk mengubah foto profil.';
    end if;
    if v_clean <> '' and v_clean !~ '^https://' then
        raise exception 'URL foto profil tidak valid.';
    end if;
    if char_length(v_clean) > 600 then
        raise exception 'URL foto profil terlalu panjang.';
    end if;

    update public.profiles
       set avatar_url = nullif(v_clean, '')
     where id = uid;

    return v_clean;
end;
$function$;

GRANT EXECUTE ON FUNCTION public.update_my_avatar(text) TO authenticated;

-- 2. Order Ratings table (Toko, Durasi, Rasa: 1..5 stars per paid/completed order)
CREATE TABLE IF NOT EXISTS public.order_ratings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL UNIQUE REFERENCES public.orders(id) ON DELETE CASCADE,
    stall_id uuid NOT NULL REFERENCES public.stalls(id) ON DELETE CASCADE,
    buyer_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rating_store smallint NOT NULL CHECK (rating_store BETWEEN 1 AND 5),
    rating_duration smallint NOT NULL CHECK (rating_duration BETWEEN 1 AND 5),
    rating_taste smallint NOT NULL CHECK (rating_taste BETWEEN 1 AND 5),
    rating_overall numeric(3,2) GENERATED ALWAYS AS (
        round(((rating_store + rating_duration + rating_taste)::numeric / 3.0), 2)
    ) STORED,
    comment text NOT NULL DEFAULT '' CHECK (char_length(comment) <= 400),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_ratings_stall_created
    ON public.order_ratings (stall_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_ratings_buyer
    ON public.order_ratings (buyer_id, created_at DESC);

ALTER TABLE public.order_ratings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Order ratings readable by authenticated" ON public.order_ratings;
CREATE POLICY "Order ratings readable by authenticated"
    ON public.order_ratings FOR SELECT
    TO authenticated
    USING (true);

DROP POLICY IF EXISTS "Order ratings insert own" ON public.order_ratings;
CREATE POLICY "Order ratings insert own"
    ON public.order_ratings FOR INSERT
    TO authenticated
    WITH CHECK (buyer_id = (SELECT auth.uid()));

DROP POLICY IF EXISTS "Order ratings update own" ON public.order_ratings;
CREATE POLICY "Order ratings update own"
    ON public.order_ratings FOR UPDATE
    TO authenticated
    USING (buyer_id = (SELECT auth.uid()))
    WITH CHECK (buyer_id = (SELECT auth.uid()));

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
    if lower(coalesce(v_ord.payment_status, '')) <> 'paid'
       and lower(coalesce(v_ord.status, '')) <> 'completed' then
        raise exception 'Selesaikan pembayaran terlebih dahulu sebelum memberi rating.';
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
        'rating_overall', v_row.rating_overall,
        'comment', v_row.comment,
        'created_at', v_row.created_at,
        'updated_at', v_row.updated_at
    );
end;
$function$;

GRANT EXECUTE ON FUNCTION public.submit_order_rating(uuid, integer, integer, integer, text) TO authenticated;

-- 3. Seller Reports table (2 mandatory photo proofs) + Storage bucket `report-evidences`
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'report-evidences',
    'report-evidences',
    true,
    5242880,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE
   SET public = EXCLUDED.public,
       file_size_limit = EXCLUDED.file_size_limit,
       allowed_mime_types = EXCLUDED.allowed_mime_types;

DROP POLICY IF EXISTS "Public read report evidences" ON storage.objects;
CREATE POLICY "Public read report evidences"
    ON storage.objects FOR SELECT
    USING (bucket_id = 'report-evidences');

DROP POLICY IF EXISTS "Users upload own report evidences" ON storage.objects;
CREATE POLICY "Users upload own report evidences"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'report-evidences'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    );

DROP POLICY IF EXISTS "Users delete own report evidences" ON storage.objects;
CREATE POLICY "Users delete own report evidences"
    ON storage.objects FOR DELETE
    USING (
        bucket_id = 'report-evidences'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
    );

CREATE TABLE IF NOT EXISTS public.seller_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    stall_id uuid NOT NULL REFERENCES public.stalls(id) ON DELETE CASCADE,
    seller_id uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    order_id uuid REFERENCES public.orders(id) ON DELETE SET NULL,
    source text NOT NULL DEFAULT 'store_profile' CHECK (source IN ('order_history', 'store_profile')),
    category text NOT NULL DEFAULT 'Pelayanan Buruk' CHECK (char_length(trim(category)) BETWEEN 2 AND 60),
    description text NOT NULL CHECK (char_length(trim(description)) BETWEEN 10 AND 1000),
    evidence_url_1 text NOT NULL CHECK (evidence_url_1 ~ '^https://'),
    evidence_url_2 text NOT NULL CHECK (evidence_url_2 ~ '^https://'),
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'reviewing', 'resolved', 'dismissed')),
    developer_notes text NOT NULL DEFAULT '' CHECK (char_length(developer_notes) <= 600),
    resolved_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_seller_reports_status_created
    ON public.seller_reports (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_seller_reports_stall_created
    ON public.seller_reports (stall_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_seller_reports_reporter_created
    ON public.seller_reports (reporter_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_seller_reports_order_id
    ON public.seller_reports (order_id) WHERE order_id IS NOT NULL;

ALTER TABLE public.seller_reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Seller reports select reporter or developer" ON public.seller_reports;
CREATE POLICY "Seller reports select reporter or developer"
    ON public.seller_reports FOR SELECT
    TO authenticated
    USING (
        reporter_id = (SELECT auth.uid())
        OR (SELECT public.is_developer())
    );

DROP POLICY IF EXISTS "Seller reports insert own" ON public.seller_reports;
CREATE POLICY "Seller reports insert own"
    ON public.seller_reports FOR INSERT
    TO authenticated
    WITH CHECK (reporter_id = (SELECT auth.uid()));

DROP POLICY IF EXISTS "Seller reports update developer only" ON public.seller_reports;
CREATE POLICY "Seller reports update developer only"
    ON public.seller_reports FOR UPDATE
    TO authenticated
    USING ((SELECT public.is_developer()))
    WITH CHECK ((SELECT public.is_developer()));

DROP POLICY IF EXISTS "Seller reports delete developer only" ON public.seller_reports;
CREATE POLICY "Seller reports delete developer only"
    ON public.seller_reports FOR DELETE
    TO authenticated
    USING ((SELECT public.is_developer()));

CREATE OR REPLACE FUNCTION public.submit_seller_report(
    p_stall_id uuid,
    p_order_id uuid DEFAULT NULL,
    p_source text DEFAULT 'store_profile',
    p_category text DEFAULT 'Pelayanan Buruk',
    p_description text DEFAULT '',
    p_evidence_url_1 text DEFAULT '',
    p_evidence_url_2 text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_stall public.stalls%rowtype;
    v_ord public.orders%rowtype;
    v_source text := case lower(trim(coalesce(p_source, 'store_profile')))
        when 'order_history' then 'order_history'
        else 'store_profile'
    end;
    v_cat text := left(trim(coalesce(p_category, 'Pelayanan Buruk')), 60);
    v_desc text := left(trim(coalesce(p_description, '')), 1000);
    v_ev1 text := trim(coalesce(p_evidence_url_1, ''));
    v_ev2 text := trim(coalesce(p_evidence_url_2, ''));
    v_today_count integer := 0;
    v_row public.seller_reports%rowtype;
begin
    if uid is null then
        raise exception 'Masuk terlebih dahulu untuk melaporkan penjual.';
    end if;
    if p_stall_id is null then
        raise exception 'Warung yang dilaporkan tidak valid.';
    end if;
    if char_length(v_cat) < 2 then
        v_cat := 'Pelayanan Buruk';
    end if;
    if char_length(v_desc) < 10 then
        raise exception 'Jelaskan kronologi masalah minimal 10 karakter.';
    end if;
    if v_ev1 !~ '^https://' or v_ev2 !~ '^https://' then
        raise exception 'Wajib melampirkan 2 foto bukti yang valid untuk mengirim laporan.';
    end if;
    if v_ev1 = v_ev2 then
        raise exception 'Bukti foto 1 dan bukti foto 2 harus merupakan 2 foto yang berbeda.';
    end if;

    select *
      into v_stall
      from public.stalls s
     where s.id = p_stall_id;

    if not found then
        raise exception 'Warung tidak ditemukan.';
    end if;
    if v_stall.owner_id is not null and v_stall.owner_id = uid then
        raise exception 'Kamu tidak dapat melaporkan warung milik sendiri.';
    end if;

    if p_order_id is not null then
        select *
          into v_ord
          from public.orders o
         where o.id = p_order_id;
        if not found or v_ord.user_id <> uid then
            raise exception 'Pesanan tidak ditemukan atau bukan milik akun ini.';
        end if;
        v_source := 'order_history';
    end if;

    select count(*)::integer
      into v_today_count
      from public.seller_reports sr
     where sr.reporter_id = uid
       and sr.created_at >= now() - interval '24 hours';
    if v_today_count >= 5 then
        raise exception 'Batas maksimal 5 laporan dalam 24 jam telah tercapai.';
    end if;

    insert into public.seller_reports (
        reporter_id, stall_id, seller_id, order_id,
        source, category, description,
        evidence_url_1, evidence_url_2, status
    )
    values (
        uid, v_stall.id, v_stall.owner_id, p_order_id,
        v_source, v_cat, v_desc,
        v_ev1, v_ev2, 'open'
    )
    returning * into v_row;

    return jsonb_build_object(
        'id', v_row.id,
        'stall_id', v_row.stall_id,
        'order_id', v_row.order_id,
        'status', v_row.status,
        'created_at', v_row.created_at
    );
end;
$function$;

GRANT EXECUTE ON FUNCTION public.submit_seller_report(uuid, uuid, text, text, text, text, text) TO authenticated;

CREATE OR REPLACE FUNCTION public.developer_update_seller_report(
    p_report_id uuid,
    p_status text,
    p_developer_notes text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    uid uuid := auth.uid();
    v_status text := lower(trim(coalesce(p_status, '')));
    v_notes text := left(trim(coalesce(p_developer_notes, '')), 600);
    v_row public.seller_reports%rowtype;
begin
    if uid is null or not public.is_developer() then
        raise exception 'Hanya developer yang dapat memperbarui status laporan.';
    end if;
    if v_status not in ('open', 'reviewing', 'resolved', 'dismissed') then
        raise exception 'Status laporan tidak valid.';
    end if;

    update public.seller_reports
       set status = v_status,
           developer_notes = case when v_notes <> '' then v_notes else developer_notes end,
           resolved_by = case when v_status in ('resolved', 'dismissed') then uid else resolved_by end,
           resolved_at = case when v_status in ('resolved', 'dismissed') then now() else null end,
           updated_at = now()
     where id = p_report_id
     returning * into v_row;

    if not found then
        raise exception 'Laporan tidak ditemukan.';
    end if;

    return jsonb_build_object(
        'id', v_row.id,
        'status', v_row.status,
        'developer_notes', v_row.developer_notes,
        'resolved_at', v_row.resolved_at
    );
end;
$function$;

GRANT EXECUTE ON FUNCTION public.developer_update_seller_report(uuid, text, text) TO authenticated;

-- 4. Update get_public_store_profile & search_public_stores with live Store Rating aggregates
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

CREATE OR REPLACE FUNCTION public.search_public_stores(
    p_query text DEFAULT ''::text,
    p_category text DEFAULT 'All'::text,
    p_limit integer DEFAULT 12,
    p_offset integer DEFAULT 0
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_q text := lower(trim(coalesce(p_query, '')));
    v_cat text := lower(trim(coalesce(p_category, 'All')));
    v_limit integer := greatest(1, least(coalesce(p_limit, 12), 50));
    v_offset integer := greatest(0, coalesce(p_offset, 0));
    v_total integer := 0;
    v_items jsonb := '[]'::jsonb;
begin
    if auth.uid() is null then
        raise exception 'Masuk terlebih dahulu untuk menjelajahi toko.';
    end if;

    with base as (
        select s.id,
               s.name,
               public.clean_public_stall_bio(s.public_bio, s.description) as bio,
               coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman') as store_category,
               case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end as location,
               s.is_open,
               s.is_verified,
               s.verified_at,
               s.completed_orders_count,
               s.logo_url,
               s.banner_url,
               s.created_at,
               public.clean_public_person_name(p.display_name) as seller_name,
               coalesce(ms.active_products, 0)::integer as active_product_count,
               coalesce(ms.total_products, 0)::integer as total_product_count,
               ms.min_price_idr,
               coalesce(ms.has_food, false) as has_food,
               coalesce(ms.has_drinks, false) as has_drinks,
               coalesce(rs.avg_overall, 0)::double precision as avg_rating,
               coalesce(rs.rating_count, 0)::integer as rating_count
          from public.stalls s
          left join public.profiles p on p.id = s.owner_id
          left join lateral (
              select count(*) filter (where mi.is_available) as active_products,
                     count(*) as total_products,
                     min(mi.price_idr) filter (where mi.is_available) as min_price_idr,
                     bool_or(mi.is_available and lower(mi.category) in ('food', 'makanan')) as has_food,
                     bool_or(mi.is_available and lower(mi.category) in ('drinks', 'drink', 'minuman')) as has_drinks,
                     bool_or(v_q <> '' and (lower(mi.name) like '%' || v_q || '%' or lower(mi.description) like '%' || v_q || '%')) as menu_matches_query
                from public.menu_items mi
               where mi.stall_id = s.id
          ) ms on true
          left join lateral (
              select round(avg(r.rating_overall)::numeric, 2) as avg_overall,
                     count(*)::integer as rating_count
                from public.order_ratings r
               where r.stall_id = s.id
          ) rs on true
         where coalesce(p.account_status, 'active') <> 'suspended'
           and (
               v_q = ''
               or lower(s.name) like '%' || v_q || '%'
               or lower(coalesce(s.store_category, '')) like '%' || v_q || '%'
               or lower(public.clean_public_stall_bio(s.public_bio, s.description)) like '%' || v_q || '%'
               or coalesce(ms.menu_matches_query, false)
           )
           and (
               v_cat in ('', 'all', 'semua')
               or (v_cat in ('verified', 'terverifikasi') and s.is_verified)
               or (v_cat in ('open', 'buka') and s.is_open)
               or (v_cat in ('food', 'makanan') and (coalesce(ms.has_food, false) or lower(s.store_category) like '%makanan%'))
               or (v_cat in ('drinks', 'drink', 'minuman') and (coalesce(ms.has_drinks, false) or lower(s.store_category) like '%minuman%'))
               or lower(s.store_category) = v_cat
           )
    )
    select count(*)::integer into v_total from base;

    with base as (
        select s.id,
               s.name,
               public.clean_public_stall_bio(s.public_bio, s.description) as bio,
               coalesce(nullif(trim(s.store_category), ''), 'Makanan & Minuman') as store_category,
               case when trim(coalesce(s.location, '')) in ('', 'Kantin') then '' else trim(s.location) end as location,
               s.is_open,
               s.is_verified,
               s.verified_at,
               s.completed_orders_count,
               s.logo_url,
               s.banner_url,
               s.created_at,
               public.clean_public_person_name(p.display_name) as seller_name,
               coalesce(ms.active_products, 0)::integer as active_product_count,
               coalesce(ms.total_products, 0)::integer as total_product_count,
               ms.min_price_idr,
               coalesce(ms.has_food, false) as has_food,
               coalesce(ms.has_drinks, false) as has_drinks,
               coalesce(rs.avg_overall, 0)::double precision as avg_rating,
               coalesce(rs.rating_count, 0)::integer as rating_count
          from public.stalls s
          left join public.profiles p on p.id = s.owner_id
          left join lateral (
              select count(*) filter (where mi.is_available) as active_products,
                     count(*) as total_products,
                     min(mi.price_idr) filter (where mi.is_available) as min_price_idr,
                     bool_or(mi.is_available and lower(mi.category) in ('food', 'makanan')) as has_food,
                     bool_or(mi.is_available and lower(mi.category) in ('drinks', 'drink', 'minuman')) as has_drinks,
                     bool_or(v_q <> '' and (lower(mi.name) like '%' || v_q || '%' or lower(mi.description) like '%' || v_q || '%')) as menu_matches_query
                from public.menu_items mi
               where mi.stall_id = s.id
          ) ms on true
          left join lateral (
              select round(avg(r.rating_overall)::numeric, 2) as avg_overall,
                     count(*)::integer as rating_count
                from public.order_ratings r
               where r.stall_id = s.id
          ) rs on true
         where coalesce(p.account_status, 'active') <> 'suspended'
           and (
               v_q = ''
               or lower(s.name) like '%' || v_q || '%'
               or lower(coalesce(s.store_category, '')) like '%' || v_q || '%'
               or lower(public.clean_public_stall_bio(s.public_bio, s.description)) like '%' || v_q || '%'
               or coalesce(ms.menu_matches_query, false)
           )
           and (
               v_cat in ('', 'all', 'semua')
               or (v_cat in ('verified', 'terverifikasi') and s.is_verified)
               or (v_cat in ('open', 'buka') and s.is_open)
               or (v_cat in ('food', 'makanan') and (coalesce(ms.has_food, false) or lower(s.store_category) like '%makanan%'))
               or (v_cat in ('drinks', 'drink', 'minuman') and (coalesce(ms.has_drinks, false) or lower(s.store_category) like '%minuman%'))
               or lower(s.store_category) = v_cat
           )
         order by s.is_open desc, s.is_verified desc, s.completed_orders_count desc, lower(s.name) asc
         limit v_limit offset v_offset
    )
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', b.id,
                'name', b.name,
                'bio', b.bio,
                'store_category', b.store_category,
                'location', b.location,
                'is_open', b.is_open,
                'is_verified', b.is_verified,
                'verified_at', b.verified_at,
                'completed_orders_count', b.completed_orders_count,
                'logo_url', b.logo_url,
                'banner_url', b.banner_url,
                'seller_name', b.seller_name,
                'active_product_count', b.active_product_count,
                'min_price_idr', b.min_price_idr,
                'avg_rating', b.avg_rating,
                'rating_count', b.rating_count
            )
        ),
        '[]'::jsonb
    ) into v_items
    from base b;

    return jsonb_build_object(
        'total', v_total,
        'has_more', (v_offset + jsonb_array_length(v_items) < v_total),
        'stores', v_items
    );
end;
$function$;

-- 5. Enable Supabase Realtime on seller_reports, order_ratings, and stalls
DO $$
BEGIN
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.seller_reports;
    EXCEPTION WHEN duplicate_object THEN NULL;
    END;
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.order_ratings;
    EXCEPTION WHEN duplicate_object THEN NULL;
    END;
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.stalls;
    EXCEPTION WHEN duplicate_object THEN NULL;
    END;
END $$;
