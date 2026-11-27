package com.jajanin.app.feature.buyer.home

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.data.repository.SalesStat
import com.jajanin.app.feature.buyer.store_discovery.drawHomeStoreDiscoverySection
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
        val dotColor = if (suspended) kit.danger else kit.success
        kit.rounded(c, RectF(20f, y + 5f, 26f, y + 11f), dotColor, 3f)
        kit.text(c, "${serverClockLabel()} · live", 32f, y + 12f, 11f, kit.muted)

        val rightText = if (suspended) "Akun ditangguhkan" else "Buka · Hari Ini & PO"
        val rightColor = if (suspended) kit.danger else kit.success
        kit.textRight(c, rightText, 370f, y + 12f, 180f, 11f, rightColor, true)
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

    // 3. Crisp Inline Search Field + Store Discovery Button (Direct in-box typing, no popup)
    val search = RectF(20f, y, 290f, y + 42f)
    val storeBtn = RectF(298f, y, 370f, y + 42f)
    val menuSearchFocused = inlineSearchActiveTarget == InlineSearchOverlay.TARGET_MENU
    if (isVisible(search.top, search.bottom)) {
        val searchActive = menuSearchFocused || query.isNotBlank()
        kit.rounded(c, search, kit.card, 8f, if (searchActive) kit.blue else kit.border)
        AppIcon.draw(c, "search", 35f, search.centerY(), 15f, if (searchActive) kit.blue else kit.muted)
        if (menuSearchFocused) {
            setInlineSearchFieldBounds(
                left = 52f,
                top = search.top,
                right = if (query.isNotBlank()) 256f else 282f,
                bottom = search.bottom,
                textSize = 13f,
            )
        } else {
            val searchText = if (query.isBlank()) "Cari menu atau minuman..." else query
            kit.textEllipsized(
                c,
                searchText,
                52f,
                y + 26f,
                if (query.isBlank()) 228f else 202f,
                13f,
                if (query.isBlank()) kit.muted else kit.ink,
                query.isNotBlank()
            )
        }
        if (query.isNotBlank()) {
            val clearRect = RectF(258f, y + 7f, 286f, y + 35f)
            AppIcon.draw(c, "close", clearRect.centerX(), clearRect.centerY(), 12f, kit.muted)
            bounds.putVisible("clearSearch", clearRect, topLimit = HEADER_H, bottomLimit = bottomClip)
        }

        kit.rounded(c, storeBtn, kit.tint, 8f, kit.blue)
        AppIcon.draw(c, "store", storeBtn.left + 15f, storeBtn.centerY(), 13.5f, kit.blue)
        kit.text(c, "Warung", storeBtn.left + 26f, y + 26f, 11f, kit.blue, true)
        press(c, "openStoreSearch", storeBtn, 8f)
    }
    bounds.putVisible("search", RectF(20f, y, if (query.isNotBlank()) 256f else 290f, y + 42f), topLimit = HEADER_H, bottomLimit = bottomClip)
    bounds.putVisible("openStoreSearch", storeBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
    y += 54f

    // 3b. Store Discovery Showcase ("Jelajahi Warung")
    if (activeCategory == "All" && query.isBlank()) {
        y = drawHomeStoreDiscoverySection(c, y, ::isVisible, HEADER_H, bottomClip)
    }

    // 4. "Paling Laris · Penjualan terbanyak" (Hanya menu yang benar-benar sudah pernah terjual)
    val rankedTrending = computeTrendingTop3(products, topSellingStats)
    if (rankedTrending.isNotEmpty() && activeCategory == "All" && query.isBlank()) {
        if (isVisible(y, y + 22f)) {
            kit.text(c, "Paling Laris", 20f, y + 15f, 15f, kit.ink, true)
            kit.textRight(c, "Penjualan terbanyak", 370f, y + 15f, 150f, 11f, kit.muted)
        }
        y += 24f

        val tileW = (350f - 20f) / 3f // 3 open tiles across 350f
        val imgH = 86f
        val tileH = imgH + 68f
        if (isVisible(y, y + tileH)) {
            rankedTrending.forEachIndexed { idx, (product, stat) ->
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
                kit.textEllipsized(c, product.name, left, y + imgH + 16f, tileW, 12.5f, kit.ink, true)
                val storeInfo = product.stallId?.let { storeDirectoryById[it] }
                if (storeInfo != null) {
                    StoreVerifiedBadge.drawInlineWithStoreName(
                        view = this,
                        c = c,
                        storeName = storeInfo.name,
                        isVerified = storeInfo.isVerified,
                        x = left,
                        baselineY = y + imgH + 31f,
                        maxTotalWidth = tileW,
                        textSize = 10f,
                        textColor = kit.muted,
                        bold = false,
                    )
                } else {
                    kit.textEllipsized(c, "${stat.totalQty}x terjual", left, y + imgH + 31f, tileW, 10f, kit.muted)
                }
                kit.textEllipsized(c, formatRupiah(product.priceIdr), left, y + imgH + 48f, tileW, 12f, kit.blue, true)
                if (storeInfo != null) {
                    kit.textEllipsized(c, "${stat.totalQty}x terjual", left, y + imgH + 62f, tileW, 9.5f, kit.muted)
                }

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
            val rowH = 122f
            matches.forEachIndexed { index, product ->
                val rowTop = y
                val rowBottom = y + rowH
                if (isVisible(rowTop, rowBottom)) {
                    val imgRect = RectF(20f, rowTop + 16f, 98f, rowTop + 94f)
                    drawProductImage(c, product, imgRect, 8f)

                    // Dish Name + Heart icon inline on the right
                    val isFav = favoriteIds.contains(product.id)
                    kit.textEllipsized(c, product.name, 110f, rowTop + 25f, 224f, 14.5f, kit.ink, true)

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

                    // Store Name + Verified Badge (tappable to open Public Store Profile)
                    val storeSummary = product.stallId?.let { storeDirectoryById[it] }
                    if (storeSummary != null) {
                        StoreVerifiedBadge.drawInlineWithStoreName(
                            view = this,
                            c = c,
                            storeName = storeSummary.name,
                            isVerified = storeSummary.isVerified,
                            x = 110f,
                            baselineY = rowTop + 42f,
                            maxTotalWidth = 226f,
                            textSize = 11f,
                            textColor = kit.blue,
                            bold = true,
                        )
                    }

                    // Subtle subtitle (description or category + stock)
                    val available = product.available && (product.stock == null || product.stock > 0)
                    val catName = if (normalizeCategory(product.category) == "Food") "Makanan" else "Minuman"
                    val stockInfo = when {
                        !available -> "Habis"
                        product.stock != null -> "Stok ${product.stock}"
                        else -> "Tersedia"
                    }
                    val descLine = product.description.takeIf { it.isNotBlank() } ?: "$catName · $stockInfo"
                    val descY = if (storeSummary != null) rowTop + 57f else rowTop + 44f
                    kit.textEllipsized(c, descLine, 110f, descY, 250f, 11f, kit.muted)

                    // Pre-Order / Same-Day schedule pill
                    val schedLabel = product.scheduleBadgeLabel
                    val schedW = (kit.measure(schedLabel, 9.5f, true) + 14f).coerceAtMost(150f)
                    val schedTop = if (storeSummary != null) rowTop + 63f else rowTop + 52f
                    val schedPill = RectF(110f, schedTop, 110f + schedW, schedTop + 16f)
                    val isNextOnly = product.orderSchedule == "next_day"
                    val pillBg = if (isNextOnly) kit.warnBox else kit.tint
                    val pillFg = if (isNextOnly) Color.rgb(183, 116, 0) else kit.blue
                    kit.rounded(c, schedPill, pillBg, 4f)
                    kit.centerText(c, schedLabel, schedPill, 9.5f, pillFg, true)
                    if (product.hasVariants) {
                        val firstSec = product.activeVariantSections.first()
                        val varLabel = if (product.activeVariantSections.size == 1) {
                            "${firstSec.name}: ${firstSec.options.size} pilihan"
                        } else {
                            "${product.activeVariantSections.size} opsi varian"
                        }
                        val varLeft = schedPill.right + 6f
                        val availW = 370f - varLeft
                        if (availW >= 54f) {
                            val varW = (kit.measure(varLabel, 9.5f, true) + 12f).coerceAtMost(availW)
                            val varPill = RectF(varLeft, schedTop, varLeft + varW, schedTop + 16f)
                            kit.rounded(c, varPill, kit.bg, 4f, kit.border)
                            kit.textEllipsized(c, varLabel, varPill.left + 6f, varPill.top + 11.2f, varW - 12f, 9.5f, kit.ink, true)
                        }
                    }

                    // Price on the left, clean 8f radius action on the right
                    kit.text(c, formatRupiah(product.priceIdr), 110f, rowTop + 103f, 14.5f, kit.ink, true)

                    val inCartQty = com.jajanin.app.data.model.totalQtyForProductInCart(cart, product.id)
                    if (available) {
                        if (product.hasVariants) {
                            val label = if (inCartQty > 0) "${inCartQty}x · Pilih" else "+ Pilih"
                            val addBtn = RectF(286f, rowTop + 79f, 370f, rowTop + 109f)
                            kit.rounded(c, addBtn, if (inCartQty > 0) kit.tint else kit.blue, 7f, if (inCartQty > 0) kit.blue else null)
                            kit.centerText(c, label, addBtn, 10.5f, if (inCartQty > 0) kit.blue else Color.WHITE, true)
                            bounds.putVisible("add:${product.id}", addBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
                        } else if (inCartQty > 0) {
                            val stepper = RectF(286f, rowTop + 79f, 370f, rowTop + 109f)
                            kit.rounded(c, stepper, kit.tint, 7f, kit.blue)
                            val minusHit = RectF(stepper.left, stepper.top, stepper.left + 28f, stepper.bottom)
                            val plusHit = RectF(stepper.right - 28f, stepper.top, stepper.right, stepper.bottom)
                            AppIcon.draw(c, "minus", minusHit.centerX(), minusHit.centerY(), 12f, kit.blue)
                            kit.centerText(c, "$inCartQty", stepper, 12f, kit.ink, true)
                            AppIcon.draw(c, "plus", plusHit.centerX(), plusHit.centerY(), 12f, kit.blue)
                            bounds.putVisible("cartMinus:${product.id}", minusHit, topLimit = HEADER_H, bottomLimit = bottomClip)
                            bounds.putVisible("cartPlus:${product.id}", plusHit, topLimit = HEADER_H, bottomLimit = bottomClip)
                        } else {
                            val addBtn = RectF(296f, rowTop + 79f, 370f, rowTop + 109f)
                            kit.rounded(c, addBtn, kit.blue, 7f)
                            kit.centerText(c, "+ Tambah", addBtn, 11f, Color.WHITE, true)
                            bounds.putVisible("add:${product.id}", addBtn, topLimit = HEADER_H, bottomLimit = bottomClip)
                        }
                    } else {
                        kit.textRight(c, "Habis", 370f, rowTop + 99f, 64f, 11.5f, kit.danger, true)
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
                    if (storeSummary != null) {
                        bounds.putVisible(
                            "openStore:${storeSummary.id}",
                            RectF(108f, rowTop + 29f, 282f, rowTop + 47f),
                            topLimit = HEADER_H,
                            bottomLimit = bottomClip
                        )
                    }
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
        val totalCartIdr = cart.entries.sumOf { (cartKey, qty) ->
            val pid = com.jajanin.app.data.model.productIdFromCartKey(cartKey)
            val p = products.firstOrNull { it.id == pid }
                ?: storeProfileProducts.firstOrNull { it.id == pid }
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
        drawTrailingActionWithArrow(c, "Checkout", 356f, floatBar.centerY() + 4.5f, 13f, Color.WHITE)
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
    press(c, "inbox", inboxBtn, 6f)
    bounds["inbox"] = inboxBtn
    val unread = inboxUnreadCount()
    if (unread > 0) {
        val dot = RectF(inboxBtn.right - 9f, inboxBtn.top + 4f, inboxBtn.right - 1f, inboxBtn.top + 12f)
        kit.rounded(c, dot, kit.danger, 4f)
    }

    // Right 3: Profile Avatar + Short Name (+ Verified & Dev emblem for NIS 11855)
    val rawName = myDisplayName.trim().ifBlank { "Profil" }
    val shortName = rawName.substringBefore(' ').take(8)
    val initial = (rawName.firstOrNull() ?: 'P').uppercaseChar().toString()
    val profText = shortName
    val isDevHeader = StoreVerifiedBadge.isDevIdentity(
        view = this,
        name = rawName,
        nis = myNis,
        stallId = myStallId.orEmpty(),
    )
    val badgeExtraW = if (isDevHeader) 30f else 0f
    val profTextW = kit.measure(profText, 11.5f, true).coerceAtMost(72f)
    val profW = 24f + 6f + profTextW + badgeExtraW
    val profBtn = RectF(inboxBtn.left - 10f - profW, 16f, inboxBtn.left - 10f, 44f)
    val avatar = RectF(profBtn.left, 18f, profBtn.left + 24f, 42f)
    kit.rounded(c, avatar, kit.tint, 12f)
    kit.centerText(c, initial, avatar, 11f, kit.blue, true)
    StoreVerifiedBadge.drawInlineWithStoreName(
        view = this,
        c = c,
        storeName = profText,
        isVerified = isDevHeader,
        x = avatar.right + 6f,
        baselineY = 34.5f,
        maxTotalWidth = 72f + badgeExtraW,
        textSize = 11.5f,
        textColor = kit.ink,
        bold = true,
        isDev = isDevHeader,
        nis = myNis,
    )
    bounds["homeProfile"] = profBtn

    c.restore()
}

private fun computeTrendingTop3(
    products: List<ProductRow>,
    stats: Map<String, SalesStat>
): List<Pair<ProductRow, SalesStat>> {
    if (products.isEmpty() || stats.isEmpty()) return emptyList()
    return products
        .mapNotNull { product ->
            val stat = stats[product.id] ?: stats[product.name.trim().lowercase()]
            if (stat == null || stat.totalQty <= 0) null else product to stat
        }
        .sortedWith(
            compareByDescending<Pair<ProductRow, SalesStat>> { it.second.totalQty }
                .thenByDescending { it.second.orderCount }
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
