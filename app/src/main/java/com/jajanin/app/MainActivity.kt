package com.jajanin.app

import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.inbox.*

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
import com.jajanin.app.data.repository.OrderObserver
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.PhoneRepository
import com.jajanin.app.data.repository.PlacedOrder
import com.jajanin.app.data.repository.ProductDraft
import com.jajanin.app.data.repository.RegisterResult
import com.jajanin.app.data.repository.SellerInvite
import com.jajanin.app.data.repository.StallInfo
import com.jajanin.app.data.repository.SupabaseRepository
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
    internal var authForm: AuthForm? = null
    internal var pendingPasswordRecovery = false
    internal var adminObserver: OrderObserver? = null
    internal var adminRefreshJob: Job? = null
    internal var adminStatusBusy = false
    internal var productObserver: OrderObserver? = null
    internal var productRefreshJob: Job? = null
    internal val REQUEST_PRODUCT_IMAGE = 8301
    internal val REQUEST_QRIS_IMAGE = 8302
    internal val REQUEST_OTA_APK = 8303
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

    internal fun resetOrderNotificationState() {
        buyerOrdersSeeded = false
        knownBuyerOrderStatuses.clear()
        knownBuyerPaymentStatuses.clear()
        sellerOrdersSeeded = false
        knownSellerOrderIds.clear()
        knownSellerPaymentStatuses.clear()
    }

    internal fun detectAndNotifyBuyerOrderChanges(orders: List<AdminOrder>) {
        if (!buyerOrdersSeeded) {
            orders.forEach { order ->
                knownBuyerOrderStatuses[order.id] = order.status.lowercase()
                knownBuyerPaymentStatuses[order.id] = order.paymentStatus.lowercase()
            }
            buyerOrdersSeeded = true
            return
        }
        orders.forEach { order ->
            val newStatus = order.status.lowercase()
            val oldStatus = knownBuyerOrderStatuses.put(order.id, newStatus)
            val newPay = order.paymentStatus.lowercase()
            val oldPay = knownBuyerPaymentStatuses.put(order.id, newPay)

            if (oldStatus != null && oldStatus != newStatus) {
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
                val title = if (newPay == "paid") {
                    "Pembayaran #${order.pickupCode} Diverifikasi"
                } else {
                    "Bukti Bayar #${order.pickupCode} Ditolak"
                }
                val body = if (newPay == "paid") {
                    "Pembayaran pesanan #${order.pickupCode} telah ditandai lunas oleh penjual."
                } else {
                    "Bukti pembayaran QRIS #${order.pickupCode} ditolak penjual. Silakan konfirmasi ulang."
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
    }

    internal fun detectAndNotifySellerOrderChanges(orders: List<AdminOrder>) {
        if (!sellerOrdersSeeded) {
            orders.forEach { order ->
                knownSellerOrderIds.add(order.id)
                knownSellerPaymentStatuses[order.id] = order.paymentStatus.lowercase()
            }
            sellerOrdersSeeded = true
            return
        }
        orders.forEach { order ->
            val isNewOrder = knownSellerOrderIds.add(order.id)
            val newPay = order.paymentStatus.lowercase()
            val oldPay = knownSellerPaymentStatuses.put(order.id, newPay)

            if (isNewOrder && !isTerminalStatus(order.status)) {
                val summary = order.itemsSummary.ifBlank { "${order.itemCount} item" }
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:new",
                    orderId = order.id,
                    title = "Pesanan Baru #${order.pickupCode}",
                    body = "${order.buyerName} memesan $summary (${formatRupiah(order.totalIdr)})",
                )
            } else if (!isNewOrder && oldPay != null && oldPay != newPay && newPay == "waiting_verification") {
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = "${order.id}:qris_wait",
                    orderId = order.id,
                    title = "Bukti Bayar QRIS #${order.pickupCode}",
                    body = "${order.buyerName} mengirim konfirmasi pembayaran QRIS (${formatRupiah(order.totalIdr)}).",
                )
            }
        }
    }

    internal fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = authScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                if (!SupabaseRepository.isConfigured()) continue
                if (ui.myRole == "seller" && SupabaseRepository.hasSession()) {
                    silentRefreshSellerTick()
                } else if (ui.myRole == "buyer" || ui.myRole == null) {
                    silentRefreshBuyerTick()
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
        val role = SupabaseRepository.currentRole()
        refreshMyStatus()
        // Nama asli DAN NIS wajib untuk pembeli maupun penjual: akun yang belum punya
        // nama asli atau NIS tidak bisa masuk sebelum melengkapinya.
        val rawDisplay = SupabaseRepository.myRawDisplayName().orEmpty()
        val displayName = SupabaseRepository.extractCleanName(rawDisplay)
        val nis = SupabaseRepository.extractNis(rawDisplay)
        ui.myDisplayName = displayName
        ui.myNis = nis
        if ((role == "buyer" || role == "seller") && (displayName.length < 3 || nis.isBlank())) {
            showDisplayNameDialog { authScope.launch { routeSignedInUser() } }
            return
        }
        if (role == "developer") {
            if (!developerUnlocked) {
                // Jatuh ke home pembeli dulu agar tidak stuck di splash,
                // lalu minta kode; bila benar masuk dasbor developer.
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
                refreshMyOrders()
                refreshOrderingStatus()
                startInboxObserver()
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
        if (role == "seller") {
            // Undangan developer = bebas kode; non-undangan wajib kode akses.
            if (!isSellerAdmitted()) {
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
                refreshMyOrders()
                refreshOrderingStatus()
                startInboxObserver()
                val pending = pendingOrderNotificationId
                requirePrivilegedAccess("seller") {
                    if (pending != null) {
                        pendingOrderNotificationId = pending
                        enterSellerDashboard()
                    } else {
                        enterSellerDashboard()
                    }
                }
                return
            }
            ui.myRole = "seller"
            ensureSellerStallName {
                // Jangan timpa layar detail yang sudah dibuka dari tap notifikasi.
                if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.showAdmin()
                startAdminOrders()
                refreshSellerProducts()
                registerMyDevice()
                refreshMyOrders()
                refreshInbox()
                startInboxObserver()
                pendingOrderNotificationId?.let { orderId ->
                    pendingOrderNotificationId = null
                    openOrderDetail(orderId)
                }
            }
        } else {
            ui.myRole = "buyer"
            ui.showHome()
            refreshProducts()
            refreshMyOrders()
            refreshInbox()
            refreshOrderingStatus()
            registerMyDevice()
            startInboxObserver()
            pendingOrderNotificationId?.let { orderId ->
                pendingOrderNotificationId = null
                ui.selectedBuyerOrderId = orderId
                ui.lastOrderId = orderId
                ui.go(Screen.READY)
            }
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
            if (SupabaseRepository.hasSession()) refreshMyStatus()
        }
        if (ui.myRole == "buyer") {
            refreshProducts()
            startProductUpdates()
        }
        startAutoRefreshLoop()
    }

    override fun onStop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
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
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                uploadProductImage(uri)
            }
            REQUEST_QRIS_IMAGE -> {
                val uri = data?.data
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    uploadStallQrisImage(uri)
                }
            }
            REQUEST_OTA_APK -> {
                val uri = data?.data ?: return
                publishOtaApkFromUri(uri)
            }
        }
    }

    /** Simpan dari halaman form (dialog selalu null; param dipertahankan kompat). */
    internal var orderingStatusJob: Job? = null

    internal var stallAvailJob: Job? = null

    fun submitRoleChoice(role: String) {
        if (role == "seller") {
            enterSellerDashboard()
            return
        }
        ui.myRole = "buyer"
        if (SupabaseRepository.hasSession()) {
            authScope.launch {
                if (SupabaseRepository.currentRole() == "seller") enterSellerDashboard()
                else ui.go(Screen.LOGIN)
            }
        } else {
            ui.go(Screen.LOGIN)
        }
    }

    /** Kembali ke pola aplikasi: seller tetap di dashboard, developer di dasbornya, pembeli pulang ke beranda. */
    internal var devObserver: OrderObserver? = null
    internal var devRefreshJob: Job? = null
    internal var inboxRefreshJob: Job? = null

    /** Dasbor developer wajib kode akses + login DAN ber-role developer di database. */
    internal var pendingOrderNotificationId: String? = null

    override fun onDestroy() {
        autoRefreshJob?.cancel()
        otaDownloadJob?.cancel()
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
        val open = authForm
        if (open != null && open.isShowing) {
            open.setMode(signup)
            open.focus(focusPassword)
            return
        }
        val form = AuthForm()
        authForm = form
        form.show(signup, focusPassword)
    }

    internal var searchDialog: Dialog? = null

    fun showSearchForm() {
        val open = searchDialog
        if (open != null && open.isShowing) {
            open.findViewById<EditText>(R.id.searchInput)?.requestFocus()
            return
        }
        val dialog = Dialog(this).apply {
            setContentView(R.layout.dialog_search)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setOnDismissListener { searchDialog = null }
        }
        searchDialog = dialog
        val input = dialog.findViewById<EditText>(R.id.searchInput)
        val apply = dialog.findViewById<Button>(R.id.searchApply)
        val clear = dialog.findViewById<TextView>(R.id.searchClear)

        input.setText(ui.query)
        input.setSelection(ui.query.length)
        clear.visibility = if (ui.query.isEmpty()) View.GONE else View.VISIBLE

        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch(dialog, input)
                true
            } else false
        }
        apply.setOnClickListener { submitSearch(dialog, input) }
        clear.setOnClickListener {
            ui.applyQuery("")
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
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
                text = if (selected) "✓  $option" else option
                setTextColor(if (selected) Color.rgb(0, 99, 214) else Color.rgb(27, 35, 53))
                textSize = 15f
                setPadding(padH, padV, padH, padV)
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
                // Lepas token FCM dulu (sebelum sesi hilang) agar server berhenti mengirim ke perangkat ini.
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
        if (!ui.goBack()) super.onBackPressed()
    }

    internal inner class AuthForm {
        private val dialog = Dialog(this@MainActivity).apply {
            setContentView(R.layout.dialog_auth)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setOnDismissListener { authForm = null }
        }
        private val title = dialog.findViewById<TextView>(R.id.authTitle)
        private val subtitle = dialog.findViewById<TextView>(R.id.authSubtitle)
        private val name = dialog.findViewById<EditText>(R.id.authName)
        private val nis = dialog.findViewById<EditText>(R.id.authNis)
        private val phone = dialog.findViewById<EditText>(R.id.authPhone)
        private val email = dialog.findViewById<EditText>(R.id.authEmail)
        private val password = dialog.findViewById<EditText>(R.id.authPassword)
        private val error = dialog.findViewById<TextView>(R.id.authError)
        private val action = dialog.findViewById<Button>(R.id.authAction)
        private val progress = dialog.findViewById<ProgressBar>(R.id.authProgress)
        private val toggle = dialog.findViewById<TextView>(R.id.authToggle)
        private val showPass = dialog.findViewById<TextView>(R.id.authShowPass)
        private val forgot = dialog.findViewById<TextView>(R.id.authForgot)

        private var signup = false
        private var busy = false
        private var passVisible = false

        val isShowing: Boolean
            get() = dialog.isShowing

        init {
            email.imeOptions = EditorInfo.IME_ACTION_NEXT
            name.imeOptions = EditorInfo.IME_ACTION_NEXT
            nis.imeOptions = EditorInfo.IME_ACTION_NEXT
            name.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    nis.requestFocus()
                    true
                } else false
            }
            nis.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    phone.requestFocus()
                    true
                } else false
            }
            password.imeOptions = EditorInfo.IME_ACTION_GO
            password.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
            action.setOnClickListener { submit() }
            toggle.setOnClickListener { setMode(!signup) }
            forgot.setOnClickListener {
                val typedEmail = email.text.toString().trim()
                dialog.dismiss()
                showForgotPasswordDialog(typedEmail)
            }
            showPass.setOnClickListener {
                passVisible = !passVisible
                val cursor = password.selectionStart
                password.inputType = if (passVisible) {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                password.setSelection(cursor.coerceAtLeast(0).coerceAtMost(password.text.length))
                showPass.text = if (passVisible) "Sembunyi" else "Lihat"
            }
        }

        fun show(signup: Boolean, focusPasswordField: Boolean) {
            this.signup = signup
            applyMode()
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            focus(focusPasswordField)
        }

        fun setMode(signup: Boolean) {
            this.signup = signup
            applyMode()
            error.visibility = View.GONE
        }

        fun focus(focusPasswordField: Boolean) {
            val target = when {
                focusPasswordField -> password
                signup -> name
                else -> email
            }
            target.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            target.post { imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }
        }

        private fun applyMode() {
            title.text = if (signup) "Buat akun" else "Masuk dengan email"
            subtitle.text = if (signup) "Daftar untuk mulai jajan di kampus." else "Lanjutkan jajan favoritmu di kampus."
            name.visibility = if (signup) View.VISIBLE else View.GONE
            nis.visibility = if (signup) View.VISIBLE else View.GONE
            phone.visibility = if (signup) View.VISIBLE else View.GONE
            forgot.visibility = if (signup) View.GONE else View.VISIBLE
            // Jarak atas email menyesuaikan: tanpa kolom nama, email yang paling atas.
            (email.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                (if (signup) 12 else 22 * resources.displayMetrics.density).toInt()
            action.text = if (signup) "Daftar" else "Masuk"
            toggle.text = if (signup) "Sudah punya akun?  Masuk" else "Belum punya akun?  Daftar"
        }

        private fun fail(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
        }

        private fun setBusy(value: Boolean) {
            busy = value
            action.isEnabled = !value
            toggle.isEnabled = !value
            forgot.isEnabled = !value
            name.isEnabled = !value
            nis.isEnabled = !value
            phone.isEnabled = !value
            email.isEnabled = !value
            password.isEnabled = !value
            showPass.isEnabled = !value
            progress.visibility = if (value) View.VISIBLE else View.GONE
            action.text = when {
                value && signup -> "Mendaftar..."
                value -> "Masuk..."
                signup -> "Daftar"
                else -> "Masuk"
            }
        }

        private fun submit() {
            if (busy) return
            error.visibility = View.GONE
            val nameValue = name.text.toString().trim()
            val nisValue = nis.text.toString().trim()
            val phoneValue = phone.text.toString().trim()
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            if (signup && nameValue.length < 3) {
                fail("Isi nama aslimu dulu (minimal 3 karakter)")
                return
            }
            val normNis = SupabaseRepository.normalizeNis(nisValue)
            if (signup && normNis == null) {
                fail("NIS (Nomor Induk Siswa) wajib diisi (minimal 4 angka)")
                return
            }
            if (signup && PhoneRepository.normalizePhone(phoneValue) == null) {
                fail("Isi nomor HP valid (cth. 0812xxxxxxx)")
                return
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailValue).matches() || passwordValue.length < 6) {
                fail("Masukkan email valid dan kata sandi minimal 6 karakter")
                return
            }
            if (!SupabaseRepository.isConfigured()) {
                fail("Supabase belum dikonfigurasi. Tambahkan SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY di ~/.gradle/gradle.properties, lalu build ulang.")
                return
            }
            setBusy(true)
            authScope.launch {
                try {
                    if (signup) {
                        val validNis = normNis ?: ""
                        val packedName = SupabaseRepository.packNameAndNis(nameValue, validNis)
                        if (SupabaseRepository.register(emailValue, passwordValue, packedName) == RegisterResult.LOGGED_IN) {
                            runCatching { SupabaseRepository.setMyName(nameValue, validNis) }
                            ui.myDisplayName = SupabaseRepository.extractCleanName(nameValue)
                            ui.myNis = validNis
                            runCatching { ui.myPhone = PhoneRepository.setMyPhone(phoneValue) }
                            dialog.dismiss()
                            routeSignedInUser()
                        } else {
                            fail("Pendaftaran berhasil, tapi Supabase masih meminta konfirmasi email. Nonaktifkan Confirm email di Dashboard → Authentication → Providers → Email.")
                            setBusy(false)
                        }
                    } else {
                        SupabaseRepository.login(emailValue, passwordValue)
                        dialog.dismiss()
                        routeSignedInUser()
                    }
                } catch (e: Throwable) {
                    fail(SupabaseRepository.errorMessage(e))
                    setBusy(false)
                }
            }
        }
    }
}
