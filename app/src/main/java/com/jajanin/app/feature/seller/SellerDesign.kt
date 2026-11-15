package com.jajanin.app.feature.seller

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible

internal object SellerTokens {
    const val PAD = 20f
    const val RIGHT = 370f
    const val WIDTH = 350f

    const val CONTENT_TOP = 92f
    const val SECTION_GAP = 26f
    const val ROW_GAP = 12f

    const val FIELD_H = 48f
    const val CONTROL_H = 38f
    const val TOUCH_MIN = 44f

    const val RADIUS_SURFACE = 16f
    const val RADIUS_CONTROL = 12f
    const val RADIUS_PILL = 11f

    const val T_EYEBROW = 10.5f
    const val T_LABEL = 10.5f
    const val T_BODY = 13f
    const val T_TITLE = 14f
    const val T_METRIC = 18f
}

internal fun JajaninView.sellerSection(
    c: Canvas,
    title: String,
    baseline: Float,
    trailing: String? = null,
    trailingColor: Int = kit.muted,
) {
    kit.text(c, title, SellerTokens.PAD + 2f, baseline, SellerTokens.T_TITLE, kit.ink, true)
    if (!trailing.isNullOrBlank()) {
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

internal fun JajaninView.sellerSurface(c: Canvas, r: RectF, radius: Float = SellerTokens.RADIUS_SURFACE) {
    if (r.bottom < 80f || r.top > 2000f) return
    kit.card(c, r, radius)
}

internal fun JajaninView.sellerDivider(c: Canvas, y: Float, left: Float = 34f, right: Float = 356f) {
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
    height: Float = 24f,
    size: Float = 10f,
): RectF {
    val width = maxOf(34f, kit.measure(label, size, true) + 20f)
    val r = RectF(right - width, centerY - height / 2f, right, centerY + height / 2f)
    kit.rounded(c, r, background, height / 2f)
    kit.centerText(c, label, r, size, foreground, true)
    return r
}

internal fun JajaninView.sellerChevron(c: Canvas, x: Float, centerY: Float, color: Int = kit.muted) {
    drawIcon(c, "chevronRight", x, centerY, 15f, color)
}

internal fun JajaninView.sellerHeader(c: Canvas, title: String) {
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, 64f, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 42f, 18f, kit.ink)
    kit.textEllipsized(c, title, 52f, 46f, 300f, 18f, kit.ink, true)
    kit.line(c, SellerTokens.PAD, 64f, SellerTokens.RIGHT, 64f, kit.divider)
    c.restore()
    bounds["headerBack"] = RectF(12f, 18f, 54f, 64f)
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
                    c, row.subtitle, 34f, rowTop + 43f, 224f,
                    SellerTokens.T_EYEBROW, kit.muted,
                )
            }
            val centerY = rowTop + rowH / 2f
            val rightEdge = if (row.chevron) 340f else 356f
            if (!row.badge.isNullOrBlank()) {
                sellerPill(
                    c, row.badge, rightEdge, centerY,
                    row.badgeColor?.let { withSoft(it) } ?: kit.tint,
                    row.badgeColor ?: kit.blue,
                    height = 22f, size = 9.5f,
                )
            } else if (!row.value.isNullOrBlank()) {
                kit.textRight(
                    c, row.value, rightEdge, centerY + 4f, 128f,
                    11.5f, row.valueColor ?: kit.muted, true,
                )
            }
            if (row.chevron) sellerChevron(c, 350f, centerY)
            press(c, row.key, RectF(rowRect.left + 4f, rowRect.top + 2f, rowRect.right - 4f, rowRect.bottom - 2f), 0f)
            if (index < rows.size - 1) sellerDivider(c, rowTop + rowH, 34f, 356f)
        }
        bounds.putVisible(row.key, rowRect, topLimit = 86f, bottomLimit = bottomLimit)
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
    kit.text(c, label, x + 2f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(x, top + 18f, x + width, top + 18f + height)
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
        14f,
        if (filled) kit.ink else kit.muted,
        filled,
    )
    press(c, key, field, 0f)
    bounds[key] = RectF(field)
    return top + 18f + height + 12f
}

internal fun JajaninView.sellerSwitch(c: Canvas, on: Boolean, right: Float, centerY: Float) {
    val track = RectF(right - 44f, centerY - 13f, right, centerY + 13f)
    kit.rounded(c, track, if (on) kit.success else kit.border, 13f)
    val knobCx = if (on) track.right - 13f else track.left + 13f
    kit.rounded(
        c,
        RectF(knobCx - 9f, centerY - 9f, knobCx + 9f, centerY + 9f),
        Color.WHITE,
        9f,
    )
}
