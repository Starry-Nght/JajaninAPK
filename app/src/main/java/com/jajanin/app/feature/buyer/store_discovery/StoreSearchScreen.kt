package com.jajanin.app.feature.buyer.store_discovery

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible

private const val HEADER_TOP_LIMIT = 76f

/**
 * Buyer Store Search & Marketplace Discovery Screen (`Screen.STORE_SEARCH`).
 *
 * Supports debounced search by store name/category/menu, category filter chips,
 * loading/empty/error states, and paginated lazy loading.
 */
internal fun JajaninView.drawStoreSearch(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bottomLimit = h - 12f

    val bodySave = c.save()
    c.clipRect(0f, HEADER_TOP_LIMIT, w, h)

    var y = 88f + off

    // 1. Inline Search Input Bar (Direct in-box debounced live typing, no popup dialog)
    val searchBar = RectF(20f, y, 370f, y + 44f)
    val storeSearchFocused = inlineSearchActiveTarget == InlineSearchOverlay.TARGET_STORE
    if (searchBar.bottom >= HEADER_TOP_LIMIT && searchBar.top <= bottomLimit) {
        val barActive = storeSearchFocused || storeSearchQuery.isNotBlank()
        kit.rounded(c, searchBar, kit.card, 10f, if (barActive) kit.blue else kit.border)
        AppIcon.draw(c, "search", 38f, searchBar.centerY(), 16f, if (barActive) kit.blue else kit.muted)
        if (storeSearchFocused) {
            setInlineSearchFieldBounds(
                left = 56f,
                top = searchBar.top,
                right = if (storeSearchQuery.isNotBlank()) 332f else 360f,
                bottom = searchBar.bottom,
                textSize = 13f,
            )
        } else {
            val placeholder = "Cari nama warung, kategori, atau menu..."
            val textToDraw = storeSearchQuery.ifBlank { placeholder }
            kit.textEllipsized(
                c,
                textToDraw,
                56f,
                y + 27f,
                if (storeSearchQuery.isBlank()) 298f else 272f,
                13f,
                if (storeSearchQuery.isBlank()) kit.muted else kit.ink,
                storeSearchQuery.isNotBlank(),
            )
        }
        if (storeSearchQuery.isNotBlank()) {
            val clearHit = RectF(334f, y + 6f, 366f, y + 38f)
            AppIcon.draw(c, "close", clearHit.centerX(), clearHit.centerY(), 13f, kit.muted)
            bounds.putVisible("storeSearchClear", clearHit, topLimit = HEADER_TOP_LIMIT, bottomLimit = bottomLimit)
        }
    }
    bounds.putVisible(
        "storeSearchInput",
        RectF(20f, y, if (storeSearchQuery.isNotBlank()) 332f else 370f, y + 44f),
        topLimit = HEADER_TOP_LIMIT,
        bottomLimit = bottomLimit,
    )
    y += 54f

    // 2. Category Filter Chips (All, Open, Verified, Food, Drinks)
    if (y + 32f >= HEADER_TOP_LIMIT && y <= bottomLimit) {
        var cx = 20f
        STORE_DISCOVERY_CATEGORIES.forEach { (catKey, label) ->
            val active = storeSearchCategory == catKey
            val extraIcon = if (catKey == "Verified") 14f else 0f
            val chipW = kit.measure(label, 10.5f, true) + 16f + extraIcon
            if (cx + chipW <= 374f) {
                val chip = RectF(cx, y, cx + chipW, y + 30f)
                kit.rounded(
                    c,
                    chip,
                    if (active) kit.blue else kit.card,
                    15f,
                    if (active) null else kit.border,
                )
                if (catKey == "Verified") {
                    AppIcon.draw(
                        c,
                        "verified",
                        cx + 12f,
                        chip.centerY(),
                        11.5f,
                        if (active) Color.WHITE else kit.blue,
                    )
                    kit.text(
                        c,
                        label,
                        cx + 20f,
                        chip.centerY() + 3.8f,
                        10.5f,
                        if (active) Color.WHITE else kit.ink,
                        true,
                    )
                } else {
                    kit.centerText(c, label, chip, 10.5f, if (active) Color.WHITE else kit.ink, true)
                }
                press(c, "storeSearchCat:$catKey", chip, 15f)
                bounds.putVisible("storeSearchCat:$catKey", chip, topLimit = HEADER_TOP_LIMIT, bottomLimit = bottomLimit)
                cx += chipW + 5f
            }
        }
    }
    y += 42f

    // 3. Result Counter
    if (!storeSearchLoading || storeSearchResults.isNotEmpty()) {
        val countText = if (storeSearchResults.isEmpty()) {
            "Tidak ada warung ditemukan"
        } else {
            "Menampilkan ${storeSearchResults.size} dari $storeSearchTotal warung"
        }
        if (y + 18f >= HEADER_TOP_LIMIT && y <= bottomLimit) {
            kit.text(c, countText, 20f, y + 12f, 11.5f, kit.muted, true)
        }
        y += 22f
    }

    // 4. States: Loading, Error, Empty, Results + Lazy Pagination
    when {
        storeSearchLoading && storeSearchResults.isEmpty() -> {
            kit.centerText(c, "Mencari warung...", RectF(24f, y + 60f, 366f, y + 100f), 14f, kit.muted)
            y += 160f
        }
        storeSearchError != null && storeSearchResults.isEmpty() -> {
            val errCard = RectF(20f, y + 18f, 370f, y + 142f)
            kit.rounded(c, errCard, kit.card, 12f, kit.dangerBorder)
            AppIcon.draw(c, "warning", errCard.centerX(), y + 46f, 24f, kit.danger)
            kit.centerText(c, "Gagal memuat daftar warung", RectF(32f, y + 64f, 358f, y + 86f), 13.5f, kit.danger, true)
            kit.centerText(c, (storeSearchError ?: "").take(56), RectF(32f, y + 84f, 358f, y + 104f), 11f, kit.muted)
            val retryBtn = RectF(130f, y + 104f, 260f, y + 134f)
            kit.rounded(c, retryBtn, kit.blue, 8f)
            kit.centerText(c, "Coba Lagi", retryBtn, 12f, Color.WHITE, true)
            bounds.putVisible("storeSearchRetry", retryBtn, topLimit = HEADER_TOP_LIMIT, bottomLimit = bottomLimit)
            y += 164f
        }
        storeSearchResults.isEmpty() -> {
            val emptyCard = RectF(20f, y + 18f, 370f, y + 146f)
            kit.rounded(c, emptyCard, kit.card, 12f, kit.border)
            AppIcon.draw(c, "store", emptyCard.centerX(), y + 50f, 28f, kit.muted)
            kit.centerText(c, "Warung tidak ditemukan", RectF(32f, y + 72f, 358f, y + 96f), 14f, kit.ink, true)
            val hint = if (storeSearchQuery.isNotBlank() || storeSearchCategory != "All") {
                "Coba ubah kata kunci pencarian atau pilih kategori Semua."
            } else {
                "Belum ada warung publik yang terdaftar saat ini."
            }
            kit.centerText(c, hint, RectF(32f, y + 94f, 358f, y + 118f), 11f, kit.muted)
            if (storeSearchQuery.isNotBlank() || storeSearchCategory != "All") {
                val resetBtn = RectF(122f, y + 114f, 268f, y + 138f)
                kit.rounded(c, resetBtn, kit.tint, 8f, kit.blue)
                kit.centerText(c, "Reset Pencarian", resetBtn, 11.5f, kit.blue, true)
                bounds.putVisible("storeSearchReset", resetBtn, topLimit = HEADER_TOP_LIMIT, bottomLimit = bottomLimit)
            }
            y += 170f
        }
        else -> {
            storeSearchResults.forEach { store ->
                y = drawStoreSearchCard(c, store, y, HEADER_TOP_LIMIT, bottomLimit)
                y += 12f
            }

            // Automatic lazy pagination trigger when scrolled near bottom + explicit button fallback
            if (storeSearchHasMore) {
                val nearBottom = y <= h + 120f
                if (nearBottom && !storeSearchLoadingMore) {
                    activity()?.let { StoreSearchViewModel.loadNextPage(it) }
                }
                val moreRect = RectF(20f, y + 4f, 370f, y + 44f)
                if (moreRect.bottom >= HEADER_TOP_LIMIT && moreRect.top <= bottomLimit) {
                    kit.rounded(c, moreRect, kit.card, 10f, kit.border)
                    val moreText = if (storeSearchLoadingMore) "Memuat warung lainnya..." else "Muat Lebih Banyak Warung"
                    kit.centerText(c, moreText, moreRect, 12f, if (storeSearchLoadingMore) kit.muted else kit.blue, true)
                    if (!storeSearchLoadingMore) {
                        press(c, "storeSearchMore", moreRect, 10f)
                        bounds.putVisible("storeSearchMore", moreRect, topLimit = HEADER_TOP_LIMIT, bottomLimit = bottomLimit)
                    }
                }
                y += 58f
            }
        }
    }

    c.restoreToCount(bodySave)
    contentHeight = (y - off) + 28f

    header(c, "Cari Warung")
}
