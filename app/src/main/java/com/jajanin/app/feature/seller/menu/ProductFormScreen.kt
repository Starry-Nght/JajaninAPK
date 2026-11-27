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

    // --- 1b. Pilihan Varian / Rasa (Editable Section Name + Tambah Section) ---
    y += 8f
    y = variantSectionsEditor(c, y, h - 72f)

    // --- 1c. Ambil di Kelas / Ruangan (Lokasi Pengambilan — Wajib) ---
    y += 8f
    sellerSection(c, "Ambil di Kelas / Ruangan", y + 12f, trailing = "Tampil di pesanan pembeli", trailingColor = kit.blue)
    y += 24f
    y = roomPickupSection(c, y, h - 72f)

    // --- 2. Jadwal Pemesanan & Sesi Pengambilan (Pre-Order / Hari Ini) ---
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
        label = "Kelas / ruangan tempat pembeli mengambil pesanan (wajib)",
        value = productRoom,
        placeholder = "cth. Kelas XII RPL 1 / Kantin Utama",
        key = "pfRoom",
        top = top,
    )

    val recentRooms = buildList {
        addAll(recentSellerRooms)
        if (myStallRoom.isNotBlank()) add(myStallRoom.trim())
        if (myClass.isNotBlank()) add("Kelas ${myClass.trim()}")
        sellerProducts.forEach { prod ->
            val r = prod.pickupRoom.trim()
            if (r.isNotBlank()) add(r)
        }
    }.distinctBy { it.lowercase() }.take(4)

    if (recentRooms.isEmpty()) return y + 4f

    kit.text(c, "Pilih cepat kelas / ruangan:", 20f, y + 10f, 10f, kit.muted, true)
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

    // Pemilih Tanggal Kalender PO + Aturan Ketat PO (tampil saat Pre-Order atau Keduanya aktif)
    if (productOrderSchedule == "next_day" || productOrderSchedule == "both") {
        val poAccent = Color.rgb(183, 116, 0)
        val sortedDates = productPoDates.filter {
            OrderRepository.isValidPreOrderDate(it) && !OrderRepository.isWeekendWib(it)
        }.sorted()
        val mandatoryDate = productOrderSchedule == "next_day"

        val dateHeader = if (mandatoryDate) {
            "Tanggal Pre-Order (Wajib · Senin–Jumat)"
        } else {
            "Tanggal Pre-Order (Kalender · Senin–Jumat)"
        }
        kit.text(
            c,
            dateHeader,
            20f,
            y + 10f,
            SellerTokens.T_LABEL,
            if (mandatoryDate && sortedDates.isEmpty()) kit.danger else poAccent,
            true,
        )
        y += 16f

        val calCard = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
        val calBorder = if (mandatoryDate && sortedDates.isEmpty()) kit.danger else poAccent
        kit.rounded(c, calCard, kit.warnBox, SellerTokens.RADIUS_CONTROL, calBorder)
        val titleStr = when {
            sortedDates.isEmpty() && mandatoryDate -> "Wajib pilih min. 1 tanggal PO"
            sortedDates.isEmpty() -> "Semua hari sekolah (Sen–Jum)"
            else -> "${sortedDates.size} tanggal PO dipilih"
        }
        val subStr = if (sortedDates.isEmpty()) {
            "Ketuk untuk pilih tanggal kalender (Sabtu & Minggu libur)"
        } else {
            sortedDates.take(3).joinToString(" · ") { formatCalendarShortChip(it) } +
                if (sortedDates.size > 3) " +${sortedDates.size - 3}" else ""
        }
        kit.textEllipsized(
            c,
            titleStr,
            SellerTokens.PAD + 12f,
            y + 22f,
            232f,
            12f,
            if (mandatoryDate && sortedDates.isEmpty()) kit.danger else kit.ink,
            true,
        )
        kit.textEllipsized(c, subStr, SellerTokens.PAD + 12f, y + 40f, 232f, 10f, kit.muted)

        val calBtn = RectF(SellerTokens.RIGHT - 90f, y + 12f, SellerTokens.RIGHT - 10f, y + 42f)
        kit.rounded(c, calBtn, kit.card, 8f, poAccent)
        kit.centerText(c, "+ Kalender", calBtn, 10.5f, poAccent, true)
        press(c, "pfPickPoDate", calCard, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfPickPoDate", calCard, topLimit = 60f, bottomLimit = bottomLimit)
        y += 62f

        // Baris cepat: 3 hari sekolah terdekat yang masih buka + Reset
        val openSchool = OrderRepository.nextOpenSchoolDatesWib(3, productPoCutoffHour)
        val tomorrowIso = OrderRepository.tomorrowWibDateString()
        val quickRows = buildList {
            openSchool.forEach { iso ->
                val lbl = if (iso == tomorrowIso) "Besok (${formatShortIdDate(iso)})" else formatCalendarShortChip(iso)
                add("pfTogglePoDate:$iso" to lbl)
            }
            if (!mandatoryDate || sortedDates.isNotEmpty()) {
                add("pfClearPoDates" to if (mandatoryDate) "Reset" else "Bebas")
            }
        }
        val qGap = 6f
        val qW = (SellerTokens.WIDTH - qGap * (quickRows.size - 1).coerceAtLeast(0)) / quickRows.size.coerceAtLeast(1)
        quickRows.forEachIndexed { idx, (key, label) ->
            val left = SellerTokens.PAD + idx * (qW + qGap)
            val chip = RectF(left, y, left + qW, y + 30f)
            val on = if (key == "pfClearPoDates") {
                !mandatoryDate && sortedDates.isEmpty()
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

        // Daftar chip tanggal yang dipilih (ketuk untuk hapus)
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

        // Batas tutup pemesanan PO (Cutoff H-1 WIB)
        kit.text(c, "Batas tutup PO (H-1 sebelum hari ambil)", 20f, y + 10f, SellerTokens.T_LABEL, kit.muted, true)
        y += 16f
        val cutoffs = listOf(16, 18, 20, 21)
        val cGap = 6f
        val cW = (SellerTokens.WIDTH - cGap * (cutoffs.size - 1)) / cutoffs.size
        cutoffs.forEachIndexed { idx, hr ->
            val left = SellerTokens.PAD + idx * (cW + cGap)
            val pill = RectF(left, y, left + cW, y + 32f)
            val on = productPoCutoffHour == hr
            kit.rounded(
                c,
                pill,
                if (on) kit.warnBox else kit.card,
                7f,
                if (on) poAccent else kit.border,
            )
            kit.centerText(c, "${hr}:00 WIB", pill, 10.5f, if (on) poAccent else kit.ink, on)
            press(c, "pfCutoff:$hr", pill, 7f)
            bounds.putVisible("pfCutoff:$hr", pill, topLimit = 60f, bottomLimit = bottomLimit)
        }
        y += 42f

        // Kuota PO per tanggal (opsional)
        val quotaVal = productPoDailyQuota.toIntOrNull()?.let { "$it porsi / tanggal" }
            ?: "Tanpa batas per tanggal (ikut stok)"
        val quotaRect = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 46f)
        kit.rounded(c, quotaRect, kit.card, SellerTokens.RADIUS_CONTROL, kit.border)
        kit.text(c, "Kuota PO per tanggal", SellerTokens.PAD + 12f, y + 19f, 11.5f, kit.ink, true)
        kit.textEllipsized(
            c,
            quotaVal,
            SellerTokens.PAD + 12f,
            y + 36f,
            240f,
            10.5f,
            if (productPoDailyQuota.toIntOrNull() != null) poAccent else kit.muted,
            productPoDailyQuota.toIntOrNull() != null,
        )
        kit.textRight(c, "Atur", SellerTokens.RIGHT - 12f, y + 27f, 50f, 11.5f, kit.blue, true)
        press(c, "pfPoQuota", quotaRect, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfPoQuota", quotaRect, topLimit = 60f, bottomLimit = bottomLimit)
        y += 54f

        // Toggle Wajib QRIS untuk PO (PO > 3 hari selalu otomatis wajib QRIS)
        val qrisRow = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
        kit.rounded(c, qrisRow, kit.card, SellerTokens.RADIUS_CONTROL, if (productPoRequireQris) poAccent else kit.border)
        kit.text(c, "Wajib QRIS untuk semua PO", SellerTokens.PAD + 12f, y + 22f, 12f, kit.ink, true)
        kit.textEllipsized(
            c,
            "PO > 3 hari otomatis wajib QRIS · aktifkan agar PO 1–3 hari juga wajib QRIS",
            SellerTokens.PAD + 12f,
            y + 40f,
            262f,
            9.5f,
            kit.muted,
        )
        sellerSwitch(c, productPoRequireQris, SellerTokens.RIGHT - 12f, y + 27f)
        press(c, "pfPoRequireQris", qrisRow, SellerTokens.RADIUS_CONTROL)
        bounds.putVisible("pfPoRequireQris", qrisRow, topLimit = 60f, bottomLimit = bottomLimit)
        y += 64f
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

private fun JajaninView.variantSectionsEditor(c: Canvas, top: Float, bottomLimit: Float): Float {
    var y = top
    kit.text(c, "Pilihan Varian / Rasa", SellerTokens.PAD, y + 12f, 13.5f, kit.ink, true)
    val topAddBtn = RectF(248f, y - 4f, SellerTokens.RIGHT, y + 22f)
    kit.rounded(c, topAddBtn, kit.tint, 6f, kit.blue)
    kit.centerText(c, "+ Tambah Section", topAddBtn, 10f, kit.blue, true)
    press(c, "pfAddVarSec", topAddBtn, 6f)
    bounds.putVisible("pfAddVarSec", topAddBtn, topLimit = 60f, bottomLimit = bottomLimit)
    y += 28f

    kit.textEllipsized(
        c,
        "Opsional · Nama section bisa diubah (cth. Rasa, Topping, Level Pedas).",
        SellerTokens.PAD,
        y + 8f,
        SellerTokens.WIDTH,
        10f,
        kit.muted,
    )
    y += 18f

    productVariantSections.forEachIndexed { secIdx, sec ->
        val options = sec.options
        // Pre-calculate chip rows height
        val chipH = 26f
        val chipGap = 6f
        val cardLeft = SellerTokens.PAD
        val cardRight = SellerTokens.RIGHT
        val innerLeft = cardLeft + 12f
        val innerRight = cardRight - 12f

        var chipsHeight = 0f
        if (options.isEmpty()) {
            chipsHeight = 20f
        } else {
            var cx = innerLeft
            var rows = 1
            options.forEach { opt ->
                val chipTxt = "$opt  ×"
                val cw = (kit.measure(chipTxt, 10f, true) + 18f).coerceAtMost(innerRight - innerLeft)
                if (cx > innerLeft && cx + cw > innerRight) {
                    cx = innerLeft
                    rows++
                }
                cx += cw + chipGap
            }
            chipsHeight = rows * chipH + (rows - 1) * chipGap
        }

        val cardH = 44f + chipsHeight + 48f
        val cardRect = RectF(cardLeft, y, cardRight, y + cardH)
        kit.rounded(c, cardRect, kit.card, SellerTokens.RADIUS_SURFACE, kit.border)

        // Header inside card: Section Name + Ubah Nama + Hapus
        val removeBtn = RectF(innerRight - 46f, y + 10f, innerRight, y + 32f)
        kit.rounded(c, removeBtn, kit.bg, 6f, kit.dangerBorder)
        kit.centerText(c, "Hapus", removeBtn, 9.5f, kit.danger, true)
        press(c, "pfRemoveVarSec:$secIdx", removeBtn, 6f)
        bounds.putVisible("pfRemoveVarSec:$secIdx", removeBtn, topLimit = 60f, bottomLimit = bottomLimit)

        val renameBtn = RectF(removeBtn.left - 76f, y + 10f, removeBtn.left - 6f, y + 32f)
        kit.rounded(c, renameBtn, kit.tint, 6f, kit.blue)
        kit.centerText(c, "Ubah Nama", renameBtn, 9.5f, kit.blue, true)
        press(c, "pfEditVarSecName:$secIdx", renameBtn, 6f)
        bounds.putVisible("pfEditVarSecName:$secIdx", renameBtn, topLimit = 60f, bottomLimit = bottomLimit)

        val nameMaxW = (renameBtn.left - innerLeft - 8f).coerceAtLeast(80f)
        kit.textEllipsized(c, sec.cleanName, innerLeft, y + 25f, nameMaxW, 13.5f, kit.ink, true)
        kit.line(c, innerLeft, y + 38f, innerRight, y + 38f, kit.divider)

        // Option chips
        var chipTop = y + 46f
        if (options.isEmpty()) {
            kit.textEllipsized(
                c,
                "Belum ada pilihan ${sec.cleanName} (cth. Cokelat, Stroberi, Matcha)",
                innerLeft,
                chipTop + 13f,
                innerRight - innerLeft,
                10.5f,
                kit.muted,
            )
        } else {
            var cx = innerLeft
            var cy = chipTop
            options.forEachIndexed { optIdx, opt ->
                val chipTxt = "$opt  ×"
                val cw = (kit.measure(chipTxt, 10f, true) + 18f).coerceAtMost(innerRight - innerLeft)
                if (cx > innerLeft && cx + cw > innerRight) {
                    cx = innerLeft
                    cy += chipH + chipGap
                }
                val chipRect = RectF(cx, cy, cx + cw, cy + chipH)
                kit.rounded(c, chipRect, kit.tint, 6f, kit.blue)
                kit.centerText(c, chipTxt, chipRect, 10f, kit.blue, true)
                bounds.putVisible("pfRemoveVarOpt:$secIdx:$optIdx", chipRect, topLimit = 60f, bottomLimit = bottomLimit)
                cx += cw + chipGap
            }
        }

        // Bottom inside card: + Tambah Pilihan
        val addOptTop = y + 46f + chipsHeight + 10f
        val addOptBtn = RectF(innerLeft, addOptTop, innerRight, addOptTop + 32f)
        kit.rounded(c, addOptBtn, kit.bg, 7f, kit.blue)
        kit.centerText(c, "+ Tambah Pilihan ${sec.cleanName}", addOptBtn, 11f, kit.blue, true)
        press(c, "pfAddVarOpt:$secIdx", addOptBtn, 7f)
        bounds.putVisible("pfAddVarOpt:$secIdx", addOptBtn, topLimit = 60f, bottomLimit = bottomLimit)

        y += cardH + 10f
    }

    // Bottom full-width button: + Tambah Section
    val addSecBtn = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 38f)
    kit.rounded(c, addSecBtn, kit.card, SellerTokens.RADIUS_CONTROL, kit.blue)
    kit.centerText(c, "+ Tambah Section Baru", addSecBtn, 12f, kit.blue, true)
    press(c, "pfAddVarSecBottom", addSecBtn, SellerTokens.RADIUS_CONTROL)
    bounds.putVisible("pfAddVarSecBottom", addSecBtn, topLimit = 60f, bottomLimit = bottomLimit)
    return y + 48f
}

private fun JajaninView.priceAndStock(c: Canvas, top: Float): Float {
    val half = 171f
    val gap = 8f
    val priceText = productPrice.toIntOrNull()?.let { formatRupiah(it) }
        ?: "cth. 12000"
    val parsedStock = productStock.trim().filter(Char::isDigit).toIntOrNull()
    val stockValid = parsedStock != null && parsedStock in 1..100
    val stockText = when {
        parsedStock != null -> "$parsedStock porsi"
        else -> "Wajib 1–100"
    }
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

    kit.text(c, "Stok (Wajib 1–100)", 20f + half + gap, top + 10f, SellerTokens.T_LABEL, kit.muted, true)
    val stockBorder = when {
        pressedKey == "pfStock" -> kit.blue
        parsedStock != null && !stockValid -> kit.danger
        else -> kit.border
    }
    kit.rounded(c, stockField, kit.card, SellerTokens.RADIUS_CONTROL, stockBorder)
    val stockColor = when {
        parsedStock == null -> kit.muted
        !stockValid -> kit.danger
        else -> kit.ink
    }
    kit.textEllipsized(
        c, stockText, stockField.left + 14f, stockField.centerY() + 4.5f,
        stockField.width() - 28f, 13.5f, stockColor,
        parsedStock != null,
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
