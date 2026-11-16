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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class JajaninGlassTabBar(context: Context) : LiquidGlassView(context) {

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

    private val tabsRow = LinearLayout(context)
    private val tabs = mutableListOf<TabHolder>()

    private val droplet = LensDropletView(context)

    private var overLightAppearance = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    private var settleAnimator: ValueAnimator? = null

    // Multi-layer wet-glass specular reflection & bevel rim paints
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

        val pad = dp(4)
        setPadding(pad, pad, pad, pad)

        // Pure crystal optical lens droplet (zero blur, full-resolution refraction & dispersion)
        droplet.apply {
            enablePressEffect = false
            enableBackdropBlur = false
            material = GlassMaterial.CLEAR
            cornerRadius = 999f
            adaptiveLensScale = true
            bevelWidth = dpF(22f)
            refractionHeight = dpF(28f)
            refractionFalloff = 1.5f
            edgeSoftness = dpF(0.5f)
            dispersionStrength = 0.28f
            enableChromaticDispersion = true
            enableEdgeHighlight = true
            edgeHighlightOpacity = 165f
            enableSensorHighlight = true
            enableAdaptiveTint = false
            glassTint = 0x0E0066FF
            saturation = 148f
            downsampleScale = 1
            collectFrameStats = false
            visibility = GONE
        }
        addView(droplet, LayoutParams(0, 0, Gravity.TOP or Gravity.START))

        tabsRow.orientation = LinearLayout.HORIZONTAL
        addView(tabsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        overLightAppearance = isOverLightBackground
        droplet.overLight = overLightAppearance
    }

    /** Bind backdrop source to both the outer dock and the inner sliding optical lens. */
    fun setLensBackdropSource(source: View) {
        backdropSource = source
        droplet.backdropSource = source
    }

    fun refreshBackdrop() {
        invalidate()
        droplet.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBarGeometry(w, h)
    }

    private fun rebuildBarGeometry(w: Int, h: Int) {
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

        // 1. Crisp top wet-glass specular band (overhead light reflection)
        topGlossPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h * 0.38f,
            intArrayOf(0x6EFFFFFF, 0x24E0F2FE, 0x00FFFFFF),
            floatArrayOf(0f, 0.36f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. Bottom internal caustic rebound along the lower bevel
        bottomCausticPaint.shader = LinearGradient(
            0f,
            h * 0.72f,
            0f,
            h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x1E38BDF8, 0x42FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. Sharp diagonal crystal reflection streaks across the bar
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

        // 4. Outer high-contrast specular rim
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

        // 5. Inner 3D glass thickness bevel line
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

    fun setTabs(items: List<TabItem>) {
        tabsRow.removeAllViews()
        tabs.clear()

        items.forEach { item ->
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
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

    override fun onAppearanceChanged(isOverLight: Boolean) {
        overLightAppearance = isOverLight
        droplet.overLight = isOverLight
        updateTabStyles()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (settleAnimator?.isRunning != true && !dragging) syncDroplet()
    }

    private fun syncDroplet() {
        val tab = tabs.getOrNull(selected)?.root
        if (tab == null || tab.width <= 0) {
            droplet.visibility = GONE
            return
        }
        droplet.visibility = VISIBLE
        syncDropletSize(tab.width, tab.height)
        droplet.translationX = (tabsRow.left + tab.left - paddingLeft).toFloat()
        droplet.translationY = (tabsRow.top + tab.top - paddingTop).toFloat()
        droplet.scaleX = 1f
        droplet.scaleY = 1f
        droplet.setLensActive(false)
        droplet.invalidate()
    }

    private fun syncDropletSize(w: Int, h: Int) {
        val lp = droplet.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            droplet.layoutParams = lp
        }
    }

    private fun animateDropletTo(index: Int) {
        val tab = tabs.getOrNull(index)?.root ?: return
        if (tab.width <= 0) return
        syncDropletSize(tab.width, tab.height)

        settleAnimator?.cancel()
        val startX = droplet.translationX
        val targetX = (tabsRow.left + tab.left - paddingLeft).toFloat()
        val targetY = (tabsRow.top + tab.top - paddingTop).toFloat()
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
                val sx = 1f + stretch * envelope
                droplet.scaleX = sx
                droplet.scaleY = 1f - stretch * 0.46f * envelope
                droplet.translationX = clampDropletX(startX + dist * springValue(t), sx)
                droplet.translationY = targetY
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
        val rowW = tabsRow.width.toFloat()
        val w = droplet.width.toFloat()
        if (rowW <= 0f || w <= 0f) return x
        val bulge = (scaleX - 1f) * w / 2f
        val minX = bulge
        val maxX = rowW - w - bulge
        return if (minX <= maxX) x.coerceIn(minX, maxX) else (rowW - w) / 2f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                droplet.setLensActive(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    settleAnimator?.cancel()
                    droplet.scaleX = 1.09f
                    droplet.scaleY = 1.07f
                    droplet.setLensActive(true)
                }
                if (dragging) dragDropletTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    selectTab(nearestTabIndex(), animate = true)
                } else {
                    selectTab(tabIndexAt(event.x), animate = true)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animateDropletTo(selected)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dragDropletTo(x: Float) {
        if (tabsRow.width <= 0 || droplet.width <= 0) return
        val local = x - paddingLeft - droplet.width / 2f
        droplet.translationX = clampDropletX(local, droplet.scaleX)
        droplet.invalidate()
    }

    private fun nearestTabIndex(): Int {
        val centerX = droplet.translationX + droplet.width / 2f
        var best = selected
        var bestDist = Float.MAX_VALUE
        tabs.forEachIndexed { index, tab ->
            val tabCenter = tab.root.left + tab.root.width / 2f
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
 * Crystal water-droplet optical lens with sharp top crescent reflection,
 * lower chromatic caustic glow, and 3D double-bevel rim.
 */
private class LensDropletView(context: Context) : LiquidGlassView(context) {

    private val crescentGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    init {
        setWillNotDraw(false)
    }

    fun setLensActive(active: Boolean) {
        val d = resources.displayMetrics.density
        refractionHeight = (if (active) 36f else 28f) * d
        bevelWidth = (if (active) 26f else 22f) * d
        dispersionStrength = if (active) 0.34f else 0.28f
        edgeHighlightOpacity = if (active) 180f else 165f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLensShaders(w, h)
    }

    private fun rebuildLensShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val inset = 0.75f * d
        val r = min(w, h) / 2f
        pillRect.set(inset, inset, w - inset, h - inset)
        pillPath.reset()
        pillPath.addRoundRect(pillRect, r, r, Path.Direction.CW)

        val innerInset = 2.2f * d
        val innerR = (r - innerInset).coerceAtLeast(0f)
        innerPillRect.set(innerInset, innerInset, w - innerInset, h - innerInset)
        innerPillPath.reset()
        innerPillPath.addRoundRect(innerPillRect, innerR, innerR, Path.Direction.CW)

        // Top specular crescent cap (like light reflecting off a curved glass bead)
        val capRect = RectF(w * 0.12f, 2f * d, w * 0.88f, h * 0.42f)
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

        // Bottom electric-blue / cyan optical caustic focus inside the lens
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
        canvas.drawPath(crescentPath, crescentGlossPaint)
        canvas.restoreToCount(save)

        canvas.drawPath(innerPillPath, innerLensBevelPaint)
        canvas.drawPath(pillPath, outerLensRimPaint)
    }
}
