package com.jajanin.app.feature.buyer.home

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.blackoutReopen
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.feature.developer.inboxUnreadCount

private const val NAV_HEIGHT = 82f
private const val HEADER_H = 60f

internal fun JajaninView.drawHome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    val off = -scroll
    val bottomClip = if (cart.isNotEmpty()) h - NAV_HEIGHT - 54f else h - NAV_HEIGHT
    fun isVisible(top: Float, bottom: Float): Boolean = bottom >= HEADER_H && top <= bottomClip

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 72f + off

    // 1. Minimal Inline Status Row (No Box)
    val suspended = myAccountStatus == "suspended"
    if (isVisible(y, y + 20f)) {
        val dotColor = when {
            suspended -> kit.danger
            orderingBlocked -> Color.rgb(183, 116, 0)
            else -> kit.success
        }
        kit.rounded(c, RectF(20f, y + 5f, 26f, y + 11f), dotColor, 3f)
        kit.text(c, "${serverClockLabel()} · live", 32f, y + 12f, 11f, kit.muted)

        val reopen = blackoutReopen()
        val rightText = when {
            suspended -> "Akun ditangguhkan"
            reopen != null -> "Buka %02d:00 (%d mnt)".format(reopen.first, reopen.second)
            orderingStatusLoading -> "Memuat..."
            else -> "Kuota harian $ordersToday/2"
        }
        val rightColor = when {
            suspended -> kit.danger
            orderingBlocked -> Color.rgb(183, 116, 0)
            else -> kit.ink
        }
        kit.textRight(c, rightText, 370f, y + 12f, 160f, 11f, rightColor, true)
    }
    if (currentScreen == Screen.HOME) postInvalidateDelayed(1000)
    y += 24f

    // 2. Active Order Strip (Left accent bar + hairline, no heavy box)
    val activeOrder = myOrders.firstOrNull { !isTerminalStatus(it.status) }
    if (activeOrder != null) {
        val stripRect = RectF(20f, y, 370f, y + 48f)
        if (isVisible(stripRect.top, stripRect.bottom)) {
            kit.rounded(c, stripRect, kit.tint, 8f)
            kit.rect(c, 20f, y + 8f, 23.5f, y + 40f, kit.blue)
            val (stLabel, _) = statusLabel(activeOrder.status)
            kit.text(c, "#${activeOrder.pickupCode}", 32f, y + 21f, 13f, kit.blue, true)
            kit.textEllipsized(
                c,
                "$stLabel  ·  ${activeOrder.itemsSummary.ifBlank { "Pantau pesanan" }}",
                32f + kit.measure("#${activeOrder.pickupCode}", 13f, true) + 10f,
                y + 21f,
                220f,
                12f,
                kit.ink,
                true
            )
            kit.text(c, "Ketuk untuk lihat detail pesanan", 32f, y + 38f, 10.5f, kit.muted)
            drawIcon(c, "chevronRight", 356f, stripRect.centerY(), 14f, kit.blue)
            press(c, "orders", stripRect, 8f)
        }
        bounds.putVisible("orders", stripRect, topLimit = HEADER_H, bottomLimit = bottomClip)
        y += 58f
    }

    // 3. Crisp Search Field (Radius 8f, subtle border, no heavy shadow)
    val search = RectF(20f, y, 370f, y + 42f)
    if (isVisible(search.top, search.bottom)) {
        kit.rounded(c, search, kit.card, 8f, kit.border)
        drawIcon(c, "search", 36f, search.centerY(), 15f, kit.muted)
        val searchText = if (query.isBlank()) "Cari menu atau minuman..." else query
        kit.textEllipsized(
            c,
            searchText,
            54f,
            y + 26f,
            if (query.isBlank()) 300f else 274f,
            13f,
            if (query.isBlank()) kit.muted else kit.ink,
            query.isNotBlank()
        )
        if (query.isNotBlank()) {
            val clearRect = RectF(336f, y + 7f, 364f, y + 35f)
            drawIcon(c, "close", clearRect.centerX(), clearRect.centerY(), 12f, kit.muted)
            bounds.putVisible("clearSearch", clearRect, topLimit = HEADER_H, bottomLimit = bottomClip)
        }
    }
    bounds.putVisible("search", search, topLimit = HEADER_H, bottomLimit = bottomClip)
    y += 56f

    // 4. "Paling Laris · 3 Hari Terakhir" (Open borderless editorial tiles — no outer card boxes)
    val rankedTrending = computeTrendingTop3(products, topSelling3d)
    if (rankedTrending.isNotEmpty() && activeCategory == "All" && query.isBlank()) {
        if (isVisible(y, y + 22f)) {
            kit.text(c, "Paling Laris", 20f, y + 15f, 15f, kit.ink, true)
            kit.textRight(c, "3 hari terakhir", 370f, y + 15f, 120f, 11f, kit.muted)
        }
        y += 24f

        val tileW = (350f - 20f) / 3f // 3 open tiles across 350f
        val imgH = 86f
        val tileH = imgH + 54f
        if (isVisible(y, y + tileH)) {
            rankedTrending.forEachIndexed { idx, (product, soldCount) ->
                val left = 20f + idx * (tileW + 10f)
                val imgRect = RectF(left, y, left + tileW, y + imgH)
                drawProductImage(c, product, imgRect, 8f)

                // Crisp architectural rank tag (#1, #2, #3)
                val rankRect = RectF(left + 5f, y + 5f, left + 27f, y + 21f)
                kit.rounded(c, rankRect, kit.blue, 4f)
                kit.centerText(c, "#${idx + 1}", rankRect, 9.5f, Color.WHITE, true)

                // Minimal heart toggle on photo corner
                val isFav = favoriteIds.contains(product.id)
                val favRect = RectF(imgRect.right - 25f, imgRect.top + 4f, imgRect.right - 3f, imgRect.top + 26f)
                kit.rounded(c, favRect, Color.argb(210, 255, 255, 255), 6f)
                drawIcon(
                    c,
                    if (isFav) "heartFill" else "heart",
                    favRect.centerX(),
                    favRect.centerY(),
                    11.5f,
                    if (isFav) kit.danger else kit.ink
                )
                bounds.putVisible("fav:${product.id}", favRect, topLimit = HEADER_H, bottomLimit = bottomClip)

                // Open typography below image (no card box!)
                kit.textEllipsized(c, product.name, left, y + imgH + 17f, tileW, 12.5f, kit.ink, true)
                kit.textEllipsized(c, formatRupiah(product.priceIdr), left, y + imgH + 34f, tileW, 12f, kit.blue, true)
                val soldText = if (soldCount > 0) "${soldCount}x terjual" else "Populer"
                kit.textEllipsized(c, soldText, left, y + imgH + 49f, tileW, 10f, kit.muted)

                val tileHit = RectF(left, y, left + tileW, y + tileH)
                bounds.putVisible("item:${product.id}", tileHit, topLimit = HEADER_H, bottomLimit = bottomClip)
            }
        }
        y += tileH + 16f
        if (isVisible(y - 6f, y)) {
            kit.line(c, 20f, y - 4f, 370f, y - 4f, kit.divider)
        }
        y += 8f
    }

    // 5. Category Underline Tabs (No boxy chips — clean typographic tabs + 2f blue indicator)
    val categorySpecs = listOf(
        "All" to "Semua",
        "Favorites" to if (favoriteIds.isNotEmpty()) "Favorit (${favoriteIds.size})" else "Favorit",
        "Food" to "Makanan",
        "Drinks" to "Minuman"
    )
    if (isVisible(y, y + 34f)) {
        kit.line(c, 20f, y + 32f, 370f, y + 32f, kit.divider)
        var tx = 20f
        categorySpecs.forEach { (catKey, catLabel) ->
            val active = activeCategory == catKey
            val labelW = kit.measure(catLabel, 13f, true)
            val tabRect = RectF(tx, y, tx + labelW + 16f, y + 32f)
            kit.text(
                c,
                catLabel,
                tx,
                y + 21f,
                13f,
                if (active) kit.blue else kit.muted,
                active
            )
            if (active) {
                kit.rect(c, tx, y + 30f, tx + labelW, y + 32.5f, kit.blue)
            }
            bounds.putVisible("cat:$catKey", tabRect, topLimit = HEADER_H, bottomLimit = bottomClip)
            tx += labelW + 24f
        }
    }
    y += 42f

    // 6. Filtered Menu Rows (Clean borderless rows with hairline dividers — NO boxes per item)
    val matches = products.filter { product ->
        val categoryMatch = when (activeCategory) {
            "All" -> true
            "Favorites" -> favoriteIds.contains(product.id)
            else -> normalizeCategory(product.category) == activeCategory
        }
        val queryMatch = query.isBlank() ||
            product.name.contains(query.trim(), ignoreCase = true) ||
            product.description.contains(query.trim(), ignoreCase = true)
        categoryMatch && queryMatch
    }

    when {
        productsLoading && products.isEmpty() -> {
            kit.centerText(c, "Memuat menu...", RectF(20f, y + 20f, 370f, y + 80f), 13.5f, kit.muted)
            y += 90f
        }
        productsError != null && products.isEmpty() -> {
            kit.centerText(c, "Menu gagal dimuat. Coba lagi sebentar.", RectF(20f, y + 20f, 370f, y + 80f), 13.5f, kit.danger)
            y += 90f
        }
        matches.isEmpty() -> {
            val emptyMsg = if (activeCategory == "Favorites") {
                "Belum ada menu favorit. Ketuk ikon hati pada menu."
            } else if (query.isBlank()) {
                "Belum ada menu tersedia."
            } else {
                "Menu tidak ditemukan."
            }
            kit.centerText(c, emptyMsg, RectF(20f, y + 24f, 370f, y + 76f), 13f, kit.muted)
            y += 88f
        }
        else -> {
            val rowH = 98f
            matches.forEachIndexed { index, product ->
                val rowTop = y
                val rowBottom = y + rowH
                if (isVisible(rowTop, rowBottom)) {
                    val imgRect = RectF(20f, rowTop + 10f, 96f, rowTop + 86f)
                    drawProductImage(c, product, imgRect, 8f)

                    // Dish Name + Heart icon inline on the right
                    val isFav = favoriteIds.contains(product.id)
                    kit.textEllipsized(c, product.name, 110f, rowTop + 26f, 224f, 14.5f, kit.ink, true)

                    val favHit = RectF(342f, rowTop + 8f, 372f, rowTop + 36f)
                    drawIcon(
                        c,
                        if (isFav) "heartFill" else "heart",
                        favHit.centerX(),
                        favHit.centerY(),
                        13.5f,
                        if (isFav) kit.danger else kit.muted
                    )
                    bounds.putVisible("fav:${product.id}", favHit, topLimit = HEADER_H, bottomLimit = bottomClip)

                    // Subtle subtitle (description or category + stock)
                    val available = product.available && (product.stock == null || product.stock > 0)
                    val catName = if (normalizeCategory(product.category) == "Food") "Makanan" else "Minuman"
                    val stockInfo = when {
                        !available -> "Habis"
                        product.stock != null -> "Stok ${product.stock}"
                        else -> "Tersedia"
                    }
                    val descLine = product.description.takeIf { it.isNotBlank() } ?: "$catName · $stockInfo"
                    kit.textEllipsized(c, descLine, 110f, rowTop + 45f, 250f, 11.5f, kit.muted)

                    // Price on the left, clean 8f radius action on the right
                    kit.text(c, formatRupiah(product.priceIdr), 110f, rowTop + 76f, 14.5f, kit.ink, true)

                    val inCartQty = cart[product.id] ?: 0
                    if (available) {
                        if (inCartQty > 0) {
                            val stepper = RectF(286f, rowTop + 54f, 370f, rowTop + 84f)
                            kit.rounded(c, stepper, kit.tint, 7f, kit.blue)
                            val minusHit = RectF(stepper.left, stepper.top, stepper.left + 28f, stepper.bottom)
                            val plusHit = RectF(stepper.right - 28f, stepper.top, stepper.right, stepper.bottom)
                            kit.centerText(c, "−", minusHit, 15f, kit.blue, true)
                            kit.centerText(c, "$inCartQty", stepper, 12f, kit.ink, true)
                            kit.centerText(c, "+", plusHit, 15f, kit.blue, true)
                            bounds.putVisible("cartMinus:${product.id}", minusHit, topLimit = HEADER_H, bottomLimit = bottomClip)
                            bounds.putVisible("cartPlus:${product.id}", plusHit, topLimit = HEADER_H, bottomLimit = bottomClip)
                        } else {
                            val addBtn = RectF(296f, rowTop + 54f, 370f, rowTop + 84f)
                            kit.rounded(c, addBtn, kit.blue, 7f)
                            kit.centerText(c, "+ Tambah", addBtn, 11f, Color.WHITE, true)
                            bounds.putVisible("add:${product.id}", addBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
                        }
                    } else {
                        kit.textRight(c, "Habis", 370f, rowTop + 74f, 64f, 11.5f, kit.danger, true)
                    }

                    // Hairline divider between rows
                    if (index < matches.size - 1) {
                        kit.line(c, 110f, rowBottom, 370f, rowBottom, kit.divider)
                    }

                    bounds.putVisible(
                        "item:${product.id}",
                        RectF(20f, rowTop, 284f, rowBottom),
                        topLimit = HEADER_H,
                        bottomLimit = bottomClip
                    )
                }
                y += rowH
            }
        }
    }

    c.restoreToCount(bodySave)

    contentHeight = (y - off) + (if (cart.isNotEmpty()) 90f else 36f)

    // 7. Crisp Checkout Bar above Navbar when cart has items (Radius 10f)
    val cartCount = cart.values.sum()
    if (cartCount > 0) {
        val totalCartIdr = cart.entries.sumOf { (id, qty) ->
            val p = products.firstOrNull { it.id == id }
            (p?.priceIdr ?: 0) * qty
        }
        c.save()
        c.translate(-slideX, 0f)
        val floatBar = RectF(20f, h - NAV_HEIGHT - 48f, 370f, h - NAV_HEIGHT - 6f)
        kit.rounded(c, floatBar, kit.blue, 10f)
        kit.text(
            c,
            "$cartCount item  ·  ${formatRupiah(totalCartIdr)}",
            34f,
            floatBar.centerY() + 4.5f,
            13f,
            Color.WHITE,
            true
        )
        kit.textRight(c, "Checkout →", 356f, floatBar.centerY() + 4.5f, 100f, 13f, Color.WHITE, true)
        press(c, "cartCheckoutBar", floatBar, 10f)
        c.restore()
        bounds["cartCheckoutBar"] = floatBar
    }

    // 8. Flat Architectural Top Header (No dark floating box)
    drawCleanTopHeader(c, cartCount)

    drawBottomNav(c, w, h, "Home")
}

