// JAJANIN Real-Time Push Notification Dispatcher (Orders & Store Activity)
// Trigger Postgres (pg_net) -> Edge Function -> FCM HTTP v1 API -> Android Device(s)
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
//   - Strict per-recipient targeting (Buyer = order.user_id, Seller = stall.owner_id).
//   - Server-side idempotency via `public.push_notification_deliveries` (prevents duplicates).
//   - Multi-device fanout per user with automatic stale/UNREGISTERED token cleanup.
//   - Safe exponential retry on transient FCM 429 / 5xx failures.
//   - Privacy sanitization: strips internal [NIS:...] and [Kelas:...] tags from notifications.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { importPKCS8, SignJWT } from "npm:jose@5";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

interface DeviceRow {
  id: string;
  user_id: string;
  fcm_token: string;
}

interface PushJob {
  eventKey: string;
  eventType: string;
  channelId: string;
  recipientUserIds: string[];
  title: string;
  body: string;
  tag: string;
  data: Record<string, string>;
}

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

// Android Notification Channels — MUST match NotificationHelper.kt & AndroidManifest.xml
const CHANNEL_ORDERS = "jajanin_orders_v2";
const CHANNEL_STORE = "jajanin_store_activity";

const NIS_TAG_RE = /\s*\[NIS:\s*([0-9]+)\]/gi;
const KELAS_TAG_RE = /\s*\[Kelas:\s*([^\]]+)\]/gi;

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

function cleanDisplayName(
  rawName: string | null | undefined,
  fallbackEmail: string | null | undefined,
  fallbackLabel = "Pembeli",
): string {
  const stripped = (rawName ?? "")
    .replace(NIS_TAG_RE, "")
    .replace(KELAS_TAG_RE, "")
    .trim();
  if (stripped) return stripped;
  const emailPrefix = (fallbackEmail ?? "").split("@")[0]?.trim() ?? "";
  if (emailPrefix && !/^\d+$/.test(emailPrefix)) return emailPrefix;
  return fallbackLabel;
}

function formatRupiah(amount: number | null | undefined): string {
  const safe = Math.max(0, Math.round(Number(amount ?? 0)));
  return "Rp " + safe.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
}

function truncateText(text: string, maxLen: number): string {
  const clean = text.replace(/\s+/g, " ").trim();
  if (clean.length <= maxLen) return clean;
  return clean.slice(0, Math.max(1, maxLen - 1)).trimEnd() + "...";
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
  channelId: string,
  title: string,
  body: string,
  tag: string,
  data: Record<string, string>,
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
          channel_id: channelId,
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
      const isGone =
        res.status === 404 ||
        (res.status === 400 && (
          text.includes("UNREGISTERED") ||
          text.includes("not a valid FCM registration token") ||
          text.includes("Requested entity was not found") ||
          text.includes("SENDER_ID_MISMATCH")
        )) ||
        res.status === 403 && text.includes("SENDER_ID_MISMATCH");

      if (isGone) return "removed";

      const isTransient = res.status === 429 || res.status >= 500;
      if (isTransient && attempt < backoffs.length - 1) {
        console.warn(`FCM transient error (${res.status}), retrying attempt ${attempt + 2}`);
        continue;
      }

      console.error(`FCM send failed (${res.status}): ${text}`);
      return "failed";
    } catch (err) {
      if (attempt < backoffs.length - 1) {
        console.warn(`FCM network error, retrying attempt ${attempt + 2}: ${err}`);
        continue;
      }
      console.error(`FCM send network exception: ${err}`);
      return "failed";
    }
  }
  return "failed";
}

async function claimDelivery(
  db: ReturnType<typeof createClient>,
  eventKey: string,
  eventType: string,
  channelId: string,
): Promise<{ claimed: boolean; reason?: string }> {
  const { error } = await db
    .from("push_notification_deliveries")
    .insert({
      event_key: eventKey,
      event_type: eventType,
      channel_id: channelId,
      status: "processing",
    });

  if (!error) return { claimed: true };
  if (error.code !== "23505") {
    console.warn(`push_notification_deliveries insert warning (${eventKey}): ${error.message}`);
    // Do not block push delivery if audit table had an unexpected transient error.
    return { claimed: true };
  }

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
  const { error } = await db
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
  if (error) {
    console.warn(`push_notification_deliveries update failed (${eventKey}): ${error.message}`);
  }
}

