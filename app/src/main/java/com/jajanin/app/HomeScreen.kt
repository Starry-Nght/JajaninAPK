package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

private const val NAV_HEIGHT = 67f

internal fun JajaninView.drawHome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.rect(c, 0f, 0f, w, 74f, kit.card)
    kit.text(c, "Halo, Jajanin!", 20f, 40f, 22f, kit.ink, true)
    kit.text(c, "Temukan makanan favoritmu hari ini", 20f, 61f, 11f, kit.muted)
    val cartButton = RectF(319f, 18f, 370f, 58f)
    kit.rounded(c, cartButton, kit.tint, 14f)
    kit.centerText(c, "Tas ${cart.values.sum()}", cartButton, 10f, kit.blue, true)
    bounds["cartCheckout"] = cartButton
    // Inbox developer (peringatan/pengumuman) dengan badge belum dibaca.
    val inboxButton = RectF(262f, 18f, 312f, 58f)
    kit.rounded(c, inboxButton, kit.tint, 14f)
    drawIcon(c, "bell", inboxButton.centerX(), inboxButton.centerY(), 16f, kit.blue)
    bounds["inbox"] = inboxButton
    val unread = inboxUnreadCount()
    if (unread > 0) {
        val badgeText = if (unread > 9) "9+" else unread.toString()
        val badge = RectF(inboxButton.right - 14f, inboxButton.top - 6f, inboxButton.right + 8f, inboxButton.top + 14f)
        kit.rounded(c, badge, kit.danger, 10f)
        kit.centerText(c, badgeText, badge, 9f, Color.WHITE, true)
    }

    val search = RectF(20f, 112f, 370f, 158f)
    kit.rounded(c, search, kit.card, 14f, kit.border)
    kit.text(c, "⌕", 34f, 142f, 23f, kit.muted)
    kit.text(c, if (query.isBlank()) "Cari nama menu..." else query, 68f, 140f, 14f, if (query.isBlank()) kit.muted else kit.ink)
    bounds["search"] = search

    // Strip status: jam server live + jatah harian; banner amber saat blackout,
    // merah saat akun ditangguhkan.
    val strip = RectF(20f, 80f, 370f, 106f)
    val suspended = myAccountStatus == "suspended"
    if (suspended) {
        kit.rounded(c, strip, kit.dangerBorder, 13f, kit.border)
    } else if (orderingBlocked) {
        kit.rounded(c, strip, kit.warnBox, 13f, kit.border)
    }
    kit.text(c, "●", 32f, 99f, 11f, if (suspended) kit.danger else if (orderingBlocked) Color.rgb(183, 116, 0) else kit.success, true)
    kit.text(c, "${serverClockLabel()} · live", 46f, 99f, 11f, kit.ink)
    val reopen = blackoutReopen()
    val rightText = if (suspended) {
        "Akun ditangguhkan"
    } else if (reopen != null) {
        "Tutup s/d %02d:00 · %d mnt lagi".format(reopen.first, reopen.second)
    } else if (orderingStatusLoading) {
        "Memuat..."
    } else {
        "Pesanan: $ordersToday/2"
    }
    kit.text(c, rightText, 358f - kit.measure(rightText, 11f, true), 99f, 11f, if (suspended) kit.danger else if (orderingBlocked) Color.rgb(183, 116, 0) else kit.blue, true)
    if (currentScreen == Screen.HOME) postInvalidateDelayed(1000)

    kit.text(c, "Kategori", 20f, 184f, 16f, kit.ink, true)
    var x = 20f
    listOf("All", "Food", "Drinks").forEach { category ->
        val width = kit.measure(category, 12f, true) + 30f
        val chip = RectF(x, 196f, x + width, 230f)
        val active = activeCategory == category
        kit.rounded(c, chip, if (active) kit.blue else kit.card, 17f, if (active) null else kit.border)
        kit.centerText(c, category, chip, 12f, if (active) Color.WHITE else kit.ink, true)
        bounds["cat:$category"] = chip
        x += width + 9f
    }

    val matches = products.filter { product ->
        (activeCategory == "All" || normalizeCategory(product.category) == activeCategory) &&
            (query.isBlank() || product.name.contains(query.trim(), ignoreCase = true))
    }
    kit.text(c, "Menu tersedia", 20f, 258f - scroll, 17f, kit.ink, true)
    if (activeCategory == "All" && query.isBlank()) {
        kit.text(c, "Unggulan · ${matches.firstOrNull()?.name ?: ""}".take(34), 20f, 284f - scroll, 10f, kit.blue, true)
    }
    when {
        productsLoading && products.isEmpty() -> kit.centerText(c, "Memuat menu...", RectF(20f, 306f, 370f, 358f), 14f, kit.muted)
        productsError != null && products.isEmpty() -> kit.centerText(c, "Menu gagal dimuat. Coba lagi sebentar.", RectF(20f, 306f, 370f, 358f), 14f, kit.danger)
        matches.isEmpty() -> kit.centerText(c, if (query.isBlank()) "Belum ada menu tersedia" else "Menu tidak ditemukan", RectF(20f, 306f, 370f, 358f), 14f, kit.muted)
    }

    matches.forEachIndexed { index, product ->
        val top = 296f + index * 184f - scroll
        if (top > h || top + 170f < 246f) return@forEachIndexed
        val card = RectF(20f, top, 370f, top + 170f)
        kit.rounded(c, card, kit.card, 16f, kit.border)
        drawProductImage(c, product, RectF(30f, top + 10f, 125f, top + 160f), 12f)
        kit.text(c, product.name.take(23), 137f, top + 31f, 14f, kit.ink, true)
        kit.text(c, product.description.ifBlank { normalizeCategory(product.category) }.take(29), 137f, top + 53f, 10f, kit.muted)
        kit.text(c, formatRupiah(product.priceIdr), 137f, top + 82f, 14f, kit.ink, true)
        val available = product.available && (product.stock == null || product.stock > 0)
        kit.text(c, if (available) "Tersedia${product.stock?.let { " · Stok $it" }.orEmpty()}" else "Out of Stock", 137f, top + 103f, 10f, if (available) kit.success else kit.danger, true)
        bounds["item:${product.id}"] = RectF(card.left, card.top.coerceAtLeast(246f), card.right, card.bottom.coerceAtMost(h - NAV_HEIGHT))
        if (available) {
            val add = RectF(137f, top + 119f, 358f, top + 157f)
            bounds["add:${product.id}"] = add
            kit.button(c, "Tambah ke keranjang", add, kit.blue, 12f)
        } else {
            kit.button(c, "Habis", RectF(137f, top + 119f, 358f, top + 157f), kit.muted, 12f)
        }
    }
    contentHeight = 274f + matches.size * 184f
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
        kit.centerText(c, "J", rect, 28f, kit.blue, true)
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
