package com.jajanin.app.feature.buyer

import com.jajanin.app.MainActivity
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
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.showThemedInfo
import com.jajanin.app.core.ui.showThemedOptions
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
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import com.jajanin.app.R

internal fun MainActivity.refreshMyOrders() {
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
            val fresh = withTimeout(12_000L) { OrderRepository.fetchMyOrders() }
            detectAndNotifyBuyerOrderChanges(fresh)
            ui.myOrders = fresh
            ui.myOrdersError = null
            refreshMyExp()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            ui.myOrdersError = SupabaseRepository.errorMessage(error)
        } finally {
            ui.myOrdersLoading = false
            ui.invalidate()
        }
    }
}

/** Auto-refresh pembeli setiap 1 detik di latar belakang tanpa kedipan indikator loading. */
internal suspend fun MainActivity.silentRefreshBuyerTick() {
    if (!SupabaseRepository.isConfigured()) return
    runCatching {
        val freshProducts = OrderRepository.fetchAvailableProducts()
        if (freshProducts != ui.products) {
            ui.products = freshProducts
            ui.productsLoading = false
            ui.invalidate()
        }
    }
    if (SupabaseRepository.hasSession() && !ui.placingOrder) {
        runCatching {
            val freshOrders = OrderRepository.fetchMyOrders()
            detectAndNotifyBuyerOrderChanges(freshOrders)
            if (freshOrders != ui.myOrders) {
                ui.myOrders = freshOrders
                ui.lastOrderId?.let { id ->
                    freshOrders.firstOrNull { it.id == id }?.let { matched ->
                        ui.lastOrderStatus = matched.status
                    }
                }
                ui.myOrdersLoading = false
                ui.invalidate()
            }
        }
    }
}

internal fun MainActivity.startMyOrdersUpdates() {
    ui.myObserver?.close()
    ui.myObserver = null
    if (!SupabaseRepository.isConfigured()) return
    runCatching {
        ui.myObserver = OrderRepository.observeOrders(authScope, "buyer-orders") { action ->
            if (!SupabaseRepository.hasSession()) return@observeOrders
            if (!applyRealtimeOrderToBuyer(action)) refreshMyOrders()
        }
    }
}

private fun MainActivity.applyRealtimeOrderToBuyer(action: PostgresAction): Boolean {
    return when (action) {
        is PostgresAction.Update -> {
            val patch = OrderRepository.decodeOrderPatch(action.record) ?: return false
            val currentUid = SupabaseRepository.currentUid()
            if (patch.userId != null && currentUid != null && patch.userId != currentUid) return true
            if (ui.myOrders.none { it.id == patch.id }) return false
            ui.myOrders = ui.myOrders.map { order ->
                if (order.id == patch.id) {
                    order.copy(
                        status = patch.status ?: order.status,
                        paymentStatus = patch.paymentStatus ?: order.paymentStatus,
                        cancelReason = patch.paymentReference?.trim() ?: order.cancelReason,
                    )
                } else {
                    order
                }
            }
            if (ui.lastOrderId == patch.id && patch.status != null) {
                ui.lastOrderStatus = patch.status
            }
            if (patch.status.equals(STATUS_CANCELLED, ignoreCase = true)) {
                refreshOrderingStatus()
            }
            detectAndNotifyBuyerOrderChanges(ui.myOrders)
            ui.invalidate()
            true
        }
        is PostgresAction.Insert -> {
            val patch = OrderRepository.decodeOrderPatch(action.record) ?: return false
            val currentUid = SupabaseRepository.currentUid()
            patch.userId != null && currentUid != null && patch.userId != currentUid
        }
        is PostgresAction.Delete -> {
            val deletedId = OrderRepository.extractRecordId(action.oldRecord) ?: return false
            if (ui.myOrders.any { it.id == deletedId }) {
                ui.myOrders = ui.myOrders.filterNot { it.id == deletedId }
                ui.invalidate()
            }
            true
        }
        else -> false
    }
}

internal fun MainActivity.refreshMyExp() {
    // EXP feature removed on buyer per user request
    ui.myExp = 0
    ui.myExpLoading = false
}

