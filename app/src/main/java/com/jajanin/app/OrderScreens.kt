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
        drawBottomNav(c, w, h, "Orders")
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
    // QRIS hanya ditawarkan bila warung sudah pasang (null = belum dicek).
    val stallQris = cartStallQris
    val qrisOffered = stallQris == null || stallQris.isNotBlank()
    val cashBox = if (qrisOffered) RectF(18f, y + 38f, 190f, y + 96f) else RectF(18f, y + 38f, 372f, y + 96f)
    val cashOn = checkoutPayment == "cash" || !qrisOffered
    kit.rounded(c, cashBox, kit.card, 13f, if (cashOn) kit.blue else kit.border)
    kit.text(c, "Tunai", 34f, y + 63f, 13f, kit.ink, true)
    kit.text(c, if (qrisOffered) "Bayar saat ambil" else "Bayar saat ambil · satu-satunya metode", 34f, y + 83f, 10f, kit.muted)
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

    val cta = RectF(20f, h - 88f - 56f, 370f, h - 88f)
    if (orderingBlocked) {
        // Blackout WIB (status dari server): tombol checkout nonaktif + alasan jelas.
        val lines = ArrayList((orderingBlockMessage ?: "Pesanan sementara ditutup.").chunked(38))
        blackoutReopenLabel()?.let { lines.add(it) }
        val boxTop = cta.top - 20f - lines.size * 20f
        kit.rounded(c, RectF(20f, boxTop, 370f, cta.top - 8f), kit.warnBox, 12f, kit.border)
        lines.forEachIndexed { index, line -> kit.text(c, line, 32f, boxTop + 25f + index * 20f, 11f, kit.ink) }
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
        kit.button(c, if (checkoutPayment == "cash") "Buat pesanan · bayar tunai" else "Buat pesanan · bayar QRIS", cta)
        bounds["pay"] = RectF(cta)
    }
    drawBottomNav(c, w, h, "Orders")
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
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                val path = android.graphics.Path().apply { addRoundRect(qr, 12f, 12f, android.graphics.Path.Direction.CW) }
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

