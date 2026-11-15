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
)
