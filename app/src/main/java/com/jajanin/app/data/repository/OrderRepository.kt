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
    val status: String,
    @SerialName("total_idr") val totalIdr: Int,
    @SerialName("pickup_code") val pickupCode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
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
internal data class RealtimeOrderPatch(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val status: String? = null,
    @SerialName("payment_status") val paymentStatus: String? = null,
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
) {
    val itemCount: Int
        get() = items.sumOf { it.quantity }

    val itemsSummary: String
        get() = items.joinToString("  ·  ") { "${it.quantity}× ${it.itemName}" }
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
private data class PaymentPatch(@SerialName("payment_status") val paymentStatus: String)

@Serializable
internal data class PlacedOrder(
    val id: String,
    @SerialName("pickup_code") val pickupCode: String,
    @SerialName("total_idr") val totalIdr: Int,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    val status: String = "pending",
)

@Serializable
internal data class StallInfo(
    val id: String,
    val name: String,
    val location: String = "",
    val description: String = "",
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("qris_image_url") val qrisImageUrl: String? = null,
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
        "id,user_id,status,total_idr,pickup_code,created_at,payment_method,payment_status," +
            "order_items(id,menu_item_id,item_name,unit_price_idr,quantity)"
    )

    private val productColumns = Columns.raw(
        "id,name,description,category,price_idr,image_url,is_available,stock,stall_id,ingredients"
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
            draft.stock?.let { put("stock", it) }
        }
        return client.postgrest.from("menu_items").insert(payload) {
            select(productColumns)
        }.decodeSingle<ProductRow>()
    }

    suspend fun updateProduct(productId: String, draft: ProductDraft) {
        val patch = buildJsonObject {
            put("name", draft.name)
            put("description", draft.description)
            put("category", draft.category)
            put("price_idr", draft.priceIdr)
            put("image_url", draft.imageUrl)
            put("is_available", draft.available)
            put("ingredients", draft.ingredients)
            put("stock", draft.stock)
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
    suspend fun placeOrder(lines: List<Pair<String, Int>>, paymentMethod: String): PlacedOrder {
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
        val params = mapOf(
            "p_items" to array,
            "p_payment_method" to kotlinx.serialization.json.JsonPrimitive(method),
        )
        return try {
            // place_order mengembalikan satu objek jsonb telanjang (bukan array).
            client.postgrest.rpc("place_order", params).decodeAs<PlacedOrder>()
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
            clean.contains("tidak valid", true) -> clean.take(160)
            clean.contains("tidak ditemukan", true) -> "Menu tidak ditemukan."
            clean.contains("tidak tersedia", true) -> clean.take(160)
            clean.contains("Stok", true) -> clean.take(160)
            else -> SupabaseRepository.errorMessage(t)
        }
    }

    /**
     * Mengubah status satu order (dipakai aksi tombol detail admin).
     *
     * Query UPDATE dengan RETURNING dipakai sebagai verifikasi: bila RLS
     * menyaring baris (bukan admin) atau id tidak ada, hasilnya kosong dan
     * perubahan dianggap gagal.
     */
    suspend fun updateOrderStatus(orderId: String, status: String) {
        val result = client.postgrest.from("orders")
            .update(StatusPatch(status)) {
                select()
                filter { eq("id", orderId) }
            }
        if (result.decodeList<OrderRow>().isEmpty()) {
            error("Perubahan status gagal disimpan. Pastikan kamu masih masuk sebagai admin.")
        }
    }

    /**
     * Pembeli mengajukan bukti bayar QRIS: unpaid|rejected -> waiting_verification.
     * Trigger DB menolak perubahan lain; penjual yang memverifikasi.
     */
    suspend fun submitPaymentProof(orderId: String) {
        val result = client.postgrest.from("orders")
            .update(PaymentPatch("waiting_verification")) {
                select(Columns.raw("id"))
                filter { eq("id", orderId) }
            }
        if (result.decodeList<IdRow>().isEmpty()) {
            error("Konfirmasi bayar gagal disimpan.")
        }
    }

    /** Penjual menandai lunas (tunai saat ambil, atau verifikasi QRIS). paid_at + verified_by diisi server. */
    suspend fun markOrderPaid(orderId: String) {
        val result = client.postgrest.from("orders")
            .update(PaymentPatch("paid")) {
                select(Columns.raw("id"))
                filter { eq("id", orderId) }
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

    /** Info publik satu warung (nama, ruangan ambil, kontak penjual, & QRIS) untuk layar pembeli. */
    suspend fun fetchStall(stallId: String): StallInfo? = runCatching {
        client.postgrest.from("stalls")
            .select(Columns.raw("id,name,location,description,owner_id,qris_image_url")) {
                filter { eq("id", stallId) }
                limit(1)
            }
            .decodeSingleOrNull<StallInfo>()
    }.getOrNull()

    /** Warung milik penjual ini beserta ruangan ambil, kontak, & QRIS-nya. */
    suspend fun fetchMyStall(): StallInfo? {
        val uid = SupabaseRepository.currentUid() ?: return null
        return runCatching {
            client.postgrest.from("stalls")
                .select(Columns.raw("id,name,location,description,owner_id,qris_image_url")) {
                    filter { eq("owner_id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<StallInfo>()
        }.getOrNull()
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
        // Ambil SEMUA pesanan aktif (belum selesai/batal) dari hari apa pun,
        // ditambah pesanan yang selesai/dibatalkan hari ini.
        val active = runCatching {
            client.postgrest.from("orders").select(orderColumns) {
                filter {
                    isIn("status", listOf(STATUS_PENDING, STATUS_CONFIRMED, STATUS_PREPARING, STATUS_PROCESSING_LEGACY, STATUS_READY))
                }
                order("created_at", Order.DESCENDING)
                limit(50)
            }.decodeList<OrderRow>()
        }.getOrDefault(emptyList())

        val todayDone = runCatching {
            client.postgrest.from("orders").select(orderColumns) {
                filter {
                    gte("created_at", todayStartIso())
                    isIn("status", listOf(STATUS_COMPLETED, STATUS_CANCELLED))
                }
                order("created_at", Order.DESCENDING)
                limit(50)
            }.decodeList<OrderRow>()
        }.getOrDefault(emptyList())

        val combined = (active + todayDone).distinctBy { it.id }
        return hydrate(combined)
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

        val stallIds = menuLookup.values.map { it.stallId }.distinct()
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
            val stall = menuRow?.let { stallLookup[it.stallId] }
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
            )
        }
    }

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
     * Hitung jumlah pembelian per menu dalam 3 hari terakhir (72 jam).
     * Mengembalikan peta key (menu_item_id & nama lowercase) -> total quantity.
     */
    suspend fun fetchTopSelling3Days(): Map<String, Int> = runCatching {
        val sinceIso = daysAgoIso(3)
        val rows = client.postgrest.from("orders")
            .select(orderColumns) {
                filter {
                    gte("created_at", sinceIso)
                    neq("status", STATUS_CANCELLED)
                }
                order("created_at", Order.DESCENDING)
                limit(120)
            }
            .decodeList<OrderRow>()

        val counts = HashMap<String, Int>()
        rows.forEach { order ->
            order.items.forEach { item ->
                val qty = item.quantity.coerceAtLeast(1)
                item.menuItemId?.takeIf { it.isNotBlank() }?.let { id ->
                    counts[id] = (counts[id] ?: 0) + qty
                }
                val nameKey = item.itemName.trim().lowercase()
                if (nameKey.isNotBlank()) {
                    counts[nameKey] = (counts[nameKey] ?: 0) + qty
                }
            }
        }
        counts
    }.getOrDefault(emptyMap())

    private fun daysAgoIso(days: Int): String {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -days)
        }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(cal.time)
    }

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
