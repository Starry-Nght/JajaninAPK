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
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.formatCalendarShortChip
import com.jajanin.app.data.repository.formatShortIdDate

/**
 * Form tambah/ubah produk sebagai HALAMAN:
 * Mencakup Informasi Produk, Ambil di Ruangan, Harga & Stok, Foto Produk, dan Visibilitas.
 */
internal fun JajaninView.drawProductForm(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val editing = productEditId != null
    val off = -scroll

    val bodySave = c.save()
    c.clipRect(0f, 60f, w, h - 72f)

    var y = SellerTokens.CONTENT_TOP + off

    // --- 1. Informasi produk ---
    sellerSection(c, "Informasi Produk", y + 12f)
    y += 24f
    y = sellerFormField(
        c, "Nama produk", productName, "cth. Nasi goreng", "pfName", y,
    )
    y = categoryPicker(c, y)
    y = descriptionField(c, y)
    y = ingredientsField(c, y)

    // --- 2. Ambil di Ruangan (Lokasi Pengambilan) ---
    y += 8f
    sellerSection(c, "Ambil di Ruangan", y + 12f, trailing = "Tampil di pesanan pembeli", trailingColor = kit.blue)
    y += 24f
    y = roomPickupSection(c, y, h - 72f)

    // --- 2b. Jadwal Pemesanan & Sesi Pengambilan (Pre-Order / Hari Ini) ---
    y += 8f
    sellerSection(c, "Jadwal & Sesi Pengambilan", y + 12f, trailing = "Atur Hari Ini / Pre-Order", trailingColor = kit.blue)
    y += 24f
    y = scheduleAndSessionPicker(c, y, h - 72f)

    // --- 3. Harga & stok ---
    y += 8f
    sellerSection(c, "Harga & Stok", y + 12f)
    y += 24f
    y = priceAndStock(c, y)

    // --- 4. Foto produk ---
    sellerSection(c, "Foto Produk", y + 12f)
    y += 24f
    y = photoPicker(c, y)
    if (productImageUploading) postInvalidateDelayed(60)

    // --- 5. Visibilitas ---
    sellerSection(c, "Visibilitas", y + 12f)
    y += 24f
    y = visibilityRow(c, y)

    if (editing) {
        y += 16f
        y = deleteRow(c, y)
    }

    c.restoreToCount(bodySave)
    contentHeight = y - off + 28f

    // CTA bawah tetap
    if (productError.isNotBlank()) {
        kit.textEllipsized(c, productError, 20f, h - 76f, 350f, 10.5f, kit.danger, true)
    }
    val cta = RectF(20f, h - 60f, w - 20f, h - 10f)
    when {
        productImageUploading -> {
            kit.rounded(c, cta, kit.muted, SellerTokens.RADIUS_SURFACE)
            kit.centerText(c, "Mengunggah gambar...", cta, 14f, Color.WHITE, true)
        }
        productSaving -> {
            kit.rounded(c, cta, kit.muted, SellerTokens.RADIUS_SURFACE)
            kit.centerText(c, "Menyimpan...", cta, 14f, Color.WHITE, true)
        }
        else -> {
            kit.rounded(c, cta, kit.blue, SellerTokens.RADIUS_SURFACE)
            kit.centerText(c, if (editing) "Simpan Perubahan" else "Simpan Produk", cta, 14f, Color.WHITE, true)
            press(c, "pfSave", cta, SellerTokens.RADIUS_SURFACE)
            bounds["pfSave"] = RectF(cta)
        }
    }
    sellerHeader(c, if (editing) "Ubah Produk" else "Tambah Produk")
}

