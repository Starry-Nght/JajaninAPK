// JAJANIN Real-Time Push Notification Dispatcher (Chat Messages)
// Trigger Postgres (pg_net) -> Edge Function -> FCM HTTP v1 API -> Chat Recipient Device(s)
//
// Authentication:
//   Header `x-notify-secret` verified in constant time against Edge Function secret
//   `NOTIFY_SECRET` or Vault key `notify_secret` (via service_role RPC `edge_config`).
//
// FCM Credentials (NEVER stored in Android app):
//   Read exclusively server-side from Edge Function secret `FCM_SERVICE_ACCOUNT_JSON`
//   or Vault key `fcm_service_account`.
//
// Reliability & Security:
//   - Strict recipient validation: ONLY the opposite party of `conversations` (never sender).
//   - Server-side idempotency via `public.push_notification_deliveries` (`chat:<message_id>`).
//   - Skips messages already read in an active chat room (`is_read === true`).
//   - Multi-device fanout with automatic invalid/UNREGISTERED token cleanup.
//   - Exponential retry on transient FCM 429 / 5xx errors.
//   - Strips internal [NIS:...] and [Kelas:...] tags for lockscreen privacy.

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

let cachedToken: { value: string; expiresAtMs: number; clientEmail: string } | null = null;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function timingSafeEqualStr(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function parseCleanIdentity(
  rawName: string | null | undefined,
  fallbackEmail: string | null | undefined,
): { cleanName: string; kelas: string } {
  const source = (rawName ?? "").trim();
  const kelasMatch = source.match(KELAS_TAG_RE);
  const kelas = kelasMatch?.[1]?.trim() ?? "";
  const stripped = source
    .replace(NIS_TAG_RE, "")
    .replace(/\s*\[Kelas:\s*([^\]]+)\]/gi, "")
    .trim();
  const emailPrefix = (fallbackEmail ?? "").split("@")[0]?.trim() ?? "";
  const safePrefix = emailPrefix && !/^\d+$/.test(emailPrefix) ? emailPrefix : "";
  return {
    cleanName: stripped || safePrefix || "Pengguna Jajanin",
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
  return typeof data === "string" ? data.trim() : "";
}

async function loadServiceAccount(
  db: ReturnType<typeof createClient>,
): Promise<{ sa: ServiceAccount | null; error?: string }> {
  const raw =
    (Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "").trim() ||
    await edgeConfig(db, "fcm_service_account");
  if (!raw) {
    return { sa: null, error: "fcm_service_account_not_configured" };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<ServiceAccount>;
    if (!parsed.project_id || !parsed.client_email || !parsed.private_key) {
      return { sa: null, error: "fcm_service_account_incomplete" };
    }
    return {
      sa: {
        project_id: String(parsed.project_id).trim(),
        client_email: String(parsed.client_email).trim(),
        private_key: String(parsed.private_key),
      },
    };
  } catch {
    return { sa: null, error: "fcm_service_account_invalid_json" };
  }
}

async function getFcmAccessToken(sa: ServiceAccount): Promise<string> {
  if (
    cachedToken &&
    cachedToken.clientEmail === sa.client_email &&
    cachedToken.expiresAtMs - Date.now() > 60_000
  ) {
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
    throw new Error(`FCM OAuth token exchange failed: ${res.status} ${await res.text()}`);
  }
  const payload = await res.json();
  cachedToken = {
    value: payload.access_token as string,
    expiresAtMs: Date.now() + Number(payload.expires_in ?? 3600) * 1000,
    clientEmail: sa.client_email,
  };
  return cachedToken.value;
}

type SendResult = "sent" | "removed" | "failed";

async function sendToDeviceWithRetry(
  accessToken: string,
  projectId: string,
  deviceToken: string,
  data: Record<string, string>,
  title: string,
  body: string,
  tag: string,
): Promise<SendResult> {
  const endpoint = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;
  const payload = JSON.stringify({
    message: {
      token: deviceToken,
      notification: { title, body },
      data,
      android: {
        priority: "HIGH",
        ttl: "86400s",
        collapse_key: tag.slice(0, 64),
        notification: {
          channel_id: NOTIFICATION_CHANNEL,
          sound: "default",
          icon: "ic_stat_orders",
          notification_priority: "PRIORITY_HIGH",
          visibility: "PUBLIC",
          tag: tag.slice(0, 64),
        },
      },
    },
  });

  const backoffs = [0, 300, 900];
  for (let attempt = 0; attempt < backoffs.length; attempt++) {
    if (backoffs[attempt] > 0) {
      await new Promise((r) => setTimeout(r, backoffs[attempt]));
    }
    try {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        body: payload,
      });
      if (res.ok) return "sent";

      const text = await res.text();
      const gone =
        res.status === 404 ||
        (res.status === 400 && (
          text.includes("UNREGISTERED") ||
          text.includes("not a valid FCM registration token") ||
          text.includes("Requested entity was not found") ||
          text.includes("SENDER_ID_MISMATCH")
        )) ||
        (res.status === 403 && text.includes("SENDER_ID_MISMATCH"));
      if (gone) return "removed";

      if ((res.status === 429 || res.status >= 500) && attempt < backoffs.length - 1) {
        console.warn(`FCM chat transient error (${res.status}), retrying attempt ${attempt + 2}`);
        continue;
      }

      console.error(`FCM chat send failed (${res.status}): ${text}`);
      return "failed";
    } catch (err) {
      if (attempt < backoffs.length - 1) {
        console.warn(`FCM chat network error, retrying attempt ${attempt + 2}: ${err}`);
        continue;
      }
      console.error(`FCM chat network exception: ${err}`);
      return "failed";
    }
  }
  return "failed";
}

