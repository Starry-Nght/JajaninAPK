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
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
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

    // Resolve Seller Contact
    val storeName = matchedOrder?.sellerStoreName?.takeIf { it.isNotBlank() }
        ?: cartStallName.takeIf { it.isNotBlank() }
        ?: "Warung Jajanin"
    val sellerOwner = matchedOrder?.sellerOwnerName.orEmpty()
    val sellerPhone = matchedOrder?.sellerPhone.orEmpty()

    val off = -scroll
    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h - 70f)

    var y = 76f + off

    // 1. Architectural Ticket Surface (Dynamic multi-line items & variants — zero truncation)
    val items = matchedOrder?.items.orEmpty()
    data class BuyerTicketItemLayout(
        val item: com.jajanin.app.data.repository.OrderItemRow,
        val sub: String,
        val subW: Float,
        val titleLines: List<String>,
        val variantLines: List<String>,
        val variantStartRelY: Float,
        val rowH: Float,
    )
    val bTitleStep = 16f
    val bVarStep = 14.5f
    val ticketItems = items.map { item ->
        val sub = formatRupiah(item.unitPriceIdr * item.quantity)
        val subW = kit.measure(sub, 12.5f, true)
        val textMaxW = (356f - subW - 12f - 34f).coerceAtLeast(180f)
        val titleLines = kit.wrap("${item.quantity}×  ${item.displayBaseName}", textMaxW, 12.5f, maxLines = 3, bold = true)
            .ifEmpty { listOf("${item.quantity}×  ${item.displayBaseName}") }
        val variant = item.resolvedVariantLabel
        val variantLines = if (variant.isNotBlank()) {
            kit.wrap(variant, textMaxW, 11f, maxLines = 5, bold = true)
        } else {
            emptyList()
        }
        val titleEndRelY = 17f + (titleLines.size - 1) * bTitleStep
        val variantStartRelY = titleEndRelY + 15f
        val lastLineRelY = if (variantLines.isNotEmpty()) {
            variantStartRelY + (variantLines.size - 1) * bVarStep
        } else {
            titleEndRelY
        }
        val rowH = maxOf(32f, lastLineRelY + 12f)
        BuyerTicketItemLayout(item, sub, subW, titleLines, variantLines, variantStartRelY, rowH)
    }
    val cancelNote = matchedOrder?.cancelReason?.takeIf {
        it.isNotBlank() && status.equals(STATUS_CANCELLED, ignoreCase = true)
    }
    val subtitleText = if (cancelNote != null) {
        "Alasan dibatalkan: $cancelNote"
    } else {
        "Tunjukkan kode ini ke penjual saat mengambil pesanan"
    }
    val subtitleLines = kit.wrap(subtitleText, 322f, 11f, maxLines = 2, bold = cancelNote != null)
    val extraSubH = ((subtitleLines.size - 1).coerceAtLeast(0)) * 14f
    val itemBlockH = if (ticketItems.isEmpty()) 30f else ticketItems.sumOf { it.rowH.toDouble() }.toFloat() + 8f
    val ticketTop = y
    val ticketBottom = ticketTop + 210f + extraSubH + itemBlockH
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
    subtitleLines.forEachIndexed { i, line ->
        kit.text(
            c,
            line,
            34f,
            ticketTop + 78f + i * 14f,
            11f,
            if (cancelNote != null) kit.danger else kit.muted,
            cancelNote != null,
        )
    }

    drawOrderProgressTrack(c, status, 34f, 356f, ticketTop + 94f + extraSubH)

    val dashY = ticketTop + 142f + extraSubH
    dashedPaint.color = kit.divider
    c.drawLine(28f, dashY, 362f, dashY, dashedPaint)

    kit.text(c, "RINCIAN ITEM", 34f, dashY + 20f, 10f, kit.muted, true)
    kit.textRight(c, timeText, 356f, dashY + 20f, 90f, 10f, kit.muted)

    var itemY = dashY + 30f
    if (ticketItems.isEmpty()) {
        kit.text(c, "1× Pesanan Kantin Jajanin", 34f, itemY + 16f, 12.5f, kit.ink, true)
        kit.textRight(c, formatRupiah(totalIdr), 356f, itemY + 16f, 110f, 12.5f, kit.ink, true)
        itemY += 28f
    } else {
        ticketItems.forEachIndexed { idx, row ->
            row.titleLines.forEachIndexed { lIdx, line ->
                kit.text(c, line, 34f, itemY + 17f + lIdx * bTitleStep, 12.5f, kit.ink, true)
            }
            row.variantLines.forEachIndexed { lIdx, line ->
                kit.text(c, line, 34f, itemY + row.variantStartRelY + lIdx * bVarStep, 11f, kit.blue, true)
            }
            kit.textRight(c, row.sub, 356f, itemY + 17f, row.subW + 14f, 12.5f, kit.ink, true)
            if (idx < ticketItems.lastIndex) {
                kit.line(c, 34f, itemY + row.rowH - 1f, 356f, itemY + row.rowH - 1f, kit.divider)
            }
            itemY += row.rowH
        }
    }

    kit.line(c, 34f, ticketBottom - 34f, 356f, ticketBottom - 34f, kit.divider)
    kit.text(c, "Total Pembayaran", 34f, ticketBottom - 13f, 13f, kit.ink, true)
    kit.textRight(c, formatRupiah(totalIdr), 356f, ticketBottom - 12f, 140f, 15f, kit.blue, true)

    y = ticketBottom + 12f

    // 2. Section: Warung, Lokasi Ambil (Kelas/Ruangan) & Kontak / Chat Penjual
    val targetOrderId = matchedOrder?.id ?: lastOrderId
    val unreadForOrder = targetOrderId?.let { unreadChatByOrder[it] ?: 0 } ?: 0
    val hasSellerPhone = sellerPhone.isNotBlank()
    val orderStallId = matchedOrder?.stallId?.takeIf { it.isNotBlank() }
    val firstMenuId = matchedOrder?.items?.firstNotNullOfOrNull { it.menuItemId }
    val localProduct = firstMenuId?.let { id -> products.firstOrNull { it.id == id } }
    val pickupRoom = matchedOrder?.pickupRoom?.takeIf { it.isNotBlank() }
        ?: localProduct?.pickupRoom?.takeIf { it.isNotBlank() }
        ?: orderStallId?.let { storeDirectoryById[it]?.location?.trim()?.takeIf { loc -> loc.isNotBlank() } }
        ?: ""
    val roomBonus = if (pickupRoom.isNotBlank()) 18f else 0f
    val roomCardH = 114f + roomBonus
    val roomCard = RectF(20f, y, 370f, y + roomCardH)
    kit.rounded(c, roomCard, kit.card, 8f, kit.border)
    kit.rect(c, 20f, y + 8f, 23.5f, y + roomCardH - 8f, kit.blue)

    kit.text(c, "WARUNG & LOKASI AMBIL", 34f, y + 20f, 9.5f, kit.muted, true)
    val schedLabel = matchedOrder?.pickupScheduleLabel?.takeIf { it.isNotBlank() }
    if (schedLabel != null) {
        val isPo = matchedOrder.isPreOrder
        val pillW = (kit.measure(schedLabel, 9.5f, true) + 14f).coerceAtMost(200f)
        val pillRect = RectF(356f - pillW, y + 9f, 356f, y + 27f)
        kit.rounded(c, pillRect, if (isPo) kit.warnBox else kit.tint, 5f)
        kit.centerText(c, schedLabel, pillRect, 9.5f, if (isPo) Color.rgb(183, 116, 0) else kit.blue, true)
    }
    val storeVerified = orderStallId?.let { storeDirectoryById[it]?.isVerified } == true
    StoreVerifiedBadge.drawInlineWithStoreName(
        view = this,
        c = c,
        storeName = storeName,
        isVerified = storeVerified,
        x = 34f,
        baselineY = y + 41f,
        maxTotalWidth = if (orderStallId != null) 232f else 320f,
        textSize = 16f,
        textColor = kit.blue,
        bold = true,
    )
    if (orderStallId != null) {
        drawTrailingActionWithArrow(c, "Profil Toko", 356f, y + 41f, 10.5f, kit.blue)
        bounds.putVisible(
            "openStore:$orderStallId",
            RectF(28f, y + 24f, 362f, y + 48f),
            topLimit = HEADER_H,
            bottomLimit = h - 70f,
        )
    }

    if (pickupRoom.isNotBlank()) {
        kit.textEllipsized(c, "Ambil di: $pickupRoom", 34f, y + 59f, 320f, 12f, kit.ink, true)
    }
    val sellerIdentity = sellerOwner.ifBlank { "Koordinasi langsung dengan penjual" }
    kit.textEllipsized(c, sellerIdentity, 34f, y + 60f + roomBonus, 320f, 11.5f, kit.muted, sellerOwner.isNotBlank() && pickupRoom.isBlank())

    kit.line(c, 34f, y + 70f + roomBonus, 356f, y + 70f + roomBonus, kit.divider)
    if (hasSellerPhone) {
        val localPhone = displayBuyerPhone(sellerPhone)
        drawIcon(c, "phone", 41f, y + 91f + roomBonus, 13.5f, kit.blue)
        kit.textEllipsized(c, localPhone, 53f, y + 95f + roomBonus, 128f, 11.5f, kit.ink, true)

        val callBtn = RectF(184f, y + 78f + roomBonus, 244f, y + 104f + roomBonus)
        kit.rounded(c, callBtn, kit.bg, 6f, kit.border)
        kit.centerText(c, "Telepon", callBtn, 10.5f, kit.ink, true)
        bounds.putVisible("dialPhone:$sellerPhone", callBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
    } else {
        drawIcon(c, "chat", 42f, y + 91f + roomBonus, 14f, kit.blue)
        kit.textEllipsized(c, "Koordinasi langsung via chat", 55f, y + 95f + roomBonus, 180f, 11f, kit.muted)
    }

    if (!targetOrderId.isNullOrBlank()) {
        val chatBtn = RectF(250f, y + 78f + roomBonus, 356f, y + 104f + roomBonus)
        kit.rounded(c, chatBtn, kit.blue, 6f)
        val chatLabel = if (unreadForOrder > 0) "Chat ($unreadForOrder)" else "Chat Penjual"
        kit.centerText(c, chatLabel, chatBtn, 10.5f, Color.WHITE, true)
        bounds.putVisible("chatOrder:$targetOrderId", chatBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
        press(c, "chatOrder:$targetOrderId", chatBtn, 6f)
    }

    y += roomCardH + 12f

    // 3. Payment Info Row
    val (payLabel, payColor) = paymentLabel(payStatus)
    val methodLabel = if (method == "cash") "Tunai (Bayar saat ambil)" else "Midtrans (QRIS Dinamis)"
    val activeOrder = !isTerminalStatus(status)
    val needsPayment = activeOrder && method == "qris" && (payStatus == "unpaid" || payStatus == "rejected" || payStatus == "waiting_verification")
    val hasProofPhoto = method == "qris" && !matchedOrder?.paymentProofUrl.isNullOrBlank()
    val remSec = if (needsPayment) com.jajanin.app.data.repository.remainingPaymentSeconds(matchedOrder?.paymentExpiresAt) else null
    if (remSec != null && remSec > 0L && currentScreen == com.jajanin.app.core.navigation.Screen.READY) {
        postInvalidateDelayed(1000)
    }
    val payBoxH = when {
        needsPayment && remSec != null -> 104f
        needsPayment -> 86f
        hasProofPhoto -> 86f
        else -> 48f
    }
    val payRect = RectF(20f, y, 370f, y + payBoxH)
    kit.rounded(c, payRect, kit.card, 8f, kit.border)
    kit.text(c, "Metode: $methodLabel", 34f, y + 28f, 12.5f, kit.ink, true)
    kit.textRight(c, payLabel, 356f, y + 28f, 130f, 11.5f, payColor, true)

    if (needsPayment && matchedOrder != null) {
        var btnTop = y + 44f
        if (remSec != null) {
            val timerText = if (remSec <= 0L) {
                "Batas bayar Midtrans habis · pesanan segera dibatalkan"
            } else {
                "Sisa waktu bayar Midtrans: ${com.jajanin.app.data.repository.formatCountdownMmSs(remSec)}"
            }
            kit.textEllipsized(
                c,
                timerText,
                34f,
                y + 48f,
                322f,
                10.5f,
                if (remSec <= 120L) kit.danger else Color.rgb(183, 116, 0),
                true,
            )
            btnTop = y + 60f
        }
        val proofBtn = RectF(34f, btnTop, 356f, btnTop + 32f)
        kit.rounded(c, proofBtn, kit.tint, 6f, kit.blue)
        kit.centerText(
            c,
            "Bayar Sekarang (Midtrans)",
            proofBtn,
            11.5f,
            kit.blue,
            true
        )
        bounds.putVisible("myOrderPaid:${matchedOrder.id}", proofBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
    } else if (hasProofPhoto && matchedOrder != null) {
        val viewBtn = RectF(34f, y + 44f, 356f, y + 76f)
        kit.rounded(c, viewBtn, kit.bg, 6f, kit.border)
        kit.centerText(c, "Lihat Foto Bukti Lama", viewBtn, 11.5f, kit.blue, true)
        bounds.putVisible("viewProof:${matchedOrder.id}", viewBtn, topLimit = HEADER_H, bottomLimit = h - 70f)
    }

    var endContentY = y + payBoxH
    if (matchedOrder?.isPreOrder == true && !isTerminalStatus(status)) {
        val lockLines = kit.wrap(
            "Pesanan Pre-Order bersifat final · tidak dapat dibatalkan pembeli.",
            326f,
            10.5f,
            maxLines = 3,
            bold = true,
        )
        val lockLineH = 14.5f
        val lockH = maxOf(38f, 14f + lockLines.size * lockLineH + 8f)
        val lockBox = RectF(20f, endContentY + 10f, 370f, endContentY + 10f + lockH)
        kit.rounded(c, lockBox, kit.warnBox, 8f, Color.rgb(183, 116, 0))
        lockLines.forEachIndexed { idx, line ->
            kit.text(c, line, 32f, lockBox.top + 20f + idx * lockLineH, 10.5f, Color.rgb(183, 116, 0), true)
        }
        endContentY = lockBox.bottom
    }

    // 4. Rating Card (Toko, Durasi, Rasa 1-5) — tersedia setelah pembayaran lunas / pesanan selesai
    val canRateOrder = targetOrderId != null &&
        !status.equals(STATUS_CANCELLED, ignoreCase = true) &&
        (payStatus.equals("paid", ignoreCase = true) || status.equals(STATUS_COMPLETED, ignoreCase = true))
    if (canRateOrder && targetOrderId != null) {
        val existingRating = myOrderRatings[targetOrderId]
        val rateTop = endContentY + 12f
        val rateCard = RectF(20f, rateTop, 370f, rateTop + 62f)
        val goldColor = Color.rgb(217, 119, 6)
        kit.rounded(
            c,
            rateCard,
            if (existingRating != null) kit.card else kit.warnBox,
            8f,
            if (existingRating != null) kit.border else goldColor,
        )
        if (existingRating != null) {
            val avgStr = String.format(java.util.Locale.US, "%.1f", existingRating.ratingOverall)
            kit.text(c, "Rating Terkirim · ★ $avgStr / 5.0", 34f, rateTop + 25f, 12.5f, goldColor, true)
            kit.textEllipsized(
                c,
                "Toko ${existingRating.ratingStore} · Durasi ${existingRating.ratingDuration} · Rasa ${existingRating.ratingTaste}",
                34f,
                rateTop + 45f,
                238f,
                11f,
                kit.muted,
            )
            val editPill = RectF(292f, rateTop + 17f, 358f, rateTop + 45f)
            kit.rounded(c, editPill, kit.tint, 6f, kit.blue)
            kit.centerText(c, "Ubah", editPill, 11f, kit.blue, true)
        } else {
            kit.text(c, "Beri Rating Pesanan (1–5 Bintang)", 34f, rateTop + 25f, 12.5f, goldColor, true)
            kit.textEllipsized(c, "Nilai Toko, Durasi penyiapan & Rasa", 34f, rateTop + 45f, 232f, 11f, kit.ink)
            val rateBtn = RectF(280f, rateTop + 17f, 358f, rateTop + 45f)
            kit.rounded(c, rateBtn, goldColor, 6f)
            kit.centerText(c, "Beri Nilai", rateBtn, 11f, Color.WHITE, true)
        }
        press(c, "rateOrder:$targetOrderId", rateCard, 8f)
        bounds.putVisible("rateOrder:$targetOrderId", rateCard, topLimit = HEADER_H, bottomLimit = h - 70f)
        endContentY = rateCard.bottom
    }

    // 5. Laporkan Penjual (Wajib 2 Bukti Foto -> langsung diteruskan ke Developer)
    if (targetOrderId != null && matchedOrder != null) {
        val repTop = endContentY + 10f
        val repCard = RectF(20f, repTop, 370f, repTop + 48f)
        kit.rounded(c, repCard, kit.card, 8f, kit.dangerBorder)
        kit.text(c, "Ada kendala dengan penjual?", 34f, repTop + 21f, 11.5f, kit.ink, true)
        kit.text(c, "Laporkan dengan 2 foto bukti ke Developer", 34f, repTop + 37f, 10f, kit.muted)
        val repBtn = RectF(274f, repTop + 10f, 358f, repTop + 38f)
        kit.rounded(c, repBtn, kit.bg, 6f, kit.danger)
        kit.centerText(c, "Laporkan", repBtn, 10.5f, kit.danger, true)
        press(c, "reportOrder:$targetOrderId", repCard, 8f)
        bounds.putVisible("reportOrder:$targetOrderId", repCard, topLimit = HEADER_H, bottomLimit = h - 70f)
        endContentY = repCard.bottom
    }

    c.restoreToCount(bodySave)
    contentHeight = (endContentY - off) + 24f

    // Flat Top Header
    kit.rect(c, 0f, 0f, w, HEADER_H, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.text(c, "Detail Pesanan", 50f, 37f, 18f, kit.ink, true)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    bounds["headerBack"] = RectF(12f, 10f, 64f, HEADER_H)

    // Bottom Action CTA (Pre-Order & pesanan yang sudah bayar / unggah bukti QRIS dikunci — tidak bisa dibatalkan)
    val canBuyerCancel = matchedOrder?.let { com.jajanin.app.data.repository.buyerCanCancelOrder(it) }
        ?: (status.equals(STATUS_PENDING, ignoreCase = true) && payStatus == "unpaid" && !targetOrderId.isNullOrBlank())
    if (canBuyerCancel && targetOrderId != null) {
        val cancelBtn = RectF(20f, h - 62f, 134f, h - 14f)
        kit.rounded(c, cancelBtn, kit.card, 8f, kit.dangerBorder)
        kit.centerText(c, "Batalkan", cancelBtn, 13f, kit.danger, true)
        press(c, "buyerCancel:$targetOrderId", cancelBtn, 8f)
        bounds["buyerCancel:$targetOrderId"] = RectF(cancelBtn)

        val cta = RectF(144f, h - 62f, w - 20f, h - 14f)
        kit.rounded(c, cta, kit.blue, 8f)
        kit.centerText(c, "Lihat Semua Pesanan", cta, 13.5f, Color.WHITE, true)
        bounds["orders"] = RectF(cta)
    } else {
        val cta = RectF(20f, h - 62f, w - 20f, h - 14f)
        kit.rounded(c, cta, kit.blue, 8f)
        kit.centerText(c, "Lihat Semua Pesanan", cta, 14f, Color.WHITE, true)
        bounds["orders"] = RectF(cta)
    }
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
    val canCancel = active && com.jajanin.app.data.repository.buyerCanCancelOrder(order)
    val needsProof = order.paymentMethod == "qris" &&
        (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected" || order.paymentStatus == "waiting_verification")
    val canRate = !order.status.equals(STATUS_CANCELLED, ignoreCase = true) &&
        (order.paymentStatus.equals("paid", ignoreCase = true) || order.status.equals(STATUS_COMPLETED, ignoreCase = true))
    val hasActiveActionRow = active && (needsProof || canCancel || canRate)
    val hasHistoryActionRow = !active
    val hasActionRow = hasActiveActionRow || hasHistoryActionRow
    val totalStr = formatRupiah(order.totalIdr)
    val totalW = kit.measure(totalStr, 13.5f, true)
    val summaryMaxW = (358f - totalW - 10f - 32f).coerceAtLeast(180f)
    val summaryText = order.itemsSummary.ifBlank { "Pesanan Kantin" }
    val summaryLines = kit.wrap(summaryText, summaryMaxW, 12.5f, maxLines = 3, bold = true)
        .ifEmpty { listOf(summaryText) }
    val summaryStep = 15.5f
    val extraSummaryH = ((summaryLines.size - 1).coerceAtLeast(0)) * summaryStep
    val baseCardH = when {
        hasActiveActionRow -> 212f
        active -> 176f
        else -> 162f
    }
    val cardH = baseCardH + extraSummaryH
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

        // Row 2: Items Summary (wrapped cleanly up to 3 lines) + Total Price
        summaryLines.forEachIndexed { sIdx, line ->
            kit.text(c, line, 32f, top + 52f + sIdx * summaryStep, 12.5f, kit.ink, true)
        }
        kit.textRight(c, totalStr, 358f, top + 52f, totalW + 14f, 13.5f, kit.ink, true)

        val rowOff = extraSummaryH
        // Row 3: Jadwal & Sesi Pengambilan (Hari Ini vs PO Besok)
        val schedColor = if (order.isPreOrder) Color.rgb(183, 116, 0) else kit.success
        kit.textEllipsized(c, "Jadwal: ${order.pickupScheduleLabel}", 32f, top + 70f + rowOff, 324f, 10.5f, schedColor, true)

        // Row 4: Warung (+ Verified Badge) + Lokasi Ambil / Telepon Penjual
        val storeLabel = order.sellerStoreName.takeIf { it.isNotBlank() } ?: "Warung Jajanin"
        val isStoreVerified = order.stallId.isNotBlank() && storeDirectoryById[order.stallId]?.isVerified == true
        val roomSuffix = order.pickupRoom.takeIf { it.isNotBlank() }?.let { " · Ambil: $it" }.orEmpty()
        val phoneSuffix = if (roomSuffix.isNotBlank()) {
            roomSuffix
        } else {
            order.sellerPhone.takeIf { it.isNotBlank() }?.let { " · HP ${displayBuyerPhone(it)}" }.orEmpty()
        }
        if (isStoreVerified) {
            val endX = StoreVerifiedBadge.drawInlineWithStoreName(
                view = this,
                c = c,
                storeName = storeLabel,
                isVerified = true,
                x = 32f,
                baselineY = top + 87f + rowOff,
                maxTotalWidth = if (phoneSuffix.isNotBlank()) 195f else 324f,
                textSize = 11f,
                textColor = kit.blue,
                bold = true,
            )
            if (phoneSuffix.isNotBlank() && endX < 340f) {
                kit.textEllipsized(c, phoneSuffix, endX + 2f, top + 87f + rowOff, 356f - endX, 10.5f, kit.muted)
            }
        } else {
            kit.textEllipsized(c, "$storeLabel$phoneSuffix", 32f, top + 87f + rowOff, 324f, 11f, kit.blue, true)
        }

        // Row 5: Payment method & status + Detail chevron
        val (payLabel, payColor) = paymentLabel(order.paymentStatus)
        val methodName = if (order.paymentMethod == "cash") "Tunai" else "Midtrans"
        val cancelInfo = order.cancelReason.takeIf {
            it.isNotBlank() && order.status.equals(STATUS_CANCELLED, ignoreCase = true)
        }
        val orderRemSec = if (active && needsProof) {
            com.jajanin.app.data.repository.remainingPaymentSeconds(order.paymentExpiresAt)
        } else {
            null
        }
        if (orderRemSec != null && orderRemSec > 0L && currentScreen == com.jajanin.app.core.navigation.Screen.ORDERS) {
            postInvalidateDelayed(1000)
        }
        if (cancelInfo != null) {
            kit.textEllipsized(c, "Alasan: $cancelInfo", 32f, top + 106f + rowOff, 200f, 10.5f, kit.danger, true)
        } else if (orderRemSec != null) {
            val timerInfo = if (orderRemSec <= 0L) "Midtrans · Waktu habis" else "Midtrans · Sisa ${com.jajanin.app.data.repository.formatCountdownMmSs(orderRemSec)}"
            kit.textEllipsized(c, timerInfo, 32f, top + 106f + rowOff, 190f, 10.5f, if (orderRemSec <= 120L) kit.danger else Color.rgb(183, 116, 0), true)
        } else {
            kit.text(c, "$methodName · $payLabel", 32f, top + 106f + rowOff, 10.5f, payColor, true)
        }
        val unreadOrder = unreadChatByOrder[order.id] ?: 0
        val chatLabel = if (unreadOrder > 0) "Chat ($unreadOrder)" else "Chat"
        val chatW = kit.measure(chatLabel, 10f, true) + 18f
        val chatPill = RectF(302f - chatW, top + 92f + rowOff, 302f, top + 112f + rowOff)
        kit.rounded(
            c,
            chatPill,
            if (unreadOrder > 0) kit.blue else kit.tint,
            5f,
            if (unreadOrder > 0) null else kit.blue,
        )
        kit.centerText(c, chatLabel, chatPill, 9.5f, if (unreadOrder > 0) Color.WHITE else kit.blue, true)
        bounds.putVisible("chatOrder:${order.id}", chatPill, topLimit = HEADER_H, bottomLimit = bottomClip)
        press(c, "chatOrder:${order.id}", chatPill, 5f)

        drawTrailingActionWithArrow(c, "Detail", 358f, top + 106f + rowOff, 11f, kit.blue)

        if (active) {
            kit.line(c, 32f, top + 116f + rowOff, 358f, top + 116f + rowOff, kit.divider)
            drawOrderProgressTrack(c, order.status, 34f, 356f, top + 124f + rowOff)
        }

        if (canCancel && needsProof) {
            val cancelBtn = RectF(32f, top + 170f + rowOff, 132f, top + 200f + rowOff)
            kit.rounded(c, cancelBtn, kit.card, 6f, kit.dangerBorder)
            kit.centerText(c, "Batalkan", cancelBtn, 11f, kit.danger, true)
            press(c, "buyerCancel:${order.id}", cancelBtn, 6f)
            bounds.putVisible("buyerCancel:${order.id}", cancelBtn, topLimit = HEADER_H, bottomLimit = bottomClip)

            val payBtn = RectF(140f, top + 170f + rowOff, 358f, top + 200f + rowOff)
            kit.rounded(c, payBtn, kit.tint, 6f, kit.blue)
            kit.centerText(
                c,
                "Bayar via Midtrans",
                payBtn,
                11f,
                kit.blue,
                true
            )
            bounds.putVisible("myOrderPaid:${order.id}", payBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
        } else if (canCancel) {
            val cancelBtn = RectF(32f, top + 170f + rowOff, 358f, top + 200f + rowOff)
            kit.rounded(c, cancelBtn, kit.card, 6f, kit.dangerBorder)
            kit.centerText(c, "Batalkan Pesanan", cancelBtn, 11f, kit.danger, true)
            press(c, "buyerCancel:${order.id}", cancelBtn, 6f)
            bounds.putVisible("buyerCancel:${order.id}", cancelBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
        } else if (active && needsProof) {
            val payBtn = RectF(32f, top + 170f + rowOff, 358f, top + 200f + rowOff)
            kit.rounded(c, payBtn, kit.tint, 6f, kit.blue)
            kit.centerText(
                c,
                "Bayar via Midtrans",
                payBtn,
                11f,
                kit.blue,
                true
            )
            bounds.putVisible("myOrderPaid:${order.id}", payBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
        } else if (active && canRate) {
            val existingRate = myOrderRatings[order.id]
            val goldColor = Color.rgb(217, 119, 6)
            val rateBtn = RectF(32f, top + 170f + rowOff, 224f, top + 200f + rowOff)
            kit.rounded(c, rateBtn, if (existingRate != null) kit.card else kit.warnBox, 6f, goldColor)
            val rateText = if (existingRate != null) {
                "★ ${String.format(java.util.Locale.US, "%.1f", existingRate.ratingOverall)} · Ubah Rating"
            } else {
                "Beri Rating (1–5)"
            }
            kit.centerText(c, rateText, rateBtn, 10.5f, goldColor, true)
            press(c, "rateOrder:${order.id}", rateBtn, 6f)
            bounds.putVisible("rateOrder:${order.id}", rateBtn, topLimit = HEADER_H, bottomLimit = bottomClip)

            val repBtn = RectF(232f, top + 170f + rowOff, 358f, top + 200f + rowOff)
            kit.rounded(c, repBtn, kit.card, 6f, kit.dangerBorder)
            kit.centerText(c, "Laporkan Penjual", repBtn, 10.5f, kit.danger, true)
            press(c, "reportOrder:${order.id}", repBtn, 6f)
            bounds.putVisible("reportOrder:${order.id}", repBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
        } else if (!active) {
            kit.line(c, 32f, top + 116f + rowOff, 358f, top + 116f + rowOff, kit.divider)
            val btnTop = top + 124f + rowOff
            val btnBottom = btnTop + 28f
            if (canRate) {
                val existingRate = myOrderRatings[order.id]
                val goldColor = Color.rgb(217, 119, 6)
                val rateBtn = RectF(32f, btnTop, 224f, btnBottom)
                kit.rounded(c, rateBtn, if (existingRate != null) kit.card else kit.warnBox, 6f, goldColor)
                val rateText = if (existingRate != null) {
                    "★ ${String.format(java.util.Locale.US, "%.1f", existingRate.ratingOverall)} · Ubah Rating"
                } else {
                    "Beri Rating (1–5)"
                }
                kit.centerText(c, rateText, rateBtn, 10.5f, goldColor, true)
                press(c, "rateOrder:${order.id}", rateBtn, 6f)
                bounds.putVisible("rateOrder:${order.id}", rateBtn, topLimit = HEADER_H, bottomLimit = bottomClip)

                val repBtn = RectF(232f, btnTop, 358f, btnBottom)
                kit.rounded(c, repBtn, kit.card, 6f, kit.dangerBorder)
                kit.centerText(c, "Laporkan Penjual", repBtn, 10.5f, kit.danger, true)
                press(c, "reportOrder:${order.id}", repBtn, 6f)
                bounds.putVisible("reportOrder:${order.id}", repBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
            } else {
                val repBtn = RectF(32f, btnTop, 358f, btnBottom)
                kit.rounded(c, repBtn, kit.card, 6f, kit.dangerBorder)
                kit.centerText(c, "Laporkan Penjual (2 Foto Bukti)", repBtn, 10.5f, kit.danger, true)
                press(c, "reportOrder:${order.id}", repBtn, 6f)
                bounds.putVisible("reportOrder:${order.id}", repBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
            }
        }

        press(c, "buyerOrder:${order.id}", card, 8f)
    }

    val hitBottom = when {
        hasActiveActionRow -> top + 166f + extraSummaryH
        hasHistoryActionRow -> top + 116f + extraSummaryH
        else -> top + cardH
    }
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
