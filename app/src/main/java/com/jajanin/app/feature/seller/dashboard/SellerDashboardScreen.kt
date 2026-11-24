package com.jajanin.app.feature.seller.dashboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.ui.FloatNavItem
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.NavVariant
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.adminActionableCount
import com.jajanin.app.core.ui.drawFloatingNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.navBadge
import com.jajanin.app.core.ui.paymentLabel
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.ui.statusNextAction
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.sellerDivider
import com.jajanin.app.feature.seller.sellerDot
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSurface
import com.jajanin.app.feature.seller.sellerSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val adminDateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.forLanguageTag("id"))

private const val HEADER_H = 64f
private const val NAV_CLEAR = GLASS_TOP_OFFSET + 8f

internal fun JajaninView.drawAdminNav(c: Canvas, w: Float, h: Float, active: String) {
    drawFloatingNav(
        c, w, h,
        listOf(
            FloatNavItem("Dashboard", "grid", true),
            FloatNavItem("Pesanan", "receipt", true),
            FloatNavItem("Produk", "plus", true),
            FloatNavItem("Chat", "chat", true),
            FloatNavItem("Notifikasi", "bell", true),
            FloatNavItem("Profil", "person", true),
        ),
        active,
        "stab:",
        variant = NavVariant.STANDARD,
    )
    val notifCount = adminActionableCount()
    if (unreadChatTotal > 0) {
        navBadge(index = 3, count = unreadChatTotal)
    } else if (notifCount > 0) {
        navBadge(index = 4, count = notifCount)
    }
}

