package com.jajanin.app.feature.buyer.explore

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.feature.buyer.home.normalizeCategory

/**
 * Jelajah: khusus browsing + cari menu. Susunannya beda dari Home (yang
 * berupa daftar kartu horizontal): header kaca + cari garis-bawah + chip
 * kategori + grid foto 2 kolom. Berbagi query & kategori dengan Home
 * sehingga state pencarian tetap saat pindah tab.
 */
internal fun JajaninView.drawExplore(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    // Header kaca: judul + Tas. Stabil saat slide.
    c.save()
    c.translate(-slideX, 0f)
    val topBar = RectF(16f, 12f, 374f, 68f)
    kit.rounded(c, RectF(topBar.left, topBar.top + 2f, topBar.right, topBar.bottom + 3f), kit.glassShadow, 20f)
    kit.rounded(c, topBar, kit.glass, 20f, kit.glassBorder)
    kit.text(c, "Jelajah menu", 32f, 46f, 18f, Color.WHITE, true)
    val cartCount = cart.values.sum()
    val cartLabel = if (cartCount > 0) "Tas · $cartCount" else "Tas"
    val cartIcon = 13f
    val cartTextW = kit.measure(cartLabel, 12f, true)
    val cartW = cartTextW + cartIcon + 28f
    val cartButton = RectF(358f - cartW, 22f, 358f, 58f)
    kit.glassPill(c, cartButton, 18f, onDark = true, accent = if (cartCount > 0) kit.blue else null)
    val cartGroupW = cartIcon + 4f + cartTextW
    val cartStartX = cartButton.centerX() - cartGroupW / 2f
    drawIcon(c, "box", cartStartX + cartIcon / 2f, cartButton.centerY(), cartIcon, Color.WHITE)
    kit.text(c, cartLabel, cartStartX + cartIcon + 4f, cartButton.centerY() + 12f * .34f, 12f, Color.WHITE, true)
    bounds["cartCheckout"] = cartButton
    c.restore()

    val off = -scroll

    // Cari langsung di dalam kotak input + pintasan Cari Warung (tanpa popup dialog).
    val searchBox = RectF(20f, 88f + off, 264f, 128f + off)
    val exploreSearchFocused = inlineSearchActiveTarget == InlineSearchOverlay.TARGET_MENU
    val searchActive = exploreSearchFocused || query.isNotBlank()
    kit.rounded(c, searchBox, kit.card, 8f, if (searchActive) kit.blue else kit.border)
    AppIcon.draw(c, "search", 35f, searchBox.centerY(), 15f, if (searchActive) kit.blue else kit.muted)
    if (exploreSearchFocused) {
        setInlineSearchFieldBounds(
            left = 50f,
            top = searchBox.top,
            right = if (query.isNotBlank()) 232f else 256f,
            bottom = searchBox.bottom,
            textSize = 13f,
        )
    } else {
        kit.textEllipsized(
            c,
            if (query.isBlank()) "Cari nama menu..." else query,
            50f,
            searchBox.top + 25f,
            if (query.isBlank()) 206f else 180f,
            13f,
            if (query.isBlank()) kit.muted else kit.ink,
            query.isNotBlank(),
        )
    }
    if (query.isNotBlank()) {
        val clearHit = RectF(234f, searchBox.top + 6f, 262f, searchBox.bottom - 6f)
        AppIcon.draw(c, "close", clearHit.centerX(), clearHit.centerY(), 12f, kit.muted)
        bounds["clearSearch"] = clearHit
    }
    val storePill = RectF(272f, 88f + off, 370f, 128f + off)
    kit.rounded(c, storePill, kit.tint, 8f, kit.blue)
    AppIcon.draw(c, "store", storePill.left + 15f, storePill.centerY(), 13f, kit.blue)
    kit.text(c, "Cari Warung", storePill.left + 26f, storePill.centerY() + 3.8f, 10.5f, kit.blue, true)
    press(c, "openStoreSearch", storePill, 8f)
    bounds["search"] = RectF(20f, searchBox.top, if (query.isNotBlank()) 232f else 264f, searchBox.bottom)
    bounds["openStoreSearch"] = storePill

    // Kategori: chip pil ringkas.
    var x = 20f
    listOf("All", "Food", "Drinks").forEach { category ->
        val label = if (category == "All") "Semua" else category
        val width = kit.measure(label, 12f, true) + 30f
        val chip = RectF(x, 140f + off, x + width, 172f + off)
        val active = activeCategory == category
        kit.glassPill(c, chip, 16f, accent = if (active) kit.blue else null)
        kit.centerText(c, label, chip, 12f, if (active) Color.WHITE else kit.ink, true)
        bounds["cat:$category"] = chip
        x += width + 9f
    }

    val matches = products.filter { product ->
        (activeCategory == "All" || normalizeCategory(product.category) == activeCategory) &&
            (query.isBlank() || product.name.contains(query.trim(), ignoreCase = true))
    }
    kit.text(c, if (matches.isEmpty()) "Nggak ketemu" else "${matches.size} menu", 20f, 198f + off, 12f, kit.muted, true)
    when {
        productsLoading && products.isEmpty() -> kit.centerText(c, "Memuat menu...", RectF(20f, 230f + off, 370f, 290f + off), 14f, kit.muted)
        productsError != null && products.isEmpty() -> kit.centerText(c, "Menu gagal dimuat. Coba lagi sebentar.", RectF(20f, 230f + off, 370f, 290f + off), 14f, kit.danger)
        matches.isEmpty() -> kit.centerText(c, "Coba kata kunci atau kategori lain.", RectF(20f, 230f + off, 370f, 290f + off), 13f, kit.muted)
    }

    // Grid foto: ubin foto + nama + nama warung (verified badge) + harga + tombol tambah.
    val tileW = (w - 20f * 2f - 12f) / 2f
    val photoH = 118f
    val tileH = photoH + 82f
    val gridTop = 212f
    matches.forEachIndexed { index, product ->
        val col = index % 2
        val row = index / 2
        val left = 20f + col * (tileW + 12f)
        val top = gridTop + row * (tileH + 14f) + off
        if (top > h - 82f || top + tileH < 96f) return@forEachIndexed
        drawProductImage(c, product, RectF(left, top, left + tileW, top + photoH), 12f)
        val available = product.available && (product.stock == null || product.stock > 0)
        if (!available) {
            kit.rounded(c, RectF(left + 8f, top + 8f, left + 62f, top + 30f), Color.argb(200, 20, 25, 40), 8f)
            kit.centerText(c, "Habis", RectF(left + 8f, top + 8f, left + 62f, top + 30f), 10f, Color.WHITE, true)
        } else if (product.stock != null && product.stock <= 3) {
            kit.rounded(c, RectF(left + 8f, top + 8f, left + 96f, top + 30f), Color.argb(200, 20, 25, 40), 8f)
            kit.centerText(c, "Sisa ${product.stock}", RectF(left + 8f, top + 8f, left + 96f, top + 30f), 10f, Color.WHITE, true)
        }
        val schedShort = product.scheduleBadgeLabel
        val schedW = (kit.measure(schedShort, 9f, true) + 14f).coerceAtMost(tileW - 14f)
        val schedRect = RectF(left + 7f, top + photoH - 24f, left + 7f + schedW, top + photoH - 6f)
        val schedBg = if (product.orderSchedule == "next_day") Color.argb(225, 183, 116, 0) else Color.argb(215, 0, 99, 214)
        kit.rounded(c, schedRect, schedBg, 5f)
        kit.centerText(c, schedShort, schedRect, 9f, Color.WHITE, true)
        kit.textEllipsized(c, product.name, left + 2f, top + photoH + 20f, tileW - 4f, 13f, kit.ink, true)
        val storeInfo = product.stallId?.let { storeDirectoryById[it] }
        if (storeInfo != null) {
            StoreVerifiedBadge.drawInlineWithStoreName(
                view = this,
                c = c,
                storeName = storeInfo.name,
                isVerified = storeInfo.isVerified,
                x = left + 2f,
                baselineY = top + photoH + 36f,
                maxTotalWidth = tileW - 6f,
                textSize = 10.5f,
                textColor = kit.blue,
                bold = true,
            )
        }
        val priceY = if (storeInfo != null) top + photoH + 60f else top + photoH + 45f
        kit.text(c, formatRupiah(product.priceIdr), left + 2f, priceY, 13f, kit.ink, true)
        if (available) {
            val addLabel = "+ Tambah"
            val addW = kit.measure(addLabel, 10.5f, true) + 18f
            val addTop = if (storeInfo != null) top + photoH + 44f else top + photoH + 28f
            val add = RectF(left + tileW - addW, addTop, left + tileW, addTop + 26f)
            kit.rounded(c, add, kit.tint, 13f)
            kit.centerText(c, addLabel, add, 10.5f, kit.blue, true)
            bounds["add:${product.id}"] = add
        }
        bounds["item:${product.id}"] = RectF(left, top.coerceAtLeast(96f), left + tileW, (top + photoH + 42f).coerceAtMost(h - 82f))
        if (storeInfo != null && top + photoH + 24f >= 96f && top + photoH + 42f <= h - 82f) {
            bounds["openStore:${storeInfo.id}"] = RectF(left, top + photoH + 24f, left + tileW, top + photoH + 42f)
        }
    }
    contentHeight = gridTop + (matches.size + 1) / 2 * (tileH + 14f) + 40f
    drawBottomNav(c, w, h, "Explore")
}
