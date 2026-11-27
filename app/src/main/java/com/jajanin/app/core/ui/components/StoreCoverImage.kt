package com.jajanin.app.core.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.feature.seller.loadProductImage

/**
 * Reusable Store Cover / Banner Image component.
 *
 * Renders the seller's uploaded cover photo center-cropped with a subtle bottom scrim, or a clean
 * architectural geometric gradient surface consistent with Jajanin's Light & Dark themes.
 */
internal object StoreCoverImage {

    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val clipPath = Path()

    fun draw(
        view: JajaninView,
        c: Canvas,
        bannerUrl: String?,
        rect: RectF,
        cornerRadius: Float = 0f,
    ) {
        val kit = view.kit
        val validUrl = bannerUrl?.trim()?.takeIf { it.startsWith("https://") }
        val bmp = validUrl?.let { ProductImageCache.images[it] }
        if (bmp != null) {
            drawBitmapCenterCrop(c, bmp, rect, cornerRadius)
            // Subtle bottom gradient scrim so overlaid avatar / back button remain readable
            gradPaint.shader = LinearGradient(
                0f, rect.top,
                0f, rect.bottom,
                intArrayOf(
                    Color.argb(68, 10, 15, 28),
                    Color.argb(18, 10, 15, 28),
                    Color.argb(110, 10, 15, 28),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            if (cornerRadius > 0f) {
                clipPath.reset()
                clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
                val save = c.save()
                c.clipPath(clipPath)
                c.drawRect(rect, gradPaint)
                c.restoreToCount(save)
            } else {
                c.drawRect(rect, gradPaint)
            }
            gradPaint.shader = null
            return
        }

        if (validUrl != null) {
            view.activity()?.loadProductImage(validUrl)
        }

        // Clean architectural gradient cover fallback (Light & Dark aware)
        val c1 = if (kit.isDark) Color.rgb(20, 34, 64) else Color.rgb(18, 68, 148)
        val c2 = if (kit.isDark) Color.rgb(15, 22, 38) else Color.rgb(0, 99, 214)
        gradPaint.shader = LinearGradient(
            rect.left, rect.top,
            rect.right, rect.bottom,
            c1, c2,
            Shader.TileMode.CLAMP,
        )
        val save = c.save()
        if (cornerRadius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            c.clipPath(clipPath)
        } else {
            c.clipRect(rect)
        }
        c.drawRect(rect, gradPaint)
        gradPaint.shader = null

        // Subtle architectural diagonal grid lines (lightweight, zero bitmap allocation)
        accentLinePaint.color = Color.argb(26, 255, 255, 255)
        var gx = rect.left - rect.height()
        while (gx < rect.right + rect.height()) {
            c.drawLine(gx, rect.bottom, gx + rect.height() * 0.75f, rect.top, accentLinePaint)
            gx += 28f
        }
        AppIcon.draw(
            c,
            "store",
            rect.right - 36f,
            rect.centerY(),
            36f,
            Color.argb(40, 255, 255, 255),
        )
        c.restoreToCount(save)
    }
}
