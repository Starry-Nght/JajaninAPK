package com.jajanin.app.data.repository

import com.jajanin.app.data.model.ChatMessage
import com.jajanin.app.data.model.ChatMessageDto
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.model.ConversationSummaryDto
import com.jajanin.app.data.model.TypingBroadcastPayload
import com.jajanin.app.data.model.UnreadByOrderDto
import com.jajanin.app.data.model.toDomain
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class UnreadChatSnapshot(
    val totalUnread: Int = 0,
    val byOrder: Map<String, Int> = emptyMap(),
)

internal class ChatRoomSession internal constructor(
    val conversationId: String,
    private val scope: CoroutineScope,
) {
    internal var channel: RealtimeChannel? = null
    internal var job: Job? = null
    private var lastTypingSentAtMs: Long = 0L
    @Volatile private var closed = false

    fun isClosed(): Boolean = closed

    /**
     * Kirim sinyal ephemeral sedang mengetik lewat Supabase Realtime Broadcast
     * (dibatasi paling sering 1x per 1.8 detik, nol penulisan database).
     */
    fun notifyTyping(currentUserId: String, isTyping: Boolean = true) {
        if (closed || currentUserId.isBlank()) return
        val ch = channel ?: return
        if (ch.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        val now = System.currentTimeMillis()
        if (isTyping && now - lastTypingSentAtMs < 1_800L) return
        lastTypingSentAtMs = if (isTyping) now else 0L
        scope.launch {
            runCatching {
                ch.broadcast(
                    event = "typing",
                    message = buildJsonObject {
                        put("user_id", currentUserId)
                        put("is_typing", isTyping)
                    },
                )
            }
        }
    }

    fun close(): Job {
        closed = true
        job?.cancel()
        val ch = channel
        channel = null
        return scope.launch {
            if (ch != null) {
                runCatching { SupabaseRepository.client.realtime.removeChannel(ch) }
            }
        }
    }
}

internal object ChatRepository {

    private val client get() = SupabaseRepository.client

    /** Ambil atau buat percakapan berbasis pesanan (`conversation_type = 'order'`). */
    suspend fun getOrCreateOrderConversation(orderId: String): ConversationSummary {
        val cleanId = orderId.trim()
        require(cleanId.isNotEmpty()) { "ID pesanan tidak valid." }
        return try {
            val dto = client.postgrest.rpc(
                "get_or_create_conversation",
                buildJsonObject { put("p_order_id", cleanId) },
            ).decodeAs<ConversationSummaryDto>()
            dto.toDomain()
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /** Ambil atau buat percakapan pra-pesanan dari halaman detail produk (`conversation_type = 'product_inquiry'`). */
    suspend fun getOrCreateProductConversation(productId: String): ConversationSummary {
        val cleanId = productId.trim()
        require(cleanId.isNotEmpty()) { "ID produk tidak valid." }
        return try {
            val dto = client.postgrest.rpc(
                "get_or_create_product_conversation",
                buildJsonObject { put("p_product_id", cleanId) },
            ).decodeAs<ConversationSummaryDto>()
            dto.toDomain()
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /** Ambil detail satu percakapan beserta konteks produk/pesanan terbaru. */
    suspend fun fetchConversation(conversationId: String): ConversationSummary {
        val cleanId = conversationId.trim()
        require(cleanId.isNotEmpty()) { "ID percakapan tidak valid." }
        return try {
            val dto = client.postgrest.rpc(
                "fetch_conversation",
                buildJsonObject { put("p_conversation_id", cleanId) },
            ).decodeAs<ConversationSummaryDto>()
            dto.toDomain()
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /**
     * Daftar percakapan milik user yang sedang login, diurutkan dari pesan terbaru
     * dengan dukungan cursor pagination (`beforeIso`).
     */
    suspend fun listMyConversations(
        limit: Int = 30,
        beforeIso: String? = null,
    ): List<ConversationSummary> {
        val params = buildJsonObject {
            put("p_limit", limit.coerceIn(1, 100))
            if (!beforeIso.isNullOrBlank()) {
                put("p_before", beforeIso)
            }
        }
        return try {
            client.postgrest.rpc("list_my_conversations", params)
                .decodeList<ConversationSummaryDto>()
                .map { it.toDomain() }
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /**
     * Ambil halaman pesan untuk satu percakapan (terbaru lebih dulu di DB, dikembalikan urut kronologis).
     * Bila `beforeIso` diberikan, memuat pesan yang lebih lama dari timestamp tersebut.
     */
    suspend fun fetchMessages(
        conversationId: String,
        limit: Int = 45,
        beforeIso: String? = null,
    ): List<ChatMessage> {
        val cleanId = conversationId.trim()
        require(cleanId.isNotEmpty()) { "ID percakapan tidak valid." }
        val params = buildJsonObject {
            put("p_conversation_id", cleanId)
            put("p_limit", limit.coerceIn(1, 200))
            if (!beforeIso.isNullOrBlank()) {
                put("p_before", beforeIso)
            }
        }
        return try {
            client.postgrest.rpc("fetch_messages_page", params)
                .decodeList<ChatMessageDto>()
                .map { it.toDomain() }
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /** Kirim pesan teks (1..500 karakter setelah trim) lewat RPC `send_message`. */
    suspend fun sendMessage(
        conversationId: String,
        body: String,
        localTempId: String? = null,
    ): ChatMessage {
        val cleanId = conversationId.trim()
        val cleanBody = body.trim()
        require(cleanId.isNotEmpty()) { "Percakapan tidak valid." }
        require(cleanBody.isNotEmpty()) { "Pesan tidak boleh kosong." }
        require(cleanBody.length <= 500) { "Pesan maksimal 500 karakter." }
        return try {
            val dto = client.postgrest.rpc(
                "send_message",
                buildJsonObject {
                    put("p_conversation_id", cleanId)
                    put("p_body", cleanBody)
                },
            ).decodeAs<ChatMessageDto>()
            dto.toDomain(localTempId = localTempId)
        } catch (t: Throwable) {
            throw IllegalStateException(parseChatError(t), t)
        }
    }

    /** Tandai seluruh pesan masuk dari lawan bicara di percakapan ini sebagai sudah dibaca. */
    suspend fun markConversationRead(conversationId: String): Int = runCatching {
        val cleanId = conversationId.trim()
        if (cleanId.isEmpty()) return@runCatching 0
        client.postgrest.rpc(
            "mark_conversation_read",
            buildJsonObject { put("p_conversation_id", cleanId) },
        ).decodeAs<Int>()
    }.getOrDefault(0)

    /** Ambil total pesan belum dibaca & peta jumlah unread per `order_id`. */
    suspend fun fetchUnreadSnapshot(): UnreadChatSnapshot = coroutineScope {
        val totalDeferred = async {
            runCatching { client.postgrest.rpc("my_unread_chats").decodeAs<Int>() }.getOrDefault(0)
        }
        val byOrderDeferred = async {
            runCatching {
                client.postgrest.rpc("unread_by_order")
                    .decodeList<UnreadByOrderDto>()
                    .filter { it.orderId.isNotBlank() && it.unread > 0 }
                    .associate { it.orderId to it.unread }
            }.getOrDefault(emptyMap())
        }
        UnreadChatSnapshot(
            totalUnread = totalDeferred.await().coerceAtLeast(0),
            byOrder = byOrderDeferred.await(),
        )
    }

    /**
     * Langganan Realtime khusus untuk 1 ruang percakapan yang sedang dibuka:
     * - `INSERT` pada `public.chat_messages` dengan filter `conversation_id = eq.<conversationId>`
     * - `UPDATE` pada `public.chat_messages` dengan filter `conversation_id = eq.<conversationId>` (read receipt `is_read` / `read_at`)
     * - Broadcast ephemeral `"typing"` untuk indikator sedang mengetik
     */
    fun observeConversationRoom(
        scope: CoroutineScope,
        conversationId: String,
        currentUserId: String,
        previousCleanup: Job? = null,
        onMessageInserted: (ChatMessage) -> Unit,
        onMessageUpdated: (ChatMessage) -> Unit,
        onPartnerTyping: (Boolean) -> Unit,
        onConnectionStatusChanged: (RealtimeChannel.Status) -> Unit = {},
    ): ChatRoomSession {
        val topic = "chat-room-$conversationId"
        val session = ChatRoomSession(conversationId, scope)
        session.job = scope.launch {
            runCatching { previousCleanup?.join() }
            client.realtime.subscriptions.values
                .filter { it.topic.endsWith(topic) }
                .toList()
                .forEach { old -> runCatching { client.realtime.removeChannel(old) } }
            if (session.isClosed()) return@launch

            val channel = client.realtime.channel(topic)
            session.channel = channel

            val insertFlow = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
                table = "chat_messages"
                filter("conversation_id", FilterOperator.EQ, conversationId)
            }
            val updateFlow = channel.postgresChangeFlow<PostgresAction.Update>("public") {
                table = "chat_messages"
                filter("conversation_id", FilterOperator.EQ, conversationId)
            }
            val typingFlow = channel.broadcastFlow<TypingBroadcastPayload>("typing")

            launch {
                runCatching {
                    insertFlow.collect { action ->
                        val dto = runCatching { action.decodeRecord<ChatMessageDto>() }.getOrNull() ?: return@collect
                        if (dto.conversationId == conversationId) {
                            onMessageInserted(dto.toDomain())
                        }
                    }
                }
            }

            launch {
                runCatching {
                    updateFlow.collect { action ->
                        val dto = runCatching { action.decodeRecord<ChatMessageDto>() }.getOrNull() ?: return@collect
                        if (dto.conversationId == conversationId) {
                            onMessageUpdated(dto.toDomain())
                        }
                    }
                }
            }

            launch {
                runCatching {
                    typingFlow.collect { payload ->
                        if (payload.userId.isNotBlank() && payload.userId != currentUserId) {
                            onPartnerTyping(payload.isTyping)
                        }
                    }
                }
            }

            launch {
                runCatching {
                    channel.status.collect { st ->
                        onConnectionStatusChanged(st)
                    }
                }
            }

            runCatching { client.realtime.connect() }
            runCatching { channel.subscribe(blockUntilSubscribed = false) }
        }
        return session
    }

    /**
     * Langganan Realtime ringan untuk daftar percakapan & badge unread milik user yang sedang login.
     * RLS pada `public.conversations` dan `public.chat_messages` menjamin hanya perubahan milik partisipan
     * yang dikirimkan ke klien ini.
     */
    fun observeMyConversations(
        scope: CoroutineScope,
        userId: String,
        onChanged: () -> Unit,
    ): OrderObserver {
        val topic = "chat-list-${userId.take(12)}"
        val channel = client.realtime.channel(topic)
        val convoFlow = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "conversations"
        }
        val msgFlow = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "chat_messages"
        }
        val job = scope.launch {
            client.realtime.subscriptions.values
                .filter { it !== channel && it.topic.endsWith(topic) }
                .toList()
                .forEach { old -> runCatching { client.realtime.removeChannel(old) } }
            launch { runCatching { convoFlow.collect { onChanged() } } }
            launch { runCatching { msgFlow.collect { onChanged() } } }
            runCatching { client.realtime.connect() }
            runCatching { channel.subscribe(blockUntilSubscribed = false) }
        }
        return OrderObserver(channel, job, scope)
    }

    private fun parseChatError(t: Throwable): String {
        val raw = t.message.orEmpty()
        val detail = Regex("""message=([^\n,]+)""").find(raw)?.groupValues?.getOrNull(1)?.trim()
        return when {
            !detail.isNullOrBlank() -> detail
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("SocketTimeout", ignoreCase = true) ->
                "Koneksi internet bermasalah. Periksa jaringan lalu coba lagi."
            else -> SupabaseRepository.errorMessage(t)
        }
    }
}
