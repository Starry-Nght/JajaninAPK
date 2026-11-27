package com.jajanin.app.feature.seller

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible

internal object SellerTokens {
    const val PAD = 20f
    const val RIGHT = 370f
    const val WIDTH = 350f

    const val CONTENT_TOP = 76f
    const val SECTION_GAP = 22f
    const val ROW_GAP = 10f

    const val FIELD_H = 46f
    const val CONTROL_H = 36f
    const val TOUCH_MIN = 44f

    // Crisp architectural corner radii (non-AI, editorial feel)
    const val RADIUS_SURFACE = 8f
    const val RADIUS_CONTROL = 7f
    const val RADIUS_PILL = 5f

    const val T_EYEBROW = 10f
    const val T_LABEL = 10.5f
    const val T_BODY = 13f
    const val T_TITLE = 14.5f
    const val T_METRIC = 19f
}

internal fun JajaninView.sellerSection(
    c: Canvas,
    title: String,
    baseline: Float,
    trailing: String? = null,
    trailingColor: Int = kit.muted,
    trailingArrow: Boolean = false,
) {
    kit.text(c, title.uppercase(), SellerTokens.PAD, baseline, SellerTokens.T_EYEBROW, kit.muted, true)
    if (!trailing.isNullOrBlank()) {
        if (trailingArrow) {
            drawTrailingActionWithArrow(c, trailing, SellerTokens.RIGHT, baseline, SellerTokens.T_EYEBROW, trailingColor)
        } else {
            kit.text(
                c,
                trailing,
                SellerTokens.RIGHT - kit.measure(trailing, SellerTokens.T_EYEBROW, true),
                baseline,
                SellerTokens.T_EYEBROW,
                trailingColor,
                true,
            )
        }
    }
}

/** Clean flat surface with 1f hairline border (no heavy blurry drop-shadows). */
internal fun JajaninView.sellerSurface(c: Canvas, r: RectF, radius: Float = SellerTokens.RADIUS_SURFACE) {
    if (r.bottom < 60f || r.top > 2000f) return
    kit.rounded(c, r, kit.card, radius, kit.border)
}

internal fun JajaninView.sellerDivider(c: Canvas, y: Float, left: Float = 20f, right: Float = 370f) {
    kit.line(c, left, y, right, y, kit.divider)
}

internal fun JajaninView.sellerDot(c: Canvas, cx: Float, cy: Float, color: Int, radius: Float = 3f) {
    kit.rounded(c, RectF(cx - radius, cy - radius, cx + radius, cy + radius), color, radius)
}

internal fun JajaninView.sellerPill(
    c: Canvas,
    label: String,
    right: Float,
    centerY: Float,
    background: Int,
    foreground: Int,
    height: Float = 21f,
    size: Float = 9.5f,
): RectF {
    val width = maxOf(34f, kit.measure(label, size, true) + 16f)
    val r = RectF(right - width, centerY - height / 2f, right, centerY + height / 2f)
    kit.rounded(c, r, background, SellerTokens.RADIUS_PILL)
    kit.centerText(c, label, r, size, foreground, true)
    return r
}

internal fun JajaninView.sellerChevron(c: Canvas, x: Float, centerY: Float, color: Int = kit.muted) {
    drawIcon(c, "chevronRight", x, centerY, 14f, color)
}

internal fun JajaninView.sellerHeader(c: Canvas, title: String) {
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, 60f, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.textEllipsized(c, title, 50f, 37f, 300f, 18f, kit.ink, true)
    kit.line(c, SellerTokens.PAD, 60f, SellerTokens.RIGHT, 60f, kit.divider)
    c.restore()
    bounds["headerBack"] = RectF(12f, 10f, 56f, 60f)
}

internal data class SellerSettingRow(
    val title: String,
    val key: String,
    val subtitle: String? = null,
    val value: String? = null,
    val valueColor: Int? = null,
    val badge: String? = null,
    val badgeColor: Int? = null,
    val chevron: Boolean = true,
    val titleColor: Int? = null,
)

