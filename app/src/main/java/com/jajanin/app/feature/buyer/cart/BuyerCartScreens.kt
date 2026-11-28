package com.jajanin.app.feature.buyer.cart

import com.jajanin.app.feature.seller.*

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.SERVICE_FEE
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.StoreAvatar
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.drawQr
import com.jajanin.app.core.ui.summaryRow
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.formatCalendarFullIdDate
import com.jajanin.app.data.repository.formatCalendarShortChip
import com.jajanin.app.data.repository.formatShortIdDate
import com.jajanin.app.feature.buyer.home.normalizeCategory
import com.jajanin.app.feature.buyer.onQrisTimerExpired

internal fun JajaninView.drawDetail(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val y = minOf(h * .34f, 290f)
    kit.image(c, selected.image, RectF(0f, 0f, w, y))
    val back = RectF(18f, 18f, 58f, 58f)
    kit.rounded(c, back, kit.card, 20f)
    AppIcon.draw(c, "chevronLeft", back.centerX(), back.centerY(), 20f, kit.ink)
    bounds["back"] = RectF(back)

    kit.rounded(c, RectF(0f, y - 18f, w, h + 20f), kit.card, 22f)
    kit.text(c, selected.name, 24f, y + 25f, 23f, kit.ink, true)
    kit.text(c, selected.stall, 24f, y + 49f, 13f, kit.muted)
    AppIcon.draw(c, "star", 31f, y + 72f, 13f, kit.star)
    kit.text(c, "${selected.rating}   ·   120+ terjual", 42f, y + 77f, 13f, kit.star, true)
    kit.line(c, 24f, y + 97f, w - 24f, y + 97f)
    kit.text(c, "Tentang menu", 24f, y + 127f, 16f, kit.ink, true)
    kit.text(c, "Disiapkan segar oleh penjual kantin pilihan.", 24f, y + 153f, 13f, kit.muted)
    kit.text(c, "Pedas, gurih, dan pas buat nemenin", 24f, y + 175f, 13f, kit.muted)
    kit.text(c, "waktu istirahatmu.", 24f, y + 196f, 13f, kit.muted)

    kit.text(c, "Jumlah", 24f, h - 104f, 14f, kit.ink, true)
    val minusBtn = RectF(234f, h - 126f, 279f, h - 76f)
    val plusBtn = RectF(321f, h - 126f, 366f, h - 76f)
    kit.rounded(c, RectF(234f, h - 126f, 366f, h - 76f), kit.bg, 14f, kit.border)
    AppIcon.draw(c, "minus", minusBtn.centerX(), minusBtn.centerY(), 16f, kit.blue)
    kit.text(c, "$quantity", 296f, h - 94f, 15f, kit.ink, true)
    AppIcon.draw(c, "plus", plusBtn.centerX(), plusBtn.centerY(), 16f, kit.blue)
    bounds["minus"] = minusBtn
    bounds["plus"] = plusBtn

    val cta = RectF(20f, h - 65f, w - 20f, h - 12f)
    kit.button(c, "Tambah ke keranjang · ${selected.price}", cta)
    bounds["checkout"] = RectF(cta)
}

