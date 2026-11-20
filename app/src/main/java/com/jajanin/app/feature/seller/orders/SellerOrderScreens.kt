package com.jajanin.app.feature.seller.orders

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerSurface

private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.2f
    pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
}

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
            val retry = RectF(125f, 368f, 265f, 410f)
            kit.rounded(c, retry, kit.blue, 8f)
            kit.centerText(c, "Muat ulang", retry, 12.5f, Color.WHITE, true)
            bounds["adminRetry"] = RectF(retry)
            contentHeight = 0f
        }
        order == null -> {
            kit.centerText(c, "Pesanan tidak ditemukan.", RectF(24f, 300f, 366f, 360f), 14f, kit.muted)
            contentHeight = 0f
        }
        else -> {
            val bodySave = c.save()
            c.clipRect(0f, 60f, w, h - 68f)

            var y = 76f + off

            if (adminDetailError != null) {
                val errorCard = RectF(20f, y, 370f, y + 38f)
                kit.rounded(c, errorCard, kit.card, 7f, kit.dangerBorder)
                kit.textEllipsized(
                    c, adminDetailError ?: "", 32f, y + 23f,
                    326f, 11f, kit.danger, true,
                )
                y += 48f
            }

            // 1. Open Editorial Buyer & Pickup Code Header (No heavy box)
            kit.text(c, "KODE AMBIL", 20f, y + 12f, 10f, kit.muted, true)
            kit.text(c, "#${order.pickupCode}", 20f, y + 46f, 32f, kit.blue, true)

            val (label, _) = statusLabel(order.status)
            sellerPill(
                c, label, 370f, y + 28f,
                statusSoft(order.status), statusColor(order.status),
                height = 22f, size = 9.5f,
            )

            kit.textEllipsized(c, order.buyerName, 20f, y + 70f, 250f, 15f, kit.ink, true)
            val phone = order.buyerPhone.takeIf { it.isNotBlank() }
            val phoneLine = if (phone != null) "HP: ${displayPhone(phone)}" else "Nomor HP belum diisi"
            kit.textEllipsized(
                c,
                "$phoneLine  ·  Masuk ${order.timeLabel}",
                20f,
                y + 89f,
                340f,
                11.5f,
                kit.muted
            )
            y += 104f

            // 2. Horizontal 5-Step Status Track
            val stepLabels = listOf("Baru", "Diterima", "Siapkan", "Siap", "Selesai")
            val stage = when (order.status.lowercase()) {
                STATUS_PENDING -> 0
                STATUS_CONFIRMED -> 1
                STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
                STATUS_READY -> 3
                STATUS_COMPLETED -> 4
                else -> -1
            }
            val segW = (350f) / stepLabels.size
            val trackY = y + 12f
            val firstCx = 20f + segW / 2f
            val lastCx = 370f - segW / 2f
            kit.line(c, firstCx, trackY, lastCx, trackY, kit.divider)
            if (stage > 0) {
                kit.rect(c, firstCx, trackY - 1f, 20f + segW * stage + segW / 2f, trackY + 1f, kit.blue)
            }
            stepLabels.forEachIndexed { index, stepLabel ->
                val cx = 20f + segW * index + segW / 2f
                val done = stage >= 0 && index <= stage
                val current = index == stage
                val r = if (current) 5.5f else 4f
                if (done) {
                    kit.rounded(c, RectF(cx - r, trackY - r, cx + r, trackY + r), kit.blue, r)
                } else {
                    kit.rounded(c, RectF(cx - r, trackY - r, cx + r, trackY + r), kit.card, r, kit.border)
                }
                kit.centerText(
                    c, stepLabel,
                    RectF(cx - segW / 2f, y + 22f, cx + segW / 2f, y + 38f),
                    9.5f, if (current) kit.blue else if (done) kit.ink else kit.muted, current || done,
                )
            }
            y += 52f

            // 3. Unified Receipt Surface (Items + Dashed Line + Total in one clean 8f surface)
            val itemsH = if (order.items.isEmpty()) 36f else order.items.size * 34f
            val receiptTop = y
            val receiptBottom = receiptTop + 34f + itemsH + 52f
            sellerSurface(c, RectF(SellerTokens.PAD, receiptTop, SellerTokens.RIGHT, receiptBottom))

            kit.text(c, "RINCIAN PESANAN (${order.itemCount} ITEM)", 34f, receiptTop + 23f, 10f, kit.muted, true)

            var iy = receiptTop + 34f
            if (order.items.isEmpty()) {
                kit.text(c, "Tidak ada rincian item.", 34f, iy + 20f, 12f, kit.muted)
            } else {
                order.items.forEachIndexed { index, item ->
                    val subtotal = formatRupiah(item.unitPriceIdr * item.quantity)
                    val subtotalW = kit.measure(subtotal, 12.5f, true)
                    kit.textEllipsized(
                        c, "${item.quantity}×  ${item.itemName}", 34f, iy + 21f,
                        180f, 12.5f, kit.ink, true,
                    )
                    kit.textEllipsized(
                        c, "@${formatRupiah(item.unitPriceIdr)}", 216f, iy + 21f,
                        66f, 10.5f, kit.muted,
                    )
                    kit.textRight(c, subtotal, 356f, iy + 21f, subtotalW + 16f, 12.5f, kit.ink, true)
                    if (index < order.items.size - 1) {
                        kit.line(c, 34f, iy + 32f, 356f, iy + 32f, kit.divider)
                    }
                    iy += 34f
                }
            }

            val dashY = receiptBottom - 46f
            dashedPaint.color = kit.divider
            c.drawLine(28f, dashY, 362f, dashY, dashedPaint)

            kit.text(c, "Total Pembayaran", 34f, receiptBottom - 18f, 13f, kit.ink, true)
            kit.textRight(c, formatRupiah(order.totalIdr), 356f, receiptBottom - 17f, 180f, 16f, kit.blue, true)

            y = receiptBottom + 14f

            // 4. Payment Verification Strip (Radius 8f)
            val isCash = order.paymentMethod == "cash"
            val needsAction = (isCash && order.paymentStatus == "unpaid") ||
                (!isCash && order.paymentStatus == "waiting_verification")
            val payH = if (needsAction) 98f else 62f
            val payCard = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + payH)
            sellerSurface(c, payCard)

            kit.textEllipsized(
                c, if (isCash) "Pembayaran Tunai" else "Pembayaran QRIS",
                34f, y + 25f, 220f, 13f, kit.ink, true,
            )
            val payLabel = paymentLabelFor(order.paymentStatus)
            sellerPill(
                c, payLabel, 356f, y + 21f,
                paymentSoft(order.paymentStatus), paymentColor(order.paymentStatus),
                height = 20f, size = 9.5f,
            )

            if (isCash && order.paymentStatus == "unpaid") {
                kit.textEllipsized(
                    c, "Tandai lunas setelah pembeli menyerahkan uang tunai.",
                    34f, y + 44f, 310f, 10.5f, kit.muted,
                )
                val paidBtn = RectF(34f, y + 54f, 356f, y + 86f)
                kit.rounded(c, paidBtn, kit.successSoft, 6f, kit.success)
                kit.centerText(c, "Tandai Sudah Dibayar Lunas", paidBtn, 11.5f, kit.success, true)
                press(c, "payPaid:${order.id}", paidBtn, 6f)
                bounds.putVisible("payPaid:${order.id}", paidBtn, topLimit = 60f, bottomLimit = h - 68f)
            } else if (!isCash && order.paymentStatus == "waiting_verification") {
                kit.textEllipsized(
                    c, "Pembeli konfirmasi sudah bayar QRIS. Cek mutasi lalu verifikasi.",
                    34f, y + 44f, 316f, 10.5f, kit.muted,
                )
                val verifyBtn = RectF(34f, y + 54f, 226f, y + 86f)
                kit.rounded(c, verifyBtn, kit.success, 6f)
                kit.centerText(c, "Verifikasi Lunas", verifyBtn, 11.5f, Color.WHITE, true)
                press(c, "payPaid:${order.id}", verifyBtn, 6f)
                bounds.putVisible("payPaid:${order.id}", verifyBtn, topLimit = 60f, bottomLimit = h - 68f)

                val rejectBtn = RectF(234f, y + 54f, 356f, y + 86f)
                kit.rounded(c, rejectBtn, kit.card, 6f, kit.dangerBorder)
                kit.centerText(c, "Tolak Bukti", rejectBtn, 11.5f, kit.danger, true)
                press(c, "payReject:${order.id}", rejectBtn, 6f)
                bounds.putVisible("payReject:${order.id}", rejectBtn, topLimit = 60f, bottomLimit = h - 68f)
            } else {
                val hint = when (order.paymentStatus) {
                    "paid" -> "Pembayaran telah diverifikasi lunas."
                    "rejected" -> "Bukti ditolak — menunggu pembeli konfirmasi ulang."
                    else -> "Menunggu konfirmasi pembayaran dari pembeli."
                }
                kit.textEllipsized(c, hint, 34f, y + 46f, 310f, 10.5f, kit.muted)
            }

            c.restoreToCount(bodySave)
            contentHeight = (y - off) + payH + 24f
        }
    }

    // Sticky Bottom Order Status Action (Radius 8f)
    val next = statusNextAction(order?.status ?: "")
    if (next != null && order != null) {
        val cancelBtn = RectF(20f, h - 58f, 132f, h - 10f)
        kit.rounded(c, cancelBtn, kit.card, 8f, kit.dangerBorder)
        kit.centerText(c, "Batalkan", cancelBtn, 13f, kit.danger, true)
        press(c, "adminCancel:${order.id}", cancelBtn, 8f)
        bounds["adminCancel:${order.id}"] = RectF(cancelBtn)

        val cta = RectF(142f, h - 58f, w - 20f, h - 10f)
        kit.rounded(c, cta, kit.blue, 8f)
        kit.centerText(c, "${next.first} →", cta, 14f, Color.WHITE, true)
        press(c, "adminSet:${next.second}", cta, 8f)
        bounds["adminSet:${next.second}"] = RectF(cta)
    } else if (order != null) {
        val terminal = order.status.lowercase() == STATUS_CANCELLED
        val statusCard = RectF(20f, h - 58f, w - 20f, h - 10f)
        kit.rounded(c, statusCard, kit.card, 8f, if (terminal) kit.dangerBorder else kit.border)
        kit.centerText(
            c, if (terminal) "Pesanan Dibatalkan" else "Pesanan Selesai",
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
