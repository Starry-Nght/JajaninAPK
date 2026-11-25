package com.jajanin.app.feature.buyer.cart

import com.jajanin.app.feature.seller.*

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.SERVICE_FEE
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.ProductImageCache
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
    kit.centerText(c, "‹", back, 27f, kit.ink, true)
    bounds["back"] = RectF(back)

    kit.rounded(c, RectF(0f, y - 18f, w, h + 20f), kit.card, 22f)
    kit.text(c, selected.name, 24f, y + 25f, 23f, kit.ink, true)
    kit.text(c, selected.stall, 24f, y + 49f, 13f, kit.muted)
    kit.text(c, "★ ${selected.rating}   ·   120+ terjual", 24f, y + 77f, 13f, kit.star, true)
    kit.line(c, 24f, y + 97f, w - 24f, y + 97f)
    kit.text(c, "Tentang menu", 24f, y + 127f, 16f, kit.ink, true)
    kit.text(c, "Disiapkan segar oleh penjual kantin pilihan.", 24f, y + 153f, 13f, kit.muted)
    kit.text(c, "Pedas, gurih, dan pas buat nemenin", 24f, y + 175f, 13f, kit.muted)
    kit.text(c, "waktu istirahatmu.", 24f, y + 196f, 13f, kit.muted)

    kit.text(c, "Jumlah", 24f, h - 104f, 14f, kit.ink, true)
    kit.rounded(c, RectF(234f, h - 126f, 366f, h - 76f), kit.bg, 14f, kit.border)
    kit.text(c, "−", 249f, h - 94f, 23f, kit.blue, true)
    kit.text(c, "$quantity", 296f, h - 94f, 15f, kit.ink, true)
    kit.text(c, "+", 337f, h - 94f, 23f, kit.blue, true)
    bounds["minus"] = RectF(234f, h - 126f, 279f, h - 76f)
    bounds["plus"] = RectF(321f, h - 126f, 366f, h - 76f)

    val cta = RectF(20f, h - 65f, w - 20f, h - 12f)
    kit.button(c, "Tambah ke keranjang · ${selected.price}", cta)
    bounds["checkout"] = RectF(cta)
}

