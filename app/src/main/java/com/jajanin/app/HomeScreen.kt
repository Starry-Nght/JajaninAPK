package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

private const val NAV_HEIGHT = 67f

/**
 * Beranda pembeli: dirancang seperti papan menu kantin, bukan dashboard.
 * Bar atas ramping, sapaan personal satu baris, status live sebaris teks,
 * cari berupa garis bawah, kategori berupa tab teks, menu berupa grid foto
 * 2 kolom dengan tombol tambah kecil per ubin.
 */
internal fun JajaninView.drawHome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    // --- Bar atas ramping: wordmark + lonceng + tas. Tanpa kartu header. ---
    kit.text(c, "Jajanin", 20f, 44f, 18f, kit.ink, true)
    val cartCount = cart.values.sum()
    val cartLabel = if (cartCount > 0) "Tas · $cartCount" else "Tas"
    val cartW = kit.measure(cartLabel, 12f, true) + 32f
    val cartButton = RectF(w - 20f - cartW, 18f, w - 20f, 56f)
    kit.rounded(c, cartButton, if (cartCount > 0) kit.blue else kit.tint, 19f)
    kit.centerText(c, cartLabel, cartButton, 12f, if (cartCount > 0) Color.WHITE else kit.blue, true)
    bounds["cartCheckout"] = cartButton
    val inboxButton = RectF(cartButton.left - 52f, 18f, cartButton.left - 12f, 56f)
    kit.rounded(c, inboxButton, kit.tint, 19f)
    drawIcon(c, "bell", inboxButton.centerX(), inboxButton.centerY(), 15f, kit.blue)
    bounds["inbox"] = inboxButton
    val unread = inboxUnreadCount()
    if (unread > 0) {
        val badgeText = if (unread > 9) "9+" else unread.toString()
        val badge = RectF(inboxButton.right - 13f, inboxButton.top - 5f, inboxButton.right + 9f, inboxButton.top + 15f)
        kit.rounded(c, badge, kit.danger, 10f)
        kit.centerText(c, badgeText, badge, 9f, Color.WHITE, true)
    }

    // --- Sapaan + status live sebaris. Bukan strip/banner. ---
    val firstName = myDisplayName.takeIf { it.isNotBlank() }?.substringBefore(' ')?.take(14) ?: "Kamu"
    kit.text(c, "Mau jajan apa, $firstName?", 20f, 90f, 20f, kit.ink, true)
    val suspended = myAccountStatus == "suspended"
    val dotColor = if (suspended) kit.danger else if (orderingBlocked) Color.rgb(183, 116, 0) else kit.success
    val reopen = blackoutReopen()
    val statusText = if (suspended) {
        "Akun ditangguhkan"
    } else if (reopen != null) {
        "Tutup s/d %02d:00 · buka %d mnt lagi".format(reopen.first, reopen.second)
    } else if (orderingStatusLoading) {
        "${serverClockLabel()} · memuat..."
    } else {
        "${serverClockLabel()} · pesanan $ordersToday/2"
    }
    kit.text(c, "●", 20f, 112f, 10f, dotColor, true)
    kit.text(c, statusText, 34f, 112f, 11f, if (suspended || orderingBlocked) dotColor else kit.muted)
    if (currentScreen == Screen.HOME) postInvalidateDelayed(1000)

    // --- Cari: baris garis-bawah, bukan kartu. ---
    kit.text(c, "⌕", 22f, 146f, 22f, kit.muted)
    kit.text(c, if (query.isBlank()) "Cari menu..." else query, 52f, 144f, 14f, if (query.isBlank()) kit.muted else kit.ink)
    kit.line(c, 20f, 158f, w - 20f, 158f)
    bounds["search"] = RectF(20f, 118f, w - 20f, 158f)

    // --- Kategori: tab teks dengan garis penanda, bukan pil. ---
    var x = 20f
    listOf("Semua", "Makanan", "Minuman").forEach { label ->
        val key = if (label == "Semua") "All" else if (label == "Makanan") "Food" else "Drinks"
        val active = activeCategory == key
        val tw = kit.measure(label, 13f, true)
        kit.text(c, label, x, 188f, 13f, if (active) kit.blue else kit.muted, active)
        if (active) kit.rect(c, x, 194f, x + tw, 196f, kit.blue)
        bounds["cat:$key"] = RectF(x - 6f, 164f, x + tw + 6f, 202f)
        x += tw + 26f
    }

    val matches = products.filter { product ->
        (activeCategory == "All" || normalizeCategory(product.category) == activeCategory) &&
            (query.isBlank() || product.name.contains(query.trim(), ignoreCase = true))
    }
    kit.text(c, "Menu hari ini · ${matches.size}", 20f, 226f, 11f, kit.muted, true)

    when {
        productsLoading && products.isEmpty() -> kit.centerText(c, "Memuat menu...", RectF(20f, 260f, 370f, 320f), 14f, kit.muted)
        productsError != null && products.isEmpty() -> kit.centerText(c, "Menu gagal dimuat. Coba lagi sebentar.", RectF(20f, 260f, 370f, 320f), 14f, kit.danger)
        matches.isEmpty() -> kit.centerText(c, if (query.isBlank()) "Belum ada menu hari ini" else "Nggak ketemu. Coba kata lain.", RectF(20f, 260f, 370f, 320f), 14f, kit.muted)
    }

    // --- Grid foto 2 kolom. Tiap ubin: foto, nama, harga + tombol tambah mungil. ---
    val tileW = (w - 20f * 2f - 12f) / 2f
    val photoH = 118f
    val tileH = photoH + 66f
    val gridTop = 240f
    matches.forEachIndexed { index, product ->
        val col = index % 2
        val row = index / 2
        val left = 20f + col * (tileW + 12f)
        val top = gridTop + row * (tileH + 14f) - scroll
        if (top > h || top + tileH < 240f) return@forEachIndexed
        drawProductImage(c, product, RectF(left, top, left + tileW, top + photoH), 12f)
        val available = product.available && (product.stock == null || product.stock > 0)
        if (!available) {
            kit.rounded(c, RectF(left + 8f, top + 8f, left + 62f, top + 30f), Color.argb(200, 20, 25, 40), 8f)
            kit.centerText(c, "Habis", RectF(left + 8f, top + 8f, left + 62f, top + 30f), 10f, Color.WHITE, true)
        } else if (product.stock != null && product.stock <= 3) {
            kit.rounded(c, RectF(left + 8f, top + 8f, left + 96f, top + 30f), Color.argb(200, 20, 25, 40), 8f)
            kit.centerText(c, "Sisa ${product.stock}", RectF(left + 8f, top + 8f, left + 96f, top + 30f), 10f, Color.WHITE, true)
        }
        kit.text(c, product.name.take(21), left + 2f, top + photoH + 22f, 13f, kit.ink, true)
        kit.text(c, formatRupiah(product.priceIdr), left + 2f, top + photoH + 43f, 13f, kit.ink, true)
        if (available) {
            val addLabel = "+ Tambah"
            val addW = kit.measure(addLabel, 11f, true) + 24f
            val add = RectF(left + tileW - addW, top + photoH + 22f, left + tileW, top + photoH + 50f)
            kit.rounded(c, add, kit.tint, 14f)
            kit.centerText(c, addLabel, add, 11f, kit.blue, true)
            bounds["add:${product.id}"] = add
        }
        bounds["item:${product.id}"] = RectF(left, top.coerceAtLeast(240f), left + tileW, (top + tileH).coerceAtMost(h - NAV_HEIGHT))
    }
    contentHeight = gridTop + (matches.size + 1) / 2 * (tileH + 14f)
    drawBottomNav(c, w, h, "Home")
}

internal fun normalizeCategory(value: String): String = when (value.lowercase().trim()) {
    "food", "makanan" -> "Food"
    "drink", "drinks", "minuman" -> "Drinks"
    else -> value
}

internal fun JajaninView.drawProductImage(c: Canvas, product: ProductRow, rect: RectF, radius: Float) {
    val url = product.imageUrl?.takeIf { it.startsWith("https://") }
    val bitmap = url?.let { ProductImageCache.images[it] }
    if (bitmap == null) {
        kit.rounded(c, rect, kit.tint, radius)
        val initial = (product.name.firstOrNull() ?: 'J').uppercaseChar().toString()
        kit.centerText(c, initial, rect, 30f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
        return
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val path = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
    val save = c.save()
    c.clipPath(path)
    c.drawBitmap(bitmap, null, rect, paint)
    c.restoreToCount(save)
}
