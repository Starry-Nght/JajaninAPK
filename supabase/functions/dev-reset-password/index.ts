import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function extractNisFromDisplayName(displayName: string | null | undefined): string {
  if (!displayName) return "";
  const match = /\[NIS:([0-9]+)\]/i.exec(displayName);
  return match?.[1]?.trim() ?? "";
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "Server belum dikonfigurasi." }, 500);
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    return json({ error: "Sesi developer tidak ditemukan. Silakan masuk ulang." }, 401);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  // 1. Verifikasi JWT pemanggil secara ketat
  const { data: callerAuth, error: authErr } = await db.auth.getUser(token);
  if (authErr || !callerAuth?.user) {
    return json({ error: "Sesi tidak valid atau sudah berakhir. Silakan masuk ulang." }, 401);
  }
  const actorId = callerAuth.user.id;

  // 2. Pastikan pemanggil ber-role developer di database (bukan dari user_metadata)
  const { data: actorProf, error: actorProfErr } = await db
    .from("profiles")
    .select("id, role, account_status")
    .eq("id", actorId)
    .maybeSingle();

  if (actorProfErr) {
    return json({ error: "Gagal memverifikasi hak akses developer." }, 500);
  }
  if (!actorProf || actorProf.role !== "developer") {
    return json({ error: "Akses ditolak. Hanya developer yang dapat mereset kata sandi siswa." }, 403);
  }
  if (actorProf.account_status === "suspended") {
    return json({ error: "Akun developer sedang ditangguhkan." }, 403);
  }

  // 3. Parse & validasi payload
  let body: {
    target_user_id?: string;
    target_nis?: string;
    new_password?: string;
    reason?: string;
  };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Format permintaan tidak valid." }, 400);
  }

  const rawTargetUserId = String(body.target_user_id ?? "").trim();
  const cleanTargetNis = String(body.target_nis ?? "").replace(/[^0-9]/g, "").trim();
  const newPassword = String(body.new_password ?? "");
  const reason = String(body.reason ?? "").trim().slice(0, 240);

  if (!rawTargetUserId && !cleanTargetNis) {
    return json({ error: "Pilih akun siswa atau masukkan NIS siswa yang akan direset." }, 400);
  }
  if (rawTargetUserId && !isUuid(rawTargetUserId)) {
    return json({ error: "ID akun target tidak valid." }, 400);
  }
  if (cleanTargetNis && (cleanTargetNis.length < 3 || cleanTargetNis.length > 20)) {
    return json({ error: "Format NIS tidak valid." }, 400);
  }
  if (newPassword.trim().length < 6) {
    return json({ error: "Kata sandi baru minimal 6 karakter." }, 400);
  }
  if (newPassword.length > 72) {
    return json({ error: "Kata sandi baru maksimal 72 karakter." }, 400);
  }

  // 4. Cari profil akun siswa target berdasarkan user_id atau NIS
  let targetProfile: {
    id: string;
    display_name: string | null;
    email: string | null;
    role: string | null;
    nis: string | null;
  } | null = null;

  if (rawTargetUserId) {
    const { data, error } = await db
      .from("profiles")
      .select("id, display_name, email, role, nis")
      .eq("id", rawTargetUserId)
      .maybeSingle();
    if (error) {
      return json({ error: "Gagal membaca profil akun siswa." }, 500);
    }
    targetProfile = data;
  } else {
    const syntheticEmail = `nis.${cleanTargetNis}@siswa.jajanin.app`;
    const { data, error } = await db
      .from("profiles")
      .select("id, display_name, email, role, nis")
      .or(`nis.eq.${cleanTargetNis},email.eq.${syntheticEmail}`)
      .limit(1)
      .maybeSingle();
    if (error) {
      return json({ error: "Gagal mencari akun berdasarkan NIS." }, 500);
    }
    targetProfile = data;
  }

  if (!targetProfile) {
    return json(
      {
        error: cleanTargetNis
          ? `Akun siswa dengan NIS ${cleanTargetNis} belum terdaftar di Jajanin.`
          : "Akun siswa tidak ditemukan.",
      },
      404,
    );
  }

  // 5. Proteksi ketat: developer tidak boleh mereset akun developer / diri sendiri
  if (targetProfile.id === actorId || targetProfile.role === "developer") {
    return json(
      { error: "Akun developer tidak dapat direset melalui fitur reset sandi siswa." },
      403,
    );
  }

  const resolvedNis =
    (targetProfile.nis ?? "").trim() || extractNisFromDisplayName(targetProfile.display_name);

  // 6. Perbarui kata sandi langsung via Supabase Auth Admin API
  const { error: updateErr } = await db.auth.admin.updateUserById(targetProfile.id, {
    password: newPassword,
  });

  if (updateErr) {
    console.error("auth.admin.updateUserById failed:", updateErr);
    return json(
      { error: `Gagal mereset kata sandi: ${updateErr.message}` },
      500,
    );
  }

  // 7. Catat audit log (tanpa pernah menyimpan kata sandi)
  const { error: auditErr } = await db.from("password_reset_audit_logs").insert({
    target_user_id: targetProfile.id,
    target_nis: resolvedNis,
    actor_id: actorId,
    reason: reason || "Reset sandi siswa oleh developer",
  });
  if (auditErr) {
    console.error("password_reset_audit_logs insert warning:", auditErr);
  }

  return json({
    ok: true,
    user_id: targetProfile.id,
    nis: resolvedNis,
    display_name: targetProfile.display_name ?? "",
    email: targetProfile.email ?? "",
    message: "Kata sandi akun siswa berhasil direset.",
  });
});
