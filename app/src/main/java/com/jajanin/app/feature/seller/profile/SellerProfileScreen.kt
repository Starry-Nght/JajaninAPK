package com.jajanin.app.feature.seller.profile

import android.graphics.Canvas
import android.graphics.RectF
import com.jajanin.app.BuildConfig
import com.jajanin.app.JajaninView
import com.jajanin.app.core.ui.GLASS_TOP_OFFSET
import com.jajanin.app.core.ui.components.StoreAvatar
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.ui.components.drawTrailingActionWithArrow
import com.jajanin.app.core.ui.press
import com.jajanin.app.core.ui.putVisible
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.developer.inboxUnreadCount
import com.jajanin.app.feature.seller.SellerSettingRow
import com.jajanin.app.feature.seller.SellerTokens
import com.jajanin.app.feature.seller.dashboard.drawAdminNav
import com.jajanin.app.feature.seller.sellerHeader
import com.jajanin.app.feature.seller.sellerPill
import com.jajanin.app.feature.seller.sellerSection
import com.jajanin.app.feature.seller.sellerSettingsGroup
import com.jajanin.app.feature.seller.sellerSurface

private const val HEADER_H = 60f

internal fun JajaninView.drawSellerProfile(c: Canvas, w: Float, h: Float) {
    c.drawColor(kit.bg)
    val off = -scroll
    val navClear = h - GLASS_TOP_OFFSET - 8f
    fun visible(top: Float, bottom: Float) = bottom >= HEADER_H && top <= navClear
    val isVisible: (Float, Float) -> Boolean = { top, bottom -> visible(top, bottom) }

    val bodySave = c.save()
    c.clipRect(0f, HEADER_H, w, h)

    var y = 78f + off

    // 1. Open Editorial Store & Owner Real Name Identity Header
    if (visible(y, y + 70f)) {
        val stall = myStallName.takeIf { it.isNotBlank() } ?: "Warung Saya"
        val ownerName = myDisplayName.takeIf { it.isNotBlank() } ?: "Pemilik Warung"
        val avatar = RectF(20f, y, 72f, y + 52f)
        StoreAvatar.draw(this, c, stall, myStallLogoUrl, avatar, cornerRadius = 10f)

        val suspended = myAccountStatus == "suspended"
        val statusText = when {
            suspended -> "Ditangguhkan"
            myStallIsOpen -> "Buka"
            else -> "Tutup"
        }
        val statusBg = when {
            suspended || !myStallIsOpen -> kit.dangerBorder
            else -> kit.successSoft
        }
        val statusFg = when {
            suspended || !myStallIsOpen -> kit.danger
            else -> kit.success
        }
        val statusRect = sellerPill(
            c,
            statusText,
            370f,
            y + 14f,
            statusBg,
            statusFg,
            height = 20f,
            size = 9.5f,
        )
        val isDevSeller = StoreVerifiedBadge.isDevIdentity(
            view = this,
            name = stall,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        val nameWidth = (statusRect.left - 84f - 10f).coerceAtLeast(90f)
        StoreVerifiedBadge.drawInlineWithStoreName(
            view = this,
            c = c,
            storeName = stall,
            isVerified = myStallIsVerified || isDevSeller,
            x = 84f,
            baselineY = y + 19f,
            maxTotalWidth = nameWidth,
            textSize = 16.5f,
            textColor = kit.ink,
            bold = true,
            isDev = isDevSeller,
            nis = myNis,
            stallId = myStallId.orEmpty(),
        )
        val ownerWithNis = buildString {
            append("Pemilik: $ownerName")
            if (myNis.isNotBlank()) append("  ·  NIS: $myNis")
            if (myClass.isNotBlank()) append("  ·  $myClass")
        }
        kit.textEllipsized(
            c,
            ownerWithNis,
            84f,
            y + 37f,
            280f,
            12f,
            kit.blue,
            true,
        )
        val visibleEmail = mySellerVerifiedEmail
            .trim()
            .takeIf { it.isNotBlank() && !it.endsWith("@siswa.jajanin.app", ignoreCase = true) }
            ?: SupabaseRepository.currentVisibleEmail()
        val emailAndPhone = buildList {
            if (visibleEmail != null) add(visibleEmail)
            else if (!SupabaseRepository.hasSession()) add("Belum masuk")
            if (myPhone.isNotBlank()) add(displayLocalPhone(myPhone))
        }.joinToString("  ·  ")
        if (emailAndPhone.isNotBlank()) {
            kit.textEllipsized(c, emailAndPhone, 84f, y + 54f, 284f, 11f, kit.muted)
        }
    }
    y += 72f

    // 2. Compact Store Stats Strip
    val statsStrip = RectF(20f, y, 370f, y + 52f)
    if (visible(statsStrip.top, statsStrip.bottom)) {
        sellerSurface(c, statsStrip)
        val colW = SellerTokens.WIDTH / 3f
        val activeMenus = sellerProducts.count { it.available }

        val cols = listOf(
            Triple("$activeMenus/${sellerProducts.size}", "Menu Aktif", kit.ink),
            Triple(adminOrders.size.toString(), "Order Hari Ini", kit.blue),
            Triple(formatRupiah(sellerLedgerAvailable), "Saldo Midtrans", kit.blue)
        )
        cols.forEachIndexed { idx, (valText, label, colColor) ->
            val left = 20f + idx * colW
            if (idx > 0) kit.line(c, left, y + 8f, left, y + 44f, kit.divider)
            kit.textEllipsized(c, valText, left + 12f, y + 23f, colW - 20f, 13.5f, colColor, true)
            kit.text(c, label, left + 12f, y + 40f, 10f, kit.muted)
        }
        bounds.putVisible(
            "openSellerMenu",
            RectF(20f, y, 20f + colW, y + 52f),
            topLimit = HEADER_H,
            bottomLimit = navClear,
        )
        bounds.putVisible(
            "profileMidtrans",
            RectF(20f + 2f * colW, y, 370f, y + 52f),
            topLimit = HEADER_H,
            bottomLimit = navClear,
        )
    }
    y += 62f

    // 2a. Automatic Store Verification Progress Card (Server-managed 20+ completed orders or Official Dev)
    val isDevSellerBadge = StoreVerifiedBadge.isDevIdentity(
        view = this,
        name = myStallName,
        nis = myNis,
        stallId = myStallId.orEmpty(),
    )
    val verifCard = RectF(20f, y, 370f, y + 56f)
    if (visible(verifCard.top, verifCard.bottom)) {
        sellerSurface(c, verifCard)
        if (myStallIsVerified || isDevSellerBadge) {
            StoreVerifiedBadge.drawPill(
                view = this,
                c = c,
                left = 32f,
                top = y + 10f,
                label = "Terverifikasi Jajanin",
                height = 20f,
                textSize = 9.5f,
                isDev = isDevSellerBadge,
            )
            kit.textEllipsized(
                c,
                "$myStallCompletedOrders pesanan selesai · Lencana tampil di profil & menu",
                32f,
                y + 45f,
                326f,
                10.5f,
                kit.muted,
            )
        } else {
            val done = myStallCompletedOrders.coerceIn(0, 20)
            kit.text(c, "Progres Verifikasi Toko Otomatis", 32f, y + 22f, 12f, kit.ink, true)
            kit.textRight(c, "$done/20 selesai", 358f, y + 22f, 96f, 11f, kit.blue, true)
            val track = RectF(32f, y + 32f, 358f, y + 37f)
            kit.rounded(c, track, kit.divider, 2.5f)
            if (done > 0) {
                val fillW = track.width() * (done / 20f)
                kit.rounded(c, RectF(track.left, track.top, track.left + fillW, track.bottom), kit.blue, 2.5f)
            }
            kit.text(c, "Otomatis aktif saat warung menyelesaikan 20 pesanan.", 32f, y + 49f, 9.5f, kit.muted)
        }
    }
    y += 68f

    // 2b. Switch back to Buyer Mode (1 Akun Dual-Mode)
    val buyerModeCard = RectF(20f, y, 370f, y + 54f)
    if (visible(buyerModeCard.top, buyerModeCard.bottom)) {
        kit.rounded(c, buyerModeCard, kit.tint, 10f, kit.blue)
        kit.rect(c, 20f, y + 10f, 24f, y + 44f, kit.blue)
        kit.textEllipsized(c, "Beralih ke Mode Pembeli", 36f, y + 23f, 240f, 13.5f, kit.blue, true)
        kit.textEllipsized(c, "Belanja & pesan menu dari warung lain", 36f, y + 40f, 240f, 10.5f, kit.muted)
        drawTrailingActionWithArrow(c, "Belanja", 356f, y + 31f, 11.5f, kit.blue)
        bounds.putVisible("switchToBuyer", buyerModeCard, topLimit = HEADER_H, bottomLimit = navClear)
        press(c, "switchToBuyer", buyerModeCard, 10f)
    }
    y += 68f

    // 3. Pengaturan Warung (Identitas siswa dikunci sesuai data sekolah & tampil di header atas)
    if (visible(y, y + 20f)) {
        sellerSection(c, "Pengaturan Warung", y + 12f)
    }
    y += 20f
    val activeMenuCount = sellerProducts.count { it.available }
    y = sellerSettingsGroup(
        c, y,
        listOf(
            SellerSettingRow(
                title = "Lihat Profil Publik Warung",
                key = "profileStorePublic",
                subtitle = "Tampilan etalase publik warungmu seperti yang dilihat pembeli",
                chevron = true,
            ),
            SellerSettingRow(
                title = "Status Operasional Warung",
                key = "profileStoreOpenToggle",
                subtitle = if (myStallIsOpen) "Menerima pesanan pembeli saat ini" else "Warung sedang tutup sementara",
                badge = if (myStallIsOpen) "Buka" else "Tutup",
                badgeColor = if (myStallIsOpen) kit.success else kit.danger,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Menu Dagangan",
                key = "openSellerMenu",
                subtitle = if (sellerProducts.isEmpty()) {
                    "Tambah & kelola daftar dagangan warungmu"
                } else {
                    "$activeMenuCount aktif dari ${sellerProducts.size} dagangan · Kelola stok & PO"
                },
                badge = "${sellerProducts.size} menu",
                badgeColor = kit.blue,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Nama Toko / Warung",
                key = "profileStallName",
                subtitle = myStallName.takeIf { it.isNotBlank() } ?: "Wajib diisi",
                value = "Ubah",
                valueColor = kit.blue,
                chevron = false,
            ),
            SellerSettingRow(
                title = "Kategori Warung",
                key = "profileStoreCategory",
                subtitle = myStallCategory,
                value = "Pilih",
                valueColor = kit.blue,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Deskripsi Publik Warung",
                key = "profileStoreBio",
                subtitle = myStallBio.takeIf { it.isNotBlank() } ?: "Tambahkan deskripsi singkat tentang warungmu",
                value = if (myStallBio.isBlank()) "Isi" else "Ubah",
                valueColor = kit.blue,
                chevron = false,
            ),
            SellerSettingRow(
                title = "Logo / Foto Profil Warung",
                key = "profileStoreLogo",
                subtitle = if (myStallLogoUrl.isBlank()) "Menggunakan inisial default warung" else "Foto profil warung terpasang",
                badge = if (myStallLogoUrl.isBlank()) "Default" else "Terpasang",
                badgeColor = if (myStallLogoUrl.isBlank()) kit.muted else kit.success,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Sampul / Banner Warung",
                key = "profileStoreBanner",
                subtitle = if (myStallBannerUrl.isBlank()) "Menggunakan sampul standar Jajanin" else "Foto sampul warung terpasang",
                badge = if (myStallBannerUrl.isBlank()) "Default" else "Terpasang",
                badgeColor = if (myStallBannerUrl.isBlank()) kit.muted else kit.success,
                chevron = true,
            ),
            SellerSettingRow(
                title = "Nomor Telepon Penjual (Wajib)",
                key = "profilePhone",
                subtitle = myPhone.takeIf { it.isNotBlank() }?.let { "${displayLocalPhone(it)} · Tampil di pesanan pembeli" }
                    ?: "Belum diisi · wajib untuk koordinasi ambil pesanan",
                value = if (myPhone.isBlank()) "Isi" else "Ubah",
                valueColor = if (myPhone.isBlank()) kit.danger else kit.blue,
                chevron = false,
            ),
            SellerSettingRow(
                title = "Pembayaran Midtrans",
                key = "profileMidtrans",
                subtitle = "Otomatis lunas & masuk saldo · $sellerLedgerTxCount transaksi",
                badge = "Aktif",
                badgeColor = kit.success,
                chevron = true,
            ),
        ),
        isVisible,
        navClear,
    )
    y += SellerTokens.SECTION_GAP

    // 4. Akun & Aplikasi
    if (visible(y, y + 20f)) {
        sellerSection(c, "Akun & Aplikasi", y + 12f)
    }
    y += 20f
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
                title = "Tema Aplikasi",
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

    // 5. Bantuan & Informasi
    if (visible(y, y + 20f)) {
        sellerSection(c, "Bantuan & Informasi", y + 12f)
    }
    y += 20f
    y = sellerSettingsGroup(
        c, y,
        listOf(
            SellerSettingRow("Bantuan", "help", subtitle = "Cara berjualan di Jajanin"),
            SellerSettingRow("Tentang Jajanin", "about", subtitle = "Versi dan informasi aplikasi"),
            SellerSettingRow("Informasi Penting", "infoPage", subtitle = "Ketentuan dan panduan mitra"),
            SellerSettingRow(
                title = "Cek Update (OTA)",
                key = "checkOta",
                subtitle = if (otaUpdateAvailable) {
                    otaLatestChangelog?.replace('\n', ' ') ?: "Pembaruan siap dipasang langsung dari HP"
                } else {
                    "Periksa pembaruan langsung tanpa hapus aplikasi"
                },
                value = if (otaUpdateAvailable && !otaLatestVersion.isNullOrBlank()) {
                    "Update $otaLatestVersion"
                } else {
                    "v${BuildConfig.VERSION_NAME}"
                },
                valueColor = if (otaUpdateAvailable) kit.blue else kit.muted,
                chevron = true,
            ),
        ),
        isVisible,
        navClear,
    )
    y += SellerTokens.SECTION_GAP

    val logout = RectF(SellerTokens.PAD, y, SellerTokens.RIGHT, y + 48f)
    if (visible(logout.top, logout.bottom)) {
        kit.rounded(c, logout, kit.card, SellerTokens.RADIUS_SURFACE, kit.dangerBorder)
        kit.centerText(c, "Keluar dari akun", logout, 13.5f, kit.danger, true)
        press(c, "logout", logout, SellerTokens.RADIUS_SURFACE)
    }
    bounds.putVisible("logout", logout, topLimit = HEADER_H, bottomLimit = navClear)

    val deleteRow = RectF(SellerTokens.PAD, logout.bottom + 10f, SellerTokens.RIGHT, logout.bottom + 54f)
    if (visible(deleteRow.top, deleteRow.bottom)) {
        kit.rounded(c, deleteRow, kit.bg, SellerTokens.RADIUS_SURFACE, kit.dangerBorder)
        kit.centerText(c, "Hapus Akun Penjual / Akun", deleteRow, 12.5f, kit.danger, true)
        press(c, "deleteSellerAccount", deleteRow, SellerTokens.RADIUS_SURFACE)
    }
    bounds.putVisible("deleteSellerAccount", deleteRow, topLimit = HEADER_H, bottomLimit = navClear)

    c.restoreToCount(bodySave)
    contentHeight = deleteRow.bottom - off + GLASS_TOP_OFFSET + 20f

    sellerHeader(c, "Profil Warung")
    drawAdminNav(c, w, h, "Profil")
}

private fun displayLocalPhone(raw: String): String =
    if (raw.startsWith("+62")) "0" + raw.removePrefix("+62") else raw
