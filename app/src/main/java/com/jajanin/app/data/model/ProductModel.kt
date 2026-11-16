package com.jajanin.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal data class MenuItem(val name: String, val stall: String, val price: String, val rating: String, val image: Int, val category: String) {
    val priceInRupiah: Int
        get() = price.filter(Char::isDigit).toIntOrNull() ?: 0
}

@Serializable
internal data class ProductRow(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String,
    @SerialName("price_idr") val priceIdr: Int,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("is_available") val available: Boolean = true,
    @SerialName("stock") val stock: Int? = null,
    @SerialName("stall_id") val stallId: String,
    @SerialName("ingredients") val ingredients: String = "",
) {
    /** Ruangan pengambilan yang disematkan pada produk (jika ada). */
    val pickupRoom: String
        get() = extractRoomTag(ingredients)

    /** Bahan produk bersih tanpa tag ruangan internal. */
    val cleanIngredients: String
        get() = stripRoomTag(ingredients)
}

private val ROOM_TAG_REGEX = Regex("^\\[Ruang:\\s*([^\\]]+)\\]\\s*")

internal fun extractRoomTag(raw: String): String =
    ROOM_TAG_REGEX.find(raw.trim())?.groupValues?.getOrNull(1)?.trim().orEmpty()

internal fun stripRoomTag(raw: String): String =
    raw.trim().replaceFirst(ROOM_TAG_REGEX, "").trim()

internal fun packRoomAndIngredients(room: String, ingredients: String): String {
    val cleanRoom = room.trim().replace("]", "")
    val cleanIng = stripRoomTag(ingredients)
    return when {
        cleanRoom.isBlank() -> cleanIng
        cleanIng.isBlank() -> "[Ruang: $cleanRoom]"
        else -> "[Ruang: $cleanRoom] $cleanIng"
    }
}
