package com.jajanin.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

internal class DrawKit(private val res: Resources, private val canvasScale: () -> Float) {

    // Palet tema: Surface di semua screen WAJIB memakai kit.card / kit.bg /
    // kit.warnBox (jangan Color.WHITE langsung) agar dark mode bekerja di
    // semua role. Teks di atas tombol berwarna tetap Color.WHITE.
    var ink = Color.rgb(27, 35, 53); private set
    var blue = Color.rgb(0, 99, 214); private set
    var muted = Color.rgb(112, 120, 137); private set
    var bg = Color.rgb(247, 248, 252); private set
    var card = Color.WHITE; private set
    var divider = Color.rgb(232, 234, 240); private set
    var tint = Color.rgb(232, 242, 255); private set
    var border = Color.rgb(224, 228, 236); private set
    var star = Color.rgb(225, 151, 0); private set
    var danger = Color.rgb(206, 60, 60); private set
    var dangerBorder = Color.rgb(240, 214, 214); private set
    var success = Color.rgb(31, 158, 95); private set
    var successSoft = Color.rgb(230, 248, 239); private set
    var warnSoft = Color.rgb(211, 228, 251); private set
    var warnBox = Color.rgb(255, 244, 224); private set
    var pressOverlay = Color.argb(32, 15, 25, 45); private set
    // --- Kaca melayang (identitas navy Jajanin): dipakai nav bawah +
    // header fixed di semua role. Navy gelap di kedua tema agar kontras
    // di atas konten terang maupun gelap.
    var glass = Color.argb(231, 22, 30, 52); private set
    var glassBorder = Color.argb(46, 255, 255, 255); private set
    var glassShadow = Color.argb(42, 5, 10, 25); private set
    var bgTrans = Color.argb(222, 247, 248, 252); private set

    /** Terapkan palet terang/gelap. Berlaku untuk buyer, seller, dan developer. */
    fun applyTheme(dark: Boolean) {
        if (!dark) {
            ink = Color.rgb(27, 35, 53)
            blue = Color.rgb(0, 99, 214)
            muted = Color.rgb(112, 120, 137)
            bg = Color.rgb(247, 248, 252)
            card = Color.WHITE
            divider = Color.rgb(232, 234, 240)
            tint = Color.rgb(232, 242, 255)
            border = Color.rgb(224, 228, 236)
            star = Color.rgb(225, 151, 0)
            danger = Color.rgb(206, 60, 60)
            dangerBorder = Color.rgb(240, 214, 214)
            success = Color.rgb(31, 158, 95)
            successSoft = Color.rgb(230, 248, 239)
            warnSoft = Color.rgb(211, 228, 251)
            warnBox = Color.rgb(255, 244, 224)
            pressOverlay = Color.argb(32, 15, 25, 45)
            glass = Color.argb(231, 22, 30, 52)
            glassBorder = Color.argb(46, 255, 255, 255)
            glassShadow = Color.argb(42, 5, 10, 25)
            bgTrans = Color.argb(222, 247, 248, 252)
        } else {
            ink = Color.rgb(235, 238, 245)
            blue = Color.rgb(80, 150, 255)
            muted = Color.rgb(150, 158, 175)
            bg = Color.rgb(13, 17, 26)
            card = Color.rgb(26, 32, 48)
            divider = Color.rgb(48, 56, 76)
            tint = Color.rgb(30, 50, 90)
            border = Color.rgb(48, 56, 76)
            star = Color.rgb(225, 151, 0)
            danger = Color.rgb(235, 110, 110)
            dangerBorder = Color.rgb(90, 50, 50)
            success = Color.rgb(60, 190, 120)
            successSoft = Color.rgb(24, 60, 45)
            warnSoft = Color.rgb(40, 60, 100)
            warnBox = Color.rgb(64, 48, 20)
            pressOverlay = Color.argb(48, 255, 255, 255)
            glass = Color.argb(228, 24, 33, 58)
            glassBorder = Color.argb(52, 255, 255, 255)
            glassShadow = Color.argb(60, 0, 0, 0)
            bgTrans = Color.argb(222, 13, 17, 26)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /*
     * Hasil ukuran teks hanya bergantung pada argumennya, sehingga aman disimpan.
     * Sebelum ada cache ini, measure() mengubah state paint lalu mengukur ulang
     * label yang sama (chips kategori, "Lihat semua", ringkasan) setiap frame.
     */
    private val measureCache = HashMap<String, Float>()

    // Dipakai ulang tiap frame; Path baru per gambar per frame menimbulkan churn GC.
    private val clipPath = Path()

    /*
     * Decoded once per resource and reused. Decoding inside onDraw (the previous
     * behaviour) allocated ~44 MB of bitmap per Home frame and recycled it
     * immediately, which is what made scrolling stutter.
     */
    private val bitmaps = HashMap<Int, Bitmap>()

    fun rounded(c: Canvas, r: RectF, color: Int, radius: Float = 16f, stroke: Int? = null) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRoundRect(r, radius, radius, paint)
        if (stroke != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = stroke
            c.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    /**
     * Kartu permukaan: bayangan halus dua lapis + isi + hairline.
     * Dipakai untuk kartu konten (produk, order, menu, profil) agar terasa
     * terangkat tanpa terlihat berat. Pil/chip/tombol tetap memakai rounded
     * polos supaya tidak semua elemen berbayang.
     */
    fun card(c: Canvas, r: RectF, radius: Float = 16f) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(16, 15, 25, 45)
        c.drawRoundRect(RectF(r.left, r.top + 2f, r.right, r.bottom + 3f), radius, radius, paint)
        paint.color = Color.argb(10, 15, 25, 45)
        c.drawRoundRect(RectF(r.left, r.top + 5f, r.right, r.bottom + 6f), radius, radius, paint)
        rounded(c, r, card, radius, border)
    }

    fun rect(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRect(left, top, right, bottom, paint)
    }

    fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = ink, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) medium else regular
        c.drawText(value, x, y, paint)
    }

