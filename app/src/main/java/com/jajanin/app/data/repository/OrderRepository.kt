package com.jajanin.app.data.repository

import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.data.model.ProductRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Satu baris order dengan item yang di-embed PostgREST lewat relasi `order_items`. */
@Serializable
internal data class OrderRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("stall_id") val stallId: String? = null,
    val status: String,
    @SerialName("total_idr") val totalIdr: Int,
    @SerialName("pickup_code") val pickupCode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    @SerialName("payment_reference") val paymentReference: String? = null,
    @SerialName("payment_proof_url") val paymentProofUrl: String? = null,
    @SerialName("payment_expires_at") val paymentExpiresAt: String? = null,
    @SerialName("stock_deducted") val stockDeducted: Boolean = true,
    @SerialName("order_mode") val orderMode: String = "same_day",
    @SerialName("pickup_date") val pickupDate: String = "",
    @SerialName("pickup_session") val pickupSession: String = "Istirahat 1",
    @SerialName("order_items") val items: List<OrderItemRow> = emptyList(),
)

@Serializable
internal data class OrderItemRow(
    val id: String,
    @SerialName("order_id") val orderId: String = "",
    @SerialName("menu_item_id") val menuItemId: String? = null,
    @SerialName("item_name") val itemName: String,
    @SerialName("unit_price_idr") val unitPriceIdr: Int,
    val quantity: Int,
)

@Serializable
internal data class ProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
)

@Serializable
internal data class TopSellingRow(
    @SerialName("menu_item_id") val menuItemId: String? = null,
    @SerialName("norm_name") val normName: String = "",
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("order_count") val orderCount: Int = 0,
)

internal data class SalesStat(
    val totalQty: Int = 0,
    val orderCount: Int = 0,
)

@Serializable
internal data class RealtimeOrderPatch(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("stall_id") val stallId: String? = null,
    val status: String? = null,
    @SerialName("payment_status") val paymentStatus: String? = null,
    @SerialName("payment_reference") val paymentReference: String? = null,
    @SerialName("payment_proof_url") val paymentProofUrl: String? = null,
    @SerialName("payment_expires_at") val paymentExpiresAt: String? = null,
    @SerialName("stock_deducted") val stockDeducted: Boolean? = null,
)

/** Model siap-tampil untuk layar admin & pembeli: nama pembeli, jam, ruangan ambil, dan kontak penjual sudah diresolusi saat fetch. */
internal data class AdminOrder(
    val id: String,
    val userId: String,
    val status: String,
    val totalIdr: Int,
    val pickupCode: String,
    val createdAt: String,
    val items: List<OrderItemRow>,
    val buyerName: String,
    val timeLabel: String,
    val paymentMethod: String? = null,
    val paymentStatus: String = "unpaid",
    val buyerPhone: String = "",
    val pickupRoom: String = "",
    val sellerPhone: String = "",
    val sellerStoreName: String = "",
    val sellerOwnerName: String = "",
    val cancelReason: String = "",
    val orderMode: String = "same_day",
    val pickupDate: String = "",
    val pickupSession: String = "Istirahat 1",
    val stallId: String = "",
    val paymentProofUrl: String = "",
    val paymentExpiresAt: String? = null,
    val stockDeducted: Boolean = true,
) {
    val itemCount: Int
        get() = items.sumOf { it.quantity }

    val itemsSummary: String
        get() = items.joinToString("  ·  ") { "${it.quantity}× ${it.itemName}" }

    val isPreOrder: Boolean
        get() = orderMode.equals("pre_order", ignoreCase = true)

    /** Label ringkas jadwal ambil, mis. "Ambil Hari Ini · Istirahat 1" atau "PO Kam, 26 Nov · Istirahat 2". */
    val pickupScheduleLabel: String
        get() {
            val today = OrderRepository.todayWibDateString()
            val tomorrow = OrderRepository.tomorrowWibDateString()
            val dateTag = when {
                pickupDate == today -> "Hari Ini"
                pickupDate == tomorrow -> "Besok (${formatShortIdDate(pickupDate)})"
                pickupDate.isNotBlank() -> formatCalendarShortChip(pickupDate)
                isPreOrder -> "Besok"
                else -> "Hari Ini"
            }
            val prefix = if (isPreOrder) "PO $dateTag" else "Ambil $dateTag"
            val sess = pickupSession.trim()
            return if (sess.isNotBlank()) "$prefix · $sess" else prefix
        }
}

/** Penanda bahwa aliran Realtime untuk order admin sedang aktif; tutup saat logout. */
internal class OrderObserver internal constructor(
    private val channel: RealtimeChannel,
    private val job: Job,
    private val scope: CoroutineScope,
) {
    fun close() {
        job.cancel()
        scope.launch { runCatching { SupabaseRepository.client.realtime.removeChannel(channel) } }
    }
}

@Serializable
private data class StatusPatch(val status: String)

@Serializable
private data class CancelPatch(
    val status: String,
    @SerialName("payment_reference") val paymentReference: String,
)

@Serializable
private data class PaymentPatch(@SerialName("payment_status") val paymentStatus: String)

@Serializable
private data class PaymentProofPatch(
    @SerialName("payment_status") val paymentStatus: String,
    @SerialName("payment_proof_url") val paymentProofUrl: String,
)

@Serializable
internal data class PlacedOrder(
    val id: String,
    @SerialName("pickup_code") val pickupCode: String,
    @SerialName("total_idr") val totalIdr: Int,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    val status: String = "pending",
    @SerialName("order_mode") val orderMode: String = "same_day",
    @SerialName("pickup_date") val pickupDate: String = "",
    @SerialName("pickup_session") val pickupSession: String = "Istirahat 1",
    @SerialName("payment_expires_at") val paymentExpiresAt: String? = null,
    @SerialName("stock_deducted") val stockDeducted: Boolean = true,
)

@Serializable
internal data class StallInfo(
    val id: String,
    val name: String,
    val location: String = "",
    val description: String = "",
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("qris_image_url") val qrisImageUrl: String? = null,
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("store_category") val storeCategory: String = "Makanan & Minuman",
    @SerialName("public_bio") val publicBio: String = "",
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("completed_orders_count") val completedOrdersCount: Int = 0,
) {
    val sellerPhone: String
        get() = Regex("HP:\\s*([+0-9]+)").find(description)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    val ownerRealName: String
        get() = Regex("Pemilik:\\s*([^|]+)").find(description)?.groupValues?.getOrNull(1)?.trim().orEmpty()
}

@Serializable
private data class MenuRoomLookupRow(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    val ingredients: String = "",
)

/** Hasil probe stall_availability: antrean aktif warung (maksimal 10). */
@Serializable
internal data class StallAvailabilityRow(
    @SerialName("stall_id") val stallId: String = "",
    @SerialName("active_orders") val activeOrders: Int = 0,
    val capacity: Int = 10,
    @SerialName("is_full") val isFull: Boolean = false,
)

