package com.jajanin.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.jajanin.app.MainActivity
import com.jajanin.app.R
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal object NotificationHelper {
    const val CHANNEL_ID = "jajanin_orders_v2"
    const val CHANNEL_CHAT_ID = "jajanin_chat"
    const val EXTRA_ORDER_ID = "order_id"
    const val EXTRA_CONVERSATION_ID = "conversation_id"

    private const val CHANNEL_NAME = "Pesanan & Status Order"
    private const val CHANNEL_CHAT_NAME = "Chat Pembeli & Penjual"
    private const val PREFS_THEME = "jajanin_theme"
    private const val PREF_MUTED_CONVOS = "muted_conversations"

    /** ID percakapan yang sedang dibuka aktif di layar (untuk men-suppress notifikasi duplikat). */
    @Volatile
    var activeOpenConversationId: String? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi pesanan masuk untuk penjual & update status pesanan untuk pembeli"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(CHANNEL_CHAT_ID) == null) {
            val chatChannel = NotificationChannel(CHANNEL_CHAT_ID, CHANNEL_CHAT_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pesan chat langsung antara pembeli dan penjual"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(chatChannel)
        }
    }

    fun isConversationMuted(context: Context, conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(PREF_MUTED_CONVOS, emptySet()).orEmpty()
        return conversationId in set
    }

    fun cancelChatNotification(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel("chat_$conversationId".hashCode())
        }
    }

    fun showNewOrder(context: Context, orderId: String, title: String, body: String) {
        showOrderUpdate(context, orderId, orderId, title, body)
    }

    fun showOrderUpdate(
        context: Context,
        notificationKey: String,
        orderId: String,
        title: String,
        body: String,
    ) {
        ensureChannel(context)
        (context as? MainActivity)?.runOnUiThread {
            android.widget.Toast.makeText(context, "🔔 $title\n$body", android.widget.Toast.LENGTH_LONG).show()
        }
        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ORDER_ID, orderId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notifId = notificationKey.hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_orders)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS belum diberikan; Toast in-app di atas tetap tampil.
        }
    }

    fun showChatMessage(
        context: Context,
        conversationId: String,
        orderId: String?,
        rawSenderName: String,
        rawSubtitle: String,
        preview: String,
    ) {
        val cleanConvoId = conversationId.trim()
        if (cleanConvoId.isEmpty()) return
        // Jangan tampilkan notifikasi apabila pengguna sedang membuka percakapan ini atau membisukannya.
        if (activeOpenConversationId == cleanConvoId || isConversationMuted(context, cleanConvoId)) {
            return
        }
        ensureChannel(context)

        val cleanTitle = SupabaseRepository.extractCleanName(rawSenderName).ifBlank {
            rawSenderName.ifBlank { "Pesan Baru" }
        }
        val extractedClass = SupabaseRepository.extractClass(rawSenderName)
        val cleanSubtitle = rawSubtitle.trim().ifBlank { extractedClass }
        val cleanBody = preview.trim().ifBlank { "Mengirim pesan baru" }

        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CONVERSATION_ID, cleanConvoId)
            if (!orderId.isNullOrBlank()) {
                putExtra(EXTRA_ORDER_ID, orderId)
            }
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notifId = "chat_$cleanConvoId".hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_CHAT_ID)
        } else {
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }
        if (cleanSubtitle.isNotBlank()) {
            builder.setSubText(cleanSubtitle)
        }
        val displayBody = if (cleanSubtitle.isNotBlank()) "$cleanSubtitle · $cleanBody" else cleanBody
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_orders)
            .setContentTitle(cleanTitle)
            .setContentText(cleanBody)
            .setStyle(Notification.BigTextStyle().bigText(displayBody))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notifId, notification)
        } catch (e: SecurityException) {
            // Izin notifikasi belum diberikan.
        }
    }

    suspend fun currentFcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
