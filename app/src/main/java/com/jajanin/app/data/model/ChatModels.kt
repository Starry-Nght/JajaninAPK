package com.jajanin.app.data.model

import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class MessageDeliveryState {
    SENDING,
    SENT,
    FAILED,
}

@Serializable
internal data class ChatLastMessageDto(
    val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    val body: String = "",
    @SerialName("message_type") val messageType: String = "text",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class ChatProductContextDto(
    val id: String? = null,
    val name: String = "Produk Jajanin",
    @SerialName("price_idr") val priceIdr: Int = 0,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("seller_name") val sellerName: String = "Warung Jajanin",
)

@Serializable
internal data class ChatOrderContextDto(
    val id: String = "",
    @SerialName("pickup_code") val pickupCode: String = "",
    val status: String = "pending",
    @SerialName("total_idr") val totalIdr: Int = 0,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    @SerialName("items_summary") val itemsSummary: String = "",
)

@Serializable
internal data class ConversationSummaryDto(
    val id: String,
    @SerialName("conversation_type") val conversationType: String = "order",
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("buyer_id") val buyerId: String = "",
    @SerialName("seller_id") val sellerId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("counterparty_id") val counterpartyId: String = "",
    @SerialName("counterparty_name") val counterpartyName: String = "Pengguna",
    @SerialName("counterparty_role") val counterpartyRole: String = "buyer",
    @SerialName("stall_name") val stallName: String = "",
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_message") val lastMessage: ChatLastMessageDto? = null,
    @SerialName("product_context") val productContext: ChatProductContextDto? = null,
    @SerialName("order_context") val orderContext: ChatOrderContextDto? = null,
)

@Serializable
internal data class ChatMessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("message_type") val messageType: String = "text",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class UnreadByOrderDto(
    @SerialName("order_id") val orderId: String,
    val unread: Int = 0,
)

@Serializable
internal data class TypingBroadcastPayload(
    @SerialName("user_id") val userId: String = "",
    @SerialName("is_typing") val isTyping: Boolean = false,
)

@Serializable
internal data class ReadReceiptBroadcastPayload(
    @SerialName("reader_id") val readerId: String = "",
    @SerialName("read_at") val readAt: String = "",
)

internal data class ChatProductContext(
    val id: String?,
    val name: String,
    val priceIdr: Int,
    val imageUrl: String?,
    val available: Boolean,
    val deleted: Boolean,
    val sellerName: String,
)

internal data class ChatOrderContext(
    val id: String,
    val pickupCode: String,
    val status: String,
    val totalIdr: Int,
    val paymentMethod: String?,
    val paymentStatus: String,
    val itemsSummary: String,
)

internal data class ConversationSummary(
    val id: String,
    val conversationType: String,
    val orderId: String?,
    val productId: String?,
    val buyerId: String,
    val sellerId: String,
    val createdAt: String,
    val updatedAt: String,
    val lastMessageAt: String?,
    val counterpartyId: String,
    val rawCounterpartyName: String,
    val counterpartyRole: String,
    val stallName: String,
    val unreadCount: Int,
    val lastMessageId: String,
    val lastMessageBody: String,
    val lastMessageSenderId: String,
    val lastMessageIsRead: Boolean,
    val lastMessageCreatedAt: String,
    val timeLabel: String,
    val productContext: ChatProductContext?,
    val orderContext: ChatOrderContext?,
) {
    val cleanCounterpartyName: String =
        SupabaseRepository.extractCleanName(rawCounterpartyName).ifBlank { rawCounterpartyName.ifBlank { "Pengguna" } }

    val counterpartyClass: String =
        SupabaseRepository.extractClass(rawCounterpartyName)

    val counterpartyNis: String =
        SupabaseRepository.extractNis(rawCounterpartyName)

    /** Judul utama baris chat sesuai perspektif viewer (Pembeli melihat nama penjual/warung, Penjual melihat nama pembeli). */
    fun displayTitle(viewerIsSeller: Boolean): String {
        return if (viewerIsSeller) {
            cleanCounterpartyName
        } else {
            val cleanStall = stallName.trim()
            when {
                cleanStall.isNotBlank() && !cleanStall.equals(cleanCounterpartyName, ignoreCase = true) ->
                    "$cleanStall · $cleanCounterpartyName"
                cleanStall.isNotBlank() -> cleanStall
                else -> cleanCounterpartyName
            }
        }
    }

    /** Sub-identitas lawan chat (kelas untuk siswa/pembeli, atau nama warung untuk penjual). */
    fun displaySubtitle(viewerIsSeller: Boolean): String {
        return if (viewerIsSeller) {
            when {
                counterpartyClass.isNotBlank() && counterpartyNis.isNotBlank() -> "$counterpartyClass · NIS $counterpartyNis"
                counterpartyClass.isNotBlank() -> counterpartyClass
                counterpartyNis.isNotBlank() -> "NIS $counterpartyNis"
                else -> "Pembeli"
            }
        } else {
            when {
                stallName.isNotBlank() && counterpartyClass.isNotBlank() -> "${stallName.trim()} · $counterpartyClass"
                stallName.isNotBlank() -> stallName.trim()
                counterpartyClass.isNotBlank() -> counterpartyClass
                else -> "Penjual"
            }
        }
    }

    val sortTimestamp: String
        get() = lastMessageAt?.takeIf { it.isNotBlank() }
            ?: lastMessageCreatedAt.takeIf { it.isNotBlank() }
            ?: updatedAt.takeIf { it.isNotBlank() }
            ?: createdAt
}

internal data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val messageType: String = "text",
    val isRead: Boolean = false,
    val readAt: String? = null,
    val createdAt: String = "",
    val timeLabel: String = "",
    val deliveryState: MessageDeliveryState = MessageDeliveryState.SENT,
    val localTempId: String? = null,
)

