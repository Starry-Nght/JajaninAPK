package com.jajanin.app.core.ui

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView

/**
 * Halaman info statis dari Profil: Bantuan, Tentang, Info Penting.
 * Kartu teks center dengan border radius; kembali via toolbar.
 */

private fun JajaninView.infoBox(c: Canvas, w: Float, h: Float, lines: List<Pair<String, Int>>, titleSize: Float = 15f) {
    val boxH = 40f + lines.size * 30f
    val top = (h - boxH) / 2f
    val box = RectF(28f, top, w - 28f, top + boxH)
    kit.card(c, box, 18f)
    lines.forEachIndexed { i, (text, color) ->
        val size = if (titleSize > 15f) titleSize else 14f
        kit.centerText(c, text, RectF(box.left, top + 34f + i * 30f, box.right, top + 60f + i * 30f), size, color, true)
    }
}

internal fun JajaninView.drawHelp(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Bantuan")
    infoBox(
        c, w, h,
        listOf(
            "Jika ada masalah dengan akun anda" to kit.ink,
            "silahkan hubungi nomor ini:" to kit.ink,
            "082329650282 (Athala DEV)" to kit.blue,
            "Instagram: athalamahogra" to kit.blue,
        ),
    )
    contentHeight = 0f
}

internal fun JajaninView.drawAbout(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Tentang Jajanin")
    infoBox(
        c, w, h,
        listOf(
            "Apk sedang dalam kondisi BETA" to kit.ink,
            "belum optimal sepenuhnya." to kit.muted,
        ),
    )
    contentHeight = 0f
}

internal fun JajaninView.drawInfoPage(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Informasi Penting")
    infoBox(
        c, w, h,
        listOf(
            "HANYA DIGUNAKAN DI AREA" to kit.ink,
            "SMKN8 SEMARANG" to kit.blue,
        ),
        titleSize = 19f,
    )
    contentHeight = 0f
}
