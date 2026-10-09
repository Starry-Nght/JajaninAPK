// Trigger Postgres (pg_net) -> fungsi ini -> FCM v1 ke HP admin.
//
// Autentikasi: header x-notify-secret harus cocok dengan secret di vault
// ('notify_secret') yang dibaca lewat RPC edge_config (service_role only).
// Konfigurasi tidak memakai env var sama sekali agar bisa dijalankan dari
// MCP/Dashboard tanpa `supabase secrets set`:
//   vault 'notify_secret'        -> header autentikasi trigger
//   vault 'fcm_service_account'  -> JSON service account Firebase (FCM v1)
// Env var NOTIFY_SECRET / FCM_SERVICE_ACCOUNT_JSON tetap didukung sebagai
// fallback bila suatu saat di-set lewat CLI.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { importPKCS8, SignJWT } from "npm:jose@5";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const NOTIFICATION_CHANNEL = "jajanin_orders";

let cachedToken: { value: string; expiresAtMs: number } | null = null;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Baca satu key dari vault lewat RPC edge_config (hanya service_role yang boleh). */
async function edgeConfig(
  db: ReturnType<typeof createClient>,
  key: string,
): Promise<string> {
  const { data, error } = await db.rpc("edge_config", { p_key: key });
  if (error) {
    console.error(`edge_config(${key}) failed: ${error.message}`);
    return "";
  }
  return typeof data === "string" ? data : "";
}

async function getFcmAccessToken(sa: ServiceAccount): Promise<string> {
  if (cachedToken && cachedToken.expiresAtMs - Date.now() > 60_000) {
    return cachedToken.value;
  }
  const privateKey = sa.private_key.replace(/\\n/g, "\n");
  const key = await importPKCS8(privateKey, "RS256");
  const issuedAt = Math.floor(Date.now() / 1000);
  const assertion = await new SignJWT({ scope: FCM_SCOPE })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setSubject(sa.client_email)
    .setAudience(TOKEN_URL)
    .setIssuedAt(issuedAt)
    .setExpirationTime(issuedAt + 3600)
    .sign(key);

  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) {
    throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  }
  const payload = await res.json();
  cachedToken = {
    value: payload.access_token as string,
    expiresAtMs: Date.now() + (payload.expires_in as number) * 1000,
  };
  return cachedToken.value;
}

type SendResult = "sent" | "removed" | "failed";

async function sendToDevice(
  accessToken: string,
  projectId: string,
  deviceToken: string,
  data: Record<string, string>,
  title: string,
  body: string,
): Promise<SendResult> {
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token: deviceToken,
          notification: { title, body },
          data,
          android: {
            priority: "HIGH",
            notification: {
              channel_id: NOTIFICATION_CHANNEL,
              sound: "default",
            },
          },
        },
      }),
    },
  );
  if (res.ok) return "sent";
  const text = await res.text();
  const gone =
    res.status === 404 ||
    (res.status === 400 && text.includes("UNREGISTERED")) ||
    text.includes("not a valid FCM registration token");
  if (gone) return "removed";
  console.error(`FCM send failed (${res.status}): ${text}`);
  return "failed";
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "server not configured" }, 500);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

  const notifySecret =
    Deno.env.get("NOTIFY_SECRET") || await edgeConfig(db, "notify_secret");
  if (!notifySecret || req.headers.get("x-notify-secret") !== notifySecret) {
    return json({ error: "unauthorized" }, 401);
  }

  let orderId = "";
  try {
    const body = await req.json();
    if (typeof body?.order_id === "string") orderId = body.order_id;
  } catch {
    return json({ error: "invalid json" }, 400);
  }
  if (!orderId) {
    return json({ error: "order_id required" }, 400);
  }

  const { data: order, error: orderError } = await db
    .from("orders")
    .select("id, user_id, status, total_idr, pickup_code")
    .eq("id", orderId)
    .maybeSingle();
  if (orderError) {
    return json({ error: orderError.message }, 500);
  }
  if (!order) {
    return json({ error: "order not found" }, 404);
  }

  const [{ data: profile }, { data: devices, error: devicesError }] =
    await Promise.all([
      db
        .from("profiles")
        .select("display_name, email")
        .eq("id", order.user_id)
        .maybeSingle(),
      db.from("admin_devices").select("id, user_id, fcm_token"),
    ]);
  if (devicesError) {
    return json({ error: devicesError.message }, 500);
  }

  // Pertahanan berlapis: hanya device milik akun role 'seller' (admin) yang
  // menerima notifikasi, walau RLS sudah mencegah non-admin mendaftarkan token.
  const { data: admins, error: adminsError } = await db
    .from("profiles")
    .select("id")
    .eq("role", "seller");
  if (adminsError) {
    return json({ error: adminsError.message }, 500);
  }
  const adminIds = new Set((admins ?? []).map((row) => row.id));
  const targets = (devices ?? []).filter((device) =>
    adminIds.has(device.user_id)
  );
  if (targets.length === 0) {
    return json({ ok: true, sent: 0, reason: "no registered devices" });
  }

  const fcmJson =
    Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ||
    await edgeConfig(db, "fcm_service_account");
  if (!fcmJson) {
    return json({ error: "server not configured" }, 500);
  }
  let serviceAccount: ServiceAccount;
  try {
    serviceAccount = JSON.parse(fcmJson);
  } catch {
    return json({ error: "invalid FCM service account json" }, 500);
  }

  const buyerName = profile?.display_name || profile?.email || "Pembeli";
  const title = "Pesanan Baru";
  const body = `Ada pesanan baru dari ${buyerName}`;
  const data: Record<string, string> = {
    type: "new_order",
    order_id: order.id,
    buyer_name: buyerName,
    pickup_code: order.pickup_code,
  };

  let accessToken: string;
  try {
    accessToken = await getFcmAccessToken(serviceAccount);
  } catch (e) {
    console.error(`FCM auth failed: ${e}`);
    return json({ error: "fcm auth failed" }, 500);
  }

  let sent = 0;
  let removed = 0;
  let failed = 0;
  for (const device of targets) {
    const result = await sendToDevice(
      accessToken,
      serviceAccount.project_id,
      device.fcm_token,
      data,
      title,
      body,
    );
    if (result === "sent") {
      sent++;
    } else if (result === "removed") {
      const { error } = await db
        .from("admin_devices")
        .delete()
        .eq("id", device.id);
      if (error) {
        console.error(`device cleanup failed: ${error.message}`);
      }
      removed++;
    } else {
      failed++;
    }
  }

  return json({ ok: true, sent, removed, failed });
});
