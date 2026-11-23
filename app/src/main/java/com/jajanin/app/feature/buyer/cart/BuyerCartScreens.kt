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
import com.jajanin.app.feature.buyer.home.normalizeCategory

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

    // 1. Jadwal Pengambilan (Hari Ini vs PO Besok)
    val canSameDay = lines.all { it.first.allowsSameDay }
    val canNextDay = lines.all { it.first.allowsNextDay }
    val activeMode = when {
        checkoutOrderMode == "pre_order" && canNextDay -> "pre_order"
        checkoutOrderMode == "same_day" && canSameDay -> "same_day"
        canSameDay -> "same_day"
        else -> "pre_order"
    }
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
        if (canSameDay) "Ambil hari ini" else "Khusus PO besok",
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
        if (poSelected) Color.rgb(183, 116, 0) else kit.border,
    )
    kit.text(c, "PO Besok", 214f, y + 59f, 13f, if (canNextDay) kit.ink else kit.muted, true)
    kit.text(
        c,
        if (canNextDay) "Pre-Order untuk besok" else "Khusus hari ini",
        214f,
        y + 79f,
        10f,
        kit.muted,
    )
    if (poSelected) kit.centerText(c, "●", RectF(338f, y + 52f, 362f, y + 76f), 13f, Color.rgb(183, 116, 0), true)
    bounds.putVisible("orderMode:pre_order", poBox, topLimit = bodyTop, bottomLimit = bodyBottom)

    y += 108f

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
    val cashBox = if (qrisOffered) RectF(18f, y + 34f, 190f, y + 92f) else RectF(18f, y + 34f, 372f, y + 92f)
    val cashOn = checkoutPayment == "cash" || !qrisOffered
    kit.rounded(c, cashBox, kit.card, 13f, if (cashOn) kit.blue else kit.border)
    kit.text(c, "Tunai", 34f, y + 59f, 13f, kit.ink, true)
    val cashSub = when {
        stallQris == null -> "Bayar saat ambil · memeriksa QRIS warung..."
        qrisOffered -> "Bayar saat ambil"
        else -> "Bayar saat ambil · toko belum pasang QRIS"
    }
    kit.text(c, cashSub, 34f, y + 79f, 10f, kit.muted)
    if (cashOn) kit.centerText(c, "●", RectF(cashBox.right - 32f, y + 52f, cashBox.right - 8f, y + 76f), 13f, kit.blue, true)
    bounds.putVisible("payMethod:cash", cashBox, topLimit = bodyTop, bottomLimit = bodyBottom)
    if (qrisOffered) {
        val qrisBox = RectF(198f, y + 34f, 372f, y + 92f)
        kit.rounded(c, qrisBox, kit.card, 13f, if (!cashOn) kit.blue else kit.border)
        kit.text(c, "QRIS", 214f, y + 59f, 13f, kit.ink, true)
        kit.text(c, "Scan lalu konfirmasi", 214f, y + 79f, 10f, kit.muted)
        if (!cashOn) kit.centerText(c, "●", RectF(338f, y + 52f, 362f, y + 76f), 13f, kit.blue, true)
        bounds.putVisible("payMethod:qris", qrisBox, topLimit = bodyTop, bottomLimit = bodyBottom)
    }

    val subtotal = lines.sumOf { (p, qty) -> p.priceIdr * qty }
    val totalY = y + 128f
    kit.text(c, "Ringkasan", 22f, totalY, 15f, kit.ink, true)
    val scheduleText = if (activeMode == "pre_order") "PO Besok · $activeSession" else "Hari Ini · $activeSession"
    summaryRow(c, "Jadwal ambil", scheduleText, totalY + 28f)
    summaryRow(c, "Subtotal", formatRupiah(subtotal), totalY + 54f)
    summaryRow(c, "Biaya layanan", formatRupiah(SERVICE_FEE), totalY + 80f)
    kit.line(c, 22f, totalY + 98f, 368f, totalY + 98f)
    summaryRow(c, "Total", formatRupiah(subtotal + SERVICE_FEE), totalY + 126f, true)
    kit.text(c, "Harga final dihitung ulang server dari database.", 22f, totalY + 148f, 10f, kit.muted)
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
        val activeMethod = if (qrisOffered && checkoutPayment == "qris") "qris" else "cash"
        val prefix = if (activeMode == "pre_order") "Buat Pre-Order" else "Buat pesanan"
        kit.button(c, if (activeMethod == "cash") "$prefix · bayar tunai" else "$prefix · bayar QRIS", cta)
        bounds["pay"] = RectF(cta)
    }
}