internal fun JajaninView.drawCheckout(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bodyTop = 74f
    val bodyBottom = h - 84f
    data class CheckoutLine(
        val cartKey: String,
        val product: com.jajanin.app.data.model.ProductRow,
        val variantLabel: String,
        val qty: Int,
    )
    val checkoutLines = cart.entries.mapNotNull { (cartKey, qty) ->
        val pid = com.jajanin.app.data.model.productIdFromCartKey(cartKey)
        val p = products.firstOrNull { it.id == pid }
            ?: storeProfileProducts.firstOrNull { it.id == pid }
            ?: sellerProducts.firstOrNull { it.id == pid }
        if (p == null || qty <= 0) null
        else CheckoutLine(cartKey, p, com.jajanin.app.data.model.variantLabelFromCartKey(cartKey), qty)
    }
    val lines = checkoutLines.map { it.product to it.qty }
    if (checkoutLines.isEmpty()) {
        header(c, "Checkout")
        kit.centerText(c, "Keranjang kosong.", RectF(24f, 280f, 366f, 340f), 14f, kit.muted)
        kit.centerText(c, "Kembali dan pilih menu dulu.", RectF(24f, 310f, 366f, 360f), 12f, kit.muted)
        contentHeight = 0f
        return
    }

    val bodySave = c.save()
    c.clipRect(0f, bodyTop, w, bodyBottom)

    val checkoutStoreVerified = cartStallId?.let { storeDirectoryById[it]?.isVerified } == true
    StoreVerifiedBadge.drawInlineWithStoreName(
        view = this,
        c = c,
        storeName = cartStallName.take(30),
        isVerified = checkoutStoreVerified,
        x = 22f,
        baselineY = 108f + off,
        maxTotalWidth = 346f,
        textSize = 18f,
        textColor = kit.ink,
        bold = true,
    )
    kit.text(c, "${checkoutLines.sumOf { it.qty }} item (${checkoutLines.size} pilihan) di keranjang", 22f, 130f + off, 12f, kit.muted)
    var y = 146f + off
    checkoutLines.forEach { line ->
        val p = line.product
        val qty = line.qty
        val sub = formatRupiah(p.priceIdr * qty)
        val subW = kit.measure(sub, 12f, true)
        val nameMaxW = (358f - subW - 10f - 32f).coerceAtLeast(180f)
        val nameLines = kit.wrap(p.name, nameMaxW, 13f, maxLines = 2, bold = true).ifEmpty { listOf(p.name) }
        val varLines = if (line.variantLabel.isNotBlank()) {
            kit.wrap(line.variantLabel, 326f, 11f, maxLines = 4, bold = true)
        } else {
            emptyList()
        }
        val nameStep = 16.5f
        val varStep = 14.5f
        val nameEndRelY = 26f + (nameLines.size - 1) * nameStep
        val varStartRelY = nameEndRelY + 16f
        val ctrlTopRel = if (varLines.isNotEmpty()) {
            varStartRelY + (varLines.size - 1) * varStep + 12f
        } else {
            nameEndRelY + 14f
        }
        val cardH = maxOf(76f, ctrlTopRel + 34f)
        if (y + cardH >= bodyTop && y <= bodyBottom) {
            kit.card(c, RectF(18f, y, 372f, y + cardH), 14f)
            nameLines.forEachIndexed { idx, nLine ->
                kit.text(c, nLine, 32f, y + 26f + idx * nameStep, 13f, kit.ink, true)
            }
            kit.text(c, sub, 358f - subW, y + 26f, 12f, kit.ink, true)
            varLines.forEachIndexed { idx, vLine ->
                kit.text(c, vLine, 32f, y + varStartRelY + idx * varStep, 11f, kit.blue, true)
            }
            val ctrlTop = y + ctrlTopRel
            val priceY = ctrlTop + 15f
            kit.text(c, "${formatRupiah(p.priceIdr)}  ×  $qty", 32f, priceY, 11f, kit.muted)
            val minus = RectF(216f, ctrlTop, 244f, ctrlTop + 24f)
            val plus = RectF(274f, ctrlTop, 302f, ctrlTop + 24f)
            val rm = RectF(308f, ctrlTop, 358f, ctrlTop + 24f)
            kit.rounded(c, minus, kit.bg, 8f, kit.border)
            AppIcon.draw(c, "minus", minus.centerX(), minus.centerY(), 12f, kit.blue)
            kit.centerText(c, "$qty", RectF(244f, ctrlTop, 274f, ctrlTop + 24f), 12f, kit.ink, true)
            kit.rounded(c, plus, kit.bg, 8f, kit.border)
            AppIcon.draw(c, "plus", plus.centerX(), plus.centerY(), 12f, kit.blue)
            kit.centerText(c, "Hapus", rm, 10f, kit.danger, true)
            bounds.putVisible("cartMinus:${line.cartKey}", minus, topLimit = bodyTop, bottomLimit = bodyBottom)
            bounds.putVisible("cartPlus:${line.cartKey}", plus, topLimit = bodyTop, bottomLimit = bodyBottom)
            bounds.putVisible("cartRemove:${line.cartKey}", rm, topLimit = bodyTop, bottomLimit = bodyBottom)
        }
        y += cardH + 10f
    }

    // 1. Jadwal Pengambilan (Hari Ini vs Pre-Order Kalender)
    val canSameDay = lines.all { it.first.allowsSameDay }
    val canNextDay = lines.all { it.first.allowsNextDay }
    val activeMode = when {
        checkoutOrderMode == "pre_order" && canNextDay -> "pre_order"
        checkoutOrderMode == "same_day" && canSameDay -> "same_day"
        canSameDay -> "same_day"
        else -> "pre_order"
    }
    val poAccent = Color.rgb(183, 116, 0)
    kit.text(c, "Jadwal pengambilan", 22f, y + 22f, 15f, kit.ink, true)
    val sameBox = RectF(18f, y + 34f, 190f, y + 92f)
    val poBox = RectF(198f, y + 34f, 372f, y + 92f)

    val sameSelected = activeMode == "same_day"
    kit.rounded(
        c,
        sameBox,
        if (sameSelected) kit.tint else kit.card,
        13f,
        if (sameSelected) kit.blue else kit.border,
    )
    kit.text(c, "Hari Ini", 34f, y + 59f, 13f, if (canSameDay) kit.ink else kit.muted, true)
    kit.text(
        c,
        if (canSameDay) "Ambil hari ini" else "Khusus Pre-Order",
        34f,
        y + 79f,
        10f,
        kit.muted,
    )
    if (sameSelected) AppIcon.draw(c, "radioChecked", 170f, y + 64f, 16f, kit.blue)
    bounds.putVisible("orderMode:same_day", sameBox, topLimit = bodyTop, bottomLimit = bodyBottom)

    val poSelected = activeMode == "pre_order"
    kit.rounded(
        c,
        poBox,
        if (poSelected) kit.warnBox else kit.card,
        13f,
        if (poSelected) poAccent else kit.border,
    )
    kit.text(c, "Pre-Order (PO)", 214f, y + 59f, 13f, if (canNextDay) kit.ink else kit.muted, true)
    kit.text(
        c,
        if (canNextDay) "Pilih tanggal kalender" else "Khusus hari ini",
        214f,
        y + 79f,
        10f,
        kit.muted,
    )
    if (poSelected) AppIcon.draw(c, "radioChecked", 350f, y + 64f, 16f, poAccent)
    bounds.putVisible("orderMode:pre_order", poBox, topLimit = bodyTop, bottomLimit = bodyBottom)

    y += 106f

    // 1b. Pemilih Tanggal Kalender (tampil saat Pre-Order aktif)
    val tomorrowIso = OrderRepository.tomorrowWibDateString()
    val minCutoffHour = lines.minOfOrNull { it.first.poCutoffHour }?.coerceIn(12, 23) ?: 20
    val allowWeekendPo = lines.isNotEmpty() && lines.all { it.first.poAllowWeekend }
    var commonPoDates: Set<String>? = null
    lines.forEach { (p, _) ->
        if (p.poDates.isNotBlank() || p.orderSchedule == "next_day") {
            val s = p.openParsedPoDates.toSet()
            commonPoDates = if (commonPoDates == null) s else commonPoDates?.intersect(s)
        }
    }
    val sellerPoDates = commonPoDates?.sorted().orEmpty()
    val defaultSchoolDate = OrderRepository.nextOpenSchoolDatesWib(1, minCutoffHour, allowWeekendPo).firstOrNull()
        ?: tomorrowIso
    val chosenPoDate = when {
        sellerPoDates.isNotEmpty() -> if (checkoutPickupDate in sellerPoDates) checkoutPickupDate else sellerPoDates.first()
        OrderRepository.isValidPreOrderDate(checkoutPickupDate) &&
            (allowWeekendPo || !OrderRepository.isWeekendWib(checkoutPickupDate)) &&
            !OrderRepository.isPastPoCutoffWib(checkoutPickupDate, minCutoffHour) -> checkoutPickupDate
        else -> defaultSchoolDate
    }
    val daysAheadPo = OrderRepository.daysAheadFromTodayWib(chosenPoDate)
    val poRequiresQris = poSelected && (daysAheadPo > 3 || lines.any { it.first.poRequireQris })
    val poQrisReason = if (daysAheadPo > 3) {
        "PO > 3 hari wajib QRIS di muka"
    } else {
        "Menu PO ini wajib QRIS di muka"
    }

    if (poSelected) {
        val dateCard = RectF(18f, y + 4f, 372f, y + 74f)
        kit.rounded(c, dateCard, kit.warnBox, 12f, poAccent)
        val headerLabel = if (sellerPoDates.isNotEmpty()) "TANGGAL PO DARI PENJUAL (SEN–JUM)" else "TANGGAL PENGAMBILAN PO (SEN–JUM)"
        kit.text(c, headerLabel, 32f, y + 21f, 9f, poAccent, true)
        val fullLabel = formatCalendarFullIdDate(chosenPoDate)
        val dateDisplay = if (chosenPoDate == tomorrowIso) "$fullLabel (Besok)" else fullLabel
        kit.textEllipsized(c, dateDisplay, 32f, y + 41f, 232f, 12.5f, kit.ink, true)

        val slot = checkoutPoSlots[chosenPoDate]
        val quotaInfo = when {
            slot != null && slot.quota != null -> " · Sisa ${slot.remaining ?: 0}/${slot.quota} porsi"
            lines.any { it.first.poDailyQuota != null } -> {
                val q = lines.mapNotNull { it.first.poDailyQuota }.minOrNull()
                if (q != null) " · Kuota $q/hari" else ""
            }
            else -> ""
        }
        val metaLine = "Tutup H-1 ${minCutoffHour}:00 WIB$quotaInfo"
        kit.textEllipsized(c, metaLine, 32f, y + 59f, 232f, 9.5f, kit.muted, true)

        val calPill = RectF(272f, y + 22f, 360f, y + 52f)
        kit.rounded(c, calPill, kit.card, 8f, poAccent)
        kit.centerText(c, "Kalender", calPill, 11f, poAccent, true)
        bounds.putVisible("pickPoDateCard", dateCard, topLimit = bodyTop, bottomLimit = bodyBottom)
        press(c, "pickPoDateCard", dateCard, 12f)

        // Baris cepat: tanggal PO penjual (bila dikunci) atau 3 hari sekolah terbuka + Kalender...
        val quickChoices: List<Pair<String, String>> = if (sellerPoDates.isNotEmpty()) {
            if (sellerPoDates.size <= 4) {
                sellerPoDates.map { iso ->
                    val lbl = if (iso == tomorrowIso) "Besok (${formatShortIdDate(iso)})" else formatCalendarShortChip(iso)
                    "poQuickDate:$iso" to lbl
                }
            } else {
                sellerPoDates.take(3).map { iso ->
                    val lbl = if (iso == tomorrowIso) "Besok (${formatShortIdDate(iso)})" else formatCalendarShortChip(iso)
                    "poQuickDate:$iso" to lbl
                } + ("pickPoDateMore" to "Kalender...")
            }
        } else {
            val openDates = OrderRepository.nextOpenSchoolDatesWib(3, minCutoffHour, allowWeekendPo)
            openDates.map { iso ->
                val lbl = if (iso == tomorrowIso) "Besok (${formatShortIdDate(iso)})" else formatCalendarShortChip(iso)
                "poQuickDate:$iso" to lbl
            } + ("pickPoDateMore" to "Kalender...")
        }
        val qGap = 6f
        val qW = (354f - qGap * (quickChoices.size - 1).coerceAtLeast(0)) / quickChoices.size.coerceAtLeast(1)
        val shownQuickIsos = quickChoices.mapNotNull { (k, _) ->
            if (k.startsWith("poQuickDate:")) k.removePrefix("poQuickDate:") else null
        }.toSet()
        quickChoices.forEachIndexed { idx, (key, label) ->
            val left = 18f + idx * (qW + qGap)
            val chip = RectF(left, y + 82f, left + qW, y + 112f)
            val isQuickSelected = when (key) {
                "pickPoDateMore" -> chosenPoDate !in shownQuickIsos
                else -> key.removePrefix("poQuickDate:") == chosenPoDate
            }
            kit.rounded(
                c,
                chip,
                if (isQuickSelected) kit.warnBox else kit.card,
                8f,
                if (isQuickSelected) poAccent else kit.border,
            )
            kit.centerText(c, label, chip, 9.5f, if (isQuickSelected) poAccent else kit.ink, isQuickSelected)
            bounds.putVisible(key, chip, topLimit = bodyTop, bottomLimit = bodyBottom)
            press(c, key, chip, 8f)
        }
        y += 124f
    }

    // 2. Sesi Pengambilan (Istirahat 1 / Istirahat 2 / Pulang)
    var commonSessions: Set<String>? = null
    lines.forEach { (p, _) ->
        val s = p.parsedPickupSessions.toSet()
        commonSessions = if (commonSessions == null) s else commonSessions?.intersect(s)
    }
    val sessionList = DEFAULT_PICKUP_SESSIONS.filter { commonSessions?.contains(it) == true }
        .ifEmpty { commonSessions?.toList()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PICKUP_SESSIONS }
    val activeSession = if (checkoutPickupSession in sessionList) checkoutPickupSession else sessionList.first()

    kit.text(c, "Sesi pengambilan", 22f, y + 14f, 13f, kit.muted, true)
    val chipGap = 8f
    val chipW = (354f - chipGap * (sessionList.size - 1).coerceAtLeast(0)) / sessionList.size.coerceAtLeast(1)
    sessionList.forEachIndexed { idx, sess ->
        val left = 18f + idx * (chipW + chipGap)
        val chip = RectF(left, y + 24f, left + chipW, y + 58f)
        val on = sess == activeSession
        kit.rounded(c, chip, if (on) kit.tint else kit.card, 10f, if (on) kit.blue else kit.border)
        kit.centerText(c, sess, chip, 11.5f, if (on) kit.blue else kit.ink, on)
        bounds.putVisible("pickupSession:$sess", chip, topLimit = bodyTop, bottomLimit = bodyBottom)
    }
    y += 72f

    // 3. Metode Pembayaran
    kit.text(c, "Metode pembayaran", 22f, y + 22f, 15f, kit.ink, true)
    val cashLockedByPo = poRequiresQris
    val cashBox = RectF(18f, y + 34f, 190f, y + 92f)
    val cashOn = !cashLockedByPo && checkoutPayment == "cash"
    kit.rounded(
        c,
        cashBox,
        if (cashLockedByPo) kit.bg else kit.card,
        13f,
        if (cashOn) kit.blue else kit.border,
    )
    kit.text(c, "Tunai", 34f, y + 59f, 13f, if (cashLockedByPo) kit.muted else kit.ink, true)
    val cashSub = if (cashLockedByPo) poQrisReason else "Bayar saat ambil"
    kit.textEllipsized(c, cashSub, 34f, y + 79f, (cashBox.width() - 28f).coerceAtLeast(100f), 9.5f, if (cashLockedByPo) poAccent else kit.muted)
    if (cashOn) AppIcon.draw(c, "radioChecked", cashBox.right - 20f, y + 64f, 16f, kit.blue)
    bounds.putVisible("payMethod:cash", cashBox, topLimit = bodyTop, bottomLimit = bodyBottom)

    val qrisBox = RectF(198f, y + 34f, 372f, y + 92f)
    val qrisOn = cashLockedByPo || !cashOn
    kit.rounded(c, qrisBox, if (cashLockedByPo) kit.warnBox else kit.card, 13f, if (qrisOn) (if (cashLockedByPo) poAccent else kit.blue) else kit.border)
    kit.text(c, if (cashLockedByPo) "Midtrans (Wajib)" else "Midtrans", 212f, y + 59f, 13f, kit.ink, true)
    kit.textEllipsized(
        c,
        if (cashLockedByPo) "QRIS otomatis lunas" else "QRIS dinamis · otomatis",
        212f,
        y + 79f,
        130f,
        9.5f,
        if (cashLockedByPo) poAccent else kit.muted,
    )
    if (qrisOn) AppIcon.draw(c, "radioChecked", 352f, y + 64f, 16f, if (cashLockedByPo) poAccent else kit.blue)
    bounds.putVisible("payMethod:qris", qrisBox, topLimit = bodyTop, bottomLimit = bodyBottom)

    val summaryOffset = y + 128f

    val subtotal = lines.sumOf { (p, qty) -> p.priceIdr * qty }
    val totalY = summaryOffset
    kit.text(c, "Ringkasan", 22f, totalY, 15f, kit.ink, true)
    val scheduleText = if (activeMode == "pre_order") {
        "PO ${formatCalendarShortChip(chosenPoDate)} · $activeSession"
    } else {
        "Hari Ini · $activeSession"
    }
    summaryRow(c, "Jadwal ambil", scheduleText, totalY + 28f)
    summaryRow(c, "Subtotal", formatRupiah(subtotal), totalY + 54f)
    summaryRow(c, "Biaya layanan", formatRupiah(SERVICE_FEE), totalY + 80f)
    kit.line(c, 22f, totalY + 98f, 368f, totalY + 98f)
    summaryRow(c, "Total", formatRupiah(subtotal + SERVICE_FEE), totalY + 126f, true)
    val noteLine = if (activeMode == "pre_order") {
        "Pesanan Pre-Order bersifat final dan tidak dapat dibatalkan oleh pembeli."
    } else {
        "Harga final dihitung ulang server dari database."
    }
    kit.textEllipsized(c, noteLine, 22f, totalY + 148f, 346f, 10f, if (activeMode == "pre_order") poAccent else kit.muted, activeMode == "pre_order")
    kit.text(c, "Jam server: ${serverClockLabel()} · live", 22f, totalY + 168f, 11f, kit.muted)

    c.restoreToCount(bodySave)
    contentHeight = (totalY - off) + 280f
    if (currentScreen == Screen.CHECKOUT) postInvalidateDelayed(1000)

    header(c, "Checkout")

    kit.rect(c, 0f, h - 84f, w, h, kit.bg)
    kit.line(c, 20f, h - 84f, 370f, h - 84f, kit.divider)
    val cta = RectF(20f, h - 72f, 370f, h - 18f)
    if (placingOrder) {
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Membuat pesanan...", cta, 14f, Color.WHITE, true)
    } else {
        val activeMethod = if (poRequiresQris || checkoutPayment == "qris") "qris" else "cash"
        val prefix = if (activeMode == "pre_order") "Buat Pre-Order" else "Buat pesanan"
        kit.button(c, if (activeMethod == "cash") "$prefix · bayar tunai" else "$prefix · bayar Midtrans", cta)
        bounds["pay"] = RectF(cta)
    }
}

