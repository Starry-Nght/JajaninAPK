package com.jajanin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

internal enum class Screen { SPLASH, ONBOARD, WELCOME, ROLE, LOGIN, HOME, EXPLORE, DETAIL, CHECKOUT, QRIS, READY, ORDERS, PROFILE, EXP_HISTORY, NOTIF, ADMIN, ADMIN_DETAIL, PRODUCT_FORM, HELP, ABOUT, INFO, DEV }

internal const val STATUS_PENDING = "pending"
internal const val STATUS_CONFIRMED = "confirmed"
internal const val STATUS_PREPARING = "preparing"
internal const val STATUS_READY = "ready"
internal const val STATUS_COMPLETED = "completed"
internal const val STATUS_CANCELLED = "cancelled"

// Legacy status kept readable for old orders written before the confirmed step existed.
internal const val STATUS_PROCESSING_LEGACY = "processing"

/** Label Indonesia yang rapi untuk semua status order baru maupun lama. */
internal fun statusLabelShort(status: String): String = when (status.lowercase()) {
    STATUS_PENDING -> "Pending"
    STATUS_CONFIRMED -> "Confirmed"
    STATUS_PROCESSING_LEGACY -> "Preparing"
    STATUS_PREPARING -> "Preparing"
    STATUS_READY -> "Ready"
    STATUS_COMPLETED -> "Completed"
    STATUS_CANCELLED -> "Cancelled"
    else -> status
}

/** Urutan tampilan kanban order: terbaru, masih aktif, lalu selesai. */
internal fun statusSortOrder(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> 0
    STATUS_CONFIRMED -> 1
    STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
    STATUS_READY -> 3
    STATUS_COMPLETED -> 4
    STATUS_CANCELLED -> 5
    else -> 6
}

internal data class MenuItem(val name: String, val stall: String, val price: String, val rating: String, val image: Int, val category: String) {
    val priceInRupiah: Int
        get() = price.filter(Char::isDigit).toIntOrNull() ?: 0
}

@kotlinx.serialization.Serializable
internal data class ProductRow(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String,
    @kotlinx.serialization.SerialName("price_idr") val priceIdr: Int,
    @kotlinx.serialization.SerialName("image_url") val imageUrl: String? = null,
    @kotlinx.serialization.SerialName("is_available") val available: Boolean = true,
    @kotlinx.serialization.SerialName("stock") val stock: Int? = null,
    @kotlinx.serialization.SerialName("stall_id") val stallId: String,
    @kotlinx.serialization.SerialName("ingredients") val ingredients: String = "",
)

internal const val SERVICE_FEE = 1_000

