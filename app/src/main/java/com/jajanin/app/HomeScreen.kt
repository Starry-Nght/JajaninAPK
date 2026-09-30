package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

/*
 * Home terbagi dua zona (semua ukuran dalam satuan desain 390f, lihat JajaninView.onDraw):
 *
 *  - Zona tetap   (0 s/d VIEWPORT_TOP): top bar, pencarian, lokasi, kategori.
 *    Digambar sekali dan tidak ikut bergeser.
 *  - Zona scroll  (VIEWPORT_TOP s/d bawah nav): header section + kartu, di-clip
 *    seperti viewport LazyColumn sehingga konten yang di-scroll tidak pernah
 *    menimpa zona tetap.
 *
 * contentHeight harus dihitung dalam koordinat absolut (bebas scroll). Bila dihitung
 * dari posisi yang sudah digeser, maxScroll di JajaninView.onTouchEvent menyusut saat
 * user scroll dan konten akan memantul ke atas.
 */

private const val MARGIN = 20f
private const val VIEWPORT_TOP = 296f
private const val CONTENT_START = 316f
private const val NAV_HEIGHT = 67f
private const val SEE_ALL = "Lihat semua"
private const val CATEGORIES = "KATEGORI"

internal fun JajaninView.drawHome(c: Canvas, w: Float, h: Float) {
    kit.image(c, R.drawable.bghome, RectF(0f, 0f, w, h))
    // Scrim bg Jajanin (alpha 230) agar doodle hanya ~10% terlihat dan teks tetap terbaca.
    kit.rect(c, 0f, 0f, w, h, Color.argb(230, 247, 248, 252))
    drawHomeHeader(c, w)

    val restore = c.save()
    c.clipRect(0f, VIEWPORT_TOP, w, h - NAV_HEIGHT)
    drawHomeContent(c, w, h)
    c.restoreToCount(restore)

    drawBottomNav(c, w, h, "Home")
}

private fun JajaninView.drawHomeHeader(c: Canvas, w: Float) {
    kit.rect(c, 0f, 0f, w, 74f, Color.WHITE)
    kit.line(c, 0f, 74f, w, 74f)
    kit.text(c, "Jajanin", MARGIN, 46f, 23f, kit.ink, true)
    val homeChip = RectF(338f, 24f, 372f, 56f)
    kit.rounded(c, homeChip, kit.tint, 17f)
    kit.centerText(c, "⌂", homeChip, 17f, kit.blue, true)

    val search = RectF(MARGIN, 88f, w - MARGIN, 140f)
    kit.rounded(c, search, Color.WHITE, 16f, kit.border)
    kit.text(c, "⌕", search.left + 16f, 123f, 28f, kit.muted)
    kit.text(
        c,
        if (query.isEmpty()) "Makan apa hari ini?" else query,
        search.left + 48f,
        120f,
        15f,
        if (query.isEmpty()) kit.muted else kit.ink
    )
    bounds["search"] = RectF(search)

    val locationCard = RectF(MARGIN, 156f, w - MARGIN, 206f)
    kit.rounded(c, locationCard, Color.WHITE, 14f, kit.border)
    kit.text(c, "Lokasi kamu", locationCard.left + 16f, 176f, 11f, kit.muted)
    kit.text(c, location, locationCard.left + 16f, 197f, 14f, kit.ink, true)
    kit.text(c, "⌄", 344f, 192f, 15f, kit.muted)
    bounds["location"] = RectF(locationCard)

    kit.text(c, CATEGORIES, MARGIN, 238f, 11f, kit.muted, true)
    var cx = MARGIN
    listOf("Semua", "Makanan", "Minuman", "Camilan").forEach { cat ->
        val cw = kit.measure(cat, 12f, true) + 34f
        val r = RectF(cx, 250f, cx + cw, 288f)
        val on = activeCategory == cat
        kit.rounded(c, r, if (on) kit.blue else Color.WHITE, 19f, if (on) null else kit.border)
        kit.centerText(c, cat, r, 12f, if (on) Color.WHITE else kit.ink, true)
        bounds["cat:$cat"] = r
        cx += cw + 8f
    }
}

