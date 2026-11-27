package com.jajanin.app.feature.buyer

import com.jajanin.app.MainActivity
import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.chat.refreshChatList
import com.jajanin.app.feature.chat.refreshUnreadChats
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
import com.jajanin.app.feature.buyer.store_discovery.StoreSearchViewModel
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
            OrderRepository.expireUnpaidQrisOrders()
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

private var buyerTopSellingTick = 0

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
    if (buyerTopSellingTick++ % 5 == 0) {
        runCatching {
            val freshStats = OrderRepository.fetchTopSellingStats()
            if (freshStats != ui.topSellingStats) {
                ui.topSellingStats = freshStats
                ui.invalidate()
            }
        }
        if (SupabaseRepository.hasSession() && buyerTopSellingTick % 10 == 1) {
            StoreSearchViewModel.refreshStoreDirectory(this)
        }
    }
    if (SupabaseRepository.hasSession() && !ui.placingOrder) {
        val hasExpiredQris = ui.myOrders.any { o ->
            o.status.equals(STATUS_PENDING, ignoreCase = true) &&
                o.paymentMethod.equals("qris", ignoreCase = true) &&
                (o.paymentStatus == "unpaid" || o.paymentStatus == "rejected") &&
                (com.jajanin.app.data.repository.remainingPaymentSeconds(o.paymentExpiresAt) ?: 1L) <= 0L
        }
        if (hasExpiredQris) {
            runCatching { OrderRepository.expireUnpaidQrisOrders() }
        }
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
                val activeQrisId = ui.qrisOrderId
                if (ui.currentScreen == Screen.QRIS && !activeQrisId.isNullOrBlank()) {
                    val paidOrder = freshOrders.firstOrNull { it.id == activeQrisId && it.paymentStatus == "paid" }
                    if (paidOrder != null) {
                        onMidtransPaymentCompleted(paidOrder.id, paidOrder.status)
                        return@runCatching
                    }
                }
                ui.myOrdersLoading = false
                ui.invalidate()
            }
        }
        runCatching {
            val status = OrderRepository.fetchOrderingStatus()
            val changed = ui.orderingBlocked != status.blocked ||
                ui.orderingBlockMessage != status.blockMessage ||
                ui.ordersToday != status.ordersToday
            ui.orderingBlocked = status.blocked
            ui.orderingBlockMessage = status.blockMessage
            ui.ordersToday = status.ordersToday
            ui.orderingStatusLoading = false
            if (changed) ui.invalidate()
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
                        paymentProofUrl = patch.paymentProofUrl?.trim() ?: order.paymentProofUrl,
                        paymentExpiresAt = patch.paymentExpiresAt ?: order.paymentExpiresAt,
                        stockDeducted = patch.stockDeducted ?: order.stockDeducted,
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
            if (ui.currentScreen == Screen.QRIS && ui.qrisOrderId == patch.id && patch.paymentStatus == "paid") {
                onMidtransPaymentCompleted(patch.id, patch.status ?: ui.lastOrderStatus)
                return true
            }
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

            StoreSearchViewModel.refreshStoreDirectory(this@refreshProducts)
            val freshStats = OrderRepository.fetchTopSellingStats()
            ui.topSellingStats = freshStats
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
    val name = resolveKnownProduct(productId)?.name ?: "Menu"
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

internal fun MainActivity.resolveKnownProduct(productId: String): ProductRow? =
    ui.products.firstOrNull { it.id == productId }
        ?: ui.storeProfileProducts.firstOrNull { it.id == productId }
        ?: ui.sellerProducts.firstOrNull { it.id == productId }

internal fun MainActivity.cartProducts(): List<ProductRow> =
    ui.cart.keys.mapNotNull { pid -> resolveKnownProduct(pid) }

internal fun MainActivity.allowedCartOrderModes(): Set<String> {
    val items = cartProducts()
    if (items.isEmpty()) return setOf("same_day", "pre_order")
    val out = LinkedHashSet<String>()
    if (items.all { it.allowsSameDay }) out.add("same_day")
    if (items.all { it.allowsNextDay }) out.add("pre_order")
    return out
}

internal fun MainActivity.allowedCartPickupSessions(): List<String> {
    val items = cartProducts()
    if (items.isEmpty()) return com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS
    var common: Set<String>? = null
    for (p in items) {
        val set = p.parsedPickupSessions.toSet()
        common = if (common == null) set else common.intersect(set)
    }
    val ordered = com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS.filter { common?.contains(it) == true }
    if (ordered.isNotEmpty()) return ordered
    return common?.toList()?.takeIf { it.isNotEmpty() } ?: com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS
}

private fun MainActivity.allowedCartPoDates(): List<String> {
    var common: Set<String>? = null
    ui.cart.keys.forEach { pid ->
        val prod = resolveKnownProduct(pid) ?: return@forEach
        val dates = prod.openParsedPoDates
        if (prod.poDates.isNotBlank() || prod.orderSchedule == "next_day") {
            val s = dates.toSet()
            common = if (common == null) s else common?.intersect(s)
        }
    }
    return common?.sorted().orEmpty()
}

private fun MainActivity.cartMinPoCutoffHour(): Int =
    cartProducts().minOfOrNull { it.poCutoffHour }?.coerceIn(12, 23) ?: 20

private fun MainActivity.cartAllowsWeekendPo(): Boolean =
    cartProducts().isNotEmpty() && cartProducts().all { it.poAllowWeekend }

internal fun MainActivity.cartRequiresQrisForPo(pickupDate: String): Boolean {
    if (OrderRepository.daysAheadFromTodayWib(pickupDate) > 3) return true
    return cartProducts().any { it.poRequireQris }
}

private fun MainActivity.cartQrisRequiredReason(pickupDate: String): String =
    if (OrderRepository.daysAheadFromTodayWib(pickupDate) > 3) {
        "Pre-Order lebih dari 3 hari wajib pembayaran Midtrans di muka."
    } else {
        "Menu Pre-Order ini mewajibkan pembayaran Midtrans di muka."
    }

private fun MainActivity.syncCheckoutScheduleDefaults() {
    val modes = allowedCartOrderModes()
    if (ui.checkoutOrderMode !in modes) {
        ui.checkoutOrderMode = when {
            "same_day" in modes -> "same_day"
            "pre_order" in modes -> "pre_order"
            else -> "same_day"
        }
    }
    val sellerPoDates = allowedCartPoDates()
    val cutoff = cartMinPoCutoffHour()
    val allowWknd = cartAllowsWeekendPo()
    val defaultOpen = OrderRepository.nextOpenSchoolDatesWib(1, cutoff, allowWknd).firstOrNull()
        ?: OrderRepository.tomorrowWibDateString()

    if (sellerPoDates.isNotEmpty()) {
        if (ui.checkoutPickupDate !in sellerPoDates) {
            ui.checkoutPickupDate = sellerPoDates.first()
        }
    } else if (
        !OrderRepository.isValidPreOrderDate(ui.checkoutPickupDate) ||
        (!allowWknd && OrderRepository.isWeekendWib(ui.checkoutPickupDate)) ||
        OrderRepository.isPastPoCutoffWib(ui.checkoutPickupDate, cutoff)
    ) {
        ui.checkoutPickupDate = defaultOpen
    }
    if (ui.checkoutOrderMode == "pre_order" && cartRequiresQrisForPo(ui.checkoutPickupDate)) {
        ui.checkoutPayment = "qris"
    }
    val sessions = allowedCartPickupSessions()
    if (ui.checkoutPickupSession !in sessions) {
        ui.checkoutPickupSession = sessions.first()
    }
}

internal fun MainActivity.setCheckoutPickupDate(isoDate: String) {
    val clean = isoDate.trim()
    if (!OrderRepository.isValidPreOrderDate(clean)) {
        Toast.makeText(this, "Pilih tanggal Pre-Order antara besok sampai 30 hari ke depan.", Toast.LENGTH_SHORT).show()
        return
    }
    if (!cartAllowsWeekendPo() && OrderRepository.isWeekendWib(clean)) {
        Toast.makeText(this, "Pre-Order hanya tersedia pada hari sekolah (Senin–Jumat).", Toast.LENGTH_LONG).show()
        return
    }
    val cutoff = cartMinPoCutoffHour()
    if (OrderRepository.isPastPoCutoffWib(clean, cutoff)) {
        Toast.makeText(
            this,
            "Pemesanan PO untuk ${com.jajanin.app.data.repository.formatCalendarShortChip(clean)} sudah ditutup (maks. H-1 pukul ${cutoff}:00 WIB).",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val slot = ui.checkoutPoSlots[clean]
    if (slot != null && slot.closed) {
        Toast.makeText(this, slot.closeReason ?: "Tanggal PO ini sudah penuh atau ditutup.", Toast.LENGTH_LONG).show()
        return
    }
    val sellerPoDates = allowedCartPoDates()
    if (sellerPoDates.isNotEmpty() && clean !in sellerPoDates) {
        val avail = sellerPoDates.take(4).joinToString(", ") {
            com.jajanin.app.data.repository.formatCalendarShortChip(it)
        }
        Toast.makeText(this, "Penjual hanya membuka PO pada: $avail", Toast.LENGTH_LONG).show()
        return
    }
    ui.checkoutPickupDate = clean
    if ("pre_order" in allowedCartOrderModes()) {
        ui.checkoutOrderMode = "pre_order"
    }
    if (cartRequiresQrisForPo(clean)) {
        ui.checkoutPayment = "qris"
        Toast.makeText(this, cartQrisRequiredReason(clean), Toast.LENGTH_SHORT).show()
    }
    ui.invalidate()
}

internal fun MainActivity.showPreOrderDatePicker() {
    val wibTz = java.util.TimeZone.getTimeZone("Asia/Jakarta")
    val sellerPoDates = allowedCartPoDates()
    val minCal = java.util.Calendar.getInstance(wibTz).apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val maxCal = java.util.Calendar.getInstance(wibTz).apply {
        add(java.util.Calendar.DAY_OF_YEAR, 30)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59)
        set(java.util.Calendar.MILLISECOND, 999)
    }
    val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
        timeZone = wibTz
    }
    if (sellerPoDates.isNotEmpty()) {
        runCatching { isoFmt.parse(sellerPoDates.first()) }.getOrNull()?.let { firstDate ->
            minCal.time = firstDate
            minCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            minCal.set(java.util.Calendar.MINUTE, 0)
            minCal.set(java.util.Calendar.SECOND, 0)
            minCal.set(java.util.Calendar.MILLISECOND, 0)
        }
        runCatching { isoFmt.parse(sellerPoDates.last()) }.getOrNull()?.let { lastDate ->
            maxCal.time = lastDate
            maxCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            maxCal.set(java.util.Calendar.MINUTE, 59)
            maxCal.set(java.util.Calendar.SECOND, 59)
            maxCal.set(java.util.Calendar.MILLISECOND, 999)
        }
    }
    val curCal = java.util.Calendar.getInstance(wibTz)
    val parsedCur = runCatching { isoFmt.parse(ui.checkoutPickupDate) }.getOrNull()
    if (parsedCur != null && parsedCur.time in minCal.timeInMillis..maxCal.timeInMillis) {
        curCal.time = parsedCur
    } else {
        curCal.timeInMillis = minCal.timeInMillis
    }

    val themeRes = if (isDarkMode()) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }
    val picker = android.app.DatePickerDialog(
        this,
        themeRes,
        { _, year, month, dayOfMonth ->
            val picked = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
            setCheckoutPickupDate(picked)
        },
        curCal.get(java.util.Calendar.YEAR),
        curCal.get(java.util.Calendar.MONTH),
        curCal.get(java.util.Calendar.DAY_OF_MONTH),
    )
    picker.datePicker.minDate = minCal.timeInMillis
    picker.datePicker.maxDate = maxCal.timeInMillis
    picker.setTitle("Pilih Tanggal Pengambilan PO")
    picker.show()
}

