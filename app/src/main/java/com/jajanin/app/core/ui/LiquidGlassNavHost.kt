package com.jajanin.app.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import com.example.liquidglass.BlurMethod
import com.example.liquidglass.GlassMaterial
import com.jajanin.app.JajaninView
import com.jajanin.app.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * Host dock Liquid Glass asli (QWEA0/Liquid-Glass-Android) di atas JajaninView.
 *
 * JajaninView tetap menjadi satu-satunya sumber konten; dock ditempatkan
 * sebagai sibling setelahnya di FrameLayout, dan [JajaninView] menunjuk balik
 * ke dirinya lewat [JajaninGlassTabBar.backdropSource]. Jadi lensa SDF benar-
 * benar mengambil piksel halaman di belakangnya, bukan blur palsu.
 *
 * Spesifikasi tab datang dari pemanggilan drawBottomNav/drawAdminNav yang
 * sudah ada, sehingga visibilitas, urutan, ikon, label, dan tujuan navigasi
 * tidak berubah.
 */
internal class LiquidGlassNavHost(
    private val context: Context,
    private val root: FrameLayout,
    private val content: JajaninView,
) {
    private val bar = JajaninGlassTabBar(context)
    private val badge = TextView(context)

    private val density = context.resources.displayMetrics.density

    private var bottomInset = 0
    private var lastModeKey: String? = null
    private var lastSelected = -1
    private var lastSpec: NavSpec? = null
    private var syncing = false
    private var barHeight = 0

    init {
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundColor(content.kit.bg)

        configureBar()
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        configureBadge()
        root.addView(
            badge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        bar.onTabSelected = { index ->
            if (!syncing) {
                val spec = lastSpec
                val item = spec?.items?.getOrNull(index)
                if (spec != null && item != null) {
                    content.selectNavTab(spec.prefix, item.label)
                }
            }
        }
        // Render ditunda satu frame: setTabs/selectedIndex memanggil
        // requestLayout dan tidak boleh dijalankan di tengah onDraw kanvas.
        content.navPresenter = { spec -> bar.post { render(spec) } }
        // Di bawah API 33 pipeline klasik jauh lebih mahal; jangan biarkan
        // glass menggambar terus-menerus saat layar diam. Kanvas menandai
        // setiap perubahan konten dan host hanya invalidate saat itu.
        // Backdrop Jajanin hanya berubah saat kanvas digambar ulang (scroll,
        // animasi, ganti layar), jadi render on-demand sudah cukup di semua API
        // dan menghindari shader lensa berjalan terus saat layar diam.
        content.navContentInvalidator = { bar.refreshBackdrop() }
        content.themeChangedListener = {
            root.setBackgroundColor(content.kit.bg)
            bar.selectedTintColor = brandSelectionTint()
            bar.refreshBackdrop()
        }

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutBar()
            positionBadge(lastSpec)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    private fun configureBar() {
        bar.apply {
            visibility = View.GONE
            // Dock sedikit lebih tinggi dari bar bawaan; padding bawah lebih
            // besar agar label tidak menempel ke gesture bar dan droplet tetap
            // di tengah. Setiap tab tetap memenuhi tinggi dock (target sentuh).
            setPadding(dp(4), dp(6), dp(4), dp(8))
            // Backdrop = halaman kanvas JajaninView yang berada tepat di
            // belakang dock. Ini mempertahankan GPU pipeline di API 31+.
            backdropSource = content
            // Backdrop hanya berubah saat kanvas menggambar ulang; host
            // memicu invalidate dari navContentInvalidator. Jadi jangan
            // menjalankan shader lensa terus-menerus saat layar diam.
            enableDynamicBackground = false
            material = GlassMaterial.REGULAR
            cornerRadius = GLASS_R * content.resources.displayMetrics.widthPixels / 390f
            adaptiveLensScale = true
            bevelWidth = dpF(16f)
            refractionHeight = dpF(12f)
            refractionFalloff = 2f
            edgeSoftness = dpF(0.75f)
            dispersionStrength = 0.12f
            enableChromaticDispersion = true
            enableChromaticAberration = false
            enableEdgeHighlight = true
            enableSensorHighlight = true
            enableAdaptiveTint = true
            // Nuansa biru Jajanin yang tipis; ikon terpilih memakai brand blue.
            glassTint = 0x240063D6
            selectedTintColor = brandSelectionTint()
            blurAmount = 0.045f
            saturation = 120f
            // Optimasi perangkat mid-range: turunkan resolusi backdrop dan
            // matikan statistik frame yang hanya berguna saat profiling.
            downsampleScale = 3
            collectFrameStats = false
            blurMethod = BlurMethod.SMART
            enablePressEffect = false
            contentDescription = "Navigasi utama"
        }
    }

    private fun configureBadge() {
        badge.apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 9f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(9f)
                setColor(Color.rgb(206, 60, 60))
            }
            isClickable = false
            isFocusable = false
            elevation = dpF(4f)
        }
    }

    /**
     * Menyusun ulang dock dari spesifikasi kanvas. Dipanggil hanya ketika
     * NavSpec berubah (layar/aktif/ikon/badge), bukan setiap frame.
     */
    private fun render(spec: NavSpec?) {
        lastSpec = spec
        if (spec == null) {
            bar.visibility = View.GONE
            badge.visibility = View.GONE
            lastModeKey = null
            lastSelected = -1
            return
        }

        val modeKey = spec.prefix + "|" + spec.items.joinToString("|") { it.label }
        if (modeKey != lastModeKey) {
            syncing = true
            bar.setTabs(spec.items.map { JajaninGlassTabBar.TabItem(it.label, iconFor(it.label)) })
            lastModeKey = modeKey
            lastSelected = -1
            syncing = false
        }
        if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE

        val selected = spec.items.indexOfFirst { it.label == spec.active }
            .let { if (it >= 0) it else 0 }
        if (selected != lastSelected) {
            syncing = true
            bar.selectedIndex = selected
            lastSelected = selected
            syncing = false
        }

        renderBadge(spec)
    }

    private fun renderBadge(spec: NavSpec) {
        val count = spec.badgeCount
        if (spec.badgeIndex !in spec.items.indices || count <= 0) {
            badge.visibility = View.GONE
            return
        }
        badge.text = if (count > 9) "9+" else count.toString()
        badge.visibility = View.VISIBLE
        positionBadge(spec)
    }

    private fun positionBadge(spec: NavSpec?) {
        if (spec == null || badge.visibility != View.VISIBLE) return
        if (root.width <= 0 || root.height <= 0) return
        val scale = root.width / 390f
        val barLeft = GLASS_MARGIN * scale
        val barRight = root.width - GLASS_MARGIN * scale
        val slot = (barRight - barLeft) / spec.items.size
        val centerX = barLeft + spec.badgeIndex * slot + slot / 2f
        val barTop = root.height - root.paddingBottom - GLASS_TOP_OFFSET * scale

        val lp = badge.layoutParams as FrameLayout.LayoutParams
        val width = max(dp(20), ceil(badge.paint.measureText(badge.text.toString())).toInt() + dp(10))
        val height = dp(18)
        if (lp.width != width || lp.height != height) {
            lp.width = width
            lp.height = height
            badge.layoutParams = lp
        }
        badge.translationX = centerX + slot * 0.16f - width / 2f
        badge.translationY = barTop - height / 2f
    }

    private fun layoutBar() {
        if (root.width <= 0) return
        val scale = root.width / 390f
        val height = (GLASS_H * scale).toInt().coerceAtLeast(dp(56))
        if (height != barHeight) {
            barHeight = height
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            lp.height = height
            bar.layoutParams = lp
        }
        bar.cornerRadius = GLASS_R * scale
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val side = (GLASS_MARGIN * scale).toInt()
        val bottom = (GLASS_BOTTOM * scale).toInt()
        if (lp.leftMargin != side || lp.rightMargin != side || lp.bottomMargin != bottom) {
            lp.leftMargin = side
            lp.rightMargin = side
            lp.bottomMargin = bottom
            bar.layoutParams = lp
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (bottom == bottomInset) return
        bottomInset = bottom
        // Padding root menyusutkan JajaninView tepat sebesar inset sistem,
        // sehingga dock dan ruang konten tetap sejajar di atas gesture bar.
        root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
        layoutBar()
        positionBadge(lastSpec)
    }

    private fun brandSelectionTint(): Int =
        if (content.darkMode) 0xFF5096FF.toInt() else 0xFF0063D6.toInt()

    private fun iconFor(label: String): Drawable? {
        val res = when (label) {
            "Home" -> R.drawable.ic_nav_home
            "Explore" -> R.drawable.ic_nav_explore
            "Orders" -> R.drawable.ic_nav_orders
            "Pesanan" -> R.drawable.ic_nav_receipt
            "Profile", "Profil" -> R.drawable.ic_nav_profile
            "Dashboard" -> R.drawable.ic_nav_grid
            "Produk" -> R.drawable.ic_nav_box
            "Notifikasi" -> R.drawable.ic_nav_bell
            else -> return null
        }
        return context.getDrawable(res)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun dpF(value: Float): Float = value * density
}