internal fun JajaninView.drawAdminDashboard(c: Canvas, w: Float, h: Float) {
    if (pendingScrollToOrders) {
        pendingScrollToOrders = false
        sellerTab = "Pesanan"
        scroll = 0f
    }

    if (sellerTab == "Pesanan") {
        drawSellerOrdersBoard(c, w, h)
        return
    }

    c.drawColor(kit.bg)
    val today = adminOrders
    val pending = today.count { it.status.equals(STATUS_PENDING, true) }
    val confirmed = today.count { it.status.equals(STATUS_CONFIRMED, true) }
    val preparing = today.count {
        it.status.equals(STATUS_PREPARING, true) || it.status.equals(STATUS_PROCESSING_LEGACY, true)
    }
    val ready = today.count { it.status.equals(STATUS_READY, true) }
    val completed = today.count { it.status.equals(STATUS_COMPLETED, true) }
    val active = pending + confirmed + preparing + ready
    val revenue = today.filter { it.status.equals(STATUS_COMPLETED, true) }.sumOf { it.totalIdr }

    val off = -scroll
    val bottomLimit = h - NAV_CLEAR
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= bottomLimit

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 78f + off

    // 1. Open Editorial Revenue & Queue Capacity Section (No heavy outer box)
    if (visible(y, y + 76f)) {
        kit.text(c, "OMZET HARI INI", 20f, y + 12f, 10f, kit.muted, true)
        kit.textFitted(c, formatRupiah(revenue), 20f, y + 44f, 220f, 28f, 18f, kit.ink, true)
        kit.text(c, "$completed selesai  ·  ${today.size} total pesanan", 20f, y + 64f, 11f, kit.muted)

        // Vertical divider + Active Orders & Pre-Order Breakdown (tanpa batas 10 maks)
        val todayStr = OrderRepository.todayWibDateString()
        val activeOrders = today.filter { !isTerminalStatus(it.status) }
        val poActive = activeOrders.count { it.isPreOrder && (it.pickupDate.isBlank() || it.pickupDate > todayStr) }
        val todayActive = (activeOrders.size - poActive).coerceAtLeast(0)
        kit.line(c, 246f, y + 4f, 246f, y + 66f, kit.divider)
        kit.text(c, "PESANAN AKTIF", 260f, y + 12f, 9.5f, kit.muted, true)
        kit.text(c, "$active", 260f, y + 42f, 24f, kit.blue, true)
        val activeW = kit.measure("$active", 24f, true)
        kit.text(c, "aktif", 260f + activeW + 5f, y + 40f, 11f, kit.muted, true)
        kit.textEllipsized(
            c,
            "$todayActive hari ini · $poActive PO",
            260f,
            y + 62f,
            110f,
            10f,
            if (poActive > 0) Color.rgb(183, 116, 0) else kit.muted,
            poActive > 0,
        )
    }
    y += 78f

    // 2. Interactive 4-Stage Pipeline Strip (Tap any stage to open Pesanan filtered to that status)
    val pipeRect = RectF(20f, y, 370f, y + 54f)
    if (visible(pipeRect.top, pipeRect.bottom)) {
        sellerSurface(c, pipeRect)
        val colW = SellerTokens.WIDTH / 4f
        val stages = listOf(
            Triple("Baru", pending, kit.star),
            Triple("Disiapkan", confirmed + preparing, kit.blue),
            Triple("Siap", ready, kit.success),
            Triple("Selesai", completed, kit.muted),
        )
        stages.forEachIndexed { idx, (label, count, color) ->
            val left = 20f + idx * colW
            val cell = RectF(left, y, left + colW, y + 54f)
            if (idx > 0) kit.line(c, left, y + 8f, left, y + 46f, kit.divider)
            kit.text(c, count.toString(), left + 14f, y + 25f, 18f, kit.ink, true)
            sellerDot(c, left + 17f, y + 40f, color, 3f)
            kit.textEllipsized(c, label, left + 24f, y + 43.5f, colW - 28f, 10f, kit.muted, true)
            press(c, "sellerPipeline:$label", cell, 8f)
            bounds.putVisible("sellerPipeline:$label", cell, topLimit = HEADER_H, bottomLimit = bottomLimit)
        }
    }
    y += 72f

    // 3. Active Orders Queue ("PESANAN AKTIF") — Tampilkan semua pesanan berjalan sampai Selesai/Dibatalkan
    val urgentOrders = today.filter {
        !isTerminalStatus(it.status)
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))

    if (urgentOrders.isNotEmpty()) {
        if (visible(y, y + 20f)) {
            sellerSection(
                c,
                "Pesanan Aktif (${urgentOrders.size})",
                y + 12f,
                trailing = "Semua pesanan →",
                trailingColor = kit.blue
            )
            bounds.putVisible(
                "sellerPipeline:Semua",
                RectF(250f, y - 4f, 370f, y + 20f),
                topLimit = HEADER_H,
                bottomLimit = bottomLimit
            )
        }
        y += 20f

        val shownUrgent = urgentOrders.take(5)
        shownUrgent.forEach { order ->
            val rowH = 104f
            if (visible(y, y + rowH)) {
                drawCompactOrderRow(c, order, y, rowH, bottomLimit)
            }
            y += rowH + 8f
        }
        y += 12f
    }

    // 4. Etalase Menu Warung (Open Hairline-Divided Product Rows — No Individual Boxes)
    val shownProducts = sellerProducts
    if (visible(y, y + 22f)) {
        sellerSection(c, "Etalase Menu (${shownProducts.size})", y + 13f)
        val addLabel = "+ Tambah Produk"
        val addW = kit.measure(addLabel, 11f, true)
        kit.text(c, addLabel, 370f - addW, y + 13f, 11f, kit.blue, true)
        bounds.putVisible(
            "productAdd",
            RectF(370f - addW - 12f, y - 6f, 370f, y + 22f),
            topLimit = HEADER_H,
            bottomLimit = bottomLimit
        )
    }
    y += 22f

    if (shownProducts.isEmpty()) {
        val emptyRect = RectF(20f, y, 370f, y + 96f)
        if (visible(emptyRect.top, emptyRect.bottom)) {
            sellerSurface(c, emptyRect)
            drawIcon(c, "box", 42f, y + 34f, 20f, kit.blue)
            kit.text(c, "Belum ada menu di etalase", 64f, y + 32f, 13f, kit.ink, true)
            kit.textEllipsized(c, "Tambahkan produk pertama agar pembeli bisa memesan.", 64f, y + 50f, 290f, 11f, kit.muted)
            val addBtn = RectF(64f, y + 60f, 184f, y + 86f)
            kit.rounded(c, addBtn, kit.tint, 6f)
            kit.centerText(c, "+ Tambah Produk", addBtn, 11f, kit.blue, true)
            bounds.putVisible("productAdd", addBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)
        }
        y += 108f
    } else {
        val productRowH = 84f
        if (visible(y, y + 4f)) {
            kit.line(c, 20f, y, 370f, y, kit.divider)
        }
        shownProducts.forEachIndexed { index, product ->
            val rowTop = y + index * productRowH
            if (visible(rowTop, rowTop + productRowH)) {
                drawCleanSellerProductRow(
                    c = c,
                    id = product.id,
                    name = product.name,
                    priceIdr = product.priceIdr,
                    stock = product.stock,
                    available = product.available,
                    scheduleLabel = product.scheduleBadgeLabel,
                    imageUrl = product.imageUrl,
                    top = rowTop,
                    height = productRowH,
                    bottomLimit = bottomLimit,
                    last = index == shownProducts.lastIndex
                )
            }
        }
        y += shownProducts.size * productRowH + 16f
    }

    c.restoreToCount(bodySave)

    contentHeight = (y - off) + 32f

    drawSellerTopHeader(
        c,
        title = myStallName.takeIf { it.isNotBlank() } ?: "Dashboard Warung",
        subtitle = adminDateFormat.format(Date())
    )
    drawAdminNav(c, w, h, "Dashboard")

    if (adminNotifOpen) drawNotifPanel(c, w, h, today)
}

