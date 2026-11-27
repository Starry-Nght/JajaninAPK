package com.jajanin.app.core.ui.components

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.jajanin.app.JajaninView
import com.jajanin.app.R
import com.jajanin.app.core.ui.drawIcon

/**
 * Shared application icon system backed by local VectorDrawable XML resources (`res/drawable/ic_*.xml`).
 *
 * Replaces inconsistent raw Unicode glyphs across Canvas screens, native Views, and dialogs while
 * preserving crisp sub-pixel positioning, Light/Dark theme tinting, and accessibility descriptions.
 */
internal object AppIcon {
    @Volatile
    private var appResources: Resources? = null

    private val cachedDrawables = HashMap<Int, Drawable>()

    fun init(context: Context) {
        if (appResources == null) {
            appResources = context.applicationContext.resources
        }
    }

    /** Maps semantic icon keys to local VectorDrawable resource IDs. */
    fun resolveDrawableRes(name: String): Int = when (name) {
        "home" -> R.drawable.ic_nav_home
        "explore" -> R.drawable.ic_nav_explore
        "orders" -> R.drawable.ic_nav_orders
        "profile", "person" -> R.drawable.ic_nav_profile
        "bell" -> R.drawable.ic_nav_bell
        "box" -> R.drawable.ic_nav_box
        "chat" -> R.drawable.ic_nav_chat
        "grid" -> R.drawable.ic_nav_grid
        "receipt" -> R.drawable.ic_nav_receipt
        "plus" -> R.drawable.ic_nav_plus
        "minus" -> R.drawable.ic_app_minus
        "search" -> R.drawable.ic_app_search
        "store" -> R.drawable.ic_app_store
        "verified" -> R.drawable.ic_store_verified
        "devBadge", "dev" -> R.drawable.ic_dev_badge
        "chevronLeft" -> R.drawable.ic_app_chevron_left
        "chevronRight" -> R.drawable.ic_app_chevron_right
        "chevronDown" -> R.drawable.ic_app_chevron_down
        "chevronUp" -> R.drawable.ic_app_chevron_up
        "arrowRight" -> R.drawable.ic_app_arrow_right
        "arrowLeft" -> R.drawable.ic_app_arrow_left
        "close" -> R.drawable.ic_app_close
        "check" -> R.drawable.ic_app_check
        "checkDouble" -> R.drawable.ic_app_check_double
        "radioChecked" -> R.drawable.ic_app_radio_checked
        "radioUnchecked" -> R.drawable.ic_app_radio_unchecked
        "star" -> R.drawable.ic_app_star
        "warning", "alert" -> R.drawable.ic_app_warning
        "location" -> R.drawable.ic_app_location
        "image" -> R.drawable.ic_app_image
        "theme" -> R.drawable.ic_app_theme
        else -> 0
    }

    /** Standardized Indonesian accessibility descriptions for screen readers & Views. */
    fun contentDescription(name: String): String = when (name) {
        "home" -> "Beranda"
        "explore" -> "Jelajah"
        "orders" -> "Pesanan"
        "profile", "person" -> "Profil"
        "bell" -> "Notifikasi"
        "box" -> "Keranjang"
        "chat" -> "Pesan chat"
        "search" -> "Cari"
        "store" -> "Toko"
        "verified" -> "Toko Terverifikasi Jajanin"
        "devBadge", "dev" -> "Developer Resmi Jajanin"
        "chevronLeft", "arrowLeft" -> "Kembali"
        "chevronRight", "arrowRight" -> "Lanjut"
        "chevronDown" -> "Buka daftar"
        "chevronUp" -> "Tutup daftar"
        "close" -> "Tutup"
        "check" -> "Terkirim"
        "checkDouble" -> "Dibaca"
        "radioChecked" -> "Dipilih"
        "radioUnchecked" -> "Belum dipilih"
        "star" -> "Bintang"
        "warning", "alert" -> "Peringatan"
        "location" -> "Lokasi pengambilan"
        "image" -> "Gambar"
        "plus" -> "Tambah"
        "minus" -> "Kurangi"
        "theme" -> "Tema aplikasi"
        else -> name
    }

    /**
     * Renders a local VectorDrawable centered at `(cx, cy)` with virtual width `size` at full
     * native device pixel density. Returns `true` if drawn from XML resource, `false` if caller
     * should fall back to procedural path icon.
     */
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int): Boolean {
        val res = appResources ?: return false
        val resId = resolveDrawableRes(name)
        if (resId == 0 || size <= 0f) return false

        val drawable = synchronized(cachedDrawables) {
            cachedDrawables.getOrPut(resId) {
                ResourcesCompat.getDrawable(res, resId, null)?.mutate() ?: return false
            }
        }
        val density = res.displayMetrics.density.coerceAtLeast(1f)
        val pxSize = (size * density * 1.5f).toInt().coerceIn(24, 192)

        drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.setBounds(0, 0, pxSize, pxSize)

        val save = c.save()
        c.translate(cx - size * 0.5f, cy - size * 0.5f)
        val scale = size / pxSize.toFloat()
        c.scale(scale, scale)
        drawable.draw(c)
        c.restoreToCount(save)
        return true
    }

    /** Creates an independent tinted Drawable instance for Android `ImageView` / `TextView` widgets. */
    fun drawable(context: Context, name: String, sizeDp: Float, color: Int): Drawable? {
        init(context)
        val resId = resolveDrawableRes(name)
        if (resId == 0) return null
        val d = ResourcesCompat.getDrawable(context.resources, resId, context.theme)?.mutate() ?: return null
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(12)
        d.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        d.setBounds(0, 0, px, px)
        return d
    }
}

/**
 * Draws a right-aligned text label paired with a standardized trailing vector arrow (`arrowRight`),
 * replacing legacy hardcoded `"Label →"` strings.
 */
internal fun JajaninView.drawTrailingActionWithArrow(
    c: Canvas,
    label: String,
    rightX: Float,
    baselineY: Float,
    textSize: Float,
    color: Int,
    bold: Boolean = true,
) {
    val iconSize = (textSize * 1.05f).coerceIn(10f, 15f)
    val gap = 4f
    val iconCx = rightX - iconSize * 0.5f
    val iconCy = baselineY - textSize * 0.34f
    drawIcon(c, "arrowRight", iconCx, iconCy, iconSize, color)
    val textW = kit.measure(label, textSize, bold) + 8f
    kit.textRight(c, label, rightX - iconSize - gap, baselineY, textW, textSize, color, bold)
}

/**
 * Draws a centered button label paired with a standardized trailing vector arrow (`arrowRight`)
 * inside `rect`, replacing `"${label} →"` button text.
 */
internal fun JajaninView.drawCenteredButtonWithArrow(
    c: Canvas,
    label: String,
    rect: RectF,
    textSize: Float,
    color: Int,
    bold: Boolean = true,
) {
    val iconSize = (textSize * 1.05f).coerceIn(11f, 16f)
    val gap = 5f
    val textW = kit.measure(label, textSize, bold)
    val totalW = textW + gap + iconSize
    val startX = rect.centerX() - totalW * 0.5f
    val baselineY = rect.centerY() + textSize * 0.35f
    kit.text(c, label, startX, baselineY, textSize, color, bold)
    drawIcon(c, "arrowRight", startX + textW + gap + iconSize * 0.5f, rect.centerY(), iconSize, color)
}
