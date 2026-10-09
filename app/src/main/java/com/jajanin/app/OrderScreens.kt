package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

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

/**
 * Keranjang: daftar baris pemisah hairline, bayar berupa daftar radio,
 * total berupa baris angka rata. Tanpa kartu per item.
 */
internal fun JajaninView.drawCheckout(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Keranjang")
    val off = -scroll
    val lines = cart.entries.mapNotNull { (id, qty) ->
        val p = products.firstOrNull { it.id == id } ?: sellerProducts.firstOrNull { it.id == id }
        if (p == null || qty <= 0) null else p to qty
    }
    if (lines.isEmpty()) {
        kit.centerText(c, "Keranjangmu kosong.", RectF(24f, 280f, 366f, 340f), 16f, kit.ink, true)
        kit.centerText(c, "Pilih menu dulu dari beranda.", RectF(24f, 308f, 366f, 360f), 12f, kit.muted)
        contentHeight = 0f
        drawBottomNav(c, w, h, "Orders")
        return
    }
    kit.text(c, cartStallName.take(30), 22f, 110f + off, 18f, kit.ink, true)
    kit.text(c, "${lines.sumOf { it.second }} menu", 22f, 132f + off, 12f, kit.muted)
    var y = 150f + off
    lines.forEach { (p, qty) ->
        if (y + 64f >= 78f && y <= h - 78f) {
            kit.text(c, p.name.take(24), 22f, y + 22f, 13f, kit.ink, true)
            kit.text(c, "${formatRupiah(p.priceIdr)} × $qty", 22f, y + 44f, 11f, kit.muted)
            val sub = formatRupiah(p.priceIdr * qty)
            kit.text(c, sub, 368f - kit.measure(sub, 13f, true), y + 24f, 13f, kit.ink, true)
            val minus = RectF(210f, y + 34f, 236f, y + 58f)
            val plus = RectF(262f, y + 34f, 288f, y + 58f)
            kit.centerText(c, "−", minus, 17f, kit.blue, true)
            kit.centerText(c, "$qty", RectF(236f, y + 34f, 262f, y + 58f), 12f, kit.ink, true)
            kit.centerText(c, "+", plus, 17f, kit.blue, true)
            kit.centerText(c, "Hapus", RectF(296f, y + 34f, 350f, y + 58f), 10f, kit.danger, true)
            bounds["cartMinus:${p.id}"] = RectF(minus)
            bounds["cartPlus:${p.id}"] = RectF(plus)
            bounds["cartRemove:${p.id}"] = RectF(296f, y + 34f, 350f, y + 58f)
        }
        y += 64f
        kit.line(c, 22f, y, 368f, y)
    }

    y += 22f
    kit.text(c, "BAYAR PAKAI", 22f, y, 10f, kit.muted, true)
    y += 8f
    y = payRow(c, "Tunai", "Bayar saat ambil", checkoutPayment == "cash", "payMethod:cash", y)
    y = payRow(c, "QRIS", "Scan, lalu konfirmasi", checkoutPayment == "qris", "payMethod:qris", y)

    val subtotal = lines.sumOf { (p, qty) -> p.priceIdr * qty }
    y += 18f
    summaryRow(c, "Subtotal", formatRupiah(subtotal), y)
    y += 26f
    summaryRow(c, "Layanan", formatRupiah(SERVICE_FEE), y)
    y += 30f
    kit.text(c, "Total", 22f, y, 15f, kit.ink, true)
    val totalText = formatRupiah(subtotal + SERVICE_FEE)
    kit.text(c, totalText, 368f - kit.measure(totalText, 16f, true), y, 16f, kit.ink, true)
    y += 26f
    kit.text(c, if (orderingStatusLoading) "Memuat jatah..." else "Pesanan hari ini: $ordersToday/2", 22f, y, 11f, kit.blue, true)
    y += 20f
    kit.text(c, "Jam server ${serverClockLabel()}", 22f, y, 11f, kit.muted)
    y += 20f
    val queueText = if (stallLoading) "Memuat antrean..." else if (stallActiveOrders < 0) "" else "Antrean warung $stallActiveOrders/10"
    if (queueText.isNotBlank()) {
        kit.text(c, queueText, 22f, y, 11f, if (stallFull) kit.danger else kit.muted, stallFull)
        y += 20f
    }
    contentHeight = y + 20f
    if (currentScreen == Screen.CHECKOUT) postInvalidateDelayed(1000)

    val cta = RectF(20f, h - 78f - 56f, 370f, h - 78f)
    val blockMsg = if (orderingBlocked) {
        (orderingBlockMessage ?: "Pesanan sementara ditutup.") + (blackoutReopenLabel()?.let { " $it" } ?: "")
    } else if (stallFull) {
        "Warung penuh (10 antrean). Coba lagi sebentar."
    } else null
    if (blockMsg != null) {
        kit.text(c, blockMsg.take(72), 22f, cta.top - 20f, 11f, kit.danger, true)
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, if (orderingBlocked) "Pemesanan ditutup" else "Warung penuh", cta, 14f, Color.WHITE, true)
    } else if (placingOrder) {
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, "Membuat pesanan...", cta, 14f, Color.WHITE, true)
    } else {
        kit.button(c, if (checkoutPayment == "cash") "Pesan · bayar tunai" else "Pesan · bayar QRIS", cta)
        bounds["pay"] = RectF(cta)
        bounds["placeOrder"] = RectF(cta)
    }
    drawBottomNav(c, w, h, "Orders")
}

/** Baris radio pembayaran: label kiri, titik kanan. Mengembalikan y setelah baris. */
private fun JajaninView.payRow(c: Canvas, title: String, sub: String, on: Boolean, key: String, y: Float): Float {
    kit.text(c, title, 22f, y + 24f, 14f, kit.ink, true)
    kit.text(c, sub, 22f, y + 44f, 11f, kit.muted)
    kit.text(c, if (on) "●" else "○", 344f, y + 34f, 16f, if (on) kit.blue else kit.muted)
    kit.line(c, 22f, y + 56f, 368f, y + 56f)
    bounds[key] = RectF(22f, y, 368f, y + 56f)
    return y + 56f
}

/**
 * Bayar QRIS: QR di tengah polos, nominal besar, kode ambil, satu tombol.
 */
