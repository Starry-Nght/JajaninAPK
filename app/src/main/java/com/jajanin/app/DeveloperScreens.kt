package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

internal val DEV_TABS = listOf("Overview", "Users", "Sellers", "Orders", "Warn", "Info")

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
    // --- Fixed header ---
    kit.rect(c, 0f, 0f, w, 80f, kit.card)
    kit.line(c, 0f, 80f, w, 80f)
    kit.text(c, "Developer", 20f, 40f, 19f, kit.ink, true)
    kit.text(c, "Monitoring Jajanin", 20f, 62f, 11f, kit.muted)
    val refresh = RectF(246f, 20f, 288f, 62f)
    kit.rounded(c, refresh, kit.tint, 13f)
    drawIcon(c, "refresh", refresh.centerX(), refresh.centerY(), 17f, kit.blue)
    bounds["devRefresh"] = RectF(refresh)
    val out = RectF(296f, 20f, 370f, 62f)
    kit.rounded(c, out, kit.tint, 13f)
    kit.centerText(c, "Keluar", out, 11f, kit.danger, true)
    bounds["devLogout"] = RectF(out)
    // --- Fixed tab bar ---
    val tabTop = 80f
    kit.rect(c, 0f, tabTop, w, tabTop + 46f, kit.card)
    kit.line(c, 0f, tabTop + 46f, w, tabTop + 46f)
    val slot = w / DEV_TABS.size
    DEV_TABS.forEachIndexed { i, tab ->
        val active = devTab == tab
        val r = RectF(i * slot + 4f, tabTop + 7f, (i + 1) * slot - 4f, tabTop + 39f)
        if (active) kit.rounded(c, r, kit.blue, 11f)
        kit.centerText(c, tab, r, 10f, if (active) Color.WHITE else kit.muted, active)
        bounds["devTab:$tab"] = RectF(i * slot, tabTop, (i + 1) * slot, tabTop + 46f)
    }
    if (devTab == "Warn" || devTab == "Info") {
        val fab = RectF(w - 76f, h - 72f, w - 20f, h - 16f)
        kit.rounded(c, fab, kit.blue, 28f)
        drawIcon(c, "plus", fab.centerX(), fab.centerY(), 22f, Color.WHITE)
        bounds[if (devTab == "Warn") "devWarnNew" else "devAnnNew"] = RectF(fab)
    }
}

private fun JajaninView.devContentTop(): Float = 140f