/** Highlight sentuhan untuk kontrol; dipakai lintas file layar. */
internal fun JajaninView.press(c: Canvas, key: String, r: RectF, radius: Float = 12f) {
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

/**
 * Navigasi kaca melayang 5 tab khusus penjual (kunci hit "stab:<label>").
 * Lencana aksi di tab Notifikasi dipertahankan, digambar di atas bar.
 */
internal fun JajaninView.drawAdminNav(c: Canvas, w: Float, h: Float, active: String) {
    drawFloatingNav(
        c, w, h,
        listOf(
            FloatNavItem("Dashboard", "grid", false),
            FloatNavItem("Pesanan", "receipt", false),
            FloatNavItem("Produk", "box", false),
            FloatNavItem("Notifikasi", "bell", false),
            FloatNavItem("Profil", "person", false),
        ),
        active, "stab:",
    )
    val notifCount = adminActionableCount()
    if (notifCount > 0) {
        val bar = RectF(GLASS_MARGIN, h - GLASS_TOP_OFFSET, w - GLASS_MARGIN, h - GLASS_BOTTOM)
        val slot = bar.width() / 5
        val cx = bar.left + 3 * slot + slot / 2f
        val badgeText = if (notifCount > 9) "9+" else notifCount.toString()
        val badge = RectF(cx + 4f, bar.top - 8f, cx + 26f, bar.top + 10f)
        kit.rounded(c, badge, kit.danger, 9f)
        kit.centerText(c, badgeText, badge, 8f, Color.WHITE, true)
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
    val sectionGap = 26f
    val rowPad = 10f
    val omzetTop = 126f + 2 * 72f + 10f
    val salesEnd = omzetTop + 58f
    val prodTop = salesEnd + sectionGap + rowPad
    val shownProducts = sellerProducts.take(6)
    val productsEnd = prodTop + maxOf(shownProducts.size * 58f, 58f)
    val ordersStart = productsEnd + 60f

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
    val rowHeight = 172f
    val listTop = ordersStart + 24f
    val contentTotal = listTop + filtered.size * rowHeight + 170f
    if (pendingScrollToOrders) {
        pendingScrollToOrders = false
        val maxScroll = (contentTotal - h + 78f).coerceAtLeast(0f)
        scroll = (ordersStart - 96f).coerceAtLeast(0f).coerceAtMost(maxScroll)
    }
    val off = -scroll

    kit.text(c, "Ringkasan hari ini", 20f, 112f + off, 17f, kit.ink, true)
    val cards = listOf(
        listOf("Pesanan baru", pending.toString(), kit.tint, "inbox"),
        listOf("Disiapkan", (confirmed + preparing).toString(), kit.warnBox, "clock"),
        listOf("Siap diambil", ready.toString(), kit.warnSoft, "check"),
        listOf("Selesai", completed.toString(), kit.successSoft, "checkCircle"),
    )
    cards.forEachIndexed { index, card ->
        val col = index % 2
        val row = index / 2
        val left = 20f + col * 179f
        val top = 126f + row * 72f + off
        if (top + 62f < 86f || top > h) return@forEachIndexed
        kit.card(c, RectF(left, top, left + 169f, top + 62f), 15f)
        kit.rounded(c, RectF(left + 12f, top + 12f, left + 46f, top + 46f), card[2] as Int, 12f)
        drawIcon(c, card[3] as String, left + 29f, top + 29f, 17f, kit.blue)
        kit.text(c, card[0] as String, left + 56f, top + 25f, 11f, kit.muted)
        kit.text(c, card[1] as String, left + 56f, top + 47f, 18f, kit.ink, true)
    }

    kit.rounded(c, RectF(20f, omzetTop + off, 370f, omzetTop + 58f + off), kit.blue, 15f)
    kit.text(c, "Omzet hari ini", 36f, omzetTop + 25f + off, 11f, Color.WHITE)
    kit.text(c, formatRupiah(revenue), 36f, omzetTop + 47f + off, 17f, Color.WHITE, true)
    val omzetSub = "$pending baru · ${confirmed + preparing} disiapkan · $ready siap"
    kit.text(c, omzetSub, 366f - kit.measure(omzetSub, 9f), omzetTop + 36f + off, 9f, Color.WHITE)
    kit.text(c, "Produk saya", 20f, salesEnd + sectionGap + off, 17f, kit.ink, true)
    if (shownProducts.isEmpty()) {
        val boxY = prodTop + off
        val box = RectF(20f, boxY, 370f, boxY + 58f)
        kit.rounded(c, box, kit.card, 12f, kit.border)
        kit.text(c, "Belum ada produk.", 32f, boxY + 24f, 11f, kit.ink, true)
        kit.text(c, "Ketuk + di bawah untuk menambah menu pertama.", 32f, boxY + 42f, 10f, kit.muted)
    }
    shownProducts.forEachIndexed { index, product ->
        val py = prodTop + index * 58f + off
        if (py + 52f < 86f || py > h) return@forEachIndexed
        kit.card(c, RectF(20f, py, 370f, py + 52f), 13f)
        kit.text(c, product.name.take(24), 32f, py + 21f, 12f, kit.ink, true)
        val priceStock = "${formatRupiah(product.priceIdr)}${product.stock?.let { " · Stok $it" }.orEmpty()}"
        kit.text(c, priceStock.take(30), 32f, py + 39f, 10f, kit.muted)
        val dotColor = if (product.available) kit.success else kit.muted
        kit.rounded(c, RectF(196f, py + 14f, 204f, py + 22f), dotColor, 4f)
        val edit = RectF(250f, py + 8f, 304f, py + 44f)
        kit.rounded(c, edit, kit.tint, 11f)
        kit.centerText(c, "Edit", edit, 10f, kit.blue, true)
        press(c, "productEdit:${product.id}", edit, 11f)
        bounds.putVisible("productEdit:${product.id}", edit, bottomLimit = h - 78f)
        val toggle = RectF(308f, py + 8f, 362f, py + 44f)
        kit.rounded(c, toggle, if (product.available) kit.successSoft else kit.bg, 11f, if (product.available) null else kit.border)
        kit.centerText(c, if (product.available) "Aktif" else "Mati", toggle, 10f, if (product.available) kit.success else kit.muted, true)
        press(c, "productToggle:${product.id}", toggle, 11f)
        bounds.putVisible("productToggle:${product.id}", toggle, bottomLimit = h - 78f)
        val delete = RectF(206f, py + 8f, 244f, py + 44f)
        drawIcon(c, "close", delete.centerX(), delete.centerY(), 13f, kit.danger)
        press(c, "productDelete:${product.id}", delete, 11f)
        bounds.putVisible("productDelete:${product.id}", delete, bottomLimit = h - 78f)
    }

    kit.text(c, "Pesanan masuk", 20f, productsEnd + 22f + off, 17f, kit.ink, true)
    val filters = listOf("Semua", "Baru", "Disiapkan", "Siap", "Selesai")
    var x = 20f
    filters.forEach { filter ->
        // Chip "Baru" memuat jumlahnya supaya pesanan yang menunggu langsung terbaca.
        val labelText = if (filter == "Baru" && pending > 0) "Baru ($pending)" else filter
        val width = kit.measure(labelText, 11f, true) + 32f
        val chip = RectF(x, ordersStart - 24f + off, x + width, ordersStart + 8f + off)
        val active = adminFilter == filter
        kit.rounded(c, chip, if (active) kit.blue else kit.card, 16f, if (active) null else kit.border)
        kit.centerText(c, labelText, chip, 11f, if (active) Color.WHITE else kit.ink, true)
        press(c, "adminFilter:$filter", chip, 16f)
        bounds.putVisible("adminFilter:$filter", chip, bottomLimit = h - 78f)
        x += width + 8f
    }

    filtered.forEachIndexed { index, order ->
        val top = listTop + index * rowHeight + off
        if (top + 156f < 86f || top > h - 78f) return@forEachIndexed
        val card = RectF(20f, top, 370f, top + 156f)
        kit.card(c, card, 22f)
        // Aksen status berupa bar inset (menghormati sudut kartu).
        val stripeColor = when (order.status.lowercase()) {
            STATUS_PENDING -> Color.rgb(183, 116, 0)
            STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
            STATUS_READY -> kit.success
            else -> kit.border
        }
        kit.rounded(c, RectF(30f, top + 18f, 36f, top + 138f), stripeColor, 3f)
        kit.text(c, order.buyerName.take(18), 48f, top + 27f, 13f, kit.ink, true)
        kit.text(c, order.timeLabel, 350f - kit.measure(order.timeLabel, 11f), top + 27f, 11f, kit.muted)
        kit.text(c, "${order.itemCount} item  ·  ${order.itemsSummary.take(28)}", 48f, top + 49f, 10f, kit.muted)
        kit.text(c, "Kode ${order.pickupCode}", 48f, top + 73f, 11f, kit.blue, true)
        val total = formatRupiah(order.totalIdr)
        kit.text(c, total, 350f - kit.measure(total, 12f, true), top + 73f, 12f, kit.ink, true)
        kit.text(c, order.paymentMethod?.takeIf { it.isNotBlank() } ?: "QRIS", 48f, top + 97f, 10f, kit.muted)
        // Pill status di kanan agar tidak lagi tertimpa tombol aksi di baris bawah.
        val (label, color) = statusLabel(order.status)
        statusPill(c, kit, label, color, 350f - (kit.measure(label, 10f, true) + 22f), top + 83f)
        bounds.putVisible("adminOrder:${order.id}", card, bottomLimit = h)
        val nextAction = statusNextAction(order.status)
        val actionY0 = top + 112f
        val actionY1 = top + 140f
        if (nextAction != null) {
            val action = RectF(198f, actionY0, 350f, actionY1)
            kit.rounded(c, action, kit.tint, 12f)
            kit.centerText(c, nextAction.first, action, 10f, kit.blue, true)
            press(c, "adminStatus:${order.id}", action, 12f)
            bounds.putVisible("adminStatus:${order.id}", action, bottomLimit = h - 78f)
        } else if (!isTerminalStatus(order.status)) {
            val action = RectF(198f, actionY0, 350f, actionY1)
            kit.rounded(c, action, kit.tint, 12f)
            kit.centerText(c, statusLabelShort(order.status), action, 10f, kit.blue, true)
        }
        if (!isTerminalStatus(order.status)) {
            val cancel = RectF(48f, actionY0, 190f, actionY1)
            kit.rounded(c, cancel, kit.card, 12f, kit.dangerBorder)
            kit.centerText(c, "Batalkan", cancel, 10f, kit.danger, true)
            press(c, "adminCancel:${order.id}", cancel, 12f)
            bounds.putVisible("adminCancel:${order.id}", cancel, bottomLimit = h - 78f)
        }
    }

    val emptyTop = listTop + 8f + off
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

    // --- Header kaca melayang (fixed, stabil saat slide; konten mengintip di sela) ---
    c.save()
    c.translate(-slideX, 0f)
    val sellerBar = RectF(16f, 12f, 374f, 76f)
    kit.rounded(c, RectF(sellerBar.left, sellerBar.top + 2f, sellerBar.right, sellerBar.bottom + 3f), kit.glassShadow, 20f)
    kit.rounded(c, sellerBar, kit.glass, 20f, kit.glassBorder)
    kit.text(c, myStallName.takeIf { it.isNotBlank() }?.take(16) ?: "Dashboard Penjual", 32f, 42f, 18f, Color.WHITE, true)
    kit.text(c, adminDateFormat.format(java.util.Date()), 32f, 62f, 11f, Color.argb(200, 255, 255, 255))
    val refresh = RectF(224f, 22f, 266f, 64f)
    kit.rounded(c, refresh, kit.tint, 14f)
    drawIcon(c, "refresh", refresh.centerX(), refresh.centerY(), 18f, kit.blue)
    press(c, "adminRefresh", refresh, 14f)
    bounds["adminRefresh"] = RectF(refresh)
    val bell = RectF(270f, 22f, 314f, 64f)
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
    val avatar = RectF(322f, 22f, 362f, 62f)
    kit.rounded(c, avatar, kit.tint, 20f)
    val initial = (myStallName.firstOrNull() ?: SupabaseRepository.currentEmail()?.firstOrNull() ?: 'J').uppercaseChar().toString()
    kit.centerText(c, initial, avatar, 17f, kit.blue, true)
    press(c, "adminProfile", avatar, 20f)
    bounds["adminProfile"] = RectF(avatar)
    c.restore()

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
            kit.card(c, card1, 16f)
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
            kit.card(c, RectF(20f, itemsTop + off, 370f, card2Bottom + off), 16f)
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
            kit.card(c, RectF(20f, totalTop + off, 370f, totalTop + 56f + off), 14f)
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
            kit.card(c, RectF(20f, payTop + off, 370f, payTop + payH + off), 14f)
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

    // Header detail: toolbar kompak yang sama dengan layar dalam lain.
    header(c, "Detail Pesanan")
}

internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Profil")
    kit.card(c, RectF(18f, 92f, 372f, 192f), 16f)
    val avatar = RectF(34f, 112f, 94f, 172f)
    kit.rounded(c, avatar, kit.tint, 30f)
    // Seller: tampilkan nama toko, bukan email akun. Buyer: nama asli akun.
    val titleText = if (isSellerProfile) {
        myStallName.takeIf { it.isNotBlank() }?.take(24) ?: "Memuat toko..."
    } else {
        myDisplayName.takeIf { it.isNotBlank() }?.take(24) ?: "Pengguna Jajanin"
    }
    kit.centerText(c, (titleText.firstOrNull() ?: 'A').uppercaseChar().toString(), avatar, 25f, kit.blue, true)
    kit.text(c, titleText, 110f, 133f, 17f, kit.ink, true)
    kit.text(c, SupabaseRepository.currentEmail()?.take(34) ?: "Belum masuk", 110f, 156f, 12f, kit.muted)
    val suspended = myAccountStatus == "suspended"
    kit.text(c, if (suspended) "Status: Ditangguhkan" else "Status: Aktif", 110f, 178f, 11f, if (suspended) kit.danger else kit.success, true)

    // Baris info: Bantuan + Tentang + Info Penting (ketuk = halaman sendiri).
    var y = 216f
    listOf(
        "Bantuan" to "help",
        "Tentang Jajanin" to "about",
        "INFORMASI PENTING" to "infoPage",
    ).forEach { (title, key) ->
        kit.rounded(c, RectF(18f, y, 372f, y + 50f), kit.card, 13f, kit.border)
        kit.text(c, title, 34f, y + 31f, 14f, kit.ink)
        drawIcon(c, "chevronRight", 349f, y + 25f, 15f, kit.muted)
        bounds[key] = RectF(18f, y, 372f, y + 50f)
        y += 62f
    }

    // Kotak masuk developer: peringatan + pengumuman, badge bila ada yang belum dibaca.
    val inboxY = y
    kit.rounded(c, RectF(18f, inboxY, 372f, inboxY + 50f), kit.card, 13f, kit.border)
    kit.text(c, "Notifikasi", 34f, inboxY + 22f, 14f, kit.ink)
    val unread = inboxUnreadCount()
    kit.text(c, if (unread > 0) "$unread belum dibaca" else "Tidak ada pesan baru", 34f, inboxY + 40f, 10f, if (unread > 0) kit.danger else kit.success, true)
    drawIcon(c, "chevronRight", 349f, inboxY + 25f, 15f, kit.muted)
    bounds["inbox"] = RectF(18f, inboxY, 372f, inboxY + 50f)

    // Pilih tema (berlaku untuk buyer, seller, dan developer).
    val themeY = inboxY + 58f
    kit.rounded(c, RectF(18f, themeY, 372f, themeY + 50f), kit.card, 13f, kit.border)
    kit.text(c, "Tema aplikasi", 34f, themeY + 22f, 14f, kit.ink)
    kit.text(c, themeLabel, 34f, themeY + 40f, 10f, kit.blue, true)
    drawIcon(c, "chevronRight", 349f, themeY + 25f, 15f, kit.muted)
    bounds["theme"] = RectF(18f, themeY, 372f, themeY + 50f)

    var logoutTop = themeY + 58f + 18f
    if (isSellerProfile) {
        // Button ubah nama toko (seller saja).
        val sy = themeY + 58f
        kit.rounded(c, RectF(18f, sy, 372f, sy + 50f), kit.card, 13f, kit.border)
        kit.text(c, "Nama toko", 34f, sy + 22f, 14f, kit.ink)
        kit.text(c, "${myStallName.takeIf { it.isNotBlank() } ?: "Belum diisi"} · Ubah", 34f, sy + 40f, 10f, kit.blue, true)
        drawIcon(c, "chevronRight", 349f, sy + 25f, 15f, kit.muted)
        press(c, "profileStallName", RectF(18f, sy, 372f, sy + 50f), 13f)
        bounds["profileStallName"] = RectF(18f, sy, 372f, sy + 50f)

        val qy = sy + 58f
        kit.rounded(c, RectF(18f, qy, 372f, qy + 50f), kit.card, 13f, kit.border)
        kit.text(c, "QRIS warung saya", 34f, qy + 22f, 14f, kit.ink)
        kit.text(c, if (myStallQris.isBlank()) "Belum dipasang" else "Terpasang ✓", 34f, qy + 40f, 10f, if (myStallQris.isBlank()) kit.danger else kit.success, true)
        drawIcon(c, "chevronRight", 349f, qy + 25f, 15f, kit.muted)
        press(c, "profileQris", RectF(18f, qy, 372f, qy + 50f), 13f)
        bounds["profileQris"] = RectF(18f, qy, 372f, qy + 50f)
        logoutTop = qy + 50f + 18f
    }

    val logoutRow = RectF(18f, logoutTop, 372f, logoutTop + 54f)
    kit.rounded(c, logoutRow, kit.card, 14f, kit.dangerBorder)
    kit.centerText(c, "Keluar dari akun", logoutRow, 14f, kit.danger, true)
    press(c, "logout", logoutRow, 14f)
    bounds["logout"] = logoutRow
    if (isSellerProfile) drawAdminNav(c, w, h, "Profil") else drawBottomNav(c, w, h, "Profile")
}

internal fun JajaninView.drawProductDetail(c: Canvas, w: Float, h: Float) {
    val product = selectedProduct ?: run { goBack(); return }
    c.drawColor(kit.bg)
    kit.rect(c, 0f, 0f, w, 70f, kit.card)
    drawIcon(c, "chevronLeft", 27f, 35f, 24f, kit.blue)
    kit.text(c, "Detail Produk", 60f, 45f, 19f, kit.ink, true)
    bounds["headerBack"] = RectF(0f, 0f, 54f, 70f)
    drawProductImage(c, product, RectF(20f, 86f, 370f, minOf(h * .43f, 335f)), 18f)
    kit.text(c, normalizeCategory(product.category), 22f, minOf(h * .43f, 335f) + 28f, 12f, kit.blue, true)
    kit.text(c, product.name.take(31), 22f, minOf(h * .43f, 335f) + 62f, 22f, kit.ink, true)
    kit.text(c, formatRupiah(product.priceIdr), 22f, minOf(h * .43f, 335f) + 91f, 19f, kit.ink, true)
    kit.text(c, "Deskripsi", 22f, minOf(h * .43f, 335f) + 131f, 14f, kit.ink, true)
    val lines = product.description.ifBlank { "Belum ada deskripsi produk." }.chunked(43).take(3)
    lines.forEachIndexed { index, line -> kit.text(c, line, 22f, minOf(h * .43f, 335f) + 154f + index * 19f, 12f, kit.muted) }
    if (product.ingredients.isNotBlank()) {
        kit.text(c, "Bahan: ${product.ingredients.take(80)}", 22f, minOf(h * .43f, 335f) + 154f + lines.size * 19f, 12f, kit.muted)
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
    val action = RectF(20f, h - 74f, 370f, h - 18f)
    kit.button(c, if (available) "Tambah ke keranjang" else "Stok habis", action, if (available) kit.blue else kit.muted)
    if (available) bounds["detailAdd"] = action
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
