package com.jajanin.app.feature.developer

import com.jajanin.app.MainActivity
import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.inbox.*

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import com.jajanin.app.core.ui.adaptiveDialogWidthPx
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
import com.jajanin.app.data.repository.StudentPasswordResetResult
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.exp.EXP_MAX_LEVEL
import com.jajanin.app.feature.buyer.exp.ExpEntry
import com.jajanin.app.feature.buyer.exp.ExpRepository
import com.jajanin.app.feature.buyer.exp.expLevel
import com.jajanin.app.feature.buyer.exp.expNextAt
import com.jajanin.app.feature.buyer.store_discovery.StoreDiscoveryRepository
import com.jajanin.app.feature.buyer.store_discovery.StoreSearchViewModel
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
    ui.devReportsLoading = ui.devReports.isEmpty()
    ui.devError = null
    ui.invalidate()
    devRefreshJob = authScope.launch {
        try {
            ui.devStats = DeveloperRepository.fetchStats()
            ui.devUsers = DeveloperRepository.fetchUsers()
            ui.devOrders = DeveloperRepository.fetchAllOrders()
            ui.devWarnings = DeveloperRepository.fetchWarningHistory()
            ui.devAnnouncements = DeveloperRepository.fetchAnnouncements()
            ui.devReports = com.jajanin.app.data.repository.FeedbackRepository.fetchAllReportsForDev()
            ui.devInvites = emptyList()
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
            ui.devReportsLoading = false
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
            StoreSearchViewModel.refreshStoreDirectory(self)
            Toast.makeText(self, if (open) "Warung dibuka." else "Warung ditutup.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Audited Developer action to revoke or restore a store's Jajanin Verified badge. */
internal fun MainActivity.showDevStallVerificationDialog(stallId: String, revoke: Boolean) {
    val self = this
    val seller = ui.devUsers.firstOrNull { it.stallId == stallId }
    val storeTitle = seller?.stallName ?: "Warung ini"
    val input = EditText(this).apply {
        hint = "Tulis alasan audit (wajib diisi, min. 3 karakter)"
        minLines = 2
        gravity = android.view.Gravity.TOP
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 20, 48, 8)
        addView(input)
    }
    val dialogTitle = if (revoke) "Cabut Verifikasi $storeTitle?" else "Pulihkan Verifikasi $storeTitle?"
    val dialogMsg = if (revoke) {
        "Lencana Terverifikasi akan dicabut dari pencarian & profil publik warung. Tindakan ini dicatat di log audit."
    } else {
        "Pencabutan manual dibersihkan. Lencana otomatis aktif bila warung memiliki minimal 20 pesanan selesai."
    }
    android.app.AlertDialog.Builder(this)
        .setTitle(dialogTitle)
        .setMessage(dialogMsg)
        .setView(box)
        .setPositiveButton(if (revoke) "Cabut Verifikasi" else "Pulihkan") { _, _ ->
            val reason = input.text.toString().trim()
            if (reason.length < 3) {
                Toast.makeText(self, "Alasan audit wajib diisi minimal 3 karakter.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            authScope.launch {
                try {
                    StoreDiscoveryRepository.developerSetStoreVerification(
                        stallId = stallId,
                        revoke = revoke,
                        reason = reason,
                    )
                    refreshDeveloper()
                    StoreSearchViewModel.refreshStoreDirectory(self)
                    Toast.makeText(
                        self,
                        if (revoke) "Verifikasi warung dicabut & tercatat di log audit." else "Status verifikasi warung dipulihkan.",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
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
        .setMessage("Order aktif akan diselesaikan, riwayat warn/info dibersihkan, dan hitungan peringatan dinolkan. Riwayat completed/cancelled tetap tersimpan.")
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

/** Developer memperbarui status laporan penjual (reviewing / resolved / dismissed) dengan catatan opsional. */
internal fun MainActivity.updateDevSellerReportStatus(reportId: String, nextStatus: String) {
    val self = this
    val statusTitle = when (nextStatus.lowercase()) {
        "reviewing" -> "Tandai Ditinjau"
        "resolved" -> "Tandai Selesai"
        "dismissed" -> "Tolak Laporan"
        else -> "Perbarui Status"
    }
    if (nextStatus.equals("reviewing", ignoreCase = true)) {
        authScope.launch {
            try {
                com.jajanin.app.data.repository.FeedbackRepository.updateSellerReportForDev(reportId, "reviewing", "")
                refreshDeveloper()
                Toast.makeText(self, "Laporan ditandai sedang ditinjau.", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
        return
    }
    val noteInput = EditText(this).apply {
        hint = "Catatan tindak lanjut developer (opsional)"
        minLines = 2
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 20, 48, 8)
        addView(noteInput)
    }
    android.app.AlertDialog.Builder(this)
        .setTitle(statusTitle)
        .setMessage("Tambahkan catatan hasil penanganan laporan jika diperlukan.")
        .setView(box)
        .setPositiveButton("Simpan") { _, _ ->
            val note = noteInput.text.toString().trim()
            authScope.launch {
                try {
                    com.jajanin.app.data.repository.FeedbackRepository.updateSellerReportForDev(reportId, nextStatus, note)
                    refreshDeveloper()
                    Toast.makeText(self, "Status laporan diperbarui.", Toast.LENGTH_SHORT).show()
                } catch (error: Throwable) {
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/**
 * Dialog developer untuk mereset kata sandi akun siswa (baik dari baris user terpilih
 * maupun langsung mengetik NIS siswa dari tab Overview).
 */
internal fun MainActivity.showDevResetPasswordDialog(targetUserId: String? = null) {
    val self = this
    val targetUser = targetUserId?.let { id -> ui.devUsers.firstOrNull { it.id == id } }
    if (targetUser != null && targetUser.role == "developer") {
        Toast.makeText(this, "Akun developer tidak dapat direset lewat menu ini.", Toast.LENGTH_LONG).show()
        return
    }

    val d = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()
    val dark = ui.darkMode
    val surfaceBg = if (dark) Color.rgb(22, 28, 44) else Color.WHITE
    val fieldBg = if (dark) Color.rgb(15, 20, 34) else Color.rgb(244, 246, 250)
    val ink = ui.kit.ink
    val muted = ui.kit.muted
    val blue = ui.kit.blue
    val border = ui.kit.border
    val danger = ui.kit.danger

    fun roundedBg(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(fill)
            if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(muted)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(5)
        }
    }

    fun styledInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(ink)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = roundedBg(fieldBg, 12f, border)
        minHeight = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(surfaceBg, 20f, border)
        setPadding(dp(20), dp(20), dp(20), dp(16))
    }

    card.addView(TextView(this).apply {
        text = "Reset Sandi Akun Siswa"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    })

    card.addView(TextView(this).apply {
        text = "Kata sandi baru langsung aktif sehingga siswa bisa masuk kembali memakai NIS dan sandi baru."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(muted)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
    })

    var nisInput: EditText? = null
    if (targetUser != null) {
        val cleanName = SupabaseRepository.extractCleanName(targetUser.displayName).ifBlank { targetUser.displayName }
        val resolvedNis = targetUser.nis.ifBlank { SupabaseRepository.extractNis(targetUser.displayName) }
        val resolvedClass = SupabaseRepository.extractClass(targetUser.displayName)
        val infoLines = buildString {
            append(cleanName)
            if (resolvedNis.isNotBlank()) append("\nNIS: $resolvedNis")
            if (resolvedClass.isNotBlank()) append("  ·  $resolvedClass")
            if (targetUser.email.isNotBlank() && targetUser.email != "-") append("\n${targetUser.email}")
        }
        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(ui.kit.tint, 12f, border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            addView(TextView(self).apply {
                text = infoLines
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(ink)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        card.addView(infoBox)
    } else {
        card.addView(sectionLabel("NIS Siswa"))
        val field = styledInput("Contoh: 11855").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        nisInput = field
        card.addView(field)
    }

    card.addView(sectionLabel("Kata Sandi Baru (min. 6 karakter)"))
    val pwdInput = styledInput("Masukkan sandi baru").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(72))
    }
    card.addView(pwdInput)

    // Quick tools row: Show/Hide password + Generate easy-to-read temporary password
    var passwordVisible = false
    val toolsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }
    val toggleVisBtn = TextView(this).apply {
        text = "Tampilkan sandi"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(blue)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        background = roundedBg(ui.kit.tint, 10f, border)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }
    val randomPwdBtn = TextView(this).apply {
        text = "Buat sandi acak"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(blue)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        background = roundedBg(ui.kit.tint, 10f, blue)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    toolsRow.addView(toggleVisBtn)
    toolsRow.addView(randomPwdBtn)
    card.addView(toolsRow)

    card.addView(sectionLabel("Konfirmasi Kata Sandi Baru"))
    val confirmInput = styledInput("Ketik ulang sandi baru").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(72))
    }
    card.addView(confirmInput)

    fun applyPasswordVisibility(show: Boolean) {
        passwordVisible = show
        val type = if (show) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pwdSel = pwdInput.selectionEnd.coerceAtLeast(0)
        val confSel = confirmInput.selectionEnd.coerceAtLeast(0)
        pwdInput.inputType = type
        confirmInput.inputType = type
        pwdInput.setSelection(pwdSel.coerceAtMost(pwdInput.text?.length ?: 0))
        confirmInput.setSelection(confSel.coerceAtMost(confirmInput.text?.length ?: 0))
        toggleVisBtn.text = if (show) "Sembunyikan" else "Tampilkan sandi"
    }

    toggleVisBtn.setOnClickListener { applyPasswordVisibility(!passwordVisible) }
    randomPwdBtn.setOnClickListener {
        val generated = generateReadableStudentPassword()
        pwdInput.setText(generated)
        confirmInput.setText(generated)
        applyPasswordVisibility(true)
    }

    card.addView(sectionLabel("Catatan Audit (opsional)"))
    val reasonInput = styledInput("Mis. siswa lupa kata sandi").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(InputFilter.LengthFilter(200))
    }
    card.addView(reasonInput)

    val errorTv = TextView(this).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(danger)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
    }
    card.addView(errorTv)

    val actionsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) }
    }
    val cancelBtn = TextView(this).apply {
        text = "Batal"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(muted)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(76)
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { dialog.dismiss() }
    }
    val submitBtn = TextView(this).apply {
        text = "Reset Sandi"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(120)
        minHeight = dp(48)
        background = roundedBg(blue, 12f)
        setPadding(dp(18), dp(10), dp(18), dp(10))
    }
    actionsRow.addView(cancelBtn)
    actionsRow.addView(submitBtn)
    card.addView(actionsRow)

    var busy = false
    submitBtn.setOnClickListener {
        if (busy) return@setOnClickListener
        val nisRaw = nisInput?.text?.toString()?.filter { it.isDigit() }?.trim().orEmpty()
        val newPwd = pwdInput.text?.toString().orEmpty()
        val confPwd = confirmInput.text?.toString().orEmpty()
        val reason = reasonInput.text?.toString()?.trim().orEmpty()

        val validationError = when {
            targetUser == null && nisRaw.length < 3 -> "Masukkan NIS siswa yang valid (minimal 3 digit)."
            newPwd.trim().length < 6 -> "Kata sandi baru minimal 6 karakter."
            newPwd != newPwd.trim() -> "Kata sandi tidak boleh diawali atau diakhiri spasi."
            newPwd != confPwd -> "Konfirmasi kata sandi tidak cocok."
            else -> null
        }
        if (validationError != null) {
            errorTv.text = validationError
            errorTv.visibility = View.VISIBLE
            return@setOnClickListener
        }

        errorTv.visibility = View.GONE
        busy = true
        submitBtn.text = "Memproses..."
        submitBtn.alpha = 0.65f
        cancelBtn.isEnabled = false
        dialog.setCancelable(false)

        authScope.launch {
            try {
                val res = DeveloperRepository.resetStudentPassword(
                    targetUserId = targetUser?.id,
                    targetNis = if (targetUser == null) nisRaw else null,
                    newPassword = newPwd,
                    reason = reason,
                )
                dialog.dismiss()
                refreshDeveloper()
                showDevResetPasswordSuccessDialog(res, newPwd)
            } catch (error: Throwable) {
                busy = false
                submitBtn.text = "Reset Sandi"
                submitBtn.alpha = 1f
                cancelBtn.isEnabled = true
                dialog.setCancelable(true)
                errorTv.text = SupabaseRepository.errorMessage(error)
                errorTv.visibility = View.VISIBLE
            }
        }
    }

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        addView(card)
    }
    dialog.setContentView(scroll)
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.92f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
}

private fun generateReadableStudentPassword(): String {
    val letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz"
    val digits = "23456789"
    val rng = java.security.SecureRandom()
    return buildString {
        append("Jjn")
        repeat(4) { append(digits[rng.nextInt(digits.length)]) }
        append(letters[rng.nextInt(letters.length)])
    }
}

private fun MainActivity.showDevResetPasswordSuccessDialog(
    result: StudentPasswordResetResult,
    newPassword: String,
) {
    val d = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()
    val dark = ui.darkMode
    val surfaceBg = if (dark) Color.rgb(22, 28, 44) else Color.WHITE
    val ink = ui.kit.ink
    val muted = ui.kit.muted
    val blue = ui.kit.blue
    val success = ui.kit.success
    val border = ui.kit.border

    fun roundedBg(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(fill)
            if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    val cleanName = SupabaseRepository.extractCleanName(result.displayName).ifBlank {
        result.displayName.ifBlank { "Akun Siswa" }
    }
    val nisLine = result.nis.ifBlank { "-" }
    val copyText = buildString {
        append("Reset Sandi Akun Jajanin\n")
        append("Nama: $cleanName\n")
        if (result.nis.isNotBlank()) append("NIS: ${result.nis}\n")
        append("Sandi Baru: $newPassword")
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(surfaceBg, 20f, border)
        setPadding(dp(20), dp(20), dp(20), dp(16))
    }

    card.addView(TextView(this).apply {
        text = "Sandi Siswa Berhasil Direset"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(success)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    })

    card.addView(TextView(this).apply {
        text = "Berikan kata sandi baru ini kepada siswa agar bisa langsung login kembali."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(muted)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    })

    val credBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(ui.kit.tint, 12f, border)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
        addView(TextView(this@showDevResetPasswordSuccessDialog).apply {
            text = "Nama: $cleanName\nNIS: $nisLine\nSandi Baru: $newPassword"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        })
    }
    card.addView(credBox)

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) }
    }
    val copyBtn = TextView(this).apply {
        text = "Salin Info Login"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(blue)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        background = roundedBg(ui.kit.tint, 12f, blue)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
        setOnClickListener {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Info Login Jajanin", copyText))
                Toast.makeText(this@showDevResetPasswordSuccessDialog, "Info login disalin ke clipboard.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val closeBtn = TextView(this).apply {
        text = "Selesai"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(88)
        minHeight = dp(48)
        background = roundedBg(blue, 12f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { dialog.dismiss() }
    }
    buttons.addView(copyBtn)
    buttons.addView(closeBtn)
    card.addView(buttons)

    dialog.setContentView(card)
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.90f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    dialog.show()
}

// ================= Buyer/seller inbox =================

/** Realtime ringan untuk badge inbox buyer/seller (peringatan + pengumuman). */
