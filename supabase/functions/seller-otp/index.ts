import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function edgeConfig(
  db: ReturnType<typeof createClient>,
  key: string,
): Promise<string> {
  const { data, error } = await db.rpc("edge_config", { p_key: key });
  if (error) return "";
  return typeof data === "string" ? data : "";
}

async function sha256Hex(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function generateSixDigitOtp(): string {
  const arr = new Uint32Array(1);
  crypto.getRandomValues(arr);
  return String(100000 + (arr[0] % 900000));
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

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    return json({ error: "Masuk dulu untuk mengaktifkan Mode Penjual." }, 401);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: userData, error: authErr } = await db.auth.getUser(token);
  if (authErr || !userData?.user) {
    return json({ error: "Sesi tidak valid. Silakan masuk ulang." }, 401);
  }
  const userId = userData.user.id;

  const { data: prof } = await db
    .from("profiles")
    .select("account_status")
    .eq("id", userId)
    .maybeSingle();
  if (prof?.account_status === "suspended") {
    return json({ error: "Akun ditangguhkan." }, 403);
  }

  let body: {
    action?: string;
    email?: string;
    code?: string;
    stall_name?: string;
    phone?: string;
    room?: string;
  };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Format permintaan tidak valid." }, 400);
  }

  const action = String(body.action ?? "").trim().toLowerCase();
  const cleanEmail = String(body.email ?? "").trim().toLowerCase();
  if (!cleanEmail || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
    return json({ error: "Masukkan alamat email aktif yang valid." }, 400);
  }
  if (cleanEmail.endsWith("@siswa.jajanin.app")) {
    return json({ error: "Gunakan alamat email aktif milikmu (mis. Gmail) untuk menerima kode OTP." }, 400);
  }

  if (action === "request") {
    const resendKey = Deno.env.get("RESEND_API_KEY") || await edgeConfig(db, "resend_api_key");
    if (!resendKey) {
      // Fallback ke built-in Email OTP Supabase Auth di client
      return json({
        ok: true,
        provider: "supabase_auth",
        message: "Menggunakan verifikasi OTP email Supabase.",
      });
    }

    // Rate-limit: 1 OTP per 55 detik
    const cutoffIso = new Date(Date.now() - 55_000).toISOString();
    const { data: recent } = await db
      .from("seller_email_otps")
      .select("id")
      .eq("user_id", userId)
      .gte("created_at", cutoffIso)
      .limit(1);
    if (recent && recent.length > 0) {
      return json({ error: "Tunggu 1 menit sebelum meminta kode OTP baru." }, 429);
    }

    const otp = generateSixDigitOtp();
    const codeHash = await sha256Hex(`${userId}:${cleanEmail}:${otp}`);
    const expiresAt = new Date(Date.now() + 10 * 60_000).toISOString();

    const { error: insertErr } = await db.from("seller_email_otps").insert({
      user_id: userId,
      email: cleanEmail,
      code_hash: codeHash,
      expires_at: expiresAt,
    });
    if (insertErr) {
      return json({ error: "Gagal menyimpan kode OTP." }, 500);
    }

    const fromAddress =
      Deno.env.get("RESEND_FROM_EMAIL") ||
      await edgeConfig(db, "resend_from_email") ||
      "Jajanin <onboarding@resend.dev>";

    const mailRes = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${resendKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: fromAddress,
        to: [cleanEmail],
        subject: `Kode OTP Aktivasi Penjual Jajanin: ${otp}`,
        html: `
          <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 460px; margin: 0 auto; padding: 24px; color: #1b2335;">
            <h2 style="color: #0063d6; margin: 0 0 8px;">Aktivasi Mode Penjual Jajanin</h2>
            <p style="margin: 0 0 18px; color: #556070;">Masukkan kode OTP 6 digit berikut di aplikasi Jajanin untuk membuka warungmu:</p>
            <div style="font-size: 32px; font-weight: 700; letter-spacing: 8px; background: #eef5ff; color: #0063d6; padding: 16px 20px; border-radius: 12px; text-align: center;">${otp}</div>
            <p style="margin: 18px 0 0; font-size: 12px; color: #707889;">Kode berlaku 10 menit. Jangan bagikan kode ini kepada siapa pun.</p>
          </div>
        `,
      }),
    });

    if (!mailRes.ok) {
      const errText = await mailRes.text();
      console.error("Resend error:", mailRes.status, errText);
      return json({ error: "Gagal mengirim email OTP. Coba lagi beberapa saat." }, 502);
    }

    return json({
      ok: true,
      provider: "resend",
      message: `Kode OTP 6 digit telah dikirim ke ${cleanEmail}.`,
    });
  }

  if (action === "verify") {
    const cleanCode = String(body.code ?? "").replace(/[^0-9]/g, "").trim();
    const stallName = String(body.stall_name ?? "").trim();
    const rawPhone = String(body.phone ?? "").trim();
    const room = String(body.room ?? "").trim();

    if (cleanCode.length !== 6) {
      return json({ error: "Kode OTP harus 6 digit angka." }, 400);
    }
    if (stallName.length < 3 || stallName.length > 40) {
      return json({ error: "Nama warung wajib 3 sampai 40 karakter." }, 400);
    }
    const normalizedPhone = normalizePhone(rawPhone);
    if (!/^\+628[0-9]{7,13}$/.test(normalizedPhone)) {
      return json({ error: "Nomor WhatsApp aktif tidak valid (gunakan 08... atau +62...)." }, 400);
    }

    const { data: otpRow, error: otpErr } = await db
      .from("seller_email_otps")
      .select("*")
      .eq("user_id", userId)
      .eq("email", cleanEmail)
      .is("consumed_at", null)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (otpErr || !otpRow) {
      return json({ error: "Kode OTP tidak ditemukan. Minta kode baru." }, 400);
    }
    if (new Date(otpRow.expires_at).getTime() < Date.now()) {
      return json({ error: "Kode OTP sudah kedaluwarsa. Minta kode baru." }, 400);
    }
    if ((otpRow.attempts ?? 0) >= 5) {
      return json({ error: "Terlalu banyak percobaan salah. Minta kode OTP baru." }, 429);
    }

    const expectedHash = await sha256Hex(`${userId}:${cleanEmail}:${cleanCode}`);
    if (expectedHash !== otpRow.code_hash) {
      await db
        .from("seller_email_otps")
        .update({ attempts: (otpRow.attempts ?? 0) + 1 })
        .eq("id", otpRow.id);
      return json({ error: "Kode OTP salah. Periksa kembali email kamu." }, 400);
    }

    await db
      .from("seller_email_otps")
      .update({ consumed_at: new Date().toISOString() })
      .eq("id", otpRow.id);

    await db.from("profile_phones").upsert(
      { user_id: userId, phone: normalizedPhone, updated_at: new Date().toISOString() },
      { onConflict: "user_id" },
    );

    const { error: profUpdateErr } = await db
      .from("profiles")
      .update({
        is_seller_verified: true,
        seller_verified_email: cleanEmail,
        seller_verified_at: new Date().toISOString(),
      })
      .eq("id", userId);

    if (profUpdateErr) {
      return json({ error: `Gagal mengaktifkan profil penjual: ${profUpdateErr.message}` }, 500);
    }

    const { data: existingStall } = await db
      .from("stalls")
      .select("id, location")
      .eq("owner_id", userId)
      .order("created_at", { ascending: true })
      .limit(1)
      .maybeSingle();

    let stallId = existingStall?.id ?? "";
    if (!stallId) {
      const { data: newStall, error: stallErr } = await db
        .from("stalls")
        .insert({
          owner_id: userId,
          name: stallName,
          description: `[Telp:${normalizedPhone}]`,
          location: room || "Kantin Sekolah",
          is_open: true,
        })
        .select("id")
        .single();
      if (stallErr) {
        return json({ error: `Gagal membuat warung: ${stallErr.message}` }, 500);
      }
      stallId = newStall.id;
    } else {
      await db
        .from("stalls")
        .update({
          name: stallName,
          location: room || existingStall?.location || "Kantin Sekolah",
          is_open: true,
        })
        .eq("id", stallId);
    }

    return json({
      ok: true,
      stall_id: stallId,
      stall_name: stallName,
      phone: normalizedPhone,
      is_seller_verified: true,
    });
  }

  return json({ error: "Action tidak dikenali." }, 400);
});
