package com.jajanin.app

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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

class MainActivity : Activity() {
    private lateinit var ui: JajaninView

    /** Akses tema gelap untuk helper dialog bertema. */
    internal fun isDarkMode(): Boolean = ui.darkMode
    private val authScope = CoroutineScope(Dispatchers.Main)
    private var authForm: AuthForm? = null
    private var adminObserver: OrderObserver? = null
    private var adminRefreshJob: Job? = null
    private var adminStatusBusy = false
    private var productObserver: OrderObserver? = null
    private var productRefreshJob: Job? = null
    private val REQUEST_PRODUCT_IMAGE = 8301
    private val REQUEST_QRIS_IMAGE = 8302

    // Kode akses wajib untuk memasuki role penjual & developer.
    // Cukup dimasukkan SATU KALI (saat pertama); status tersimpan permanen
    // di perangkat sehingga tidak ditanyakan lagi walau logout/restart.
    private var sellerUnlocked: Boolean
        get() = appPrefs().getBoolean(PREF_ACCESS_SELLER, false)
        set(value) { appPrefs().edit().putBoolean(PREF_ACCESS_SELLER, value).apply() }
    private var developerUnlocked: Boolean
        get() = appPrefs().getBoolean(PREF_ACCESS_DEVELOPER, false)
        set(value) { appPrefs().edit().putBoolean(PREF_ACCESS_DEVELOPER, value).apply() }

    /**
     * Bebas kode akses penjual bila perangkat ini sudah unlock ATAU akun
     * diundang developer (flag server seller_invited). Seller non-undangan
     * tetap wajib kode.
     */
    private suspend fun isSellerAdmitted(): Boolean =
        sellerUnlocked || SupabaseRepository.isSellerInvited()

    /**
     * Gerbang kode akses role istimewa. Sekali benar di perangkat ini,
     * tidak diminta lagi selamanya (tidak reset saat logout).
     */
    private fun requirePrivilegedAccess(role: String, onGranted: () -> Unit) {
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
    private data class SellerStallId(val id: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        ui = JajaninView(this)
        ui.setDarkMode(resolveTheme())
        ui.themeLabel = themeModeLabel()
        setContentView(ui)
        NotificationHelper.ensureChannel(this)
        requestNotificationPermission()
        startProductUpdates()
        startMyOrdersUpdates()
        refreshProducts()
        refreshMyOrders()
        handleAuthLink(intent)
        restoreSessionIfNeeded()
        handleOrderNotification(intent)
    }

    /** Izin notifikasi runtime (Android 13+); diamkan bila ditolak — pipeline order tidak terganggu. */
    private fun requestNotificationPermission() {
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
    private fun restoreSessionIfNeeded() {
        authScope.launch {
            if (SupabaseRepository.restoreSession()) routeSignedInUser()
            else if (!isOnboarded()) ui.showOnboard()
            else ui.showWelcome()
        }
    }

    private fun appPrefs() = getSharedPreferences(PREFS_THEME, MODE_PRIVATE)

    private fun isOnboarded(): Boolean = appPrefs().getBoolean(PREF_ONBOARDED, false)

    /** Tutorial selesai/dilewati: tandai agar tidak muncul lagi. */
    fun finishOnboarding() {
        appPrefs().edit().putBoolean(PREF_ONBOARDED, true).apply()
        ui.showWelcome()
    }

    private suspend fun routeSignedInUser() {
        val role = SupabaseRepository.currentRole()
        refreshMyStatus()
        // Nama asli wajib untuk pembeli/penjual: akun lama yang belum punya
        // nama tidak bisa lewat sebelum mengisi (daftar baru sudah isi di form).
        val displayName = SupabaseRepository.myDisplayName().orEmpty()
        ui.myDisplayName = displayName
        if ((role == "buyer" || role == "seller") && displayName.isBlank()) {
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
        }
    }

    /** Status akun sendiri (mis. suspended) untuk banner + blokir client-side. */
    private suspend fun refreshMyStatus() {
        ui.myAccountStatus = SupabaseRepository.myAccountStatus() ?: "active"
        ui.myPhone = runCatching { PhoneRepository.myPhone() }.getOrNull().orEmpty()
        ui.invalidate()
    }

    override fun onStart() {
        super.onStart()
        // Segarkan status akun tiap kembali ke aplikasi (semua role).
        authScope.launch {
            if (SupabaseRepository.hasSession()) refreshMyStatus()
        }
    }

    /** Mendaftarkan token FCM perangkat ini (semua role). Gagal = diam. */
    private fun registerMyDevice() {
        if (!SupabaseRepository.hasSession()) return
        authScope.launch {
            runCatching {
                val uid = SupabaseRepository.currentUid() ?: return@runCatching
                val token = NotificationHelper.currentFcmToken() ?: return@runCatching
                OrderRepository.registerDevice(uid, token)
            }
        }
    }

    private var myOrdersJob: Job? = null

    /** Riwayat order pembeli sendiri (status terkini sinkron dari database). */
    fun refreshMyOrders() {
        if (!SupabaseRepository.hasSession()) {
            ui.myOrders = emptyList()
            ui.myOrdersError = null
            ui.myOrdersLoading = false
            ui.invalidate()
            return
        }
        myOrdersJob?.cancel()
        ui.myOrdersLoading = ui.myOrders.isEmpty()
        ui.myOrdersError = null
        ui.invalidate()
        myOrdersJob = authScope.launch {
            try {
                ui.myOrders = OrderRepository.fetchMyOrders()
                ui.myOrdersError = null
            } catch (error: Throwable) {
                ui.myOrdersError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.myOrdersLoading = false
                ui.invalidate()
            }
        }
    }

    private fun startMyOrdersUpdates() {
        ui.myObserver?.close()
        ui.myObserver = null
        if (!SupabaseRepository.isConfigured()) return
        runCatching {
            ui.myObserver = OrderRepository.observeOrders(authScope) {
                if (SupabaseRepository.hasSession()) refreshMyOrders()
            }
        }
    }

    private fun startProductUpdates() {
        if (!SupabaseRepository.isConfigured()) return
        runCatching {
            productObserver?.close()
            productObserver = OrderRepository.observeProducts(authScope) { refreshProducts() }
        }
    }

    fun refreshProducts() {
        if (!SupabaseRepository.isConfigured()) return
        productRefreshJob?.cancel()
        ui.productsLoading = ui.products.isEmpty()
        ui.productsError = null
        ui.invalidate()
        productRefreshJob = authScope.launch {
            try {
                ui.products = OrderRepository.fetchAvailableProducts()
                ui.productsError = null
            } catch (error: Throwable) {
                ui.productsError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.productsLoading = false
                ui.invalidate()
            }
        }
    }

    fun showProductForm() {
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk sebagai penjual untuk menambah produk.", Toast.LENGTH_LONG).show()
            return
        }
        authScope.launch {
            val stallId = OrderRepository.sellerStallId()
            if (stallId == null) {
                Toast.makeText(this@MainActivity, "Lengkapi nama warung dulu.", Toast.LENGTH_LONG).show()
                ensureSellerStallName { openProductForm(null) }
                return@launch
            }
            openProductForm(null)
        }
    }

    fun showProductEditForm(productId: String) {
        val product = ui.sellerProducts.firstOrNull { it.id == productId } ?: return
        openProductForm(product)
    }

    /** Buka halaman form produk (geser, bukan dialog). */
    private fun openProductForm(existing: ProductRow?) {
        if (existing != null) {
            ui.productEditId = existing.id
            ui.productName = existing.name
            ui.productDescription = existing.description
            ui.productCategory = normalizeCategory(existing.category)
            ui.productPrice = existing.priceIdr.toString()
            ui.productStock = existing.stock?.toString().orEmpty()
            ui.productImageUrl = existing.imageUrl.orEmpty()
            ui.productIngredients = existing.ingredients
            ui.productAvailable = existing.available
        } else {
            ui.productEditId = null
            ui.productName = ""
            ui.productDescription = ""
            ui.productCategory = "Food"
            ui.productPrice = ""
            ui.productStock = ""
            ui.productImageUrl = ""
            ui.productIngredients = ""
            ui.productAvailable = true
        }
        ui.productSaving = false
        ui.productError = ""
        ui.go(Screen.PRODUCT_FORM)
    }

    /** Input satu baris form produk lewat dialog fokus (judul + keyboard sesuai). */
    fun showProductTextField(field: String) {
        // title, hint, initial, numeric, maxLen, multiline
        val spec = when (field) {
            "harga" -> arrayOf("Harga", "cth. 12000", ui.productPrice, "1", "9", "0")
            "stok" -> arrayOf("Stok", "Kosongkan = tanpa batas", ui.productStock, "1", "5", "0")
            "deskripsi" -> arrayOf("Deskripsi", "Singkat saja", ui.productDescription, "0", "200", "1")
            "bahan" -> arrayOf("Bahan", "Opsional", ui.productIngredients, "0", "120", "1")
            else -> arrayOf("Nama produk", "cth. Nasi goreng", ui.productName, "0", "60", "0")
        }
        val title = spec[0]
        val hintText = spec[1]
        val initial = spec[2]
        val numeric = spec[3] == "1"
        val multiline = spec[5] == "1"
        val input = EditText(this).apply {
            setSingleLine(!multiline)
            if (multiline) {
                minLines = 2
                gravity = android.view.Gravity.TOP
            }
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(spec[4].toInt()))
            setText(initial)
            setSelection(initial.length)
            hint = hintText
        }
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                when (field) {
                    "harga" -> ui.productPrice = value.filter(Char::isDigit)
                    "stok" -> ui.productStock = value.filter(Char::isDigit)
                    "deskripsi" -> ui.productDescription = value
                    "bahan" -> ui.productIngredients = value
                    else -> ui.productName = value
                }
                ui.productError = ""
                ui.invalidate()
            }
            .setNegativeButton("Batal", null)
            .show()
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Pilih kategori lewat opsi bertema. */
    fun showProductCategoryPicker() {
        val choices = listOf("Food", "Drinks")
        showThemedOptions("Kategori", choices, choices.indexOf(ui.productCategory).coerceAtLeast(0)) { which ->
            ui.productCategory = choices[which]
            ui.invalidate()
        }
    }

