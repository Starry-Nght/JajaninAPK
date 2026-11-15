package com.jajanin.app.feature.seller.profile

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.SellerSettingRow
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSettingsGroup
import com.jajanin.app.feature.seller.sellerSurface
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.dashboard.drawAdminNav

internal fun JajaninView.drawSellerProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val navClear = h - GLASS_TOP_OFFSET - 12f
    fun visible(top: Float, bottom: Float) = bottom >= 86f && top <= navClear
    val isVisible: (Float, Float) -> Boolean = { top, bottom -> visible(top, bottom) }

    var y = 96f + off

    val identity = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 92f)
    if (visible(identity.top, identity.bottom)) {
        sellerSurface(c, identity)
        val stall = myStallName.takeIf { it.isNotBlank() }
        val initial = (stall?.firstOrNull() ?: SupabaseRepository.currentEmail()?.firstOrNull() ?: 'J')
            .uppercaseChar().toString()
        val avatar = RectF(32f, y + 26f, 68f, y + 62f)
        kit.rounded(c, avatar, kit.tint, 18f)
        kit.centerText(c, initial, avatar, 18f, kit.blue, true)

        val suspended = myAccountStatus == "suspended"
        val statusRect = sellerPill(
            c,
            if (suspended) "Ditangguhkan" else "Aktif",
            356f,
            y + 34f,
            if (suspended) kit.dangerBorder else kit.successSoft,
            if (suspended) kit.danger else kit.success,
            height = 22f,
            size = 9.5f,
        )
        val nameWidth = (statusRect.left - 80f - 12f).coerceAtLeast(90f)
        kit.textEllipsized(c, stall ?: "Toko saya", 80f, y + 38f, nameWidth, 16f, kit.ink, true)
        kit.textEllipsized(
            c, SupabaseRepository.currentEmail() ?: "Belum masuk",
            80f, y + 58f, 276f, 11f, kit.muted,
        )
        kit.text(c, "Akun penjual", 80f, y + 78f, 9.5f, kit.blue, true)
    }
    y += 92f + SellerTokens.SECTION_GAP

    sellerSection(c, "Toko", y + 12f)
    y += 26f
    y = sellerSettingsGroup(
        c, y,
        listOf(
            SellerSettingRow(
                title = "Nama toko",
                key = "profileStallName",
                subtitle = myStallName.takeIf { it.isNotBlank() } ?: "Belum diisi",
                value = "Ubah",
                valueColor = kit.blue,
                chevron = false,
            ),
            SellerSettingRow(
                title = "QRIS warung",
                key = "profileQris",
                subtitle = if (myStallQris.isBlank()) "Belum dipasang" else "Siap menerima pembayaran QRIS",
                badge = if (myStallQris.isBlank()) "Belum" else "Terpasang",
                badgeColor = if (myStallQris.isBlank()) kit.danger else kit.success,
                chevron = true,
            ),
        ),
        isVisible,
        navClear,
    )
    y += SellerTokens.SECTION_GAP

    sellerSection(c, "Akun & aplikasi", y + 12f)
    y += 26f
    val unread = inboxUnreadCount()
    y = sellerSettingsGroup(
        c, y,
        listOf(
            SellerSettingRow(
                title = "Notifikasi",
                key = "inbox",
                subtitle = if (unread > 0) "$unread pesan belum dibaca" else "Tidak ada pesan baru",
                badge = if (unread > 0) if (unread > 9) "9+" else unread.toString() else null,
                badgeColor = if (unread > 0) kit.danger else null,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Tema aplikasi",
                key = "theme",
                subtitle = "Terang, gelap, atau ikuti sistem",
                value = themeLabel,
                valueColor = kit.blue,
                chevron = true,
            ),
        ),
        isVisible,
        navClear,
    )
    y += SellerTokens.SECTION_GAP

    sellerSection(c, "Bantuan & informasi", y + 12f)
    y += 26f
    y = sellerSettingsGroup(
        c, y,
        listOf(
            SellerSettingRow("Bantuan", "help", subtitle = "Cara berjualan di Jajanin"),
            SellerSettingRow("Tentang Jajanin", "about", subtitle = "Versi dan informasi aplikasi"),
            SellerSettingRow("Informasi penting", "infoPage", subtitle = "Ketentuan dan panduan"),
        ),
        isVisible,
        navClear,
    )
    y += SellerTokens.SECTION_GAP

    val logout = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 54f)
    if (visible(logout.top, logout.bottom)) {
        kit.rounded(c, logout, kit.card, SellerTokens.RADIUS_CONTROL, kit.dangerBorder)
        kit.text(c, "Keluar dari akun", 34f, y + 34f, 13.5f, kit.danger, true)
        press(c, "logout", logout, 0f)
    }
    bounds.putVisible("logout", logout, bottomLimit = navClear)
    contentHeight = logout.bottom - off + 24f

    sellerHeader(c, "Profil")
    drawAdminNav(c, w, h, "Profil")
}
