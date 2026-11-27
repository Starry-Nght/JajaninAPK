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

async function sha512Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-512", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function timingSafeEqualStr(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "Server not configured" }, 500);
  }

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "Invalid JSON payload" }, 400);
  }

  const orderId = String(payload.order_id ?? "").trim();
  const statusCode = String(payload.status_code ?? "").trim();
  const grossAmountRaw = String(payload.gross_amount ?? "").trim();
  const signatureKey = String(payload.signature_key ?? "").trim().toLowerCase();
  const txStatus = String(payload.transaction_status ?? "").trim().toLowerCase();
  const fraudStatus = String(payload.fraud_status ?? "").trim().toLowerCase();
  const gatewayTxId = String(payload.transaction_id ?? "").trim();

  if (!orderId || !statusCode || !grossAmountRaw || !signatureKey) {
    return json({ error: "Missing required notification fields" }, 400);
  }

  const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const serverKey = (Deno.env.get("MIDTRANS_SERVER_KEY") ?? "").trim() ||
    await edgeConfig(db, "midtrans_server_key");
  if (!serverKey) {
    return json({ error: "midtrans_server_key not configured" }, 500);
  }

  const expectedSig = (
    await sha512Hex(`${orderId}${statusCode}${grossAmountRaw}${serverKey}`)
  ).toLowerCase();

  if (!timingSafeEqualStr(expectedSig, signatureKey)) {
    console.warn(`Invalid Midtrans webhook signature for order_id=${orderId}`);
    return json({ error: "Invalid signature_key" }, 403);
  }

  const { data: txRow, error: txErr } = await db
    .from("payment_transactions")
    .select("id, order_id, gross_amount, status")
    .eq("gateway_order_id", orderId)
    .maybeSingle();

  if (txErr) {
    return json({ error: txErr.message }, 500);
  }
  if (!txRow) {
    return json({ ok: true, ignored: true, reason: "transaction_not_tracked" }, 200);
  }

  const incomingAmount = Math.round(Number(grossAmountRaw));
  if (!Number.isFinite(incomingAmount) || incomingAmount !== Number(txRow.gross_amount)) {
    console.warn(`Gross amount mismatch for ${orderId}: expected=${txRow.gross_amount}, got=${grossAmountRaw}`);
    return json({ error: "Amount mismatch" }, 400);
  }

  if (txStatus === "settlement" || (txStatus === "capture" && (!fraudStatus || fraudStatus === "accept"))) {
    const { data, error } = await db.rpc("finalize_gateway_payment", {
      p_gateway_order_id: orderId,
      p_gateway_tx_id: gatewayTxId,
      p_raw_notification: payload,
    });
    if (error) {
      console.error("finalize_gateway_payment failed:", error.message);
      return json({ error: error.message }, 500);
    }
    return json({ ok: true, action: "settled", result: data });
  }

  if (txStatus === "expire" || txStatus === "cancel" || txStatus === "deny" || txStatus === "failure") {
    const { data, error } = await db.rpc("expire_gateway_payment", {
      p_gateway_order_id: orderId,
      p_gateway_status: txStatus,
      p_raw_notification: payload,
    });
    if (error) {
      console.error("expire_gateway_payment failed:", error.message);
      return json({ error: error.message }, 500);
    }
    return json({ ok: true, action: txStatus, result: data });
  }

  await db
    .from("payment_transactions")
    .update({
      gateway_transaction_id: gatewayTxId || undefined,
      raw_notification: payload,
      updated_at: new Date().toISOString(),
    })
    .eq("id", txRow.id);

  return json({ ok: true, action: "pending_acknowledged" });
});