internal fun JajaninView.drawPayment(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Bayar QRIS")
    kit.text(c, cartStallName.take(28), 24f, 118f, 17f, kit.ink, true)
    kit.text(c, "Scan, bayar pas, lalu ketuk tombol bawah.", 24f, 142f, 12f, kit.muted)
    val qr = RectF(75f, 172f, 315f, 412f)
    kit.rounded(c, RectF(qr.left - 14f, qr.top - 14f, qr.right + 14f, qr.bottom + 14f), kit.card, 12f, kit.border)
    val qrUrl = qrisImageUrl
    when {
        qrisLoading -> kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
        qrUrl.isNullOrBlank() -> {
            kit.centerText(c, "Warung belum", RectF(85f, 300f, 305f, 330f), 13f, kit.danger, true)
            kit.centerText(c, "pasang QRIS.", RectF(85f, 322f, 305f, 352f), 13f, kit.danger, true)
        }
        else -> {
            val bmp = ProductImageCache.images[qrUrl]
            if (bmp == null) {
                kit.centerText(c, "Memuat QR...", qr, 13f, kit.muted)
                activity()?.loadProductImage(qrUrl)
            } else {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                val save = c.save()
                c.drawBitmap(bmp, null, qr, paint)
                c.restoreToCount(save)
            }
        }
    }
    kit.text(c, formatRupiah(qrisAmount), 24f, 478f, 30f, kit.ink, true)
    if (lastOrderPickup != null) {
        kit.text(c, "Kode ambil $lastOrderPickup", 24f, 504f, 13f, kit.blue, true)
    }
    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    kit.button(c, "Saya sudah membayar", cta)
    bounds["paid"] = RectF(cta)
}

/** Pesanan jadi: fokus ke kode ambil raksasa + tombol pantau. Tanpa kartu. */
internal fun JajaninView.drawReady(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.centerText(c, "✓", RectF(20f, 120f, w - 20f, 190f), 60f, kit.success, true)
    kit.centerText(c, "Pesanan diterima", RectF(20f, 200f, w - 20f, 236f), 22f, kit.ink, true)
    kit.centerText(c, lastOrderPickup ?: "—", RectF(20f, 248f, w - 20f, 320f), 52f, kit.blue, true)
    kit.centerText(c, "Tunjukkan kode ini ke penjual", RectF(20f, 326f, w - 20f, 350f), 13f, kit.muted)
    val methodText = if (lastOrderMethod == "cash") "Tunai · bayar saat ambil" else "QRIS · menunggu verifikasi"
    kit.centerText(c, "$methodText · ${formatRupiah(lastOrderTotal)}", RectF(20f, 356f, w - 20f, 380f), 12f, kit.muted)
    val cta = RectF(20f, h - 79f, w - 20f, h - 22f)
    kit.button(c, "Pantau pesanan", cta)
    bounds["orders"] = RectF(cta)
}

/**
 * Riwayat pesanan: entri bernapas lega dipisah hairline — status + jam,
 * isi, kode + bayar, aksi hanya bila perlu. Tanpa kartu.
 */
internal fun JajaninView.drawOrders(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Pesanan")
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
        else -> {
            var y = 96f + off
            orders.forEach { order ->
                val (label, color) = statusLabel(order.status)
                val actionH = if (order.paymentMethod == "qris" &&
                    (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected")
                ) 40f else if (order.paymentStatus == "waiting_verification") 22f else 0f
                val entryH = 108f + actionH
                if (y + entryH >= 78f && y <= h - 78f) {
                    kit.text(c, label, 22f, y + 24f, 14f, color, true)
                    kit.text(c, order.timeLabel, 368f - kit.measure(order.timeLabel, 11f), y + 24f, 11f, kit.muted)
                    kit.text(c, order.itemsSummary.take(34).ifBlank { "Pesanan" }, 22f, y + 50f, 14f, kit.ink, true)
                    val (payLabel, payColor) = paymentLabel(order.paymentStatus)
                    val methodName = if (order.paymentMethod == "cash") "Tunai" else "QRIS"
                    kit.text(c, "${order.pickupCode} · $methodName · $payLabel · ${formatRupiah(order.totalIdr)}", 22f, y + 72f, 11f, payColor, true)
                    if (order.paymentMethod == "qris" &&
                        (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected")
                    ) {
                        val pay = RectF(22f, y + 84f, 250f, y + 116f)
                        kit.rounded(c, pay, kit.tint, 10f)
                        kit.centerText(
                            c,
                            if (order.paymentStatus == "rejected") "Ditolak · kirim bukti lagi" else "Saya sudah membayar",
                            pay, 11f, kit.blue, true,
                        )
                        bounds["myOrderPaid:${order.id}"] = RectF(pay)
                    } else if (order.paymentStatus == "waiting_verification") {
                        kit.text(c, "Menunggu verifikasi penjual…", 22f, y + 92f, 10f, kit.muted)
                    }
                }
                y += entryH + 10f
                kit.line(c, 22f, y, 368f, y)
                y += 10f
            }
            contentHeight = y - (96f + off) + 96f
        }
    }
    if (orders.isEmpty()) contentHeight = 0f
    drawBottomNav(c, w, h, "Orders")
}

/** Label + warna status pembayaran (terpisah dari status order). */
private fun paymentLabel(status: String): Pair<String, Int> = when (status.lowercase()) {
    "paid" -> "Lunas" to Color.rgb(31, 158, 95)
    "waiting_verification" -> "Verifikasi bayar" to Color.rgb(0, 99, 214)
    "rejected" -> "Bukti ditolak" to Color.rgb(206, 60, 60)
    else -> "Belum bayar" to Color.rgb(183, 116, 0)
}

private val adminDateFormat = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.forLanguageTag("id"))

/** Pasangan (label, warna) untuk satu status order baru maupun lama. */
private fun statusLabel(status: String): Pair<String, Int> = when (status.lowercase()) {
    STATUS_PENDING -> "Menunggu" to Color.rgb(183, 116, 0)
    STATUS_CONFIRMED -> "Dikonfirmasi" to Color.rgb(99, 102, 241)
    STATUS_PROCESSING_LEGACY -> "Disiapkan" to Color.rgb(0, 99, 214)
    STATUS_PREPARING -> "Disiapkan" to Color.rgb(0, 99, 214)
    STATUS_READY -> "Siap diambil" to Color.rgb(31, 158, 95)
    STATUS_COMPLETED -> "Selesai" to Color.rgb(112, 120, 137)
    STATUS_CANCELLED -> "Dibatalkan" to Color.rgb(206, 60, 60)
    else -> status to Color.rgb(112, 120, 137)
}

/** Langkah status berikutnya (label tombol, nilai DB) dari alur penjual. */
internal fun statusNextAction(status: String): Pair<String, String>? = when (status.lowercase()) {
    STATUS_PENDING -> "Konfirmasi" to STATUS_CONFIRMED
    STATUS_CONFIRMED -> "Mulai siapkan" to STATUS_PREPARING
    STATUS_PROCESSING_LEGACY -> "Mulai siapkan" to STATUS_PREPARING
    STATUS_PREPARING -> "Tandai siap" to STATUS_READY
    STATUS_READY -> "Selesaikan" to STATUS_COMPLETED
    else -> null
}