/** Hasil probe ordering_status: flag blackout (waktu server) + hitungan order hari ini. */
@Serializable
internal data class OrderingStatusRow(
    val blocked: Boolean = false,
    @SerialName("block_message") val blockMessage: String? = null,
    @SerialName("orders_today") val ordersToday: Int = 0,
    val remaining: Int = 2,
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
private data class IdRow(val id: String)

internal data class ProductDraft(
    val name: String,
    val description: String,
    val category: String,
    val priceIdr: Int,
    val imageUrl: String?,
    val stock: Int?,
    val ingredients: String = "",
    val available: Boolean = true,
    val orderSchedule: String = "both",
    val pickupSessions: String = "Istirahat 1,Istirahat 2,Pulang",
    val poDates: String = "",
    val poCutoffHour: Int = 20,
    val poDailyQuota: Int? = null,
    val poRequireQris: Boolean = false,
    val poAllowWeekend: Boolean = false,
)

@Serializable
internal data class PoDateSlot(
    val used: Int = 0,
    val quota: Int? = null,
    val remaining: Int? = null,
    val closed: Boolean = false,
    @SerialName("close_reason") val closeReason: String? = null,
)

@Serializable
internal data class PoAvailabilityResponse(
    @SerialName("cutoff_hour") val cutoffHour: Int = 20,
    @SerialName("daily_quota") val dailyQuota: Int? = null,
    @SerialName("require_qris") val requireQris: Boolean = false,
    @SerialName("allow_weekend") val allowWeekend: Boolean = false,
    val dates: Map<String, PoDateSlot> = emptyMap(),
)

@Serializable
internal data class GatewayPaymentSession(
    @SerialName("order_id") val orderId: String = "",
    @SerialName("gateway_order_id") val gatewayOrderId: String = "",
    @SerialName("gateway_transaction_id") val gatewayTransactionId: String? = null,
    @SerialName("payment_channel") val paymentChannel: String = "qris",
    val environment: String = "sandbox",
    val status: String = "pending",
    @SerialName("gross_amount") val grossAmount: Int = 0,
    @SerialName("qr_url") val qrUrl: String? = null,
    @SerialName("qr_string") val qrString: String? = null,
    @SerialName("redirect_url") val redirectUrl: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val error: String? = null,
)

@Serializable
internal data class GatewayStatusResult(
    @SerialName("order_id") val orderId: String = "",
    @SerialName("gateway_order_id") val gatewayOrderId: String = "",
    val status: String = "pending",
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    @SerialName("qr_url") val qrUrl: String? = null,
    @SerialName("redirect_url") val redirectUrl: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
internal data class SellerLedgerSummary(
    @SerialName("available_idr") val availableIdr: Int = 0,
    @SerialName("paid_out_idr") val paidOutIdr: Int = 0,
    @SerialName("total_idr") val totalIdr: Int = 0,
    @SerialName("tx_count") val txCount: Int = 0,
)

@Serializable
private data class NewDevice(
    @SerialName("user_id") val userId: String,
    @SerialName("fcm_token") val fcmToken: String,
)

/**
 * Satu-satunya tempat aplikasi membaca/menulis tabel order, order_items,
 * profiles (untuk nama pembeli) dan admin_devices.
 *
 * Semua akses order melewati RLS: buyer hanya melihat order miliknya, admin
 * (profiles.role = 'seller') melihat semuanya lewat policy `is_admin()`.
 */
internal object OrderRepository {
    private val client get() = SupabaseRepository.client

    // Kolom eksplisit + embed order_items: respons tidak pernah membawa kolom
    // tak dikenal dan daftar order tidak memicu N+1 query per item.
    private val orderColumns = Columns.raw(
        "id,user_id,stall_id,status,total_idr,pickup_code,created_at,payment_method,payment_status,payment_reference," +
            "payment_proof_url,payment_expires_at,stock_deducted," +
            "order_mode,pickup_date,pickup_session," +
            "order_items(id,menu_item_id,item_name,unit_price_idr,quantity)"
    )

    private val productColumns = Columns.raw(
        "id,name,description,category,price_idr,image_url,is_available,stock,stall_id,ingredients,order_schedule,pickup_sessions,po_dates,po_cutoff_hour,po_daily_quota,po_require_qris,po_allow_weekend"
    )

    private val realtimeJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    internal fun decodeProductRecord(record: JsonObject): ProductRow? = runCatching {
        realtimeJson.decodeFromJsonElement<ProductRow>(record)
    }.getOrNull()

    internal fun decodeOrderPatch(record: JsonObject): RealtimeOrderPatch? = runCatching {
        realtimeJson.decodeFromJsonElement<RealtimeOrderPatch>(record)
    }.getOrNull()

    internal fun extractRecordId(record: JsonObject): String? =
        record["id"]?.jsonPrimitive?.contentOrNull

    fun observeProducts(scope: CoroutineScope, onAction: (PostgresAction) -> Unit): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel("available-menu-items")
        val flow = channel.postgresChangeFlow<PostgresAction>("public") { table = "menu_items" }
        val job = scope.launch {
            runCatching { SupabaseRepository.client.realtime.connect() }
            launch { runCatching { flow.collect { onAction(it) } } }
            runCatching { channel.subscribe() }
        }
        return OrderObserver(channel, job, scope)
    }

    suspend fun fetchAvailableProducts(): List<ProductRow> = client.postgrest.from("menu_items")
        .select(productColumns) {
            filter { eq("is_available", true) }
            order("created_at", Order.DESCENDING)
        }
        .decodeList<ProductRow>()
        .filter { it.name.isNotBlank() && it.priceIdr > 0 && it.category.isNotBlank() }

    /** Produk milik warung penjual ini saja (dua langkah agar sintaks PostgREST pasti valid). */
    suspend fun fetchSellerProducts(): List<ProductRow> {
        val uid = SupabaseRepository.currentUid() ?: return emptyList()
        val stallIds = runCatching {
            client.postgrest.from("stalls").select(Columns.list("id")) {
                filter { eq("owner_id", uid) }
            }.decodeList<IdRow>().map { it.id }
        }.getOrNull().orEmpty()
        if (stallIds.isEmpty()) return emptyList()
        return client.postgrest.from("menu_items")
            .select(productColumns) {
                filter { isIn("stall_id", stallIds.map { id -> "\"$id\"" }) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ProductRow>()
    }

    /** Warung milik penjual ini; null bila belum ada (daftar produk pertama akan membuatnya). */
    suspend fun sellerStallId(): String? {
        val uid = SupabaseRepository.currentUid() ?: return null
        return runCatching {
            client.postgrest.from("stalls").select(Columns.list("id")) {
                filter { eq("owner_id", uid) }
                limit(1)
            }.decodeSingleOrNull<IdRow>()?.id
        }.getOrNull()
    }

    suspend fun createProduct(draft: ProductDraft): ProductRow {
        val stockVal = draft.stock
        require(stockVal != null && stockVal in 1..100) {
            "Stok wajib diisi antara 1 sampai 100 sebelum menjual produk."
        }
        val uid = SupabaseRepository.currentUid() ?: error("Silakan masuk untuk menyimpan produk.")
        val stallId = resolveSellerStall(uid)
        val payload = buildJsonObject {
            put("stall_id", stallId)
            put("name", draft.name)
            put("description", draft.description)
            put("category", draft.category)
            put("price_idr", draft.priceIdr)
            put("image_url", draft.imageUrl)
            put("is_available", draft.available)
            put("ingredients", draft.ingredients)
            put("order_schedule", draft.orderSchedule)
            put("pickup_sessions", draft.pickupSessions)
            put("po_dates", draft.poDates)
            put("po_cutoff_hour", draft.poCutoffHour.coerceIn(12, 23))
            draft.poDailyQuota?.let { put("po_daily_quota", it) }
            put("po_require_qris", draft.poRequireQris)
            put("po_allow_weekend", draft.poAllowWeekend)
            put("stock", stockVal)
        }
        return client.postgrest.from("menu_items").insert(payload) {
            select(productColumns)
        }.decodeSingle<ProductRow>()
    }

    suspend fun updateProduct(productId: String, draft: ProductDraft) {
        val stockVal = draft.stock
        require(stockVal != null && stockVal in 1..100) {
            "Stok wajib diisi antara 1 sampai 100 sebelum menjual produk."
        }
        val patch = buildJsonObject {
            put("name", draft.name)
            put("description", draft.description)
            put("category", draft.category)
            put("price_idr", draft.priceIdr)
            put("image_url", draft.imageUrl)
            put("is_available", draft.available)
            put("ingredients", draft.ingredients)
            put("order_schedule", draft.orderSchedule)
            put("pickup_sessions", draft.pickupSessions)
            put("po_dates", draft.poDates)
            put("po_cutoff_hour", draft.poCutoffHour.coerceIn(12, 23))
            put("po_daily_quota", draft.poDailyQuota)
            put("po_require_qris", draft.poRequireQris)
            put("po_allow_weekend", draft.poAllowWeekend)
            put("stock", stockVal)
        }
        val result = client.postgrest.from("menu_items").update(patch) {
            select(Columns.raw("id"))
            filter { eq("id", productId) }
        }
        if (result.decodeList<IdRow>().isEmpty()) error("Produk tidak dapat diperbarui.")
    }

    suspend fun deleteProduct(productId: String) {
        // Jangan menganggap representasi kosong sebagai kegagalan: sebagian
        // konfigurasi PostgREST membalas 204 tanpa body walau baris terhapus.
        // Verifikasi ulang ke server agar hasilnya pasti.
        val deleteError = runCatching {
            client.postgrest.from("menu_items").delete {
                select(Columns.raw("id"))
                filter { eq("id", productId) }
            }
        }.exceptionOrNull()

        val stillExists = runCatching {
            client.postgrest.from("menu_items").select(Columns.list("id")) {
                filter { eq("id", productId) }
                limit(1)
            }.decodeSingleOrNull<IdRow>() != null
        }.getOrElse { error -> throw deleteError ?: error }

        if (stillExists) {
            deleteError?.let { throw it }
            error("Produk gagal dihapus. Pastikan kamu pemilik warung ini.")
        }
    }

    suspend fun setProductAvailable(productId: String, available: Boolean) {
        val patch = buildJsonObject { put("is_available", available) }
        val result = client.postgrest.from("menu_items").update(patch) {
            select(Columns.raw("id"))
            filter { eq("id", productId) }
        }
        if (result.decodeList<IdRow>().isEmpty()) error("Produk tidak dapat diperbarui.")
    }

    private suspend fun resolveSellerStall(userId: String): String {
        val existing = client.postgrest.from("stalls").select(Columns.list("id")) {
            filter { eq("owner_id", userId) }
            limit(1)
        }.decodeSingleOrNull<IdRow>()
        if (existing != null) return existing.id
        val payload = buildJsonObject {
            put("name", "Kantin Penjual")
            put("description", "Menu kantin Jajanin")
            put("location", "Kantin")
            put("owner_id", userId)
        }
        return client.postgrest.from("stalls").insert(payload) {
            select(Columns.list("id"))
        }.decodeSingle<IdRow>().id
    }

    /**
     * Warung penjual wajib punya nama toko/brand. Dipanggil saat penjual
     * masuk dashboard: bila belum ada warung dibuatkan dengan nama yang
     * diinput; bila nama masih kosong/default, diperbarui ke nama baru.
     * Mengembalikan id warung.
     */
    suspend fun ensureSellerStall(name: String): String {
        val clean = name.trim()
        require(clean.length >= 3) { "Nama toko minimal 3 karakter." }
        val uid = SupabaseRepository.currentUid() ?: error("Silakan masuk untuk menyimpan warung.")
        val existing = client.postgrest.from("stalls").select(Columns.list("id")) {
            filter { eq("owner_id", uid) }
            limit(1)
        }.decodeSingleOrNull<IdRow>()
        if (existing != null) {
            updateStallName(existing.id, clean)
            return existing.id
        }
        val payload = buildJsonObject {
            put("name", clean)
            put("description", "Menu kantin Jajanin")
            put("location", "Kantin")
            put("owner_id", uid)
        }
        return client.postgrest.from("stalls").insert(payload) {
            select(Columns.list("id"))
        }.decodeSingle<IdRow>().id
    }

    /** Mengganti nama warung milik penjual ini. */
    suspend fun updateStallName(stallId: String, name: String) {
        val clean = name.trim()
        require(clean.length >= 3) { "Nama toko minimal 3 karakter." }
        val patch = buildJsonObject { put("name", clean) }
        val result = client.postgrest.from("stalls").update(patch) {
            select(Columns.raw("id"))
            filter { eq("id", stallId) }
        }
        if (result.decodeList<IdRow>().isEmpty()) error("Nama warung tidak dapat disimpan.")
    }

    /**
     * Menempatkan order lewat RPC server-side `place_order`: validasi menu,
     * ketersediaan, dan stok + hitung harga dari database, snapshot item,
     * kurangi stok, tulis order + items atomis. Total client tidak dipercaya.
     */
    suspend fun placeOrder(
        lines: List<Pair<String, Int>>,
        paymentMethod: String,
        orderMode: String = "same_day",
        pickupSession: String = "Istirahat 1",
        pickupDate: String? = null,
    ): PlacedOrder {
        val method = paymentMethod.lowercase()
        require(method == "cash" || method == "qris") { "Metode bayar tidak valid." }
        require(lines.isNotEmpty()) { "Keranjang masih kosong." }
        val array = kotlinx.serialization.json.JsonArray(
            lines.map { (id, qty) ->
                buildJsonObject {
                    put("menu_item_id", id)
                    put("quantity", qty)
                }
            }
        )
        val params = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("p_items", array)
            put("p_payment_method", kotlinx.serialization.json.JsonPrimitive(method))
            put("p_order_mode", kotlinx.serialization.json.JsonPrimitive(orderMode))
            put("p_pickup_session", kotlinx.serialization.json.JsonPrimitive(pickupSession.trim().ifBlank { "Istirahat 1" }))
            if (orderMode == "pre_order" && !pickupDate.isNullOrBlank()) {
                put("p_pickup_date", kotlinx.serialization.json.JsonPrimitive(pickupDate.trim()))
            }
        }
        return try {
            client.postgrest.rpc("place_order_v3", params).decodeAs<PlacedOrder>()
        } catch (t: Throwable) {
            throw IllegalStateException(parseDbError(t), t)
        }
    }

    /**
     * Probe status pemesanan dari server (waktu WIB + hitungan order hari ini).
     * Hanya untuk tampilan; penegakan tetap di place_order.
     */
    suspend fun fetchOrderingStatus(): OrderingStatusRow {
        return client.postgrest.rpc("ordering_status").decodeAs<OrderingStatusRow>()
    }

    /**
     * Antrean aktif satu warung dari server (maksimal 10).
     * Hanya untuk tampilan; place_order selalu memeriksa ulang.
     */
    suspend fun fetchStallAvailability(stallId: String): StallAvailabilityRow {
        val params = mapOf("p_stall_id" to kotlinx.serialization.json.JsonPrimitive(stallId))
        return client.postgrest.rpc("stall_availability", params).decodeAs<StallAvailabilityRow>()
    }

    /** Pesan error DB yang aman tampil (ambil detail raise exception Postgres). */
    private fun parseDbError(t: Throwable): String {
        val msg = t.message.orEmpty()
        // PostgREST mengembalikan body JSON berisi "message" dari raise exception.
        val jsonMsg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(msg)?.groupValues?.getOrNull(1)
        val clean = (jsonMsg ?: msg).replace("\\n", " ").trim()
        return when {
            clean.contains("Masuk dulu", true) -> "Masuk dulu sebelum memesan."
            clean.contains("Keranjang", true) -> "Keranjang masih kosong."
            clean.contains("ditutup pukul", true) -> clean.take(200)
            clean.contains("Warung sedang penuh", true) -> clean.take(200)
            clean.contains("Batas pesanan harian", true) -> clean.take(200)
            clean.contains("Satu pesanan hanya", true) -> clean.take(200)
            clean.contains("tepat 1", true) -> clean.take(200)
            clean.contains("ditangguhkan", true) -> clean.take(200)
            clean.contains("Tanggal Pre-Order", true) -> clean.take(200)
            clean.contains("Tanggal PO", true) -> clean.take(200)
            clean.contains("Batas pemesanan PO", true) -> clean.take(220)
            clean.contains("Kuota PO", true) -> clean.take(200)
            clean.contains("Senin-Jumat", true) -> clean.take(200)
            clean.contains("wajib pembayaran QRIS", true) -> clean.take(220)
            clean.contains("lebih dari 3 hari wajib", true) -> clean.take(220)
            clean.contains("mewajibkan pembayaran QRIS", true) -> clean.take(220)
            clean.contains("Maksimal 2 pesanan Pre-Order", true) -> clean.take(200)
            clean.contains("belum menentukan tanggal", true) -> clean.take(200)
            clean.contains("tidak dapat dibatalkan oleh pembeli", true) -> clean.take(220)
            clean.contains("pada hari pengambilan", true) -> clean.take(220)
            clean.contains("Verifikasi pembayaran QRIS dulu", true) -> clean.take(220)
            clean.contains("Verifikasi bukti bayar QRIS", true) -> clean.take(220)
            clean.contains("sudah lunas tidak dapat dibatalkan", true) -> clean.take(220)
            clean.contains("Pembeli sudah mengunggah bukti bayar QRIS", true) -> clean.take(240)
            clean.contains("tidak dapat dibatalkan manual oleh penjual", true) -> clean.take(240)
            clean.contains("sudah dibayar / mengunggah bukti QRIS", true) -> clean.take(220)
            clean.contains("foto bukti transfer", true) -> clean.take(220)
            clean.contains("Batas waktu bayar QRIS", true) -> clean.take(220)
            clean.contains("belum dibayar", true) -> clean.take(220)
            clean.contains("menunggu verifikasi", true) -> clean.take(220)
            clean.contains("Warung sedang tutup", true) -> clean.take(200)
            clean.contains("belum memasang kode QRIS", true) -> clean.take(200)
            clean.contains("Format tanggal", true) -> clean.take(200)
            clean.contains("khusus Pre-Order", true) -> clean.take(200)
            clean.contains("khusus ambil Hari Ini", true) -> clean.take(200)
            clean.contains("tidak valid", true) -> clean.take(160)
            clean.contains("tidak ditemukan", true) -> "Menu tidak ditemukan."
            clean.contains("tidak tersedia", true) -> clean.take(160)
            clean.contains("Stok", true) -> clean.take(200)
            else -> SupabaseRepository.errorMessage(t)
        }
    }

    /** Sapu pesanan Midtrans yang melewati batas bayar di server (idempoten, aman dipanggil berkala). */
    suspend fun expireUnpaidQrisOrders(): Int = runCatching {
        client.postgrest.rpc("expire_unpaid_qris_orders").decodeAs<Int>()
    }.getOrDefault(0)

    /** Buat atau ambil ulang sesi pembayaran Midtrans Sandbox untuk order terkait. */
    suspend fun createGatewayPayment(
        orderId: String,
        forceSnap: Boolean = false,
    ): GatewayPaymentSession {
        val token = SupabaseRepository.currentAccessToken()
            ?: error("Masuk dulu untuk memproses pembayaran.")
        val body = buildJsonObject {
            put("order_id", orderId)
            put("force_snap", forceSnap)
        }.toString()
        val (code, raw) = SupabaseRepository.postEdgeFunction("create-payment", body, token)
        val parsed = runCatching { realtimeJson.decodeFromString<GatewayPaymentSession>(raw) }.getOrNull()
        if (code !in 200..299) {
            error(parsed?.error?.takeIf { it.isNotBlank() } ?: "Gagal membuat sesi Midtrans (HTTP $code).")
        }
        return parsed ?: error("Respons Midtrans tidak dapat dibaca.")
    }

    /** Periksa status pembayaran Midtrans secara real-time & sinkronkan ke DB bila sudah settlement. */
    suspend fun checkGatewayPaymentStatus(orderId: String): GatewayStatusResult {
        val token = SupabaseRepository.currentAccessToken()
            ?: error("Masuk dulu untuk mengecek pembayaran.")
        val body = buildJsonObject { put("order_id", orderId) }.toString()
        val (code, raw) = SupabaseRepository.postEdgeFunction("check-payment-status", body, token)
        val parsed = runCatching { realtimeJson.decodeFromString<GatewayStatusResult>(raw) }.getOrNull()
        if (code !in 200..299) {
            error(parsed?.error?.takeIf { it.isNotBlank() } ?: "Gagal mengecek status Midtrans (HTTP $code).")
        }
        return parsed ?: error("Respons status Midtrans tidak valid.")
    }

    /** Ambil ringkasan saldo hasil pembayaran Midtrans otomatis milik warung penjual. */
    suspend fun fetchMySellerLedgerSummary(): SellerLedgerSummary = runCatching {
        client.postgrest.rpc("my_seller_ledger_summary").decodeAs<SellerLedgerSummary>()
    }.getOrDefault(SellerLedgerSummary())

    /**
     * Mengubah status satu order (dipakai aksi tombol detail admin).
     *
     * Query UPDATE dengan RETURNING dipakai sebagai verifikasi: bila RLS
     * menyaring baris (bukan admin) atau id tidak ada, hasilnya kosong dan
     * perubahan dianggap gagal.
     */
    suspend fun updateOrderStatus(orderId: String, status: String) {
        val result = try {
            client.postgrest.from("orders")
                .update(StatusPatch(status)) {
                    select()
                    filter { eq("id", orderId) }
                }
        } catch (t: Throwable) {
            throw IllegalStateException(parseDbError(t), t)
        }
        if (result.decodeList<OrderRow>().isEmpty()) {
            error("Perubahan status gagal disimpan. Pastikan kamu masih masuk sebagai admin.")
        }
    }

    /**
     * Penjual membatalkan pesanan disertai alasan wajib dan kontrol stok eksplisit
     * (kembalikan stok bila sudah terpotong, atau set stok = 0 bila alasan stok habis).
     */
    suspend fun cancelSellerOrder(
        orderId: String,
        reason: String,
        restoreStock: Boolean = true,
        markOutOfStock: Boolean = false,
    ) {
        val cleanReason = reason.trim()
        require(cleanReason.isNotBlank()) { "Alasan pembatalan wajib diisi." }
        try {
            client.postgrest.rpc(
                "cancel_seller_order_v2",
                buildJsonObject {
                    put("p_order_id", orderId)
                    put("p_reason", cleanReason)
                    put("p_restore_stock", restoreStock)
                    put("p_mark_out_of_stock", markOutOfStock)
                },
            )
        } catch (t: Throwable) {
            throw IllegalStateException(parseDbError(t), t)
        }
    }

    /**
     * Pembeli membatalkan pesanannya sendiri sebelum dikonfirmasi penjual (`status = 'pending'`).
     */
    suspend fun cancelBuyerPendingOrder(orderId: String) {
        val uid = SupabaseRepository.currentUid() ?: error("Silakan masuk terlebih dahulu.")
        val current = client.postgrest.from("orders")
            .select(Columns.raw("id,status")) {
                filter {
                    eq("id", orderId)
                    eq("user_id", uid)
                }
                limit(1)
            }
            .decodeSingleOrNull<RealtimeOrderPatch>()
            ?: error("Pesanan tidak ditemukan.")
        if (!current.status.equals(STATUS_PENDING, ignoreCase = true)) {
            error("Pesanan sudah dikonfirmasi penjual dan tidak bisa dibatalkan.")
        }
        val updated = runCatching {
            client.postgrest.from("orders")
                .update(CancelPatch(status = STATUS_CANCELLED, paymentReference = "Dibatalkan oleh pembeli")) {
                    select(Columns.raw("id"))
                    filter {
                        eq("id", orderId)
                        eq("user_id", uid)
                        eq("status", STATUS_PENDING)
                    }
                }
                .decodeList<IdRow>()
                .isNotEmpty()
        }.getOrDefault(false)
        if (updated) return

        client.postgrest.from("orders").delete {
            filter {
                eq("id", orderId)
                eq("user_id", uid)
                eq("status", STATUS_PENDING)
            }
        }
        val stillExists = runCatching {
            client.postgrest.from("orders")
                .select(Columns.raw("id")) {
                    filter { eq("id", orderId) }
                    limit(1)
                }
                .decodeSingleOrNull<IdRow>() != null
        }.getOrDefault(false)
        if (stillExists) {
            error("Gagal membatalkan pesanan. Pesanan mungkin sudah dikonfirmasi penjual.")
        }
    }

    /**
     * Pembeli mengajukan bukti bayar QRIS disertai URL foto bukti transfer wajib:
     * unpaid|rejected -> waiting_verification. Trigger DB memvalidasi URL & batas waktu 10 menit.
     */
    suspend fun submitPaymentProof(orderId: String, proofUrl: String) {
        val cleanUrl = proofUrl.trim()
        require(cleanUrl.isNotBlank()) { "Wajib mengunggah foto bukti transfer QRIS terlebih dahulu." }
        val result = try {
            client.postgrest.from("orders")
                .update(PaymentProofPatch("waiting_verification", cleanUrl)) {
                    select(Columns.raw("id"))
                    filter { eq("id", orderId) }
                }
        } catch (t: Throwable) {
            throw IllegalStateException(parseDbError(t), t)
        }
        if (result.decodeList<IdRow>().isEmpty()) {
            error("Konfirmasi bayar gagal disimpan.")
        }
    }

    /** Penjual menandai lunas (tunai saat ambil, atau verifikasi QRIS). Untuk QRIS, stok dipotong atomis di server saat ini. */
    suspend fun markOrderPaid(orderId: String) {
        val result = try {
            client.postgrest.from("orders")
                .update(PaymentPatch("paid")) {
                    select(Columns.raw("id"))
                    filter { eq("id", orderId) }
                }
        } catch (t: Throwable) {
            throw IllegalStateException(parseDbError(t), t)
        }
        if (result.decodeList<IdRow>().isEmpty()) {
            error("Gagal menandai lunas. Pastikan ini order warungmu.")
        }
    }

    /** Penjual menolak bukti bayar QRIS; pembeli boleh mengajukan ulang. */
    suspend fun rejectOrderPayment(orderId: String) {
        val result = client.postgrest.from("orders")
            .update(PaymentPatch("rejected")) {
                select(Columns.raw("id"))
                filter { eq("id", orderId) }
            }
        if (result.decodeList<IdRow>().isEmpty()) {
            error("Gagal menolak pembayaran.")
        }
    }

    /** Info publik satu warung (nama, ruangan ambil, kontak penjual, verifikasi, & QRIS) untuk layar pembeli. */
    suspend fun fetchStall(stallId: String): StallInfo? = runCatching {
        client.postgrest.from("stalls")
            .select(
                Columns.raw(
                    "id,name,location,description,owner_id,qris_image_url,is_open," +
                        "logo_url,banner_url,store_category,public_bio,is_verified,completed_orders_count"
                )
            ) {
                filter { eq("id", stallId) }
                limit(1)
            }
            .decodeSingleOrNull<StallInfo>()
    }.getOrElse {
        runCatching {
            client.postgrest.from("stalls")
                .select(Columns.raw("id,name,location,description,owner_id,qris_image_url,is_open")) {
                    filter { eq("id", stallId) }
                    limit(1)
                }
                .decodeSingleOrNull<StallInfo>()
        }.getOrNull()
    }

    /** Warung milik penjual ini beserta ruangan ambil, profil publik, status verifikasi, kontak, & QRIS-nya. */
    suspend fun fetchMyStall(): StallInfo? {
        val uid = SupabaseRepository.currentUid() ?: return null
        return runCatching {
            client.postgrest.from("stalls")
                .select(
                    Columns.raw(
                        "id,name,location,description,owner_id,qris_image_url,is_open," +
                            "logo_url,banner_url,store_category,public_bio,is_verified,completed_orders_count"
                    )
                ) {
                    filter { eq("owner_id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<StallInfo>()
        }.getOrElse {
            runCatching {
                client.postgrest.from("stalls")
                    .select(Columns.raw("id,name,location,description,owner_id,qris_image_url,is_open")) {
                        filter { eq("owner_id", uid) }
                        limit(1)
                    }
                    .decodeSingleOrNull<StallInfo>()
            }.getOrNull()
        }
    }

    /** Simpan ruangan pengambilan ("Ambil di ruangan") pada warung penjual. */
    suspend fun updateStallLocation(stallId: String, location: String) {
        val clean = location.trim()
        if (clean.isBlank()) return
        val patch = buildJsonObject { put("location", clean) }
        runCatching {
            client.postgrest.from("stalls").update(patch) {
                select(Columns.raw("id"))
                filter { eq("id", stallId) }
            }
        }
    }

    /** Sinkronkan nama asli pemilik + nomor telepon penjual ke metadata warung agar dapat dibaca di detail pesanan pembeli. */
    suspend fun syncStallContactMetadata(stallId: String, ownerName: String, phone: String) {
        val cleanOwner = ownerName.trim().replace("|", " ")
        val cleanPhone = phone.trim()
        val desc = buildString {
            if (cleanOwner.isNotBlank()) append("Pemilik: $cleanOwner")
            if (cleanPhone.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append("HP: $cleanPhone")
            }
        }
        if (desc.isBlank()) return
        val patch = buildJsonObject { put("description", desc) }
        runCatching {
            client.postgrest.from("stalls").update(patch) {
                select(Columns.raw("id"))
                filter { eq("id", stallId) }
            }
        }
    }

    /** Penjual menyimpan URL gambar QRIS warungnya sendiri. */
    suspend fun updateStallQris(stallId: String, qrisUrl: String?) {
        val patch = buildJsonObject { put("qris_image_url", qrisUrl) }
        val result = client.postgrest.from("stalls").update(patch) {
            select(Columns.raw("id"))
            filter { eq("id", stallId) }
        }
        if (result.decodeList<IdRow>().isEmpty()) error("QRIS warung tidak dapat disimpan.")
    }

    suspend fun fetchAdminTodayOrders(): List<AdminOrder> {
        // Isolasi ketat per warung penjual: hanya pesanan masuk untuk warung milik uid ini
        // (tidak pernah menyertakan pesanan milik sendiri sebagai pembeli di warung orang lain).
        val uid = SupabaseRepository.currentUid() ?: return emptyList()
        val myStallIds = runCatching {
            client.postgrest.from("stalls").select(Columns.list("id")) {
                filter { eq("owner_id", uid) }
            }.decodeList<IdRow>().map { it.id }
        }.getOrDefault(emptyList())
        if (myStallIds.isEmpty()) return emptyList()
        val quotedStalls = myStallIds.map { id -> "\"$id\"" }
        val stallIdSet = myStallIds.toSet()

        val active = runCatching {
            client.postgrest.from("orders").select(orderColumns) {
                filter {
                    isIn("stall_id", quotedStalls)
                    neq("user_id", uid)
                    isIn("status", listOf(STATUS_PENDING, STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY, STATUS_READY))
                }
                order("created_at", Order.DESCENDING)
                limit(50)
            }.decodeList<OrderRow>()
        }.getOrDefault(emptyList())

        val todayDone = runCatching {
            client.postgrest.from("orders").select(orderColumns) {
                filter {
                    isIn("stall_id", quotedStalls)
                    neq("user_id", uid)
                    gte("created_at", todayStartIso())
                    isIn("status", listOf(STATUS_COMPLETED, STATUS_CANCELLED))
                }
                order("created_at", Order.DESCENDING)
                limit(50)
            }.decodeList<OrderRow>()
        }.getOrDefault(emptyList())

        val combined = (active + todayDone)
            .distinctBy { it.id }
            .filter { row -> row.userId != uid && (row.stallId == null || row.stallId in stallIdSet) }
        return hydrate(combined).filter { order ->
            order.userId != uid && (order.stallId.isBlank() || order.stallId in stallIdSet)
        }
    }

    /** Mengambil satu order berdasar id (dipakai deep-link notifikasi). Null bila tak terlihat oleh RLS. */
    suspend fun fetchOrderById(orderId: String): AdminOrder? {
        val row = client.postgrest.from("orders")
            .select(orderColumns) {
                filter { eq("id", orderId) }
                limit(1)
            }
            .decodeSingleOrNull<OrderRow>() ?: return null
        val order = hydrate(listOf(row)).firstOrNull() ?: return null
        return order.copy(buyerPhone = fetchOrderBuyerPhone(orderId))
    }

    /** Nomor HP pembeli untuk satu order; RPC memvalidasi akses seller/admin. */
    private suspend fun fetchOrderBuyerPhone(orderId: String): String = runCatching {
        client.postgrest.rpc(
            "order_buyer_phone",
            buildJsonObject { put("p_order_id", orderId) },
        ).decodeAsOrNull<String>()
    }.getOrNull().orEmpty()

    /**
     * Mendaftarkan token FCM perangkat ini untuk notifikasi order admin.
     * Upsert pada (user_id, fcm_token) agar token yang sama tidak ganda.
     */
    suspend fun registerDevice(userId: String, fcmToken: String) {
        client.postgrest.from("admin_devices")
            .upsert(NewDevice(userId, fcmToken), onConflict = "user_id,fcm_token", defaultToNull = false)
    }

    /** Melepas token perangkat ini saat logout agar notifikasi tidak dikirim ke sesi yang sudah keluar. */
    suspend fun removeDevice(userId: String, fcmToken: String) {
        client.postgrest.from("admin_devices")
            .delete {
                filter {
                    eq("user_id", userId)
                    eq("fcm_token", fcmToken)
                }
            }
    }

    /**
     * Berlangganan perubahan tabel orders lewat Supabase Realtime dengan payload PostgresAction.
     */
    fun observeOrders(
        scope: CoroutineScope,
        channelName: String = "orders-live",
        onAction: (PostgresAction) -> Unit,
    ): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel(channelName)
        val flow = channel.postgresChangeFlow<PostgresAction>("public") { table = "orders" }
        val job = scope.launch {
            runCatching { SupabaseRepository.client.realtime.connect() }
            launch { runCatching { flow.collect { onAction(it) } } }
            runCatching { channel.subscribe() }
        }
        return OrderObserver(channel, job, scope)
    }

    private suspend fun hydrate(rows: List<OrderRow>): List<AdminOrder> = coroutineScope {
        if (rows.isEmpty()) return@coroutineScope emptyList()
        val buyerNamesDeferred = async { resolveBuyerNames(rows) }
        val menuIds = rows.flatMap { it.items }.mapNotNull { it.menuItemId?.takeIf(String::isNotBlank) }.distinct()
        val menuLookupDeferred = async {
            if (menuIds.isEmpty()) emptyMap() else runCatching {
                client.postgrest.from("menu_items")
                    .select(Columns.raw("id,stall_id,ingredients")) {
                        filter { isIn("id", menuIds.map { id -> "\"$id\"" }) }
                    }
                    .decodeList<MenuRoomLookupRow>()
                    .associateBy { it.id }
            }.getOrDefault(emptyMap())
        }
        val buyerNames = buyerNamesDeferred.await()
        val menuLookup = menuLookupDeferred.await()

        val stallIds = (menuLookup.values.map { it.stallId } + rows.mapNotNull { it.stallId?.takeIf(String::isNotBlank) }).distinct()
        val stallLookup = if (stallIds.isEmpty()) emptyMap() else runCatching {
            client.postgrest.from("stalls")
                .select(Columns.raw("id,name,location,description,owner_id,qris_image_url")) {
                    filter { isIn("id", stallIds.map { id -> "\"$id\"" }) }
                }
                .decodeList<StallInfo>()
                .associateBy { it.id }
        }.getOrDefault(emptyMap())

        rows.map { row ->
            val firstMenuId = row.items.firstNotNullOfOrNull { it.menuItemId?.takeIf(String::isNotBlank) }
            val menuRow = firstMenuId?.let { menuLookup[it] }
            val resolvedStallId = row.stallId?.takeIf(String::isNotBlank) ?: menuRow?.stallId.orEmpty()
            val stall = resolvedStallId.takeIf(String::isNotBlank)?.let { stallLookup[it] }
            val itemRoom = menuRow?.let { com.jajanin.app.data.model.extractRoomTag(it.ingredients) }.orEmpty()
            val resolvedRoom = itemRoom.ifBlank { stall?.location?.takeIf { it != "Kantin" }.orEmpty() }

            AdminOrder(
                id = row.id,
                userId = row.userId,
                status = row.status,
                totalIdr = row.totalIdr,
                pickupCode = row.pickupCode,
                createdAt = row.createdAt,
                items = row.items,
                buyerName = buyerNames[row.userId] ?: "Pembeli",
                timeLabel = formatOrderTime(row.createdAt),
                paymentMethod = row.paymentMethod,
                paymentStatus = row.paymentStatus,
                pickupRoom = resolvedRoom,
                sellerPhone = stall?.sellerPhone.orEmpty(),
                sellerStoreName = stall?.name.orEmpty(),
                sellerOwnerName = stall?.ownerRealName.orEmpty(),
                cancelReason = row.paymentReference?.trim().orEmpty(),
                orderMode = row.orderMode,
                pickupDate = row.pickupDate,
                pickupSession = row.pickupSession,
                stallId = resolvedStallId,
                paymentProofUrl = row.paymentProofUrl?.trim().orEmpty(),
                paymentExpiresAt = row.paymentExpiresAt?.trim()?.takeIf { it.isNotBlank() },
                stockDeducted = row.stockDeducted,
            )
        }
    }

    internal fun todayWibDateString(): String = wibDateStringOffset(0)

    internal fun tomorrowWibDateString(): String = wibDateStringOffset(1)

    internal fun wibDateStringOffset(daysAhead: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta")).apply {
            add(Calendar.DAY_OF_YEAR, daysAhead)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }.format(cal.time)
    }

    internal fun isValidPreOrderDate(isoDate: String): Boolean {
        val clean = isoDate.trim()
        if (!clean.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
        val minDate = wibDateStringOffset(1)
        val maxDate = wibDateStringOffset(30)
        return clean >= minDate && clean <= maxDate
    }

    internal fun isWeekendWib(isoDate: String): Boolean {
        val tz = TimeZone.getTimeZone("Asia/Jakarta")
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.parse(isoDate.trim())
        }.getOrNull() ?: return false
        val cal = Calendar.getInstance(tz).apply { time = parsed }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    internal fun daysAheadFromTodayWib(isoDate: String): Int {
        val tz = TimeZone.getTimeZone("Asia/Jakarta")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val todayMs = runCatching { fmt.parse(todayWibDateString())?.time }.getOrNull() ?: return 0
        val targetMs = runCatching { fmt.parse(isoDate.trim())?.time }.getOrNull() ?: return 0
        return ((targetMs - todayMs) / 86_400_000L).toInt()
    }

    internal fun isPastPoCutoffWib(isoDate: String, cutoffHour: Int = 20): Boolean {
        val tz = TimeZone.getTimeZone("Asia/Jakarta")
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.parse(isoDate.trim())
        }.getOrNull() ?: return true
        val cutoffCal = Calendar.getInstance(tz).apply {
            time = parsed
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, cutoffHour.coerceIn(12, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return System.currentTimeMillis() >= cutoffCal.timeInMillis
    }

    internal fun nextOpenSchoolDatesWib(
        count: Int = 3,
        cutoffHour: Int = 20,
        allowWeekend: Boolean = false,
    ): List<String> {
        val out = mutableListOf<String>()
        for (offset in 1..30) {
            val iso = wibDateStringOffset(offset)
            if (!allowWeekend && isWeekendWib(iso)) continue
            if (isPastPoCutoffWib(iso, cutoffHour)) continue
            out.add(iso)
            if (out.size >= count) break
        }
        return out
    }

    suspend fun fetchPoSlotAvailability(menuItemId: String): PoAvailabilityResponse? = runCatching {
        val params = mapOf("p_menu_item_id" to kotlinx.serialization.json.JsonPrimitive(menuItemId))
        client.postgrest.rpc("po_slot_availability", params).decodeAs<PoAvailabilityResponse>()
    }.getOrNull()

    /** Riwayat order pembeli yang sedang masuk (dipakai layar Pesanan pembeli). */
    suspend fun fetchMyOrders(max: Int = 20): List<AdminOrder> {
        val uid = SupabaseRepository.currentUid() ?: return emptyList()
        val rows = client.postgrest.from("orders")
            .select(orderColumns) {
                filter { eq("user_id", uid) }
                order("created_at", Order.DESCENDING)
                limit(max.toLong())
            }
            .decodeList<OrderRow>()
        return hydrate(rows)
    }

    /**
     * Ambil statistik penjualan terbanyak & paling sering dibeli dari semua pembeli lewat RPC server
     * (`top_selling_items`) agar tidak terblokir RLS `orders` per-pembeli.
     */
    suspend fun fetchTopSellingStats(limit: Int = 20): Map<String, SalesStat> = runCatching {
        val rows = client.postgrest.rpc(
            "top_selling_items",
            buildJsonObject { put("p_limit", limit) },
        ).decodeList<TopSellingRow>()
        val stats = HashMap<String, SalesStat>()
        rows.forEach { row ->
            if (row.totalQty <= 0) return@forEach
            val key = row.menuItemId?.takeIf { it.isNotBlank() } ?: row.normName.trim().lowercase()
            if (key.isNotBlank()) {
                val prev = stats[key]
                stats[key] = SalesStat(
                    totalQty = (prev?.totalQty ?: 0) + row.totalQty,
                    orderCount = (prev?.orderCount ?: 0) + row.orderCount,
                )
            }
        }
        stats
    }.getOrDefault(emptyMap())

    private suspend fun resolveBuyerNames(rows: List<OrderRow>): Map<String, String> {
        val userIds = rows.map { it.userId }.distinct()
        if (userIds.isEmpty()) return emptyMap()
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("id", "display_name", "email")) {
                    filter { isIn("id", userIds.map { id -> "\"$id\"" }) }
                }
                .decodeList<ProfileRow>()
                .associate { profile ->
                    profile.id to (
                        profile.displayName?.takeIf { it.isNotBlank() }
                            ?: profile.email?.substringBefore('@')
                            ?: "Pembeli"
                        )
                }
        }.getOrElse { emptyMap() }
    }

    /** Awal hari (waktu lokal perangkat) dalam format ISO-8601 UTC; tanpa '+' agar aman di query string. */
    private fun todayStartIso(): String {
        val localMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(localMidnight.time)
    }

    /**
     * Jam tampil (HH:mm waktu perangkat) dari timestamp ISO PostgREST.
     * Bagian tanggal dibaca sebagai UTC karena kolom timestamptz disimpan UTC;
     * hasil akhir diformat dengan zona perangkat. Dipanggil sekali saat fetch,
     * bukan tiap frame, karena SimpleDateFormat tidak thread-safe.
     */
    private fun formatOrderTime(createdAt: String): String {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(createdAt.take(19))
        }.getOrNull()
        if (parsed != null) return SimpleDateFormat("HH:mm", Locale.US).format(parsed)
        return createdAt.substringAfter('T', "").take(5)
    }
}

internal fun formatShortIdDate(isoDate: String): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate.take(10))
    }.getOrNull() ?: return isoDate.take(10)
    return SimpleDateFormat("d MMM", Locale("id", "ID")).format(parsed)
}

