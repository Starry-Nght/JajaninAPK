package com.jajanin.app.feature.buyer.store_discovery

import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.data.repository.SupabaseRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val STORE_DISCOVERY_CATEGORIES = listOf(
    "All" to "Semua",
    "Open" to "Sedang Buka",
    "Verified" to "Terverifikasi",
    "Food" to "Makanan",
    "Drinks" to "Minuman",
)

internal val SELLER_STORE_CATEGORIES = listOf(
    "Makanan & Minuman",
    "Makanan Berat",
    "Jajanan & Camilan",
    "Minuman & Kopi",
    "Roti & Dessert",
)

@Serializable
internal data class PublicStoreSummary(
    val id: String,
    val name: String,
    val bio: String = "",
    @SerialName("store_category") val storeCategory: String = "Makanan & Minuman",
    val location: String = "",
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("completed_orders_count") val completedOrdersCount: Int = 0,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("seller_name") val sellerName: String = "",
    @SerialName("active_product_count") val activeProductCount: Int = 0,
    @SerialName("min_price_idr") val minPriceIdr: Int? = null,
)

@Serializable
internal data class StoreSearchResponse(
    val total: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val stores: List<PublicStoreSummary> = emptyList(),
)

@Serializable
internal data class PublicStoreDetail(
    val id: String,
    val name: String,
    val bio: String = "",
    @SerialName("store_category") val storeCategory: String = "Makanan & Minuman",
    val location: String = "",
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("completed_orders_count") val completedOrdersCount: Int = 0,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("has_qris") val hasQris: Boolean = false,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("seller_name") val sellerName: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
internal data class PublicStoreProfilePayload(
    val stall: PublicStoreDetail,
    val products: List<ProductRow> = emptyList(),
)

@Serializable
private data class IdOnlyRow(val id: String)

/**
 * Repository dedicated to Public Store Discovery, Store Profile inspection,
 * Seller Store Profile customization, and Developer audited verification actions.
 */
internal object StoreDiscoveryRepository {
    private val client get() = SupabaseRepository.client

    const val PAGE_SIZE = 12
    const val VERIFICATION_THRESHOLD = 20

    /** Paginated public store search against Supabase `search_public_stores` RPC. */
    suspend fun searchPublicStores(
        query: String,
        category: String = "All",
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): StoreSearchResponse {
        val params = buildJsonObject {
            put("p_query", query.trim())
            put("p_category", category.trim().ifBlank { "All" })
            put("p_limit", limit.coerceIn(1, 50))
            put("p_offset", offset.coerceAtLeast(0))
        }
        return client.postgrest.rpc("search_public_stores", params).decodeAs<StoreSearchResponse>()
    }

    /** Loads a single store's complete public profile + available products in one RPC call. */
    suspend fun fetchPublicStoreProfile(stallId: String): PublicStoreProfilePayload {
        require(stallId.isNotBlank()) { "Toko tidak valid." }
        val params = buildJsonObject { put("p_stall_id", stallId.trim()) }
        return client.postgrest.rpc("get_public_store_profile", params).decodeAs<PublicStoreProfilePayload>()
    }

    /** Lightweight lookup map (`stallId -> PublicStoreSummary`) for product cards on Home/Explore/Detail. */
    suspend fun fetchDirectoryMap(limit: Int = 50): Map<String, PublicStoreSummary> = runCatching {
        searchPublicStores(query = "", category = "All", limit = limit, offset = 0)
            .stores
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    /** Seller updates their own store's public bio, category, logo, cover banner, or open/closed state. */
    suspend fun updateMyStoreProfile(
        stallId: String,
        publicBio: String? = null,
        storeCategory: String? = null,
        logoUrl: String? = null,
        clearLogo: Boolean = false,
        bannerUrl: String? = null,
        clearBanner: Boolean = false,
        isOpen: Boolean? = null,
    ) {
        val patch = buildJsonObject {
            if (publicBio != null) put("public_bio", publicBio.trim().take(500))
            if (storeCategory != null) put("store_category", storeCategory.trim().take(60))
            if (clearLogo) put("logo_url", null as String?)
            else if (logoUrl != null) put("logo_url", logoUrl.trim())
            if (clearBanner) put("banner_url", null as String?)
            else if (bannerUrl != null) put("banner_url", bannerUrl.trim())
            if (isOpen != null) put("is_open", isOpen)
        }
        val res = client.postgrest.from("stalls").update(patch) {
            select(Columns.raw("id"))
            filter { eq("id", stallId) }
        }
        if (res.decodeList<IdOnlyRow>().isEmpty()) {
            error("Profil toko tidak dapat disimpan. Pastikan kamu pemilik warung ini.")
        }
    }

    /** Audited Developer action to revoke or restore a store's Jajanin Verified status. */
    suspend fun developerSetStoreVerification(
        stallId: String,
        revoke: Boolean,
        reason: String,
    ) {
        val cleanReason = reason.trim()
        require(cleanReason.length >= 3) { "Alasan audit wajib diisi (minimal 3 karakter)." }
        client.postgrest.rpc(
            "dev_set_stall_verification",
            buildJsonObject {
                put("p_stall_id", stallId)
                put("p_revoke", revoke)
                put("p_reason", cleanReason)
            },
        )
    }
}
