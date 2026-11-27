package com.jajanin.app.data.repository

import com.jajanin.app.data.model.ProductRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.presenceChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Row mirrors for the developer tables (see 202610080006_developer.sql). */
@Serializable
internal data class DevProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    val role: String = "buyer",
    @SerialName("account_status") val accountStatus: String = "active",
    @SerialName("warning_count") val warningCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class DevStallRow(
    val id: String,
    val name: String,
    val location: String = "",
    val description: String = "",
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("verification_revoked") val verificationRevoked: Boolean = false,
    @SerialName("completed_orders_count") val completedOrdersCount: Int = 0,
) {
    val sellerPhone: String
        get() = Regex("HP:\\s*([+0-9]+)").find(description)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val ownerRealName: String
        get() = Regex("Pemilik:\\s*([^|]+)").find(description)?.groupValues?.getOrNull(1)?.trim().orEmpty()
}

@Serializable
internal data class DevWarningRow(
    val id: String,
    @SerialName("target_user_id") val targetUserId: String = "",
    @SerialName("created_by") val createdBy: String? = null,
    val title: String = "",
    val message: String = "",
    val severity: String = "info",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class DevAnnouncementRow(
    val id: String,
    @SerialName("created_by") val createdBy: String? = null,
    val title: String = "",
    val message: String = "",
    @SerialName("target_role") val targetRole: String = "all",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
)

/** Undangan developer -> pembeli untuk menjadi penjual (lihat migrasi seller_invite_flow). */
@Serializable
internal data class SellerInvite(
    val id: String,
    @SerialName("target_user_id") val targetUserId: String = "",
    @SerialName("created_by") val createdBy: String? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("responded_at") val respondedAt: String? = null,
)

@Serializable
private data class DevStatusPatch(@SerialName("account_status") val accountStatus: String)

@Serializable
private data class DevWarnCountPatch(@SerialName("warning_count") val warningCount: Int)

@Serializable
private data class DevReadPatch(@SerialName("is_read") val isRead: Boolean)

@Serializable
private data class DevStallOpenPatch(@SerialName("is_open") val isOpen: Boolean)

/** Flat user model for the developer Users/Sellers tabs. */
internal data class DevUser(
    val id: String,
    val displayName: String,
    val email: String,
    val role: String,
    val accountStatus: String,
    val warningCount: Int,
    val createdAt: String,
    val orderCount: Int = 0,
    val stallName: String? = null,
    val stallOpen: Boolean? = null,
    val stallId: String? = null,
    val stallVerified: Boolean = false,
    val stallVerificationRevoked: Boolean = false,
    val stallCompletedOrders: Int = 0,
    val phone: String = "",
)

internal data class DevStats(
    val totalBuyers: Int = 0,
    val totalSellers: Int = 0,
    val activeStalls: Int = 0,
    val totalOrders: Int = 0,
    val pending: Int = 0,
    val completed: Int = 0,
    val cancelled: Int = 0,
    val todayCount: Int = 0,
    val todayRevenue: Int = 0,
)

@kotlinx.serialization.Serializable
internal data class DailyResetResult(
    @SerialName("completed_orders") val completedOrders: Int = 0,
    @SerialName("cleared_warnings") val clearedWarnings: Int = 0,
    @SerialName("cleared_announcements") val clearedAnnouncements: Int = 0,
    @SerialName("reset_profiles") val resetProfiles: Int = 0,
)

/**
 * Developer-only data access. RLS (`profiles.role = 'developer'`) is the real
 * gate; these functions only shape queries for the dashboard. Developers are
 * READ-ONLY on orders/finance — no status/payment mutation lives here.
 */
internal object DeveloperRepository {
    private val client get() = SupabaseRepository.client

    suspend fun isDeveloper(): Boolean = SupabaseRepository.currentRole() == "developer"

    suspend fun fetchStats(): DevStats {
        val buyers = client.postgrest.from("profiles")
            .select(Columns.list("id")) { filter { eq("role", "buyer") } }
            .decodeList<Map<String, String>>().size
        val sellers = client.postgrest.from("profiles")
            .select(Columns.list("id")) { filter { eq("role", "seller") } }
            .decodeList<Map<String, String>>().size
        val activeStalls = client.postgrest.from("stalls")
            .select(Columns.list("id")) { filter { eq("is_open", true) } }
            .decodeList<Map<String, String>>().size

        // Total orders + breakdown status (semua waktu).
        val allOrders = client.postgrest.from("orders")
            .select(Columns.list("id", "status")) { limit(1000) }
            .decodeList<Map<String, String>>()
        val pending = allOrders.count {
            val s = it["status"]?.lowercase() ?: ""
            s == "pending" || s == "confirmed" || s == "preparing" || s == "processing"
        }
        val completed = allOrders.count { it["status"]?.lowercase() == "completed" }
        val cancelled = allOrders.count { it["status"]?.lowercase() == "cancelled" }

        // Omzet + hitungan "Hari ini": dihitung dari 00:00 WIB hari ini (UTC+7).
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val todayStartIso = df.format(cal.time)

        val todayOrders = client.postgrest.from("orders")
            .select(Columns.list("id", "total_idr", "status", "created_at")) {
                filter { gte("created_at", todayStartIso) }
                limit(1000)
            }.decodeList<DevOrderLite>()

        val todayCount = todayOrders.size
        val todayRevenue = todayOrders
            .filter { it.status.equals("completed", ignoreCase = true) }
            .sumOf { it.totalIdr }

        return DevStats(
            totalBuyers = buyers,
            totalSellers = sellers,
            activeStalls = activeStalls,
            totalOrders = allOrders.size,
            pending = pending,
            completed = completed,
            cancelled = cancelled,
            todayCount = todayCount,
            todayRevenue = todayRevenue,
        )
    }

    @Serializable
    private data class DevOrderLite(
        val id: String = "",
        @SerialName("total_idr") val totalIdr: Int = 0,
        val status: String = "",
    )

    suspend fun fetchUsers(): List<DevUser> {
        val profiles = client.postgrest.from("profiles")
            .select(Columns.list("id", "display_name", "email", "role", "account_status", "warning_count", "created_at")) {
                order("created_at", Order.DESCENDING); limit(200)
            }.decodeList<DevProfileRow>()
        val stalls = runCatching {
            client.postgrest.from("stalls")
                .select(
                    Columns.list(
                        "id",
                        "name",
                        "location",
                        "description",
                        "is_open",
                        "owner_id",
                        "is_verified",
                        "verification_revoked",
                        "completed_orders_count",
                    )
                ) { limit(200) }
                .decodeList<DevStallRow>()
                .filter { it.ownerId != null }
                .associateBy { it.ownerId!! }
        }.getOrElse {
            runCatching {
                client.postgrest.from("stalls")
                    .select(Columns.list("id", "name", "location", "description", "is_open", "owner_id")) { limit(200) }
                    .decodeList<DevStallRow>()
                    .filter { it.ownerId != null }
                    .associateBy { it.ownerId!! }
            }.getOrDefault(emptyMap())
        }

        // Order count per user (best-effort; bila gagal isi 0).
        val orderCounts = runCatching {
            client.postgrest.from("orders").select(Columns.list("user_id")) { limit(1000) }
                .decodeList<Map<String, String>>()
                .groupingBy { it["user_id"].orEmpty() }
                .eachCount()
        }.getOrDefault(emptyMap())

        // Nomor HP pengguna (migrasi buyer_seller_chat_and_phones).
        val phones = runCatching {
            client.postgrest.from("user_phones").select(Columns.list("user_id", "phone")) { limit(500) }
                .decodeList<DevPhoneRow>()
                .associate { it.userId to it.phone }
        }.getOrDefault(emptyMap())

        return profiles.map { p ->
            val stall = stalls[p.id]
            val resolvedRealName = p.displayName?.takeIf { it.isNotBlank() }
                ?: stall?.ownerRealName?.takeIf { it.isNotBlank() }
                ?: (p.email?.substringBefore('@') ?: "Tanpa Nama")
            val resolvedPhone = phones[p.id]?.takeIf { it.isNotBlank() }
                ?: stall?.sellerPhone.orEmpty()
            DevUser(
                id = p.id,
                displayName = resolvedRealName,
                email = p.email ?: "-",
                role = p.role,
                accountStatus = p.accountStatus,
                warningCount = p.warningCount,
                createdAt = p.createdAt,
                orderCount = orderCounts[p.id] ?: 0,
                stallName = stall?.name,
                stallOpen = stall?.isOpen,
                stallId = stall?.id,
                stallVerified = stall?.isVerified == true,
                stallVerificationRevoked = stall?.verificationRevoked == true,
                stallCompletedOrders = stall?.completedOrdersCount ?: 0,
                phone = resolvedPhone,
            )
        }
    }

    @Serializable
    private data class DevPhoneRow(
        @SerialName("user_id") val userId: String = "",
        val phone: String = "",
    )

    /** Platform-wide orders (read-only; capped for the dashboard list). */
    suspend fun fetchAllOrders(max: Int = 60): List<AdminOrder> {
        val rows = client.postgrest.from("orders")
            .select(
                Columns.raw(
                    "id,user_id,status,total_idr,pickup_code,created_at,payment_method,payment_status," +
                        "order_items(id,menu_item_id,item_name,unit_price_idr,quantity)"
                )
            ) {
                order("created_at", Order.DESCENDING); limit(max.toLong())
            }.decodeList<OrderRow>()
        if (rows.isEmpty()) return emptyList()
        val names = runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("id", "display_name", "email")) {
                    filter { isIn("id", rows.map { it.userId }.distinct().map { id -> "\"$id\"" }) }
                }.decodeList<ProfileRow>()
                .associate { it.id to (it.displayName?.takeIf { s -> s.isNotBlank() } ?: it.email?.substringBefore('@') ?: "Pembeli") }
        }.getOrDefault(emptyMap())
        val stallNames = runCatching {
            client.postgrest.from("stalls").select(Columns.list("id", "name")) { limit(200) }
                .decodeList<Map<String, String>>()
                .associate { (it["id"].orEmpty()) to (it["name"].orEmpty()) }
        }.getOrDefault(emptyMap())
        return rows.map { row ->
            AdminOrder(
                id = row.id, userId = row.userId, status = row.status,
                totalIdr = row.totalIdr, pickupCode = row.pickupCode,
                createdAt = row.createdAt, items = row.items,
                buyerName = names[row.userId] ?: "Pembeli",
                timeLabel = row.createdAt.substring(0, 16).replace('T', ' '),
                paymentMethod = row.paymentMethod, paymentStatus = row.paymentStatus,
            )
        }
    }

    /** Stall lookup for the seller inspector (Seller -> Stall -> Menu -> Orders). */
    suspend fun fetchStallMenu(stallId: String): List<ProductRow> = runCatching {
        client.postgrest.from("menu_items")
            .select(Columns.raw("id,name,description,category,price_idr,image_url,is_available,stock,stall_id,ingredients,order_schedule,pickup_sessions")) {
                filter { eq("stall_id", stallId) }; order("created_at", Order.DESCENDING)
            }.decodeList<ProductRow>()
    }.getOrDefault(emptyList())

    // --- Warnings ---

    suspend fun fetchWarningHistory(max: Int = 80): List<DevWarningRow> =
        client.postgrest.from("developer_warnings")
            .select(Columns.list("id", "target_user_id", "created_by", "title", "message", "severity", "is_read", "created_at")) {
                order("created_at", Order.DESCENDING); limit(max.toLong())
            }.decodeList<DevWarningRow>()

    suspend fun sendWarning(targetUserId: String, title: String, message: String, severity: String) {
        val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
        val sev = severity.lowercase().takeIf { it == "info" || it == "warning" || it == "critical" } ?: "info"
        val payload = buildJsonObject {
            put("target_user_id", targetUserId)
            put("created_by", uid)
            put("title", title.trim())
            put("message", message.trim())
            put("severity", sev)
        }
        client.postgrest.from("developer_warnings").insert(payload)
        // Keep the counter on profiles for the Users tab (best-effort; RLS may no-op).
        runCatching {
            val current = client.postgrest.from("profiles").select(Columns.list("warning_count")) {
                filter { eq("id", targetUserId) }; limit(1)
            }.decodeSingleOrNull<WarnCountRow>()?.warningCount ?: 0
            client.postgrest.from("profiles").update(DevWarnCountPatch(current + 1)) {
                filter { eq("id", targetUserId) }
            }
        }
        // Nudge status to warned unless already suspended (best-effort).
        runCatching {
            client.postgrest.from("profiles").update(DevStatusPatch("warned")) {
                filter { eq("id", targetUserId) }
            }
        }
    }

    @Serializable
    private data class WarnCountRow(@SerialName("warning_count") val warningCount: Int = 0)

    // --- Announcements ---

    suspend fun fetchAnnouncements(): List<DevAnnouncementRow> =
        client.postgrest.from("developer_announcements")
            .select(Columns.list("id", "created_by", "title", "message", "target_role", "created_at", "is_active")) {
                order("created_at", Order.DESCENDING); limit(40)
            }.decodeList<DevAnnouncementRow>()

    suspend fun sendAnnouncement(title: String, message: String, targetRole: String) {
        val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
        val target = targetRole.lowercase().takeIf { it == "all" || it == "buyers" || it == "sellers" } ?: "all"
        require(title.isNotBlank()) { "Judul pengumuman wajib diisi." }
        val payload = buildJsonObject {
            put("created_by", uid)
            put("title", title.trim())
            put("message", message.trim())
            put("target_role", target)
            put("is_active", true)
        }
        client.postgrest.from("developer_announcements").insert(payload)
    }

    // --- Restrictions (no deletes, ever) ---

    suspend fun setAccountStatus(userId: String, status: String) {
        require(status == "active" || status == "warned" || status == "suspended") { "Status tidak valid." }
        client.postgrest.from("profiles").update(DevStatusPatch(status)) {
            select(Columns.list("id")); filter { eq("id", userId) }
        }
    }

    suspend fun toggleStall(stallId: String, open: Boolean) {
        client.postgrest.from("stalls").update(DevStallOpenPatch(open)) {
            select(Columns.list("id")); filter { eq("id", stallId) }
        }
    }

    /**
     * Reset harian monitor (developer only, enforcement di DB via
     * `daily_reset_monitor`): order aktif -> completed, warnings +
     * announcements dibersihkan, warning_count direset. Riwayat order
     * (completed/cancelled) tetap utuh untuk keuangan.
     */
    suspend fun dailyResetMonitor(): DailyResetResult {
        return client.postgrest.rpc("daily_reset_monitor").decodeAs<DailyResetResult>()
    }

    /** Reset jatah pesanan harian (0/2) untuk satu pembeli tertentu (developer only). */
    suspend fun resetBuyerQuota(userId: String): Int {
        val params = mapOf("p_target_user_id" to kotlinx.serialization.json.JsonPrimitive(userId))
        return client.postgrest.rpc("dev_reset_buyer_quota", params).decodeAs<Int>()
    }

    /** Reset jatah pesanan harian (0/2) untuk seluruh pembeli sekaligus (developer only). */
    suspend fun resetAllBuyerQuotas(): Int {
        return client.postgrest.rpc("dev_reset_all_quotas").decodeAs<Int>()
    }

    // --- Buyer/seller inbox ---

    suspend fun fetchMyWarnings(): List<DevWarningRow> {
        val uid = SupabaseRepository.currentUid() ?: return emptyList()
        return runCatching {
            client.postgrest.from("developer_warnings")
                .select(Columns.list("id", "target_user_id", "created_by", "title", "message", "severity", "is_read", "created_at")) {
                    filter { eq("target_user_id", uid) }
                    order("created_at", Order.DESCENDING); limit(30)
                }.decodeList<DevWarningRow>()
        }.getOrDefault(emptyList())
    }

    suspend fun fetchMyAnnouncements(): List<DevAnnouncementRow> {
        return runCatching {
            client.postgrest.from("developer_announcements")
                .select(Columns.list("id", "created_by", "title", "message", "target_role", "created_at", "is_active")) {
                    filter { eq("is_active", true) }
                    order("created_at", Order.DESCENDING); limit(20)
                }.decodeList<DevAnnouncementRow>()
        }.getOrDefault(emptyList())
    }

    suspend fun markWarningRead(warningId: String) {
        runCatching {
            client.postgrest.from("developer_warnings").update(DevReadPatch(true)) {
                filter { eq("id", warningId) }
            }
        }
    }

    // --- Seller invites (developer undang pembeli -> pembeli verifikasi) ---

    private val inviteColumns = Columns.list("id", "target_user_id", "created_by", "status", "created_at", "responded_at")

    /** Undangan milik akun ini (dipakai badge + dialog verifikasi pembeli). */
    suspend fun fetchMyInvites(): List<SellerInvite> {
        val uid = SupabaseRepository.currentUid() ?: return emptyList()
        return runCatching {
            client.postgrest.from("seller_invites")
                .select(inviteColumns) {
                    filter { eq("target_user_id", uid) }
                    order("created_at", Order.DESCENDING); limit(10)
                }.decodeList<SellerInvite>()
        }.getOrDefault(emptyList())
    }

    /** Semua undangan pending (dipakai dashboard developer). */
    suspend fun fetchPendingInvites(): List<SellerInvite> = runCatching {
        client.postgrest.from("seller_invites")
            .select(inviteColumns) {
                filter { eq("status", "pending") }
                order("created_at", Order.DESCENDING); limit(100)
            }.decodeList<SellerInvite>()
    }.getOrDefault(emptyList())

    /** Developer mengundang satu pembeli jadi penjual (balikan: id undangan). */
    suspend fun inviteSeller(targetUserId: String) {
        val params = mapOf("p_target_user_id" to kotlinx.serialization.json.JsonPrimitive(targetUserId))
        client.postgrest.rpc("invite_seller", params).decodeAs<String>()
    }

    /** Developer membatalkan undangan pending. */
    suspend fun cancelInvite(inviteId: String) {
        val params = mapOf("p_invite_id" to kotlinx.serialization.json.JsonPrimitive(inviteId))
        client.postgrest.rpc("cancel_seller_invite", params)
    }

    /** Pembeli menerima undangan -> role jadi seller. */
    suspend fun acceptInvite(inviteId: String) {
        val params = mapOf("p_invite_id" to kotlinx.serialization.json.JsonPrimitive(inviteId))
        client.postgrest.rpc("accept_seller_invite", params)
    }

    /** Pembeli menolak undangan. */
    suspend fun declineInvite(inviteId: String) {
        val params = mapOf("p_invite_id" to kotlinx.serialization.json.JsonPrimitive(inviteId))
        client.postgrest.rpc("decline_seller_invite", params)
    }

    fun observeInbox(scope: CoroutineScope, onChange: () -> Unit): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel("dev-inbox")
        val flowW = channel.postgresChangeFlow<PostgresAction>("public") { table = "developer_warnings" }
        val flowA = channel.postgresChangeFlow<PostgresAction>("public") { table = "developer_announcements" }
        val flowI = channel.postgresChangeFlow<PostgresAction>("public") { table = "seller_invites" }
        val job = scope.launch {
            launch { flowW.collect { onChange() } }
            launch { flowA.collect { onChange() } }
            launch { flowI.collect { onChange() } }
            channel.subscribe()
        }
        return OrderObserver(channel, job, scope)
    }

    fun observePlatform(scope: CoroutineScope, onChange: () -> Unit): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel("dev-platform")
        val flow = channel.postgresChangeFlow<PostgresAction>("public") { table = "orders" }
        val job = scope.launch {
            launch { flow.collect { onChange() } }
            channel.subscribe()
        }
        return OrderObserver(channel, job, scope)
    }

    private const val ONLINE_CHANNEL_TOPIC = "jajanin-online-presence"

    /** Pelacak kehadiran live (siapa saja yang sedang membuka aplikasi Jajanin saat ini). */
    fun startOnlinePresence(
        scope: CoroutineScope,
        userId: String,
        role: String,
        previousCleanup: Job?,
        onOnlineChanged: (Set<String>) -> Unit,
    ): OnlinePresenceSession {
        val client = SupabaseRepository.client
        val onlineRefs = HashMap<String, MutableSet<String>>()
        val session = OnlinePresenceSession(
            userId = userId,
            role = role,
            scope = scope,
            onClosed = {
                onlineRefs.clear()
                onOnlineChanged(emptySet())
            },
        )
        session.job = scope.launch {
            runCatching { previousCleanup?.join() }
            client.realtime.subscriptions.values
                .filter { it.topic.endsWith(ONLINE_CHANNEL_TOPIC) }
                .toList()
                .forEach { old -> runCatching { client.realtime.removeChannel(old) } }
            if (session.isClosed()) return@launch

            val channel = client.realtime.channel(ONLINE_CHANNEL_TOPIC) {
                presence { key = userId }
            }
            session.channel = channel
            val presenceFlow = channel.presenceChangeFlow()

            fun extractUid(key: String, state: JsonObject): String =
                state["user_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: key

            launch {
                runCatching {
                    presenceFlow.collect { action ->
                        var changed = false
                        action.joins.forEach { (key, presence) ->
                            val uid = extractUid(key, presence.state)
                            if (uid.isNotBlank() && onlineRefs.getOrPut(uid) { mutableSetOf() }.add(presence.presenceRef)) {
                                changed = true
                            }
                        }
                        action.leaves.forEach { (key, presence) ->
                            val uid = extractUid(key, presence.state)
                            val refs = onlineRefs[uid]
                            if (refs != null && refs.remove(presence.presenceRef)) {
                                if (refs.isEmpty()) onlineRefs.remove(uid)
                                changed = true
                            }
                        }
                        if (changed) {
                            onOnlineChanged(onlineRefs.keys.toSet())
                        }
                    }
                }
            }

            launch {
                var wasSubscribed = false
                runCatching {
                    channel.status.collect { st ->
                        when (st) {
                            RealtimeChannel.Status.SUBSCRIBING -> if (wasSubscribed) {
                                onlineRefs.clear()
                                onOnlineChanged(emptySet())
                            }
                            RealtimeChannel.Status.SUBSCRIBED -> {
                                wasSubscribed = true
                                runCatching {
                                    channel.track(buildJsonObject {
                                        put("user_id", userId)
                                        put("role", session.role)
                                    })
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }

            runCatching { client.realtime.connect() }
            runCatching { channel.subscribe(blockUntilSubscribed = false) }
        }
        return session
    }
}

internal class OnlinePresenceSession internal constructor(
    val userId: String,
    role: String,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    var role: String = role
        private set
    internal var channel: RealtimeChannel? = null
    internal var job: Job? = null
    @Volatile private var closed = false

    fun updateRole(newRole: String) {
        if (closed || role == newRole) return
        role = newRole
        val ch = channel ?: return
        if (ch.status.value == RealtimeChannel.Status.SUBSCRIBED) {
            scope.launch {
                runCatching {
                    ch.track(buildJsonObject {
                        put("user_id", userId)
                        put("role", newRole)
                    })
                }
            }
        }
    }

    fun isClosed(): Boolean = closed

    fun close(): Job {
        closed = true
        job?.cancel()
        onClosed()
        val ch = channel
        channel = null
        return scope.launch {
            if (ch != null) {
                runCatching { ch.untrack() }
                runCatching { SupabaseRepository.client.realtime.removeChannel(ch) }
            }
        }
    }
}
