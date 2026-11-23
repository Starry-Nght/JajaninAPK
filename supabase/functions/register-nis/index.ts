import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^0-9]/g, "");
  if (!digits) return "";
  if (digits.startsWith("0")) return "+62" + digits.slice(1);
  if (digits.startsWith("62")) return "+" + digits;
  if (digits.startsWith("8")) return "+62" + digits;
  return "+" + digits;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "Server belum dikonfigurasi." }, 500);
  }

  let body: { nis?: string; password?: string; phone?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Format permintaan tidak valid." }, 400);
  }

  const cleanNis = String(body.nis ?? "").replace(/[^0-9]/g, "").trim();
  const password = String(body.password ?? "");
  const rawPhone = String(body.phone ?? "").trim();

  if (cleanNis.length < 3 || cleanNis.length > 20) {
    return json({ error: "NIS tidak valid." }, 400);
  }
  if (password.length < 6) {
    return json({ error: "Kata sandi minimal 6 karakter." }, 400);
  }

  let normalizedPhone = "";
  if (rawPhone) {
    normalizedPhone = normalizePhone(rawPhone);
    if (!/^\+628[0-9]{7,13}$/.test(normalizedPhone)) {
      return json({ error: "Nomor WhatsApp tidak valid. Gunakan format 08... atau +62..." }, 400);
    }
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  // 1. Validasi NIS di tabel master public.students
  const { data: student, error: stuErr } = await db
    .from("students")
    .select("nis, full_name, class_name, is_active")
    .eq("nis", cleanNis)
    .eq("is_active", true)
    .maybeSingle();

  if (stuErr) {
    return json({ error: "Gagal memeriksa database siswa." }, 500);
  }
  if (!student) {
    return json({ error: "NIS tidak terdaftar di database siswa SMKN 8 Semarang." }, 404);
  }

  // 2. Pastikan 1 NIS = 1 akun
  const { data: existingProfile } = await db
    .from("profiles")
    .select("id")
    .eq("nis", cleanNis)
    .maybeSingle();

  if (existingProfile) {
    return json({ error: "NIS ini sudah memiliki akun. Silakan langsung masuk dengan kata sandi." }, 409);
  }

  const authEmail = `nis.${student.nis}@siswa.jajanin.app`;
  const packedDisplayName = `${student.full_name.trim()} [NIS:${student.nis}] [Kelas:${student.class_name.trim()}]`;

  // 3. Buat user Auth terkonfirmasi (tanpa butuh klik link email sintetis)
  const { data: created, error: createErr } = await db.auth.admin.createUser({
    email: authEmail,
    password,
    email_confirm: true,
    user_metadata: {
      display_name: packedDisplayName,
      nis: student.nis,
      class_name: student.class_name,
    },
  });

  if (createErr || !created.user) {
    const msg = createErr?.message ?? "Gagal membuat akun NIS.";
    if (msg.toLowerCase().includes("already") || msg.toLowerCase().includes("registered")) {
      return json({ error: "NIS ini sudah terdaftar. Silakan masuk dengan kata sandi." }, 409);
    }
    return json({ error: msg }, 400);
  }

  const userId = created.user.id;

  // 4. Pastikan baris profiles memiliki nis + display_name resmi dari database siswa
  await db
    .from("profiles")
    .upsert(
      {
        id: userId,
        email: authEmail,
        display_name: packedDisplayName,
        nis: student.nis,
        role: "buyer",
      },
      { onConflict: "id" },
    );

  // 5. Simpan nomor HP awal jika diisi
  if (normalizedPhone) {
    await db
      .from("profile_phones")
      .upsert(
        {
          user_id: userId,
          phone: normalizedPhone,
          updated_at: new Date().toISOString(),
        },
        { onConflict: "user_id" },
      );
  }

  return json({
    ok: true,
    user_id: userId,
    nis: student.nis,
    full_name: student.full_name,
    class_name: student.class_name,
    auth_email: authEmail,
  });
});
