package com.jajanin.app

import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.inbox.*
import com.jajanin.app.feature.chat.*

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.core.constants.LOCATION_OPTIONS
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.LiquidGlassNavHost
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.showThemedInfo
import com.jajanin.app.core.ui.showThemedOptions
import com.jajanin.app.core.update.OtaPublishDraft
import com.jajanin.app.core.update.OtaRelease
import com.jajanin.app.core.update.checkForOtaUpdate
import com.jajanin.app.core.update.publishOtaApkFromUri
import com.jajanin.app.core.update.resumePendingOtaIfPermitted
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.feature.buyer.home.normalizeCategory
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.DailyResetResult
import com.jajanin.app.data.repository.DevAnnouncementRow
import com.jajanin.app.data.repository.DevStats
import com.jajanin.app.data.repository.DevUser
import com.jajanin.app.data.repository.DevWarningRow
import com.jajanin.app.data.repository.DeveloperRepository
import com.jajanin.app.data.repository.OnlinePresenceSession
import com.jajanin.app.data.repository.OrderObserver
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.PhoneRepository
import com.jajanin.app.data.repository.PlacedOrder
import com.jajanin.app.data.repository.ProductDraft
import com.jajanin.app.data.repository.RegisterResult
import com.jajanin.app.data.repository.SellerInvite
import com.jajanin.app.data.repository.StallInfo
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.auth.NativeLoginOverlay
import com.jajanin.app.feature.seller.menu.NativeProductFormOverlay
import com.jajanin.app.feature.buyer.exp.EXP_MAX_LEVEL
import com.jajanin.app.feature.buyer.exp.ExpEntry
import com.jajanin.app.feature.buyer.exp.ExpRepository
import com.jajanin.app.feature.buyer.exp.expLevel
import com.jajanin.app.feature.buyer.exp.expNextAt
import com.jajanin.app.notifications.NotificationHelper
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : Activity() {
    internal lateinit var ui: JajaninView

    /** Akses tema gelap untuk helper dialog bertema. */
    internal fun isDarkMode(): Boolean = ui.darkMode
    internal val authScope = CoroutineScope(Dispatchers.Main)
    internal var inlineSearch: InlineSearchOverlay? = null
    internal lateinit var loginOverlay: NativeLoginOverlay
    internal lateinit var productFormOverlay: NativeProductFormOverlay
    internal var pendingPasswordRecovery = false
    internal var adminObserver: OrderObserver? = null
    internal var adminRefreshJob: Job? = null
    internal var adminStatusBusy = false
    internal var productObserver: OrderObserver? = null
    internal var productRefreshJob: Job? = null
    internal val REQUEST_PRODUCT_IMAGE = 8301
    internal val REQUEST_QRIS_IMAGE = 8302
    internal val REQUEST_OTA_APK = 8303
    internal val REQUEST_PAYMENT_PROOF = 8304
    internal val REQUEST_STORE_LOGO = 8305
    internal val REQUEST_STORE_BANNER = 8306
    internal val REQUEST_PROFILE_AVATAR = 8307
    internal val REQUEST_REPORT_EVIDENCE_1 = 8308
    internal val REQUEST_REPORT_EVIDENCE_2 = 8309
    internal var activeSellerReport: com.jajanin.app.feature.buyer.ActiveSellerReportHolder? = null
    internal var pendingOtaRelease: OtaRelease? = null
    internal var pendingOtaPublishDraft: OtaPublishDraft? = null
    internal var otaPromptedVersion: Int = -1
    internal var otaDownloadJob: Job? = null
    internal var autoRefreshJob: Job? = null
    internal var buyerOrdersSeeded = false
    internal val knownBuyerOrderStatuses = HashMap<String, String>()
    internal val knownBuyerPaymentStatuses = HashMap<String, String>()
    internal var sellerOrdersSeeded = false
    internal val knownSellerOrderIds = HashSet<String>()
    internal val knownSellerPaymentStatuses = HashMap<String, String>()

    private fun notifStatePrefs() = getSharedPreferences("jajanin_order_notif_state", MODE_PRIVATE)

    internal fun recordBuyerOrderSeed(orderId: String, status: String, paymentStatus: String) {
        val uid = SupabaseRepository.currentUid().orEmpty()
        val normStatus = status.lowercase()
        val normPay = paymentStatus.lowercase()
        knownBuyerOrderStatuses[orderId] = normStatus
        knownBuyerPaymentStatuses[orderId] = normPay
        buyerOrdersSeeded = true
        if (uid.isNotBlank()) {
            notifStatePrefs().edit()
                .putBoolean("buyer_seeded:$uid", true)
                .putString("buyer_status:$uid:$orderId", normStatus)
                .putString("buyer_pay:$uid:$orderId", normPay)
                .apply()
        }
    }

    internal fun resetOrderNotificationState() {
        buyerOrdersSeeded = false
        knownBuyerOrderStatuses.clear()
        knownBuyerPaymentStatuses.clear()
        sellerOrdersSeeded = false
        knownSellerOrderIds.clear()
        knownSellerPaymentStatuses.clear()
    }

    internal fun detectAndNotifyBuyerOrderChanges(orders: List<AdminOrder>) {
        val uid = SupabaseRepository.currentUid().orEmpty()
        val prefs = notifStatePrefs()
        val wasSeeded = buyerOrdersSeeded || (uid.isNotBlank() && prefs.getBoolean("buyer_seeded:$uid", false))
        if (!wasSeeded) {
            val editor = prefs.edit()
            if (uid.isNotBlank()) editor.putBoolean("buyer_seeded:$uid", true)
            orders.forEach { order ->
                val st = order.status.lowercase()
                val pay = order.paymentStatus.lowercase()
                knownBuyerOrderStatuses[order.id] = st
                knownBuyerPaymentStatuses[order.id] = pay
                if (uid.isNotBlank()) {
                    editor.putString("buyer_status:$uid:${order.id}", st)
                    editor.putString("buyer_pay:$uid:${order.id}", pay)
                }
            }
            editor.apply()
            buyerOrdersSeeded = true
            return
        }
        buyerOrdersSeeded = true
        val editor = prefs.edit()
        var statusChangedCount = 0
        orders.forEach { order ->
            val newStatus = order.status.lowercase()
            val persistedStatus = if (uid.isNotBlank()) prefs.getString("buyer_status:$uid:${order.id}", null) else null
            val oldStatus = knownBuyerOrderStatuses[order.id] ?: persistedStatus
            knownBuyerOrderStatuses[order.id] = newStatus
            if (uid.isNotBlank()) editor.putString("buyer_status:$uid:${order.id}", newStatus)

            val newPay = order.paymentStatus.lowercase()
            val persistedPay = if (uid.isNotBlank()) prefs.getString("buyer_pay:$uid:${order.id}", null) else null
            val oldPay = knownBuyerPaymentStatuses[order.id] ?: persistedPay
            knownBuyerPaymentStatuses[order.id] = newPay
            if (uid.isNotBlank()) editor.putString("buyer_pay:$uid:${order.id}", newPay)

            if (oldStatus != null && oldStatus != newStatus) {
                statusChangedCount++
                val store = order.sellerStoreName.takeIf { it.isNotBlank() } ?: "Penjual"
                val roomSuffix = order.pickupRoom.takeIf { it.isNotBlank() }?.let { " di $it" }.orEmpty()
                val title = when (newStatus) {
                    STATUS_CONFIRMED -> "Pesanan #${order.pickupCode} Dikonfirmasi"
                    STATUS_PREPARING, "processing" -> "Pesanan #${order.pickupCode} Sedang Disiapkan"
                    STATUS_READY -> "Pesanan #${order.pickupCode} Siap Diambil!"
                    STATUS_COMPLETED -> "Pesanan #${order.pickupCode} Selesai"
                    STATUS_CANCELLED -> "Pesanan #${order.pickupCode} Dibatalkan"
                    else -> "Status Pesanan #${order.pickupCode} Diperbarui"
                }
                val body = when (newStatus) {
                    STATUS_CONFIRMED -> "$store telah menerima pesananmu (${order.itemsSummary})."
                    STATUS_PREPARING, "processing" -> "$store sedang menyiapkan pesananmu."
                    STATUS_READY -> "Pesananmu sudah siap diambil$roomSuffix. Tunjukkan kode #${order.pickupCode}."
                    STATUS_COMPLETED -> "Terima kasih! Pesanan #${order.pickupCode} telah selesai."
                    STATUS_CANCELLED -> {
                        val reasonLine = order.cancelReason.takeIf { it.isNotBlank() }?.let { " Alasan: $it." }.orEmpty()
                        "Pesanan #${order.pickupCode} dibatalkan oleh $store.$reasonLine Kuota pesananmu telah dikembalikan."
                    }
                    else -> "Status pesanan #${order.pickupCode} berubah menjadi $newStatus."
                }
                if (newStatus == STATUS_CANCELLED) {
                    refreshOrderingStatus()
                }
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:status:$newStatus",
                    orderId = order.id,
                    title = title,
                    body = body,
                )
            }

            if (oldPay != null && oldPay != newPay && (newPay == "paid" || newPay == "rejected")) {
                statusChangedCount++
                val title = if (newPay == "paid") {
                    "Pembayaran #${order.pickupCode} Lunas"
                } else {
                    "Pembayaran #${order.pickupCode} Ditolak"
                }
                val body = if (newPay == "paid") {
                    if (order.paymentMethod.equals("qris", ignoreCase = true)) {
                        "Pembayaran Midtrans untuk pesanan #${order.pickupCode} sudah terkonfirmasi lunas otomatis."
                    } else {
                        "Pembayaran tunai pesanan #${order.pickupCode} telah ditandai lunas oleh penjual."
                    }
                } else {
                    "Pembayaran pesanan #${order.pickupCode} belum berhasil. Silakan coba lagi."
                }
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:pay:$newPay",
                    orderId = order.id,
                    title = title,
                    body = body,
                )
            }
        }
        editor.apply()
        if (statusChangedCount > 0) {
            ui.unreadOrderNotifCount += statusChangedCount
            ui.invalidate()
        }
    }

    internal fun detectAndNotifySellerOrderChanges(orders: List<AdminOrder>) {
        val uid = SupabaseRepository.currentUid().orEmpty()
        val prefs = notifStatePrefs()
        val editor = prefs.edit()
        orders.forEach { order ->
            val notifKey = "seller_notified:$uid:${order.id}"
            val alreadyNotified = knownSellerOrderIds.contains(order.id) ||
                (uid.isNotBlank() && prefs.getBoolean(notifKey, false))
            knownSellerOrderIds.add(order.id)
            if (uid.isNotBlank()) editor.putBoolean(notifKey, true)

            val payKey = "seller_pay:$uid:${order.id}"
            val newPay = order.paymentStatus.lowercase()
            val oldPay = knownSellerPaymentStatuses[order.id]
                ?: (if (uid.isNotBlank()) prefs.getString(payKey, null) else null)
            knownSellerPaymentStatuses[order.id] = newPay
            if (uid.isNotBlank()) editor.putString(payKey, newPay)

            if (!alreadyNotified && order.status.equals(STATUS_PENDING, ignoreCase = true)) {
                val summary = order.itemsSummary.ifBlank { "${order.itemCount} item" }
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:new",
                    orderId = order.id,
                    title = "Pesanan Baru #${order.pickupCode}",
                    body = "${order.buyerName} memesan $summary (${formatRupiah(order.totalIdr)})",
                )
            } else if (alreadyNotified && oldPay != null && oldPay != newPay && newPay == "paid" && order.paymentMethod.equals("qris", ignoreCase = true)) {
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:midtrans_paid",
                    orderId = order.id,
                    title = "Pembayaran Midtrans #${order.pickupCode} Lunas",
                    body = "${order.buyerName} telah menyelesaikan pembayaran (${formatRupiah(order.totalIdr)}). Pesanan siap diproses.",
                )
            }
        }
        editor.apply()
        sellerOrdersSeeded = true
    }

    internal fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = authScope.launch {
            while (true) {
                kotlinx.coroutines.delay(400L)
                if (!SupabaseRepository.isConfigured()) continue
                if (ui.myRole == "seller" && SupabaseRepository.hasSession()) {
                    silentRefreshSellerTick()
                    silentRefreshChatTick()
                } else if (ui.myRole == "buyer" || ui.myRole == null) {
                    silentRefreshBuyerTick()
                    silentRefreshChatTick()
                }
            }
        }
    }

    // Kode akses wajib untuk memasuki role penjual & developer.
    // Cukup dimasukkan SATU KALI (saat pertama); status tersimpan permanen
    // di perangkat sehingga tidak ditanyakan lagi walau logout/restart.
    internal var sellerUnlocked: Boolean
        get() = appPrefs().getBoolean(PREF_ACCESS_SELLER, false)
        set(value) { appPrefs().edit().putBoolean(PREF_ACCESS_SELLER, value).apply() }
    internal var developerUnlocked: Boolean
        get() = appPrefs().getBoolean(PREF_ACCESS_DEVELOPER, false)
        set(value) { appPrefs().edit().putBoolean(PREF_ACCESS_DEVELOPER, value).apply() }

    /**
     * Bebas kode akses penjual bila perangkat ini sudah unlock ATAU akun
     * diundang developer (flag server seller_invited). Seller non-undangan
     * tetap wajib kode.
     */
    internal suspend fun isSellerAdmitted(): Boolean =
        sellerUnlocked || SupabaseRepository.isSellerInvited()

    /**
     * Gerbang kode akses role istimewa. Sekali benar di perangkat ini,
     * tidak diminta lagi selamanya (tidak reset saat logout).
     */
    internal fun requirePrivilegedAccess(role: String, onGranted: () -> Unit) {
        if (role == "seller" && sellerUnlocked) { onGranted(); return }
        if (role == "developer" && developerUnlocked) { onGranted(); return }
        val label = if (role == "seller") "Penjual" else "Developer"
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Kode akses $label"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(input)
        android.app.AlertDialog.Builder(this).setTitle("Kode akses $label")
            .setMessage("Masukkan kode akses untuk memasuki role $label.")
            .setView(container)
            .setPositiveButton("Masuk") { _, _ ->
                val expected = if (role == "seller") SELLER_ACCESS_CODE else DEVELOPER_ACCESS_CODE
                if (input.text.toString().trim() == expected) {
                    if (role == "seller") sellerUnlocked = true else developerUnlocked = true
                    onGranted()
                } else {
                    Toast.makeText(this, "Kode akses salah.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @Serializable
    internal data class SellerStallId(val id: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        AppIcon.init(this)
        ui = JajaninView(this)
        loadFavorites()
        ui.setDarkMode(resolveTheme())
        ui.themeLabel = themeModeLabel()
        // Kanvas tetap menjadi konten utama; dock Liquid Glass asli hidup
        // sebagai sibling di atasnya agar bisa merefraksi halaman di belakang.
        val root = FrameLayout(this)
        root.addView(
            ui,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        LiquidGlassNavHost(this, root, ui)
        val searchOverlay = InlineSearchOverlay(this, root)
        inlineSearch = searchOverlay
        searchOverlay.applyTheme()
        ui.inlineSearchBoundsListener = { rect, scale, textSize ->
            inlineSearch?.syncCanvasBounds(rect, scale, textSize)
        }
        loginOverlay = NativeLoginOverlay(this, root)
        productFormOverlay = NativeProductFormOverlay(this, root)
        ui.screenChangedListener = { screen ->
            inlineSearch?.dismiss()
            val isLogin = screen == Screen.LOGIN
            val isProductForm = screen == Screen.PRODUCT_FORM
            if (isLogin || isProductForm) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            loginOverlay.setVisible(isLogin)
            productFormOverlay.setVisible(isProductForm)
        }
        ui.loginThemeListener = { dark ->
            loginOverlay.applyTheme(dark)
            productFormOverlay.applyTheme(dark)
        }
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(root)
        NotificationHelper.ensureChannel(this)
        requestNotificationPermission()
        startProductUpdates()
        startMyOrdersUpdates()
        refreshProducts()
        refreshMyOrders()
        checkForOtaUpdate(silent = true)
        handleAuthLink(intent)
        restoreSessionIfNeeded()
        handleOrderNotification(intent)
    }

    /** Izin notifikasi runtime (Android 13+); diamkan bila ditolak — pipeline order tidak terganggu. */
    internal fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4411)
        }
    }

    /**
     * Memulihkan session dari penyimpanan sebelum menentukan layar awal.
     *
     * Alur startup: SPLASH → session valid? dashboard : onboarding (pertama
     * kali) / welcome. Tidak pernah meminta login ulang bila session masih
     * valid; Supabase me-refresh token kedaluwarsa secara otomatis.
     */
    internal fun restoreSessionIfNeeded() {
        authScope.launch {
            if (SupabaseRepository.restoreSession()) {
                if (pendingPasswordRecovery) showSetNewPasswordDialog() else routeSignedInUser()
            } else if (pendingPasswordRecovery) {
                // Deep link recovery sedang memproses sesi lewat importDeepLinkSession.
            } else if (!isOnboarded()) ui.showOnboard()
            else ui.showWelcome()
        }
    }

    internal fun appPrefs() = getSharedPreferences(PREFS_THEME, MODE_PRIVATE)

    internal fun isOnboarded(): Boolean = appPrefs().getBoolean(PREF_ONBOARDED, false)

    /** Tutorial selesai/dilewati: tandai agar tidak muncul lagi. */
    fun finishOnboarding() {
        appPrefs().edit().putBoolean(PREF_ONBOARDED, true).apply()
        ui.showWelcome()
    }

    internal suspend fun routeSignedInUser() {
        val prof = SupabaseRepository.fetchMyAccountProfile()
        val role = prof?.role?.takeIf { it.isNotBlank() } ?: SupabaseRepository.currentRole()
        if (prof == null && role == null) {
            // Sesi lokal menunjuk ke akun yang sudah dihapus di server -> bersihkan dan kembali ke Login NIS.
            runCatching { SupabaseRepository.logout() }
            ui.myRole = null
            ui.showLogin()
            return
        }
        refreshMyStatus()
        ui.myIsSellerVerified = prof?.isSellerVerified == true || role == "seller"
        ui.mySellerVerifiedEmail = prof?.sellerVerifiedEmail.orEmpty()
        prof?.stallId?.takeIf { it.isNotBlank() }?.let { ui.myStallId = it }
        prof?.stallName?.takeIf { it.isNotBlank() }?.let { ui.myStallName = it }

        val rawDisplay = prof?.displayName?.takeIf { it.isNotBlank() } ?: SupabaseRepository.myRawDisplayName().orEmpty()
        val displayName = SupabaseRepository.extractCleanName(rawDisplay)
        val nis = prof?.nis?.takeIf { it.isNotBlank() } ?: SupabaseRepository.extractNis(rawDisplay)
        val kelas = SupabaseRepository.extractClass(rawDisplay)
        ui.myDisplayName = displayName
        ui.myNis = nis
        ui.myClass = kelas
        refreshMyAvatarUrl()
        refreshMyOrderRatings()
        if (role == "developer") {
            if (!developerUnlocked) {
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
                refreshMyOrders()
                refreshOrderingStatus()
                startInboxObserver()
                startChatObserver()
                ensureOnlinePresence("buyer")
                requirePrivilegedAccess("developer") { enterDeveloper() }
                return
            }
            ui.myRole = "developer"
            ui.showDev()
            startDeveloper()
            refreshInbox()
            pendingOrderNotificationId?.let { pendingOrderNotificationId = null }
            return
        }
        // Semua akun non-developer default masuk sebagai Pembeli (Mode Buyer);
        // penjual terverifikasi bisa bebas switch Mode Pembeli <-> Mode Penjual dari Profil.
        ui.myRole = "buyer"
        ui.isSellerProfile = false
        ui.showHome()
        refreshProducts()
        refreshMyOrders()
        refreshInbox()
        refreshOrderingStatus()
        registerMyDevice()
        startInboxObserver()
        startChatObserver()
        ensureOnlinePresence("buyer")
        pendingOrderNotificationId?.let { orderId ->
            pendingOrderNotificationId = null
            ui.selectedBuyerOrderId = orderId
            ui.lastOrderId = orderId
            ui.go(Screen.READY)
        }
        pendingChatConversationId?.let { cid ->
            pendingChatConversationId = null
            openConversationById(cid)
        }
    }

    /** Status akun sendiri (mis. suspended) untuk banner + blokir client-side. */
    internal suspend fun refreshMyStatus() {
        ui.myAccountStatus = SupabaseRepository.myAccountStatus() ?: "active"
        ui.myPhone = runCatching { PhoneRepository.myPhone() }.getOrNull().orEmpty()
        ui.invalidate()
    }

    override fun onStart() {
        super.onStart()
        // Segarkan akun + katalog pembeli tiap kembali ke aplikasi agar menu tidak tertinggal.
        authScope.launch {
            if (SupabaseRepository.hasSession()) {
                refreshMyStatus()
                ensureOnlinePresence()
            }
        }
        if (ui.myRole == "buyer") {
            refreshProducts()
            startProductUpdates()
        }
        startAutoRefreshLoop()
    }

    override fun onStop() {
        inlineSearch?.dismiss()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        stopOnlinePresence()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumePendingOtaIfPermitted()
    }

    /** Mendaftarkan token FCM perangkat ini (semua role). Gagal = diam. */
    internal fun registerMyDevice() {
        if (!SupabaseRepository.hasSession()) return
        authScope.launch {
            runCatching {
                val uid = SupabaseRepository.currentUid() ?: return@runCatching
                val token = NotificationHelper.currentFcmToken() ?: return@runCatching
                OrderRepository.registerDevice(uid, token)
            }
        }
    }

    internal var myOrdersJob: Job? = null

    /** Riwayat order pembeli sendiri (status terkini sinkron dari database). */
    internal var expJob: Job? = null

    /**
     * Muat saldo EXP + beri tahu kenaikan (toast) dan animasi badge bila naik
     * level. Aman dari koneksi lambat: refresh berurutan, saldo dari server.
     */
    @Deprecated("Legacy activity result used for the document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PRODUCT_IMAGE -> {
                val uris = buildList {
                    val clip = data?.clipData
                    if (clip != null && clip.itemCount > 0) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i)?.uri?.let { add(it) }
                        }
                    } else {
                        data?.data?.let { add(it) }
                    }
                }
                if (uris.isEmpty()) return
                uris.forEach { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                uploadProductImages(uris)
            }
            REQUEST_OTA_APK -> {
                val uri = data?.data ?: return
                publishOtaApkFromUri(uri)
            }
            REQUEST_STORE_LOGO -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                uploadSellerStoreImage(uri, isBanner = false)
            }
            REQUEST_STORE_BANNER -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                uploadSellerStoreImage(uri, isBanner = true)
            }
            REQUEST_PROFILE_AVATAR -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                uploadBuyerAvatar(uri)
            }
            REQUEST_REPORT_EVIDENCE_1 -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                onReportEvidencePicked(1, uri)
            }
            REQUEST_REPORT_EVIDENCE_2 -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                onReportEvidencePicked(2, uri)
            }
        }
    }

    /** Simpan dari halaman form (dialog selalu null; param dipertahankan kompat). */
    internal var orderingStatusJob: Job? = null

    internal var stallAvailJob: Job? = null

    /** Kembali ke pola aplikasi: seller tetap di dashboard, developer di dasbornya, pembeli pulang ke beranda. */
    internal var devObserver: OrderObserver? = null
    internal var devRefreshJob: Job? = null
    internal var inboxRefreshJob: Job? = null
    internal var chatObserver: OrderObserver? = null
    internal var chatListJob: Job? = null
    internal var activeChatDialog: ChatRoomDialog? = null
    internal var pendingChatConversationId: String? = null
    internal var onlinePresenceSession: OnlinePresenceSession? = null
    internal var onlinePresenceCleanupJob: Job? = null

    /** Nyalakan/pertahankan pelacak kehadiran live selama user login dan aplikasi terbuka. */
    internal fun ensureOnlinePresence(roleOverride: String? = null) {
        val uid = SupabaseRepository.currentUid() ?: return
        val role = roleOverride ?: ui.myRole ?: return
        val cur = onlinePresenceSession
        if (cur != null && !cur.isClosed() && cur.userId == uid) {
            cur.updateRole(role)
            return
        }
        stopOnlinePresence()
        onlinePresenceSession = DeveloperRepository.startOnlinePresence(
            scope = authScope,
            userId = uid,
            role = role,
            previousCleanup = onlinePresenceCleanupJob,
        ) { ids ->
            ui.onlineUserIds = ids
            activeChatDialog?.refreshPresenceIndicator()
            ui.invalidate()
        }
    }

    internal fun stopOnlinePresence() {
        val cur = onlinePresenceSession ?: return
        onlinePresenceSession = null
        onlinePresenceCleanupJob = cur.close()
    }

    /** Dasbor developer wajib kode akses + login DAN ber-role developer di database. */
    internal var pendingOrderNotificationId: String? = null

    override fun onDestroy() {
        autoRefreshJob?.cancel()
        otaDownloadJob?.cancel()
        chatListJob?.cancel()
        activeChatDialog?.dismiss()
        activeChatDialog = null
        stopChatObserver()
        stopOnlinePresence()
        adminObserver?.close()
        productObserver?.close()
        ui.myObserver?.close()
        devObserver?.close()
        inboxObserverClose()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
        handleOrderNotification(intent)
    }

    internal fun handleAuthLink(authIntent: Intent?) {
        val data = authIntent?.data ?: return
        if (data.scheme != "jajanin") return
        val isRecoveryLink = pendingPasswordRecovery ||
            data.toString().contains("type=recovery", ignoreCase = true) ||
            data.fragment?.contains("type=recovery", ignoreCase = true) == true
        if (isRecoveryLink) pendingPasswordRecovery = true

        SupabaseRepository.importDeepLinkSession(authIntent) {
            authScope.launch {
                if (pendingPasswordRecovery) {
                    showSetNewPasswordDialog()
                } else if (SupabaseRepository.hasSession()) {
                    routeSignedInUser()
                    Toast.makeText(this@MainActivity, "Berhasil masuk", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Alur Lupa Kata Sandi (2 jalur otomatis):
     * - Jalur 1: Kirim email reset dari Supabase Auth (berisi link `jajanin://auth-callback` + OTP).
     *   Jika user menekan link di email, app terbuka kembali & langsung minta kata sandi baru.
     * - Jalur 2: Jika email berisi kode verifikasi/OTP (6 digit), user bisa langsung masukkan
     *   kode + kata sandi baru di dalam dialog tanpa keluar aplikasi.
     */
    fun showForgotPasswordDialog(prefillEmail: String = "") {
        val self = this
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val emailInput = EditText(this).apply {
            hint = "Email akun terdaftar"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            if (prefillEmail.isNotBlank()) setText(prefillEmail.trim())
        }
        val otpInput = EditText(this).apply {
            hint = "Kode OTP dari email (6-8 digit)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            visibility = View.GONE
        }
        val newPassInput = EditText(this).apply {
            hint = "Kata sandi baru (minimal 6 karakter)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            visibility = View.GONE
        }
        val confirmPassInput = EditText(this).apply {
            hint = "Ulangi kata sandi baru"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            visibility = View.GONE
        }
        val hintText = TextView(this).apply {
            text = "Kami akan mengirim tautan & kode reset kata sandi ke emailmu."
            textSize = 12.5f
            setPadding(0, 14, 0, 8)
        }
        val toggleOtpLink = TextView(this).apply {
            text = "Sudah punya kode OTP reset?"
            textSize = 12.5f
            setTextColor(Color.rgb(0, 99, 214))
            setPadding(0, 12, 0, 4)
        }
        root.addView(emailInput)
        root.addView(otpInput)
        root.addView(newPassInput)
        root.addView(confirmPassInput)
        root.addView(hintText)
        root.addView(toggleOtpLink)

        var otpMode = false
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Lupa Kata Sandi")
            .setView(root)
            .setPositiveButton("Kirim Email Reset", null)
            .setNegativeButton("Batal", null)
            .show()

        fun applyOtpMode(enable: Boolean) {
            otpMode = enable
            otpInput.visibility = if (enable) View.VISIBLE else View.GONE
            newPassInput.visibility = if (enable) View.VISIBLE else View.GONE
            confirmPassInput.visibility = if (enable) View.VISIBLE else View.GONE
            emailInput.isEnabled = !enable
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text =
                if (enable) "Simpan Sandi Baru" else "Kirim Email Reset"
            toggleOtpLink.text =
                if (enable) "Kirim ulang ke email lain" else "Sudah punya kode OTP reset?"
            hintText.text = if (enable) {
                "Masukkan kode reset dari email (atau cukup klik tautan Reset di email untuk otomatis membuka aplikasi)."
            } else {
                "Kami akan mengirim tautan & kode reset kata sandi ke emailmu."
            }
            if (enable) otpInput.requestFocus() else emailInput.requestFocus()
        }

        toggleOtpLink.setOnClickListener { applyOtpMode(!otpMode) }

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val emailVal = emailInput.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailVal).matches()) {
                Toast.makeText(self, "Masukkan email yang valid.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!SupabaseRepository.isConfigured()) {
                Toast.makeText(self, "Supabase belum dikonfigurasi.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            if (!otpMode) {
                btn.isEnabled = false
                btn.text = "Mengirim..."
                authScope.launch {
                    try {
                        pendingPasswordRecovery = true
                        SupabaseRepository.sendPasswordReset(emailVal)
                        Toast.makeText(
                            self,
                            "Email reset dikirim ke $emailVal. Cek inbox atau folder Spam.",
                            Toast.LENGTH_LONG
                        ).show()
                        applyOtpMode(true)
                        btn.isEnabled = true
                    } catch (e: Throwable) {
                        pendingPasswordRecovery = false
                        btn.isEnabled = true
                        btn.text = "Kirim Email Reset"
                        Toast.makeText(self, SupabaseRepository.errorMessage(e), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val otpVal = otpInput.text.toString().trim()
                val newPass = newPassInput.text.toString()
                val confirmPass = confirmPassInput.text.toString()
                if (otpVal.length < 6) {
                    Toast.makeText(self, "Masukkan kode reset minimal 6 digit.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (newPass.length < 6) {
                    Toast.makeText(self, "Kata sandi baru minimal 6 karakter.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (newPass != confirmPass) {
                    Toast.makeText(self, "Konfirmasi kata sandi tidak cocok.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btn.isEnabled = false
                btn.text = "Memproses..."
                authScope.launch {
                    try {
                        SupabaseRepository.verifyRecoveryOtpAndSetPassword(emailVal, otpVal, newPass)
                        pendingPasswordRecovery = false
                        dialog.dismiss()
                        Toast.makeText(self, "Kata sandi berhasil diperbarui!", Toast.LENGTH_LONG).show()
                        routeSignedInUser()
                    } catch (e: Throwable) {
                        btn.isEnabled = true
                        btn.text = "Simpan Sandi Baru"
                        Toast.makeText(self, SupabaseRepository.errorMessage(e), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        emailInput.requestFocus()
    }

    /** Dialog setelah user membuka link Reset Password dari email (`jajanin://auth-callback`). */
    internal fun showSetNewPasswordDialog() {
        val self = this
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val newPass = EditText(this).apply {
            hint = "Kata sandi baru (min. 6 karakter)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirmPass = EditText(this).apply {
            hint = "Ulangi kata sandi baru"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(newPass)
        container.addView(confirmPass)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Buat Kata Sandi Baru")
            .setMessage("Masukkan kata sandi baru untuk akunmu.")
            .setView(container)
            .setPositiveButton("Simpan Sandi", null)
            .setNegativeButton("Batal") { _, _ -> pendingPasswordRecovery = false }
            .show()
        dialog.setCancelable(false)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = newPass.text.toString()
            val p2 = confirmPass.text.toString()
            if (p1.length < 6) {
                Toast.makeText(self, "Kata sandi minimal 6 karakter.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (p1 != p2) {
                Toast.makeText(self, "Konfirmasi kata sandi tidak cocok.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            authScope.launch {
                try {
                    SupabaseRepository.updatePassword(p1)
                    pendingPasswordRecovery = false
                    dialog.dismiss()
                    Toast.makeText(self, "Kata sandi baru berhasil disimpan!", Toast.LENGTH_LONG).show()
                    routeSignedInUser()
                } catch (e: Throwable) {
                    btn.isEnabled = true
                    Toast.makeText(self, SupabaseRepository.errorMessage(e), Toast.LENGTH_LONG).show()
                }
            }
        }
        newPass.requestFocus()
    }

    fun showAuthForm(focusPassword: Boolean, signup: Boolean = false) {
        if (ui.currentScreen != Screen.LOGIN) ui.go(Screen.LOGIN)
        loginOverlay.openMode(emailMode = signup, focusPassword = focusPassword)
    }

    internal var searchDialog: Dialog? = null

    fun showSearchForm() {
        inlineSearch?.open(InlineSearchOverlay.TARGET_MENU)
    }

    internal fun submitSearch(dialog: Dialog, input: EditText) {
        ui.applyQuery(input.text.toString().trim())
        dialog.dismiss()
    }

    internal var locationDialog: Dialog? = null

    companion object {
        private const val PREFS_THEME = "jajanin_prefs"
        private const val PREF_DARK_THEME = "dark_theme"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_ONBOARDED = "onboarded"
        private const val PREF_ACCESS_SELLER = "access_verified_seller"
        private const val PREF_ACCESS_DEVELOPER = "access_verified_developer"
        private const val SELLER_ACCESS_CODE = "0910261916"
        private const val DEVELOPER_ACCESS_CODE = "0000000010"
    }

    internal fun themePrefs() = getSharedPreferences(PREFS_THEME, MODE_PRIVATE)

    /** Mode tema tersimpan: "light" | "dark" | "system" (default dark). */
    internal fun themeMode(): String {
        val stored = themePrefs().getString(PREF_THEME_MODE, null)
        if (stored == "light" || stored == "dark" || stored == "system") return stored
        // Migrasi dari saklar lama (default dark untuk instalasi baru).
        return if (themePrefs().contains(PREF_DARK_THEME) && !themePrefs().getBoolean(PREF_DARK_THEME, true)) "light" else "dark"
    }

    internal fun systemInDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Selesaikan mode menjadi boolean untuk kanvas. */
    internal fun resolveTheme(): Boolean = when (themeMode()) {
        "dark" -> true
        "system" -> systemInDark()
        else -> false
    }

    internal fun themeModeLabel(): String = when (themeMode()) {
        "dark" -> "Gelap"
        "system" -> "Sistem"
        else -> "Terang"
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode() == "system") ui.setDarkMode(systemInDark())
    }

    /** Dialog pilih tema bertema (Terang/Gelap/Sistem); tersimpan permanen. */
    fun showThemePicker() {
        val options = listOf("Terang", "Gelap (Recommended)", "Ikuti sistem")
        val checked = when (themeMode()) {
            "dark" -> 1
            "system" -> 2
            else -> 0
        }
        showThemedOptions("Tema aplikasi", options, checked) { which ->
            val mode = when (which) {
                1 -> "dark"
                2 -> "system"
                else -> "light"
            }
            themePrefs().edit().putString(PREF_THEME_MODE, mode).apply()
            ui.setDarkMode(resolveTheme())
            ui.themeLabel = themeModeLabel()
            Toast.makeText(this, "Tema ${options[which].lowercase()} aktif.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Terapkan + simpan tema boolean (kompat); berlaku untuk semua role. */
    fun setTheme(dark: Boolean) {
        themePrefs().edit().putString(PREF_THEME_MODE, if (dark) "dark" else "light").apply()
        ui.setDarkMode(dark)
        Toast.makeText(this, if (dark) "Tema gelap aktif." else "Tema terang aktif.", Toast.LENGTH_SHORT).show()
    }

    fun showLocationForm() {
        val open = locationDialog
        if (open != null && open.isShowing) return
        val dialog = Dialog(this).apply {
            setContentView(R.layout.dialog_location)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setOnDismissListener { locationDialog = null }
        }
        locationDialog = dialog
        val container = dialog.findViewById<LinearLayout>(R.id.locationOptions)
        val density = resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (13 * density).toInt()

        LOCATION_OPTIONS.forEach { option ->
            val selected = option == ui.location
            val row = TextView(this).apply {
                text = option
                setTextColor(if (selected) Color.rgb(0, 99, 214) else Color.rgb(27, 35, 53))
                textSize = 15f
                setPadding(padH, padV, padH, padV)
                if (selected) {
                    val checkIcon = AppIcon.drawable(this@MainActivity, "check", 16f, Color.rgb(0, 99, 214))
                    setCompoundDrawables(checkIcon, null, null, null)
                    compoundDrawablePadding = (10 * density).toInt()
                }
                isClickable = true
                setBackgroundResource(R.drawable.bg_option)
            }
            row.setOnClickListener {
                ui.applyLocation(option)
                dialog.dismiss()
            }
            container.addView(row)
        }

        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Keluar: selalu minta konfirmasi agar tidak tersentuh tanpa sengaja. */
    fun logout() {
        android.app.AlertDialog.Builder(this).setTitle("Keluar dari akun?")
            .setMessage("Session di perangkat ini akan dihapus dan kamu harus masuk lagi.")
            .setPositiveButton("Keluar") { _, _ -> doLogout() }
            .setNegativeButton("Batal", null)
            .show()
    }

    internal fun doLogout() {
        authScope.launch {
            try {
                // Lepas kehadiran live, observer chat & token FCM sebelum sesi logout dihapus.
                activeChatDialog?.dismiss()
                activeChatDialog = null
                stopChatObserver()
                ChatDraftStore.clearAll()
                stopOnlinePresence()
                ui.onlineUserIds = emptySet()
                ui.chatConversations = emptyList()
                ui.unreadChatTotal = 0
                ui.unreadChatByOrder = emptyMap()
                runCatching {
                    val uid = SupabaseRepository.currentUid()
                    val token = NotificationHelper.currentFcmToken()
                    if (uid != null && token != null) OrderRepository.removeDevice(uid, token)
                }
                SupabaseRepository.logout()
                adminObserver?.close()
                adminObserver = null
                devObserver?.close()
                devObserver = null
                inboxObserverClose()
                resetOrderNotificationState()
                ui.adminNotifOpen = false
                ui.isSellerProfile = false
                ui.myRole = null
                ui.myDisplayName = ""
                ui.myNis = ""
                ui.myClass = ""
                ui.myIsSellerVerified = false
                ui.mySellerVerifiedEmail = ""
                ui.myStallId = null
                ui.myStallName = ""
                ui.myStallLogoUrl = ""
                ui.myStallBannerUrl = ""
                ui.myStallBio = ""
                ui.myStallCategory = "Makanan & Minuman"
                ui.myStallIsOpen = true
                ui.myStallIsVerified = false
                ui.myStallCompletedOrders = 0
                ui.storeDirectoryById = emptyMap()
                ui.storeSearchQuery = ""
                ui.storeSearchCategory = "All"
                ui.storeSearchResults = emptyList()
                ui.selectedStoreId = null
                ui.storeProfileDetail = null
                ui.storeProfileProducts = emptyList()
                ui.selectedRole = "buyer"
                ui.myOrders = emptyList()
                ui.myOrdersError = null
                ui.devUsers = emptyList()
                ui.devOrders = emptyList()
                ui.devWarnings = emptyList()
                ui.devAnnouncements = emptyList()
                ui.devSelectedUserId = null
                ui.myWarnings = emptyList()
                ui.myAnnouncements = emptyList()
                ui.myInvites = emptyList()
                ui.devInvites = emptyList()
                ui.myPhone = ""
                ui.myExp = 0
                ui.lastExpBalance = -1
                ui.lastExpLevel = 0
                ui.expHistory = emptyList()
                ui.expHistoryError = null
                ui.expHistoryLimit = 20
                ui.expHistoryEnd = false
                ui.stallActiveOrders = -1
                ui.stallFull = false
                ui.showLogin()
                Toast.makeText(this@MainActivity, "Berhasil keluar", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (inlineSearch?.isActive() == true) {
            inlineSearch?.dismiss()
            return
        }
        if (ui.currentScreen == Screen.LOGIN && loginOverlay.handleBack()) return
        if (ui.currentScreen == Screen.PRODUCT_FORM && productFormOverlay.handleBack()) return
        if (ui.currentScreen == Screen.DETAIL && ui.detailSheetMode != null) {
            ui.detailSheetMode = null
            ui.detailSheetScroll = 0f
            ui.invalidate()
            return
        }
        if (ui.currentScreen == Screen.STORE_PROFILE && ui.storeRatingsSheetOpen) {
            ui.animateStoreRatingsSheet(false)
            return
        }
        if (!ui.goBack()) super.onBackPressed()
    }
}
