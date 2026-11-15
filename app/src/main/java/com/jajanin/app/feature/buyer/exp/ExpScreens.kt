package com.jajanin.app.feature.buyer.exp

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView

/**
 * Riwayat EXP: saldo + legenda level + daftar transaksi (terbaru dulu).
 * Halaman dalam (kembali via toolbar), konten scroll vertikal.
 */
internal fun JajaninView.drawExpHistory(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    header(c, "Riwayat EXP")
    val off = -scroll
    val level = expLevel(myExp)

    kit.card(c, RectF(18f, 92f + off, 372f, 182f + off), 16f)
    kit.text(c, "Total EXP", 36f, 118f + off, 11f, kit.muted, true)
    kit.text(c, "$myExp EXP", 36f, 152f + off, 26f, kit.ink, true)
    val badge = RectF(286f, 112f + off, 354f, 162f + off)
    kit.rounded(c, badge, kit.tint, 25f)
    kit.centerText(c, "Lv $level", badge, 14f, kit.blue, true)
    kit.text(c, if (level >= EXP_MAX_LEVEL) "★ Prestise" else "Pembeli Level $level", 36f, 172f + off, 10f, kit.muted)

    kit.text(c, "Lv2·100  Lv3·250  Lv4·500  Lv5·1000  Lv6·2000", 20f, 210f + off, 10f, kit.muted)
    kit.text(c, "Lv7·3500  Lv8·5500  Lv9·8000  Lv10·12000+", 20f, 228f + off, 10f, kit.muted)

    var y = 248f + off
    when {
        expHistoryLoading && expHistory.isEmpty() ->
            kit.centerText(c, "Memuat riwayat...", RectF(20f, y, 370f, y + 60f), 13f, kit.muted)
        expHistoryError != null && expHistory.isEmpty() -> {
            kit.centerText(c, expHistoryError ?: "Gagal memuat.", RectF(20f, y, 370f, y + 40f), 13f, kit.danger)
            val retry = RectF(120f, y + 52f, 270f, y + 98f)
            kit.button(c, "Coba lagi", retry, kit.blue, 14f)
            bounds["expRetry"] = RectF(retry)
            y += 110f
        }
        expHistory.isEmpty() ->
            kit.centerText(c, "Belum ada EXP. Selesaikan pesanan dulu!", RectF(20f, y, 370f, y + 60f), 13f, kit.muted)
        else -> expHistory.forEach { e ->
            val rowH = 68f
            if (y + rowH >= 78f && y <= h - 20f) {
                kit.card(c, RectF(18f, y, 372f, y + rowH), 14f)
                kit.text(c, e.reason.takeIf { it.isNotBlank() } ?: "Transaksi EXP", 34f, y + 27f, 13f, kit.ink, true)
                kit.text(c, expDate(e.createdAt), 34f, y + 49f, 10f, kit.muted)
                val amt = (if (e.exp >= 0) "+" else "") + "${e.exp} EXP"
                val amtColor = if (e.exp >= 0) kit.success else kit.danger
                kit.text(c, amt, 356f - kit.measure(amt, 15f, true), y + 27f, 15f, amtColor, true)
                val bal = "saldo ${e.balanceAfter}"
                kit.text(c, bal, 356f - kit.measure(bal, 9f), y + 49f, 9f, kit.muted)
            }
            y += rowH + 10f
        }
    }

    if (expHistory.isNotEmpty() && !expHistoryEnd && expHistoryError == null) {
        val more = RectF(90f, y + 6f, 300f, y + 52f)
        if (y + 52f >= 78f && y <= h - 20f) {
            kit.rounded(c, more, kit.card, 14f, kit.border)
            kit.centerText(c, if (expHistoryLoading) "Memuat..." else "Muat lagi", more, 13f, kit.blue, true)
            if (!expHistoryLoading) bounds["expMore"] = RectF(more)
        }
        y += 62f
    }

    contentHeight = y - off + 40f
}

/** "2026-10-10" + "14:30" dari ISO; fallback mentah bila format tak dikenal. */
private fun expDate(iso: String): String {
    if (iso.length < 16) return iso.take(16)
    return iso.take(10) + " · " + iso.substringAfter('T', "").take(5)
}