private fun JajaninView.drawCleanTopHeader(c: Canvas, cartCount: Int) {
    c.save()
    c.translate(-slideX, 0f)

    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)

    // Brand Mark (Radius 6f) + Title
    val logoBox = RectF(20f, 17f, 46f, 43f)
    kit.rounded(c, logoBox, kit.blue, 6f)
    kit.centerText(c, "J", logoBox, 15.5f, Color.WHITE, true)
    kit.text(c, "Jajanin", 54f, 36.5f, 18f, kit.ink, true)

    // Right 1: Cart Action
    val cartLabel = if (cartCount > 0) "Tas ($cartCount)" else "Tas"
    val cartW = kit.measure(cartLabel, 11.5f, true) + 22f
    val cartBtn = RectF(370f - cartW, 16f, 370f, 44f)
    if (cartCount > 0) {
        kit.rounded(c, cartBtn, kit.blue, 7f)
        kit.centerText(c, cartLabel, cartBtn, 11.5f, Color.WHITE, true)
    } else {
        kit.rounded(c, cartBtn, kit.card, 7f, kit.border)
        kit.centerText(c, cartLabel, cartBtn, 11.5f, kit.ink, true)
    }
    press(c, "cartCheckout", cartBtn, 7f)
    bounds["cartCheckout"] = cartBtn

    // Right 2: Bell / Inbox Icon
    val inboxBtn = RectF(cartBtn.left - 38f, 16f, cartBtn.left - 8f, 44f)
    drawIcon(c, "bell", inboxBtn.centerX(), inboxBtn.centerY(), 16f, kit.ink)
    bounds["inbox"] = inboxBtn
    val unread = inboxUnreadCount()
    if (unread > 0) {
        val dot = RectF(inboxBtn.right - 9f, inboxBtn.top + 4f, inboxBtn.right - 1f, inboxBtn.top + 12f)
        kit.rounded(c, dot, kit.danger, 4f)
    }

    // Right 3: Profile Avatar + Short Name
    val rawName = myDisplayName.trim().ifBlank { "Profil" }
    val shortName = rawName.substringBefore(' ').take(8)
    val initial = (rawName.firstOrNull() ?: 'P').uppercaseChar().toString()
    val profText = shortName
    val profTextW = kit.measure(profText, 11.5f, true).coerceAtMost(84f)
    val profW = 24f + 6f + profTextW
    val profBtn = RectF(inboxBtn.left - 10f - profW, 16f, inboxBtn.left - 10f, 44f)
    val avatar = RectF(profBtn.left, 18f, profBtn.left + 24f, 42f)
    kit.rounded(c, avatar, kit.tint, 12f)
    kit.centerText(c, initial, avatar, 11f, kit.blue, true)
    kit.textEllipsized(c, profText, avatar.right + 6f, 34.5f, 84f, 11.5f, kit.ink, true)
    bounds["homeProfile"] = profBtn

    c.restore()
}

private fun computeTrendingTop3(
    products: List<ProductRow>,
    counts: Map<String, Int>
): List<Pair<ProductRow, Int>> {
    if (products.isEmpty()) return emptyList()
    return products
        .map { product ->
            val soldById = counts[product.id] ?: 0
            val soldByName = counts[product.name.trim().lowercase()] ?: 0
            product to maxOf(soldById, soldByName)
        }
        .sortedWith(
            compareByDescending<Pair<ProductRow, Int>> { it.second }
                .thenByDescending { it.first.available }
                .thenBy { it.first.name.lowercase() }
        )
        .take(3)
}

internal fun normalizeCategory(value: String): String = when (value.lowercase().trim()) {
    "food", "makanan" -> "Food"
    "drink", "drinks", "minuman" -> "Drinks"
    else -> value
}
