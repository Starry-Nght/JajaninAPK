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
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.components.drawCenteredButtonWithArrow
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.ui.statusNextAction
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.feature.seller.loadProductImage
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

            val (cleanBuyerName, buyerNis) = com.jajanin.app.data.repository.splitBuyerNameAndNis(order.buyerName)
            val buyerNameLines = kit.wrap(cleanBuyerName, 232f, 14.5f, maxLines = 2, bold = true)
            val nameStep = 18f
            buyerNameLines.forEachIndexed { i, line ->
                kit.text(c, line, 20f, y + 70f + i * nameStep, 14.5f, kit.ink, true)
            }
            val phone = order.buyerPhone.takeIf { it.isNotBlank() }
            val phoneLine = if (phone != null) "HP: ${displayPhone(phone)}" else "Nomor HP belum diisi"
            val buyerMetaText = buildList {
                if (!buyerNis.isNullOrBlank()) add("NIS $buyerNis")
                add(phoneLine)
                add("Masuk ${order.timeLabel}")
            }.joinToString(" · ")
            val buyerMetaLines = kit.wrap(buyerMetaText, 234f, 11f, maxLines = 2)
            val metaStartY = y + 70f + (buyerNameLines.size - 1).coerceAtLeast(0) * nameStep + 18f
            val metaStep = 14.5f
            buyerMetaLines.forEachIndexed { i, line ->
                kit.text(c, line, 20f, metaStartY + i * metaStep, 11f, kit.muted)
            }
            val metaBottomY = metaStartY + (buyerMetaLines.size - 1).coerceAtLeast(0) * metaStep + 6f

            val unreadForOrder = unreadChatByOrder[order.id] ?: 0
            val chatBtn = RectF(258f, y + 58f, 370f, y + 90f)
            kit.rounded(
                c,
                chatBtn,
                if (unreadForOrder > 0) kit.blue else kit.tint,
                6f,
                if (unreadForOrder > 0) null else kit.blue,
            )
            val chatLabel = if (unreadForOrder > 0) "Chat ($unreadForOrder)" else "Chat Pembeli"
            kit.centerText(c, chatLabel, chatBtn, 11f, if (unreadForOrder > 0) Color.WHITE else kit.blue, true)
            press(c, "chatOrder:${order.id}", chatBtn, 6f)
            bounds.putVisible("chatOrder:${order.id}", chatBtn, topLimit = 60f, bottomLimit = h - 68f)
            y = maxOf(y + 102f, metaBottomY + 10f)

            // 1b. Jadwal & Lokasi Pengambilan Banner
            val roomNote = order.pickupRoom.takeIf { it.isNotBlank() }
            val schedH = if (roomNote != null) 54f else 36f
            val schedRect = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + schedH)
            val isPo = order.isPreOrder
            kit.rounded(
                c,
                schedRect,
                if (isPo) kit.warnBox else kit.tint,
                7f,
                if (isPo) Color.rgb(183, 116, 0) else kit.border,
            )
            kit.text(c, "JADWAL AMBIL", 32f, y + 22f, 9.5f, kit.muted, true)
            kit.textRight(
                c,
                order.pickupScheduleLabel,
                358f,
                y + 22.5f,
                220f,
                11.5f,
                if (isPo) Color.rgb(183, 116, 0) else kit.blue,
                true,
            )
            if (roomNote != null) {
                kit.text(c, "KELAS / RUANGAN", 32f, y + 42f, 9.5f, kit.muted, true)
                kit.textRight(c, roomNote, 358f, y + 42.5f, 200f, 11.5f, kit.ink, true)
            }
            y += schedH + 10f

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

            // 2b. Highlighted Product Banner (Memudahkan seller melihat produk yang dibeli & variannya secara lengkap)
            val primaryItem = order.items.firstOrNull()
            if (primaryItem != null) {
                val matchedProd = resolveOrderItemProduct(primaryItem.menuItemId, primaryItem.displayBaseName)
                val isConfirmedHighlight = order.status.equals(STATUS_CONFIRMED, ignoreCase = true) ||
                    order.status.equals(STATUS_PREPARING, ignoreCase = true) ||
                    order.status.equals(STATUS_PROCESSING_LEGACY, ignoreCase = true)

                val extraItems = (order.items.size - 1).coerceAtLeast(0)
                val mainHeadline = if (extraItems > 0) {
                    "${primaryItem.quantity}× ${primaryItem.displayBaseName} (+$extraItems menu lain)"
                } else {
                    "${primaryItem.quantity}× ${primaryItem.displayBaseName}"
                }
                val headlineLines = kit.wrap(mainHeadline, 262f, 13f, maxLines = 2, bold = true)
                val primaryVariant = primaryItem.resolvedVariantLabel
                val variantLines = if (primaryVariant.isNotBlank()) {
                    kit.wrap(primaryVariant, 262f, 11f, maxLines = 3, bold = true)
                } else {
                    emptyList()
                }
                val headlineStartRelY = 40f
                val headlineStep = 16.5f
                val variantStep = 14.5f
                val afterHeadlineRelY = headlineStartRelY + (headlineLines.size - 1).coerceAtLeast(0) * headlineStep
                val variantStartRelY = afterHeadlineRelY + 15.5f
                val metaRelY = if (variantLines.isNotEmpty()) {
                    variantStartRelY + (variantLines.size - 1) * variantStep + 16f
                } else {
                    afterHeadlineRelY + 17f
                }
                val prodBannerH = maxOf(74f, metaRelY + 14f)
                val prodBanner = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + prodBannerH)
                kit.rounded(
                    c,
                    prodBanner,
                    if (isConfirmedHighlight) kit.tint else kit.card,
                    8f,
                    if (isConfirmedHighlight) kit.blue else kit.border,
                )

                val pThumbTop = y + ((prodBannerH - 52f) / 2f).coerceAtLeast(11f)
                val pThumb = RectF(32f, pThumbTop, 84f, pThumbTop + 52f)
                val pUrl = matchedProd?.imageUrl?.takeIf { it.startsWith("https://") }
                val pBmp = pUrl?.let { ProductImageCache.images[it] }
                if (pBmp != null) {
                    drawBitmapCenterCrop(c, pBmp, pThumb, 7f)
                } else {
                    kit.rounded(c, pThumb, kit.card, 7f, kit.border)
                    val initChar = primaryItem.displayBaseName.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
                    kit.centerText(c, initChar, pThumb, 17f, kit.blue, true)
                    if (pUrl != null) activity()?.loadProductImage(pUrl)
                }

                val bannerTag = if (order.status.equals(STATUS_CONFIRMED, ignoreCase = true)) {
                    "PRODUK PESANAN · CONFIRMED"
                } else {
                    "PRODUK YANG DIBELI"
                }
                kit.textEllipsized(c, bannerTag, 96f, y + 22f, 260f, 9.5f, kit.blue, true)

                headlineLines.forEachIndexed { idx, line ->
                    kit.text(c, line, 96f, y + headlineStartRelY + idx * headlineStep, 13f, kit.ink, true)
                }
                variantLines.forEachIndexed { idx, line ->
                    kit.text(c, line, 96f, y + variantStartRelY + idx * variantStep, 11f, kit.blue, true)
                }

                val stockMeta = matchedProd?.stock?.let { "Sisa stok etalase: $it porsi" }
                    ?: "Subtotal: ${formatRupiah(primaryItem.unitPriceIdr * primaryItem.quantity)}"
                kit.textEllipsized(c, stockMeta, 96f, y + metaRelY, 262f, 10.5f, kit.muted)

                y += prodBannerH + 12f
            }

            // 3. Unified Receipt Surface (Items with Product Thumbnails + Wrapped Variants + Dashed Line + Total)
            data class ReceiptItemLayout(
                val item: com.jajanin.app.data.repository.OrderItemRow,
                val subtotal: String,
                val subtotalW: Float,
                val titleLines: List<String>,
                val variantLines: List<String>,
                val variantStartRelY: Float,
                val priceRelY: Float,
                val rowH: Float,
            )
            val titleStep = 16f
            val varStep = 14.5f
            val receiptLayouts = order.items.map { item ->
                val subtotal = formatRupiah(item.unitPriceIdr * item.quantity)
                val subtotalW = kit.measure(subtotal, 12.5f, true)
                val textMaxW = (356f - subtotalW - 12f - 76f).coerceAtLeast(150f)
                val titleLines = kit.wrap("${item.quantity}× ${item.displayBaseName}", textMaxW, 12.5f, maxLines = 3, bold = true)
                    .ifEmpty { listOf("${item.quantity}× ${item.displayBaseName}") }
                val variant = item.resolvedVariantLabel
                val variantLines = if (variant.isNotBlank()) {
                    kit.wrap(variant, textMaxW, 11f, maxLines = 5, bold = true)
                } else {
                    emptyList()
                }
                val titleEndRelY = 18f + (titleLines.size - 1) * titleStep
                val variantStartRelY = titleEndRelY + 15f
                val priceRelY = if (variantLines.isNotEmpty()) {
                    variantStartRelY + (variantLines.size - 1) * varStep + 14.5f
                } else {
                    titleEndRelY + 15f
                }
                val rowH = maxOf(46f, priceRelY + 11f)
                ReceiptItemLayout(item, subtotal, subtotalW, titleLines, variantLines, variantStartRelY, priceRelY, rowH)
            }
            val itemsH = if (receiptLayouts.isEmpty()) 36f else receiptLayouts.sumOf { it.rowH.toDouble() }.toFloat()
            val receiptTop = y
            val receiptBottom = receiptTop + 34f + itemsH + 52f
            sellerSurface(c, RectF(SellerTokens.PAD, receiptTop, SellerTokens.RIGHT, receiptBottom))

            kit.text(c, "RINCIAN PESANAN (${order.itemCount} ITEM)", 34f, receiptTop + 23f, 10f, kit.muted, true)

            var iy = receiptTop + 34f
            if (receiptLayouts.isEmpty()) {
                kit.text(c, "Tidak ada rincian item.", 34f, iy + 20f, 12f, kit.muted)
            } else {
                receiptLayouts.forEachIndexed { index, row ->
                    val item = row.item
                    val itemProd = resolveOrderItemProduct(item.menuItemId, item.displayBaseName)
                    val rowThumb = RectF(34f, iy + 6f, 66f, iy + 38f)
                    val rowUrl = itemProd?.imageUrl?.takeIf { it.startsWith("https://") }
                    val rowBmp = rowUrl?.let { ProductImageCache.images[it] }
                    if (rowBmp != null) {
                        drawBitmapCenterCrop(c, rowBmp, rowThumb, 5f)
                    } else {
                        kit.rounded(c, rowThumb, kit.tint, 5f)
                        val ch = item.displayBaseName.firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                        kit.centerText(c, ch, rowThumb, 12f, kit.blue, true)
                        if (rowUrl != null) activity()?.loadProductImage(rowUrl)
                    }

                    row.titleLines.forEachIndexed { lIdx, line ->
                        kit.text(c, line, 76f, iy + 18f + lIdx * titleStep, 12.5f, kit.ink, true)
                    }
                    row.variantLines.forEachIndexed { lIdx, line ->
                        kit.text(c, line, 76f, iy + row.variantStartRelY + lIdx * varStep, 11f, kit.blue, true)
                    }
                    kit.text(
                        c, "@${formatRupiah(item.unitPriceIdr)}", 76f, iy + row.priceRelY,
                        10f, kit.muted,
                    )
                    kit.textRight(c, row.subtotal, 356f, iy + 20f, row.subtotalW + 16f, 12.5f, kit.ink, true)
                    if (index < receiptLayouts.lastIndex) {
                        kit.line(c, 76f, iy + row.rowH - 1f, 356f, iy + row.rowH - 1f, kit.divider)
                    }
                    iy += row.rowH
                }
            }

            val dashY = receiptBottom - 46f
            dashedPaint.color = kit.divider
            c.drawLine(28f, dashY, 362f, dashY, dashedPaint)

            kit.text(c, "Total Pembayaran", 34f, receiptBottom - 18f, 13f, kit.ink, true)
            kit.textRight(c, formatRupiah(order.totalIdr), 356f, receiptBottom - 17f, 180f, 16f, kit.blue, true)

            y = receiptBottom + 14f

            // 4. Payment Status & Midtrans Settlement Strip (Radius 8f)
            val isCash = order.paymentMethod == "cash"
            val isCancelled = order.status.equals(STATUS_CANCELLED, ignoreCase = true)
            val proofUrl = order.paymentProofUrl.takeIf { it.isNotBlank() }
            val hasProof = !isCash && proofUrl != null
            val needsAction = !isCancelled && isCash && order.paymentStatus == "unpaid"
            val qrisRemSec = if (!isCash && !isCancelled && (order.paymentStatus == "unpaid" || order.paymentStatus == "rejected" || order.paymentStatus == "waiting_verification")) {
                com.jajanin.app.data.repository.remainingPaymentSeconds(order.paymentExpiresAt)
            } else {
                null
            }
            if (qrisRemSec != null && qrisRemSec > 0L && currentScreen == com.jajanin.app.core.navigation.Screen.ADMIN_DETAIL) {
                postInvalidateDelayed(1000)
            }
            val hint = when {
                isCancelled -> "Pesanan dibatalkan."
                order.paymentStatus == "paid" -> if (isCash) "Pembayaran tunai telah ditandai lunas." else "Lunas otomatis via Midtrans Sandbox · Masuk ke saldo warung."
                qrisRemSec != null && qrisRemSec <= 0L -> "Batas waktu bayar Midtrans habis · dibatalkan otomatis."
                qrisRemSec != null -> "Menunggu pembayaran Midtrans (${com.jajanin.app.data.repository.formatCountdownMmSs(qrisRemSec)}) · Stok dipotong saat lunas."
                else -> "Menunggu pembayaran otomatis via Midtrans · Stok dipotong saat lunas."
            }
            val hintLines = if (needsAction) emptyList() else kit.wrap(hint, 322f, 10.5f, maxLines = 3)
            val proofStripH = if (hasProof) 62f else 0f
            val basePayH = if (needsAction) 98f else maxOf(66f, 44f + hintLines.size * 14f + 10f)
            val payH = basePayH + proofStripH
            val payCard = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + payH)
            sellerSurface(c, payCard)

            kit.textEllipsized(
                c, if (isCash) "Pembayaran Tunai" else "Pembayaran Midtrans",
                34f, y + 25f, 220f, 13f, kit.ink, true,
            )
            val payLabel = paymentLabelFor(order.paymentStatus)
            sellerPill(
                c, payLabel, 356f, y + 21f,
                paymentSoft(order.paymentStatus), paymentColor(order.paymentStatus),
                height = 20f, size = 9.5f,
            )

            var innerY = y + 44f
            if (hasProof && proofUrl != null) {
                val strip = RectF(34f, y + 38f, 356f, y + 92f)
                kit.rounded(c, strip, kit.bg, 7f, kit.blue)
                val thumb = RectF(40f, y + 44f, 82f, y + 86f)
                kit.rounded(c, thumb, kit.card, 5f, kit.border)
                val bmp = com.jajanin.app.core.ui.ProductImageCache.images[proofUrl]
                if (bmp != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val path = android.graphics.Path().apply { addRoundRect(thumb, 5f, 5f, android.graphics.Path.Direction.CW) }
                    val save = c.save()
                    c.clipPath(path)
                    c.drawBitmap(bmp, null, thumb, paint)
                    c.restoreToCount(save)
                } else {
                    kit.centerText(c, "Foto", thumb, 9.5f, kit.muted)
                    activity()?.loadProductImage(proofUrl)
                }
                kit.textEllipsized(c, "Foto Bukti Lama", 92f, y + 61f, 185f, 12f, kit.ink, true)
                kit.textEllipsized(c, "Ketuk untuk melihat lampiran lama", 92f, y + 78f, 185f, 10f, kit.muted)
                drawTrailingActionWithArrow(c, "Lihat", 346f, y + 69f, 11.5f, kit.blue)
                press(c, "viewProof:${order.id}", strip, 7f)
                bounds.putVisible("viewProof:${order.id}", strip, topLimit = 60f, bottomLimit = h - 68f)
                innerY = y + 108f
            }

            if (isCash && !isCancelled && order.paymentStatus == "unpaid") {
                kit.textEllipsized(
                    c, "Tandai lunas setelah pembeli menyerahkan uang tunai.",
                    34f, innerY, 310f, 10.5f, kit.muted,
                )
                val paidBtn = RectF(34f, innerY + 10f, 356f, innerY + 42f)
                kit.rounded(c, paidBtn, kit.successSoft, 6f, kit.success)
                kit.centerText(c, "Tandai Sudah Dibayar Lunas", paidBtn, 11.5f, kit.success, true)
                press(c, "payPaid:${order.id}", paidBtn, 6f)
                bounds.putVisible("payPaid:${order.id}", paidBtn, topLimit = 60f, bottomLimit = h - 68f)
            } else {
                hintLines.forEachIndexed { idx, line ->
                    kit.text(c, line, 34f, innerY + 2f + idx * 14f, 10.5f, kit.muted)
                }
            }

            var endPayY = y + payH
            if (!isCancelled && !com.jajanin.app.core.ui.isTerminalStatus(order.status) && !com.jajanin.app.data.repository.sellerCanCancelOrder(order)) {
                val lockLines = kit.wrap(
                    "Pesanan terkunci · pembayaran sudah lunas, penjual tidak bisa membatalkan.",
                    326f,
                    10.5f,
                    maxLines = 3,
                    bold = true,
                )
                val lockLineH = 14.5f
                val lockH = maxOf(38f, 14f + lockLines.size * lockLineH + 8f)
                val lockBanner = RectF(SellerTokens.PAD, endPayY + 10f, SellerTokens.RIGHT, endPayY + 10f + lockH)
                kit.rounded(c, lockBanner, kit.warnBox, 7f, Color.rgb(183, 116, 0))
                val lockTextTop = lockBanner.top + 20f
                lockLines.forEachIndexed { idx, line ->
                    kit.text(c, line, 32f, lockTextTop + idx * lockLineH, 10.5f, Color.rgb(183, 116, 0), true)
                }
                endPayY = lockBanner.bottom
            }

            c.restoreToCount(bodySave)
            contentHeight = (endPayY - off) + 24f
        }
    }

    // Sticky Bottom Order Status Action (Radius 8f)
    val next = statusNextAction(order?.status ?: "")
    if (next != null && order != null) {
        val canCancel = com.jajanin.app.data.repository.sellerCanCancelOrder(order)
        if (canCancel) {
            val cancelBtn = RectF(20f, h - 58f, 132f, h - 10f)
            kit.rounded(c, cancelBtn, kit.card, 8f, kit.dangerBorder)
            kit.centerText(c, "Batalkan", cancelBtn, 13f, kit.danger, true)
            press(c, "adminCancel:${order.id}", cancelBtn, 8f)
            bounds["adminCancel:${order.id}"] = RectF(cancelBtn)
        }

        val ctaLeft = if (canCancel) 142f else 20f
        val cta = RectF(ctaLeft, h - 58f, w - 20f, h - 10f)
        val lockReason = com.jajanin.app.data.repository.sellerPoAdvanceBlockReason(order, next.second)
        if (lockReason != null) {
            val needsQrisFirst = order.paymentMethod.equals("qris", ignoreCase = true) &&
                !order.paymentStatus.equals("paid", ignoreCase = true)
            val lockedLabel = if (needsQrisFirst) {
                "Tunggu Bayar Midtrans"
            } else if (order.pickupDate.isNotBlank() && order.pickupDate > com.jajanin.app.data.repository.OrderRepository.todayWibDateString()) {
                "Terkunci s/d ${com.jajanin.app.data.repository.formatShortIdDate(order.pickupDate)}"
            } else {
                "Tunggu Bayar Midtrans"
            }
            kit.rounded(c, cta, kit.warnBox, 8f, Color.rgb(183, 116, 0))
            kit.centerText(c, lockedLabel, cta, 12.5f, Color.rgb(183, 116, 0), true)
            bounds["adminSet:${next.second}"] = RectF(cta)
        } else {
            kit.rounded(c, cta, kit.blue, 8f)
            drawCenteredButtonWithArrow(c, next.first, cta, 14f, Color.WHITE)
            press(c, "adminSet:${next.second}", cta, 8f)
            bounds["adminSet:${next.second}"] = RectF(cta)
        }
    } else if (order != null) {
        val terminal = order.status.lowercase() == STATUS_CANCELLED
        val statusCard = RectF(20f, h - 58f, w - 20f, h - 10f)
        kit.rounded(c, statusCard, kit.card, 8f, if (terminal) kit.dangerBorder else kit.border)
        val terminalText = if (terminal && order.cancelReason.isNotBlank()) {
            "Dibatalkan: ${order.cancelReason}"
        } else if (terminal) {
            "Pesanan Dibatalkan"
        } else {
            "Pesanan Selesai"
        }
        kit.centerText(
            c, terminalText,
            statusCard, 12.5f, if (terminal) kit.danger else kit.success, true,
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
