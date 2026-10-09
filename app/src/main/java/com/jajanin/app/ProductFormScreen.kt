package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

/**
 * Form tambah/ubah produk sebagai HALAMAN (bukan dialog timpa): masuk dengan
 * geser, kembali via toolbar/back-stack. Tiap baris diketuk untuk mengisi
 * lewat input native fokus; simpan di CTA bawah yang tetap terlihat.
 */
internal fun JajaninView.drawProductForm(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val editing = productEditId != null
    header(c, if (editing) "Ubah produk" else "Tambah produk")
    val off = -scroll
    var y = 92f + off

    y = formKicker(c, "INFO DASAR", y)
    y = formRow(c, "Nama produk", productName.takeIf { it.isNotBlank() } ?: "cth. Nasi goreng", "pfName", y, filled = productName.isNotBlank())
    y = formRow(c, "Kategori", productCategory.ifBlank { "Food" }, "pfCategory", y, filled = true)
    val priceLabel = productPrice.toIntOrNull()?.let { formatRupiah(it) } ?: "cth. 12000"
    y = formRow(c, "Harga", priceLabel, "pfPrice", y, filled = productPrice.toIntOrNull() != null)

    y = formKicker(c, "DETAIL", y)
    y = formRow(c, "Deskripsi", productDescription.takeIf { it.isNotBlank() } ?: "Singkat saja", "pfDesc", y, filled = productDescription.isNotBlank())
    y = formRow(c, "Bahan", productIngredients.takeIf { it.isNotBlank() } ?: "Opsional", "pfIngredients", y, filled = productIngredients.isNotBlank())
    val stockLabel = productStock.toIntOrNull()?.toString() ?: "Tanpa batas"
    y = formRow(c, "Stok", stockLabel, "pfStock", y, filled = productStock.toIntOrNull() != null)
    y = formRow(
        c, "Tampil di etalase", if (productAvailable) "Aktif" else "Mati", "pfAvailable", y,
        filled = true, valueColor = if (productAvailable) kit.success else kit.muted,
    )

    y = formKicker(c, "FOTO", y)
    val thumbUrl = productImageUrl.takeIf { it.startsWith("https://") }
    val thumb = thumbUrl?.let { ProductImageCache.images[it] }
    val photoH = 84f
    if (y + photoH >= 78f && y <= h - 160f) {
        kit.card(c, RectF(20f, y, 370f, y + photoH), 14f)
        if (thumb != null) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            val dst = RectF(32f, y + 12f, 32f + 60f, y + photoH - 12f)
            val path = android.graphics.Path().apply { addRoundRect(dst, 10f, 10f, android.graphics.Path.Direction.CW) }
            val save = c.save()
            c.clipPath(path)
            c.drawBitmap(thumb, null, dst, paint)
            c.restoreToCount(save)
        } else {
            kit.rounded(c, RectF(32f, y + 12f, 92f, y + photoH - 12f), kit.tint, 10f)
            kit.centerText(c, "◍", RectF(32f, y + 12f, 92f, y + photoH - 12f), 20f, kit.blue, true)
            if (thumbUrl != null) activity()?.loadProductImage(thumbUrl)
        }
        val status = when {
            productImageUploading -> "Mengunggah..."
            thumbUrl == null -> "Ketuk untuk upload"
            else -> "Terpasang ✓ · ketuk ganti"
        }
        kit.text(c, "Foto produk", 106f, y + 36f, 14f, kit.ink, true)
        kit.text(c, status, 106f, y + 60f, 11f, if (productImageUploading) kit.blue else kit.muted, productImageUploading)
        bounds["pfImage"] = RectF(20f, y, 370f, y + photoH)
    }
    y += photoH + 10f

    if (editing) {
        if (y + 44f >= 78f && y <= h - 160f) {
            kit.centerText(c, "Hapus produk ini", RectF(20f, y, 370f, y + 44f), 13f, kit.danger, true)
            bounds["pfDelete"] = RectF(20f, y, 370f, y + 44f)
        }
        y += 54f
    }

    contentHeight = y - off + 120f

    // CTA bawah tetap (tanpa nav di halaman ini).
    if (productError.isNotBlank()) {
        kit.text(c, productError.take(64), 22f, h - 92f, 11f, kit.danger, true)
    }
    val cta = RectF(20f, h - 72f, 370f, h - 16f)
    if (productSaving || productImageUploading) {
        kit.rounded(c, cta, kit.muted, 14f)
        kit.centerText(c, if (productImageUploading) "Mengunggah gambar..." else "Menyimpan...", cta, 14f, Color.WHITE, true)
    } else {
        kit.button(c, if (editing) "Simpan perubahan" else "Simpan produk", cta)
        bounds["pfSave"] = RectF(cta)
    }
}

private fun JajaninView.formKicker(c: Canvas, text: String, y: Float): Float {
    kit.text(c, text, 22f, y + 14f, 11f, kit.muted, true)
    return y + 28f
}

/** Baris kartu form: label kecil + nilai + chevron. Mengembalikan y berikut. */
private fun JajaninView.formRow(
    c: Canvas,
    label: String,
    value: String,
    key: String,
    y: Float,
    filled: Boolean,
    valueColor: Int = kit.ink,
): Float {
    val rowH = 64f
    if (y + rowH >= 78f && y <= 2000f) {
        kit.card(c, RectF(20f, y, 370f, y + rowH), 14f)
        kit.text(c, label, 36f, y + 25f, 11f, kit.muted, true)
        kit.text(c, value.take(30), 36f, y + 49f, 14f, if (filled) valueColor else kit.muted, filled)
        drawIcon(c, "chevronRight", 348f, y + rowH / 2f, 14f, kit.muted)
        press(c, key, RectF(20f, y, 370f, y + rowH), 14f)
        bounds[key] = RectF(20f, y, 370f, y + rowH)
    }
    return y + rowH + 10f
}