internal fun JajaninView.drawCheckout(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bodyTop = 74f
    val bodyBottom = h - 84f
    val lines = cart.entries.mapNotNull { (id, qty) ->
        val p = products.firstOrNull { it.id == id } ?: sellerProducts.firstOrNull { it.id == id }
        if (p == null || qty <= 0) null else p to qty
    }
    if (lines.isEmpty()) {
        header(c, "Checkout")
        kit.centerText(c, "Keranjang kosong.", RectF(24f, 280f, 366f, 340f), 14f, kit.muted)
        kit.centerText(c, "Kembali dan pilih menu dulu.", RectF(24f, 310f, 366f, 360f), 12f, kit.muted)
        contentHeight = 0f
        return
    }

    val bodySave = c.save()
    c.clipRect(0f, bodyTop, w, bodyBottom)

    kit.text(c, cartStallName.take(30), 22f, 108f + off, 18f, kit.ink, true)
    kit.text(c, "${lines.sumOf { it.second }} item (${lines.size} menu) di keranjang", 22f, 130f + off, 12f, kit.muted)
    var y = 146f + off
    lines.forEach { (p, qty) ->
        if (y + 76f >= bodyTop && y <= bodyBottom) {
            kit.card(c, RectF(18f, y, 372f, y + 76f), 14f)
            kit.textEllipsized(c, p.name, 32f, y + 27f, 210f, 13f, kit.ink, true)
            kit.text(c, "${formatRupiah(p.priceIdr)}  ×  $qty", 32f, y + 51f, 11f, kit.muted)
            val sub = formatRupiah(p.priceIdr * qty)
            kit.text(c, sub, 358f - kit.measure(sub, 12f, true), y + 30f, 12f, kit.ink, true)
            val minus = RectF(216f, y + 44f, 244f, y + 68f)
            val plus = RectF(274f, y + 44f, 302f, y + 68f)
            val rm = RectF(308f, y + 44f, 358f, y + 68f)
            kit.rounded(c, minus, kit.bg, 8f, kit.border)
            kit.centerText(c, "−", minus, 15f, kit.blue, true)
            kit.centerText(c, "$qty", RectF(244f, y + 44f, 274f, y + 68f), 12f, kit.ink, true)
            kit.rounded(c, plus, kit.bg, 8f, kit.border)
            kit.centerText(c, "+", plus, 15f, kit.blue, true)
            kit.centerText(c, "Hapus", rm, 10f, kit.danger, true)
            bounds.putVisible("cartMinus:${p.id}", minus, topLimit = bodyTop, bottomLimit = bodyBottom)
            bounds.putVisible("cartPlus:${p.id}", plus, topLimit = bodyTop, bottomLimit = bodyBottom)
            bounds.putVisible("cartRemove:${p.id}", rm, topLimit = bodyTop, bottomLimit = bodyBottom)
        }
        y += 86f
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
    if (sameSelected) kit.centerText(c, "●", RectF(158f, y + 52f, 182f, y + 76f), 13f, kit.blue, true)
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
    if (poSelected) kit.centerText(c, "●", RectF(338f, y + 52f, 362f, y + 76f), 13f, poAccent, true)
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
    val stallQris = cartStallQris
    val qrisOffered = !stallQris.isNullOrBlank()
    val cashLockedByPo = poRequiresQris
    val missingQrisForStrictPo = poRequiresQris && stallQris != null && !qrisOffered
    val cashBox = if (qrisOffered) RectF(18f, y + 34f, 190f, y + 92f) else RectF(18f, y + 34f, 372f, y + 92f)
    val cashOn = !cashLockedByPo && (checkoutPayment == "cash" || !qrisOffered)
    kit.rounded(
        c,
        cashBox,
        if (cashLockedByPo) kit.bg else kit.card,
        13f,
        if (cashOn) kit.blue else kit.border,
    )
    kit.text(c, "Tunai", 34f, y + 59f, 13f, if (cashLockedByPo) kit.muted else kit.ink, true)
    val cashSub = when {
        cashLockedByPo -> poQrisReason
        stallQris == null -> "Bayar saat ambil · memeriksa QRIS warung..."
        qrisOffered -> "Bayar saat ambil"
        else -> "Bayar saat ambil · toko belum pasang QRIS"
    }
    kit.textEllipsized(c, cashSub, 34f, y + 79f, (cashBox.width() - 28f).coerceAtLeast(100f), 9.5f, if (cashLockedByPo) poAccent else kit.muted)
    if (cashOn) kit.centerText(c, "●", RectF(cashBox.right - 32f, y + 52f, cashBox.right - 8f, y + 76f), 13f, kit.blue, true)
    bounds.putVisible("payMethod:cash", cashBox, topLimit = bodyTop, bottomLimit = bodyBottom)
    if (qrisOffered) {
        val qrisBox = RectF(198f, y + 34f, 372f, y + 92f)
        val qrisOn = cashLockedByPo || !cashOn
        kit.rounded(c, qrisBox, if (cashLockedByPo) kit.warnBox else kit.card, 13f, if (qrisOn) (if (cashLockedByPo) poAccent else kit.blue) else kit.border)
        kit.text(c, if (cashLockedByPo) "QRIS (Wajib)" else "QRIS", 214f, y + 59f, 13f, kit.ink, true)
        kit.text(c, if (cashLockedByPo) "Bayar di muka" else "Scan lalu konfirmasi", 214f, y + 79f, 10f, if (cashLockedByPo) poAccent else kit.muted)
        if (qrisOn) kit.centerText(c, "●", RectF(338f, y + 52f, 362f, y + 76f), 13f, if (cashLockedByPo) poAccent else kit.blue, true)
        bounds.putVisible("payMethod:qris", qrisBox, topLimit = bodyTop, bottomLimit = bodyBottom)
    }

    var summaryOffset = y + 128f
    if (missingQrisForStrictPo) {
        val warnBox = RectF(18f, y + 100f, 372f, y + 140f)
        kit.rounded(c, warnBox, kit.dangerBorder, 10f, kit.danger)
        kit.textEllipsized(c, "Warung ini belum memasang QRIS untuk $poQrisReason.", 30f, y + 117f, 330f, 10f, kit.danger, true)
        kit.textEllipsized(c, "Pilih tanggal PO 1–3 hari ke depan atau hubungi penjual.", 30f, y + 132f, 330f, 9.5f, kit.ink)
        summaryOffset = y + 162f
    }

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
    } else if (missingQrisForStrictPo) {
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "QRIS Warung Belum Tersedia (Pilih 1–3 Hari)", cta, 12f, Color.WHITE, true)
    } else {
        val activeMethod = if (qrisOffered && (poRequiresQris || checkoutPayment == "qris")) "qris" else "cash"
        val prefix = if (activeMode == "pre_order") "Buat Pre-Order" else "Buat pesanan"
        kit.button(c, if (activeMethod == "cash") "$prefix · bayar tunai" else "$prefix · bayar QRIS", cta)
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
    val stallLabel = cartStallName.takeIf { it.isNotBlank() }?.take(24) ?: "Warung"
    kit.textEllipsized(c, "Scan QRIS $stallLabel", 22f, y + 18f, 346f, 18f, kit.ink, true)
    y += 28f

    // 1. Live 10-minute payment deadline & stock protection banner
    val remSec = com.jajanin.app.data.repository.remainingPaymentSeconds(qrisExpiresAt)
    val isExpired = remSec != null && remSec <= 0L
    val isUrgent = remSec != null && remSec in 1L..120L
    if (isExpired) {
        qrisOrderId?.let { oid -> activity()?.onQrisTimerExpired(oid) }
    } else if (remSec != null && currentScreen == Screen.QRIS) {
        postInvalidateDelayed(1000)
    }
    val bannerRect = RectF(20f, y, 370f, y + 48f)
    val bannerBg = when {
        isExpired || isUrgent -> kit.dangerBorder
        else -> kit.warnBox
    }
    val bannerAccent = when {
        isExpired || isUrgent -> kit.danger
        else -> Color.rgb(183, 116, 0)
    }
    kit.rounded(c, bannerRect, bannerBg, 10f, bannerAccent)
    val timerHeadline = when {
        isExpired -> "Batas waktu bayar QRIS habis · Pesanan dibatalkan"
        remSec != null -> "Batas waktu bayar: ${com.jajanin.app.data.repository.formatCountdownMmSs(remSec)}"
        else -> "Selesaikan pembayaran & unggah screenshot bukti"
    }
    kit.textEllipsized(c, timerHeadline, 32f, y + 20f, 326f, 12f, bannerAccent, true)
    kit.textEllipsized(
        c,
        "Stok diamankan setelah penjual memverifikasi bukti transfer QRIS.",
        32f,
        y + 38f,
        326f,
        10f,
        kit.ink,
    )
    y += 58f

    // 2. QRIS Card
    val qrCard = RectF(28f, y, 362f, y + 266f)
    kit.rounded(c, qrCard, kit.card, 18f, kit.border)
    kit.centerText(c, "QRIS", RectF(28f, y + 10f, 362f, y + 34f), 15f, kit.blue, true)
    val qr = RectF(90f, y + 38f, 300f, y + 248f)
    val qrUrl = qrisImageUrl
    when {
        qrisLoading -> kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
        qrUrl.isNullOrBlank() -> {
            kit.centerText(c, "Penjual belum memasang", RectF(90f, y + 115f, 300f, y + 142f), 13f, kit.danger, true)
            kit.centerText(c, "kode QRIS.", RectF(90f, y + 137f, 300f, y + 164f), 13f, kit.danger, true)
            drawQr(c, qr)
        }
        else -> {
            val bmp = ProductImageCache.images[qrUrl]
            if (bmp == null) {
                kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
                activity()?.loadProductImage(qrUrl)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val path = Path().apply { addRoundRect(qr, 10f, 10f, Path.Direction.CW) }
                val save = c.save()
                c.clipPath(path)
                c.drawBitmap(bmp, null, qr, paint)
                c.restoreToCount(save)
            }
        }
    }
    y += 278f

    // 3. Total & Pickup Code Card
    val amtCard = RectF(20f, y, 370f, y + 66f)
    kit.rounded(c, amtCard, kit.card, 12f, kit.border)
    kit.text(c, "Total bayar pas", 34f, y + 22f, 11f, kit.muted)
    kit.text(c, formatRupiah(qrisAmount), 34f, y + 50f, 22f, kit.ink, true)
    if (!lastOrderPickup.isNullOrBlank()) {
        val codePill = RectF(268f, y + 19f, 356f, y + 47f)
        kit.rounded(c, codePill, kit.tint, 8f)
        kit.centerText(c, "#$lastOrderPickup", codePill, 13f, kit.blue, true)
    }
    y += 78f

    // 4. Mandatory Payment Proof Upload Card
    val proofUrl = qrisProofUrl?.takeIf { it.isNotBlank() }
    val proofCardH = if (proofUrl != null) 120f else 104f
    val proofCard = RectF(20f, y, 370f, y + proofCardH)
    kit.rounded(
        c,
        proofCard,
        kit.card,
        12f,
        if (proofUrl != null) kit.success else kit.blue,
    )
    kit.text(c, "BUKTI TRANSFER QRIS (WAJIB)", 34f, y + 22f, 10f, kit.muted, true)
    when {
        qrisProofUploading -> {
            kit.text(c, "Mengunggah screenshot bukti transfer...", 34f, y + 52f, 13f, kit.blue, true)
            kit.text(c, "Mohon tunggu sebentar.", 34f, y + 74f, 11f, kit.muted)
        }
        proofUrl != null -> {
            val statusPill = RectF(256f, y + 10f, 356f, y + 28f)
            kit.rounded(c, statusPill, kit.successSoft, 6f)
            kit.centerText(c, "Terunggah", statusPill, 9.5f, kit.success, true)

            val thumb = RectF(34f, y + 36f, 98f, y + 104f)
            kit.rounded(c, thumb, kit.bg, 8f, kit.border)
            val bmp = ProductImageCache.images[proofUrl]
            if (bmp != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val path = Path().apply { addRoundRect(thumb, 8f, 8f, Path.Direction.CW) }
                val save = c.save()
                c.clipPath(path)
                c.drawBitmap(bmp, null, thumb, paint)
                c.restoreToCount(save)
            } else {
                kit.centerText(c, "Foto", thumb, 10.5f, kit.muted)
                activity()?.loadProductImage(proofUrl)
            }
            bounds.putVisible("qrisViewProofThumb", thumb, topLimit = bodyTop, bottomLimit = bodyBottom)

            kit.text(c, "Screenshot bukti siap dikirim", 110f, y + 54f, 12.5f, kit.ink, true)
            val viewBtn = RectF(110f, y + 68f, 224f, y + 102f)
            kit.rounded(c, viewBtn, kit.tint, 8f, kit.blue)
            kit.centerText(c, "Lihat Foto", viewBtn, 11.5f, kit.blue, true)
            bounds.putVisible("qrisViewProofBtn", viewBtn, topLimit = bodyTop, bottomLimit = bodyBottom)

            val changeBtn = RectF(232f, y + 68f, 356f, y + 102f)
            kit.rounded(c, changeBtn, kit.bg, 8f, kit.border)
            kit.centerText(c, "Ganti Foto", changeBtn, 11.5f, kit.ink, true)
            bounds.putVisible("qrisPickProofCard", changeBtn, topLimit = bodyTop, bottomLimit = bodyBottom)
        }
        else -> {
            kit.textEllipsized(
                c,
                "Unggah screenshot bukti berhasil bayar dari e-wallet / m-banking.",
                34f,
                y + 44f,
                322f,
                11.5f,
                kit.muted,
            )
            val pickBtn = RectF(34f, y + 58f, 356f, y + 92f)
            kit.rounded(c, pickBtn, kit.tint, 8f, kit.blue)
            kit.centerText(c, "Unggah Screenshot Bukti Bayar", pickBtn, 12.5f, kit.blue, true)
            bounds.putVisible("qrisPickProofCard", pickBtn, topLimit = bodyTop, bottomLimit = bodyBottom)
        }
    }

    c.restoreToCount(bodySave)
    contentHeight = (y - off) + proofCardH + 28f

    header(c, "Pembayaran QRIS")

    // Sticky Bottom CTA
    kit.rect(c, 0f, bodyBottom, w, h, kit.bg)
    kit.line(c, 20f, bodyBottom, 370f, bodyBottom, kit.divider)
    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    when {
        isExpired -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Waktu Bayar Habis", cta, 14f, Color.WHITE, true)
        }
        qrisProofSubmitting -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Mengirim bukti bayar...", cta, 14f, Color.WHITE, true)
        }
        qrisProofUploading -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Mengunggah foto bukti...", cta, 14f, Color.WHITE, true)
        }
        proofUrl == null -> {
            kit.rounded(c, cta, kit.blue, 14f)
            kit.centerText(c, "Unggah Bukti Bayar Dulu", cta, 14f, Color.WHITE, true)
            bounds["qrisPickProof"] = RectF(cta)
        }
        else -> {
            kit.rounded(c, cta, kit.success, 14f)
            kit.centerText(c, "Kirim Bukti & Konfirmasi Bayar", cta, 14f, Color.WHITE, true)
            bounds["paid"] = RectF(cta)
        }
    }
}

