package com.jajanin.app.feature.buyer.profile

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.BuildConfig
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.profile.drawSellerProfile

private const val HEADER_H = 60f

internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    if (isSellerProfile) {
        drawSellerProfile(c, w, h)
        return
    }
    c.drawColor(kit.bg)
    val off = -scroll
    val navClear = h - GLASS_TOP_OFFSET - 12f
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 78f + off

    // 1. Open Editorial Identity Block (No outer card box)
    if (visible(y, y + 72f)) {
        val titleText = myDisplayName.takeIf { it.isNotBlank() }?.take(26) ?: "Pengguna Jajanin"
        val initial = (titleText.firstOrNull() ?: 'A').uppercaseChar().toString()
        val avatar = RectF(20f, y, 72f, y + 52f)
        kit.rounded(c, avatar, kit.tint, 8f)
        kit.centerText(c, initial, avatar, 21f, kit.blue, true)

        val suspended = myAccountStatus == "suspended"
        val statusText = if (suspended) "Ditangguhkan" else "Aktif"
        val statusColor = if (suspended) kit.danger else kit.success
        val statusBg = if (suspended) kit.dangerBorder else kit.successSoft
        val statusW = kit.measure(statusText, 9.5f, true) + 16f
        val statusPill = RectF(370f - statusW, y + 4f, 370f, y + 24f)
        kit.rounded(c, statusPill, statusBg, 5f)
        kit.centerText(c, statusText, statusPill, 9.5f, statusColor, true)

        kit.textEllipsized(c, titleText, 84f, y + 19f, statusPill.left - 94f, 16.5f, kit.ink, true)
        val nisText = buildString {
            append(if (myNis.isNotBlank()) "NIS: $myNis" else "NIS: Belum diisi")
            append("  ·  ")
            append(if (myClass.isNotBlank()) "Kelas: $myClass" else "Kelas: Belum diisi")
        }
        val nisColor = if (myNis.isNotBlank() && myClass.isNotBlank()) kit.blue else kit.danger
        kit.textEllipsized(c, nisText, 84f, y + 36f, 280f, 12f, nisColor, true)
        kit.textEllipsized(
            c,
            SupabaseRepository.currentEmail() ?: "Belum masuk",
            84f,
            y + 53f,
            280f,
            11.5f,
            kit.muted
        )
    }
    y += 76f

    // 1b. Dual-Mode Switcher / Aktivasi Penjual (1 Akun: Pembeli <-> Penjual via Email OTP)
    val sellerCard = RectF(20f, y, 370f, y + 60f)
    if (visible(sellerCard.top, sellerCard.bottom)) {
        kit.rounded(c, sellerCard, kit.tint, 10f, kit.blue)
        kit.rect(c, 20f, y + 10f, 24f, y + 50f, kit.blue)
        if (myIsSellerVerified) {
            kit.textEllipsized(c, "Beralih ke Mode Penjual", 36f, y + 25f, 240f, 13.5f, kit.blue, true)
            val stallSub = myStallName.takeIf { it.isNotBlank() } ?: "Kelola produk, stok & pesanan warungmu"
            kit.textEllipsized(c, stallSub, 36f, y + 44f, 240f, 11f, kit.muted)
            kit.textRight(c, "Buka →", 356f, y + 34f, 70f, 12f, kit.blue, true)
            bounds.putVisible("switchToSeller", sellerCard, topLimit = HEADER_H, bottomLimit = navClear)
            press(c, "switchToSeller", sellerCard, 10f)
        } else {
            kit.textEllipsized(c, "Mulai Berjualan di Jajanin", 36f, y + 25f, 236f, 13.5f, kit.blue, true)
            kit.textEllipsized(c, "Aktifkan Mode Penjual via Email OTP 6 digit", 36f, y + 44f, 242f, 10.5f, kit.muted)
            kit.textRight(c, "Aktifkan →", 356f, y + 34f, 80f, 11.5f, kit.blue, true)
            bounds.putVisible("activateSeller", sellerCard, topLimit = HEADER_H, bottomLimit = navClear)
            press(c, "activateSeller", sellerCard, 10f)
        }
    }
    y += 74f

    // 2. Section: IDENTITAS & AKUN (Clean hairline-divided rows, no EXP)
    if (visible(y, y + 20f)) {
        kit.text(c, "IDENTITAS & AKUN", 20f, y + 12f, 10.5f, kit.muted, true)
    }
    y += 20f

    val unread = inboxUnreadCount()
    val accountRows = listOf(
        ProfileRowSpec(
            title = "Nama Asli, NIS & Kelas (Wajib)",
            subtitle = "${myDisplayName.ifBlank { "Belum diisi" }}  ·  NIS: ${myNis.ifBlank { "-" }}  ·  Kelas: ${myClass.ifBlank { "Wajib diisi" }}",
            trailing = if (myNis.isBlank() || myDisplayName.isBlank() || myClass.isBlank()) "Lengkapi" else "Ubah",
            trailingColor = if (myNis.isBlank() || myDisplayName.isBlank() || myClass.isBlank()) kit.danger else kit.blue,
            key = "profileNis"
        ),
        ProfileRowSpec(
            title = "Nomor HP",
            subtitle = myPhone.takeIf { it.isNotBlank() } ?: "Belum diisi · wajib sebelum memesan",
            trailing = if (myPhone.isBlank()) "Isi" else "Ubah",
            trailingColor = if (myPhone.isBlank()) kit.danger else kit.blue,
            key = "profilePhone"
        ),
        ProfileRowSpec(
            title = "Notifikasi & Pesan",
            subtitle = if (unread > 0) "$unread pesan belum dibaca" else "Tidak ada pesan baru",
            trailing = if (unread > 0) "$unread baru" else null,
            trailingColor = kit.danger,
            key = "inbox"
        )
    )
    y = drawCleanProfileGroup(c, y, accountRows, ::visible, navClear)
    y += 22f

    // 4. Section: PREFERENSI & INFORMASI
    if (visible(y, y + 20f)) {
        kit.text(c, "PREFERENSI & INFORMASI", 20f, y + 12f, 10.5f, kit.muted, true)
    }
    y += 20f

    val appRows = listOf(
        ProfileRowSpec(
            title = "Tema Aplikasi",
            subtitle = "Terang, gelap, atau ikuti sistem",
            trailing = themeLabel,
            trailingColor = kit.blue,
            key = "theme"
        ),
        ProfileRowSpec(
            title = "Bantuan",
            subtitle = "Panduan cara memesan di Jajanin",
            key = "help"
        ),
        ProfileRowSpec(
            title = "Tentang Jajanin",
            subtitle = "Informasi aplikasi & versi",
            key = "about"
        ),
        ProfileRowSpec(
            title = "Informasi Penting",
            subtitle = "Aturan pemesanan & jam operasional",
            key = "infoPage"
        ),
        ProfileRowSpec(
            title = "Cek Update (OTA)",
            subtitle = if (otaUpdateAvailable) {
                otaLatestChangelog?.replace('\n', ' ') ?: "Pembaruan siap dipasang langsung dari aplikasi"
            } else {
                "Periksa versi terbaru tanpa install ulang"
            },
            trailing = if (otaUpdateAvailable && !otaLatestVersion.isNullOrBlank()) {
                "Update $otaLatestVersion"
            } else {
                "v${BuildConfig.VERSION_NAME}"
            },
            trailingColor = if (otaUpdateAvailable) kit.blue else kit.muted,
            key = "checkOta"
        )
    )
    y = drawCleanProfileGroup(c, y, appRows, ::visible, navClear)
    y += 24f

    // 5. Minimal Logout Row (Radius 8f)
    val logoutRow = RectF(20f, y, 370f, y + 48f)
    if (visible(logoutRow.top, logoutRow.bottom)) {
        kit.rounded(c, logoutRow, kit.card, 8f, kit.dangerBorder)
        kit.centerText(c, "Keluar dari akun", logoutRow, 13.5f, kit.danger, true)
        press(c, "logout", logoutRow, 8f)
    }
    bounds.putVisible("logout", logoutRow, topLimit = HEADER_H, bottomLimit = navClear)

    c.restoreToCount(bodySave)

    // Flat Top Header
    c.save()
    c.translate(-slideX, 0f)
    kit.rect(c, -100f, 0f, 490f, HEADER_H, kit.bg)
    drawIcon(c, "chevronLeft", 28f, 32f, 18f, kit.ink)
    kit.text(c, "Profil", 50f, 37f, 18f, kit.ink, true)
    kit.line(c, 20f, HEADER_H, 370f, HEADER_H, kit.divider)
    c.restore()
    bounds["headerBack"] = RectF(12f, 10f, 56f, HEADER_H)

    contentHeight = logoutRow.bottom - off + GLASS_TOP_OFFSET + 24f
    drawBottomNav(c, w, h, "Profile")
}

