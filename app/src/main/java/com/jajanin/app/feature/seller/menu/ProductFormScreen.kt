package com.jajanin.app.feature.seller.menu

import com.jajanin.app.feature.seller.*

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.util.formatRupiah

/**
 * Form tambah/ubah produk sebagai HALAMAN (bukan dialog timpa): masuk dengan
 * geser, kembali via toolbar/back-stack. Bidang memakai gaya input nyata dengan
 * label di atas, bukan baris kartu ber-chevron. Simpan tetap di CTA bawah.
 */
internal fun JajaninView.drawProductForm(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val editing = productEditId != null
    val off = -scroll
    var y = SellerTokens.CONTENT_TOP + off

    // --- Informasi produk ---
    sellerSection(c, "Informasi produk", y + 14f)
    y += 30f
    y = sellerFormField(
        c, "Nama produk", productName, "cth. Nasi goreng", "pfName", y,
    )
    y = categoryPicker(c, y)
    y = descriptionField(c, y)
    y = ingredientsField(c, y)

    // --- Harga & stok ---
    y += 8f
    sellerSection(c, "Harga & stok", y + 14f)
    y += 30f
    y = priceAndStock(c, y)

    // --- Foto produk ---
    sellerSection(c, "Foto produk", y + 14f)
    y += 30f
    y = photoPicker(c, y)
    if (productImageUploading) postInvalidateDelayed(60)

    // --- Visibilitas ---
    sellerSection(c, "Visibilitas", y + 14f)
    y += 30f
    y = visibilityRow(c, y)

    if (editing) {
        y += 18f
        y = deleteRow(c, y)
    }

    contentHeight = y - off + 24f

    // CTA bawah tetap, tidak tertutup system navigation (h sudah dikurangi inset).
    if (productError.isNotBlank()) {
        kit.textEllipsized(c, productError, 20f, h - 84f, 350f, 10.5f, kit.danger, true)
    }
    val cta = RectF(20f, h - 66f, w - 20f, h - 12f)
    when {
        productImageUploading -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Mengunggah gambar...", cta, 14f, Color.WHITE, true)
        }
        productSaving -> {
            kit.rounded(c, cta, kit.muted, 14f)
            kit.centerText(c, "Menyimpan...", cta, 14f, Color.WHITE, true)
        }
        else -> {
            kit.button(c, if (editing) "Simpan perubahan" else "Simpan produk", cta)
            press(c, "pfSave", cta, 0f)
            bounds["pfSave"] = RectF(cta)
        }
    }
    sellerHeader(c, if (editing) "Ubah produk" else "Tambah produk")
}