/** Status akhir: order dianggap tutup dan tidak punya aksi lanjut. */
internal fun isTerminalStatus(status: String): Boolean {
    val s = status.lowercase()
    return s == STATUS_COMPLETED || s == STATUS_CANCELLED
}

private fun statusPill(c: Canvas, kit: DrawKit, label: String, color: Int, left: Float, top: Float) {
    val width = kit.measure(label, 10f, true) + 22f
    val pill = RectF(left, top, left + width, top + 24f)
    val soft = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
    kit.rounded(c, pill, soft, 12f)
    kit.centerText(c, label, pill, 10f, color, true)
}

/**
 * Order yang masih menunggu aksi penjual: baru/dikonfirmasi, plus bukti bayar
 * QRIS yang menunggu verifikasi. Dipakai badge lonceng & nav penjual.
 */
internal fun JajaninView.adminActionableCount(): Int = adminOrders.count { order ->
    when (order.status.lowercase()) {
        STATUS_PENDING, STATUS_CONFIRMED -> true
        STATUS_COMPLETED, STATUS_CANCELLED -> false
        else -> order.paymentStatus == "waiting_verification"
    }
}

/** Highlight sentuhan untuk kontrol penjual; dipanggil tepat setelah elemen digambar. */
private fun JajaninView.press(c: Canvas, key: String, r: RectF, radius: Float = 12f) {
    if (pressedKey == key) kit.rounded(c, r, kit.pressOverlay, radius)
}

/**
 * Daftarkan hit area yang menutup sebagian di bawah header/nav: bagian yang
 * terlihat tetap bisa diketuk dan bagian tersembunyi dibuang — tak ada "tap hantu"
 * di balik header seperti pada kondisi sebelumnya.
 */
private fun LinkedHashMap<String, RectF>.putVisible(
    key: String,
    r: RectF,
    topLimit: Float = 86f,
    bottomLimit: Float = Float.MAX_VALUE,
) {
    val top = r.top.coerceAtLeast(topLimit)
    val bottom = r.bottom.coerceAtMost(bottomLimit)
    if (bottom > top && r.right > r.left) put(key, RectF(r.left, top, r.right, bottom))
}

/** Bottom navigation 5 tab khusus penjual (kunci hit "stab:<label>"). */
internal fun JajaninView.drawAdminNav(c: Canvas, w: Float, h: Float, active: String) {
    val top = h - 78f
    kit.rect(c, 0f, top, w, h, kit.card)
    kit.line(c, 0f, top, w, top)
    val labels = listOf("Dashboard", "Pesanan", "Produk", "Notifikasi", "Profil")
    val slot = w / labels.size
    val notifCount = adminActionableCount()
    labels.forEachIndexed { i, label ->
        val cx = slot * i + slot / 2f
        val selected = label == active
        if (selected) kit.rounded(c, RectF(cx - 34f, top + 6f, cx + 34f, top + 38f), kit.tint, 15f)
        if (pressedKey == "stab:$label") kit.rounded(c, RectF(cx - 34f, top + 6f, cx + 34f, top + 38f), kit.pressOverlay, 15f)
        val iconColor = if (selected) kit.blue else kit.muted
        val iconName = when (label) {
            "Dashboard" -> "grid"
            "Pesanan" -> "receipt"
            "Produk" -> "box"
            "Notifikasi" -> "bell"
            "Profil" -> "person"
            else -> "grid"
        }
        drawIcon(c, iconName, cx, top + 21f, 17f, iconColor)
        if (label == "Notifikasi" && notifCount > 0) {
            val badgeText = if (notifCount > 9) "9+" else notifCount.toString()
            val badge = RectF(cx + 6f, top + 8f, cx + 26f, top + 24f)
            kit.rounded(c, badge, kit.danger, 8f)
            kit.centerText(c, badgeText, badge, 8f, Color.WHITE, true)
        }
        kit.centerText(c, label, RectF(cx - 45f, top + 40f, cx + 45f, top + 64f), 9f, if (selected) kit.blue else kit.muted, selected)
        bounds["stab:$label"] = RectF(i * slot, top, (i + 1) * slot, h)
    }
}

