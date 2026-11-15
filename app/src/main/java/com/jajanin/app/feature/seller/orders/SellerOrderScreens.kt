package com.jajanin.app.feature.seller.orders

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.ui.statusNextAction
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.sellerSurface

internal fun JajaninView.drawAdminDetail(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val order = adminDetailOrder

    when {
        order == null && adminDetailLoading -> {
            kit.centerText(c, "Memuat pesanan...", RectF(24f, 300f, 366f, 360f), 14f, kit.muted)
            contentHeight = 0f
        }
        order == null && adminDetailError != null -> {
            kit.centerText(c, adminDetailError ?: "Gagal memuat pesanan.", RectF(24f, 280f, 366f, 350f), 13f, kit.danger)
            val retry = RectF(120f, 372f, 270f, 418f)
            kit.centerText(c, "Muat ulang", retry, 12f, kit.blue, true)
            kit.line(c, 150f, 414f, 240f, 414f, kit.blue)
            bounds["adminRetry"] = RectF(retry)
            contentHeight = 0f
        }
        order == null -> {
            kit.centerText(c, "Pesanan tidak ditemukan.", RectF(24f, 300f, 366f, 360f), 14f, kit.muted)
            contentHeight = 0f
        }
        else -> {
            if (adminDetailError != null) {
                val errorCard = RectF(20f, 96f + off, 370f, 136f + off)
                kit.rounded(c, errorCard, kit.card, SellerTokens.RADIUS_CONTROL, kit.dangerBorder)
                kit.textEllipsized(
                    c, adminDetailError ?: "", 34f, 120f + off,
                    322f, 11f, kit.danger, true,
                )
            }
            val top = if (adminDetailError != null) 152f else 100f
            val card1 = RectF(SellerTokens.PAD, top + off, SellerTokens.RIGHT, top + 154f + off)
            sellerSurface(c, card1)
            kit.text(c, "KODE AMBIL", 34f, top + 26f + off, 9.5f, kit.muted, true)
            kit.text(c, order.pickupCode, 34f, top + 60f + off, 28f, kit.blue, true)

            val (label, _) = statusLabel(order.status)
            sellerPill(
                c, label, 356f, top + 42f + off,
                statusSoft(order.status), statusColor(order.status),
                height = 24f, size = 9.5f,
            )
            kit.textEllipsized(c, order.buyerName, 34f, top + 88f + off, 248f, 14f, kit.ink, true)
            val phone = order.buyerPhone.takeIf { it.isNotBlank() }
            if (phone != null) {
                kit.text(c, "HP", 34f, top + 108f + off, 9.5f, kit.muted, true)
                kit.textEllipsized(c, displayPhone(phone), 56f, top + 108f + off, 300f, 11.5f, kit.ink, true)
            } else {
                kit.textEllipsized(c, "Nomor HP pembeli belum diisi", 34f, top + 108f + off, 300f, 10.5f, kit.muted)
            }
            val payment = "Bayar via ${order.paymentMethod?.takeIf { it.isNotBlank() } ?: "QRIS"}"
            val paymentW = kit.measure(payment, 10.5f)
            kit.textEllipsized(
                c, "Masuk ${order.timeLabel} · ${order.itemCount} item",
                34f, top + 130f + off,
                SellerTokens.RIGHT - 34f - paymentW - 24f, 10.5f, kit.muted,
            )
            kit.textRight(c, payment, 356f, top + 130f + off, paymentW + 20f, 10.5f, kit.muted)

            // --- Alur status ---
            kit.text(c, "Alur status", 24f, top + 178f + off, 12f, kit.ink, true)
            val trackTop = top + 190f + off
            val stepLabels = listOf("Baru", "Konfirmasi", "Siapkan", "Siap", "Selesai")
            val stage = when (order.status.lowercase()) {
                STATUS_PENDING -> 0
                STATUS_CONFIRMED -> 1
                STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
                STATUS_READY -> 3
                STATUS_COMPLETED -> 4
                else -> -1
            }
            val segW = (356f - 34f) / stepLabels.size
            val trackY = trackTop + 26f
            kit.line(c, 34f, trackY, 356f, trackY, kit.divider)
            if (stage >= 0) {
                kit.rect(c, 34f, trackY - 1f, 34f + segW * (stage + 1), trackY + 1f, kit.blue)
            }
            stepLabels.forEachIndexed { index, stepLabel ->
                val cx = 34f + segW * index + segW / 2f
                val done = stage >= 0 && index <= stage
                if (done) {
                    kit.rounded(c, RectF(cx - 6f, trackY - 6f, cx + 6f, trackY + 6f), kit.blue, 6f)
                } else {
                    kit.rounded(c, RectF(cx - 6f, trackY - 6f, cx + 6f, trackY + 6f), kit.card, 6f, kit.border)
                }
                kit.centerText(
                    c, stepLabel,
                    RectF(cx - segW / 2f, trackTop, cx + segW / 2f, trackTop + 20f),
                    9f, if (done) kit.blue else kit.muted, done,
                )
            }

            // --- Item pesanan ---
            kit.text(c, "Item pesanan", 24f, top + 246f + off, 15f, kit.ink, true)
            val itemsTop = top + 260f
            val card2Bottom = itemsTop + order.items.size * 38f + 16f
            sellerSurface(c, RectF(SellerTokens.PAD, itemsTop + off, SellerTokens.RIGHT, card2Bottom + off))
            if (order.items.isEmpty()) {
                kit.centerText(
                    c, "Tidak ada rincian item.",
                    RectF(32f, itemsTop + off, 358f, card2Bottom + off), 12f, kit.muted,
                )
            }
            order.items.forEachIndexed { index, item ->
                val iy = itemsTop + 14f + index * 38f + off
                val subtotal = formatRupiah(item.unitPriceIdr * item.quantity)
                val subtotalW = kit.measure(subtotal, 12f, true)
                kit.textEllipsized(
                    c, "${item.quantity}× ${item.itemName}", 34f, iy + 22f,
                    164f, 12f, kit.ink, true,
                )
                kit.textEllipsized(
                    c, formatRupiah(item.unitPriceIdr), 206f, iy + 22f,
                    76f, 10f, kit.muted,
                )
                kit.textRight(c, subtotal, 356f, iy + 22f, subtotalW + 16f, 12f, kit.ink, true)
                if (index < order.items.size - 1) kit.line(c, 34f, iy + 34f, 356f, iy + 34f)
            }

            val totalTop = card2Bottom + 12f
            sellerSurface(c, RectF(SellerTokens.PAD, totalTop + off, SellerTokens.RIGHT, totalTop + 54f + off))
            kit.text(c, "Total", 34f, totalTop + 32f + off, 13f, kit.muted, true)
            val totalText = formatRupiah(order.totalIdr)
            kit.textRight(c, totalText, 356f, totalTop + 33f + off, 200f, 16f, kit.ink, true)

            // Kartu pembayaran: hanya penjual yang boleh menandai lunas / verifikasi / tolak.
            val payTop = totalTop + 66f + 12f
            val isCash = order.paymentMethod == "cash"
            val needsAction = (isCash && order.paymentStatus == "unpaid") ||
                (!isCash && order.paymentStatus == "waiting_verification")
            val payH = if (needsAction) 108f else 72f
            sellerSurface(c, RectF(SellerTokens.PAD, payTop + off, SellerTokens.RIGHT, payTop + payH + off))
            kit.textEllipsized(
                c, if (isCash) "Tunai · bayar saat ambil" else "QRIS",
                34f, payTop + 28f + off, 230f, 13f, kit.ink, true,
            )
            val payLabel = paymentLabelFor(order.paymentStatus)
            sellerPill(
                c, payLabel, 356f, payTop + 28f + off,
                paymentSoft(order.paymentStatus), paymentColor(order.paymentStatus),
                height = 24f, size = 9.5f,
            )
            if (isCash && order.paymentStatus == "unpaid") {
                kit.textEllipsized(
                    c, "Tandai lunas setelah pembeli membayar.",
                    34f, payTop + 50f + off, 300f, 10f, kit.muted,
                )
                val paid = RectF(34f, payTop + 60f + off, 220f, payTop + 96f + off)
                kit.centerText(c, "Tandai sudah dibayar", paid, 11f, kit.success, true)
                kit.line(c, 54f, payTop + 92f + off, 200f, payTop + 92f + off, kit.success)
                press(c, "payPaid:${order.id}", paid, 0f)
                bounds.putVisible("payPaid:${order.id}", paid)
            } else if (!isCash && order.paymentStatus == "waiting_verification") {
                kit.textEllipsized(
                    c, "Pembeli mengaku sudah membayar. Verifikasi dulu.",
                    34f, payTop + 50f + off, 300f, 10f, kit.muted,
                )
                val verify = RectF(34f, payTop + 60f + off, 196f, payTop + 96f + off)
                kit.centerText(c, "Verifikasi lunas", verify, 11f, kit.success, true)
                kit.line(c, 54f, payTop + 92f + off, 176f, payTop + 92f + off, kit.success)
                press(c, "payPaid:${order.id}", verify, 0f)
                bounds.putVisible("payPaid:${order.id}", verify)
                val reject = RectF(204f, payTop + 60f + off, 330f, payTop + 96f + off)
                kit.centerText(c, "Tolak bukti", reject, 11f, kit.danger, true)
                kit.line(c, 224f, payTop + 92f + off, 310f, payTop + 92f + off, kit.danger)
                press(c, "payReject:${order.id}", reject, 0f)
                bounds.putVisible("payReject:${order.id}", reject)
            } else {
                val hint = when (order.paymentStatus) {
                    "paid" -> "Sudah lunas, tercatat sistem."
                    "rejected" -> "Bukti ditolak — pembeli bisa kirim ulang."
                    else -> "Menunggu pembayaran pembeli."
                }
                kit.textEllipsized(c, hint, 34f, payTop + 50f + off, 300f, 10f, kit.muted)
            }
            contentHeight = payTop + payH + 12f
        }
    }

    // Aksi status tetap di bawah layar. Nav sengaja tidak digambar di layar
    // detail agar tombol utama tidak tertimpa dock.
    val next = statusNextAction(order?.status ?: "")
    if (next != null) {
        val cta = RectF(20f, h - 60f, w - 20f, h - 8f)
        kit.rounded(c, cta, kit.blue, 14f)
        kit.centerText(c, next.first, cta, 14f, Color.WHITE, true)
        press(c, "adminSet:${next.second}", cta, 0f)
        bounds["adminSet:${next.second}"] = RectF(cta)
    } else if (order != null) {
        val terminal = order.status.lowercase() == STATUS_CANCELLED
        val statusCard = RectF(20f, h - 60f, w - 20f, h - 8f)
        kit.rounded(c, statusCard, kit.card, 14f, if (terminal) kit.dangerBorder else kit.border)
        kit.centerText(
            c, if (terminal) "Pesanan dibatalkan" else "Pesanan selesai",
            statusCard, 13f, if (terminal) kit.danger else kit.success, true,
        )
    }

    sellerHeader(c, "Detail Pesanan")
}

private fun JajaninView.statusColor(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.star
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
    STATUS_READY, STATUS_COMPLETED -> kit.success
    STATUS_CANCELLED -> kit.danger
    else -> kit.muted
}

private fun JajaninView.statusSoft(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.warnBox
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.tint
    STATUS_READY, STATUS_COMPLETED -> kit.successSoft
    STATUS_CANCELLED -> kit.dangerBorder
    else -> kit.tint
}

private fun displayPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw

private fun paymentLabelFor(status: String): String = when (status.lowercase()) {
    "paid" -> "Lunas"
    "waiting_verification" -> "Verifikasi bukti"
    "rejected" -> "Bukti ditolak"
    else -> "Belum bayar"
}

private fun JajaninView.paymentColor(status: String): Int = when (status.lowercase()) {
    "paid" -> kit.success
    "waiting_verification" -> kit.star
    "rejected" -> kit.danger
    else -> kit.muted
}

private fun JajaninView.paymentSoft(status: String): Int = when (status.lowercase()) {
    "paid" -> kit.successSoft
    "waiting_verification" -> kit.warnBox
    "rejected" -> kit.dangerBorder
    else -> kit.bg
}