/** Buka layar riwayat EXP (halaman pertama). */
internal fun MainActivity.openExpHistory() {
    ui.expHistoryLimit = 20
    ui.expHistoryEnd = false
    ui.expHistoryError = null
    ui.go(Screen.EXP_HISTORY)
    loadExpPage()
}

/** Muat ulang halaman riwayat sesuai limit saat ini. */
internal fun MainActivity.loadExpPage() {
    if (!SupabaseRepository.hasSession()) {
        ui.expHistory = emptyList()
        ui.expHistoryError = null
        ui.expHistoryLoading = false
        ui.invalidate()
        return
    }
    expJob?.cancel()
    ui.expHistoryLoading = true
    ui.expHistoryError = null
    ui.invalidate()
    expJob = authScope.launch {
        val list = runCatching { ExpRepository.myHistory(ui.expHistoryLimit.toLong()) }.getOrNull()
        ui.expHistoryLoading = false
        if (list == null) {
            ui.expHistoryError = "Gagal memuat. Periksa koneksi."
        } else {
            ui.expHistory = list
            ui.expHistoryError = null
            ui.expHistoryEnd = list.size < ui.expHistoryLimit
        }
        ui.invalidate()
    }
}

/** Tambah 20 entri riwayat (lazy loading). */
internal fun MainActivity.loadMoreExp() {
    if (ui.expHistoryLoading || ui.expHistoryEnd) return
    ui.expHistoryLimit += 20
    loadExpPage()
}

internal fun MainActivity.startProductUpdates() {
    if (!SupabaseRepository.isConfigured()) return
    runCatching {
        productObserver?.close()
        productObserver = OrderRepository.observeProducts(authScope) { action ->
            if (!applyRealtimeProduct(action)) refreshProducts()
        }
    }
}

private fun MainActivity.applyRealtimeProduct(action: PostgresAction): Boolean {
    return when (action) {
        is PostgresAction.Insert, is PostgresAction.Update -> {
            val record = if (action is PostgresAction.Insert) action.record else (action as PostgresAction.Update).record
            val product = OrderRepository.decodeProductRecord(record) ?: return false
            val visible = product.available && product.name.isNotBlank() && product.priceIdr > 0 && product.category.isNotBlank()
            ui.products = if (visible) {
                if (ui.products.any { it.id == product.id }) {
                    ui.products.map { if (it.id == product.id) product else it }
                } else {
                    listOf(product) + ui.products
                }
            } else {
                ui.products.filterNot { it.id == product.id }
            }
            ui.invalidate()
            true
        }
        is PostgresAction.Delete -> {
            val deletedId = OrderRepository.extractRecordId(action.oldRecord) ?: return false
            ui.products = ui.products.filterNot { it.id == deletedId }
            ui.invalidate()
            true
        }
        else -> false
    }
}

internal fun MainActivity.refreshProducts(manual: Boolean = false) {
    if (!SupabaseRepository.isConfigured()) return
    productRefreshJob?.cancel()
    ui.productsRefreshing = true
    ui.productsLoading = ui.products.isEmpty()
    ui.productsError = null
    ui.invalidate()
    productRefreshJob = authScope.launch {
        try {
            ui.products = OrderRepository.fetchAvailableProducts()
            ui.productsError = null
            ui.productsLoading = false
            ui.invalidate()

            val remoteTop = runCatching { OrderRepository.fetchTopSelling3Days() }.getOrDefault(emptyMap())
            val merged = HashMap(remoteTop)
            ui.myOrders.forEach { o ->
                if (o.status != STATUS_CANCELLED) {
                    o.items.forEach { item ->
                        item.menuItemId?.takeIf { it.isNotBlank() }?.let { id ->
                            merged[id] = maxOf(merged[id] ?: 0, item.quantity)
                        }
                        val nameKey = item.itemName.trim().lowercase()
                        if (nameKey.isNotBlank()) {
                            merged[nameKey] = maxOf(merged[nameKey] ?: 0, item.quantity)
                        }
                    }
                }
            }
            ui.topSelling3d = merged
            if (manual) {
                refreshOrderingStatus()
                Toast.makeText(this@refreshProducts, "Menu diperbarui", Toast.LENGTH_SHORT).show()
            }
        } catch (error: Throwable) {
            ui.productsError = SupabaseRepository.errorMessage(error)
            if (manual) {
                Toast.makeText(this@refreshProducts, ui.productsError ?: "Gagal memuat menu", Toast.LENGTH_SHORT).show()
            }
        } finally {
            ui.productsLoading = false
            ui.productsRefreshing = false
            ui.invalidate()
        }
    }
}