async function executePushJob(
  db: ReturnType<typeof createClient>,
  job: PushJob,
  saLoader: () => Promise<{ sa: ServiceAccount | null; accessToken: string | null; error?: string }>,
): Promise<Record<string, unknown>> {
  const recipients = Array.from(
    new Set(job.recipientUserIds.map((u) => u.trim()).filter(Boolean)),
  );
  if (recipients.length === 0) {
    return { event_key: job.eventKey, status: "skipped", reason: "no_recipients" };
  }

  const claim = await claimDelivery(db, job.eventKey, job.eventType, job.channelId);
  if (!claim.claimed) {
    return { event_key: job.eventKey, status: "skipped", reason: claim.reason ?? "duplicate" };
  }

  const { data: devices, error: devErr } = await db
    .from("admin_devices")
    .select("id, user_id, fcm_token")
    .in("user_id", recipients);

  if (devErr) {
    await finishDelivery(db, job.eventKey, {
      recipientCount: recipients.length,
      sentCount: 0,
      failedCount: 1,
      removedTokens: 0,
      status: "failed",
      detail: `devices_query_failed: ${devErr.message}`,
    });
    return { event_key: job.eventKey, status: "failed", error: devErr.message };
  }

  const deviceList = (devices ?? []) as DeviceRow[];
  if (deviceList.length === 0) {
    await finishDelivery(db, job.eventKey, {
      recipientCount: recipients.length,
      sentCount: 0,
      failedCount: 0,
      removedTokens: 0,
      status: "skipped",
      detail: "no_registered_devices",
    });
    return {
      event_key: job.eventKey,
      status: "skipped",
      reason: "no_registered_devices",
      recipients: recipients.length,
    };
  }

  const auth = await saLoader();
  if (!auth.sa || !auth.accessToken) {
    await finishDelivery(db, job.eventKey, {
      recipientCount: recipients.length,
      sentCount: 0,
      failedCount: deviceList.length,
      removedTokens: 0,
      status: "failed",
      detail: auth.error ?? "fcm_not_configured",
    });
    return {
      event_key: job.eventKey,
      status: "failed",
      error: auth.error ?? "fcm_not_configured",
    };
  }

  let sent = 0;
  let removed = 0;
  let failed = 0;
  const seenTokens = new Set<string>();

  for (const device of deviceList) {
    const token = String(device.fcm_token ?? "").trim();
    if (!token || seenTokens.has(token)) continue;
    seenTokens.add(token);

    const result = await sendToDeviceWithRetry(
      auth.accessToken,
      auth.sa.project_id,
      token,
      job.channelId,
      job.title,
      job.body,
      job.tag,
      job.data,
    );

    if (result === "sent") {
      sent++;
    } else if (result === "removed") {
      const { error: delErr } = await db
        .from("admin_devices")
        .delete()
        .eq("id", device.id);
      if (delErr) {
        console.error(`Failed cleaning invalid FCM token ${device.id}: ${delErr.message}`);
      }
      removed++;
    } else {
      failed++;
    }
  }

  const finalStatus: "sent" | "skipped" | "failed" =
    sent > 0 ? "sent" : (failed > 0 ? "failed" : "skipped");
  await finishDelivery(db, job.eventKey, {
    recipientCount: recipients.length,
    sentCount: sent,
    failedCount: failed,
    removedTokens: removed,
    status: finalStatus,
    detail: removed > 0 && sent === 0 && failed === 0 ? "all_tokens_unregistered" : undefined,
  });

  return {
    event_key: job.eventKey,
    status: finalStatus,
    sent,
    removed,
    failed,
    recipients: recipients.length,
  };
}

interface OrderContext {
  order: {
    id: string;
    user_id: string;
    stall_id: string | null;
    status: string;
    total_idr: number;
    pickup_code: string;
    payment_method: string | null;
    payment_status: string | null;
    payment_reference: string | null;
    order_mode: string | null;
    pickup_date: string | null;
    pickup_session: string | null;
  };
  stallId: string;
  stallOwnerId: string | null;
  stallName: string;
  stallLocation: string;
  buyerName: string;
  itemsSummary: string;
}

