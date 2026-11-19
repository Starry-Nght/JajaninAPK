package com.jajanin.app.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.BlurMethod
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unclipped container hosting:
 * 1) [dockSurface] (LiquidGlassView) — renders the rounded glass bar backdrop & specular rim.
 * 2) [droplet] (LensDropletView) — sibling above [dockSurface] so when it transforms big & shiny
 *    on hold, its top/bottom/side edges are NEVER clipped by [LiquidGlassView]'s outline clip!
 * 3) [tabsRow] (LinearLayout) — sibling above [droplet] so icons & labels scale freely in 3D.
 */
internal class JajaninGlassTabBar(context: Context) : FrameLayout(context) {

    class TabItem(val title: CharSequence, val icon: Drawable? = null)

    var onTabSelected: ((index: Int) -> Unit)? = null

    var selectedTintColor: Int? = null
        set(value) {
            if (field != value) {
                field = value
                updateTabStyles()
            }
        }

    private var selected = 0

    var selectedIndex: Int
        get() = selected
        set(value) = selectTab(value, animate = true)

    private class TabHolder(val root: LinearLayout, val icon: ImageView?, val label: TextView)

    private val dockSurface = BarGlassSurfaceView(context)
    private val tabsRow = LinearLayout(context)
    private val tabs = mutableListOf<TabHolder>()
    private val droplet = LensDropletView(context)

    private var overLightAppearance = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    private var holding = false
    private var heldTabIndex = -1
    private var holdProgress = 0f
    private var settleAnimator: ValueAnimator? = null
    private var holdAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null

    private var padL = 0
    private var padT = 0
    private var padR = 0
    private var padB = 0

    private val holdTriggerRunnable = Runnable {
        if (holding && !dragging) {
            if (heldTabIndex in tabs.indices && heldTabIndex != selected) {
                // If user actually holds another tab, smoothly slide there before going big & shiny
                selectTab(heldTabIndex, animate = true)
            }
            animateHoldState(target = 1f, durationMs = 240L)
        }
    }

    // Forward LiquidGlassView configuration properties to dockSurface
    var enableDynamicBackground: Boolean
        get() = dockSurface.enableDynamicBackground
        set(value) { dockSurface.enableDynamicBackground = value }

    var material: GlassMaterial
        get() = dockSurface.material
        set(value) { dockSurface.material = value }

    var enableBackdropBlur: Boolean
        get() = dockSurface.enableBackdropBlur
        set(value) { dockSurface.enableBackdropBlur = value }

    var blurAmount: Float
        get() = dockSurface.blurAmount
        set(value) { dockSurface.blurAmount = value }

    var cornerRadius: Float
        get() = dockSurface.cornerRadius
        set(value) {
            dockSurface.cornerRadius = value
            dockSurface.rebuildBarGeometry(dockSurface.width, dockSurface.height)
            dockSurface.invalidate()
        }

    var adaptiveLensScale: Boolean
        get() = dockSurface.adaptiveLensScale
        set(value) { dockSurface.adaptiveLensScale = value }

    var bevelWidth: Float
        get() = dockSurface.bevelWidth
        set(value) { dockSurface.bevelWidth = value }

    var refractionHeight: Float
        get() = dockSurface.refractionHeight
        set(value) { dockSurface.refractionHeight = value }

    var refractionFalloff: Float
        get() = dockSurface.refractionFalloff
        set(value) { dockSurface.refractionFalloff = value }

    var edgeSoftness: Float
        get() = dockSurface.edgeSoftness
        set(value) { dockSurface.edgeSoftness = value }

    var dispersionStrength: Float
        get() = dockSurface.dispersionStrength
        set(value) { dockSurface.dispersionStrength = value }

    var enableChromaticDispersion: Boolean
        get() = dockSurface.enableChromaticDispersion
        set(value) { dockSurface.enableChromaticDispersion = value }

