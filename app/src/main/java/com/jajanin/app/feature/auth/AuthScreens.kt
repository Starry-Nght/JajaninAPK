package com.jajanin.app.feature.auth

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.R
import com.jajanin.app.core.ui.components.drawCenteredButtonWithArrow

internal fun JajaninView.drawWelcome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "Beta", 30f, 68f, 18f, kit.blue, true)
    kit.image(c, R.drawable.welcome, RectF(30f, 116f, 360f, minOf(h * .59f, 485f)), 24f)
    kit.text(c, "Jajan enak,", 30f, h * .68f, 32f, kit.ink, true)
    kit.text(c, "nggak pake lama.", 30f, h * .68f + 39f, 32f, kit.ink, true)
    kit.text(c, "Temukan makanan favoritmu di sekitar SMKN 8.", 30f, h * .68f + 71f, 14f, kit.muted)
    val cta = RectF(24f, h - 82f, w - 24f, h - 24f)
    kit.rounded(c, cta, kit.blue, 14f)
    drawCenteredButtonWithArrow(c, "Mulai sekarang", cta, 15f, Color.WHITE)
    bounds["welcome"] = RectF(cta)
}

internal fun JajaninView.drawLogin(c: Canvas, w: Float, h: Float) {
    // Layar login interaktif asli dirender oleh NativeLoginOverlay di atas kanvas.
    c.drawColor(kit.bg)
}
