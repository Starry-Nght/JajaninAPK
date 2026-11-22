package com.jajanin.app.feature.chat

import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.repository.ChatRepository
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val CHAT_PAGE_SIZE = 30
private const val PREF_MUTED_CONVOS = "muted_conversations"

internal fun MainActivity.openChatList() {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk membuka chat.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    ui.go(Screen.CHAT_LIST)
    refreshChatList(silent = false)
    refreshUnreadChats()
}

internal fun MainActivity.refreshChatList(silent: Boolean = false) {
    if (!SupabaseRepository.hasSession()) return
    chatListJob?.cancel()
    if (!silent && ui.chatConversations.isEmpty()) {
        ui.chatListLoading = true
        ui.chatListError = null
        ui.invalidate()
    }
    chatListJob = authScope.launch {
        try {
            val list = ChatRepository.listMyConversations(limit = CHAT_PAGE_SIZE, beforeIso = null)
            ui.chatConversations = list
            ui.chatHasMore = list.size >= CHAT_PAGE_SIZE
            ui.chatListError = null
            val unreadTotal = list.sumOf { it.unreadCount }
            val byOrder = list.mapNotNull { c ->
                val oid = c.orderId
                if (!oid.isNullOrBlank() && c.unreadCount > 0) oid to c.unreadCount else null
            }.toMap()
            ui.unreadChatTotal = unreadTotal
            ui.unreadChatByOrder = byOrder
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (!silent) {
                ui.chatListError = t.message ?: "Gagal memuat daftar percakapan."
            }
        } finally {
            ui.chatListLoading = false
            ui.chatLoadingMore = false
            ui.invalidate()
        }
    }
}

internal fun MainActivity.loadMoreConversations() {
    if (!SupabaseRepository.hasSession() || ui.chatLoadingMore || !ui.chatHasMore) return
    val oldest = ui.chatConversations.lastOrNull()?.sortTimestamp?.takeIf { it.isNotBlank() } ?: return
    ui.chatLoadingMore = true
    ui.invalidate()
    authScope.launch {
        try {
            val next = ChatRepository.listMyConversations(limit = CHAT_PAGE_SIZE, beforeIso = oldest)
            ui.chatHasMore = next.size >= CHAT_PAGE_SIZE
            if (next.isNotEmpty()) {
                val merged = LinkedHashMap<String, ConversationSummary>()
                ui.chatConversations.forEach { merged[it.id] = it }
                next.forEach { merged[it.id] = it }
                ui.chatConversations = merged.values.toList()
            }
        } catch (t: Throwable) {
            Toast.makeText(this@loadMoreConversations, t.message ?: "Gagal memuat percakapan lama.", Toast.LENGTH_SHORT).show()
        } finally {
            ui.chatLoadingMore = false
            ui.invalidate()
        }
    }
}

/** Sinkronisasi cepat daftar chat & lencana unread di latar belakang tanpa kedipan loading. */
internal suspend fun MainActivity.silentRefreshChatTick() {
    if (!SupabaseRepository.isConfigured() || !SupabaseRepository.hasSession()) return
    if (ui.currentScreen == Screen.CHAT_LIST && !ui.chatListLoading && !ui.chatLoadingMore) {
        runCatching {
            val list = ChatRepository.listMyConversations(limit = CHAT_PAGE_SIZE, beforeIso = null)
            val unreadTotal = list.sumOf { it.unreadCount }
            val byOrder = list.mapNotNull { c ->
                val oid = c.orderId
                if (!oid.isNullOrBlank() && c.unreadCount > 0) oid to c.unreadCount else null
            }.toMap()
            if (list != ui.chatConversations || unreadTotal != ui.unreadChatTotal || byOrder != ui.unreadChatByOrder) {
                ui.chatConversations = list
                ui.unreadChatTotal = unreadTotal
                ui.unreadChatByOrder = byOrder
                ui.invalidate()
            }
        }
    } else {
        runCatching {
            val snap = ChatRepository.fetchUnreadSnapshot()
            if (ui.unreadChatTotal != snap.totalUnread || ui.unreadChatByOrder != snap.byOrder) {
                ui.unreadChatTotal = snap.totalUnread
                ui.unreadChatByOrder = snap.byOrder
                ui.invalidate()
            }
        }
    }
}

internal fun MainActivity.refreshUnreadChats() {
    if (!SupabaseRepository.hasSession()) return
    authScope.launch {
        runCatching {
            val snap = ChatRepository.fetchUnreadSnapshot()
            val changed = ui.unreadChatTotal != snap.totalUnread || ui.unreadChatByOrder != snap.byOrder
            ui.unreadChatTotal = snap.totalUnread
            ui.unreadChatByOrder = snap.byOrder
            if (changed) ui.invalidate()
        }
    }
}

