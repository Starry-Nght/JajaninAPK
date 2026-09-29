package com.jajanin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

internal enum class Screen { WELCOME, ROLE, LOGIN, HOME, DETAIL, CHECKOUT, QRIS, READY, ORDERS, PROFILE }

internal data class MenuItem(val name: String, val stall: String, val price: String, val rating: String, val image: Int, val category: String) {
    val priceInRupiah: Int
        get() = price.filter(Char::isDigit).toIntOrNull() ?: 0
}

internal const val SERVICE_FEE = 1_000

internal fun formatRupiah(amount: Int): String =
    "Rp " + amount.toString().reversed().chunked(3).joinToString(".").reversed()

internal class JajaninView(context: Context) : View(context) {

    internal val kit = DrawKit(resources) { width / 390f }

    // Insertion-ordered so overlapping hit areas resolve exactly as they draw.
    internal val bounds = LinkedHashMap<String, RectF>()

    internal val items = listOf(
        MenuItem("Ayam Geprek Crispy", "Kantin Kuning · Stan Bu Ani", "Rp 18.000", "4.8", R.drawable.home, "Makanan"),
        MenuItem("Nasi Rames Spesial", "Kantin Kuning · Warung Bu Siti", "Rp 25.000", "4.8", R.drawable.detail, "Makanan"),
        MenuItem("Es Jeruk Peras Segar", "Kantin Biru · Bar Minuman", "Rp 8.000", "4.9", R.drawable.home, "Minuman"),
        MenuItem("Pisang Bakar Coklat Keju", "Snack Corner · Stan 04", "Rp 12.000", "4.7", R.drawable.detail, "Camilan")
    )

    internal var selected = items[0]
    internal var quantity = 1
    internal var query = ""
    internal var activeCategory = "Semua"
    internal var scroll = 0f
    internal var contentHeight = 0f

    private var screen = Screen.WELCOME
    private val history = ArrayDeque<Screen>()
    private var tab = "Home"
    private var touchStartY = 0f
    private var previousY = 0f
    private var moved = false
    private var searchFocused = false

    internal fun go(next: Screen) {
        if (screen == next) return
        history.addLast(screen)
        screen = next
        scroll = 0f
        invalidate()
    }

    internal fun goBack(): Boolean {
        if (history.isEmpty()) return false
        screen = history.removeLast()
        scroll = 0f
        invalidate()
        return true
    }

    fun showHome() { screen = Screen.HOME; history.clear(); invalidate() }

    fun showLogin() { screen = Screen.LOGIN; history.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 390f
        canvas.save()
        canvas.scale(s, s)
        bounds.clear()
        when (screen) {
            Screen.WELCOME -> drawWelcome(canvas, 390f, height / s)
            Screen.ROLE -> drawRole(canvas, 390f, height / s)
            Screen.LOGIN -> drawLogin(canvas, 390f, height / s)
            Screen.HOME -> drawHome(canvas, 390f, height / s)
            Screen.DETAIL -> drawDetail(canvas, 390f, height / s)
            Screen.CHECKOUT -> drawCheckout(canvas, 390f, height / s)
            Screen.QRIS -> drawPayment(canvas, 390f, height / s)
            Screen.READY -> drawReady(canvas, 390f, height / s)
            Screen.ORDERS -> drawOrders(canvas, 390f, height / s)
            Screen.PROFILE -> drawProfile(canvas, 390f, height / s)
        }
        canvas.restore()
    }

    internal fun header(c: Canvas, title: String) {
        kit.rect(c, 0f, 0f, 390f, 72f, Color.WHITE)
        kit.text(c, "‹", 20f, 46f, 27f, kit.blue, true)
        kit.text(c, title, 60f, 45f, 19f, kit.ink, true)
        bounds["headerBack"] = RectF(0f, 0f, 54f, 72f)
    }

    internal fun drawBottomNav(c: Canvas, w: Float, h: Float, active: String) {
        val y = h - 67f
        kit.rect(c, 0f, y, w, h, Color.WHITE)
        kit.line(c, 0f, y, w, y)
        val labels = listOf("Home", "Search", "Orders", "Profile")
        val icons = listOf("⌂", "⌕", "▤", "♙")
        labels.forEachIndexed { i, label ->
            val cx = 48f + i * 98f
            val color = if (label == active) kit.blue else kit.muted
            kit.centerText(c, icons[i], RectF(cx - 24f, y + 4f, cx + 24f, y + 37f), 22f, color, true)
            kit.centerText(c, label, RectF(cx - 39f, y + 37f, cx + 39f, y + 61f), 10f, color, label == active)
            bounds["tab:$label"] = RectF(cx - 48f, y, cx + 48f, h)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = width / 390f
        val x = event.x / s
        val y = event.y / s
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { touchStartY = y; previousY = y; moved = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (screen == Screen.HOME && abs(y - touchStartY) > 6f) {
                    moved = true
                    val maxScroll = (contentHeight - height / s + 78f).coerceAtLeast(0f)
                    scroll = (scroll + previousY - y).coerceIn(0f, maxScroll)
                    previousY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) return true
                val hit = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
                when {
                    hit == "welcome" -> go(Screen.ROLE)
                    hit == "role" || hit == "buyer" || hit == "seller" -> go(Screen.LOGIN)
                    hit == "emailField" -> activity()?.showAuthForm(false)
                    hit == "passwordField" -> activity()?.showAuthForm(true)
                    hit == "emailLogin" -> activity()?.showAuthForm(false)
                    hit == "toggleAuth" -> activity()?.showAuthForm(false, signup = true)
                    hit == "search" -> searchFocused = true
                    hit.startsWith("cat:") -> { activeCategory = hit.removePrefix("cat:"); invalidate() }
                    hit.startsWith("item") -> {
                        val i = hit.removePrefix("item").toIntOrNull() ?: 0
                        selected = items[i]; quantity = 1; go(Screen.DETAIL)
                    }
                    hit.startsWith("tab:") -> {
                        when (hit.removePrefix("tab:")) {
                            "Home" -> { tab = "Home"; if (screen != Screen.HOME) go(Screen.HOME) }
                            "Search" -> { tab = "Search"; screen = Screen.HOME; searchFocused = true; invalidate() }
                            "Orders" -> { tab = "Orders"; go(Screen.ORDERS) }
                            "Profile" -> { tab = "Profile"; go(Screen.PROFILE) }
                        }
                    }
                    hit == "logout" -> activity()?.logout()
                    hit == "back" || hit == "headerBack" -> goBack()
                    hit == "plus" -> { quantity++; invalidate() }
                    hit == "minus" -> { quantity = (quantity - 1).coerceAtLeast(1); invalidate() }
                    hit == "checkout" -> go(Screen.CHECKOUT)
                    hit == "pay" -> go(Screen.QRIS)
                    hit == "paid" -> go(Screen.READY)
                    hit == "orders" -> go(Screen.ORDERS)
                    hit.startsWith("stall") -> { selected = items[0]; go(Screen.DETAIL) }
                }
                return true
            }
        }
        return true
    }

    private fun activity() = context as? MainActivity
}
