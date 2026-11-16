package com.jajanin.app.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val HERO_ACTIVE = 0xFF66CCFF.toInt()
private val HERO_INACTIVE = 0xB8DCEBFF.toInt()
private val HERO_BADGE = 0xFFE5484D.toInt()
private val HERO_DOCK_TINT = 0x660B1B33
private val HERO_PLUS_TINT = 0x5A1FB4FF

internal class SellerHeroDock(context: Context) : FrameLayout(context) {

    class TabItem(val label: String, val icon: Drawable?)

    var onTabSelected: ((index: Int) -> Unit)? = null

    var selectedTintColor: Int = HERO_ACTIVE
        set(value) {
            if (field != value) {
                field = value
                updateTabStyles()
            }
        }

    var badgeCount: Int = 0
        set(value) {
            if (field != value) {
                field = value
                updateBadge()
                updateTabStyles()
            }
        }

    private var selected = 0

    var selectedIndex: Int
        get() = selected
        set(value) {
            val clamped = value.coerceIn(0, 4)
            if (clamped != selected) {
                selected = clamped
                updateTabStyles()
            }
        }

    private class Holder(
        val index: Int,
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    private val dock = HeroDockGlass(context)
    private val tabsRow = LinearLayout(context)
    private val holders = arrayOfNulls<Holder>(5)
    private val centerSpacer = View(context)
    private val plus = HeroPlusView(context)
    private val badge = TextView(context)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShader: RadialGradient? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val spring = SpringAnimator { value -> applyExpand(value) }
    private var expand = 0f
    private var applyPosted = false

    private var downX = 0f
    private var downY = 0f
    private var downTarget = -1
    private var downInPlus = false
    private var moved = false
    private var canceled = false
    private var held = false

    private val longPressRunnable = Runnable { beginHold() }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isClickable = false

        configureDock()
        addView(
            dock,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM),
        )

        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.gravity = Gravity.CENTER_VERTICAL
        dock.addView(
            tabsRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        configurePlus()
        addView(
            plus,
            LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )

        configureBadge()
        addView(
            badge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
    }

    fun setBackdropSource(source: View) {
        dock.backdropSource = source
        plus.backdropSource = source
    }

    fun refreshBackdrop() {
        dock.invalidate()
        plus.invalidate()
    }

    /**
     * True once the inner glass dock and at least one tab have real bounds.
     * The host uses this to fall back to the standard glass bar instead of
     * leaving the seller with no navigation if the hero never lays out.
     */
    fun isReady(): Boolean =
        width > 0 && height > 0 &&
            dock.width > 0 && dock.height > 0 &&
            (holders[0]?.root?.height ?: 0) > 0

    fun setTabs(items: List<TabItem>) {
        tabsRow.removeAllViews()
        holders.fill(null)

        val secondary = listOf(0, 1, 3, 4)
        secondary.forEach { index ->
            val item = items.getOrNull(index) ?: return@forEach
            val holder = createHolder(index, item)
            holders[index] = holder
            tabsRow.addView(
                holder.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            if (index == 1) {
                tabsRow.addView(
                    centerSpacer,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT),
                )
            }
        }

        selected = selected.coerceIn(0, 4)
        updateTabStyles()
        updateBadge()
        applyExpand(expand)
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleApply()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionBadge()
        scheduleApply()
    }

    private fun scheduleApply() {
        if (applyPosted) return
        applyPosted = true
        post {
            applyPosted = false
            applyExpand(expand)
            updateBadge()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shader = glowShader ?: return
        glowPaint.shader = shader
        glowPaint.alpha = (70 + 55 * expand).toInt().coerceIn(0, 255)
        canvas.drawCircle(
            width / 2f,
            plus.top + plus.height / 2f,
            plus.width * 0.92f,
            glowPaint,
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                canceled = false
                held = false
                downTarget = targetAt(event.x, event.y)
                downInPlus = downTarget == 2
                if (downInPlus) animatePlus(pressed = true, hold = false)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, HOLD_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelHold()
                canceled = true
                if (!held) animatePlus(pressed = false, hold = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!held && !canceled &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    moved = true
                    cancelHold()
                    if (downInPlus) animatePlus(pressed = false, hold = false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val wasHeld = held
                if (wasHeld) {
                    held = false
                    setExpanded(false)
                } else if (!moved && !canceled && event.pointerCount <= 1) {
                    downTarget.takeIf { it >= 0 }?.let { index ->
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        selectFromUser(index)
                    }
                }
                held = false
                downTarget = -1
                downInPlus = false
                animatePlus(pressed = false, hold = false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                if (held) {
                    held = false
                    setExpanded(false)
                }
                downTarget = -1
                downInPlus = false
                animatePlus(pressed = false, hold = false)
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        spring.cancel()
        plus.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun configureDock() {
        dock.apply {
            enablePressEffect = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            material = GlassMaterial.CLEAR
            enableBackdropBlur = true
            blurAmount = 0.035f
            saturation = 122f
            adaptiveLensScale = true
            bevelWidth = 34f
            refractionHeight = 20f
            refractionFalloff = 2f
            edgeSoftness = 2f
            dispersionStrength = 0.12f
            enableChromaticDispersion = true
            enableChromaticAberration = false
            enableEdgeHighlight = true
            edgeHighlightOpacity = 100f
            enableSensorHighlight = false
            enableAdaptiveTint = false
            glassTint = HERO_DOCK_TINT
            downsampleScale = 3
            collectFrameStats = false
            enableDynamicBackground = false
            overLight = false
            contentDescription = "Dock navigasi penjual"
        }
    }

    private fun configurePlus() {
        plus.apply {
            enablePressEffect = false
            material = GlassMaterial.CLEAR
            enableBackdropBlur = true
            blurAmount = 0.025f
            saturation = 136f
            adaptiveLensScale = true
            bevelWidth = 24f
            refractionHeight = 22f
            refractionFalloff = 2f
            edgeSoftness = 1.5f
            dispersionStrength = 0.17f
            enableChromaticDispersion = true
            enableChromaticAberration = false
            enableEdgeHighlight = true
            edgeHighlightOpacity = 122f
            enableSensorHighlight = false
            enableAdaptiveTint = false
            glassTint = HERO_PLUS_TINT
            downsampleScale = 2
            collectFrameStats = false
            enableDynamicBackground = false
            overLight = false
            isClickable = true
            isFocusable = true
            contentDescription = "Tambah produk"
            setOnClickListener { onTabSelected?.invoke(2) }
        }
    }

    private fun configureBadge() {
        badge.apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f
                setColor(HERO_BADGE)
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun createHolder(index: Int, item: TabItem): Holder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            setOnClickListener { selectFromUser(index) }
        }
        val icon = ImageView(context).apply {
            setImageDrawable(item.icon)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(icon, LinearLayout.LayoutParams(0, 0))
        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return Holder(index, root, icon, label)
    }

    private fun updateTabStyles() {
        holders.forEach { holder ->
            holder ?: return@forEach
            val active = holder.index == selected
            val color = if (active) selectedTintColor else HERO_INACTIVE
            holder.icon.imageTintList = ColorStateList.valueOf(color)
            holder.label.setTextColor(color)
            holder.root.isSelected = active
            val base = holder.label.text.toString()
            holder.root.contentDescription = when {
                holder.index == 3 && badgeCount > 0 ->
                    "$base, $badgeCount belum dibaca" + if (active) ", dipilih" else ""
                active -> "$base, dipilih"
                else -> base
            }
        }
        plus.ringBoost = if (selected == 2) 1f else 0f
    }

    private fun updateBadge() {
        val count = badgeCount
        if (count <= 0) {
            badge.visibility = View.GONE
            return
        }
        badge.text = if (count > 9) "9+" else count.toString()
        badge.visibility = View.VISIBLE
        badge.contentDescription = "$count notifikasi"
        val u = unit()
        val density = resources.displayMetrics.density
        val height = (18f * u).toInt().coerceAtLeast((16f * density).toInt())
        val textWidth = ceil(badge.paint.measureText(badge.text.toString())).toInt()
        val width = max(height, textWidth + (10f * u).toInt())
        val lp = badge.layoutParams as LayoutParams
        if (lp.width != width || lp.height != height) {
            lp.width = width
            lp.height = height
            badge.layoutParams = lp
        }
        post { positionBadge() }
    }

    private fun positionBadge() {
        if (badge.visibility != View.VISIBLE) return
        val holder = holders[3] ?: return
        if (holder.root.width <= 0 || badge.width <= 0) return
        val rect = Rect()
        offsetDescendantRectToMyCoords(holder.root, rect)
        val u = unit()
        badge.translationX = rect.centerX() + rect.width() * 0.18f - badge.width / 2f
        badge.translationY = rect.top - badge.height / 2f + 2f * u
    }

    private fun selectFromUser(index: Int) {
        val clamped = index.coerceIn(0, 4)
        if (clamped != selected) {
            selected = clamped
            updateTabStyles()
        }
        onTabSelected?.invoke(clamped)
    }

    private fun targetAt(x: Float, y: Float): Int {
        if (plus.visibility == View.VISIBLE && x >= plus.left && x <= plus.right &&
            y >= plus.top && y <= plus.bottom
        ) {
            return 2
        }
        val rect = Rect()
        holders.forEach { holder ->
            holder ?: return@forEach
            rect.set(0, 0, holder.root.width, holder.root.height)
            offsetDescendantRectToMyCoords(holder.root, rect)
            if (rect.contains(x.toInt(), y.toInt())) return holder.index
        }
        return -1
    }

    private fun beginHold() {
        if (moved || canceled || !isAttachedToWindow) return
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setExpanded(true)
        if (downInPlus) animatePlus(pressed = true, hold = true)
    }

    private fun cancelHold() {
        removeCallbacks(longPressRunnable)
    }

    private fun setExpanded(expanded: Boolean) {
        spring.animateTo(if (expanded) 1f else 0f)
        dock.applyOpticalState(expanded)
        plus.applyOpticalState(expanded)
        if (expanded) {
            plus.animate().scaleX(1.11f).scaleY(1.05f)
                .setDuration(360L)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }
    }

    private fun animatePlus(pressed: Boolean, hold: Boolean) {
        plus.animate().cancel()
        if (pressed) {
            plus.animate()
                .scaleX(if (hold) 1.11f else 1.05f)
                .scaleY(if (hold) 1.05f else 0.96f)
                .setDuration(if (hold) 360L else 170L)
                .setInterpolator(OvershootInterpolator(if (hold) 1.5f else 1.0f))
                .start()
        } else {
            plus.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        }
    }

    private fun applyExpand(value: Float) {
        expand = value
        val u = unit()
        val t = value.coerceIn(0f, 1.06f)

        val dockMargin = lerp(HERO_DOCK_MARGIN, HERO_DOCK_MARGIN_EXPANDED, t) * u
        val dockH = lerp(HERO_DOCK_H, HERO_DOCK_H_EXPANDED, t) * u
        val dockLp = dock.layoutParams as LayoutParams
        val dockLeft = dockMargin.toInt()
        val dockHeight = dockH.toInt()
        if (dockLp.leftMargin != dockLeft || dockLp.rightMargin != dockLeft ||
            dockLp.height != dockHeight
        ) {
            dockLp.leftMargin = dockLeft
            dockLp.rightMargin = dockLeft
            dockLp.height = dockHeight
            dock.layoutParams = dockLp
        }
        dock.updateShape(26f * u)

        val plusD = lerp(HERO_PLUS_D, HERO_PLUS_D_EXPANDED, t) * u
        val plusLp = plus.layoutParams as LayoutParams
        val plusSize = plusD.toInt()
        val plusTop = (2f * u).toInt()
        if (plusLp.width != plusSize || plusLp.height != plusSize || plusLp.topMargin != plusTop) {
            plusLp.width = plusSize
            plusLp.height = plusSize
            plusLp.topMargin = plusTop
            plus.layoutParams = plusLp
        }

        val centerLp = centerSpacer.layoutParams as? LinearLayout.LayoutParams
        if (centerLp != null) {
            val centerWidth = (plusD + 12f * u).toInt()
            if (centerLp.width != centerWidth) {
                centerLp.width = centerWidth
                centerSpacer.layoutParams = centerLp
            }
        }

        val padH = (7f * u).toInt()
        val padV = (3f * u).toInt()
        if (tabsRow.paddingLeft != padH || tabsRow.paddingTop != padV) {
            tabsRow.setPadding(padH, padV, padH, padV)
        }

        val itemScale = lerp(1f, 1.09f, t)
        val labelAlpha = lerp(0.74f, 1f, t)
        val iconSize = (22f * u).toInt().coerceAtLeast(1)
        holders.forEach { holder ->
            holder ?: return@forEach
            holder.root.scaleX = itemScale
            holder.root.scaleY = itemScale
            holder.label.alpha = labelAlpha
            val iconLp = holder.icon.layoutParams as LinearLayout.LayoutParams
            if (iconLp.width != iconSize || iconLp.height != iconSize) {
                iconLp.width = iconSize
                iconLp.height = iconSize
                holder.icon.layoutParams = iconLp
            }
            val labelLp = holder.label.layoutParams as LinearLayout.LayoutParams
            val labelGap = (2f * u).toInt()
            if (labelLp.topMargin != labelGap) {
                labelLp.topMargin = labelGap
                holder.label.layoutParams = labelLp
            }
        }

        val glowSize = plusD * 0.92f
        val cx = width / 2f
        val cy = plusTop + plusSize / 2f
        glowShader = RadialGradient(
            cx,
            cy,
            glowSize,
            intArrayOf(0x6633CCFF, 0x1F33CCFF, 0x0033CCFF),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        invalidate()
        dock.invalidate()
        plus.invalidate()
    }

    private fun unit(): Float =
        if (width > 0) width / 390f else resources.displayMetrics.density

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private companion object {
        const val HOLD_DELAY_MS = 380L
    }
}

private class HeroDockGlass(context: Context) : LiquidGlassView(context) {

    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shapePath = Path()
    private val rect = RectF()

    fun updateShape(radius: Float) {
        cornerRadius = radius
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        bevelWidth = 18f * d
        refractionHeight = 18f * d
        edgeSoftness = 1f * d
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.save()
        canvas.clipPath(shapePath)
        glossPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height * 0.58f,
            intArrayOf(0x3DFFFFFF, 0x13D9F3FF, 0x00000000),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.58f, glossPaint)
        glossPaint.shader = LinearGradient(
            0f,
            height * 0.58f,
            0f,
            height.toFloat(),
            intArrayOf(0x00000000, 0x2E020B18),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height * 0.58f, width.toFloat(), height.toFloat(), glossPaint)
        canvas.restore()

        rimPaint.strokeWidth = (0.8f * resources.displayMetrics.density).coerceAtLeast(1f)
        rimPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xCCFFFFFF.toInt(), 0x338DDFFF, 0x1AFFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(shapePath, rimPaint)
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        rect.set(0.75f, 0.75f, width - 0.75f, height - 0.75f)
        shapePath.reset()
        shapePath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    fun applyOpticalState(expanded: Boolean) {
        val d = resources.displayMetrics.density
        dispersionStrength = if (expanded) 0.2f else 0.12f
        refractionHeight = (if (expanded) 26f else 18f) * d
        edgeHighlightOpacity = if (expanded) 128f else 100f
        invalidate()
    }
}

private class HeroPlusView(context: Context) : LiquidGlassView(context) {

    var ringBoost: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plusGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x5533CCFF
    }
    private val plusCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF3FCFF.toInt()
    }
    private var ringShader: Shader? = null
    private var highlightShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val short = min(w, h).toFloat()
        bevelWidth = short * 0.2f
        refractionHeight = short * 0.18f
        edgeSoftness = 0.75f * d
        val cx = w / 2f
        val cy = h / 2f
        val r = short / 2f
        ringShader = LinearGradient(
            cx - r,
            cy - r,
            cx + r,
            cy + r,
            intArrayOf(0xF2FFFFFF.toInt(), 0x995BE7FF.toInt(), 0x2E5BC8FF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        highlightShader = RadialGradient(
            cx - r * 0.3f,
            cy - r * 0.36f,
            r * 0.92f,
            intArrayOf(0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        highlightPaint.shader = highlightShader
        highlightPaint.alpha = 170
        canvas.drawCircle(cx, cy, r - r * 0.05f, highlightPaint)

        ringPaint.strokeWidth = (r * 0.055f).coerceAtLeast(1f)
        ringPaint.shader = ringShader
        ringPaint.alpha = (168 + 72 * ringBoost.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth * 0.6f, ringPaint)

        val arm = r * 0.43f
        plusGlow.strokeWidth = r * 0.23f
        plusGlow.alpha = (96 + 54 * ringBoost.coerceIn(0f, 1f)).toInt()
        plusCore.strokeWidth = r * 0.125f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, plusGlow)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, plusGlow)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, plusCore)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, plusCore)
    }

    fun applyOpticalState(expanded: Boolean) {
        dispersionStrength = if (expanded) 0.24f else 0.17f
        edgeHighlightOpacity = if (expanded) 138f else 122f
        invalidate()
    }
}

private class SpringAnimator(private val onChange: (Float) -> Unit) {

    private var value = 0f
    private var velocity = 0f
    private var target = 0f
    private var lastFrameNs = 0L
    private var running = false

    private val callback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val last = lastFrameNs
            if (last == 0L) {
                lastFrameNs = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dt = ((frameTimeNanos - last) / 1_000_000_000f).coerceIn(0f, 0.04f)
            lastFrameNs = frameTimeNanos

            val stiffness = 240f
            val damping = 2f * sqrt(stiffness) * 0.84f
            val accel = stiffness * (target - value) - damping * velocity
            velocity += accel * dt
            value += velocity * dt

            if (abs(target - value) < 0.001f && abs(velocity) < 0.01f) {
                value = target
                velocity = 0f
                running = false
                lastFrameNs = 0L
                onChange(value)
            } else {
                onChange(value)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun animateTo(newTarget: Float) {
        target = newTarget
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun cancel() {
        running = false
        lastFrameNs = 0L
        velocity = 0f
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