/**
 * Dedicated Kitchen Display / POS Order Board when Seller selects the "Pesanan" tab.
 */
private fun JajaninView.drawSellerOrdersBoard(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val today = adminOrders
    val pending = today.count { it.status.equals(STATUS_PENDING, true) }
    val confirmed = today.count { it.status.equals(STATUS_CONFIRMED, true) }
    val preparing = today.count {
        it.status.equals(STATUS_PREPARING, true) || it.status.equals(STATUS_PROCESSING_LEGACY, true)
    }
    val ready = today.count { it.status.equals(STATUS_READY, true) }
    val completed = today.count { it.status.equals(STATUS_COMPLETED, true) }
    val active = pending + confirmed + preparing + ready

    val todayStr = OrderRepository.todayWibDateString()
    val poCount = today.count { it.isPreOrder && (it.pickupDate.isBlank() || it.pickupDate > todayStr) }
    val sameDayCount = (today.size - poCount).coerceAtLeast(0)

    val filtered = today.filter { order ->
        val s = order.status.lowercase()
        val statusOk = when (adminFilter) {
            "Baru" -> s == STATUS_PENDING
            "Disiapkan" -> s == STATUS_CONFIRMED || s == STATUS_PREPARING || s == STATUS_PROCESSING_LEGACY
            "Siap" -> s == STATUS_READY
            "Selesai" -> s == STATUS_COMPLETED || s == STATUS_CANCELLED
            else -> true
        }
        val isPoFuture = order.isPreOrder && (order.pickupDate.isBlank() || order.pickupDate > todayStr)
        val schedOk = when (adminScheduleFilter) {
            "Hari Ini" -> !isPoFuture
            "Pre-Order", "PO Besok" -> isPoFuture
            else -> true
        }
        statusOk && schedOk
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.pickupDate.ifBlank { "9999-12-31" } }, { it.createdAt }))

    val off = -scroll
    val bottomLimit = h - NAV_CLEAR
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= bottomLimit

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 74f + off

    // 1. Clean Underline Status Filter Tabs (No boxy chips)
    if (visible(y, y + 34f)) {
        kit.line(c, 20f, y + 32f, 370f, y + 32f, kit.divider)
        val filterSpecs = listOf(
            "Semua" to "Semua (${today.size})",
            "Baru" to if (pending > 0) "Baru ($pending)" else "Baru",
            "Disiapkan" to if (confirmed + preparing > 0) "Disiapkan (${confirmed + preparing})" else "Disiapkan",
            "Siap" to if (ready > 0) "Siap ($ready)" else "Siap",
            "Selesai" to "Selesai"
        )
        var tx = 20f
        filterSpecs.forEach { (key, label) ->
            val isSel = adminFilter == key
            val lw = kit.measure(label, 11.5f, true)
            val tabRect = RectF(tx, y, tx + lw + 10f, y + 32f)
            kit.text(
                c,
                label,
                tx,
                y + 21f,
                11.5f,
                if (isSel) kit.blue else kit.muted,
                isSel
            )
            if (isSel) {
                kit.rect(c, tx, y + 30f, tx + lw, y + 32.5f, kit.blue)
            }
            bounds.putVisible("adminFilter:$key", tabRect, topLimit = HEADER_H, bottomLimit = bottomLimit)
            tx += lw + 16f
        }
    }
    y += 40f

    // 1b. Schedule Filter Pills (Semua / Hari Ini / Pre-Order)
    if (visible(y, y + 32f)) {
        val schedSpecs = listOf(
            "Semua" to "Semua Jadwal",
            "Hari Ini" to "Hari Ini ($sameDayCount)",
            "Pre-Order" to "Pre-Order ($poCount)",
        )
        var sx = 20f
        schedSpecs.forEach { (key, label) ->
            val active = adminScheduleFilter == key || (key == "Pre-Order" && adminScheduleFilter == "PO Besok")
            val sw = kit.measure(label, 10.5f, true) + 20f
            val pill = RectF(sx, y + 2f, sx + sw, y + 28f)
            val isPoPill = key == "Pre-Order"
            val bg = when {
                active && isPoPill -> kit.warnBox
                active -> kit.tint
                else -> kit.card
            }
            val border = when {
                active && isPoPill -> Color.rgb(183, 116, 0)
                active -> kit.blue
                else -> kit.border
            }
            val fg = when {
                active && isPoPill -> Color.rgb(183, 116, 0)
                active -> kit.blue
                else -> kit.muted
            }
            kit.rounded(c, pill, bg, 7f, border)
            kit.centerText(c, label, pill, 10.5f, fg, active)
            bounds.putVisible("adminSchedule:$key", pill, topLimit = HEADER_H, bottomLimit = bottomLimit)
            sx += sw + 8f
        }
    }
    y += 38f

    // 2. POS Order Rows
    if (filtered.isEmpty()) {
        drawOrdersEmpty(c, y, h)
        y += 96f
    } else {
        filtered.forEach { order ->
            val hasNext = statusNextAction(order.status) != null
            val rowH = if (hasNext) 132f else 98f
            if (visible(y, y + rowH)) {
                drawOrderCard(c, order, y, rowH, bottomLimit)
            }
            y += rowH + 10f
        }
    }

    c.restoreToCount(bodySave)
    contentHeight = (y - off) + 32f

    drawSellerTopHeader(
        c,
        title = "Antrean Pesanan",
        subtitle = "$active pesanan aktif  ·  $completed selesai hari ini"
    )
    drawAdminNav(c, w, h, "Pesanan")

    if (adminNotifOpen) drawNotifPanel(c, w, h, today)
}