    var enableChromaticAberration: Boolean
        get() = dockSurface.enableChromaticAberration
        set(value) { dockSurface.enableChromaticAberration = value }

    var enableEdgeHighlight: Boolean
        get() = dockSurface.enableEdgeHighlight
        set(value) { dockSurface.enableEdgeHighlight = value }

    var edgeHighlightOpacity: Float
        get() = dockSurface.edgeHighlightOpacity
        set(value) { dockSurface.edgeHighlightOpacity = value }

    var enableSensorHighlight: Boolean
        get() = dockSurface.enableSensorHighlight
        set(value) { dockSurface.enableSensorHighlight = value }

    var enableAdaptiveTint: Boolean
        get() = dockSurface.enableAdaptiveTint
        set(value) { dockSurface.enableAdaptiveTint = value }

    var glassTint: Int
        get() = dockSurface.glassTint
        set(value) { dockSurface.glassTint = value }

    var saturation: Float
        get() = dockSurface.saturation
        set(value) { dockSurface.saturation = value }

    var downsampleScale: Int
        get() = dockSurface.downsampleScale
        set(value) { dockSurface.downsampleScale = value }

    var collectFrameStats: Boolean
        get() = dockSurface.collectFrameStats
        set(value) { dockSurface.collectFrameStats = value }

    var blurMethod: BlurMethod
        get() = dockSurface.blurMethod
        set(value) { dockSurface.blurMethod = value }

    var enablePressEffect: Boolean
        get() = dockSurface.enablePressEffect
        set(value) { dockSurface.enablePressEffect = value }

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false