private fun JajaninView.drawDevOverview(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    if (devStatsLoading) {
        kit.centerText(c, "Memuat statistik...", RectF(24f, top + 40f, 366f, top + 100f), 14f, kit.muted)
        contentHeight = 0f
        return
    }
    val s = devStats
    val cards = listOf(
        "Pembeli" to s.totalBuyers.toString(),
        "Penjual" to s.totalSellers.toString(),
        "Warung aktif" to s.activeStalls.toString(),
        "Total order" to s.totalOrders.toString(),
        "Pending" to s.pending.toString(),
        "Selesai" to s.completed.toString(),
        "Batal" to s.cancelled.toString(),
        "Hari ini" to s.todayCount.toString(),
    )
    kit.text(c, "Ringkasan platform", 20f, top + 22f + off, 16f, kit.ink, true)
    cards.forEachIndexed { index, (label, value) ->
        val col = index % 2
        val row = index / 2
        val left = 20f + col * 179f
        val y = top + 34f + row * 66f + off
        if (y + 58f < 130f || y > h) return@forEachIndexed
        kit.rounded(c, RectF(left, y, left + 169f, y + 58f), kit.card, 14f, kit.border)
        kit.text(c, label, left + 14f, y + 23f, 11f, kit.muted)
        kit.text(c, value, left + 14f, y + 47f, 18f, kit.ink, true)
    }
    val revTop = top + 34f + 4 * 66f + 8f
    kit.rounded(c, RectF(20f, revTop + off, 370f, revTop + 58f + off), kit.blue, 14f)
    kit.text(c, "Transaksi hari ini", 36f, revTop + 25f + off, 11f, Color.WHITE)
    kit.text(c, formatRupiah(s.todayRevenue), 36f, revTop + 47f + off, 17f, Color.WHITE, true)
    // --- Developer-only daily reset: tutup order aktif + bersihkan monitor ---
    val resetTop = revTop + 58f + 10f
    val resetRect = RectF(20f, resetTop + off, 370f, resetTop + 46f + off)
    if (resetTop + off > 130f - 46f && resetTop + off < h) {
        kit.rounded(c, resetRect, kit.danger, 14f)
        drawIcon(c, "refresh", 52f, resetRect.centerY(), 15f, Color.WHITE)
        kit.text(c, if (devResetting) "Mereset..." else "Reset harian monitor", 74f, resetRect.centerY() - 2f, 14f, Color.WHITE, true)
        kit.text(c, "Tutup order aktif · bersihkan warn/info · nolkan ⚠", 74f, resetRect.centerY() + 16f, 9f, Color.WHITE)
        bounds["devReset"] = RectF(resetRect)
    }
    val actTitleY = resetTop + 46f + 26f
    kit.text(c, "Aktivitas terbaru", 20f, actTitleY + off, 16f, kit.ink, true)
    if (devOrders.isEmpty()) {
        kit.centerText(c, "Belum ada aktivitas order.", RectF(24f, actTitleY + 12f + off, 366f, actTitleY + 62f + off), 12f, kit.muted)
    }
    devOrders.take(8).forEachIndexed { index, order ->
        val y = actTitleY + 24f + index * 56f + off
        if (y + 50f < 130f || y > h) return@forEachIndexed
        kit.rounded(c, RectF(20f, y, 370f, y + 50f), kit.card, 12f, kit.border)
        kit.text(c, "${order.buyerName.take(16)} · ${order.pickupCode}", 32f, y + 21f, 12f, kit.ink, true)
        kit.text(c, "${formatRupiah(order.totalIdr)} · ${statusLabelShort(order.status)}", 32f, y + 40f, 10f, kit.muted)
    }
    contentHeight = actTitleY + 24f + devOrders.take(8).size * 56f + 40f
    if (devError != null) kit.centerText(c, devError ?: "", RectF(24f, top + 4f + off, 366f, top + 30f + off), 11f, kit.danger)
}

