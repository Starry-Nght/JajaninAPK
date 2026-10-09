package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock

/*
 * Bottom navigation bergaya Material 3: indikator pill di belakang ikon, ikon
 * vektor (Path), umpan balik tekan, dan pill yang mengembang saat tab dipilih.
 *
 * Kontrak dengan JajaninView: bounds tiap tab WAJIB tetap kunci "tab:$label"
 * dan dibagi sebagai slot horizontal bersebelahan (i*w/4 .. (i+1)*w/4, tinggi
 * penuh bar) supaya seluruh area bar punya pemilik dan tidak ada tap yang jatuh
 * ke kartu konten. pressedTab dan navAnimAt di-set dari JajaninView.onTouchEvent.
 */

private const val NAV_HEIGHT = 67f
private const val NAV_ANIM_MS = 180L

private val navStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val navFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val navPath = Path()

internal fun JajaninView.drawBottomNav(c: Canvas, w: Float, h: Float, active: String) {
    val top = h - NAV_HEIGHT
    kit.rect(c, 0f, top, w, h, kit.card)
    kit.line(c, 0f, top, w, top)

    val grow = navGrowProgress()
    val labels = listOf("Home", "Search", "Orders", "Profile")
    val slot = w / labels.size
    labels.forEachIndexed { i, label ->
        val cx = 48f + i * 98f
        val pressed = pressedTab == "tab:$label"
        val selected = label == active

        if (selected || pressed) {
            val halfWidth = if (pressed) 32f else 20f + 12f * grow
            val fill = if (pressed) kit.blue else kit.tint
            kit.rounded(c, RectF(cx - halfWidth, top + 6f, cx + halfWidth, top + 36f), fill, 15f)
        }

        val iconColor = when {
            pressed -> Color.WHITE
            selected -> kit.blue
            else -> kit.muted
        }
        drawTabIcon(c, label, cx, top + 21f, iconColor)

        val labelColor = if (selected || pressed) kit.blue else kit.muted
        kit.centerText(c, label, RectF(cx - 45f, top + 38f, cx + 45f, top + 62f), 10f, labelColor, selected)

        bounds["tab:$label"] = RectF(i * slot, top, (i + 1) * slot, h)
    }
}

// 0..1 selama 180ms setelah navAnimAt; postInvalidateOnAnimation menjaga frame
// berjalan sampai animasi selesai, lalu berhenti (tidak ada invalidate sia-sia).
private fun JajaninView.navGrowProgress(): Float {
    if (navAnimAt == 0L) return 1f
    val elapsed = SystemClock.uptimeMillis() - navAnimAt
    if (elapsed >= NAV_ANIM_MS) return 1f
    postInvalidateOnAnimation()
    val t = elapsed / NAV_ANIM_MS.toFloat()
    return t * t * (3f - 2f * t)
}

private fun drawTabIcon(c: Canvas, tab: String, cx: Float, cy: Float, color: Int) {
    navStroke.color = color
    navFill.color = color
    when (tab) {
        "Home" -> {
            navPath.reset()
            navPath.moveTo(cx - 9f, cy - 1f)
            navPath.lineTo(cx, cy - 9f)
            navPath.lineTo(cx + 9f, cy - 1f)
            navPath.moveTo(cx - 6f, cy - 3f)
            navPath.lineTo(cx - 6f, cy + 8f)
            navPath.lineTo(cx + 6f, cy + 8f)
            navPath.lineTo(cx + 6f, cy - 3f)
            c.drawPath(navPath, navStroke)
        }
        "Search" -> {
            c.drawCircle(cx - 1.5f, cy - 1.5f, 6.5f, navStroke)
            c.drawLine(cx + 3.6f, cy + 3.6f, cx + 8.5f, cy + 8.5f, navStroke)
        }
        "Orders" -> {
            for (row in -1..1) {
                val y = cy + row * 5f
                c.drawCircle(cx - 7f, y, 1.5f, navFill)
                c.drawLine(cx - 3f, y, cx + 8f, y, navStroke)
            }
        }
        "Profile" -> {
            c.drawCircle(cx, cy - 4f, 4f, navStroke)
            c.drawArc(RectF(cx - 8f, cy + 1f, cx + 8f, cy + 17f), 180f, 180f, false, navStroke)
        }
    }
}
