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

/** Tutorial 4 langkah: rata kiri, bernomor, progres garis tipis di atas. */
internal fun JajaninView.drawOnboard(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val page = ONBOARD_PAGES[onboardPage.coerceIn(0, ONBOARD_PAGES.size - 1)]
    val last = onboardPage >= ONBOARD_PAGES.size - 1

    // Progres: garis tipis segmen, bukan dot.
    val segW = (w - 40f) / ONBOARD_PAGES.size
    ONBOARD_PAGES.forEachIndexed { index, _ ->
        kit.rounded(
            c,
            RectF(20f + index * segW + 2f, 52f, 20f + (index + 1) * segW - 2f, 56f),
            if (index <= onboardPage) kit.blue else kit.border, 2f,
        )
    }
    val skip = RectF(w - 110f, 66f, w - 20f, 100f)
    kit.centerText(c, "Lewati", skip, 13f, kit.muted, true)
    bounds["obSkip"] = RectF(skip)

    kit.text(c, "${onboardPage + 1} / ${ONBOARD_PAGES.size}", 24f, 130f, 12f, kit.muted, true)
    drawIcon(c, page.icon, 52f, 200f, 30f, kit.blue)
    kit.text(c, page.title, 24f, 268f, 26f, kit.ink, true)
    kit.text(c, page.line1, 24f, 302f, 14f, kit.muted)
    kit.text(c, page.line2, 24f, 326f, 14f, kit.muted)

    // Navigasi bawah: Kembali teks polos + Lanjut.
    if (!last) {
        if (onboardPage > 0) {
            val back = RectF(20f, h - 140f, 150f, h - 84f)
            kit.centerText(c, "‹ Kembali", back, 15f, kit.ink, true)
            bounds["obBack"] = RectF(back)
        }
        val next = RectF(if (onboardPage > 0) 162f else 20f, h - 140f, w - 20f, h - 84f)
        kit.button(c, "Lanjut", next)
        bounds["obNext"] = RectF(next)
    } else {
        val start = RectF(20f, h - 140f, w - 20f, h - 84f)
        kit.button(c, "Mulai Sekarang", start)
        bounds["obStart"] = RectF(start)
    }
    contentHeight = 0f
}
