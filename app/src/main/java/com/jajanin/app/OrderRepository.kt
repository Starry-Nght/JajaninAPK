package com.jajanin.app

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
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
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

/** Model siap-tampil untuk layar admin: nama pembeli dan jam sudah diresolvisi saat fetch. */
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
    @SerialName("qris_image_url") val qrisImageUrl: String? = null,
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

    fun observeProducts(scope: CoroutineScope, onChange: () -> Unit): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel("available-menu-items")
        val flow = channel.postgresChangeFlow<PostgresAction>("public") { table = "menu_items" }
        val job = scope.launch {
            launch { flow.collect { onChange() } }
            channel.subscribe()
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
        val result = client.postgrest.from("menu_items").delete {
            select(Columns.raw("id"))
            filter { eq("id", productId) }
        }
        if (result.decodeList<IdRow>().isEmpty()) error("Produk gagal dihapus. Coba lagi.")
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

    /** Info publik satu warung (nama + QR QRIS) untuk layar bayar pembeli. */
    suspend fun fetchStall(stallId: String): StallInfo? = runCatching {
        client.postgrest.from("stalls")
            .select(Columns.raw("id,name,qris_image_url")) {
                filter { eq("id", stallId) }
                limit(1)
            }
            .decodeSingleOrNull<StallInfo>()
    }.getOrNull()

    /** Warung milik penjual ini beserta QRIS-nya (untuk pengaturan). */
    suspend fun fetchMyStall(): StallInfo? {
        val uid = SupabaseRepository.currentUid() ?: return null
        return runCatching {
            client.postgrest.from("stalls")
                .select(Columns.raw("id,name,qris_image_url")) {
                    filter { eq("owner_id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<StallInfo>()
        }.getOrNull()
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
        val rows = client.postgrest.from("orders")
            .select(orderColumns) {
                filter { gte("created_at", todayStartIso()) }
                order("created_at", Order.DESCENDING)
                limit(50)
            }
            .decodeList<OrderRow>()
        return hydrate(rows)
    }

    /** Mengambil satu order berdasar id (dipakai deep-link notifikasi). Null bila tak terlihat oleh RLS. */
    suspend fun fetchOrderById(orderId: String): AdminOrder? {
        val row = client.postgrest.from("orders")
            .select(orderColumns) {
                filter { eq("id", orderId) }
                limit(1)
            }
            .decodeSingleOrNull<OrderRow>() ?: return null
        return hydrate(listOf(row)).firstOrNull()
    }

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
     * Berlangganan perubahan tabel orders lewat Supabase Realtime.
     *
     * [onChange] dipanggil di thread [scope] untuk setiap insert/update/delete;
     * pemicu refresh daftar, bukan membawa payload (daftar di-fetch ulang utuh).
     */
    fun observeOrders(scope: CoroutineScope, onChange: () -> Unit): OrderObserver {
        val channel = SupabaseRepository.client.realtime.channel("admin-orders")
        val flow = channel.postgresChangeFlow<PostgresAction>("public") { table = "orders" }
        val job = scope.launch {
            launch { flow.collect { onChange() } }
            channel.subscribe()
        }
        return OrderObserver(channel, job, scope)
    }

    private suspend fun hydrate(rows: List<OrderRow>): List<AdminOrder> {
        val buyerNames = resolveBuyerNames(rows)
        return rows.map { row ->
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
