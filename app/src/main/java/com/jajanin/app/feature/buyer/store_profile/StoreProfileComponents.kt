package com.jajanin.app.feature.buyer.store_profile

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.StoreAvatar
import com.jajanin.app.core.ui.components.StoreCoverImage
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.drawProductImage
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.store_discovery.PublicStoreDetail

/**
 * Modular visual sections for the Public Store Profile (`StoreProfileScreen.kt`).
 */
internal object StoreProfileComponents {

    fun drawHeroAndIdentity(
        view: JajaninView,
        c: Canvas,
        store: PublicStoreDetail,
        top: Float,
        w: Float,
        firstProductId: String?,
        topLimit: Float,
        bottomLimit: Float,
    ): Float {
        val kit = view.kit
        val bannerH = 128f
        val bannerRect = RectF(0f, top, w, top + bannerH)
        StoreCoverImage.draw(view, c, store.bannerUrl, bannerRect, cornerRadius = 0f)

        // Operating Status Pill on Cover
        val openLabel = if (store.isOpen) "Sedang Buka" else "Sedang Tutup"
        val openBg = if (store.isOpen) kit.successSoft else kit.dangerBorder
        val openFg = if (store.isOpen) kit.success else kit.danger
        val openW = kit.measure(openLabel, 10f, true) + 18f
        val openPill = RectF(w - 20f - openW, top + bannerH - 32f, w - 20f, top + bannerH - 10f)
        kit.rounded(c, openPill, openBg, 7f)
        kit.centerText(c, openLabel, openPill, 10f, openFg, true)

        // Overlapping Store Avatar
        val avatarRect = RectF(20f, top + bannerH - 34f, 84f, top + bannerH + 30f)
        StoreAvatar.draw(view, c, store.name, store.logoUrl, avatarRect, cornerRadius = 14f, withCardRing = true)

        // Chat Penjual & Laporkan Warung Action Buttons (when viewing another seller's warung)
        val isOwnStore = (!store.ownerId.isNullOrBlank() && store.ownerId == SupabaseRepository.currentUid()) ||
            (!view.myStallId.isNullOrBlank() && view.myStallId == store.id)
        if (!isOwnStore) {
            val chatBtn = RectF(w - 134f, top + bannerH + 6f, w - 20f, top + bannerH + 38f)
            kit.rounded(c, chatBtn, kit.tint, 8f, kit.blue)
            AppIcon.draw(c, "chat", chatBtn.left + 16f, chatBtn.centerY(), 13f, kit.blue)
            kit.text(c, "Chat Penjual", chatBtn.left + 27f, chatBtn.centerY() + 4f, 10.5f, kit.blue, true)
            view.press(c, "chatStore:${store.id}", chatBtn, 8f)
            view.bounds.putVisible("chatStore:${store.id}", chatBtn, topLimit = topLimit, bottomLimit = bottomLimit)

            val repBtn = RectF(chatBtn.left - 82f, top + bannerH + 6f, chatBtn.left - 8f, top + bannerH + 38f)
            kit.rounded(c, repBtn, kit.card, 8f, kit.dangerBorder)
            kit.centerText(c, "Laporkan", repBtn, 10.5f, kit.danger, true)
            view.press(c, "reportStore:${store.id}", repBtn, 8f)
            view.bounds.putVisible("reportStore:${store.id}", repBtn, topLimit = topLimit, bottomLimit = bottomLimit)
        }

        var y = top + bannerH + 46f

        // Store Name + Reusable Verified Seal (& Dev Shield for NIS 11855)
        val isDevStore = StoreVerifiedBadge.isDevIdentity(
            view = view,
            name = store.name,
            ownerId = store.ownerId.orEmpty(),
            stallId = store.id,
        ) || StoreVerifiedBadge.isDevIdentity(view = view, name = store.sellerName)
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = view,
            c = c,
            storeName = store.name,
            isVerified = store.isVerified || isDevStore,
            x = 20f,
            baselineY = y + 14f,
            maxTotalWidth = 350f,
            textSize = 20f,
            textColor = kit.ink,
            bold = true,
            isDev = isDevStore,
            ownerId = store.ownerId.orEmpty(),
            stallId = store.id,
        )
        y += 24f

