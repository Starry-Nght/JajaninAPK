package com.jajanin.app

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Satu baris order dengan item yang di-embed PostgREST lewat relasi `order_items`. */
@Serializable
internal data class OrderRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val status: String,
    @SerialName("total_idr") val totalIdr: Int,
    @SerialName("pickup_code") val pickupCode: String,
    @SerialName("created_at") val createdAt: String,
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
private data class NewOrder(
    val id: String,
    @SerialName("user_id") val userId: String,
    val status: String,
    @SerialName("total_idr") val totalIdr: Int,
)

@Serializable
private data class NewOrderItem(
    @SerialName("order_id") val orderId: String,
    @SerialName("menu_item_id") val menuItemId: String?,
    @SerialName("item_name") val itemName: String,
    @SerialName("unit_price_idr") val unitPriceIdr: Int,
    val quantity: Int,
)

@Serializable
private data class StatusPatch(val status: String)

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
        "id,user_id,status,total_idr,pickup_code,created_at," +
            "order_items(id,menu_item_id,item_name,unit_price_idr,quantity)"
    )

    /**
     * Membuat order 'pending' dari keranjang checkout, lalu itemnya.
     *
     * Order ditulis lebih dulu karena order_items bergantung pada foreign key
     * order; bila penyisipan item gagal, order dibersihkan kembali agar admin
     * tidak melihat order kosong (best-effort, di bawah policy hapus pending).
     * Mengembalikan id order untuk dipakai layar QRIS.
     */
    suspend fun createOrder(itemName: String, unitPriceIdr: Int, quantity: Int): String {
        val uid = SupabaseRepository.currentUid()
            ?: error("Belum masuk. Silakan masuk kembali sebelum membayar.")
        val orderId = UUID.randomUUID().toString()
        val total = unitPriceIdr * quantity + SERVICE_FEE
        client.postgrest.from("orders")
            .insert(NewOrder(orderId, uid, "pending", total), defaultToNull = false)
        try {
            client.postgrest.from("order_items")
                .insert(
                    listOf(NewOrderItem(orderId, null, itemName, unitPriceIdr, quantity)),
                    defaultToNull = false,
                )
        } catch (t: Throwable) {
            runCatching {
                client.postgrest.from("orders").delete { filter { eq("id", orderId) } }
            }
            throw t
        }
        return orderId
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
     * Mengambil order hari ini (maksimal 50, terbaru dahulu) lengkap dengan
     * nama pembeli yang di-hydrate terpisah karena orders tidak punya relasi
     * FK langsung ke profiles.
     */
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
            )
        }
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
