package com.jajanin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.jajanin.app.core.constants.LOCATION_OPTIONS
import com.jajanin.app.core.constants.SERVICE_FEE
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.DrawKit
import com.jajanin.app.core.ui.HERO_TOP_OFFSET
import com.jajanin.app.core.ui.HitBounds
import com.jajanin.app.core.ui.NavSpec
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.drawAbout
import com.jajanin.app.core.ui.drawHelp
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.drawInfoPage
import com.jajanin.app.core.ui.statusNextAction
import com.jajanin.app.feature.buyer.store_discovery.PublicStoreDetail
import com.jajanin.app.feature.buyer.store_discovery.PublicStoreSummary
import com.jajanin.app.feature.buyer.store_discovery.StoreSearchViewModel
import com.jajanin.app.feature.buyer.store_discovery.drawStoreSearch
import com.jajanin.app.feature.buyer.store_profile.StoreProfileViewModel
import com.jajanin.app.feature.buyer.store_profile.drawStoreProfile
import com.jajanin.app.core.update.checkForOtaUpdate
import com.jajanin.app.core.update.showDevOtaPublishForm
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.model.MenuItem
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.feature.chat.drawChatList
import com.jajanin.app.feature.chat.loadMoreConversations
import com.jajanin.app.feature.chat.openChatList
import com.jajanin.app.feature.chat.openConversationById
import com.jajanin.app.feature.chat.openOrderChat
import com.jajanin.app.feature.chat.openProductInquiryChat
import com.jajanin.app.feature.chat.openStoreChat
import com.jajanin.app.feature.chat.refreshChatList
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.DevAnnouncementRow
import com.jajanin.app.data.repository.DevStats
import com.jajanin.app.data.repository.DevUser
import com.jajanin.app.data.repository.DevWarningRow
import com.jajanin.app.data.repository.OrderObserver
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.sellerPoAdvanceBlockReason
import com.jajanin.app.data.repository.SalesStat
import com.jajanin.app.data.repository.PlacedOrder
import com.jajanin.app.data.repository.SellerInvite
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.auth.drawLogin
import com.jajanin.app.feature.auth.drawWelcome
import com.jajanin.app.feature.auth.showDeleteAccountFlow
import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.inbox.*
import com.jajanin.app.feature.buyer.cart.drawCheckout
import com.jajanin.app.feature.buyer.cart.drawDetail
import com.jajanin.app.feature.buyer.cart.drawPayment
import com.jajanin.app.feature.buyer.cart.drawProductDetail
import com.jajanin.app.feature.buyer.exp.ExpEntry
import com.jajanin.app.feature.buyer.exp.drawExpHistory
import com.jajanin.app.feature.buyer.explore.drawExplore
import com.jajanin.app.feature.buyer.home.drawHome
import com.jajanin.app.feature.buyer.notifications.drawNotif
import com.jajanin.app.feature.buyer.orders.drawOrders
import com.jajanin.app.feature.buyer.orders.drawReady
import com.jajanin.app.feature.buyer.profile.drawProfile
import com.jajanin.app.feature.developer.drawDeveloper
import com.jajanin.app.feature.onboarding.drawOnboard
import com.jajanin.app.feature.onboarding.drawSplash
import com.jajanin.app.feature.seller.dashboard.drawAdminDashboard
import com.jajanin.app.feature.seller.menu.drawProductForm
import com.jajanin.app.feature.seller.menu.drawSellerMenu
import com.jajanin.app.feature.seller.orders.drawAdminDetail
import kotlin.math.abs

/** Durasi transisi geser antar halaman (ms). */
private const val PAGE_SLIDE_MS = 240L

internal class JajaninView(context: Context) : View(context) {

    init {
        AppIcon.init(context)
    }

    internal val kit = DrawKit(resources) { width / 390f }

    // Append-only draw-ordered hit regions so duplicate keys never overwrite earlier hitboxes.
    internal val bounds = HitBounds()

    internal var products: List<ProductRow> = emptyList()
    internal var sellerProducts: List<ProductRow> = emptyList()
    internal var favoriteIds = linkedSetOf<String>()
    internal var topSellingStats: Map<String, SalesStat> = emptyMap()
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
    internal var adminScheduleFilter = "Semua"
    internal var sellerTab = "Dashboard"
    internal var sellerMenuFilter = "Semua"
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
    internal var productsRefreshing = false
    internal var pullRefreshDragY = 0f
    internal var productsError: String? = null
    internal var scrollDummy = 0
    internal var productDescription = ""
    internal var productCategory = "Food"
    internal var productPrice = ""
    internal var productName = ""
    internal var productImageUrl = ""
    internal var productImageUrls: MutableList<String> = mutableListOf()
    internal var productImageUploading = false
    internal var productStock = ""
    internal var productIngredients = ""
    internal var productRoom = ""
    internal var productAvailable = true
    internal var productOrderSchedule = "both"
    internal var productPickupSessions = linkedSetOf("Istirahat 1", "Istirahat 2", "Pulang")
    internal var productPoDates = linkedSetOf<String>()
    internal var productPoCutoffHour = 20
    internal var productPoDailyQuota = ""
    internal var productPoRequireQris = false
    internal var productVariantSections: MutableList<com.jajanin.app.data.model.ProductVariantSection> = mutableListOf()
    internal var detailSelectedVariants: MutableMap<Int, String> = linkedMapOf()
    // Bottom sheet pemilih varian & jumlah di Detail Produk (null = tutup, "cart" = Keranjang, "buy" = Beli Sekarang).
    internal var detailSheetMode: String? = null
    internal var detailSheetScroll: Float = 0f
    internal var detailSheetMaxScroll: Float = 0f
    internal var detailSheetTopY: Float = 0f
    internal var detailPhotoIndex: Int = 0
    // Animated Bottom Sheet detail penilaian pembeli di Profil Warung.
    internal var storeRatingsSheetOpen: Boolean = false
    internal var storeRatingsSheetProgress: Float = 0f
    internal var storeRatingsSheetFrom: Float = 0f
    internal var storeRatingsSheetAnimAt: Long = 0L
    internal var storeRatingsSheetScroll: Float = 0f
    internal var storeRatingsSheetMaxScroll: Float = 0f
    internal var storeRatingsSheetTopY: Float = 0f
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
    internal var myNis: String = ""
    internal var myClass: String = ""
    internal var myIsSellerVerified = false
    internal var mySellerVerifiedEmail: String = ""
    internal var savedProductId: String? = null
    internal var isSellerProfile = false
    // --- Buyer checkout state (multi-item cart, satu warung per order) ---
    internal var cartStallId: String? = null
    internal var cartStallName: String = "Kantin Jajanin"
    // QRIS warung isi keranjang: null = belum dicek, non-blank = Midtrans / non-tunai aktif
    internal var cartStallQris: String? = "midtrans_sandbox"
    internal var checkoutPayment: String = "cash"
    internal var checkoutOrderMode: String = "same_day"
    internal var checkoutPickupDate: String = OrderRepository.nextOpenSchoolDatesWib(1).firstOrNull()
        ?: OrderRepository.tomorrowWibDateString()
    internal var checkoutPickupSession: String = "Istirahat 1"
    internal var checkoutPoSlots: Map<String, com.jajanin.app.data.repository.PoDateSlot> = emptyMap()
    internal var placingOrder = false
    internal var lastOrderId: String? = null
    internal var lastOrderPickup: String? = null
    internal var lastOrderTotal: Int = 0
    internal var lastOrderMethod: String = "cash"
    internal var lastOrderStatus: String = "pending"
    internal var selectedBuyerOrderId: String? = null
    internal var qrisOrderId: String? = null
    internal var qrisAmount: Int = 0
    internal var qrisImageUrl: String? = null
    internal var qrisQrString: String? = null
    internal var qrisRedirectUrl: String? = null
    internal var qrisGatewayOrderId: String? = null
    internal var qrisPaymentChannel: String = "qris"
    internal var qrisEnvironment: String = "sandbox"
    internal var qrisLoading = false
    internal var qrisCheckingStatus = false
    internal var qrisSavingImage = false
    internal var qrisError: String? = null
    internal var qrisExpiresAt: String? = null
    internal var qrisProofUrl: String? = null
    internal var qrisProofUploading = false
    internal var qrisProofSubmitting = false
    internal var qrisExpiryTriggeredForId: String? = null
    // --- Seller settings state ---
    internal var myStallId: String? = null
    internal var myStallName: String = ""
    internal var myStallRoom: String = ""
    internal var recentSellerRooms: List<String> = emptyList()
    internal var myStallQris: String = ""
    internal var sellerLedgerAvailable: Int = 0
    internal var sellerLedgerPaidOut: Int = 0
    internal var sellerLedgerTxCount: Int = 0
    internal var myStallLogoUrl: String = ""
    internal var myStallBannerUrl: String = ""
    internal var myStallBio: String = ""
    internal var myStallCategory: String = "Makanan & Minuman"
    internal var myStallIsOpen: Boolean = true
    internal var myStallIsVerified: Boolean = false
    internal var myStallCompletedOrders: Int = 0
    internal var cart = linkedMapOf<String, Int>()
    // --- Buyer Store Discovery & Public Store Profile state ---
    internal var storeDirectoryById: Map<String, PublicStoreSummary> = emptyMap()
    internal var storeSearchQuery: String = ""
    internal var storeSearchCategory: String = "All"
    internal var storeSearchResults: List<PublicStoreSummary> = emptyList()
    internal var storeSearchTotal: Int = 0
    internal var storeSearchHasMore: Boolean = false
    internal var storeSearchOffset: Int = 0
    internal var storeSearchLoading: Boolean = false
    internal var storeSearchLoadingMore: Boolean = false
    internal var storeSearchError: String? = null
    internal var selectedStoreId: String? = null
    internal var storeProfileDetail: PublicStoreDetail? = null
    internal var storeProfileProducts: List<ProductRow> = emptyList()
    internal var storeProfileCategoryFilter: String = "All"
    internal var storeProfileLoading: Boolean = false
    internal var storeProfileError: String? = null
    // --- Inline Direct Search Input state (no popup dialog) ---
    internal var inlineSearchActiveTarget: String? = null
    private val inlineSearchRect = RectF()
    private var inlineSearchHasRect = false
    private var inlineSearchVirtualTextSize = 13f
    internal var inlineSearchBoundsListener: ((RectF?, Float, Float) -> Unit)? = null

    internal fun setInlineSearchFieldBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        textSize: Float = 13f,
    ) {
        inlineSearchRect.set(left, top, right, bottom)
        inlineSearchHasRect = true
        inlineSearchVirtualTextSize = textSize
    }
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
    internal var devOrderExpandAt: Long = 0L
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
    internal var onlineUserIds: Set<String> = emptySet()
    // --- Real-time chat & DM state ---
    internal var chatConversations: List<ConversationSummary> = emptyList()
    internal var chatListLoading = false
    internal var chatLoadingMore = false
    internal var chatHasMore = false
    internal var chatListError: String? = null
    internal var chatListFilter = "Semua"
    internal var unreadChatTotal = 0
    internal var unreadChatByOrder: Map<String, Int> = emptyMap()
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
    internal var myAvatarUrl = ""
    internal var myOrderRatings: Map<String, com.jajanin.app.data.repository.OrderRatingRow> = emptyMap()
    internal var storeProfileRatings: List<com.jajanin.app.data.repository.StoreRecentRating> = emptyList()
    internal var devReports: List<com.jajanin.app.data.repository.DevSellerReport> = emptyList()
    internal var devReportsLoading = false
    internal var devReportFilter = "Semua"
    internal var devSelectedReportId: String? = null
    // --- Ordering restrictions (mirror server rules; enforcement is in place_order) ---
    internal var orderingBlocked = false
    internal var orderingBlockMessage: String? = null
    internal var ordersToday = 0
    internal var orderingStatusLoading = false
    // --- Antrean warung (maksimal 10 order aktif; enforcement di place_order) ---
    internal var stallActiveOrders = -1
    internal var stallFull = false
    internal var stallLoading = false
    internal var otaUpdateAvailable = false
    internal var otaLatestVersion: String? = null
    internal var otaLatestChangelog: String? = null
    internal var unreadOrderNotifCount = 0
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
    internal var tab = "Home"
    // Posisi scroll per halaman tab agar tidak reset saat pindah tab.
    private val pageScroll = HashMap<String, Float>()
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var previousY = 0f
    private var touchDownAt = 0L
    private var moved = false
    private var swipeMode = 0 // 0 = undecided, 1 = vertical scroll, 2 = horizontal tab swipe
    private var swipeDragX = 0f
    private var downHitKey: String? = null

    // Dikonsumsi drawBottomNav di BottomNav.kt: tab yang ditekan + waktu mulai animasi indikator.
    internal var pressedTab: String? = null
    internal var navAnimAt = 0L
    // State dock kaca eksternal: diisi tiap frame oleh drawFloatingNav dan
    // diteruskan ke host (LiquidGlassTabBar di MainActivity).
    internal var navSpec: NavSpec? = null
    internal var navBadgeIndex = -1
    internal var navBadgeCount = 0
    internal var navPresenter: ((NavSpec?) -> Unit)? = null
    internal var navContentInvalidator: (() -> Unit)? = null
    private var lastPresentedNavSpec: NavSpec? = null
    // Tema aplikasi (dibaca dari SharedPreferences saat MainActivity.onCreate).
    internal var darkMode = false
    // Label mode tema aktif untuk baris Profil ("Terang"/"Gelap"/"Sistem").
    internal var themeLabel = "Gelap"
    // Offset geser halaman saat ini (untuk chrome yang stabil: header/nav
    // menggambar dengan counter-translate sehingga tetap diam).
    internal var slideX = 0f
    // Transisi geser antar halaman: arah (+1 dari kanan, -1 dari kiri, 0 mati)
    // + waktu mulai. Digambar di onDraw selama ~240ms, lalu berhenti sendiri.
    private var pageSlideDir = 0f
    private var pageSlideOffset = 0f
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
            pageSlideOffset = 0f
            return
        }
        pageSlideDir = dir
        pageSlideOffset = dir * 56f
        pageSlideAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Memantulkan halaman kembali ke posisi semula jika geser horizontal belum melewati ambang batas. */
    private fun snapSlideBack(fromOffset: Float) {
        if (abs(fromOffset) < 1f || !motionEnabled()) {
            pageSlideDir = 0f
            pageSlideOffset = 0f
            slideX = 0f
            invalidate()
            return
        }
        pageSlideDir = if (fromOffset > 0f) 1f else -1f
        pageSlideOffset = fromOffset
        pageSlideAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun swipeableTabSpec(): Pair<String, List<String>>? = when {
        navSpec?.prefix == "tab:" -> "tab:" to listOf("Home", "Explore", "Chat", "Orders", "Profile")
        navSpec?.prefix == "stab:" -> "stab:" to listOf("Dashboard", "Pesanan", "Chat", "Notifikasi", "Profil")
        navSpec?.prefix == "dtab:" -> "dtab:" to listOf("Overview", "Users", "Sellers", "Orders", "Laporan", "Warn", "Info")
        else -> null
    }

    private fun canSwipeHorizontal(): Boolean =
        screen == Screen.ONBOARD || swipeableTabSpec() != null

    private fun currentHorizontalBounds(): Pair<Int, Int>? {
        if (screen == Screen.ONBOARD) return onboardPage to (onboardCount - 1)
        val (prefix, order) = swipeableTabSpec() ?: return null
        val currentLabel = when (prefix) {
            "stab:" -> when (screen) {
                Screen.CHAT_LIST -> "Chat"
                Screen.NOTIF -> "Notifikasi"
                Screen.PROFILE -> if (isSellerProfile) "Profil" else sellerTab
                else -> sellerTab
            }
            "dtab:" -> devTab
            else -> if (screen == Screen.CHAT_LIST) "Chat" else (navSpec?.active ?: tab)
        }
        val idx = order.indexOf(currentLabel)
        return if (idx >= 0) idx to (order.size - 1) else null
    }

    private fun stepHorizontalPage(delta: Int): Boolean {
        if (screen == Screen.ONBOARD) {
            val next = (onboardPage + delta).coerceIn(0, onboardCount - 1)
            if (next == onboardPage) return false
            onboardPage = next
            beginSlide(if (delta > 0) 1f else -1f)
            return true
        }
        val (prefix, order) = swipeableTabSpec() ?: return false
        val (idx, maxIdx) = currentHorizontalBounds() ?: return false
        val next = (idx + delta).coerceIn(0, maxIdx)
        if (next == idx) return false
        selectNavTab(prefix, order[next])
        return true
    }

    /** Dibaca host dock kaca untuk menyelaraskan latar belakang & warna aksen. */
    internal var themeChangedListener: (() -> Unit)? = null

    /** Listener tema khusus overlay native (mis. NativeLoginOverlay). */
    internal var loginThemeListener: ((Boolean) -> Unit)? = null

    /** Listener perpindahan layar untuk menampilkan/menyembunyikan overlay native. */
    internal var screenChangedListener: ((Screen) -> Unit)? = null

    /** Ganti tema terang/gelap; berlaku untuk buyer, seller, dan developer. */
    fun setDarkMode(dark: Boolean) {
        darkMode = dark
        kit.applyTheme(dark)
        invalidate()
        themeChangedListener?.invoke()
        loginThemeListener?.invoke(dark)
        activity()?.inlineSearch?.applyTheme()
    }

    internal fun canGoBack(): Boolean = history.isNotEmpty()

    internal fun animateStoreRatingsSheet(open: Boolean) {
        val target = if (open) 1f else 0f
        if (storeRatingsSheetOpen == open && storeRatingsSheetProgress == target) return
        storeRatingsSheetFrom = currentStoreRatingsProgress()
        storeRatingsSheetOpen = open
        storeRatingsSheetAnimAt = SystemClock.uptimeMillis()
        if (open) storeRatingsSheetScroll = 0f
        if (!motionEnabled()) {
            storeRatingsSheetProgress = target
        }
        invalidate()
    }

    internal fun currentStoreRatingsProgress(): Float {
        val target = if (storeRatingsSheetOpen) 1f else 0f
        if (!motionEnabled() || storeRatingsSheetAnimAt == 0L) {
            storeRatingsSheetProgress = target
            return target
        }
        val elapsed = (SystemClock.uptimeMillis() - storeRatingsSheetAnimAt).coerceAtLeast(0L)
        val rawT = (elapsed / 240f).coerceIn(0f, 1f)
        val eased = 1f - (1f - rawT) * (1f - rawT) * (1f - rawT)
        val value = storeRatingsSheetFrom + (target - storeRatingsSheetFrom) * eased
        storeRatingsSheetProgress = value
        if (rawT < 1f) {
            postInvalidateOnAnimation()
        } else {
            storeRatingsSheetProgress = target
        }
        return value
    }

    internal fun go(next: Screen) {
        if (screen == next) return
        // Panel notifikasi hanya hidup di dashboard; kalau tidak dibersihkan,
        // scroll di layar lain ikut terkunci (gerakan gulir mengecek flag ini).
        if (next != Screen.ADMIN) adminNotifOpen = false
        detailSheetMode = null
        detailSheetScroll = 0f
        storeRatingsSheetOpen = false
        storeRatingsSheetProgress = 0f
        storeRatingsSheetScroll = 0f
        history.addLast(screen)
        screen = next
        scroll = 0f
        beginSlide(1f)
        invalidate()
        screenChangedListener?.invoke(screen)
    }

    internal fun goBack(): Boolean {
        if (history.isEmpty()) return false
        val target = history.removeLast()
        if (target != Screen.ADMIN) adminNotifOpen = false
        detailSheetMode = null
        detailSheetScroll = 0f
        storeRatingsSheetOpen = false
        storeRatingsSheetProgress = 0f
        storeRatingsSheetScroll = 0f
        screen = target
        scroll = 0f
        beginSlide(-1f)
        invalidate()
        screenChangedListener?.invoke(screen)
        return true
    }

    internal val currentScreen: Screen get() = screen

    /**
     * Pintu masuk tunggal untuk pilihan tab dari dock kaca. Prefix menjaga
     * kontrak lama: "tab:" = pembeli, "stab:" = penjual.
     */
    internal fun selectNavTab(prefix: String, label: String) {
        activity()?.inlineSearch?.dismiss()
        when (prefix) {
            "stab:" -> selectSellerTab(label)
            "dtab:" -> selectDevTab(label)
            else -> selectBuyerTab(label)
        }
    }

    internal fun selectDevTab(nextTab: String) {
        val tabOrder = listOf("Overview", "Users", "Sellers", "Orders", "Laporan", "Warn", "Info")
        val dir = when {
            tabOrder.indexOf(nextTab) > tabOrder.indexOf(devTab) -> 1f
            tabOrder.indexOf(nextTab) < tabOrder.indexOf(devTab) -> -1f
            else -> 0f
        }
        pageScroll["d:$devTab"] = scroll
        devTab = nextTab
        devSelectedUserId = null
        devExpandedOrderId = null
        if (nextTab == "Sellers" && (devUserFilter == "Buyer" || devUserFilter == "Seller")) {
            devUserFilter = "Semua"
        }
        scroll = pageScroll["d:$nextTab"] ?: 0f
        if (dir != 0f) beginSlide(dir)
        invalidate()
    }

    /** Logika tab pembeli (dulu hidup di dalam onTouchEvent). */
    internal fun selectBuyerTab(nextTab: String) {
        navAnimAt = SystemClock.uptimeMillis()
        // Arah geser mengikuti urutan tab (kiri/kanan).
        val tabOrder = listOf("Home", "Explore", "Chat", "Orders", "Profile")
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
            "Chat" -> {
                isSellerProfile = false
                if (SupabaseRepository.hasSession()) {
                    tab = "Chat"
                    activity()?.openChatList()
                } else {
                    android.widget.Toast.makeText(context, "Masuk dulu untuk membuka chat.", android.widget.Toast.LENGTH_SHORT).show()
                    activity()?.showAuthForm(false)
                }
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

    /** Logika tab penjual dengan halaman Dashboard & Pesanan terpisah + transisi geser. */
    internal fun selectSellerTab(label: String) {
        val tabOrder = listOf("Dashboard", "Pesanan", "Produk", "Chat", "Notifikasi", "Profil")
        val currentLabel = when {
            screen == Screen.ADMIN -> sellerTab
            screen == Screen.PRODUCT_FORM -> "Produk"
            screen == Screen.CHAT_LIST -> "Chat"
            screen == Screen.NOTIF -> "Notifikasi"
            screen == Screen.PROFILE && isSellerProfile -> "Profil"
            else -> sellerTab
        }
        val tabDir = when {
            tabOrder.indexOf(label) > tabOrder.indexOf(currentLabel) -> 1f
            tabOrder.indexOf(label) < tabOrder.indexOf(currentLabel) -> -1f
            else -> 0f
        }
        val beforeKey = "s:$screen:$currentLabel"
        pageScroll[beforeKey] = scroll

        when (label) {
            "Dashboard" -> {
                sellerTab = "Dashboard"
                if (screen != Screen.ADMIN) go(Screen.ADMIN)
                scroll = pageScroll["s:${Screen.ADMIN}:Dashboard"] ?: 0f
                if (tabDir != 0f) beginSlide(tabDir)
                activity()?.refreshAdminOrders()
            }
            "Pesanan" -> {
                sellerTab = "Pesanan"
                if (screen != Screen.ADMIN) go(Screen.ADMIN)
                scroll = pageScroll["s:${Screen.ADMIN}:Pesanan"] ?: 0f
                if (tabDir != 0f) beginSlide(tabDir)
                activity()?.refreshAdminOrders()
            }
            "Produk" -> activity()?.showProductForm()
            "Chat" -> {
                activity()?.openChatList()
                if (tabDir != 0f) beginSlide(tabDir)
            }
            "Notifikasi" -> {
                activity()?.openNotif()
                if (tabDir != 0f) beginSlide(tabDir)
            }
            "Profil" -> {
                isSellerProfile = true
                if (SupabaseRepository.hasSession()) {
                    go(Screen.PROFILE)
                    if (tabDir != 0f) beginSlide(tabDir)
                    activity()?.refreshSellerSettings()
                    activity()?.refreshSellerProducts()
                } else {
                    android.widget.Toast.makeText(context, "Masuk dulu untuk membuka profil.", android.widget.Toast.LENGTH_SHORT).show()
                    activity()?.showAuthForm(false)
                }
            }
        }
        invalidate()
    }

    fun showHome() { screen = Screen.HOME; history.clear(); invalidate(); screenChangedListener?.invoke(screen) }

    fun showSplash() { screen = Screen.SPLASH; history.clear(); invalidate(); screenChangedListener?.invoke(screen) }

    fun showOnboard() { screen = Screen.ONBOARD; history.clear(); onboardPage = 0; scroll = 0f; invalidate(); screenChangedListener?.invoke(screen) }

    fun showWelcome() { screen = Screen.WELCOME; history.clear(); scroll = 0f; invalidate(); screenChangedListener?.invoke(screen) }

    fun showAdmin() { screen = Screen.ADMIN; history.clear(); invalidate(); screenChangedListener?.invoke(screen) }

    fun showDev() { screen = Screen.DEV; history.clear(); devTab = "Overview"; scroll = 0f; invalidate(); screenChangedListener?.invoke(screen) }

    fun showLogin() { screen = Screen.LOGIN; history.clear(); invalidate(); screenChangedListener?.invoke(screen) }

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
        // Geser halaman interaktif dengan jari atau animasi decelerate (~240ms).
        if (swipeMode == 2 && abs(swipeDragX) > 0.5f) {
            slideX = swipeDragX
            canvas.translate(slideX, 0f)
        } else if (pageSlideDir != 0f) {
            val elapsed = android.os.SystemClock.uptimeMillis() - pageSlideAt
            if (elapsed >= PAGE_SLIDE_MS) {
                pageSlideDir = 0f
                pageSlideOffset = 0f
                slideX = 0f
            } else {
                val t = elapsed / PAGE_SLIDE_MS.toFloat()
                val eased = 1f - (1f - t) * (1f - t)
                slideX = (1f - eased) * pageSlideOffset
                canvas.translate(slideX, 0f)
                postInvalidateOnAnimation()
            }
        } else {
            slideX = 0f
        }
        bounds.clear()
        inlineSearchHasRect = false
        // Layar yang tidak memanggil drawFloatingNav otomatis tidak punya dock.
        navSpec = null
        navBadgeIndex = -1
        navBadgeCount = 0
        // Aksesibilitas: seluruh layar (Buyer, Seller, Developer) menghormati
        // fontScale dari pengaturan Android secara konsisten dengan batas aman.
        kit.textScale = com.jajanin.app.core.ui.ResponsiveLayout.adaptiveTextScale(resources.configuration.fontScale)
        when (screen) {
            // --- Fondasi: splash, onboarding, autentikasi ---
            Screen.SPLASH -> drawSplash(canvas, 390f, height / s)
            Screen.ONBOARD -> drawOnboard(canvas, 390f, height / s)
            Screen.WELCOME -> drawWelcome(canvas, 390f, height / s)
            Screen.LOGIN -> drawLogin(canvas, 390f, height / s)

            // --- Pembeli (buyer) ---
            Screen.HOME -> drawHome(canvas, 390f, height / s)
            Screen.EXPLORE -> drawExplore(canvas, 390f, height / s)
            Screen.STORE_SEARCH -> drawStoreSearch(canvas, 390f, height / s)
            Screen.STORE_PROFILE -> drawStoreProfile(canvas, 390f, height / s)
            Screen.DETAIL -> drawProductDetail(canvas, 390f, height / s)
            Screen.CHECKOUT -> drawCheckout(canvas, 390f, height / s)
            Screen.QRIS -> drawPayment(canvas, 390f, height / s)
            Screen.READY -> drawReady(canvas, 390f, height / s)
            Screen.ORDERS -> drawOrders(canvas, 390f, height / s)
            Screen.PROFILE -> drawProfile(canvas, 390f, height / s)
            Screen.EXP_HISTORY -> drawExpHistory(canvas, 390f, height / s)

            // --- Penjual (seller) ---
            Screen.ADMIN -> drawAdminDashboard(canvas, 390f, height / s)
            Screen.ADMIN_DETAIL -> drawAdminDetail(canvas, 390f, height / s)
            Screen.PRODUCT_FORM -> drawProductForm(canvas, 390f, height / s)
            Screen.SELLER_MENU -> drawSellerMenu(canvas, 390f, height / s)

            // --- Developer ---
            Screen.DEV -> drawDeveloper(canvas, 390f, height / s)

            // --- Notifikasi, chat & informasi (semua peran) ---
            Screen.CHAT_LIST -> drawChatList(canvas, 390f, height / s)
            Screen.NOTIF -> drawNotif(canvas, 390f, height / s)
            Screen.HELP -> drawHelp(canvas, 390f, height / s)
            Screen.ABOUT -> drawAbout(canvas, 390f, height / s)
            Screen.INFO -> drawInfoPage(canvas, 390f, height / s)
        }
        drawPullRefreshBanner(canvas)
        canvas.restore()
        inlineSearchBoundsListener?.invoke(
            if (inlineSearchHasRect) inlineSearchRect else null,
            s,
            inlineSearchVirtualTextSize,
        )
        val spec = navSpec?.copy(badgeIndex = navBadgeIndex, badgeCount = navBadgeCount)
        if (spec != lastPresentedNavSpec) {
            lastPresentedNavSpec = spec
            navPresenter?.invoke(spec)
        }
        // Dock kaca perlu menangkap ulang konten kanvas setiap kali kanvas
        // berubah (scroll/animasi/ganti layar). Host merender lensa on-demand
        // agar tidak ada shader yang berjalan terus saat layar diam.
        navContentInvalidator?.invoke()
    }

    private fun navScrollPad(): Float = if (screen == Screen.DETAIL) 0f else 82f

    private fun canPullRefresh(): Boolean =
        screen == Screen.HOME || screen == Screen.EXPLORE || screen == Screen.STORE_SEARCH ||
            screen == Screen.STORE_PROFILE || screen == Screen.ORDERS ||
            screen == Screen.READY || screen == Screen.ADMIN || screen == Screen.ADMIN_DETAIL ||
            screen == Screen.SELLER_MENU || screen == Screen.CHAT_LIST

    private fun drawPullRefreshBanner(c: Canvas) {
        if (!canPullRefresh()) return
        val activePull = pullRefreshDragY > 6f
        val showRefreshing = productsRefreshing && (screen == Screen.HOME || screen == Screen.EXPLORE)
        if (!activePull && !showRefreshing) return
        val offset = if (activePull) (pullRefreshDragY * 0.45f).coerceAtMost(34f) else 18f
        val top = 56f + offset
        val pill = RectF(118f, top, 272f, top + 30f)
        val ready = pullRefreshDragY >= 44f || showRefreshing
        kit.rounded(c, pill, kit.card, 15f, if (ready) kit.blue else kit.border)
        drawIcon(c, "refresh", pill.left + 18f, pill.centerY(), 13f, if (ready) kit.blue else kit.muted)
        val label = when {
            showRefreshing && !activePull -> "Menyegarkan..."
            ready -> "Lepas untuk refresh"
            else -> "Tarik untuk refresh"
        }
        kit.text(c, label, pill.left + 32f, pill.centerY() + 4f, 10.5f, if (ready) kit.blue else kit.ink, true)
    }

    private fun triggerPullRefresh() {
        val act = activity() ?: return
        when (screen) {
            Screen.HOME, Screen.EXPLORE -> {
                act.refreshProducts(manual = true)
                act.refreshMyOrders()
            }
            Screen.STORE_SEARCH -> {
                StoreSearchViewModel.triggerSearch(act, immediate = true)
                android.widget.Toast.makeText(context, "Daftar warung diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            Screen.STORE_PROFILE -> {
                StoreProfileViewModel.loadStoreProfile(act)
                android.widget.Toast.makeText(context, "Profil warung diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            Screen.ORDERS, Screen.READY -> {
                act.refreshMyOrders()
                act.refreshProducts()
                android.widget.Toast.makeText(context, "Pesanan diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            Screen.ADMIN -> {
                act.refreshAdminOrders()
                act.refreshSellerProducts()
                android.widget.Toast.makeText(context, "Antrean diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            Screen.ADMIN_DETAIL -> {
                adminDetailId?.let { act.loadAdminOrderDetail(it) }
            }
            Screen.SELLER_MENU -> {
                act.refreshSellerProducts()
                android.widget.Toast.makeText(context, "Menu dagangan diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            Screen.CHAT_LIST -> {
                act.refreshChatList(silent = false)
                android.widget.Toast.makeText(context, "Daftar chat diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    /** Layar yang memakai tipografi penjual (termasuk profil & notifikasi seller). */
    private fun isSellerScreen(): Boolean =
        screen == Screen.ADMIN || screen == Screen.ADMIN_DETAIL || screen == Screen.PRODUCT_FORM ||
            screen == Screen.SELLER_MENU ||
            (screen == Screen.PROFILE && isSellerProfile) ||
            (screen == Screen.NOTIF && myRole == "seller") ||
            (screen == Screen.CHAT_LIST && myRole == "seller")

    /**
     * Dukungan font scaling Android untuk kanvas penjual. Perubahan dibatasi
     * 1.0–1.18 agar teks lebih besar tetap muat di kartu yang sudah dihitung;
     * semua teks penjual juga memakai ellipsis berbasis lebar, bukan `take(n)`.
     */
    private fun sellerTextScale(): Float {
        val fontScale = resources.configuration.fontScale
        return (1f + (fontScale - 1f) * 0.5f).coerceIn(1f, 1.18f)
    }

    internal fun onResponsiveConfigChanged() {
        kit.clearTextCaches()
        invalidate()
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
        kit.textEllipsized(c, title, 78f, 46f, 282f, 18f, android.graphics.Color.WHITE, true)
        c.restore()
        bounds["headerBack"] = RectF(16f, 12f, 76f, 68f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = (width / 390f).takeIf { it > 0f } ?: return false
        val x = event.x / s
        val y = event.y / s
        val hVirtual = height / s
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x; touchStartY = y; previousY = y
                touchDownAt = SystemClock.uptimeMillis()
                moved = false
                swipeMode = 0
                swipeDragX = 0f
                pullRefreshDragY = 0f
                // Scan terbalik (topmost-first) agar highlight menunjuk kontrol
                // paling atas saat area tumpang tindih.
                val hitKey = bounds.hitTest(x, y)
                downHitKey = hitKey
                if (inlineSearchActiveTarget != null &&
                    hitKey != "search" &&
                    hitKey != "clearSearch" &&
                    hitKey != "storeSearchInput" &&
                    hitKey != "storeSearchClear" &&
                    hitKey != "devSearch" &&
                    hitKey != "devSearchClear"
                ) {
                    activity()?.inlineSearch?.dismiss()
                }
                pressedKey = hitKey
                pressedTab = hitKey?.takeIf { it.startsWith("tab:") }
                if (pressedKey != null) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val scrollable = screen == Screen.HOME || screen == Screen.EXPLORE ||
                    screen == Screen.STORE_SEARCH || screen == Screen.STORE_PROFILE ||
                    screen == Screen.DETAIL || screen == Screen.QRIS || screen == Screen.ORDERS ||
                    screen == Screen.READY || screen == Screen.ADMIN || screen == Screen.ADMIN_DETAIL ||
                    screen == Screen.PRODUCT_FORM || screen == Screen.SELLER_MENU || screen == Screen.DEV ||
                    screen == Screen.CHECKOUT || screen == Screen.NOTIF || screen == Screen.EXP_HISTORY ||
                    screen == Screen.PROFILE || screen == Screen.CHAT_LIST
                val detailSheetOpen = screen == Screen.DETAIL && detailSheetMode != null
                val storeSheetOpen = screen == Screen.STORE_PROFILE && storeRatingsSheetOpen
                val sheetOpen = detailSheetOpen || storeSheetOpen
                val maxScroll = when {
                    detailSheetOpen -> detailSheetMaxScroll.coerceAtLeast(0f)
                    storeSheetOpen -> storeRatingsSheetMaxScroll.coerceAtLeast(0f)
                    else -> (contentHeight - hVirtual + navScrollPad()).coerceAtLeast(0f)
                }
                val touchInPinnedHeader = !sheetOpen && touchStartY <= 68f
                val touchInStickyFooter = when {
                    detailSheetOpen -> touchStartY < detailSheetTopY || touchStartY >= hVirtual - 76f
                    storeSheetOpen -> touchStartY < storeRatingsSheetTopY
                    else -> (screen == Screen.DETAIL && touchStartY >= hVirtual - 64f) ||
                        ((screen == Screen.QRIS || screen == Screen.CHECKOUT) && touchStartY >= hVirtual - 84f)
                }
                val dx = x - touchStartX
                val dy = y - touchStartY
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (swipeMode == 0 && !adminNotifOpen && !touchInPinnedHeader && !touchInStickyFooter) {
                    val canScrollVert = scrollable && (
                        maxScroll > 0.5f ||
                            (!sheetOpen && dy > 0f && scroll <= 0.5f && canPullRefresh())
                    )
                    if (!sheetOpen && canSwipeHorizontal() && absDx > 12f && absDx >= absDy * 1.1f) {
                        swipeMode = 2
                        moved = true
                        downHitKey = null
                    } else if (canScrollVert && absDy > 12f && absDy > absDx) {
                        swipeMode = 1
                        moved = true
                        downHitKey = null
                    } else if ((sheetOpen || !canSwipeHorizontal()) && canScrollVert && absDy > 12f) {
                        swipeMode = 1
                        moved = true
                        downHitKey = null
                    }
                    if (swipeMode != 0 && (pressedTab != null || pressedKey != null || inlineSearchActiveTarget != null)) {
                        if (inlineSearchActiveTarget != null) {
                            activity()?.inlineSearch?.dismiss()
                        }
                        pressedTab = null
                        pressedKey = null
                        invalidate()
                    }
                }
                if (swipeMode == 0 && (absDx > 22f || absDy > 22f)) {
                    if (pressedTab != null || pressedKey != null || downHitKey != null) {
                        pressedTab = null
                        pressedKey = null
                        downHitKey = null
                        invalidate()
                    }
                }
                when (swipeMode) {
                    2 -> {
                        val range = currentHorizontalBounds()
                        val atLeftEdge = range != null && range.first == 0 && dx > 0f
                        val atRightEdge = range != null && range.first == range.second && dx < 0f
                        val factor = if (atLeftEdge || atRightEdge) 0.24f else 0.72f
                        swipeDragX = (dx * factor).coerceIn(-160f, 160f)
                        invalidate()
                    }
                    1 -> {
                        if (detailSheetOpen) {
                            detailSheetScroll = (detailSheetScroll + previousY - y).coerceIn(0f, maxScroll)
                        } else if (storeSheetOpen) {
                            storeRatingsSheetScroll = (storeRatingsSheetScroll + previousY - y).coerceIn(0f, maxScroll)
                        } else {
                            val deltaY = y - previousY
                            if (scroll <= 0.5f && (deltaY > 0f || pullRefreshDragY > 0f) && canPullRefresh()) {
                                pullRefreshDragY = (pullRefreshDragY + deltaY * 0.58f).coerceIn(0f, 84f)
                                scroll = 0f
                            } else {
                                pullRefreshDragY = 0f
                                scroll = (scroll + previousY - y).coerceIn(0f, maxScroll)
                            }
                        }
                        previousY = y
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasHorizontal = swipeMode == 2
                val offset = swipeDragX
                swipeMode = 0
                swipeDragX = 0f
                pullRefreshDragY = 0f
                downHitKey = null
                pressedTab = null
                pressedKey = null
                if (wasHorizontal) snapSlideBack(offset) else invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val fallbackHit = downHitKey
                downHitKey = null
                if (pressedTab != null || pressedKey != null) {
                    pressedTab = null
                    pressedKey = null
                    invalidate()
                }
                if (pullRefreshDragY >= 44f) {
                    pullRefreshDragY = 0f
                    swipeMode = 0
                    swipeDragX = 0f
                    invalidate()
                    triggerPullRefresh()
                    return true
                }
                if (pullRefreshDragY > 0f) {
                    pullRefreshDragY = 0f
                    invalidate()
                }
                val elapsed = SystemClock.uptimeMillis() - touchDownAt
                if (swipeMode == 2) {
                    val drag = swipeDragX
                    swipeMode = 0
                    swipeDragX = 0f
                    val passed = abs(drag) >= 48f || (elapsed < 260L && abs(drag) >= 22f)
                    val switched = if (passed) stepHorizontalPage(if (drag < 0f) 1 else -1) else false
                    if (!switched) snapSlideBack(drag)
                    return true
                }
                val upDx = x - touchStartX
                val upDy = y - touchStartY
                val sheetOpenUp = (screen == Screen.DETAIL && detailSheetMode != null) ||
                    (screen == Screen.STORE_PROFILE && storeRatingsSheetOpen)
                val touchInPinnedHeader = !sheetOpenUp && touchStartY <= 68f
                val touchInStickyFooter =
                    (screen == Screen.DETAIL && touchStartY >= hVirtual - 64f) ||
                        ((screen == Screen.QRIS || screen == Screen.CHECKOUT) && touchStartY >= hVirtual - 84f)
                if (!sheetOpenUp && swipeMode == 0 && !moved && !adminNotifOpen && !touchInPinnedHeader && !touchInStickyFooter &&
                    canSwipeHorizontal() && elapsed < 280L && abs(upDx) >= 32f && abs(upDx) >= abs(upDy) * 1.15f
                ) {
                    stepHorizontalPage(if (upDx < 0f) 1 else -1)
                    return true
                }
                swipeMode = 0
                swipeDragX = 0f
                if (moved || abs(upDx) > 22f || abs(upDy) > 22f) return true
                val hit = bounds.hitTest(x, y) ?: fallbackHit ?: return true
                when {
                    // --- Fondasi: onboarding, pilihan role, autentikasi ---
                    hit == "welcome" -> go(Screen.LOGIN)
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
                    // --- Pembeli (buyer): pencarian, katalog, toko, keranjang, checkout ---
                    hit == "search" -> activity()?.inlineSearch?.open(InlineSearchOverlay.TARGET_MENU)
                    hit == "clearSearch" -> {
                        if (inlineSearchActiveTarget == InlineSearchOverlay.TARGET_MENU) {
                            activity()?.inlineSearch?.clearText()
                        } else {
                            applyQuery("")
                        }
                    }
                    hit == "openStoreSearch" -> activity()?.let { StoreSearchViewModel.openStoreDiscovery(it) }
                    hit == "storeSearchInput" -> activity()?.inlineSearch?.open(InlineSearchOverlay.TARGET_STORE)
                    hit == "storeSearchClear" -> {
                        if (inlineSearchActiveTarget == InlineSearchOverlay.TARGET_STORE) {
                            activity()?.inlineSearch?.clearText()
                        } else {
                            activity()?.let { StoreSearchViewModel.onQueryChanged(it, "", immediate = true) }
                        }
                    }
                    hit == "storeSearchReset" -> {
                        storeSearchCategory = "All"
                        activity()?.let { StoreSearchViewModel.onQueryChanged(it, "", immediate = true) }
                    }
                    hit == "storeSearchRetry" -> activity()?.let { StoreSearchViewModel.triggerSearch(it, immediate = true) }
                    hit == "storeSearchMore" -> activity()?.let { StoreSearchViewModel.loadNextPage(it) }
                    hit.startsWith("storeSearchCat:") ->
                        activity()?.let { StoreSearchViewModel.onCategorySelected(it, hit.substringAfter(':')) }
                    hit.startsWith("openStore:") ->
                        activity()?.let { StoreProfileViewModel.openStoreProfile(it, hit.substringAfter(':')) }
                    hit == "storeProfRetry" -> activity()?.let { StoreProfileViewModel.loadStoreProfile(it) }
                    hit == "storeOpenRatings" -> animateStoreRatingsSheet(true)
                    hit == "storeCloseRatings" -> animateStoreRatingsSheet(false)
                    hit == "storeRatingsNoop" -> Unit
                    hit.startsWith("storeProfCat:") ->
                        activity()?.let { StoreProfileViewModel.selectCategoryFilter(it, hit.substringAfter(':')) }
                    hit == "buyerRefresh" -> activity()?.refreshProducts(manual = true)
                    hit == "homeProfile" -> selectBuyerTab("Profile")
                    hit.startsWith("fav:") -> activity()?.toggleFavorite(hit.substringAfter(':'))
                    hit == "cartCheckout" || hit == "cartCheckoutBar" -> {
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
                            ?: storeProfileProducts.firstOrNull { it.id == id }
                            ?: sellerProducts.firstOrNull { it.id == id }
                        quantity = 1
                        detailSheetMode = null
                        detailSheetScroll = 0f
                        detailPhotoIndex = 0
                        detailSelectedVariants.clear()
                        selectedProduct?.activeVariantSections?.forEachIndexed { idx, sec ->
                            sec.options.firstOrNull()?.let { firstOpt ->
                                detailSelectedVariants[idx] = firstOpt
                            }
                        }
                        if (selectedProduct != null) go(Screen.DETAIL)
                    }
                    hit == "detailSheetNoop" -> Unit
                    hit.startsWith("detailPhoto:") -> {
                        val idx = hit.substringAfter(':').toIntOrNull()
                        val count = selectedProduct?.allImageUrls?.size ?: 0
                        if (idx != null && count > 0) {
                            detailPhotoIndex = idx.coerceIn(0, count - 1)
                            invalidate()
                        }
                    }
                    hit == "detailPhotoPrev" -> {
                        val count = selectedProduct?.allImageUrls?.size ?: 0
                        if (count > 1) {
                            detailPhotoIndex = (detailPhotoIndex - 1 + count) % count
                            invalidate()
                        }
                    }
                    hit == "detailPhotoNext" -> {
                        val count = selectedProduct?.allImageUrls?.size ?: 0
                        if (count > 1) {
                            detailPhotoIndex = (detailPhotoIndex + 1) % count
                            invalidate()
                        }
                    }
                    hit == "detailSheetClose" -> {
                        detailSheetMode = null
                        detailSheetScroll = 0f
                        invalidate()
                    }
                    hit.startsWith("detailOpenSheet:") -> {
                        val mode = hit.substringAfter(':')
                        detailSheetMode = if (mode == "buy") "buy" else "cart"
                        detailSheetScroll = 0f
                        invalidate()
                    }
                    hit.startsWith("detailSheetConfirm:") -> {
                        val mode = hit.substringAfter(':')
                        selectedProduct?.let { product ->
                            val sections = product.activeVariantSections
                            val variantLabel = if (sections.isEmpty()) {
                                ""
                            } else {
                                sections.mapIndexed { idx, sec ->
                                    val chosen = detailSelectedVariants[idx] ?: sec.options.firstOrNull().orEmpty()
                                    "${sec.name}: $chosen"
                                }.joinToString(" · ")
                            }
                            detailSheetMode = null
                            detailSheetScroll = 0f
                            invalidate()
                            if (mode == "buy") {
                                activity()?.addToCart(product.id, quantity, variantLabel) {
                                    activity()?.openCheckout()
                                }
                            } else {
                                activity()?.addToCart(product.id, quantity, variantLabel)
                            }
                        }
                    }
                    hit.startsWith("detailVar:") -> {
                        val parts = hit.removePrefix("detailVar:").split(":")
                        val secIdx = parts.getOrNull(0)?.toIntOrNull()
                        val optIdx = parts.getOrNull(1)?.toIntOrNull()
                        val prod = selectedProduct
                        if (secIdx != null && optIdx != null && prod != null) {
                            val opt = prod.activeVariantSections.getOrNull(secIdx)?.options?.getOrNull(optIdx)
                            if (opt != null) {
                                detailSelectedVariants[secIdx] = opt
                                invalidate()
                            }
                        }
                    }
                    hit.startsWith("add:") -> {
                        activity()?.addToCart(hit.substringAfter(':'))
                    }
                    hit == "detailAdd" -> {
                        selectedProduct?.let { product ->
                            val sections = product.activeVariantSections
                            val variantLabel = if (sections.isEmpty()) {
                                ""
                            } else {
                                sections.mapIndexed { idx, sec ->
                                    val chosen = detailSelectedVariants[idx] ?: sec.options.firstOrNull().orEmpty()
                                    "${sec.name}: $chosen"
                                }.joinToString(" · ")
                            }
                            activity()?.addToCart(product.id, quantity, variantLabel)
                        }
                        goBack()
                    }
                    hit.startsWith("askSeller:") -> {
                        activity()?.openProductInquiryChat(hit.substringAfter(':'))
                    }
                    hit.startsWith("chatStore:") -> {
                        activity()?.openStoreChat(hit.substringAfter(':'))
                    }
                    hit.startsWith("chatOrder:") -> {
                        activity()?.openOrderChat(hit.substringAfter(':'))
                    }
                    hit.startsWith("chatConvo:") -> {
                        activity()?.openConversationById(hit.substringAfter(':'))
                    }
                    hit.startsWith("chatFilter:") -> {
                        chatListFilter = hit.substringAfter(':')
                        scroll = 0f
                        invalidate()
                    }
                    hit == "chatRefresh" || hit == "chatRetry" -> {
                        activity()?.refreshChatList(silent = false)
                    }
                    hit == "chatLoadMore" -> {
                        activity()?.loadMoreConversations()
                    }
                    hit.startsWith("cartPlus:") -> activity()?.setCartQty(hit.substringAfter(':'), (cart[hit.substringAfter(':')] ?: 0) + 1)
                    hit.startsWith("cartMinus:") -> activity()?.setCartQty(hit.substringAfter(':'), (cart[hit.substringAfter(':')] ?: 0) - 1)
                    hit.startsWith("cartRemove:") -> activity()?.setCartQty(hit.substringAfter(':'), 0)
                    hit.startsWith("payMethod:") -> activity()?.setCheckoutPayment(hit.substringAfter(':'))
                    hit.startsWith("orderMode:") -> activity()?.setCheckoutOrderMode(hit.substringAfter(':'))
                    hit == "pickPoDate" || hit == "pickPoDateCard" || hit == "pickPoDateMore" -> activity()?.showPreOrderDatePicker()
                    hit.startsWith("poQuickDate:") -> activity()?.setCheckoutPickupDate(hit.substringAfter(':'))
                    hit.startsWith("pickupSession:") -> {
                        checkoutPickupSession = hit.substringAfter(':')
                        invalidate()
                    }
                    hit == "deleteAccount" -> activity()?.showDeleteAccountFlow(fromSellerProfile = false)
                    hit == "deleteSellerAccount" -> activity()?.showDeleteAccountFlow(fromSellerProfile = true)
                    hit == "switchToSeller" -> activity()?.switchToSellerMode()
                    hit == "switchToBuyer" -> activity()?.switchToBuyerMode()
                    hit == "activateSeller" -> activity()?.showSellerOtpActivationDialog()
                    hit == "openSellerMenu" -> {
                        sellerMenuFilter = "Semua"
                        go(Screen.SELLER_MENU)
                        activity()?.refreshSellerProducts()
                    }
                    hit.startsWith("sellerMenuFilter:") -> {
                        sellerMenuFilter = hit.substringAfter(':')
                        scroll = 0f
                        invalidate()
                    }
                    hit.startsWith("pfSchedule:") -> {
                        productOrderSchedule = hit.substringAfter(':')
                        invalidate()
                    }
                    hit == "pfPickPoDate" -> activity()?.showSellerProductPoDatePicker()
                    hit == "pfClearPoDates" -> {
                        productPoDates.clear()
                        invalidate()
                    }
                    hit.startsWith("pfTogglePoDate:") -> {
                        val iso = hit.substringAfter(':')
                        if (iso in productPoDates) {
                            productPoDates.remove(iso)
                        } else if (productPoDates.size < 14) {
                            productPoDates.add(iso)
                        }
                        invalidate()
                    }
                    hit.startsWith("pfRemovePoDate:") -> {
                        productPoDates.remove(hit.substringAfter(':'))
                        invalidate()
                    }
                    hit.startsWith("pfCutoff:") -> {
                        productPoCutoffHour = hit.substringAfter(':').toIntOrNull()?.coerceIn(12, 23) ?: 20
                        invalidate()
                    }
                    hit == "pfPoQuota" -> activity()?.showProductTextField("po_quota")
                    hit == "pfPoRequireQris" -> {
                        productPoRequireQris = !productPoRequireQris
                        invalidate()
                    }
                    hit.startsWith("pfSession:") -> {
                        val s = hit.substringAfter(':')
                        if (s in productPickupSessions) {
                            if (productPickupSessions.size > 1) productPickupSessions.remove(s)
                        } else {
                            productPickupSessions.add(s)
                        }
                        invalidate()
                    }
                    hit.startsWith("adminSchedule:") -> {
                        adminScheduleFilter = hit.substringAfter(':')
                        scroll = 0f
                        invalidate()
                    }
                    hit == "placeOrder" -> if (!placingOrder) activity()?.placeCartOrder()
                    hit == "midtransSaveQr" -> activity()?.saveQrisBitmapToGallery()
                    hit == "midtransCopyQr" -> activity()?.copyMidtransQrCode()
                    hit == "midtransCheckStatus" -> activity()?.checkGatewayPaymentStatus()
                    hit == "midtransOpenSnap" -> activity()?.openMidtransSnapCheckout()
                    hit == "midtransOpenSimulator" -> activity()?.openMidtransSandboxSimulator()
                    hit == "midtransRetryCreate" -> activity()?.retryCreateGatewayPayment()
                    hit.startsWith("viewProof:") -> {
                        val targetOrderId = hit.substringAfter(':')
                        val url = adminDetailOrder?.takeIf { it.id == targetOrderId }?.paymentProofUrl
                            ?: myOrders.firstOrNull { it.id == targetOrderId }?.paymentProofUrl
                            ?: adminOrders.firstOrNull { it.id == targetOrderId }?.paymentProofUrl
                        if (!url.isNullOrBlank()) {
                            activity()?.showPaymentProofPreview(url, "Bukti Transfer")
                        }
                    }
                    hit.startsWith("myOrderPaid:") -> activity()?.openPaymentProofForOrder(hit.substringAfter(':'))
                    hit.startsWith("buyerCancel:") -> activity()?.cancelBuyerOrder(hit.substringAfter(':'))
                    hit.startsWith("rateOrder:") -> activity()?.showOrderRatingDialogById(hit.substringAfter(':'))
                    hit.startsWith("reportOrder:") -> activity()?.showReportSellerFromOrder(hit.substringAfter(':'))
                    hit.startsWith("reportStore:") -> activity()?.showReportSellerFromStore(hit.substringAfter(':'))
                    hit.startsWith("buyerOrder:") -> {
                        selectedBuyerOrderId = hit.substringAfter(':')
                        go(Screen.READY)
                    }
                    // --- Profil, EXP, dan pengaturan warung ---
                    hit == "profileAvatar" -> activity()?.showBuyerAvatarOptions()
                    hit == "profilePhone" -> activity()?.showPhoneEditor()
                    hit == "profileStallRoom" -> activity()?.showStallRoomEdit()
                    hit.startsWith("copyPhone:") -> activity()?.copyPhoneToClipboard(hit.substringAfter(':'))
                    hit.startsWith("dialPhone:") -> activity()?.openDialer(hit.substringAfter(':'))
                    hit == "expHistory" -> activity()?.openExpHistory()
                    hit == "expMore" -> activity()?.loadMoreExp()
                    hit == "expRetry" -> activity()?.loadExpPage()
                    hit == "profileMidtrans" -> activity()?.showSellerMidtransInfo()
                    hit == "profileStallName" -> activity()?.showStallNameEdit()
                    hit == "profileStorePublic" -> {
                        val sid = myStallId?.takeIf { it.isNotBlank() }
                        if (sid != null) {
                            activity()?.let { StoreProfileViewModel.openStoreProfile(it, sid) }
                        } else {
                            android.widget.Toast.makeText(context, "Lengkapi nama warung terlebih dahulu.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    hit == "profileStoreOpenToggle" -> activity()?.toggleMyStoreOpenStatus()
                    hit == "profileStoreBio" -> activity()?.showSellerStoreBioEdit()
                    hit == "profileStoreCategory" -> activity()?.showSellerStoreCategoryPicker()
                    hit == "profileStoreLogo" -> activity()?.showSellerStoreImageOptions(isBanner = false)
                    hit == "profileStoreBanner" -> activity()?.showSellerStoreImageOptions(isBanner = true)
                    hit.startsWith("tab:") -> selectBuyerTab(hit.removePrefix("tab:"))
                    // --- Sesi bersama ---
                    hit == "logout" -> activity()?.logout()
                    hit == "devBell" -> activity()?.openNotif()
                    // --- Penjual (seller): dashboard, pesanan, produk ---
                    hit == "adminRefresh" -> activity()?.refreshAdminOrders()
                    hit.startsWith("stab:") -> selectSellerTab(hit.removePrefix("stab:"))
                    hit.startsWith("productEdit:") -> activity()?.showProductEditForm(hit.substringAfter(':'))
                    hit.startsWith("productDelete:") -> activity()?.confirmDeleteProduct(hit.substringAfter(':'))
                    hit == "adminBell" -> activity()?.openNotif()
                    hit == "adminNotifClose" || hit == "adminNotifCloseBtn" -> { adminNotifOpen = false; invalidate() }
                    hit == "adminProfile" -> {
                        if (SupabaseRepository.hasSession()) {
                            isSellerProfile = true
                            go(Screen.PROFILE)
                            activity()?.refreshSellerSettings()
                    activity()?.refreshSellerProducts()
                        } else {
                            android.widget.Toast.makeText(context, "Masuk dulu untuk membuka profil.", android.widget.Toast.LENGTH_SHORT).show()
                            activity()?.showAuthForm(false)
                        }
                    }
                    hit.startsWith("adminFilter:") -> { adminFilter = hit.substringAfter(':'); scroll = 0f; invalidate() }
                    hit.startsWith("sellerPipeline:") -> {
                        adminFilter = hit.substringAfter(':')
                        sellerTab = "Pesanan"
                        scroll = 0f
                        beginSlide(1f)
                        invalidate()
                    }
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
                        val detail = adminDetailOrder
                        if (detail != null) {
                            val lockMsg = sellerPoAdvanceBlockReason(detail, status)
                            if (lockMsg != null) {
                                android.widget.Toast.makeText(context, lockMsg, android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                adminDetailOrder = detail.copy(status = status)
                                invalidate()
                                activity()?.setAdminOrderStatus(detail.id, status)
                            }
                        }
                    }
                    hit.startsWith("adminStatus:") -> {
                        val orderId = hit.substringAfter(':')
                        val current = adminOrders.firstOrNull { it.id == orderId }
                        val next = current?.let { statusNextAction(it.status)?.second }
                        if (current != null && next != null) {
                            val lockMsg = sellerPoAdvanceBlockReason(current, next)
                            if (lockMsg != null) {
                                android.widget.Toast.makeText(context, lockMsg, android.widget.Toast.LENGTH_LONG).show()
                                if (lockMsg.contains("QRIS", ignoreCase = true)) {
                                    activity()?.openOrderDetail(orderId)
                                }
                            } else {
                                // Optimis: status kartu langsung bergerak; server mengonfirmasi lewat refresh.
                                adminOrders = adminOrders.map { if (it.id == orderId) it.copy(status = next) else it }
                                invalidate()
                                activity()?.setAdminOrderStatus(orderId, next)
                            }
                        }
                    }
                    hit.startsWith("adminCancel:") -> activity()?.cancelAdminOrder(hit.substringAfter(':'))
                    hit.startsWith("payPaid:") -> activity()?.markOrderPaid(hit.substringAfter(':'))
                    hit.startsWith("payReject:") -> activity()?.rejectOrderPayment(hit.substringAfter(':'))
                    hit == "productAdd" || hit == "productAddHeader" -> activity()?.showProductForm()
                    hit.startsWith("productToggle:") -> activity()?.toggleProductAvailability(hit.substringAfter(':'))
                    hit == "productSave" -> activity()?.saveProduct()
                    hit == "pfName" -> activity()?.showProductTextField("nama")
                    hit == "pfPrice" -> activity()?.showProductTextField("harga")
                    hit == "pfStock" -> activity()?.showProductTextField("stok")
                    hit == "pfDesc" -> activity()?.showProductTextField("deskripsi")
                    hit == "pfIngredients" -> activity()?.showProductTextField("bahan")
                    hit == "pfAddVarSec" || hit == "pfAddVarSecBottom" -> activity()?.showAddVariantSectionDialog()
                    hit.startsWith("pfEditVarSecName:") -> {
                        hit.substringAfter(':').toIntOrNull()?.let { activity()?.showRenameVariantSectionDialog(it) }
                    }
                    hit.startsWith("pfRemoveVarSec:") -> {
                        hit.substringAfter(':').toIntOrNull()?.let { activity()?.removeVariantSection(it) }
                    }
                    hit.startsWith("pfAddVarOpt:") -> {
                        hit.substringAfter(':').toIntOrNull()?.let { activity()?.showAddVariantOptionDialog(it) }
                    }
                    hit.startsWith("pfRemoveVarOpt:") -> {
                        val parts = hit.substringAfter(':').split(':')
                        val secIdx = parts.getOrNull(0)?.toIntOrNull()
                        val optIdx = parts.getOrNull(1)?.toIntOrNull()
                        if (secIdx != null && optIdx != null) {
                            activity()?.removeVariantOption(secIdx, optIdx)
                        }
                    }
                    hit == "pfCategory" -> activity()?.showProductCategoryPicker()
                    hit == "pfRoom" -> activity()?.showProductTextField("ruangan")
                    hit.startsWith("pfRoomChip:") -> {
                        productRoom = hit.substringAfter(':')
                        productError = ""
                        invalidate()
                    }
                    hit.startsWith("pfCat:") -> {
                        productCategory = hit.substringAfter(':')
                        invalidate()
                    }
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
                    hit == "checkOta" -> activity()?.checkForOtaUpdate(silent = false)
                    hit == "devPublishOta" -> activity()?.showDevOtaPublishForm()
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
                    // --- Pengaturan bersama ---
                    hit == "theme" -> activity()?.showThemePicker()

                    // --- Developer / super admin ---
                    hit == "devSearch" -> activity()?.inlineSearch?.open(InlineSearchOverlay.TARGET_DEV)
                    hit == "devSearchClear" -> {
                        if (inlineSearchActiveTarget == InlineSearchOverlay.TARGET_DEV) {
                            activity()?.inlineSearch?.clearText()
                        } else {
                            devQuery = ""
                            scroll = 0f
                            invalidate()
                        }
                    }
                    hit == "devReset" -> activity()?.confirmDailyReset()
                    hit == "devResetQuotas" -> activity()?.confirmResetAllQuotas()
                    hit.startsWith("devResetUserQuota:") -> activity()?.confirmResetUserQuota(hit.substringAfter(':'))
                    hit == "devLogout" -> activity()?.logout()
                    hit == "devRefresh" -> activity()?.refreshDeveloper()
                    hit == "devBell" -> activity()?.openNotif()
                    hit == "devTheme" -> activity()?.showThemePicker()
                    hit == "devUserClose" -> { devSelectedUserId = null; invalidate() }
                    hit == "devWarnNew" -> activity()?.showWarnPicker()
                    hit == "devAnnNew" -> activity()?.showAnnouncementForm()
                    hit.startsWith("devTab:") -> selectDevTab(hit.substringAfter(':'))
                    hit.startsWith("devFilter:") -> {
                        devUserFilter = hit.substringAfter(':')
                        devSelectedUserId = null
                        scroll = 0f
                        invalidate()
                    }
                    hit.startsWith("devUser:") -> {
                        val id = hit.substringAfter(':')
                        devSelectedUserId = if (devSelectedUserId == id) null else id
                        invalidate()
                    }
                    hit.startsWith("devWarn:") -> activity()?.showWarningForm(hit.substringAfter(':'))
                    hit.startsWith("devActive:") -> activity()?.setDevUserStatus(hit.substringAfter(':'), "active")
                    hit.startsWith("devSuspend:") -> activity()?.setDevUserStatus(hit.substringAfter(':'), "suspended")
                    hit.startsWith("devStall:") -> {
                        val parts = hit.substringAfter(':').split(':')
                        if (parts.size == 2) activity()?.toggleDevStall(parts[0], parts[1].toBoolean())
                    }
                    hit.startsWith("devVerifyStall:") -> {
                        val parts = hit.substringAfter(':').split(':')
                        if (parts.size == 2) activity()?.showDevStallVerificationDialog(parts[0], parts[1].toBoolean())
                    }
                    hit.startsWith("devOrder:") -> {
                        val id = hit.substringAfter(':')
                        val opening = devExpandedOrderId != id
                        devExpandedOrderId = if (opening) id else null
                        devOrderExpandAt = if (opening && motionEnabled()) android.os.SystemClock.uptimeMillis() else 0L
                        invalidate()
                    }
                    hit.startsWith("devReportFilter:") -> {
                        devReportFilter = hit.substringAfter(':')
                        devSelectedReportId = null
                        scroll = 0f
                        invalidate()
                    }
                    hit.startsWith("devReportRow:") -> {
                        val id = hit.substringAfter(':')
                        devSelectedReportId = if (devSelectedReportId == id) null else id
                        invalidate()
                    }
                    hit.startsWith("devReportImg:") -> {
                        val parts = hit.removePrefix("devReportImg:").split(":", limit = 2)
                        val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Bukti Laporan"
                        val url = parts.getOrNull(1).orEmpty()
                        if (url.startsWith("https://")) {
                            activity()?.showPaymentProofPreview(url, title)
                        }
                    }
                    hit.startsWith("devReportStatus:") -> {
                        val parts = hit.removePrefix("devReportStatus:").split(":")
                        val reportId = parts.getOrNull(0).orEmpty()
                        val nextStatus = parts.getOrNull(1).orEmpty()
                        if (reportId.isNotBlank() && nextStatus.isNotBlank()) {
                            activity()?.updateDevSellerReportStatus(reportId, nextStatus)
                        }
                    }
                    hit == "plus" -> {
                        val maxQty = selectedProduct?.stock?.takeIf { it > 0 }?.coerceAtMost(99) ?: 99
                        if (quantity < maxQty) {
                            quantity++
                            invalidate()
                        } else {
                            android.widget.Toast.makeText(context, "Maksimal $maxQty porsi tersedia.", android.widget.Toast.LENGTH_SHORT).show()
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

    internal fun resolveOrderItemProduct(menuItemId: String?, itemName: String): ProductRow? {
        if (!menuItemId.isNullOrBlank()) {
            sellerProducts.firstOrNull { it.id == menuItemId }?.let { return it }
            products.firstOrNull { it.id == menuItemId }?.let { return it }
            storeProfileProducts.firstOrNull { it.id == menuItemId }?.let { return it }
        }
        val clean = itemName.trim()
        if (clean.isNotEmpty()) {
            sellerProducts.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
            products.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
