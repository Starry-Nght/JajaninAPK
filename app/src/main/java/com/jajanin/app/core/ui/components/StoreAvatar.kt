package com.jajanin.app.core.ui.components

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.feature.seller.loadProductImage

/**
 * Reusable Store Avatar / Logo component.
 *
 * Displays the store's uploaded `logoUrl` center-cropped with rounded border, or a clean
 * themed fallback combining store monogram / `ic_app_store` icon in Light & Dark themes.
 */
internal object StoreAvatar {

    fun draw(
        view: JajaninView,
        c: Canvas,
        storeName: String,
        logoUrl: String?,
        rect: RectF,
        cornerRadius: Float = 12f,
        withCardRing: Boolean = false,
    ) {
        val kit = view.kit
        if (withCardRing) {
            val outer = RectF(rect.left - 3f, rect.top - 3f, rect.right + 3f, rect.bottom + 3f)
            kit.rounded(c, outer, kit.bg, cornerRadius + 3f)
        }
        val validUrl = logoUrl?.trim()?.takeIf { it.startsWith("https://") }
        val bmp = validUrl?.let { ProductImageCache.images[it] }
        if (bmp != null) {
            drawBitmapCenterCrop(c, bmp, rect, cornerRadius)
            kit.rounded(c, rect, android.graphics.Color.TRANSPARENT, cornerRadius, kit.border)
            return
        }
        if (validUrl != null) {
            view.activity()?.loadProductImage(validUrl)
        }
        kit.rounded(c, rect, kit.tint, cornerRadius, kit.border)
        val initial = storeName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
        if (initial != null && rect.width() >= 38f) {
            kit.centerText(c, initial, rect, (rect.height() * 0.38f).coerceIn(13f, 24f), kit.blue, true)
        } else {
            AppIcon.draw(c, "store", rect.centerX(), rect.centerY(), (rect.width() * 0.48f).coerceIn(14f, 26f), kit.blue)
        }
    }
}