internal fun JajaninView.drawAdminDashboard(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val today = adminOrders
    val pending = today.count { it.status.equals(STATUS_PENDING, true) }
    val confirmed = today.count { it.status.equals(STATUS_CONFIRMED, true) }
    val preparing = today.count { it.status.equals(STATUS_PREPARING, true) || it.status.equals(STATUS_PROCESSING_LEGACY, true) }
    val ready = today.count { it.status.equals(STATUS_READY, true) }
    val completed = today.count { it.status.equals(STATUS_COMPLETED, true) }
    val revenue = today.filterNot { it.status.equals(STATUS_CANCELLED, true) }.sumOf { it.totalIdr }

    // --- Jangkar layout dihitung dulu (murni aritmetika) sehingga tab "Pesanan"
    // bisa melompat ke daftar pesanan dengan scroll tepat sebelum konten digambar. ---
    // Susunan vertikal: perhatian dulu, lalu angka hari ini, menu, daftar pesanan.
    val actionable = today.filter { order ->
        val s = order.status.lowercase()
        s == STATUS_PENDING || s == STATUS_CONFIRMED ||
            (!isTerminalStatus(order.status) && order.paymentStatus == "waiting_verification")
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
    val attentionRowH = 104f
    val attentionH = 40f + if (actionable.isEmpty()) 44f else actionable.size * attentionRowH
    val omzetH = 92f
    val shownProducts = sellerProducts.take(6)
    val menuRowH = 62f
    val menuH = 40f + maxOf(shownProducts.size * menuRowH, 52f)
    val ordersStart = 100f + attentionH + omzetH + menuH + 28f

    val filtered = today.filter { order ->
        val s = order.status.lowercase()
        when (adminFilter) {
            "Baru" -> s == STATUS_PENDING
            "Disiapkan" -> s == STATUS_CONFIRMED || s == STATUS_PREPARING || s == STATUS_PROCESSING_LEGACY
            "Siap" -> s == STATUS_READY
            "Selesai" -> s == STATUS_COMPLETED || s == STATUS_CANCELLED
            else -> true
        }
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
    val rowHeight = 72f
    val contentTotal = ordersStart + 84f + filtered.size * rowHeight + 170f
    if (pendingScrollToOrders) {
        pendingScrollToOrders = false
        val maxScroll = (contentTotal - h + 78f).coerceAtLeast(0f)
        scroll = (ordersStart - 96f).coerceAtLeast(0f).coerceAtMost(maxScroll)
    }
    val off = -scroll

    // --- 1. Perlu tindakan: hanya order yang menunggu aksi, paling atas. ---
    val attentionTitle = if (actionable.isEmpty()) "Nggak ada yang nunggu" else "Perlu tindakan · ${actionable.size}"
    kit.text(c, attentionTitle.uppercase(), 20f, 112f + off, 11f, if (actionable.isEmpty()) kit.muted else kit.danger, true)
    if (actionable.isEmpty()) {
        kit.text(c, "Semua pesanan beres. Santai dulu.", 20f, 140f + off, 13f, kit.muted)
    }
    actionable.forEachIndexed { index, order ->
        val top = 124f + index * attentionRowH + off
        if (top + 96f >= 86f && top <= h - 78f) {
            val needsPay = order.paymentStatus == "waiting_verification" && !isTerminalStatus(order.status)
            val headline = if (needsPay) "Cek bukti bayar · ${order.buyerName.take(16)}" else "${order.buyerName.take(18)} · ${order.timeLabel}"
            kit.text(c, headline, 20f, top + 22f, 14f, kit.ink, true)
            kit.text(c, "${order.pickupCode} · ${order.itemsSummary.take(26)} · ${formatRupiah(order.totalIdr)}", 20f, top + 44f, 11f, kit.muted)
            val nextAction = statusNextAction(order.status)
            var bx = 20f
            if (nextAction != null) {
                val label = nextAction.first
                val bw = kit.measure(label, 11f, true) + 30f
                val action = RectF(bx, top + 56f, bx + bw, top + 88f)
                kit.rounded(c, action, kit.blue, 10f)
                kit.centerText(c, label, action, 11f, Color.WHITE, true)
                press(c, "adminStatus:${order.id}", action, 10f)
                bounds.putVisible("adminStatus:${order.id}", action, bottomLimit = h - 78f)
                bx += bw + 10f
            }
            if (needsPay) {
                val label = "Lunas"
                val bw = kit.measure(label, 11f, true) + 30f
                val paid = RectF(bx, top + 56f, bx + bw, top + 88f)
                kit.rounded(c, paid, kit.success, 10f)
                kit.centerText(c, label, paid, 11f, Color.WHITE, true)
                press(c, "payPaid:${order.id}", paid, 10f)
                bounds.putVisible("payPaid:${order.id}", paid, bottomLimit = h - 78f)
                bx += bw + 10f
            }
            if (!isTerminalStatus(order.status)) {
                val cancel = RectF(bx, top + 56f, bx + 76f, top + 88f)
                kit.centerText(c, "Batalkan", cancel, 11f, kit.danger, true)
                press(c, "adminCancel:${order.id}", cancel, 10f)
                bounds.putVisible("adminCancel:${order.id}", cancel, bottomLimit = h - 78f)
            }
            bounds.putVisible("adminOrder:${order.id}", RectF(20f, top, 240f, top + 52f), bottomLimit = h - 78f)
        }
        if (index < actionable.size - 1) kit.line(c, 20f, top + 96f, 370f, top + 96f)
    }

    // --- 2. Angka hari ini: satu baris angka, bukan spanduk. ---
    val omzetY = 100f + attentionH + off
    kit.text(c, "HARI INI", 20f, omzetY + 14f, 11f, kit.muted, true)
    kit.text(c, formatRupiah(revenue), 20f, omzetY + 46f, 24f, kit.ink, true)
    kit.text(c, "${today.size} pesanan · $ready siap · $completed selesai", 20f, omzetY + 70f, 11f, kit.muted)
    kit.line(c, 20f, omzetY + 84f, 370f, omzetY + 84f)
    // --- 3. Menu: baris kerja ringkas — nama/harga kiri, Edit + status + hapus kanan. ---
    val menuY = 100f + attentionH + omzetH + off
    kit.text(c, "MENU · ${sellerProducts.size}".uppercase(), 20f, menuY + 14f, 11f, kit.muted, true)
    if (shownProducts.isEmpty()) {
        kit.text(c, "Belum ada menu. Ketuk + untuk nambah.", 20f, menuY + 42f, 13f, kit.muted)
    }
    shownProducts.forEachIndexed { index, product ->
        val py = menuY + 28f + index * menuRowH
        if (py + menuRowH >= 86f && py <= h - 78f) {
            kit.text(c, product.name.take(22), 20f, py + 22f, 13f, kit.ink, true)
            val priceStock = "${formatRupiah(product.priceIdr)}${product.stock?.let { " · sisa $it" }.orEmpty()}"
            kit.text(c, priceStock.take(32), 20f, py + 43f, 11f, kit.muted)
            val edit = RectF(196f, py + 12f, 244f, py + 46f)
            kit.centerText(c, "Edit", edit, 11f, kit.blue, true)
            press(c, "productEdit:${product.id}", edit, 8f)
            bounds.putVisible("productEdit:${product.id}", edit, bottomLimit = h - 78f)
            val toggle = RectF(248f, py + 12f, 312f, py + 46f)
            kit.centerText(c, if (product.available) "● Aktif" else "○ Mati", toggle, 11f, if (product.available) kit.success else kit.muted, true)
            press(c, "productToggle:${product.id}", toggle, 8f)
            bounds.putVisible("productToggle:${product.id}", toggle, bottomLimit = h - 78f)
            val delete = RectF(322f, py + 12f, 362f, py + 46f)
            drawIcon(c, "close", delete.centerX(), delete.centerY(), 13f, kit.danger)
            press(c, "productDelete:${product.id}", delete, 8f)
            bounds.putVisible("productDelete:${product.id}", delete, bottomLimit = h - 78f)
        }
        if (index < shownProducts.size - 1) kit.line(c, 20f, py + menuRowH - 6f, 370f, py + menuRowH - 6f)
    }

    // --- 4. Semua pesanan: saringan + baris ledger. Aksi ada di bagian atas. ---
    kit.text(c, "PESANAN · ${filtered.size}".uppercase(), 20f, ordersStart + 14f + off, 11f, kit.muted, true)
    val filters = listOf("Semua", "Baru", "Disiapkan", "Siap", "Selesai")
    var x = 20f
    filters.forEach { filter ->
        // Chip "Baru" memuat jumlahnya supaya pesanan yang menunggu langsung terbaca.
        val labelText = if (filter == "Baru" && pending > 0) "Baru ($pending)" else filter
        val width = kit.measure(labelText, 10f, true) + 22f
        val chip = RectF(x, ordersStart + 24f + off, x + width, ordersStart + 52f + off)
        val active = adminFilter == filter
        kit.rounded(c, chip, if (active) kit.blue else kit.card, 14f, if (active) null else kit.border)
        kit.centerText(c, labelText, chip, 10f, if (active) Color.WHITE else kit.ink, true)
        press(c, "adminFilter:$filter", chip, 14f)
        bounds.putVisible("adminFilter:$filter", chip, bottomLimit = h - 78f)
        x += width + 7f
    }

    filtered.forEachIndexed { index, order ->
        val top = ordersStart + 62f + index * rowHeight + off
        if (top + rowHeight >= 86f && top <= h - 78f) {
            val (label, color) = statusLabel(order.status)
            kit.text(c, "●", 20f, top + 26f, 11f, color, true)
            kit.text(c, "${order.buyerName.take(18)} · $label", 36f, top + 26f, 13f, kit.ink, true)
            val total = formatRupiah(order.totalIdr)
            kit.text(c, total, 370f - kit.measure(total, 12f, true), top + 26f, 12f, kit.ink, true)
            kit.text(c, "${order.pickupCode} · ${order.itemCount} item · ${order.itemsSummary.take(24)}", 36f, top + 50f, 11f, kit.muted)
            // Order yang juga tampil di "Perlu tindakan" sudah punya area ketuk
            // di sana; jangan timpa supaya ketukannya tetap di posisi yang benar.
            if (!bounds.containsKey("adminOrder:${order.id}")) {
                bounds.putVisible("adminOrder:${order.id}", RectF(20f, top, 370f, top + rowHeight), bottomLimit = h - 78f)
            }
        }
    }

    val emptyTop = ordersStart + 70f + off
    when {
        adminLoading && today.isEmpty() ->
            kit.centerText(c, "Memuat pesanan...", RectF(24f, emptyTop, 366f, emptyTop + 60f), 14f, kit.muted)
        adminError != null && today.isEmpty() ->
            kit.centerText(c, "Gagal memuat pesanan. Ketuk ↻ untuk mencoba lagi.", RectF(24f, emptyTop - 4f, 366f, emptyTop + 64f), 13f, kit.danger)
        filtered.isEmpty() ->
            kit.centerText(c, if (today.isEmpty()) "Belum ada pesanan hari ini" else "Tidak ada pesanan di filter ini", RectF(24f, emptyTop, 366f, emptyTop + 60f), 13f, kit.muted)
    }
    contentHeight = contentTotal

    // FAB digambar setelah seluruh konten agar tidak tertimpa kartu yang di-scroll.
    val fab = RectF(w - 76f, h - 78f - 72f, w - 20f, h - 78f - 16f)
    kit.rounded(c, fab, kit.blue, 28f)
    drawIcon(c, "plus", fab.centerX(), fab.centerY(), 22f, Color.WHITE)
    press(c, "productAdd", fab, 28f)
    bounds["productAdd"] = RectF(fab)

    // --- Header (fixed; digambar setelah konten agar menutupi yang di-scroll) ---
    kit.rect(c, 0f, 0f, w, 80f, kit.card)
    kit.line(c, 0f, 80f, w, 80f)
    kit.text(c, myStallName.takeIf { it.isNotBlank() }?.take(20) ?: "Warung saya", 20f, 42f, 19f, kit.ink, true)
    kit.text(c, adminDateFormat.format(java.util.Date()), 20f, 64f, 11f, kit.muted)
    val refresh = RectF(240f, 20f, 282f, 64f)
    kit.rounded(c, refresh, kit.tint, 14f)
    drawIcon(c, "refresh", refresh.centerX(), refresh.centerY(), 18f, kit.blue)
    press(c, "adminRefresh", refresh, 14f)
    bounds["adminRefresh"] = RectF(refresh)
    val bell = RectF(286f, 20f, 330f, 64f)
    kit.rounded(c, bell, kit.tint, 14f)
    drawIcon(c, "bell", bell.centerX(), bell.centerY(), 18f, kit.blue)
    press(c, "adminBell", bell, 14f)
    bounds["adminBell"] = RectF(bell)
    val notifCount = adminActionableCount()
    if (notifCount > 0) {
        val badgeText = if (notifCount > 9) "9+" else notifCount.toString()
        val badgeWidth = maxOf(20f, kit.measure(badgeText, 9f, true) + 12f)
        val badge = RectF(bell.right - 16f, bell.top - 6f, bell.right - 16f + badgeWidth, bell.top + 14f)
        kit.rounded(c, badge, kit.danger, 10f)
        kit.centerText(c, badgeText, badge, 9f, Color.WHITE, true)
    }
    val avatar = RectF(338f, 20f, 382f, 64f)
    kit.rounded(c, avatar, kit.tint, 22f)
    val initial = (myStallName.firstOrNull() ?: SupabaseRepository.currentEmail()?.firstOrNull() ?: 'J').uppercaseChar().toString()
    kit.centerText(c, initial, avatar, 17f, kit.blue, true)
    press(c, "adminProfile", avatar, 22f)
    bounds["adminProfile"] = RectF(avatar)

    drawAdminNav(c, w, h, "Dashboard")

    // --- Panel notifikasi digambar paling akhir sehingga menutupi header & nav;
    // area di luar panel otomatis jadi tombol tutup lewat bound "adminNotifClose". ---
    if (adminNotifOpen) {
        kit.rect(c, 0f, 0f, w, h, Color.argb(150, 15, 20, 35))
        bounds["adminNotifClose"] = RectF(0f, 0f, w, h)
        val rows = ArrayList<Pair<AdminOrder, Boolean>>()
        today.filter { order ->
            val s = order.status.lowercase()
            s == STATUS_PENDING || s == STATUS_CONFIRMED
        }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
            .forEach { rows.add(it to true) }
        today.filter { order ->
            order.paymentStatus == "waiting_verification" && !isTerminalStatus(order.status)
        }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
            .forEach { rows.add(it to false) }
        val shown = rows.take(8)
        val panelBottom = minOf(96f + 76f + shown.size * 62f + 12f, h - 40f)
        val panel = RectF(20f, 96f, 370f, panelBottom)
        kit.rounded(c, panel, kit.card, 18f)
        kit.text(c, "Notifikasi", 36f, 130f, 16f, kit.ink, true)
        val close = RectF(332f, 106f, 364f, 138f)
        drawIcon(c, "close", close.centerX(), close.centerY(), 13f, kit.muted)
        press(c, "adminNotifCloseBtn", close, 16f)
        bounds["adminNotifCloseBtn"] = RectF(close)
        if (shown.isEmpty()) {
            kit.centerText(c, "Belum ada notifikasi", RectF(36f, 148f, 354f, 180f), 13f, kit.muted)
        }
        shown.forEachIndexed { index, (order, isNew) ->
            val top = 150f + index * 62f
            if (top + 54f > panelBottom) return@forEachIndexed
            val row = RectF(32f, top, 358f, top + 54f)
            kit.rounded(c, row, kit.bg, 12f)
            kit.rounded(c, RectF(44f, top + 14f, 56f, top + 26f), if (isNew) Color.rgb(183, 116, 0) else kit.blue, 6f)
            val title = if (isNew) "Pesanan baru dari ${order.buyerName.take(18)}" else "Bukti bayar dari ${order.buyerName.take(18)}"
            kit.text(c, title, 66f, top + 24f, 12f, kit.ink, true)
            kit.text(c, "Kode ${order.pickupCode} · ${order.timeLabel} · ${formatRupiah(order.totalIdr)}", 66f, top + 43f, 10f, kit.muted)
            press(c, "adminNotifOrder:${order.id}", row, 12f)
            bounds["adminNotifOrder:${order.id}"] = RectF(row)
        }
    }
}

internal fun JajaninView.drawAdminDetail(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val order = adminDetailOrder

    when {
        order == null && adminDetailLoading -> {
            kit.centerText(c, "Memuat pesanan...", RectF(24f, 300f, 366f, 360f), 14f, kit.muted)
            contentHeight = 0f
        }
        order == null && adminDetailError != null -> {
            kit.centerText(c, adminDetailError ?: "Gagal memuat pesanan.", RectF(24f, 280f, 366f, 350f), 13f, kit.danger)
            val retry = RectF(120f, 372f, 270f, 418f)
            kit.button(c, "Muat ulang", retry, kit.blue, 14f)
            bounds["adminRetry"] = RectF(retry)
            contentHeight = 0f
        }
        order == null -> {
            kit.centerText(c, "Pesanan tidak ditemukan.", RectF(24f, 300f, 366f, 360f), 14f, kit.muted)
            contentHeight = 0f
        }
        else -> {
            if (adminDetailError != null) {
                kit.rounded(c, RectF(20f, 96f + off, 370f, 136f + off), kit.dangerBorder, 12f)
                kit.centerText(c, adminDetailError ?: "", RectF(28f, 96f + off, 362f, 136f + off), 11f, kit.danger, true)
            }
            val top = if (adminDetailError != null) 152f else 100f
            val card1 = RectF(20f, top + off, 370f, top + 136f + off)
            kit.rounded(c, card1, kit.card, 16f, kit.border)
            kit.text(c, "KODE AMBIL", 34f, top + 30f + off, 10f, kit.muted, true)
            kit.text(c, order.pickupCode, 34f, top + 68f + off, 30f, kit.blue, true)
            val (label, color) = statusLabel(order.status)
            val pillWidth = kit.measure(label, 10f, true) + 22f
            statusPill(c, kit, label, color, 356f - pillWidth, top + 18f + off)
            kit.text(c, order.buyerName, 34f, top + 100f + off, 14f, kit.ink, true)
            kit.text(c, "Masuk ${order.timeLabel}  ·  ${order.itemCount} item", 34f, top + 122f + off, 11f, kit.muted)
            kit.text(c, "Bayar via ${order.paymentMethod?.takeIf { it.isNotBlank() } ?: "QRIS"}", 200f, top + 122f + off, 11f, kit.muted)

            kit.text(c, "Alur status", 24f, top + 156f + off, 12f, kit.ink, true)
            val trackTop = top + 166f + off
            val steps = listOf(
                STATUS_PENDING to "1",
                STATUS_CONFIRMED to "2",
                STATUS_PREPARING to "3",
                STATUS_READY to "4",
                STATUS_COMPLETED to "5",
            )
            val stepLabels = listOf("Baru", "Dikonfirmasi", "Disiapkan", "Siap", "Selesai")
            val stage = when (order.status.lowercase()) {
                STATUS_PENDING -> 0
                STATUS_CONFIRMED -> 1
                STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
                STATUS_READY -> 3
                STATUS_COMPLETED -> 4
                else -> -1
            }
            val segW = (356f - 34f) / steps.size
            steps.forEachIndexed { index, step ->
                val cx = 34f + segW * index + segW / 2f
                val done = stage >= 0 && index <= stage
                kit.rounded(c, RectF(cx - 13f, trackTop, cx + 13f, trackTop + 26f), if (done) kit.blue else kit.bg, 13f, if (done) null else kit.border)
                kit.centerText(c, step.second, RectF(cx - 13f, trackTop, cx + 13f, trackTop + 26f), 12f, if (done) Color.WHITE else kit.muted, true)
                kit.centerText(c, stepLabels[index], RectF(cx - segW / 2f, trackTop + 28f, cx + segW / 2f, trackTop + 42f), 8f, if (done) kit.ink else kit.muted, done)
                if (index < steps.size - 1) {
                    kit.rect(c, cx + 13f, trackTop + 12f, cx + segW - 13f, trackTop + 14f, if (stage > index) kit.blue else kit.border)
                }
            }
            kit.text(c, "Item pesanan", 24f, top + 224f + off, 15f, kit.ink, true)
            val itemsTop = top + 238f
            val card2Bottom = itemsTop + order.items.size * 38f + 14f
            kit.rounded(c, RectF(20f, itemsTop + off, 370f, card2Bottom + off), kit.card, 16f, kit.border)
            if (order.items.isEmpty()) {
                kit.centerText(c, "Tidak ada rincian item.", RectF(32f, itemsTop + off, 358f, card2Bottom + off), 12f, kit.muted)
            }
            order.items.forEachIndexed { index, item ->
                val iy = itemsTop + 14f + index * 38f + off
                kit.text(c, "${item.quantity}× ${item.itemName.take(20)}", 34f, iy + 22f, 12f, kit.ink, true)
                kit.text(c, formatRupiah(item.unitPriceIdr), 216f, iy + 22f, 10f, kit.muted)
                val sub = formatRupiah(item.unitPriceIdr * item.quantity)
                kit.text(c, sub, 356f - kit.measure(sub, 12f, true), iy + 22f, 12f, kit.ink, true)
                if (index < order.items.size - 1) kit.line(c, 34f, iy + 34f, 356f, iy + 34f)
            }

            val totalTop = card2Bottom + 14f
            kit.rounded(c, RectF(20f, totalTop + off, 370f, totalTop + 56f + off), kit.card, 14f, kit.border)
            kit.text(c, "Total", 34f, totalTop + 35f + off, 14f, kit.ink, true)
            val totalText = formatRupiah(order.totalIdr)
            kit.text(c, totalText, 356f - kit.measure(totalText, 16f, true), totalTop + 36f + off, 16f, kit.ink, true)

            // Kartu pembayaran: hanya penjual yang boleh menandai lunas / verifikasi / tolak.
            val payTop = totalTop + 56f + 14f
            val isCash = order.paymentMethod == "cash"
            val (payLabel, payColor) = paymentLabel(order.paymentStatus)
            val needsAction = (isCash && order.paymentStatus == "unpaid") ||
                (!isCash && order.paymentStatus == "waiting_verification")
            val payH = if (needsAction) 108f else 72f
            kit.rounded(c, RectF(20f, payTop + off, 370f, payTop + payH + off), kit.card, 14f, kit.border)
            kit.text(c, if (isCash) "Tunai · bayar saat ambil" else "QRIS", 34f, payTop + 28f + off, 13f, kit.ink, true)
            statusPill(c, kit, payLabel, payColor, 356f - (kit.measure(payLabel, 10f, true) + 22f), payTop + 14f + off)
            if (isCash && order.paymentStatus == "unpaid") {
                kit.text(c, "Tandai lunas setelah pembeli membayar.", 34f, payTop + 50f + off, 10f, kit.muted)
                val paid = RectF(34f, payTop + 60f + off, 220f, payTop + 96f + off)
                kit.rounded(c, paid, kit.successSoft, 10f)
                kit.centerText(c, "Tandai sudah dibayar", paid, 11f, kit.success, true)
                press(c, "payPaid:${order.id}", paid, 10f)
                bounds.putVisible("payPaid:${order.id}", paid)
            } else if (!isCash && order.paymentStatus == "waiting_verification") {
                kit.text(c, "Pembeli mengaku sudah membayar. Verifikasi dulu.", 34f, payTop + 50f + off, 10f, kit.muted)
                val verify = RectF(34f, payTop + 60f + off, 196f, payTop + 96f + off)
                kit.rounded(c, verify, kit.successSoft, 10f)
                kit.centerText(c, "Verifikasi lunas", verify, 11f, kit.success, true)
                press(c, "payPaid:${order.id}", verify, 10f)
                bounds.putVisible("payPaid:${order.id}", verify)
                val reject = RectF(204f, payTop + 60f + off, 330f, payTop + 96f + off)
                kit.rounded(c, reject, kit.card, 10f, kit.dangerBorder)
                kit.centerText(c, "Tolak bukti", reject, 11f, kit.danger, true)
                press(c, "payReject:${order.id}", reject, 10f)
                bounds.putVisible("payReject:${order.id}", reject)
            } else {
                val hint = when (order.paymentStatus) {
                    "paid" -> "Sudah lunas, tercatat sistem."
                    "rejected" -> "Bukti ditolak — pembeli bisa kirim ulang."
                    else -> "Menunggu pembayaran pembeli."
                }
                kit.text(c, hint, 34f, payTop + 50f + off, 10f, kit.muted)
            }
            contentHeight = payTop + payH + 100f
        }
    }

    // Aksi status tetap di bawah layar (fixed). Nav sengaja tidak digambar di
    // layar detail: dulu nav menindih tombol ini sehingga tap utama tidak pernah
    // kena (hit test mengambil bound nav yang didaftarkan belakangan).
    val next = statusNextAction(order?.status ?: "")
    if (next != null) {
        val cta = RectF(20f, h - 76f, 370f, h - 20f)
        kit.button(c, next.first, cta)
        press(c, "adminSet:${next.second}", cta, 14f)
        bounds["adminSet:${next.second}"] = RectF(cta)
    } else if (order != null) {
        val terminal = order.status.lowercase() == STATUS_CANCELLED
        val chip = RectF(95f, h - 72f, 295f, h - 26f)
        kit.rounded(c, chip, if (terminal) kit.dangerBorder else kit.successSoft, 14f)
        kit.centerText(c, if (terminal) "Pesanan dibatalkan" else "Pesanan selesai ✓", chip, 13f, if (terminal) kit.danger else kit.success, true)
    }

    // Header detail (fixed, di atas konten)
    kit.rect(c, 0f, 0f, w, 78f, kit.card)
    kit.line(c, 0f, 78f, w, 78f)
    drawIcon(c, "chevronLeft", 27f, 39f, 24f, kit.blue)
    kit.text(c, "Detail Pesanan", 58f, 47f, 19f, kit.ink, true)
    press(c, "adminBack", RectF(0f, 0f, 56f, 78f), 0f)
    bounds["adminBack"] = RectF(0f, 0f, 56f, 78f)
}

/**
 * Profil: kepala polos (avatar + nama + email), daftar baris hairline,
 * keluar berupa teks merah. Tanpa kartu menu.
 */
internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Profil")
    val titleText = if (isSellerProfile) {
        myStallName.takeIf { it.isNotBlank() }?.take(24) ?: "Memuat toko..."
    } else {
        myDisplayName.takeIf { it.isNotBlank() }?.take(24) ?: "Pengguna Jajanin"
    }
    val avatar = RectF(24f, 96f, 84f, 156f)
    kit.rounded(c, avatar, kit.tint, 30f)
    kit.centerText(c, (titleText.firstOrNull() ?: 'A').uppercaseChar().toString(), avatar, 25f, kit.blue, true)
    kit.text(c, titleText, 100f, 122f, 18f, kit.ink, true)
    kit.text(c, SupabaseRepository.currentEmail()?.take(34) ?: "Belum masuk", 100f, 144f, 12f, kit.muted)
    val suspended = myAccountStatus == "suspended"
    kit.text(c, if (suspended) "Ditangguhkan" else "Aktif", 100f, 164f, 11f, if (suspended) kit.danger else kit.success, true)

    var y = 188f
    if (isSellerProfile) {
        y = profileRow(c, "Nama toko", "${myStallName.takeIf { it.isNotBlank() } ?: "Belum diisi"} · Ubah", "profileStallName", y)
        y = profileRow(c, "QRIS warung", if (myStallQris.isBlank()) "Belum dipasang" else "Terpasang", "profileQris", y)
    } else {
        listOf("Informasi akun", "Alamat tersimpan", "Bantuan", "Tentang Jajanin").forEach { title ->
            y = profileRow(c, title, "", null, y)
        }
    }
    val unread = inboxUnreadCount()
    y = profileRow(
        c, "Notifikasi",
        if (unread > 0) "$unread belum dibaca" else "Semua sudah dibaca",
        "inbox", y,
        subColor = if (unread > 0) kit.danger else kit.success,
    )
    y = profileRow(c, "Tema", if (darkMode) "Gelap" else "Terang", "theme", y, subColor = kit.blue)

    y += 18f
    kit.text(c, "Keluar dari akun", 24f, y + 22f, 14f, kit.danger, true)
    bounds["logout"] = RectF(24f, y, 220f, y + 44f)

    if (isSellerProfile) drawAdminNav(c, w, h, "Profil") else drawBottomNav(c, w, h, "Profile")
}

/** Satu baris daftar profil + hairline. Mengembalikan y setelah baris. */
private fun JajaninView.profileRow(
    c: Canvas,
    title: String,
    sub: String,
    key: String?,
    y: Float,
    subColor: Int = kit.muted,
): Float {
    val h = if (sub.isNotBlank()) 62f else 52f
    kit.text(c, title, 24f, y + 24f, 14f, kit.ink)
    if (sub.isNotBlank()) kit.text(c, sub, 24f, y + 46f, 11f, subColor, true)
    drawIcon(c, "chevronRight", 352f, y + h / 2f, 14f, kit.muted)
    kit.line(c, 24f, y + h, 366f, y + h)
    if (key != null) {
        if (key == "profileQris" || key == "profileStallName") press(c, key, RectF(24f, y, 366f, y + h), 8f)
        bounds[key] = RectF(24f, y, 366f, y + h)
    }
    return y + h
}

/**
 * Detail produk: foto full-bleed di atas, info di bawah mengalir,
 * bar bawah split (stepper | tambah). Tanpa kartu dan tanpa teks palsu.
 */
internal fun JajaninView.drawProductDetail(c: Canvas, w: Float, h: Float) {
    val product = selectedProduct ?: run { goBack(); return }
    c.drawColor(kit.bg)
    val photoBottom = minOf(h * .38f, 300f)
    drawProductImage(c, product, RectF(0f, 0f, w, photoBottom), 0f)
    val back = RectF(16f, 34f, 56f, 74f)
    kit.rounded(c, back, Color.argb(220, 255, 255, 255), 20f)
    kit.centerText(c, "‹", back, 26f, kit.ink, true)
    bounds["headerBack"] = RectF(0f, 0f, 72f, 100f)

    val available = product.available && (product.stock == null || product.stock > 0)
    var y = photoBottom + 28f
    kit.text(c, normalizeCategory(product.category), 22f, y, 12f, kit.blue, true)
    y += 30f
    kit.text(c, product.name.take(30), 22f, y, 22f, kit.ink, true)
    y += 32f
    kit.text(c, formatRupiah(product.priceIdr), 22f, y, 19f, kit.ink, true)
    val stockText = if (available) product.stock?.let { "Stok $it" } ?: "Tersedia" else "Habis"
    kit.text(c, stockText, 368f - kit.measure(stockText, 12f, true), y, 12f, if (available) kit.success else kit.danger, true)
    y += 24f
    kit.line(c, 22f, y, 368f, y)
    y += 28f
    val desc = product.description.ifBlank { "Belum ada deskripsi." }
    kit.text(c, "Tentang", 22f, y, 13f, kit.ink, true)
    y += 22f
    desc.chunked(42).take(4).forEach { line ->
        kit.text(c, line, 22f, y, 12f, kit.muted)
        y += 19f
    }
    if (product.ingredients.isNotBlank()) {
        y += 6f
        kit.text(c, "Bahan: ${product.ingredients.take(72)}", 22f, y, 12f, kit.muted)
    }

    // Bar bawah: stepper + CTA total.
    val barTop = h - 78f
    kit.line(c, 0f, barTop, w, barTop)
    val step = RectF(20f, barTop + 12f, 152f, h - 18f)
    kit.rounded(c, step, kit.tint, 12f)
    kit.centerText(c, "−", RectF(20f, barTop + 12f, 64f, h - 18f), 22f, kit.blue, true)
    kit.centerText(c, quantity.toString(), RectF(64f, barTop + 12f, 108f, h - 18f), 15f, kit.ink, true)
    kit.centerText(c, "+", RectF(108f, barTop + 12f, 152f, h - 18f), 22f, kit.blue, true)
    bounds["minus"] = RectF(20f, barTop + 12f, 64f, h - 18f)
    bounds["plus"] = RectF(108f, barTop + 12f, 152f, h - 18f)
    val cta = RectF(162f, barTop + 12f, 370f, h - 18f)
    if (available) {
        kit.button(c, "Tambah · ${formatRupiah(product.priceIdr * quantity)}", cta, kit.blue, 12f)
        bounds["detailAdd"] = cta
    } else {
        kit.rounded(c, cta, kit.muted, 12f)
        kit.centerText(c, "Stok habis", cta, 14f, Color.WHITE, true)
    }
}

/**
 * Hitung mundur blackout dari jam server WIB: (jam buka kembali, sisa menit).
 * Null bila tidak blackout atau jam server belum sinkron.
 */
internal fun JajaninView.blackoutReopen(): Pair<Int, Int>? {
    if (!orderingBlocked) return null
    val now = serverNowMillis()
    if (now == 0L) return null
    val tz = java.util.TimeZone.getTimeZone("Asia/Jakarta")
    val hour = java.util.Calendar.getInstance(tz).apply { timeInMillis = now }
        .get(java.util.Calendar.HOUR_OF_DAY)
    val endHour = when (hour) {
        9 -> 10
        12 -> 13
        else -> return null
    }
    val end = java.util.Calendar.getInstance(tz).apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, endHour)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val mins = ((end - now).coerceAtLeast(0) / 60000).toInt()
    return endHour to mins
}

