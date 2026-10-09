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
    // --- Fixed header ramping: judul + aksi teks. ---
    kit.rect(c, 0f, 0f, w, 80f, kit.card)
    kit.line(c, 0f, 80f, w, 80f)
    kit.text(c, "Pantau Jajanin", 20f, 46f, 19f, kit.ink, true)
    kit.text(c, "Developer", 20f, 66f, 11f, kit.muted)
    kit.centerText(c, "⟳", RectF(252f, 20f, 292f, 62f), 22f, kit.blue, true)
    bounds["devRefresh"] = RectF(252f, 20f, 292f, 62f)
    kit.centerText(c, "Keluar", RectF(300f, 20f, 370f, 62f), 12f, kit.danger, true)
    bounds["devLogout"] = RectF(300f, 20f, 370f, 62f)
    // --- Tab berupa teks bergaris bawah, bukan pil. ---
    val tabTop = 80f
    kit.rect(c, 0f, tabTop, w, tabTop + 44f, kit.card)
    kit.line(c, 0f, tabTop + 44f, w, tabTop + 44f)
    val slot = w / DEV_TABS.size
    DEV_TABS.forEachIndexed { i, tab ->
        val active = devTab == tab
        val cx = i * slot + slot / 2f
        val tw = kit.measure(tab, 11f, active)
        kit.text(c, tab, cx - tw / 2f, tabTop + 28f, 11f, if (active) kit.blue else kit.muted, active)
        if (active) kit.rect(c, cx - tw / 2f, tabTop + 36f, cx + tw / 2f, tabTop + 39f, kit.blue)
        bounds["devTab:$tab"] = RectF(i * slot, tabTop, (i + 1) * slot, tabTop + 44f)
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
    // Tiga angka kunci berdampingan tanpa kartu, lalu angka uang sebaris.
    kit.text(c, "PLATFORM", 20f, top + 22f + off, 11f, kit.muted, true)
    val thirds = listOf(
        "Pembeli" to s.totalBuyers.toString(),
        "Penjual" to s.totalSellers.toString(),
        "Warung buka" to s.activeStalls.toString(),
    )
    thirds.forEachIndexed { i, (label, value) ->
        val cx = 20f + i * 117f
        if (top + 70f + off > 130f && top + 30f + off < h) {
            kit.text(c, value, cx, top + 58f + off, 26f, kit.ink, true)
            kit.text(c, label, cx, top + 80f + off, 11f, kit.muted)
        }
    }
    kit.line(c, 20f, top + 96f + off, 370f, top + 96f + off)
    kit.text(c, "Uang masuk hari ini", 20f, top + 122f + off, 12f, kit.muted)
    kit.text(c, formatRupiah(s.todayRevenue), 20f, top + 152f + off, 24f, kit.ink, true)
    kit.text(
        c, "${s.todayCount} order · ${s.pending} nunggu · ${s.completed} selesai · ${s.cancelled} batal",
        20f, top + 174f + off, 11f, kit.muted,
    )
    // Reset harian: baris teks merah, bukan spanduk.
    val resetRect = RectF(20f, top + 190f + off, 370f, top + 228f + off)
    if (top + 228f + off > 130f && top + 190f + off < h) {
        kit.text(c, if (devResetting) "Mereset..." else "Reset harian monitor  →", 20f, top + 210f + off, 13f, kit.danger, true)
        kit.text(c, "Tutup order aktif · bersihkan warn/info", 20f, top + 226f + off, 10f, kit.muted)
        bounds["devReset"] = RectF(resetRect)
    }
    val actTitleY = top + 252f
    kit.text(c, "TERBARU", 20f, actTitleY + off, 11f, kit.muted, true)
    if (devOrders.isEmpty()) {
        kit.centerText(c, "Belum ada aktivitas order.", RectF(24f, actTitleY + 12f + off, 366f, actTitleY + 62f + off), 12f, kit.muted)
    }
    devOrders.take(8).forEachIndexed { index, order ->
        val y = actTitleY + 12f + index * 46f + off
        if (y + 46f < 130f || y > h) return@forEachIndexed
        kit.text(c, "${order.buyerName.take(16)} · ${order.pickupCode}", 20f, y + 20f, 12f, kit.ink, true)
        kit.text(c, "${formatRupiah(order.totalIdr)} · ${statusLabelShort(order.status)}", 20f, y + 39f, 10f, kit.muted)
        kit.line(c, 20f, y + 46f, 370f, y + 46f)
    }
    contentHeight = actTitleY + 12f + devOrders.take(8).size * 46f + 40f
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
        // Panel rinci tanpa kartu: nama besar, meta, baris aksi teks.
        kit.text(c, selected.displayName.take(26), 20f, listTop + 28f + off, 17f, kit.ink, true)
        kit.text(c, selected.email.take(34), 20f, listTop + 50f + off, 11f, kit.muted)
        val meta = "${selected.role} · ${selected.accountStatus} · ${selected.orderCount} order · ${selected.warningCount} peringatan"
        kit.text(c, meta.take(52), 20f, listTop + 70f + off, 10f, kit.muted)
        if (selected.role == "seller") {
            val stall = "${selected.stallName ?: "Belum ada warung"}${selected.stallOpen?.let { if (it) " · buka" else " · tutup" } ?: ""}"
            kit.text(c, stall.take(46), 20f, listTop + 90f + off, 11f, kit.blue, true)
        }
        val ay = listTop + 100f + off
        kit.centerText(c, "Peringatkan", RectF(20f, ay, 120f, ay + 34f), 11f, kit.blue, true)
        bounds["devWarn:${selected.id}"] = RectF(20f, ay, 120f, ay + 34f)
        val suspended = selected.accountStatus == "suspended"
        kit.centerText(c, if (suspended) "Aktifkan" else "Tangguhkan", RectF(130f, ay, 230f, ay + 34f), 11f, if (suspended) kit.success else kit.danger, true)
        bounds[if (suspended) "devActive:${selected.id}" else "devSuspend:${selected.id}"] = RectF(130f, ay, 230f, ay + 34f)
        if (selected.stallId != null) {
            kit.centerText(c, if (selected.stallOpen == true) "Tutup warung" else "Buka warung", RectF(240f, ay, 360f, ay + 34f), 11f, kit.ink, true)
            bounds["devStall:${selected.stallId}:${!(selected.stallOpen == true)}"] = RectF(240f, ay, 360f, ay + 34f)
        }
        // Undang pembeli jadi penjual (hanya untuk role buyer).
        var inviteH = 0f
        if (selected.role == "buyer") {
            val pending = devInvites.firstOrNull { it.targetUserId == selected.id && it.status == "pending" }
            val iy = ay + 36f
            if (pending != null) {
                kit.text(c, "Undangan seller menunggu · Batalkan", 20f, iy + 22f, 11f, Color.rgb(183, 116, 0), true)
                bounds["devInviteCancel:${pending.id}"] = RectF(20f, iy, 370f, iy + 36f)
            } else {
                kit.text(c, "Undang jadi penjual  →", 20f, iy + 22f, 11f, kit.blue, true)
                bounds["devInvite:${selected.id}"] = RectF(20f, iy, 370f, iy + 36f)
            }
            inviteH = 36f
        }
        val close = RectF(330f, listTop + 8f + off, 366f, listTop + 36f + off)
        drawIcon(c, "close", close.centerX(), close.centerY(), 12f, kit.muted)
        bounds["devUserClose"] = RectF(close)
        kit.line(c, 20f, ay + 36f + inviteH + 8f, 370f, ay + 36f + inviteH + 8f)
        listTop += 152f + inviteH
    }
    rows.forEachIndexed { index, u ->
        val y = listTop + index * 58f + off
        if (y + 58f < 130f || y > h) return@forEachIndexed
        kit.text(c, u.displayName.take(22), 20f, y + 24f, 13f, kit.ink, true)
        kit.text(c, "${u.role} · ${u.accountStatus} · ${u.orderCount} order · ⚠${u.warningCount}", 20f, y + 45f, 10f, statusColor(u.accountStatus))
        kit.line(c, 20f, y + 58f, 370f, y + 58f)
        bounds["devUser:${u.id}"] = RectF(20f, y.coerceAtLeast(130f), 370f, y + 58f)
    }
    if (rows.isEmpty()) kit.centerText(c, "Tidak ada pengguna cocok.", RectF(24f, listTop + 20f + off, 366f, listTop + 70f + off), 12f, kit.muted)
    contentHeight = listTop + rows.size * 58f + 40f
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
        val cardH = if (expanded) 88f + order.items.size * 20f else 68f
        if (y + cardH + off > 130f && y + off < h) {
            kit.text(c, "#${order.id.take(8)} · ${order.buyerName.take(14)}", 20f, y + 24f + off, 12f, kit.ink, true)
            kit.text(c, statusLabelShort(order.status), 370f - kit.measure(statusLabelShort(order.status), 10f, true), y + 24f + off, 10f, kit.muted, true)
            kit.text(c, "${formatRupiah(order.totalIdr)} · ${order.paymentMethod ?: "-"} · ${order.paymentStatus}", 20f, y + 46f + off, 10f, kit.muted)
            if (expanded) {
                order.items.forEachIndexed { i, item ->
                    kit.text(c, "${item.quantity}× ${item.itemName.take(26)} · ${formatRupiah(item.unitPriceIdr * item.quantity)}", 20f, y + 68f + i * 20f + off, 10f, kit.ink)
                }
            } else {
                kit.text(c, order.timeLabel, 20f, y + 62f + off, 10f, kit.muted)
            }
            bounds["devOrder:${order.id}"] = RectF(20f, (y + off).coerceAtLeast(130f), 370f, y + cardH + off)
            kit.line(c, 20f, y + cardH + off, 370f, y + cardH + off)
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
        val cardH = 66f
        if (y + cardH + off > 130f && y + off < h - 80f) {
            val sevColor = when (warn.severity) {
                "critical" -> Color.rgb(206, 60, 60)
                "warning" -> Color.rgb(183, 116, 0)
                else -> Color.rgb(0, 99, 214)
            }
            kit.text(c, "●", 20f, y + 24f + off, 11f, sevColor, true)
            kit.text(c, warn.title.take(26), 36f, y + 24f + off, 12f, kit.ink, true)
            kit.text(c, if (warn.isRead) "dibaca" else "baru", 370f - kit.measure(if (warn.isRead) "dibaca" else "baru", 9f), y + 24f + off, 9f, kit.muted)
            kit.text(c, "${warn.message.take(48)} · ${warn.createdAt.take(16).replace('T', ' ')}", 36f, y + 46f + off, 10f, kit.muted)
            kit.line(c, 20f, y + cardH + off, 370f, y + cardH + off)
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
        val cardH = 66f
        if (y + cardH + off > 130f && y + off < h - 80f) {
            kit.text(c, ann.title.take(26), 20f, y + 24f + off, 12f, kit.ink, true)
            kit.text(c, ann.targetRole, 370f - kit.measure(ann.targetRole, 9f, true), y + 24f + off, 9f, kit.blue, true)
            kit.text(c, "${ann.message.take(48)} · ${ann.createdAt.take(16).replace('T', ' ')}", 20f, y + 46f + off, 10f, kit.muted)
            kit.line(c, 20f, y + cardH + off, 370f, y + cardH + off)
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
