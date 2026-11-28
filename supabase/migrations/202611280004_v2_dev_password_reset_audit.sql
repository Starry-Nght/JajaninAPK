-- Audit log for Developer-initiated student password resets.
-- Plaintext passwords are NEVER stored; only actor, target user/NIS, optional reason, and timestamp.

CREATE TABLE IF NOT EXISTS public.password_reset_audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    target_nis text NOT NULL DEFAULT '',
    actor_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reason text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_audit_logs_target_created
    ON public.password_reset_audit_logs (target_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_password_reset_audit_logs_actor_created
    ON public.password_reset_audit_logs (actor_id, created_at DESC);

ALTER TABLE public.password_reset_audit_logs ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'password_reset_audit_logs'
          AND policyname = 'dev_select_password_reset_audit_logs'
    ) THEN
        CREATE POLICY dev_select_password_reset_audit_logs
            ON public.password_reset_audit_logs
            FOR SELECT
            TO authenticated
            USING (
                EXISTS (
                    SELECT 1
                    FROM public.profiles p
                    WHERE p.id = (SELECT auth.uid())
                      AND p.role = 'developer'
                )
            );
    END IF;
END
$$;

REVOKE ALL ON TABLE public.password_reset_audit_logs FROM anon;
GRANT SELECT ON TABLE public.password_reset_audit_logs TO authenticated;
GRANT ALL ON TABLE public.password_reset_audit_logs TO service_role;