        // Category + Verified Achievement Pill
        var chipX = 20f
        if (store.isVerified || isDevStore) {
            val vPill = StoreVerifiedBadge.drawPill(
                view = view,
                c = c,
                left = chipX,
                top = y,
                label = if (isDevStore) "Terverifikasi" else "Terverifikasi · 20+ Selesai",
                height = 22f,
                textSize = 9.5f,
                isDev = isDevStore,
            )
            chipX = vPill.right + 6f
        }
        val catLabel = store.storeCategory.ifBlank { "Makanan & Minuman" }
        val catW = (kit.measure(catLabel, 9.5f, true) + 16f).coerceAtMost(370f - chipX)
        if (catW > 40f) {
            val catPill = RectF(chipX, y, chipX + catW, y + 22f)
            kit.rounded(c, catPill, kit.card, 6f, kit.border)
            kit.centerText(c, catLabel, catPill, 9.5f, kit.muted, true)
        }
        y += 30f

        if (store.sellerName.isNotBlank()) {
            kit.textEllipsized(c, "Dikelola oleh ${store.sellerName}", 20f, y + 10f, 350f, 11.5f, kit.muted)
            y += 18f
        }

        return y + 6f
    }

    fun drawMetricsAndInfoSection(
        view: JajaninView,
        c: Canvas,
        store: PublicStoreDetail,
        productCount: Int,
        top: Float,
    ): Float {
        val kit = view.kit
        var y = top

        // 3-Column Metrics Surface (Rating, Completed Orders, Active Products)
        val statsRect = RectF(20f, y, 370f, y + 58f)
        kit.rounded(c, statsRect, kit.card, 10f, kit.border)
        val colW = statsRect.width() / 3f
        val ratingMain = if (store.ratingCount > 0) {
            "★ ${String.format(java.util.Locale.US, "%.1f", store.avgRating)}"
        } else {
            "Baru"
        }
        val ratingSub = if (store.ratingCount > 0) "${store.ratingCount} ulasan" else "Belum ada rating"
        val cols = listOf(
            Triple(ratingMain, ratingSub, Color.rgb(217, 119, 6)),
            Triple(store.completedOrdersCount.toString(), "Pesanan Selesai", kit.blue),
            Triple(productCount.toString(), "Menu Aktif", kit.ink),
        )
        cols.forEachIndexed { idx, (valStr, label, clr) ->
            val left = 20f + idx * colW
            if (idx > 0) kit.line(c, left, y + 10f, left, y + 48f, kit.divider)
            kit.textEllipsized(c, valStr, left + 12f, y + 25f, colW - 20f, 14f, clr, true)
            kit.textEllipsized(c, label, left + 12f, y + 43f, colW - 20f, 10f, kit.muted)
        }
        y += 70f

        // 3-Aspect Rating Breakdown (Toko, Durasi, Rasa) + Recent Reviews
        if (store.ratingCount > 0) {
            val shownReviews = view.storeProfileRatings.take(3)
            val reviewLayouts = shownReviews.map { rev ->
                val commentLines = if (rev.comment.isNotBlank()) {
                    kit.wrap(rev.comment, 324f, 11f, maxLines = 2)
                } else {
                    emptyList()
                }
                rev to commentLines
            }
            val reviewsH = reviewLayouts.sumOf { (_, lines) ->
                (38f + lines.size * 14f).toDouble()
            }.toFloat()
            val rateCardH = 74f + (if (reviewLayouts.isNotEmpty()) reviewsH + 10f else 0f)
            val rateCard = RectF(20f, y, 370f, y + rateCardH)
            kit.rounded(c, rateCard, kit.card, 10f, kit.border)

            val gold = Color.rgb(217, 119, 6)
            kit.text(c, "PENILAIAN PEMBELI (${store.ratingCount})", 32f, y + 20f, 9.5f, kit.muted, true)
            kit.textRight(
                c,
                "★ ${String.format(java.util.Locale.US, "%.1f", store.avgRating)} / 5.0",
                358f,
                y + 20f,
                96f,
                11f,
                gold,
                true,
            )

            val aspectW = (326f - 12f) / 3f
            val aspects = listOf(
                "Toko" to store.avgRatingStore,
                "Durasi" to store.avgRatingDuration,
                "Rasa" to store.avgRatingTaste,
            )
            aspects.forEachIndexed { idx, (lbl, score) ->
                val ax = 32f + idx * (aspectW + 6f)
                val pill = RectF(ax, y + 29f, ax + aspectW, y + 63f)
                kit.rounded(c, pill, kit.bg, 7f, kit.border)
                kit.text(c, lbl, ax + 10f, y + 43f, 9.5f, kit.muted, true)
                kit.text(
                    c,
                    "★ ${String.format(java.util.Locale.US, "%.1f", score)}",
                    ax + 10f,
                    y + 57f,
                    12f,
                    gold,
                    true,
                )
            }

            if (reviewLayouts.isNotEmpty()) {
                var ry = y + 73f
                reviewLayouts.forEachIndexed { rIdx, (rev, commentLines) ->
                    kit.line(c, 32f, ry, 358f, ry, kit.divider)
                    kit.textEllipsized(c, rev.buyerName, 32f, ry + 16f, 185f, 11.5f, kit.ink, true)
                    kit.textRight(
                        c,
                        "★ ${String.format(java.util.Locale.US, "%.1f", rev.ratingOverall)} (T${rev.ratingStore}·D${rev.ratingDuration}·R${rev.ratingTaste})",
                        358f,
                        ry + 16f,
                        145f,
                        10f,
                        gold,
                        true,
                    )
                    commentLines.forEachIndexed { cIdx, cLine ->
                        kit.text(c, cLine, 32f, ry + 31f + cIdx * 14f, 11f, kit.muted)
                    }
                    ry += 36f + commentLines.size * 14f
                    if (rIdx == reviewLayouts.lastIndex) ry += 2f
                }
            }
            y += rateCardH + 12f
        }

        // Store Bio & Location Information Card
        val bioText = store.bio.trim()
        val locText = store.location.trim()
        if (bioText.isNotBlank() || locText.isNotBlank() || !store.isOpen) {
            val bioLines = if (bioText.isNotBlank()) 2 else 0
            val locExtra = if (locText.isNotBlank()) 24f else 0f
            val closedExtra = if (!store.isOpen) 24f else 0f
            val infoH = 30f + (if (bioLines > 0) 38f else 0f) + locExtra + closedExtra
            val infoRect = RectF(20f, y, 370f, y + infoH)
            kit.rounded(c, infoRect, kit.card, 10f, kit.border)

            kit.text(c, "INFORMASI WARUNG", 32f, y + 20f, 9.5f, kit.muted, true)
            var iy = y + 36f
            if (bioText.isNotBlank()) {
                kit.textWrapped(c, bioText, 32f, iy, 326f, 12f, kit.ink, maxLines = 2, lineHeight = 17f)
                iy += 36f
            }
            if (locText.isNotBlank()) {
                AppIcon.draw(c, "location", 38f, iy - 4f, 13f, kit.blue)
                kit.textEllipsized(c, "Info ambil: $locText", 50f, iy, 308f, 11f, kit.ink, true)
                iy += 22f
            }
            if (!store.isOpen) {
                AppIcon.draw(c, "warning", 38f, iy - 4f, 13f, kit.danger)
                kit.textEllipsized(
                    c,
                    "Warung sedang tutup sementara. Kamu tetap bisa melihat katalog menu.",
                    50f,
                    iy,
                    308f,
                    10.5f,
                    kit.danger,
                    true,
                )
            }
            y += infoH + 14f
        }

        return y
    }

    fun drawCategoryTabs(
        view: JajaninView,
        c: Canvas,
        activeCategory: String,
        allCount: Int,
        foodCount: Int,
        drinkCount: Int,
        top: Float,
        topLimit: Float,
        bottomLimit: Float,
    ): Float {
        val kit = view.kit
        val tabs = listOf(
            "All" to "Semua ($allCount)",
            "Food" to "Makanan ($foodCount)",
            "Drinks" to "Minuman ($drinkCount)",
        )
        kit.line(c, 20f, top + 32f, 370f, top + 32f, kit.divider)
        var tx = 20f
        tabs.forEach { (key, label) ->
            val on = activeCategory == key
            val lw = kit.measure(label, 12.5f, true)
            val hit = RectF(tx, top, tx + lw + 14f, top + 32f)
            kit.text(c, label, tx, top + 21f, 12.5f, if (on) kit.blue else kit.muted, on)
            if (on) {
                kit.rect(c, tx, top + 30f, tx + lw, top + 32.5f, kit.blue)
            }
            view.bounds.putVisible("storeProfCat:$key", hit, topLimit = topLimit, bottomLimit = bottomLimit)
            tx += lw + 22f
        }
        return top + 42f
    }

    fun drawStoreProductGrid(
        view: JajaninView,
        c: Canvas,
        products: List<ProductRow>,
        storeOpen: Boolean,
        top: Float,
        w: Float,
        topLimit: Float,
        bottomLimit: Float,
    ): Float {
        val kit = view.kit
        if (products.isEmpty()) {
            val emptyBox = RectF(20f, top + 8f, 370f, top + 108f)
            kit.rounded(c, emptyBox, kit.card, 10f, kit.border)
            AppIcon.draw(c, "box", emptyBox.centerX(), top + 34f, 22f, kit.muted)
            kit.centerText(c, "Belum ada menu di kategori ini.", RectF(32f, top + 50f, 358f, top + 74f), 12.5f, kit.ink, true)
            kit.centerText(c, "Gunakan tombol Chat Penjual di atas untuk bertanya langsung.", RectF(28f, top + 72f, 362f, top + 98f), 10.5f, kit.muted)
            return top + 120f
        }

        val tileW = (w - 40f - 12f) / 2f
        val photoH = 114f
        val tileH = photoH + 74f

        products.forEachIndexed { idx, product ->
            val col = idx % 2
            val row = idx / 2
            val left = 20f + col * (tileW + 12f)
            val cardTop = top + row * (tileH + 12f)
            val card = RectF(left, cardTop, left + tileW, cardTop + tileH)
            if (card.bottom < topLimit || card.top > bottomLimit) return@forEachIndexed

            kit.rounded(c, card, kit.card, 10f, kit.border)
            val imgRect = RectF(left + 1f, cardTop + 1f, card.right - 1f, cardTop + photoH)
            view.drawProductImage(c, product, imgRect, 9f)

            val itemAvail = storeOpen && product.available && (product.stock == null || product.stock > 0)
            if (!product.available || (product.stock != null && product.stock <= 0)) {
                val tag = RectF(left + 7f, cardTop + 7f, left + 58f, cardTop + 26f)
                kit.rounded(c, tag, Color.argb(205, 20, 25, 40), 6f)
                kit.centerText(c, "Habis", tag, 9.5f, Color.WHITE, true)
            } else if (product.stock != null && product.stock <= 5) {
                val tag = RectF(left + 7f, cardTop + 7f, left + 68f, cardTop + 26f)
                kit.rounded(c, tag, Color.argb(205, 20, 25, 40), 6f)
                kit.centerText(c, "Sisa ${product.stock}", tag, 9.5f, Color.WHITE, true)
            }

            // Schedule pill on bottom-left of photo
            val sched = product.scheduleBadgeLabel
            val schedW = (kit.measure(sched, 8.5f, true) + 12f).coerceAtMost(tileW - 14f)
            val schedRect = RectF(left + 7f, cardTop + photoH - 22f, left + 7f + schedW, cardTop + photoH - 5f)
            val schedBg = if (product.orderSchedule == "next_day") Color.argb(225, 183, 116, 0) else Color.argb(215, 0, 99, 214)
            kit.rounded(c, schedRect, schedBg, 5f)
            kit.centerText(c, sched, schedRect, 8.5f, Color.WHITE, true)

            kit.textEllipsized(c, product.name, left + 9f, cardTop + photoH + 20f, tileW - 18f, 12.5f, kit.ink, true)
            kit.textEllipsized(c, formatRupiah(product.priceIdr), left + 9f, cardTop + photoH + 39f, tileW - 18f, 12f, kit.blue, true)

            val inCartQty = com.jajanin.app.data.model.totalQtyForProductInCart(view.cart, product.id)
            if (itemAvail) {
                if (product.hasVariants) {
                    val label = if (inCartQty > 0) "${inCartQty}x · Pilih" else "+ Pilih"
                    val addBtn = RectF(card.right - 78f, cardTop + photoH + 44f, card.right - 8f, cardTop + tileH - 7f)
                    kit.rounded(c, addBtn, if (inCartQty > 0) kit.tint else kit.blue, 6f, if (inCartQty > 0) kit.blue else null)
                    kit.centerText(c, label, addBtn, 9.5f, if (inCartQty > 0) kit.blue else Color.WHITE, true)
                    view.bounds.putVisible("add:${product.id}", addBtn, topLimit = topLimit, bottomLimit = bottomLimit)
                } else if (inCartQty > 0) {
                    val step = RectF(card.right - 78f, cardTop + photoH + 44f, card.right - 8f, cardTop + tileH - 7f)
                    kit.rounded(c, step, kit.tint, 6f, kit.blue)
                    val minusHit = RectF(step.left, step.top, step.left + 24f, step.bottom)
                    val plusHit = RectF(step.right - 24f, step.top, step.right, step.bottom)
                    AppIcon.draw(c, "minus", minusHit.centerX(), minusHit.centerY(), 11f, kit.blue)
                    kit.centerText(c, "$inCartQty", step, 11f, kit.ink, true)
                    AppIcon.draw(c, "plus", plusHit.centerX(), plusHit.centerY(), 11f, kit.blue)
                    view.bounds.putVisible("cartMinus:${product.id}", minusHit, topLimit = topLimit, bottomLimit = bottomLimit)
                    view.bounds.putVisible("cartPlus:${product.id}", plusHit, topLimit = topLimit, bottomLimit = bottomLimit)
                } else {
                    val addBtn = RectF(card.right - 74f, cardTop + photoH + 44f, card.right - 8f, cardTop + tileH - 7f)
                    kit.rounded(c, addBtn, kit.blue, 6f)
                    kit.centerText(c, "+ Tambah", addBtn, 10f, Color.WHITE, true)
                    view.bounds.putVisible("add:${product.id}", addBtn, topLimit = topLimit, bottomLimit = bottomLimit)
                }
            } else {
                val closedTxt = if (!storeOpen) "Tutup" else "Habis"
                kit.textRight(c, closedTxt, card.right - 10f, cardTop + tileH - 13f, 58f, 10f, kit.muted, true)
            }

            // Tap upper area of card to inspect Product Detail
            val detailHit = RectF(card.left, card.top, card.right, cardTop + photoH + 40f)
            view.bounds.putVisible("item:${product.id}", detailHit, topLimit = topLimit, bottomLimit = bottomLimit)
        }

        val rows = (products.size + 1) / 2
        return top + rows * (tileH + 12f)
    }
}