async function loadOrderContext(
  db: ReturnType<typeof createClient>,
  orderId: string,
): Promise<OrderContext | null> {
  const { data: order, error: orderError } = await db
    .from("orders")
    .select(
      "id, user_id, stall_id, status, total_idr, pickup_code, payment_method, payment_status, payment_reference, order_mode, pickup_date, pickup_session",
    )
    .eq("id", orderId)
    .maybeSingle();

  if (orderError || !order) {
    if (orderError) console.error(`loadOrderContext order failed: ${orderError.message}`);
    return null;
  }

  const [{ data: buyerProfile }, { data: itemRows }] = await Promise.all([
    db
      .from("profiles")
      .select("display_name, email")
      .eq("id", order.user_id)
      .maybeSingle(),
    db
      .from("order_items")
      .select("item_name, quantity, menu_item_id")
      .eq("order_id", order.id),
  ]);

  let resolvedStallId = String(order.stall_id ?? "").trim();
  if (!resolvedStallId && itemRows && itemRows.length > 0) {
    const firstMenuId = itemRows.find((r) => r.menu_item_id)?.menu_item_id;
    if (firstMenuId) {
      const { data: menuRow } = await db
        .from("menu_items")
        .select("stall_id")
        .eq("id", firstMenuId)
        .maybeSingle();
      resolvedStallId = String(menuRow?.stall_id ?? "").trim();
    }
  }

  let stallOwnerId: string | null = null;
  let stallName = "Warung Jajanin";
  let stallLocation = "";
  if (resolvedStallId) {
    const { data: stall } = await db
      .from("stalls")
      .select("id, owner_id, name, location")
      .eq("id", resolvedStallId)
      .maybeSingle();
    if (stall) {
      stallOwnerId = stall.owner_id ? String(stall.owner_id) : null;
      stallName = String(stall.name ?? "").trim() || "Warung Jajanin";
      stallLocation = String(stall.location ?? "").trim();
    }
  }

  const itemsList = (itemRows ?? []).map((r) => `${r.quantity}x ${r.item_name}`);
  const itemsSummary = itemsList.length > 0
    ? truncateText(itemsList.join(", "), 90)
    : "Pesanan Kantin";

  return {
    order,
    stallId: resolvedStallId,
    stallOwnerId,
    stallName,
    stallLocation,
    buyerName: cleanDisplayName(buyerProfile?.display_name, buyerProfile?.email, "Pembeli"),
    itemsSummary,
  };
}

