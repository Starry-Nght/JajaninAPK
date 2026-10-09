package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

private data class OnboardPage(
    val icon: String,
    val title: String,
    val line1: String,
    val line2: String,
)

private val ONBOARD_PAGES = listOf(
    OnboardPage(
        "box",
        "Jajan tanpa antre",
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

    val skip = RectF(w - 110f, 46f, w - 20f, 86f)
    kit.centerText(c, "Lewati", skip, 13f, kit.muted, true)
    bounds["obSkip"] = RectF(skip)

    // Ilustrasi: lingkaran ikon vektor + kartu judul.
    kit.rounded(c, RectF(w / 2f - 70f, h * .16f, w / 2f + 70f, h * .16f + 140f), kit.tint, 70f)
    drawIcon(c, page.icon, w / 2f, h * .16f + 70f, 56f, kit.blue)
    kit.centerText(c, page.title, RectF(24f, h * .16f + 172f, w - 24f, h * .16f + 212f), 24f, kit.ink, true)
    kit.centerText(c, page.line1, RectF(24f, h * .16f + 226f, w - 24f, h * .16f + 252f), 14f, kit.muted)
    kit.centerText(c, page.line2, RectF(24f, h * .16f + 250f, w - 24f, h * .16f + 276f), 14f, kit.muted)

    // Indikator halaman.
    val dotsWidth = ONBOARD_PAGES.size * 22f
    var dx = w / 2f - dotsWidth / 2f + 11f
    ONBOARD_PAGES.forEachIndexed { index, _ ->
        val active = index == onboardPage
        kit.rounded(
            c,
            RectF(dx - (if (active) 12f else 5f), h - 168f, dx + (if (active) 12f else 5f), h - 160f),
            if (active) kit.blue else kit.border, 4f,
        )
        dx += 22f
    }

    // Navigasi bawah: Kembali (kecuali halaman 1) + Lanjut/Mulai.
    if (!last) {
        if (onboardPage > 0) {
            val back = RectF(20f, h - 140f, 170f, h - 84f)
            kit.rounded(c, back, kit.card, 14f, kit.border)
            kit.centerText(c, "‹ Kembali", back, 15f, kit.ink, true)
            bounds["obBack"] = RectF(back)
        }
        val next = RectF(if (onboardPage > 0) 182f else 20f, h - 140f, w - 20f, h - 84f)
        kit.button(c, "Lanjut", next)
        bounds["obNext"] = RectF(next)
    } else {
        val start = RectF(20f, h - 140f, w - 20f, h - 84f)
        kit.button(c, "Mulai Sekarang", start)
        bounds["obStart"] = RectF(start)
    }
    contentHeight = 0f
}
