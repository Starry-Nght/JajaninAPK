package com.jajanin.app.feature.onboarding

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.drawIcon

private data class OnboardPage(
    val icon: String,
    val title: String,
    val line1: String,
    val line2: String,
)

private val ONBOARD_PAGES = listOf(
    OnboardPage(
        "box",
        "Jajan tanpa antre ",
        "Pesan makanan kantin langsung",
        "dari HP, tanpa jalan & antre.",
    ),
    OnboardPage(
        "receipt",
        "Pilih 1 menu favorit",
        "Cari menu, buka detailnya,",
        "lalu tambah ke keranjang.",
    ),
    OnboardPage(
        "clock",
        "Maksimal 2 pesanan/hari",
        "Tiap pesanan berisi tepat 1 menu.",
        "Tutup saat 09–10 & 12–13 WIB.",
    ),
    OnboardPage(
        "checkCircle",
        "Pantau lalu ambil",
        "Lihat status di tab Pesanan,",
        "tunjukkan kode ambil ke penjual.",
    ),
)

/** Splash: logo + status saat session dipulihkan (tanpa login ulang bila valid). */
internal fun JajaninView.drawSplash(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.centerText(c, "JAJANIN", RectF(0f, h / 2f - 90f, w, h / 2f - 50f), 30f, kit.blue, true)
    kit.centerText(c, "Memuat...", RectF(0f, h / 2f - 30f, w, h / 2f + 10f), 14f, kit.muted)
    kit.rounded(c, RectF(w / 2f - 60f, h / 2f + 30f, w / 2f + 60f, h / 2f + 38f), kit.border, 4f)
    val pulse = ((System.currentTimeMillis() / 500L) % 2L) == 0L
    kit.rounded(
        c,
        if (pulse) RectF(w / 2f - 60f, h / 2f + 30f, w / 2f, h / 2f + 38f)
        else RectF(w / 2f, h / 2f + 30f, w / 2f + 60f, h / 2f + 38f),
        kit.blue, 4f,
    )
    if (currentScreen == Screen.SPLASH) postInvalidateDelayed(500)
}

/** Tutorial 4 langkah untuk pengguna pertama kali (Skip/Back/Next/indikator). */
internal fun JajaninView.drawOnboard(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val page = ONBOARD_PAGES[onboardPage.coerceIn(0, ONBOARD_PAGES.size - 1)]
    val last = onboardPage >= ONBOARD_PAGES.size - 1
    val compactH = h < 620f

    val skipTop = if (compactH) 14f else 24f
    val skip = RectF(w - 110f, skipTop, w - 16f, skipTop + 48f)
    kit.centerText(c, "Lewati", skip, 13.5f, kit.muted, true)
    bounds["obSkip"] = RectF(skip)

    val btnBottom = h - (if (compactH) 22f else 48f)
    val btnTop = btnBottom - 54f
    val dotsY = btnTop - (if (compactH) 24f else 30f)

    // Distribusi vertikal adaptif antara tombol Lewati dan indikator halaman agar tidak pernah bertumpuk.
    val circleSize = if (compactH) 108f else 136f
    val iconSize = if (compactH) 44f else 54f
    val blockHeight = circleSize + (if (compactH) 108f else 132f)
    val availTop = skip.bottom + 8f
    val availBottom = dotsY - 16f
    val blockTop = (availTop + (availBottom - availTop - blockHeight) * 0.45f)
        .coerceAtLeast(availTop)

    val circleRect = RectF(
        w / 2f - circleSize / 2f,
        blockTop,
        w / 2f + circleSize / 2f,
        blockTop + circleSize,
    )
    kit.rounded(c, circleRect, kit.tint, circleSize / 2f)
    drawIcon(c, page.icon, circleRect.centerX(), circleRect.centerY(), iconSize, kit.blue)

    val titleTop = circleRect.bottom + (if (compactH) 18f else 28f)
    val titleFitted = kit.ellipsize(page.title.trim(), w - 48f, if (compactH) 21f else 24f, true)
    kit.centerText(c, titleFitted, RectF(24f, titleTop, w - 24f, titleTop + 34f), if (compactH) 21f else 24f, kit.ink, true)
    val line1Top = titleTop + (if (compactH) 40f else 48f)
    kit.centerText(c, kit.ellipsize(page.line1, w - 48f, 14f, false), RectF(24f, line1Top, w - 24f, line1Top + 24f), 14f, kit.muted)
    kit.centerText(c, kit.ellipsize(page.line2, w - 48f, 14f, false), RectF(24f, line1Top + 24f, w - 24f, line1Top + 48f), 14f, kit.muted)

    // Indikator halaman.
    val dotsWidth = ONBOARD_PAGES.size * 22f
    var dx = w / 2f - dotsWidth / 2f + 11f
    ONBOARD_PAGES.forEachIndexed { index, _ ->
        val active = index == onboardPage
        kit.rounded(
            c,
            RectF(dx - (if (active) 12f else 5f), dotsY - 4f, dx + (if (active) 12f else 5f), dotsY + 4f),
            if (active) kit.blue else kit.border, 4f,
        )
        dx += 22f
    }

    // Navigasi bawah: Kembali (kecuali halaman 1) + Lanjut/Mulai.
    if (!last) {
        if (onboardPage > 0) {
            val back = RectF(20f, btnTop, 170f, btnBottom)
            kit.rounded(c, back, kit.card, 14f, kit.border)
            val labelW = kit.measure("Kembali", 15f, true)
            val totalW = 16f + 6f + labelW
            val startX = back.centerX() - totalW * 0.5f
            AppIcon.draw(c, "chevronLeft", startX + 8f, back.centerY(), 16f, kit.ink)
            kit.text(c, "Kembali", startX + 22f, back.centerY() + 5.2f, 15f, kit.ink, true)
            bounds["obBack"] = RectF(back)
        }
        val next = RectF(if (onboardPage > 0) 182f else 20f, btnTop, w - 20f, btnBottom)
        kit.button(c, "Lanjut", next)
        bounds["obNext"] = RectF(next)
    } else {
        val start = RectF(20f, btnTop, w - 20f, btnBottom)
        kit.button(c, "Mulai Sekarang", start)
        bounds["obStart"] = RectF(start)
    }
    contentHeight = 0f
}