private fun JajaninView.drawCompactOrderRow(
    c: Canvas,
    order: AdminOrder,
    top: Float,
    height: Float,
    bottomLimit: Float
) {
    val card = RectF(20f, top, 370f, top + height)
    sellerSurface(c, card)
    val stripeColor = when (order.status.lowercase()) {
        STATUS_PENDING -> kit.star
        STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
        else -> kit.success
    }
    kit.rect(c, 20f, top + 8f, 23f, top + height - 8f, stripeColor)

    val codeTag = RectF(32f, top + 10f, 80f, top + 29f)
    kit.rounded(c, codeTag, kit.tint, 4f)
    kit.centerText(c, "#${order.pickupCode}", codeTag, 10.5f, kit.blue, true)

    kit.textEllipsized(c, order.buyerName, 88f, top + 24f, 180f, 13f, kit.ink, true)
    kit.textRight(c, formatRupiah(order.totalIdr), 358f, top + 24f, 95f, 13f, kit.ink, true)

    kit.textEllipsized(
        c,
        "${order.itemCount} item · ${order.itemsSummary}",
        32f,
        top + 43f,
        320f,
        11f,
        kit.muted
    )

    val schedColor = if (order.isPreOrder) Color.rgb(183, 116, 0) else kit.blue
    kit.textEllipsized(c, "Jadwal: ${order.pickupScheduleLabel}", 32f, top + 59f, 320f, 10f, schedColor, true)

    val (payLabel, payColor) = paymentLabel(order.paymentStatus)
    val method = if (order.paymentMethod == "cash") "Tunai" else "QRIS"
    val (stLabel, _) = statusLabel(order.status)
    kit.textEllipsized(c, "$stLabel · $method · $payLabel", 32f, top + 84f, 130f, 10.5f, payColor, true)

    bounds.putVisible(
        "adminOrder:${order.id}",
        RectF(card.left, card.top, if (statusNextAction(order.status) != null) 164f else card.right, card.bottom),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit
    )

    val nextAction = statusNextAction(order.status)
    if (nextAction != null) {
        val cancelBtn = RectF(168f, top + 66f, 238f, top + 94f)
        kit.rounded(c, cancelBtn, kit.card, 6f, kit.dangerBorder)
        kit.centerText(c, "Batal", cancelBtn, 10.5f, kit.danger, true)
        press(c, "adminCancel:${order.id}", cancelBtn, 6f)
        bounds.putVisible("adminCancel:${order.id}", cancelBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)

        val actionBtn = RectF(244f, top + 66f, 358f, top + 94f)
        kit.rounded(c, actionBtn, kit.blue, 6f)
        kit.centerText(c, nextAction.first, actionBtn, 10.5f, Color.WHITE, true)
        press(c, "adminStatus:${order.id}", actionBtn, 6f)
        bounds.putVisible("adminStatus:${order.id}", actionBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)
    }
}

