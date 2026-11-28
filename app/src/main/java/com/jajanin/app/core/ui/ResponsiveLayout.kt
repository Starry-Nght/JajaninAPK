package com.jajanin.app.core.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Adaptive responsive viewport & System UI compatibility utilities for JAJANIN.
 *
 * Ensures consistent, distortion-free layout across:
 * - Compact phones (720x1280 / 320-360dp), HD+ (720x1600), FHD+ (1080x2400), QHD+ (1440x3200)
 * - Tablets in Portrait & Landscape (600dp-1440dp) and Phone Landscape
 * - Status bars, 3-button / gesture navigation bars, display cutouts (notch / punch-hole), and IME
 * - Android system accessibility font scaling (`fontScale`)
 */
internal object ResponsiveLayout {
    const val DESIGN_WIDTH = 390f
    const val MIN_VIRTUAL_HEIGHT = 520f
    const val PHONE_MAX_PORTRAIT_WIDTH_DP = 520f
    const val TABLET_MAX_CONTENT_WIDTH_DP = 520f
    const val LANDSCAPE_COMPACT_MAX_WIDTH_DP = 460f
    const val MIN_TOUCH_TARGET_DP = 48f

    data class AdaptiveViewport(
        val hostWidthPx: Int,
        val hostHeightPx: Int,
        val canvasScale: Float,
        val virtualHeight: Float,
        val isCenteredColumn: Boolean,
    )

    data class SafeInsetsPx(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val imeBottom: Int,
    )

    /**
     * Computes adaptive viewport dimensions for the 390-unit canvas inside safe bounds
     * `(safeWidthPx, safeHeightPx)` after system bars & display cutouts are removed.
     *
     * - Portrait phones (<= 520dp wide, >= 520dp tall): full-width edge-to-edge.
     * - Tablets & landscape viewports: caps width and enforces a minimum virtual viewport
     *   height (`MIN_VIRTUAL_HEIGHT`) so typography and controls never blow up giant or clip.
     */
    fun computeAdaptiveViewport(
        safeWidthPx: Int,
        safeHeightPx: Int,
        density: Float,
    ): AdaptiveViewport {
        val safeW = safeWidthPx.coerceAtLeast(1)
        val safeH = safeHeightPx.coerceAtLeast(1)
        val d = density.coerceAtLeast(0.75f)
        val safeWidthDp = safeW / d
        val safeHeightDp = safeH / d

        val isPortraitPhone = safeWidthDp <= PHONE_MAX_PORTRAIT_WIDTH_DP &&
            safeHeightDp >= MIN_VIRTUAL_HEIGHT
        if (isPortraitPhone) {
            val scale = safeW / DESIGN_WIDTH
            return AdaptiveViewport(
                hostWidthPx = safeW,
                hostHeightPx = safeH,
                canvasScale = scale,
                virtualHeight = safeH / scale,
                isCenteredColumn = false,
            )
        }

        val targetWidthDp = if (safeHeightDp < MIN_VIRTUAL_HEIGHT) {
            minOf(safeWidthDp, LANDSCAPE_COMPACT_MAX_WIDTH_DP)
        } else {
            minOf(safeWidthDp, TABLET_MAX_CONTENT_WIDTH_DP)
        }
        val scaleByWidth = (targetWidthDp * d) / DESIGN_WIDTH
        val scaleByHeight = safeH / MIN_VIRTUAL_HEIGHT
        val canvasScale = minOf(scaleByWidth, scaleByHeight).coerceAtLeast(0.5f)
        val hostW = (DESIGN_WIDTH * canvasScale).roundToInt().coerceIn(1, safeW)
        val effectiveScale = hostW / DESIGN_WIDTH
        return AdaptiveViewport(
            hostWidthPx = hostW,
            hostHeightPx = safeH,
            canvasScale = effectiveScale,
            virtualHeight = safeH / effectiveScale,
            isCenteredColumn = hostW < safeW - 2,
        )
    }

    data class HitBox(
        val key: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    /**
     * Pure two-pass accessible hit-testing:
     * 1) Exact visual hit (topmost-first).
     * 2) Symmetric expansion of compact targets (< [minTargetSize]) to at least [minTargetSize]
     *    (48dp) picking the nearest target center so small icons/chips remain easy to tap without
     *    overlapping neighbors stealing direct hits.
     */
    fun resolveAccessibleHit(
        x: Float,
        y: Float,
        boxes: List<HitBox>,
        minTargetSize: Float = MIN_TOUCH_TARGET_DP,
    ): String? {
        for (i in boxes.lastIndex downTo 0) {
            val box = boxes[i]
            if (box.contains(x, y)) return box.key
        }
        if (minTargetSize <= 0f) return null
        var bestKey: String? = null
        var bestDistSq = Float.MAX_VALUE
        for (i in boxes.lastIndex downTo 0) {
            val box = boxes[i]
            val padX = ((minTargetSize - box.width) * 0.5f).coerceAtLeast(0f)
            val padY = ((minTargetSize - box.height) * 0.5f).coerceAtLeast(0f)
            if (padX <= 0f && padY <= 0f) continue
            if (x >= box.left - padX && x <= box.right + padX &&
                y >= box.top - padY && y <= box.bottom + padY
            ) {
                val dx = x - box.centerX
                val dy = y - box.centerY
                val distSq = dx * dx + dy * dy
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestKey = box.key
                }
            }
        }
        return bestKey
    }

