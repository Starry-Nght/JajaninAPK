package com.jajanin.app.feature.buyer.profile

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.exp.EXP_MAX_LEVEL
import com.jajanin.app.feature.buyer.exp.expLevel
import com.jajanin.app.feature.buyer.exp.expLevelStart
import com.jajanin.app.feature.buyer.exp.expNextAt
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.dashboard.drawAdminNav
import com.jajanin.app.feature.seller.profile.drawSellerProfile

internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    if (isSellerProfile) {
        drawSellerProfile(c, w, h)
        return
    }
    c.drawColor(kit.bg)
    header(c, "Profil")
    val off = -scroll
    val navClear = h - GLASS_TOP_OFFSET - 12f
    fun visible(top: Float, bottom: Float) = bottom >= 86f && top <= navClear

    val headTop = 92f + off
    if (visible(headTop, headTop + 100f)) {
        kit.card(c, RectF(18f, headTop, 372f, headTop + 100f), 16f)
        val avatar = RectF(34f, headTop + 20f, 94f, headTop + 80f)
        kit.rounded(c, avatar, kit.tint, 30f)
        // Seller: tampilkan nama toko, bukan email akun. Buyer: nama asli akun.
        val titleText = if (isSellerProfile) {
            myStallName.takeIf { it.isNotBlank() }?.take(24) ?: "Memuat toko..."
        } else {
            myDisplayName.takeIf { it.isNotBlank() }?.take(24) ?: "Pengguna Jajanin"
        }
        kit.centerText(c, (titleText.firstOrNull() ?: 'A').uppercaseChar().toString(), avatar, 25f, kit.blue, true)
        kit.text(c, titleText, 110f, headTop + 41f, 17f, kit.ink, true)
        kit.text(c, SupabaseRepository.currentEmail()?.take(34) ?: "Belum masuk", 110f, headTop + 64f, 12f, kit.muted)
        val suspended = myAccountStatus == "suspended"
        kit.text(c, if (suspended) "Status: Ditangguhkan" else "Status: Aktif", 110f, headTop + 86f, 11f, if (suspended) kit.danger else kit.success, true)
    }

    // Kartu level pembeli (buyer saja): badge + EXP + progres + riwayat.
    var y = 216f + off
    if (!isSellerProfile) {
        val level = expLevel(myExp)
        val nextAt = expNextAt(level)
        val start = expLevelStart(level)
        val frac = if (nextAt == null) 1f else ((myExp - start).toFloat() / (nextAt - start).toFloat()).coerceIn(0f, 1f)
        var badgeR = 26f
        if (expPulseAt > 0L && motionEnabled()) {
            val t = ((android.os.SystemClock.uptimeMillis() - expPulseAt) / 600f).coerceIn(0f, 1f)
            if (t < 1f) {
                badgeR += 8f * (1f - t)
                postInvalidateOnAnimation()
            }
        }
        val card = RectF(18f, 204f + off, 372f, 300f + off)
        if (visible(card.top, card.bottom)) {
            kit.card(c, card, 16f)
            kit.rounded(c, RectF(66f - badgeR, card.top + 48f - badgeR, 66f + badgeR, card.top + 48f + badgeR), kit.tint, badgeR)
            kit.centerText(c, "Lv $level", RectF(30f, card.top + 36f, 102f, card.top + 60f), 13f, kit.blue, true)
            kit.text(c, if (level >= EXP_MAX_LEVEL) "★ Prestise" else "Level $level", 104f, card.top + 28f, 11f, kit.muted, true)
            kit.text(c, "$myExp EXP", 104f, card.top + 54f, 20f, kit.ink, true)
            kit.rounded(c, RectF(104f, card.top + 64f, 340f, card.top + 74f), kit.border, 5f)
            if (frac > 0f) {
                val fw = (340f - 104f) * frac
                if (fw >= 10f) kit.rounded(c, RectF(104f, card.top + 64f, 104f + fw, card.top + 74f), kit.blue, 5f)
            }
            kit.text(
                c,
                if (nextAt == null) "$myExp EXP · level maks" else "${myExp - start} / ${nextAt - start} menuju Level ${level + 1}",
                104f, card.top + 90f, 10f, kit.muted,
            )
        }
        bounds["expHistory"] = RectF(card)
        y = 312f + off
    }
    // Nomor HP sendiri (pembeli saja; wajib sebelum memesan).
    if (!isSellerProfile) {
        val row = RectF(18f, y, 372f, y + 50f)
        if (visible(row.top, row.bottom)) {
            kit.rounded(c, row, kit.card, 13f, kit.border)
            kit.text(c, "Nomor HP", 34f, y + 22f, 14f, kit.ink)
            kit.text(c, myPhone.takeIf { it.isNotBlank() } ?: "Belum diisi · ketuk untuk isi", 34f, y + 40f, 10f, if (myPhone.isBlank()) kit.danger else kit.blue, true)
            drawIcon(c, "chevronRight", 349f, y + 25f, 15f, kit.muted)
            press(c, "profilePhone", row, 13f)
        }
        bounds["profilePhone"] = RectF(row)
        y += 62f
    }
    val infoRows = mutableListOf(
        "Bantuan" to "help",
        "Tentang Jajanin" to "about",
        "INFORMASI PENTING" to "infoPage",
    )
    if (!isSellerProfile) infoRows.add(0, "Riwayat EXP" to "expHistory")
    infoRows.forEach { (title, key) ->
        val row = RectF(18f, y, 372f, y + 50f)
        if (visible(row.top, row.bottom)) {
            kit.rounded(c, row, kit.card, 13f, kit.border)
            kit.text(c, title, 34f, y + 31f, 14f, kit.ink)
            drawIcon(c, "chevronRight", 349f, y + 25f, 15f, kit.muted)
        }
        bounds[key] = RectF(row)
        y += 62f
    }

    // Kotak masuk developer: peringatan + pengumuman, badge bila ada yang belum dibaca.
    val inboxY = y
    val inboxRow = RectF(18f, inboxY, 372f, inboxY + 50f)
    if (visible(inboxRow.top, inboxRow.bottom)) {
        kit.rounded(c, inboxRow, kit.card, 13f, kit.border)
        kit.text(c, "Notifikasi", 34f, inboxY + 22f, 14f, kit.ink)
        val unread = inboxUnreadCount()
        kit.text(c, if (unread > 0) "$unread belum dibaca" else "Tidak ada pesan baru", 34f, inboxY + 40f, 10f, if (unread > 0) kit.danger else kit.success, true)
        drawIcon(c, "chevronRight", 349f, inboxY + 25f, 15f, kit.muted)
    }
    bounds["inbox"] = RectF(inboxRow)

    // Pilih tema (berlaku untuk buyer, seller, dan developer).
    val themeY = inboxY + 58f
    val themeRow = RectF(18f, themeY, 372f, themeY + 50f)
    if (visible(themeRow.top, themeRow.bottom)) {
        kit.rounded(c, themeRow, kit.card, 13f, kit.border)
        kit.text(c, "Tema aplikasi", 34f, themeY + 22f, 14f, kit.ink)
        kit.text(c, themeLabel, 34f, themeY + 40f, 10f, kit.blue, true)
        drawIcon(c, "chevronRight", 349f, themeY + 25f, 15f, kit.muted)
    }
    bounds["theme"] = RectF(themeRow)

    var logoutTop = themeY + 58f + 18f
    if (isSellerProfile) {
        // Button ubah nama toko (seller saja).
        val sy = themeY + 58f
        val stallRow = RectF(18f, sy, 372f, sy + 50f)
        if (visible(stallRow.top, stallRow.bottom)) {
            kit.rounded(c, stallRow, kit.card, 13f, kit.border)
            kit.text(c, "Nama toko", 34f, sy + 22f, 14f, kit.ink)
            kit.text(c, "${myStallName.takeIf { it.isNotBlank() } ?: "Belum diisi"} · Ubah", 34f, sy + 40f, 10f, kit.blue, true)
            drawIcon(c, "chevronRight", 349f, sy + 25f, 15f, kit.muted)
            press(c, "profileStallName", stallRow, 13f)
        }
        bounds["profileStallName"] = RectF(stallRow)

        val qy = sy + 58f
        val qrisRow = RectF(18f, qy, 372f, qy + 50f)
        if (visible(qrisRow.top, qrisRow.bottom)) {
            kit.rounded(c, qrisRow, kit.card, 13f, kit.border)
            kit.text(c, "QRIS warung saya", 34f, qy + 22f, 14f, kit.ink)
            kit.text(c, if (myStallQris.isBlank()) "Belum dipasang" else "Terpasang ✓", 34f, qy + 40f, 10f, if (myStallQris.isBlank()) kit.danger else kit.success, true)
            drawIcon(c, "chevronRight", 349f, qy + 25f, 15f, kit.muted)
            press(c, "profileQris", qrisRow, 13f)
        }
        bounds["profileQris"] = RectF(qrisRow)
        logoutTop = qy + 50f + 18f
    }

    val logoutRow = RectF(18f, logoutTop, 372f, logoutTop + 54f)
    if (visible(logoutRow.top, logoutRow.bottom)) {
        kit.rounded(c, logoutRow, kit.card, 14f, kit.dangerBorder)
        kit.centerText(c, "Keluar dari akun", logoutRow, 14f, kit.danger, true)
        press(c, "logout", logoutRow, 14f)
    }
    bounds["logout"] = logoutRow
    // Ruang scroll: tombol logout harus bisa digulir ke atas nav melayang.
    contentHeight = logoutRow.bottom - off + GLASS_TOP_OFFSET + 24f
    if (isSellerProfile) drawAdminNav(c, w, h, "Profil") else drawBottomNav(c, w, h, "Profile")
}
