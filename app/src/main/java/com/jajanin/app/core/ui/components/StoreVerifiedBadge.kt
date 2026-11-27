package com.jajanin.app.core.ui.components

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView

/**
 * Reusable Jajanin Verified Store Badge component (`ic_store_verified.xml`).
 *
 * Represents Jajanin's internal store achievement (20+ completed orders, server-enforced).
 * Used consistently across Store Search, Public Store Profile, Product Cards, Product Detail,
 * Seller Profile, and Developer Console.
 */
internal object StoreVerifiedBadge {

    /**
     * Draws the compact verified seal icon right beside a store name.
     * Returns the right-most X coordinate occupied by `[name + badge]`.
     */
    fun drawInlineWithStoreName(
        view: JajaninView,
        c: Canvas,
        storeName: String,
        isVerified: Boolean,
        x: Float,
        baselineY: Float,
        maxTotalWidth: Float,
        textSize: Float,
        textColor: Int,
        bold: Boolean = true,
    ): Float {
        val kit = view.kit
        if (!isVerified) {
            kit.textEllipsized(c, storeName, x, baselineY, maxTotalWidth, textSize, textColor, bold)
            val measured = kit.measure(storeName, textSize, bold).coerceAtMost(maxTotalWidth)
            return x + measured
        }
        val badgeSize = (textSize * 1.12f).coerceIn(12f, 18f)
        val gap = 4.5f
        val availTextW = (maxTotalWidth - badgeSize - gap).coerceAtLeast(36f)
        kit.textEllipsized(c, storeName, x, baselineY, availTextW, textSize, textColor, bold)
        val drawnTextW = kit.measure(storeName, textSize, bold).coerceAtMost(availTextW)
        val badgeCx = x + drawnTextW + gap + badgeSize * 0.5f
        val badgeCy = baselineY - textSize * 0.34f
        AppIcon.draw(c, "verified", badgeCx, badgeCy, badgeSize, kit.blue)
        return badgeCx + badgeSize * 0.5f
    }

    /**
     * Draws a tasteful pill badge (`[seal] Terverifikasi Jajanin`) for Store Profile headers
     * and Seller/Developer status strips. Returns the rendered `RectF`.
     */
    fun drawPill(
        view: JajaninView,
        c: Canvas,
        left: Float,
        top: Float,
        label: String = "Terverifikasi",
        height: Float = 22f,
        textSize: Float = 10f,
    ): RectF {
        val kit = view.kit
        val iconSize = (height * 0.62f).coerceIn(11f, 15f)
        val textW = kit.measure(label, textSize, true)
        val pillW = 8f + iconSize + 4.5f + textW + 9f
        val rect = RectF(left, top, left + pillW, top + height)
        kit.rounded(c, rect, kit.tint, height * 0.32f, kit.blue)
        AppIcon.draw(c, "verified", left + 8f + iconSize * 0.5f, rect.centerY(), iconSize, kit.blue)
        kit.text(
            c,
            label,
            left + 8f + iconSize + 4.5f,
            rect.centerY() + textSize * 0.35f,
            textSize,
            kit.blue,
            true,
        )
        return rect
    }
}
