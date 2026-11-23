-- Combined migration applied as v2_preorder_nis_dualmode_schema + v2_preorder_nis_dualmode_rpcs
-- See Supabase migration history on project yezxyhpczfnkcndfpuht.

CREATE TABLE IF NOT EXISTS public.students (
    nis text PRIMARY KEY,
    full_name text NOT NULL,
    class_name text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.students ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS nis text,
    ADD COLUMN IF NOT EXISTS is_seller_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS seller_verified_email text,
    ADD COLUMN IF NOT EXISTS seller_verified_at timestamptz;

CREATE UNIQUE INDEX IF NOT EXISTS profiles_nis_uidx
    ON public.profiles (nis)
    WHERE nis IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.seller_email_otps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    email text NOT NULL,
    code_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.seller_email_otps ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.menu_items
    ADD COLUMN IF NOT EXISTS order_schedule text NOT NULL DEFAULT 'both',
    ADD COLUMN IF NOT EXISTS pickup_sessions text NOT NULL DEFAULT 'Istirahat 1,Istirahat 2,Pulang';

ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS order_mode text NOT NULL DEFAULT 'same_day',
    ADD COLUMN IF NOT EXISTS pickup_date date NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Jakarta')::date),
    ADD COLUMN IF NOT EXISTS pickup_session text NOT NULL DEFAULT 'Istirahat 1';
