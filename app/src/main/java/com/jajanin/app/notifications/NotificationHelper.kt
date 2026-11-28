package com.jajanin.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.jajanin.app.MainActivity
import com.jajanin.app.R
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.refreshMyOrders
import com.jajanin.app.feature.chat.refreshChatList
import com.jajanin.app.feature.chat.refreshUnreadChats
import com.jajanin.app.feature.inbox.refreshInbox
import com.jajanin.app.feature.seller.refreshAdminOrders
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

internal object NotificationHelper {
    // 3 Android Notification Channels (aligned 1:1 with Supabase Edge Functions & AndroidManifest)
    const val CHANNEL_ORDERS_ID = "jajanin_orders_v2"
    const val CHANNEL_ID = CHANNEL_ORDERS_ID
    const val CHANNEL_CHAT_ID = "jajanin_chat"
    const val CHANNEL_STORE_ID = "jajanin_store_activity"

    // Deep-link Intent extra keys (also match FCM `data` payload keys for background OS taps)
    const val EXTRA_ORDER_ID = "order_id"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_STALL_ID = "stall_id"
    const val EXTRA_TARGET_ROLE = "target_role"
    const val EXTRA_TARGET_SCREEN = "target_screen"
    const val EXTRA_EVENT_TYPE = "type"

    private const val CHANNEL_ORDERS_NAME = "Pesanan"
    private const val CHANNEL_CHAT_NAME = "Chat"
    private const val CHANNEL_STORE_NAME = "Aktivitas Toko"

    private const val PREFS_THEME = "jajanin_theme"
    private const val PREF_MUTED_CONVOS = "muted_conversations"
    private const val PREFS_FCM = "jajanin_fcm_state"
    private const val PREF_CACHED_TOKEN = "cached_fcm_token"

    private const val DEDUP_WINDOW_MS = 45_000L
    private const val DEDUP_MAX_ENTRIES = 160

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentKeysLock = Any()
    private val recentNotificationKeys = LinkedHashMap<String, Long>(64, 0.75f, true)

    /** ID percakapan yang sedang dibuka aktif di layar (untuk men-suppress notifikasi duplikat). */
    @Volatile
    var activeOpenConversationId: String? = null

    /** Status apakah MainActivity sedang berada di foreground (aktif di layar). */
    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    private var activeActivityRef: WeakReference<MainActivity>? = null

    fun attachActivity(activity: MainActivity) {
        activeActivityRef = WeakReference(activity)
    }

    fun detachActivity(activity: MainActivity) {
        if (activeActivityRef?.get() === activity) {
            activeActivityRef = null
        }
    }

    fun activeActivityOrNull(): MainActivity? {
        val act = activeActivityRef?.get() ?: return null
        if (act.isFinishing || act.isDestroyed) return null
        return act
    }