async function claimDelivery(
  db: ReturnType<typeof createClient>,
  eventKey: string,
): Promise<{ claimed: boolean; reason?: string }> {
  const { error } = await db
    .from("push_notification_deliveries")
    .insert({
      event_key: eventKey,
      event_type: "chat_message",
      channel_id: NOTIFICATION_CHANNEL,
      status: "processing",
    });

  if (!error) return { claimed: true };
  if (error.code !== "23505") return { claimed: true };

  const { data: existing } = await db
    .from("push_notification_deliveries")
    .select("status, updated_at")
    .eq("event_key", eventKey)
    .maybeSingle();

  if (!existing) return { claimed: true };
  if (existing.status === "sent" || existing.status === "skipped") {
    return { claimed: false, reason: "duplicate_event" };
  }
  const ageMs = Date.now() - new Date(existing.updated_at).getTime();
  if (existing.status === "processing" && ageMs < 60_000) {
    return { claimed: false, reason: "already_processing" };
  }

  await db
    .from("push_notification_deliveries")
    .update({ status: "processing", updated_at: new Date().toISOString() })
    .eq("event_key", eventKey);
  return { claimed: true };
}

async function finishDelivery(
  db: ReturnType<typeof createClient>,
  eventKey: string,
  stats: {
    recipientCount: number;
    sentCount: number;
    failedCount: number;
    removedTokens: number;
    status: "sent" | "skipped" | "failed";
    detail?: string;
  },
): Promise<void> {
  await db
    .from("push_notification_deliveries")
    .update({
      recipient_count: stats.recipientCount,
      sent_count: stats.sentCount,
      failed_count: stats.failedCount,
      removed_tokens: stats.removedTokens,
      status: stats.status,
      detail: stats.detail ?? null,
      updated_at: new Date().toISOString(),
    })
    .eq("event_key", eventKey);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "server not configured" }, 500);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const notifySecret =
    (Deno.env.get("NOTIFY_SECRET") ?? "").trim() ||
    await edgeConfig(db, "notify_secret");
  const incomingSecret = (req.headers.get("x-notify-secret") ?? "").trim();
  if (!notifySecret || !incomingSecret || !timingSafeEqualStr(incomingSecret, notifySecret)) {
    return json({ error: "unauthorized" }, 401);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  if (String(body.event_type ?? "").trim().toLowerCase() === "health_check") {
    const { sa, error } = await loadServiceAccount(db);
    return json({
      ok: true,
      notify_secret_verified: true,
      fcm_configured: Boolean(sa),
      fcm_status: sa ? "ready" : (error ?? "missing"),
      channel: NOTIFICATION_CHANNEL,
    });
  }

  const messageId = typeof body.message_id === "string" ? body.message_id.trim() : "";
  if (!messageId) {
    return json({ error: "message_id required" }, 400);
  }

  const eventKey = `chat:${messageId}`;
  const claim = await claimDelivery(db, eventKey);
  if (!claim.claimed) {
    return json({ ok: true, sent: 0, skipped: true, reason: claim.reason ?? "duplicate_event" });
  }

  // Brief delay so if recipient already has this exact chat room open, markRead runs first.
  await new Promise((r) => setTimeout(r, 350));

  const { data: message, error: messageError } = await db
    .from("chat_messages")
    .select("id, conversation_id, sender_id, body, message_type, is_read, created_at")
    .eq("id", messageId)
    .maybeSingle();

  if (messageError) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 1,
      removedTokens: 0,
      status: "failed",
      detail: messageError.message,
    });
    return json({ error: messageError.message }, 500);
  }
  if (!message) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "message_not_found",
    });
    return json({ error: "message not found" }, 404);
  }
  if (
    message.message_type === "order_card" ||
    message.message_type === "product_card" ||
    message.message_type === "auto_reply"
  ) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: `ignored_message_type:${message.message_type}`,
    });
    return json({ ok: true, sent: 0, reason: "system_card_ignored" });
  }
  if (message.is_read === true) {
    await finishDelivery(db, eventKey, {
      recipientCount: 1,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "already_read_in_active_chat",
    });
    return json({ ok: true, sent: 0, reason: "already_read_in_active_chat" });
  }

  const { data: convo, error: convoError } = await db
    .from("conversations")
    .select("id, conversation_type, order_id, product_id, buyer_id, seller_id")
    .eq("id", message.conversation_id)
    .maybeSingle();

  if (convoError || !convo) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: convoError?.message ?? "conversation_not_found",
    });
    return json({ error: convoError?.message ?? "conversation not found" }, 404);
  }

  // Strict recipient validation: ONLY the opposite participant, NEVER the sender.
  const senderIsBuyer = message.sender_id === convo.buyer_id;
  const senderIsSeller = message.sender_id === convo.seller_id;
  if (!senderIsBuyer && !senderIsSeller) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "sender_not_participant",
    });
    return json({ ok: true, sent: 0, reason: "sender_not_participant" });
  }

  const recipientId = senderIsBuyer ? convo.seller_id : convo.buyer_id;
  const targetRole = senderIsBuyer ? "seller" : "buyer";
  if (!recipientId || recipientId === message.sender_id) {
    await finishDelivery(db, eventKey, {
      recipientCount: 0,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "invalid_recipient",
    });
    return json({ ok: true, sent: 0, reason: "invalid_recipient" });
  }

  const [{ data: sender }, { data: stall }, { data: devices, error: devicesError }] =
    await Promise.all([
      db
        .from("profiles")
        .select("display_name, email")
        .eq("id", message.sender_id)
        .maybeSingle(),
      senderIsSeller
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
    await finishDelivery(db, eventKey, {
      recipientCount: 1,
      sentCount: 0,
      failedCount: 1,
      removedTokens: 0,
      status: "failed",
      detail: devicesError.message,
    });
    return json({ error: devicesError.message }, 500);
  }
  if (!devices || devices.length === 0) {
    await finishDelivery(db, eventKey, {
      recipientCount: 1,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "no_registered_devices",
    });
    return json({ ok: true, sent: 0, reason: "no_registered_devices" });
  }

  const { sa: serviceAccount, error: saError } = await loadServiceAccount(db);
  if (!serviceAccount) {
    await finishDelivery(db, eventKey, {
      recipientCount: 1,
      sentCount: 0,
      failedCount: devices.length,
      removedTokens: 0,
      status: "failed",
      detail: saError ?? "fcm_service_account_not_configured",
    });
    return json({ ok: false, error: saError ?? "fcm_service_account_not_configured" }, 503);
  }

  const { cleanName, kelas } = parseCleanIdentity(sender?.display_name, sender?.email);
  const stallName = (stall?.name ?? "").trim();
  const displaySender = senderIsSeller && stallName ? stallName : cleanName;
  const subtitle = senderIsSeller ? cleanName : kelas;
  const preview = ((message.body as string) ?? "").replace(/\s+/g, " ").trim().slice(0, 140) || "Mengirim pesan baru";
  const title = displaySender;
  const notificationBody = subtitle && subtitle !== displaySender
    ? `${subtitle}: ${preview}`
    : preview;

  const data: Record<string, string> = {
    type: "chat_message",
    event_key: eventKey,
    dedup_key: eventKey,
    channel_id: NOTIFICATION_CHANNEL,
    message_id: String(message.id),
    conversation_id: String(convo.id ?? ""),
    conversation_type: String(convo.conversation_type ?? "order"),
    order_id: convo.order_id ? String(convo.order_id) : "",
    product_id: convo.product_id ? String(convo.product_id) : "",
    sender_name: displaySender,
    sender_subtitle: subtitle,
    message_preview: preview,
    target_role: targetRole,
    target_screen: "chat",
    title,
    body: notificationBody,
  };

  let accessToken: string;
  try {
    accessToken = await getFcmAccessToken(serviceAccount);
  } catch (e) {
    console.error(`FCM chat auth failed: ${e}`);
    await finishDelivery(db, eventKey, {
      recipientCount: 1,
      sentCount: 0,
      failedCount: devices.length,
      removedTokens: 0,
      status: "failed",
      detail: "fcm_auth_failed",
    });
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

    const result = await sendToDeviceWithRetry(
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

  await finishDelivery(db, eventKey, {
    recipientCount: 1,
    sentCount: sent,
    failedCount: failed,
    removedTokens: removed,
    status: sent > 0 ? "sent" : (failed > 0 ? "failed" : "skipped"),
    detail: removed > 0 && sent === 0 && failed === 0 ? "all_tokens_unregistered" : undefined,
  });

  return json({ ok: true, sent, removed, failed });
});
