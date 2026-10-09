package com.jajanin.app

import android.graphics.Canvas
import android.graphics.RectF

/**
 * Layar masuk & pilih peran: kompak dan polos. Tanpa dekorasi blob,
 * tanpa kartu peran — cukup daftar radio dan tombol.
 */
internal fun JajaninView.drawWelcome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "Jajanin", 28f, 66f, 18f, kit.blue, true)
    kit.image(c, R.drawable.welcome, RectF(28f, 108f, w - 28f, minOf(h * .56f, 470f)), 20f)
    kit.text(c, "Jajan enak,", 28f, h * .66f, 31f, kit.ink, true)
    kit.text(c, "nggak pake lama.", 28f, h * .66f + 38f, 31f, kit.ink, true)
    kit.text(c, "Pesan dari kantin, ambil pakai kode.", 28f, h * .66f + 68f, 13f, kit.muted)
    val cta = RectF(24f, h - 82f, w - 24f, h - 24f)
    kit.button(c, "Mulai sekarang   →", cta)
    bounds["welcome"] = RectF(cta)
}

internal fun JajaninView.drawRole(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "Jajanin", 24f, 56f, 16f, kit.blue, true)
    kit.text(c, "Masuk sebagai", 24f, 128f, 26f, kit.ink, true)
    // Daftar radio tanpa kartu: baris teks + hairline.
    roleRow(c, "Pembeli", "Pesan makanan kantin", 168f, "buyer", selectedRole == "buyer")
    kit.line(c, 24f, 226f, w - 24f, 226f)
    roleRow(c, "Penjual", "Jualan & kelola pesanan", 240f, "seller", selectedRole == "seller")
    kit.line(c, 24f, 298f, w - 24f, 298f)
    roleRow(c, "Developer", "Pantau platform", 312f, "developer", selectedRole == "developer")
    kit.line(c, 24f, 370f, w - 24f, 370f)
    val cta = RectF(24f, h - 78f, w - 24f, h - 22f)
    kit.button(c, "Lanjutkan", cta)
    bounds["role"] = RectF(cta)
}

internal fun JajaninView.drawLogin(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "Jajanin", 24f, 60f, 17f, kit.blue, true)
    kit.text(c, "Selamat datang", 24f, 138f, 27f, kit.ink, true)
    kit.text(c, "Masuk untuk mulai jajan.", 24f, 164f, 13f, kit.muted)

    // Kolom datar berisi tint, bukan kartu ber-border.
    authField(c, "emailField", "EMAIL", "nama@kampus.ac.id", 200f)
    authField(c, "passwordField", "KATA SANDI", "Minimal 6 karakter", 282f)

    val submit = RectF(24f, 372f, 366f, 426f)
    kit.button(c, "Masuk", submit)
    bounds["emailLogin"] = RectF(submit)

    val toggle = RectF(24f, 440f, 366f, 478f)
    kit.centerText(c, "Belum punya akun?  Daftar", toggle, 13f, kit.blue, true)
    bounds["toggleAuth"] = RectF(toggle)

    kit.centerText(c, "Dengan masuk, kamu menyetujui ketentuan Jajanin.", RectF(18f, h - 60f, w - 18f, h - 34f), 10f, kit.muted)
}

private fun JajaninView.authField(c: Canvas, key: String, label: String, hint: String, top: Float) {
    kit.text(c, label, 26f, top + 18f, 10f, kit.muted, true)
    val r = RectF(24f, top + 28f, 366f, top + 74f)
    kit.rounded(c, r, kit.tint, 10f)
    kit.text(c, hint, r.left + 16f, r.top + 30f, 14f, kit.muted)
    bounds[key] = RectF(r)
}

private fun JajaninView.roleRow(c: Canvas, title: String, subtitle: String, y: Float, key: String, selected: Boolean) {
    kit.text(c, if (selected) "●" else "○", 28f, y + 30f, 18f, if (selected) kit.blue else kit.muted)
    kit.text(c, title, 58f, y + 24f, 16f, kit.ink, true)
    kit.text(c, subtitle, 58f, y + 46f, 12f, kit.muted)
    bounds[key] = RectF(24f, y, 366f, y + 58f)
}
