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
import com.jajanin.app.core.ui.blackoutReopenLabel
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.drawQr
import com.jajanin.app.core.ui.summaryRow
import com.jajanin.app.core.util.formatRupiah
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
    header(c, "Checkout")
    val off = -scroll
    val lines = cart.entries.mapNotNull { (id, qty) ->
        val p = products.firstOrNull { it.id == id } ?: sellerProducts.firstOrNull { it.id == id }
        if (p == null || qty <= 0) null else p to qty
    }
    if (lines.isEmpty()) {
        kit.centerText(c, "Keranjang kosong.", RectF(24f, 280f, 366f, 340f), 14f, kit.muted)
        kit.centerText(c, "Kembali dan pilih menu dulu.", RectF(24f, 310f, 366f, 360f), 12f, kit.muted)
        contentHeight = 0f
        return
    }
    kit.text(c, cartStallName.take(30), 22f, 112f + off, 18f, kit.ink, true)
    kit.text(c, "${lines.sumOf { it.second }} item di keranjang", 22f, 134f + off, 12f, kit.muted)
    var y = 150f + off
    lines.forEach { (p, qty) ->
        if (y + 76f < 78f || y > h - 78f) {
            // Lewati gambar di luar layar, tapi hitung tingginya.
        } else {
            kit.card(c, RectF(18f, y, 372f, y + 76f), 14f)
            kit.text(c, p.name.take(22), 32f, y + 27f, 13f, kit.ink, true)
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
            bounds["cartMinus:${p.id}"] = RectF(minus)
            bounds["cartPlus:${p.id}"] = RectF(plus)
            bounds["cartRemove:${p.id}"] = RectF(rm)
        }
        y += 86f
    }

    kit.text(c, "Metode pembayaran", 22f, y + 26f, 15f, kit.ink, true)
    // QRIS hanya ditawarkan bila warung sudah benar-benar memasang gambar QRIS.
    val stallQris = cartStallQris
    val qrisOffered = !stallQris.isNullOrBlank()
    val cashBox = if (qrisOffered) RectF(18f, y + 38f, 190f, y + 96f) else RectF(18f, y + 38f, 372f, y + 96f)
    val cashOn = checkoutPayment == "cash" || !qrisOffered
    kit.rounded(c, cashBox, kit.card, 13f, if (cashOn) kit.blue else kit.border)
    kit.text(c, "Tunai", 34f, y + 63f, 13f, kit.ink, true)
    val cashSub = when {
        stallQris == null -> "Bayar saat ambil · memeriksa QRIS warung..."
        qrisOffered -> "Bayar saat ambil"
        else -> "Bayar saat ambil · toko belum pasang QRIS"
    }
    kit.text(c, cashSub, 34f, y + 83f, 10f, kit.muted)
    if (cashOn) kit.centerText(c, "●", RectF(cashBox.right - 32f, y + 56f, cashBox.right - 8f, y + 80f), 13f, kit.blue, true)
    bounds["payMethod:cash"] = RectF(cashBox)
    if (qrisOffered) {
        val qrisBox = RectF(198f, y + 38f, 372f, y + 96f)
        kit.rounded(c, qrisBox, kit.card, 13f, if (!cashOn) kit.blue else kit.border)
        kit.text(c, "QRIS", 214f, y + 63f, 13f, kit.ink, true)
        kit.text(c, "Scan lalu konfirmasi", 214f, y + 83f, 10f, kit.muted)
        if (!cashOn) kit.centerText(c, "●", RectF(338f, y + 56f, 362f, y + 80f), 13f, kit.blue, true)
        bounds["payMethod:qris"] = RectF(qrisBox)
    }

    val subtotal = lines.sumOf { (p, qty) -> p.priceIdr * qty }
    val totalY = y + 134f
    kit.text(c, "Ringkasan", 22f, totalY, 15f, kit.ink, true)
    summaryRow(c, "Subtotal", formatRupiah(subtotal), totalY + 28f)
    summaryRow(c, "Biaya layanan", formatRupiah(SERVICE_FEE), totalY + 56f)
    kit.line(c, 22f, totalY + 76f, 368f, totalY + 76f)
    summaryRow(c, "Total", formatRupiah(subtotal + SERVICE_FEE), totalY + 105f, true)
    kit.text(c, "Harga final dihitung ulang server dari database.", 22f, totalY + 127f, 10f, kit.muted)
    val counterText = if (orderingStatusLoading) "Memuat jatah pesanan..." else "Pesanan hari ini: $ordersToday/2"
    kit.text(c, counterText, 22f, totalY + 149f, 11f, kit.blue, true)
    // Jam server live (WIB): ticking tiap detik dari offset terakhir, bukan jam perangkat.
    kit.text(c, "Jam server: ${serverClockLabel()} · live", 22f, totalY + 171f, 11f, kit.muted)
    // Antrean aktif warung (maksimal 10); backend tetap sumber kebenaran.
    val queueText = if (stallLoading) "Memuat antrean warung..." else if (stallActiveOrders < 0) "" else "Antrean warung: $stallActiveOrders/10"
    if (queueText.isNotBlank()) kit.text(c, queueText, 22f, totalY + 193f, 11f, if (stallFull) kit.danger else kit.muted, stallFull)
    contentHeight = totalY + 206f
    if (currentScreen == Screen.CHECKOUT) postInvalidateDelayed(1000)

    val cta = RectF(20f, h - 72f, 370f, h - 18f)
    if (orderingBlocked) {
        // Blackout WIB (status dari server): tombol checkout nonaktif + alasan jelas.
        val blockedLines = ArrayList((orderingBlockMessage ?: "Pesanan sementara ditutup.").chunked(38))
        blackoutReopenLabel()?.let { blockedLines.add(it) }
        val boxTop = cta.top - 20f - blockedLines.size * 20f
        kit.rounded(c, RectF(20f, boxTop, 370f, cta.top - 8f), kit.warnBox, 12f, kit.border)
        blockedLines.forEachIndexed { index, line -> kit.text(c, line, 32f, boxTop + 25f + index * 20f, 11f, kit.ink) }
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Pemesanan ditutup", cta, 14f, Color.WHITE, true)
        // Tanpa bounds["pay"]: tombol sengaja tidak bisa diketuk.
    } else if (stallFull) {
        // Kapasitas warung penuh (status dari server): checkout nonaktif.
        val fullMsg = "Warung sedang penuh. Maksimal 10 pesanan aktif. Silakan coba lagi beberapa saat."
        val fullLines = fullMsg.chunked(38)
        val fullTop = cta.top - 20f - fullLines.size * 20f
        kit.rounded(c, RectF(20f, fullTop, 370f, cta.top - 8f), kit.warnBox, 12f, kit.border)
        fullLines.forEachIndexed { index, line -> kit.text(c, line, 32f, fullTop + 25f + index * 20f, 11f, kit.ink) }
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Warung penuh", cta, 14f, Color.WHITE, true)
        // Tanpa bounds["pay"]: tombol sengaja tidak bisa diketuk.
    } else if (!orderingStatusLoading && ordersToday >= 2) {
        // Jatah harian habis (2/2): kunci tombol, server juga menolak.
        kit.text(c, "Jatah pesananmu hari ini habis (2/2). Balik lagi besok.", 22f, cta.top - 20f, 11f, kit.danger, true)
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Jatah habis", cta, 14f, Color.WHITE, true)
        // Tanpa bounds["pay"]: tombol sengaja tidak bisa diketuk.
    } else if (placingOrder) {
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Membuat pesanan...", cta, 14f, Color.WHITE, true)
    } else {
        val activeMethod = if (qrisOffered && checkoutPayment == "qris") "qris" else "cash"
        kit.button(c, if (activeMethod == "cash") "Buat pesanan · bayar tunai" else "Buat pesanan · bayar QRIS", cta)
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
    val imgBottom = minOf(h * .43f, 335f)
    drawProductImage(c, product, RectF(20f, 86f, 370f, imgBottom), 18f)
    kit.text(c, normalizeCategory(product.category), 22f, imgBottom + 28f, 12f, kit.blue, true)
    kit.text(c, product.name.take(31), 22f, imgBottom + 62f, 22f, kit.ink, true)
    kit.text(c, formatRupiah(product.priceIdr), 22f, imgBottom + 91f, 19f, kit.ink, true)
    kit.text(c, "Deskripsi", 22f, imgBottom + 131f, 14f, kit.ink, true)
    val lines = product.description.ifBlank { "Belum ada deskripsi produk." }.chunked(43).take(3)
    var nextY = imgBottom + 154f
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