private data class ProfileRowSpec(
    val title: String,
    val subtitle: String,
    val trailing: String? = null,
    val trailingColor: Int? = null,
    val key: String
)

private fun JajaninView.drawCleanProfileGroup(
    c: Canvas,
    startY: Float,
    rows: List<ProfileRowSpec>,
    visible: (Float, Float) -> Boolean,
    navClear: Float
): Float {
    val rowH = 56f
    val groupBottom = startY + rows.size * rowH
    if (visible(startY, groupBottom)) {
        kit.rounded(c, RectF(20f, startY, 370f, groupBottom), kit.card, 8f, kit.border)
    }
    rows.forEachIndexed { idx, row ->
        val top = startY + idx * rowH
        val rect = RectF(20f, top, 370f, top + rowH)
        if (visible(top, top + rowH)) {
            kit.textEllipsized(c, row.title, 34f, top + 24f, 210f, 13.5f, kit.ink, true)
            kit.textEllipsized(c, row.subtitle, 34f, top + 42f, 224f, 11f, kit.muted)
            if (!row.trailing.isNullOrBlank()) {
                kit.textRight(
                    c,
                    row.trailing,
                    338f,
                    top + 32f,
                    90f,
                    11.5f,
                    row.trailingColor ?: kit.muted,
                    true
                )
            }
            drawIcon(c, "chevronRight", 352f, top + rowH / 2f, 14f, kit.muted)
            press(c, row.key, rect, 8f)
            if (idx < rows.size - 1) {
                kit.line(c, 34f, top + rowH, 356f, top + rowH, kit.divider)
            }
        }
        bounds.putVisible(row.key, rect, topLimit = HEADER_H, bottomLimit = navClear)
    }
    return groupBottom
}
