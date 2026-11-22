package com.jajanin.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.runBlocking

class JajaninFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "new_order" -> {
                val orderId = message.data["order_id"] ?: return
                val buyer = message.data["buyer_name"]?.takeIf { it.isNotBlank() } ?: "pembeli"
                val title = message.notification?.title ?: "Pesanan Baru"
                val body = message.notification?.body ?: "Ada pesanan baru dari $buyer"
                NotificationHelper.showNewOrder(this, orderId, title, body)
            }
            "chat_message" -> {
                val conversationId = message.data["conversation_id"]?.takeIf { it.isNotBlank() } ?: return
                val orderId = message.data["order_id"]?.takeIf { it.isNotBlank() }
                val senderName = message.data["sender_name"]?.takeIf { it.isNotBlank() }
                    ?: message.notification?.title
                    ?: "Pesan Baru"
                val subtitle = message.data["sender_subtitle"].orEmpty()
                val preview = message.data["message_preview"]?.takeIf { it.isNotBlank() }
                    ?: message.notification?.body
                    ?: "Ada pesan baru"
                NotificationHelper.showChatMessage(
                    context = this,
                    conversationId = conversationId,
                    orderId = orderId,
                    rawSenderName = senderName,
                    rawSubtitle = subtitle,
                    preview = preview,
                )
            }
        }
    }

    // runBlocking: panggilan FirebaseMessagingService wajib menyelesaikan kerjanya
    // sebelum metode kembali, atau proses bisa mati sebelum token terdaftar.
    override fun onNewToken(token: String) {
        runBlocking {
            runCatching {
                if (!SupabaseRepository.awaitLoggedIn()) return@runCatching
                val uid = SupabaseRepository.currentUid() ?: return@runCatching
                // Semua role didaftarkan (server menyaring penerima per jenis notif).
                OrderRepository.registerDevice(uid, token)
            }
        }
    }
}