    fun centerText(c: Canvas, value: String, r: RectF, size: Float, color: Int = ink, bold: Boolean = false) {
        val w = measure(value, size, bold)
        text(c, value, r.centerX() - w / 2f, r.centerY() + size * .34f, size, color, bold)
    }

    fun measure(value: String, size: Float, bold: Boolean = false): Float =
        measureCache.getOrPut("$size|$bold|$value") {
            paint.textSize = size
            paint.typeface = if (bold) medium else regular
            paint.measureText(value)
        }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int = divider) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = color
        c.drawLine(x1, y1, x2, y2, paint)
        paint.style = Paint.Style.FILL
    }

    fun button(c: Canvas, label: String, r: RectF, color: Int = blue, radius: Float = 14f) {
        rounded(c, r, color, radius)
        centerText(c, label, r, 15f, Color.WHITE, true)
    }

    fun image(c: Canvas, resId: Int, r: RectF, radius: Float = 0f) {
        val bmp = bitmap(resId) ?: return
        val round = radius * res.displayMetrics.density / canvasScale()
        val save = c.save()
        clipPath.reset()
        clipPath.addRoundRect(r, round, round, Path.Direction.CW)
        c.clipPath(clipPath)
        val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
        val sw = r.width() / scale
        val sh = r.height() / scale
        val src = Rect(
            ((bmp.width - sw) / 2f).toInt(),
            ((bmp.height - sh) / 2f).toInt(),
            ((bmp.width + sw) / 2f).toInt(),
            ((bmp.height + sh) / 2f).toInt()
        )
        c.drawBitmap(bmp, src, r, paint)
        c.restoreToCount(save)
    }

    private fun bitmap(resId: Int): Bitmap? {
        bitmaps[resId]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeResource(res, resId, opts)?.also { bitmaps[resId] = it }
    }

    // drawable-nodpi images are up to 876x2983; capped so a full-screen hero stays
    // sharp while keeping the whole cache under ~8 MB for the three used images.
    private fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        while (maxOf(width, height) / size > MAX_BITMAP_EDGE) size *= 2
        return size
    }

    private companion object {
        const val MAX_BITMAP_EDGE = 1600
    }
}

internal object ProductImageCache {
    val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
}
