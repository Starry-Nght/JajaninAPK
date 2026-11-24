package com.jajanin.app.feature.seller.menu

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.sellerDivider
import com.jajanin.app.feature.seller.sellerSurface
import com.jajanin.app.feature.seller.sellerSwitch

private const val HEADER_H = 60f

/**
 * Halaman khusus "Menu Dagangan" milik penjual yang dibuka dari Profil (Profil Warung / Profil Pembeli).
 * Menampilkan seluruh daftar dagangan warung sendiri, ringkasan, filter status/jadwal,
 * tombol tambah dagangan, ubah, hapus, serta toggle aktif/nonaktif.
 */
internal fun JajaninView.drawSellerMenu(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val bottomLimit = h - 16f
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= bottomLimit

    val allItems = sellerProducts
    val activeCount = allItems.count { it.available }
    val poCount = allItems.count { it.allowsNextDay }
    val inactiveCount = allItems.count { !it.available }

    val filtered = when (sellerMenuFilter) {
        "Aktif" -> allItems.filter { it.available }
        "Pre-Order" -> allItems.filter { it.allowsNextDay }
        "Nonaktif" -> allItems.filter { !it.available }
        else -> allItems
    }

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 74f + off

    // 1. Ringkasan Dagangan (3 kolom)
    val statsRect = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
    if (visible(statsRect.top, statsRect.bottom)) {
        sellerSurface(c, statsRect)
        val colW = SellerTokens.WIDTH / 3f
        val cols = listOf(
            Triple(allItems.size.toString(), "Total Menu", kit.ink),
            Triple(activeCount.toString(), "Aktif", kit.success),
            Triple(poCount.toString(), "Pre-Order", Color.rgb(183, 116, 0)),
        )
        cols.forEachIndexed { idx, (valStr, lbl, colColor) ->
            val left = SellerTokens.PAD + idx * colW
            if (idx > 0) kit.line(c, left, y + 10f, left, y + 44f, kit.divider)
            kit.text(c, valStr, left + 14f, y + 24f, 16f, colColor, true)
            kit.text(c, lbl, left + 14f, y + 42f, 10f, kit.muted)
        }
    }
    y += 66f

    // 2. Filter Chips (Semua / Aktif / Pre-Order / Nonaktif)
    if (visible(y, y + 32f)) {
        val filters = listOf(
            "Semua" to "Semua (${allItems.size})",
            "Aktif" to "Aktif ($activeCount)",
            "Pre-Order" to "PO ($poCount)",
            "Nonaktif" to "Mati ($inactiveCount)",
        )
        var fx = SellerTokens.PAD
        filters.forEach { (key, label) ->
            val active = sellerMenuFilter == key
            val fw = kit.measure(label, 10.5f, true) + 18f
            val pill = RectF(fx, y, fx + fw, y + 28f)
            val isPo = key == "Pre-Order"
            val bg = when {
                active && isPo -> kit.warnBox
                active -> kit.tint
                else -> kit.card
            }
            val border = when {
                active && isPo -> Color.rgb(183, 116, 0)
                active -> kit.blue
                else -> kit.border
            }
            val fg = when {
                active && isPo -> Color.rgb(183, 116, 0)
                active -> kit.blue
                else -> kit.muted
            }
            kit.rounded(c, pill, bg, 7f, border)
            kit.centerText(c, label, pill, 10.5f, fg, active)
            press(c, "sellerMenuFilter:$key", pill, 7f)
            bounds.putVisible("sellerMenuFilter:$key", pill, topLimit = HEADER_H, bottomLimit = bottomLimit)
            fx += fw + 7f
        }
    }
    y += 40f

    // 3. Daftar Dagangan
    if (filtered.isEmpty()) {
        val emptyCard = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 114f)
        if (visible(emptyCard.top, emptyCard.bottom)) {
            sellerSurface(c, emptyCard)
            drawIcon(c, "box", 44f, y + 38f, 22f, kit.blue)
            val emptyTitle = if (allItems.isEmpty()) "Belum ada menu dagangan" else "Tidak ada dagangan di filter ini"
            val emptySub = if (allItems.isEmpty()) {
                "Tambahkan produk pertamamu agar langsung tampil di katalog pembeli."
            } else {
                "Coba pilih filter Semua atau tambahkan dagangan baru."
            }
            kit.textEllipsized(c, emptyTitle, 68f, y + 34f, 286f, 13.5f, kit.ink, true)
            kit.textEllipsized(c, emptySub, 68f, y + 53f, 286f, 11f, kit.muted)

            val addBtn = RectF(68f, y + 68f, 222f, y + 98f)
            kit.rounded(c, addBtn, kit.blue, 8f)
            kit.centerText(c, "+ Tambah Dagangan", addBtn, 11.5f, Color.WHITE, true)
            press(c, "productAdd", addBtn, 8f)
            bounds.putVisible("productAdd", addBtn, topLimit = HEADER_H, bottomLimit = bottomLimit)
        }
        y += 128f
    } else {
        val rowH = 98f
        if (visible(y, y + 4f)) {
            kit.line(c, SellerTokens.PAD, y, SellerTokens.RIGHT, y, kit.divider)
        }
        filtered.forEachIndexed { idx, product ->
            val rowTop = y + idx * rowH
            if (visible(rowTop, rowTop + rowH)) {
                drawSellerMenuItemRow(
                    c = c,
                    product = product,
                    top = rowTop,
                    height = rowH,
                    bottomLimit = bottomLimit,
                    last = idx == filtered.lastIndex,
                )
            }
        }
        y += filtered.size * rowH + 20f
    }

    c.restoreToCount(bodySave)
    contentHeight = y - off + 28f

    // Header atas tetap: Back + "Menu Dagangan" + tombol "+ Tambah"
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.text(c, "Menu Dagangan", 50f, 37f, 18f, kit.ink, true)
    val addHeaderBtn = RectF(286f, 15f, 370f, 45f)
    kit.rounded(c, addHeaderBtn, kit.tint, 8f, kit.blue)
    kit.centerText(c, "+ Tambah", addHeaderBtn, 11.5f, kit.blue, true)
    press(c, "productAddHeader", addHeaderBtn, 8f)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    c.restore()
    bounds["headerBack"] = RectF(10f, 8f, 58f, HEADER_H)
    bounds["productAddHeader"] = RectF(addHeaderBtn)
}

