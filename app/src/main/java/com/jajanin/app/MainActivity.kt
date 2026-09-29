package com.jajanin.app

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.content.Context
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var ui: JajaninView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        ui = JajaninView(this)
        setContentView(ui)
    }

    override fun onBackPressed() {
        if (!ui.goBack()) super.onBackPressed()
    }
}

private enum class Screen { WELCOME, ROLE, HOME, DETAIL, CHECKOUT, QRIS, READY, ORDERS, PROFILE }

private data class MenuItem(val name: String, val stall: String, val price: String, val rating: String, val image: Int)

private class JajaninView(context: Context) : View(context) {
    private val ink = Color.rgb(27, 35, 53)
    private val blue = Color.rgb(0, 99, 214)
    private val muted = Color.rgb(112, 120, 137)
    private val bg = Color.rgb(247, 248, 252)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val items = listOf(
        MenuItem("Ayam Geprek Crispy", "Kantin Kuning · Stan Bu Ani", "Rp 18.000", "4.8", R.drawable.home),
        MenuItem("Nasi Rames Spesial", "Kantin Kuning · Warung Bu Siti", "Rp 25.000", "4.8", R.drawable.detail),
        MenuItem("Es Jeruk Peras Segar", "Kantin Biru · Bar Minuman", "Rp 8.000", "4.9", R.drawable.home),
        MenuItem("Pisang Bakar Coklat Keju", "Snack Corner · Stan 04", "Rp 12.000", "4.7", R.drawable.detail)
    )
    private var screen = Screen.WELCOME
    private val history = ArrayDeque<Screen>()
    private var selected = items[0]
    private var quantity = 1
    private var query = ""
    private var activeCategory = "Semua"
    private var tab = "Home"
    private var scroll = 0f
    private var contentHeight = 0f
    private var touchStartY = 0f
    private var previousY = 0f
    private var moved = false
    private var searchFocused = false
    private val bounds = mutableMapOf<String, RectF>()

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    private fun go(next: Screen) {
        if (screen == next) return
        history.addLast(screen)
        screen = next
        scroll = 0f
        invalidate()
    }

    fun goBack(): Boolean {
        if (history.isEmpty()) return false
        screen = history.removeLast()
        scroll = 0f
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 390f
        canvas.save()
        canvas.scale(s, s)
        val w = 390f
        val h = height / s
        bounds.clear()
        when (screen) {
            Screen.WELCOME -> drawWelcome(canvas, w, h)
            Screen.ROLE -> drawRole(canvas, w, h)
            Screen.HOME -> drawHome(canvas, w, h)
            Screen.DETAIL -> drawDetail(canvas, w, h)
            Screen.CHECKOUT -> drawCheckout(canvas, w, h)
            Screen.QRIS -> drawPayment(canvas, w, h)
            Screen.READY -> drawReady(canvas, w, h)
            Screen.ORDERS -> drawOrders(canvas, w, h)
            Screen.PROFILE -> drawProfile(canvas, w, h)
        }
        canvas.restore()
    }

