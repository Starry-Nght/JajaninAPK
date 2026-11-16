package com.jajanin.app.feature.seller.notifications

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.dashboard.drawAdminNav
import com.jajanin.app.feature.seller.sellerDivider
import com.jajanin.app.feature.seller.sellerDot
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSurface

private const val HEADER_H = 60f
private const val NAV_CLEAR = GLASS_TOP_OFFSET + 8f

internal fun JajaninView.drawSellerNotif(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bottomLimit = h - NAV_CLEAR
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= bottomLimit

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = SellerTokens.CONTENT_TOP + off

    val actionable = adminOrders.filter { order ->
        val s = order.status.lowercase()
        s == STATUS_PENDING || s == STATUS_CONFIRMED ||
            (!isTerminalStatus(order.status) && order.paymentStatus == "waiting_verification")
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))

    if (visible(y, y + 20f)) {
        sellerSection(
            c, "Perlu Tindakan", y + 12f,
            trailing = if (actionable.isEmpty()) "Semua beres" else "${actionable.size} pesanan",
            trailingColor = if (actionable.isEmpty()) kit.success else kit.danger,
        )
    }
    y += 20f

    if (actionable.isEmpty()) {
        val panel = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
        if (visible(panel.top, panel.bottom)) {
            sellerSurface(c, panel)
            drawIcon(c, "checkCircle", 40f, panel.centerY(), 17f, kit.success)
            kit.text(c, "Tidak ada pesanan yang menunggu tindakan.", 62f, y + 32f, 12f, kit.muted)
        }
        y += 54f + 22f
    } else {
        val rowH = 60f
        val groupBottom = y + actionable.size * rowH
        if (visible(y, groupBottom)) {
            sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        }
        actionable.forEachIndexed { index, order ->
            val top = y + index * rowH
            if (visible(top, top + rowH)) {
                val (label, _) = statusLabel(order.status)
                sellerDot(c, 34f, top + 23f, statusColor(order.status), 3.5f)
                kit.textEllipsized(
                    c, "${order.buyerName} · ${order.timeLabel}",
                    48f, top + 24f, 250f, 12.5f, kit.ink, true,
                )
                kit.textRight(c, "#${order.pickupCode}", 356f, top + 24f, 64f, 11.5f, kit.blue, true)
                kit.textEllipsized(
                    c,
                    "${formatRupiah(order.totalIdr)}  ·  $label  ·  ${order.itemsSummary}",
                    48f, top + 43f, 306f, 10.5f, kit.muted,
                )
                if (index < actionable.size - 1) sellerDivider(c, top + rowH, 34f, 356f)
                press(c, "notifOrder:${order.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 8f)
                bounds.putVisible(
                    "notifOrder:${order.id}",
                    RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH),
                    topLimit = HEADER_H,
                    bottomLimit = bottomLimit
                )
            }
        }
        y = groupBottom + 22f
    }

    val invites = myInvites.filter { it.status == "pending" }
    if (invites.isNotEmpty()) {
        if (visible(y, y + 20f)) {
            sellerSection(c, "Undangan Toko", y + 12f)
        }
        y += 20f
        val rowH = 56f
        val groupBottom = y + invites.size * rowH
        if (visible(y, groupBottom)) {
            sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        }
        invites.forEachIndexed { index, invite ->
            val top = y + index * rowH
            if (visible(top, top + rowH)) {
                kit.textEllipsized(c, "Undangan menjadi penjual", 34f, top + 23f, 300f, 12.5f, kit.ink, true)
                kit.textEllipsized(c, "Ketuk untuk Terima atau Tolak", 34f, top + 41f, 300f, 10.5f, kit.blue, true)
                if (index < invites.size - 1) sellerDivider(c, top + rowH, 34f, 356f)
                press(c, "notifInvite:${invite.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 8f)
                bounds.putVisible(
                    "notifInvite:${invite.id}",
                    RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH),
                    topLimit = HEADER_H,
                    bottomLimit = bottomLimit
                )
            }
        }
        y = groupBottom + 22f
    }

    if (visible(y, y + 20f)) {
        sellerSection(c, "Peringatan & Pengumuman", y + 12f)
    }
    y += 20f

    val warnRows = myWarnings.sortedByDescending { it.createdAt }.take(20)
    val annRows = myAnnouncements.take(10)
    if (warnRows.isEmpty() && annRows.isEmpty()) {
        if (visible(y, y + 36f)) {
            kit.textEllipsized(c, "Belum ada pengumuman atau peringatan baru.", 20f, y + 20f, 340f, 12f, kit.muted)
        }
        y += 42f
    } else {
        val total = warnRows.size + annRows.size
        val rowH = 60f
        val groupBottom = y + total * rowH
        if (visible(y, groupBottom)) {
            sellerSurface(c, RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, groupBottom))
        }
        var index = 0
        warnRows.forEach { warn ->
            val top = y + index * rowH
            if (visible(top, top + rowH)) {
                sellerDot(c, 34f, top + 23f, if (warn.isRead) kit.border else kit.danger, 3.5f)
                kit.textEllipsized(c, warn.title, 48f, top + 23f, 304f, 12.5f, kit.ink, true)
                kit.textEllipsized(
                    c, "${warn.message} · ${warn.createdAt.take(10)}",
                    48f, top + 42f, 304f, 10.5f, kit.muted,
                )
                press(c, "notifWarn:${warn.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 8f)
                bounds.putVisible(
                    "notifWarn:${warn.id}",
                    RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH),
                    topLimit = HEADER_H,
                    bottomLimit = bottomLimit
                )
            }
            if (index < total - 1) sellerDivider(c, top + rowH, 34f, 356f)
            index++
        }
        annRows.forEach { ann ->
            val top = y + index * rowH
            if (visible(top, top + rowH)) {
                sellerDot(c, 34f, top + 23f, kit.blue, 3.5f)
                kit.textEllipsized(c, ann.title, 48f, top + 23f, 304f, 12.5f, kit.ink, true)
                kit.textEllipsized(
                    c, "${ann.message} · ${ann.createdAt.take(10)}",
                    48f, top + 42f, 304f, 10.5f, kit.muted,
                )
                press(c, "notifAnn:${ann.id}", RectF(22f, top + 2f, 368f, top + rowH - 2f), 8f)
                bounds.putVisible(
                    "notifAnn:${ann.id}",
                    RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + rowH),
                    topLimit = HEADER_H,
                    bottomLimit = bottomLimit
                )
            }
            if (index < total - 1) sellerDivider(c, top + rowH, 34f, 356f)
            index++
        }
        y = groupBottom + 24f
    }

    c.restoreToCount(bodySave)
    contentHeight = y - off + 32f
    sellerHeader(c, "Notifikasi")
    drawAdminNav(c, w, h, "Notifikasi")
}

private fun JajaninView.statusColor(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.star
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
    STATUS_READY -> kit.success
    else -> kit.muted
}