internal fun MainActivity.startChatObserver() {
    stopChatObserver()
    if (!SupabaseRepository.isConfigured() || !SupabaseRepository.hasSession()) return
    val uid = SupabaseRepository.currentUid() ?: return
    runCatching {
        chatObserver = ChatRepository.observeMyConversations(authScope, uid) {
            refreshUnreadChats()
            if (ui.currentScreen == Screen.CHAT_LIST || ui.chatConversations.isNotEmpty()) {
                refreshChatList(silent = true)
            }
        }
    }
    refreshUnreadChats()
    refreshChatList(silent = true)
}

internal fun MainActivity.stopChatObserver() {
    chatObserver?.close()
    chatObserver = null
}

/** Buka ruang chat berdasarkan `order_id` (buat otomatis bila belum ada). */
internal fun MainActivity.openOrderChat(orderId: String) {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk membuka chat.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    val cleanId = orderId.trim()
    if (cleanId.isEmpty()) return

    val cached = ui.chatConversations.firstOrNull { it.orderId == cleanId }
    if (cached != null) {
        showChatRoomDialog(cached)
        return
    }

    authScope.launch {
        try {
            val summary = ChatRepository.getOrCreateOrderConversation(cleanId)
            upsertLocalConversationSummary(summary)
            showChatRoomDialog(summary)
        } catch (t: Throwable) {
            Toast.makeText(this@openOrderChat, t.message ?: "Gagal membuka chat pesanan.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Buka ruang chat pra-pesanan berdasarkan `product_id` ("Tanya Penjual" di Detail Produk). */
internal fun MainActivity.openProductInquiryChat(productId: String) {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk bertanya ke penjual.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    val cleanId = productId.trim()
    if (cleanId.isEmpty()) return

    val cached = ui.chatConversations.firstOrNull {
        it.conversationType == "product_inquiry" && it.orderId == null && it.productId == cleanId
    }
    if (cached != null) {
        showChatRoomDialog(cached)
        return
    }

    authScope.launch {
        try {
            val summary = ChatRepository.getOrCreateProductConversation(cleanId)
            upsertLocalConversationSummary(summary)
            showChatRoomDialog(summary)
        } catch (t: Throwable) {
            Toast.makeText(this@openProductInquiryChat, t.message ?: "Gagal membuka chat produk.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Buka ruang chat berdasarkan `conversation_id` (dari daftar chat atau deep-link notifikasi push). */
internal fun MainActivity.openConversationById(conversationId: String) {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk membuka chat.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    val cleanId = conversationId.trim()
    if (cleanId.isEmpty()) return

    if (activeChatDialog?.isShowingFor(cleanId) == true) return

    val cached = ui.chatConversations.firstOrNull { it.id == cleanId }
    if (cached != null) {
        showChatRoomDialog(cached)
        return
    }

    authScope.launch {
        try {
            val summary = ChatRepository.fetchConversation(cleanId)
            upsertLocalConversationSummary(summary)
            showChatRoomDialog(summary)
        } catch (t: Throwable) {
            Toast.makeText(this@openConversationById, t.message ?: "Gagal membuka percakapan.", Toast.LENGTH_LONG).show()
        }
    }
}

internal fun MainActivity.showChatRoomDialog(summary: ConversationSummary) {
    if (activeChatDialog?.isShowingFor(summary.id) == true) return
    activeChatDialog?.dismiss()
    val dialog = ChatRoomDialog(this, summary)
    activeChatDialog = dialog
    dialog.show()
}

private fun MainActivity.upsertLocalConversationSummary(summary: ConversationSummary) {
    val list = ui.chatConversations.toMutableList()
    val idx = list.indexOfFirst { it.id == summary.id }
    if (idx >= 0) {
        list[idx] = summary
    } else {
        list.add(0, summary)
    }
    ui.chatConversations = list
    ui.invalidate()
}

internal fun MainActivity.isConversationMuted(conversationId: String): Boolean {
    val set = appPrefs().getStringSet(PREF_MUTED_CONVOS, emptySet()).orEmpty()
    return conversationId in set
}

internal fun MainActivity.toggleConversationMuted(conversationId: String): Boolean {
    val current = appPrefs().getStringSet(PREF_MUTED_CONVOS, emptySet()).orEmpty().toMutableSet()
    val nowMuted = if (conversationId in current) {
        current.remove(conversationId)
        false
    } else {
        current.add(conversationId)
        true
    }
    appPrefs().edit().putStringSet(PREF_MUTED_CONVOS, current).apply()
    return nowMuted
}
