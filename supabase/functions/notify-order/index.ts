import { createClient } from "jsr:@supabase/supabase-js@2";
import { importPKCS8, SignJWT } from "npm:jose@5";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const NOTIFY_SECRET = Deno.env.get("NOTIFY_SECRET");
const FCM_SERVICE_ACCOUNT_JSON = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");

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
  orderId: string,
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
          data: {
            type: "new_order",
            order_id: orderId,
          },
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
  if (!NOTIFY_SECRET || req.headers.get("x-notify-secret") !== NOTIFY_SECRET) {
    return json({ error: "unauthorized" }, 401);
  }
  if (!FCM_SERVICE_ACCOUNT_JSON || !SERVICE_ROLE_KEY || !SUPABASE_URL) {
    return json({ error: "server not configured" }, 500);
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

  let serviceAccount: ServiceAccount;
  try {
    serviceAccount = JSON.parse(FCM_SERVICE_ACCOUNT_JSON);
  } catch {
    return json({ error: "invalid FCM service account json" }, 500);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

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
      db.from("admin_devices").select("id, fcm_token"),
    ]);
  if (devicesError) {
    return json({ error: devicesError.message }, 500);
  }
  if (!devices || devices.length === 0) {
    return json({ ok: true, sent: 0, reason: "no registered devices" });
  }

  const buyerName = profile?.display_name || profile?.email || "Pembeli";
  const total = new Intl.NumberFormat("id-ID").format(order.total_idr);
  const title = "Pesanan baru";
  const body = `${buyerName} - kode ${order.pickup_code} - Rp${total}`;

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
  for (const device of devices) {
    const result = await sendToDevice(
      accessToken,
      serviceAccount.project_id,
      device.fcm_token,
      orderId,
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
