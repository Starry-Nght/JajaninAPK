package com.jajanin.app.feature.buyer.store_profile

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.feature.buyer.home.normalizeCategory

private const val PINNED_HEADER_H = 60f

/**
 * Public Store Profile Screen (`Screen.STORE_PROFILE`).
 *
 * Allows Buyers to inspect any Seller/Warung's cover, avatar, verified badge,
 * operating status, completed orders, bio, location, and product catalog without
 * needing to place an order first.
 */
internal fun JajaninView.drawStoreProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val store = storeProfileDetail
    val off = -scroll
    val hasCartBar = cart.isNotEmpty()
    val bottomClip = if (hasCartBar) h - 66f else h
    val showCollapsedBar = scroll > 92f && store != null
    val topClip = if (showCollapsedBar) PINNED_HEADER_H else 0f

    val bodySave = c.save()
    c.clipRect(0f, topClip, w, bottomClip)

    when {
        store == null && storeProfileLoading -> {
            kit.centerText(c, "Memuat profil warung...", RectF(24f, 260f, 366f, 320f), 14f, kit.muted)
            contentHeight = 0f
        }
        store == null && storeProfileError != null -> {
            val errCard = RectF(20f, 160f, 370f, 296f)
            kit.rounded(c, errCard, kit.card, 12f, kit.dangerBorder)
            AppIcon.draw(c, "warning", errCard.centerX(), 194f, 26f, kit.danger)
            kit.centerText(c, "Gagal memuat profil warung", RectF(32f, 214f, 358f, 238f), 14f, kit.danger, true)
            kit.centerText(c, (storeProfileError ?: "").take(56), RectF(32f, 238f, 358f, 258f), 11f, kit.muted)
            val retryBtn = RectF(130f, 258f, 260f, 288f)
            kit.rounded(c, retryBtn, kit.blue, 8f)
            kit.centerText(c, "Muat Ulang", retryBtn, 12f, Color.WHITE, true)
            bounds["storeProfRetry"] = RectF(retryBtn)
            contentHeight = 0f
        }
        store == null -> {
            kit.centerText(c, "Warung tidak ditemukan.", RectF(24f, 260f, 366f, 320f), 14f, kit.muted)
            contentHeight = 0f
        }
        else -> {
            val allItems = storeProfileProducts
            val foodCount = allItems.count { normalizeCategory(it.category) == "Food" }
            val drinkCount = allItems.count { normalizeCategory(it.category) == "Drinks" }
            val filtered = when (storeProfileCategoryFilter) {
                "Food" -> allItems.filter { normalizeCategory(it.category) == "Food" }
                "Drinks" -> allItems.filter { normalizeCategory(it.category) == "Drinks" }
                else -> allItems
            }

            var y = off
            y = StoreProfileComponents.drawHeroAndIdentity(
                view = this,
                c = c,
                store = store,
                top = y,
                w = w,
                firstProductId = allItems.firstOrNull()?.id,
                topLimit = topClip,
                bottomLimit = bottomClip,
            )
            y = StoreProfileComponents.drawMetricsAndInfoSection(
                view = this,
                c = c,
                store = store,
                productCount = allItems.size,
                top = y,
            )
            y = StoreProfileComponents.drawCategoryTabs(
                view = this,
                c = c,
                activeCategory = storeProfileCategoryFilter,
                allCount = allItems.size,
                foodCount = foodCount,
                drinkCount = drinkCount,
                top = y,
                topLimit = topClip,
                bottomLimit = bottomClip,
            )
            y = StoreProfileComponents.drawStoreProductGrid(
                view = this,
                c = c,
                products = filtered,
                storeOpen = store.isOpen,
                top = y,
                w = w,
                topLimit = topClip,
                bottomLimit = bottomClip,
            )
            contentHeight = (y - off) + (if (hasCartBar) 82f else 28f)
        }
    }

    c.restoreToCount(bodySave)

    // Floating Checkout Bar if buyer added items while browsing the store
    if (hasCartBar) {
        val cartCount = cart.values.sum()
        val totalCartIdr = cart.entries.sumOf { (cartKey, qty) ->
            val pid = com.jajanin.app.data.model.productIdFromCartKey(cartKey)
            val p = storeProfileProducts.firstOrNull { it.id == pid }
                ?: products.firstOrNull { it.id == pid }
            (p?.priceIdr ?: 0) * qty
        }
        c.save()
        c.translate(-slideX, 0f)
        val floatBar = RectF(20f, h - 56f, 370f, h - 12f)
        kit.rounded(c, floatBar, kit.blue, 10f)
        kit.text(c, "$cartCount item  ·  ${formatRupiah(totalCartIdr)}", 34f, floatBar.centerY() + 4.5f, 13f, Color.WHITE, true)
        drawTrailingActionWithArrow(c, "Checkout", 356f, floatBar.centerY() + 4.5f, 13f, Color.WHITE)
        press(c, "cartCheckoutBar", floatBar, 10f)
        c.restore()
        bounds["cartCheckoutBar"] = floatBar
    }

    // Top Navigation Chrome: Floating glass pill over cover, collapsing into pinned bar on scroll
    c.save()
    c.translate(-slideX, 0f)
    val collapsedStore = if (showCollapsedBar) store else null
    if (collapsedStore != null) {
        val isOwnCollapsed = (!collapsedStore.ownerId.isNullOrBlank() && collapsedStore.ownerId == com.jajanin.app.data.repository.SupabaseRepository.currentUid()) ||
            (!myStallId.isNullOrBlank() && myStallId == collapsedStore.id)
        kit.rect(c, -100f, 0f, 490f, PINNED_HEADER_H, kit.bg)
        kit.line(c, 20f, PINNED_HEADER_H, 370f, PINNED_HEADER_H, kit.divider)
        AppIcon.draw(c, "chevronLeft", 28f, 32f, 19f, kit.ink)
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = collapsedStore.name,
            isVerified = collapsedStore.isVerified,
            x = 48f,
            baselineY = 37f,
            maxTotalWidth = if (isOwnCollapsed) 310f else 230f,
            textSize = 16.5f,
            textColor = kit.ink,
            bold = true,
            ownerId = collapsedStore.ownerId.orEmpty(),
            stallId = collapsedStore.id,
        )
        if (!isOwnCollapsed) {
            val topChatBtn = RectF(294f, 16f, 370f, 44f)
            kit.rounded(c, topChatBtn, kit.tint, 7f, kit.blue)
            AppIcon.draw(c, "chat", topChatBtn.left + 15f, topChatBtn.centerY(), 12.5f, kit.blue)
            kit.text(c, "Chat", topChatBtn.left + 26f, topChatBtn.centerY() + 4f, 11f, kit.blue, true)
            press(c, "chatStore:${collapsedStore.id}", topChatBtn, 7f)
            bounds["chatStore:${collapsedStore.id}"] = RectF(topChatBtn)
        }
    } else {
        val backCircle = RectF(16f, 14f, 52f, 50f)
        kit.rounded(c, backCircle, kit.glass, 18f, kit.glassBorder)
        AppIcon.draw(c, "chevronLeft", backCircle.centerX(), backCircle.centerY(), 19f, Color.WHITE)
    }
    c.restore()
    bounds["headerBack"] = RectF(10f, 8f, 64f, 58f)
}