private fun JajaninView.drawCleanSellerProductRow(
    c: Canvas,
    id: String,
    name: String,
    priceIdr: Int,
    stock: Int?,
    available: Boolean,
    scheduleLabel: String,
    imageUrl: String?,
    top: Float,
    height: Float,
    bottomLimit: Float,
    last: Boolean,
) {
    val thumbnail = RectF(20f, top + 12f, 80f, top + 72f)
    drawProductImageUrl(c, imageUrl, thumbnail)

    val textX = 92f
    val textWidth = 210f
    kit.textEllipsized(c, name, textX, top + 28f, textWidth, 14f, kit.ink, true)
    val stockLabel = stock?.let { "Stok $it" } ?: "Stok bebas"
    val statusText = if (available) stockLabel else "Nonaktif"
    kit.textEllipsized(
        c,
        "${formatRupiah(priceIdr)} · $statusText · $scheduleLabel",
        textX,
        top + 47f,
        textWidth,
        11f,
        if (available) kit.muted else kit.danger,
        !available
    )

    // Quick inline Edit & Delete links
    kit.text(c, "Ubah", textX, top + 68f, 11f, kit.blue, true)
    val editW = kit.measure("Ubah", 11f, true)
    kit.text(c, "·", textX + editW + 6f, top + 68f, 11f, kit.muted)
    kit.text(c, "Hapus", textX + editW + 14f, top + 68f, 11f, kit.danger, true)

    bounds.putVisible(
        "productEdit:$id",
        RectF(textX - 4f, top + 52f, textX + editW + 8f, top + height),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )
    bounds.putVisible(
        "productDelete:$id",
        RectF(textX + editW + 10f, top + 52f, textX + editW + 66f, top + height),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )

    // Right side: Availability Toggle Switch
    val toggle = RectF(316f, top + 24f, 370f, top + 60f)
    sellerSwitch(c, available, 370f, top + 42f)
    bounds.putVisible(
        "productToggle:$id",
        toggle,
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )

    // Tapping row body opens edit form
    bounds.putVisible(
        "productEdit:$id",
        RectF(20f, top, 280f, top + 52f),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )

    if (!last) sellerDivider(c, top + height, 92f, 370f)
}