internal fun MainActivity.loadFavorites() {
    val raw = appPrefs().getStringSet("buyer_favorite_ids", emptySet()) ?: emptySet()
    ui.favoriteIds.clear()
    ui.favoriteIds.addAll(raw)
}

internal fun MainActivity.toggleFavorite(productId: String) {
    if (productId.isBlank()) return
    val added = if (ui.favoriteIds.contains(productId)) {
        ui.favoriteIds.remove(productId)
        false
    } else {
        ui.favoriteIds.add(productId)
        true
    }
    appPrefs().edit().putStringSet("buyer_favorite_ids", HashSet(ui.favoriteIds)).apply()
    val name = ui.products.firstOrNull { it.id == productId }?.name ?: "Menu"
    Toast.makeText(
        this,
        if (added) "$name disimpan ke Favorit" else "$name dihapus dari Favorit",
        Toast.LENGTH_SHORT
    ).show()
    ui.invalidate()
}

internal fun MainActivity.addProductToOrder() {
    val product = ui.selectedProduct ?: return
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk untuk membuat pesanan.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    addToCart(product.id, ui.quantity)
}

/** Tambah ke keranjang; aturan: satu pesanan = tepat satu menu, jumlah 1. */
internal fun MainActivity.addToCart(productId: String, qty: Int = 1) {
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

internal fun MainActivity.setCartQty(productId: String, qty: Int) {
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

internal fun MainActivity.setCheckoutPayment(method: String) {
    if (method == "qris" && ui.cartStallQris.isNullOrBlank()) {
        Toast.makeText(this, "Toko ini belum memasang QRIS. Silakan bayar Tunai.", Toast.LENGTH_SHORT).show()
        ui.checkoutPayment = "cash"
        ui.invalidate()
        return
    }
    ui.checkoutPayment = if (method == "cash") "cash" else "qris"
    ui.invalidate()
}

/** Buka layar checkout: pastikan login + keranjang tidak kosong + ambil info warung. */
internal fun MainActivity.openCheckout() {
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
    // Pastikan cartStallId selalu terisi dari item keranjang.
    if (ui.cartStallId.isNullOrBlank()) {
        val firstPid = ui.cart.keys.firstOrNull()
        ui.cartStallId = firstPid?.let { pid ->
            ui.products.firstOrNull { it.id == pid }?.stallId
                ?: ui.sellerProducts.firstOrNull { it.id == pid }?.stallId
        }
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
    ui.placingOrder = false
    ui.checkoutPayment = "cash"
    ui.cartStallQris = null
    ui.go(Screen.CHECKOUT)
    refreshOrderingStatus()
    refreshStallAvailability()
    authScope.launch {
        val stallId = ui.cartStallId
        if (stallId.isNullOrBlank()) {
            ui.cartStallQris = ""
            ui.checkoutPayment = "cash"
            ui.invalidate()
            return@launch
        }
        val stall = withTimeoutOrNull(8_000L) {
            runCatching { OrderRepository.fetchStall(stallId) }.getOrNull()
        }
        ui.cartStallName = stall?.name ?: "Kantin Jajanin"
        ui.cartStallQris = stall?.qrisImageUrl?.trim().orEmpty()
        // Warung belum pasang QRIS = tunai saja, opsi QRIS disembunyikan.
        if (ui.cartStallQris.isNullOrBlank()) ui.checkoutPayment = "cash"
        ui.invalidate()
    }
}

/**
 * Status pemesanan dari server (flag blackout WIB + "Pesanan hari ini: X/2").
 * Hanya cerminan untuk UX; place_order di backend selalu memeriksa ulang.
 */
internal fun MainActivity.refreshOrderingStatus() {
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
internal fun MainActivity.refreshStallAvailability() {
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
internal fun MainActivity.applyServerTime(serverTime: String) {
    val clean = serverTime.replace('T', ' ').take(19)
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }.parse(clean)?.time
    }.getOrNull() ?: return
    ui.serverBaseMillis = parsed
    ui.serverBaseElapsed = android.os.SystemClock.elapsedRealtime()
    ui.invalidate()
}

/**
 * Buat order dari seluruh keranjang lewat RPC server-side.
 * Harga dihitung dari database; total client tidak dipercaya.
 */
internal fun MainActivity.placeCartOrder() {
    val self = this
    if (ui.placingOrder) return
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
    val requestedMethod = ui.checkoutPayment
    if (requestedMethod == "qris" && ui.cartStallQris.isNullOrBlank() && ui.cartStallQris != null) {
        ui.checkoutPayment = "cash"
        ui.invalidate()
        Toast.makeText(this, "Toko belum memasang QRIS. Silakan bayar Tunai.", Toast.LENGTH_SHORT).show()
        return
    }
    ui.placingOrder = true
    ui.invalidate()
    authScope.launch {
        try {
            val stallId = ui.cartStallId
            var verifiedQris = ui.cartStallQris
            if (requestedMethod == "qris" && verifiedQris == null && !stallId.isNullOrBlank()) {
                val stall = withTimeoutOrNull(6_000L) {
                    runCatching { OrderRepository.fetchStall(stallId) }.getOrNull()
                }
                verifiedQris = stall?.qrisImageUrl?.trim().orEmpty()
                ui.cartStallQris = verifiedQris
            }
            if (requestedMethod == "qris" && verifiedQris.isNullOrBlank()) {
                ui.checkoutPayment = "cash"
                Toast.makeText(self, "Toko ini belum memasang kode QRIS. Silakan pilih Tunai.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val method = if (requestedMethod == "qris") "qris" else "cash"
            ui.checkoutPayment = method
            val placed = withTimeout(12_000L) { OrderRepository.placeOrder(lines, method) }
            ui.cart.clear()
            ui.cartStallId = null
            ui.cartStallQris = null
            ui.lastOrderId = placed.id
            ui.selectedBuyerOrderId = placed.id
            ui.lastOrderPickup = placed.pickupCode
            ui.lastOrderTotal = placed.totalIdr
            ui.lastOrderMethod = placed.paymentMethod
            recordBuyerOrderSeed(placed.id, placed.status, placed.paymentStatus)
            if (method == "qris") {
                // Ambil QR warung lalu tampilkan layar bayar.
                ui.lastOrderStatus = placed.status
                openQrisPayment(placed.id, placed.totalIdr, stallId, verifiedQris)
            } else {
                refreshMyOrders()
                ui.lastOrderStatus = placed.status
                ui.go(Screen.READY)
                Toast.makeText(self, "Pesanan dibuat. Bayar tunai saat ambil.", Toast.LENGTH_LONG).show()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
            Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
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
internal fun MainActivity.openQrisPayment(
    orderId: String,
    amountIdr: Int,
    stallId: String?,
    preloadedQris: String? = null,
) {
    ui.qrisOrderId = orderId
    ui.qrisAmount = amountIdr
    val cachedUrl = preloadedQris?.trim()?.takeIf { it.isNotBlank() }
    if (cachedUrl != null) {
        ui.qrisImageUrl = cachedUrl
        ui.qrisLoading = false
        ui.go(Screen.QRIS)
        return
    }
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
internal fun MainActivity.submitPaymentProof() {
    val self = this
    val orderId = ui.qrisOrderId ?: return
    val previousOrders = ui.myOrders
    val previousStatus = ui.lastOrderStatus
    ui.myOrders = previousOrders.map { if (it.id == orderId) it.copy(paymentStatus = "waiting_verification") else it }
    ui.lastOrderStatus = "pending"
    ui.go(Screen.READY)
    authScope.launch {
        try {
            OrderRepository.submitPaymentProof(orderId)
            refreshMyOrders()
            Toast.makeText(self, "Menunggu verifikasi penjual.", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            ui.myOrders = previousOrders
            ui.lastOrderStatus = previousStatus
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Pembeli mengajukan bukti dari kartu riwayat pesanannya sendiri. */
internal fun MainActivity.submitPaymentProofFor(orderId: String) {
    val self = this
    val previousOrders = ui.myOrders
    ui.myOrders = previousOrders.map { if (it.id == orderId) it.copy(paymentStatus = "waiting_verification") else it }
    ui.invalidate()
    authScope.launch {
        try {
            OrderRepository.submitPaymentProof(orderId)
            refreshMyOrders()
            Toast.makeText(self, "Menunggu verifikasi penjual.", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            ui.myOrders = previousOrders
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Pembeli membatalkan pesanan miliknya sendiri selama masih berstatus 'pending' (sebelum dikonfirmasi penjual). */
internal fun MainActivity.cancelBuyerOrder(orderId: String) {
    val self = this
    val order = ui.myOrders.firstOrNull { it.id == orderId }
    val currentStatus = order?.status ?: if (ui.lastOrderId == orderId) ui.lastOrderStatus else null
    if (currentStatus != null && !currentStatus.equals(STATUS_PENDING, ignoreCase = true)) {
        Toast.makeText(this, "Pesanan sudah dikonfirmasi penjual dan tidak bisa dibatalkan.", Toast.LENGTH_LONG).show()
        return
    }
    val codeLabel = order?.pickupCode ?: ui.lastOrderPickup ?: orderId.take(6).uppercase()
    android.app.AlertDialog.Builder(this)
        .setTitle("Batalkan pesanan #$codeLabel?")
        .setMessage("Pesanan hanya dapat dibatalkan sebelum dikonfirmasi penjual.")
        .setPositiveButton("Batalkan Pesanan") { _, _ ->
            val previousOrders = ui.myOrders
            ui.myOrders = previousOrders.filterNot { it.id == orderId }
            if (ui.currentScreen == Screen.READY && (ui.selectedBuyerOrderId == orderId || ui.lastOrderId == orderId)) {
                ui.go(Screen.ORDERS)
            } else {
                ui.invalidate()
            }
            authScope.launch {
                try {
                    OrderRepository.cancelBuyerPendingOrder(orderId)
                    recordBuyerOrderSeed(orderId, STATUS_CANCELLED, order?.paymentStatus ?: "unpaid")
                    refreshMyOrders()
                    refreshOrderingStatus()
                    refreshProducts()
                    Toast.makeText(self, "Pesanan #$codeLabel berhasil dibatalkan & kuota dikembalikan.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    ui.myOrders = previousOrders
                    ui.invalidate()
                    refreshMyOrders()
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Kembali", null)
        .show()
}

/** Penjual menandai lunas (tunai saat ambil / verifikasi QRIS). */
internal fun MainActivity.showPhoneEditor(onSaved: (() -> Unit)? = null) {
    val self = this
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
                        val saved = runCatching { PhoneRepository.setMyPhone(raw) }
                            .getOrElse { PhoneRepository.normalizePhone(raw) ?: raw }
                        ui.myPhone = saved
                        ui.myStallId?.let { stallId ->
                            OrderRepository.syncStallContactMetadata(stallId, ui.myDisplayName, saved)
                        }
                        ui.invalidate()
                        dialog.dismiss()
                        Toast.makeText(self, "Nomor HP disimpan.", Toast.LENGTH_SHORT).show()
                        onSaved?.invoke()
                    } catch (error: Throwable) {
                        saveBtn.isEnabled = true
                        Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    input.requestFocus()
}

/** Gerbang wajib-HP sebelum memesan (akun lama tanpa nomor). */
internal fun MainActivity.showPhoneGate(onReady: () -> Unit) {
    android.app.AlertDialog.Builder(this).setTitle("Lengkapi nomor HP")
        .setMessage("Nomor HP wajib diisi sebelum memesan, untuk koordinasi pengambilan pesanan.")
        .setPositiveButton("Isi nomor") { _, _ -> showPhoneEditor { onReady() } }
        .setNegativeButton("Nanti", null)
        .show()
}

