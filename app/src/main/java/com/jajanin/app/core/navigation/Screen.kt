package com.jajanin.app.core.navigation

/**
 * Semua rute layar aplikasi, diurutkan per peran agar mudah dicari:
 * fondasi -> pembeli -> penjual -> developer -> halaman informasi.
 */
internal enum class Screen {
    // Fondasi: splash, onboarding, autentikasi
    SPLASH, ONBOARD, WELCOME, ROLE, LOGIN,

    // Pembeli (buyer): katalog, pesanan, profil
    HOME, EXPLORE, DETAIL, CHECKOUT, QRIS,
    READY, ORDERS, PROFILE, EXP_HISTORY,

    // Penjual (seller): dashboard, detail pesanan, form produk
    ADMIN, ADMIN_DETAIL, PRODUCT_FORM,

    // Developer / super admin
    DEV,

    // Notifikasi & halaman informasi (dipakai semua peran)
    NOTIF, HELP, ABOUT, INFO
}