async function buildOrderJobs(
  db: ReturnType<typeof createClient>,
  body: Record<string, unknown>,
): Promise<PushJob[]> {
  const eventType = String(body.event_type ?? "order_created").trim().toLowerCase();
  const orderId = String(body.order_id ?? "").trim();
  const jobs: PushJob[] = [];

  if (
    eventType === "order_created" ||
    eventType === "new_order" ||
    eventType === "order_status" ||
    eventType === "order_cancelled_by_buyer" ||
    eventType === "order_payment_update"
  ) {
    if (!orderId) return [];
    const ctx = await loadOrderContext(db, orderId);
    if (!ctx) return [];

    const code = String(ctx.order.pickup_code ?? "").trim() || orderId.slice(0, 6);
    const totalLabel = formatRupiah(ctx.order.total_idr);
    const isQris = String(ctx.order.payment_method ?? "").toLowerCase() === "qris";
    const payLabel = isQris ? "Midtrans" : "Tunai";

    if (eventType === "order_created" || eventType === "new_order") {
      // 1. Buyer notification: order successfully created
      const buyerTitle = `Pesanan #${code} Berhasil Dibuat`;
      const buyerBody = isQris
        ? `Segera selesaikan pembayaran Midtrans (${totalLabel}) untuk ${ctx.stallName} dalam 15 menit.`
        : `Pesananmu di ${ctx.stallName} (${ctx.itemsSummary}) menunggu konfirmasi penjual.`;
      const buyerEventKey = `order_created:${ctx.order.id}:buyer`;
      const buyerDedupKey = `order:${ctx.order.id}:created`;

      jobs.push({
        eventKey: buyerEventKey,
        eventType: "order_created",
        channelId: CHANNEL_ORDERS,
        recipientUserIds: [ctx.order.user_id],
        title: buyerTitle,
        body: buyerBody,
        tag: `order_buyer_${ctx.order.id}`,
        data: {
          type: "order_created",
          event_key: buyerEventKey,
          dedup_key: buyerDedupKey,
          channel_id: CHANNEL_ORDERS,
          order_id: ctx.order.id,
          pickup_code: code,
          status: "pending",
          payment_method: isQris ? "qris" : "cash",
          stall_id: ctx.stallId,
          stall_name: ctx.stallName,
          target_role: "buyer",
          target_screen: "order_detail",
          title: buyerTitle,
          body: buyerBody,
        },
      });

      // 2. Seller notification: ONLY the exact owner of the ordered stall (never broadcast!)
      if (ctx.stallOwnerId && ctx.stallOwnerId !== ctx.order.user_id) {
        const sellerTitle = `Pesanan Baru #${code}`;
        const sellerBody = `${ctx.buyerName} memesan ${ctx.itemsSummary} (${totalLabel} · ${payLabel}).`;
        const sellerEventKey = `new_order:${ctx.order.id}:seller`;
        const sellerDedupKey = `order:${ctx.order.id}:new`;

        jobs.push({
          eventKey: sellerEventKey,
          eventType: "new_order",
          channelId: CHANNEL_ORDERS,
          recipientUserIds: [ctx.stallOwnerId],
          title: sellerTitle,
          body: sellerBody,
          tag: `order_seller_${ctx.order.id}`,
          data: {
            type: "new_order",
            event_key: sellerEventKey,
            dedup_key: sellerDedupKey,
            channel_id: CHANNEL_ORDERS,
            order_id: ctx.order.id,
            pickup_code: code,
            buyer_name: ctx.buyerName,
            status: "pending",
            stall_id: ctx.stallId,
            stall_name: ctx.stallName,
            target_role: "seller",
            target_screen: "order_detail",
            title: sellerTitle,
            body: sellerBody,
          },
        });
      }
      return jobs;
    }

    if (eventType === "order_cancelled_by_buyer") {
      if (ctx.stallOwnerId && ctx.stallOwnerId !== ctx.order.user_id) {
        const title = `Pesanan #${code} Dibatalkan Pembeli`;
        const bodyText = `${ctx.buyerName} membatalkan pesanan #${code} (${ctx.itemsSummary}). Stok telah dikembalikan.`;
        const eventKey = `order_cancelled_by_buyer:${ctx.order.id}:seller`;
        const dedupKey = `order:${ctx.order.id}:cancelled_by_buyer`;
        jobs.push({
          eventKey,
          eventType: "order_cancelled_by_buyer",
          channelId: CHANNEL_ORDERS,
          recipientUserIds: [ctx.stallOwnerId],
          title,
          body: bodyText,
          tag: `order_seller_${ctx.order.id}`,
          data: {
            type: "order_cancelled_by_buyer",
            event_key: eventKey,
            dedup_key: dedupKey,
            channel_id: CHANNEL_ORDERS,
            order_id: ctx.order.id,
            pickup_code: code,
            buyer_name: ctx.buyerName,
            status: "cancelled",
            stall_id: ctx.stallId,
            stall_name: ctx.stallName,
            target_role: "seller",
            target_screen: "order_detail",
            title,
            body: bodyText,
          },
        });
      }
      return jobs;
    }

    if (eventType === "order_status") {
      const newStatus = String(body.new_status ?? ctx.order.status ?? "").trim().toLowerCase();
      const cancelledBy = String(body.cancelled_by ?? "").trim().toLowerCase();
      const locSuffix = ctx.stallLocation ? ` di ${ctx.stallLocation}` : "";
      const cancelReason = String(ctx.order.payment_reference ?? "").trim();

      let title = `Status Pesanan #${code} Diperbarui`;
      let bodyText = `Status pesanan #${code} di ${ctx.stallName} berubah menjadi ${newStatus}.`;

      switch (newStatus) {
        case "confirmed":
          title = `Pesanan #${code} Dikonfirmasi`;
          bodyText = `${ctx.stallName} telah menerima pesananmu (${ctx.itemsSummary}).`;
          break;
        case "preparing":
        case "processing":
          title = `Pesanan #${code} Sedang Diproses`;
          bodyText = `${ctx.stallName} sedang menyiapkan pesananmu.`;
          break;
        case "ready":
          title = `Pesanan #${code} Siap Diambil`;
          bodyText = `Pesananmu di ${ctx.stallName} siap diambil${locSuffix}. Tunjukkan kode #${code}.`;
          break;
        case "completed":
          title = `Pesanan #${code} Selesai`;
          bodyText = `Terima kasih! Pesanan #${code} di ${ctx.stallName} telah selesai.`;
          break;
        case "cancelled":
          if (cancelledBy === "system_expired") {
            title = `Pesanan #${code} Kedaluwarsa`;
            bodyText = `Batas waktu pembayaran Midtrans untuk pesanan #${code} telah habis. Pesanan dibatalkan otomatis.`;
          } else {
            const reasonLine = cancelReason ? ` Alasan: ${truncateText(cancelReason, 90)}.` : "";
            title = `Pesanan #${code} Dibatalkan`;
            bodyText = `Pesanan #${code} dibatalkan oleh ${ctx.stallName}.${reasonLine} Kuota pesananmu dikembalikan.`;
          }
          break;
        default:
          return [];
      }

      const eventKey = `order_status:${ctx.order.id}:${newStatus}:buyer`;
      const dedupKey = `order:${ctx.order.id}:status:${newStatus}`;
      jobs.push({
        eventKey,
        eventType: "order_status",
        channelId: CHANNEL_ORDERS,
        recipientUserIds: [ctx.order.user_id],
        title,
        body: bodyText,
        tag: `order_buyer_${ctx.order.id}`,
        data: {
          type: "order_status",
          event_key: eventKey,
          dedup_key: dedupKey,
          channel_id: CHANNEL_ORDERS,
          order_id: ctx.order.id,
          pickup_code: code,
          status: newStatus,
          stall_id: ctx.stallId,
          stall_name: ctx.stallName,
          target_role: "buyer",
          target_screen: "order_detail",
          title,
          body: bodyText,
        },
      });
      return jobs;
    }

    if (eventType === "order_payment_update") {
      const payStatus = String(body.payment_status ?? ctx.order.payment_status ?? "").trim().toLowerCase();
      const isGatewayWebhook = Boolean(body.is_gateway_webhook);
      if (payStatus !== "paid" && payStatus !== "rejected") return [];

      // 1. Notify Buyer
      const buyerTitle = payStatus === "paid"
        ? `Pembayaran #${code} Lunas`
        : `Pembayaran #${code} Ditolak`;
      const buyerBody = payStatus === "paid"
        ? (isQris
          ? `Pembayaran Midtrans untuk pesanan #${code} sudah terkonfirmasi lunas otomatis.`
          : `Pembayaran tunai pesanan #${code} telah ditandai lunas oleh ${ctx.stallName}.`)
        : `Pembayaran pesanan #${code} belum berhasil. Silakan periksa detail pesananmu.`;
      const buyerEventKey = `order_pay:${ctx.order.id}:${payStatus}:buyer`;
      const buyerDedupKey = `order:${ctx.order.id}:pay:${payStatus}`;

      jobs.push({
        eventKey: buyerEventKey,
        eventType: "order_payment_update",
        channelId: CHANNEL_ORDERS,
        recipientUserIds: [ctx.order.user_id],
        title: buyerTitle,
        body: buyerBody,
        tag: `order_pay_buyer_${ctx.order.id}`,
        data: {
          type: "order_paid",
          event_key: buyerEventKey,
          dedup_key: buyerDedupKey,
          channel_id: CHANNEL_ORDERS,
          order_id: ctx.order.id,
          pickup_code: code,
          payment_status: payStatus,
          stall_id: ctx.stallId,
          stall_name: ctx.stallName,
          target_role: "buyer",
          target_screen: "order_detail",
          title: buyerTitle,
          body: buyerBody,
        },
      });

      // 2. Notify Seller ONLY when Midtrans/QRIS auto-settles (not when seller marks cash manually)
      if (
        payStatus === "paid" &&
        (isGatewayWebhook || isQris) &&
        ctx.stallOwnerId &&
        ctx.stallOwnerId !== ctx.order.user_id
      ) {
        const sellerTitle = `Pembayaran Midtrans #${code} Lunas`;
        const sellerBody = `${ctx.buyerName} telah menyelesaikan pembayaran (${totalLabel}). Pesanan siap diproses.`;
        const sellerEventKey = `order_pay:${ctx.order.id}:paid:seller`;
        const sellerDedupKey = `order:${ctx.order.id}:midtrans_paid`;

        jobs.push({
          eventKey: sellerEventKey,
          eventType: "order_paid",
          channelId: CHANNEL_ORDERS,
          recipientUserIds: [ctx.stallOwnerId],
          title: sellerTitle,
          body: sellerBody,
          tag: `order_pay_seller_${ctx.order.id}`,
          data: {
            type: "order_paid",
            event_key: sellerEventKey,
            dedup_key: sellerDedupKey,
            channel_id: CHANNEL_ORDERS,
            order_id: ctx.order.id,
            pickup_code: code,
            payment_status: "paid",
            stall_id: ctx.stallId,
            stall_name: ctx.stallName,
            target_role: "seller",
            target_screen: "order_detail",
            title: sellerTitle,
            body: sellerBody,
          },
        });
      }
      return jobs;
    }
  }

  // ==========================================================================
  // Store Activity Channel (`jajanin_store_activity`)
  // ==========================================================================

  if (eventType === "stall_verification") {
    const stallId = String(body.stall_id ?? "").trim();
    if (!stallId) return [];
    const { data: stall } = await db
      .from("stalls")
      .select("id, owner_id, name, is_verified, verified_at, verification_revoked_at, verification_revoked_reason")
      .eq("id", stallId)
      .maybeSingle();
    if (!stall?.owner_id) return [];

    const isVerified = Boolean(body.is_verified ?? stall.is_verified);
    const stallName = String(stall.name ?? "").trim() || "Warungmu";
    const reason = String(body.reason ?? stall.verification_revoked_reason ?? "").trim();
    const stamp = String(
      (isVerified ? stall.verified_at : stall.verification_revoked_at) ??
      Math.floor(Date.now() / 60_000),
    );
    const eventKey = `stall_verification:${stall.id}:${isVerified}:${stamp}`;
    const title = isVerified
      ? `Warung ${stallName} Terverifikasi`
      : `Status Verifikasi ${stallName} Diperbarui`;
    const bodyText = isVerified
      ? "Selamat! Warungmu kini mendapatkan lencana terverifikasi resmi Jajanin."
      : `Status verifikasi warungmu diperbarui oleh sistem.${reason ? ` Catatan: ${truncateText(reason, 90)}` : ""}`;

    jobs.push({
      eventKey,
      eventType: "stall_verification",
      channelId: CHANNEL_STORE,
      recipientUserIds: [String(stall.owner_id)],
      title,
      body: bodyText,
      tag: `store_verify_${stall.id}`,
      data: {
        type: "store_activity",
        event_key: eventKey,
        dedup_key: eventKey,
        channel_id: CHANNEL_STORE,
        stall_id: String(stall.id),
        stall_name: stallName,
        target_role: "seller",
        target_screen: "store_profile",
        title,
        body: bodyText,
      },
    });
    return jobs;
  }

  if (eventType === "stall_visibility") {
    const stallId = String(body.stall_id ?? "").trim();
    if (!stallId) return [];
    const { data: stall } = await db
      .from("stalls")
      .select("id, owner_id, name, is_open")
      .eq("id", stallId)
      .maybeSingle();
    if (!stall?.owner_id) return [];

    const isOpen = Boolean(body.is_open ?? stall.is_open);
    const stallName = String(stall.name ?? "").trim() || "Warungmu";
    const minuteBucket = Math.floor(Date.now() / 60_000);
    const eventKey = `stall_visibility:${stall.id}:${isOpen}:${minuteBucket}`;
    const title = isOpen
      ? `Warung ${stallName} Dibuka Kembali`
      : `Warung ${stallName} Ditutup Sementara`;
    const bodyText = isOpen
      ? "Visibilitas warungmu diaktifkan kembali oleh developer Jajanin."
      : "Warungmu ditutup sementara oleh developer Jajanin. Periksa halaman Notifikasi untuk detail.";

    jobs.push({
      eventKey,
      eventType: "stall_visibility",
      channelId: CHANNEL_STORE,
      recipientUserIds: [String(stall.owner_id)],
      title,
      body: bodyText,
      tag: `store_vis_${stall.id}`,
      data: {
        type: "store_activity",
        event_key: eventKey,
        dedup_key: eventKey,
        channel_id: CHANNEL_STORE,
        stall_id: String(stall.id),
        stall_name: stallName,
        target_role: "seller",
        target_screen: "notif",
        title,
        body: bodyText,
      },
    });
    return jobs;
  }

  if (eventType === "developer_warning") {
    const warningId = String(body.warning_id ?? "").trim();
    if (!warningId) return [];
    const { data: warn } = await db
      .from("developer_warnings")
      .select("id, target_user_id, title, message, severity")
      .eq("id", warningId)
      .maybeSingle();
    if (!warn?.target_user_id) return [];

    const eventKey = `warning:${warn.id}`;
    const title = truncateText(String(warn.title ?? "").trim() || "Peringatan Penting Jajanin", 80);
    const bodyText = truncateText(String(warn.message ?? "").trim() || "Periksa kotak masuk notifikasi Jajanin.", 180);

    jobs.push({
      eventKey,
      eventType: "developer_warning",
      channelId: CHANNEL_STORE,
      recipientUserIds: [String(warn.target_user_id)],
      title,
      body: bodyText,
      tag: `warn_${warn.id}`,
      data: {
        type: "store_activity",
        event_key: eventKey,
        dedup_key: eventKey,
        channel_id: CHANNEL_STORE,
        warning_id: String(warn.id),
        target_role: "buyer",
        target_screen: "notif",
        title,
        body: bodyText,
      },
    });
    return jobs;
  }

  if (eventType === "developer_announcement") {
    const annId = String(body.announcement_id ?? "").trim();
    if (!annId) return [];
    const { data: ann } = await db
      .from("developer_announcements")
      .select("id, title, message, target_role, is_active")
      .eq("id", annId)
      .maybeSingle();
    if (!ann || ann.is_active === false) return [];

    const targetRole = String(ann.target_role ?? "all").trim().toLowerCase();
    let recipientIds: string[] = [];

    if (targetRole === "seller") {
      const [{ data: sellers }, { data: stalls }] = await Promise.all([
        db.from("profiles").select("id").or("is_seller_verified.eq.true,role.eq.seller"),
        db.from("stalls").select("owner_id"),
      ]);
      recipientIds = [
        ...(sellers ?? []).map((r) => String(r.id)),
        ...(stalls ?? []).map((r) => String(r.owner_id ?? "")),
      ].filter(Boolean);
    } else if (targetRole === "buyer") {
      const { data: buyers } = await db
        .from("profiles")
        .select("id")
        .neq("role", "developer");
      recipientIds = (buyers ?? []).map((r) => String(r.id));
    } else {
      const { data: allProfiles } = await db
        .from("profiles")
        .select("id");
      recipientIds = (allProfiles ?? []).map((r) => String(r.id));
    }

    const eventKey = `announcement:${ann.id}`;
    const title = truncateText(String(ann.title ?? "").trim() || "Pengumuman Jajanin", 80);
    const bodyText = truncateText(String(ann.message ?? "").trim() || "Ada informasi terbaru dari Jajanin.", 180);

    jobs.push({
      eventKey,
      eventType: "developer_announcement",
      channelId: CHANNEL_STORE,
      recipientUserIds: recipientIds,
      title,
      body: bodyText,
      tag: `ann_${ann.id}`,
      data: {
        type: "store_activity",
        event_key: eventKey,
        dedup_key: eventKey,
        channel_id: CHANNEL_STORE,
        announcement_id: String(ann.id),
        target_role: targetRole === "seller" ? "seller" : "buyer",
        target_screen: "notif",
        title,
        body: bodyText,
      },
    });
    return jobs;
  }

  if (eventType === "seller_report_created" || eventType === "seller_report_updated") {
    const reportId = String(body.report_id ?? "").trim();
    if (!reportId) return [];
    const { data: rep } = await db
      .from("seller_reports")
      .select("id, reporter_id, stall_id, category, status")
      .eq("id", reportId)
      .maybeSingle();
    if (!rep) return [];

    if (eventType === "seller_report_created") {
      const { data: devs } = await db
        .from("profiles")
        .select("id")
        .eq("role", "developer");
      const devIds = (devs ?? []).map((r) => String(r.id));
      const eventKey = `seller_report_created:${rep.id}`;
      const title = "Laporan Warung Baru";
      const bodyText = `Ada laporan baru kategori "${truncateText(String(rep.category ?? "Umum"), 48)}" yang perlu ditinjau.`;

      jobs.push({
        eventKey,
        eventType,
        channelId: CHANNEL_STORE,
        recipientUserIds: devIds,
        title,
        body: bodyText,
        tag: `report_${rep.id}`,
        data: {
          type: "store_activity",
          event_key: eventKey,
          dedup_key: eventKey,
          channel_id: CHANNEL_STORE,
          report_id: String(rep.id),
          target_role: "developer",
          target_screen: "notif",
          title,
          body: bodyText,
        },
      });
    } else if (rep.reporter_id) {
      const newStatus = String(body.new_status ?? rep.status ?? "").toLowerCase();
      const eventKey = `seller_report_updated:${rep.id}:${newStatus}`;
      const statusLabel = newStatus === "resolved"
        ? "telah ditindaklanjuti"
        : newStatus === "dismissed"
          ? "telah ditutup"
          : "sedang ditinjau";
      const title = "Update Laporan Warung";
      const bodyText = `Laporanmu (${truncateText(String(rep.category ?? "Warung"), 40)}) ${statusLabel} oleh tim Jajanin.`;

      jobs.push({
        eventKey,
        eventType,
        channelId: CHANNEL_STORE,
        recipientUserIds: [String(rep.reporter_id)],
        title,
        body: bodyText,
        tag: `report_${rep.id}`,
        data: {
          type: "store_activity",
          event_key: eventKey,
          dedup_key: eventKey,
          channel_id: CHANNEL_STORE,
          report_id: String(rep.id),
          target_role: "buyer",
          target_screen: "notif",
          title,
          body: bodyText,
        },
      });
    }
    return jobs;
  }

  return jobs;
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
    const [{ sa, error }, { count }] = await Promise.all([
      loadServiceAccount(db),
      db.from("admin_devices").select("id", { count: "exact", head: true }),
    ]);
    return json({
      ok: true,
      notify_secret_verified: true,
      fcm_configured: Boolean(sa),
      fcm_status: sa ? "ready" : (error ?? "missing"),
      fcm_project_id: sa?.project_id ?? null,
      registered_devices: count ?? 0,
      channels: {
        orders: CHANNEL_ORDERS,
        store_activity: CHANNEL_STORE,
      },
    });
  }

  const jobs = await buildOrderJobs(db, body);
  if (jobs.length === 0) {
    return json({ ok: true, results: [], reason: "no_applicable_notifications" });
  }

  let cachedAuthPromise: Promise<{ sa: ServiceAccount | null; accessToken: string | null; error?: string }> | null = null;
  const loadFcmAuthOnce = () => {
    if (!cachedAuthPromise) {
      cachedAuthPromise = (async () => {
        const { sa, error } = await loadServiceAccount(db);
        if (!sa) return { sa: null, accessToken: null, error };
        try {
          const accessToken = await getFcmAccessToken(sa);
          return { sa, accessToken };
        } catch (err) {
          console.error(`FCM OAuth error: ${err}`);
          return { sa, accessToken: null, error: "fcm_auth_failed" };
        }
      })();
    }
    return cachedAuthPromise;
  };

  const results: Record<string, unknown>[] = [];
  for (const job of jobs) {
    const res = await executePushJob(db, job, loadFcmAuthOnce);
    results.push(res);
  }

  const anyFcmMissing = results.some((r) => r.error === "fcm_service_account_not_configured");
  return json(
    { ok: !anyFcmMissing, results },
    anyFcmMissing ? 503 : 200,
  );
});
