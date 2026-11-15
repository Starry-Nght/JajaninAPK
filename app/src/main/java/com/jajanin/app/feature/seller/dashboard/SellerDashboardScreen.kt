package com.jajanin.app.feature.seller.dashboard

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
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.FloatNavItem
import com.jajanin.app.core.ui.adminActionableCount
import com.jajanin.app.core.ui.drawFloatingNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.navBadge
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.ui.statusLabel
import com.jajanin.app.core.ui.statusNextAction
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusSortOrder
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.sellerDivider
import com.jajanin.app.feature.seller.sellerDot
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSurface
import com.jajanin.app.feature.seller.sellerSwitch

private val adminDateFormat = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.forLanguageTag("id"))

private const val WARN = 0xFFB77400.toInt()
private const val BOTTOM_CLEARANCE = 32f

internal fun JajaninView.drawAdminNav(c: Canvas, w: Float, h: Float, active: String) {
    drawFloatingNav(
        c, w, h,
        listOf(
            FloatNavItem("Dashboard", "grid", false),
            FloatNavItem("Pesanan", "receipt", false),
            FloatNavItem("Produk", "box", false),
            FloatNavItem("Notifikasi", "bell", false),
            FloatNavItem("Profil", "person", false),
        ),
        active, "stab:",
    )
    // Lencana Notifikasi (indeks 3) dilukis host di atas dock kaca asli.
    navBadge(index = 3, count = adminActionableCount())
}

internal fun JajaninView.drawAdminDashboard(c: Canvas, w: Float, h: Float) {
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
    val revenue = today.filterNot { it.status.equals(STATUS_CANCELLED, true) }.sumOf { it.totalIdr }

    val shownProducts = sellerProducts
    val filtered = today.filter { order ->
        val s = order.status.lowercase()
        when (adminFilter) {
            "Baru" -> s == STATUS_PENDING
            "Disiapkan" -> s == STATUS_CONFIRMED || s == STATUS_PREPARING || s == STATUS_PROCESSING_LEGACY
            "Siap" -> s == STATUS_READY
            "Selesai" -> s == STATUS_COMPLETED || s == STATUS_CANCELLED
            else -> true
        }
    }.sortedWith(compareBy({ statusSortOrder(it.status) }, { it.createdAt }))

    // --- Jangkar layout (murni aritmetika) ---
    val revenueTop = 94f
    val revenueH = 84f
    val statsTop = revenueTop + revenueH + 14f
    val statsH = 50f
    val productsTitle = statsTop + statsH + 28f
    val prodTop = productsTitle + 16f
    val productRowH = 92f
    val productEmptyH = 132f
    val productBlockH = if (shownProducts.isEmpty()) productEmptyH else shownProducts.size * productRowH
    val productsEnd = prodTop + productBlockH
    val ordersTitle = productsEnd + 34f
    val filtersTop = ordersTitle + 14f
    val listTop = filtersTop + SellerTokens.CONTROL_H + 14f
    val orderGap = 10f
    val orderRows = filtered.map { order ->
        order to if (statusNextAction(order.status) != null) 128f else 96f
    }
    val ordersBlockH = when {
        orderRows.isEmpty() -> 92f
        else -> orderRows.sumOf { it.second.toDouble() }.toFloat() +
            orderGap * (orderRows.size - 1)
    }
    val contentTotal = listTop + ordersBlockH + BOTTOM_CLEARANCE
    if (pendingScrollToOrders) {
        pendingScrollToOrders = false
        val maxScroll = (contentTotal - h + 78f).coerceAtLeast(0f)
        scroll = (ordersTitle - 80f).coerceAtLeast(0f).coerceAtMost(maxScroll)
    }
    val off = -scroll

    drawRevenue(c, revenueTop + off, revenueH, revenue, active, today.size, h)
    drawStats(c, statsTop + off, statsH, pending, confirmed + preparing, ready, completed)

    val productsBaseline = productsTitle + off
    kit.line(c, SellerTokens.PAD, productsBaseline - 14f, SellerTokens.RIGHT, productsBaseline - 14f, kit.divider)
    kit.text(c, "Produk saya", SellerTokens.PAD + 2f, productsBaseline, SellerTokens.T_TITLE, kit.ink, true)
    val addLabel = "Tambah"
    val addX = SellerTokens.RIGHT - kit.measure(addLabel, SellerTokens.T_EYEBROW, true)
    kit.text(c, addLabel, addX, productsBaseline, SellerTokens.T_EYEBROW, kit.blue, true)
    bounds.putVisible(
        "productAdd",
        RectF(addX - 12f, productsBaseline - 16f, SellerTokens.RIGHT, productsBaseline + 8f),
        bottomLimit = h - GLASS_TOP_OFFSET,
    )
    if (shownProducts.isEmpty()) {
        drawProductEmpty(c, prodTop + off, productEmptyH, h)
    } else {
        sellerSurface(c, RectF(SellerTokens.PAD, prodTop + off, SellerTokens.RIGHT, prodTop + productBlockH + off))
        shownProducts.forEachIndexed { index, product ->
            val top = prodTop + index * productRowH + off
            if (top + productRowH < 86f || top > h - GLASS_TOP_OFFSET) return@forEachIndexed
            drawProductRow(c, product.id, product.name, product.priceIdr, product.stock, product.available, product.imageUrl, top, productRowH, h, index == shownProducts.lastIndex)
        }
    }

    kit.line(c, SellerTokens.PAD, ordersTitle + off - 14f, SellerTokens.RIGHT, ordersTitle + off - 14f, kit.divider)
    sellerSection(
        c, "Pesanan masuk", ordersTitle + off,
        trailing = if (pending > 0) "$pending baru" else "${filtered.size} pesanan",
        trailingColor = if (pending > 0) kit.star else kit.muted,
    )
    drawOrderFilters(c, filtersTop + off, pending, h)

    if (orderRows.isEmpty()) {
        drawOrdersEmpty(c, listTop + off, h)
    } else {
        var top = listTop + off
        orderRows.forEach { (order, rowHeight) ->
            if (top + rowHeight >= 86f && top <= h - GLASS_TOP_OFFSET) {
                drawOrderCard(c, order, top, rowHeight, h)
            }
            top += rowHeight + orderGap
        }
    }
    contentHeight = contentTotal

    drawDashboardHeader(c)
    drawAdminNav(c, w, h, "Dashboard")

    if (adminNotifOpen) drawNotifPanel(c, w, h, today)
}