    private fun rounded(c: Canvas, r: RectF, color: Int, radius: Float = 16f, stroke: Int? = null) {
        p.color = color; p.style = Paint.Style.FILL
        c.drawRoundRect(r, radius, radius, p)
        if (stroke != null) { p.color = stroke; p.style = Paint.Style.STROKE; p.strokeWidth = 1f; c.drawRoundRect(r, radius, radius, p); p.style = Paint.Style.FILL }
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = ink, bold: Boolean = false) {
        p.color = color; p.textSize = size; p.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL); p.style = Paint.Style.FILL
        c.drawText(value, x, y, p)
    }
    private fun centerText(c: Canvas, value: String, r: RectF, size: Float, color: Int = ink, bold: Boolean = false) {
        p.textSize = size; p.typeface = if (bold) Typeface.create("sans-serif-medium", 0) else Typeface.create("sans-serif", 0)
        text(c, value, r.centerX() - p.measureText(value) / 2f, r.centerY() + size * .34f, size, color, bold)
    }
    private fun button(c: Canvas, key: String, label: String, r: RectF, color: Int = blue) {
        rounded(c, r, color, 14f); centerText(c, label, r, 15f, Color.WHITE, true); bounds[key] = RectF(r)
    }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int = Color.rgb(232, 234, 240)) {
        p.color = color; p.strokeWidth = 1f; c.drawLine(x1, y1, x2, y2, p)
    }

    private fun drawWelcome(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.WHITE)
        text(c, "JAJANIN", 30f, 68f, 18f, blue, true)
        rounded(c, RectF(30f, 116f, 360f, minOf(h * .59f, 485f)), Color.rgb(232, 242, 255), 26f)
        image(c, R.drawable.welcome, RectF(30f, 116f, 360f, minOf(h * .59f, 485f)))
        text(c, "Jajan enak,", 30f, h * .68f, 32f, ink, true)
        text(c, "nggak pake lama.", 30f, h * .68f + 39f, 32f, ink, true)
        text(c, "Temukan makanan favoritmu di sekitar kampus.", 30f, h * .68f + 71f, 14f, muted)
        button(c, "welcome", "Mulai sekarang   →", RectF(24f, h - 82f, w - 24f, h - 24f))
    }

    private fun drawRole(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg)
        text(c, "JAJANIN", 24f, 54f, 16f, blue, true)
        text(c, "Kamu mau jajan", 24f, 133f, 28f, ink, true)
        text(c, "sebagai siapa?", 24f, 168f, 28f, ink, true)
        roleCard(c, "Pembeli", "Cari makanan dan pesan dari kantin", 210f, "buyer")
        roleCard(c, "Penjual", "Kelola menu dan pesanan kantin", 326f, "seller")
        text(c, "Kamu bisa mengubah pilihan ini nanti.", 24f, h - 104f, 13f, muted)
        button(c, "role", "Lanjutkan", RectF(24f, h - 78f, w - 24f, h - 22f))
    }
    private fun roleCard(c: Canvas, title: String, subtitle: String, y: Float, key: String) {
        val r = RectF(24f, y, 366f, y + 96f); rounded(c, r, Color.WHITE, 18f, Color.rgb(232, 235, 242))
        rounded(c, RectF(40f, y + 22f, 82f, y + 64f), Color.rgb(232, 242, 255), 12f)
        centerText(c, if (key == "buyer") "⌕" else "▦", RectF(40f, y + 22f, 82f, y + 64f), 24f, blue, true)
        text(c, title, 98f, y + 41f, 17f, ink, true); text(c, subtitle, 98f, y + 65f, 12f, muted)
        text(c, "○", 330f, y + 55f, 19f, blue)
        bounds[key] = RectF(r)
    }

    private fun drawHome(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg)
        val headerBottom = 74f
        c.drawRect(0f, 0f, w, headerBottom, Paint().apply { color = Color.WHITE })
        text(c, "Jajanin", 22f, 45f, 23f, ink, true); text(c, "⌂", 326f, 45f, 23f, blue, true)
        rounded(c, RectF(18f, 88f, w - 18f, 140f), Color.WHITE, 16f)
        text(c, "⌕", 32f, 123f, 28f, muted); text(c, if (query.isEmpty()) "Makan apa hari ini?" else query, 66f, 120f, 15f, if(query.isEmpty()) muted else ink)
        bounds["search"] = RectF(18f, 88f, w - 18f, 140f)
        text(c, "Lokasi kamu", 22f, 169f, 12f, muted); text(c, "Kampus · Jakarta Selatan  ⌄", 22f, 190f, 15f, ink, true)
        text(c, "KATEGORI", 22f, 224f, 11f, muted, true)
        val cats = listOf("Semua", "Makanan", "Minuman", "Camilan")
        var cx = 18f
        cats.forEach { cat ->
            val cw = if (cat == "Makanan") 96f else 79f
            val r = RectF(cx, 238f, cx + cw, 274f)
            rounded(c, r, if(activeCategory == cat) blue else Color.WHITE, 18f)
            centerText(c, cat, r, 12f, if(activeCategory == cat) Color.WHITE else ink, true)
            bounds["cat:$cat"] = r; cx += cw + 8f
        }
        text(c, "Kantin dekatmu", 22f, 310f, 19f, ink, true); text(c, "Lihat semua", 292f, 309f, 12f, blue, true)
        val top = 324f - scroll
        stallCard(c, RectF(18f, top, 189f, top + 142f), "Kantin Kuning", "Buka · 5–10 mnt", R.drawable.home, "stall1")
        stallCard(c, RectF(201f, top, 372f, top + 142f), "Kantin Biru Segar", "Buka · 3–5 mnt", R.drawable.home, "stall2")
        text(c, "Rekomendasi hari ini", 22f, top + 178f, 19f, ink, true)
        text(c, "Lihat semua", 300f, top + 177f, 12f, blue, true)
        var y = top + 191f
        items.forEachIndexed { i, item ->
            if (activeCategory == "Semua" || i == 0 || activeCategory == "Makanan" && i == 1 || activeCategory == "Minuman" && i == 2 || activeCategory == "Camilan" && i == 3) {
                foodCard(c, item, RectF(18f, y, 372f, y + 116f), "item$i")
                y += 126f
            }
        }
        contentHeight = y + 20f
        drawBottomNav(c, w, h, "Home")
    }
    private fun stallCard(c: Canvas, r: RectF, name: String, sub: String, imageId: Int, key: String) {
        rounded(c, r, Color.WHITE, 16f)
        image(c, imageId, RectF(r.left + 7f, r.top + 7f, r.right - 7f, r.top + 82f), 12f)
        text(c, name, r.left + 11f, r.top + 105f, 13f, ink, true); text(c, sub, r.left + 11f, r.top + 126f, 11f, muted)
        bounds[key] = RectF(r)
    }
    private fun foodCard(c: Canvas, item: MenuItem, r: RectF, key: String) {
        rounded(c, r, Color.WHITE, 16f)
        image(c, item.image, RectF(r.left + 9f, r.top + 9f, r.left + 99f, r.bottom - 9f), 12f)
        text(c, item.name, r.left + 110f, r.top + 31f, 14f, ink, true)
        text(c, item.stall, r.left + 110f, r.top + 51f, 10f, muted)
        text(c, "★ ${item.rating}  ·  Favorit", r.left + 110f, r.top + 70f, 11f, Color.rgb(225, 151, 0), true)
        text(c, item.price, r.left + 110f, r.top + 99f, 14f, ink, true)
        rounded(c, RectF(r.right - 67f, r.bottom - 40f, r.right - 10f, r.bottom - 9f), Color.rgb(232, 242, 255), 12f)
        centerText(c, "+ Tambah", RectF(r.right - 67f, r.bottom - 40f, r.right - 10f, r.bottom - 9f), 10f, blue, true)
        bounds[key] = RectF(r)
    }

    private fun drawDetail(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg)
        image(c, selected.image, RectF(0f, 0f, w, minOf(h * .34f, 290f)))
        rounded(c, RectF(18f, 18f, 58f, 58f), Color.WHITE, 20f); centerText(c, "‹", RectF(18f, 18f, 58f, 58f), 27f, ink, true); bounds["back"] = RectF(18f, 18f, 58f, 58f)
        val y = minOf(h * .34f, 290f)
        rounded(c, RectF(0f, y - 18f, w, h + 20f), Color.WHITE, 22f)
        text(c, selected.name, 24f, y + 25f, 23f, ink, true)
        text(c, selected.stall, 24f, y + 49f, 13f, muted)
        text(c, "★ ${selected.rating}   ·   120+ terjual", 24f, y + 77f, 13f, Color.rgb(225, 151, 0), true)
        line(c, 24f, y + 97f, w - 24f, y + 97f)
        text(c, "Tentang menu", 24f, y + 127f, 16f, ink, true)
        text(c, "Disiapkan segar oleh penjual kantin pilihan.", 24f, y + 153f, 13f, muted)
        text(c, "Pedas, gurih, dan pas buat nemenin", 24f, y + 175f, 13f, muted)
        text(c, "waktu istirahatmu.", 24f, y + 196f, 13f, muted)
        text(c, "Jumlah", 24f, h - 104f, 14f, ink, true)
        rounded(c, RectF(234f, h - 126f, 366f, h - 76f), bg, 14f)
        text(c, "−", 249f, h - 94f, 23f, blue, true); text(c, "$quantity", 296f, h - 94f, 15f, ink, true); text(c, "+", 337f, h - 94f, 23f, blue, true)
        bounds["minus"] = RectF(234f, h - 126f, 279f, h - 76f); bounds["plus"] = RectF(321f, h - 126f, 366f, h - 76f)
        button(c, "checkout", "Tambah ke keranjang · ${selected.price}", RectF(20f, h - 65f, w - 20f, h - 12f))
    }

    private fun drawCheckout(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg); header(c, "Checkout")
        text(c, "Pesanan", 22f, 110f, 18f, ink, true)
        rounded(c, RectF(18f, 128f, 372f, 220f), Color.WHITE, 16f)
        image(c, selected.image, RectF(28f, 139f, 96f, 208f), 10f)
        text(c, selected.name, 108f, 160f, 14f, ink, true); text(c, "${selected.stall}", 108f, 181f, 11f, muted); text(c, "${selected.price}  ×  $quantity", 108f, 202f, 12f, ink, true)
        text(c, "Metode pembayaran", 22f, 258f, 18f, ink, true)
        rounded(c, RectF(18f, 276f, 372f, 338f), Color.WHITE, 15f, Color.rgb(211, 228, 251))
        text(c, "▦", 34f, 315f, 22f, blue, true); text(c, "QRIS", 73f, 306f, 14f, ink, true); text(c, "Semua e-wallet dan mobile banking", 73f, 326f, 11f, muted); text(c, "◉", 340f, 315f, 18f, blue)
        text(c, "Ringkasan", 22f, 383f, 18f, ink, true)
        summaryRow(c, "Subtotal", selected.price, 411f); summaryRow(c, "Biaya layanan", "Rp 1.000", 439f)
        line(c, 22f, 459f, 368f, 459f); summaryRow(c, "Total", if (selected.price == "Rp 25.000") "Rp 26.000" else if(selected.price == "Rp 18.000") "Rp 19.000" else "Rp 9.000", 488f, true)
        button(c, "pay", "Bayar sekarang", RectF(20f, h - 72f, w - 20f, h - 16f))
    }
    private fun summaryRow(c: Canvas, a: String, b: String, y: Float, strong: Boolean = false) { text(c, a, 24f, y, 13f, if (strong) ink else muted, strong); p.textSize = 13f; p.typeface = Typeface.create("sans-serif-medium", 0); text(c, b, 368f - p.measureText(b), y, 13f, ink, true) }

    private fun drawPayment(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg); header(c, "Pembayaran")
        text(c, "Scan kode untuk membayar", 24f, 120f, 21f, ink, true); text(c, "Selesaikan pembayaran sebelum pesanan diproses", 24f, 145f, 12f, muted)
        rounded(c, RectF(38f, 184f, 352f, 498f), Color.WHITE, 20f)
        text(c, "QRIS", 157f, 220f, 18f, blue, true)
        val qr = RectF(85f, 248f, 305f, 468f); rounded(c, qr, Color.WHITE, 4f, Color.rgb(225, 228, 234)); drawQr(c, qr)
        text(c, "Total pembayaran", 24f, 545f, 12f, muted); text(c, "${selected.price}", 24f, 576f, 25f, ink, true)
        button(c, "paid", "Saya sudah membayar", RectF(20f, h - 72f, w - 20f, h - 16f))
    }
    private fun drawQr(c: Canvas, r: RectF) {
        val n = 25; val cell = r.width() / n
        p.color = ink
        for (row in 0 until n) for (col in 0 until n) {
            val finder = (col < 7 && row < 7) || (col > n - 8 && row < 7) || (col < 7 && row > n - 8)
            val insideFinder = finder && (col % (n - 1) < 7) && (row % (n - 1) < 7)
            if ((insideFinder && (col % (n - 1) in 0..1 || col % (n - 1) == 5 || row % (n - 1) in 0..1 || row % (n - 1) == 5 || (col % (n - 1) in 2..4 && row % (n - 1) in 2..4))) || (!finder && ((row * 17 + col * 13 + row * col) % 7 < 3))) {
                c.drawRect(r.left + col * cell, r.top + row * cell, r.left + (col + 1) * cell, r.top + (row + 1) * cell, p)
            }
        }
    }
    private fun drawReady(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.WHITE)
        rounded(c, RectF(132f, 115f, 258f, 241f), Color.rgb(230, 248, 239), 63f)
        centerText(c, "✓", RectF(132f, 115f, 258f, 241f), 66f, Color.rgb(31, 158, 95), true)
        centerText(c, "Pesanan siap!", RectF(20f, 270f, w - 20f, 317f), 25f, ink, true)
        centerText(c, "Tunjukkan nomor ini ke penjual saat", RectF(15f, 327f, w - 15f, 352f), 13f, muted)
        centerText(c, "mengambil pesananmu.", RectF(15f, 349f, w - 15f, 374f), 13f, muted)
        rounded(c, RectF(38f, 412f, 352f, 537f), bg, 18f)
        centerText(c, "NOMOR PESANAN", RectF(38f, 432f, 352f, 457f), 11f, muted, true)
        centerText(c, "A-024", RectF(38f, 455f, 352f, 506f), 36f, blue, true)
        button(c, "orders", "Lihat pesanan", RectF(20f, h - 79f, w - 20f, h - 22f))
    }
    private fun drawOrders(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg); header(c, "Pesanan saya")
        rounded(c, RectF(18f, 92f, 372f, 267f), Color.WHITE, 17f)
        text(c, "●  Siap diambil", 34f, 123f, 13f, Color.rgb(28, 146, 91), true)
        text(c, "${selected.name}", 34f, 158f, 18f, ink, true); text(c, "Kantin Kuning · Pesanan A-024", 34f, 183f, 12f, muted)
        line(c, 34f, 202f, 356f, 202f); text(c, "Tunjukkan kode ke penjual", 34f, 229f, 13f, ink)
        rounded(c, RectF(287f, 215f, 354f, 250f), Color.rgb(232, 242, 255), 9f); centerText(c, "A-024", RectF(287f, 215f, 354f, 250f), 14f, blue, true)
        drawBottomNav(c, w, h, "Orders")
    }
    private fun drawProfile(c: Canvas, w: Float, h: Float) {
        c.drawColor(bg); header(c, "Profil")
        rounded(c, RectF(18f, 92f, 372f, 192f), Color.WHITE, 16f)
        rounded(c, RectF(34f, 112f, 94f, 172f), Color.rgb(232, 242, 255), 30f); centerText(c, "A", RectF(34f, 112f, 94f, 172f), 25f, blue, true)
        text(c, "Pengguna Jajanin", 110f, 137f, 17f, ink, true); text(c, "pembeli@kampus.ac.id", 110f, 160f, 12f, muted)
        listOf("Informasi akun", "Alamat tersimpan", "Bantuan", "Tentang Jajanin").forEachIndexed { i, v ->
            val y = 216f + i * 62f; rounded(c, RectF(18f, y, 372f, y + 50f), Color.WHITE, 13f); text(c, v, 34f, y + 31f, 14f, ink); text(c, "›", 343f, y + 32f, 20f, muted)
        }
        drawBottomNav(c, w, h, "Profile")
    }

    private fun header(c: Canvas, title: String) { c.drawRect(0f, 0f, 390f, 72f, Paint().apply { color = Color.WHITE }); text(c, "‹", 20f, 46f, 27f, blue, true); text(c, title, 60f, 45f, 19f, ink, true); bounds["headerBack"] = RectF(0f, 0f, 54f, 72f) }
    private fun drawBottomNav(c: Canvas, w: Float, h: Float, active: String) {
        val y = h - 67f
        p.color = Color.WHITE; c.drawRect(0f, y, w, h, p); line(c, 0f, y, w, y)
        val labels = listOf("Home", "Search", "Orders", "Profile")
        val icons = listOf("⌂", "⌕", "▤", "♙")
        labels.forEachIndexed { i, label ->
            val cx = 48f + i * 98f; val color = if (label == active) blue else muted
            centerText(c, icons[i], RectF(cx - 24f, y + 4f, cx + 24f, y + 37f), 22f, color, true)
            centerText(c, label, RectF(cx - 39f, y + 37f, cx + 39f, y + 61f), 10f, color, label == active)
            bounds["tab:$label"] = RectF(cx - 48f, y, cx + 48f, h)
        }
    }
    private fun image(c: Canvas, res: Int, r: RectF, radius: Float = 0f) {
        val bmp = BitmapFactory.decodeResource(resources, res) ?: return
        val save = c.save(); val path = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }; c.clipPath(path)
        val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
        val sw = r.width() / scale; val sh = r.height() / scale
        val src = Rect(((bmp.width - sw) / 2f).toInt(), ((bmp.height - sh) / 2f).toInt(), ((bmp.width + sw) / 2f).toInt(), ((bmp.height + sh) / 2f).toInt())
        p.color = Color.WHITE; c.drawBitmap(bmp, src, r, p); c.restoreToCount(save); bmp.recycle()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = width / 390f; val x = event.x / s; val y = event.y / s
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { touchStartY = y; previousY = y; moved = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (screen == Screen.HOME && abs(y - touchStartY) > 6f) {
                    moved = true; val maxScroll = (contentHeight - height / s + 78f).coerceAtLeast(0f)
                    scroll = (scroll + previousY - y).coerceIn(0f, maxScroll); previousY = y; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) return true
                val hit = bounds.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return true
                when {
                    hit == "welcome" -> go(Screen.ROLE)
                    hit == "role" || hit == "buyer" || hit == "seller" -> go(Screen.HOME)
                    hit == "search" -> searchFocused = true
                    hit.startsWith("cat:") -> { activeCategory = hit.removePrefix("cat:"); invalidate() }
                    hit.startsWith("item") -> { val i = hit.removePrefix("item").toIntOrNull() ?: 0; selected = items[i]; quantity = 1; go(Screen.DETAIL) }
                    hit.startsWith("tab:") -> {
                        when (hit.removePrefix("tab:")) {
                            "Home" -> { tab = "Home"; if (screen != Screen.HOME) go(Screen.HOME) }
                            "Search" -> { tab = "Search"; screen = Screen.HOME; searchFocused = true; invalidate() }
                            "Orders" -> { tab = "Orders"; go(Screen.ORDERS) }
                            "Profile" -> { tab = "Profile"; go(Screen.PROFILE) }
                        }
                    }
                    hit == "back" || hit == "headerBack" -> goBack()
                    hit == "plus" -> { quantity++; invalidate() }
                    hit == "minus" -> { quantity = (quantity - 1).coerceAtLeast(1); invalidate() }
                    hit == "checkout" -> go(Screen.CHECKOUT)
                    hit == "pay" -> go(Screen.QRIS)
                    hit == "paid" -> go(Screen.READY)
                    hit == "orders" -> go(Screen.ORDERS)
                    hit.startsWith("stall") -> { selected = items[0]; go(Screen.DETAIL) }
                }
                return true
            }
        }
        return true
    }
}