private fun JajaninView.drawProductImageUrl(c: Canvas, imageUrl: String?, rect: RectF) {
    val url = imageUrl?.takeIf { it.startsWith("https://") }
    val bitmap = url?.let { ProductImageCache.images[it] }
    if (bitmap == null) {
        kit.rounded(c, rect, kit.tint, 7f)
        kit.centerText(c, "J", rect, 20f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
        return
    }
    com.jajanin.app.core.ui.drawBitmapCenterCrop(c, bitmap, rect, 7f)
}

private fun JajaninView.drawOrderCard(
    c: Canvas,
    order: AdminOrder,
    top: Float,
    height: Float,
    bottomLimit: Float
) {
    val card = RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + height)
    sellerSurface(c, card)

    val stripe = when (order.status.lowercase()) {
        STATUS_PENDING -> kit.star
        STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
        STATUS_READY, STATUS_COMPLETED -> kit.success
        else -> kit.border
    }
    kit.rect(c, 20f, top + 8f, 23f, top + height - 8f, stripe)

    // Row 1: Pickup Code badge + Buyer Name + Time + Status Pill
    val codeTag = RectF(32f, top + 11f, 82f, top + 31f)
    kit.rounded(c, codeTag, kit.tint, 4f)
    kit.centerText(c, "#${order.pickupCode}", codeTag, 10.5f, kit.blue, true)

    val (label, color) = statusLabel(order.status)
    val pillRect = sellerPill(
        c, label, 358f, top + 21f,
        softBackground(order.status), color,
        height = 20f, size = 9.5f,
    )

    val buyerMaxW = (pillRect.left - 90f - 8f).coerceAtLeast(80f)
    kit.textEllipsized(c, "${order.buyerName} · ${order.timeLabel}", 90f, top + 25f, buyerMaxW, 12.5f, kit.ink, true)

    // Row 2: Items summary + Total price
    val total = formatRupiah(order.totalIdr)
    val totalWidth = kit.measure(total, 13f, true)
    kit.textEllipsized(
        c,
        "${order.itemCount} item · ${order.itemsSummary}",
        32f,
        top + 48f,
        358f - 32f - totalWidth - 14f,
        11.5f,
        kit.ink
    )
    kit.textRight(c, total, 358f, top + 48f, totalWidth + 16f, 13f, kit.ink, true)

    // Row 3: Jadwal & Sesi Pengambilan
    val schedColor = if (order.isPreOrder) Color.rgb(183, 116, 0) else kit.blue
    kit.textEllipsized(c, "Jadwal: ${order.pickupScheduleLabel}", 32f, top + 64f, 320f, 10.5f, schedColor, true)

    // Row 4: Payment method & status
    val (payLabel, payColor) = paymentLabel(order.paymentStatus)
    val payment = if (order.paymentMethod == "cash") "Tunai" else "QRIS"
    kit.text(c, "$payment · $payLabel", 32f, top + 81f, 10.5f, payColor, true)

    val unreadOrder = unreadChatByOrder[order.id] ?: 0
    val chatLabel = if (unreadOrder > 0) "Chat ($unreadOrder)" else "Chat"
    val nextAction = statusNextAction(order.status)
    if (nextAction != null) {
        kit.line(c, 32f, top + 91f, 358f, top + 91f, kit.divider)

        val chatBtn = RectF(32f, top + 97f, 110f, top + 125f)
        kit.rounded(
            c,
            chatBtn,
            if (unreadOrder > 0) kit.blue else kit.tint,
            6f,
            if (unreadOrder > 0) null else kit.blue,
        )
        kit.centerText(c, chatLabel, chatBtn, 10.5f, if (unreadOrder > 0) Color.WHITE else kit.blue, true)
        press(c, "chatOrder:${order.id}", chatBtn, 6f)
        bounds.putVisible("chatOrder:${order.id}", chatBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)

        val cancel = RectF(118f, top + 97f, 204f, top + 125f)
        kit.rounded(c, cancel, kit.card, 6f, kit.dangerBorder)
        kit.centerText(c, "Batalkan", cancel, 10.5f, kit.danger, true)
        press(c, "adminCancel:${order.id}", cancel, 6f)
        bounds.putVisible("adminCancel:${order.id}", cancel, topLimit = HEADER_H, bottomLimit = bottomLimit)

        val action = RectF(212f, top + 97f, 358f, top + 125f)
        kit.rounded(c, action, kit.blue, 6f)
        kit.centerText(c, "${nextAction.first} →", action, 11f, Color.WHITE, true)
        press(c, "adminStatus:${order.id}", action, 6f)
        bounds.putVisible("adminStatus:${order.id}", action, topLimit = HEADER_H, bottomLimit = bottomLimit)
    } else {
        val chatBtn = RectF(286f, top + 66f, 358f, top + 88f)
        kit.rounded(
            c,
            chatBtn,
            if (unreadOrder > 0) kit.blue else kit.tint,
            5f,
            if (unreadOrder > 0) null else kit.blue,
        )
        kit.centerText(c, chatLabel, chatBtn, 10f, if (unreadOrder > 0) Color.WHITE else kit.blue, true)
        press(c, "chatOrder:${order.id}", chatBtn, 5f)
        bounds.putVisible("chatOrder:${order.id}", chatBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)
    }

    val bodyHitBottom = if (nextAction != null) top + 91f else top + height
    bounds.putVisible(
        "adminOrder:${order.id}",
        RectF(card.left, card.top, card.right, bodyHitBottom),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit
    )
}

