package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

internal fun JajaninView.drawDetail(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val y = minOf(h * .34f, 290f)
    kit.image(c, selected.image, RectF(0f, 0f, w, y))
    val back = RectF(18f, 18f, 58f, 58f)
    kit.rounded(c, back, Color.WHITE, 20f)
    kit.centerText(c, "‹", back, 27f, kit.ink, true)
    bounds["back"] = RectF(back)

    kit.rounded(c, RectF(0f, y - 18f, w, h + 20f), Color.WHITE, 22f)
    kit.text(c, selected.name, 24f, y + 25f, 23f, kit.ink, true)
    kit.text(c, selected.stall, 24f, y + 49f, 13f, kit.muted)
    kit.text(c, "★ ${selected.rating}   ·   120+ terjual", 24f, y + 77f, 13f, kit.star, true)
    kit.line(c, 24f, y + 97f, w - 24f, y + 97f)
    kit.text(c, "Tentang menu", 24f, y + 127f, 16f, kit.ink, true)
    kit.text(c, "Disiapkan segar oleh penjual kantin pilihan.", 24f, y + 153f, 13f, kit.muted)
    kit.text(c, "Pedas, gurih, dan pas buat nemenin", 24f, y + 175f, 13f, kit.muted)
    kit.text(c, "waktu istirahatmu.", 24f, y + 196f, 13f, kit.muted)

    kit.text(c, "Jumlah", 24f, h - 104f, 14f, kit.ink, true)
    kit.rounded(c, RectF(234f, h - 126f, 366f, h - 76f), kit.bg, 14f, kit.border)
    kit.text(c, "−", 249f, h - 94f, 23f, kit.blue, true)
    kit.text(c, "$quantity", 296f, h - 94f, 15f, kit.ink, true)
    kit.text(c, "+", 337f, h - 94f, 23f, kit.blue, true)
    bounds["minus"] = RectF(234f, h - 126f, 279f, h - 76f)
    bounds["plus"] = RectF(321f, h - 126f, 366f, h - 76f)

    val cta = RectF(20f, h - 65f, w - 20f, h - 12f)
    kit.button(c, "Tambah ke keranjang · ${selected.price}", cta)
    bounds["checkout"] = RectF(cta)
}

internal fun JajaninView.drawCheckout(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Checkout")
    kit.text(c, "Pesanan", 22f, 110f, 18f, kit.ink, true)
    kit.rounded(c, RectF(18f, 128f, 372f, 220f), Color.WHITE, 16f, kit.border)
    kit.image(c, selected.image, RectF(28f, 139f, 96f, 208f), 10f)
    kit.text(c, selected.name, 108f, 160f, 14f, kit.ink, true)
    kit.text(c, selected.stall, 108f, 181f, 11f, kit.muted)
    kit.text(c, "${selected.price}  ×  $quantity", 108f, 202f, 12f, kit.ink, true)

    kit.text(c, "Metode pembayaran", 22f, 258f, 18f, kit.ink, true)
    kit.rounded(c, RectF(18f, 276f, 372f, 338f), Color.WHITE, 15f, kit.warnSoft)
    kit.text(c, "▦", 34f, 315f, 22f, kit.blue, true)
    kit.text(c, "QRIS", 73f, 306f, 14f, kit.ink, true)
    kit.text(c, "Semua e-wallet dan mobile banking", 73f, 326f, 11f, kit.muted)
    kit.text(c, "◉", 340f, 315f, 18f, kit.blue)

    kit.text(c, "Ringkasan", 22f, 383f, 18f, kit.ink, true)
    summaryRow(c, "Subtotal", formatRupiah(selected.priceInRupiah * quantity), 411f)
    summaryRow(c, "Biaya layanan", formatRupiah(SERVICE_FEE), 439f)
    kit.line(c, 22f, 459f, 368f, 459f)
    summaryRow(c, "Total", formatRupiah(selected.priceInRupiah * quantity + SERVICE_FEE), 488f, true)

    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    kit.button(c, "Bayar sekarang", cta)
    bounds["pay"] = RectF(cta)
}

internal fun JajaninView.drawPayment(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Pembayaran")
    kit.text(c, "Scan kode untuk membayar", 24f, 120f, 21f, kit.ink, true)
    kit.text(c, "Selesaikan pembayaran sebelum pesanan diproses", 24f, 145f, 12f, kit.muted)
    kit.rounded(c, RectF(38f, 184f, 352f, 498f), Color.WHITE, 20f, kit.border)
    kit.text(c, "QRIS", 157f, 220f, 18f, kit.blue, true)
    val qr = RectF(85f, 248f, 305f, 468f)
    kit.rounded(c, qr, Color.WHITE, 4f, kit.border)
    drawQr(c, qr)
    kit.text(c, "Total pembayaran", 24f, 545f, 12f, kit.muted)
    kit.text(c, formatRupiah(selected.priceInRupiah * quantity + SERVICE_FEE), 24f, 576f, 25f, kit.ink, true)
    val cta = RectF(20f, h - 72f, w - 20f, h - 16f)
    kit.button(c, "Saya sudah membayar", cta)
    bounds["paid"] = RectF(cta)
}

