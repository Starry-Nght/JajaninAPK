package com.jajanin.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.runBlocking

class JajaninFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching { handleMessage(message) }
    }

    private fun handleMessage(message: RemoteMessage) {
        val data = message.data
        val type = data["type"]?.trim()?.lowercase().orEmpty()
        val notifTitle = data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title?.takeIf { it.isNotBlank() }
        val notifBody = data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body?.takeIf { it.isNotBlank() }

        when (type) {
            "new_order" -> {
                val orderId = data["order_id"]?.takeIf { it.isNotBlank() } ?: return
                val buyer = data["buyer_name"]?.takeIf { it.isNotBlank() } ?: "pembeli"
                val title = notifTitle ?: "Pesanan Baru"
                val body = notifBody ?: "Ada pesanan baru dari $buyer"
                val dedupKey = data["dedup_key"]?.takeIf { it.isNotBlank() } ?: "order:$orderId:new"
                NotificationHelper.showNewOrder(
                    context = this,
                    orderId = orderId,
                    title = title,
                    body = body,
                    dedupKey = dedupKey,
                    fromPush = true,
                )
            }
            "order_created",
            "order_status",
            "order_paid",
            "order_payment_update",
            "order_cancelled_by_buyer" -> {
                val orderId = data["order_id"]?.takeIf { it.isNotBlank() } ?: return
                val status = data["status"]?.trim()?.lowercase().orEmpty()
                val targetRole = data["target_role"]?.trim()?.lowercase().orEmpty()
                    .ifBlank { if (type == "order_cancelled_by_buyer") "seller" else "buyer" }
                val dedupKey = data["dedup_key"]?.takeIf { it.isNotBlank() }
                    ?: data["event_key"]?.takeIf { it.isNotBlank() }
                    ?: "order:$orderId:$type:$status"
                val title = notifTitle ?: "Pembaruan Pesanan"
                val body = notifBody ?: "Status pesananmu telah diperbarui."
                NotificationHelper.showOrderUpdate(
                    context = this,
                    notificationKey = dedupKey,
                    orderId = orderId,
                    title = title,
                    body = body,
                    targetRole = targetRole,
                    fromPush = true,
                )
            }
            "chat_message" -> {
                val conversationId = data["conversation_id"]?.takeIf { it.isNotBlank() } ?: return
                val orderId = data["order_id"]?.takeIf { it.isNotBlank() }
                val messageId = data["message_id"]?.takeIf { it.isNotBlank() }
                val targetRole = data["target_role"]?.trim()?.lowercase().orEmpty()
                val senderName = data["sender_name"]?.takeIf { it.isNotBlank() }
                    ?: notifTitle
                    ?: "Pesan Baru"
                val subtitle = data["sender_subtitle"].orEmpty()
                val preview = data["message_preview"]?.takeIf { it.isNotBlank() }
                    ?: notifBody
                    ?: "Ada pesan baru"
                NotificationHelper.showChatMessage(
                    context = this,
                    conversationId = conversationId,
                    orderId = orderId,
                    rawSenderName = senderName,
                    rawSubtitle = subtitle,
                    preview = preview,
                    messageId = messageId,
                    targetRole = targetRole,
                    fromPush = true,
                )
            }
            "store_activity",
            "stall_verification",
            "stall_visibility",
            "developer_warning",
            "developer_announcement",
            "seller_report_created",
            "seller_report_updated" -> {
                val dedupKey = data["dedup_key"]?.takeIf { it.isNotBlank() }
                    ?: data["event_key"]?.takeIf { it.isNotBlank() }
                    ?: "store:${System.currentTimeMillis() / 1000}"
                val title = notifTitle ?: "Aktivitas Toko Jajanin"
                val body = notifBody ?: "Ada pemberitahuan penting terkait toko atau akunmu."
                NotificationHelper.showStoreActivity(
                    context = this,
                    notificationKey = dedupKey,
                    title = title,
                    body = body,
                    stallId = data["stall_id"]?.takeIf { it.isNotBlank() },
                    targetRole = data["target_role"]?.trim()?.lowercase().orEmpty(),
                    targetScreen = data["target_screen"]?.trim()?.lowercase().orEmpty().ifBlank { "notif" },
                    fromPush = true,
                )
            }
            else -> {
                // Fallback aman bila server mengirim payload notifikasi umum di masa depan.
                if (!notifTitle.isNullOrBlank() || !notifBody.isNullOrBlank()) {
                    val dedupKey = data["dedup_key"]?.takeIf { it.isNotBlank() }
                        ?: data["event_key"]?.takeIf { it.isNotBlank() }
                        ?: "push:${message.messageId ?: System.currentTimeMillis()}"
                    NotificationHelper.showStoreActivity(
                        context = this,
                        notificationKey = dedupKey,
                        title = notifTitle ?: "Jajanin",
                        body = notifBody ?: "Ada pemberitahuan baru.",
                        stallId = data["stall_id"]?.takeIf { it.isNotBlank() },
                        targetRole = data["target_role"]?.trim()?.lowercase().orEmpty(),
                        targetScreen = data["target_screen"]?.trim()?.lowercase().orEmpty().ifBlank { "notif" },
                        fromPush = true,
                    )
                }
            }
        }
    }

    // runBlocking: FirebaseMessagingService wajib menyelesaikan sinkronisasi token sebelum
    // kembali agar proses tidak dihentikan sistem sebelum token tersimpan di Supabase.
    override fun onNewToken(token: String) {
        val cleanToken = token.trim()
        if (cleanToken.length < 20) return
        NotificationHelper.cacheFcmToken(applicationContext, cleanToken)
        runBlocking {
            runCatching {
                if (!SupabaseRepository.isConfigured()) return@runCatching
                val (_, uid) = SupabaseRepository.requireFreshSessionAuth()
                OrderRepository.registerDevice(
                    userId = uid,
                    fcmToken = cleanToken,
                    deviceLabel = NotificationHelper.deviceLabel(),
                )
            }
        }
    }
}
