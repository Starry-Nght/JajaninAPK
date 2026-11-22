package com.jajanin.app.core.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/*
 * Ikon vektor kecil untuk UI penjual — pengganti huruf unicode (▦ ≡ ▤ ◌ ◉ 🔔)
 * yang tampilannya berantakan antar perangkat. Digambar dengan Path dengan gaya
 * yang sama seperti ikon tab pembeli di BottomNav.kt.
 *
 * Semua ikon dirancang di kotak 20x20 lalu dipetakan ke (cx, cy) dengan lebar
 * `size`, sehingga proporsinya konsisten berapa pun ukuran panggilannya.
 */

private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val iconPath = Path()

internal fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
    val u = size / 20f
    fun x(v: Float): Float = cx + (v - 10f) * u
    fun y(v: Float): Float = cy + (v - 10f) * u
    fun box(l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) = RectF(x(l), y(t), x(r), y(b)).also {
        if (radius > 0f) c.drawRoundRect(it, radius * u, radius * u, iconStroke) else c.drawRect(it, iconStroke)
    }

    iconStroke.color = color
    iconFill.color = color
    iconStroke.strokeWidth = 1.8f * u

    when (name) {
        // Grid 2x2 — tab Dashboard
        "grid" -> {
            box(3f, 3f, 9f, 9f, 1.5f); box(11f, 3f, 17f, 9f, 1.5f)
            box(3f, 11f, 9f, 17f, 1.5f); box(11f, 11f, 17f, 17f, 1.5f)
        }
        // Daftar berbutir — tab Pesanan
        "receipt" -> {
            for (row in -1..1) {
                val yy = cy + row * 4.2f * u
                c.drawCircle(x(3.5f), yy, 1.3f * u, iconFill)
                c.drawLine(x(6.5f), yy, x(17f), yy, iconStroke)
            }
        }
        // Tas/kotak produk — tab Produk
        "box" -> {
            box(4f, 7f, 16f, 17f, 2f)
            iconPath.reset()
            iconPath.moveTo(x(7f), y(7f))
            iconPath.lineTo(x(7f), y(5f))
            iconPath.lineTo(x(13f), y(5f))
            iconPath.lineTo(x(13f), y(7f))
            c.drawPath(iconPath, iconStroke)
        }
        // Lonceng — tab Notifikasi & header
        "bell" -> {
            c.drawArc(RectF(x(4.5f), y(5f), x(15.5f), y(15.5f)), 180f, 180f, false, iconStroke)
            c.drawLine(x(4.6f), y(10.4f), x(4.6f), y(13.2f), iconStroke)
            c.drawLine(x(15.4f), y(10.4f), x(15.4f), y(13.2f), iconStroke)
            c.drawLine(x(4.6f), y(13.2f), x(15.4f), y(13.2f), iconStroke)
            c.drawArc(RectF(x(8f), y(13.2f), x(12f), y(17.2f)), 0f, 180f, false, iconStroke)
            c.drawCircle(x(10f), y(3.6f), 1.2f * u, iconFill)
        }
        // Orang — tab Profil
        "person" -> {
            c.drawCircle(x(10f), y(7f), 3.2f * u, iconStroke)
            c.drawArc(RectF(x(4f), y(12f), x(16f), y(22f)), 180f, 180f, false, iconStroke)
        }
        // Panah melingkar — tombol segarkan
        "refresh" -> {
            c.drawArc(RectF(x(4.5f), y(4.5f), x(15.5f), y(15.5f)), 45f, 270f, false, iconStroke)
            iconPath.reset()
            iconPath.moveTo(x(14.5f), y(5.5f))
            iconPath.lineTo(x(10.6f), y(5.9f))
            iconPath.moveTo(x(14.5f), y(5.5f))
            iconPath.lineTo(x(14.1f), y(9.4f))
            c.drawPath(iconPath, iconStroke)
        }
        // Silang — tutup
        "close" -> {
            c.drawLine(x(5f), y(5f), x(15f), y(15f), iconStroke)
            c.drawLine(x(15f), y(5f), x(5f), y(15f), iconStroke)
        }
        // Pensil — ubah produk
        "edit" -> {
            iconPath.reset()
            iconPath.moveTo(x(4f), y(16f))
            iconPath.lineTo(x(5.3f), y(11.4f))
            iconPath.lineTo(x(13.1f), y(3.6f))
            iconPath.lineTo(x(16.4f), y(6.9f))
            iconPath.lineTo(x(8.6f), y(14.7f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
        }
        // Tempat sampah — hapus produk
        "trash" -> {
            c.drawLine(x(4f), y(6.2f), x(16f), y(6.2f), iconStroke)
            c.drawLine(x(8f), y(3.5f), x(12f), y(3.5f), iconStroke)
            box(6.4f, 6.2f, 13.6f, 16.5f, 1.4f)
            c.drawLine(x(8.8f), y(9f), x(8.8f), y(13.8f), iconStroke)
            c.drawLine(x(11.2f), y(9f), x(11.2f), y(13.8f), iconStroke)
        }
        "chevronLeft" -> {
            iconPath.reset()
            iconPath.moveTo(x(12.5f), y(4.5f))
            iconPath.lineTo(x(6.5f), y(10f))
            iconPath.lineTo(x(12.5f), y(15.5f))
            c.drawPath(iconPath, iconStroke)
        }
        "chevronRight" -> {
            iconPath.reset()
            iconPath.moveTo(x(7.5f), y(4.5f))
            iconPath.lineTo(x(13.5f), y(10f))
            iconPath.lineTo(x(7.5f), y(15.5f))
            c.drawPath(iconPath, iconStroke)
        }
        "plus" -> {
            c.drawLine(x(4f), y(10f), x(16f), y(10f), iconStroke)
            c.drawLine(x(10f), y(4f), x(10f), y(16f), iconStroke)
        }
        // Baki masuk — kartu "Pesanan baru"
        "inbox" -> {
            box(3.5f, 4.5f, 16.5f, 15.5f, 2f)
            iconPath.reset()
            iconPath.moveTo(x(3.5f), y(10.5f))
            iconPath.lineTo(x(6.5f), y(10.5f))
            iconPath.lineTo(x(7.8f), y(12.5f))
            iconPath.lineTo(x(12.2f), y(12.5f))
            iconPath.lineTo(x(13.5f), y(10.5f))
            iconPath.lineTo(x(16.5f), y(10.5f))
            c.drawPath(iconPath, iconStroke)
        }
        // Jam — kartu "Disiapkan"
        "clock" -> {
            c.drawCircle(x(10f), y(10f), 6.5f * u, iconStroke)
            iconPath.reset()
            iconPath.moveTo(x(10f), y(6f))
            iconPath.lineTo(x(10f), y(10.4f))
            iconPath.lineTo(x(13.2f), y(12.4f))
            c.drawPath(iconPath, iconStroke)
        }
        // Centang — kartu "Siap diambil"
        "check" -> {
            iconPath.reset()
            iconPath.moveTo(x(4f), y(10.5f))
            iconPath.lineTo(x(8.3f), y(14.8f))
            iconPath.lineTo(x(16f), y(5.5f))
            c.drawPath(iconPath, iconStroke)
        }
        // Centang dalam lingkaran — kartu "Selesai"
        "checkCircle" -> {
            c.drawCircle(x(10f), y(10f), 6.8f * u, iconStroke)
            iconPath.reset()
            iconPath.moveTo(x(6.4f), y(10.2f))
            iconPath.lineTo(x(9f), y(12.8f))
            iconPath.lineTo(x(13.8f), y(7.4f))
            c.drawPath(iconPath, iconStroke)
        }
        "search" -> {
            c.drawCircle(x(8.5f), y(8.5f), 4.5f * u, iconStroke)
            c.drawLine(x(12f), y(12f), x(16.5f), y(16.5f), iconStroke)
        }
        "alert" -> {
            iconPath.reset()
            iconPath.moveTo(x(10f), y(3.5f))
            iconPath.lineTo(x(17.5f), y(16.5f))
            iconPath.lineTo(x(2.5f), y(16.5f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
            c.drawLine(x(10f), y(7.5f), x(10f), y(11.5f), iconStroke)
            c.drawCircle(x(10f), y(14f), 0.9f * u, iconFill)
        }
        "store" -> {
            box(4f, 8.5f, 16f, 16.5f, 1.5f)
            iconPath.reset()
            iconPath.moveTo(x(3f), y(8.5f))
            iconPath.lineTo(x(10f), y(3.5f))
            iconPath.lineTo(x(17f), y(8.5f))
            c.drawPath(iconPath, iconStroke)
            c.drawLine(x(8.5f), y(16.5f), x(8.5f), y(12f), iconStroke)
            c.drawLine(x(11.5f), y(16.5f), x(11.5f), y(12f), iconStroke)
            c.drawLine(x(8.5f), y(12f), x(11.5f), y(12f), iconStroke)
        }
        "megaphone" -> {
            iconPath.reset()
            iconPath.moveTo(x(4f), y(8.5f))
            iconPath.lineTo(x(11.5f), y(6f))
            iconPath.lineTo(x(11.5f), y(14f))
            iconPath.lineTo(x(4f), y(11.5f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
            c.drawArc(RectF(x(11f), y(6f), x(14f), y(14f)), -90f, 180f, false, iconStroke)
            c.drawLine(x(6f), y(11.5f), x(6f), y(15.5f), iconStroke)
            c.drawLine(x(6f), y(15.5f), x(8.5f), y(15.5f), iconStroke)
        }
        "shield" -> {
            iconPath.reset()
            iconPath.moveTo(x(10f), y(3.5f))
            iconPath.lineTo(x(16f), y(5.5f))
            iconPath.lineTo(x(16f), y(10f))
            iconPath.quadTo(x(16f), y(14.5f), x(10f), y(17f))
            iconPath.quadTo(x(4f), y(14.5f), x(4f), y(10f))
            iconPath.lineTo(x(4f), y(5.5f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
        }
        "chevronDown" -> {
            iconPath.reset()
            iconPath.moveTo(x(5.5f), y(8f))
            iconPath.lineTo(x(10f), y(12.5f))
            iconPath.lineTo(x(14.5f), y(8f))
            c.drawPath(iconPath, iconStroke)
        }
        "chevronUp" -> {
            iconPath.reset()
            iconPath.moveTo(x(5.5f), y(12f))
            iconPath.lineTo(x(10f), y(7.5f))
            iconPath.lineTo(x(14.5f), y(12f))
            c.drawPath(iconPath, iconStroke)
        }
        "zap" -> {
            iconPath.reset()
            iconPath.moveTo(x(11f), y(3.5f))
            iconPath.lineTo(x(5.5f), y(10.5f))
            iconPath.lineTo(x(10.5f), y(10.5f))
            iconPath.lineTo(x(9f), y(16.5f))
            iconPath.lineTo(x(14.5f), y(9.5f))
            iconPath.lineTo(x(9.5f), y(9.5f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
        }
        "phone" -> {
            box(6f, 3.5f, 14f, 16.5f, 2f)
            c.drawLine(x(8.5f), y(6f), x(11.5f), y(6f), iconStroke)
            c.drawCircle(x(10f), y(13.8f), 0.8f * u, iconFill)
        }
        "heart" -> {
            iconPath.reset()
            iconPath.moveTo(x(10f), y(16.2f))
            iconPath.cubicTo(x(4f), y(12.2f), x(3f), y(7.2f), x(6.2f), y(5.2f))
            iconPath.cubicTo(x(8.1f), y(4.1f), x(10f), y(5.8f), x(10f), y(7.2f))
            iconPath.cubicTo(x(10f), y(5.8f), x(11.9f), y(4.1f), x(13.8f), y(5.2f))
            iconPath.cubicTo(x(17f), y(7.2f), x(16f), y(12.2f), x(10f), y(16.2f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
        }
        "heartFill" -> {
            iconPath.reset()
            iconPath.moveTo(x(10f), y(16.2f))
            iconPath.cubicTo(x(4f), y(12.2f), x(3f), y(7.2f), x(6.2f), y(5.2f))
            iconPath.cubicTo(x(8.1f), y(4.1f), x(10f), y(5.8f), x(10f), y(7.2f))
            iconPath.cubicTo(x(10f), y(5.8f), x(11.9f), y(4.1f), x(13.8f), y(5.2f))
            iconPath.cubicTo(x(17f), y(7.2f), x(16f), y(12.2f), x(10f), y(16.2f))
            iconPath.close()
            c.drawPath(iconPath, iconFill)
        }
        "flame" -> {
            iconPath.reset()
            iconPath.moveTo(x(10.5f), y(3.2f))
            iconPath.cubicTo(x(13.5f), y(6.5f), x(15.8f), y(9.2f), x(15.2f), y(12.8f))
            iconPath.cubicTo(x(14.6f), y(15.6f), x(12.4f), y(17f), x(10f), y(17f))
            iconPath.cubicTo(x(7.4f), y(17f), x(5f), y(15f), x(5f), y(12f))
            iconPath.cubicTo(x(5f), y(9.5f), x(6.8f), y(7.6f), x(8.2f), y(6.4f))
            iconPath.cubicTo(x(7.8f), y(8.4f), x(9f), y(9.2f), x(9.6f), y(8f))
            iconPath.cubicTo(x(10.2f), y(6.5f), x(10.5f), y(4.8f), x(10.5f), y(3.2f))
            iconPath.close()
            c.drawPath(iconPath, iconFill)
        }
        "chat" -> {
            iconPath.reset()
            iconPath.moveTo(x(4.5f), y(5f))
            iconPath.lineTo(x(15.5f), y(5f))
            iconPath.cubicTo(x(16.6f), y(5f), x(17.5f), y(5.9f), x(17.5f), y(7f))
            iconPath.lineTo(x(17.5f), y(12.5f))
            iconPath.cubicTo(x(17.5f), y(13.6f), x(16.6f), y(14.5f), x(15.5f), y(14.5f))
            iconPath.lineTo(x(8.5f), y(14.5f))
            iconPath.lineTo(x(4.5f), y(17.2f))
            iconPath.lineTo(x(4.5f), y(14.5f))
            iconPath.cubicTo(x(3.4f), y(14.5f), x(2.5f), y(13.6f), x(2.5f), y(12.5f))
            iconPath.lineTo(x(2.5f), y(7f))
            iconPath.cubicTo(x(2.5f), y(5.9f), x(3.4f), y(5f), x(4.5f), y(5f))
            iconPath.close()
            c.drawPath(iconPath, iconStroke)
            c.drawCircle(x(7.2f), y(9.8f), 0.7f * u, iconFill)
            c.drawCircle(x(10f), y(9.8f), 0.7f * u, iconFill)
            c.drawCircle(x(12.8f), y(9.8f), 0.7f * u, iconFill)
        }
        else -> Unit
    }
}