internal fun JajaninView.drawReady(c: Canvas, w: Float, h: Float) {
    c.drawColor(Color.WHITE)
    kit.rounded(c, RectF(132f, 115f, 258f, 241f), kit.successSoft, 63f)
    kit.centerText(c, "✓", RectF(132f, 115f, 258f, 241f), 66f, kit.success, true)
    kit.centerText(c, "Pesanan siap!", RectF(20f, 270f, w - 20f, 317f), 25f, kit.ink, true)
    kit.centerText(c, "Tunjukkan nomor ini ke penjual saat", RectF(15f, 327f, w - 15f, 352f), 13f, kit.muted)
    kit.centerText(c, "mengambil pesananmu.", RectF(15f, 349f, w - 15f, 374f), 13f, kit.muted)
    kit.rounded(c, RectF(38f, 412f, 352f, 537f), kit.bg, 18f)
    kit.centerText(c, "NOMOR PESANAN", RectF(38f, 432f, 352f, 457f), 11f, kit.muted, true)
    kit.centerText(c, "A-024", RectF(38f, 455f, 352f, 506f), 36f, kit.blue, true)
    val cta = RectF(20f, h - 79f, w - 20f, h - 22f)
    kit.button(c, "Lihat pesanan", cta)
    bounds["orders"] = RectF(cta)
}

internal fun JajaninView.drawOrders(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Pesanan saya")
    kit.rounded(c, RectF(18f, 92f, 372f, 267f), Color.WHITE, 17f, kit.border)
    kit.text(c, "●  Siap diambil", 34f, 123f, 13f, Color.rgb(28, 146, 91), true)
    kit.text(c, selected.name, 34f, 158f, 18f, kit.ink, true)
    kit.text(c, "Kantin Kuning · Pesanan A-024", 34f, 183f, 12f, kit.muted)
    kit.line(c, 34f, 202f, 356f, 202f)
    kit.text(c, "Tunjukkan kode ke penjual", 34f, 229f, 13f, kit.ink)
    val code = RectF(287f, 215f, 354f, 250f)
    kit.rounded(c, code, kit.tint, 9f)
    kit.centerText(c, "A-024", code, 14f, kit.blue, true)
    drawBottomNav(c, w, h, "Orders")
}

internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Profil")
    kit.rounded(c, RectF(18f, 92f, 372f, 192f), Color.WHITE, 16f, kit.border)
    val avatar = RectF(34f, 112f, 94f, 172f)
    kit.rounded(c, avatar, kit.tint, 30f)
    kit.centerText(c, "A", avatar, 25f, kit.blue, true)
    kit.text(c, "Pengguna Jajanin", 110f, 137f, 17f, kit.ink, true)
    kit.text(c, SupabaseRepository.currentEmail()?.take(34) ?: "Belum masuk", 110f, 160f, 12f, kit.muted)

    listOf("Informasi akun", "Alamat tersimpan", "Bantuan", "Tentang Jajanin").forEachIndexed { i, v ->
        val y = 216f + i * 62f
        kit.rounded(c, RectF(18f, y, 372f, y + 50f), Color.WHITE, 13f, kit.border)
        kit.text(c, v, 34f, y + 31f, 14f, kit.ink)
        kit.text(c, "›", 343f, y + 32f, 20f, kit.muted)
    }

    val logoutRow = RectF(18f, 478f, 372f, 532f)
    kit.rounded(c, logoutRow, Color.WHITE, 14f, kit.dangerBorder)
    kit.centerText(c, "Keluar dari akun", logoutRow, 14f, kit.danger, true)
    bounds["logout"] = logoutRow
    drawBottomNav(c, w, h, "Profile")
}

private fun JajaninView.summaryRow(c: Canvas, a: String, b: String, y: Float, strong: Boolean = false) {
    kit.text(c, a, 24f, y, 13f, if (strong) kit.ink else kit.muted, strong)
    kit.text(c, b, 368f - kit.measure(b, 13f, true), y, 13f, kit.ink, true)
}

private fun JajaninView.drawQr(c: Canvas, r: RectF) {
    val n = 25
    val cell = r.width() / n
    for (row in 0 until n) for (col in 0 until n) {
        val finder = (col < 7 && row < 7) || (col > n - 8 && row < 7) || (col < 7 && row > n - 8)
        val insideFinder = finder && (col % (n - 1) < 7) && (row % (n - 1) < 7)
        val drawn = (insideFinder && (col % (n - 1) in 0..1 || col % (n - 1) == 5 || row % (n - 1) in 0..1 || row % (n - 1) == 5 || (col % (n - 1) in 2..4 && row % (n - 1) in 2..4))) ||
            (!finder && ((row * 17 + col * 13 + row * col) % 7 < 3))
        if (drawn) {
            kit.rect(c, r.left + col * cell, r.top + row * cell, r.left + (col + 1) * cell, r.top + (row + 1) * cell, kit.ink)
        }
    }
}