internal fun JajaninView.drawProductDetail(c: Canvas, w: Float, h: Float) {
    val product = selectedProduct ?: run { goBack(); return }
    c.drawColor(kit.bg)
    kit.rect(c, 0f, 0f, w, 70f, kit.card)
    drawIcon(c, "chevronLeft", 27f, 35f, 24f, kit.blue)
    kit.text(c, "Detail Produk", 60f, 45f, 19f, kit.ink, true)
    bounds["headerBack"] = RectF(0f, 0f, 54f, 70f)
    val imgBottom = minOf(h * .38f, 295f)
    drawProductImage(c, product, RectF(20f, 86f, 370f, imgBottom), 18f)
    kit.text(c, normalizeCategory(product.category), 22f, imgBottom + 28f, 12f, kit.blue, true)
    val schedBadge = product.scheduleBadgeLabel
    val schedW = (kit.measure(schedBadge, 10f, true) + 16f).coerceAtMost(170f)
    val schedPill = RectF(370f - schedW, imgBottom + 13f, 370f, imgBottom + 33f)
    val nextOnly = product.orderSchedule == "next_day"
    kit.rounded(c, schedPill, if (nextOnly) kit.warnBox else kit.tint, 6f)
    kit.centerText(c, schedBadge, schedPill, 10f, if (nextOnly) Color.rgb(183, 116, 0) else kit.blue, true)

    kit.text(c, product.name.take(31), 22f, imgBottom + 58f, 22f, kit.ink, true)
    kit.text(c, formatRupiah(product.priceIdr), 22f, imgBottom + 86f, 19f, kit.ink, true)
    kit.textEllipsized(
        c,
        "Sesi ambil: ${product.parsedPickupSessions.joinToString(" · ")}",
        22f,
        imgBottom + 108f,
        346f,
        11.5f,
        kit.blue,
        true,
    )
    val poSummary = product.poDatesDisplaySummary
    val poRuleLine = if (product.allowsNextDay) {
        buildList {
            if (poSummary.isNotBlank()) add("PO: $poSummary")
            add("Tutup H-1 ${product.poCutoffHour}:00")
            product.poDailyQuota?.let { add("Kuota $it/hari") }
            if (product.poRequireQris) add("Wajib QRIS")
        }.joinToString(" · ")
    } else {
        ""
    }
    var detailY = imgBottom + 132f
    if (poRuleLine.isNotBlank()) {
        kit.textEllipsized(
            c,
            poRuleLine,
            22f,
            imgBottom + 126f,
            346f,
            11f,
            Color.rgb(183, 116, 0),
            true,
        )
        detailY = imgBottom + 148f
    }
    kit.text(c, "Deskripsi", 22f, detailY, 14f, kit.ink, true)
    val lines = product.description.ifBlank { "Belum ada deskripsi produk." }.chunked(43).take(3)
    var nextY = detailY + 22f
    lines.forEach { line ->
        kit.text(c, line, 22f, nextY, 12f, kit.muted)
        nextY += 19f
    }
    val cleanIng = product.cleanIngredients
    if (cleanIng.isNotBlank()) {
        kit.textEllipsized(c, "Bahan: $cleanIng", 22f, nextY + 2f, 346f, 12f, kit.muted)
        nextY += 24f
    }
    val available = product.available && (product.stock == null || product.stock > 0)
    kit.text(c, if (available) "Tersedia${product.stock?.let { " · Stok $it" }.orEmpty()}" else "Out of Stock", 22f, h - 158f, 13f, if (available) kit.success else kit.danger, true)
    kit.text(c, "Jumlah", 22f, h - 119f, 14f, kit.ink, true)
    kit.rounded(c, RectF(220f, h - 145f, 368f, h - 95f), kit.card, 14f, kit.border)
    kit.centerText(c, "−", RectF(220f, h - 145f, 269f, h - 95f), 22f, kit.blue, true)
    kit.centerText(c, quantity.toString(), RectF(269f, h - 145f, 319f, h - 95f), 15f, kit.ink, true)
    kit.centerText(c, "+", RectF(319f, h - 145f, 368f, h - 95f), 22f, kit.blue, true)
    bounds["minus"] = RectF(220f, h - 145f, 269f, h - 95f)
    bounds["plus"] = RectF(319f, h - 145f, 368f, h - 95f)
    val askBtn = RectF(20f, h - 74f, 148f, h - 18f)
    kit.rounded(c, askBtn, kit.card, 14f, kit.blue)
    drawIcon(c, "chat", 39f, askBtn.centerY(), 14f, kit.blue)
    kit.text(c, "Tanya Penjual", 53f, askBtn.centerY() + 4.5f, 12f, kit.blue, true)
    bounds["askSeller:${product.id}"] = askBtn
    press(c, "askSeller:${product.id}", askBtn, 14f)

    val action = RectF(158f, h - 74f, 370f, h - 18f)
    kit.button(c, if (available) "Tambah ke keranjang" else "Stok habis", action, if (available) kit.blue else kit.muted)
    if (available) bounds["detailAdd"] = action
}
