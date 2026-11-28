package com.jajanin.app.data.model

import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.formatCalendarShortChip
import com.jajanin.app.data.repository.formatShortIdDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal val DEFAULT_PICKUP_SESSIONS = listOf("Istirahat 1", "Istirahat 2", "Pulang")
internal const val CART_VARIANT_SEP = "||"
internal const val MAX_PRODUCT_IMAGES = 5

internal fun cartKeyOf(productId: String, variantLabel: String = ""): String {
    val cleanVar = variantLabel.trim()
    return if (cleanVar.isBlank()) productId else "$productId$CART_VARIANT_SEP$cleanVar"
}

internal fun productIdFromCartKey(key: String): String = key.substringBefore(CART_VARIANT_SEP)

internal fun variantLabelFromCartKey(key: String): String =
    if (CART_VARIANT_SEP in key) key.substringAfter(CART_VARIANT_SEP).trim() else ""

internal fun totalQtyForProductInCart(cart: Map<String, Int>, productId: String): Int =
    cart.entries.sumOf { (k, q) -> if (productIdFromCartKey(k) == productId) q else 0 }

/** Satu bagian pilihan varian pada produk (mis. Section "Rasa" dengan pilihan ["Cokelat", "Stroberi"]). */
@Serializable
internal data class ProductVariantSection(
    val name: String = "Rasa",
    val options: List<String> = emptyList(),
) {
    val cleanName: String
        get() = name.trim().ifBlank { "Varian" }

    val cleanOptions: List<String>
        get() = options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

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
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("is_available") val available: Boolean = true,
    @SerialName("stock") val stock: Int? = null,
    @SerialName("stall_id") val stallId: String,
    @SerialName("ingredients") val ingredients: String = "",
    @SerialName("order_schedule") val orderSchedule: String = "both",
    @SerialName("pickup_sessions") val pickupSessions: String = "Istirahat 1,Istirahat 2,Pulang",
    @SerialName("po_dates") val poDates: String = "",
    @SerialName("po_cutoff_hour") val poCutoffHour: Int = 20,
    @SerialName("po_daily_quota") val poDailyQuota: Int? = null,
    @SerialName("po_require_qris") val poRequireQris: Boolean = false,
    @SerialName("po_allow_weekend") val poAllowWeekend: Boolean = false,
    @SerialName("variant_sections") val variantSections: List<ProductVariantSection> = emptyList(),
) {
    /** Semua URL foto produk (maks 5, fallback ke imageUrl tunggal untuk data lama). */
    val allImageUrls: List<String>
        get() = buildList {
            imageUrls.forEach { raw ->
                val u = raw.trim()
                if (u.startsWith("https://") && u !in this) add(u)
            }
            val single = imageUrl?.trim().orEmpty()
            if (single.startsWith("https://") && single !in this) add(0, single)
        }.take(MAX_PRODUCT_IMAGES)

    /** Daftar section varian aktif yang memiliki minimal 1 pilihan. */
    val activeVariantSections: List<ProductVariantSection>
        get() = variantSections.mapNotNull { sec ->
            val opts = sec.cleanOptions
            if (opts.isEmpty()) null else ProductVariantSection(sec.cleanName, opts)
        }

    val hasVariants: Boolean
        get() = activeVariantSections.isNotEmpty()

    /** Ringkasan singkat varian untuk kartu menu, mis. "Rasa: Cokelat, Stroberi (+2)". */
    val variantsPreviewSummary: String
        get() {
            val first = activeVariantSections.firstOrNull() ?: return ""
            val shown = first.options.take(3).joinToString(", ")
            val more = if (first.options.size > 3) " +${first.options.size - 3}" else ""
            val extraSec = if (activeVariantSections.size > 1) " · +${activeVariantSections.size - 1} pilihan" else ""
            return "${first.name}: $shown$more$extraSec"
        }

    /** Ruangan pengambilan yang disematkan pada produk (jika ada). */
    val pickupRoom: String
        get() = extractRoomTag(ingredients)

    /** Bahan produk bersih tanpa tag ruangan internal. */
    val cleanIngredients: String
        get() = stripRoomTag(ingredients)

    val allowsSameDay: Boolean
        get() = orderSchedule == "same_day" || orderSchedule == "both"

    val allowsNextDay: Boolean
        get() = orderSchedule == "next_day" || orderSchedule == "both"

    /** Daftar tanggal PO (YYYY-MM-DD) yang valid dan akan datang (besok s/d +30 hari, hari sekolah kecuali allowWeekend). */
    val parsedPoDates: List<String>
        get() = poDates
            .split(',')
            .map { it.trim() }
            .filter { iso ->
                OrderRepository.isValidPreOrderDate(iso) &&
                    (poAllowWeekend || !OrderRepository.isWeekendWib(iso))
            }
            .distinct()
            .sorted()

    /** Tanggal PO yang masih dibuka pemesanannya (belum lewat batas jam cutoff H-1 WIB). */
    val openParsedPoDates: List<String>
        get() = parsedPoDates.filter { !OrderRepository.isPastPoCutoffWib(it, poCutoffHour) }

    /** Ringkasan tanggal kalender PO untuk ditampilkan di detail produk. */
    val poDatesDisplaySummary: String
        get() {
            val dates = openParsedPoDates.ifEmpty { parsedPoDates }
            if (dates.isEmpty()) return ""
            val shown = dates.take(3).joinToString(" · ") { formatCalendarShortChip(it) }
            return if (dates.size > 3) "$shown (+${dates.size - 3} tgl)" else shown
        }

    val scheduleBadgeLabel: String
        get() {
            val dates = parsedPoDates
            return when (orderSchedule) {
                "same_day" -> "Hari Ini"
                "next_day" -> when {
                    dates.size == 1 -> "PO ${formatShortIdDate(dates[0])}"
                    dates.size > 1 -> "PO ${formatShortIdDate(dates[0])} +${dates.size - 1}"
                    else -> "Pre-Order (PO)"
                }
                else -> when {
                    dates.isNotEmpty() -> "Hari Ini / PO ${formatShortIdDate(dates[0])}"
                    else -> "Hari Ini / PO"
                }
            }
        }

    val parsedPickupSessions: List<String>
        get() = pickupSessions
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_PICKUP_SESSIONS }
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
