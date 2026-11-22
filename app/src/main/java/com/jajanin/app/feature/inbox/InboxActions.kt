package com.jajanin.app.feature.inbox

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
import com.jajanin.app.feature.chat.openConversationById
import com.jajanin.app.notifications.NotificationHelper
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.jajanin.app.R

internal fun MainActivity.inboxObserverClose() {
    ui.inboxObserver?.close()
    ui.inboxObserver = null
}

/** Muat ulang seluruh dasbor developer: statistik real + users + orders + warnings + announcements. */
internal fun MainActivity.startInboxObserver() {
    if (ui.myRole == "developer") return
    inboxObserverClose()
    if (!SupabaseRepository.isConfigured()) return
    runCatching {
        ui.inboxObserver = DeveloperRepository.observeInbox(authScope) { refreshInbox() }
    }
}

/** Kotak masuk: peringatan developer + pengumuman + undangan seller. Membuka = menandai dibaca. */
internal fun MainActivity.refreshInbox() {
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

internal fun MainActivity.showInbox() {
    val self = this
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
            android.app.AlertDialog.Builder(self).setTitle("Notifikasi")
                .setMessage("Tidak ada peringatan atau pengumuman.")
                .setPositiveButton("Tutup", null).show()
            return@launch
        }
        android.app.AlertDialog.Builder(self).setTitle("Notifikasi")
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
                        android.app.AlertDialog.Builder(self)
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
internal fun MainActivity.confirmInviteSeller(targetUserId: String) {
    val self = this
    val user = ui.devUsers.firstOrNull { it.id == targetUserId }
    android.app.AlertDialog.Builder(this)
        .setTitle("Undang jadi penjual?")
        .setMessage("${user?.displayName ?: "Pengguna"} (${user?.email ?: "-"}) akan menerima undangan di Notifikasi. Bila diterima, akunnya berubah jadi penjual.")
        .setPositiveButton("Undang") { _, _ ->
            authScope.launch {
                try {
                    DeveloperRepository.inviteSeller(targetUserId)
                    Toast.makeText(self, "Undangan terkirim.", Toast.LENGTH_SHORT).show()
                    refreshDeveloper()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/** Developer membatalkan undangan pending. */
internal fun MainActivity.confirmCancelInvite(inviteId: String) {
    val self = this
    android.app.AlertDialog.Builder(this)
        .setTitle("Batalkan undangan?")
        .setMessage("Undangan yang menunggu akan dibatalkan dan hilang dari notifikasi pembeli.")
        .setPositiveButton("Batalkan") { _, _ ->
            authScope.launch {
                try {
                    DeveloperRepository.cancelInvite(inviteId)
                    Toast.makeText(self, "Undangan dibatalkan.", Toast.LENGTH_SHORT).show()
                    refreshDeveloper()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Kembali", null)
        .show()
}

/** Verifikasi pembeli dari Notifikasi: terima = jadi penjual, tolak = tetap pembeli. */
internal fun MainActivity.showInviteVerify(inviteId: String) {
    showThemedInfo(
        title = "Undangan jadi penjual",
        message = "Developer Jajanin mengundangmu membuka toko dan mengelola menu + pesanan kantin.\n\nTerima undangan ini?",
        posLabel = "Terima",
        onPos = { acceptSellerInvite(inviteId) },
        negLabel = "Tolak",
        onNeg = { declineSellerInvite(inviteId) },
    )
}

internal fun MainActivity.acceptSellerInvite(inviteId: String) {
    val self = this
    authScope.launch {
        try {
            DeveloperRepository.acceptInvite(inviteId)
            // Undangan diterima = unlock perangkat ini juga (flag server
            // seller_invited jadi bukti di HP lain).
            sellerUnlocked = true
            Toast.makeText(self, "Selamat! Akunmu sekarang penjual.", Toast.LENGTH_LONG).show()
            refreshInbox()
            // Rute ulang sesuai role baru: langsung nama toko + dashboard.
            routeSignedInUser()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            refreshInbox()
        }
    }
}

internal fun MainActivity.declineSellerInvite(inviteId: String) {
    val self = this
    authScope.launch {
        try {
            DeveloperRepository.declineInvite(inviteId)
            Toast.makeText(self, "Undangan ditolak. Kamu tetap sebagai pembeli.", Toast.LENGTH_SHORT).show()
            refreshInbox()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            refreshInbox()
        }
    }
}

/** Lonceng -> halaman Notifikasi (tujuan nyata, bukan popup). */
internal fun MainActivity.openNotif() {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk melihat notifikasi.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    ui.unreadOrderNotifCount = 0
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
    if (ui.myRole == "seller") refreshAdminOrders() else refreshMyOrders()
}

/** Detail satu peringatan dari halaman Notifikasi (buka = tandai dibaca). */
internal fun MainActivity.showNotifWarning(warningId: String) {
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
internal fun MainActivity.showNotifAnn(annId: String) {
    val a = ui.myAnnouncements.firstOrNull { it.id == annId } ?: return
    showThemedInfo(
        title = a.title.ifBlank { "Pengumuman" },
        message = "${a.message}\n\nTanggal: ${a.createdAt.take(16).replace('T', ' ')}",
    )
}

// ================= Nomor HP =================

/** Editor nomor HP sendiri (validasi + normalisasi server). */
internal fun MainActivity.handleOrderNotification(intent: Intent?) {
    val chatConvoId = intent?.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
        ?.takeIf { it.isNotBlank() }
    if (chatConvoId != null) {
        intent.removeExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
        intent.removeExtra(NotificationHelper.EXTRA_ORDER_ID)
        pendingChatConversationId = chatConvoId
        authScope.launch {
            if (!SupabaseRepository.awaitLoggedIn()) return@launch
            if (pendingChatConversationId != chatConvoId) return@launch
            val role = SupabaseRepository.currentRole()
            if (role == "seller" && !isSellerAdmitted()) {
                requirePrivilegedAccess("seller") {
                    pendingChatConversationId = chatConvoId
                    enterSellerDashboard()
                }
                return@launch
            }
            pendingChatConversationId = null
            openConversationById(chatConvoId)
        }
        return
    }

    val orderId = intent?.getStringExtra(NotificationHelper.EXTRA_ORDER_ID) ?: return
    intent.removeExtra(NotificationHelper.EXTRA_ORDER_ID)
    pendingOrderNotificationId = orderId
    authScope.launch {
        if (!SupabaseRepository.awaitLoggedIn()) return@launch
        if (SupabaseRepository.currentRole() != "seller") {
            if (pendingOrderNotificationId != orderId) return@launch
            pendingOrderNotificationId = null
            ui.selectedBuyerOrderId = orderId
            ui.lastOrderId = orderId
            refreshMyOrders()
            ui.go(Screen.READY)
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

