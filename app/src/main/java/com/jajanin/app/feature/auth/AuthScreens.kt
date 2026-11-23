package com.jajanin.app.feature.auth

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.R

internal fun JajaninView.drawWelcome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "Beta", 30f, 68f, 18f, kit.blue, true)
    kit.image(c, R.drawable.welcome, RectF(30f, 116f, 360f, minOf(h * .59f, 485f)), 24f)
    kit.text(c, "Jajan enak \uD83D\uDE39\n,", 30f, h * .68f, 32f, kit.ink, true)
    kit.text(c, "nggak pake lama.", 30f, h * .68f + 39f, 32f, kit.ink, true)
    kit.text(c, "Temukan makanan favoritmu di sekitar SMKN8\uD83D\uDE0B\n.", 30f, h * .68f + 71f, 14f, kit.muted)
    val cta = RectF(24f, h - 82f, w - 24f, h - 24f)
    kit.button(c, "Mulai sekarang   →", cta)
    bounds["welcome"] = RectF(cta)
}

internal fun JajaninView.drawRole(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.text(c, "v2.0", 24f, 54f, 16f, kit.blue, true)
    kit.text(c, "1 Akun,", 24f, 133f, 28f, kit.ink, true)
    kit.text(c, "dua mode bebas!", 24f, 168f, 28f, kit.ink, true)
    roleCard(c, "Mode Pembeli (Default)", "Pesan menu hari ini atau Pre-Order besok", 190f, "buyer", true)
    roleCard(c, "Buka Mode Penjual", "Bisa diaktifkan kapan saja di Profil pakai OTP Email", 296f, "seller", false)
    kit.text(c, "Masuk pakai NIS — nama & kelas terisi otomatis.", 24f, h - 104f, 12.5f, kit.muted)
    val cta = RectF(24f, h - 78f, w - 24f, h - 22f)
    kit.button(c, "Lanjut Masuk NIS →", cta)
    bounds["role"] = RectF(cta)
}

internal fun JajaninView.drawLogin(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    // Dekorasi ringan: dua bentuk lembut bertint biru, digambar dulu agar semua
    // konten di atasnya. Tanpa gambar tambahan sehingga ukuran APK tidak berubah.
    kit.rounded(c, RectF(268f, -76f, 448f, 104f), Color.argb(20, 0, 99, 214), 90f)
    kit.rounded(c, RectF(238f, 36f, 290f, 88f), Color.argb(14, 0, 99, 214), 26f)

    kit.text(c, "JAJANIN", 24f, 58f, 17f, kit.blue, true)
    kit.text(c, "Masuk dengan NIS", 24f, 136f, 27f, kit.ink, true)
    kit.text(c, "Nama & kelas terisi otomatis. 1 akun bisa Pembeli & Penjual.", 24f, 165f, 12f, kit.muted)

    authField(c, "emailField", "NIS Siswa", "Ketik NIS (contoh: 11855)", 202f)
    authField(c, "passwordField", "Kata sandi", "Minimal 6 karakter", 280f)

    val forgot = RectF(216f, 344f, 366f, 372f)
    kit.textRight(c, "Lupa kata sandi?", forgot.right, forgot.centerY() + 4f, 140f, 12.5f, kit.blue, true)
    bounds["forgotPassword"] = RectF(forgot)

    val submit = RectF(24f, 382f, 366f, 436f)
    kit.button(c, "Masuk / Aktivasi dengan NIS", submit)
    bounds["emailLogin"] = RectF(submit)

    val toggle = RectF(24f, 456f, 366f, 494f)
    kit.centerText(c, "Siswa baru atau masuk pakai Email?  Ketuk di sini", toggle, 12.5f, kit.blue, true)
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