private fun JajaninView.drawRevenue(
    c: Canvas,
    top: Float,
    height: Float,
    revenue: Int,
    active: Int,
    totalOrders: Int,
    viewportH: Float,
) {
    val card = RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + height)
    if (card.bottom < 86f || card.top > viewportH) return
    sellerSurface(c, card)
    kit.text(c, "Omzet hari ini", 22f, top + 24f, SellerTokens.T_EYEBROW, kit.muted, true)
    kit.textFitted(c, formatRupiah(revenue), 22f, top + 60f, 240f, 28f, 18f, kit.ink, true)
    kit.line(c, 274f, top + 16f, 274f, top + height - 8f, kit.divider)
    kit.textEllipsized(c, "Pesanan aktif", 286f, top + 30f, 78f, 10f, kit.muted, true)
    kit.text(c, active.toString(), 286f, top + 56f, 20f, kit.ink, true)
    kit.textEllipsized(c, "dari $totalOrders pesanan", 286f, top + 72f, 78f, 9f, kit.muted)
    kit.line(c, SellerTokens.PAD, top + height, SellerTokens.RIGHT, top + height, kit.divider)
}

private fun JajaninView.drawStats(
    c: Canvas,
    top: Float,
    height: Float,
    pending: Int,
    preparing: Int,
    ready: Int,
    completed: Int,
) {
    sellerSurface(c, RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + height))
    val column = SellerTokens.WIDTH / 4f
    val colors = listOf(kit.star, kit.blue, kit.success, kit.muted)
    val labels = listOf("Baru", "Disiapkan", "Siap", "Selesai")
    val values = listOf(pending, preparing, ready, completed)
    for (i in 0 until 4) {
        val left = SellerTokens.PAD + i * column
        if (i > 0) kit.line(c, left, top + 6f, left, top + height - 6f, kit.divider)
        kit.text(c, values[i].toString(), left + 14f, top + 22f, SellerTokens.T_METRIC, kit.ink, true)
        sellerDot(c, left + 17f, top + 36f, colors[i])
        kit.textEllipsized(
            c, labels[i], left + 22f, top + 40f,
            column - 26f, 9.5f, kit.muted, true,
        )
    }
}

private fun JajaninView.drawProductRow(
    c: Canvas,
    id: String,
    name: String,
    priceIdr: Int,
    stock: Int?,
    available: Boolean,
    imageUrl: String?,
    top: Float,
    height: Float,
    h: Float,
    last: Boolean,
) {
    val thumbnail = RectF(22f, top + 16f, 78f, top + 72f)
    drawProductImageUrl(c, imageUrl, thumbnail)

    val textX = 90f
    val textWidth = 184f
    kit.textEllipsized(c, name, textX, top + 31f, textWidth, 13f, kit.ink, true)
    val stockLabel = stock?.let { "Stok $it" } ?: "Stok bebas"
    kit.textEllipsized(
        c, "${formatRupiah(priceIdr)} · $stockLabel",
        textX, top + 51f, textWidth, 11f, kit.muted,
    )

    val toggle = RectF(286f, top + 10f, 358f, top + 40f)
    sellerSwitch(c, available, toggle.right, top + 25f)
    press(c, "productToggle:$id", toggle, 0f)
    bounds.putVisible(
        "productToggle:$id",
        RectF(toggle.left - 2f, toggle.top - 8f, toggle.right + 2f, toggle.bottom + 8f),
        bottomLimit = h - GLASS_TOP_OFFSET,
    )

    val edit = RectF(286f, top + 48f, 322f, top + 84f)
    kit.rounded(c, edit, kit.card, 10f, kit.border)
    drawIcon(c, "edit", edit.centerX(), edit.centerY(), 15f, kit.blue)
    press(c, "productEdit:$id", edit, 0f)
    bounds.putVisible(
        "productEdit:$id",
        RectF(edit.left - 6f, edit.top - 4f, edit.right + 2f, edit.bottom + 4f),
        bottomLimit = h - GLASS_TOP_OFFSET,
    )

    val delete = RectF(326f, top + 48f, 362f, top + 84f)
    kit.rounded(c, delete, kit.card, 10f, kit.dangerBorder)
    drawIcon(c, "trash", delete.centerX(), delete.centerY(), 15f, kit.danger)
    press(c, "productDelete:$id", delete, 0f)
    bounds.putVisible(
        "productDelete:$id",
        RectF(delete.left - 2f, delete.top - 4f, delete.right + 2f, delete.bottom + 4f),
        bottomLimit = h - GLASS_TOP_OFFSET,
    )

    if (!last) sellerDivider(c, top + height)
}

private fun JajaninView.drawProductImageUrl(c: Canvas, imageUrl: String?, rect: RectF) {
    val url = imageUrl?.takeIf { it.startsWith("https://") }
    val bitmap = url?.let { com.jajanin.app.core.ui.ProductImageCache.images[it] }
    if (bitmap == null) {
        kit.rounded(c, rect, kit.tint, 10f)
        kit.centerText(c, "J", rect, 22f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
        return
    }
    val clip = android.graphics.Path().apply {
        addRoundRect(rect, 10f, 10f, android.graphics.Path.Direction.CW)
    }
    val save = c.save()
    c.clipPath(clip)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    c.drawBitmap(bitmap, null, rect, paint)
    c.restoreToCount(save)
}

private fun JajaninView.drawProductEmpty(c: Canvas, top: Float, height: Float, h: Float) {
    if (top + height < 86f || top > h - GLASS_TOP_OFFSET) return
    sellerSurface(c, RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + height))
    drawIcon(c, "box", 44f, top + 34f, 22f, kit.blue)
    kit.text(c, "Belum ada produk", 66f, top + 30f, 13f, kit.ink, true)
    kit.textEllipsized(
        c, "Tambahkan menu pertama agar pembeli bisa memesan.",
        66f, top + 50f, 286f, 10.5f, kit.muted,
    )
    drawIcon(c, "plus", 30f, top + 88f, 12f, kit.blue)
    kit.text(c, "Tambah produk", 40f, top + 92f, 12f, kit.blue, true)
    bounds.putVisible(
        "productAdd",
        RectF(20f, top + 72f, 190f, top + 108f),
        bottomLimit = h - GLASS_TOP_OFFSET,
    )
}

private fun JajaninView.drawOrderFilters(c: Canvas, top: Float, pending: Int, h: Float) {
    if (top + SellerTokens.CONTROL_H < 86f || top > h - GLASS_TOP_OFFSET) return
    val labels = listOf("Semua", "Baru", "Disiapkan", "Siap", "Selesai")
    val segment = SellerTokens.WIDTH / labels.size
    labels.forEachIndexed { index, label ->
        val left = SellerTokens.PAD + index * segment
        val rect = RectF(left, top, left + segment, top + SellerTokens.CONTROL_H)
        val active = adminFilter == label
        val shown = kit.ellipsize(
            if (label == "Baru" && pending > 0) "Baru ($pending)" else label,
            segment - 12f, 10f, active,
        )
        val chip = RectF(left + 2f, top + 3f, left + segment - 2f, top + SellerTokens.CONTROL_H - 3f)
        if (active) {
            kit.rounded(c, chip, kit.tint, SellerTokens.RADIUS_PILL)
        } else {
            kit.rounded(c, chip, kit.card, SellerTokens.RADIUS_PILL, kit.border)
        }
        kit.centerText(c, shown, chip, 10f, if (active) kit.blue else kit.muted, active)
        press(c, "adminFilter:$label", rect, 0f)
        bounds.putVisible("adminFilter:$label", rect, bottomLimit = h - GLASS_TOP_OFFSET)
    }
}

