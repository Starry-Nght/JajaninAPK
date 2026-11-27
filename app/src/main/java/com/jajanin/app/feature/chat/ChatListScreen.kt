package com.jajanin.app.feature.chat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.model.formatChatPreviewBody
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.seller.dashboard.drawAdminNav

private const val HEADER_H = 60f
private const val NAV_HEIGHT = 82f

internal fun JajaninView.drawChatList(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)

    val viewerIsSeller = myRole == "seller"
    val myUid = SupabaseRepository.currentUid().orEmpty()
    val off = -scroll
    val bottomClip = h - NAV_HEIGHT
    fun visible(top: Float, bottom: Float): Boolean = bottom >= HEADER_H && top <= bottomClip

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 72f + off

    // 1. Deduplikasi 1 user = 1 percakapan & Filter Chips ("Semua", "Belum Dibaca", "Pesanan")
    val unifiedConversations = chatConversations.distinctBy { it.counterpartyId.ifBlank { it.id } }
    val filters = listOf("Semua", "Belum Dibaca", "Pesanan")
    val activeFilter = if (chatListFilter in filters) chatListFilter else "Semua"

    var chipX = 20f
    filters.forEach { filter ->
        val count = when (filter) {
            "Belum Dibaca" -> unifiedConversations.count { it.unreadCount > 0 }
            "Pesanan" -> unifiedConversations.count { it.orderContext != null }
            else -> unifiedConversations.size
        }
        val label = if (count > 0) "$filter ($count)" else filter
        val chipW = kit.measure(label, 10f, true) + 18f
        if (chipX > 20f && chipX + chipW > 370f) {
            chipX = 20f
            y += 30f
        }
        val chipRect = RectF(chipX, y, chipX + chipW, y + 26f)
        val isSel = activeFilter == filter
        if (visible(chipRect.top, chipRect.bottom)) {
            kit.rounded(c, chipRect, if (isSel) kit.blue else kit.card, 6f, if (isSel) null else kit.border)
            kit.centerText(c, label, chipRect, 10f, if (isSel) Color.WHITE else kit.muted, true)
            bounds.putVisible("chatFilter:$filter", chipRect, HEADER_H, bottomClip)
            press(c, "chatFilter:$filter", chipRect, 6f)
        }
        chipX += chipW + 6f
    }
    y += 36f

    val filtered = unifiedConversations.filter { convo ->
        when (activeFilter) {
            "Belum Dibaca" -> convo.unreadCount > 0
            "Pesanan" -> convo.orderContext != null
            else -> true
        }
    }

    when {
        chatListLoading && chatConversations.isEmpty() -> {
            kit.centerText(c, "Memuat daftar percakapan...", RectF(24f, 240f, 366f, 290f), 13f, kit.muted)
            contentHeight = 0f
        }
        chatListError != null && chatConversations.isEmpty() -> {
            kit.centerText(c, "Gagal memuat percakapan", RectF(24f, 224f, 366f, 258f), 14f, kit.danger, true)
            kit.centerText(c, (chatListError ?: "").take(64), RectF(24f, 256f, 366f, 286f), 11f, kit.muted)
            val retry = RectF(125f, 300f, 265f, 338f)
            kit.rounded(c, retry, kit.blue, 8f)
            kit.centerText(c, "Coba Lagi", retry, 12.5f, Color.WHITE, true)
            bounds.putVisible("chatRetry", retry, HEADER_H, bottomClip)
            press(c, "chatRetry", retry, 8f)
            contentHeight = 0f
        }
        filtered.isEmpty() -> {
            val emptyCard = RectF(24f, y + 28f, 366f, y + 168f)
            if (visible(emptyCard.top, emptyCard.bottom)) {
                kit.rounded(c, emptyCard, kit.card, 12f, kit.border)
                val iconCircle = RectF(173f, emptyCard.top + 18f, 217f, emptyCard.top + 62f)
                kit.rounded(c, iconCircle, kit.tint, 22f)
                drawIcon(c, "chat", iconCircle.centerX(), iconCircle.centerY(), 20f, kit.blue)
                val emptyTitle = if (activeFilter == "Semua") {
                    "Belum ada percakapan"
                } else {
                    "Tidak ada chat di kategori '$activeFilter'"
                }
                kit.centerText(c, emptyTitle, RectF(36f, emptyCard.top + 72f, 354f, emptyCard.top + 96f), 13.5f, kit.ink, true)
                val emptyHint = if (viewerIsSeller) {
                    "Pertanyaan produk & chat pesanan dari pembeli akan muncul di sini."
                } else {
                    "Ketuk 'Tanya Penjual' di detail produk atau 'Chat Penjual' di pesananmu."
                }
                kit.centerText(c, emptyHint, RectF(32f, emptyCard.top + 98f, 358f, emptyCard.top + 132f), 10.5f, kit.muted)
            }
            contentHeight = 0f
        }
        else -> {
            filtered.forEach { convo ->
                y = drawConversationItem(c, convo, y, viewerIsSeller, myUid, ::visible, bottomClip)
                y += 10f
            }

            if (chatHasMore && activeFilter == "Semua") {
                val moreBtn = RectF(95f, y + 4f, 295f, y + 38f)
                if (visible(moreBtn.top, moreBtn.bottom)) {
                    kit.rounded(c, moreBtn, kit.card, 8f, kit.border)
                    val moreText = if (chatLoadingMore) "Memuat..." else "Muat percakapan lama"
                    kit.centerText(c, moreText, moreBtn, 11.5f, kit.blue, true)
                    if (!chatLoadingMore) {
                        bounds.putVisible("chatLoadMore", moreBtn, HEADER_H, bottomClip)
                        press(c, "chatLoadMore", moreBtn, 8f)
                    }
                }
                y += 48f
            }
            contentHeight = (y - off) + GLASS_TOP_OFFSET + 20f
        }
    }

    c.restoreToCount(bodySave)

    // Fixed Top Header
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    val headerTitle = if (viewerIsSeller) "Chat Pembeli" else "Chat Penjual"
    kit.text(c, headerTitle, 20f, 38f, 19f, kit.ink, true)

    if (unreadChatTotal > 0) {
        val badgeText = "$unreadChatTotal belum dibaca"
        val bw = kit.measure(badgeText, 9.5f, true) + 16f
        val titleW = kit.measure(headerTitle, 19f, true)
        val pill = RectF(28f + titleW, 21f, 28f + titleW + bw, 41f)
        kit.rounded(c, pill, kit.tint, 10f)
        kit.centerText(c, badgeText, pill, 9.5f, kit.blue, true)
    }

    val refreshRect = RectF(336f, 14f, 372f, 50f)
    drawIcon(c, "refresh", refreshRect.centerX(), refreshRect.centerY(), 16f, kit.ink)
    bounds["chatRefresh"] = refreshRect
    press(c, "chatRefresh", refreshRect, 8f)

    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    c.restore()

    if (viewerIsSeller) {
        drawAdminNav(c, w, h, "Chat")
    } else {
        drawBottomNav(c, w, h, "Chat")
    }
}

