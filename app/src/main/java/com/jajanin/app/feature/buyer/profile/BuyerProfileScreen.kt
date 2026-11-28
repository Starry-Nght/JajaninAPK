package com.jajanin.app.feature.buyer.profile

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.jajanin.app.BuildConfig
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.components.CleanProfileRow
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawProfileElevatedPromoCard
import com.jajanin.app.core.ui.components.drawProfileHairlineRows
import com.jajanin.app.core.ui.components.drawProfileSectionHeading
import com.jajanin.app.core.ui.drawBitmapCenterCrop
import com.jajanin.app.core.ui.drawBottomNav
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.profile.drawSellerProfile

private const val STICKY_HEADER_H = 54f

internal fun JajaninView.drawProfile(c: Canvas, w: Float, h: Float) {
    if (isSellerProfile) {
        drawSellerProfile(c, w, h)
        return
    }
    c.drawColor(kit.bg)
    val off = -scroll
    val showSticky = scroll > 28f
    val topClip = if (showSticky) STICKY_HEADER_H else 0f
    val navClear = h - GLASS_TOP_OFFSET - 12f
    fun visible(top: Float, bottom: Float) = bottom >= topClip && top <= navClear

    val bodySave = c.save()
    c.clipRect(0f, topClip, w, h)

    var y = 26f + off

    // 1. Big Editorial Title (Airbnb-inspired spacious top header)
    if (visible(y, y + 38f)) {
        kit.text(c, "Profil", 22f, y + 30f, 28f, kit.ink, true)
    }
    y += 52f

    // 2. Clean Profile Identity Row (Circular avatar + Name + NIS/Class + chevron + hairline)
    val idRowH = 74f
    val idRect = RectF(16f, y, 374f, y + idRowH)
    if (visible(y, y + idRowH + 8f)) {
        press(c, "profileAvatar", RectF(14f, y, 376f, y + idRowH), 12f)
        val titleText = myDisplayName.takeIf { it.isNotBlank() }?.take(26) ?: "Pengguna Jajanin"
        val initial = (titleText.firstOrNull() ?: 'A').uppercaseChar().toString()
        val avatar = RectF(22f, y + 8f, 80f, y + 66f)
        val avatarUrl = myAvatarUrl.takeIf { it.startsWith("https://") }
        val avatarBmp = avatarUrl?.let { ProductImageCache.images[it] }
        if (avatarBmp != null) {
            drawBitmapCenterCrop(c, avatarBmp, avatar, 29f)
            kit.rounded(c, avatar, Color.TRANSPARENT, 29f, kit.border)
        } else {
            kit.rounded(c, avatar, kit.tint, 29f)
            kit.centerText(c, initial, avatar, 22f, kit.blue, true)
            if (avatarUrl != null) activity()?.loadProductImage(avatarUrl)
        }
        // Subtle circular edit icon badge on bottom-right of avatar
        val editDot = RectF(61f, y + 47f, 81f, y + 67f)
        kit.rounded(c, editDot, kit.blue, 10f, kit.bg)
        drawIcon(c, "edit", editDot.centerX(), editDot.centerY(), 10f, Color.WHITE)

        val isDevUser = StoreVerifiedBadge.isDevIdentity(
            view = this,
            name = myDisplayName,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = titleText,
            isVerified = isDevUser,
            x = 96f,
            baselineY = y + 29f,
            maxTotalWidth = 242f,
            textSize = 17f,
            textColor = kit.ink,
            bold = true,
            isDev = isDevUser,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        val suspended = myAccountStatus == "suspended"
        val subLine1 = when {
            suspended -> "Akun ditangguhkan"
            myNis.isNotBlank() && myClass.isNotBlank() -> "NIS $myNis · Kelas $myClass"
            myNis.isNotBlank() -> "NIS $myNis"
            !SupabaseRepository.hasSession() -> "Belum masuk"
            else -> "Ubah foto profil"
        }
        kit.textEllipsized(
            c,
            subLine1,
            96f,
            y + 47f,
            242f,
            12.5f,
            if (suspended) kit.danger else kit.muted,
            suspended,
        )
        val visibleEmail = mySellerVerifiedEmail
            .trim()
            .takeIf { it.isNotBlank() && !it.endsWith("@siswa.jajanin.app", ignoreCase = true) }
            ?: SupabaseRepository.currentVisibleEmail()
        val subLine2 = visibleEmail?.let { "$it · Ubah foto" } ?: "Ketuk untuk ubah foto profil"
        kit.textEllipsized(c, subLine2, 96f, y + 63f, 242f, 11f, kit.blue)

        drawIcon(c, "chevronRight", 360f, y + idRowH / 2f, 15f, kit.ink)
        kit.line(c, 22f, y + idRowH + 6f, 368f, y + idRowH + 6f, kit.divider)
    }
    bounds.putVisible("profileAvatar", idRect, topLimit = topClip, bottomLimit = navClear)
    y += idRowH + 24f

    // 3. Elevated Feature Promo Card (Airbnb "Airbnb your place" style -> Seller Mode / Buka Warung)
    val promoHeadline = if (myIsSellerVerified) "Mode Penjual" else "Buka Warung di Jajanin"
    val promoSubtitle = if (myIsSellerVerified) {
        val stall = myStallName.takeIf { it.isNotBlank() }
        if (stall != null) "Kelola etalase $stall, stok, dan pesanan masuk." else "Kelola produk, stok, dan pesanan warungmu."
    } else {
        "Mulai berjualan di sekolah dengan mudah dan terima pesanan."
    }
    y = drawProfileElevatedPromoCard(
        c = c,
        top = y,
        headline = promoHeadline,
        subtitle = promoSubtitle,
        key = if (myIsSellerVerified) "switchToSeller" else "activateSeller",
        isSellerIllustration = true,
        visible = ::visible,
        topLimit = topClip,
        bottomLimit = navClear,
    )
    y += 26f

    // 4. Section: Pengaturan (Clean borderless icon + label + value + chevron rows)
    y = drawProfileSectionHeading(c, "Pengaturan", y, ::visible)
    val unread = inboxUnreadCount()
    val hasAvatar = myAvatarUrl.startsWith("https://")
    val settingsRows = buildList {
        if (myIsSellerVerified) {
            add(
                CleanProfileRow(
                    icon = "store",
                    title = "Menu Dagangan",
                    value = if (sellerProducts.isNotEmpty()) "${sellerProducts.size} menu" else "Buka",
                    valueColor = kit.blue,
                    key = "openSellerMenu",
                )
            )
        }
        add(
            CleanProfileRow(
                icon = "image",
                title = "Foto Profil",
                value = if (hasAvatar) "Terpasang" else "Tambah",
                valueColor = kit.blue,
                key = "profileAvatar",
                useAppIcon = true,
            )
        )
        add(
            CleanProfileRow(
                icon = "phone",
                title = "Nomor HP",
                value = myPhone.takeIf { it.isNotBlank() }?.let { displayLocalPhone(it) } ?: "Belum diisi",
                valueColor = if (myPhone.isBlank()) kit.danger else kit.muted,
                key = "profilePhone",
            )
        )
        add(
            CleanProfileRow(
                icon = "bell",
                title = "Notifikasi & Pesan",
                value = if (unread > 0) "$unread baru" else null,
                valueColor = kit.danger,
                key = "inbox",
            )
        )
        add(
            CleanProfileRow(
                icon = "theme",
                title = "Tema Aplikasi",
                value = themeLabel,
                valueColor = kit.blue,
                key = "theme",
                useAppIcon = true,
            )
        )
    }
    y = drawProfileHairlineRows(c, y, settingsRows, ::visible, topClip, navClear)
    y += 24f

    // 5. Section: Dukungan & Informasi
    y = drawProfileSectionHeading(c, "Dukungan", y, ::visible)
    val supportRows = listOf(
        CleanProfileRow(
            icon = "chat",
            title = "Bantuan",
            key = "help",
        ),
        CleanProfileRow(
            icon = "shield",
            title = "Informasi Penting",
            key = "infoPage",
        ),
        CleanProfileRow(
            icon = "box",
            title = "Tentang Jajanin",
            key = "about",
        ),
        CleanProfileRow(
            icon = "refresh",
            title = "Cek Update (OTA)",
            value = if (otaUpdateAvailable && !otaLatestVersion.isNullOrBlank()) {
                "Update $otaLatestVersion"
            } else {
                "v${BuildConfig.VERSION_NAME}"
            },
            valueColor = if (otaUpdateAvailable) kit.blue else kit.muted,
            key = "checkOta",
        ),
    )
    y = drawProfileHairlineRows(c, y, supportRows, ::visible, topClip, navClear)
    y += 26f

    // 6. Quiet Bottom Account Actions
    val logoutRow = RectF(20f, y, 370f, y + 46f)
    if (visible(logoutRow.top, logoutRow.bottom)) {
        kit.rounded(c, logoutRow, kit.card, 12f, kit.border)
        kit.centerText(c, "Keluar dari akun", logoutRow, 13.5f, kit.danger, true)
        press(c, "logout", logoutRow, 12f)
    }
    bounds.putVisible("logout", logoutRow, topLimit = topClip, bottomLimit = navClear)

    val deleteRow = RectF(20f, logoutRow.bottom + 10f, 370f, logoutRow.bottom + 48f)
    if (visible(deleteRow.top, deleteRow.bottom)) {
        val deleteLabel = if (myIsSellerVerified) "Hapus Akun Pembeli / Penjual" else "Hapus Akun Permanen"
        kit.centerText(c, deleteLabel, deleteRow, 12.5f, kit.danger, false)
        press(c, "deleteAccount", deleteRow, 10f)
    }
    bounds.putVisible("deleteAccount", deleteRow, topLimit = topClip, bottomLimit = navClear)

    val verY = deleteRow.bottom + 14f
    if (visible(verY - 12f, verY + 8f)) {
        kit.centerText(
            c,
            "Jajanin v${BuildConfig.VERSION_NAME}",
            RectF(20f, verY - 10f, 370f, verY + 8f),
            10.5f,
            kit.muted,
        )
    }

    c.restoreToCount(bodySave)

    // Minimal sticky top bar only after scrolling past the big editorial title
    if (showSticky) {
        c.save()
        c.translate(-slideX, 0f)
        kit.rect(c, -100f, 0f, 490f, STICKY_HEADER_H, kit.bg)
        kit.text(c, "Profil", 22f, 35f, 18f, kit.ink, true)
        kit.line(c, 20f, STICKY_HEADER_H, 370f, STICKY_HEADER_H, kit.divider)
        c.restore()
    }

    contentHeight = (verY + 12f) - off + GLASS_TOP_OFFSET + 20f
    drawBottomNav(c, w, h, "Profile")
}

private fun displayLocalPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw
