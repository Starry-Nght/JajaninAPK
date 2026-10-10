package com.jajanin.app

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
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
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("owner_id") val ownerId: String? = null,
)

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

    /** Platform stats computed client-side from bounded fetches (prototype scale). */
    suspend fun fetchStats(): DevStats {
        val profiles = runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("id", "role")) { limit(1000) }
                .decodeList<DevProfileRow>()
        }.getOrDefault(emptyList())
        val stalls = runCatching {
            client.postgrest.from("stalls")
                .select(Columns.list("id", "name", "is_open", "owner_id")) { limit(200) }
                .decodeList<DevStallRow>()
        }.getOrDefault(emptyList())
        val orders = runCatching {
            client.postgrest.from("orders")
                .select(Columns.raw("id,status,total_idr,created_at")) { limit(500); order("created_at", Order.DESCENDING) }
                .decodeList<DevOrderLite>()
        }.getOrDefault(emptyList())
        val todayPrefix = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val todayOrders = orders.filter { it.createdAt.startsWith(todayPrefix) }
        return DevStats(
            totalBuyers = profiles.count { it.role == "buyer" },
            totalSellers = profiles.count { it.role == "seller" },
            activeStalls = stalls.count { it.isOpen },
            totalOrders = orders.size,
            pending = orders.count { it.status.equals("pending", true) },
            completed = orders.count { it.status.equals("completed", true) },
            cancelled = orders.count { it.status.equals("cancelled", true) },
            todayCount = todayOrders.size,
            todayRevenue = todayOrders.filterNot { it.status.equals("cancelled", true) }.sumOf { it.totalIdr },
        )
    }

    @Serializable
    private data class DevOrderLite(
        val id: String,
        val status: String = "",
        @SerialName("total_idr") val totalIdr: Int = 0,
        @SerialName("created_at") val createdAt: String = "",
    )

    /** Users + sellers with order counts and stall info joined client-side. */
    suspend fun fetchUsers(): List<DevUser> {
        val profiles = client.postgrest.from("profiles")
            .select(Columns.list("id", "display_name", "email", "role", "account_status", "warning_count", "created_at")) {
                order("created_at", Order.DESCENDING); limit(300)
            }.decodeList<DevProfileRow>()
        val stalls = runCatching {
            client.postgrest.from("stalls")
                .select(Columns.list("id", "name", "is_open", "owner_id")) { limit(200) }
                .decodeList<DevStallRow>()
        }.getOrDefault(emptyList())
        val orderCounts = runCatching {
            client.postgrest.from("orders").select(Columns.list("user_id")) {
                limit(1000)
            }.decodeList<Map<String, String>>()
        }.getOrDefault(emptyList())
            .groupingBy { it["user_id"].orEmpty() }.eachCount()
        val stallByOwner = stalls.associateBy { it.ownerId }
        // Nomor HP (developer boleh baca semua via RLS profile_phones).
        val phones = runCatching {
            client.postgrest.from("profile_phones")
                .select(Columns.list("user_id", "phone")) { limit(1000) }
                .decodeList<DevPhoneRow>()
                .associate { it.userId to it.phone }
        }.getOrDefault(emptyMap())
        return profiles.map { p ->
            val stall = stallByOwner[p.id]
            DevUser(
                id = p.id,
                displayName = p.displayName?.takeIf { it.isNotBlank() }
                    ?: p.email?.substringBefore('@') ?: "Pengguna",
                email = p.email.orEmpty(),
                role = p.role,
                accountStatus = p.accountStatus,
                warningCount = p.warningCount,
                createdAt = p.createdAt.take(10),
                orderCount = orderCounts[p.id] ?: 0,
                stallName = stall?.name,
                stallOpen = stall?.isOpen,
                stallId = stall?.id,
                phone = phones[p.id].orEmpty(),
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
            .select(Columns.raw("id,name,description,category,price_idr,image_url,is_available,stock,stall_id,ingredients")) {
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
}