/** Durasi transisi geser antar halaman (ms). */
private const val PAGE_SLIDE_MS = 240L

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

    internal var products: List<ProductRow> = emptyList()
    internal var sellerProducts: List<ProductRow> = emptyList()
    internal var selectedProduct: ProductRow? = null
    internal val selected: MenuItem
        get() = selectedProduct?.let { MenuItem(it.name, "Kantin Jajanin", formatRupiah(it.priceIdr), "-", 0, it.category) }
            ?: MenuItem("", "", "Rp 0", "-", 0, "")
    internal val items: List<MenuItem> get() = emptyList()
    internal var quantity = 1
    internal var query = ""
    internal var activeCategory = "All"
    internal var location = LOCATION_OPTIONS.first()
    internal var scroll = 0f
    internal var contentHeight = 0f
    internal var adminOrders: List<AdminOrder> = emptyList()
    internal var adminLoading = false
    internal var adminError: String? = null
    internal var adminFilter = "Semua"
    internal var adminDetailOrder: AdminOrder? = null
    internal var adminDetailId: String? = null
    internal var adminDetailLoading = false
    internal var adminDetailError: String? = null
    internal var adminNotifOpen = false
    // Key sentuh yang sedang ditekan; dipakai OrderScreens untuk highlight press.
    internal var pressedKey: String? = null
    // Tab "Pesanan" minta dashboard melompat ke daftar pesanan saat frame berikutnya.
    internal var pendingScrollToOrders = false
    internal var selectedRole = "buyer"
    internal var productsLoading = false
    internal var productsError: String? = null
    internal var scrollDummy = 0
    internal var productDescription = ""
    internal var productCategory = "Food"
    internal var productPrice = ""
    internal var productName = ""
    internal var productImageUrl = ""
    internal var productImageUploading = false
    internal var productStock = ""
    internal var productIngredients = ""
    internal var productAvailable = true
    internal var productEditId: String? = null
    // Status form produk (halaman): loading simpan + error inline.
    internal var productSaving = false
    internal var productError = ""
    internal var myOrders: List<AdminOrder> = emptyList()
    internal var myOrdersLoading = false
    internal var myOrdersError: String? = null
    internal var myObserver: OrderObserver? = null
    internal var myRole: String? = null
    internal var myDisplayName: String = ""
    internal var savedProductId: String? = null
    internal var isSellerProfile = false
    // --- Buyer checkout state (multi-item cart, satu warung per order) ---
    internal var cartStallId: String? = null
    internal var cartStallName: String = "Kantin Jajanin"
    // QRIS warung isi keranjang: null = belum dicek (tampilkan semua),
    // "" = warung belum pasang (tunai saja), berisi URL = QRIS tersedia.
    internal var cartStallQris: String? = null
    internal var checkoutPayment: String = "cash"
    internal var placingOrder = false
    internal var lastOrderId: String? = null
    internal var lastOrderPickup: String? = null
    internal var lastOrderTotal: Int = 0
    internal var lastOrderMethod: String = "cash"
    internal var lastOrderStatus: String = "pending"
    internal var qrisOrderId: String? = null
    internal var qrisAmount: Int = 0
    internal var qrisImageUrl: String? = null
    internal var qrisLoading = false
    // --- Seller settings state ---
    internal var myStallId: String? = null
    internal var myStallName: String = ""
    internal var myStallQris: String = ""
    internal var cart = linkedMapOf<String, Int>()
    // --- Developer dashboard state ---
    internal var devTab = "Overview"
    internal var devStats = DevStats()
    internal var devStatsLoading = false
    internal var devUsers: List<DevUser> = emptyList()
    internal var devUsersLoading = false
    internal var devUserFilter = "Semua"
    internal var devQuery = ""
    internal var devSelectedUserId: String? = null
    internal var devOrders: List<AdminOrder> = emptyList()
    internal var devOrdersLoading = false
    internal var devExpandedOrderId: String? = null
    internal var devWarnings: List<DevWarningRow> = emptyList()
    internal var devWarningsLoading = false
    internal var devAnnouncements: List<DevAnnouncementRow> = emptyList()
    internal var devAnnLoading = false
    internal var devError: String? = null
    internal var devResetting = false
    // --- Buyer/seller inbox (developer warnings + announcements + seller invites) ---
    internal var myWarnings: List<DevWarningRow> = emptyList()
    internal var myAnnouncements: List<DevAnnouncementRow> = emptyList()
    internal var myInvites: List<SellerInvite> = emptyList()
    internal var devInvites: List<SellerInvite> = emptyList()
    internal var inboxObserver: OrderObserver? = null
    // --- Nomor HP sendiri (wajib sebelum memesan) ---
    internal var myPhone: String = ""
    // --- Level & EXP pembeli (saldo dari server, level dihitung lokal) ---
    internal var myExp = 0
    internal var myExpLoading = false
    internal var lastExpBalance = -1
    internal var lastExpLevel = 0
    internal var expPulseAt = 0L
    internal var expHistory: List<ExpEntry> = emptyList()
    internal var expHistoryLoading = false
    internal var expHistoryError: String? = null
    internal var expHistoryLimit = 20
    internal var expHistoryEnd = false
    // --- Onboarding (tutorial pertama kali) ---
    internal var onboardPage = 0
    internal val onboardCount = 4
    // --- Status akun sendiri (banner penangguhan) ---
    internal var myAccountStatus = "active"
    // --- Ordering restrictions (mirror server rules; enforcement is in place_order) ---
    internal var orderingBlocked = false
    internal var orderingBlockMessage: String? = null
    internal var ordersToday = 0
    internal var orderingStatusLoading = false
    // --- Antrean warung (maksimal 10 order aktif; enforcement di place_order) ---
    internal var stallActiveOrders = -1
    internal var stallFull = false
    internal var stallLoading = false
    // --- Live server clock (WIB): offset dihitung dari ordering_status, lalu tick lokal ---
    internal var serverBaseMillis = 0L
    internal var serverBaseElapsed = 0L

    /** Jam server WIB saat ini (ms); 0 bila belum pernah sinkron. */
    internal fun serverNowMillis(): Long =
        if (serverBaseMillis == 0L) 0L
        else serverBaseMillis + (android.os.SystemClock.elapsedRealtime() - serverBaseElapsed)

    /** Label jam live "HH:mm:ss WIB" dari waktu server (bukan jam perangkat). */
    internal fun serverClockLabel(): String {
        val now = serverNowMillis()
        if (now == 0L) return "--:--:--"
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }
        return f.format(java.util.Date(now)) + " WIB"
    }

    private var screen = Screen.SPLASH
    private val history = ArrayDeque<Screen>()
    private var tab = "Home"
    // Posisi scroll per halaman tab agar tidak reset saat pindah tab.
    private val pageScroll = HashMap<String, Float>()
    private var touchStartY = 0f
    private var previousY = 0f
    private var moved = false

    // Dikonsumsi drawBottomNav di BottomNav.kt: tab yang ditekan + waktu mulai animasi indikator.
    internal var pressedTab: String? = null
    internal var navAnimAt = 0L
    // Tema aplikasi (dibaca dari SharedPreferences saat MainActivity.onCreate).
    internal var darkMode = false
    // Label mode tema aktif untuk baris Profil ("Terang"/"Gelap"/"Sistem").
    internal var themeLabel = "Terang"
    // Offset geser halaman saat ini (untuk chrome yang stabil: header/nav
    // menggambar dengan counter-translate sehingga tetap diam).
    internal var slideX = 0f
    // Transisi geser antar halaman: arah (+1 dari kanan, -1 dari kiri, 0 mati)
    // + waktu mulai. Digambar di onDraw selama ~240ms, lalu berhenti sendiri.
    private var pageSlideDir = 0f
    private var pageSlideAt = 0L

    /** Hormati preferensi reduced-motion Android: matikan animasi bila skala 0. */
    internal fun motionEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) != 0f
    }.getOrDefault(true)

    /** Mulai transisi geser halaman ke arah [dir]. */
    internal fun beginSlide(dir: Float) {
        if (dir == 0f || !motionEnabled()) {
            pageSlideDir = 0f
            return
        }
        pageSlideDir = dir
        pageSlideAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Ganti tema terang/gelap; berlaku untuk buyer, seller, dan developer. */
    fun setDarkMode(dark: Boolean) {
        darkMode = dark
        kit.applyTheme(dark)
        invalidate()
    }

    internal fun go(next: Screen) {
        if (screen == next) return
        // Panel notifikasi hanya hidup di dashboard; kalau tidak dibersihkan,
        // scroll di layar lain ikut terkunci (gerakan gulir mengecek flag ini).
        if (next != Screen.ADMIN) adminNotifOpen = false
        history.addLast(screen)
        screen = next
        scroll = 0f
        beginSlide(1f)
        invalidate()
    }

    internal fun goBack(): Boolean {
        if (history.isEmpty()) return false
        val target = history.removeLast()
        if (target != Screen.ADMIN) adminNotifOpen = false
        screen = target
        scroll = 0f
        beginSlide(-1f)
        invalidate()
        return true
    }

    internal val currentScreen: Screen get() = screen

    fun showHome() { screen = Screen.HOME; history.clear(); invalidate() }

    fun showSplash() { screen = Screen.SPLASH; history.clear(); invalidate() }

    fun showOnboard() { screen = Screen.ONBOARD; history.clear(); onboardPage = 0; scroll = 0f; invalidate() }

    fun showWelcome() { screen = Screen.WELCOME; history.clear(); scroll = 0f; invalidate() }

    fun showAdmin() { screen = Screen.ADMIN; history.clear(); invalidate() }

    fun showDev() { screen = Screen.DEV; history.clear(); devTab = "Overview"; scroll = 0f; invalidate() }

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
        // Geser halaman masuk (decelerate, ~240ms). Hit area dihitung tanpa
        // offset sehingga tap selama animasi singkat tetap akurat.
        if (pageSlideDir != 0f) {
            val elapsed = android.os.SystemClock.uptimeMillis() - pageSlideAt
            if (elapsed >= PAGE_SLIDE_MS) {
                pageSlideDir = 0f
                slideX = 0f
            } else {
                val t = elapsed / PAGE_SLIDE_MS.toFloat()
                val eased = 1f - (1f - t) * (1f - t)
                slideX = (1f - eased) * pageSlideDir * 56f
                canvas.translate(slideX, 0f)
                postInvalidateOnAnimation()
            }
        } else {
            slideX = 0f
        }
        bounds.clear()
        when (screen) {
            Screen.SPLASH -> drawSplash(canvas, 390f, height / s)
            Screen.ONBOARD -> drawOnboard(canvas, 390f, height / s)
            Screen.WELCOME -> drawWelcome(canvas, 390f, height / s)
            Screen.ROLE -> drawRole(canvas, 390f, height / s)
            Screen.LOGIN -> drawLogin(canvas, 390f, height / s)
            Screen.HOME -> drawHome(canvas, 390f, height / s)
            Screen.EXPLORE -> drawExplore(canvas, 390f, height / s)
            Screen.DETAIL -> drawProductDetail(canvas, 390f, height / s)
            Screen.CHECKOUT -> drawCheckout(canvas, 390f, height / s)
            Screen.QRIS -> drawPayment(canvas, 390f, height / s)
            Screen.READY -> drawReady(canvas, 390f, height / s)
            Screen.ORDERS -> drawOrders(canvas, 390f, height / s)
            Screen.PROFILE -> drawProfile(canvas, 390f, height / s)
            Screen.EXP_HISTORY -> drawExpHistory(canvas, 390f, height / s)
            Screen.NOTIF -> drawNotif(canvas, 390f, height / s)
            Screen.HELP -> drawHelp(canvas, 390f, height / s)
            Screen.ABOUT -> drawAbout(canvas, 390f, height / s)
            Screen.INFO -> drawInfoPage(canvas, 390f, height / s)
            Screen.ADMIN -> drawAdminDashboard(canvas, 390f, height / s)
            Screen.ADMIN_DETAIL -> drawAdminDetail(canvas, 390f, height / s)
            Screen.PRODUCT_FORM -> drawProductForm(canvas, 390f, height / s)
            Screen.DEV -> drawDeveloper(canvas, 390f, height / s)
        }
        canvas.restore()
    }

    /**
     * Toolbar kaca melayang halaman dalam: terlepas dari tepi layar, tombol
     * kembali + judul putih di atas kaca navy. Konten tiap layar mulai di
     * y >= 88 sehingga tidak tertutup. Stabil saat slide (counter-translate).
     */
    internal fun header(c: Canvas, title: String) {
        c.save()
        c.translate(-slideX, 0f)
        val bar = RectF(16f, 12f, 374f, 68f)
        kit.rounded(c, RectF(bar.left, bar.top + 2f, bar.right, bar.bottom + 3f), kit.glassShadow, 20f)
        kit.rounded(c, bar, kit.glass, 20f, kit.glassBorder)
        val back = RectF(28f, 22f, 64f, 58f)
        kit.rounded(c, back, kit.tint, 18f)
        drawIcon(c, "chevronLeft", back.centerX(), back.centerY(), 19f, kit.blue)
        kit.text(c, title, 78f, 46f, 18f, android.graphics.Color.WHITE, true)
        c.restore()
        bounds["headerBack"] = RectF(20f, 14f, 84f, 64f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = width / 390f
        val x = event.x / s
        val y = event.y / s
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = y; previousY = y; moved = false
                // Sama seperti dispatch di ACTION_UP (terbalik) supaya highlight
                // menunjuk kontrol yang benar saat area tumpang tindih.
                val hitKey = bounds.entries.toList().asReversed().firstOrNull { it.value.contains(x, y) }?.key
                pressedKey = hitKey
                pressedTab = hitKey?.takeIf { it.startsWith("tab:") }
                if (pressedKey != null) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val scrollable = screen == Screen.HOME || screen == Screen.EXPLORE || screen == Screen.ORDERS ||
                    screen == Screen.ADMIN || screen == Screen.ADMIN_DETAIL || screen == Screen.PRODUCT_FORM ||
                    screen == Screen.DEV || screen == Screen.CHECKOUT || screen == Screen.NOTIF ||
                    screen == Screen.EXP_HISTORY || screen == Screen.PROFILE
                if (scrollable && !adminNotifOpen && abs(y - touchStartY) > 6f) {
                    moved = true
                    if (pressedTab != null || pressedKey != null) {
                        pressedTab = null
                        pressedKey = null
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
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedTab != null || pressedKey != null) {
                    pressedTab = null
                    pressedKey = null
                    invalidate()
                }
                if (moved) return true
                val hit = bounds.entries.toList().asReversed().firstOrNull { it.value.contains(x, y) }?.key ?: return true
                when {
                    hit == "welcome" -> go(Screen.ROLE)
                    hit == "obSkip" || hit == "obStart" -> activity()?.finishOnboarding()
                    hit == "obNext" -> {
                        if (onboardPage >= onboardCount - 1) activity()?.finishOnboarding()
                        else {
                            onboardPage++
                            invalidate()
                        }
                    }
                    hit == "obBack" -> {
                        if (onboardPage > 0) {
                            onboardPage--
                            invalidate()
                        }
                    }
                    hit == "buyer" || hit == "seller" -> {
                        selectedRole = hit
                        invalidate()
                    }
                    hit == "role" -> activity()?.submitRoleChoice(selectedRole)
                    hit == "emailField" -> activity()?.showAuthForm(false)
                    hit == "passwordField" -> activity()?.showAuthForm(true)
                    hit == "emailLogin" -> activity()?.showAuthForm(false)
                    hit == "toggleAuth" -> activity()?.showAuthForm(false, signup = true)
                    hit == "search" -> activity()?.showSearchForm()
                    hit == "cartCheckout" -> {
                        if (cart.isEmpty()) {
                            android.widget.Toast.makeText(context, "Keranjang masih kosong.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            activity()?.openCheckout()
                        }
                    }
                    hit == "location" -> activity()?.showLocationForm()
                    hit.startsWith("cat:") -> {
                        activeCategory = hit.removePrefix("cat:").let { if (it == "Semua") "All" else it }
                        scroll = 0f
                        invalidate()
                    }
                    hit.startsWith("item") -> {
                        val id = hit.removePrefix("item:")
                        selectedProduct = products.firstOrNull { it.id == id }
                        quantity = 1
                        if (selectedProduct != null) go(Screen.DETAIL)
                    }
                    hit.startsWith("add:") -> {
                        activity()?.addToCart(hit.substringAfter(':'))
                    }
                    hit == "detailAdd" -> {
                        selectedProduct?.let { product -> activity()?.addToCart(product.id, quantity) }
                        goBack()
                    }
                    hit.startsWith("cartPlus:") -> activity()?.setCartQty(hit.substringAfter(':'), (cart[hit.substringAfter(':')] ?: 0) + 1)
                    hit.startsWith("cartMinus:") -> activity()?.setCartQty(hit.substringAfter(':'), (cart[hit.substringAfter(':')] ?: 0) - 1)
                    hit.startsWith("cartRemove:") -> activity()?.setCartQty(hit.substringAfter(':'), 0)
                    hit.startsWith("payMethod:") -> activity()?.setCheckoutPayment(hit.substringAfter(':'))
                    hit == "placeOrder" -> if (!placingOrder) activity()?.placeCartOrder()
                    hit == "paid" -> activity()?.submitPaymentProof()
                    hit.startsWith("myOrderPaid:") -> activity()?.submitPaymentProofFor(hit.substringAfter(':'))
                    hit == "profilePhone" -> activity()?.showPhoneEditor()
                    hit == "expHistory" -> activity()?.openExpHistory()
                    hit == "expMore" -> activity()?.loadMoreExp()
                    hit == "expRetry" -> activity()?.loadExpPage()
                    hit == "profileQris" -> activity()?.showQrisSettings()
                    hit == "profileStallName" -> activity()?.showStallNameEdit()
                    hit == "qrisPick" -> activity()?.pickQrisImage()
                    hit == "qrisUrl" -> activity()?.showQrisUrlForm()
                    hit.startsWith("tab:") -> {
                        navAnimAt = SystemClock.uptimeMillis()
                        // Arah geser mengikuti urutan tab (kiri/kanan).
                        val tabOrder = listOf("Home", "Explore", "Orders", "Profile")
                        val nextTab = hit.removePrefix("tab:")
                        val beforeTabScreen = screen
                        val beforeTabScroll = scroll
                        val beforeTabKey = "$screen:$tab"
                        val tabDir = when {
                            tabOrder.indexOf(nextTab) > tabOrder.indexOf(tab) -> 1f
                            tabOrder.indexOf(nextTab) < tabOrder.indexOf(tab) -> -1f
                            else -> 0f
                        }
                        when (nextTab) {
                            "Home" -> {
                                isSellerProfile = false
                                if (myRole == "seller") activity()?.backToSellerHome()
                                else {
                                    tab = "Home"
                                    if (screen != Screen.HOME) go(Screen.HOME)
                                    activity()?.refreshProducts()
                                    activity()?.refreshOrderingStatus()
                                }
                            }
                            "Explore" -> {
                                tab = "Explore"
                                isSellerProfile = false
                                if (screen != Screen.EXPLORE) go(Screen.EXPLORE)
                                activity()?.refreshProducts()
                            }
                            "Orders" -> { tab = "Orders"; go(Screen.ORDERS); activity()?.refreshMyOrders() }
                            "Profile" -> {
                                tab = "Profile"
                                isSellerProfile = false
                                if (SupabaseRepository.hasSession()) go(Screen.PROFILE)
                                else {
                                    android.widget.Toast.makeText(context, "Masuk dulu untuk membuka profil.", android.widget.Toast.LENGTH_SHORT).show()
                                    activity()?.showAuthForm(false)
                                }
                            }
                        }
                        // go() selalu menggeser dari kanan; timpa dengan arah
                        // tab yang benar bila layar memang berpindah.
                        if (tabDir != 0f && screen != beforeTabScreen) beginSlide(tabDir)
                        // Pertahankan posisi scroll tiap tab.
                        if (screen != beforeTabScreen) {
                            pageScroll[beforeTabKey] = beforeTabScroll
                            scroll = pageScroll["$screen:$tab"] ?: scroll
                        }
                        invalidate()
                    }
                    hit == "logout" -> activity()?.logout()
                    hit == "devBell" -> activity()?.openNotif()
                    hit == "adminRefresh" -> activity()?.refreshAdminOrders()
                    hit.startsWith("stab:") -> {
                        val beforeStabScreen = screen
                        val beforeStabScroll = scroll
                        when (hit.removePrefix("stab:")) {
                            "Dashboard" -> go(Screen.ADMIN)
                            "Pesanan" -> {
                                pendingScrollToOrders = true
                                go(Screen.ADMIN)
                            }
                            "Produk" -> activity()?.showProductForm()
                            "Notifikasi" -> activity()?.openNotif()
                            "Profil" -> {
                                isSellerProfile = true
                                if (SupabaseRepository.hasSession()) {
                                    go(Screen.PROFILE)
                                    activity()?.refreshSellerSettings()
                                } else {
                                    android.widget.Toast.makeText(context, "Masuk dulu untuk membuka profil.", android.widget.Toast.LENGTH_SHORT).show()
                                    activity()?.showAuthForm(false)
                                }
                            }
                        }
                        if (screen != beforeStabScreen) {
                            pageScroll["s:$beforeStabScreen"] = beforeStabScroll
                            scroll = pageScroll["s:$screen"] ?: scroll
                        }
                        invalidate()
                    }
                    hit.startsWith("productEdit:") -> activity()?.showProductEditForm(hit.substringAfter(':'))
                    hit.startsWith("productDelete:") -> activity()?.confirmDeleteProduct(hit.substringAfter(':'))
                    hit == "adminBell" -> activity()?.openNotif()
                    hit == "adminNotifClose" || hit == "adminNotifCloseBtn" -> { adminNotifOpen = false; invalidate() }
                    hit == "adminProfile" -> {
                        if (SupabaseRepository.hasSession()) {
                            isSellerProfile = true
                            go(Screen.PROFILE)
                            activity()?.refreshSellerSettings()
                        } else {
                            android.widget.Toast.makeText(context, "Masuk dulu untuk membuka profil.", android.widget.Toast.LENGTH_SHORT).show()
                            activity()?.showAuthForm(false)
                        }
                    }
                    hit.startsWith("adminFilter:") -> { adminFilter = hit.substringAfter(':'); scroll = 0f; invalidate() }
                    hit.startsWith("adminOrder:") || hit.startsWith("adminNotifOrder:") -> {
                        val orderId = hit.substringAfter(':')
                        adminDetailOrder = adminOrders.firstOrNull { it.id == orderId }
                        adminDetailId = orderId
                        adminDetailError = null
                        adminDetailLoading = adminDetailOrder == null
                        adminNotifOpen = false
                        go(Screen.ADMIN_DETAIL)
                        activity()?.loadAdminOrderDetail(orderId)
                    }
                    hit == "adminBack" -> goBack()
                    hit == "adminRetry" -> adminDetailId?.let { activity()?.loadAdminOrderDetail(it) }
                    hit.startsWith("adminSet:") -> {
                        val status = hit.substringAfter(':')
                        adminDetailOrder = adminDetailOrder?.copy(status = status)
                        invalidate()
                        adminDetailOrder?.let { activity()?.setAdminOrderStatus(it.id, status) }
                    }
                    hit.startsWith("adminStatus:") -> {
                        val orderId = hit.substringAfter(':')
                        val current = adminOrders.firstOrNull { it.id == orderId }
                        val next = current?.let { statusNextAction(it.status)?.second }
                        if (current != null && next != null) {
                            // Optimis: status kartu langsung bergerak; server mengonfirmasi lewat refresh.
                            adminOrders = adminOrders.map { if (it.id == orderId) it.copy(status = next) else it }
                            invalidate()
                            activity()?.setAdminOrderStatus(orderId, next)
                        }
                    }
                    hit.startsWith("adminCancel:") -> activity()?.cancelAdminOrder(hit.substringAfter(':'))
                    hit.startsWith("payPaid:") -> activity()?.markOrderPaid(hit.substringAfter(':'))
                    hit.startsWith("payReject:") -> activity()?.rejectOrderPayment(hit.substringAfter(':'))
                    hit == "productAdd" -> activity()?.showProductForm()
                    hit.startsWith("productToggle:") -> activity()?.toggleProductAvailability(hit.substringAfter(':'))
                    hit == "productSave" -> activity()?.saveProduct()
                    hit == "pfName" -> activity()?.showProductTextField("nama")
                    hit == "pfPrice" -> activity()?.showProductTextField("harga")
                    hit == "pfStock" -> activity()?.showProductTextField("stok")
                    hit == "pfDesc" -> activity()?.showProductTextField("deskripsi")
                    hit == "pfIngredients" -> activity()?.showProductTextField("bahan")
                    hit == "pfCategory" -> activity()?.showProductCategoryPicker()
                    hit == "pfImage" -> activity()?.pickProductImage()
                    hit == "pfAvailable" -> activity()?.toggleProductFormAvailable()
                    hit == "pfSave" -> activity()?.saveProductForm()
                    hit == "pfDelete" -> {
                        productEditId?.let { activity()?.confirmDeleteProduct(it) }
                    }
                    hit == "productImage" -> activity()?.pickProductImage()
                    hit.startsWith("productCategory:") -> { productCategory = hit.substringAfter(':'); invalidate() }
                    hit == "back" || hit == "headerBack" -> goBack()
                    hit == "inbox" -> activity()?.openNotif()
                    hit == "help" -> go(Screen.HELP)
                    hit == "about" -> go(Screen.ABOUT)
                    hit == "infoPage" -> go(Screen.INFO)
                    hit.startsWith("notifInvite:") -> activity()?.showInviteVerify(hit.substringAfter(':'))
                    hit.startsWith("devNotifInviteCancel:") -> activity()?.confirmCancelInvite(hit.substringAfter(':'))
                    hit.startsWith("notifWarn:") -> activity()?.showNotifWarning(hit.substringAfter(':'))
                    hit.startsWith("notifAnn:") -> activity()?.showNotifAnn(hit.substringAfter(':'))
                    hit.startsWith("notifOrder:") -> {
                        val orderId = hit.substringAfter(':')
                        adminDetailOrder = adminOrders.firstOrNull { it.id == orderId }
                        adminDetailId = orderId
                        adminDetailError = null
                        adminDetailLoading = adminDetailOrder == null
                        go(Screen.ADMIN_DETAIL)
                        activity()?.loadAdminOrderDetail(orderId)
                    }
                    hit == "theme" -> activity()?.showThemePicker()
                    hit == "devSearch" -> activity()?.showDevSearch()
                    hit == "devReset" -> activity()?.confirmDailyReset()
                    hit == "devLogout" -> activity()?.logout()
                    hit == "devRefresh" -> activity()?.refreshDeveloper()
                    hit == "devUserClose" -> { devSelectedUserId = null; invalidate() }
                    hit == "devWarnNew" -> activity()?.showWarnPicker()
                    hit == "devAnnNew" -> activity()?.showAnnouncementForm()
                    hit.startsWith("devTab:") -> { devTab = hit.substringAfter(':'); scroll = 0f; invalidate() }
                    hit.startsWith("devFilter:") -> { devUserFilter = hit.substringAfter(':'); scroll = 0f; invalidate() }
                    hit.startsWith("devUser:") -> {
                        val id = hit.substringAfter(':')
                        devSelectedUserId = if (devSelectedUserId == id) null else id
                        invalidate()
                    }
                    hit.startsWith("devWarn:") -> activity()?.showWarningForm(hit.substringAfter(':'))
                    hit.startsWith("devInvite:") -> activity()?.confirmInviteSeller(hit.substringAfter(':'))
                    hit.startsWith("devInviteCancel:") -> activity()?.confirmCancelInvite(hit.substringAfter(':'))
                    hit.startsWith("devActive:") -> activity()?.setDevUserStatus(hit.substringAfter(':'), "active")
                    hit.startsWith("devSuspend:") -> activity()?.setDevUserStatus(hit.substringAfter(':'), "suspended")
                    hit.startsWith("devStall:") -> {
                        val parts = hit.substringAfter(':').split(':')
                        if (parts.size == 2) activity()?.toggleDevStall(parts[0], parts[1].toBoolean())
                    }
                    hit.startsWith("devOrder:") -> {
                        val id = hit.substringAfter(':')
                        devExpandedOrderId = if (devExpandedOrderId == id) null else id
                        invalidate()
                    }
                    hit == "plus" -> {
                        // Aturan: satu pesanan = satu menu, jumlah tepat 1.
                        if (quantity < 1) {
                            quantity = 1
                            invalidate()
                        } else {
                            android.widget.Toast.makeText(context, "Satu pesanan hanya untuk satu menu (1x).", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    hit == "minus" -> { quantity = (quantity - 1).coerceAtLeast(1); invalidate() }
                    hit == "checkout" -> activity()?.openCheckout()
                    hit == "pay" -> if (!placingOrder) activity()?.placeCartOrder()
                    hit == "orders" -> { go(Screen.ORDERS); activity()?.refreshMyOrders() }
                    hit == "myOrdersRetry" -> activity()?.refreshMyOrders()
                }
                return true
            }
        }
        return true
    }

    internal fun activity() = context as? MainActivity
}