        dockSurface.onAppearanceChangedCallback = { isOverLight ->
            overLightAppearance = isOverLight
            droplet.overLight = isOverLight
            updateTabStyles()
        }
        addView(dockSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Pure crystal optical lens droplet (sibling above dockSurface, never clipped by dock outline)
        droplet.apply {
            enablePressEffect = false
            enableBackdropBlur = false
            material = GlassMaterial.CLEAR
            cornerRadius = 999f
            adaptiveLensScale = true
            bevelWidth = dpF(16f)
            refractionHeight = dpF(18f)
            refractionFalloff = 1.5f
            edgeSoftness = dpF(0.6f)
            dispersionStrength = 0.20f
            enableChromaticDispersion = true
            enableEdgeHighlight = false
            enableSensorHighlight = false
            enableAdaptiveTint = false
            glassTint = 0x0E0066FF
            saturation = 144f
            downsampleScale = 1
            collectFrameStats = false
            visibility = GONE
        }
        addView(droplet, LayoutParams(0, 0, Gravity.TOP or Gravity.START))

        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.gravity = Gravity.CENTER_VERTICAL
        tabsRow.clipChildren = false
        tabsRow.clipToPadding = false
        val pad = dp(5)
        setPadding(pad, pad, pad, pad)
        addView(tabsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        overLightAppearance = dockSurface.currentOverLight()
        droplet.overLight = overLightAppearance
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padL = left
        padT = top
        padR = right
        padB = bottom
        // Apply inner padding to tabsRow instead of FrameLayout so dockSurface fills 100% of bar bounds
        // and droplet can translate freely without padding offset confusion.
        tabsRow.setPadding(left, top, right, bottom)
    }

    /** Bind backdrop source to both the outer dock and the inner sliding optical lens. */
    fun setLensBackdropSource(source: View) {
        dockSurface.backdropSource = source
        droplet.backdropSource = source
    }

    fun refreshBackdrop() {
        dockSurface.invalidate()
        droplet.invalidate()
    }

    fun setTabs(items: List<TabItem>) {
        tabsRow.removeAllViews()
        tabs.clear()

        items.forEach { item ->
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            var icon: ImageView? = null
            val label: TextView
            if (item.icon != null) {
                root.setPadding(dp(2), dp(7), dp(2), dp(7))
                icon = ImageView(context).apply { setImageDrawable(item.icon) }
                root.addView(icon, LinearLayout.LayoutParams(dp(25), dp(25)))
                label = TextView(context).apply {
                    text = item.title
                    textSize = 10f
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                root.addView(
                    label,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) },
                )
            } else {
                root.setPadding(dp(4), dp(12), dp(4), dp(12))
                label = TextView(context).apply {
                    text = item.title
                    textSize = 14f
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                root.addView(label)
            }
            tabs += TabHolder(root, icon, label)
            tabsRow.addView(root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        selected = 0
        updateTabStyles()
        droplet.visibility = if (tabs.isEmpty()) GONE else VISIBLE
        requestLayout()
    }

    private fun selectTab(index: Int, animate: Boolean) {
        val clamped = index.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        val changed = clamped != selected
        selected = clamped
        if (changed) updateTabStyles()
        if (animate) animateDropletTo(clamped) else syncDroplet()
        if (changed) onTabSelected?.invoke(clamped)
    }

    private fun updateTabStyles() {
        val selectedColor = selectedTintColor
            ?: if (overLightAppearance) 0xFF0066FF.toInt() else 0xFFFFFFFF.toInt()
        val normalColor = if (overLightAppearance) 0x990F172A.toInt() else 0xC4FFFFFF.toInt()

        tabs.forEachIndexed { index, tab ->
            val isSel = index == selected
            val color = if (isSel) selectedColor else normalColor
            tab.icon?.imageTintList = ColorStateList.valueOf(color)
            tab.label.setTextColor(color)
            tab.label.typeface = Typeface.create(
                "sans-serif-medium",
                if (isSel) Typeface.BOLD else Typeface.NORMAL
            )
            val targetScale = if (isSel) 1.10f else 1.0f
            tab.root.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(220L)
                .start()
        }
        droplet.invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (settleAnimator?.isRunning != true && !dragging) syncDroplet()
    }

    private fun dropletSizeFor(tab: View): Pair<Int, Int> {
        val availH = (((height - padT - padB).takeIf { it > 0 } ?: tab.height) - dp(2)).coerceAtLeast(dp(38))
        // Slightly oval pill (width ~1.28x height on 4-tab bars, or fitting tab width on 5/6-tab bars)
        val ovalW = if (tab.width > availH) {
            min(tab.width - dp(4), (availH * 1.28f).toInt()).coerceAtLeast(availH)
        } else {
            min(tab.width, availH).coerceAtLeast(dp(38))
        }
        return ovalW to availH
    }

    private fun dropletTargetX(tab: View, dropW: Int): Float =
        (tabsRow.left + tab.left + (tab.width - dropW) / 2f)

    private fun dropletTargetY(dropH: Int): Float =
        if (height > 0) (height - dropH) / 2f else padT.toFloat()

    private fun syncDroplet() {
        val tab = tabs.getOrNull(selected)?.root
        if (tab == null || tab.width <= 0) {
            droplet.visibility = GONE
            return
        }
        droplet.visibility = VISIBLE
        val (dw, dh) = dropletSizeFor(tab)
        syncDropletSize(dw, dh)
        val baseY = dropletTargetY(dh)
        droplet.translationX = dropletTargetX(tab, dw)
        droplet.translationY = baseY - dpF(3f) * holdProgress
        droplet.scaleX = 1f + 0.14f * holdProgress
        droplet.scaleY = 1f + 0.16f * holdProgress
        droplet.setHoldShine(holdProgress)
        droplet.invalidate()
    }

    private fun animateHoldState(target: Float, durationMs: Long) {
        holdAnimator?.cancel()
        val start = holdProgress
        if (abs(target - start) < 0.01f) {
            applyHoldTransform(target)
            return
        }
        if (target > 0.05f) startShimmerLoop() else stopShimmerLoop()
        holdAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                val curved = if (target > start) {
                    val t = ((p - start) / (target - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                    start + (target - start) * (1f + 0.14f * sin(PI.toFloat() * t)) * t
                } else {
                    p
                }
                applyHoldTransform(curved.coerceIn(0f, 1.15f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target <= 0.01f) {
                        stopShimmerLoop()
                        applyHoldTransform(0f)
                    }
                }
            })
            start()
        }
    }

    private fun applyHoldTransform(progress: Float) {
        holdProgress = progress
        val tab = tabs.getOrNull(selected)?.root
        val dh = if (tab != null && tab.width > 0) dropletSizeFor(tab).second else droplet.height
        val baseY = dropletTargetY(dh)
        if (settleAnimator?.isRunning != true) {
            droplet.scaleX = 1f + 0.14f * progress
            droplet.scaleY = 1f + 0.16f * progress
            droplet.translationY = baseY - dpF(3f) * progress
            if (dragging) {
                droplet.translationX = clampDropletX(droplet.translationX, droplet.scaleX)
            }
        }
        val activeIdx = if (heldTabIndex in tabs.indices) heldTabIndex else selected
        tabs.forEachIndexed { idx, holder ->
            val baseScale = if (idx == selected) 1.10f else 1.0f
            val boost = if (idx == activeIdx) 0.16f * progress.coerceIn(0f, 1f) else 0f
            holder.root.scaleX = baseScale + boost
            holder.root.scaleY = baseScale + boost
            holder.root.translationY = if (idx == activeIdx) -dpF(3.5f) * progress.coerceIn(0f, 1f) else 0f
        }
        droplet.setHoldShine(progress)
        droplet.invalidate()
        invalidate()
    }

    private fun startShimmerLoop() {
        if (shimmerAnimator?.isRunning == true) return
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1350L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                droplet.setShimmerPhase(anim.animatedValue as Float)
            }
            start()
        }
    }

