package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock

/*
 * Navigasi kaca melayang: satu komponen bersama untuk buyer ("tab:") dan
 * seller ("stab:"). Kontrak bounds dipertahankan — kunci "tab:$label" /
 * "stab:$label" tetap didaftarkan sehingga touch handling tidak berubah.
 * Tanpa blur platform (mahal saat scroll): efek kaca dari permukaan navy
 * translusens + hairline terang + bayangan lembut. Teks putih di kedua tema
 * agar kontras di atas konten apa pun.
 *
 * Kontrak dengan JajaninView: pressedTab dan navAnimAt di-set dari
 * JajaninView.onTouchEvent; seller memakai pressedKey.
 */

// Geometri nav melayang (dp desain, kanvas 390 lebar).
internal const val GLASS_MARGIN = 16f
internal const val GLASS_BOTTOM = 14f
internal const val GLASS_H = 68f
internal const val GLASS_R = 24f
/** Batas atas nav melayang dari bawah layar; konten scroll memakai ini. */
internal const val GLASS_TOP_OFFSET = GLASS_BOTTOM + GLASS_H // 82f

private const val NAV_ANIM_MS = 180L

private val navStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val navFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val navPath = Path()

internal data class FloatNavItem(
    val label: String,
    val icon: String,
    val tabIcon: Boolean,
)

/**
 * Bar kaca melayang generik. [prefix] = "tab:" (buyer) atau "stab:" (seller).
 * Kapsul biru menandai destinasi aktif; animasi indikator tetap 180ms dan
 * menghormati reduced-motion lewat navGrowProgress (0 bingkai ekstra).
 */
internal fun JajaninView.drawFloatingNav(
    c: Canvas,
    w: Float,
    h: Float,
    items: List<FloatNavItem>,
    active: String,
    prefix: String,
) {
    val top = h - GLASS_TOP_OFFSET
    val bottom = h - GLASS_BOTTOM
    // Chrome stabil: nav tidak ikut geser bersama konten halaman.
    c.save()
    c.translate(-slideX, 0f)
    val bar = RectF(GLASS_MARGIN, top, w - GLASS_MARGIN, bottom)
    // Bayangan lembut di bawah bar.
    kit.rounded(c, RectF(bar.left, bar.top + 3f, bar.right, bar.bottom + 4f), kit.glassShadow, GLASS_R)
    // Permukaan kaca + hairline terang.
    kit.rounded(c, bar, kit.glass, GLASS_R, kit.glassBorder)

    val grow = navGrowProgress()
    val slot = bar.width() / items.size
    items.forEachIndexed { i, item ->
        val segL = bar.left + i * slot
        val segR = segL + slot
        val cx = (segL + segR) / 2f
        val selected = item.label == active
        val pressed = pressedTab == "$prefix${item.label}" || pressedKey == "$prefix${item.label}"

        if (selected || pressed) {
            val halfWidth = if (pressed) 34f else 24f + 10f * grow
            val capsule = RectF(cx - halfWidth, top + 8f, cx + halfWidth, top + 42f)
            kit.rounded(c, capsule, if (pressed) Color.argb(60, 255, 255, 255) else kit.blue, 17f)
        }

        val fg = if (selected || pressed) Color.WHITE else Color.argb(165, 255, 255, 255)
        if (item.tabIcon) drawTabIcon(c, item.label, cx, top + 25f, fg)
        else drawIcon(c, item.icon, cx, top + 25f, 17f, fg)
        kit.centerText(c, item.label, RectF(cx - 45f, top + 44f, cx + 45f, top + 64f), 10f, fg, selected)

        bounds["$prefix${item.label}"] = RectF(segL, top, segR, bottom)
    }
    c.restore()
}

internal fun JajaninView.drawBottomNav(c: Canvas, w: Float, h: Float, active: String) {
    drawFloatingNav(
        c, w, h,
        listOf(
            FloatNavItem("Home", "home", true),
            FloatNavItem("Explore", "explore", true),
            FloatNavItem("Orders", "orders", true),
            FloatNavItem("Profile", "profile", true),
        ),
        active, "tab:",
    )
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
        "Explore" -> {
            c.drawCircle(cx, cy, 8.5f, navStroke)
            navPath.reset()
            navPath.moveTo(cx + 4.5f, cy - 4.5f)
            navPath.lineTo(cx - 1f, cy + 1f)
            navPath.lineTo(cx - 4.5f, cy + 0.5f)
            navPath.lineTo(cx - 0.5f, cy - 4.5f)
            navPath.close()
            c.drawPath(navPath, navStroke)
        }
        "Profile" -> {
            c.drawCircle(cx, cy - 4f, 4f, navStroke)
            c.drawArc(RectF(cx - 8f, cy + 1f, cx + 8f, cy + 17f), 180f, 180f, false, navStroke)
        }
    }
}