private fun JajaninView.drawOrderCard(c: Canvas, order: AdminOrder, top: Float, height: Float, h: Float) {
    val card = RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + height)
    sellerSurface(c, card)
    bounds.putVisible("adminOrder:${order.id}", card, bottomLimit = h - GLASS_TOP_OFFSET)

    val stripe = when (order.status.lowercase()) {
        STATUS_PENDING -> kit.star
        STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.blue
        STATUS_READY, STATUS_COMPLETED -> kit.success
        else -> kit.border
    }
    kit.rect(c, 22f, top + 12f, 24f, top + height - 12f, stripe)

    val textX = 34f
    val time = order.timeLabel
    val timeWidth = kit.measure(time, 10.5f)
    kit.textEllipsized(
        c, order.buyerName, textX, top + 26f,
        SellerTokens.RIGHT - textX - timeWidth - 28f, 13f, kit.ink, true,
    )
    kit.textRight(c, time, 358f, top + 26f, timeWidth + 24f, 10.5f, kit.muted)

    val total = formatRupiah(order.totalIdr)
    val totalWidth = kit.measure(total, 12f, true)
    kit.textEllipsized(
        c, "${order.itemCount} item · ${order.itemsSummary}", textX, top + 47f,
        SellerTokens.RIGHT - textX - totalWidth - 28f, 10.5f, kit.muted,
    )
    kit.textRight(c, total, 358f, top + 47f, totalWidth + 24f, 12f, kit.ink, true)

    val (label, color) = statusLabel(order.status)
    val pillWidth = kit.measure(label, 10f, true) + 14f
    val metaMax = SellerTokens.RIGHT - textX - pillWidth - 22f
    val code = "Kode ${order.pickupCode}"
    val shownCode = kit.ellipsize(code, metaMax, 10.5f, true)
    kit.text(c, shownCode, textX, top + 68f, 10.5f, kit.blue, true)
    val shownCodeWidth = kit.measure(shownCode, 10.5f, true)
    if (shownCodeWidth + 46f < metaMax) {
        val payment = order.paymentMethod?.takeIf { it.isNotBlank() }?.uppercase() ?: "QRIS"
        kit.textEllipsized(c, " · $payment", textX + shownCodeWidth, top + 68f, metaMax - shownCodeWidth, 10.5f, kit.muted)
    }
    sellerPill(
        c, label, 358f, top + 67f,
        softBackground(order.status), color,
        height = 22f, size = 9.5f,
    )

    val nextAction = statusNextAction(order.status)
    if (nextAction != null) {
        val cancel = RectF(34f, top + 90f, 156f, top + 118f)
        kit.centerText(c, "Batalkan", cancel, 10.5f, kit.danger, true)
        press(c, "adminCancel:${order.id}", cancel, 0f)
        bounds.putVisible("adminCancel:${order.id}", cancel, bottomLimit = h - GLASS_TOP_OFFSET)

        val action = RectF(168f, top + 90f, 358f, top + 118f)
        kit.centerText(c, nextAction.first, action, 10.5f, kit.blue, true)
        drawIcon(c, "chevronRight", 350f, action.centerY(), 14f, kit.blue)
        press(c, "adminStatus:${order.id}", action, 0f)
        bounds.putVisible("adminStatus:${order.id}", action, bottomLimit = h - GLASS_TOP_OFFSET)
    }
}

private fun JajaninView.softBackground(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> kit.warnBox
    STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> kit.tint
    STATUS_READY, STATUS_COMPLETED -> kit.successSoft
    STATUS_CANCELLED -> kit.dangerBorder
    else -> kit.tint
}

