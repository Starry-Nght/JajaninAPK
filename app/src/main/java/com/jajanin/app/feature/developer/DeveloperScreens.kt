package com.jajanin.app.feature.developer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.DrawKit
import com.jajanin.app.core.ui.FloatNavItem
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.NavVariant
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.InlineSearchOverlay
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.core.ui.drawFloatingNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.navBadge
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.seller.loadProductImage

internal val DEV_TABS = listOf("Overview", "Users", "Sellers", "Orders", "Laporan", "Warn", "Info")

private const val HEADER_H = 60f

/**
 * Clean, calm editorial Developer Console matching Buyer & Seller design language.
 * - Single navigation bar: uses ONLY the bottom Liquid Glass Navbar (no duplicate top tab bar).
 * - Open hairline-divided lists instead of heavy boxed cards.
 * - Inline expandable rows that automatically close when switching tabs or filters.
 */
internal fun JajaninView.drawDeveloper(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val navClear = h - GLASS_TOP_OFFSET - 10f

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    when (devTab) {
        "Users" -> drawDevUsers(c, w, h, off, navClear, sellersOnly = false)
        "Sellers" -> drawDevUsers(c, w, h, off, navClear, sellersOnly = true)
        "Orders" -> drawDevOrders(c, w, h, off, navClear)
        "Laporan" -> drawDevReports(c, w, h, off, navClear)
        "Warn" -> drawDevWarnings(c, w, h, off, navClear)
        "Info" -> drawDevAnnouncements(c, w, h, off, navClear)
        else -> drawDevOverview(c, w, h, off, navClear)
    }

    c.restoreToCount(bodySave)

    // ================= Flat Editorial Top Header (Single Navbar Architecture) =================
    c.save()
    c.translate(-slideX, 0f)

    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)

    // Brand mark + Title
    val logoBox = RectF(20f, 17f, 44f, 41f)
    kit.rounded(c, logoBox, kit.blue, 6f)
    kit.centerText(c, "D", logoBox, 14f, Color.WHITE, true)

    val headerTitle = when (devTab) {
        "Users" -> "Pembeli"
        "Sellers" -> "Penjual & Warung"
        "Orders" -> "Pesanan"
        "Laporan" -> "Laporan Penjual"
        "Warn" -> "Moderasi"
        "Info" -> "Pengumuman"
        else -> "Developer"
    }
    kit.text(c, headerTitle, 52f, 35.5f, 17f, kit.ink, true)

    // Right actions: Theme, Refresh, Bell, Keluar (with generous hit boxes so taps register on 1st try)
    val out = RectF(318f, 14f, 372f, 46f)
    kit.rounded(c, out, kit.card, 6f, kit.dangerBorder)
    kit.centerText(c, "Keluar", out, 10.5f, kit.danger, true)
    press(c, "devLogout", out, 6f)

    val bell = RectF(280f, 12f, 314f, 48f)
    drawIcon(c, "bell", bell.centerX(), bell.centerY(), 15f, kit.ink)
    val unreadCount = inboxUnreadCount()
    if (unreadCount > 0) {
        val dot = RectF(bell.right - 9f, bell.top + 4f, bell.right - 1f, bell.top + 12f)
        kit.rounded(c, dot, kit.danger, 4f)
    }
    press(c, "devBell", bell, 6f)

    val refresh = RectF(244f, 12f, 278f, 48f)
    drawIcon(c, "refresh", refresh.centerX(), refresh.centerY(), 15f, kit.ink)
    press(c, "devRefresh", refresh, 6f)

    val themeBtn = RectF(208f, 12f, 242f, 48f)
    AppIcon.draw(c, "theme", themeBtn.centerX(), themeBtn.centerY(), 15f, kit.ink)
    press(c, "devTheme", themeBtn, 6f)

    // Register header hit boxes AFTER restore() or without slideX truncation, generous Y (2..58)
    bounds["devTheme"] = RectF(208f, 2f, 242f, HEADER_H - 2f)
    bounds["devRefresh"] = RectF(244f, 2f, 278f, HEADER_H - 2f)
    bounds["devBell"] = RectF(280f, 2f, 314f, HEADER_H - 2f)
    bounds["devLogout"] = RectF(315f, 2f, 386f, HEADER_H - 2f)

    // Clean Floating Action Button for Warn & Info
    if (devTab == "Warn" || devTab == "Info") {
        val fabW = 148f
        val fabH = 40f
        val fabTop = h - GLASS_TOP_OFFSET - fabH - 12f
        val fab = RectF(w - fabW - 20f, fabTop, w - 20f, fabTop + fabH)

        val fabBg = if (devTab == "Warn") kit.danger else kit.blue
        kit.rounded(c, fab, fabBg, 8f)
        drawIcon(c, "plus", fab.left + 18f, fab.centerY(), 13f, Color.WHITE)
        val fabText = if (devTab == "Warn") "Peringatan" else "Pengumuman"
        kit.text(c, fabText, fab.left + 32f, fab.centerY() + 4f, 11.5f, Color.WHITE, true)

        val key = if (devTab == "Warn") "devWarnNew" else "devAnnNew"
        bounds[key] = RectF(fab)
        press(c, key, fab, 8f)
    }

    c.restore()

    // Single Bottom Navigation: Liquid Glass Navbar
    drawDevBottomNav(c, w, h)
}

private fun JajaninView.drawDevBottomNav(c: Canvas, w: Float, h: Float) {
    val openReports = devReports.count { it.status.equals("open", true) }
    val pendingOrders = devOrders.count { it.status.equals("pending", true) }
    val unreadWarns = devWarnings.count { !it.isRead }
    when {
        openReports > 0 -> navBadge(4, openReports)
        pendingOrders > 0 -> navBadge(3, pendingOrders)
        unreadWarns > 0 -> navBadge(5, unreadWarns)
    }
    drawFloatingNav(
        c = c,
        w = w,
        h = h,
        items = listOf(
            FloatNavItem("Overview", "grid", true),
            FloatNavItem("Users", "profile", true),
            FloatNavItem("Sellers", "store", true),
            FloatNavItem("Orders", "orders", true),
            FloatNavItem("Laporan", "warning", true),
            FloatNavItem("Warn", "bell", true),
            FloatNavItem("Info", "bell", true),
        ),
        active = devTab,
        prefix = "dtab:",
        variant = NavVariant.STANDARD
    )
}

// ================= TAB 1: OVERVIEW =================

