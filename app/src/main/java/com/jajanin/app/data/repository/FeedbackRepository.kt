package com.jajanin.app.data.repository

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
internal data class OrderRatingRow(
    val id: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("stall_id") val stallId: String = "",
    @SerialName("buyer_id") val buyerId: String = "",
    @SerialName("rating_store") val ratingStore: Int = 5,
    @SerialName("rating_duration") val ratingDuration: Int = 5,
    @SerialName("rating_taste") val ratingTaste: Int = 5,
    @SerialName("rating_overall") val ratingOverall: Double = 5.0,
    val comment: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class StoreRecentRating(
    val id: String = "",
    @SerialName("buyer_name") val buyerName: String = "Pembeli",
    @SerialName("rating_store") val ratingStore: Int = 5,
    @SerialName("rating_duration") val ratingDuration: Int = 5,
    @SerialName("rating_taste") val ratingTaste: Int = 5,
    @SerialName("rating_overall") val ratingOverall: Double = 5.0,
    val comment: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
private data class RawSellerReportRow(
    val id: String = "",
    @SerialName("reporter_id") val reporterId: String = "",
    @SerialName("stall_id") val stallId: String = "",
    @SerialName("seller_id") val sellerId: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    val source: String = "store_profile",
    val category: String = "",
    val description: String = "",
    @SerialName("evidence_url_1") val evidenceUrl1: String = "",
    @SerialName("evidence_url_2") val evidenceUrl2: String = "",
    val status: String = "open",
    @SerialName("developer_notes") val developerNotes: String = "",
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

internal data class DevSellerReport(
    val id: String,
    val reporterId: String,
    val reporterName: String,
    val reporterNis: String,
    val stallId: String,
    val stallName: String,
    val sellerId: String?,
    val sellerName: String,
    val orderId: String?,
    val orderPickupCode: String,
    val source: String,
    val category: String,
    val description: String,
    val evidenceUrl1: String,
    val evidenceUrl2: String,
    val status: String,
    val developerNotes: String,
    val createdAt: String,
    val timeLabel: String,
)

@Serializable
private data class AvatarOnlyRow(
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
private data class ReportProfileLite(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val nis: String? = null,
)

@Serializable
private data class ReportStallLite(
    val id: String,
    val name: String = "",
    @SerialName("owner_id") val ownerId: String? = null,
)

@Serializable
private data class ReportOrderLite(
    val id: String,
    @SerialName("pickup_code") val pickupCode: String = "",
)

internal object FeedbackRepository {
    private val client get() = SupabaseRepository.client

    // --- Profile Avatar ---

    suspend fun fetchMyAvatarUrl(): String {
        val uid = SupabaseRepository.currentUid() ?: return ""
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("avatar_url")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<AvatarOnlyRow>()
                ?.avatarUrl
                .orEmpty()
        }.getOrDefault("")
    }

    suspend fun updateMyAvatar(avatarUrl: String): String {
        val params = buildJsonObject {
            put("p_avatar_url", avatarUrl.trim())
        }
        return client.postgrest.rpc("update_my_avatar", params).decodeAs<String>()
    }

    /** Upload compressed JPEG bytes directly to a Supabase public storage bucket. */
    suspend fun uploadPublicImage(bucket: String, prefix: String, bytes: ByteArray): String =
        SupabaseRepository.uploadStorageObject(
            bucket = bucket,
            pathBuilder = { uid -> "$uid/${prefix}_${UUID.randomUUID()}.jpg" },
            bytes = bytes,
        )

    // --- Order Ratings (Toko, Durasi, Rasa 1..5) ---

    suspend fun fetchMyRatingsByOrderId(): Map<String, OrderRatingRow> {
        val uid = SupabaseRepository.currentUid() ?: return emptyMap()
        return runCatching {
            client.postgrest.from("order_ratings")
                .select(
                    Columns.list(
                        "id",
                        "order_id",
                        "stall_id",
                        "buyer_id",
                        "rating_store",
                        "rating_duration",
                        "rating_taste",
                        "rating_overall",
                        "comment",
                        "created_at",
                    )
                ) {
                    filter { eq("buyer_id", uid) }
                    order("created_at", Order.DESCENDING)
                    limit(120)
                }
                .decodeList<OrderRatingRow>()
                .associateBy { it.orderId }
        }.getOrDefault(emptyMap())
    }

    suspend fun submitOrderRating(
        orderId: String,
        ratingStore: Int,
        ratingDuration: Int,
        ratingTaste: Int,
        comment: String,
    ): OrderRatingRow {
        val params = buildJsonObject {
            put("p_order_id", orderId.trim())
            put("p_rating_store", ratingStore.coerceIn(1, 5))
            put("p_rating_duration", ratingDuration.coerceIn(1, 5))
            put("p_rating_taste", ratingTaste.coerceIn(1, 5))
            put("p_comment", comment.trim().take(400))
        }
        return client.postgrest.rpc("submit_order_rating", params).decodeAs<OrderRatingRow>()
    }

    // --- Seller Reports (2 Mandatory Photo Proofs -> Live Developer Console) ---

    suspend fun submitSellerReport(
        stallId: String,
        orderId: String?,
        source: String,
        category: String,
        description: String,
        evidenceUrl1: String,
        evidenceUrl2: String,
    ) {
        val params = buildJsonObject {
            put("p_stall_id", stallId.trim())
            if (orderId.isNullOrBlank()) put("p_order_id", JsonNull) else put("p_order_id", orderId.trim())
            put("p_source", source)
            put("p_category", category.trim().take(60))
            put("p_description", description.trim().take(1000))
            put("p_evidence_url_1", evidenceUrl1.trim())
            put("p_evidence_url_2", evidenceUrl2.trim())
        }
        client.postgrest.rpc("submit_seller_report", params)
    }

    suspend fun fetchAllReportsForDev(max: Int = 100): List<DevSellerReport> {
        val rows = runCatching {
            client.postgrest.from("seller_reports")
                .select(
                    Columns.list(
                        "id",
                        "reporter_id",
                        "stall_id",
                        "seller_id",
                        "order_id",
                        "source",
                        "category",
                        "description",
                        "evidence_url_1",
                        "evidence_url_2",
                        "status",
                        "developer_notes",
                        "resolved_by",
                        "resolved_at",
                        "created_at",
                    )
                ) {
                    order("created_at", Order.DESCENDING)
                    limit(max.toLong())
                }
                .decodeList<RawSellerReportRow>()
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()

        val profileIds = (rows.map { it.reporterId } + rows.mapNotNull { it.sellerId }).distinct()
        val stallIds = rows.map { it.stallId }.distinct()
        val orderIds = rows.mapNotNull { it.orderId }.distinct()

        val profilesById = runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("id", "display_name", "nis")) {
                    filter { isIn("id", profileIds) }
                }
                .decodeList<ReportProfileLite>()
                .associateBy { it.id }
        }.getOrDefault(emptyMap())

        val stallsById = runCatching {
            client.postgrest.from("stalls")
                .select(Columns.list("id", "name", "owner_id")) {
                    filter { isIn("id", stallIds) }
                }
                .decodeList<ReportStallLite>()
                .associateBy { it.id }
        }.getOrDefault(emptyMap())

        val ordersById = if (orderIds.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                client.postgrest.from("orders")
                    .select(Columns.list("id", "pickup_code")) {
                        filter { isIn("id", orderIds) }
                    }
                    .decodeList<ReportOrderLite>()
                    .associateBy { it.id }
            }.getOrDefault(emptyMap())
        }

        return rows.map { r ->
            val repProf = profilesById[r.reporterId]
            val (repCleanName, repTagNis) = splitBuyerNameAndNis(repProf?.displayName.orEmpty())
            val repNis = repProf?.nis?.takeIf { it.isNotBlank() } ?: repTagNis.orEmpty()
            val stall = stallsById[r.stallId]
            val sellerUid = r.sellerId ?: stall?.ownerId
            val sellerProf = sellerUid?.let { profilesById[it] }
            val (sellerCleanName, _) = splitBuyerNameAndNis(sellerProf?.displayName.orEmpty())
            val ordCode = r.orderId?.let { ordersById[it]?.pickupCode }.orEmpty()
            val timeLbl = if (r.createdAt.length >= 16) {
                r.createdAt.substring(0, 16).replace('T', ' ')
            } else {
                r.createdAt
            }
            DevSellerReport(
                id = r.id,
                reporterId = r.reporterId,
                reporterName = repCleanName.ifBlank { "Pembeli" },
                reporterNis = repNis,
                stallId = r.stallId,
                stallName = stall?.name?.takeIf { it.isNotBlank() } ?: "Warung",
                sellerId = sellerUid,
                sellerName = sellerCleanName.ifBlank { "Penjual" },
                orderId = r.orderId,
                orderPickupCode = ordCode,
                source = r.source,
                category = r.category,
                description = r.description,
                evidenceUrl1 = r.evidenceUrl1,
                evidenceUrl2 = r.evidenceUrl2,
                status = r.status,
                developerNotes = r.developerNotes,
                createdAt = r.createdAt,
                timeLabel = timeLbl,
            )
        }
    }

    suspend fun updateSellerReportForDev(reportId: String, status: String, notes: String = "") {
        val params = buildJsonObject {
            put("p_report_id", reportId.trim())
            put("p_status", status.trim().lowercase())
            put("p_developer_notes", notes.trim().take(600))
        }
        client.postgrest.rpc("developer_update_seller_report", params)
    }
}
