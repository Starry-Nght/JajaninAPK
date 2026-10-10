package com.jajanin.app

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

/** Nomor HP sendiri: validasi + normalisasi di server (migrasi buyer_seller_chat_and_phones). */
internal object PhoneRepository {
    private val client get() = SupabaseRepository.client

    suspend fun myPhone(): String? = runCatching {
        client.postgrest.rpc("my_phone").decodeAsOrNull<String>()
    }.getOrNull()

    suspend fun setMyPhone(phone: String): String {
        val params = mapOf("p_phone" to kotlinx.serialization.json.JsonPrimitive(phone.trim()))
        return client.postgrest.rpc("set_my_phone", params).decodeAs<String>()
    }

    /**
     * Normalisasi ringan di client (server memvalidasi ulang): 08.. -> +62..,
     * 62.. -> +62... Null bila jelas bukan nomor HP Indonesia.
     */
    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        val norm = when {
            digits.startsWith("+") -> "+" + digits.drop(1).filter(Char::isDigit)
            digits.startsWith("0") -> "+62" + digits.drop(1)
            digits.startsWith("62") -> "+$digits"
            else -> return null
        }
        val after = norm.removePrefix("+62")
        if (!norm.startsWith("+62")) return null
        if (after.isEmpty() || after[0] !in '2'..'9') return null
        if (after.length !in 8..13) return null
        return norm
    }
}
