package com.jajanin.app.feature.buyer.orders

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.paymentLabel
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort

internal fun JajaninView.drawReady(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.rounded(c, RectF(132f, 95f, 258f, 221f), kit.successSoft, 63f)
    kit.centerText(c, "✓", RectF(132f, 95f, 258f, 221f), 66f, kit.success, true)
    kit.centerText(c, "Pesanan dibuat!", RectF(20f, 246f, w - 20f, 293f), 25f, kit.ink, true)
    kit.centerText(c, "Tunjukkan nomor ini ke penjual saat", RectF(15f, 303f, w - 15f, 328f), 13f, kit.muted)
    kit.centerText(c, "mengambil pesananmu.", RectF(15f, 325f, w - 15f, 350f), 13f, kit.muted)
    kit.rounded(c, RectF(38f, 378f, 352f, 503f), kit.card, 18f)
    kit.centerText(c, "NOMOR PESANAN", RectF(38f, 396f, 352f, 421f), 11f, kit.muted, true)
    kit.centerText(c, lastOrderPickup ?: "—", RectF(38f, 419f, 352f, 470f), 36f, kit.blue, true)
    val methodText = if (lastOrderMethod == "cash") "Tunai · bayar saat ambil" else "QRIS · menunggu verifikasi"
    kit.centerText(c, methodText, RectF(38f, 470f, 352f, 495f), 12f, kit.muted)
    kit.centerText(c, formatRupiah(lastOrderTotal), RectF(20f, 525f, w - 20f, 560f), 20f, kit.ink, true)
    kit.centerText(c, "Status: ${statusLabelShort(lastOrderStatus)} · pantau di Pesanan", RectF(20f, 562f, w - 20f, 587f), 12f, kit.muted)
    val cta = RectF(20f, h - 79f, w - 20f, h - 22f)
    kit.button(c, "Lihat pesanan", cta)
    bounds["orders"] = RectF(cta)
}

internal fun JajaninView.drawOrders(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Pesanan saya")
    val off = -scroll
    val orders = myOrders
    when {
        myOrdersLoading && orders.isEmpty() ->
            kit.centerText(c, "Memuat pesanan...", RectF(24f, 280f, 366f, 340f), 14f, kit.muted)
        myOrdersError != null && orders.isEmpty() -> {
            kit.centerText(c, "Gagal memuat pesanan.", RectF(24f, 260f, 366f, 310f), 13f, kit.danger)
            val retry = RectF(120f, 330f, 270f, 376f)
            kit.button(c, "Muat ulang", retry, kit.blue, 14f)
            bounds["myOrdersRetry"] = RectF(retry)
        }
        orders.isEmpty() ->
            kit.centerText(c, "Belum ada pesanan. Mulai jajan dari beranda!", RectF(24f, 280f, 366f, 340f), 13f, kit.muted)
        else -> orders.forEachIndexed { index, order ->
            val top = 100f + index * 208f + off
            if (top + 196f < 78f || top > h - 78f) return@forEachIndexed
            val card = RectF(18f, top, 372f, top + 196f)
            kit.card(c, card, 17f)
            val (label, color) = statusLabel(order.status)
            kit.text(c, "●  $label", 34f, top + 31f, 13f, color, true)
            kit.text(c, order.itemsSummary.take(30).ifBlank { "Pesanan" }, 34f, top + 60f, 17f, kit.ink, true)
            kit.text(c, "Kantin Jajanin · Pesanan ${order.pickupCode}", 34f, top + 83f, 12f, kit.muted)
            val (payLabel, payColor) = paymentLabel(order.paymentStatus)
            val methodName = if (order.paymentMethod == "cash") "Tunai" else "QRIS"
            kit.text(c, "$methodName · $payLabel", 34f, top + 106f, 11f, payColor, true)
            kit.line(c, 34f, top + 122f, 356f, top + 122f)
            kit.text(c, "Tunjukkan kode ke penjual", 34f, top + 149f, 13f, kit.ink)
            val code = RectF(287f, top + 135f, 354f, top + 170f)
            kit.rounded(c, code, kit.tint, 9f)
            kit.centerText(c, order.pickupCode, code, 12f, kit.blue, true)
            if (order.paymentMethod == "qris" &&
                (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected")
            ) {
                val pay = RectF(34f, top + 158f, 250f, top + 188f)
                kit.rounded(c, pay, kit.tint, 10f)
                kit.centerText(
                    c,
                    if (order.paymentStatus == "rejected") "Ditolak · kirim bukti lagi" else "Saya sudah membayar",
                    pay, 10f, kit.blue, true,
                )
                bounds["myOrderPaid:${order.id}"] = RectF(pay)
            } else if (order.paymentStatus == "waiting_verification") {
                kit.text(c, "Menunggu verifikasi penjual…", 34f, top + 184f, 10f, kit.muted)
            }
        }
    }
    contentHeight = if (orders.isEmpty()) 0f else 100f + orders.size * 208f + 40f
    drawBottomNav(c, w, h, "Orders")
}
