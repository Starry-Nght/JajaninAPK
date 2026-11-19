package com.jajanin.app.feature.buyer.orders

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.paymentLabel
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.repository.AdminOrder

private const val HEADER_H = 60f
private const val NAV_HEIGHT = 82f

private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.2f
    pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
}

/**
 * Detail Pesanan & Tiket Pengambilan Pembeli:
 * Menampilkan Kode Pengambilan, Progress Track, Ambil di Ruangan, Nomor Telepon Penjual,
 * Nama Toko & Pemilik, serta Rincian Item.
 */
internal fun JajaninView.drawReady(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    val matchedOrder = selectedBuyerOrderId?.let { id -> myOrders.firstOrNull { it.id == id } }
        ?: lastOrderId?.let { id -> myOrders.firstOrNull { it.id == id } }
        ?: myOrders.firstOrNull()

    val pickupCode = matchedOrder?.pickupCode ?: lastOrderPickup ?: "—"
    val status = matchedOrder?.status ?: lastOrderStatus
    val totalIdr = matchedOrder?.totalIdr ?: lastOrderTotal
    val method = matchedOrder?.paymentMethod ?: lastOrderMethod
    val payStatus = matchedOrder?.paymentStatus ?: if (method == "cash") "unpaid" else "waiting_verification"
    val timeText = matchedOrder?.timeLabel?.takeIf { it.isNotBlank() } ?: "Baru saja"

    // Resolve Pickup Room ("Ambil di ruangan") & Seller Contact
    val firstMenuId = matchedOrder?.items?.firstNotNullOfOrNull { it.menuItemId }
    val localProduct = firstMenuId?.let { id -> products.firstOrNull { it.id == id } }
    val pickupRoom = matchedOrder?.pickupRoom?.takeIf { it.isNotBlank() }
        ?: localProduct?.pickupRoom?.takeIf { it.isNotBlank() }
        ?: "Kantin / Ruangan Penjual"
    val storeName = matchedOrder?.sellerStoreName?.takeIf { it.isNotBlank() }
        ?: cartStallName.takeIf { it.isNotBlank() }
        ?: "Warung Jajanin"
    val sellerOwner = matchedOrder?.sellerOwnerName.orEmpty()
    val sellerPhone = matchedOrder?.sellerPhone.orEmpty()

    val off = -scroll
    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h - 70f)

    var y = 76f + off

    // 1. Architectural Ticket Surface
    val items = matchedOrder?.items.orEmpty()
    val itemBlockH = if (items.isEmpty()) 28f else items.size * 30f + 10f
    val ticketTop = y
    val ticketBottom = ticketTop + 210f + itemBlockH
    val ticketRect = RectF(20f, ticketTop, 370f, ticketBottom)
    kit.rounded(c, ticketRect, kit.card, 8f, kit.border)

    kit.text(c, "KODE PENGAMBILAN", 34f, ticketTop + 24f, 10f, kit.muted, true)
    val (stLabel, stColor) = statusLabel(status)
    val stBg = when (stColor) {
        kit.success -> kit.successSoft
        kit.danger -> kit.dangerBorder
        else -> kit.tint
    }
    val stW = kit.measure(stLabel, 10f, true) + 18f
    val stPill = RectF(356f - stW, ticketTop + 12f, 356f, ticketTop + 32f)
    kit.rounded(c, stPill, stBg, 5f)
    kit.centerText(c, stLabel, stPill, 10f, stColor, true)

    kit.text(c, "#$pickupCode", 34f, ticketTop + 58f, 30f, kit.blue, true)
    kit.text(c, "Tunjukkan kode ini ke penjual saat mengambil pesanan", 34f, ticketTop + 78f, 11f, kit.muted)

    drawOrderProgressTrack(c, status, 34f, 356f, ticketTop + 94f)

    val dashY = ticketTop + 142f
    dashedPaint.color = kit.divider
    c.drawLine(28f, dashY, 362f, dashY, dashedPaint)

    kit.text(c, "RINCIAN ITEM", 34f, dashY + 20f, 10f, kit.muted, true)
    kit.textRight(c, timeText, 356f, dashY + 20f, 90f, 10f, kit.muted)

    var itemY = dashY + 30f
    if (items.isEmpty()) {
        kit.text(c, "1× Pesanan Kantin Jajanin", 34f, itemY + 16f, 12.5f, kit.ink, true)
        kit.textRight(c, formatRupiah(totalIdr), 356f, itemY + 16f, 110f, 12.5f, kit.ink, true)
        itemY += 28f
    } else {
        items.forEachIndexed { idx, item ->
            val sub = formatRupiah(item.unitPriceIdr * item.quantity)
            kit.textEllipsized(c, "${item.quantity}×  ${item.itemName}", 34f, itemY + 17f, 210f, 12.5f, kit.ink, true)
            kit.textRight(c, sub, 356f, itemY + 17f, 110f, 12.5f, kit.ink, true)
            if (idx < items.size - 1) {
                kit.line(c, 34f, itemY + 26f, 356f, itemY + 26f, kit.divider)
            }
            itemY += 30f
        }
    }

    kit.line(c, 34f, ticketBottom - 34f, 356f, ticketBottom - 34f, kit.divider)
    kit.text(c, "Total Pembayaran", 34f, ticketBottom - 13f, 13f, kit.ink, true)
    kit.textRight(c, formatRupiah(totalIdr), 356f, ticketBottom - 12f, 140f, 15f, kit.blue, true)

    y = ticketBottom + 12f

    // 2. Section: Ambil di Ruangan & Kontak Penjual (Visible to Buyer!)
    val hasSellerPhone = sellerPhone.isNotBlank()
    val roomCardH = if (hasSellerPhone) 114f else 78f
    val roomCard = RectF(20f, y, 370f, y + roomCardH)
    kit.rounded(c, roomCard, kit.card, 8f, kit.border)
    kit.rect(c, 20f, y + 8f, 23.5f, y + roomCardH - 8f, kit.blue)

    kit.text(c, "AMBIL DI RUANGAN", 34f, y + 20f, 9.5f, kit.muted, true)
    kit.textEllipsized(c, pickupRoom, 34f, y + 41f, 320f, 16f, kit.blue, true)

    val sellerIdentity = if (sellerOwner.isNotBlank()) "$storeName  ·  $sellerOwner" else storeName
    kit.textEllipsized(c, sellerIdentity, 34f, y + 60f, 320f, 12f, kit.ink, true)

    if (hasSellerPhone) {
        val localPhone = displayBuyerPhone(sellerPhone)
        kit.line(c, 34f, y + 70f, 356f, y + 70f, kit.divider)
        drawIcon(c, "phone", 41f, y + 91f, 14f, kit.blue)
        kit.textEllipsized(c, localPhone, 54f, y + 95f, 150f, 12.5f, kit.ink, true)

        val copyBtn = RectF(214f, y + 78f, 280f, y + 104f)
        kit.rounded(c, copyBtn, kit.bg, 6f, kit.border)
        kit.centerText(c, "Salin", copyBtn, 11f, kit.ink, true)
        bounds.putVisible("copyPhone:$sellerPhone", copyBtn, topLimit = HEADER_H, bottomLimit = h - 70f)

        val callBtn = RectF(286f, y + 78f, 356f, y + 104f)
        kit.rounded(c, callBtn, kit.tint, 6f, kit.blue)
        kit.centerText(c, "Hubungi", callBtn, 11f, kit.blue, true)
        bounds.putVisible("dialPhone:$sellerPhone", callBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
    }

    y += roomCardH + 12f

    // 3. Payment Info Row
    val (payLabel, payColor) = paymentLabel(payStatus)
    val methodLabel = if (method == "cash") "Tunai (Bayar saat ambil)" else "QRIS"
    val needsProof = method == "qris" && (payStatus == "unpaid" || payStatus == "rejected")
    val payBoxH = if (needsProof) 86f else 48f
    val payRect = RectF(20f, y, 370f, y + payBoxH)
    kit.rounded(c, payRect, kit.card, 8f, kit.border)
    kit.text(c, "Metode: $methodLabel", 34f, y + 28f, 12.5f, kit.ink, true)
    kit.textRight(c, payLabel, 356f, y + 28f, 130f, 11.5f, payColor, true)

    if (needsProof && matchedOrder != null) {
        val proofBtn = RectF(34f, y + 44f, 356f, y + 76f)
        kit.rounded(c, proofBtn, kit.tint, 6f, kit.blue)
        kit.centerText(
            c,
            if (payStatus == "rejected") "Bukti ditolak · Konfirmasi ulang" else "Saya sudah membayar",
            proofBtn,
            11.5f,
            kit.blue,
            true
        )
        bounds.putVisible("myOrderPaid:${matchedOrder.id}", proofBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
    }

    c.restoreToCount(bodySave)
    contentHeight = (y - off) + payBoxH + 24f

    // Flat Top Header
    kit.rect(c, 0f, 0f, w, HEADER_H, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.text(c, "Detail Pesanan", 50f, 37f, 18f, kit.ink, true)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    bounds["headerBack"] = RectF(12f, 10f, 64f, HEADER_H)

    // Bottom Action CTA
    val cta = RectF(20f, h - 62f, w - 20f, h - 14f)
    kit.rounded(c, cta, kit.blue, 8f)
    kit.centerText(c, "Lihat Semua Pesanan", cta, 14f, Color.WHITE, true)
    bounds["orders"] = RectF(cta)
}

internal fun JajaninView.drawOrders(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bottomClip = h - NAV_HEIGHT
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= bottomClip

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    val orders = myOrders
    var y = 76f + off

    when {
        myOrdersLoading && orders.isEmpty() -> {
            kit.centerText(c, "Memuat pesanan...", RectF(24f, 260f, 366f, 320f), 13.5f, kit.muted)
            contentHeight = 0f
        }
        myOrdersError != null && orders.isEmpty() -> {
            kit.centerText(c, "Gagal memuat pesanan.", RectF(24f, 232f, 366f, 270f), 13.5f, kit.danger, true)
            kit.centerText(c, (myOrdersError ?: "").take(52), RectF(24f, 268f, 366f, 296f), 11f, kit.muted)
            val retry = RectF(125f, 308f, 265f, 350f)
            kit.rounded(c, retry, kit.blue, 8f)
            kit.centerText(c, "Muat ulang", retry, 13f, Color.WHITE, true)
            bounds["myOrdersRetry"] = RectF(retry)
            contentHeight = 0f
        }
        orders.isEmpty() -> {
            kit.centerText(c, "Belum ada pesanan.", RectF(24f, 250f, 366f, 290f), 14f, kit.ink, true)
            kit.centerText(c, "Pesanan yang kamu buat akan muncul di sini.", RectF(24f, 276f, 366f, 316f), 12f, kit.muted)
            contentHeight = 0f
        }
        else -> {
            val activeList = orders.filter { !isTerminalStatus(it.status) }
            val historyList = orders.filter { isTerminalStatus(it.status) }

            if (activeList.isNotEmpty()) {
                if (visible(y, y + 20f)) {
                    kit.text(c, "PESANAN AKTIF (${activeList.size})", 20f, y + 13f, 10.5f, kit.muted, true)
                }
                y += 22f
                activeList.forEach { order ->
                    y = drawBuyerOrderItem(c, order, y, active = true, ::visible, bottomClip)
                    y += 12f
                }
                y += 8f
            }

            if (historyList.isNotEmpty()) {
                if (visible(y, y + 20f)) {
                    kit.text(c, "RIWAYAT PESANAN (${historyList.size})", 20f, y + 13f, 10.5f, kit.muted, true)
                }
                y += 22f
                historyList.forEach { order ->
                    y = drawBuyerOrderItem(c, order, y, active = false, ::visible, bottomClip)
                    y += 10f
                }
            }
            contentHeight = (y - off) + GLASS_TOP_OFFSET + 20f
        }
    }

    c.restoreToCount(bodySave)

    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.text(c, "Pesanan Saya", 50f, 37f, 18f, kit.ink, true)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    c.restore()
    bounds["headerBack"] = RectF(12f, 10f, 56f, HEADER_H)

    drawBottomNav(c, w, h, "Orders")
}

private fun JajaninView.drawBuyerOrderItem(
    c: Canvas,
    order: AdminOrder,
    top: Float,
    active: Boolean,
    visible: (Float, Float) -> Boolean,
    bottomClip: Float
): Float {
    val needsProof = order.paymentMethod == "qris" &&
        (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected")
    val cardH = when {
        active && needsProof -> 196f
        active -> 160f
        else -> 114f
    }
    val card = RectF(20f, top, 370f, top + cardH)

    if (visible(card.top, card.bottom)) {
        kit.rounded(c, card, kit.card, 8f, kit.border)
        if (active) {
            kit.rect(c, 20f, top + 10f, 23f, top + cardH - 10f, kit.blue)
        }

        // Row 1: Pickup Code + Time + Status Pill
        val codeTag = RectF(32f, top + 12f, 84f, top + 34f)
        kit.rounded(c, codeTag, kit.tint, 5f)
        kit.centerText(c, "#${order.pickupCode}", codeTag, 11.5f, kit.blue, true)

        kit.text(c, order.timeLabel, 92f, top + 27f, 11f, kit.muted)

        val (stLabel, stColor) = statusLabel(order.status)
        val stBg = when (stColor) {
            kit.success -> kit.successSoft
            kit.danger -> kit.dangerBorder
            else -> kit.tint
        }
        val stW = kit.measure(stLabel, 9.5f, true) + 16f
        val stRect = RectF(358f - stW, top + 13f, 358f, top + 33f)
        kit.rounded(c, stRect, stBg, 5f)
        kit.centerText(c, stLabel, stRect, 9.5f, stColor, true)

        // Row 2: Items Summary + Total Price
        kit.textEllipsized(
            c,
            order.itemsSummary.ifBlank { "Pesanan Kantin" },
            32f,
            top + 54f,
            220f,
            13.5f,
            kit.ink,
            true
        )
        kit.textRight(c, formatRupiah(order.totalIdr), 358f, top + 54f, 110f, 13.5f, kit.ink, true)

        // Row 3: Ambil di Ruangan + Nomor Telepon Penjual
        val roomLabel = order.pickupRoom.takeIf { it.isNotBlank() }?.let { "Ambil di: $it" }
            ?: order.sellerStoreName.takeIf { it.isNotBlank() }
            ?: "Kantin Jajanin"
        val phoneSuffix = order.sellerPhone.takeIf { it.isNotBlank() }?.let { " · HP ${displayBuyerPhone(it)}" }.orEmpty()
        kit.textEllipsized(c, "$roomLabel$phoneSuffix", 32f, top + 72f, 324f, 11f, kit.blue, true)

        // Row 4: Payment method & status + Detail chevron
        val (payLabel, payColor) = paymentLabel(order.paymentStatus)
        val methodName = if (order.paymentMethod == "cash") "Tunai" else "QRIS"
        kit.text(c, "$methodName · $payLabel", 32f, top + 90f, 10.5f, payColor, true)
        kit.textRight(c, "Detail →", 358f, top + 90f, 70f, 11f, kit.blue, true)

        if (active) {
            kit.line(c, 32f, top + 100f, 358f, top + 100f, kit.divider)
            drawOrderProgressTrack(c, order.status, 34f, 356f, top + 108f)
        }

        if (active && needsProof) {
            val payBtn = RectF(32f, top + 154f, 358f, top + 184f)
            kit.rounded(c, payBtn, kit.tint, 6f, kit.blue)
            kit.centerText(
                c,
                if (order.paymentStatus == "rejected") "Ditolak · Konfirmasi bayar ulang" else "Saya sudah membayar",
                payBtn,
                11f,
                kit.blue,
                true
            )
            bounds.putVisible("myOrderPaid:${order.id}", payBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
        }

        press(c, "buyerOrder:${order.id}", card, 8f)
    }

    val hitBottom = if (active && needsProof) top + 150f else top + cardH
    bounds.putVisible(
        "buyerOrder:${order.id}",
        RectF(card.left, card.top, card.right, hitBottom),
        topLimit = HEADER_H,
        bottomLimit = bottomClip
    )
    return top + cardH
}

private fun JajaninView.drawOrderProgressTrack(
    c: Canvas,
    status: String,
    left: Float,
    right: Float,
    top: Float
) {
    val stepLabels = listOf("Baru", "Diterima", "Disiapkan", "Siap", "Selesai")
    val stage = when (status.lowercase()) {
        STATUS_PENDING -> 0
        STATUS_CONFIRMED -> 1
        STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
        STATUS_READY -> 3
        STATUS_COMPLETED -> 4
        STATUS_CANCELLED -> -2
        else -> 0
    }
    if (stage == -2) {
        kit.text(c, "Pesanan ini telah dibatalkan.", left, top + 20f, 11f, kit.danger, true)
        return
    }

    val segW = (right - left) / stepLabels.size
    val lineY = top + 10f
    val firstCx = left + segW / 2f
    val lastCx = right - segW / 2f
    kit.line(c, firstCx, lineY, lastCx, lineY, kit.divider)
    if (stage > 0) {
        val activeEndCx = left + segW * stage + segW / 2f
        kit.rect(c, firstCx, lineY - 1f, activeEndCx, lineY + 1f, kit.blue)
    }

    stepLabels.forEachIndexed { idx, label ->
        val cx = left + segW * idx + segW / 2f
        val done = idx <= stage
        val current = idx == stage
        val r = if (current) 5.5f else 4f
        if (done) {
            kit.rounded(c, RectF(cx - r, lineY - r, cx + r, lineY + r), kit.blue, r)
        } else {
            kit.rounded(c, RectF(cx - r, lineY - r, cx + r, lineY + r), kit.card, r, kit.border)
        }
        kit.centerText(
            c,
            label,
            RectF(cx - segW / 2f, top + 20f, cx + segW / 2f, top + 36f),
            9.5f,
            if (current) kit.blue else if (done) kit.ink else kit.muted,
            current || done
        )
    }
}

private fun displayBuyerPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw
