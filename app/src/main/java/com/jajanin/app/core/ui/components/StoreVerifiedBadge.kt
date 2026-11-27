package com.jajanin.app.core.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView

/**
 * Reusable Jajanin Verified Store & Official Developer Badge component
 * (`ic_store_verified.xml` + `ic_dev_badge.xml`).
 *
 * Account / Store with NIS 11855 automatically receives the Verified seal plus the
 * Official Developer shield logo (`< / >`) right beside the Verified seal.
 */
internal object StoreVerifiedBadge {

    private const val DEV_NIS = "11855"
    private const val DEV_STALL_ID = "a634c2cf-e0ba-42c8-b40e-4b4680fd5ac7"
    private val DEV_USER_IDS = setOf(
        "ba81ff64-ea62-489f-9357-f7e61c4208c6",
        "c799bf32-1be5-4c1c-91f6-eea4af15b654",
        "ba9cd33f-4496-4fe6-b32e-9b559601210e",
    )

    val devBadgeColor: Int = Color.rgb(245, 158, 11)

    fun isDevIdentity(
        view: JajaninView? = null,
        name: String = "",
        nis: String = "",
        ownerId: String = "",
        stallId: String = "",
    ): Boolean {
        val cleanNis = nis.trim()
        if (cleanNis == DEV_NIS) return true
        val cleanOwner = ownerId.trim()
        if (cleanOwner.isNotEmpty() && cleanOwner in DEV_USER_IDS) return true
        val cleanStall = stallId.trim()
        if (cleanStall.equals(DEV_STALL_ID, ignoreCase = true)) return true

        val cleanName = name.trim()
        if (cleanName.isNotEmpty()) {
            if (cleanName.contains(DEV_NIS)) return true
            if (cleanName.equals("warung dev", ignoreCase = true)) return true
            if (cleanName.contains("ATHALA MAHOGRA", ignoreCase = true)) return true
            if (cleanName.equals("Athala Jati", ignoreCase = true)) return true
            val matchedStore = view?.storeDirectoryById?.values?.firstOrNull {
                it.name.trim().equals(cleanName, ignoreCase = true)
            }
            if (matchedStore != null) {
                if (matchedStore.id.equals(DEV_STALL_ID, ignoreCase = true)) return true
                if (matchedStore.sellerName.contains("ATHALA MAHOGRA", ignoreCase = true) ||
                    matchedStore.sellerName.contains(DEV_NIS)
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Draws the compact verified seal icon (and the Developer shield logo right beside it
     * when the identity belongs to NIS 11855) right beside a store or user name.
     * Returns the right-most X coordinate occupied by `[name + badge(s)]`.
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
        isDev: Boolean = false,
        nis: String = "",
        ownerId: String = "",
        stallId: String = "",
    ): Float {
        val kit = view.kit
        val showDev = isDev || isDevIdentity(view, storeName, nis, ownerId, stallId)
        val showVerified = isVerified || showDev
        if (!showVerified) {
            kit.textEllipsized(c, storeName, x, baselineY, maxTotalWidth, textSize, textColor, bold)
            val measured = kit.measure(storeName, textSize, bold).coerceAtMost(maxTotalWidth)
            return x + measured
        }
        val badgeSize = (textSize * 1.12f).coerceIn(12f, 18f)
        val gap = 4.5f
        val devExtraW = if (showDev) (badgeSize + 3.5f) else 0f
        val availTextW = (maxTotalWidth - badgeSize - gap - devExtraW).coerceAtLeast(36f)
        kit.textEllipsized(c, storeName, x, baselineY, availTextW, textSize, textColor, bold)
        val drawnTextW = kit.measure(storeName, textSize, bold).coerceAtMost(availTextW)
        val badgeCx = x + drawnTextW + gap + badgeSize * 0.5f
        val badgeCy = baselineY - textSize * 0.34f
        AppIcon.draw(c, "verified", badgeCx, badgeCy, badgeSize, kit.blue)
        var rightEdge = badgeCx + badgeSize * 0.5f
        if (showDev) {
            val devCx = rightEdge + 3.5f + badgeSize * 0.5f
            AppIcon.draw(c, "devBadge", devCx, badgeCy, badgeSize, devBadgeColor)
            rightEdge = devCx + badgeSize * 0.5f
        }
        return rightEdge
    }

    /**
     * Draws a tasteful pill badge (`[seal] Terverifikasi Jajanin`, plus the Developer shield
     * logo right beside the verified seal for NIS 11855) for Store Profile headers
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
        isDev: Boolean = false,
    ): RectF {
        val kit = view.kit
        val iconSize = (height * 0.62f).coerceIn(11f, 15f)
        val devIconExtra = if (isDev) (iconSize + 3.5f) else 0f
        val displayLabel = if (isDev && !label.contains("Dev", ignoreCase = true)) "$label · Dev" else label
        val textW = kit.measure(displayLabel, textSize, true)
        val pillW = 8f + iconSize + devIconExtra + 4.5f + textW + 9f
        val rect = RectF(left, top, left + pillW, top + height)
        kit.rounded(c, rect, kit.tint, height * 0.32f, kit.blue)
        val verifiedCx = left + 8f + iconSize * 0.5f
        AppIcon.draw(c, "verified", verifiedCx, rect.centerY(), iconSize, kit.blue)
        if (isDev) {
            val devCx = left + 8f + iconSize + 3.5f + iconSize * 0.5f
            AppIcon.draw(c, "devBadge", devCx, rect.centerY(), iconSize, devBadgeColor)
        }
        kit.text(
            c,
            displayLabel,
            left + 8f + iconSize + devIconExtra + 4.5f,
            rect.centerY() + textSize * 0.35f,
            textSize,
            kit.blue,
            true,
        )
        return rect
    }
}
