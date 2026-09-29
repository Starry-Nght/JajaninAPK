package com.jajanin.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

internal fun JajaninView.drawHome(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    kit.rect(c, 0f, 0f, w, 74f, Color.WHITE)
    kit.line(c, 0f, 74f, w, 74f)
    kit.text(c, "Jajanin", 22f, 46f, 23f, kit.ink, true)
    kit.rounded(c, RectF(338f, 24f, 372f, 56f), kit.tint, 17f)
    kit.centerText(c, "⌂", RectF(338f, 24f, 372f, 56f), 17f, kit.blue, true)

    kit.rounded(c, RectF(18f, 88f, w - 18f, 140f), Color.WHITE, 16f, kit.border)
    kit.text(c, "⌕", 32f, 123f, 28f, kit.muted)
    kit.text(c, if (query.isEmpty()) "Makan apa hari ini?" else query, 66f, 120f, 15f, if (query.isEmpty()) kit.muted else kit.ink)
    bounds["search"] = RectF(18f, 88f, w - 18f, 140f)

    kit.rounded(c, RectF(18f, 156f, w - 18f, 206f), Color.WHITE, 14f, kit.border)
    kit.text(c, "Lokasi kamu", 34f, 176f, 11f, kit.muted)
    kit.text(c, "Kampus · Jakarta Selatan", 34f, 197f, 14f, kit.ink, true)
    kit.text(c, "⌄", 344f, 192f, 15f, kit.muted)

    kit.text(c, "KATEGORI", 22f, 238f, 11f, kit.muted, true)
    var cx = 18f
    listOf("Semua", "Makanan", "Minuman", "Camilan").forEach { cat ->
        val cw = kit.measure(cat, 12f, true) + 34f
        val r = RectF(cx, 250f, cx + cw, 288f)
        val on = activeCategory == cat
        kit.rounded(c, r, if (on) kit.blue else Color.WHITE, 19f, if (on) null else kit.border)
        kit.centerText(c, cat, r, 12f, if (on) Color.WHITE else kit.ink, true)
        bounds["cat:$cat"] = r
        cx += cw + 8f
    }

    kit.text(c, "Kantin dekatmu", 22f, 330f, 18f, kit.ink, true)
    kit.text(c, "Lihat semua", 368f - kit.measure("Lihat semua", 12f, true), 330f, 12f, kit.blue, true)

    val top = 344f - scroll
    if (top + 142f >= 0f && top <= h) {
        stallCard(c, RectF(18f, top, 189f, top + 142f), "Kantin Kuning", "Buka · 5–10 mnt", R.drawable.home, "stall1")
        stallCard(c, RectF(201f, top, 372f, top + 142f), "Kantin Biru Segar", "Buka · 3–5 mnt", R.drawable.home, "stall2")
    }

    val listTop = top + 184f
    kit.text(c, "Rekomendasi hari ini", 22f, listTop + 16f, 18f, kit.ink, true)
    kit.text(c, "Lihat semua", 368f - kit.measure("Lihat semua", 12f, true), listTop + 16f, 12f, kit.blue, true)

    var y = listTop + 29f
    items.forEachIndexed { i, item ->
        val shown = activeCategory == "Semua" || item.category == activeCategory
        if (shown) {
            val r = RectF(18f, y, 372f, y + 116f)
            if (r.bottom >= 0f && r.top <= h) foodCard(c, item, r, "item$i")
            y += 126f
        }
    }
    contentHeight = y + 20f
    drawBottomNav(c, w, h, "Home")
}

private fun JajaninView.stallCard(c: Canvas, r: RectF, name: String, sub: String, imageId: Int, key: String) {
    kit.rounded(c, r, Color.WHITE, 16f, kit.border)
    kit.image(c, imageId, RectF(r.left + 7f, r.top + 7f, r.right - 7f, r.top + 82f), 12f)
    kit.text(c, name, r.left + 13f, r.top + 105f, 13f, kit.ink, true)
    kit.text(c, sub, r.left + 13f, r.top + 126f, 11f, kit.muted)
    bounds[key] = RectF(r)
}

private fun JajaninView.foodCard(c: Canvas, item: MenuItem, r: RectF, key: String) {
    kit.rounded(c, r, Color.WHITE, 16f, kit.border)
    kit.image(c, item.image, RectF(r.left + 9f, r.top + 9f, r.left + 99f, r.bottom - 9f), 12f)
    kit.text(c, item.name, r.left + 110f, r.top + 31f, 14f, kit.ink, true)
    kit.text(c, item.stall, r.left + 110f, r.top + 51f, 10f, kit.muted)
    kit.text(c, "★ ${item.rating}  ·  Favorit", r.left + 110f, r.top + 71f, 11f, kit.star, true)
    kit.text(c, item.price, r.left + 110f, r.top + 100f, 15f, kit.ink, true)
    val chip = RectF(r.right - 67f, r.bottom - 40f, r.right - 10f, r.bottom - 9f)
    kit.rounded(c, chip, kit.tint, 15f)
    kit.centerText(c, "+ Tambah", chip, 10f, kit.blue, true)
    bounds[key] = RectF(r)
}