private fun JajaninView.drawDevUsers(c: Canvas, w: Float, h: Float, off: Float, sellersOnly: Boolean) {
    val top = devContentTop()
    kit.text(c, if (sellersOnly) "Penjual & warung" else "Pengguna", 20f, top + 22f + off, 16f, kit.ink, true)
    if (devError != null && devUsers.isEmpty()) {
        kit.centerText(c, devError ?: "", RectF(24f, top + 118f + off, 366f, top + 150f + off), 11f, kit.danger)
    }
    val search = RectF(20f, top + 32f + off, 370f, top + 74f + off)
    kit.rounded(c, search, kit.card, 13f, kit.border)
    kit.text(c, if (devQuery.isBlank()) "Cari nama / email..." else devQuery, 34f, top + 59f + off, 13f, if (devQuery.isBlank()) kit.muted else kit.ink)
    bounds["devSearch"] = RectF(search)
    val filters = if (sellersOnly) listOf("Semua", "Aktif", "Ditangguhkan") else listOf("Semua", "Buyer", "Seller", "Ditangguhkan")
    var x = 20f
    filters.forEach { filter ->
        val width = kit.measure(filter, 10f, true) + 24f
        val chip = RectF(x, top + 82f + off, x + width, top + 108f + off)
        val active = devUserFilter == filter
        kit.rounded(c, chip, if (active) kit.blue else kit.card, 13f, if (active) null else kit.border)
        kit.centerText(c, filter, chip, 10f, if (active) Color.WHITE else kit.ink, true)
        bounds["devFilter:$filter"] = RectF(chip)
        x += width + 7f
    }
    val rows = devUsers.filter { u ->
        (!sellersOnly || u.role == "seller") &&
            (devQuery.isBlank() || u.displayName.contains(devQuery.trim(), true) || u.email.contains(devQuery.trim(), true)) &&
            when (devUserFilter) {
                "Buyer" -> u.role == "buyer"
                "Seller" -> u.role == "seller"
                "Aktif" -> u.accountStatus == "active"
                "Ditangguhkan" -> u.accountStatus == "suspended"
                else -> true
            }
    }
    if (devUsersLoading && devUsers.isEmpty()) {
        kit.centerText(c, "Memuat pengguna...", RectF(24f, top + 150f + off, 366f, top + 200f + off), 13f, kit.muted)
        contentHeight = 0f
        return
    }
    val selected = devUsers.firstOrNull { it.id == devSelectedUserId }
    var listTop = top + 118f
    if (selected != null) {
        val card = RectF(20f, listTop + off, 370f, listTop + 178f + off)
        kit.rounded(c, card, kit.card, 14f, kit.blue)
        kit.text(c, selected.displayName.take(26), 34f, listTop + 28f + off, 15f, kit.ink, true)
        kit.text(c, selected.email.take(34), 34f, listTop + 49f + off, 11f, kit.muted)
        val meta = "${selected.role} · ${selected.accountStatus} · ${selected.orderCount} order · ${selected.warningCount} peringatan"
        kit.text(c, meta.take(48), 34f, listTop + 69f + off, 10f, kit.muted)
        if (selected.role == "seller") {
            val stall = "${selected.stallName ?: "Belum ada warung"}${selected.stallOpen?.let { if (it) " · buka" else " · tutup" } ?: ""}"
            kit.text(c, stall.take(44), 34f, listTop + 89f + off, 10f, kit.blue, true)
        }
        val b1 = RectF(32f, listTop + 100f + off, 128f, listTop + 130f + off)
        val b2 = RectF(134f, listTop + 100f + off, 230f, listTop + 130f + off)
        val b3 = RectF(236f, listTop + 100f + off, 358f, listTop + 130f + off)
        kit.rounded(c, b1, kit.tint, 10f)
        kit.centerText(c, "Peringatkan", b1, 10f, kit.blue, true)
        bounds["devWarn:${selected.id}"] = RectF(b1)
        val suspended = selected.accountStatus == "suspended"
        kit.rounded(c, b2, if (suspended) kit.successSoft else Color.rgb(251, 235, 235), 10f)
        kit.centerText(c, if (suspended) "Aktifkan" else "Tangguhkan", b2, 10f, if (suspended) kit.success else kit.danger, true)
        bounds[if (suspended) "devActive:${selected.id}" else "devSuspend:${selected.id}"] = RectF(b2)
        if (selected.stallId != null) {
            kit.rounded(c, b3, kit.bg, 10f, kit.border)
            kit.centerText(c, if (selected.stallOpen == true) "Tutup warung" else "Buka warung", b3, 10f, kit.ink, true)
            bounds["devStall:${selected.stallId}:${!(selected.stallOpen == true)}"] = RectF(b3)
        } else {
            kit.rounded(c, b3, kit.bg, 10f)
            kit.centerText(c, selected.role, b3, 10f, kit.muted, true)
        }
        // Undang pembeli jadi penjual (hanya untuk role buyer).
        if (selected.role == "buyer") {
            val pending = devInvites.firstOrNull { it.targetUserId == selected.id && it.status == "pending" }
            val inviteBtn = RectF(32f, listTop + 136f + off, 358f, listTop + 166f + off)
            if (pending != null) {
                kit.rounded(c, inviteBtn, kit.warnSoft, 10f)
                kit.centerText(c, "Undangan seller menunggu · Batalkan", inviteBtn, 10f, Color.rgb(183, 116, 0), true)
                bounds["devInviteCancel:${pending.id}"] = RectF(inviteBtn)
            } else {
                kit.rounded(c, inviteBtn, kit.tint, 10f)
                kit.centerText(c, "Undang jadi penjual", inviteBtn, 10f, kit.blue, true)
                bounds["devInvite:${selected.id}"] = RectF(inviteBtn)
            }
        }
        val close = RectF(318f, listTop + 8f + off, 358f, listTop + 36f + off)
        drawIcon(c, "close", close.centerX(), close.centerY(), 12f, kit.muted)
        bounds["devUserClose"] = RectF(close)
        listTop += 188f
    }
    rows.forEachIndexed { index, u ->
        val y = listTop + index * 62f + off
        if (y + 56f < 130f || y > h) return@forEachIndexed
        kit.rounded(c, RectF(20f, y, 370f, y + 56f), kit.card, 12f, if (u.id == devSelectedUserId) kit.blue else kit.border)
        kit.text(c, u.displayName.take(20), 32f, y + 23f, 12f, kit.ink, true)
        kit.text(c, "${u.role} · ${u.accountStatus} · ${u.orderCount} order · ⚠${u.warningCount}", 32f, y + 43f, 10f, statusColor(u.accountStatus))
        bounds["devUser:${u.id}"] = RectF(20f, y.coerceAtLeast(130f), 370f, y + 56f)
    }
    if (rows.isEmpty()) kit.centerText(c, "Tidak ada pengguna cocok.", RectF(24f, listTop + 20f + off, 366f, listTop + 70f + off), 12f, kit.muted)
    contentHeight = listTop + rows.size * 62f + 40f
}