/**
 * Hitung mundur blackout dari jam server WIB: "Buka kembali pukul 10:00 WIB
 * · 12 mnt lagi". Null bila tidak blackout atau jam server belum sinkron.
 */
private fun JajaninView.blackoutReopenLabel(): String? {
    val (endHour, mins) = blackoutReopen() ?: return null
    return "Buka kembali pukul %02d:00 WIB · %d mnt lagi".format(endHour, mins)
}

private fun JajaninView.summaryRow(c: Canvas, a: String, b: String, y: Float, strong: Boolean = false) {    kit.text(c, a, 24f, y, 13f, if (strong) kit.ink else kit.muted, strong)
    kit.text(c, b, 368f - kit.measure(b, 13f, true), y, 13f, kit.ink, true)
}

private fun JajaninView.drawQr(c: Canvas, r: RectF) {
    val n = 25
    val cell = r.width() / n
    for (row in 0 until n) for (col in 0 until n) {
        val finder = (col < 7 && row < 7) || (col > n - 8 && row < 7) || (col < 7 && row > n - 8)
        val insideFinder = finder && (col % (n - 1) < 7) && (row % (n - 1) < 7)
        val drawn = (insideFinder && (col % (n - 1) in 0..1 || col % (n - 1) == 5 || row % (n - 1) in 0..1 || row % (n - 1) == 5 || (col % (n - 1) in 2..4 && row % (n - 1) in 2..4))) ||
            (!finder && ((row * 17 + col * 13 + row * col) % 7 < 3))
        if (drawn) {
            kit.rect(c, r.left + col * cell, r.top + row * cell, r.left + (col + 1) * cell, r.top + (row + 1) * cell, kit.ink)
        }
    }
}