private fun JajaninView.roomPickupSection(c: Canvas, top: Float, bottomLimit: Float): Float {
    var y = sellerFormField(
        c = c,
        label = "Ruangan / kelas tempat pembeli mengambil pesanan",
        value = productRoom,
        placeholder = "cth. Ruang XII RPL 1 / Kantin",
        key = "pfRoom",
        top = top,
    )

    val recentRooms = buildList {
        addAll(recentSellerRooms)
        if (myStallRoom.isNotBlank()) add(myStallRoom.trim())
        sellerProducts.forEach { prod ->
            val r = prod.pickupRoom.trim()
            if (r.isNotBlank()) add(r)
        }
    }.distinctBy { it.lowercase() }.take(4)

    if (recentRooms.isEmpty()) return y + 4f

    kit.text(c, "Terakhir digunakan:", 20f, y + 10f, 10f, kit.muted, true)
    y += 16f

    var chipX = 20f
    var chipY = y
    val chipH = 28f
    val gap = 6f
    recentRooms.forEach { room ->
        val chipW = (kit.measure(room, 10f, true) + 22f).coerceIn(58f, 170f)
        if (chipX + chipW > 370f) {
            chipX = 20f
            chipY += chipH + gap
        }
        val chipRect = RectF(chipX, chipY, chipX + chipW, chipY + chipH)
        val active = productRoom.equals(room, ignoreCase = true)
        if (active) {
            kit.rounded(c, chipRect, kit.blue, 6f)
        } else {
            kit.rounded(c, chipRect, kit.card, 6f, kit.border)
        }
        kit.centerText(c, room, chipRect, 10f, if (active) Color.WHITE else kit.ink, true)
        bounds.putVisible("pfRoomChip:$room", chipRect, topLimit = 60f, bottomLimit = bottomLimit)
        chipX += chipW + gap
    }

    return chipY + chipH + 8f
}