internal fun JajaninView.sellerSettingsGroup(
    c: Canvas,
    top: Float,
    rows: List<SellerSettingRow>,
    visible: (Float, Float) -> Boolean,
    bottomLimit: Float = Float.MAX_VALUE,
): Float {
    if (rows.isEmpty()) return top
    val rowH = 56f
    val bottom = top + rows.size * rowH
    if (visible(top, bottom)) sellerSurface(c, RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, bottom))
    rows.forEachIndexed { index, row ->
        val rowTop = top + index * rowH
        val rowRect = RectF(SellerTokens.PAD, rowTop, SellerTokens.RIGHT, rowTop + rowH)
        if (visible(rowTop, rowTop + rowH)) {
            kit.textEllipsized(
                c, row.title, 34f, rowTop + 24f, 206f,
                SellerTokens.T_BODY + .5f, row.titleColor ?: kit.ink, true,
            )
            if (!row.subtitle.isNullOrBlank()) {
                kit.textEllipsized(
                    c, row.subtitle, 34f, rowTop + 42f, 224f,
                    11f, kit.muted,
                )
            }
            val centerY = rowTop + rowH / 2f
            val rightEdge = if (row.chevron) 340f else 356f
            if (!row.badge.isNullOrBlank()) {
                sellerPill(
                    c, row.badge, rightEdge, centerY,
                    row.badgeColor?.let { withSoft(it) } ?: kit.tint,
                    row.badgeColor ?: kit.blue,
                    height = 20f, size = 9.5f,
                )
            } else if (!row.value.isNullOrBlank()) {
                kit.textRight(
                    c, row.value, rightEdge, centerY + 4f, 128f,
                    11.5f, row.valueColor ?: kit.muted, true,
                )
            }
            if (row.chevron) sellerChevron(c, 352f, centerY)
            press(c, row.key, RectF(rowRect.left + 2f, rowRect.top + 2f, rowRect.right - 2f, rowRect.bottom - 2f), SellerTokens.RADIUS_SURFACE)
            if (index < rows.size - 1) sellerDivider(c, rowTop + rowH, 34f, 356f)
        }
        bounds.putVisible(row.key, rowRect, topLimit = 60f, bottomLimit = bottomLimit)
    }
    return bottom
}

internal fun JajaninView.withSoft(color: Int): Int = when (color) {
    kit.success -> kit.successSoft
    kit.danger -> kit.dangerBorder
    kit.star -> kit.warnBox
    else -> kit.tint
}

internal fun JajaninView.sellerFormField(
    c: Canvas,
    label: String,
    value: String,
    placeholder: String,
    key: String,
    top: Float,
    x: Float = SellerTokens.PAD,
    width: Float = SellerTokens.WIDTH,
    height: Float = SellerTokens.FIELD_H,
): Float {
    kit.text(c, label, x, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(x, top + 16f, x + width, top + 16f + height)
    val filled = value.isNotBlank()
    kit.rounded(
        c,
        field,
        kit.card,
        SellerTokens.RADIUS_CONTROL,
        if (pressedKey == key) kit.blue else kit.border,
    )
    kit.textEllipsized(
        c,
        if (filled) value else placeholder,
        x + 14f,
        field.centerY() + 4.5f,
        width - 28f,
        13.5f,
        if (filled) kit.ink else kit.muted,
        filled,
    )
    press(c, key, field, SellerTokens.RADIUS_CONTROL)
    bounds[key] = RectF(field)
    return top + 16f + height + 12f
}

internal fun JajaninView.sellerSwitch(c: Canvas, on: Boolean, right: Float, centerY: Float) {
    val track = RectF(right - 42f, centerY - 12f, right, centerY + 12f)
    kit.rounded(c, track, if (on) kit.blue else kit.border, 12f)
    val knobCx = if (on) track.right - 12f else track.left + 12f
    kit.rounded(
        c,
        RectF(knobCx - 8.5f, centerY - 8.5f, knobCx + 8.5f, centerY + 8.5f),
        Color.WHITE,
        8.5f,
    )
}
