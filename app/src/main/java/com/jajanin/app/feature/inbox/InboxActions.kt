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
        ui.myInvites = emptyList()
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
        ui.myWarnings = warnings
        ui.myAnnouncements = anns
        ui.myInvites = emptyList()
        ui.invalidate()
        val items = ArrayList<String>()
        val warnIds = ArrayList<String?>()
        warnings.forEach { w ->
            items.add("[${w.severity}] ${w.title} — ${w.message.take(60)}${if (w.isRead) "" else " (BARU)"}")
            warnIds.add(w.id)
        }
        anns.forEach { a ->
            items.add("[Info ${a.targetRole}] ${a.title} — ${a.message.take(60)}")
            warnIds.add(null)
        }
        if (items.isEmpty()) {
            android.app.AlertDialog.Builder(self).setTitle("Notifikasi")
                .setMessage("Tidak ada peringatan atau pengumuman.")
                .setPositiveButton("Tutup", null).show()
            return@launch
        }
        android.app.AlertDialog.Builder(self).setTitle("Notifikasi")
            .setItems(items.toTypedArray()) { _, which ->
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
        // Developer melihat riwayat peringatan & pengumuman yang ia kirim.
        authScope.launch {
            ui.devWarnings = runCatching { DeveloperRepository.fetchWarningHistory() }.getOrDefault(emptyList())
            ui.devAnnouncements = runCatching { DeveloperRepository.fetchAnnouncements() }.getOrDefault(emptyList())
            ui.devInvites = emptyList()
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

/** Menangkap intent tap dari push notification Android (cold start & warm start). */
internal fun MainActivity.handleOrderNotification(intent: Intent?) {
    if (intent == null) return
    val chatConvoId = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val orderId = intent.getStringExtra(NotificationHelper.EXTRA_ORDER_ID)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val stallId = intent.getStringExtra(NotificationHelper.EXTRA_STALL_ID)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val targetRole = intent.getStringExtra(NotificationHelper.EXTRA_TARGET_ROLE)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    val targetScreen = intent.getStringExtra(NotificationHelper.EXTRA_TARGET_SCREEN)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    val eventType = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_TYPE)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    if (chatConvoId == null && orderId == null && stallId == null && targetScreen == null) {
        return
    }

    // Bersihkan extra dari Intent agar tidak terpicu ulang saat konfigurasi berubah.
    intent.removeExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
    intent.removeExtra(NotificationHelper.EXTRA_ORDER_ID)
    intent.removeExtra(NotificationHelper.EXTRA_STALL_ID)
    intent.removeExtra(NotificationHelper.EXTRA_TARGET_ROLE)
    intent.removeExtra(NotificationHelper.EXTRA_TARGET_SCREEN)
    intent.removeExtra(NotificationHelper.EXTRA_EVENT_TYPE)

    val resolvedScreen = targetScreen ?: when {
        chatConvoId != null -> "chat"
        orderId != null -> "order_detail"
        stallId != null && eventType == "store_activity" -> "store_profile"
        else -> "notif"
    }
    val resolvedRole = targetRole ?: when (eventType) {
        "new_order", "order_cancelled_by_buyer" -> "seller"
        "order_created", "order_status" -> "buyer"
        else -> null
    }

    pendingChatConversationId = chatConvoId
    pendingOrderNotificationId = orderId
    pendingPushStallId = stallId
    pendingPushTargetRole = resolvedRole
    pendingPushTargetScreen = resolvedScreen

    // Jika sesi & routing layar sudah aktif (warm start / onNewIntent), navigasi langsung;
    // jika masih cold start, `routeSignedInUser()` akan memanggil `consumePendingPushNavigation()`.
    if (SupabaseRepository.hasSession() && ui.myRole != null) {
        authScope.launch {
            consumePendingPushNavigation()
        }
    }
}

/**
 * Mengeksekusi deep-link push notification yang tertunda secara deterministik (1 jalur tunggal)
 * setelah sesi login & profil user selesai dimuat.
 */
internal suspend fun MainActivity.consumePendingPushNavigation() {
    val screen = pendingPushTargetScreen ?: when {
        pendingChatConversationId != null -> "chat"
        pendingOrderNotificationId != null -> "order_detail"
        pendingPushStallId != null -> "store_profile"
        else -> null
    } ?: return

    val chatConvoId = pendingChatConversationId
    val orderId = pendingOrderNotificationId
    val stallId = pendingPushStallId
    val targetRole = pendingPushTargetRole

    pendingChatConversationId = null
    pendingOrderNotificationId = null
    pendingPushStallId = null
    pendingPushTargetRole = null
    pendingPushTargetScreen = null

    if (!SupabaseRepository.awaitLoggedIn()) return

    when (screen) {
        "chat" -> {
            val cid = chatConvoId?.takeIf { it.isNotBlank() } ?: return
            openConversationById(cid)
        }
        "order_detail" -> {
            val oid = orderId?.takeIf { it.isNotBlank() } ?: return
            val prof = SupabaseRepository.fetchMyAccountProfile()
            val isSeller = prof?.isSellerVerified == true || prof?.role == "seller" || ui.myIsSellerVerified
            ui.myIsSellerVerified = isSeller

            val openAsSeller = when (targetRole) {
                "seller" -> isSeller
                "buyer" -> false
                else -> isSeller && (ui.myRole == "seller" || ui.myOrders.none { it.id == oid })
            }

            if (!openAsSeller) {
                if (ui.myRole == "seller") {
                    ui.myRole = "buyer"
                    ui.isSellerProfile = false
                }
                ui.selectedBuyerOrderId = oid
                ui.lastOrderId = oid
                refreshMyOrders()
                ui.go(Screen.READY)
                return
            }

            ui.myRole = "seller"
            ui.isSellerProfile = false
            if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.showAdmin()
            ensureSellerStallName {
                if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.showAdmin()
                startAdminOrders()
                refreshSellerProducts()
                openOrderDetail(oid)
            }
        }
        "store_profile" -> {
            val sid = stallId?.takeIf { it.isNotBlank() } ?: ui.myStallId?.takeIf { it.isNotBlank() }
            if (sid != null) {
                com.jajanin.app.feature.buyer.store_profile.StoreProfileViewModel.openStoreProfile(this, sid)
            } else {
                openNotif()
            }
        }
        else -> {
            if (targetRole == "seller" && ui.myIsSellerVerified && ui.myRole != "seller") {
                ui.myRole = "seller"
                ui.isSellerProfile = false
                startAdminOrders()
            }
            openNotif()
        }
    }
}