private fun JajaninView.softBackground(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.warnBox
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.tint
    STATUS_READY, STATUS_COMPLETED -> kit.successSoft
    STATUS_CANCELLED -> kit.dangerBorder
    else -> kit.tint
}

private fun JajaninView.drawOrdersEmpty(c: Canvas, top: Float, h: Float) {
    if (top > h - NAV_CLEAR) return
    when {
        adminLoading && adminOrders.isEmpty() -> kit.centerText(
            c, "Memuat pesanan...", RectF(24f, top, 366f, top + 60f), 13f, kit.muted,
        )
        adminError != null && adminOrders.isEmpty() -> kit.centerText(
            c, "Gagal memuat pesanan. Coba lagi.",
            RectF(24f, top, 366f, top + 60f), 12f, kit.danger,
        )
        else -> {
            val panel = RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + 78f)
            sellerSurface(c, panel)
            drawIcon(c, "receipt", 42f, panel.centerY(), 18f, kit.muted)
            kit.text(
                c,
                if (adminOrders.isEmpty()) "Belum ada pesanan hari ini" else "Tidak ada pesanan di status ini",
                64f, top + 34f, 13f, kit.ink, true,
            )
            kit.textEllipsized(
                c, "Pesanan baru yang masuk akan tampil otomatis di sini.",
                64f, top + 53f, 286f, 11f, kit.muted,
            )
        }
    }
}

