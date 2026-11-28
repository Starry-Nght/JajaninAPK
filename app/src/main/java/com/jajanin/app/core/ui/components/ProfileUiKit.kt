package com.jajanin.app.core.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.DrawKit
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible

/**
 * Shared Airbnb-inspired Profile UI primitives (editorial headings, borderless
 * icon + label + chevron hairline rows, floating elevated promo card, and clean
 * vector storefront/shopping illustrations).
 */
internal data class CleanProfileRow(
    val icon: String,
    val title: String,
    val value: String? = null,
    val valueColor: Int? = null,
    val key: String,
    val useAppIcon: Boolean = false,
)

internal fun JajaninView.drawProfileSectionHeading(
    c: Canvas,
    title: String,
    top: Float,
    visible: (Float, Float) -> Boolean,
): Float {
    if (visible(top, top + 30f)) {
        kit.text(c, title, 22f, top + 22f, 20f, kit.ink, true)
    }
    return top + 34f
}

internal fun JajaninView.drawProfileHairlineRows(
    c: Canvas,
    startY: Float,
    rows: List<CleanProfileRow>,
    visible: (Float, Float) -> Boolean,
    topLimit: Float,
    bottomLimit: Float,
): Float {
    var y = startY
    val rowH = 56f
    rows.forEach { row ->
        val rect = RectF(16f, y, 374f, y + rowH)
        if (visible(y, y + rowH)) {
            val cy = y + rowH / 2f
            press(c, row.key, RectF(14f, y + 2f, 376f, y + rowH - 2f), 10f)
            if (row.useAppIcon) {
                AppIcon.draw(c, row.icon, 34f, cy, 20f, kit.ink)
            } else {
                drawIcon(c, row.icon, 34f, cy, 19f, kit.ink)
            }
            val valueMaxW = if (row.value.isNullOrBlank()) 0f else 118f
            val titleMaxW = if (valueMaxW > 0f) 172f else 276f
            kit.textEllipsized(c, row.title, 62f, cy + 5f, titleMaxW, 14.5f, kit.ink, false)
            if (!row.value.isNullOrBlank()) {
                kit.textRight(
                    c,
                    row.value,
                    344f,
                    cy + 4.5f,
                    valueMaxW,
                    12f,
                    row.valueColor ?: kit.muted,
                    row.valueColor != null && row.valueColor != kit.muted,
                )
            }
            drawIcon(c, "chevronRight", 360f, cy, 14f, kit.muted)
            kit.line(c, 22f, y + rowH, 368f, y + rowH, kit.divider)
        }
        bounds.putVisible(row.key, rect, topLimit = topLimit, bottomLimit = bottomLimit)
        y += rowH
    }
    return y
}

internal fun JajaninView.drawProfileElevatedPromoCard(
    c: Canvas,
    top: Float,
    headline: String,
    subtitle: String,
    key: String,
    isSellerIllustration: Boolean,
    visible: (Float, Float) -> Boolean,
    topLimit: Float,
    bottomLimit: Float,
): Float {
    val cardH = 108f
    val card = RectF(20f, top, 370f, top + cardH)
    if (visible(card.top - 4f, card.bottom + 8f)) {
        // Soft multi-pass ambient shadow matching elevated Airbnb promo card
        kit.rounded(
            c,
            RectF(card.left + 2f, card.top + 5f, card.right - 2f, card.bottom + 7f),
            Color.argb(if (darkMode) 82 else 20, 0, 0, 0),
            17f,
        )
        kit.rounded(
            c,
            RectF(card.left + 1f, card.top + 2f, card.right - 1f, card.bottom + 3f),
            Color.argb(if (darkMode) 52 else 12, 0, 0, 0),
            16f,
        )
        kit.rounded(c, card, kit.card, 16f, kit.border)

        kit.textEllipsized(c, headline, 38f, top + 37f, 206f, 16f, kit.ink, true)
        kit.textWrapped(
            c,
            subtitle,
            38f,
            top + 58f,
            204f,
            12f,
            kit.muted,
            maxLines = 2,
            lineHeight = 17f,
        )

        val artRect = RectF(252f, top + 14f, 356f, top + cardH - 14f)
        if (isSellerIllustration) {
            drawIsometricWarungArt(c, kit, darkMode, artRect)
        } else {
            drawIsometricBuyerBagArt(c, kit, darkMode, artRect)
        }
        press(c, key, card, 16f)
    }
    bounds.putVisible(key, card, topLimit = topLimit, bottomLimit = bottomLimit)
    return top + cardH
}

/**
 * Clean geometric mini storefront illustration (Airbnb promo-card vignette style,
 * adapted to a Jajanin school warung). Zero emojis; pure anti-aliased vector Canvas art.
 */