private fun JajaninView.drawConversationItem(
    c: Canvas,
    convo: ConversationSummary,
    top: Float,
    viewerIsSeller: Boolean,
    myUid: String,
    visible: (Float, Float) -> Boolean,
    bottomClip: Float,
): Float {
    val cardH = 88f
    val card = RectF(20f, top, 370f, top + cardH)
    val hasUnread = convo.unreadCount > 0
    val isOnline = convo.counterpartyId.isNotBlank() && convo.counterpartyId in onlineUserIds

    if (visible(card.top, card.bottom)) {
        kit.rounded(
            c,
            card,
            kit.card,
            10f,
            if (hasUnread) kit.blue else kit.border,
        )
        if (hasUnread) {
            kit.rect(c, 20f, top + 10f, 23.5f, top + cardH - 10f, kit.blue)
        }

        // Avatar + Online Dot
        val av = RectF(32f, top + 16f, 74f, top + 58f)
        kit.rounded(c, av, kit.tint, 11f)
        val title = convo.displayTitle(viewerIsSeller)
        val initial = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "J"
        kit.centerText(c, initial, av, 16f, kit.blue, true)
        if (isOnline) {
            kit.rounded(
                c,
                RectF(av.right - 9f, av.bottom - 9f, av.right + 2f, av.bottom + 2f),
                kit.success,
                5.5f,
                kit.card,
            )
        }

        // Row 1: Counterparty Title + Time Label
        val timeText = convo.timeLabel
        val timeW = kit.measure(timeText, 10f, hasUnread)
        val titleMaxW = (356f - 86f - timeW - 10f).coerceAtLeast(110f)
        kit.textEllipsized(c, title, 86f, top + 27f, titleMaxW, 13.5f, kit.ink, true)
        kit.textRight(
            c,
            timeText,
            356f,
            top + 26f,
            timeW + 8f,
            10f,
            if (hasUnread) kit.blue else kit.muted,
            hasUnread,
        )

        // Row 2: Order or Product Context Pill
        val orderCtx = convo.orderContext
        val prodCtx = convo.productContext
        val (ctxLabel, ctxBg, ctxFg) = when {
            orderCtx != null -> {
                val stShort = statusLabelShort(orderCtx.status)
                val summary = orderCtx.itemsSummary.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Triple("Pesanan #${orderCtx.pickupCode} ($stShort)$summary", kit.tint, kit.blue)
            }
            prodCtx != null -> {
                val statusPrefix = when {
                    prodCtx.deleted -> "Produk dihapus: "
                    !prodCtx.available -> "Stok habis: "
                    else -> "Tanya Produk: "
                }
                val priceSuffix = if (prodCtx.priceIdr > 0) " · ${formatRupiah(prodCtx.priceIdr)}" else ""
                val fg = if (prodCtx.deleted || !prodCtx.available) kit.danger else kit.blue
                val bg = if (prodCtx.deleted || !prodCtx.available) kit.dangerBorder else kit.tint
                Triple("$statusPrefix${prodCtx.name}$priceSuffix", bg, fg)
            }
            else -> Triple(convo.displaySubtitle(viewerIsSeller), kit.tint, kit.muted)
        }
        val pillMaxW = 262f
        val clippedCtx = ctxLabel.take(44)
        val pillW = (kit.measure(clippedCtx, 9.5f, true) + 12f).coerceAtMost(pillMaxW)
        val ctxRect = RectF(86f, top + 34f, 86f + pillW, top + 51f)
        kit.rounded(c, ctxRect, ctxBg, 4.5f)
        kit.textEllipsized(c, ctxLabel, ctxRect.left + 6f, ctxRect.top + 12f, pillW - 12f, 9.5f, ctxFg, true)

        // Row 3: Last Message Preview + Read Receipt Vector Icon + Unread Counter Badge
        val rawPreview = formatChatPreviewBody(convo.lastMessageBody, convo.lastMessageType)
        val sentByMe = rawPreview.isNotEmpty() && convo.lastMessageSenderId == myUid
        var previewStartX = 86f
        if (sentByMe) {
            val iconKey = if (convo.lastMessageIsRead) "checkDouble" else "check"
            val iconClr = if (convo.lastMessageIsRead) kit.blue else kit.muted
            AppIcon.draw(c, iconKey, 92.5f, top + 67f, 13f, iconClr)
            previewStartX = 103f
        }
        val previewText = when {
            rawPreview.isEmpty() -> "Belum ada pesan · Ketuk untuk mulai chat"
            sentByMe -> "Anda: $rawPreview"
            else -> rawPreview
        }
        val previewMaxW = (if (hasUnread) 318f else 352f) - previewStartX
        kit.textEllipsized(
            c,
            previewText,
            previewStartX,
            top + 71f,
            previewMaxW,
            11.5f,
            if (hasUnread) kit.ink else kit.muted,
            hasUnread,
        )

        if (hasUnread) {
            val badgeLabel = if (convo.unreadCount > 99) "99+" else convo.unreadCount.toString()
            val bw = (kit.measure(badgeLabel, 9.5f, true) + 12f).coerceAtLeast(20f)
            val badgeRect = RectF(356f - bw, top + 56f, 356f, top + 76f)
            kit.rounded(c, badgeRect, kit.blue, 10f)
            kit.centerText(c, badgeLabel, badgeRect, 9.5f, Color.WHITE, true)
        }

        bounds.putVisible("chatConvo:${convo.id}", card, HEADER_H, bottomClip)
        press(c, "chatConvo:${convo.id}", card, 10f)
    }

    return top + cardH
}
