package com.jajanin.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

class JajaninFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "new_order") return
        val orderId = message.data["order_id"] ?: return
        val buyer = message.data["buyer_name"]?.takeIf { it.isNotBlank() } ?: "pembeli"
        val title = message.notification?.title ?: "Pesanan Baru"
        val body = message.notification?.body ?: "Ada pesanan baru dari $buyer"
        NotificationHelper.showNewOrder(this, orderId, title, body)
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
