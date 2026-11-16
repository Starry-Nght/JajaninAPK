package com.jajanin.app.feature.developer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.DrawKit
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.DevAnnouncementRow
import com.jajanin.app.data.repository.DevStats
import com.jajanin.app.data.repository.DevUser
import com.jajanin.app.data.repository.DevWarningRow
import com.jajanin.app.data.repository.SellerInvite

internal val DEV_TABS = listOf("Overview", "Users", "Sellers", "Orders", "Warn", "Info")

/**
 * Modern Developer & DevOps Console UI for Jajanin.
 * Features an executive-level command dashboard, realtime system telemetry,
 * user/seller administration, order inspection, and moderation/broadcast controls.
 */
internal fun JajaninView.drawDeveloper(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll

    when (devTab) {
        "Users" -> drawDevUsers(c, w, h, off, sellersOnly = false)
        "Sellers" -> drawDevUsers(c, w, h, off, sellersOnly = true)
        "Orders" -> drawDevOrders(c, w, h, off)
        "Warn" -> drawDevWarnings(c, w, h, off)
        "Info" -> drawDevAnnouncements(c, w, h, off)
        else -> drawDevOverview(c, w, h, off)
    }

    // ================= Pinned Header & Segmented Console Dock =================
    c.save()
    c.translate(-slideX, 0f)

    // Top Command Deck (Glassmorphism floating bar)
    val devBar = RectF(16f, 10f, 374f, 66f)
    kit.rounded(c, RectF(devBar.left, devBar.top + 2f, devBar.right, devBar.bottom + 4f), kit.glassShadow, 18f)
    kit.rounded(c, devBar, kit.glass, 18f, kit.glassBorder)

    // Live Telemetry status dot & badge
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 185, 129) }
    c.drawCircle(32f, 26f, 3.5f, dotPaint)
    kit.text(c, "LIVE OPS", 40f, 29f, 8.5f, Color.rgb(52, 211, 153), true)

    kit.text(c, "Developer Console", 30f, 47f, 15f, Color.WHITE, true)
    kit.text(c, "Super Admin & Realtime Monitor", 30f, 60f, 9.5f, Color.argb(190, 255, 255, 255))

    // Header Quick Actions: Refresh, Bell (Inbox/Notif), Logout
    val refresh = RectF(238f, 18f, 274f, 54f)
    kit.rounded(c, refresh, Color.argb(45, 255, 255, 255), 12f)
    drawIcon(c, "refresh", refresh.centerX(), refresh.centerY(), 15f, Color.WHITE)
    bounds["devRefresh"] = RectF(refresh)
    press(c, "devRefresh", refresh, 12f)

    val bell = RectF(280f, 18f, 316f, 54f)
    kit.rounded(c, bell, Color.argb(45, 255, 255, 255), 12f)
    drawIcon(c, "bell", bell.centerX(), bell.centerY(), 15f, Color.WHITE)
    val unreadCount = inboxUnreadCount()
    if (unreadCount > 0) {
        val badgeCx = bell.right - 6f
        val badgeCy = bell.top + 6f
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68) }
        c.drawCircle(badgeCx, badgeCy, 6.5f, badgePaint)
        val countLabel = if (unreadCount > 9) "9+" else unreadCount.toString()
        kit.centerText(c, countLabel, RectF(badgeCx - 6f, badgeCy - 6f, badgeCx + 6f, badgeCy + 6f), 7.5f, Color.WHITE, true)
    }
    bounds["devBell"] = RectF(bell)
    press(c, "devBell", bell, 12f)

    val out = RectF(322f, 18f, 364f, 54f)
    kit.rounded(c, out, Color.argb(55, 239, 68, 68), 12f)
    kit.centerText(c, "Keluar", out, 10.5f, Color.rgb(254, 202, 202), true)
    bounds["devLogout"] = RectF(out)
    press(c, "devLogout", out, 12f)

    // Segmented Dock (Pinned tab bar with badges & crisp vector icons)
    val tabTop = 74f
    val tabBottom = 122f
    val dockRect = RectF(16f, tabTop, 374f, tabBottom)
    kit.rounded(c, RectF(dockRect.left, dockRect.top + 2f, dockRect.right, dockRect.bottom + 3f), kit.glassShadow, 16f)
    kit.rounded(c, dockRect, kit.glass, 16f, kit.glassBorder)

    val slotW = dockRect.width() / DEV_TABS.size
    DEV_TABS.forEachIndexed { i, tab ->
        val active = devTab == tab
        val slotLeft = dockRect.left + i * slotW
        val slotRight = slotLeft + slotW
        val pill = RectF(slotLeft + 2.5f, tabTop + 3.5f, slotRight - 2.5f, tabBottom - 3.5f)

        if (active) {
            kit.rounded(c, pill, kit.blue, 12f)
        }

        val iconName = when (tab) {
            "Overview" -> "grid"
            "Users" -> "person"
            "Sellers" -> "store"
            "Orders" -> "receipt"
            "Warn" -> "alert"
            "Info" -> "megaphone"
            else -> "grid"
        }
        val iconColor = if (active) Color.WHITE else Color.argb(175, 255, 255, 255)
        val textColor = if (active) Color.WHITE else Color.argb(175, 255, 255, 255)

        drawIcon(c, iconName, pill.centerX(), pill.centerY() - 6f, 13f, iconColor)
        val labelW = kit.measure(tab, 8f, active)
        kit.text(c, tab, pill.centerX() - labelW / 2f, pill.centerY() + 12f, 8f, textColor, active)

        // Dynamic badges on specific tabs
        when (tab) {
            "Orders" -> if (devOrders.any { it.status.equals("pending", true) }) {
                c.drawCircle(slotRight - 8f, tabTop + 8f, 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 158, 11) })
            }
            "Warn" -> if (devWarnings.any { !it.isRead }) {
                c.drawCircle(slotRight - 8f, tabTop + 8f, 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68) })
            }
            "Sellers" -> if (devInvites.any { it.status == "pending" }) {
                c.drawCircle(slotRight - 8f, tabTop + 8f, 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(59, 130, 246) })
            }
        }

        bounds["devTab:$tab"] = RectF(slotLeft, tabTop, slotRight, tabBottom)
        press(c, "devTab:$tab", pill, 12f)
    }

    // Modern Floating Action Button (FAB) for Warn and Info
    if (devTab == "Warn" || devTab == "Info") {
        val fabW = 158f
        val fabH = 46f
        val fab = RectF(w - fabW - 16f, h - fabH - 20f, w - 16f, h - 20f)

        kit.rounded(c, RectF(fab.left, fab.top + 3f, fab.right, fab.bottom + 5f), Color.argb(60, 0, 0, 0), 23f)
        val fabBg = if (devTab == "Warn") Color.rgb(220, 38, 38) else kit.blue
        kit.rounded(c, fab, fabBg, 23f)

        drawIcon(c, "plus", fab.left + 22f, fab.centerY(), 16f, Color.WHITE)
        val fabText = if (devTab == "Warn") "+ Peringatan" else "+ Pengumuman"
        kit.text(c, fabText, fab.left + 38f, fab.centerY() + 4f, 11.5f, Color.WHITE, true)

        bounds[if (devTab == "Warn") "devWarnNew" else "devAnnNew"] = RectF(fab)
        press(c, if (devTab == "Warn") "devWarnNew" else "devAnnNew", fab, 23f)
    }

    c.restore()
}