internal fun JajaninView.drawPayment(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Pembayaran QRIS")
    kit.text(c, "Scan kode milik ${cartStallName.take(26)}", 24f, 120f, 19f, kit.ink, true)
    kit.text(c, "Bayar nominal pas, lalu ketuk tombol di bawah.", 24f, 145f, 12f, kit.muted)
    kit.rounded(c, RectF(38f, 178f, 352f, 492f), kit.card, 20f, kit.border)
    kit.text(c, "QRIS", 157f, 214f, 18f, kit.blue, true)
    val qr = RectF(85f, 242f, 305f, 462f)
    val qrUrl = qrisImageUrl
    when {
        qrisLoading -> kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
        qrUrl.isNullOrBlank() -> {
            kit.centerText(c, "Penjual belum memasang", RectF(85f, 320f, 305f, 350f), 13f, kit.danger, true)
            kit.centerText(c, "kode QRIS.", RectF(85f, 342f, 305f, 372f), 13f, kit.danger, true)
            drawQr(c, qr)
        }
        else -> {
            val bmp = ProductImageCache.images[qrUrl]
            if (bmp == null) {
                kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
                activity()?.loadProductImage(qrUrl)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val path = Path().apply { addRoundRect(qr, 12f, 12f, Path.Direction.CW) }
                val save = c.save()
                c.clipPath(path)
                c.drawBitmap(bmp, null, qr, paint)
                c.restoreToCount(save)
            }
        }
    }
    kit.text(c, "Total pembayaran", 24f, 539f, 12f, kit.muted)
    kit.text(c, formatRupiah(qrisAmount), 24f, 570f, 25f, kit.ink, true)
    if (lastOrderPickup != null) {
        kit.text(c, "Kode ambil: $lastOrderPickup", 24f, 596f, 12f, kit.blue, true)
    }
    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    kit.button(c, "Saya sudah membayar", cta)
    bounds["paid"] = RectF(cta)
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
    kit.text(c, "Deskripsi", 22f, imgBottom + 136f, 14f, kit.ink, true)
    val lines = product.description.ifBlank { "Belum ada deskripsi produk." }.chunked(43).take(3)
    var nextY = imgBottom + 158f
    lines.forEach { line ->
        kit.text(c, line, 22f, nextY, 12f, kit.muted)
        nextY += 19f
    }
    val cleanIng = product.cleanIngredients
    if (cleanIng.isNotBlank()) {
        kit.textEllipsized(c, "Bahan: $cleanIng", 22f, nextY + 2f, 346f, 12f, kit.muted)
        nextY += 24f
    }
    val room = product.pickupRoom
    if (room.isNotBlank() && nextY + 40f < h - 166f) {
        val poiTop = nextY + 4f
        val poiRect = RectF(20f, poiTop, 370f, poiTop + 36f)
        kit.rounded(c, poiRect, kit.tint, 8f, kit.border)
        kit.rect(c, 20f, poiTop + 6f, 23.5f, poiTop + 30f, kit.blue)
        kit.text(c, "AMBIL DI RUANGAN", 34f, poiTop + 22f, 10f, kit.muted, true)
        kit.textRight(c, room, 356f, poiTop + 22.5f, 190f, 12.5f, kit.blue, true)
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
