package com.jajanin.app.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY

internal fun paymentLabel(status: String): Pair<String, Int> = when (status.lowercase()) {
    "paid" -> "Lunas" to Color.rgb(31, 158, 95)
    "waiting_verification" -> "Verifikasi bukti" to Color.rgb(183, 116, 0)
    "rejected" -> "Bukti ditolak" to Color.rgb(206, 60, 60)
    else -> "Belum bayar" to Color.rgb(112, 120, 137)
}

internal fun statusLabel(status: String): Pair<String, Int> = when (status.lowercase()) {
    STATUS_PENDING -> "Menunggu" to Color.rgb(183, 116, 0)
    STATUS_CONFIRMED -> "Dikonfirmasi" to Color.rgb(0, 99, 214)
    STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> "Disiapkan" to Color.rgb(0, 99, 214)
    STATUS_READY -> "Siap diambil" to Color.rgb(31, 158, 95)
    STATUS_COMPLETED -> "Selesai" to Color.rgb(31, 158, 95)
    STATUS_CANCELLED -> "Dibatalkan" to Color.rgb(206, 60, 60)
    else -> status to Color.rgb(112, 120, 137)
}

internal fun statusNextAction(status: String): Pair<String, String>? = when (status.lowercase()) {
    STATUS_PENDING -> "Konfirmasi" to STATUS_CONFIRMED
    STATUS_CONFIRMED -> "Mulai siapkan" to STATUS_PREPARING
    STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> "Tandai siap diambil" to STATUS_READY
    STATUS_READY -> "Selesaikan pesanan" to STATUS_COMPLETED
    else -> null
}

internal fun isTerminalStatus(status: String): Boolean {
    val s = status.lowercase()
    return s == STATUS_COMPLETED || s == STATUS_CANCELLED
}

internal fun statusPill(c: Canvas, kit: DrawKit, label: String, color: Int, left: Float, top: Float) {
    val w = kit.measure(label, 10f, true) + 22f
    val r = RectF(left, top, left + w, top + 26f)
    val bg = when (color) {
        Color.rgb(31, 158, 95) -> kit.successSoft
        Color.rgb(206, 60, 60) -> Color.rgb(251, 235, 235)
        Color.rgb(183, 116, 0) -> kit.warnBox
        else -> kit.tint
    }
    kit.rounded(c, r, bg, 13f)
    kit.centerText(c, label, r, 10f, color, true)
}

internal fun JajaninView.adminActionableCount(): Int = adminOrders.count { order ->
    val s = order.status.lowercase()
    s == STATUS_PENDING || s == STATUS_CONFIRMED
}

/** Highlight sentuhan untuk kontrol; dipakai lintas file layar. */
internal fun JajaninView.press(c: Canvas, key: String, r: RectF, radius: Float = 12f) {
    if (pressedKey == key) kit.rounded(c, r, kit.pressOverlay, radius)
}

internal fun LinkedHashMap<String, RectF>.putVisible(
    key: String,
    r: RectF,
    topLimit: Float = 86f,
    bottomLimit: Float = Float.MAX_VALUE,
) {
    val top = r.top.coerceAtLeast(topLimit)
    val bottom = r.bottom.coerceAtMost(bottomLimit)
    if (bottom > top && r.right > r.left) put(key, RectF(r.left, top, r.right, bottom))
}

internal fun JajaninView.summaryRow(c: Canvas, a: String, b: String, y: Float, strong: Boolean = false) {
    kit.text(c, a, 24f, y, 13f, if (strong) kit.ink else kit.muted, strong)
    kit.text(c, b, 368f - kit.measure(b, 13f, true), y, 13f, kit.ink, true)
}

internal fun JajaninView.drawQr(c: Canvas, r: RectF) {
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

/**
 * Hitung mundur blackout dari jam server WIB: (label jam buka kembali "HH:mm", sisa menit).
 * Null bila tidak blackout atau jam server belum sinkron.
 */
internal fun JajaninView.blackoutReopen(): Pair<String, Int>? {
    if (!orderingBlocked) return null
    val now = serverNowMillis()
    if (now == 0L) return null
    val tz = java.util.TimeZone.getTimeZone("Asia/Jakarta")
    val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = now }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val (endHour, endMinute) = when {
        hour == 9 -> 10 to 0
        hour == 12 && minute < 30 -> 12 to 30
        else -> return null
    }
    val end = java.util.Calendar.getInstance(tz).apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, endHour)
        set(java.util.Calendar.MINUTE, endMinute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val mins = ((end - now).coerceAtLeast(0) / 60000).toInt()
    return "%02d:%02d".format(endHour, endMinute) to mins
}

/**
 * Hitung mundur blackout dari jam server WIB: "Buka kembali pukul 12:30 WIB
 * · 12 mnt lagi". Null bila tidak blackout atau jam server belum sinkron.
 */
internal fun JajaninView.blackoutReopenLabel(): String? {
    val (endTime, mins) = blackoutReopen() ?: return null
    return "Buka kembali pukul %s WIB · %d mnt lagi".format(endTime, mins)
}
