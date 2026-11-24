package com.jajanin.app.feature.buyer.notifications

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.feature.seller.dashboard.drawAdminNav
import com.jajanin.app.feature.seller.notifications.drawSellerNotif

/**
 * Halaman Notifikasi: tujuan nyata lonceng (bukan popup). Isi tergantung role:
 * - Penjual: order yang perlu tindakan + undangan/info + peringatan.
 * - Pembeli: undangan jadi penjual + peringatan + pengumuman.
 * Baris belum dibaca disiram tint; ketuk untuk aksi/detail. Riwayat diambil
 * dari mekanisme backend yang sudah ada (tanpa notifikasi palsu).
 */
internal fun JajaninView.drawNotif(c: Canvas, w: Float, h: Float) {
    if (myRole == "seller") {
        drawSellerNotif(c, w, h)
        return
    }
    c.drawColor(kit.bg)
    header(c, "Notifikasi")
    val off = -scroll
    var y = 92f + off
    val seller = myRole == "seller"

    if (seller) {
        val actionable = adminOrders.filter { order ->
            val s = order.status.lowercase()
            s == STATUS_PENDING || s == STATUS_CONFIRMED ||
                (!isTerminalStatus(order.status) && order.paymentStatus == "waiting_verification")
        }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
        kit.text(c, "PERLU TINDAKAN · ${actionable.size}".uppercase(), 20f, y + 14f, 11f, if (actionable.isEmpty()) kit.muted else kit.danger, true)
        y += 28f
        if (actionable.isEmpty()) {
            kit.text(c, "Semua beres.", 20f, y + 22f, 13f, kit.muted)
            y += 44f
        }
        actionable.take(6).forEach { order ->
            val rowH = 62f
            if (y + rowH >= 78f && y <= h - 82f) {
                kit.card(c, RectF(20f, y, 370f, y + rowH), 12f)
                kit.text(c, "${order.buyerName.take(18)} · ${order.timeLabel}", 34f, y + 25f, 13f, kit.ink, true)
                kit.text(c, "${order.pickupCode} · ${formatRupiah(order.totalIdr)} · ${statusLabelShort(order.status)}", 34f, y + 47f, 11f, kit.blue, true)
                bounds["notifOrder:${order.id}"] = RectF(20f, y, 370f, y + rowH)
            }
            y += rowH + 10f
        }
    }

    val invites = myInvites.filter { it.status == "pending" }
    if (invites.isNotEmpty()) {
        kit.text(c, "UNDANGAN TOKO", 20f, y + 14f, 11f, kit.muted, true)
        y += 28f
        invites.forEach { inv ->
            val rowH = 62f
            if (y + rowH >= 78f && y <= h - 82f) {
                kit.rounded(c, RectF(20f, y, 370f, y + rowH), kit.tint, 12f)
                kit.text(c, "Undangan jadi penjual", 34f, y + 25f, 13f, kit.ink, true)
                kit.text(c, "Ketuk untuk Terima / Tolak", 34f, y + 47f, 11f, kit.blue, true)
                bounds["notifInvite:${inv.id}"] = RectF(20f, y, 370f, y + rowH)
            }
            y += rowH + 10f
        }
    }

    if (myRole != "developer" && !seller && myOrders.isNotEmpty()) {
        kit.text(c, "STATUS PESANAN (${myOrders.size})", 20f, y + 14f, 11f, kit.muted, true)
        y += 28f
        myOrders.take(10).forEach { order ->
            val rowH = 66f
            if (y + rowH >= 78f && y <= h - 82f) {
                val st = order.status.lowercase()
                val isCancelled = st == "cancelled"
                val isReady = st == "ready"
                val cardRect = RectF(20f, y, 370f, y + rowH)
                if (!isTerminalStatus(order.status)) {
                    kit.rounded(c, cardRect, kit.tint, 12f)
                } else {
                    kit.card(c, cardRect, 12f)
                }
                val title = when (st) {
                    STATUS_PENDING -> "Pesanan #${order.pickupCode} · Menunggu Konfirmasi"
                    STATUS_CONFIRMED -> "Pesanan #${order.pickupCode} · Dikonfirmasi Penjual"
                    "preparing", "processing" -> "Pesanan #${order.pickupCode} · Sedang Disiapkan"
                    "ready" -> "Pesanan #${order.pickupCode} · Siap Diambil!"
                    "completed" -> "Pesanan #${order.pickupCode} · Selesai"
                    "cancelled" -> "Pesanan #${order.pickupCode} · Dibatalkan"
                    else -> "Pesanan #${order.pickupCode} · ${statusLabelShort(order.status)}"
                }
                val titleColor = when {
                    isCancelled -> kit.danger
                    isReady -> kit.success
                    else -> kit.ink
                }
                kit.textEllipsized(c, title, 34f, y + 25f, 260f, 12.5f, titleColor, true)
                kit.textRight(c, order.timeLabel, 356f, y + 25f, 60f, 10.5f, kit.muted)
                val subtitle = when {
                    isCancelled && order.cancelReason.isNotBlank() ->
                        "Alasan: ${order.cancelReason} · Kuota dikembalikan"
                    isCancelled ->
                        "Pesanan dibatalkan · Kuota harian dikembalikan"
                    isReady ->
                        "${order.sellerStoreName.ifBlank { "Warung Jajanin" }} · Siap diambil · ${formatRupiah(order.totalIdr)}"
                    else ->
                        "${order.itemsSummary.ifBlank { "Pesanan Kantin" }} · ${formatRupiah(order.totalIdr)}"
                }
                kit.textEllipsized(
                    c,
                    subtitle,
                    34f,
                    y + 47f,
                    322f,
                    11f,
                    if (isCancelled) kit.danger else kit.blue,
                    isCancelled || isReady,
                )
                bounds["buyerOrder:${order.id}"] = cardRect
            }
            y += rowH + 10f
        }
    }

    if (myRole == "developer") {
        val sent = devInvites.filter { it.status == "pending" }
        kit.text(c, "UNDANGAN TERKIRIM · ${sent.size}".uppercase(), 20f, y + 14f, 11f, kit.muted, true)
        y += 28f
        if (sent.isEmpty()) {
            kit.text(c, "Tidak ada undangan menunggu.", 20f, y + 22f, 13f, kit.muted)
            y += 44f
        }
        sent.take(10).forEach { inv ->
            val rowH = 56f
            if (y + rowH >= 78f && y <= h - 82f) {
                kit.card(c, RectF(20f, y, 370f, y + rowH), 12f)
                kit.text(c, "Menunggu diverifikasi pembeli", 34f, y + 24f, 13f, kit.ink, true)
                kit.text(c, "Ketuk untuk batalkan", 34f, y + 44f, 11f, kit.danger, true)
                bounds["devNotifInviteCancel:${inv.id}"] = RectF(20f, y, 370f, y + rowH)
            }
            y += rowH + 10f
        }
    }

    kit.text(c, "LAINNYA", 20f, y + 14f, 11f, kit.muted, true)
    y += 28f
    val warnRows = myWarnings.sortedByDescending { it.createdAt }.take(20)
    if (warnRows.isEmpty() && myAnnouncements.isEmpty()) {
        kit.centerText(c, "Belum ada notifikasi.", RectF(20f, y, 370f, y + 60f), 13f, kit.muted)
        y += 60f
    }
    warnRows.forEach { warn ->
        val rowH = 62f
        if (y + rowH >= 78f && y <= h - 82f) {
            if (!warn.isRead) kit.rounded(c, RectF(20f, y, 370f, y + rowH), kit.tint, 12f)
            else kit.card(c, RectF(20f, y, 370f, y + rowH), 12f)
            val dot = if (warn.isRead) kit.muted else kit.danger
            kit.text(c, "●", 34f, y + 25f, 10f, dot, true)
            kit.text(c, warn.title.take(24), 50f, y + 25f, 13f, kit.ink, true)
            kit.text(c, "${warn.message.take(34)} · ${warn.createdAt.take(10)}", 50f, y + 47f, 11f, kit.muted)
            bounds["notifWarn:${warn.id}"] = RectF(20f, y, 370f, y + rowH)
        }
        y += rowH + 10f
    }
    myAnnouncements.take(10).forEach { ann ->
        val rowH = 62f
        if (y + rowH >= 78f && y <= h - 82f) {
            kit.card(c, RectF(20f, y, 370f, y + rowH), 12f)
            kit.text(c, ann.title.take(26), 34f, y + 25f, 13f, kit.ink, true)
            kit.text(c, "${ann.message.take(34)} · ${ann.createdAt.take(10)}", 34f, y + 47f, 11f, kit.muted)
            bounds["notifAnn:${ann.id}"] = RectF(20f, y, 370f, y + rowH)
        }
        y += rowH + 10f
    }

    contentHeight = y - off + 40f
    if (seller) drawAdminNav(c, w, h, "Notifikasi")
    else if (myRole != "developer") drawBottomNav(c, w, h, "")
    // Developer: tanpa nav bawah (navigasinya tab atas); kembali via toolbar.
}