private fun drawIsometricWarungArt(c: Canvas, kit: DrawKit, dark: Boolean, r: RectF) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val cx = r.centerX()
    val cy = r.centerY() + 4f

    // Soft ground shadow oval
    p.color = Color.argb(if (dark) 75 else 24, 15, 23, 42)
    c.drawOval(RectF(cx - 42f, cy + 20f, cx + 42f, cy + 34f), p)

    // Back greenery bush accent
    p.color = if (dark) Color.rgb(34, 128, 86) else Color.rgb(74, 178, 122)
    c.drawCircle(cx + 31f, cy + 12f, 9f, p)
    c.drawCircle(cx + 25f, cy + 16f, 7f, p)
    p.color = if (dark) Color.rgb(24, 96, 64) else Color.rgb(52, 148, 98)
    c.drawCircle(cx - 32f, cy + 17f, 6.5f, p)

    // Left sunlit wall
    val leftWall = Path().apply {
        moveTo(cx - 28f, cy - 6f)
        lineTo(cx + 2f, cy + 4f)
        lineTo(cx + 2f, cy + 26f)
        lineTo(cx - 28f, cy + 16f)
        close()
    }
    p.color = if (dark) Color.rgb(44, 58, 86) else Color.rgb(244, 247, 252)
    c.drawPath(leftWall, p)

    // Right shaded wall
    val rightWall = Path().apply {
        moveTo(cx + 2f, cy + 4f)
        lineTo(cx + 28f, cy - 8f)
        lineTo(cx + 28f, cy + 15f)
        lineTo(cx + 2f, cy + 26f)
        close()
    }
    p.color = if (dark) Color.rgb(30, 41, 64) else Color.rgb(218, 227, 242)
    c.drawPath(rightWall, p)

    // Upper loft block (modern two-tier school stall)
    val loftLeft = Path().apply {
        moveTo(cx - 18f, cy - 22f)
        lineTo(cx + 6f, cy - 13f)
        lineTo(cx + 6f, cy + 2f)
        lineTo(cx - 18f, cy - 7f)
        close()
    }
    p.color = if (dark) Color.rgb(54, 71, 104) else Color.WHITE
    c.drawPath(loftLeft, p)

    val loftRight = Path().apply {
        moveTo(cx + 6f, cy - 13f)
        lineTo(cx + 24f, cy - 22f)
        lineTo(cx + 24f, cy - 8f)
        lineTo(cx + 6f, cy + 2f)
        close()
    }
    p.color = if (dark) Color.rgb(36, 49, 76) else Color.rgb(226, 234, 246)
    c.drawPath(loftRight, p)

    // Roof cap (brand blue slate)
    val roof = Path().apply {
        moveTo(cx - 21f, cy - 22f)
        lineTo(cx - 1f, cy - 32f)
        lineTo(cx + 27f, cy - 22f)
        lineTo(cx + 6f, cy - 11f)
        close()
    }
    p.color = kit.blue
    c.drawPath(roof, p)

    // Subtle roof skylight highlight
    val skylight = Path().apply {
        moveTo(cx - 8f, cy - 24f)
        lineTo(cx + 1f, cy - 28f)
        lineTo(cx + 14f, cy - 23f)
        lineTo(cx + 5f, cy - 18.5f)
        close()
    }
    p.color = Color.argb(90, 255, 255, 255)
    c.drawPath(skylight, p)

    // Awning overhang on lower front
    val awning = Path().apply {
        moveTo(cx - 32f, cy - 5f)
        lineTo(cx + 2f, cy + 7f)
        lineTo(cx + 5f, cy + 2f)
        lineTo(cx - 28f, cy - 9f)
        close()
    }
    p.color = kit.blue
    c.drawPath(awning, p)

    // Warm serving window on left wall
    val win = Path().apply {
        moveTo(cx - 23f, cy + 2f)
        lineTo(cx - 5f, cy + 8f)
        lineTo(cx - 5f, cy + 17f)
        lineTo(cx - 23f, cy + 11f)
        close()
    }
    p.color = if (dark) Color.rgb(251, 191, 36) else Color.rgb(254, 215, 102)
    c.drawPath(win, p)

    // Door / counter window on right wall
    val door = Path().apply {
        moveTo(cx + 10f, cy + 6f)
        lineTo(cx + 20f, cy + 1f)
        lineTo(cx + 20f, cy + 17f)
        lineTo(cx + 10f, cy + 22f)
        close()
    }
    p.color = if (dark) Color.rgb(22, 42, 74) else Color.rgb(186, 211, 248)
    c.drawPath(door, p)
}

/**
 * Clean geometric mini shopping tote + cup vignette for Seller -> Buyer mode promo card.
 */
private fun drawIsometricBuyerBagArt(c: Canvas, kit: DrawKit, dark: Boolean, r: RectF) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val cx = r.centerX()
    val cy = r.centerY() + 4f

    // Soft ground shadow
    p.color = Color.argb(if (dark) 75 else 24, 15, 23, 42)
    c.drawOval(RectF(cx - 36f, cy + 20f, cx + 36f, cy + 32f), p)

    // Handle arc behind bag
    val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        color = kit.blue
    }
    c.drawArc(RectF(cx - 18f, cy - 30f, cx + 4f, cy - 6f), 180f, 180f, false, strokeP)

    // Bag front body
    val front = RectF(cx - 28f, cy - 16f, cx + 14f, cy + 24f)
    kit.rounded(c, front, kit.blue, 8f)

    // Bag side gusset
    val side = Path().apply {
        moveTo(cx + 11f, cy - 15f)
        lineTo(cx + 22f, cy - 20f)
        lineTo(cx + 22f, cy + 19f)
        lineTo(cx + 11f, cy + 24f)
        close()
    }
    p.color = if (dark) Color.rgb(34, 96, 184) else Color.rgb(0, 74, 168)
    c.drawPath(side, p)

    // White emblem badge on tote
    val emblem = RectF(cx - 17f, cy - 4f, cx + 3f, cy + 14f)
    kit.rounded(c, emblem, Color.argb(235, 255, 255, 255), 6f)
    kit.centerText(c, "J", emblem, 11.5f, kit.blue, true)

    // Small snack cup next to tote
    val cup = RectF(cx + 14f, cy + 2f, cx + 32f, cy + 24f)
    kit.rounded(c, cup, if (dark) Color.rgb(48, 62, 92) else Color.rgb(236, 242, 252), 5f, kit.border)
    kit.rounded(c, RectF(cx + 12f, cy - 1f, cx + 34f, cy + 5f), if (dark) Color.rgb(74, 222, 128) else Color.rgb(31, 158, 95), 3f)
}