internal fun JajaninView.drawPayment(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bodyTop = 70f
    val bodyBottom = h - 84f
    val bodySave = c.save()
    c.clipRect(0f, bodyTop, w, bodyBottom)

    var y = 84f + off
    val stallLabel = cartStallName.takeIf { it.isNotBlank() }?.take(20) ?: "Warung"
    kit.textEllipsized(c, "Bayar Midtrans · $stallLabel", 22f, y + 18f, 232f, 16.5f, kit.ink, true)
    val isSandbox = qrisEnvironment.equals("sandbox", ignoreCase = true)
    if (isSandbox) {
        val envPill = RectF(260f, y + 2f, 370f, y + 22f)
        kit.rounded(c, envPill, kit.warnBox, 7f, Color.rgb(183, 116, 0))
        kit.centerText(c, "SANDBOX", envPill, 9.5f, Color.rgb(183, 116, 0), true)
    }
    y += 30f

    // 1. Live payment deadline & automatic settlement banner
    val remSec = com.jajanin.app.data.repository.remainingPaymentSeconds(qrisExpiresAt)
    val isExpired = remSec != null && remSec <= 0L
    val isUrgent = remSec != null && remSec in 1L..120L
    if (isExpired) {
        qrisOrderId?.let { oid -> activity()?.onQrisTimerExpired(oid) }
    } else if (remSec != null && currentScreen == Screen.QRIS) {
        postInvalidateDelayed(1000)
    }
    val bannerRect = RectF(20f, y, 370f, y + 48f)
    val bannerBg = if (isExpired || isUrgent) kit.dangerBorder else kit.warnBox
    val bannerAccent = if (isExpired || isUrgent) kit.danger else Color.rgb(183, 116, 0)
    kit.rounded(c, bannerRect, bannerBg, 10f, bannerAccent)
    val timerHeadline = when {
        isExpired -> "Batas waktu bayar Midtrans habis · Dibatalkan"
        remSec != null -> "Batas waktu bayar: ${com.jajanin.app.data.repository.formatCountdownMmSs(remSec)}"
        else -> "Pembayaran diproses otomatis oleh Midtrans"
    }
    kit.textEllipsized(c, timerHeadline, 32f, y + 20f, 326f, 12f, bannerAccent, true)
    kit.textEllipsized(
        c,
        "Stok diamankan & pesanan lunas otomatis saat pembayaran diterima.",
        32f,
        y + 38f,
        326f,
        10f,
        kit.ink,
    )
    y += 58f

    // 2. Total & Pickup Code Card
    val amtCard = RectF(20f, y, 370f, y + 66f)
    kit.rounded(c, amtCard, kit.card, 12f, kit.border)
    kit.text(c, "Total tagihan Midtrans", 34f, y + 22f, 11f, kit.muted)
    kit.text(c, formatRupiah(qrisAmount), 34f, y + 50f, 22f, kit.ink, true)
    if (!lastOrderPickup.isNullOrBlank()) {
        val codePill = RectF(268f, y + 19f, 356f, y + 47f)
        kit.rounded(c, codePill, kit.tint, 8f)
        kit.centerText(c, "#$lastOrderPickup", codePill, 13f, kit.blue, true)
    }
    y += 78f

    // 3. Standard Midtrans Snap Checkout Card
    val snapCardH = if (isSandbox) 216f else 170f
    val snapCard = RectF(20f, y, 370f, y + snapCardH)
    kit.rounded(c, snapCard, kit.card, 14f, kit.border)
    kit.text(c, "PEMBAYARAN MIDTRANS", 34f, y + 24f, 11f, kit.blue, true)
    when {
        qrisLoading -> {
            kit.text(c, "Membuka jendela pembayaran Midtrans...", 34f, y + 56f, 13f, kit.ink, true)
            kit.text(c, "Mohon tunggu sebentar.", 34f, y + 78f, 11f, kit.muted)
        }
        !qrisError.isNullOrBlank() && qrisRedirectUrl.isNullOrBlank() -> {
            kit.text(c, "Gagal memuat Midtrans Snap", 34f, y + 50f, 13f, kit.danger, true)
            kit.textEllipsized(c, qrisError.orEmpty(), 34f, y + 70f, 322f, 10.5f, kit.muted)
            val retryBtn = RectF(34f, y + 90f, 356f, y + 132f)
            kit.rounded(c, retryBtn, kit.blue, 10f)
            kit.centerText(c, "Coba Lagi Buka Midtrans", retryBtn, 12.5f, Color.WHITE, true)
            bounds.putVisible("midtransRetryCreate", retryBtn, topLimit = bodyTop, bottomLimit = bodyBottom)
        }
        else -> {
            kit.textEllipsized(
                c,
                "Pilih metode bayar (QRIS, GoPay, ShopeePay, DANA, Virtual Account BCA/BNI/BRI/Mandiri).",
                34f,
                y + 48f,
                322f,
                11.5f,
                kit.ink,
            )
            val openSnapBtn = RectF(34f, y + 68f, 356f, y + 112f)
            kit.rounded(c, openSnapBtn, kit.blue, 10f)
            kit.centerText(c, "Buka Pembayaran Midtrans", openSnapBtn, 13.5f, Color.WHITE, true)
            bounds.putVisible("midtransOpenSnap", openSnapBtn, topLimit = bodyTop, bottomLimit = bodyBottom)

            if (isSandbox) {
                kit.textEllipsized(
                    c,
                    "Atau lunasi langsung 1-klik dalam mode uji coba Sandbox:",
                    34f,
                    y + 138f,
                    322f,
                    10.5f,
                    kit.muted,
                )
                val simBtn = RectF(34f, y + 150f, 356f, y + 194f)
                kit.rounded(c, simBtn, kit.tint, 10f, kit.blue)
                kit.centerText(
                    c,
                    if (qrisCheckingStatus) "Melunasi pembayaran..." else "Bayar Lunas Sekarang (Simulasi Sandbox)",
                    simBtn,
                    12.5f,
                    kit.blue,
                    true,
                )
                bounds.putVisible("midtransOpenSimulator", simBtn, topLimit = bodyTop, bottomLimit = bodyBottom)
            }
        }
    }

    c.restoreToCount(bodySave)
    contentHeight = (y - off) + snapCardH + 28f

    header(c, "Pembayaran Midtrans")

    // Sticky Bottom CTA
    kit.rect(c, 0f, bodyBottom, w, h, kit.bg)
    kit.line(c, 20f, bodyBottom, 370f, bodyBottom, kit.divider)
    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    when {
        isExpired -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Waktu Bayar Habis", cta, 14f, Color.WHITE, true)
        }
        qrisLoading -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Menyiapkan Midtrans...", cta, 14f, Color.WHITE, true)
        }
        qrisCheckingStatus -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Memproses Pembayaran...", cta, 14f, Color.WHITE, true)
        }
        else -> {
            kit.rounded(c, cta, kit.blue, 14f)
            kit.centerText(c, "Buka Pembayaran Midtrans", cta, 14f, Color.WHITE, true)
            bounds["midtransOpenSnap"] = RectF(cta)
        }
    }
}

