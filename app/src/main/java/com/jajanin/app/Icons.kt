package com.jajanin.app

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
        // Silang — tutup/hapus
        "close" -> {
            c.drawLine(x(5f), y(5f), x(15f), y(15f), iconStroke)
            c.drawLine(x(15f), y(5f), x(5f), y(15f), iconStroke)
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
        else -> Unit
    }
}