internal fun formatCalendarShortChip(isoDate: String): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate.take(10))
    }.getOrNull() ?: return isoDate.take(10)
    return SimpleDateFormat("EEE, d MMM", Locale("id", "ID")).format(parsed)
}

internal fun formatCalendarFullIdDate(isoDate: String): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate.take(10))
    }.getOrNull() ?: return isoDate.take(10)
    return SimpleDateFormat("EEEE, d MMM yyyy", Locale("id", "ID")).format(parsed)
}

/**
 * Mengembalikan pesan alasan bila status pesanan belum boleh dimajukan ke `nextStatus`:
 * - Semua pesanan QRIS (Hari Ini maupun Pre-Order) wajib diverifikasi lunas dulu sebelum diterima/diproses.
 * - Pesanan Pre-Order baru bisa disiapkan/diselesaikan pada Hari-H.
 */
internal fun sellerPoAdvanceBlockReason(order: AdminOrder, nextStatus: String): String? {
    val target = nextStatus.lowercase()
    if (order.paymentMethod.equals("qris", ignoreCase = true)
        && target in setOf(STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY, STATUS_READY, STATUS_COMPLETED)
        && !order.paymentStatus.equals("paid", ignoreCase = true)
    ) {
        return "Menunggu pembayaran Midtrans lunas otomatis sebelum menerima pesanan."
    }
    if (!order.isPreOrder) return null
    if (target !in setOf(STATUS_PREPARING, STATUS_PROCESSING_LEGACY, STATUS_READY, STATUS_COMPLETED)) return null
    val today = OrderRepository.todayWibDateString()
    if (order.pickupDate.isNotBlank() && order.pickupDate > today) {
        return "Pesanan PO untuk ${formatCalendarShortChip(order.pickupDate)} baru bisa disiapkan pada Hari-H."
    }
    return null
}