private fun JajaninView.devContentTop(): Float = 134f

// ================= TAB 1: OVERVIEW =================

private fun JajaninView.drawDevOverview(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()

    if (devStatsLoading) {
        val loadingRect = RectF(16f, top + 30f + off, 374f, top + 150f + off)
        kit.card(c, loadingRect, 16f)
        drawIcon(c, "refresh", loadingRect.centerX(), loadingRect.centerY() - 16f, 24f, kit.blue)
        kit.centerText(c, "Menghubungkan ke Realtime Monitor...", RectF(loadingRect.left, loadingRect.centerY() + 8f, loadingRect.right, loadingRect.centerY() + 26f), 13f, kit.ink, true)
        kit.centerText(c, "Memuat metrik dan telemetri platform...", RectF(loadingRect.left, loadingRect.centerY() + 28f, loadingRect.right, loadingRect.centerY() + 44f), 10.5f, kit.muted)
        contentHeight = 0f
        return
    }

    var y = top + 8f + off

    // Error Banner
    if (devError != null) {
        val errRect = RectF(16f, y, 374f, y + 40f)
        kit.rounded(c, errRect, Color.argb(35, 239, 68, 68), 12f, kit.dangerBorder)
        drawIcon(c, "alert", 32f, errRect.centerY(), 14f, kit.danger)
        kit.text(c, devError ?: "", 46f, errRect.centerY() + 4f, 10f, kit.danger, true)
        y += 48f
    }

    // Telemetry System Pulse Strip
    val stripRect = RectF(16f, y, 374f, y + 32f)
    kit.rounded(c, stripRect, kit.tint, 10f)
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 185, 129) }
    c.drawCircle(30f, stripRect.centerY(), 3.5f, dotPaint)
    kit.text(c, "SISTEM OPERASIONAL", 38f, stripRect.centerY() + 3.5f, 9f, Color.rgb(16, 185, 129), true)
    val syncText = "Supabase Realtime Sync Active"
    kit.text(c, syncText, 360f - kit.measure(syncText, 9f), stripRect.centerY() + 3.5f, 9f, kit.muted)
    y += 40f

    val s = devStats

    // Hero Performance / Revenue Card
    val revRect = RectF(16f, y, 374f, y + 110f)
    kit.rounded(c, RectF(revRect.left, revRect.top + 2f, revRect.right, revRect.bottom + 4f), kit.glassShadow, 18f)
    kit.rounded(c, revRect, kit.blue, 18f)

    drawIcon(c, "zap", revRect.left + 28f, revRect.top + 24f, 15f, Color.rgb(253, 224, 71))
    kit.text(c, "TRANSAKSI PLATFORM HARI INI", revRect.left + 42f, revRect.top + 28f, 9.5f, Color.argb(215, 255, 255, 255), true)
    val livePill = "● LIVE DATA"
    kit.text(c, livePill, revRect.right - kit.measure(livePill, 8.5f, true) - 20f, revRect.top + 28f, 8.5f, Color.rgb(134, 239, 172), true)

    kit.text(c, formatRupiah(s.todayRevenue), revRect.left + 24f, revRect.top + 64f, 22f, Color.WHITE, true)

    val chip1 = RectF(revRect.left + 20f, revRect.bottom - 34f, revRect.left + 172f, revRect.bottom - 12f)
    kit.rounded(c, chip1, Color.argb(55, 255, 255, 255), 8f)
    kit.centerText(c, "${s.todayCount} Pesanan Hari Ini", chip1, 9.5f, Color.WHITE, true)

    val avgVal = if (s.todayCount > 0) s.todayRevenue / s.todayCount else 0
    val chip2 = RectF(revRect.left + 180f, revRect.bottom - 34f, revRect.right - 20f, revRect.bottom - 12f)
    kit.rounded(c, chip2, Color.argb(55, 255, 255, 255), 8f)
    kit.centerText(c, "Rata-rata ${formatRupiah(avgVal)}", chip2, 9.5f, Color.WHITE, true)

    y += 122f

    // KPI Metrics Grid
    kit.text(c, "Metrik Kunci Platform", 18f, y + 14f, 15f, kit.ink, true)
    kit.text(c, "Performa & statistik realtime", 18f, y + 28f, 9.5f, kit.muted)
    y += 36f

    data class StatCard(val label: String, val value: String, val sub: String, val icon: String, val tintColor: Int, val iconColor: Int)

    val successRate = if (s.completed + s.cancelled > 0) ((s.completed * 100) / (s.completed + s.cancelled)) else 100
    val kpiCards = listOf(
        StatCard("Pembeli", s.totalBuyers.toString(), "Akun terdaftar", "person", Color.argb(35, 59, 130, 246), Color.rgb(37, 99, 235)),
        StatCard("Penjual", s.totalSellers.toString(), "${s.activeStalls} warung buka", "store", Color.argb(35, 139, 92, 246), Color.rgb(124, 58, 237)),
        StatCard("Warung Aktif", s.activeStalls.toString(), "Buka terima order", "checkCircle", Color.argb(35, 16, 185, 129), Color.rgb(16, 185, 129)),
        StatCard("Total Pesanan", s.totalOrders.toString(), "Semua riwayat", "receipt", Color.argb(35, 100, 116, 139), Color.rgb(71, 85, 105)),
        StatCard("Perlu Diproses", s.pending.toString(), "Antrean pesanan", "clock", Color.argb(40, 245, 158, 11), Color.rgb(217, 119, 6)),
        StatCard("Order Selesai", s.completed.toString(), "Transaksi tuntas", "check", Color.argb(35, 16, 185, 129), Color.rgb(16, 185, 129)),
        StatCard("Dibatalkan", s.cancelled.toString(), "Pesanan gugur", "close", Color.argb(35, 239, 68, 68), Color.rgb(220, 38, 38)),
        StatCard("Rasio Sukses", "$successRate%", "Tingkat tuntas", "zap", Color.argb(35, 6, 182, 212), Color.rgb(8, 145, 178)),
    )

    val cardW = 173f
    val cardH = 68f
    kpiCards.forEachIndexed { index, item ->
        val col = index % 2
        val row = index / 2
        val left = 16f + col * 185f
        val topY = y + row * 76f

        if (topY + cardH >= 126f && topY <= h) {
            val r = RectF(left, topY, left + cardW, topY + cardH)
            kit.card(c, r, 14f)

            val iconBox = RectF(left + 10f, topY + 12f, left + 44f, topY + 46f)
            kit.rounded(c, iconBox, item.tintColor, 10f)
            drawIcon(c, item.icon, iconBox.centerX(), iconBox.centerY(), 16f, item.iconColor)

            kit.text(c, item.label, left + 52f, topY + 24f, 10f, kit.muted)
            kit.text(c, item.value, left + 52f, topY + 45f, 17f, kit.ink, true)
            kit.text(c, item.sub, left + 52f, topY + 58f, 8.5f, kit.muted)
        }
    }

    y += 4 * 76f + 12f

    // Daily Reset & Platform Operations Panel
    val resetCard = RectF(16f, y, 374f, y + 92f)
    if (resetCard.bottom >= 126f && resetCard.top <= h) {
        kit.card(c, resetCard, 16f)
        val shieldBox = RectF(28f, y + 12f, 54f, y + 38f)
        kit.rounded(c, shieldBox, Color.argb(35, 239, 68, 68), 8f)
        drawIcon(c, "shield", shieldBox.centerX(), shieldBox.centerY(), 14f, kit.danger)

        kit.text(c, "Platform Ops: Reset Harian (00:00 WIB)", 62f, y + 27f, 12f, kit.ink, true)
        kit.text(c, "Tutup antrean aktif, bersihkan log info/warn, dan nolkan counter ⚠ akun.", 28f, y + 46f, 9.5f, kit.muted)

        val resetBtn = RectF(28f, y + 54f, 362f, y + 84f)
        kit.rounded(c, resetBtn, kit.danger, 10f)
        drawIcon(c, "refresh", resetBtn.left + 24f, resetBtn.centerY(), 13f, Color.WHITE)
        val resetLabel = if (devResetting) "Mereset Sistem Monitor..." else "Eksekusi Reset Harian Monitor"
        kit.centerText(c, resetLabel, resetBtn, 11f, Color.WHITE, true)
        bounds.putVisible("devReset", resetBtn, 126f)
        press(c, "devReset", resetBtn, 10f)
    }

    y += 104f

    // Recent Live Orders Feed
    kit.text(c, "Aktivitas Pesanan Realtime", 18f, y + 14f, 15f, kit.ink, true)
    drawBadge(c, kit, "${devOrders.size} Order", 216f, y + 2f, kit.tint, kit.blue, 9f)
    y += 28f

    if (devOrders.isEmpty()) {
        val emptyRect = RectF(16f, y, 374f, y + 64f)
        kit.card(c, emptyRect, 14f)
        drawIcon(c, "inbox", emptyRect.centerX(), emptyRect.centerY() - 8f, 16f, kit.muted)
        kit.centerText(c, "Belum ada aktivitas pesanan tercatat.", RectF(emptyRect.left, emptyRect.centerY() + 8f, emptyRect.right, emptyRect.bottom), 11.5f, kit.muted)
        y += 72f
    } else {
        devOrders.take(8).forEach { order ->
            val orderH = 80f
            if (y + orderH >= 126f && y <= h) {
                val cardRect = RectF(16f, y, 374f, y + orderH)
                kit.card(c, cardRect, 14f)

                // Top row: ID, Pickup Code, Status
                val idStr = "#${order.id.take(8).uppercase()}"
                kit.text(c, idStr, 30f, y + 22f, 11f, kit.ink, true)
                val codeStr = "[ KODE ${order.pickupCode} ]"
                kit.text(c, codeStr, 96f, y + 22f, 10f, kit.blue, true)

                val (stBg, stFg) = orderStatusBadgeColors(order.status)
                drawRightBadge(c, kit, statusLabelShort(order.status).uppercase(), 360f, y + 10f, stBg, stFg, 8.5f)

                // Middle row: Buyer Name & Total IDR
                drawIcon(c, "person", 36f, y + 42f, 12f, kit.muted)
                kit.text(c, order.buyerName.take(20), 48f, y + 46f, 12.5f, kit.ink, true)
                val totalStr = formatRupiah(order.totalIdr)
                kit.text(c, totalStr, 360f - kit.measure(totalStr, 13f, true), y + 46f, 13f, kit.blue, true)

                // Bottom row: Payment info & Items summary
                val payStr = "${order.paymentMethod?.uppercase() ?: "TUNAI"} · ${if (order.paymentStatus == "paid") "LUNAS" else "BELUM BAYAR"}"
                kit.text(c, payStr, 30f, y + 66f, 9.5f, kit.muted)
                val timeStr = order.timeLabel
                kit.text(c, timeStr, 360f - kit.measure(timeStr, 9.5f), y + 66f, 9.5f, kit.muted)

                bounds.putVisible("devOrder:${order.id}", cardRect, 126f)
                press(c, "devOrder:${order.id}", cardRect, 14f)
            }
            y += orderH + 8f
        }
    }

    contentHeight = (y - off) + 40f
}

