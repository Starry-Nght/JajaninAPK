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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal object NotificationHelper {
    const val CHANNEL_ID = "jajanin_orders_v2"
    const val EXTRA_ORDER_ID = "order_id"
    private const val CHANNEL_NAME = "Pesanan & Status Order"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifikasi pesanan masuk untuk penjual & update status pesanan untuk pembeli"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
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

    suspend fun currentFcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