    /** Pure adaptive dialog width calculation for compact phones through wide landscape tablets. */
    fun computeDialogWidthPx(
        screenWidthPx: Int,
        density: Float,
        fraction: Float = 0.92f,
        maxWidthDp: Float = 460f,
    ): Int {
        val d = density.coerceAtLeast(0.75f)
        val byFraction = (screenWidthPx * fraction).roundToInt()
        val byMaxDp = (maxWidthDp * d).roundToInt()
        val minSafe = (screenWidthPx - (24f * d).roundToInt()).coerceAtLeast(240)
        return minOf(byFraction, byMaxDp, minSafe).coerceAtLeast(240)
    }

    /**
     * Unified Android accessibility font-scale curve for all JAJANIN canvas screens
     * (Buyer, Seller, Developer). Honors user font preferences while clamping extremes
     * to prevent text overlap or card clipping.
     */
    fun adaptiveTextScale(fontScale: Float): Float {
        val fs = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return if (fs < 1f) {
            (1f + (fs - 1f) * 0.65f).coerceIn(0.88f, 1.0f)
        } else {
            (1f + (fs - 1f) * 0.45f).coerceIn(1.0f, 1.18f)
        }
    }

    /**
     * Extracts safe area insets combining status bar, navigation bar (gesture or 3-button,
     * bottom or side in landscape), display cutout (notch / punch-hole), and soft keyboard IME.
     */
    fun extractSafeInsets(insets: WindowInsets): SafeInsetsPx {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sys = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsets.Type.ime())
            return SafeInsetsPx(
                left = sys.left.coerceAtLeast(0),
                top = sys.top.coerceAtLeast(0),
                right = sys.right.coerceAtLeast(0),
                bottom = sys.bottom.coerceAtLeast(0),
                imeBottom = ime.bottom.coerceAtLeast(0),
            )
        }
        @Suppress("DEPRECATION")
        var left = insets.systemWindowInsetLeft
        @Suppress("DEPRECATION")
        var top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        var right = insets.systemWindowInsetRight
        @Suppress("DEPRECATION")
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let { cutout ->
                left = maxOf(left, cutout.safeInsetLeft)
                top = maxOf(top, cutout.safeInsetTop)
                right = maxOf(right, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }
        return SafeInsetsPx(
            left = left.coerceAtLeast(0),
            top = top.coerceAtLeast(0),
            right = right.coerceAtLeast(0),
            bottom = bottom.coerceAtLeast(0),
            imeBottom = 0,
        )
    }

    /**
     * Consumes system bar & cutout insets on API 24-35+ so child views that also install
     * WindowInsets listeners (e.g. LiquidGlassNavHost) never double-apply bottom padding.
     */
    fun consumeSystemInsets(insets: WindowInsets): WindowInsets {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.CONSUMED
        } else {
            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }
    }

    /**
     * Configures clean Edge-to-Edge system bar appearance (status bar, navigation bar,
     * notch/punch-hole display cutout, and light/dark system bar icons) without legacy
     * `FLAG_FULLSCREEN` clipping.
     */
    fun configureSystemBars(window: Window, darkMode: Boolean, surfaceBgColor: Int) {
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION")
                window.isStatusBarContrastEnforced = false
            }
            window.insetsController?.let { controller ->
                val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (darkMode) 0 else lightMask, lightMask)
            }
        } else {
            @Suppress("DEPRECATION")
            window.statusBarColor = surfaceBgColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = surfaceBgColor
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!darkMode) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }
}

/** Adaptive dialog width capped on tablets/landscape while filling compact phones cleanly. */
internal fun Context.adaptiveDialogWidthPx(
    fraction: Float = 0.92f,
    maxWidthDp: Float = 460f,
): Int {
    val dm = resources.displayMetrics
    return ResponsiveLayout.computeDialogWidthPx(dm.widthPixels, dm.density, fraction, maxWidthDp)
}

/** Adaptive dialog max height preventing overflow on compact phones or landscape screens. */
internal fun Context.adaptiveDialogMaxHeightPx(fraction: Float = 0.86f): Int {
    val dm = resources.displayMetrics
    return (dm.heightPixels * fraction).roundToInt().coerceAtLeast((240f * dm.density).roundToInt())
}

/** Computes horizontal centering padding for native full-screen forms/chat on wide screens. */
internal fun adaptiveHorizontalPaddingPx(
    availableWidthPx: Int,
    density: Float,
    basePadDp: Float,
    maxContentWidthDp: Float,
): Int {
    val basePx = (basePadDp * density).roundToInt()
    val maxPx = (maxContentWidthDp * density).roundToInt()
    if (availableWidthPx <= maxPx + basePx * 2) return basePx
    return ((availableWidthPx - maxPx) / 2).coerceAtLeast(basePx)
}