private fun JajaninView.scheduleAndSessionPicker(c: Canvas, top: Float, bottomLimit: Float): Float {
    var y = top
    kit.text(c, "Tersedia untuk", 20f, y + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val schedules = listOf(
        "same_day" to "Hari Ini",
        "next_day" to "Pre-Order",
        "both" to "Keduanya",
    )
    val gap = 8f
    val schedW = (SellerTokens.WIDTH - gap * 2f) / 3f
    schedules.forEachIndexed { idx, (value, label) ->
        val left = SellerTokens.PAD + idx * (schedW + gap)
        val chip = RectF(left, y + 16f, left + schedW, y + 54f)
        val active = productOrderSchedule == value
        val isPo = value == "next_day"
        if (active) {
            kit.rounded(
                c,
                chip,
                if (isPo) kit.warnBox else kit.tint,
                SellerTokens.RADIUS_CONTROL,
                if (isPo) Color.rgb(183, 116, 0) else kit.blue,
            )
        } else {
            kit.rounded(c, chip, kit.card, SellerTokens.RADIUS_CONTROL, kit.border)
        }
        val fg = if (!active) kit.muted else if (isPo) Color.rgb(183, 116, 0) else kit.blue
        kit.centerText(c, label, chip, 11.5f, fg, active)
        press(c, "pfSchedule:$value", chip, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfSchedule:$value", chip, topLimit = 60f, bottomLimit = bottomLimit)
    }
    y += 66f

    // Pemilih Tanggal Kalender PO (tampil saat Pre-Order atau Keduanya aktif)
    if (productOrderSchedule == "next_day" || productOrderSchedule == "both") {
        val poAccent = Color.rgb(183, 116, 0)
        val sortedDates = productPoDates.filter { OrderRepository.isValidPreOrderDate(it) }.sorted()

        kit.text(c, "Tanggal Pre-Order (Kalender)", 20f, y + 10f, SellerTokens.T_LABEL, poAccent, true)
        y += 16f

        val calCard = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
        kit.rounded(c, calCard, kit.warnBox, SellerTokens.RADIUS_CONTROL, poAccent)
        val titleStr = if (sortedDates.isEmpty()) {
            "Semua tanggal (Besok s/d 30 hari)"
        } else {
            "${sortedDates.size} tanggal PO dipilih"
        }
        val subStr = if (sortedDates.isEmpty()) {
            "Ketuk untuk pilih tanggal kalender tertentu"
        } else {
            sortedDates.take(3).joinToString(" · ") { formatCalendarShortChip(it) } +
                if (sortedDates.size > 3) " +${sortedDates.size - 3}" else ""
        }
        kit.textEllipsized(c, titleStr, SellerTokens.PAD + 12f, y + 22f, 232f, 12f, kit.ink, true)
        kit.textEllipsized(c, subStr, SellerTokens.PAD + 12f, y + 40f, 232f, 10f, kit.muted)

        val calBtn = RectF(SellerTokens.RIGHT - 90f, y + 12f, SellerTokens.RIGHT - 10f, y + 42f)
        kit.rounded(c, calBtn, kit.card, 8f, poAccent)
        kit.centerText(c, "+ Kalender", calBtn, 10.5f, poAccent, true)
        press(c, "pfPickPoDate", calCard, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfPickPoDate", calCard, topLimit = 60f, bottomLimit = bottomLimit)
        y += 62f

        // Baris cepat: Besok, +2 hr, +3 hr, Bebas (reset)
        val d1 = OrderRepository.wibDateStringOffset(1)
        val d2 = OrderRepository.wibDateStringOffset(2)
        val d3 = OrderRepository.wibDateStringOffset(3)
        val qGap = 6f
        val qW = (SellerTokens.WIDTH - qGap * 3f) / 4f
        val quickRows = listOf(
            "pfTogglePoDate:$d1" to "Besok (${formatShortIdDate(d1)})",
            "pfTogglePoDate:$d2" to formatCalendarShortChip(d2),
            "pfTogglePoDate:$d3" to formatCalendarShortChip(d3),
            "pfClearPoDates" to "Bebas",
        )
        quickRows.forEachIndexed { idx, (key, label) ->
            val left = SellerTokens.PAD + idx * (qW + qGap)
            val chip = RectF(left, y, left + qW, y + 30f)
            val on = if (key == "pfClearPoDates") {
                sortedDates.isEmpty()
            } else {
                key.removePrefix("pfTogglePoDate:") in sortedDates
            }
            kit.rounded(
                c,
                chip,
                if (on) kit.warnBox else kit.card,
                7f,
                if (on) poAccent else kit.border,
            )
            kit.centerText(c, label, chip, 9.5f, if (on) poAccent else kit.ink, on)
            press(c, key, chip, 7f)
            bounds.putVisible(key, chip, topLimit = 60f, bottomLimit = bottomLimit)
        }
        y += 38f

        // Daftar chip tanggal yang dipilih (bisa ketuk untuk hapus, termasuk tanggal jauh dari kalender)
        if (sortedDates.isNotEmpty()) {
            var cx = SellerTokens.PAD
            var cy = y
            val ch = 26f
            val cGap = 6f
            sortedDates.forEach { iso ->
                val chipText = "${formatCalendarShortChip(iso)}  ×"
                val cw = (kit.measure(chipText, 9.5f, true) + 18f).coerceAtMost(150f)
                if (cx + cw > SellerTokens.RIGHT) {
                    cx = SellerTokens.PAD
                    cy += ch + cGap
                }
                val r = RectF(cx, cy, cx + cw, cy + ch)
                kit.rounded(c, r, kit.warnBox, 6f, poAccent)
                kit.centerText(c, chipText, r, 9.5f, poAccent, true)
                bounds.putVisible("pfRemovePoDate:$iso", r, topLimit = 60f, bottomLimit = bottomLimit)
                cx += cw + cGap
            }
            y = cy + ch + 10f
        }
    }

    kit.text(c, "Sesi pengambilan (bisa pilih lebih dari 1)", 20f, y + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val sessions = com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS
    val sessW = (SellerTokens.WIDTH - gap * (sessions.size - 1)) / sessions.size
    sessions.forEachIndexed { idx, sess ->
        val left = SellerTokens.PAD + idx * (sessW + gap)
        val chip = RectF(left, y + 16f, left + sessW, y + 50f)
        val active = sess in productPickupSessions
        if (active) {
            kit.rounded(c, chip, kit.blue, SellerTokens.RADIUS_CONTROL)
        } else {
            kit.rounded(c, chip, kit.card, SellerTokens.RADIUS_CONTROL, kit.border)
        }
        kit.centerText(c, sess, chip, 10.5f, if (active) Color.WHITE else kit.ink, active)
        press(c, "pfSession:$sess", chip, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfSession:$sess", chip, topLimit = 60f, bottomLimit = bottomLimit)
    }
    return y + 62f
}

private fun JajaninView.categoryPicker(c: Canvas, top: Float): Float {
    kit.text(c, "Kategori", 20f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    if (productCategory != "Food" && productCategory != "Drinks") {
        kit.textRight(c, "Saat ini: $productCategory", 368f, top + 10f, 150f, 9.5f, kit.muted)
    }
    val choices = listOf(
        "Food" to "Makanan",
        "Drinks" to "Minuman",
    )
    val gap = 8f
    val segmentW = (SellerTokens.WIDTH - gap) / 2f
    choices.forEachIndexed { index, (value, label) ->
        val left = SellerTokens.PAD + index * (segmentW + gap)
        val seg = RectF(left, top + 16f, left + segmentW, top + 58f)
        val active = productCategory == value
        if (active) {
            kit.rounded(c, seg, kit.tint, SellerTokens.RADIUS_CONTROL, kit.blue)
        } else {
            kit.rounded(c, seg, kit.card, SellerTokens.RADIUS_CONTROL, kit.border)
        }
        kit.centerText(c, label, seg, 12.5f, if (active) kit.blue else kit.muted, active)
        press(c, "pfCat:$value", seg, SellerTokens.RADIUS_CONTROL)
        bounds["pfCat:$value"] = RectF(seg)
    }
    return top + 58f + 12f
}

private fun JajaninView.descriptionField(c: Canvas, top: Float): Float {
    kit.text(c, "Deskripsi", 20f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(20f, top + 16f, 370f, top + 76f)
    kit.rounded(
        c, field, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfDesc") kit.blue else kit.border,
    )
    if (productDescription.isBlank()) {
        kit.text(c, "Tulis deskripsi singkat produk", 34f, top + 42f, 13f, kit.muted)
    } else {
        kit.textWrapped(
            c, productDescription, 34f, top + 34f, 322f, 13f, kit.ink,
            maxLines = 2, lineHeight = 19f,
        )
    }
    press(c, "pfDesc", field, SellerTokens.RADIUS_CONTROL)
    bounds["pfDesc"] = RectF(field)
    return top + 76f + 12f
}

private fun JajaninView.ingredientsField(c: Canvas, top: Float): Float {
    kit.text(c, "Bahan (opsional)", 20f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val field = RectF(20f, top + 16f, 370f, top + 64f)
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
    press(c, "pfIngredients", field, SellerTokens.RADIUS_CONTROL)
    bounds["pfIngredients"] = RectF(field)
    return top + 64f + 12f
}

private fun JajaninView.priceAndStock(c: Canvas, top: Float): Float {
    val half = 171f
    val gap = 8f
    val priceText = productPrice.toIntOrNull()?.let { formatRupiah(it) }
        ?: "cth. 12000"
    val stockText = productStock.toIntOrNull()?.toString() ?: "Tanpa batas"
    val priceField = RectF(20f, top + 16f, 20f + half, top + 62f)
    val stockField = RectF(20f + half + gap, top + 16f, 370f, top + 62f)

    kit.text(c, "Harga (Rp)", 20f, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    kit.rounded(
        c, priceField, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfPrice") kit.blue else kit.border,
    )
    kit.textEllipsized(
        c, priceText, 34f, priceField.centerY() + 4.5f,
        half - 28f, 13.5f, if (productPrice.toIntOrNull() != null) kit.ink else kit.muted,
        productPrice.toIntOrNull() != null,
    )
    press(c, "pfPrice", priceField, SellerTokens.RADIUS_CONTROL)
    bounds["pfPrice"] = RectF(priceField)

    kit.text(c, "Stok", 20f + half + gap, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    kit.rounded(
        c, stockField, kit.card, SellerTokens.RADIUS_CONTROL,
        if (pressedKey == "pfStock") kit.blue else kit.border,
    )
    kit.textEllipsized(
        c, stockText, stockField.left + 14f, stockField.centerY() + 4.5f,
        stockField.width() - 28f, 13.5f, if (productStock.toIntOrNull() != null) kit.ink else kit.muted,
        productStock.toIntOrNull() != null,
    )
    press(c, "pfStock", stockField, SellerTokens.RADIUS_CONTROL)
    bounds["pfStock"] = RectF(stockField)
    return top + 62f + 12f
}

private fun JajaninView.photoPicker(c: Canvas, top: Float): Float {
    val panel = RectF(20f, top, 370f, top + 112f)
    sellerSurface(c, panel)
    val thumbUrl = productImageUrl.takeIf { it.startsWith("https://") }
    val thumb = thumbUrl?.let { ProductImageCache.images[it] }
    val preview = RectF(32f, top + 14f, 116f, top + 98f)
    if (thumb != null) {
        com.jajanin.app.core.ui.drawBitmapCenterCrop(c, thumb, preview, 8f)
    } else {
        kit.rounded(c, preview, kit.tint, 8f)
        drawIcon(c, "box", preview.centerX(), preview.centerY() - 6f, 20f, kit.blue)
        kit.centerText(c, "1:1", RectF(preview.left, preview.bottom - 26f, preview.right, preview.bottom - 8f), 9f, kit.blue, true)
        if (thumbUrl != null) activity()?.loadProductImage(thumbUrl)
    }

    kit.textEllipsized(c, "Foto produk", 132f, top + 34f, 214f, 13f, kit.ink, true)
    val status = when {
        productImageUploading -> "Mengunggah..."
        thumbUrl == null -> "Belum ada foto"
        else -> "Foto terpasang"
    }
    kit.textEllipsized(
        c, status, 132f, top + 54f, 214f, 11f,
        if (productImageUploading) kit.blue else if (thumbUrl == null) kit.muted else kit.success,
        productImageUploading,
    )
    kit.textEllipsized(c, "JPG/PNG · maks 5MB", 132f, top + 72f, 214f, 10f, kit.muted)

    val pickLabel = when {
        productImageUploading -> "Memproses..."
        thumbUrl == null -> "Pilih foto"
        else -> "Ganti foto"
    }
    drawIcon(c, "plus", 138f, top + 91f, 12f, if (productImageUploading) kit.muted else kit.blue)
    kit.text(c, pickLabel, 148f, top + 95f, 11f, if (productImageUploading) kit.muted else kit.blue, true)
    press(c, "pfImage", panel, SellerTokens.RADIUS_SURFACE)
    bounds["pfImage"] = RectF(panel)
    return top + 112f + 12f
}

private fun JajaninView.visibilityRow(c: Canvas, top: Float): Float {
    val row = RectF(20f, top, 370f, top + 60f)
    sellerSurface(c, row)
    kit.text(c, "Tampil di etalase", 32f, top + 25f, 13f, kit.ink, true)
    kit.textEllipsized(
        c, "Pembeli bisa melihat dan memesan produk ini",
        32f, top + 43f, 256f, 10.5f, kit.muted,
    )
    sellerSwitch(c, productAvailable, 356f, top + 30f)
    press(c, "pfAvailable", row, SellerTokens.RADIUS_SURFACE)
    bounds["pfAvailable"] = RectF(row)
    return top + 60f + 2f
}

private fun JajaninView.deleteRow(c: Canvas, top: Float): Float {
    val row = RectF(20f, top, 370f, top + 54f)
    kit.rounded(c, row, kit.card, SellerTokens.RADIUS_CONTROL, kit.dangerBorder)
    drawIcon(c, "trash", 34f, row.centerY(), 16f, kit.danger)
    kit.text(c, "Hapus produk", 50f, top + 25f, 13f, kit.danger, true)
    kit.textEllipsized(c, "Tindakan ini tidak bisa dibatalkan", 50f, top + 42f, 294f, 10f, kit.muted)
    if (!productSaving && !productImageUploading) {
        press(c, "pfDelete", row, SellerTokens.RADIUS_CONTROL)
        bounds["pfDelete"] = RectF(row)
    }
    return top + 54f
}