// ================= TAB 2 & 3: USERS & SELLERS =================

private fun JajaninView.drawDevUsers(c: Canvas, w: Float, h: Float, off: Float, sellersOnly: Boolean) {
    val top = devContentTop()
    var y = top + 8f + off

    val title = if (sellersOnly) "Mitra Penjual & Warung" else "Manajemen Pengguna"
    val sub = if (sellersOnly) "Kelola akun penjual, status toko, dan kemitraan kantin" else "Kelola seluruh akun pengguna, verifikasi, dan moderasi"
    kit.text(c, title, 18f, y + 14f, 16f, kit.ink, true)
    kit.text(c, sub, 18f, y + 28f, 9.5f, kit.muted)
    y += 34f

    // Search Bar with crisp search icon and clear indicator
    val search = RectF(16f, y, 374f, y + 42f)
    kit.card(c, search, 13f)
    drawIcon(c, "search", search.left + 20f, search.centerY(), 14f, kit.blue)
    val queryText = if (devQuery.isBlank()) "Cari nama, email, nomor HP..." else devQuery
    val queryColor = if (devQuery.isBlank()) kit.muted else kit.ink
    kit.text(c, queryText, search.left + 36f, search.centerY() + 4f, 11.5f, queryColor, devQuery.isNotBlank())
    if (devQuery.isNotBlank()) {
        drawIcon(c, "close", search.right - 18f, search.centerY(), 12f, kit.muted)
    }
    bounds.putVisible("devSearch", search, 126f)
    press(c, "devSearch", search, 13f)
    y += 50f

    // Filter Chips with dynamic counts
    val filters = if (sellersOnly) listOf("Semua", "Aktif", "Ditangguhkan") else listOf("Semua", "Buyer", "Seller", "Ditangguhkan")
    var chipX = 16f
    filters.forEach { filter ->
        val count = when (filter) {
            "Buyer" -> devUsers.count { it.role == "buyer" }
            "Seller" -> devUsers.count { it.role == "seller" }
            "Aktif" -> devUsers.count { if (sellersOnly) it.role == "seller" && it.accountStatus == "active" else it.accountStatus == "active" }
            "Ditangguhkan" -> devUsers.count { if (sellersOnly) it.role == "seller" && it.accountStatus == "suspended" else it.accountStatus == "suspended" }
            else -> if (sellersOnly) devUsers.count { it.role == "seller" } else devUsers.size
        }
        val label = "$filter ($count)"
        val chipW = kit.measure(label, 9.5f, true) + 20f
        val chip = RectF(chipX, y, chipX + chipW, y + 28f)
        val active = devUserFilter == filter

        kit.rounded(c, chip, if (active) kit.blue else kit.card, 14f, if (active) null else kit.border)
        kit.centerText(c, label, chip, 9.5f, if (active) Color.WHITE else kit.ink, true)
        bounds.putVisible("devFilter:$filter", chip, 126f)
        press(c, "devFilter:$filter", chip, 14f)
        chipX += chipW + 6f
    }
    y += 36f

    val rows = devUsers.filter { u ->
        (!sellersOnly || u.role == "seller") &&
            (devQuery.isBlank() ||
                u.displayName.contains(devQuery.trim(), true) ||
                u.stallName.orEmpty().contains(devQuery.trim(), true) ||
                u.email.contains(devQuery.trim(), true) ||
                u.phone.contains(devQuery.trim())) &&
            when (devUserFilter) {
                "Buyer" -> u.role == "buyer"
                "Seller" -> u.role == "seller"
                "Aktif" -> u.accountStatus == "active"
                "Ditangguhkan" -> u.accountStatus == "suspended"
                else -> true
            }
    }

    if (devUsersLoading && devUsers.isEmpty()) {
        val loadRect = RectF(16f, y, 374f, y + 80f)
        kit.card(c, loadRect, 14f)
        kit.centerText(c, "Memuat daftar akun pengguna...", loadRect, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    // Selected User Detailed Inspector Modal Card
    val selected = devUsers.firstOrNull { it.id == devSelectedUserId }
    if (selected != null) {
        val hasStall = selected.role == "seller"
        val isBuyer = selected.role == "buyer"
        val inspectorH = if (isBuyer) 220f else if (hasStall) 214f else 184f
        val card = RectF(16f, y, 374f, y + inspectorH)

        // Inspector card container with glowing border
        kit.rounded(c, RectF(card.left, card.top + 2f, card.right, card.bottom + 4f), kit.glassShadow, 16f)
        kit.rounded(c, card, kit.card, 16f, kit.blue)

        // User Avatar Circle
        val (roleBg, roleFg) = roleBadgeColors(selected.role)
        drawAvatar(c, kit, selected.displayName, 42f, y + 36f, 20f, roleFg)

        // Name & Identity (Seller shows Real Name + Store Name clearly for Developer)
        val inspectorTitle = if (selected.role == "seller" && !selected.stallName.isNullOrBlank()) {
            "${selected.displayName} · ${selected.stallName}"
        } else {
            selected.displayName
        }
        kit.textEllipsized(c, inspectorTitle, 70f, y + 26f, 258f, 14f, kit.ink, true)
        val subEmail = selected.email.take(28) + if (selected.phone.isNotBlank()) " · ${selected.phone}" else ""
        kit.text(c, subEmail, 70f, y + 42f, 10f, kit.muted)

        // Role & Account Status Badges
        val roleW = drawBadge(c, kit, selected.role.uppercase(), 70f, y + 48f, roleBg, roleFg, 8.5f)
        val (stBg, stFg) = accountStatusBadgeColors(selected.accountStatus)
        drawBadge(c, kit, selected.accountStatus.uppercase(), 70f + roleW + 6f, y + 48f, stBg, stFg, 8.5f)

        // Close Inspector Button
        val closeBtn = RectF(334f, y + 10f, 364f, y + 40f)
        drawIcon(c, "close", closeBtn.centerX(), closeBtn.centerY(), 14f, kit.muted)
        bounds.putVisible("devUserClose", closeBtn, 126f)
        press(c, "devUserClose", closeBtn, 10f)

        // User Stats Matrix ribbon
        val ribbon = RectF(28f, y + 74f, 362f, y + 106f)
        kit.rounded(c, ribbon, kit.tint, 10f)
        val colW = ribbon.width() / 3f
        kit.centerText(c, "${selected.orderCount} Order", RectF(ribbon.left, ribbon.top, ribbon.left + colW, ribbon.bottom), 10f, kit.ink, true)
        val warnClr = if (selected.warningCount > 0) kit.danger else kit.muted
        kit.centerText(c, "⚠ ${selected.warningCount} Peringatan", RectF(ribbon.left + colW, ribbon.top, ribbon.left + colW * 2f, ribbon.bottom), 10f, warnClr, true)
        val statusLabel = if (selected.accountStatus == "active") "Akun Aktif" else "Ditangguhkan"
        kit.centerText(c, statusLabel, RectF(ribbon.left + colW * 2f, ribbon.top, ribbon.right, ribbon.bottom), 10f, kit.ink, true)

        var actY = y + 116f

        // Stall Status (if seller)
        if (selected.role == "seller") {
            val stallStr = "Warung: ${selected.stallName ?: "Belum ada warung"}${selected.stallOpen?.let { if (it) " (Buka)" else " (Tutup)" } ?: ""}"
            kit.text(c, stallStr.take(44), 30f, actY + 12f, 10.5f, kit.blue, true)
            actY += 22f
        }

        // Action Buttons Row 1: Peringatkan & Tangguhkan/Aktifkan
        val b1 = RectF(28f, actY, 190f, actY + 34f)
        val b2 = RectF(198f, actY, 362f, actY + 34f)

        kit.rounded(c, b1, Color.argb(35, 245, 158, 11), 10f)
        drawIcon(c, "alert", b1.left + 18f, b1.centerY(), 13f, Color.rgb(217, 119, 6))
        kit.centerText(c, "Peringatkan User", RectF(b1.left + 12f, b1.top, b1.right, b1.bottom), 10.5f, Color.rgb(217, 119, 6), true)
        bounds.putVisible("devWarn:${selected.id}", b1, 126f)
        press(c, "devWarn:${selected.id}", b1, 10f)

        val suspended = selected.accountStatus == "suspended"
        val b2Bg = if (suspended) kit.successSoft else Color.argb(35, 239, 68, 68)
        val b2Fg = if (suspended) kit.success else kit.danger
        kit.rounded(c, b2, b2Bg, 10f)
        kit.centerText(c, if (suspended) "Aktifkan Akun" else "Tangguhkan Akun", b2, 10.5f, b2Fg, true)
        bounds.putVisible(if (suspended) "devActive:${selected.id}" else "devSuspend:${selected.id}", b2, 126f)
        press(c, if (suspended) "devActive:${selected.id}" else "devSuspend:${selected.id}", b2, 10f)

        actY += 40f

        // Action Buttons Row 2: Stall Toggle (if seller) or Seller Invite (if buyer)
        if (selected.stallId != null) {
            val b3 = RectF(28f, actY, 362f, actY + 32f)
            kit.rounded(c, b3, kit.bg, 10f, kit.border)
            val isOpen = selected.stallOpen == true
            kit.centerText(c, if (isOpen) "Tutup Warung Sementara" else "Buka Warung Kembali", b3, 10f, kit.ink, true)
            bounds.putVisible("devStall:${selected.stallId}:${!isOpen}", b3, 126f)
            press(c, "devStall:${selected.stallId}:${!isOpen}", b3, 10f)
        } else if (selected.role == "buyer") {
            val pending = devInvites.firstOrNull { it.targetUserId == selected.id && it.status == "pending" }
            val inviteBtn = RectF(28f, actY, 362f, actY + 34f)
            if (pending != null) {
                kit.rounded(c, inviteBtn, kit.warnSoft, 10f)
                kit.centerText(c, "Undangan Menunggu · Batalkan Undangan", inviteBtn, 10.5f, Color.rgb(183, 116, 0), true)
                bounds.putVisible("devInviteCancel:${pending.id}", inviteBtn, 126f)
                press(c, "devInviteCancel:${pending.id}", inviteBtn, 10f)
            } else {
                kit.rounded(c, inviteBtn, kit.tint, 10f)
                drawIcon(c, "plus", inviteBtn.left + 24f, inviteBtn.centerY(), 13f, kit.blue)
                kit.centerText(c, "Undang Akun Menjadi Penjual", inviteBtn, 10.5f, kit.blue, true)
                bounds.putVisible("devInvite:${selected.id}", inviteBtn, 126f)
                press(c, "devInvite:${selected.id}", inviteBtn, 10f)
            }
        }

        y += inspectorH + 12f
    }

    // User Cards List
    if (rows.isEmpty()) {
        val emptyRect = RectF(16f, y, 374f, y + 64f)
        kit.card(c, emptyRect, 14f)
        kit.centerText(c, "Tidak ada akun pengguna yang cocok.", emptyRect, 12f, kit.muted)
        y += 72f
    } else {
        rows.forEach { u ->
            val userH = 68f
            if (y + userH >= 126f && y <= h) {
                val card = RectF(16f, y, 374f, y + userH)
                val isSelected = u.id == devSelectedUserId
                kit.card(c, card, 14f)
                if (isSelected) {
                    kit.rounded(c, card, Color.TRANSPARENT, 14f, kit.blue)
                }

                // Avatar
                val (_, roleFg) = roleBadgeColors(u.role)
                drawAvatar(c, kit, u.displayName, 42f, y + 34f, 18f, roleFg)

                // Online/Status Dot on avatar corner
                val isSuspended = u.accountStatus == "suspended"
                val statusDotColor = if (isSuspended) Color.rgb(239, 68, 68) else Color.rgb(16, 185, 129)
                c.drawCircle(54f, y + 46f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusDotColor })

                // Details (For sellers: show both Real Name & Store Name)
                val primaryTitle = if (u.role == "seller" && !u.stallName.isNullOrBlank()) {
                    "${u.displayName}  ·  Toko: ${u.stallName}"
                } else {
                    u.displayName
                }
                kit.textEllipsized(c, primaryTitle, 68f, y + 26f, 224f, 13f, kit.ink, true)
                val subline = "${u.email.take(24)}${if (u.phone.isNotBlank()) " · ${u.phone}" else ""}"
                kit.textEllipsized(c, subline, 68f, y + 43f, 224f, 10f, kit.muted)
                val roleCaption = "${u.role.uppercase()} · ${u.accountStatus.uppercase()}"
                kit.text(c, roleCaption, 68f, y + 57f, 8.5f, kit.muted)

                // Right stack: Order count & Warning badge
                val rightX = 360f
                if (u.warningCount > 0) {
                    drawRightBadge(c, kit, "⚠ ${u.warningCount}", rightX, y + 16f, Color.argb(40, 239, 68, 68), kit.danger, 9f)
                }
                val orderLabel = "${u.orderCount} order"
                kit.text(c, orderLabel, rightX - kit.measure(orderLabel, 10f, true), y + 48f, 10f, kit.ink, true)

                if (u.role == "seller" && u.stallOpen != null) {
                    val stallStatus = if (u.stallOpen) "Buka" else "Tutup"
                    val stallColor = if (u.stallOpen) Color.rgb(16, 185, 129) else kit.muted
                    kit.text(c, stallStatus, rightX - kit.measure(stallStatus, 9f), y + 60f, 9f, stallColor)
                }

                bounds.putVisible("devUser:${u.id}", card, 126f)
                press(c, "devUser:${u.id}", card, 14f)
            }
            y += userH + 8f
        }
    }

    contentHeight = (y - off) + 40f
}

// ================= TAB 4: ORDERS =================

private fun JajaninView.drawDevOrders(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    var y = top + 8f + off

    kit.text(c, "Platform Order Inspector", 18f, y + 14f, 16f, kit.ink, true)
    kit.text(c, "Audit dan pantau seluruh transaksi kantin secara langsung", 18f, y + 28f, 9.5f, kit.muted)
    y += 34f

    // Summary banner
    val summaryRect = RectF(16f, y, 374f, y + 36f)
    kit.rounded(c, summaryRect, kit.tint, 10f)
    kit.text(c, "${devOrders.size} Pesanan Terdata", 30f, summaryRect.centerY() + 3.5f, 10.5f, kit.ink, true)
    val totalRevenue = devOrders.sumOf { it.totalIdr }
    val revStr = "Total: ${formatRupiah(totalRevenue)}"
    kit.text(c, revStr, 360f - kit.measure(revStr, 11f, true), summaryRect.centerY() + 3.5f, 11f, kit.blue, true)
    y += 46f

    if (devError != null && devOrders.isEmpty()) {
        val errRect = RectF(16f, y, 374f, y + 40f)
        kit.rounded(c, errRect, Color.argb(35, 239, 68, 68), 12f, kit.dangerBorder)
        kit.centerText(c, devError ?: "", errRect, 11f, kit.danger)
        y += 48f
    }

    if (devOrdersLoading && devOrders.isEmpty()) {
        val loadRect = RectF(16f, y, 374f, y + 80f)
        kit.card(c, loadRect, 14f)
        kit.centerText(c, "Memuat data seluruh pesanan...", loadRect, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    if (devOrders.isEmpty()) {
        val emptyRect = RectF(16f, y, 374f, y + 64f)
        kit.card(c, emptyRect, 14f)
        drawIcon(c, "inbox", emptyRect.centerX(), emptyRect.centerY() - 8f, 16f, kit.muted)
        kit.centerText(c, "Belum ada order masuk di platform.", RectF(emptyRect.left, emptyRect.centerY() + 8f, emptyRect.right, emptyRect.bottom), 11.5f, kit.muted)
        y += 72f
    } else {
        devOrders.forEach { order ->
            val expanded = devExpandedOrderId == order.id
            val baseH = 88f
            val itemLinesH = if (expanded) order.items.size * 22f + 36f else 0f
            val cardH = baseH + itemLinesH

            if (y + cardH >= 126f && y <= h) {
                val cardRect = RectF(16f, y, 374f, y + cardH)
                kit.card(c, cardRect, 14f)

                // Top row: ID, Pickup Code, Status
                val idStr = "#${order.id.take(8).uppercase()}"
                kit.text(c, idStr, 30f, y + 22f, 11f, kit.ink, true)
                val codeStr = "[ KODE ${order.pickupCode} ]"
                kit.text(c, codeStr, 96f, y + 22f, 10f, kit.blue, true)

                val (stBg, stFg) = orderStatusBadgeColors(order.status)
                drawRightBadge(c, kit, statusLabelShort(order.status).uppercase(), 360f, y + 10f, stBg, stFg, 8.5f)

                // Middle row: Buyer Name & Total IDR
                drawIcon(c, "person", 36f, y + 42f, 12f, kit.muted)
                kit.text(c, order.buyerName.take(20), 48f, y + 46f, 12.5f, kit.ink, true)
                val totalStr = formatRupiah(order.totalIdr)
                kit.text(c, totalStr, 360f - kit.measure(totalStr, 13.5f, true), y + 46f, 13.5f, kit.blue, true)

                // Bottom row: Payment info & Time & Expansion Hint
                val payStr = "${order.paymentMethod?.uppercase() ?: "TUNAI"} · ${if (order.paymentStatus == "paid") "LUNAS" else "BELUM BAYAR"}"
                kit.text(c, payStr, 30f, y + 68f, 9.5f, kit.muted)
                val timeStr = order.timeLabel
                kit.text(c, timeStr, 170f, y + 68f, 9.5f, kit.muted)

                val hintText = if (expanded) "Tutup ▲" else "Rincian ▼"
                kit.text(c, hintText, 360f - kit.measure(hintText, 9.5f, true), y + 68f, 9.5f, kit.blue, true)

                // Expanded Item Breakdown Table
                if (expanded) {
                    val lineY = y + 78f
                    kit.line(c, 28f, lineY, 362f, lineY, kit.divider)
                    var itemY = lineY + 16f
                    order.items.forEach { item ->
                        val qtyStr = "${item.quantity}×"
                        drawBadge(c, kit, qtyStr, 30f, itemY - 11f, kit.tint, kit.blue, 8.5f, padX = 4f, padY = 2f)
                        kit.text(c, item.itemName.take(24), 56f, itemY, 11f, kit.ink)
                        val subtotal = formatRupiah(item.unitPriceIdr * item.quantity)
                        kit.text(c, subtotal, 360f - kit.measure(subtotal, 11f, true), itemY, 11f, kit.muted, true)
                        itemY += 22f
                    }
                    kit.line(c, 28f, itemY - 6f, 362f, itemY - 6f, kit.divider)
                    kit.text(c, "Total Tagihan Pesanan", 30f, itemY + 10f, 10.5f, kit.ink, true)
                    kit.text(c, totalStr, 360f - kit.measure(totalStr, 11.5f, true), itemY + 10f, 11.5f, kit.blue, true)
                }

                bounds.putVisible("devOrder:${order.id}", cardRect, 126f)
                press(c, "devOrder:${order.id}", cardRect, 14f)
            }
            y += cardH + 8f
        }
    }

    contentHeight = (y - off) + 40f
}

// ================= TAB 5: WARN (MODERASI) =================

private fun JajaninView.drawDevWarnings(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    var y = top + 8f + off

    kit.text(c, "Riwayat & Log Moderasi", 18f, y + 14f, 16f, kit.ink, true)
    kit.text(c, "Rekam jejak peringatan dan penegakan aturan platform", 18f, y + 28f, 9.5f, kit.muted)
    y += 34f

    // Overview metric banner
    val summaryRect = RectF(16f, y, 374f, y + 36f)
    kit.rounded(c, summaryRect, kit.tint, 10f)
    val colW = summaryRect.width() / 3f
    kit.centerText(c, "${devWarnings.size} Total Log", RectF(summaryRect.left, summaryRect.top, summaryRect.left + colW, summaryRect.bottom), 10f, kit.ink, true)
    val unreadW = devWarnings.count { !it.isRead }
    kit.centerText(c, "$unreadW Belum Dibaca", RectF(summaryRect.left + colW, summaryRect.top, summaryRect.left + colW * 2f, summaryRect.bottom), 10f, if (unreadW > 0) kit.danger else kit.muted, true)
    val critW = devWarnings.count { it.severity == "critical" }
    kit.centerText(c, "$critW Kritis", RectF(summaryRect.left + colW * 2f, summaryRect.top, summaryRect.right, summaryRect.bottom), 10f, if (critW > 0) kit.danger else kit.muted, true)
    y += 46f

    if (devWarningsLoading && devWarnings.isEmpty()) {
        val loadRect = RectF(16f, y, 374f, y + 80f)
        kit.card(c, loadRect, 14f)
        kit.centerText(c, "Memuat log peringatan...", loadRect, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    if (devWarnings.isEmpty()) {
        val emptyRect = RectF(16f, y, 374f, y + 64f)
        kit.card(c, emptyRect, 14f)
        drawIcon(c, "alert", emptyRect.centerX(), emptyRect.centerY() - 8f, 16f, kit.muted)
        kit.centerText(c, "Belum ada peringatan moderasi terkirim.", RectF(emptyRect.left, emptyRect.centerY() + 8f, emptyRect.right, emptyRect.bottom), 11.5f, kit.muted)
        y += 72f
    } else {
        devWarnings.forEach { warn ->
            val msgLines = wrapText(kit, warn.message, 316f, 10f)
            val lineCount = maxOf(msgLines.size, 1)
            val cardH = 74f + lineCount * 16f

            if (y + cardH >= 126f && y <= h - 70f) {
                val card = RectF(16f, y, 374f, y + cardH)
                kit.card(c, card, 14f)

                // Left Severity Accent Stripe
                val sevColor = when (warn.severity.lowercase()) {
                    "critical" -> Color.rgb(220, 38, 38)
                    "warning" -> Color.rgb(217, 119, 6)
                    else -> Color.rgb(37, 99, 235)
                }
                kit.rounded(c, RectF(card.left, card.top, card.left + 5f, card.bottom), sevColor, 2f)

                // Top row: Severity badge & Read/Unread badge
                val (sevBg, sevFg) = severityBadgeColors(warn.severity)
                val sevLabel = when (warn.severity.lowercase()) {
                    "critical" -> "KRITIS"
                    "warning" -> "PERINGATAN"
                    else -> "INFORMASI"
                }
                val badgeW = drawBadge(c, kit, sevLabel, 28f, y + 10f, sevBg, sevFg, 8.5f)

                val readText = if (warn.isRead) "DIBACA ✓" else "BARU ●"
                val readColor = if (warn.isRead) kit.muted else Color.rgb(217, 119, 6)
                kit.text(c, readText, 360f - kit.measure(readText, 8.5f, true), y + 21f, 8.5f, readColor, true)

                // Warning Title
                kit.text(c, warn.title.take(32), 28f, y + 38f, 13f, kit.ink, true)

                // Wrapped Message Body
                var textY = y + 54f
                msgLines.forEach { line ->
                    kit.text(c, line, 28f, textY, 10f, kit.muted)
                    textY += 16f
                }

                // Footer: Target User ID & Timestamp
                val targetStr = "Target: #${warn.targetUserId.take(8)}..."
                kit.text(c, targetStr, 28f, card.bottom - 10f, 9f, kit.muted)
                val timeStr = warn.createdAt.take(16).replace('T', ' ') + " WIB"
                kit.text(c, timeStr, 360f - kit.measure(timeStr, 9f), card.bottom - 10f, 9f, kit.muted)
            }
            y += cardH + 8f
        }
    }

    contentHeight = (y - off) + 100f
}

// ================= TAB 6: INFO (PENGUMUMAN) =================

private fun JajaninView.drawDevAnnouncements(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    var y = top + 8f + off

    kit.text(c, "Siaran & Pengumuman Platform", 18f, y + 14f, 16f, kit.ink, true)
    kit.text(c, "Broadcast pengumuman ke seluruh role pengguna aplikasi", 18f, y + 28f, 9.5f, kit.muted)
    y += 34f

    // Summary banner
    val summaryRect = RectF(16f, y, 374f, y + 36f)
    kit.rounded(c, summaryRect, kit.tint, 10f)
    kit.text(c, "${devAnnouncements.size} Siaran Pengumuman", 30f, summaryRect.centerY() + 3.5f, 10.5f, kit.ink, true)
    val syncText = "Realtime Broadcast Active"
    kit.text(c, syncText, 360f - kit.measure(syncText, 9.5f), summaryRect.centerY() + 3.5f, 9.5f, kit.blue)
    y += 46f

    if (devAnnLoading && devAnnouncements.isEmpty()) {
        val loadRect = RectF(16f, y, 374f, y + 80f)
        kit.card(c, loadRect, 14f)
        kit.centerText(c, "Memuat daftar pengumuman...", loadRect, 12f, kit.muted)
        contentHeight = 0f
        return
    }

    if (devAnnouncements.isEmpty()) {
        val emptyRect = RectF(16f, y, 374f, y + 64f)
        kit.card(c, emptyRect, 14f)
        drawIcon(c, "megaphone", emptyRect.centerX(), emptyRect.centerY() - 8f, 16f, kit.muted)
        kit.centerText(c, "Belum ada siaran pengumuman aktif.", RectF(emptyRect.left, emptyRect.centerY() + 8f, emptyRect.right, emptyRect.bottom), 11.5f, kit.muted)
        y += 72f
    } else {
        devAnnouncements.forEach { ann ->
            val msgLines = wrapText(kit, ann.message, 316f, 10.5f)
            val lineCount = maxOf(msgLines.size, 1)
            val cardH = 74f + lineCount * 16f

            if (y + cardH >= 126f && y <= h - 70f) {
                val card = RectF(16f, y, 374f, y + cardH)
                kit.card(c, card, 14f)

                // Left Accent Stripe (Purple / Indigo)
                kit.rounded(c, RectF(card.left, card.top, card.left + 5f, card.bottom), Color.rgb(124, 58, 237), 2f)

                // Target Audience Badge
                val (tBg, tFg) = targetRoleBadgeColors(ann.targetRole)
                val targetLabel = when (ann.targetRole.lowercase()) {
                    "sellers" -> "PENJUAL KANTIN"
                    "buyers" -> "PEMBELI"
                    else -> "SEMUA PENGGUNA"
                }
                drawBadge(c, kit, targetLabel, 28f, y + 10f, tBg, tFg, 8.5f)

                val activeStr = "SIARAN AKTIF ●"
                kit.text(c, activeStr, 360f - kit.measure(activeStr, 8.5f, true), y + 21f, 8.5f, Color.rgb(16, 185, 129), true)

                // Title
                kit.text(c, ann.title.take(32), 28f, y + 38f, 13.5f, kit.ink, true)

                // Wrapped Message Body
                var textY = y + 54f
                msgLines.forEach { line ->
                    kit.text(c, line, 28f, textY, 10.5f, kit.muted)
                    textY += 16f
                }

                // Footer: Date & Author
                val dateStr = ann.createdAt.take(16).replace('T', ' ') + " WIB"
                kit.text(c, dateStr, 28f, card.bottom - 10f, 9f, kit.muted)
                val authorStr = "Admin / Developer"
                kit.text(c, authorStr, 360f - kit.measure(authorStr, 9f, true), card.bottom - 10f, 9f, kit.blue, true)
            }
            y += cardH + 8f
        }
    }

    contentHeight = (y - off) + 100f
}

// ================= VISUAL HELPERS & COLOR SYSTEMS =================

/** Unread inbox count for buyer/seller (warnings + announcements + seller invites badge). */
internal fun JajaninView.inboxUnreadCount(): Int =
    myWarnings.count { !it.isRead } + myAnnouncements.size +
        myInvites.count { it.status == "pending" }

private fun LinkedHashMap<String, RectF>.putVisible(
    key: String,
    r: RectF,
    topLimit: Float = 126f,
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
    textSize: Float = 9.5f,
    padX: Float = 7f,
    padY: Float = 3.5f,
    radius: Float = 6f,
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
    textSize: Float = 9.5f,
    padX: Float = 7f,
    padY: Float = 3.5f,
    radius: Float = 6f,
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

private fun drawAvatar(
    c: Canvas,
    kit: DrawKit,
    name: String,
    cx: Float,
    cy: Float,
    radius: Float,
    bgColor: Int,
    textColor: Int = Color.WHITE
) {
    val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    kit.rounded(c, rect, bgColor, radius)
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val fontSize = radius * 1.05f
    val textW = kit.measure(initial, fontSize, true)
    kit.text(c, initial, cx - textW / 2f, cy + fontSize * 0.36f, fontSize, textColor, true)
}

private fun orderStatusBadgeColors(status: String): Pair<Int, Int> = when (status.lowercase()) {
    "pending" -> Pair(Color.argb(45, 245, 158, 11), Color.rgb(217, 119, 6))
    "confirmed", "preparing", "processing" -> Pair(Color.argb(40, 37, 99, 235), Color.rgb(37, 99, 235))
    "ready" -> Pair(Color.argb(40, 147, 51, 234), Color.rgb(147, 51, 234))
    "completed" -> Pair(Color.argb(40, 16, 185, 129), Color.rgb(16, 185, 129))
    "cancelled" -> Pair(Color.argb(40, 239, 68, 68), Color.rgb(220, 38, 38))
    else -> Pair(Color.argb(30, 100, 116, 139), Color.rgb(100, 116, 139))
}

private fun accountStatusBadgeColors(status: String): Pair<Int, Int> = when (status.lowercase()) {
    "active" -> Pair(Color.argb(40, 16, 185, 129), Color.rgb(16, 185, 129))
    "suspended" -> Pair(Color.argb(40, 239, 68, 68), Color.rgb(220, 38, 38))
    "warned" -> Pair(Color.argb(45, 245, 158, 11), Color.rgb(217, 119, 6))
    else -> Pair(Color.argb(30, 100, 116, 139), Color.rgb(100, 116, 139))
}

private fun roleBadgeColors(role: String): Pair<Int, Int> = when (role.lowercase()) {
    "seller" -> Pair(Color.argb(40, 124, 58, 237), Color.rgb(124, 58, 237))
    "developer" -> Pair(Color.argb(45, 245, 158, 11), Color.rgb(217, 119, 6))
    else -> Pair(Color.argb(35, 59, 130, 246), Color.rgb(37, 99, 235))
}

private fun severityBadgeColors(severity: String): Pair<Int, Int> = when (severity.lowercase()) {
    "critical" -> Pair(Color.argb(40, 239, 68, 68), Color.rgb(220, 38, 38))
    "warning" -> Pair(Color.argb(45, 245, 158, 11), Color.rgb(217, 119, 6))
    else -> Pair(Color.argb(40, 37, 99, 235), Color.rgb(37, 99, 235))
}

private fun targetRoleBadgeColors(target: String): Pair<Int, Int> = when (target.lowercase()) {
    "sellers" -> Pair(Color.argb(40, 124, 58, 237), Color.rgb(124, 58, 237))
    "buyers" -> Pair(Color.argb(40, 16, 185, 129), Color.rgb(16, 185, 129))
    else -> Pair(Color.argb(40, 147, 51, 234), Color.rgb(147, 51, 234))
}
