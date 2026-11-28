package com.jajanin.app.core.ui

import com.jajanin.app.feature.seller.*

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.data.model.ProductRow

private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val imageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val imageClipPath = Path()
private val srcRect = Rect()
private val fitDstRect = RectF()

/**
 * Menggambar seluruh bitmap secara utuh tanpa cropping (ContentScale.Fit / object-fit: contain),
 * mempertahankan aspect ratio asli untuk foto portrait, landscape, maupun square.
 * Ruang kosong di sekitar foto diisi dengan [backgroundColor] netral sesuai tema.
 */
internal fun drawBitmapFit(
    c: Canvas,
    bitmap: Bitmap,
    rect: RectF,
    radius: Float = 0f,
    backgroundColor: Int? = null,
    inset: Float = 0f,
) {
    val bw = bitmap.width
    val bh = bitmap.height
    val rw = rect.width()
    val rh = rect.height()
    if (bw <= 0 || bh <= 0 || rw <= 0f || rh <= 0f) return

    val availW = (rw - inset * 2f).coerceAtLeast(1f)
    val availH = (rh - inset * 2f).coerceAtLeast(1f)
    val scale = minOf(availW / bw.toFloat(), availH / bh.toFloat())
    val dstW = bw * scale
    val dstH = bh * scale
    val dstLeft = rect.centerX() - dstW * 0.5f
    val dstTop = rect.centerY() - dstH * 0.5f
    fitDstRect.set(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)

    val save = c.save()
    if (radius > 0f) {
        imageClipPath.reset()
        imageClipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(imageClipPath)
    } else {
        c.clipRect(rect)
    }
    if (backgroundColor != null) {
        imageBgPaint.style = Paint.Style.FILL
        imageBgPaint.color = backgroundColor
        c.drawRect(rect, imageBgPaint)
    }
    c.drawBitmap(bitmap, null, fitDstRect, imagePaint)
    c.restoreToCount(save)
}

/**
 * Menggambar bitmap dengan proporsi asli (Center-Crop / ContentScale.Crop)
 * sehingga foto menu tidak pernah gepeng atau ketarik (stretched).
 */
internal fun drawBitmapCenterCrop(c: Canvas, bitmap: Bitmap, rect: RectF, radius: Float) {
    val bw = bitmap.width
    val bh = bitmap.height
    val rw = rect.width()
    val rh = rect.height()
    if (bw <= 0 || bh <= 0 || rw <= 0f || rh <= 0f) return

    val scale = maxOf(rw / bw.toFloat(), rh / bh.toFloat())
    val sw = rw / scale
    val sh = rh / scale
    val left = ((bw - sw) * 0.5f).toInt().coerceAtLeast(0)
    val top = ((bh - sh) * 0.5f).toInt().coerceAtLeast(0)
    val right = (left + sw.toInt()).coerceAtMost(bw)
    val bottom = (top + sh.toInt()).coerceAtMost(bh)
    srcRect.set(left, top, right, bottom)

    imageClipPath.reset()
    imageClipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
    val save = c.save()
    c.clipPath(imageClipPath)
    c.drawBitmap(bitmap, srcRect, rect, imagePaint)
    c.restoreToCount(save)
}

/** Thumbnail produk bersama (Buyer & Seller): gambar URL center-crop atau placeholder "J". */
internal fun JajaninView.drawProductImage(c: Canvas, product: ProductRow, rect: RectF, radius: Float) {
    val url = product.imageUrl?.takeIf { it.startsWith("https://") }
    val bitmap = url?.let { ProductImageCache.images[it] }
    if (bitmap == null) {
        kit.rounded(c, rect, kit.tint, radius)
        kit.centerText(c, "J", rect, 22f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
        return
    }
    drawBitmapCenterCrop(c, bitmap, rect, radius)
}
