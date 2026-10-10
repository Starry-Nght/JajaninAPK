package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

internal fun JajaninView.drawWelcome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "JAJANIN", 30f, 68f, 18f, kit.blue, true)
    kit.image(c, R.drawable.welcome, RectF(30f, 116f, 360f, minOf(h * .59f, 485f)), 24f)
    kit.text(c, "Jajan enak,", 30f, h * .68f, 32f, kit.ink, true)
    kit.text(c, "nggak pake lama.", 30f, h * .68f + 39f, 32f, kit.ink, true)
    kit.text(c, "Temukan makanan favoritmu di sekitar kampus.", 30f, h * .68f + 71f, 14f, kit.muted)
    val cta = RectF(24f, h - 82f, w - 24f, h - 24f)
    kit.button(c, "Mulai sekarang   →", cta)
    bounds["welcome"] = RectF(cta)
}

internal fun JajaninView.drawRole(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "JAJANIN", 24f, 54f, 16f, kit.blue, true)
    kit.text(c, "Kamu mau jajan", 24f, 133f, 28f, kit.ink, true)
    kit.text(c, "sebagai siapa?", 24f, 168f, 28f, kit.ink, true)
    roleCard(c, "Pembeli", "Cari makanan dan pesan dari kantin", 190f, "buyer", selectedRole == "buyer")
    roleCard(c, "Penjual", "Kelola menu dan pesanan kantin", 296f, "seller", selectedRole == "seller")
    kit.text(c, "Pilih mode aplikasi.", 24f, h - 104f, 13f, kit.muted)
    val cta = RectF(24f, h - 78f, w - 24f, h - 22f)
    kit.button(c, "Lanjutkan", cta)
    bounds["role"] = RectF(cta)
}

internal fun JajaninView.drawLogin(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    // Dekorasi ringan: dua bentuk lembut bertint biru, digambar dulu agar semua
    // konten di atasnya. Tanpa gambar tambahan sehingga ukuran APK tidak berubah.
    kit.rounded(c, RectF(268f, -76f, 448f, 104f), Color.argb(20, 0, 99, 214), 90f)
    kit.rounded(c, RectF(238f, 36f, 290f, 88f), Color.argb(14, 0, 99, 214), 26f)

    kit.text(c, "JAJANIN", 24f, 58f, 17f, kit.blue, true)
    kit.text(c, "Masuk ke akunmu", 24f, 140f, 27f, kit.ink, true)
    kit.text(c, "Lanjutkan jajan favoritmu di kampus.", 24f, 168f, 13f, kit.muted)

    authField(c, "emailField", "Email", "nama@kampus.ac.id", 202f)
    authField(c, "passwordField", "Kata sandi", "Minimal 6 karakter", 280f)

    val submit = RectF(24f, 366f, 366f, 420f)
    kit.button(c, "Masuk dengan email", submit)
    bounds["emailLogin"] = RectF(submit)

    val toggle = RectF(24f, 448f, 366f, 486f)
    kit.centerText(c, "Belum punya akun?  Daftar", toggle, 13f, kit.blue, true)
    bounds["toggleAuth"] = RectF(toggle)

    kit.centerText(c, "Dengan masuk, kamu menyetujui ketentuan Jajanin.", RectF(18f, h - 66f, w - 18f, h - 35f), 10f, kit.muted)
}

private fun JajaninView.authField(c: Canvas, key: String, label: String, hint: String, top: Float) {
    val r = RectF(24f, top, 366f, top + 62f)
    kit.rounded(c, r, kit.card, 14f, kit.border)
    kit.text(c, label, r.left + 18f, r.top + 25f, 11f, kit.muted, true)
    kit.text(c, hint, r.left + 18f, r.top + 48f, 14f, kit.ink)
    bounds[key] = RectF(r)
}

private fun JajaninView.roleCard(c: Canvas, title: String, subtitle: String, y: Float, key: String, selected: Boolean) {
    val r = RectF(24f, y, 366f, y + 96f)
    kit.rounded(c, r, if (selected) kit.tint else kit.card, 18f, if (selected) kit.blue else kit.border)
    val icon = RectF(40f, y + 22f, 82f, y + 64f)
    kit.rounded(c, icon, kit.tint, 12f)
    kit.centerText(c, if (key == "buyer") "⌕" else "▦", icon, 24f, kit.blue, true)
    kit.text(c, title, 98f, y + 41f, 17f, kit.ink, true)
    kit.text(c, subtitle, 98f, y + 65f, 12f, kit.muted)
    kit.text(c, if (selected) "●" else "○", 330f, y + 55f, 19f, kit.blue)
    bounds[key] = RectF(r)
}