private fun JajaninView.drawDevOverview(c: Canvas, w: Float, h: Float, off: Float, navClear: Float) {
    var y = HEADER_H + 16f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    if (devStatsLoading) {
        kit.text(c, "Memuat ringkasan platform...", 20f, y + 24f, 13f, kit.muted)
        contentHeight = 0f
        return
    }

    if (devError != null) {
        val errRect = RectF(20f, y, 370f, y + 36f)
        kit.rounded(c, errRect, kit.card, 6f, kit.dangerBorder)
        kit.textEllipsized(c, devError ?: "", 32f, errRect.centerY() + 4f, 326f, 10.5f, kit.danger, true)
        y += 46f
    }

    val openReportCount = devReports.count { it.status.equals("open", true) }
    if (openReportCount > 0) {
        val alertCard = RectF(20f, y, 370f, y + 50f)
        if (visible(alertCard.top, alertCard.bottom)) {
            kit.rounded(c, alertCard, kit.card, 8f, kit.danger)
            kit.rect(c, 20f, y + 8f, 24f, y + 42f, kit.danger)
            kit.text(c, "$openReportCount Laporan Penjual Baru", 34f, y + 22f, 12.5f, kit.danger, true)
            kit.text(c, "Ketuk untuk meninjau 2 foto bukti & tindak lanjuti", 34f, y + 39f, 10.5f, kit.muted)
            drawRightBadge(c, kit, "Buka", 358f, y + 16f, kit.danger, Color.WHITE, 9.5f, padX = 10f, padY = 4f, radius = 6f)
            press(c, "devTab:Laporan", alertCard, 8f)
            bounds.putVisible("devTab:Laporan", alertCard, topLimit = HEADER_H, bottomLimit = navClear)
        }
        y += 60f
    }

    val s = devStats
    val avgVal = if (s.todayCount > 0) s.todayRevenue / s.todayCount else 0
    val successRate = if (s.completed + s.cancelled > 0) ((s.completed * 100) / (s.completed + s.cancelled)) else 100

    // 1. Open Editorial Revenue Summary (No heavy box)
    if (visible(y, y + 88f)) {
        kit.text(c, "Volume Transaksi Hari Ini", 20f, y + 12f, 11.5f, kit.muted)
        drawRightBadge(c, kit, "Aktif · Realtime", 370f, y, kit.successSoft, kit.success, 9f, radius = 5f)

        kit.text(c, formatRupiah(s.todayRevenue), 20f, y + 42f, 26f, kit.ink, true)
        kit.text(
            c,
            "${s.todayCount} pesanan hari ini  ·  Rata-rata ${formatRupiah(avgVal)}  ·  Selesai $successRate%",
            20f,
            y + 62f,
            11f,
            kit.muted
        )

        // Clean thin progress bar
        val total = (s.completed + s.pending + s.cancelled).coerceAtLeast(1)
        val track = RectF(20f, y + 74f, 370f, y + 78f)
        kit.rounded(c, track, kit.divider, 2f)
        val compW = 350f * (s.completed.toFloat() / total)
        val pendW = 350f * (s.pending.toFloat() / total)
        if (compW > 0f) {
            kit.rounded(c, RectF(20f, track.top, 20f + compW, track.bottom), kit.success, 2f)
        }
        if (pendW > 0f) {
            kit.rounded(c, RectF(20f + compW, track.top, 20f + compW + pendW, track.bottom), kit.blue, 2f)
        }
    }
    y += 94f

    // 2. Clean 4-Column Metric Strip (Single surface, hairline dividers)
    val strip = RectF(20f, y, 370f, y + 58f)
    if (visible(strip.top, strip.bottom)) {
        kit.rounded(c, strip, kit.card, 8f, kit.border)
        val colW = strip.width() / 4f
        val cols = listOf(
            Triple(s.totalBuyers.toString(), "Pembeli", kit.ink),
            Triple("${s.activeStalls}/${s.totalSellers}", "Warung Buka", kit.blue),
            Triple(s.pending.toString(), "Antrean", if (s.pending > 0) kit.star else kit.ink),
            Triple(s.totalOrders.toString(), "Total Order", kit.ink)
        )
        cols.forEachIndexed { idx, (valStr, label, clr) ->
            val left = strip.left + idx * colW
            if (idx > 0) kit.line(c, left, strip.top + 10f, left, strip.bottom - 10f, kit.divider)
            kit.text(c, valStr, left + 12f, strip.top + 26f, 15.5f, clr, true)
            kit.text(c, label, left + 12f, strip.top + 44f, 10f, kit.muted)
        }
    }
    y += 76f

    // 3. Minimal Daily Reset Row
    val resetRow = RectF(20f, y, 370f, y + 52f)
    if (visible(resetRow.top, resetRow.bottom)) {
        kit.rounded(c, resetRow, kit.card, 8f, kit.border)
        kit.text(c, "Reset Harian (00:00 WIB)", 32f, y + 22f, 13f, kit.ink, true)
        kit.text(c, "Tutup order gantung & bersihkan log harian", 32f, y + 39f, 10.5f, kit.muted)

        val btn = RectF(286f, y + 12f, 358f, y + 40f)
        kit.rounded(c, btn, kit.dangerBorder, 6f)
        kit.centerText(c, if (devResetting) "Proses..." else "Reset", btn, 11f, kit.danger, true)
        bounds.putVisible("devReset", btn, HEADER_H, navClear)
        press(c, "devReset", btn, 6f)
    }
    y += 64f

    // 3b. OTA App Update Row (Check + Publish from Developer Console)
    val otaRow = RectF(20f, y, 370f, y + 52f)
    if (visible(otaRow.top, otaRow.bottom)) {
        kit.rounded(c, otaRow, kit.card, 8f, kit.border)
        val curVer = com.jajanin.app.BuildConfig.VERSION_NAME
        val otaSub = if (otaUpdateAvailable && !otaLatestVersion.isNullOrBlank()) {
            "Tersedia $otaLatestVersion (terpasang v$curVer)"
        } else {
            "Versi terpasang v$curVer · Update langsung via Supabase"
        }
        kit.text(c, "Pembaruan OTA Aplikasi", 32f, y + 22f, 13f, kit.ink, true)
        kit.textEllipsized(c, otaSub, 32f, y + 39f, 190f, 10.5f, if (otaUpdateAvailable) kit.blue else kit.muted)

        val checkBtn = RectF(234f, y + 12f, 290f, y + 40f)
        kit.rounded(c, checkBtn, kit.tint, 6f, kit.border)
        kit.centerText(c, "Cek", checkBtn, 11f, kit.blue, true)
        bounds.putVisible("checkOta", checkBtn, HEADER_H, navClear)
        press(c, "checkOta", checkBtn, 6f)

        val pubBtn = RectF(296f, y + 12f, 358f, y + 40f)
        kit.rounded(c, pubBtn, kit.blue, 6f)
        kit.centerText(c, "Rilis", pubBtn, 11f, android.graphics.Color.WHITE, true)
        bounds.putVisible("devPublishOta", pubBtn, HEADER_H, navClear)
        press(c, "devPublishOta", pubBtn, 6f)
    }
    y += 72f

    // 4. Open Recent Orders List (Hairline-divided, no individual card boxes)
    if (visible(y, y + 24f)) {
        kit.text(c, "Pesanan Terbaru", 20f, y + 14f, 14f, kit.ink, true)
        kit.textRight(c, "${devOrders.size} total", 370f, y + 14f, 80f, 11f, kit.muted)
        kit.line(c, 20f, y + 24f, 370f, y + 24f, kit.divider)
    }
    y += 26f

    if (devOrders.isEmpty()) {
        if (visible(y, y + 48f)) {
            kit.text(c, "Belum ada pesanan hari ini.", 20f, y + 28f, 12f, kit.muted)
        }
        y += 52f
    } else {
        devOrders.take(8).forEach { order ->
            y = drawDevOrderRow(c, order, y, ::visible, navClear)
        }
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 28f
}

// ================= TAB 2 & 3: USERS (BUYERS) & SELLERS =================

private fun JajaninView.drawDevUsers(
    c: Canvas,
    w: Float,
    h: Float,
    off: Float,
    navClear: Float,
    sellersOnly: Boolean
) {
    var y = HEADER_H + 14f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    // Search Input (Direct inline typing, Radius 8f, no popup dialog)
    val search = RectF(20f, y, 370f, y + 38f)
    val devSearchFocused = inlineSearchActiveTarget == InlineSearchOverlay.TARGET_DEV
    if (visible(search.top, search.bottom)) {
        val searchActive = devSearchFocused || devQuery.isNotBlank()
        kit.rounded(c, search, kit.card, 8f, if (searchActive) kit.blue else kit.border)
        drawIcon(c, "search", search.left + 16f, search.centerY(), 14f, if (searchActive) kit.blue else kit.muted)
        if (devSearchFocused) {
            setInlineSearchFieldBounds(
                left = search.left + 34f,
                top = search.top,
                right = if (devQuery.isNotBlank()) 336f else 360f,
                bottom = search.bottom,
                textSize = 11.5f,
            )
        } else {
            val placeholder = if (sellersOnly) "Cari nama penjual, NIS, atau nama toko..." else "Cari nama pembeli, NIS, atau email..."
            val queryText = if (devQuery.isBlank()) placeholder else devQuery
            val queryColor = if (devQuery.isBlank()) kit.muted else kit.ink
            kit.textEllipsized(c, queryText, search.left + 34f, search.centerY() + 4f, 296f, 11.5f, queryColor, devQuery.isNotBlank())
        }
        if (devQuery.isNotBlank()) {
            val clearHit = RectF(338f, y + 4f, 368f, y + 34f)
            drawIcon(c, "close", clearHit.centerX(), clearHit.centerY(), 12f, kit.muted)
            bounds.putVisible("devSearchClear", clearHit, HEADER_H, navClear)
        }
        val inputHit = RectF(20f, y, if (devQuery.isNotBlank()) 336f else 370f, y + 38f)
        bounds.putVisible("devSearch", inputHit, HEADER_H, navClear)
        press(c, "devSearch", inputHit, 8f)
    }
    y += 48f

    // Role-specific base list (No duplicate Seller tab inside Users tab!)
    val rolePool = devUsers.filter { u ->
        if (sellersOnly) u.role == "seller" else u.role != "seller"
    }

    val filters = if (sellersOnly) {
        listOf("Semua", "Aktif", "Buka", "Tutup", "Ditangguhkan")
    } else {
        listOf("Semua", "Aktif", "Ditangguhkan")
    }
    val activeFilter = if (devUserFilter in filters) devUserFilter else "Semua"

    var chipX = 20f
    filters.forEach { filter ->
        val count = when (filter) {
            "Aktif" -> rolePool.count { it.id in onlineUserIds }
            "Buka" -> rolePool.count { it.stallOpen == true }
            "Tutup" -> rolePool.count { it.stallOpen == false }
            "Ditangguhkan" -> rolePool.count { it.accountStatus == "suspended" }
            else -> rolePool.size
        }
        val label = "$filter ($count)"
        val chipW = kit.measure(label, 10f, true) + 20f
        if (chipX > 20f && chipX + chipW > 370f) {
            chipX = 20f
            y += 32f
        }
        val chip = RectF(chipX, y, chipX + chipW, y + 26f)
        val isSel = activeFilter == filter

        if (visible(chip.top, chip.bottom)) {
            kit.rounded(c, chip, if (isSel) kit.blue else kit.card, 6f, if (isSel) null else kit.border)
            kit.centerText(c, label, chip, 10f, if (isSel) Color.WHITE else kit.muted, true)
            bounds.putVisible("devFilter:$filter", chip, HEADER_H, navClear)
            press(c, "devFilter:$filter", chip, 6f)
        }
        chipX += chipW + 6f
    }
    y += 36f

    val rows = rolePool.filter { u ->
        (devQuery.isBlank() ||
            u.displayName.contains(devQuery.trim(), true) ||
            u.stallName.orEmpty().contains(devQuery.trim(), true) ||
            u.email.contains(devQuery.trim(), true) ||
            u.phone.contains(devQuery.trim())) &&
            when (activeFilter) {
                "Aktif" -> u.id in onlineUserIds
                "Buka" -> u.stallOpen == true
                "Tutup" -> u.stallOpen == false
                "Ditangguhkan" -> u.accountStatus == "suspended"
                else -> true
            }
    }

    if (devUsersLoading && devUsers.isEmpty()) {
        kit.text(c, "Memuat daftar akun...", 20f, y + 24f, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    if (rows.isEmpty()) {
        if (visible(y, y + 50f)) {
            val emptyMsg = if (activeFilter == "Aktif") "Belum ada user yang sedang membuka Jajanin." else "Tidak ada akun yang cocok."
            kit.text(c, emptyMsg, 20f, y + 26f, 12f, kit.muted)
        }
        y += 56f
    } else {
        kit.line(c, 20f, y, 370f, y, kit.divider)
        rows.forEach { u ->
            val isExpanded = u.id == devSelectedUserId
            val isOnline = u.id in onlineUserIds
            val hasRow2 = u.stallId != null
            val hasVerifAction = u.stallId != null && (u.stallVerified || u.stallVerificationRevoked)
            val baseH = 66f
            val expandH = if (isExpanded) {
                when {
                    hasVerifAction -> 122f
                    hasRow2 -> 86f
                    else -> 50f
                }
            } else 0f
            val rowH = baseH + expandH

            if (visible(y, y + rowH)) {
                val rowRect = RectF(20f, y, 370f, y + baseH)
                if (isExpanded) {
                    kit.rounded(c, RectF(16f, y + 2f, 374f, y + rowH - 2f), kit.card, 8f, kit.border)
                }

                val cleanName = SupabaseRepository.extractCleanName(u.displayName).ifBlank { u.displayName }
                val nis = SupabaseRepository.extractNis(u.displayName)
                val kelas = SupabaseRepository.extractClass(u.displayName)
                val (_, roleFg) = roleBadgeColors(u.role)

                // Clean Soft Avatar + live online dot
                val av = RectF(20f, y + 13f, 56f, y + 49f)
                kit.rounded(c, av, kit.tint, 8f)
                val initial = cleanName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"
                kit.centerText(c, initial, av, 15f, roleFg, true)
                if (isOnline) {
                    kit.rounded(
                        c,
                        RectF(av.right - 8f, av.bottom - 8f, av.right + 2f, av.bottom + 2f),
                        kit.success,
                        5f,
                        if (isExpanded) kit.card else kit.bg,
                    )
                }

                // Right Column Badges (stack right-to-left before measuring primary title width)
                val isSuspended = u.accountStatus == "suspended"
                var badgeRightX = 370f
                when {
                    isSuspended -> {
                        val w = drawRightBadge(c, kit, "Ditangguhkan", badgeRightX, y + 12f, kit.dangerBorder, kit.danger, 8.5f, radius = 4f)
                        badgeRightX -= w + 4f
                    }
                    u.warningCount > 0 -> {
                        val w = drawRightBadge(c, kit, "Warn ${u.warningCount}", badgeRightX, y + 12f, kit.dangerBorder, kit.danger, 8.5f, radius = 4f)
                        badgeRightX -= w + 4f
                    }
                    sellersOnly && u.stallOpen != null -> {
                        val open = u.stallOpen
                        val w = drawRightBadge(
                            c,
                            kit,
                            if (open) "Buka" else "Tutup",
                            badgeRightX,
                            y + 12f,
                            if (open) kit.successSoft else kit.tint,
                            if (open) kit.success else kit.muted,
                            8.5f,
                            radius = 4f,
                        )
                        badgeRightX -= w + 4f
                    }
                    u.role == "developer" -> {
                        val (devBg, devFg) = roleBadgeColors("developer")
                        val w = drawRightBadge(c, kit, "Dev", badgeRightX, y + 12f, devBg, devFg, 8.5f, radius = 4f)
                        badgeRightX -= w + 4f
                    }
                }
                if (isOnline) {
                    val w = drawRightBadge(c, kit, "Online", badgeRightX, y + 12f, kit.successSoft, kit.success, 8.5f, radius = 4f)
                    badgeRightX -= w + 4f
                }

                // Primary Line: Clean Name (+ Store Name + Verified Badge for Sellers)
                val titleText = if (sellersOnly && !u.stallName.isNullOrBlank()) {
                    "$cleanName  ·  ${u.stallName}"
                } else {
                    cleanName
                }
                val titleMaxW = (badgeRightX - 76f).coerceIn(130f, 215f)
                StoreVerifiedBadge.drawInlineWithStoreName(
                    view = this,
                    c = c,
                    storeName = titleText,
                    isVerified = sellersOnly && u.stallVerified,
                    x = 68f,
                    baselineY = y + 25f,
                    maxTotalWidth = titleMaxW,
                    textSize = 13.5f,
                    textColor = kit.ink,
                    bold = true,
                )

                // Secondary Line: NIS + Kelas + Phone + Email
                val subParts = mutableListOf<String>()
                if (nis.isNotBlank()) subParts += "NIS $nis"
                if (kelas.isNotBlank()) subParts += kelas
                if (u.phone.isNotBlank()) subParts += displayLocalPhone(u.phone)
                subParts += u.email
                kit.textEllipsized(c, subParts.joinToString("  ·  "), 68f, y + 44f, 212f, 10.5f, kit.muted)

                val rightCountLabel = if (sellersOnly && u.stallId != null) {
                    "${u.stallCompletedOrders} selesai"
                } else {
                    "${u.orderCount} pesanan"
                }
                kit.textRight(c, rightCountLabel, 370f, y + 44f, 88f, 10.5f, kit.muted)

                bounds.putVisible("devUser:${u.id}", rowRect, HEADER_H, navClear)
                press(c, "devUser:${u.id}", rowRect, 6f)

                // Inline Expanded Actions (Calm, compact buttons right inside the row)
                if (isExpanded) {
                    kit.line(c, 28f, y + baseH - 2f, 362f, y + baseH - 2f, kit.divider)
                    val btnY = y + baseH + 8f

                    // Row 1: Peringatkan & Tangguhkan/Aktifkan
                    val b1 = RectF(28f, btnY, 190f, btnY + 32f)
                    val b2 = RectF(198f, btnY, 362f, btnY + 32f)

                    kit.rounded(c, b1, kit.warnBox, 6f)
                    kit.centerText(c, "Kirim Peringatan", b1, 10.5f, kit.star, true)
                    bounds.putVisible("devWarn:${u.id}", b1, HEADER_H, navClear)
                    press(c, "devWarn:${u.id}", b1, 6f)

                    val b2Bg = if (isSuspended) kit.successSoft else kit.dangerBorder
                    val b2Fg = if (isSuspended) kit.success else kit.danger
                    kit.rounded(c, b2, b2Bg, 6f)
                    kit.centerText(c, if (isSuspended) "Aktifkan Akun" else "Tangguhkan Akun", b2, 10.5f, b2Fg, true)
                    bounds.putVisible(if (isSuspended) "devActive:${u.id}" else "devSuspend:${u.id}", b2, HEADER_H, navClear)
                    press(c, if (isSuspended) "devActive:${u.id}" else "devSuspend:${u.id}", b2, 6f)

                    // Row 2: Stall Toggle (for Seller only)
                    if (u.stallId != null) {
                        val b3 = RectF(28f, btnY + 38f, 362f, btnY + 68f)
                        val isOpen = u.stallOpen == true
                        kit.rounded(c, b3, kit.tint, 6f)
                        kit.centerText(c, if (isOpen) "Tutup Warung Sementara" else "Buka Kembali Warung", b3, 10.5f, kit.blue, true)
                        bounds.putVisible("devStall:${u.stallId}:${!isOpen}", b3, HEADER_H, navClear)
                        press(c, "devStall:${u.stallId}:${!isOpen}", b3, 6f)

                        // Row 3: Audited Verification Revoke / Restore
                        if (hasVerifAction) {
                            val b4 = RectF(28f, btnY + 74f, 362f, btnY + 104f)
                            val revoking = u.stallVerified
                            kit.rounded(c, b4, if (revoking) kit.dangerBorder else kit.successSoft, 6f)
                            kit.centerText(
                                c,
                                if (revoking) "Cabut Verifikasi Warung (Audit)" else "Pulihkan Verifikasi Warung (Audit)",
                                b4,
                                10.5f,
                                if (revoking) kit.danger else kit.success,
                                true,
                            )
                            bounds.putVisible("devVerifyStall:${u.stallId}:$revoking", b4, HEADER_H, navClear)
                            press(c, "devVerifyStall:${u.stallId}:$revoking", b4, 6f)
                        }
                    }
                } else {
                    kit.line(c, 20f, y + rowH, 370f, y + rowH, kit.divider)
                }
            }
            y += rowH
        }
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 28f
}

// ================= TAB 4: ORDERS =================

private fun JajaninView.drawDevOrders(c: Canvas, w: Float, h: Float, off: Float, navClear: Float) {
    var y = HEADER_H + 14f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    // Compact summary strip
    val summary = RectF(20f, y, 370f, y + 48f)
    if (visible(summary.top, summary.bottom)) {
        kit.rounded(c, summary, kit.card, 8f, kit.border)
        val activeCount = devOrders.count { !it.status.equals("completed", true) && !it.status.equals("cancelled", true) }
        val totalRev = devOrders.filter { !it.status.equals("cancelled", true) }.sumOf { it.totalIdr }
        kit.text(c, "${devOrders.size} Pesanan  ·  $activeCount Aktif", 32f, summary.centerY() + 4f, 12f, kit.ink, true)
        kit.textRight(c, formatRupiah(totalRev), 358f, summary.centerY() + 4f, 140f, 12.5f, kit.blue, true)
    }
    y += 60f

    if (devOrdersLoading && devOrders.isEmpty()) {
        kit.text(c, "Memuat pesanan...", 20f, y + 24f, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    if (devOrders.isEmpty()) {
        if (visible(y, y + 50f)) {
            kit.text(c, "Belum ada pesanan tercatat.", 20f, y + 26f, 12f, kit.muted)
        }
        y += 56f
    } else {
        kit.line(c, 20f, y, 370f, y, kit.divider)
        devOrders.forEach { order ->
            y = drawDevOrderRow(c, order, y, ::visible, navClear)
        }
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 28f
}

private fun JajaninView.drawDevOrderRow(
    c: Canvas,
    order: com.jajanin.app.data.repository.AdminOrder,
    y: Float,
    visible: (Float, Float) -> Boolean,
    navClear: Float,
): Float {
    val expanded = devExpandedOrderId == order.id
    val baseH = 68f
    val itemCount = maxOf(order.items.size, 1)
    val roomBonus = if (order.pickupRoom.isNotBlank() || order.sellerStoreName.isNotBlank()) 22f else 0f
    val targetExtraH = itemCount * 22f + 24f + roomBonus
    val animFrac = if (!expanded) {
        0f
    } else if (devOrderExpandAt > 0L && motionEnabled()) {
        val elapsed = (android.os.SystemClock.uptimeMillis() - devOrderExpandAt).coerceAtLeast(0L)
        val raw = (elapsed / 180f).coerceIn(0f, 1f)
        if (raw < 1f) postInvalidateOnAnimation()
        1f - (1f - raw) * (1f - raw) * (1f - raw)
    } else {
        1f
    }
    val extraH = if (expanded) targetExtraH * animFrac else 0f
    val rowH = baseH + extraH

    if (visible(y, y + rowH)) {
        val cardBounds = if (expanded) {
            RectF(16f, y + 2f, 374f, y + rowH - 2f)
        } else {
            RectF(20f, y, 370f, y + rowH)
        }
        if (expanded) {
            kit.rounded(c, cardBounds, kit.card, 8f, kit.border)
        }

        val cleanBuyer = SupabaseRepository.extractCleanName(order.buyerName).ifBlank { order.buyerName }
        val nis = SupabaseRepository.extractNis(order.buyerName)
        val title = if (nis.isNotBlank()) "$cleanBuyer · NIS $nis" else cleanBuyer

        kit.textEllipsized(c, title, 22f, y + 24f, 215f, 13.5f, kit.ink, true)
        val totalStr = formatRupiah(order.totalIdr)
        kit.textRight(c, totalStr, 368f, y + 24f, 110f, 13.5f, kit.blue, true)

        val payLabel = "${order.paymentMethod?.uppercase() ?: "TUNAI"} · ${if (order.paymentStatus == "paid") "Lunas" else "Belum bayar"}"
        val meta = "#${order.id.take(6).uppercase()} · Kode ${order.pickupCode} · $payLabel"
        val (stBg, stFg) = orderStatusBadgeColors(order.status)
        val stText = statusLabelShort(order.status)
        val stW = kit.measure(stText, 8.5f, true) + 14f
        kit.textEllipsized(c, meta, 22f, y + 45f, 340f - stW - 8f, 10.5f, kit.muted)
        drawRightBadge(c, kit, stText, 368f, y + 33f, stBg, stFg, 8.5f, radius = 4f)

        if (expanded && extraH > 6f) {
            val detailSave = c.save()
            c.clipRect(16f, y + baseH - 4f, 374f, y + rowH - 2f)
            kit.line(c, 24f, y + baseH - 4f, 366f, y + baseH - 4f, kit.divider)
            var itemY = y + baseH + 14f
            if (roomBonus > 0f) {
                val storeInfo = buildString {
                    if (order.sellerStoreName.isNotBlank()) append(order.sellerStoreName)
                    if (order.pickupRoom.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("Ruang: ${order.pickupRoom}")
                    }
                }
                kit.textEllipsized(c, storeInfo, 24f, itemY, 336f, 10.5f, kit.blue, true)
                itemY += 22f
            }
            if (order.items.isEmpty()) {
                kit.text(c, "Rincian item tidak tersedia.", 24f, itemY, 10.5f, kit.muted)
            } else {
                order.items.forEach { item ->
                    kit.textEllipsized(c, "${item.quantity}×  ${item.itemName}", 24f, itemY, 230f, 11f, kit.ink)
                    val sub = formatRupiah(item.unitPriceIdr * item.quantity)
                    kit.textRight(c, sub, 366f, itemY, 100f, 11f, kit.muted, true)
                    itemY += 22f
                }
            }
            c.restoreToCount(detailSave)
        } else {
            kit.line(c, 20f, y + rowH, 370f, y + rowH, kit.divider)
        }

        press(c, "devOrder:${order.id}", cardBounds, if (expanded) 8f else 6f)
        bounds.putVisible("devOrder:${order.id}", cardBounds, HEADER_H, navClear)
    }
    return y + rowH
}

// ================= TAB 5: WARN (MODERASI) =================

private fun JajaninView.drawDevWarnings(c: Canvas, w: Float, h: Float, off: Float, navClear: Float) {
    var y = HEADER_H + 14f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    val unreadW = devWarnings.count { !it.isRead }
    if (visible(y, y + 24f)) {
        kit.text(c, "${devWarnings.size} riwayat peringatan  ·  $unreadW belum dibaca", 20f, y + 14f, 11.5f, kit.muted)
        kit.line(c, 20f, y + 24f, 370f, y + 24f, kit.divider)
    }
    y += 26f

    if (devWarnings.isEmpty()) {
        if (visible(y, y + 50f)) {
            kit.text(c, "Belum ada peringatan moderasi terkirim.", 20f, y + 26f, 12f, kit.muted)
        }
        y += 56f
    } else {
        devWarnings.forEach { warn ->
            val msgLines = wrapText(kit, warn.message, 340f, 11f)
            val rowH = 56f + maxOf(msgLines.size, 1) * 16f

            if (visible(y, y + rowH)) {
                val (sevBg, sevFg) = severityBadgeColors(warn.severity)
                val sevLabel = when (warn.severity.lowercase()) {
                    "critical" -> "Kritis"
                    "warning" -> "Peringatan"
                    else -> "Info"
                }
                val bw = drawBadge(c, kit, sevLabel, 20f, y + 10f, sevBg, sevFg, 8.5f, radius = 4f)
                kit.textEllipsized(c, warn.title, 20f + bw + 8f, y + 21f, 230f, 13f, kit.ink, true)

                val readLabel = if (warn.isRead) "Dibaca" else "Baru"
                kit.textRight(c, readLabel, 370f, y + 21f, 60f, 10f, if (warn.isRead) kit.muted else kit.star, true)

                var textY = y + 40f
                msgLines.forEach { line ->
                    kit.text(c, line, 20f, textY, 11f, kit.muted)
                    textY += 16f
                }

                val targetUser = devUsers.firstOrNull { it.id == warn.targetUserId }
                val targetName = targetUser?.let { SupabaseRepository.extractCleanName(it.displayName) }
                    ?: "#${warn.targetUserId.take(8)}"
                val foot = "Kepada: $targetName  ·  ${warn.createdAt.take(16).replace('T', ' ')}"
                kit.text(c, foot, 20f, y + rowH - 9f, 9.5f, kit.muted)

                kit.line(c, 20f, y + rowH, 370f, y + rowH, kit.divider)
            }
            y += rowH
        }
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 80f
}

// ================= TAB 6: INFO (PENGUMUMAN) =================

private fun JajaninView.drawDevAnnouncements(c: Canvas, w: Float, h: Float, off: Float, navClear: Float) {
    var y = HEADER_H + 14f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    if (visible(y, y + 24f)) {
        kit.text(c, "${devAnnouncements.size} siaran pengumuman aktif", 20f, y + 14f, 11.5f, kit.muted)
        kit.line(c, 20f, y + 24f, 370f, y + 24f, kit.divider)
    }
    y += 26f

    if (devAnnouncements.isEmpty()) {
        if (visible(y, y + 50f)) {
            kit.text(c, "Belum ada pengumuman aktif.", 20f, y + 26f, 12f, kit.muted)
        }
        y += 56f
    } else {
        devAnnouncements.forEach { ann ->
            val msgLines = wrapText(kit, ann.message, 340f, 11f)
            val rowH = 56f + maxOf(msgLines.size, 1) * 16f

            if (visible(y, y + rowH)) {
                val (tBg, tFg) = targetRoleBadgeColors(ann.targetRole)
                val targetLabel = when (ann.targetRole.lowercase()) {
                    "sellers" -> "Penjual"
                    "buyers" -> "Pembeli"
                    else -> "Semua"
                }
                val bw = drawBadge(c, kit, targetLabel, 20f, y + 10f, tBg, tFg, 8.5f, radius = 4f)
                kit.textEllipsized(c, ann.title, 20f + bw + 8f, y + 21f, 250f, 13f, kit.ink, true)

                var textY = y + 40f
                msgLines.forEach { line ->
                    kit.text(c, line, 20f, textY, 11f, kit.muted)
                    textY += 16f
                }

                kit.text(c, ann.createdAt.take(16).replace('T', ' '), 20f, y + rowH - 9f, 9.5f, kit.muted)
                kit.line(c, 20f, y + rowH, 370f, y + rowH, kit.divider)
            }
            y += rowH
        }
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 80f
}

// ================= HELPERS =================

internal fun JajaninView.inboxUnreadCount(): Int =
    myWarnings.count { !it.isRead } + myAnnouncements.size + unreadOrderNotifCount

private fun displayLocalPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw

private fun LinkedHashMap<String, RectF>.putVisible(
    key: String,
    r: RectF,
    topLimit: Float = HEADER_H,
    bottomLimit: Float = Float.MAX_VALUE,
) {
    val top = r.top.coerceAtLeast(topLimit)
    val bottom = r.bottom.coerceAtMost(bottomLimit)
    if (bottom > top && r.right > r.left) put(key, RectF(r.left, top, r.right, bottom))
}

private fun wrapText(kit: DrawKit, text: String, maxWidth: Float, textSize: Float, bold: Boolean = false): List<String> {
    if (text.isBlank()) return emptyList()
    val words = text.split(Regex("\\s+"))
    val lines = mutableListOf<String>()
    var currentLine = StringBuilder()
    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
        if (kit.measure(testLine, textSize, bold) <= maxWidth) {
            currentLine.append(if (currentLine.isEmpty()) word else " $word")
        } else {
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            } else {
                lines.add(word)
            }
        }
    }
    if (currentLine.isNotEmpty()) {
        lines.add(currentLine.toString())
    }
    return lines
}

private fun drawBadge(
    c: Canvas,
    kit: DrawKit,
    text: String,
    x: Float,
    y: Float,
    bgColor: Int,
    textColor: Int,
    textSize: Float = 9f,
    padX: Float = 6f,
    padY: Float = 3f,
    radius: Float = 4f,
    bold: Boolean = true
): Float {
    val textW = kit.measure(text, textSize, bold)
    val totalW = textW + padX * 2f
    val totalH = textSize + padY * 2f
    val rect = RectF(x, y, x + totalW, y + totalH)
    kit.rounded(c, rect, bgColor, radius)
    kit.text(c, text, x + padX, rect.centerY() + textSize * 0.35f, textSize, textColor, bold)
    return totalW
}

private fun drawRightBadge(
    c: Canvas,
    kit: DrawKit,
    text: String,
    rightX: Float,
    y: Float,
    bgColor: Int,
    textColor: Int,
    textSize: Float = 9f,
    padX: Float = 6f,
    padY: Float = 3f,
    radius: Float = 4f,
    bold: Boolean = true
): Float {
    val textW = kit.measure(text, textSize, bold)
    val totalW = textW + padX * 2f
    val leftX = rightX - totalW
    val totalH = textSize + padY * 2f
    val rect = RectF(leftX, y, rightX, y + totalH)
    kit.rounded(c, rect, bgColor, radius)
    kit.text(c, text, leftX + padX, rect.centerY() + textSize * 0.35f, textSize, textColor, bold)
    return totalW
}

private fun orderStatusBadgeColors(status: String): Pair<Int, Int> = when (status.lowercase()) {
    "pending" -> Pair(Color.argb(40, 245, 158, 11), Color.rgb(217, 119, 6))
    "confirmed", "preparing", "processing" -> Pair(Color.argb(35, 59, 130, 246), Color.rgb(59, 130, 246))
    "ready" -> Pair(Color.argb(35, 139, 92, 246), Color.rgb(139, 92, 246))
    "completed" -> Pair(Color.argb(35, 16, 185, 129), Color.rgb(16, 185, 129))
    "cancelled" -> Pair(Color.argb(35, 239, 68, 68), Color.rgb(239, 68, 68))
    else -> Pair(Color.argb(25, 100, 116, 139), Color.rgb(100, 116, 139))
}

private fun roleBadgeColors(role: String): Pair<Int, Int> = when (role.lowercase()) {
    "seller" -> Pair(Color.argb(35, 139, 92, 246), Color.rgb(139, 92, 246))
    "developer" -> Pair(Color.argb(40, 245, 158, 11), Color.rgb(217, 119, 6))
    else -> Pair(Color.argb(35, 59, 130, 246), Color.rgb(59, 130, 246))
}

private fun severityBadgeColors(severity: String): Pair<Int, Int> = when (severity.lowercase()) {
    "critical" -> Pair(Color.argb(35, 239, 68, 68), Color.rgb(239, 68, 68))
    "warning" -> Pair(Color.argb(40, 245, 158, 11), Color.rgb(217, 119, 6))
    else -> Pair(Color.argb(35, 59, 130, 246), Color.rgb(59, 130, 246))
}

private fun targetRoleBadgeColors(target: String): Pair<Int, Int> = when (target.lowercase()) {
    "sellers" -> Pair(Color.argb(35, 139, 92, 246), Color.rgb(139, 92, 246))
    "buyers" -> Pair(Color.argb(35, 16, 185, 129), Color.rgb(16, 185, 129))
    else -> Pair(Color.argb(35, 59, 130, 246), Color.rgb(59, 130, 246))
}

// ================= TAB: LAPORAN PENJUAL (2 BUKTI FOTO -> LIVE DEVELOPER) =================

private fun reportStatusBadgeColors(status: String): Triple<String, Int, Int> = when (status.lowercase()) {
    "open" -> Triple("Baru", Color.argb(38, 239, 68, 68), Color.rgb(239, 68, 68))
    "reviewing" -> Triple("Ditinjau", Color.argb(40, 245, 158, 11), Color.rgb(217, 119, 6))
    "resolved" -> Triple("Selesai", Color.argb(35, 16, 185, 129), Color.rgb(16, 185, 129))
    "dismissed" -> Triple("Ditolak", Color.argb(28, 100, 116, 139), Color.rgb(148, 163, 184))
    else -> Triple(status, Color.argb(25, 100, 116, 139), Color.rgb(148, 163, 184))
}

private fun JajaninView.drawDevReports(c: Canvas, w: Float, h: Float, off: Float, navClear: Float) {
    var y = HEADER_H + 14f + off
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    val allReports = devReports
    val openCount = allReports.count { it.status.equals("open", true) }
    val reviewingCount = allReports.count { it.status.equals("reviewing", true) }
    val resolvedCount = allReports.count { it.status.equals("resolved", true) }

    // 1. Summary Strip
    val statsRect = RectF(20f, y, 370f, y + 52f)
    if (visible(statsRect.top, statsRect.bottom)) {
        kit.rounded(c, statsRect, kit.card, 8f, kit.border)
        val colW = 350f / 4f
        val cols = listOf(
            Triple(openCount.toString(), "Baru", kit.danger),
            Triple(reviewingCount.toString(), "Ditinjau", Color.rgb(217, 119, 6)),
            Triple(resolvedCount.toString(), "Selesai", kit.success),
            Triple(allReports.size.toString(), "Total", kit.ink),
        )
        cols.forEachIndexed { idx, (v, lbl, col) ->
            val left = 20f + idx * colW
            if (idx > 0) kit.line(c, left, y + 10f, left, y + 42f, kit.divider)
            kit.text(c, v, left + 12f, y + 23f, 15.5f, col, true)
            kit.text(c, lbl, left + 12f, y + 40f, 9.5f, kit.muted)
        }
    }
    y += 62f

    // 2. Status Filter Pills
    val filters = listOf(
        "Semua" to "Semua (${allReports.size})",
        "open" to "Baru ($openCount)",
        "reviewing" to "Ditinjau ($reviewingCount)",
        "resolved" to "Selesai ($resolvedCount)",
        "dismissed" to "Ditolak",
    )
    if (visible(y, y + 32f)) {
        var fx = 20f
        filters.forEach { (key, lbl) ->
            val active = devReportFilter == key
            val fw = kit.measure(lbl, 10f, true) + 16f
            if (fx + fw <= 372f) {
                val pill = RectF(fx, y, fx + fw, y + 26f)
                kit.rounded(c, pill, if (active) kit.tint else kit.card, 7f, if (active) kit.blue else kit.border)
                kit.centerText(c, lbl, pill, 10f, if (active) kit.blue else kit.muted, active)
                press(c, "devReportFilter:$key", pill, 7f)
                bounds.putVisible("devReportFilter:$key", pill, topLimit = HEADER_H, bottomLimit = navClear)
                fx += fw + 6f
            }
        }
    }
    y += 38f

    if (devReportsLoading && allReports.isEmpty()) {
        kit.text(c, "Memuat laporan penjual...", 20f, y + 24f, 13f, kit.muted)
        contentHeight = 0f
        return
    }

    val filtered = if (devReportFilter == "Semua") {
        allReports
    } else {
        allReports.filter { it.status.equals(devReportFilter, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        val emptyRect = RectF(20f, y, 370f, y + 86f)
        if (visible(emptyRect.top, emptyRect.bottom)) {
            kit.rounded(c, emptyRect, kit.card, 8f, kit.border)
            kit.text(c, "Belum ada laporan penjual", 34f, y + 34f, 13.5f, kit.ink, true)
            kit.text(c, "Laporan pembeli (beserta 2 bukti foto) otomatis masuk live di sini.", 34f, y + 56f, 11f, kit.muted)
        }
        contentHeight = (y + 100f - off) + GLASS_TOP_OFFSET
        return
    }

    filtered.forEach { rep ->
        val descLines = kit.wrap(rep.description, 324f, 11.5f, maxLines = 5)
        val noteLines = if (rep.developerNotes.isNotBlank()) {
            kit.wrap("Catatan Dev: ${rep.developerNotes}", 324f, 10.5f, maxLines = 3, bold = true)
        } else {
            emptyList()
        }
        val sourceText = when {
            rep.orderPickupCode.isNotBlank() -> "Riwayat Pesanan #${rep.orderPickupCode}"
            rep.source == "order_history" -> "Riwayat Pembelian"
            else -> "Profil Warung"
        }
        val reporterLine = buildString {
            append("Pelapor: ${rep.reporterName}")
            if (rep.reporterNis.isNotBlank()) append(" (NIS ${rep.reporterNis})")
        }
        val descH = descLines.size * 15.5f
        val noteH = if (noteLines.isNotEmpty()) noteLines.size * 14f + 8f else 0f
        val actionsH = if (rep.status == "open" || rep.status == "reviewing") 76f else 38f
        val cardH = 82f + descH + 104f + noteH + actionsH + 14f
        val card = RectF(20f, y, 370f, y + cardH)

        if (visible(card.top, card.bottom)) {
            val (stLbl, stBg, stFg) = reportStatusBadgeColors(rep.status)
            kit.rounded(c, card, kit.card, 10f, if (rep.status == "open") kit.dangerBorder else kit.border)
            if (rep.status == "open") {
                kit.rect(c, 20f, y + 10f, 23.5f, y + cardH - 10f, kit.danger)
            }

            // Header: Stall name + Status badge
            val badgeW = drawRightBadge(c, kit, stLbl, 358f, y + 12f, stBg, stFg, 9.5f, padX = 8f, padY = 4f, radius = 5f)
            kit.textEllipsized(c, rep.stallName, 32f, y + 24f, 358f - badgeW - 42f, 14f, kit.ink, true)
            kit.textEllipsized(
                c,
                "${rep.category} · $sourceText · ${rep.timeLabel}",
                32f,
                y + 42f,
                324f,
                10.5f,
                kit.danger,
                true,
            )
            kit.textEllipsized(c, "$reporterLine · Penjual: ${rep.sellerName}", 32f, y + 58f, 324f, 10.5f, kit.muted)
            kit.line(c, 32f, y + 68f, 358f, y + 68f, kit.divider)

            // Wrapped description
            var cy = y + 84f
            descLines.forEachIndexed { i, line ->
                kit.text(c, line, 32f, cy + i * 15.5f, 11.5f, kit.ink)
            }
            cy += descH + 6f

            // 2 Evidence Photo Thumbnails side by side (tappable full preview)
            val evW = (326f - 10f) / 2f
            val evH = 88f
            val evUrls = listOf("Bukti 1" to rep.evidenceUrl1, "Bukti 2" to rep.evidenceUrl2)
            evUrls.forEachIndexed { idx, (label, url) ->
                val left = 32f + idx * (evW + 10f)
                val box = RectF(left, cy, left + evW, cy + evH)
                val bmp = url.takeIf { it.startsWith("https://") }?.let { ProductImageCache.images[it] }
                if (bmp != null) {
                    drawBitmapCenterCrop(c, bmp, box, 7f)
                    kit.rounded(c, box, Color.TRANSPARENT, 7f, kit.border)
                } else {
                    kit.rounded(c, box, kit.bg, 7f, kit.border)
                    kit.centerText(c, "Memuat $label...", box, 10f, kit.muted)
                    if (url.startsWith("https://")) activity()?.loadProductImage(url)
                }
                val tag = RectF(left + 6f, cy + evH - 22f, left + 70f, cy + evH - 6f)
                kit.rounded(c, tag, Color.argb(190, 15, 23, 42), 4f)
                kit.centerText(c, "$label · Lihat", tag, 8.5f, Color.WHITE, true)
                val hitKey = "devReportImg:$label:$url"
                press(c, hitKey, box, 7f)
                bounds.putVisible(hitKey, box, topLimit = HEADER_H, bottomLimit = navClear)
            }
            cy += evH + 14f

            if (noteLines.isNotEmpty()) {
                noteLines.forEachIndexed { i, line ->
                    kit.text(c, line, 32f, cy + i * 14f, 10.5f, kit.blue, true)
                }
                cy += noteH
            }

            // Action row 1: Moderation status transitions
            if (rep.status == "open" || rep.status == "reviewing") {
                val btnW = (326f - 12f) / 3f
                val actions = listOf(
                    Triple("Tinjau", "reviewing", Color.rgb(217, 119, 6)),
                    Triple("Selesai", "resolved", kit.success),
                    Triple("Tolak", "dismissed", kit.muted),
                )
                actions.forEachIndexed { idx, (lbl, st, fg) ->
                    val bx = 32f + idx * (btnW + 6f)
                    val btn = RectF(bx, cy, bx + btnW, cy + 30f)
                    kit.rounded(c, btn, kit.bg, 6f, fg)
                    kit.centerText(c, lbl, btn, 10.5f, fg, true)
                    val key = "devReportStatus:${rep.id}:$st"
                    press(c, key, btn, 6f)
                    bounds.putVisible(key, btn, topLimit = HEADER_H, bottomLimit = navClear)
                }
                cy += 38f
            }

            // Action row 2: Direct action against reported Seller (Warn / Suspend)
            val sellerUid = rep.sellerId
            if (!sellerUid.isNullOrBlank()) {
                val halfW = (326f - 8f) / 2f
                val warnBtn = RectF(32f, cy, 32f + halfW, cy + 30f)
                kit.rounded(c, warnBtn, kit.warnBox, 6f, Color.rgb(217, 119, 6))
                kit.centerText(c, "Beri Peringatan Seller", warnBtn, 10f, Color.rgb(217, 119, 6), true)
                press(c, "devWarn:$sellerUid", warnBtn, 6f)
                bounds.putVisible("devWarn:$sellerUid", warnBtn, topLimit = HEADER_H, bottomLimit = navClear)

                val suspBtn = RectF(32f + halfW + 8f, cy, 358f, cy + 30f)
                kit.rounded(c, suspBtn, kit.card, 6f, kit.danger)
                kit.centerText(c, "Tangguhkan Seller", suspBtn, 10f, kit.danger, true)
                press(c, "devSuspend:$sellerUid", suspBtn, 6f)
                bounds.putVisible("devSuspend:$sellerUid", suspBtn, topLimit = HEADER_H, bottomLimit = navClear)
            }
        }
        y += cardH + 12f
    }

    contentHeight = (y - off) + GLASS_TOP_OFFSET + 20f
}