private fun JajaninView.drawOrdersEmpty(c: Canvas, top: Float, h: Float) {
    if (top > h - GLASS_TOP_OFFSET) return
    when {
        adminLoading && adminOrders.isEmpty() -> kit.centerText(
            c, "Memuat pesanan...", RectF(24f, top, 366f, top + 60f), 13f, kit.muted,
        )
        adminError != null && adminOrders.isEmpty() -> kit.centerText(
            c, "Gagal memuat pesanan. Coba lagi.",
            RectF(24f, top, 366f, top + 60f), 12f, kit.danger,
        )
        else -> {
            val panel = RectF(SellerTokens.PAD, top, SellerTokens.RIGHT, top + 92f)
            sellerSurface(c, panel)
            drawIcon(c, "receipt", 44f, panel.centerY(), 19f, kit.muted)
            kit.text(
                c,
                if (adminOrders.isEmpty()) "Belum ada pesanan hari ini" else "Tidak ada pesanan di filter ini",
                66f, top + 40f, 12.5f, kit.ink, true,
            )
            kit.textEllipsized(
                c, "Pesanan baru akan muncul di sini secara otomatis.",
                66f, top + 60f, 280f, 10.5f, kit.muted,
            )
        }
    }
}

private fun JajaninView.drawDashboardHeader(c: Canvas) {
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, 80f, kit.bg)
    val name = myStallName.takeIf { it.isNotBlank() } ?: "Dashboard Penjual"
    kit.textEllipsized(c, name, 20f, 34f, 238f, 20f, kit.ink, true)
    kit.textEllipsized(c, adminDateFormat.format(java.util.Date()), 20f, 55f, 238f, 10.5f, kit.muted)

    drawIcon(c, "refresh", 288f, 40f, 18f, kit.muted)
    press(c, "adminRefresh", RectF(268f, 20f, 308f, 60f), 0f)
    bounds["adminRefresh"] = RectF(268f, 20f, 308f, 60f)

    drawIcon(c, "bell", 326f, 40f, 18f, kit.muted)
    press(c, "adminBell", RectF(308f, 20f, 344f, 60f), 0f)
    bounds["adminBell"] = RectF(308f, 20f, 344f, 60f)
    val notifCount = adminActionableCount()
    if (notifCount > 0) {
        sellerDot(c, 336f, 31f, kit.danger, 3.5f)
    }

    drawIcon(c, "person", 362f, 40f, 19f, kit.blue)
    press(c, "adminProfile", RectF(344f, 20f, 380f, 60f), 0f)
    bounds["adminProfile"] = RectF(344f, 20f, 380f, 60f)

    kit.line(c, SellerTokens.PAD, 80f, SellerTokens.RIGHT, 80f, kit.divider)
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
    val panelTop = 88f
    val lineH = 62f
    val panelBottom = minOf(panelTop + 48f + shown.size * lineH + 10f, h - 94f)
    sellerSurface(c, RectF(12f, panelTop, w - 12f, panelBottom))
    kit.text(c, "Notifikasi", SellerTokens.PAD, panelTop + 32f, 16f, kit.ink, true)

    val close = RectF(336f, panelTop + 10f, 376f, panelTop + 50f)
    drawIcon(c, "close", close.centerX(), close.centerY(), 16f, kit.muted)
    press(c, "adminNotifCloseBtn", close, 0f)
    bounds["adminNotifCloseBtn"] = RectF(close)
    kit.line(c, SellerTokens.PAD, panelTop + 44f, SellerTokens.RIGHT, panelTop + 44f, kit.divider)

    if (shown.isEmpty()) {
        kit.textEllipsized(c, "Belum ada notifikasi", SellerTokens.PAD, panelTop + 74f, 330f, 12f, kit.muted)
        return
    }
    shown.forEachIndexed { index, (order, isNew) ->
        val top = panelTop + 48f + index * lineH
        if (top + lineH > panelBottom) return@forEachIndexed
        sellerDot(c, 27f, top + 22f, if (isNew) kit.star else kit.blue, 3.5f)
        val title = if (isNew) "Pesanan baru · ${order.buyerName}" else "Bukti bayar · ${order.buyerName}"
        kit.textEllipsized(c, title, 40f, top + 21f, 300f, 11.5f, kit.ink, true)
        kit.textEllipsized(
            c,
            "Kode ${order.pickupCode} · ${order.timeLabel} · ${formatRupiah(order.totalIdr)}",
            40f, top + 40f, 306f, 10f, kit.muted,
        )
        if (index < shown.size - 1) sellerDivider(c, top + lineH, 20f, 370f)
        press(c, "adminNotifOrder:${order.id}", RectF(20f, top + 4f, 370f, top + lineH - 4f), 0f)
        bounds["adminNotifOrder:${order.id}"] = RectF(20f, top + 4f, 370f, top + lineH - 4f)
    }
}
