package com.jajanin.app.core.ui

import com.jajanin.app.feature.seller.*

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.data.model.ProductRow

/** Thumbnail produk bersama (Buyer & Seller): gambar URL atau placeholder "J". */
internal fun JajaninView.drawProductImage(c: Canvas, product: ProductRow, rect: RectF, radius: Float) {
    val url = product.imageUrl?.takeIf { it.startsWith("https://") }
    val bitmap = url?.let { ProductImageCache.images[it] }
    if (bitmap == null) {
        kit.rounded(c, rect, kit.tint, radius)
        kit.centerText(c, "J", rect, 28f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
        return
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
    val save = c.save()
    c.clipPath(path)
    c.drawBitmap(bitmap, null, rect, paint)
    c.restoreToCount(save)
}