internal fun ConversationSummaryDto.toDomain(): ConversationSummary {
    val effectiveTime = lastMessage?.createdAt?.takeIf { it.isNotBlank() }
        ?: lastMessageAt?.takeIf { it.isNotBlank() }
        ?: updatedAt.takeIf { it.isNotBlank() }
        ?: createdAt
    return ConversationSummary(
        id = id,
        conversationType = conversationType,
        orderId = orderId?.takeIf { it.isNotBlank() },
        productId = productId?.takeIf { it.isNotBlank() },
        buyerId = buyerId,
        sellerId = sellerId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        counterpartyId = counterpartyId,
        rawCounterpartyName = counterpartyName,
        counterpartyRole = counterpartyRole,
        stallName = stallName,
        unreadCount = unreadCount.coerceAtLeast(0),
        lastMessageId = lastMessage?.id.orEmpty(),
        lastMessageBody = lastMessage?.body.orEmpty(),
        lastMessageSenderId = lastMessage?.senderId.orEmpty(),
        lastMessageIsRead = lastMessage?.isRead ?: false,
        lastMessageCreatedAt = lastMessage?.createdAt.orEmpty(),
        timeLabel = formatChatTimestamp(effectiveTime),
        productContext = productContext?.let {
            ChatProductContext(
                id = it.id?.takeIf { s -> s.isNotBlank() },
                name = it.name.ifBlank { "Produk Jajanin" },
                priceIdr = it.priceIdr,
                imageUrl = it.imageUrl?.takeIf { s -> s.isNotBlank() },
                available = it.isAvailable && !it.isDeleted,
                deleted = it.isDeleted,
                sellerName = SupabaseRepository.extractCleanName(it.sellerName).ifBlank { it.sellerName },
            )
        },
        orderContext = orderContext?.takeIf { it.id.isNotBlank() }?.let {
            ChatOrderContext(
                id = it.id,
                pickupCode = it.pickupCode,
                status = it.status,
                totalIdr = it.totalIdr,
                paymentMethod = it.paymentMethod,
                paymentStatus = it.paymentStatus,
                itemsSummary = it.itemsSummary,
            )
        },
    )
}

internal fun ChatMessageDto.toDomain(
    deliveryState: MessageDeliveryState = MessageDeliveryState.SENT,
    localTempId: String? = null,
): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
    messageType = messageType,
    isRead = isRead,
    readAt = readAt,
    createdAt = createdAt,
    timeLabel = formatChatBubbleTime(createdAt),
    deliveryState = deliveryState,
    localTempId = localTempId,
)

internal fun parseIsoUtcMillis(iso: String): Long? {
    val clean = iso.trim()
    if (clean.length < 19) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(clean.take(19))?.time
    }.getOrNull()
}

internal fun formatChatBubbleTime(createdAt: String): String {
    val millis = parseIsoUtcMillis(createdAt)
    if (millis != null) {
        return SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    }
    return createdAt.substringAfter('T', "").take(5)
}

internal fun formatChatTimestamp(iso: String): String {
    val millis = parseIsoUtcMillis(iso) ?: return iso.substringAfter('T', "").take(5)
    val now = System.currentTimeMillis()
    val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val itemDate = Date(millis)
    return if (todayFmt.format(itemDate) == todayFmt.format(Date(now))) {
        SimpleDateFormat("HH:mm", Locale.US).format(itemDate)
    } else {
        SimpleDateFormat("dd MMM, HH:mm", Locale("id", "ID")).format(itemDate)
    }
}