private fun statusColor(status: String): Int = when (status) {
    "suspended" -> Color.rgb(206, 60, 60)
    "warned" -> Color.rgb(183, 116, 0)
    else -> Color.rgb(112, 120, 137)
}

private fun JajaninView.drawDevOrders(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    kit.text(c, "Order platform (baca saja)", 20f, top + 22f + off, 16f, kit.ink, true)
    if (devError != null && devOrders.isEmpty()) {
        kit.centerText(c, devError ?: "", RectF(24f, top + 34f + off, 366f, top + 66f + off), 11f, kit.danger)
    }
    if (devOrdersLoading && devOrders.isEmpty()) {
        kit.centerText(c, "Memuat order...", RectF(24f, top + 60f + off, 366f, top + 110f + off), 13f, kit.muted)
        contentHeight = 0f
        return
    }
    var y = top + 32f
    devOrders.forEach { order ->
        val expanded = devExpandedOrderId == order.id
        val cardH = if (expanded) 96f + order.items.size * 20f else 76f
        if (y + cardH + off > 130f && y + off < h) {
            kit.rounded(c, RectF(20f, y + off, 370f, y + cardH + off), kit.card, 12f, kit.border)
            kit.text(c, "#${order.id.take(8)} · ${order.buyerName.take(14)}", 32f, y + 24f + off, 12f, kit.ink, true)
            kit.text(c, statusLabelShort(order.status), 358f - kit.measure(statusLabelShort(order.status), 10f, true), y + 24f + off, 10f, kit.muted, true)
            kit.text(c, "${formatRupiah(order.totalIdr)} · ${order.paymentMethod ?: "-"} · ${order.paymentStatus}", 32f, y + 46f + off, 10f, kit.muted)
            kit.text(c, order.timeLabel, 32f, y + 64f + off, 10f, kit.muted)
            if (expanded) {
                order.items.forEachIndexed { i, item ->
                    kit.text(c, "${item.quantity}× ${item.itemName.take(24)} · ${formatRupiah(item.unitPriceIdr * item.quantity)}", 32f, y + 84f + i * 20f + off, 10f, kit.ink)
                }
            }
            bounds["devOrder:${order.id}"] = RectF(20f, (y + off).coerceAtLeast(130f), 370f, y + cardH + off)
        }
        y += cardH + 8f
    }
    if (devOrders.isEmpty()) kit.centerText(c, "Belum ada order.", RectF(24f, y + off, 366f, y + 60f + off), 12f, kit.muted)
    contentHeight = y + 40f
}

private fun JajaninView.drawDevWarnings(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    kit.text(c, "Riwayat peringatan", 20f, top + 22f + off, 16f, kit.ink, true)
    if (devWarningsLoading && devWarnings.isEmpty()) {
        kit.centerText(c, "Memuat...", RectF(24f, top + 60f + off, 366f, top + 110f + off), 13f, kit.muted)
        contentHeight = 0f
        return
    }
    var y = top + 32f
    devWarnings.forEach { warn ->
        val cardH = 78f
        if (y + cardH + off > 130f && y + off < h - 80f) {
            kit.rounded(c, RectF(20f, y + off, 370f, y + cardH + off), kit.card, 12f, kit.border)
            val sevColor = when (warn.severity) {
                "critical" -> Color.rgb(206, 60, 60)
                "warning" -> Color.rgb(183, 116, 0)
                else -> Color.rgb(0, 99, 214)
            }
            kit.rounded(c, RectF(32f, y + 12f + off, 40f, y + 20f + off), sevColor, 4f)
            kit.text(c, warn.title.take(28), 48f, y + 26f + off, 12f, kit.ink, true)
            kit.text(c, if (warn.isRead) "dibaca" else "belum dibaca", 358f - kit.measure(if (warn.isRead) "dibaca" else "belum dibaca", 9f), y + 26f + off, 9f, kit.muted)
            kit.text(c, warn.message.take(46), 32f, y + 46f + off, 10f, kit.muted)
            kit.text(c, "${warn.severity} · ${warn.createdAt.take(16).replace('T', ' ')}", 32f, y + 64f + off, 9f, kit.muted)
        }
        y += cardH + 8f
    }
    if (devWarnings.isEmpty()) kit.centerText(c, "Belum ada peringatan terkirim.", RectF(24f, y + off, 366f, y + 60f + off), 12f, kit.muted)
    contentHeight = y + 100f
}

private fun JajaninView.drawDevAnnouncements(c: Canvas, w: Float, h: Float, off: Float) {
    val top = devContentTop()
    kit.text(c, "Pengumuman global", 20f, top + 22f + off, 16f, kit.ink, true)
    if (devAnnLoading && devAnnouncements.isEmpty()) {
        kit.centerText(c, "Memuat...", RectF(24f, top + 60f + off, 366f, top + 110f + off), 13f, kit.muted)
        contentHeight = 0f
        return
    }
    var y = top + 32f
    devAnnouncements.forEach { ann ->
        val cardH = 78f
        if (y + cardH + off > 130f && y + off < h - 80f) {
            kit.rounded(c, RectF(20f, y + off, 370f, y + cardH + off), kit.card, 12f, kit.border)
            kit.text(c, ann.title.take(28), 32f, y + 26f + off, 12f, kit.ink, true)
            kit.text(c, ann.targetRole, 358f - kit.measure(ann.targetRole, 9f, true), y + 26f + off, 9f, kit.blue, true)
            kit.text(c, ann.message.take(46), 32f, y + 46f + off, 10f, kit.muted)
            kit.text(c, ann.createdAt.take(16).replace('T', ' '), 32f, y + 64f + off, 9f, kit.muted)
        }
        y += cardH + 8f
    }
    if (devAnnouncements.isEmpty()) kit.centerText(c, "Belum ada pengumuman.", RectF(24f, y + off, 366f, y + 60f + off), 12f, kit.muted)
    contentHeight = y + 100f
}

/** Unread inbox count for buyer/seller (warnings + announcements + seller invites badge). */
internal fun JajaninView.inboxUnreadCount(): Int =
    myWarnings.count { !it.isRead } + myAnnouncements.size +
        myInvites.count { it.status == "pending" }
