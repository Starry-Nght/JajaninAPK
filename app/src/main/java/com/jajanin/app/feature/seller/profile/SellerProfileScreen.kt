package com.jajanin.app.feature.seller.profile

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.BuildConfig
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.components.CleanProfileRow
import com.jajanin.app.core.ui.components.StoreAvatar
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawProfileElevatedPromoCard
import com.jajanin.app.core.ui.components.drawProfileHairlineRows
import com.jajanin.app.core.ui.components.drawProfileSectionHeading
import com.jajanin.app.core.ui.drawIcon
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.dashboard.drawAdminNav
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerSurface

private const val STICKY_HEADER_H = 54f

internal fun JajaninView.drawSellerProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val showSticky = scroll > 28f
    val topClip = if (showSticky) STICKY_HEADER_H else 0f
    val navClear = h - GLASS_TOP_OFFSET - 8f
    fun visible(top: Float, bottom: Float) = bottom >= topClip && top <= navClear

    val bodySave = c.save()
    c.clipRect(0f, topClip, w, h)

    var y = 26f + off

    // 1. Big Editorial Title (Airbnb-inspired)
    if (visible(y, y + 38f)) {
        kit.text(c, "Profil Warung", 22f, y + 30f, 27f, kit.ink, true)
        val suspended = myAccountStatus == "suspended"
        val statusText = when {
            suspended -> "Ditangguhkan"
            myStallIsOpen -> "Buka"
            else -> "Tutup"
        }
        val statusBg = if (suspended || !myStallIsOpen) kit.dangerBorder else kit.successSoft
        val statusFg = if (suspended || !myStallIsOpen) kit.danger else kit.success
        sellerPill(c, statusText, 368f, y + 21f, statusBg, statusFg, height = 22f, size = 10f)
    }
    y += 52f

    // 2. Clean Store Identity Row (Circular store logo + Store Name + Owner + chevron + hairline)
    val idRowH = 74f
    val idRect = RectF(16f, y, 374f, y + idRowH)
    if (visible(y, y + idRowH + 8f)) {
        press(c, "profileStorePublic", RectF(14f, y, 376f, y + idRowH), 12f)
        val stall = myStallName.takeIf { it.isNotBlank() } ?: "Warung Saya"
        val ownerName = myDisplayName.takeIf { it.isNotBlank() } ?: "Pemilik Warung"
        val avatar = RectF(22f, y + 8f, 80f, y + 66f)
        StoreAvatar.draw(this, c, stall, myStallLogoUrl, avatar, cornerRadius = 29f)

        val isDevSeller = StoreVerifiedBadge.isDevIdentity(
            view = this,
            name = stall,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = stall,
            isVerified = myStallIsVerified || isDevSeller,
            x = 96f,
            baselineY = y + 28f,
            maxTotalWidth = 242f,
            textSize = 17f,
            textColor = kit.ink,
            bold = true,
            isDev = isDevSeller,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        val ownerWithNis = buildString {
            append(ownerName)
            if (myNis.isNotBlank()) append(" · NIS $myNis")
            if (myClass.isNotBlank()) append(" · $myClass")
        }
        kit.textEllipsized(c, ownerWithNis, 96f, y + 46f, 242f, 12f, kit.muted)
        kit.textEllipsized(c, "Lihat profil publik warung", 96f, y + 62f, 242f, 11f, kit.blue)
        drawIcon(c, "chevronRight", 360f, y + idRowH / 2f, 15f, kit.ink)
        kit.line(c, 22f, y + idRowH + 6f, 368f, y + idRowH + 6f, kit.divider)
    }
    bounds.putVisible("profileStorePublic", idRect, topLimit = topClip, bottomLimit = navClear)
    bounds.putVisible("profileStoreLogo", RectF(20f, y + 6f, 84f, y + 68f), topLimit = topClip, bottomLimit = navClear)
    y += idRowH + 22f

    // 3. Elevated Feature Promo Card -> Beralih ke Mode Pembeli
    y = drawProfileElevatedPromoCard(
        c = c,
        top = y,
        headline = "Mode Pembeli",
        subtitle = "Kembali ke beranda untuk belanja dan pesan menu warung lain.",
        key = "switchToBuyer",
        isSellerIllustration = false,
        visible = ::visible,
        topLimit = topClip,
        bottomLimit = navClear,
    )
    y += 18f

    // 4. Compact Store Metrics Strip
    val statsStrip = RectF(20f, y, 370f, y + 54f)
    if (visible(statsStrip.top, statsStrip.bottom)) {
        sellerSurface(c, statsStrip)
        val colW = SellerTokens.WIDTH / 3f
        val activeMenus = sellerProducts.count { it.available }
        val cols = listOf(
            Triple("$activeMenus/${sellerProducts.size}", "Menu Aktif", kit.ink),
            Triple(adminOrders.size.toString(), "Order Hari Ini", kit.blue),
            Triple(formatRupiah(sellerLedgerAvailable), "Saldo Midtrans", kit.blue),
        )
        cols.forEachIndexed { idx, (valText, label, colColor) ->
            val left = 20f + idx * colW
            if (idx > 0) kit.line(c, left, y + 10f, left, y + 44f, kit.divider)
            kit.textEllipsized(c, valText, left + 12f, y + 24f, colW - 20f, 13.5f, colColor, true)
            kit.text(c, label, left + 12f, y + 41f, 10f, kit.muted)
        }
        bounds.putVisible("openSellerMenu", RectF(20f, y, 20f + colW, y + 54f), topLimit = topClip, bottomLimit = navClear)
        bounds.putVisible("profileMidtrans", RectF(20f + 2f * colW, y, 370f, y + 54f), topLimit = topClip, bottomLimit = navClear)
    }
    y += 64f

    // 4b. Store Verification Progress Strip
    val isDevSellerBadge = StoreVerifiedBadge.isDevIdentity(
        view = this,
        name = myStallName,
        nis = myNis,
        stallId = myStallId.orEmpty(),
    )
    val verifCard = RectF(20f, y, 370f, y + 54f)
    if (visible(verifCard.top, verifCard.bottom)) {
        sellerSurface(c, verifCard)
        if (myStallIsVerified || isDevSellerBadge) {
            StoreVerifiedBadge.drawPill(
                view = this,
                c = c,
                left = 32f,
                top = y + 9f,
                label = "Terverifikasi Jajanin",
                height = 20f,
                textSize = 9.5f,
                isDev = isDevSellerBadge,
            )
            kit.textEllipsized(
                c,
                "$myStallCompletedOrders pesanan selesai · Lencana tampil di profil & menu",
                32f,
                y + 44f,
                326f,
                10.5f,
                kit.muted,
            )
        } else {
            val done = myStallCompletedOrders.coerceIn(0, 20)
            kit.text(c, "Verifikasi Toko Otomatis", 32f, y + 21f, 12f, kit.ink, true)
            kit.textRight(c, "$done/20 selesai", 358f, y + 21f, 96f, 11f, kit.blue, true)
            val track = RectF(32f, y + 31f, 358f, y + 36f)
            kit.rounded(c, track, kit.divider, 2.5f)
            if (done > 0) {
                val fillW = track.width() * (done / 20f)
                kit.rounded(c, RectF(track.left, track.top, track.left + fillW, track.bottom), kit.blue, 2.5f)
            }
            kit.text(c, "Aktif otomatis setelah 20 pesanan selesai.", 32f, y + 48f, 9.5f, kit.muted)
        }
    }
    y += 74f

    // 5. Section: Pengaturan Warung (Clean borderless Airbnb rows)
    y = drawProfileSectionHeading(c, "Pengaturan Warung", y, ::visible)
    val storeRows = listOf(
        CleanProfileRow(
            icon = "store",
            title = "Profil Publik Warung",
            value = "Pratinjau",
            valueColor = kit.blue,
            key = "profileStorePublic",
        ),
        CleanProfileRow(
            icon = "checkCircle",
            title = "Status Operasional",
            value = if (myStallIsOpen) "Buka" else "Tutup",
            valueColor = if (myStallIsOpen) kit.success else kit.danger,
            key = "profileStoreOpenToggle",
        ),
        CleanProfileRow(
            icon = "grid",
            title = "Menu Dagangan",
            value = "${sellerProducts.size} menu",
            valueColor = kit.blue,
            key = "openSellerMenu",
        ),
        CleanProfileRow(
            icon = "edit",
            title = "Nama Warung",
            value = myStallName.takeIf { it.isNotBlank() } ?: "Wajib diisi",
            valueColor = if (myStallName.isBlank()) kit.danger else kit.muted,
            key = "profileStallName",
        ),
        CleanProfileRow(
            icon = "box",
            title = "Kategori Warung",
            value = myStallCategory,
            valueColor = kit.muted,
            key = "profileStoreCategory",
        ),
        CleanProfileRow(
            icon = "location",
            title = "Kelas / Ruangan Ambil",
            value = myStallRoom.takeIf { it.isNotBlank() } ?: "Atur",
            valueColor = if (myStallRoom.isBlank()) kit.blue else kit.muted,
            key = "profileStallRoom",
            useAppIcon = true,
        ),
        CleanProfileRow(
            icon = "receipt",
            title = "Deskripsi Warung",
            value = if (myStallBio.isBlank()) "Isi" else "Ubah",
            valueColor = kit.blue,
            key = "profileStoreBio",
        ),
        CleanProfileRow(
            icon = "image",
            title = "Logo Warung",
            value = if (myStallLogoUrl.isBlank()) "Default" else "Terpasang",
            valueColor = if (myStallLogoUrl.isBlank()) kit.muted else kit.success,
            key = "profileStoreLogo",
            useAppIcon = true,
        ),
        CleanProfileRow(
            icon = "image",
            title = "Sampul / Banner",
            value = if (myStallBannerUrl.isBlank()) "Default" else "Terpasang",
            valueColor = if (myStallBannerUrl.isBlank()) kit.muted else kit.success,
            key = "profileStoreBanner",
            useAppIcon = true,
        ),
        CleanProfileRow(
            icon = "phone",
            title = "Nomor HP Penjual",
            value = myPhone.takeIf { it.isNotBlank() }?.let { displayLocalPhone(it) } ?: "Wajib diisi",
            valueColor = if (myPhone.isBlank()) kit.danger else kit.muted,
            key = "profilePhone",
        ),
        CleanProfileRow(
            icon = "zap",
            title = "Pembayaran Midtrans",
            value = "Aktif",
            valueColor = kit.success,
            key = "profileMidtrans",
        ),
    )
    y = drawProfileHairlineRows(c, y, storeRows, ::visible, topClip, navClear)
    y += 24f

    // 6. Section: Akun & Aplikasi
    y = drawProfileSectionHeading(c, "Akun & Aplikasi", y, ::visible)
    val unread = inboxUnreadCount()
    val appRows = listOf(
        CleanProfileRow(
            icon = "bell",
            title = "Notifikasi",
            value = if (unread > 0) "$unread baru" else null,
            valueColor = kit.danger,
            key = "inbox",
        ),
        CleanProfileRow(
            icon = "theme",
            title = "Tema Aplikasi",
            value = themeLabel,
            valueColor = kit.blue,
            key = "theme",
            useAppIcon = true,
        ),
    )
    y = drawProfileHairlineRows(c, y, appRows, ::visible, topClip, navClear)
    y += 24f

    // 7. Section: Dukungan
    y = drawProfileSectionHeading(c, "Dukungan", y, ::visible)
    val supportRows = listOf(
        CleanProfileRow(icon = "chat", title = "Bantuan", key = "help"),
        CleanProfileRow(icon = "shield", title = "Informasi Penting", key = "infoPage"),
        CleanProfileRow(icon = "box", title = "Tentang Jajanin", key = "about"),
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

    // 8. Quiet Bottom Account Actions
    val logout = RectF(20f, y, 370f, y + 46f)
    if (visible(logout.top, logout.bottom)) {
        kit.rounded(c, logout, kit.card, 12f, kit.border)
        kit.centerText(c, "Keluar dari akun", logout, 13.5f, kit.danger, true)
        press(c, "logout", logout, 12f)
    }
    bounds.putVisible("logout", logout, topLimit = topClip, bottomLimit = navClear)

    val deleteRow = RectF(20f, logout.bottom + 10f, 370f, logout.bottom + 48f)
    if (visible(deleteRow.top, deleteRow.bottom)) {
        kit.centerText(c, "Hapus Akun Penjual / Akun", deleteRow, 12.5f, kit.danger, false)
        press(c, "deleteSellerAccount", deleteRow, 10f)
    }
    bounds.putVisible("deleteSellerAccount", deleteRow, topLimit = topClip, bottomLimit = navClear)

    val verY = deleteRow.bottom + 14f
    if (visible(verY - 12f, verY + 8f)) {
        val visibleEmail = mySellerVerifiedEmail
            .trim()
            .takeIf { it.isNotBlank() && !it.endsWith("@siswa.jajanin.app", ignoreCase = true) }
            ?: SupabaseRepository.currentVisibleEmail()
        val footerText = if (visibleEmail != null) {
            "$visibleEmail · v${BuildConfig.VERSION_NAME}"
        } else {
            "Jajanin v${BuildConfig.VERSION_NAME}"
        }
        kit.centerText(c, footerText, RectF(20f, verY - 10f, 370f, verY + 8f), 10.5f, kit.muted)
    }

    c.restoreToCount(bodySave)

    if (showSticky) {
        c.save()
        c.translate(-slideX, 0f)
        kit.rect(c, -100f, 0f, 490f, STICKY_HEADER_H, kit.bg)
        kit.text(c, "Profil Warung", 22f, 35f, 18f, kit.ink, true)
        kit.line(c, 20f, STICKY_HEADER_H, 370f, STICKY_HEADER_H, kit.divider)
        c.restore()
    }

    contentHeight = (verY + 12f) - off + GLASS_TOP_OFFSET + 20f
    drawAdminNav(c, w, h, "Profil")
}

private fun displayLocalPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw
