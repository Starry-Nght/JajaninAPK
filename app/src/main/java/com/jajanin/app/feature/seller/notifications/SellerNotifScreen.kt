package com.jajanin.app.feature.seller.notifications

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.sellerDivider
import com.jajanin.app.feature.seller.sellerDot
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSurface
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.dashboard.drawAdminNav

internal fun JajaninView.drawSellerNotif(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    var y = SellerTokens.CONTENT_TOP + off

    val actionable = adminOrders.filter { order ->
        val s = order.status.lowercase()
        s == STATUS_PENDING || s == STATUS_CONFIRMED ||
            (!isTerminalStatus(order.status) && order.paymentStatus == "waiting_verification")
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))

    sellerSection(
        c, "Perlu tindakan", y + 14f,
        trailing = if (actionable.isEmpty()) "Semua beres" else "${actionable.size} pesanan",
        trailingColor = if (actionable.isEmpty()) kit.success else kit.danger,
    )
    y += 28f
    if (actionable.isEmpty()) {
        val panel = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 60f)
        sellerSurface(c, panel)
        drawIcon(c, "checkCircle", 44f, panel.centerY(), 18f, kit.success)
        kit.text(c, "Tidak ada pesanan yang menunggu.", 66f, y + 35f, 12f, kit.muted)
        y += 60f + 24f
    } else {
        val rowH = 62f
        val groupBottom = y + actionable.size * rowH
        if (y <= h - 82f) sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        actionable.forEachIndexed { index, order ->
            val top = y + index * rowH
            if (top + rowH >= 86f && top <= h - 82f) {
                val (label, _) = statusLabel(order.status)
                sellerDot(c, 36f, top + 24f, statusColor(order.status), 3.5f)
                kit.textEllipsized(
                    c, "${order.buyerName} · ${order.timeLabel}",
                    52f, top + 24f, 288f, 12f, kit.ink, true,
                )
                kit.textEllipsized(
                    c,
                    "Kode ${order.pickupCode} · ${formatRupiah(order.totalIdr)} · $label",
                    52f, top + 44f, 300f, 10f, kit.muted,
                )
                if (index < actionable.size - 1) sellerDivider(c, top + rowH)
                press(c, "notifOrder:${order.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 10f)
                bounds.putVisible("notifOrder:${order.id}", RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH), bottomLimit = h - 82f)
            }
        }
        y = groupBottom + 24f
    }

    val invites = myInvites.filter { it.status == "pending" }
    if (invites.isNotEmpty()) {
        sellerSection(c, "Undangan toko", y + 14f)
        y += 28f
        val rowH = 58f
        val groupBottom = y + invites.size * rowH
        if (y <= h - 82f) sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        invites.forEachIndexed { index, invite ->
            val top = y + index * rowH
            if (top + rowH >= 86f && top <= h - 82f) {
                kit.textEllipsized(c, "Undangan jadi penjual", 34f, top + 24f, 300f, 12f, kit.ink, true)
                kit.textEllipsized(c, "Ketuk untuk Terima / Tolak", 34f, top + 43f, 300f, 10.5f, kit.blue, true)
                if (index < invites.size - 1) sellerDivider(c, top + rowH)
                press(c, "notifInvite:${invite.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 10f)
                bounds.putVisible("notifInvite:${invite.id}", RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH), bottomLimit = h - 82f)
            }
        }
        y = groupBottom + 24f
    }

    sellerSection(c, "Lainnya", y + 14f)
    y += 28f
    val warnRows = myWarnings.sortedByDescending { it.createdAt }.take(20)
    val annRows = myAnnouncements.take(10)
    if (warnRows.isEmpty() && annRows.isEmpty()) {
        kit.textEllipsized(
            c, "Belum ada notifikasi lain.",
            22f, y + 22f, 330f, 12f, kit.muted,
        )
        y += 44f
    } else {
        val total = warnRows.size + annRows.size
        val rowH = 62f
        val groupBottom = y + total * rowH
        if (y <= h - 82f) sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        var index = 0
        warnRows.forEach { warn ->
            val top = y + index * rowH
            if (top + rowH >= 86f && top <= h - 82f) {
                sellerDot(c, 36f, top + 24f, if (warn.isRead) kit.border else kit.danger, 3.5f)
                kit.textEllipsized(
                    c, warn.title, 52f, top + 24f, 300f, 12f,
                    kit.ink, true,
                )
                kit.textEllipsized(
                    c, "${warn.message} · ${warn.createdAt.take(10)}",
                    52f, top + 44f, 300f, 10f, kit.muted,
                )
                press(c, "notifWarn:${warn.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 10f)
                bounds.putVisible("notifWarn:${warn.id}", RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH), bottomLimit = h - 82f)
            }
            if (index < total - 1) sellerDivider(c, top + rowH)
            index++
        }
        annRows.forEach { ann ->
            val top = y + index * rowH
            if (top + rowH >= 86f && top <= h - 82f) {
                sellerDot(c, 36f, top + 24f, kit.blue, 3.5f)
                kit.textEllipsized(c, ann.title, 52f, top + 24f, 300f, 12f, kit.ink, true)
                kit.textEllipsized(
                    c, "${ann.message} · ${ann.createdAt.take(10)}",
                    52f, top + 44f, 300f, 10f, kit.muted,
                )
                press(c, "notifAnn:${ann.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 10f)
                bounds.putVisible("notifAnn:${ann.id}", RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH), bottomLimit = h - 82f)
            }
            if (index < total - 1) sellerDivider(c, top + rowH)
            index++
        }
        y = groupBottom + 24f
    }

    contentHeight = y - off + 40f
    sellerHeader(c, "Notifikasi")
    drawAdminNav(c, w, h, "Notifikasi")
}

private fun JajaninView.statusColor(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.star
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
    STATUS_READY -> kit.success
    else -> kit.muted
}