private fun JajaninView.drawHomeContent(c: Canvas, w: Float, h: Float) {
    val top = CONTENT_START - scroll
    val clipBottom = h - NAV_HEIGHT

    val stallHeaderY = top + 14f
    if (stallHeaderY >= VIEWPORT_TOP - 24f && stallHeaderY <= h) {
        sectionHeader(c, "Kantin dekatmu", stallHeaderY, w)
    }

    val stallTop = top + 28f
    if (stallTop <= clipBottom && stallTop + 142f >= VIEWPORT_TOP) {
        val stallOne = RectF(MARGIN, stallTop, 189f, stallTop + 142f)
        val stallTwo = RectF(201f, stallTop, w - MARGIN, stallTop + 142f)
        stallCard(c, stallOne, "Kantin Kuning", "Buka · 5–10 mnt", R.drawable.home)
        stallCard(c, stallTwo, "Kantin Biru Segar", "Buka · 3–5 mnt", R.drawable.home)
        bounds["stall1"] = visibleHit(stallOne, clipBottom)
        bounds["stall2"] = visibleHit(stallTwo, clipBottom)
    }

    val listTop = top + 212f
    val listHeaderY = listTop + 16f
    if (listHeaderY >= VIEWPORT_TOP - 24f && listHeaderY <= h) {
        sectionHeader(c, "Rekomendasi hari ini", listHeaderY, w)
    }

    var y = listTop + 29f
    var matches = 0
    items.forEachIndexed { i, item ->
        if (matchesFilters(item)) {
            val r = RectF(MARGIN, y, w - MARGIN, y + 116f)
            if (r.bottom >= VIEWPORT_TOP && r.top <= clipBottom) {
                foodCard(c, item, r)
                bounds["item$i"] = visibleHit(r, clipBottom)
            }
            y += 126f
            matches++
        }
    }
    if (matches == 0) {
        val message = if (query.isEmpty()) "Belum ada menu di kategori ini" else "Tidak ada menu untuk \"$query\""
        kit.centerText(c, message, RectF(MARGIN, VIEWPORT_TOP + 48f, w - MARGIN, VIEWPORT_TOP + 96f), 14f, kit.muted)
    }
    contentHeight = y + scroll + 20f
}

private fun JajaninView.matchesFilters(item: MenuItem): Boolean {
    val byCategory = activeCategory == "Semua" || item.category == activeCategory
    val byQuery = query.isEmpty() ||
        item.name.contains(query, ignoreCase = true) ||
        item.stall.contains(query, ignoreCase = true)
    return byCategory && byQuery
}

private fun JajaninView.sectionHeader(c: Canvas, title: String, y: Float, w: Float) {
    kit.text(c, title, MARGIN, y, 17f, kit.ink, true)
    kit.text(c, SEE_ALL, w - MARGIN - kit.measure(SEE_ALL, 12f, true), y, 12f, kit.blue, true)
}

private fun JajaninView.stallCard(c: Canvas, r: RectF, name: String, sub: String, imageId: Int) {
    kit.rounded(c, r, Color.WHITE, 16f, kit.border)
    kit.image(c, imageId, RectF(r.left + 7f, r.top + 7f, r.right - 7f, r.top + 82f), 12f)
    kit.text(c, name, r.left + 13f, r.top + 105f, 13f, kit.ink, true)
    kit.text(c, sub, r.left + 13f, r.top + 126f, 11f, kit.muted)
}

private fun JajaninView.foodCard(c: Canvas, item: MenuItem, r: RectF) {
    kit.rounded(c, r, Color.WHITE, 16f, kit.border)
    kit.image(c, item.image, RectF(r.left + 9f, r.top + 9f, r.left + 99f, r.bottom - 9f), 12f)
    kit.text(c, item.name, r.left + 110f, r.top + 31f, 14f, kit.ink, true)
    kit.text(c, item.stall, r.left + 110f, r.top + 51f, 10f, kit.muted)
    kit.text(c, "★ ${item.rating}  ·  Favorit", r.left + 110f, r.top + 71f, 11f, kit.star, true)
    kit.text(c, item.price, r.left + 110f, r.top + 100f, 15f, kit.ink, true)
    val chip = RectF(r.right - 67f, r.bottom - 40f, r.right - 10f, r.bottom - 9f)
    kit.rounded(c, chip, kit.tint, 15f)
    kit.centerText(c, "+ Tambah", chip, 10f, kit.blue, true)
}

private fun visibleHit(r: RectF, clipBottom: Float): RectF =
    RectF(r.left, r.top.coerceAtLeast(VIEWPORT_TOP), r.right, r.bottom.coerceAtMost(clipBottom))
