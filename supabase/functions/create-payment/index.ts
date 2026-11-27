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
  return typeof data === "string" ? data.trim() : "";
}

function basicAuthHeader(serverKey: string): string {
  return "Basic " + btoa(`${serverKey}:`);
}

function midtransCoreBaseUrl(env: string): string {
  return env === "production"
    ? "https://api.midtrans.com"
    : "https://api.sandbox.midtrans.com";
}

function midtransSnapBaseUrl(env: string): string {
  return env === "production"
    ? "https://app.midtrans.com"
    : "https://app.sandbox.midtrans.com";
}

function formatMidtransTimestampWib(date: Date): string {
  const wibMs = date.getTime() + 7 * 3600 * 1000;
  const w = new Date(wibMs);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${w.getUTCFullYear()}-${pad(w.getUTCMonth() + 1)}-${pad(w.getUTCDate())} ` +
    `${pad(w.getUTCHours())}:${pad(w.getUTCMinutes())}:${pad(w.getUTCSeconds())} +0700`;
}

function parseExpiryToIso(rawExpiry: string | undefined, fallbackMinutes = 15): string {
  if (rawExpiry) {
    // Midtrans format: "2026-11-27 16:35:00" (+0700 WIB)
    const normalized = rawExpiry.trim().replace(" ", "T");
    const withOffset = normalized.includes("+") || normalized.endsWith("Z")
      ? normalized
      : `${normalized}+07:00`;
    const parsed = Date.parse(withOffset);
    if (!Number.isNaN(parsed)) {
      return new Date(parsed).toISOString();
    }
  }
  return new Date(Date.now() + fallbackMinutes * 60_000).toISOString();
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "Server belum dikonfigurasi." }, 500);
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    return json({ error: "Masuk dulu sebelum membayar." }, 401);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: userData, error: authErr } = await db.auth.getUser(token);
  if (authErr || !userData?.user) {
    return json({ error: "Sesi tidak valid. Silakan masuk ulang." }, 401);
  }
  const userId = userData.user.id;

  let body: { order_id?: string; force_snap?: boolean };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Format permintaan tidak valid." }, 400);
  }

  const orderId = String(body.order_id ?? "").trim();
  if (!orderId) {
    return json({ error: "order_id wajib diisi." }, 400);
  }

  const { data: order, error: orderErr } = await db
    .from("orders")
    .select("id, user_id, stall_id, status, total_idr, payment_method, payment_status, pickup_code, payment_expires_at")
    .eq("id", orderId)
    .maybeSingle();

  if (orderErr || !order) {
    return json({ error: "Pesanan tidak ditemukan." }, 404);
  }
  if (order.user_id !== userId) {
    return json({ error: "Kamu tidak memiliki akses ke pesanan ini." }, 403);
  }
  if (order.status === "cancelled") {
    return json({ error: "Pesanan sudah dibatalkan." }, 400);
  }
  if (String(order.payment_method ?? "").toLowerCase() !== "qris") {
    return json({ error: "Pesanan ini menggunakan metode Tunai, bukan Midtrans." }, 400);
  }
  if (order.payment_status === "paid") {
    return json({
      order_id: order.id,
      status: "paid",
      gross_amount: order.total_idr,
      environment: "sandbox",
      payment_channel: "midtrans",
      qr_url: null,
      qr_string: null,
      redirect_url: null,
      expires_at: null,
    });
  }

  const serverKey = (Deno.env.get("MIDTRANS_SERVER_KEY") ?? "").trim() ||
    await edgeConfig(db, "midtrans_server_key");
  if (!serverKey) {
    return json({ error: "Konfigurasi midtrans_server_key belum tersedia di server." }, 500);
  }
  const envSetting = ((Deno.env.get("MIDTRANS_ENV") ?? "").trim() ||
    await edgeConfig(db, "midtrans_env") ||
    (serverKey.startsWith("SB-") ? "sandbox" : "production")).toLowerCase();
  const environment = envSetting === "production" ? "production" : "sandbox";

  // Check for existing pending transaction first (idempotency across retries)
  const { data: existingTx } = await db
    .from("payment_transactions")
    .select("*")
    .eq("order_id", order.id)
    .maybeSingle();

  const existingExpMs = existingTx?.expires_at ? Date.parse(existingTx.expires_at) : 0;
  if (
    existingTx &&
    existingTx.status === "pending" &&
    existingExpMs > Date.now() + 15_000 &&
    existingTx.redirect_url
  ) {
    return json({
      order_id: order.id,
      gateway_order_id: existingTx.gateway_order_id,
      gateway_transaction_id: existingTx.gateway_transaction_id,
      payment_channel: existingTx.payment_channel,
      environment: existingTx.environment,
      status: existingTx.status,
      gross_amount: existingTx.gross_amount,
      qr_url: existingTx.qr_url,
      qr_string: existingTx.qr_string,
      redirect_url: existingTx.redirect_url,
      expires_at: existingTx.expires_at,
    });
  }

  // Load items & customer profile for clean Midtrans receipt details
  const [{ data: items }, { data: profile }] = await Promise.all([
    db
      .from("order_items")
      .select("menu_item_id, item_name, unit_price_idr, quantity")
      .eq("order_id", order.id),
    db
      .from("profiles")
      .select("display_name, email")
      .eq("id", userId)
      .maybeSingle(),
  ]);

  const itemDetails: Array<{ id: string; price: number; quantity: number; name: string }> = [];
  let itemsSum = 0;
  for (const it of items ?? []) {
    const price = Number(it.unit_price_idr ?? 0);
    const qty = Number(it.quantity ?? 1);
    if (price > 0 && qty > 0) {
      itemsSum += price * qty;
      itemDetails.push({
        id: String(it.menu_item_id ?? "menu").slice(0, 48),
        price,
        quantity: qty,
        name: String(it.item_name ?? "Menu Jajanin").slice(0, 50),
      });
    }
  }
  const diff = Number(order.total_idr) - itemsSum;
  if (diff > 0) {
    itemDetails.push({
      id: "service-fee",
      price: diff,
      quantity: 1,
      name: "Biaya Layanan Jajanin",
    });
  } else if (diff < 0 || itemDetails.length === 0) {
    itemDetails.length = 0;
    itemDetails.push({
      id: order.id.slice(0, 32),
      price: Number(order.total_idr),
      quantity: 1,
      name: `Pesanan #${order.pickup_code}`,
    });
  }

  const rawDisplay = String(profile?.display_name ?? "").replace(/\[[^\]]+\]/g, "").trim() || "Pembeli Jajanin";
  const buyerEmail = String(profile?.email ?? userData.user.email ?? "buyer@jajanin.app").trim();
  const suffix = Math.random().toString(36).slice(2, 7).toUpperCase();
  const gatewayOrderId = `JJN-${String(order.pickup_code ?? "000").slice(0, 6)}-${order.id.slice(0, 8)}-${suffix}`;

  let qrUrl: string | null = null;
  let qrString: string | null = null;
  let redirectUrl: string | null = null;
  let gatewayTxId: string | null = null;
  let paymentChannel = "qris";
  let expiresAtIso = parseExpiryToIso(undefined, 15);
  let rawChargeResp: Record<string, unknown> | null = null;

  const authValue = basicAuthHeader(serverKey);

  // Standard Midtrans Snap Checkout (supports QRIS, GoPay, ShopeePay, DANA, Virtual Account BCA/BNI/BRI/Mandiri, dll.)
  paymentChannel = "snap";
  const snapOrderId = gatewayOrderId;
  const snapPayload = {
    transaction_details: {
      order_id: snapOrderId,
      gross_amount: Number(order.total_idr),
    },
    item_details: itemDetails,
    customer_details: {
      first_name: rawDisplay.slice(0, 40),
      email: buyerEmail,
    },
    expiry: {
      start_time: formatMidtransTimestampWib(new Date()),
      unit: "minutes",
      duration: 15,
    },
  };

  const snapRes = await fetch(`${midtransSnapBaseUrl(environment)}/snap/v1/transactions`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: authValue,
    },
    body: JSON.stringify(snapPayload),
  });
  const snapJson = await snapRes.json().catch(() => ({} as Record<string, unknown>));
  rawChargeResp = snapJson;

  if (!snapRes.ok || !snapJson.redirect_url) {
    const errMsgs = Array.isArray(snapJson.error_messages)
      ? snapJson.error_messages.join(", ")
      : String(snapJson.status_message ?? "Gagal membuat transaksi Midtrans Snap.");
    return json({ error: `Midtrans error: ${errMsgs}` }, 502);
  }
  redirectUrl = String(snapJson.redirect_url);
  gatewayTxId = snapJson.token ? String(snapJson.token) : null;

  const feeIdr = Math.min(1000, Number(order.total_idr));
  const netSeller = Math.max(0, Number(order.total_idr) - feeIdr);

  const { error: upsertErr } = await db
    .from("payment_transactions")
    .upsert(
      {
        order_id: order.id,
        provider: "midtrans",
        environment,
        gateway_order_id: snapOrderId,
        gateway_transaction_id: gatewayTxId,
        payment_channel: "snap",
        gross_amount: Number(order.total_idr),
        fee_idr: feeIdr,
        net_seller_amount: netSeller,
        status: "pending",
        qr_url: qrUrl,
        qr_string: qrString,
        redirect_url: redirectUrl,
        expires_at: expiresAtIso,
        raw_notification: rawChargeResp,
        updated_at: new Date().toISOString(),
      },
      { onConflict: "order_id" },
    );

  if (upsertErr) {
    return json({ error: `Gagal menyimpan transaksi: ${upsertErr.message}` }, 500);
  }

  await db
    .from("orders")
    .update({ payment_expires_at: expiresAtIso })
    .eq("id", order.id);

  return json({
    order_id: order.id,
    gateway_order_id: snapOrderId,
    gateway_transaction_id: gatewayTxId,
    payment_channel: "snap",
    environment,
    status: "pending",
    gross_amount: Number(order.total_idr),
    qr_url: qrUrl,
    qr_string: qrString,
    redirect_url: redirectUrl,
    expires_at: expiresAtIso,
  });
});
