package com.jajanin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

internal enum class Screen { WELCOME, ROLE, LOGIN, HOME, DETAIL, CHECKOUT, QRIS, READY, ORDERS, PROFILE }

internal data class MenuItem(val name: String, val stall: String, val price: String, val rating: String, val image: Int, val category: String) {
    val priceInRupiah: Int
        get() = price.filter(Char::isDigit).toIntOrNull() ?: 0
}

internal const val SERVICE_FEE = 1_000

internal val LOCATION_OPTIONS = listOf(
    "X PPLG 1", "X PPLG 2", "X PPLG 3",
    "XI PPLG 1", "XI PPLG 2", "XI PPLG 3",
    "XII PPLG 1", "XII PPLG 2", "XII PPLG 3",
)

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
    internal var location = LOCATION_OPTIONS.first()
    internal var scroll = 0f
    internal var contentHeight = 0f

    private var screen = Screen.WELCOME
    private val history = ArrayDeque<Screen>()
    private var tab = "Home"
    private var touchStartY = 0f
    private var previousY = 0f
    private var moved = false

    // Dikonsumsi drawBottomNav di BottomNav.kt: tab yang ditekan + waktu mulai animasi indikator.
    internal var pressedTab: String? = null
    internal var navAnimAt = 0L

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

    internal fun applyQuery(value: String) {
        query = value
        scroll = 0f
        invalidate()
    }

    internal fun applyLocation(value: String) {
        location = value
        invalidate()
    }

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = width / 390f
        val x = event.x / s
        val y = event.y / s
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = y; previousY = y; moved = false
                val tabHit = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key
                    ?.takeIf { it.startsWith("tab:") }
                if (tabHit != null) {
                    pressedTab = tabHit
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (screen == Screen.HOME && abs(y - touchStartY) > 6f) {
                    moved = true
                    if (pressedTab != null) {
                        pressedTab = null
                        invalidate()
                    }
                    val maxScroll = (contentHeight - height / s + 78f).coerceAtLeast(0f)
                    scroll = (scroll + previousY - y).coerceIn(0f, maxScroll)
                    previousY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedTab = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedTab != null) {
                    pressedTab = null
                    invalidate()
                }
                if (moved) return true
                val hit = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
                when {
                    hit == "welcome" -> go(Screen.ROLE)
                    hit == "role" || hit == "buyer" || hit == "seller" -> go(Screen.LOGIN)
                    hit == "emailField" -> activity()?.showAuthForm(false)
                    hit == "passwordField" -> activity()?.showAuthForm(true)
                    hit == "emailLogin" -> activity()?.showAuthForm(false)
                    hit == "toggleAuth" -> activity()?.showAuthForm(false, signup = true)
                    hit == "search" -> activity()?.showSearchForm()
                    hit == "location" -> activity()?.showLocationForm()
                    hit.startsWith("cat:") -> {
                        activeCategory = hit.removePrefix("cat:")
                        scroll = 0f
                        invalidate()
                    }
                    hit.startsWith("item") -> {
                        val i = hit.removePrefix("item").toIntOrNull() ?: 0
                        selected = items[i]; quantity = 1; go(Screen.DETAIL)
                    }
                    hit.startsWith("tab:") -> {
                        navAnimAt = SystemClock.uptimeMillis()
                        when (hit.removePrefix("tab:")) {
                            "Home" -> { tab = "Home"; if (screen != Screen.HOME) go(Screen.HOME) }
                            "Search" -> { tab = "Search"; screen = Screen.HOME; activity()?.showSearchForm() }
                            "Orders" -> { tab = "Orders"; go(Screen.ORDERS) }
                            "Profile" -> { tab = "Profile"; go(Screen.PROFILE) }
                        }
                        invalidate()
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