    /** Saklar tampil di etalase dari halaman form. */
    fun toggleProductFormAvailable() {
        ui.productAvailable = !ui.productAvailable
        ui.invalidate()
    }

    /** Validasi + simpan dari halaman form (error tampil inline). */
    fun saveProductForm() {
        if (ui.productSaving || ui.productImageUploading) return
        if (ui.productImageUploading) {
            ui.productError = "Tunggu gambar selesai diunggah."
            ui.invalidate()
            return
        }
        val priceValue = ui.productPrice.filter(Char::isDigit).toIntOrNull()
        val stockText = ui.productStock.trim()
        ui.productError = when {
            ui.productName.isBlank() -> "Nama produk wajib diisi."
            priceValue == null || priceValue <= 0 -> "Harga wajib angka lebih dari Rp 0."
            priceValue > 999_999_999 -> "Harga terlalu besar."
            stockText.isNotBlank() && (stockText.toIntOrNull() == null || stockText.toInt() < 0) ->
                "Stok harus angka 0 ke atas, atau kosongkan."
            else -> ""
        }
        if (ui.productError.isNotBlank()) {
            ui.invalidate()
            return
        }
        ui.productSaving = true
        ui.invalidate()
        saveProduct(null)
    }

    fun pickProductImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PRODUCT_IMAGE)
    }

    /** Upload gambar produk pilihan ke Storage publik lalu simpan URL-nya. */
    fun uploadProductImage(uri: android.net.Uri) {
        ui.productImageUploading = true
        ui.invalidate()
        authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Gambar tidak bisa dibaca.")
                require(bytes.size <= 5 * 1024 * 1024) { "Ukuran gambar maksimal 5MB." }
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mime.contains("png", true) -> "png"
                    mime.contains("webp", true) -> "webp"
                    else -> "jpg"
                }
                val token = SupabaseRepository.currentAccessToken()
                    ?: error("Sesi habis, masuk ulang.")
                val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
                val path = "$uid/${java.util.UUID.randomUUID()}.$ext"
                val url = java.net.URL("${SupabaseRepository.baseUrl}/storage/v1/object/product-images/$path")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("apikey", SupabaseRepository.publishableKey)
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", mime)
                    setRequestProperty("x-upsert", "true")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code !in 200..299) error("Unggah gagal (HTTP $code).")
                val publicUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/public/product-images/$path"
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    ui.productImageUrl = publicUrl
                    ui.productImageUploading = false
                    ui.invalidate()
                                Toast.makeText(this@MainActivity, "Gambar terunggah.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    ui.productImageUploading = false
                    ui.invalidate()
                                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun loadProductImage(url: String) {
        if (ProductImageCache.images.containsKey(url)) return
        authScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val connection = java.net.URL(url).openConnection().apply { connectTimeout = 6000; readTimeout = 6000 }
                connection.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) {
                ProductImageCache.images[url] = bitmap
                launch(Dispatchers.Main) { ui.invalidate() }
            }
        }
    }

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
        }
    }

    /** Simpan dari halaman form (dialog selalu null; param dipertahankan kompat). */
    fun saveProduct(dialog: Dialog? = null) {
        if (ui.productImageUploading) {
            ui.productError = "Tunggu gambar selesai diunggah."
            ui.productSaving = false
            ui.invalidate()
            return
        }
        val price = ui.productPrice.filter(Char::isDigit).toIntOrNull()
        val stock = ui.productStock.filter(Char::isDigit).toIntOrNull()
        if (price == null) {
            ui.productError = "Harga tidak valid."
            ui.productSaving = false
            ui.invalidate()
            return
        }
        authScope.launch {
            try {
                val imageUrl = ui.productImageUrl.takeIf { it.startsWith("https://") }
                val draft = ProductDraft(
                    ui.productName, ui.productDescription, ui.productCategory,
                    price, imageUrl, stock, ui.productIngredients, ui.productAvailable,
                )
                val editingId = ui.productEditId
                if (editingId == null) {
                    OrderRepository.createProduct(draft)
                } else {
                    OrderRepository.updateProduct(editingId, draft)
                    ui.productEditId = null
                }
                dialog?.dismiss()
                ui.productSaving = false
                ui.productError = ""
                refreshProducts()
                refreshSellerProducts()
                Toast.makeText(this@MainActivity, "Produk berhasil disimpan.", Toast.LENGTH_SHORT).show()
                if (dialog == null && ui.currentScreen == Screen.PRODUCT_FORM) ui.goBack()
            } catch (error: Throwable) {
                ui.productSaving = false
                ui.productError = SupabaseRepository.errorMessage(error)
                ui.invalidate()
                Toast.makeText(this@MainActivity, ui.productError, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Konfirmasi lalu hapus produk milik penjual. */
    fun confirmDeleteProduct(productId: String) {
        val product = ui.sellerProducts.firstOrNull { it.id == productId } ?: return
        showThemedInfo(
            title = "Hapus produk?",
            message = "\"${product.name}\" tidak lagi tampil di beranda pembeli.",
            posLabel = "Hapus",
            destructivePos = true,
            onPos = {
                authScope.launch {
                    try {
                        OrderRepository.deleteProduct(productId)
                        refreshProducts()
                        refreshSellerProducts()
                        Toast.makeText(this@MainActivity, "Produk dihapus.", Toast.LENGTH_SHORT).show()
                        if (ui.currentScreen == Screen.PRODUCT_FORM) ui.goBack()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            },
            negLabel = "Batal",
        )
    }

    private fun refreshSellerProducts() {
        authScope.launch {
            runCatching { OrderRepository.fetchSellerProducts() }
                .onSuccess { products -> ui.sellerProducts = products; ui.invalidate() }
                .onFailure { error -> Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show() }
        }
    }

    fun toggleProductAvailability(productId: String) {
        val product = ui.sellerProducts.firstOrNull { it.id == productId } ?: return
        // Optimis: pill "Aktif/Mati" langsung berpindah; server mengonfirmasi lewat refresh.
        val optimistic = !product.available
        ui.sellerProducts = ui.sellerProducts.map { if (it.id == productId) it.copy(available = optimistic) else it }
        ui.invalidate()
        authScope.launch {
            try {
                OrderRepository.setProductAvailable(productId, optimistic)
                refreshSellerProducts()
                refreshProducts()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                refreshSellerProducts()
            }
        }
    }

    fun addProductToOrder() {
        val product = ui.selectedProduct ?: return
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk untuk membuat pesanan.", Toast.LENGTH_SHORT).show()
            showAuthForm(false)
            return
        }
        addToCart(product.id, ui.quantity)
    }

    /** Tambah ke keranjang; aturan: satu pesanan = tepat satu menu, jumlah 1. */
    fun addToCart(productId: String, qty: Int = 1) {
        if (ui.myAccountStatus == "suspended") {
            Toast.makeText(this, "Akun ditangguhkan. Kamu tidak bisa memesan.", Toast.LENGTH_LONG).show()
            return
        }
        val product = ui.products.firstOrNull { it.id == productId }
            ?: ui.sellerProducts.firstOrNull { it.id == productId } ?: return
        if (ui.cart.isNotEmpty()) {
            Toast.makeText(this, "Satu pesanan hanya untuk satu menu (1x). Selesaikan pesanan ini dulu.", Toast.LENGTH_LONG).show()
            return
        }
        ui.cartStallId = product.stallId
        ui.cart[productId] = 1
        Toast.makeText(this, "${product.name} ditambahkan ke keranjang", Toast.LENGTH_SHORT).show()
        ui.invalidate()
    }

    fun setCartQty(productId: String, qty: Int) {
        if (qty <= 0) {
            ui.cart.remove(productId)
            if (ui.cart.isEmpty()) {
                ui.cartStallId = null
                ui.cartStallQris = null
            }
        } else {
            // Jumlah dikunci 1 (aturan satu menu per pesanan).
            if (qty > 1) Toast.makeText(this, "Satu pesanan hanya untuk satu menu (1x).", Toast.LENGTH_SHORT).show()
            ui.cart[productId] = 1
        }
        ui.invalidate()
    }

    fun setCheckoutPayment(method: String) {
        ui.checkoutPayment = if (method == "cash") "cash" else "qris"
        ui.invalidate()
    }

    /** Buka layar checkout: pastikan login + keranjang tidak kosong + ambil info warung. */
    fun openCheckout() {
        if (ui.myAccountStatus == "suspended") {
            Toast.makeText(this, "Akun ditangguhkan. Kamu tidak bisa memesan.", Toast.LENGTH_LONG).show()
            return
        }
        if (ui.cart.isEmpty()) {
            Toast.makeText(this, "Keranjang masih kosong.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk sebagai pembeli untuk memesan.", Toast.LENGTH_SHORT).show()
            showAuthForm(false)
            return
        }
        // Nomor HP wajib sebelum memesan (akun lama yang belum punya).
        if (ui.myPhone.isBlank()) {
            authScope.launch {
                ui.myPhone = runCatching { PhoneRepository.myPhone() }.getOrNull().orEmpty()
                if (ui.myPhone.isBlank()) showPhoneGate { openCheckout() }
                else openCheckout()
            }
            return
        }
        ui.go(Screen.CHECKOUT)
        refreshOrderingStatus()
        refreshStallAvailability()
        ui.cartStallQris = null
        authScope.launch {
            val stallId = ui.cartStallId ?: return@launch
            runCatching { OrderRepository.fetchStall(stallId) }
                .onSuccess { stall ->
                    ui.cartStallName = stall?.name ?: "Kantin Jajanin"
                    ui.cartStallQris = stall?.qrisImageUrl.orEmpty()
                    // Warung belum pasang QRIS = tunai saja, opsi QRIS disembunyikan.
                    if (ui.cartStallQris.isNullOrBlank()) ui.checkoutPayment = "cash"
                    ui.invalidate()
                }
        }
    }

    /**
     * Status pemesanan dari server (flag blackout WIB + "Pesanan hari ini: X/2").
     * Hanya cerminan untuk UX; place_order di backend selalu memeriksa ulang.
     */
    private var orderingStatusJob: Job? = null

    fun refreshOrderingStatus() {
        if (!SupabaseRepository.hasSession()) {
            ui.orderingBlocked = false
            ui.orderingBlockMessage = null
            ui.ordersToday = 0
            ui.orderingStatusLoading = false
            ui.invalidate()
            return
        }
        orderingStatusJob?.cancel()
        ui.orderingStatusLoading = true
        ui.invalidate()
        orderingStatusJob = authScope.launch {
            runCatching { OrderRepository.fetchOrderingStatus() }
                .onSuccess { status ->
                    ui.orderingBlocked = status.blocked
                    ui.orderingBlockMessage = status.blockMessage
                    ui.ordersToday = status.ordersToday
                    applyServerTime(status.serverTime)
                }
                .onFailure {
                    // Gagal probe = jangan blokir UI; backend tetap menegakkan aturan.
                    ui.orderingBlocked = false
                    ui.orderingBlockMessage = null
                }
            ui.orderingStatusLoading = false
            ui.invalidate()
        }
    }

    /**
     * Antrean aktif warung di keranjang dari server (maksimal 10).
     * Hanya cerminan untuk UX; place_order di backend selalu memeriksa ulang.
     */
    private var stallAvailJob: Job? = null

    fun refreshStallAvailability() {
        val stallId = ui.cartStallId
        if (!SupabaseRepository.hasSession() || stallId == null) {
            ui.stallActiveOrders = -1
            ui.stallFull = false
            ui.stallLoading = false
            ui.invalidate()
            return
        }
        stallAvailJob?.cancel()
        ui.stallLoading = true
        ui.invalidate()
        stallAvailJob = authScope.launch {
            runCatching { OrderRepository.fetchStallAvailability(stallId) }
                .onSuccess { avail ->
                    ui.stallActiveOrders = avail.activeOrders
                    ui.stallFull = avail.isFull
                }
                .onFailure {
                    // Gagal probe = jangan blokir UI; backend tetap menegakkan aturan.
                    ui.stallActiveOrders = -1
                    ui.stallFull = false
                }
            ui.stallLoading = false
            ui.invalidate()
        }
    }

    /**
     * Sinkron jam server WIB ("yyyy-MM-dd HH:mm:ss") ke offset lokal sehingga
     * UI bisa menampilkan jam live tanpa polling tiap detik. Jam perangkat
     * tidak dipercaya untuk penegakan — hanya untuk tampilan.
     */
    private fun applyServerTime(serverTime: String) {
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
            }.parse(serverTime.take(19))?.time
        }.getOrNull() ?: return
        ui.serverBaseMillis = parsed
        ui.serverBaseElapsed = android.os.SystemClock.elapsedRealtime()
        ui.invalidate()
    }

    /**
     * Buat order dari seluruh keranjang lewat RPC server-side.
     * Harga dihitung dari database; total client tidak dipercaya.
     */
    fun placeCartOrder() {
        if (ui.cart.isEmpty()) {
            Toast.makeText(this, "Keranjang masih kosong.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk sebagai pembeli untuk memesan.", Toast.LENGTH_SHORT).show()
            showAuthForm(false)
            return
        }
        if (ui.myAccountStatus == "suspended") {
            Toast.makeText(this, "Akun ditangguhkan. Kamu tidak bisa memesan.", Toast.LENGTH_LONG).show()
            return
        }
        // Pengaman: tanpa nomor HP tidak boleh memesan.
        if (ui.myPhone.isBlank()) {
            showPhoneGate { placeCartOrder() }
            return
        }
        val lines = ui.cart.entries.map { it.key to it.value }
        // Pengaman balapan: QRIS dicabut penjual setelah checkout dibuka = tunai.
        val method = if (ui.checkoutPayment == "qris" && ui.cartStallQris?.isBlank() == true) "cash" else ui.checkoutPayment
        ui.checkoutPayment = method
        ui.placingOrder = true
        ui.invalidate()
        authScope.launch {
            try {
                val placed = OrderRepository.placeOrder(lines, method)
                val stallId = ui.cartStallId
                ui.cart.clear()
                ui.cartStallId = null
                ui.cartStallQris = null
                ui.lastOrderId = placed.id
                ui.lastOrderPickup = placed.pickupCode
                ui.lastOrderTotal = placed.totalIdr
                ui.lastOrderMethod = placed.paymentMethod
                if (method == "qris") {
                    // Ambil QR warung lalu tampilkan layar bayar.
                    ui.lastOrderStatus = placed.status
                    openQrisPayment(placed.id, placed.totalIdr, stallId)
                } else {
                    refreshMyOrders()
                    ui.lastOrderStatus = placed.status
                    ui.go(Screen.READY)
                    Toast.makeText(this@MainActivity, "Pesanan dibuat. Bayar tunai saat ambil.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } finally {
                ui.placingOrder = false
                ui.invalidate()
                // Perbarui sisa jatah harian ("Pesanan hari ini: X/2") dari server.
                refreshOrderingStatus()
                refreshStallAvailability()
            }
        }
    }

    /** Layar QRIS: tampilkan QR warung + nominal pas untuk order yang baru dibuat. */
    private fun openQrisPayment(orderId: String, amountIdr: Int, stallId: String?) {
        ui.qrisOrderId = orderId
        ui.qrisAmount = amountIdr
        ui.qrisImageUrl = null
        ui.qrisLoading = stallId != null
        ui.go(Screen.QRIS)
        if (stallId == null) {
            ui.qrisLoading = false
            ui.invalidate()
            return
        }
        authScope.launch {
            val stall = runCatching { OrderRepository.fetchStall(stallId) }.getOrNull()
            ui.cartStallName = stall?.name ?: ui.cartStallName
            ui.qrisImageUrl = stall?.qrisImageUrl?.takeIf { it.isNotBlank() }
            ui.qrisLoading = false
            ui.invalidate()
        }
    }

    /** Pembeli menekan "Saya sudah membayar": status -> waiting_verification (bukan paid). */
    fun submitPaymentProof() {
        val orderId = ui.qrisOrderId ?: return
        authScope.launch {
            try {
                OrderRepository.submitPaymentProof(orderId)
                ui.lastOrderStatus = "pending"
                refreshMyOrders()
                ui.go(Screen.READY)
                Toast.makeText(this@MainActivity, "Menunggu verifikasi penjual.", Toast.LENGTH_LONG).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pembeli mengajukan bukti dari kartu riwayat pesanannya sendiri. */
    fun submitPaymentProofFor(orderId: String) {
        authScope.launch {
            try {
                OrderRepository.submitPaymentProof(orderId)
                refreshMyOrders()
                Toast.makeText(this@MainActivity, "Menunggu verifikasi penjual.", Toast.LENGTH_LONG).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Penjual menandai lunas (tunai saat ambil / verifikasi QRIS). */
    fun markOrderPaid(orderId: String) {
        authScope.launch {
            try {
                OrderRepository.markOrderPaid(orderId)
                refreshAdminOrders()
                loadAdminOrderDetail(orderId)
                Toast.makeText(this@MainActivity, "Pembayaran ditandai lunas.", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Penjual menolak bukti bayar QRIS. */
    fun rejectOrderPayment(orderId: String) {
        authScope.launch {
            try {
                OrderRepository.rejectOrderPayment(orderId)
                refreshAdminOrders()
                loadAdminOrderDetail(orderId)
                Toast.makeText(this@MainActivity, "Bukti pembayaran ditolak.", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Muat pengaturan warung penjual (nama + QRIS) untuk layar profil. */
    fun refreshSellerSettings() {
        authScope.launch {
            val stall = runCatching { OrderRepository.fetchMyStall() }.getOrNull()
            ui.myStallId = stall?.id
            ui.myStallName = stall?.name.orEmpty()
            ui.myStallQris = stall?.qrisImageUrl.orEmpty()
            ui.invalidate()
        }
    }

    /** Dialog pengaturan QRIS warung: unggah gambar atau tempel URL. */
    fun showQrisSettings() {
        refreshSellerSettings()
        val current = ui.myStallQris
        val items = if (current.isBlank()) {
            arrayOf("Pilih gambar dari galeri", "Tempel URL gambar")
        } else {
            arrayOf("Lihat QR terpasang", "Pilih gambar dari galeri", "Tempel URL gambar", "Hapus QRIS")
        }
        android.app.AlertDialog.Builder(this).setTitle("QRIS ${ui.myStallName.takeIf { it.isNotBlank() } ?: "warung saya"}")
            .setItems(items) { _, which ->
                val choice = items[which]
                when {
                    choice.startsWith("Lihat") -> showQrisPreview(current)
                    choice.startsWith("Pilih") -> pickQrisImage()
                    choice.startsWith("Tempel") -> showQrisUrlForm()
                    choice.startsWith("Hapus") -> confirmRemoveQris()
                }
            }
            .show()
    }

    private fun showQrisPreview(url: String) {
        authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bmp = runCatching {
                val conn = java.net.URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (bmp != null) {
                    ProductImageCache.images[url] = bmp
                    val view = android.widget.ImageView(this@MainActivity).apply { setImageBitmap(bmp) }
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("QRIS terpasang")
                        .setView(view).setPositiveButton("Tutup", null).show()
                } else {
                    Toast.makeText(this@MainActivity, "QR tidak bisa dimuat.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Konfirmasi sebelum menghapus QRIS warung (tindakan destruktif). */
    fun confirmRemoveQris() {
        android.app.AlertDialog.Builder(this).setTitle("Hapus QRIS warung?")
            .setMessage("Pembeli tidak bisa bayar QRIS sampai QR baru dipasang.")
            .setPositiveButton("Hapus") { _, _ -> saveStallQrisUrl("") }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Penjual menyimpan URL gambar QRIS warungnya (paste URL). */
    fun showQrisUrlForm() {
        val input = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(ui.myStallQris)
        }
        android.app.AlertDialog.Builder(this).setTitle("URL gambar QRIS")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ -> saveStallQrisUrl(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Penjual menyimpan URL gambar QRIS warungnya (paste URL). */
    fun saveStallQrisUrl(url: String) {
        val stallId = ui.myStallId
        if (stallId == null) {
            Toast.makeText(this, "Warung belum ada.", Toast.LENGTH_SHORT).show()
            return
        }
        authScope.launch {
            try {
                OrderRepository.updateStallQris(stallId, url.ifBlank { null })
                ui.myStallQris = url
                ui.invalidate()
                Toast.makeText(this@MainActivity, "QRIS warung disimpan.", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Penjual mengunggah file gambar QRIS ke Storage publik lalu menyimpan URL-nya. */
    fun uploadStallQrisImage(uri: android.net.Uri) {
        val stallId = ui.myStallId
        if (stallId == null) {
            Toast.makeText(this, "Warung belum ada.", Toast.LENGTH_SHORT).show()
            return
        }
        authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Gambar tidak bisa dibaca.")
                require(bytes.size <= 5 * 1024 * 1024) { "Ukuran gambar maksimal 5MB." }
                val token = SupabaseRepository.currentAccessToken()
                    ?: error("Sesi habis, masuk ulang.")
                val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
                val url = java.net.URL("${SupabaseRepository.baseUrl}/storage/v1/object/qris/$uid.png")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("apikey", SupabaseRepository.publishableKey)
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "image/png")
                    setRequestProperty("x-upsert", "true")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code !in 200..299) error("Unggah gagal (HTTP $code).")
                val publicUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/public/qris/$uid.png"
                OrderRepository.updateStallQris(stallId, publicUrl)
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    ui.myStallQris = publicUrl
                    ui.invalidate()
                    Toast.makeText(this@MainActivity, "QRIS warung disimpan.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun pickQrisImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_QRIS_IMAGE)
    }

    private fun startAdminOrders() {
        adminObserver?.close()
        adminObserver = OrderRepository.observeOrders(authScope) { refreshAdminOrders() }
        refreshAdminOrders()
    }

    /** Pilihan peran dari layar ROLE: penjual/developer tanpa sesi diarahkan masuk, dengan sesi dicek rolenya. */
    fun submitRoleChoice(role: String) {
        if (role == "seller") {
            enterSellerDashboard()
            return
        }
        if (role == "developer") {
            enterDeveloper()
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
    fun backToSellerHome() {
        authScope.launch {
            if (!SupabaseRepository.hasSession()) {
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
                return@launch
            }
            when (SupabaseRepository.currentRole()) {
                "developer" -> {
                    if (!developerUnlocked) {
                        requirePrivilegedAccess("developer") { backToSellerHome() }
                        return@launch
                    }
                    ui.myRole = "developer"
                    ui.showDev()
                    refreshDeveloper()
                    return@launch
                }
                "seller" -> { /* lanjut ke dashboard penjual di bawah */ }
                else -> {
                    ui.myRole = "buyer"
                    ui.showHome()
                    refreshProducts()
                    return@launch
                }
            }
            ui.myRole = "seller"
            if (!isSellerAdmitted()) {
                requirePrivilegedAccess("seller") { backToSellerHome() }
                ui.myRole = "buyer"
                return@launch
            }
            ensureSellerStallName {
                ui.showAdmin()
                refreshAdminOrders()
            }
        }
    }

    // ============ Nama toko/warung wajib untuk penjual ============

    private fun isDefaultStallName(name: String): Boolean {
        val t = name.trim()
        return t.isEmpty() ||
            t.equals("Kantin Penjual", ignoreCase = true) ||
            t.equals("Kantin Jajanin", ignoreCase = true)
    }

    /**
     * Pastikan penjual punya nama toko/brand sebelum dashboard dibuka.
     * Bila warung belum ada / nama masih default, tampilkan dialog wajib
     * isi; bila sudah ada nama proper, langsung [onReady].
     */
    private fun ensureSellerStallName(onReady: () -> Unit) {
        authScope.launch {
            val stall = runCatching { OrderRepository.fetchMyStall() }.getOrNull()
            val current = stall?.name?.trim().orEmpty()
            if (stall != null && !isDefaultStallName(current)) {
                ui.myStallId = stall.id
                ui.myStallName = current
                ui.myStallQris = stall.qrisImageUrl.orEmpty()
                ui.invalidate()
                onReady()
                return@launch
            }
            showStallNameDialog(prefill = if (isDefaultStallName(current)) "" else current) { onReady() }
        }
    }

    /**
     * Dialog wajib isi nama asli; dipakai akun lama yang belum punya nama.
     * Tidak bisa dilewat: tombol Batal = keluar dari akun.
     */
    private fun showDisplayNameDialog(onSaved: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "cth. Budi Santoso"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        container.addView(input)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Siapa namamu?")
            .setMessage("Mulai sekarang setiap akun wajib memakai nama asli. Isi dulu sebelum masuk aplikasi.")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal") { _, _ -> doLogout() }
            .show()
        dialog.setCancelable(false)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.length < 3) {
                Toast.makeText(this, "Nama asli minimal 3 karakter.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn.isEnabled = false
            authScope.launch {
                try {
                    SupabaseRepository.setMyName(value)
                    ui.myDisplayName = value
                    ui.invalidate()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Halo, $value!", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (error: Throwable) {
                    saveBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        input.requestFocus()
    }

    /**
     * Dialog wajib isi nama toko; tidak bisa dilewat tanpa disimpan.
     * Tombol Batal = mundur ke home pembeli (tidak masuk dashboard).
     */
    private fun showStallNameDialog(prefill: String, onSaved: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "cth. Warung Bu Tini"
            setSingleLine(true)
            setText(prefill)
        }
        container.addView(input)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Nama toko/warung")
            .setMessage("Setiap penjual wajib punya nama toko/brand. Isi dulu sebelum membuka dashboard.")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal") { _, _ ->
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
            }
            .show()
        dialog.setCancelable(false)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.length < 3) {
                Toast.makeText(this, "Nama toko minimal 3 karakter.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn.isEnabled = false
            authScope.launch {
                try {
                    val stallId = OrderRepository.ensureSellerStall(value)
                    ui.myStallId = stallId
                    ui.myStallName = value
                    ui.invalidate()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Warung \"$value\" siap digunakan.", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (error: Throwable) {
                    saveBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Dialog ubah nama toko dari layar Profil seller (bisa dibatalkan).
     * Memakai nama toko yang tersimpan, bukan email akun.
     */
    fun showStallNameEdit() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "cth. Warung Bu Tini"
            setSingleLine(true)
            setText(ui.myStallName)
            selectAll()
        }
        container.addView(input)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Ubah nama toko")
            .setMessage("Nama ini tampil di profil dan dashboard penjual.")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.length < 3) {
                Toast.makeText(this, "Nama toko minimal 3 karakter.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (value == ui.myStallName.trim()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn.isEnabled = false
            authScope.launch {
                try {
                    val stallId = ui.myStallId
                    val newId = if (stallId != null) {
                        OrderRepository.updateStallName(stallId, value)
                        stallId
                    } else {
                        OrderRepository.ensureSellerStall(value)
                    }
                    ui.myStallId = newId
                    ui.myStallName = value
                    ui.invalidate()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Nama toko diganti jadi \"$value\".", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    saveBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        input.requestFocus()
    }

    fun enterSellerDashboard() {
        // Dashboard penjual wajib login dulu DAN ber-role seller di database:
        // tanpa role seller tidak ada baris yang terbaca RLS, dashboard akan kosong.
        // Kode akses hanya untuk seller non-undangan; undangan developer bebas kode.
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk dulu untuk membuka dashboard penjual.", Toast.LENGTH_LONG).show()
            showAuthForm(false)
            return
        }
        ui.myRole = "seller"
        authScope.launch {
            val role = SupabaseRepository.currentRole()
            if (role != "seller") {
                Toast.makeText(this@MainActivity, "Akun ini terdaftar sebagai pembeli, bukan penjual.", Toast.LENGTH_LONG).show()
                ui.myRole = "buyer"
                ui.showHome()
                refreshProducts()
                return@launch
            }
            if (!isSellerAdmitted()) {
                requirePrivilegedAccess("seller") { enterSellerDashboard() }
                return@launch
            }
            ui.adminOrders = emptyList()
            ui.adminLoading = false
            ui.adminError = null
            ui.adminNotifOpen = false
            ensureSellerStallName {
                ui.showAdmin()
                startAdminOrders()
                refreshSellerProducts()
                registerMyDevice()
                refreshInbox()
                startInboxObserver()
            }
        }
    }

    fun refreshAdminOrders() {
        if (!SupabaseRepository.hasSession()) return
        adminRefreshJob?.cancel()
        ui.adminLoading = ui.adminOrders.isEmpty()
        ui.adminError = null
        ui.invalidate()
        adminRefreshJob = authScope.launch {
            try {
                ui.adminOrders = OrderRepository.fetchAdminTodayOrders()
                ui.adminError = null
                // Jika layar detail sedang terbuka, ikutkan perubahan realtime
                // (mis. pembeli baru mengirim bukti bayar) tanpa perlu tarik-ulang.
                ui.adminDetailId?.let { id ->
                    ui.adminOrders.firstOrNull { it.id == id }?.let { ui.adminDetailOrder = it }
                }
            } catch (error: Throwable) {
                ui.adminError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.adminLoading = false
                ui.invalidate()
            }
        }
    }

    // ================= Developer Dashboard =================

    private var devObserver: OrderObserver? = null
    private var devRefreshJob: Job? = null
    private var inboxRefreshJob: Job? = null

    /** Dasbor developer wajib kode akses + login DAN ber-role developer di database. */
    fun enterDeveloper() {
        if (!developerUnlocked) {
            requirePrivilegedAccess("developer") { enterDeveloper() }
            return
        }
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk dulu untuk membuka dashboard developer.", Toast.LENGTH_LONG).show()
            showAuthForm(false)
            return
        }
        authScope.launch {
            if (SupabaseRepository.currentRole() != "developer") {
                Toast.makeText(this@MainActivity, "Akun ini bukan developer.", Toast.LENGTH_LONG).show()
                return@launch
            }
            ui.myRole = "developer"
            ui.showDev()
            startDeveloper()
            refreshInbox()
        }
    }

    private fun startDeveloper() {
        devObserver?.close()
        inboxObserverClose()
        if (!SupabaseRepository.isConfigured()) return
        runCatching {
            devObserver = DeveloperRepository.observePlatform(authScope) { refreshDeveloper() }
            ui.inboxObserver = DeveloperRepository.observeInbox(authScope) { refreshInbox() }
        }
        refreshDeveloper()
    }

    private fun inboxObserverClose() {
        ui.inboxObserver?.close()
        ui.inboxObserver = null
    }

    /** Muat ulang seluruh dasbor developer: statistik real + users + orders + warnings + announcements. */
    fun refreshDeveloper() {
        if (!SupabaseRepository.hasSession()) return
        devRefreshJob?.cancel()
        val firstLoad = ui.devUsers.isEmpty() && ui.devOrders.isEmpty()
        ui.devStatsLoading = firstLoad
        ui.devUsersLoading = firstLoad
        ui.devOrdersLoading = firstLoad
        ui.devWarningsLoading = ui.devWarnings.isEmpty()
        ui.devAnnLoading = ui.devAnnouncements.isEmpty()
        ui.devError = null
        ui.invalidate()
        devRefreshJob = authScope.launch {
            try {
                ui.devStats = DeveloperRepository.fetchStats()
                ui.devUsers = DeveloperRepository.fetchUsers()
                ui.devOrders = DeveloperRepository.fetchAllOrders()
                ui.devWarnings = DeveloperRepository.fetchWarningHistory()
                ui.devAnnouncements = DeveloperRepository.fetchAnnouncements()
                ui.devInvites = DeveloperRepository.fetchPendingInvites()
                ui.devError = null
            } catch (error: Throwable) {
                ui.devError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.devStatsLoading = false
                ui.devUsersLoading = false
                ui.devOrdersLoading = false
                ui.devWarningsLoading = false
                ui.devAnnLoading = false
                ui.invalidate()
            }
        }
    }

    fun showDevSearch() {
        val input = EditText(this).apply {
            hint = "Nama / email"
            setSingleLine(true)
            setText(ui.devQuery)
        }
        android.app.AlertDialog.Builder(this).setTitle("Cari pengguna")
            .setView(input)
            .setPositiveButton("Cari") { _, _ ->
                ui.devQuery = input.text.toString().trim()
                ui.scroll = 0f
                ui.invalidate()
            }
            .setNegativeButton("Reset") { _, _ ->
                ui.devQuery = ""
                ui.devUserFilter = "Semua"
                ui.invalidate()
            }
            .show()
    }

    /** Form kirim peringatan ke satu pengguna (dipakai dari kartu user terpilih). */
    fun showWarningForm(targetUserId: String) {
        val user = ui.devUsers.firstOrNull { it.id == targetUserId }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val title = EditText(this).apply { hint = "Judul, mis. Order Abuse Warning"; setSingleLine(true) }
        val message = EditText(this).apply { hint = "Pesan peringatan"; minLines = 2 }
        val sevView = TextView(this).apply { text = "Severity: warning (ketuk untuk ganti)"; setPadding(0, 24, 0, 0) }
        var severity = "warning"
        sevView.setOnClickListener {
            val choices = arrayOf("info", "warning", "critical")
            android.app.AlertDialog.Builder(this).setTitle("Severity")
                .setItems(choices) { _, which ->
                    severity = choices[which]
                    sevView.text = "Severity: $severity (ketuk untuk ganti)"
                }.show()
        }
        layout.addView(title)
        layout.addView(message)
        layout.addView(sevView)
        android.app.AlertDialog.Builder(this)
            .setTitle("Peringatkan ${user?.displayName ?: "pengguna"}")
            .setView(layout)
            .setPositiveButton("Kirim") { _, _ ->
                if (title.text.isBlank() || message.text.isBlank()) {
                    Toast.makeText(this, "Judul dan pesan wajib diisi.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                authScope.launch {
                    try {
                        DeveloperRepository.sendWarning(targetUserId, title.text.toString(), message.text.toString(), severity)
                        refreshDeveloper()
                        Toast.makeText(this@MainActivity, "Peringatan terkirim.", Toast.LENGTH_SHORT).show()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Pilih target dulu bila tombol + di tab Warn ditekan tanpa user terpilih. */
    fun showWarnPicker() {
        val selected = ui.devUsers.firstOrNull { it.id == ui.devSelectedUserId }
        if (selected != null) {
            showWarningForm(selected.id)
            return
        }
        val names = ui.devUsers.take(30).map { "${it.displayName} (${it.email})" }.toTypedArray()
        if (names.isEmpty()) {
            Toast.makeText(this, "Muat pengguna dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("Pilih pengguna")
            .setItems(names) { _, which ->
                ui.devSelectedUserId = ui.devUsers[which].id
                ui.devTab = "Users"
                ui.invalidate()
                showWarningForm(ui.devUsers[which].id)
            }.show()
    }

    fun showAnnouncementForm() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val title = EditText(this).apply { hint = "Judul, mis. Maintenance malam ini"; setSingleLine(true) }
        val message = EditText(this).apply { hint = "Isi pengumuman"; minLines = 2 }
        val targetView = TextView(this).apply { text = "Target: all (ketuk untuk ganti)"; setPadding(0, 24, 0, 0) }
        var target = "all"
        targetView.setOnClickListener {
            val choices = arrayOf("all", "buyers", "sellers")
            android.app.AlertDialog.Builder(this).setTitle("Target")
                .setItems(choices) { _, which ->
                    target = choices[which]
                    targetView.text = "Target: $target (ketuk untuk ganti)"
                }.show()
        }
        layout.addView(title)
        layout.addView(message)
        layout.addView(targetView)
        android.app.AlertDialog.Builder(this).setTitle("Pengumuman baru")
            .setView(layout)
            .setPositiveButton("Kirim") { _, _ ->
                if (title.text.isBlank()) {
                    Toast.makeText(this, "Judul wajib diisi.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                authScope.launch {
                    try {
                        DeveloperRepository.sendAnnouncement(title.text.toString(), message.text.toString(), target)
                        refreshDeveloper()
                        Toast.makeText(this@MainActivity, "Pengumuman terkirim.", Toast.LENGTH_SHORT).show()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Aksi pembatasan akun: active / warned / suspended. Tidak pernah menghapus user. */
    fun setDevUserStatus(userId: String, status: String) {
        val label = if (status == "suspended") "Tangguhkan akun ini?" else "Aktifkan kembali akun ini?"
        android.app.AlertDialog.Builder(this).setTitle(label)
            .setMessage(if (status == "suspended") "User tidak bisa membuat order/produk baru. Data lama tetap utuh." else "User bisa bertransaksi lagi.")
            .setPositiveButton(if (status == "suspended") "Tangguhkan" else "Aktifkan") { _, _ ->
                authScope.launch {
                    try {
                        DeveloperRepository.setAccountStatus(userId, status)
                        refreshDeveloper()
                        Toast.makeText(this@MainActivity, "Status akun diperbarui.", Toast.LENGTH_SHORT).show()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun toggleDevStall(stallId: String, open: Boolean) {
        authScope.launch {
            try {
                DeveloperRepository.toggleStall(stallId, open)
                refreshDeveloper()
                Toast.makeText(this@MainActivity, if (open) "Warung dibuka." else "Warung ditutup.", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Reset harian monitor (developer only): konfirmasi -> RPC
     * `daily_reset_monitor` -> refresh dashboard. Order aktif jadi
     * completed (antrean warung kosong), warnings + announcements
     * dibersihkan, warning_count dinolkan. Riwayat completed/cancelled
     * tetap utuh; "Hari ini" otomatis mulai dari nol tiap 00:00 WIB.
     */
    fun confirmDailyReset() {
        if (ui.devResetting) return
        android.app.AlertDialog.Builder(this).setTitle("Reset harian monitor?")
            .setMessage("Order aktif akan diselesaikan, riwayat warn/info dibersihkan, dan hitungan ⚠ dinolkan. Riwayat completed/cancelled tetap tersimpan.")
            .setPositiveButton("Reset") { _, _ -> doDailyReset() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doDailyReset() {
        if (ui.devResetting) return
        ui.devResetting = true
        ui.invalidate()
        authScope.launch {
            try {
                val r = DeveloperRepository.dailyResetMonitor()
                refreshDeveloper()
                Toast.makeText(
                    this@MainActivity,
                    "Reset selesai: ${r.completedOrders} order ditutup, ${r.clearedWarnings} warn + ${r.clearedAnnouncements} info dibersihkan, ${r.resetProfiles} akun dinolkan.",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            } finally {
                ui.devResetting = false
                ui.invalidate()
            }
        }
    }

    // ================= Buyer/seller inbox =================

    /** Realtime ringan untuk badge inbox buyer/seller (peringatan + pengumuman). */
    private fun startInboxObserver() {
        if (ui.myRole == "developer") return
        inboxObserverClose()
        if (!SupabaseRepository.isConfigured()) return
        runCatching {
            ui.inboxObserver = DeveloperRepository.observeInbox(authScope) { refreshInbox() }
        }
    }

    /** Kotak masuk: peringatan developer + pengumuman + undangan seller. Membuka = menandai dibaca. */
    fun refreshInbox() {
        if (!SupabaseRepository.hasSession()) {
            ui.myWarnings = emptyList()
            ui.myAnnouncements = emptyList()
            ui.myInvites = emptyList()
            ui.invalidate()
            return
        }
        inboxRefreshJob?.cancel()
        inboxRefreshJob = authScope.launch {
            ui.myWarnings = runCatching { DeveloperRepository.fetchMyWarnings() }.getOrDefault(emptyList())
            ui.myAnnouncements = runCatching { DeveloperRepository.fetchMyAnnouncements() }.getOrDefault(emptyList())
            ui.myInvites = runCatching { DeveloperRepository.fetchMyInvites() }.getOrDefault(emptyList())
            ui.invalidate()
        }
    }

    fun showInbox() {
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk dulu untuk melihat notifikasi.", Toast.LENGTH_SHORT).show()
            showAuthForm(false)
            return
        }
        authScope.launch {
            val warnings = runCatching { DeveloperRepository.fetchMyWarnings() }.getOrDefault(emptyList())
            val anns = runCatching { DeveloperRepository.fetchMyAnnouncements() }.getOrDefault(emptyList())
            val invites = runCatching { DeveloperRepository.fetchMyInvites() }.getOrDefault(emptyList())
            ui.myWarnings = warnings
            ui.myAnnouncements = anns
            ui.myInvites = invites
            ui.invalidate()
            val items = ArrayList<String>()
            val warnIds = ArrayList<String?>()
            val inviteIds = ArrayList<String?>()
            invites.filter { it.status == "pending" }.forEach { inv ->
                items.add("[Toko] Undangan jadi penjual — ketuk untuk verifikasi (Terima/Tolak)")
                warnIds.add(null)
                inviteIds.add(inv.id)
            }
            warnings.forEach { w ->
                items.add("[${w.severity}] ${w.title} — ${w.message.take(60)}${if (w.isRead) "" else " (BARU)"}")
                warnIds.add(w.id)
                inviteIds.add(null)
            }
            anns.forEach { a ->
                items.add("[Info ${a.targetRole}] ${a.title} — ${a.message.take(60)}")
                warnIds.add(null)
                inviteIds.add(null)
            }
            if (items.isEmpty()) {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Notifikasi")
                    .setMessage("Tidak ada peringatan atau pengumuman.")
                    .setPositiveButton("Tutup", null).show()
                return@launch
            }
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Notifikasi")
                .setItems(items.toTypedArray()) { _, which ->
                    val invId = inviteIds[which]
                    if (invId != null) {
                        showInviteVerify(invId)
                        return@setItems
                    }
                    val wid = warnIds[which]
                    if (wid != null) {
                        authScope.launch {
                            DeveloperRepository.markWarningRead(wid)
                            refreshInbox()
                        }
                        val w = warnings.firstOrNull { it.id == wid }
                        if (w != null) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle(w.title)
                                .setMessage("${w.message}\n\nSeverity: ${w.severity}\nTanggal: ${w.createdAt.take(16).replace('T', ' ')}")
                                .setPositiveButton("Tutup", null).show()
                        }
                    }
                }.show()
            // Semua warning yang terlihat dianggap dibaca (badge hilang).
            warnings.filterNot { it.isRead }.forEach { DeveloperRepository.markWarningRead(it.id) }
            refreshInbox()
        }
    }

    // ================= Seller invites (dev undang -> buyer verifikasi) =================

    /** Developer mengundang pembeli jadi penjual (konfirmasi dulu). */
    fun confirmInviteSeller(targetUserId: String) {
        val user = ui.devUsers.firstOrNull { it.id == targetUserId }
        android.app.AlertDialog.Builder(this)
            .setTitle("Undang jadi penjual?")
            .setMessage("${user?.displayName ?: "Pengguna"} (${user?.email ?: "-"}) akan menerima undangan di Notifikasi. Bila diterima, akunnya berubah jadi penjual.")
            .setPositiveButton("Undang") { _, _ ->
                authScope.launch {
                    try {
                        DeveloperRepository.inviteSeller(targetUserId)
                        Toast.makeText(this@MainActivity, "Undangan terkirim.", Toast.LENGTH_SHORT).show()
                        refreshDeveloper()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Developer membatalkan undangan pending. */
    fun confirmCancelInvite(inviteId: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Batalkan undangan?")
            .setMessage("Undangan yang menunggu akan dibatalkan dan hilang dari notifikasi pembeli.")
            .setPositiveButton("Batalkan") { _, _ ->
                authScope.launch {
                    try {
                        DeveloperRepository.cancelInvite(inviteId)
                        Toast.makeText(this@MainActivity, "Undangan dibatalkan.", Toast.LENGTH_SHORT).show()
                        refreshDeveloper()
                    } catch (error: Throwable) {
                        Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Kembali", null)
            .show()
    }

    /** Verifikasi pembeli dari Notifikasi: terima = jadi penjual, tolak = tetap pembeli. */
    fun showInviteVerify(inviteId: String) {
        showThemedInfo(
            title = "Undangan jadi penjual",
            message = "Developer Jajanin mengundangmu membuka toko dan mengelola menu + pesanan kantin.\n\nTerima undangan ini?",
            posLabel = "Terima",
            onPos = { acceptSellerInvite(inviteId) },
            negLabel = "Tolak",
            onNeg = { declineSellerInvite(inviteId) },
        )
    }

    private fun acceptSellerInvite(inviteId: String) {
        authScope.launch {
            try {
                DeveloperRepository.acceptInvite(inviteId)
                // Undangan diterima = unlock perangkat ini juga (flag server
                // seller_invited jadi bukti di HP lain).
                sellerUnlocked = true
                Toast.makeText(this@MainActivity, "Selamat! Akunmu sekarang penjual.", Toast.LENGTH_LONG).show()
                refreshInbox()
                // Rute ulang sesuai role baru: langsung nama toko + dashboard.
                routeSignedInUser()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                refreshInbox()
            }
        }
    }

    private fun declineSellerInvite(inviteId: String) {
        authScope.launch {
            try {
                DeveloperRepository.declineInvite(inviteId)
                Toast.makeText(this@MainActivity, "Undangan ditolak. Kamu tetap sebagai pembeli.", Toast.LENGTH_SHORT).show()
                refreshInbox()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                refreshInbox()
            }
        }
    }

    /** Lonceng -> halaman Notifikasi (tujuan nyata, bukan popup). */
    fun openNotif() {
        if (!SupabaseRepository.hasSession()) {
            Toast.makeText(this, "Masuk dulu untuk melihat notifikasi.", Toast.LENGTH_SHORT).show()
            showAuthForm(false)
            return
        }
        ui.go(Screen.NOTIF)
        if (ui.myRole == "developer") {
            // Developer melihat riwayat yang ia kirim + undangan menunggu.
            authScope.launch {
                ui.devWarnings = runCatching { DeveloperRepository.fetchWarningHistory() }.getOrDefault(emptyList())
                ui.devAnnouncements = runCatching { DeveloperRepository.fetchAnnouncements() }.getOrDefault(emptyList())
                ui.devInvites = runCatching { DeveloperRepository.fetchPendingInvites() }.getOrDefault(emptyList())
                ui.myWarnings = ui.devWarnings
                ui.myAnnouncements = ui.devAnnouncements
                ui.invalidate()
            }
            return
        }
        refreshInbox()
        if (ui.myRole == "seller") refreshAdminOrders()
    }

    /** Detail satu peringatan dari halaman Notifikasi (buka = tandai dibaca). */
    fun showNotifWarning(warningId: String) {
        val w = ui.myWarnings.firstOrNull { it.id == warningId } ?: return
        authScope.launch {
            DeveloperRepository.markWarningRead(warningId)
            refreshInbox()
        }
        showThemedInfo(
            title = w.title.ifBlank { "Peringatan" },
            message = "${w.message}\n\nTingkat: ${w.severity}\nTanggal: ${w.createdAt.take(16).replace('T', ' ')}",
            severity = w.severity,
        )
    }

    /** Detail satu pengumuman dari halaman Notifikasi. */
    fun showNotifAnn(annId: String) {
        val a = ui.myAnnouncements.firstOrNull { it.id == annId } ?: return
        showThemedInfo(
            title = a.title.ifBlank { "Pengumuman" },
            message = "${a.message}\n\nTanggal: ${a.createdAt.take(16).replace('T', ' ')}",
        )
    }

    // ================= Nomor HP =================

    /** Editor nomor HP sendiri (validasi + normalisasi server). */
    fun showPhoneEditor(onSaved: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "cth. 0812xxxxxxx"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setText(ui.myPhone)
            setSelection(ui.myPhone.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(input)
        android.app.AlertDialog.Builder(this).setTitle("Nomor HP")
            .setMessage("Dipakai untuk koordinasi ambil pesanan dan info order penting.")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .show()
            .also { dialog ->
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val raw = input.text.toString().trim()
                    if (PhoneRepository.normalizePhone(raw) == null) {
                        Toast.makeText(this, "Nomor HP tidak valid. Gunakan 08... atau +62...", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    saveBtn.isEnabled = false
                    authScope.launch {
                        try {
                            ui.myPhone = PhoneRepository.setMyPhone(raw)
                            ui.invalidate()
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, "Nomor HP disimpan.", Toast.LENGTH_SHORT).show()
                            onSaved?.invoke()
                        } catch (error: Throwable) {
                            saveBtn.isEnabled = true
                            Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        input.requestFocus()
    }

    /** Gerbang wajib-HP sebelum memesan (akun lama tanpa nomor). */
    private fun showPhoneGate(onReady: () -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle("Lengkapi nomor HP")
            .setMessage("Nomor HP wajib diisi sebelum memesan, untuk koordinasi pengambilan pesanan.")
            .setPositiveButton("Isi nomor") { _, _ -> showPhoneEditor { onReady() } }
            .setNegativeButton("Nanti", null)
            .show()
    }

    fun cancelAdminOrder(orderId: String) {
        val order = ui.adminOrders.firstOrNull { it.id == orderId } ?: return
        if (isTerminalStatus(order.status)) return
        android.app.AlertDialog.Builder(this).setTitle("Batalkan pesanan?")
            .setMessage("${order.buyerName} · Kode ${order.pickupCode}")
            .setPositiveButton("Batalkan") { _, _ -> submitAdminOrderStatus(orderId, STATUS_CANCELLED) }
            .setNegativeButton("Kembali", null)
            .show()
    }

    private fun submitAdminOrderStatus(orderId: String, nextStatus: String) {
        if (adminStatusBusy) return
        adminStatusBusy = true
        authScope.launch {
            try {
                OrderRepository.updateOrderStatus(orderId, nextStatus)
                refreshAdminOrders()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            } finally {
                adminStatusBusy = false
            }
        }
    }

    /** Memuat satu order ke layar detail (dipanggil dari tap baris & deep-link notifikasi). */
    fun loadAdminOrderDetail(orderId: String) {
        ui.adminDetailId = orderId
        ui.adminDetailLoading = true
        ui.adminDetailError = null
        ui.invalidate()
        authScope.launch {
            try {
                val order = OrderRepository.fetchOrderById(orderId)
                if (order == null) {
                    ui.adminDetailOrder = null
                    ui.adminDetailError = "Pesanan tidak ditemukan atau bukan milikmu."
                } else {
                    ui.adminDetailOrder = order
                    ui.adminDetailError = null
                }
            } catch (error: Throwable) {
                ui.adminDetailError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.adminDetailLoading = false
                ui.invalidate()
            }
        }
    }

    /** Mengubah status order dari layar detail; UI sudah diupdate optimis oleh view. */
    fun setAdminOrderStatus(orderId: String, status: String) {
        if (adminStatusBusy) return
        adminStatusBusy = true
        authScope.launch {
            try {
                OrderRepository.updateOrderStatus(orderId, status)
                refreshAdminOrders()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                // Tarik ulang daftar juga: update optimis di kartu dashboard harus dibatalkan.
                refreshAdminOrders()
                loadAdminOrderDetail(orderId)
            } finally {
                adminStatusBusy = false
            }
        }
    }

    /** Order dari tap notifikasi; dikonsumsi routeSignedInUser bila restore session menang lebih dulu. */
    private var pendingOrderNotificationId: String? = null

    private fun openOrderDetail(orderId: String) {
        if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.go(Screen.ADMIN_DETAIL)
        loadAdminOrderDetail(orderId)
    }

    /** Tap notifikasi "Pesanan Baru": buka dashboard + layar detail order terkait. */
    private fun handleOrderNotification(intent: Intent?) {
        val orderId = intent?.getStringExtra(NotificationHelper.EXTRA_ORDER_ID) ?: return
        intent.removeExtra(NotificationHelper.EXTRA_ORDER_ID)
        pendingOrderNotificationId = orderId
        authScope.launch {
            if (!SupabaseRepository.awaitLoggedIn()) return@launch
            if (SupabaseRepository.currentRole() != "seller") {
                pendingOrderNotificationId = null
                return@launch
            }
            // Bila routeSignedInUser sudah lebih dulu mengonsumsi id ini, jangan navigasi ulang.
            if (pendingOrderNotificationId != orderId) return@launch
            pendingOrderNotificationId = null
            if (!isSellerAdmitted()) {
                requirePrivilegedAccess("seller") {
                    pendingOrderNotificationId = orderId
                    enterSellerDashboard()
                    pendingOrderNotificationId?.let { id ->
                        pendingOrderNotificationId = null
                        openOrderDetail(id)
                    }
                }
                return@launch
            }
            if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.showAdmin()
            ensureSellerStallName {
                if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.showAdmin()
                startAdminOrders()
                refreshSellerProducts()
                openOrderDetail(orderId)
            }
        }
    }

    override fun onDestroy() {
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

    private fun handleAuthLink(authIntent: Intent?) {
        if (authIntent?.data?.scheme != "jajanin") return
        authScope.launch {
            if (SupabaseRepository.hasSession()) {
                routeSignedInUser()
                Toast.makeText(this@MainActivity, "Berhasil masuk", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this@MainActivity, "Tautan diterima. Coba masuk kembali.", Toast.LENGTH_LONG).show()
        }
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

    private var searchDialog: Dialog? = null

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

    private fun submitSearch(dialog: Dialog, input: EditText) {
        ui.applyQuery(input.text.toString().trim())
        dialog.dismiss()
    }

    private var locationDialog: Dialog? = null

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

    private fun themePrefs() = getSharedPreferences(PREFS_THEME, MODE_PRIVATE)

    /** Mode tema tersimpan: "light" | "dark" | "system" (default light). */
    private fun themeMode(): String {
        val stored = themePrefs().getString(PREF_THEME_MODE, null)
        if (stored == "light" || stored == "dark" || stored == "system") return stored
        // Migrasi dari saklar lama.
        return if (themePrefs().getBoolean(PREF_DARK_THEME, false)) "dark" else "light"
    }

    private fun systemInDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Selesaikan mode menjadi boolean untuk kanvas. */
    private fun resolveTheme(): Boolean = when (themeMode()) {
        "dark" -> true
        "system" -> systemInDark()
        else -> false
    }

    private fun themeModeLabel(): String = when (themeMode()) {
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
        val options = listOf("Terang", "Gelap", "Ikuti sistem")
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

    private fun doLogout() {
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
                ui.adminNotifOpen = false
                ui.isSellerProfile = false
                ui.myRole = null
                ui.myDisplayName = ""
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

    private inner class AuthForm {
        private val dialog = Dialog(this@MainActivity).apply {
            setContentView(R.layout.dialog_auth)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setOnDismissListener { authForm = null }
        }
        private val title = dialog.findViewById<TextView>(R.id.authTitle)
        private val subtitle = dialog.findViewById<TextView>(R.id.authSubtitle)
        private val name = dialog.findViewById<EditText>(R.id.authName)
        private val phone = dialog.findViewById<EditText>(R.id.authPhone)
        private val email = dialog.findViewById<EditText>(R.id.authEmail)
        private val password = dialog.findViewById<EditText>(R.id.authPassword)
        private val error = dialog.findViewById<TextView>(R.id.authError)
        private val action = dialog.findViewById<Button>(R.id.authAction)
        private val progress = dialog.findViewById<ProgressBar>(R.id.authProgress)
        private val toggle = dialog.findViewById<TextView>(R.id.authToggle)
        private val showPass = dialog.findViewById<TextView>(R.id.authShowPass)

        private var signup = false
        private var busy = false
        private var passVisible = false

        val isShowing: Boolean
            get() = dialog.isShowing

        init {
            email.imeOptions = EditorInfo.IME_ACTION_NEXT
            name.imeOptions = EditorInfo.IME_ACTION_NEXT
            name.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    email.requestFocus()
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
            phone.visibility = if (signup) View.VISIBLE else View.GONE
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
            name.isEnabled = !value
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
            val phoneValue = phone.text.toString().trim()
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            if (signup && nameValue.length < 3) {
                fail("Isi nama aslimu dulu (minimal 3 karakter)")
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
                        if (SupabaseRepository.register(emailValue, passwordValue, nameValue) == RegisterResult.LOGGED_IN) {
                            // Nama dikirim via metadata saat daftar; pastikan tersimpan
                            // (mis. trigger belum jalan) sebelum masuk.
                            runCatching { SupabaseRepository.setMyName(nameValue) }
                            ui.myDisplayName = SupabaseRepository.myDisplayName() ?: nameValue
                            // Nomor HP wajib: simpan dinormalisasi server.
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
