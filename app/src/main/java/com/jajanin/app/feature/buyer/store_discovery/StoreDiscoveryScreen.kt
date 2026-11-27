package com.jajanin.app.feature.buyer.store_discovery

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.StoreAvatar
import com.jajanin.app.core.ui.components.StoreCoverImage
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah

/**
 * Reusable Store Discovery showcase section & Store Card components used on Buyer Home,
 * Explore, and Store Search screens.
 */
internal fun JajaninView.drawHomeStoreDiscoverySection(
    c: Canvas,
    top: Float,
    isVisible: (Float, Float) -> Boolean,
    topLimit: Float,
    bottomLimit: Float,
): Float {
    val stores = storeDirectoryById.values
        .sortedWith(
            compareByDescending<PublicStoreSummary> { it.isOpen }
                .thenByDescending { it.isVerified }
                .thenByDescending { it.completedOrdersCount }
                .thenBy { it.name.lowercase() }
        )
        .take(3)

    var y = top
    if (isVisible(y, y + 24f)) {
        kit.text(c, "Jelajahi Warung", 20f, y + 16f, 15f, kit.ink, true)
        drawTrailingActionWithArrow(c, "Cari toko", 370f, y + 16f, 11.5f, kit.blue)
        val seeAllHit = RectF(268f, y - 4f, 374f, y + 24f)
        bounds.putVisible("openStoreSearch", seeAllHit, topLimit = topLimit, bottomLimit = bottomLimit)
    }
    y += 26f

    if (stores.isEmpty()) {
        val discoverBanner = RectF(20f, y, 370f, y + 54f)
        if (isVisible(discoverBanner.top, discoverBanner.bottom)) {
            kit.rounded(c, discoverBanner, kit.card, 10f, kit.border)
            AppIcon.draw(c, "store", 42f, discoverBanner.centerY(), 20f, kit.blue)
            kit.text(c, "Cari & lihat profil warung di Jajanin", 60f, y + 24f, 12.5f, kit.ink, true)
            kit.text(c, "Lihat menu lengkap, status buka, dan lencana toko", 60f, y + 41f, 10.5f, kit.muted)
            AppIcon.draw(c, "chevronRight", 354f, discoverBanner.centerY(), 15f, kit.blue)
            press(c, "openStoreSearch", discoverBanner, 10f)
        }
        bounds.putVisible("openStoreSearch", discoverBanner, topLimit = topLimit, bottomLimit = bottomLimit)
        return y + 66f
    }

    val cardW = (350f - 16f) / 3f
    val cardH = 122f
    if (isVisible(y, y + cardH)) {
        stores.forEachIndexed { idx, store ->
            val left = 20f + idx * (cardW + 8f)
            val card = RectF(left, y, left + cardW, y + cardH)
            kit.rounded(c, card, kit.card, 10f, kit.border)

            val coverRect = RectF(left + 1f, y + 1f, card.right - 1f, y + 42f)
            StoreCoverImage.draw(this, c, store.bannerUrl, coverRect, cornerRadius = 9f)

            // Status dot pill in top-right of mini cover
            val stDotBg = if (store.isOpen) kit.successSoft else kit.dangerBorder
            val stDotFg = if (store.isOpen) kit.success else kit.danger
            val stText = if (store.isOpen) "Buka" else "Tutup"
            val stW = kit.measure(stText, 8.5f, true) + 10f
            val stPill = RectF(card.right - stW - 5f, y + 5f, card.right - 5f, y + 21f)
            kit.rounded(c, stPill, stDotBg, 5f)
            kit.centerText(c, stText, stPill, 8.5f, stDotFg, true)

            val avatarRect = RectF(left + 8f, y + 22f, left + 42f, y + 56f)
            StoreAvatar.draw(this, c, store.name, store.logoUrl, avatarRect, cornerRadius = 8f, withCardRing = true)

            StoreVerifiedBadge.drawInlineWithStoreName(
                view = this,
                c = c,
                storeName = store.name,
                isVerified = store.isVerified,
                x = left + 8f,
                baselineY = y + 74f,
                maxTotalWidth = cardW - 16f,
                textSize = 11.5f,
                textColor = kit.ink,
                bold = true,
                stallId = store.id,
            )

            kit.textEllipsized(
                c,
                store.storeCategory,
                left + 8f,
                y + 91f,
                cardW - 16f,
                9.5f,
                kit.muted,
            )

            val metaLine = "${store.completedOrdersCount} selesai · ${store.activeProductCount} menu"
            kit.textEllipsized(
                c,
                metaLine,
                left + 8f,
                y + 108f,
                cardW - 16f,
                9.5f,
                kit.blue,
                true,
            )

            press(c, "openStore:${store.id}", card, 10f)
            bounds.putVisible("openStore:${store.id}", card, topLimit = topLimit, bottomLimit = bottomLimit)
        }
    }
    return y + cardH + 16f
}

