// Trigger Postgres (pg_net) -> fungsi ini -> FCM v1 ke penerima chat.
//
// Pola autentikasi + secret sama dengan notify-order:
//   vault 'notify_secret'        -> header autentikasi trigger
//   vault 'fcm_service_account'  -> JSON service account Firebase (FCM v1)
// Env var NOTIFY_SECRET / FCM_SERVICE_ACCOUNT_JSON tetap didukung fallback.
//
// Privasi: tag internal [NIS:...] dan [Kelas:...] dibersihkan dari judul.

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
const NOTIFICATION_CHANNEL = "jajanin_chat";

const NIS_TAG_RE = /\s*\[NIS:\s*([0-9]+)\]/gi;
const KELAS_TAG_RE = /\s*\[Kelas:\s*([^\]]+)\]/i;

let cachedToken: { value: string; expiresAtMs: number } | null = null;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function parseCleanIdentity(rawName: string | null | undefined, fallbackEmail: string | null | undefined): {
  cleanName: string;
  kelas: string;
} {
  const source = (rawName ?? "").trim();
  const kelasMatch = source.match(KELAS_TAG_RE);
  const kelas = kelasMatch?.[1]?.trim() ?? "";
  const stripped = source
    .replace(NIS_TAG_RE, "")
    .replace(/\s*\[Kelas:\s*([^\]]+)\]/gi, "")
    .trim();
  const emailPrefix = (fallbackEmail ?? "").split("@")[0]?.trim() ?? "";
  return {
    cleanName: stripped || emailPrefix || "Pengguna Jajanin",
    kelas,
  };
}

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
  tag: string,
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
            collapse_key: tag,
            notification: {
              channel_id: NOTIFICATION_CHANNEL,
              sound: "default",
              tag,
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

  let messageId = "";
  try {
    const body = await req.json();
    if (typeof body?.message_id === "string") messageId = body.message_id;
  } catch {
    return json({ error: "invalid json" }, 400);
  }
  if (!messageId) {
    return json({ error: "message_id required" }, 400);
  }

  await new Promise((r) => setTimeout(r, 350));

  const { data: message, error: messageError } = await db
    .from("chat_messages")
    .select("id, conversation_id, sender_id, body, is_read, created_at")
    .eq("id", messageId)
    .maybeSingle();
  if (messageError) {
    return json({ error: messageError.message }, 500);
  }
  if (!message) {
    return json({ error: "message not found" }, 404);
  }
  if (message.is_read === true) {
    return json({ ok: true, sent: 0, reason: "already_read_in_active_chat" });
  }

  const { data: convo, error: convoError } = await db
    .from("conversations")
    .select("id, conversation_type, order_id, product_id, buyer_id, seller_id")
    .eq("id", message.conversation_id)
    .maybeSingle();
  if (convoError || !convo) {
    return json({ error: convoError?.message ?? "conversation not found" }, 404);
  }

  const recipientId = message.sender_id === convo.buyer_id
    ? convo.seller_id
    : convo.buyer_id;

  if (!recipientId || recipientId === message.sender_id) {
    return json({ ok: true, sent: 0, reason: "invalid_recipient" });
  }

  const [{ data: sender }, { data: stall }, { data: devices, error: devicesError }] =
    await Promise.all([
      db
        .from("profiles")
        .select("display_name, email")
        .eq("id", message.sender_id)
        .maybeSingle(),
      message.sender_id === convo.seller_id
        ? db
            .from("stalls")
            .select("name")
            .eq("owner_id", convo.seller_id)
            .limit(1)
            .maybeSingle()
        : Promise.resolve({ data: null }),
      db.from("admin_devices")
        .select("id, user_id, fcm_token")
        .eq("user_id", recipientId),
    ]);
  if (devicesError) {
    return json({ error: devicesError.message }, 500);
  }
  if (!devices || devices.length === 0) {
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

  const { cleanName, kelas } = parseCleanIdentity(sender?.display_name, sender?.email);
  const stallName = (stall?.name ?? "").trim();
  const subtitle = kelas || stallName || "";
  const preview = ((message.body as string) ?? "").trim().slice(0, 100);
  const title = cleanName;
  const notificationBody = subtitle ? `${subtitle}\n${preview}` : preview;

  const data: Record<string, string> = {
    type: "chat_message",
    conversation_id: String(convo.id ?? ""),
    conversation_type: String(convo.conversation_type ?? "order"),
    order_id: convo.order_id ? String(convo.order_id) : "",
    product_id: convo.product_id ? String(convo.product_id) : "",
    sender_name: cleanName,
    sender_subtitle: subtitle,
    message_preview: preview,
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
  const seenTokens = new Set<string>();
  const tag = `chat_${convo.id}`;

  for (const device of devices) {
    const token = String(device.fcm_token ?? "").trim();
    if (!token || seenTokens.has(token)) continue;
    seenTokens.add(token);

    const result = await sendToDevice(
      accessToken,
      serviceAccount.project_id,
      token,
      data,
      title,
      notificationBody,
      tag,
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