    private fun stopShimmerLoop() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun syncDropletSize(w: Int, h: Int) {
        val lp = droplet.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            droplet.layoutParams = lp
        }
        droplet.pivotX = w / 2f
        droplet.pivotY = h / 2f
    }

    private fun animateDropletTo(index: Int) {
        val tab = tabs.getOrNull(index)?.root ?: return
        if (tab.width <= 0) return
        val (dw, dh) = dropletSizeFor(tab)
        syncDropletSize(dw, dh)

        settleAnimator?.cancel()
        val startX = droplet.translationX
        val targetX = dropletTargetX(tab, dw)
        val targetY = dropletTargetY(dh)
        val dist = targetX - startX
        if (abs(dist) < 0.5f) {
            syncDroplet()
            return
        }

        val stretch = 0.26f * min(1f, abs(dist) / (tab.width * 2.5f))
        droplet.setLensActive(true)

        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 540
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val envelope = sin(PI.toFloat() * min(t * 1.12f, 1f))
                val holdScaleX = 0.14f * holdProgress
                val holdScaleY = 0.16f * holdProgress
                val sx = 1f + holdScaleX + stretch * envelope
                val sy = 1f + holdScaleY - stretch * 0.46f * envelope
                droplet.scaleX = sx
                droplet.scaleY = sy
                droplet.translationX = clampDropletX(startX + dist * springValue(t), sx)
                droplet.translationY = targetY - dpF(3f) * holdProgress
                droplet.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) syncDroplet()
                }
            })
            start()
        }
    }

    private fun springValue(t: Float): Float {
        val zeta = 0.66f
        val omega = 16.5f
        val omegaD = omega * sqrt(1f - zeta * zeta)
        val decay = exp(-zeta * omega * t)
        return 1f - decay * (cos(omegaD * t) + (zeta * omega / omegaD) * sin(omegaD * t))
    }

    private fun clampDropletX(x: Float, scaleX: Float): Float {
        val rowW = width.toFloat()
        val w = droplet.width.toFloat()
        if (rowW <= 0f || w <= 0f) return x
        val minX = padL.toFloat()
        val maxX = rowW - padR - w
        return if (minX <= maxX) x.coerceIn(minX, maxX) else (rowW - w) / 2f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                holding = true
                heldTabIndex = tabIndexAt(event.x)
                droplet.setLensActive(true)
                // Only pop in-place if pressing current tab; when tapping a new tab, wait for
                // slide animation on release or smooth hold-slide after 160ms
                if (heldTabIndex == selected) {
                    animateHoldState(target = 0.58f, durationMs = 125L)
                }
                removeCallbacks(holdTriggerRunnable)
                postDelayed(holdTriggerRunnable, 160L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    removeCallbacks(holdTriggerRunnable)
                    settleAnimator?.cancel()
                    droplet.setLensActive(true)
                    animateHoldState(target = 1f, durationMs = 180L)
                }
                if (dragging) {
                    heldTabIndex = nearestTabIndex()
                    dragDropletTo(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                holding = false
                removeCallbacks(holdTriggerRunnable)
                val targetIdx = if (dragging) nearestTabIndex() else tabIndexAt(event.x)
                dragging = false
                heldTabIndex = -1
                animateHoldState(target = 0f, durationMs = 310L)
                selectTab(targetIdx, animate = true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                holding = false
                dragging = false
                heldTabIndex = -1
                removeCallbacks(holdTriggerRunnable)
                animateHoldState(target = 0f, durationMs = 270L)
                animateDropletTo(selected)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dragDropletTo(x: Float) {
        if (width <= 0 || droplet.width <= 0) return
        val local = x - droplet.width / 2f
        droplet.translationX = clampDropletX(local, droplet.scaleX)
        droplet.invalidate()
    }

    private fun nearestTabIndex(): Int {
        val centerX = droplet.translationX + droplet.width / 2f
        var best = selected
        var bestDist = Float.MAX_VALUE
        tabs.forEachIndexed { index, tab ->
            val tabCenter = tabsRow.left + tab.root.left + tab.root.width / 2f
            val d = abs(tabCenter - centerX)
            if (d < bestDist) {
                bestDist = d
                best = index
            }
        }
        return best
    }

    private fun tabIndexAt(x: Float): Int {
        val local = x - tabsRow.left
        tabs.forEachIndexed { index, tab ->
            if (local < tab.root.right) return index
        }
        return (tabs.size - 1).coerceAtLeast(0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
}

/**
 * Glass dock surface (extends LiquidGlassView) so its rounded outline only clips the bar itself,
 * never the sibling LensDropletView above it.
 */
private class BarGlassSurfaceView(context: Context) : LiquidGlassView(context) {

    var onAppearanceChangedCallback: ((Boolean) -> Unit)? = null

    private val topGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomCausticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val diagonalSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val barClipPath = Path()
    private val innerBevelPath = Path()
    private val barRect = RectF()
    private val innerRect = RectF()

    init {
        setWillNotDraw(false)
        enablePressEffect = false
    }

    fun currentOverLight(): Boolean = isOverLightBackground

    override fun onAppearanceChanged(isOverLight: Boolean) {
        onAppearanceChangedCallback?.invoke(isOverLight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBarGeometry(w, h)
    }

    fun rebuildBarGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val inset = 0.75f * d
        barRect.set(inset, inset, w - inset, h - inset)
        barClipPath.reset()
        barClipPath.addRoundRect(barRect, cornerRadius, cornerRadius, Path.Direction.CW)

        val bevelInset = 2.2f * d
        innerRect.set(bevelInset, bevelInset, w - bevelInset, h - bevelInset)
        innerBevelPath.reset()
        innerBevelPath.addRoundRect(
            innerRect,
            (cornerRadius - bevelInset).coerceAtLeast(0f),
            (cornerRadius - bevelInset).coerceAtLeast(0f),
            Path.Direction.CW
        )

        topGlossPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h * 0.38f,
            intArrayOf(0x6EFFFFFF, 0x24E0F2FE, 0x00FFFFFF),
            floatArrayOf(0f, 0.36f, 1f),
            Shader.TileMode.CLAMP
        )

        bottomCausticPaint.shader = LinearGradient(
            0f,
            h * 0.72f,
            0f,
            h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x1E38BDF8, 0x42FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        diagonalSheenPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h * 1.1f,
            intArrayOf(
                0x36FFFFFF,
                0x00FFFFFF,
                0x28FFFFFF,
                0x00FFFFFF,
                0x2238BDF8
            ),
            floatArrayOf(0f, 0.28f, 0.48f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )

        outerRimPaint.strokeWidth = (1.25f * d).coerceAtLeast(1f)
        outerRimPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                0xF5FFFFFF.toInt(),
                0x6638BDF8,
                0x330066FF,
                0xCCFFFFFF.toInt()
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        innerBevelPaint.strokeWidth = (0.85f * d).coerceAtLeast(1f)
        innerBevelPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(0x55FFFFFF, 0x00FFFFFF, 0x260066FF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (barClipPath.isEmpty) rebuildBarGeometry(width, height)

        val save = canvas.save()
        canvas.clipPath(barClipPath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), diagonalSheenPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.38f, topGlossPaint)
        canvas.drawRect(0f, height * 0.72f, width.toFloat(), height.toFloat(), bottomCausticPaint)
        canvas.restoreToCount(save)

        canvas.drawPath(innerBevelPath, innerBevelPaint)
        canvas.drawPath(barClipPath, outerRimPaint)
    }
}

/**
 * Crystal water-droplet optical lens with sharp top crescent reflection,
 * lower chromatic caustic glow, 3D double-bevel rim, and dynamic Hold-Shine transformation.
 */
private class LensDropletView(context: Context) : LiquidGlassView(context) {

    private val crescentGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdCoreFlarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdOuterHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val outerLensRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerLensBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pillPath = Path()
    private val innerPillPath = Path()
    private val crescentPath = Path()
    private val pillRect = RectF()
    private val innerPillRect = RectF()
    private val haloRect = RectF()

    private var shineAmount = 0f
    private var shimmerPhase = 0f

    init {
        setWillNotDraw(false)
    }

    fun setLensActive(active: Boolean) {
        if (!active && shineAmount <= 0.01f) {
            setHoldShine(0f)
        } else if (active && shineAmount < 0.35f) {
            setHoldShine(0.35f)
        }
    }

    fun setHoldShine(progress: Float) {
        val p = progress.coerceIn(0f, 1.15f)
        shineAmount = p
        val d = resources.displayMetrics.density
        refractionHeight = (28f + 22f * p) * d
        bevelWidth = (22f + 11f * p) * d
        dispersionStrength = 0.28f + 0.22f * p
        edgeHighlightOpacity = (165f + 85f * p).coerceAtMost(255f)
        saturation = 148f + 32f * p
        if (width > 0 && height > 0) updateDynamicShineShaders(width, height)
        invalidate()
    }

    fun setShimmerPhase(phase: Float) {
        shimmerPhase = phase
        if (shineAmount > 0.02f && width > 0 && height > 0) {
            updateDynamicShineShaders(width, height)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLensShaders(w, h)
        updateDynamicShineShaders(w, h)
    }

    private fun updateDynamicShineShaders(w: Int, h: Int) {
        val p = shineAmount.coerceIn(0f, 1f)
        if (p <= 0.01f) return
        val d = resources.displayMetrics.density

        val flareAlpha = (195 * p).toInt().coerceIn(0, 235)
        val midAlpha = (115 * p).toInt().coerceIn(0, 180)
        val cx = w * (0.38f + 0.24f * sin(shimmerPhase * 2f * PI.toFloat()))
        val cy = h * 0.26f
        holdCoreFlarePaint.shader = RadialGradient(
            cx,
            cy,
            maxOf(w, h) * (0.55f + 0.20f * p),
            intArrayOf(
                (flareAlpha shl 24) or 0xFFFFFF,
                (midAlpha shl 24) or 0x7DD3FC,
                0x0038BDF8
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        val sweepOffset = (shimmerPhase * 1.6f - 0.3f) * w
        val beamAlpha = (150 * p).toInt().coerceIn(0, 210)
        holdSweepPaint.shader = LinearGradient(
            sweepOffset - w * 0.35f,
            0f,
            sweepOffset + w * 0.35f,
            h.toFloat(),
            intArrayOf(
                0x00FFFFFF,
                (beamAlpha shl 24) or 0xE0F2FE,
                ((beamAlpha + 35).coerceAtMost(245) shl 24) or 0xFFFFFF,
                (beamAlpha shl 24) or 0x38BDF8,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )

        holdOuterHaloPaint.strokeWidth = (1.6f + 1.8f * p) * d
        val rimAlpha = (240 * p).toInt().coerceIn(0, 255)
        val cyanAlpha = (190 * p).toInt().coerceIn(0, 240)
        holdOuterHaloPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                (rimAlpha shl 24) or 0xFFFFFF,
                (cyanAlpha shl 24) or 0x38BDF8,
                (rimAlpha shl 24) or 0xFFFFFF
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun rebuildLensShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        // Keep full outer rim + hold halo strictly inside View bounds so Android never chops the top/bottom curve flat.
        val inset = 2.4f * d
        val r = (min(w, h) / 2f - inset).coerceAtLeast(0f)
        pillRect.set(inset, inset, w - inset, h - inset)
        pillPath.reset()
        pillPath.addRoundRect(pillRect, r, r, Path.Direction.CW)
        val haloInset = 2.0f * d
        haloRect.set(haloInset, haloInset, w - haloInset, h - haloInset)

        val innerInset = 3.8f * d
        val innerR = (min(w, h) / 2f - innerInset).coerceAtLeast(0f)
        innerPillRect.set(innerInset, innerInset, w - innerInset, h - innerInset)
        innerPillPath.reset()
        innerPillPath.addRoundRect(innerPillRect, innerR, innerR, Path.Direction.CW)

        val capRect = RectF(w * 0.14f, inset + 1.5f * d, w * 0.86f, h * 0.42f)
        crescentPath.reset()
        crescentPath.addRoundRect(capRect, capRect.height() / 2f, capRect.height() / 2f, Path.Direction.CW)

        crescentGlossPaint.shader = LinearGradient(
            0f,
            capRect.top,
            0f,
            capRect.bottom,
            intArrayOf(0x98FFFFFF.toInt(), 0x32E0F2FE, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        causticPaint.shader = RadialGradient(
            w * 0.5f,
            h * 0.92f,
            w * 0.58f,
            intArrayOf(0x4838BDF8, 0x220066FF, 0x000066FF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        outerLensRimPaint.strokeWidth = (1.35f * d).coerceAtLeast(1f)
        outerLensRimPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0x9938BDF8.toInt(),
                0x660066FF,
                0xEEFFFFFF.toInt()
            ),
            floatArrayOf(0f, 0.42f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        innerLensBevelPaint.strokeWidth = (0.9f * d).coerceAtLeast(1f)
        innerLensBevelPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(0x80FFFFFF.toInt(), 0x00FFFFFF, 0x4438BDF8),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (pillPath.isEmpty) rebuildLensShaders(width, height)

        val save = canvas.save()
        canvas.clipPath(pillPath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), causticPaint)
        if (shineAmount > 0.01f) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), holdCoreFlarePaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), holdSweepPaint)
        }
        canvas.drawPath(crescentPath, crescentGlossPaint)
        canvas.restoreToCount(save)

        canvas.drawPath(innerPillPath, innerLensBevelPaint)
        canvas.drawPath(pillPath, outerLensRimPaint)
        if (shineAmount > 0.01f) {
            val r = min(haloRect.width(), haloRect.height()) / 2f
            canvas.drawRoundRect(haloRect, r, r, holdOuterHaloPaint)
        }
    }
}