private fun JajaninView.drawSellerMenuItemRow(
    c: Canvas,
    product: ProductRow,
    top: Float,
    height: Float,
    bottomLimit: Float,
    last: Boolean,
) {
    val thumb = RectF(20f, top + 14f, 82f, top + 76f)
    val url = product.imageUrl?.takeIf { it.startsWith("https://") }
    val bmp = url?.let { ProductImageCache.images[it] }
    if (bmp == null) {
        kit.rounded(c, thumb, kit.tint, 8f)
        kit.centerText(c, product.name.firstOrNull()?.uppercaseChar()?.toString() ?: "J", thumb, 20f, kit.blue, true)
        if (url != null) activity()?.loadProductImage(url)
    } else {
        drawBitmapCenterCrop(c, bmp, thumb, 8f)
    }

    val textX = 94f
    val maxW = 212f
    kit.textEllipsized(c, product.name, textX, top + 26f, maxW, 14f, kit.ink, true)

    val stockStr = product.stock?.let { "Stok $it" } ?: "Stok bebas"
    val subColor = if (product.available) kit.muted else kit.danger
    kit.textEllipsized(
        c,
        "${formatRupiah(product.priceIdr)} · ${if (product.available) stockStr else "Nonaktif"}",
        textX,
        top + 44f,
        maxW,
        11.5f,
        subColor,
        !product.available,
    )

    // Badge jadwal & aturan PO ringkas
    val schedInfo = buildList {
        add(product.scheduleBadgeLabel)
        if (product.allowsNextDay) {
            product.poDailyQuota?.let { add("Kuota $it/hr") }
            if (product.poRequireQris) add("Wajib QRIS")
        }
    }.joinToString(" · ")
    val schedColor = if (product.orderSchedule == "next_day") Color.rgb(183, 116, 0) else kit.blue
    kit.textEllipsized(c, schedInfo, textX, top + 61f, maxW, 10f, schedColor, true)

    // Link cepat Ubah · Hapus
    kit.text(c, "Ubah", textX, top + 82f, 11f, kit.blue, true)
    val editW = kit.measure("Ubah", 11f, true)
    kit.text(c, "·", textX + editW + 6f, top + 82f, 11f, kit.muted)
    kit.text(c, "Hapus", textX + editW + 14f, top + 82f, 11f, kit.danger, true)

    // Toggle switch kanan
    sellerSwitch(c, product.available, 370f, top + 44f)

    // Register hitbox: row body -> edit dulu, lalu delete & toggle di atasnya (dispatch terbalik)
    bounds.putVisible(
        "productEdit:${product.id}",
        RectF(20f, top, 306f, top + height),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )
    bounds.putVisible(
        "productDelete:${product.id}",
        RectF(textX + editW + 8f, top + 66f, textX + editW + 72f, top + height),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )
    bounds.putVisible(
        "productToggle:${product.id}",
        RectF(314f, top + 20f, 370f, top + 68f),
        topLimit = HEADER_H,
        bottomLimit = bottomLimit,
    )

    if (!last) sellerDivider(c, top + height, 94f, 370f)
}