    /** Membuat/memastikan seluruh 3 Notification Channel (Pesanan, Chat, Aktivitas Toko). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return@runCatching

            val channels = listOf(
                NotificationChannel(
                    CHANNEL_ORDERS_ID,
                    CHANNEL_ORDERS_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Notifikasi pesanan masuk untuk penjual dan pembaruan status pesanan untuk pembeli"
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(
                    CHANNEL_CHAT_ID,
                    CHANNEL_CHAT_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Pesan chat langsung antara pembeli dan penjual"
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(
                    CHANNEL_STORE_ID,
                    CHANNEL_STORE_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Notifikasi penting terkait warung, verifikasi, peringatan, dan pengumuman"
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            channels.forEach { ch ->
                // createNotificationChannel bersifat idempotent & memperbarui nama/deskripsi bila berubah.
                manager.createNotificationChannel(ch)
            }
        }
    }

    /**
     * Deduplikasi in-memory agar event yang sama (mis. diterima lewat FCM sekaligus
     * Realtime/polling lokal dalam rentang 45 detik) hanya tampil 1 kali.
     * Mengembalikan `true` jika notifikasi boleh ditampilkan.
     */
    fun claimNotificationKey(dedupKey: String, windowMs: Long = DEDUP_WINDOW_MS): Boolean {
        val clean = dedupKey.trim()
        if (clean.isEmpty()) return true
        val now = System.currentTimeMillis()
        synchronized(recentKeysLock) {
            val prev = recentNotificationKeys[clean]
            if (prev != null && now - prev < windowMs) {
                return false
            }
            recentNotificationKeys[clean] = now
            if (recentNotificationKeys.size > DEDUP_MAX_ENTRIES) {
                val it = recentNotificationKeys.entries.iterator()
                while (it.hasNext() && recentNotificationKeys.size > DEDUP_MAX_ENTRIES) {
                    it.next()
                    it.remove()
                }
            }
            return true
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

    fun showNewOrder(
        context: Context,
        orderId: String,
        title: String,
        body: String,
        dedupKey: String = "order:$orderId:new",
        fromPush: Boolean = false,
    ) {
        showOrderUpdate(
            context = context,
            notificationKey = dedupKey,
            orderId = orderId,
            title = title,
            body = body,
            targetRole = "seller",
            fromPush = fromPush,
        )
    }

    fun showOrderUpdate(
        context: Context,
        notificationKey: String,
        orderId: String,
        title: String,
        body: String,
        targetRole: String = "",
        fromPush: Boolean = false,
    ) {
        if (!claimNotificationKey(notificationKey)) return
        ensureChannel(context)

        val act = (context as? MainActivity) ?: activeActivityOrNull()
        val inForeground = isAppInForeground && act != null

        if (inForeground) {
            // Foreground: gunakan sistem in-app notification yang sudah ada (Toast + refresh UI/badge),
            // tanpa duplikasi tray banner Android.
            showInAppToast(act!!, "$title\n$body")
            if (fromPush) {
                act.runOnUiThread {
                    act.ui.unreadOrderNotifCount += 1
                    act.ui.invalidate()
                    if (act.ui.myRole == "seller" || targetRole == "seller") {
                        act.refreshAdminOrders()
                    }
                    if (act.ui.myRole == "buyer" || targetRole == "buyer") {
                        act.refreshMyOrders()
                    }
                }
            }
            return
        }

        // Background / app terminated / lockscreen: tampilkan push notification Android.
        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ORDER_ID, orderId)
            putExtra(EXTRA_TARGET_SCREEN, "order_detail")
            if (targetRole.isNotBlank()) putExtra(EXTRA_TARGET_ROLE, targetRole)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        postAndroidNotification(
            context = context,
            channelId = CHANNEL_ORDERS_ID,
            notifId = notificationKey.hashCode(),
            title = title,
            body = body,
            bigText = body,
            subText = null,
            category = Notification.CATEGORY_STATUS,
            tapIntent = tap,
        )
    }

    fun showChatMessage(
        context: Context,
        conversationId: String,
        orderId: String?,
        rawSenderName: String,
        rawSubtitle: String,
        preview: String,
        messageId: String? = null,
        targetRole: String = "",
        fromPush: Boolean = false,
    ) {
        val cleanConvoId = conversationId.trim()
        if (cleanConvoId.isEmpty()) return
        // Jangan tampilkan notifikasi apabila pengguna sedang membuka percakapan ini atau membisukannya.
        if (activeOpenConversationId == cleanConvoId || isConversationMuted(context, cleanConvoId)) {
            return
        }
        val dedupKey = if (!messageId.isNullOrBlank()) {
            "chat:${messageId.trim()}"
        } else {
            "chat_msg:$cleanConvoId:${preview.trim().take(48)}"
        }
        if (!claimNotificationKey(dedupKey)) return
        ensureChannel(context)

        val cleanTitle = SupabaseRepository.extractCleanName(rawSenderName).ifBlank {
            rawSenderName.ifBlank { "Pesan Baru" }
        }
        val extractedClass = SupabaseRepository.extractClass(rawSenderName)
        val cleanSubtitle = rawSubtitle.trim().ifBlank { extractedClass }
        val cleanBody = preview.trim().ifBlank { "Mengirim pesan baru" }
        val displayBody = if (cleanSubtitle.isNotBlank() && cleanSubtitle != cleanTitle) {
            "$cleanSubtitle · $cleanBody"
        } else {
            cleanBody
        }

        val act = (context as? MainActivity) ?: activeActivityOrNull()
        val inForeground = isAppInForeground && act != null
        if (inForeground) {
            showInAppToast(act!!, "$cleanTitle\n$cleanBody")
            if (fromPush) {
                act.runOnUiThread {
                    act.refreshUnreadChats()
                    act.refreshChatList(silent = true)
                }
            }
            return
        }

        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CONVERSATION_ID, cleanConvoId)
            putExtra(EXTRA_TARGET_SCREEN, "chat")
            if (!orderId.isNullOrBlank()) putExtra(EXTRA_ORDER_ID, orderId)
            if (targetRole.isNotBlank()) putExtra(EXTRA_TARGET_ROLE, targetRole)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        postAndroidNotification(
            context = context,
            channelId = CHANNEL_CHAT_ID,
            notifId = "chat_$cleanConvoId".hashCode(),
            title = cleanTitle,
            body = cleanBody,
            bigText = displayBody,
            subText = cleanSubtitle.takeIf { it.isNotBlank() && it != cleanTitle },
            category = Notification.CATEGORY_MESSAGE,
            tapIntent = tap,
        )
    }