private fun JajaninView.categoryPicker(c: Canvas, top: Float): Float {
    kit.text(c, "Kategori", 22f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    if (productCategory != "Food" && productCategory != "Drinks") {
        kit.textRight(c, "Saat ini: $productCategory", 368f, top + 10f, 150f, 9.5f, kit.muted)
    }
    val choices = listOf(
        "Food" to "Makanan",
        "Drinks" to "Minuman",
    )
    val segmentW = SellerTokens.WIDTH / 2f
    choices.forEachIndexed { index, (value, label) ->
        val left = SellerTokens.PAD + index * segmentW
        val seg = RectF(left, top + 18f, left + segmentW, top + 62f)
        val active = productCategory == value
        if (active) {
            kit.rounded(c, seg, kit.tint, SellerTokens.RADIUS_CONTROL)
        } else {
            kit.rounded(c, seg, kit.card, SellerTokens.RADIUS_CONTROL, kit.border)
        }
        kit.centerText(c, label, seg, 12f, if (active) kit.blue else kit.muted, active)
        press(c, "pfCat:$value", seg, 0f)
        bounds["pfCat:$value"] = RectF(seg)
    }
    return top + 62f + 12f
}

private fun JajaninView.descriptionField(c: Canvas, top: Float): Float {
    kit.text(c, "Deskripsi", 22f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(20f, top + 18f, 370f, top + 82f)
    kit.rounded(
        c, field, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfDesc") kit.blue else kit.border,
    )
    if (productDescription.isBlank()) {
        kit.text(c, "Tulis deskripsi singkat produk", 34f, top + 44f, 13f, kit.muted)
    } else {
        kit.textWrapped(
            c, productDescription, 34f, top + 36f, 322f, 13f, kit.ink,
            maxLines = 2, lineHeight = 20f,
        )
    }
    press(c, "pfDesc", field, 0f)
    bounds["pfDesc"] = RectF(field)
    return top + 82f + 12f
}

private fun JajaninView.ingredientsField(c: Canvas, top: Float): Float {
    kit.text(c, "Bahan", 22f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(20f, top + 18f, 370f, top + 70f)
    kit.rounded(
        c, field, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfIngredients") kit.blue else kit.border,
    )
    if (productIngredients.isBlank()) {
        kit.text(c, "Opsional — bahan utama", 34f, top + 44f, 13f, kit.muted)
    } else {
        kit.textWrapped(
            c, productIngredients, 34f, top + 36f, 322f, 13f, kit.ink,
            maxLines = 2, lineHeight = 18f,
        )
    }
    press(c, "pfIngredients", field, 0f)
    bounds["pfIngredients"] = RectF(field)
    return top + 70f + 12f
}

private fun JajaninView.priceAndStock(c: Canvas, top: Float): Float {
    val half = 171f
    val gap = 8f
    val priceText = productPrice.toIntOrNull()?.let { formatRupiah(it) }
        ?: "cth. 12000"
    val stockText = productStock.toIntOrNull()?.toString() ?: "Tanpa batas"
    val priceField = RectF(20f, top + 18f, 20f + half, top + 66f)
    val stockField = RectF(20f + half + gap, top + 18f, 370f, top + 66f)

    kit.text(c, "Harga (Rp)", 22f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    kit.rounded(
        c, priceField, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfPrice") kit.blue else kit.border,
    )
    kit.textEllipsized(
        c, priceText, 34f, priceField.centerY() + 4.5f,
        half - 28f, 14f, if (productPrice.toIntOrNull() != null) kit.ink else kit.muted,
        productPrice.toIntOrNull() != null,
    )
    press(c, "pfPrice", priceField, 0f)
    bounds["pfPrice"] = RectF(priceField)

    kit.text(c, "Stok", 20f + half + gap + 2f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    kit.rounded(
        c, stockField, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfStock") kit.blue else kit.border,
    )
    kit.textEllipsized(
        c, stockText, stockField.left + 14f, stockField.centerY() + 4.5f,
        stockField.width() - 28f, 14f, if (productStock.toIntOrNull() != null) kit.ink else kit.muted,
        productStock.toIntOrNull() != null,
    )
    press(c, "pfStock", stockField, 0f)
    bounds["pfStock"] = RectF(stockField)
    return top + 66f + 12f
}

private fun JajaninView.photoPicker(c: Canvas, top: Float): Float {
    val panel = RectF(20f, top, 370f, top + 124f)
    sellerSurface(c, panel)
    val thumbUrl = productImageUrl.takeIf { it.startsWith("https://") }
    val thumb = thumbUrl?.let { ProductImageCache.images[it] }
    val preview = RectF(32f, top + 16f, 124f, top + 108f)
    if (thumb != null) {
        val clip = Path().apply { addRoundRect(preview, 10f, 10f, Path.Direction.CW) }
        val save = c.save()
        c.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(thumb, null, preview, paint)
        c.restoreToCount(save)
    } else {
        kit.rounded(c, preview, kit.tint, 10f)
        drawIcon(c, "box", preview.centerX(), preview.centerY() - 8f, 22f, kit.blue)
        kit.centerText(c, "1:1", RectF(preview.left, preview.bottom - 30f, preview.right, preview.bottom - 10f), 9f, kit.blue, true)
        if (thumbUrl != null) activity()?.loadProductImage(thumbUrl)
    }

    kit.textEllipsized(c, "Foto produk", 140f, top + 40f, 208f, 13f, kit.ink, true)
    val status = when {
        productImageUploading -> "Mengunggah..."
        thumbUrl == null -> "Belum ada foto"
        else -> "Foto terpasang"
    }
    kit.textEllipsized(
        c, status, 140f, top + 60f, 214f, 11f,
        if (productImageUploading) kit.blue else if (thumbUrl == null) kit.muted else kit.success,
        productImageUploading,
    )
    kit.textEllipsized(c, "JPG/PNG · maks 5MB", 140f, top + 78f, 214f, 10f, kit.muted)

    val pickLabel = when {
        productImageUploading -> "Memproses..."
        thumbUrl == null -> "Pilih foto"
        else -> "Ganti foto"
    }
    drawIcon(c, "plus", 146f, top + 100f, 12f, if (productImageUploading) kit.muted else kit.blue)
    kit.text(c, pickLabel, 156f, top + 104f, 11f, if (productImageUploading) kit.muted else kit.blue, true)
    press(c, "pfImage", panel, 0f)
    bounds["pfImage"] = RectF(panel)
    return top + 124f + 12f
}

private fun JajaninView.visibilityRow(c: Canvas, top: Float): Float {
    val row = RectF(20f, top, 370f, top + 64f)
    sellerSurface(c, row)
    kit.text(c, "Tampil di etalase", 32f, top + 26f, 13f, kit.ink, true)
    kit.textEllipsized(
        c, "Pembeli bisa melihat dan memesan produk ini",
        32f, top + 46f, 256f, 10.5f, kit.muted,
    )
    sellerSwitch(c, productAvailable, 356f, top + 32f)
    press(c, "pfAvailable", row, 0f)
    bounds["pfAvailable"] = RectF(row)
    return top + 64f + 2f
}

private fun JajaninView.deleteRow(c: Canvas, top: Float): Float {
    val row = RectF(20f, top, 370f, top + 58f)
    kit.rounded(c, row, kit.card, SellerTokens.RADIUS_CONTROL, kit.dangerBorder)
    drawIcon(c, "trash", 34f, row.centerY(), 16f, kit.danger)
    kit.text(c, "Hapus produk", 50f, top + 26f, 13f, kit.danger, true)
    kit.textEllipsized(c, "Tindakan ini tidak bisa dibatalkan", 50f, top + 44f, 294f, 10f, kit.muted)
    if (!productSaving && !productImageUploading) {
        press(c, "pfDelete", row, 0f)
        bounds["pfDelete"] = RectF(row)
    }
    return top + 58f
}