private fun JajaninView.drawSellerTopHeader(
    c: Canvas,
    title: String,
    subtitle: String
) {
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)

    kit.textEllipsized(c, title, 20f, 30f, 236f, 18.5f, kit.ink, true)
    kit.textEllipsized(c, subtitle, 20f, 48f, 236f, 10.5f, kit.muted)

    // Refresh
    drawIcon(c, "refresh", 290f, 34f, 17f, kit.ink)
    press(c, "adminRefresh", RectF(272f, 14f, 308f, 54f), 6f)
    bounds["adminRefresh"] = RectF(272f, 14f, 308f, 54f)

    // Bell
    drawIcon(c, "bell", 326f, 34f, 17f, kit.ink)
    press(c, "adminBell", RectF(308f, 14f, 344f, 54f), 6f)
    bounds["adminBell"] = RectF(308f, 14f, 344f, 54f)
    val notifCount = adminActionableCount()
    if (notifCount > 0) {
        sellerDot(c, 334f, 24f, kit.danger, 3.5f)
    }

    // Profile Avatar
    val stallInitial = (myStallName.trim().firstOrNull() ?: 'W').uppercaseChar().toString()
    val avatar = RectF(346f, 21f, 370f, 45f)
    kit.rounded(c, avatar, kit.tint, 12f)
    kit.centerText(c, stallInitial, avatar, 11f, kit.blue, true)
    bounds["adminProfile"] = RectF(342f, 14f, 376f, 54f)

    kit.line(c, SellerTokens.PAD, HEADER_H, SellerTokens.RIGHT, HEADER_H, kit.divider)
    c.restore()
}

private fun JajaninView.drawNotifPanel(c: Canvas, w: Float, h: Float, today: List<AdminOrder>) {
    kit.rect(c, 0f, 0f, w, h, Color.argb(150, 15, 20, 35))
    bounds["adminNotifClose"] = RectF(0f, 0f, w, h)

    val rows = ArrayList<Pair<AdminOrder, Boolean>>()
    today.filter { order ->
        val s = order.status.lowercase()
        s == STATUS_PENDING || s == STATUS_CONFIRMED
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
        .forEach { rows.add(it to true) }
    today.filter { order ->
        order.paymentStatus == "waiting_verification" && !isTerminalStatus(order.status)
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))
        .forEach { rows.add(it to false) }

    val shown = rows.take(8)
    val panelTop = 72f
    val lineH = 58f
    val panelBottom = minOf(panelTop + 46f + shown.size * lineH + 10f, h - 94f)
    sellerSurface(c, RectF(16f, panelTop, w - 16f, panelBottom))
    kit.text(c, "Notifikasi Cepat", SellerTokens.PAD + 4f, panelTop + 30f, 15f, kit.ink, true)

    val close = RectF(336f, panelTop + 8f, 372f, panelTop + 44f)
    drawIcon(c, "close", close.centerX(), close.centerY(), 15f, kit.muted)
    bounds["adminNotifCloseBtn"] = RectF(close)
    kit.line(c, 24f, panelTop + 44f, 366f, panelTop + 44f, kit.divider)

    if (shown.isEmpty()) {
        kit.textEllipsized(c, "Belum ada notifikasi baru.", 24f, panelTop + 72f, 330f, 12f, kit.muted)
        return
    }
    shown.forEachIndexed { index, (order, isNew) ->
        val top = panelTop + 46f + index * lineH
        if (top + lineH > panelBottom) return@forEachIndexed
        sellerDot(c, 28f, top + 21f, if (isNew) kit.star else kit.blue, 3.5f)
        val title = if (isNew) "Pesanan baru · ${order.buyerName}" else "Verifikasi bayar · ${order.buyerName}"
        kit.textEllipsized(c, title, 40f, top + 21f, 300f, 12f, kit.ink, true)
        kit.textEllipsized(
            c,
            "#${order.pickupCode} · ${order.timeLabel} · ${formatRupiah(order.totalIdr)}",
            40f, top + 40f, 306f, 10.5f, kit.muted,
        )
        if (index < shown.size - 1) sellerDivider(c, top + lineH, 24f, 366f)
        press(c, "adminNotifOrder:${order.id}", RectF(20f, top + 2f, 370f, top + lineH - 2f), 6f)
        bounds["adminNotifOrder:${order.id}"] = RectF(20f, top + 2f, 370f, top + lineH - 2f)
    }
}