internal fun JajaninView.drawProductDetail(c: Canvas, w: Float, h: Float) {
    val product = selectedProduct ?: run { goBack(); return }
    c.drawColor(kit.bg)
    val off = -scroll
    val footerH = 64f
    val bodyBottom = h - footerH
    val available = product.available && (product.stock == null || product.stock > 0)
    val variantSections = product.activeVariantSections
    val stallId = product.stallId?.takeIf { it.isNotBlank() }
    val storeSummary = stallId?.let { storeDirectoryById[it] }
    val storeDetail = storeProfileDetail?.takeIf { it.id == stallId }
    val soldQty = topSellingStats[product.id]?.totalQty
        ?: topSellingStats[product.name.trim().lowercase()]?.totalQty
        ?: 0
    val isFav = favoriteIds.contains(product.id)
    val heroH = minOf(h * 0.36f, 290f)
    val showSolidTopBar = scroll > heroH - 76f
    val bodyHitTop = if (showSolidTopBar) 60f else 0f

    val bodySave = c.save()
    c.clipRect(0f, 0f, w, bodyBottom)

    val photos = product.allImageUrls
    val safePhotoIdx = detailPhotoIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    val activePhotoUrl = photos.getOrNull(safePhotoIdx)

    // 1. Full-bleed edge-to-edge Hero Photo (Shopee-style multi-photo gallery)
    val heroRect = RectF(0f, off, w, heroH + off)
    drawDetailPhotoUrl(c, product, activePhotoUrl, heroRect, 0f)
    val topScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            heroRect.top,
            0f,
            heroRect.top + 68f,
            Color.argb(120, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }
    c.drawRect(0f, heroRect.top, w, heroRect.top + 68f, topScrimPaint)

    if (photos.size > 1) {
        val navY = heroRect.centerY()
        val prevBtn = RectF(12f, navY - 17f, 46f, navY + 17f)
        val nextBtn = RectF(w - 46f, navY - 17f, w - 12f, navY + 17f)
        kit.rounded(c, prevBtn, Color.argb(125, 12, 18, 30), 17f)
        drawIcon(c, "chevronLeft", prevBtn.centerX() - 1f, prevBtn.centerY(), 16f, Color.WHITE)
        kit.rounded(c, nextBtn, Color.argb(125, 12, 18, 30), 17f)
        drawIcon(c, "chevronRight", nextBtn.centerX() + 1f, nextBtn.centerY(), 16f, Color.WHITE)
        bounds.putVisible("detailPhotoPrev", prevBtn, topLimit = bodyHitTop, bottomLimit = bodyBottom)
        bounds.putVisible("detailPhotoNext", nextBtn, topLimit = bodyHitTop, bottomLimit = bodyBottom)
    }

    // Bottom-right translucent badge on hero ("1/5" when multi-photo, else category · stock)
    val catText = if (normalizeCategory(product.category) == "Food") "Makanan" else "Minuman"
    val heroTagText = if (photos.size > 1) {
        "${safePhotoIdx + 1}/${photos.size}"
    } else if (available) {
        if (product.stock != null) "$catText · Stok ${product.stock}" else "$catText · Tersedia"
    } else {
        "Stok Habis"
    }
    val heroTagW = (kit.measure(heroTagText, 10.5f, true) + 20f).coerceAtLeast(42f)
    val heroTagRect = RectF(w - heroTagW - 14f, heroRect.bottom - 34f, w - 14f, heroRect.bottom - 12f)
    kit.rounded(c, heroTagRect, Color.argb(175, 14, 19, 32), 11f)
    kit.centerText(c, heroTagText, heroTagRect, 10.5f, Color.WHITE, true)

    var curY = heroRect.bottom

    // 1b. Multi-Photo Thumbnail Strip (up to 5 photos, right under hero just like Shopee!)
    if (photos.size > 1) {
        curY += 10f
        kit.text(c, "${photos.size} Foto Produk", 18f, curY + 11f, 11f, kit.muted, true)
        curY += 18f
        val thumbSize = 54f
        val thumbGap = 8f
        photos.forEachIndexed { idx, url ->
            val left = 18f + idx * (thumbSize + thumbGap)
            if (left + thumbSize <= w - 10f) {
                val r = RectF(left, curY, left + thumbSize, curY + thumbSize)
                drawDetailPhotoUrl(c, product, url, r, 8f)
                val selected = idx == safePhotoIdx
                kit.rounded(c, r, Color.TRANSPARENT, 8f, if (selected) kit.blue else kit.border)
                if (selected) {
                    val innerRing = RectF(r.left + 1.2f, r.top + 1.2f, r.right - 1.2f, r.bottom - 1.2f)
                    kit.rounded(c, innerRing, Color.TRANSPARENT, 7f, kit.blue)
                }
                bounds.putVisible("detailPhoto:$idx", r, topLimit = bodyHitTop, bottomLimit = bodyBottom)
            }
        }
        curY += thumbSize + 10f
        kit.line(c, 0f, curY, w, curY, kit.divider)
    }

    // 2. Variant Preview Strip right beneath hero (just like "3 Variasi Tersedia")
    if (variantSections.isNotEmpty()) {
        val totalOpts = variantSections.sumOf { it.options.size }
        val sectionSummary = variantSections.joinToString(", ") { it.cleanName }
        val stripTop = curY
        curY += 12f
        kit.textEllipsized(
            c,
            "$totalOpts Variasi Tersedia · $sectionSummary",
            18f,
            curY + 12f,
            286f,
            11.5f,
            kit.muted,
            true,
        )
        drawTrailingActionWithArrow(c, "Pilih", 372f, curY + 12f, 11.5f, kit.blue)
        curY += 22f

        // Horizontal compact preview chips (tapping strip opens Bottom Sheet)
        val previewItems = buildList {
            variantSections.forEachIndexed { secIdx, sec ->
                val chosen = detailSelectedVariants[secIdx] ?: sec.options.firstOrNull().orEmpty()
                sec.options.forEach { opt ->
                    add(opt to (opt == chosen))
                }
            }
        }
        var px = 18f
        val chipH = 28f
        var shown = 0
        for ((opt, isChosen) in previewItems) {
            val cw = (kit.measure(opt, 11f, isChosen) + 20f).coerceIn(48f, 128f)
            val remaining = previewItems.size - shown - 1
            val reserveOverflow = if (remaining > 0) 44f else 0f
            if (px + cw + reserveOverflow > 372f && shown > 0) break
            val chipR = RectF(px, curY, px + cw, curY + chipH)
            kit.rounded(c, chipR, if (isChosen) kit.tint else kit.card, 7f, if (isChosen) kit.blue else kit.border)
            kit.centerText(c, opt, chipR, 11f, if (isChosen) kit.blue else kit.ink, isChosen)
            px += cw + 7f
            shown++
        }
        val extra = previewItems.size - shown
        if (extra > 0) {
            val moreR = RectF(px, curY, (px + 38f).coerceAtMost(372f), curY + chipH)
            kit.rounded(c, moreR, kit.card, 7f, kit.border)
            kit.centerText(c, "+$extra", moreR, 10.5f, kit.muted, true)
        }
        curY += chipH + 12f
        kit.line(c, 0f, curY, w, curY, kit.divider)
        val stripRect = RectF(0f, stripTop, w, curY)
        if (available) {
            press(c, "detailOpenSheet:cart", stripRect, 0f)
            bounds.putVisible("detailOpenSheet:cart", stripRect, topLimit = bodyHitTop, bottomLimit = bodyBottom)
        }
    }

    // 3. Price + Sold Count + Favorite + Product Title Block
    curY += 14f
    kit.text(c, formatRupiah(product.priceIdr), 18f, curY + 21f, 23f, kit.blue, true)

    val favRect = RectF(344f, curY + 1f, 376f, curY + 31f)
    drawIcon(
        c,
        if (isFav) "heartFill" else "heart",
        favRect.centerX(),
        favRect.centerY(),
        17f,
        if (isFav) kit.danger else kit.muted,
    )
    bounds.putVisible("fav:${product.id}", favRect, topLimit = bodyHitTop, bottomLimit = bodyBottom)

    val soldLabel = if (soldQty > 0) "${soldQty}x Terjual" else if (available) "Menu Baru" else "Habis"
    kit.textRight(c, soldLabel, favRect.left - 8f, curY + 19f, 110f, 12f, kit.muted, false)
    curY += 34f

    // Schedule Pill + Bold Title (inline style like Shopee "Star+ PRODUCT NAME")
    val schedBadge = product.scheduleBadgeLabel
    val nextOnly = product.orderSchedule == "next_day"
    val schedW = (kit.measure(schedBadge, 9.5f, true) + 14f).coerceAtMost(112f)
    val schedPill = RectF(18f, curY + 1f, 18f + schedW, curY + 20f)
    kit.rounded(c, schedPill, if (nextOnly) kit.warnBox else kit.tint, 5f)
    kit.centerText(c, schedBadge, schedPill, 9.5f, if (nextOnly) Color.rgb(183, 116, 0) else kit.blue, true)

    val titleX = schedPill.right + 8f
    val titleAvailW = (372f - titleX).coerceAtLeast(160f)
    if (kit.measure(product.name, 16.5f, true) <= titleAvailW) {
        kit.text(c, product.name, titleX, curY + 16f, 16.5f, kit.ink, true)
        curY += 28f
    } else {
        // Wrap full name cleanly starting below badge if very long
        curY += 26f
        val usedTitleH = kit.textWrapped(
            c,
            product.name,
            18f,
            curY + 14f,
            354f,
            16.5f,
            kit.ink,
            bold = true,
            maxLines = 2,
            lineHeight = 22f,
        )
        curY += usedTitleH + 4f
    }

    // Section divider strip
    curY += 6f
    kit.rect(c, 0f, curY, w, curY + 7f, kit.divider)
    curY += 7f

    // 4. Clean Icon Info Rows (Pickup Room & Sessions, Availability & Payment)
    val room = product.pickupRoom.ifBlank {
        storeSummary?.location?.trim().orEmpty()
    }
    val poSummary = product.poDatesDisplaySummary
    val poRuleLine = if (product.allowsNextDay) {
        buildList {
            if (poSummary.isNotBlank()) add("PO: $poSummary")
            add("Tutup H-1 ${product.poCutoffHour}:00")
            product.poDailyQuota?.let { add("Kuota $it/hari") }
            if (product.poRequireQris) add("Wajib Midtrans")
        }.joinToString(" · ")
    } else {
        ""
    }
    val pickupRowTop = curY
    curY += 14f
    AppIcon.draw(c, "location", 30f, curY + 11f, 18f, kit.blue)
    kit.textEllipsized(
        c,
        if (room.isNotBlank()) "Ambil di $room" else "Ambil di warung sekolah",
        48f,
        curY + 14f,
        324f,
        13f,
        kit.ink,
        true,
    )
    kit.textEllipsized(
        c,
        "Sesi ambil: ${product.parsedPickupSessions.joinToString(" · ")}",
        48f,
        curY + 32f,
        324f,
        11.5f,
        kit.muted,
    )
    curY += 40f
    if (poRuleLine.isNotBlank()) {
        kit.textEllipsized(
            c,
            poRuleLine,
            48f,
            curY + 11f,
            324f,
            10.5f,
            if (darkMode) Color.rgb(245, 178, 56) else Color.rgb(183, 116, 0),
            true,
        )
        curY += 18f
    }
    curY += 8f
    kit.line(c, 18f, curY, 372f, curY, kit.divider)

    // Row 2: Availability & Payment info
    curY += 13f
    drawIcon(
        c,
        if (available) "checkCircle" else "alert",
        30f,
        curY + 11f,
        17f,
        if (available) kit.success else kit.danger,
    )
    val stockHeadline = when {
        !available -> "Stok sedang habis"
        product.stock != null -> "Tersedia · Sisa stok ${product.stock} porsi"
        else -> "Tersedia untuk dipesan"
    }
    kit.textEllipsized(c, stockHeadline, 48f, curY + 14f, 324f, 13f, if (available) kit.ink else kit.danger, true)
    val paySub = if (product.poRequireQris) {
        "Pembayaran via Midtrans QRIS / E-Wallet"
    } else {
        "Mendukung Tunai & Midtrans QRIS / E-Wallet"
    }
    kit.textEllipsized(c, paySub, 48f, curY + 32f, 324f, 11.5f, kit.muted)
    curY += 44f

    // Section divider strip
    kit.rect(c, 0f, curY, w, curY + 7f, kit.divider)
    curY += 7f

    // 5. Store / Warung Row (Avatar + Verified Badge + Rating + "Kunjungi")
    if (stallId != null) {
        val storeName = storeSummary?.name ?: storeDetail?.name ?: "Lihat Profil Warung"
        val storeLogo = storeSummary?.logoUrl ?: storeDetail?.logoUrl
        val storeVerified = storeSummary?.isVerified ?: (storeDetail?.isVerified == true)
        val ratingCount = storeSummary?.ratingCount ?: 0
        val avgRating = storeSummary?.avgRating ?: 0.0
        val menuCount = storeSummary?.activeProductCount ?: 0

        val storeRowRect = RectF(0f, curY, w, curY + 68f)
        press(c, "openStore:$stallId", storeRowRect, 0f)
        val avRect = RectF(18f, curY + 13f, 60f, curY + 55f)
        StoreAvatar.draw(this, c, storeName, storeLogo, avRect, cornerRadius = 21f)

        val visitBtn = RectF(294f, curY + 19f, 372f, curY + 49f)
        kit.rounded(c, visitBtn, kit.tint, 8f, kit.blue)
        kit.centerText(c, "Kunjungi", visitBtn, 11.5f, kit.blue, true)

        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = storeName,
            isVerified = storeVerified,
            x = 70f,
            baselineY = curY + 30f,
            maxTotalWidth = 216f,
            textSize = 14f,
            textColor = kit.ink,
            bold = true,
            stallId = stallId,
        )
        val storeMeta = buildString {
            if (ratingCount > 0) {
                append("★ ${String.format(java.util.Locale.US, "%.1f", avgRating)} ($ratingCount)")
                if (menuCount > 0) append(" · $menuCount menu")
            } else if (menuCount > 0) {
                append("$menuCount menu tersedia")
            } else {
                append("Ketuk untuk lihat etalase warung")
            }
        }
        kit.textEllipsized(c, storeMeta, 70f, curY + 49f, 216f, 11.5f, kit.muted)
        bounds.putVisible("openStore:$stallId", storeRowRect, topLimit = bodyHitTop, bottomLimit = bodyBottom)
        curY += 68f

        kit.rect(c, 0f, curY, w, curY + 7f, kit.divider)
        curY += 7f
    }

    // 6. Deskripsi & Bahan Produk
    curY += 14f
    kit.text(c, "Deskripsi Produk", 18f, curY + 13f, 14f, kit.ink, true)
    curY += 24f
    val descText = product.description.ifBlank { "Belum ada deskripsi produk." }
    val descH = kit.textWrapped(
        c,
        descText,
        18f,
        curY + 12f,
        354f,
        12.5f,
        kit.muted,
        maxLines = 8,
        lineHeight = 18.5f,
    )
    curY += descH + 6f
    val cleanIng = product.cleanIngredients
    if (cleanIng.isNotBlank()) {
        val ingH = kit.textWrapped(
            c,
            "Bahan: $cleanIng",
            18f,
            curY + 12f,
            354f,
            12f,
            kit.muted,
            maxLines = 3,
            lineHeight = 17.5f,
        )
        curY += ingH + 4f
    }
    curY += 10f

    // 7. Penilaian Warung Summary Row (like Shopee "4.9 ★ Penilaian Produk   Lihat Semua >")
    if (stallId != null) {
        kit.rect(c, 0f, curY, w, curY + 7f, kit.divider)
        curY += 7f
        val ratingRowRect = RectF(0f, curY, w, curY + 52f)
        press(c, "openStore:$stallId", ratingRowRect, 0f)
        val ratingCount = storeSummary?.ratingCount ?: 0
        val avgRating = storeSummary?.avgRating ?: 0.0
        val starColor = if (darkMode) Color.rgb(250, 204, 21) else Color.rgb(217, 138, 0)
        if (ratingCount > 0) {
            val scoreStr = String.format(java.util.Locale.US, "%.1f", avgRating)
            kit.text(c, scoreStr, 18f, curY + 31f, 15.5f, kit.ink, true)
            val scoreW = kit.measure(scoreStr, 15.5f, true)
            kit.text(c, "★", 18f + scoreW + 4f, curY + 31f, 15f, starColor, true)
            kit.text(c, "Penilaian Warung ($ratingCount)", 18f + scoreW + 22f, curY + 31f, 13.5f, kit.ink, true)
        } else {
            kit.text(c, "★", 18f, curY + 31f, 15f, kit.muted, true)
            kit.text(c, "Penilaian Warung · Belum ada ulasan", 36f, curY + 31f, 13f, kit.muted, false)
        }
        drawTrailingActionWithArrow(c, "Lihat Semua", 372f, curY + 31f, 12f, kit.blue)
        bounds.putVisible("openStore:$stallId", ratingRowRect, topLimit = bodyHitTop, bottomLimit = bodyBottom)
        curY += 54f
    }

    c.restoreToCount(bodySave)
    contentHeight = (curY - off) + footerH + 22f

    // 8. Top Overlay Controls (Floating translucent circles over hero -> solid bar on scroll)
    val cartCount = cart.values.sum()
    if (showSolidTopBar) {
        kit.rect(c, 0f, 0f, w, 60f, kit.bg)
        kit.line(c, 0f, 60f, w, 60f, kit.divider)
        drawIcon(c, "chevronLeft", 28f, 30f, 20f, kit.ink)
        bounds["headerBack"] = RectF(0f, 0f, 56f, 60f)
        kit.textEllipsized(c, product.name, 56f, 36f, 224f, 16f, kit.ink, true)

        val topFav = RectF(296f, 12f, 332f, 48f)
        drawIcon(c, if (isFav) "heartFill" else "heart", topFav.centerX(), topFav.centerY(), 17f, if (isFav) kit.danger else kit.ink)
        bounds["fav:${product.id}"] = topFav

        val topCart = RectF(338f, 12f, 374f, 48f)
        AppIcon.draw(c, "box", topCart.centerX(), topCart.centerY(), 18f, kit.ink)
        if (cartCount > 0) {
            val badgeTxt = if (cartCount > 99) "99+" else cartCount.toString()
            val bw = (kit.measure(badgeTxt, 8.5f, true) + 9f).coerceAtLeast(16f)
            val badge = RectF(topCart.right - bw + 2f, topCart.top, topCart.right + 2f, topCart.top + 15f)
            kit.rounded(c, badge, kit.danger, 7.5f)
            kit.centerText(c, badgeTxt, badge, 8.5f, Color.WHITE, true)
        }
        bounds["cartCheckout"] = topCart
    } else {
        val circleBg = Color.argb(148, 12, 18, 30)
        val backCircle = RectF(16f, 14f, 52f, 50f)
        kit.rounded(c, backCircle, circleBg, 18f)
        drawIcon(c, "chevronLeft", backCircle.centerX() - 1f, backCircle.centerY(), 19f, Color.WHITE)
        bounds["headerBack"] = RectF(8f, 6f, 60f, 58f)

        val favCircle = RectF(294f, 14f, 330f, 50f)
        kit.rounded(c, favCircle, circleBg, 18f)
        drawIcon(
            c,
            if (isFav) "heartFill" else "heart",
            favCircle.centerX(),
            favCircle.centerY(),
            16f,
            if (isFav) kit.danger else Color.WHITE,
        )
        bounds["fav:${product.id}"] = favCircle

        val cartCircle = RectF(338f, 14f, 374f, 50f)
        kit.rounded(c, cartCircle, circleBg, 18f)
        AppIcon.draw(c, "box", cartCircle.centerX(), cartCircle.centerY(), 17f, Color.WHITE)
        if (cartCount > 0) {
            val badgeTxt = if (cartCount > 99) "99+" else cartCount.toString()
            val bw = (kit.measure(badgeTxt, 8.5f, true) + 9f).coerceAtLeast(16f)
            val badge = RectF(cartCircle.right - bw + 3f, cartCircle.top - 3f, cartCircle.right + 3f, cartCircle.top + 12f)
            kit.rounded(c, badge, kit.danger, 7.5f)
            kit.centerText(c, badgeTxt, badge, 8.5f, Color.WHITE, true)
        }
        bounds["cartCheckout"] = cartCircle
    }

    // 9. Compact 3-Part Sticky Bottom Bar (Chat | Keranjang | Beli Sekarang Rp ...)
    val barTop = bodyBottom
    kit.rect(c, 0f, barTop, w, h, kit.card)
    kit.line(c, 0f, barTop, w, barTop, kit.divider)

    val chatRect = RectF(0f, barTop, 88f, h)
    press(c, "askSeller:${product.id}", chatRect, 0f)
    drawIcon(c, "chat", chatRect.centerX(), barTop + 23f, 17f, kit.blue)
    kit.centerText(c, "Chat", RectF(chatRect.left, barTop + 37f, chatRect.right, h - 4f), 10f, kit.ink, true)
    bounds["askSeller:${product.id}"] = chatRect

    kit.line(c, 88f, barTop + 12f, 88f, h - 12f, kit.divider)

    val cartBtnRect = RectF(88f, barTop, 194f, h)
    if (available) {
        press(c, "detailOpenSheet:cart", cartBtnRect, 0f)
        AppIcon.draw(c, "box", cartBtnRect.centerX(), barTop + 23f, 17f, kit.blue)
        kit.centerText(c, "Keranjang", RectF(cartBtnRect.left, barTop + 37f, cartBtnRect.right, h - 4f), 10f, kit.ink, true)
        bounds["detailOpenSheet:cart"] = cartBtnRect
    } else {
        AppIcon.draw(c, "box", cartBtnRect.centerX(), barTop + 23f, 17f, kit.muted)
        kit.centerText(c, "Keranjang", RectF(cartBtnRect.left, barTop + 37f, cartBtnRect.right, h - 4f), 10f, kit.muted, false)
    }

    val buyRect = RectF(194f, barTop, w, h)
    kit.rect(c, buyRect.left, buyRect.top, buyRect.right, buyRect.bottom, if (available) kit.blue else kit.muted)
    if (available) {
        press(c, "detailOpenSheet:buy", buyRect, 0f)
        kit.centerText(c, "Beli Sekarang", RectF(buyRect.left, barTop + 11f, buyRect.right, barTop + 33f), 13.5f, Color.WHITE, true)
        kit.centerText(c, formatRupiah(product.priceIdr), RectF(buyRect.left, barTop + 34f, buyRect.right, h - 7f), 11.5f, Color.argb(230, 255, 255, 255), true)
        bounds["detailOpenSheet:buy"] = buyRect
    } else {
        kit.centerText(c, "Stok Habis", buyRect, 14f, Color.WHITE, true)
    }

    // 10. Bottom Sheet Variant & Quantity Picker (Foto 3 — triggered by Keranjang / Beli Sekarang / Variasi)
    val sheetMode = detailSheetMode
    if (sheetMode != null && available) {
        drawProductVariantBottomSheet(c, w, h, product, room, sheetMode)
    } else {
        detailSheetMaxScroll = 0f
        detailSheetTopY = h
    }
}

