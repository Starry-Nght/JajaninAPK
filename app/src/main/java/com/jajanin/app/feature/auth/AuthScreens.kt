package com.jajanin.app.feature.auth

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.R
import com.jajanin.app.core.ui.components.drawCenteredButtonWithArrow

internal fun JajaninView.drawWelcome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val compactH = h < 620f
    val topLabelY = if (compactH) 38f else 54f
    kit.text(c, "Beta", 28f, topLabelY, 17f, kit.blue, true)

    val ctaBottom = h - (if (compactH) 18f else 24f)
    val ctaTop = ctaBottom - 54f
    val subBottom = ctaTop - (if (compactH) 18f else 26f)
    val headlineSize = if (compactH) 26f else 31f
    val lineStep = if (compactH) 32f else 38f
    val subY = subBottom
    val headline2Y = subY - (if (compactH) 26f else 32f)
    val headline1Y = headline2Y - lineStep

    val imgTop = topLabelY + (if (compactH) 16f else 28f)
    val imgBottom = (headline1Y - (if (compactH) 34f else 44f)).coerceAtMost(imgTop + 370f)
    if (imgBottom > imgTop + 80f) {
        kit.image(c, R.drawable.welcome, RectF(28f, imgTop, w - 28f, imgBottom), 24f)
    }

    kit.textEllipsized(c, "Jajan enak,", 28f, headline1Y, w - 56f, headlineSize, kit.ink, true)
    kit.textEllipsized(c, "nggak pake lama.", 28f, headline2Y, w - 56f, headlineSize, kit.ink, true)
    kit.textEllipsized(
        c,
        "Temukan makanan favoritmu di sekitar SMKN 8.",
        28f,
        subY,
        w - 56f,
        13.5f,
        kit.muted,
    )

    val cta = RectF(24f, ctaTop, w - 24f, ctaBottom)
    kit.rounded(c, cta, kit.blue, 14f)
    drawCenteredButtonWithArrow(c, "Mulai sekarang", cta, 15f, Color.WHITE)
    bounds["welcome"] = RectF(cta)
    contentHeight = 0f
}

internal fun JajaninView.drawLogin(c: Canvas, w: Float, h: Float) {
    // Layar login interaktif asli dirender oleh NativeLoginOverlay di atas kanvas.
    c.drawColor(kit.bg)
}