/** Tambah ke keranjang; v2.0 mendukung multi-menu & qty > 1 dari 1 warung dengan jadwal kompatibel. */
internal fun MainActivity.addToCart(productId: String, qty: Int = 1) {
    if (ui.myAccountStatus == "suspended") {
        Toast.makeText(this, "Akun ditangguhkan. Kamu tidak bisa memesan.", Toast.LENGTH_LONG).show()
        return
    }
    val product = resolveKnownProduct(productId) ?: return
    if (!ui.myStallId.isNullOrBlank() && product.stallId == ui.myStallId) {
        Toast.makeText(this, "Kamu tidak bisa memesan dari warungmu sendiri.", Toast.LENGTH_SHORT).show()
        return
    }
    val storeClosed = (!product.stallId.isNullOrBlank() && ui.storeProfileDetail?.id == product.stallId && ui.storeProfileDetail?.isOpen == false) ||
        (!product.stallId.isNullOrBlank() && ui.storeDirectoryById[product.stallId]?.isOpen == false)
    if (storeClosed) {
        Toast.makeText(this, "Warung sedang tutup sementara.", Toast.LENGTH_SHORT).show()
        return
    }
    if (product.stock != null && product.stock <= 0) {
        Toast.makeText(this, "Stok ${product.name} sedang habis.", Toast.LENGTH_SHORT).show()
        return
    }
    val addAmount = qty.coerceAtLeast(1)
    if (ui.cart.isNotEmpty() && !ui.cartStallId.isNullOrBlank() && ui.cartStallId != product.stallId) {
        showThemedInfo(
            title = "Ganti warung di keranjang?",
            message = "Keranjangmu sedang berisi menu dari warung lain. Kosongkan keranjang dan ganti ke ${product.name}?",
            posLabel = "Ganti Warung",
            onPos = {
                ui.cart.clear()
                ui.cartStallId = null
                ui.cartStallQris = null
                addToCart(productId, addAmount)
            },
            negLabel = "Batal",
            destructivePos = true,
        )
        return
    }
    // Cek kecocokan jadwal & irisan tanggal PO (jangan campur menu khusus Hari Ini dengan PO atau 2 PO beda tanggal).
    val existing = cartProducts()
    if (existing.isNotEmpty()) {
        val candidateAll = existing + product
        val canSame = candidateAll.all { it.allowsSameDay }
        val canNext = candidateAll.all { it.allowsNextDay }
        if (!canSame && !canNext) {
            Toast.makeText(
                this,
                "Jadwal ${product.name} (${product.scheduleBadgeLabel}) tidak cocok dengan menu di keranjang.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!canSame && canNext) {
            var poIntersect: Set<String>? = null
            candidateAll.forEach { prod ->
                if (prod.poDates.isNotBlank() || prod.orderSchedule == "next_day") {
                    val s = prod.openParsedPoDates.toSet()
                    poIntersect = if (poIntersect == null) s else poIntersect?.intersect(s)
                }
            }
            if (poIntersect != null && poIntersect!!.isEmpty()) {
                Toast.makeText(
                    this,
                    "Tanggal PO ${product.name} tidak cocok dengan tanggal PO menu lain di keranjang.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }
    }
    val currentQty = ui.cart[productId] ?: 0
    val maxAllowed = product.stock?.coerceAtMost(99) ?: 99
    val nextQty = (currentQty + addAmount).coerceAtMost(maxAllowed)
    if (currentQty + addAmount > maxAllowed) {
        Toast.makeText(this, "Maksimal $maxAllowed porsi untuk ${product.name}.", Toast.LENGTH_SHORT).show()
    }
    ui.cartStallId = product.stallId
    ui.cart[productId] = nextQty
    syncCheckoutScheduleDefaults()
    Toast.makeText(this, "${product.name} (${nextQty}x) di keranjang", Toast.LENGTH_SHORT).show()
    ui.invalidate()
}

internal fun MainActivity.setCartQty(productId: String, qty: Int) {
    if (qty <= 0) {
        ui.cart.remove(productId)
        if (ui.cart.isEmpty()) {
            ui.cartStallId = null
            ui.cartStallQris = null
        } else {
            syncCheckoutScheduleDefaults()
        }
    } else {
        val product = resolveKnownProduct(productId)
        val maxAllowed = product?.stock?.takeIf { it > 0 }?.coerceAtMost(99) ?: 99
        val clamped = qty.coerceAtMost(maxAllowed)
        if (qty > maxAllowed) {
            Toast.makeText(this, "Stok maksimal $maxAllowed porsi.", Toast.LENGTH_SHORT).show()
        }
        ui.cart[productId] = clamped
    }
    ui.invalidate()
}

internal fun MainActivity.setCheckoutOrderMode(mode: String) {
    val target = if (mode == "pre_order") "pre_order" else "same_day"
    val allowed = allowedCartOrderModes()
    if (target !in allowed) {
        val msg = if (target == "same_day") {
            "Ada menu di keranjang yang hanya tersedia untuk Pre-Order."
        } else {
            "Ada menu di keranjang yang hanya tersedia untuk Hari Ini."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        return
    }
    ui.checkoutOrderMode = target
    if (target == "pre_order") {
        syncCheckoutScheduleDefaults()
        if (cartRequiresQrisForPo(ui.checkoutPickupDate)) {
            ui.checkoutPayment = "qris"
        }
    }
    ui.invalidate()
}

internal fun MainActivity.setCheckoutPayment(method: String) {
    if (method == "cash" && ui.checkoutOrderMode == "pre_order" && cartRequiresQrisForPo(ui.checkoutPickupDate)) {
        Toast.makeText(this, cartQrisRequiredReason(ui.checkoutPickupDate), Toast.LENGTH_LONG).show()
        ui.checkoutPayment = "qris"
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
        ui.cartStallId = firstPid?.let { pid -> resolveKnownProduct(pid)?.stallId }
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
    ui.cartStallQris = "midtrans_sandbox"
    ui.checkoutPoSlots = emptyMap()
    syncCheckoutScheduleDefaults()
    ui.go(Screen.CHECKOUT)
    refreshOrderingStatus()
    authScope.launch {
        val stallId = ui.cartStallId
        if (stallId.isNullOrBlank()) {
            ui.cartStallQris = "midtrans_sandbox"
            ui.invalidate()
            return@launch
        }
        val stall = withTimeoutOrNull(8_000L) {
            runCatching { OrderRepository.fetchStall(stallId) }.getOrNull()
        }
        ui.cartStallName = stall?.name ?: "Kantin Jajanin"
        ui.cartStallQris = "midtrans_sandbox"
        if (ui.checkoutOrderMode == "pre_order" && cartRequiresQrisForPo(ui.checkoutPickupDate)) {
            ui.checkoutPayment = "qris"
        }
        val firstPoPid = cartProducts().firstOrNull { it.allowsNextDay }?.id
        if (firstPoPid != null) {
            val avail = withTimeoutOrNull(6_000L) {
                OrderRepository.fetchPoSlotAvailability(firstPoPid)
            }
            if (avail != null) {
                ui.checkoutPoSlots = avail.dates
            }
        }
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
    ui.placingOrder = true
    ui.invalidate()
    authScope.launch {
        try {
            syncCheckoutScheduleDefaults()
            val chosenMode = ui.checkoutOrderMode
            val chosenSession = ui.checkoutPickupSession
            val chosenDate = if (chosenMode == "pre_order") ui.checkoutPickupDate else null
            val qrisMandatory = chosenMode == "pre_order" && chosenDate != null && cartRequiresQrisForPo(chosenDate)
            val method = if (qrisMandatory || requestedMethod == "qris") "qris" else "cash"
            ui.checkoutPayment = method
            val placed = withTimeout(12_000L) {
                OrderRepository.placeOrder(
                    lines = lines,
                    paymentMethod = method,
                    orderMode = chosenMode,
                    pickupSession = chosenSession,
                    pickupDate = chosenDate,
                )
            }
            ui.cart.clear()
            ui.cartStallId = null
            ui.cartStallQris = null
            ui.lastOrderId = placed.id
            ui.selectedBuyerOrderId = placed.id
            ui.lastOrderPickup = placed.pickupCode
            ui.lastOrderTotal = placed.totalIdr
            ui.lastOrderMethod = placed.paymentMethod
            recordBuyerOrderSeed(placed.id, placed.status, placed.paymentStatus)
            refreshUnreadChats()
            refreshChatList(silent = true)
            if (method == "qris") {
                ui.lastOrderStatus = placed.status
                refreshMyOrders()
                openMidtransPayment(
                    orderId = placed.id,
                    amountIdr = placed.totalIdr,
                    expiresAt = placed.paymentExpiresAt,
                )
            } else {
                refreshMyOrders()
                ui.lastOrderStatus = placed.status
                ui.go(Screen.READY)
                val schedMsg = if (placed.orderMode == "pre_order") {
                    val dateLabel = com.jajanin.app.data.repository.formatCalendarShortChip(placed.pickupDate.ifBlank { ui.checkoutPickupDate })
                    "Pre-Order $dateLabel (${placed.pickupSession}) dibuat. Bayar tunai saat ambil."
                } else {
                    "Pesanan hari ini (${placed.pickupSession}) dibuat. Bayar tunai saat ambil."
                }
                Toast.makeText(self, schedMsg, Toast.LENGTH_LONG).show()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
            Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
        } finally {
            ui.placingOrder = false
            ui.invalidate()
            refreshOrderingStatus()
        }
    }
}

/** Layar pembayaran Midtrans Sandbox: buat sesi QRIS dinamis / Snap + timer 15 menit + cek status otomatis. */
internal fun MainActivity.openMidtransPayment(
    orderId: String,
    amountIdr: Int,
    expiresAt: String? = null,
    forceSnap: Boolean = false,
) {
    val self = this
    ui.qrisOrderId = orderId
    ui.qrisAmount = amountIdr
    ui.qrisExpiresAt = expiresAt
    ui.qrisProofUrl = null
    ui.qrisProofUploading = false
    ui.qrisProofSubmitting = false
    ui.qrisImageUrl = null
    ui.qrisQrString = null
    ui.qrisRedirectUrl = null
    ui.qrisGatewayOrderId = null
    ui.qrisPaymentChannel = if (forceSnap) "snap" else "qris"
    ui.qrisError = null
    ui.qrisCheckingStatus = false
    ui.qrisSavingImage = false
    ui.qrisExpiryTriggeredForId = null
    ui.qrisLoading = true
    ui.go(Screen.QRIS)
    authScope.launch {
        try {
            val session = withTimeout(18_000L) {
                OrderRepository.createGatewayPayment(orderId = orderId, forceSnap = forceSnap)
            }
            if (ui.qrisOrderId != orderId) return@launch
            if (session.status.equals("paid", ignoreCase = true) ||
                session.status.equals("settlement", ignoreCase = true) ||
                session.status.equals("capture", ignoreCase = true)
            ) {
                self.onMidtransPaymentCompleted(orderId, STATUS_PENDING)
                return@launch
            }
            val fetchedQrUrl = session.qrUrl?.takeIf { it.isNotBlank() }
            ui.qrisImageUrl = fetchedQrUrl
            ui.qrisQrString = session.qrString?.takeIf { it.isNotBlank() }
            ui.qrisRedirectUrl = session.redirectUrl?.takeIf { it.isNotBlank() }
            ui.qrisGatewayOrderId = session.gatewayOrderId
            ui.qrisPaymentChannel = session.paymentChannel
            ui.qrisEnvironment = session.environment
            if (!session.expiresAt.isNullOrBlank()) ui.qrisExpiresAt = session.expiresAt
            ui.qrisLoading = false
            ui.qrisError = null
            if (fetchedQrUrl != null) loadProductImage(fetchedQrUrl)
            ui.invalidate()
            val fetchedRedirect = ui.qrisRedirectUrl
            if (!fetchedRedirect.isNullOrBlank()) {
                com.jajanin.app.feature.buyer.cart.MidtransSnapDialog(
                    activity = self,
                    orderId = orderId,
                    snapUrl = fetchedRedirect,
                    amountIdr = ui.qrisAmount,
                    pickupCode = ui.lastOrderPickup,
                    isSandbox = session.environment.equals("sandbox", ignoreCase = true),
                ).show()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (ui.qrisOrderId != orderId) return@launch
            val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
            ui.qrisLoading = false
            ui.qrisError = msg
            ui.invalidate()
            Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** Muat ulang / buat ulang tagihan Midtrans apabila permintaan sebelumnya gagal. */
internal fun MainActivity.retryCreateGatewayPayment() {
    val orderId = ui.qrisOrderId ?: return
    openMidtransPayment(orderId = orderId, amountIdr = ui.qrisAmount, expiresAt = ui.qrisExpiresAt, forceSnap = false)
}

/** Buka kembali layar Midtrans untuk pesanan yang masih belum dibayar dari riwayat pesanan. */
internal fun MainActivity.openPaymentProofForOrder(orderId: String) {
    val cached = ui.myOrders.firstOrNull { it.id == orderId }
    if (cached != null) {
        val rem = com.jajanin.app.data.repository.remainingPaymentSeconds(cached.paymentExpiresAt)
        if (rem != null && rem <= 0L) {
            onQrisTimerExpired(orderId)
            return
        }
        ui.lastOrderId = cached.id
        ui.selectedBuyerOrderId = cached.id
        ui.lastOrderPickup = cached.pickupCode
        ui.lastOrderTotal = cached.totalIdr
        ui.lastOrderMethod = cached.paymentMethod ?: "qris"
        ui.lastOrderStatus = cached.status
        if (cached.sellerStoreName.isNotBlank()) ui.cartStallName = cached.sellerStoreName
        openMidtransPayment(
            orderId = cached.id,
            amountIdr = cached.totalIdr,
            expiresAt = cached.paymentExpiresAt,
        )
        return
    }
    authScope.launch {
        val fresh = runCatching { OrderRepository.fetchOrderById(orderId) }.getOrNull() ?: return@launch
        ui.lastOrderId = fresh.id
        ui.selectedBuyerOrderId = fresh.id
        ui.lastOrderPickup = fresh.pickupCode
        ui.lastOrderTotal = fresh.totalIdr
        ui.lastOrderMethod = fresh.paymentMethod ?: "qris"
        ui.lastOrderStatus = fresh.status
        if (fresh.sellerStoreName.isNotBlank()) ui.cartStallName = fresh.sellerStoreName
        openMidtransPayment(
            orderId = fresh.id,
            amountIdr = fresh.totalIdr,
            expiresAt = fresh.paymentExpiresAt,
        )
    }
}

/** Cek status transaksi langsung ke Midtrans dan sinkronkan status pesanan secara atomik. */
internal fun MainActivity.checkGatewayPaymentStatus() {
    val self = this
    val orderId = ui.qrisOrderId ?: return
    if (ui.qrisCheckingStatus || ui.qrisLoading) return
    ui.qrisCheckingStatus = true
    ui.invalidate()
    authScope.launch {
        try {
            val res = withTimeout(15_000L) {
                OrderRepository.checkGatewayPaymentStatus(orderId)
            }
            ui.qrisCheckingStatus = false
            when {
                res.paymentStatus.equals("paid", ignoreCase = true) ||
                    res.status.equals("paid", ignoreCase = true) ||
                    res.status.equals("settlement", ignoreCase = true) ||
                    res.status.equals("capture", ignoreCase = true) -> {
                    self.onMidtransPaymentCompleted(orderId, STATUS_PENDING)
                }
                res.paymentStatus.equals("expired", ignoreCase = true) ||
                    res.paymentStatus.equals("failed", ignoreCase = true) ||
                    res.status.lowercase() in listOf("expired", "failed", "cancelled", "expire", "cancel", "deny") -> {
                    onQrisTimerExpired(orderId)
                }
                else -> {
                    res.qrUrl?.takeIf { it.isNotBlank() }?.let { qr ->
                        if (ui.qrisImageUrl.isNullOrBlank()) ui.qrisImageUrl = qr
                    }
                    ui.invalidate()
                    Toast.makeText(
                        self,
                        res.message?.takeIf { it.isNotBlank() } ?: "Menunggu pembayaran Midtrans (${res.status}).",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } catch (error: CancellationException) {
            ui.qrisCheckingStatus = false
            throw error
        } catch (error: Throwable) {
            ui.qrisCheckingStatus = false
            ui.invalidate()
            val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
            Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** Dipanggil saat webhook atau check-payment-status memastikan Midtrans sudah lunas. */
internal fun MainActivity.onMidtransPaymentCompleted(orderId: String, status: String) {
    ui.qrisLoading = false
    ui.qrisCheckingStatus = false
    ui.qrisError = null
    ui.lastOrderStatus = status.ifBlank { STATUS_PENDING }
    ui.selectedBuyerOrderId = orderId
    refreshMyOrders()
    refreshProducts()
    refreshChatList(silent = true)
    ui.go(Screen.READY)
    Toast.makeText(this, "Pembayaran Midtrans lunas otomatis. Pesanan diteruskan ke warung.", Toast.LENGTH_LONG).show()
}

/** Simpan gambar QRIS dinamis Midtrans ke galeri perangkat. */
internal fun MainActivity.saveQrisBitmapToGallery() {
    val self = this
    val qrUrl = ui.qrisImageUrl?.takeIf { it.isNotBlank() } ?: run {
        Toast.makeText(this, "Gambar QRIS belum siap disimpan.", Toast.LENGTH_SHORT).show()
        return
    }
    if (ui.qrisSavingImage) return
    ui.qrisSavingImage = true
    ui.invalidate()
    authScope.launch(Dispatchers.IO) {
        try {
            val bmp = com.jajanin.app.core.ui.ProductImageCache.images[qrUrl] ?: run {
                val conn = java.net.URL(qrUrl).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            } ?: error("Gagal mengunduh gambar QR.")

            val fileName = "Jajanin_Midtrans_${ui.lastOrderPickup ?: System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Jajanin")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = self.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Tidak bisa membuat file di galeri.")
            resolver.openOutputStream(uri)?.use { out ->
                if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                    error("Gagal menyimpan gambar QR.")
                }
            } ?: error("Gagal membuka stream galeri.")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            launch(Dispatchers.Main) {
                ui.qrisSavingImage = false
                ui.invalidate()
                Toast.makeText(self, "QRIS tersimpan di Galeri (Pictures/Jajanin).", Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            launch(Dispatchers.Main) {
                ui.qrisSavingImage = false
                ui.invalidate()
                Toast.makeText(self, error.message ?: "Gagal menyimpan QR ke galeri.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Salin QR string atau URL gambar QR ke clipboard untuk ditempel di aplikasi pembayaran / simulator. */
internal fun MainActivity.copyMidtransQrCode() {
    val payload = ui.qrisQrString?.takeIf { it.isNotBlank() }
        ?: ui.qrisImageUrl?.takeIf { it.isNotBlank() }
        ?: ui.qrisGatewayOrderId?.takeIf { it.isNotBlank() }
        ?: run {
            Toast.makeText(this, "Kode QRIS belum tersedia.", Toast.LENGTH_SHORT).show()
            return
        }
    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Midtrans QR", payload))
    Toast.makeText(this, "Kode QR Midtrans disalin ke clipboard.", Toast.LENGTH_SHORT).show()
}

/** Buka halaman resmi Midtrans Snap langsung di dalam aplikasi (In-App Popup). */
internal fun MainActivity.openMidtransSnapCheckout() {
    val orderId = ui.qrisOrderId ?: return
    val existingUrl = ui.qrisRedirectUrl?.takeIf { it.isNotBlank() }
    if (existingUrl != null) {
        com.jajanin.app.feature.buyer.cart.MidtransSnapDialog(
            activity = this,
            orderId = orderId,
            snapUrl = existingUrl,
            amountIdr = ui.qrisAmount,
            pickupCode = ui.lastOrderPickup,
            isSandbox = ui.qrisEnvironment.equals("sandbox", ignoreCase = true),
        ).show()
        return
    }
    openMidtransPayment(orderId = orderId, amountIdr = ui.qrisAmount, expiresAt = ui.qrisExpiresAt, forceSnap = true)
}

/** Selesaikan pembayaran Sandbox dalam 1 klik langsung di aplikasi tanpa harus copy-paste ke web simulator. */
internal fun MainActivity.openMidtransSandboxSimulator() {
    val self = this
    val orderId = ui.qrisOrderId ?: return
    if (ui.qrisCheckingStatus || ui.qrisLoading) return
    ui.qrisCheckingStatus = true
    ui.invalidate()
    authScope.launch {
        try {
            val res = withTimeout(15_000L) {
                OrderRepository.checkGatewayPaymentStatus(orderId = orderId, simulatePay = true)
            }
            ui.qrisCheckingStatus = false
            if (res.paymentStatus.equals("paid", ignoreCase = true) ||
                res.status.equals("paid", ignoreCase = true) ||
                res.status.equals("settlement", ignoreCase = true) ||
                res.status.equals("capture", ignoreCase = true)
            ) {
                self.onMidtransPaymentCompleted(orderId, STATUS_PENDING)
            } else {
                ui.invalidate()
                Toast.makeText(self, res.message ?: "Menunggu konfirmasi Midtrans.", Toast.LENGTH_SHORT).show()
            }
        } catch (error: CancellationException) {
            ui.qrisCheckingStatus = false
            throw error
        } catch (error: Throwable) {
            ui.qrisCheckingStatus = false
            ui.invalidate()
            val msg = (error as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(error)
            Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
        }
    }
}

private fun MainActivity.launchCustomTabUrl(url: String) {
    runCatching {
        val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabs.launchUrl(this, android.net.Uri.parse(url))
    }.onFailure {
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Tidak ada browser untuk membuka halaman pembayaran.", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Tampilkan foto bukti transfer lama (jika ada pada riwayat transaksi lama). */
internal fun MainActivity.showPaymentProofPreview(url: String, title: String = "Bukti Transfer") {
    val self = this
    val cached = com.jajanin.app.core.ui.ProductImageCache.images[url]
    if (cached != null) {
        val view = android.widget.ImageView(self).apply {
            setImageBitmap(cached)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 16)
        }
        android.app.AlertDialog.Builder(self)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Tutup", null)
            .show()
        return
    }
    Toast.makeText(self, "Memuat foto bukti bayar...", Toast.LENGTH_SHORT).show()
    authScope.launch(Dispatchers.IO) {
        val bmp = runCatching {
            val conn = java.net.URL(url).openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
            conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
        launch(Dispatchers.Main) {
            if (bmp != null) {
                com.jajanin.app.core.ui.ProductImageCache.images[url] = bmp
                val view = android.widget.ImageView(self).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(24, 24, 24, 16)
                }
                android.app.AlertDialog.Builder(self)
                    .setTitle(title)
                    .setView(view)
                    .setPositiveButton("Tutup", null)
                    .show()
            } else {
                Toast.makeText(self, "Foto bukti tidak bisa dimuat.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Dipanggil otomatis saat timer pembayaran Midtrans (15 menit) habis di layar pembeli. */
internal fun MainActivity.onQrisTimerExpired(orderId: String) {
    if (ui.qrisExpiryTriggeredForId == orderId) return
    ui.qrisExpiryTriggeredForId = orderId
    val self = this
    authScope.launch {
        runCatching { OrderRepository.expireUnpaidQrisOrders() }
        refreshMyOrders()
        refreshProducts()
        Toast.makeText(
            self,
            "Batas waktu pembayaran Midtrans habis. Pesanan otomatis dibatalkan.",
            Toast.LENGTH_LONG,
        ).show()
        if (ui.currentScreen == Screen.QRIS && ui.qrisOrderId == orderId) {
            ui.go(Screen.ORDERS)
        }
    }
}

/** Pembeli membatalkan pesanan miliknya sendiri selama masih berstatus 'pending' (sebelum dikonfirmasi penjual). */
internal fun MainActivity.cancelBuyerOrder(orderId: String) {
    val self = this
    val order = ui.myOrders.firstOrNull { it.id == orderId }
    if (order?.isPreOrder == true) {
        Toast.makeText(
            this,
            "Pesanan Pre-Order tidak dapat dibatalkan oleh pembeli. Hubungi penjual lewat chat jika ada kendala.",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    if (order != null && !com.jajanin.app.data.repository.buyerCanCancelOrder(order)) {
        val msg = if (order.paymentStatus.equals("paid", ignoreCase = true)
            || order.paymentStatus.equals("waiting_verification", ignoreCase = true)
            || order.paymentProofUrl.isNotBlank()
        ) {
            "Pesanan yang sudah dibayar melalui Midtrans tidak dapat dibatalkan."
        } else {
            "Pesanan sudah dikonfirmasi penjual dan tidak bisa dibatalkan."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        return
    }
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

