package com.jajanin.app

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Batas bawah tiap level (index 0 = Level 1). Level 10: 12000+ (prestise). */
internal val EXP_THRESHOLDS = listOf(0, 100, 250, 500, 1000, 2000, 3500, 5500, 8000, 12000)

internal const val EXP_MAX_LEVEL = 10

/** Level 1..10 dari total EXP (negatif dijepit ke 0). */
internal fun expLevel(exp: Int): Int {
    val e = exp.coerceAtLeast(0)
    var level = 1
    for (i in EXP_THRESHOLDS.indices) {
        if (e >= EXP_THRESHOLDS[i]) level = i + 1
    }
    return level
}

/** Batas bawah level saat ini. */
internal fun expLevelStart(level: Int): Int =
    EXP_THRESHOLDS[(level - 1).coerceIn(0, EXP_THRESHOLDS.lastIndex)]

/** Batas untuk naik ke level berikut; null bila sudah Level 10 (prestise). */
internal fun expNextAt(level: Int): Int? =
    if (level >= EXP_MAX_LEVEL) null else EXP_THRESHOLDS[level.coerceIn(1, EXP_MAX_LEVEL - 1)]

/** Satu baris buku besar EXP milik sendiri (baca saja, RLS milik sendiri). */
@Serializable
internal data class ExpEntry(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("pickup_code") val pickupCode: String = "",
    val exp: Int = 0,
    val reason: String = "",
    @SerialName("balance_after") val balanceAfter: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

internal object ExpRepository {
    private val client get() = SupabaseRepository.client

    @Serializable
    private data class BalanceRow(@SerialName("exp_balance") val expBalance: Int = 0)

    /** Saldo EXP milik sendiri. Tidak pernah ditulis dari client. */
    suspend fun myBalance(): Int {
        val uid = SupabaseRepository.currentUid() ?: return 0
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("exp_balance")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<BalanceRow>()?.expBalance ?: 0
        }.getOrDefault(0)
    }

    /** N entri terbaru (terbaru dulu). RLS memastikan hanya milik sendiri. */
    suspend fun myHistory(limit: Long): List<ExpEntry> {
        if (!SupabaseRepository.hasSession()) return emptyList()
        return runCatching {
            client.postgrest.from("exp_ledger")
                .select(Columns.list("id", "user_id", "order_id", "pickup_code", "exp", "reason", "balance_after", "created_at")) {
                    order("created_at", Order.DESCENDING)
                    limit(limit)
                }
                .decodeList<ExpEntry>()
        }.getOrDefault(emptyList())
    }
}