private fun JajaninView.drawProductVariantBottomSheet(
    c: Canvas,
    w: Float,
    h: Float,
    product: com.jajanin.app.data.model.ProductRow,
    room: String,
    mode: String,
) {
    // 1. Dimmed Scrim (tap outside sheet closes it)
    c.drawColor(Color.argb(156, 0, 0, 0))
    bounds["detailSheetClose"] = RectF(0f, 0f, w, h)

    val sections = product.activeVariantSections
    val headerH = 114f
    val footerH = 74f

    // Pre-measure scrollable middle body height (pickup line + variant sections + Jumlah row)
    var middleHeight = 46f // pickup & session strip
    sections.forEach { sec ->
        middleHeight += 28f // section title
        var cx = 18f
        var rows = 1
        sec.options.forEach { opt ->
            val cw = (kit.measure(opt, 12f, true) + 26f).coerceIn(66f, 354f)
            if (cx > 18f && cx + cw > 372f) {
                cx = 18f
                rows++
            }
            cx += cw + 8f
        }
        middleHeight += rows * 34f + (rows - 1) * 8f + 18f
    }
    middleHeight += 64f // divider + Jumlah row + bottom breathing room

    val maxSheetH = h * 0.78f
    val desiredSheetH = headerH + middleHeight + footerH
    val sheetH = desiredSheetH.coerceAtMost(maxSheetH)
    val sheetTop = h - sheetH
    val middleTop = sheetTop + headerH
    val middleBottom = h - footerH
    val visibleMiddleH = (middleBottom - middleTop).coerceAtLeast(40f)
    detailSheetMaxScroll = (middleHeight - visibleMiddleH).coerceAtLeast(0f)
    detailSheetScroll = detailSheetScroll.coerceIn(0f, detailSheetMaxScroll)
    detailSheetTopY = sheetTop

    // 2. Sheet Surface (rounded top corners)
    val sheetPath = Path().apply {
        addRoundRect(
            RectF(0f, sheetTop, w, h + 24f),
            floatArrayOf(20f, 20f, 20f, 20f, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
    }
    val sheetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.card }
    c.drawPath(sheetPath, sheetFill)
    val sheetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = kit.border
    }
    c.drawPath(sheetPath, sheetStroke)
    // Swallow taps on sheet background so tapping empty sheet area doesn't dismiss it
    bounds["detailSheetNoop"] = RectF(0f, sheetTop, w, h)

    // 3. Sheet Header (Product thumbnail + Price + Schedule pill + Stock + Selected summary + Close X)
    val thumbRect = RectF(18f, sheetTop + 16f, 102f, sheetTop + 100f)
    val activeThumbUrl = product.allImageUrls.getOrNull(detailPhotoIndex.coerceAtLeast(0))
    drawDetailPhotoUrl(c, product, activeThumbUrl, thumbRect, 10f)
    kit.rounded(c, thumbRect, Color.TRANSPARENT, 10f, kit.border)

    kit.text(c, formatRupiah(product.priceIdr), 114f, sheetTop + 37f, 20f, kit.blue, true)
    val schedBadge = product.scheduleBadgeLabel
    val nextOnly = product.orderSchedule == "next_day"
    val schedW = (kit.measure(schedBadge, 9.5f, true) + 14f).coerceAtMost(130f)
    val schedPill = RectF(114f, sheetTop + 46f, 114f + schedW, sheetTop + 65f)
    kit.rounded(c, schedPill, if (nextOnly) kit.warnBox else kit.tint, 5f)
    kit.centerText(c, schedBadge, schedPill, 9.5f, if (nextOnly) Color.rgb(183, 116, 0) else kit.blue, true)

    val stockStr = product.stock?.let { "Stok: $it" } ?: "Stok tersedia"
    kit.text(c, stockStr, 114f, sheetTop + 82f, 12f, kit.muted)
    if (sections.isNotEmpty()) {
        val chosenSummary = sections.mapIndexed { idx, sec ->
            detailSelectedVariants[idx] ?: sec.options.firstOrNull().orEmpty()
        }.filter { it.isNotBlank() }.joinToString(" · ")
        if (chosenSummary.isNotBlank()) {
            kit.textEllipsized(c, "Dipilih: $chosenSummary", 114f, sheetTop + 98f, 224f, 11f, kit.ink, true)
        }
    }

    val closeRect = RectF(342f, sheetTop + 10f, 378f, sheetTop + 46f)
    drawIcon(c, "close", closeRect.centerX(), closeRect.centerY(), 16f, kit.muted)
    press(c, "detailSheetClose", closeRect, 18f)
    bounds["detailSheetClose"] = closeRect

    kit.line(c, 0f, middleTop, w, middleTop, kit.divider)

    // 4. Scrollable Middle Body (Pickup strip + Variant Sections + Jumlah)
    val midSave = c.save()
    c.clipRect(0f, middleTop, w, middleBottom)
    var sy = middleTop - detailSheetScroll

    // Pickup quick strip (like Shopee date line in Foto 3)
    AppIcon.draw(c, "location", 28f, sy + 22f, 15f, kit.blue)
    val pickupSummary = buildString {
        if (room.isNotBlank()) append("Ambil di $room") else append("Ambil di warung")
        append(" · ")
        append(product.parsedPickupSessions.joinToString(", "))
    }
    kit.textEllipsized(c, pickupSummary, 42f, sy + 26f, 330f, 11.5f, kit.ink, false)
    sy += 42f
    kit.line(c, 18f, sy, 372f, sy, kit.divider)
    sy += 4f

    // Variant Sections
    sections.forEachIndexed { secIdx, sec ->
        sy += 10f
        kit.text(c, sec.cleanName, 18f, sy + 13f, 12.5f, kit.ink, true)
        sy += 22f
        val chosen = detailSelectedVariants[secIdx] ?: sec.options.firstOrNull().orEmpty()
        var cx = 18f
        var cy = sy
        val chipH = 34f
        val chipGap = 8f
        sec.options.forEachIndexed { optIdx, opt ->
            val cw = (kit.measure(opt, 12f, true) + 26f).coerceIn(66f, 354f)
            if (cx > 18f && cx + cw > 372f) {
                cx = 18f
                cy += chipH + chipGap
            }
            val chipR = RectF(cx, cy, cx + cw, cy + chipH)
            val on = opt == chosen
            kit.rounded(c, chipR, if (on) kit.tint else kit.bg, 8f, if (on) kit.blue else kit.border)
            kit.centerText(c, opt, chipR, 12f, if (on) kit.blue else kit.ink, on)
            press(c, "detailVar:$secIdx:$optIdx", chipR, 8f)
            bounds.putVisible("detailVar:$secIdx:$optIdx", chipR, topLimit = middleTop, bottomLimit = middleBottom)
            cx += cw + chipGap
        }
        sy = cy + chipH + 14f
        kit.line(c, 18f, sy, 372f, sy, kit.divider)
    }

    // Jumlah Row (just like Foto 3!)
    val qtyRowTop = sy + 14f
    kit.text(c, "Jumlah", 18f, qtyRowTop + 23f, 13.5f, kit.ink, true)
    val stepperRect = RectF(252f, qtyRowTop, 372f, qtyRowTop + 36f)
    kit.rounded(c, stepperRect, kit.bg, 8f, kit.border)
    val minusBox = RectF(252f, qtyRowTop, 292f, qtyRowTop + 36f)
    val qtyBox = RectF(292f, qtyRowTop, 332f, qtyRowTop + 36f)
    val plusBox = RectF(332f, qtyRowTop, 372f, qtyRowTop + 36f)
    kit.line(c, 292f, qtyRowTop + 4f, 292f, qtyRowTop + 32f, kit.divider)
    kit.line(c, 332f, qtyRowTop + 4f, 332f, qtyRowTop + 32f, kit.divider)
    AppIcon.draw(c, "minus", minusBox.centerX(), minusBox.centerY(), 14f, if (quantity > 1) kit.ink else kit.muted)
    kit.centerText(c, quantity.toString(), qtyBox, 14f, kit.ink, true)
    AppIcon.draw(c, "plus", plusBox.centerX(), plusBox.centerY(), 14f, kit.blue)
    bounds.putVisible("minus", minusBox, topLimit = middleTop, bottomLimit = middleBottom)
    bounds.putVisible("plus", plusBox, topLimit = middleTop, bottomLimit = middleBottom)

    c.restoreToCount(midSave)

    // 5. Sticky Bottom Confirm CTA
    kit.rect(c, 0f, middleBottom, w, h, kit.card)
    kit.line(c, 0f, middleBottom, w, middleBottom, kit.divider)
    val ctaRect = RectF(18f, h - 60f, 372f, h - 14f)
    val totalStr = formatRupiah(product.priceIdr * quantity)
    val ctaLabel = if (mode == "buy") {
        "Beli Sekarang · $totalStr"
    } else {
        "Masukkan Keranjang · $totalStr"
    }
    kit.rounded(c, ctaRect, kit.blue, 12f)
    kit.centerText(c, ctaLabel, ctaRect, 14f, Color.WHITE, true)
    press(c, "detailSheetConfirm:$mode", ctaRect, 12f)
    bounds["detailSheetConfirm:$mode"] = ctaRect
}

private fun JajaninView.drawDetailPhotoUrl(
    c: Canvas,
    product: com.jajanin.app.data.model.ProductRow,
    url: String?,
    rect: RectF,
    radius: Float,
) {
    val cleanUrl = url?.takeIf { it.startsWith("https://") }
    val bmp = cleanUrl?.let { ProductImageCache.images[it] }
    if (bmp != null) {
        com.jajanin.app.core.ui.drawBitmapCenterCrop(c, bmp, rect, radius)
    } else {
        drawProductImage(c, product, rect, radius)
        if (cleanUrl != null) activity()?.loadProductImage(cleanUrl)
    }
}
