package com.jajanin.app.feature.developer

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.jajanin.app.R

internal fun MainActivity.enterDeveloper() {
    val self = this
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
            Toast.makeText(self, "Akun ini bukan developer.", Toast.LENGTH_LONG).show()
            return@launch
        }
        ui.myRole = "developer"
        ui.showDev()
        startDeveloper()
        refreshInbox()
    }
}

internal fun MainActivity.startDeveloper() {
    devObserver?.close()
    inboxObserverClose()
    if (!SupabaseRepository.isConfigured()) return
    ensureOnlinePresence("developer")
    runCatching {
        devObserver = DeveloperRepository.observePlatform(authScope) { refreshDeveloper() }
        ui.inboxObserver = DeveloperRepository.observeInbox(authScope) { refreshInbox() }
    }
    refreshDeveloper()
}

internal fun MainActivity.refreshDeveloper() {
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
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
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

internal fun MainActivity.showDevSearch() {
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
internal fun MainActivity.showWarningForm(targetUserId: String) {
    val self = this
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
                    Toast.makeText(self, "Peringatan terkirim.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/** Pilih target dulu bila tombol + di tab Warn ditekan tanpa user terpilih. */
internal fun MainActivity.showWarnPicker() {
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

internal fun MainActivity.showAnnouncementForm() {
    val self = this
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
                    Toast.makeText(self, "Pengumuman terkirim.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/** Aksi pembatasan akun: active / warned / suspended. Tidak pernah menghapus user. */
internal fun MainActivity.setDevUserStatus(userId: String, status: String) {
    val self = this
    val label = if (status == "suspended") "Tangguhkan akun ini?" else "Aktifkan kembali akun ini?"
    android.app.AlertDialog.Builder(this).setTitle(label)
        .setMessage(if (status == "suspended") "User tidak bisa membuat order/produk baru. Data lama tetap utuh." else "User bisa bertransaksi lagi.")
        .setPositiveButton(if (status == "suspended") "Tangguhkan" else "Aktifkan") { _, _ ->
            authScope.launch {
                try {
                    DeveloperRepository.setAccountStatus(userId, status)
                    refreshDeveloper()
                    Toast.makeText(self, "Status akun diperbarui.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.toggleDevStall(stallId: String, open: Boolean) {
    val self = this
    authScope.launch {
        try {
            DeveloperRepository.toggleStall(stallId, open)
            refreshDeveloper()
            Toast.makeText(self, if (open) "Warung dibuka." else "Warung ditutup.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
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
internal fun MainActivity.confirmDailyReset() {
    if (ui.devResetting) return
    android.app.AlertDialog.Builder(this).setTitle("Reset harian monitor?")
        .setMessage("Order aktif akan diselesaikan, riwayat warn/info dibersihkan, dan hitungan ⚠ dinolkan. Riwayat completed/cancelled tetap tersimpan.")
        .setPositiveButton("Reset") { _, _ -> doDailyReset() }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.doDailyReset() {
    val self = this
    if (ui.devResetting) return
    ui.devResetting = true
    ui.invalidate()
    authScope.launch {
        try {
            val r = DeveloperRepository.dailyResetMonitor()
            refreshDeveloper()
            Toast.makeText(
                self,
                "Reset selesai: ${r.completedOrders} order ditutup, ${r.clearedWarnings} warn + ${r.clearedAnnouncements} info dibersihkan, ${r.resetProfiles} akun dinolkan.",
                Toast.LENGTH_LONG,
            ).show()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        } finally {
            ui.devResetting = false
            ui.invalidate()
        }
    }
}

/** Konfirmasi & eksekusi reset jatah pesanan harian semua pembeli ke 0/2. */
internal fun MainActivity.confirmResetAllQuotas() {
    val self = this
    android.app.AlertDialog.Builder(this)
        .setTitle("Reset jatah pesanan semua buyer?")
        .setMessage("Kuota pesanan harian (maks 2/hari) untuk semua akun pembeli akan dikembalikan ke 0/2. Riwayat order & omzet tetap utuh.")
        .setPositiveButton("Reset Jatah") { _, _ ->
            authScope.launch {
                try {
                    val count = DeveloperRepository.resetAllBuyerQuotas()
                    refreshDeveloper()
                    Toast.makeText(self, "Jatah pesanan $count pembeli berhasil direset ke 0/2.", Toast.LENGTH_LONG).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/** Konfirmasi & eksekusi reset jatah pesanan harian untuk satu pembeli tertentu. */
internal fun MainActivity.confirmResetUserQuota(userId: String) {
    val self = this
    val user = ui.devUsers.firstOrNull { it.id == userId }
    val name = user?.displayName ?: "pembeli ini"
    android.app.AlertDialog.Builder(this)
        .setTitle("Reset jatah pesanan?")
        .setMessage("Kembalikan kuota pesanan harian $name ke 0/2 supaya bisa memesan lagi hari ini.")
        .setPositiveButton("Reset Jatah") { _, _ ->
            authScope.launch {
                try {
                    DeveloperRepository.resetBuyerQuota(userId)
                    refreshDeveloper()
                    Toast.makeText(self, "Jatah pesanan $name direset ke 0/2.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

// ================= Buyer/seller inbox =================

/** Realtime ringan untuk badge inbox buyer/seller (peringatan + pengumuman). */