/**
 * Penjual TIDAK boleh membatalkan pesanan yang sudah lunas ATAU pesanan QRIS yang buktinya
 * sudah diunggah pembeli (`waiting_verification` / `paid` / `rejected`) untuk mencegah penipuan.
 */
internal fun sellerCanCancelOrder(order: AdminOrder): Boolean {
    if (com.jajanin.app.core.ui.isTerminalStatus(order.status)) return false
    val pay = order.paymentStatus.lowercase()
    if (pay == "paid") return false
    val isQris = order.paymentMethod.equals("qris", ignoreCase = true)
    if (isQris && (pay == "waiting_verification" || pay == "rejected" || order.paymentProofUrl.isNotBlank())) {
        return false
    }
    return true
}

/**
 * Pembeli hanya boleh membatalkan pesanan hari-ini yang masih pending & belum dibayar/diunggah bukti QRIS.
 */
internal fun buyerCanCancelOrder(order: AdminOrder): Boolean {
    if (order.isPreOrder) return false
    if (!order.status.equals(STATUS_PENDING, ignoreCase = true)) return false
    val pay = order.paymentStatus.lowercase()
    if (pay == "paid" || pay == "waiting_verification") return false
    if (order.paymentMethod.equals("qris", ignoreCase = true) && order.paymentProofUrl.isNotBlank()) return false
    return true
}

/** Parse timestamp ISO-8601 Postgres (UTC / +00:00 / Z) ke epoch millis. */
internal fun parseIsoInstantMillis(raw: String?): Long? {
    val s = raw?.trim()?.takeIf { it.length >= 19 } ?: return null
    val base = s.substring(0, 19) // yyyy-MM-dd'T'HH:mm:ss
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(base)?.time
    }.getOrNull()
}

/** Sisa detik pembayaran QRIS (<= 0 jika sudah lewat batas 10 menit), atau null bila tidak ada batas waktu. */
internal fun remainingPaymentSeconds(expiresAtIso: String?): Long? {
    val expMs = parseIsoInstantMillis(expiresAtIso) ?: return null
    return ((expMs - System.currentTimeMillis()) / 1000L)
}

internal fun formatCountdownMmSs(seconds: Long): String {
    if (seconds <= 0L) return "00:00"
    val m = seconds / 60L
    val s = seconds % 60L
    return String.format(Locale.US, "%02d:%02d", m, s)
}
