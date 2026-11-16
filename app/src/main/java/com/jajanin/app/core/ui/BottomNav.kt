package com.jajanin.app.core.ui

import android.graphics.Canvas
import com.jajanin.app.JajaninView

/*
 * Kontrak navigasi bawah.
 *
 * Kanvas JajaninView tidak lagi menggambar bar sendiri: setiap layar tetap
 * memanggil drawBottomNav/drawAdminNav seperti sebelumnya, tetapi panggilan
 * itu sekarang hanya menerbitkan [NavSpec] yang dipakai host (MainActivity)
 * untuk menyusun JajaninGlassTabBar di atas pipeline lensa QWEA0/Liquid-Glass-Android.
 *
 * Dengan cara ini visibilitas, urutan tab, label, ikon, dan tujuan navigasi
 * tetap persis seperti kontrak lama — termasuk layar yang memang tidak punya
 * nav (detail, pembayaran, dsb.) karena mereka tidak memanggil fungsi ini.
 */

// Geometri nav melayang (dp desain, kanvas 390 lebar). Konten memakai
// GLASS_TOP_OFFSET sebagai ruang bawah; host memakai nilai yang sama untuk
// memosisikan dock kaca.
internal const val GLASS_MARGIN = 16f
internal const val GLASS_BOTTOM = 14f
internal const val GLASS_H = 68f
internal const val GLASS_R = 24f
/** Batas atas nav melayang dari bawah layar; konten scroll memakai ini. */
internal const val GLASS_TOP_OFFSET = GLASS_BOTTOM + GLASS_H // 82f

// Geometri dock penjual. Tinggi host menampung dock + plus yang melayang di
// atasnya; konten penjual memakai HERO_TOP_OFFSET agar tidak tertutup.
internal const val HERO_HEIGHT = 100f
internal const val HERO_BOTTOM = 12f
internal const val HERO_DOCK_H = 60f
internal const val HERO_DOCK_H_EXPANDED = 66f
internal const val HERO_DOCK_MARGIN = 16f
internal const val HERO_DOCK_MARGIN_EXPANDED = 9f
internal const val HERO_PLUS_D = 54f
internal const val HERO_PLUS_D_EXPANDED = 60f
internal const val HERO_TOP_OFFSET = HERO_BOTTOM + HERO_HEIGHT // 112f

internal enum class NavVariant { STANDARD, HERO }

internal data class FloatNavItem(
    val label: String,
    val icon: String,
    val tabIcon: Boolean,
)

/**
 * Spesifikasi dock yang sedang aktif pada frame ini.
 *
 * [prefix] dipertahankan ("tab:" pembeli / "stab:" penjual) agar callback
 * JajaninGlassTabBar bisa diarahkan ke jalur navigasi lama tanpa mengubah
 * perilaku. [badgeIndex] >= 0 menandai tab yang perlu lencana jumlah.
 */
internal data class NavSpec(
    val prefix: String,
    val items: List<FloatNavItem>,
    val active: String,
    val badgeIndex: Int = -1,
    val badgeCount: Int = 0,
    val variant: NavVariant = NavVariant.STANDARD,
)

/**
 * Bar kaca melayang generik. [prefix] = "tab:" (buyer) atau "stab:" (seller).
 *
 * Tidak ada lagi penggambaran kanvas di sini — ini hanya menerbitkan state
 * yang dibaca [JajaninView.onDraw] dan diteruskan ke host JajaninGlassTabBar.
 */
internal fun JajaninView.drawFloatingNav(
    c: Canvas,
    w: Float,
    h: Float,
    items: List<FloatNavItem>,
    active: String,
    prefix: String,
    variant: NavVariant = NavVariant.STANDARD,
) {
    navSpec = NavSpec(prefix, items, active, variant = variant)
}

internal fun JajaninView.drawBottomNav(c: Canvas, w: Float, h: Float, active: String) {
    drawFloatingNav(
        c, w, h,
        listOf(
            FloatNavItem("Home", "home", true),
            FloatNavItem("Explore", "explore", true),
            FloatNavItem("Orders", "orders", true),
            FloatNavItem("Profile", "profile", true),
        ),
        active, "tab:",
    )
}

/** Lencana jumlah pada tab indeks [index] (dipakai nav penjual). */
internal fun JajaninView.navBadge(index: Int, count: Int) {
    navBadgeIndex = index
    navBadgeCount = count
}