    fun showStoreActivity(
        context: Context,
        notificationKey: String,
        title: String,
        body: String,
        stallId: String? = null,
        targetRole: String = "",
        targetScreen: String = "notif",
        fromPush: Boolean = false,
    ) {
        if (!claimNotificationKey(notificationKey)) return
        ensureChannel(context)

        val act = (context as? MainActivity) ?: activeActivityOrNull()
        val inForeground = isAppInForeground && act != null
        if (inForeground) {
            showInAppToast(act!!, "$title\n$body")
            if (fromPush) {
                act.runOnUiThread {
                    act.refreshInbox()
                }
            }
            return
        }

        val tap = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_TARGET_SCREEN, targetScreen.ifBlank { "notif" })
            if (!stallId.isNullOrBlank()) putExtra(EXTRA_STALL_ID, stallId)
            if (targetRole.isNotBlank()) putExtra(EXTRA_TARGET_ROLE, targetRole)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        postAndroidNotification(
            context = context,
            channelId = CHANNEL_STORE_ID,
            notifId = notificationKey.hashCode(),
            title = title,
            body = body,
            bigText = body,
            subText = null,
            category = Notification.CATEGORY_RECOMMENDATION,
            tapIntent = tap,
        )
    }

    private fun showInAppToast(context: Context, message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun postAndroidNotification(
        context: Context,
        channelId: String,
        notifId: Int,
        title: String,
        body: String,
        bigText: String,
        subText: String?,
        category: String,
        tapIntent: Intent,
    ) {
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }
        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_orders)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setCategory(category)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notifId, notification)
        } catch (_: SecurityException) {
            // Izin POST_NOTIFICATIONS belum diberikan oleh pengguna.
        }
    }

    fun cacheFcmToken(context: Context, token: String) {
        val clean = token.trim()
        if (clean.length < 20) return
        context.applicationContext
            .getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CACHED_TOKEN, clean)
            .apply()
    }

    fun cachedFcmToken(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE)
            .getString(PREF_CACHED_TOKEN, null)
            ?.trim()
            ?.takeIf { it.length >= 20 }

    fun deviceLabel(): String {
        val brand = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = when {
            model.startsWith(brand, ignoreCase = true) -> model
            brand.isNotEmpty() && model.isNotEmpty() -> "$brand $model"
            else -> model.ifEmpty { brand.ifEmpty { "Android" } }
        }
        return "$combined (API ${Build.VERSION.SDK_INT})".take(120)
    }

    /**
     * Mengambil token FCM perangkat ini secara aman (tidak crash bila `google-services.json`
     * belum terpasang di build lokal; menyinkronkan cache token untuk login/logout).
     */
    suspend fun currentFcmToken(context: Context? = null): String? {
        val appCtx = context?.applicationContext ?: activeActivityOrNull()?.applicationContext ?: return null
        val firebaseReady = runCatching {
            FirebaseApp.getApps(appCtx).isNotEmpty()
        }.getOrDefault(false)

        if (!firebaseReady) {
            return cachedFcmToken(appCtx)
        }

        val liveToken = runCatching {
            suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token?.trim()) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }.getOrNull()?.takeIf { it.length >= 20 }

        if (liveToken != null) {
            cacheFcmToken(appCtx, liveToken)
        }
        return liveToken ?: cachedFcmToken(appCtx)
    }
}
