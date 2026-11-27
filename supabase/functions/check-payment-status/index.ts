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

function midtransCoreBaseUrl(env: string): string {
  return env === "production"
    ? "https://api.midtrans.com"
    : "https://api.sandbox.midtrans.com";
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
    return json({ error: "Masuk dulu untuk memeriksa status pembayaran." }, 401);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: userData, error: authErr } = await db.auth.getUser(token);
  if (authErr || !userData?.user) {
    return json({ error: "Sesi tidak valid." }, 401);
  }
  const userId = userData.user.id;

  let body: { order_id?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Format permintaan tidak valid." }, 400);
  }

  const orderId = String(body.order_id ?? "").trim();
  if (!orderId) {
    return json({ error: "order_id wajib diisi." }, 400);
  }

  const { data: order } = await db
    .from("orders")
    .select("id, user_id, stall_id, status, payment_status, total_idr")
    .eq("id", orderId)
    .maybeSingle();

  if (!order) {
    return json({ error: "Pesanan tidak ditemukan." }, 404);
  }

  let authorized = order.user_id === userId;
  if (!authorized && order.stall_id) {
    const { data: stall } = await db
      .from("stalls")
      .select("owner_id")
      .eq("id", order.stall_id)
      .maybeSingle();
    authorized = stall?.owner_id === userId;
  }
  if (!authorized) {
    return json({ error: "Kamu tidak punya akses ke pesanan ini." }, 403);
  }

  if (order.payment_status === "paid") {
    return json({
      order_id: order.id,
      status: "paid",
      payment_status: "paid",
      message: "Pembayaran sudah lunas.",
    });
  }

  const { data: tx } = await db
    .from("payment_transactions")
    .select("*")
    .eq("order_id", order.id)
    .maybeSingle();

  if (!tx) {
    return json({
      order_id: order.id,
      status: order.payment_status,
      payment_status: order.payment_status,
      message: "Sesi pembayaran Midtrans belum dibuat.",
    });
  }

  const serverKey = (Deno.env.get("MIDTRANS_SERVER_KEY") ?? "").trim() ||
    await edgeConfig(db, "midtrans_server_key");
  if (!serverKey) {
    return json({ error: "midtrans_server_key belum tersedia di server." }, 500);
  }

  const env = (tx.environment ?? "sandbox").toLowerCase() === "production"
    ? "production"
    : "sandbox";

  const statusRes = await fetch(
    `${midtransCoreBaseUrl(env)}/v2/${encodeURIComponent(tx.gateway_order_id)}/status`,
    {
      method: "GET",
      headers: {
        Accept: "application/json",
        Authorization: "Basic " + btoa(`${serverKey}:`),
      },
    },
  );
  const statusJson = await statusRes.json().catch(() => ({} as Record<string, unknown>));

  const txStatus = String(statusJson.transaction_status ?? "").trim().toLowerCase();
  const fraudStatus = String(statusJson.fraud_status ?? "").trim().toLowerCase();
  const gatewayTxId = String(statusJson.transaction_id ?? tx.gateway_transaction_id ?? "").trim();

  if (txStatus === "settlement" || (txStatus === "capture" && (!fraudStatus || fraudStatus === "accept"))) {
    const { error } = await db.rpc("finalize_gateway_payment", {
      p_gateway_order_id: tx.gateway_order_id,
      p_gateway_tx_id: gatewayTxId,
      p_raw_notification: statusJson,
    });
    if (error) {
      return json({ error: error.message }, 500);
    }
    return json({
      order_id: order.id,
      gateway_order_id: tx.gateway_order_id,
      status: "paid",
      payment_status: "paid",
      message: "Pembayaran Midtrans berhasil dikonfirmasi lunas.",
    });
  }

  if (txStatus === "expire" || txStatus === "cancel" || txStatus === "deny" || txStatus === "failure") {
    await db.rpc("expire_gateway_payment", {
      p_gateway_order_id: tx.gateway_order_id,
      p_gateway_status: txStatus,
      p_raw_notification: statusJson,
    });
    return json({
      order_id: order.id,
      gateway_order_id: tx.gateway_order_id,
      status: txStatus,
      payment_status: "unpaid",
      message: "Pembayaran telah kedaluwarsa atau dibatalkan.",
    });
  }

  return json({
    order_id: order.id,
    gateway_order_id: tx.gateway_order_id,
    status: "pending",
    payment_status: order.payment_status,
    qr_url: tx.qr_url,
    redirect_url: tx.redirect_url,
    expires_at: tx.expires_at,
    message: "Menunggu pembayaran diselesaikan di Midtrans.",
  });
});