/**
 * Full-width rich Store Discovery card used inside `StoreSearchScreen`.
 */
internal fun JajaninView.drawStoreSearchCard(
    c: Canvas,
    store: PublicStoreSummary,
    top: Float,
    topLimit: Float,
    bottomLimit: Float,
): Float {
    val cardH = 144f
    val card = RectF(20f, top, 370f, top + cardH)
    if (card.bottom >= topLimit && card.top <= bottomLimit) {
        kit.rounded(c, card, kit.card, 12f, kit.border)

        val bannerRect = RectF(21f, top + 1f, 369f, top + 58f)
        StoreCoverImage.draw(this, c, store.bannerUrl, bannerRect, cornerRadius = 11f)

        // Open / Closed badge on top-right of banner
        val openLabel = if (store.isOpen) "Buka" else "Tutup"
        val openBg = if (store.isOpen) kit.successSoft else kit.dangerBorder
        val openFg = if (store.isOpen) kit.success else kit.danger
        val openW = kit.measure(openLabel, 9.5f, true) + 16f
        val openPill = RectF(358f - openW, top + 10f, 358f, top + 30f)
        kit.rounded(c, openPill, openBg, 6f)
        kit.centerText(c, openLabel, openPill, 9.5f, openFg, true)

        val avatarRect = RectF(32f, top + 32f, 80f, top + 80f)
        StoreAvatar.draw(this, c, store.name, store.logoUrl, avatarRect, cornerRadius = 11f, withCardRing = true)

        // Store Name + Reusable StoreVerifiedBadge
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = store.name,
            isVerified = store.isVerified,
            x = 92f,
            baselineY = top + 76f,
            maxTotalWidth = 264f,
            textSize = 15f,
            textColor = kit.ink,
            bold = true,
            stallId = store.id,
        )

        val catAndSeller = buildList {
            add(store.storeCategory)
            if (store.sellerName.isNotBlank()) add(store.sellerName)
        }.joinToString(" · ")
        kit.textEllipsized(c, catAndSeller, 32f, top + 99f, 324f, 11f, kit.muted)

        val bioOrStats = store.bio.takeIf { it.isNotBlank() }
            ?: if (store.location.isNotBlank()) "Lokasi ambil: ${store.location}" else "Ketuk untuk melihat menu & profil warung"
        kit.textEllipsized(c, bioOrStats, 32f, top + 116f, 232f, 10.5f, kit.muted)

        kit.line(c, 32f, top + 123f, 358f, top + 123f, kit.divider)
        val statsLine = buildList {
            add("${store.completedOrdersCount} pesanan selesai")
            add("${store.activeProductCount} menu aktif")
            store.minPriceIdr?.let { add("mulai ${formatRupiah(it)}") }
        }.joinToString(" · ")
        kit.textEllipsized(c, statsLine, 32f, top + 137f, 260f, 10f, kit.blue, true)
        drawTrailingActionWithArrow(c, "Kunjungi", 358f, top + 137f, 10.5f, kit.blue)

        press(c, "openStore:${store.id}", card, 12f)
        bounds.putVisible("openStore:${store.id}", card, topLimit = topLimit, bottomLimit = bottomLimit)
    }
    return top + cardH
}
