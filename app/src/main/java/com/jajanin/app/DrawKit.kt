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

    val ink = Color.rgb(27, 35, 53)
    val blue = Color.rgb(0, 99, 214)
    val muted = Color.rgb(112, 120, 137)
    val bg = Color.rgb(247, 248, 252)
    val divider = Color.rgb(232, 234, 240)
    val tint = Color.rgb(232, 242, 255)
    val border = Color.rgb(224, 228, 236)
    val star = Color.rgb(225, 151, 0)
    val danger = Color.rgb(206, 60, 60)
    val dangerBorder = Color.rgb(240, 214, 214)
    val success = Color.rgb(31, 158, 95)
    val successSoft = Color.rgb(230, 248, 239)
    val warnSoft = Color.rgb(211, 228, 251)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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

    fun measure(value: String, size: Float, bold: Boolean = false): Float {
        paint.textSize = size
        paint.typeface = if (bold) medium else regular
        return paint.measureText(value)
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
        c.clipPath(Path().apply { addRoundRect(r, round, round, Path.Direction.CW) })
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
