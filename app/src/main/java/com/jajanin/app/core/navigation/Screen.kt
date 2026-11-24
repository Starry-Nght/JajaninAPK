package com.jajanin.app.core.navigation

/**
 * Semua rute layar aplikasi, diurutkan per peran agar mudah dicari:
 * fondasi -> pembeli -> penjual -> developer -> halaman informasi.
 */
internal enum class Screen {
    // Fondasi: splash, onboarding, autentikasi (tanpa layar pilih role)
    SPLASH, ONBOARD, WELCOME, LOGIN,

    // Pembeli (buyer): katalog, pesanan, profil
    HOME, EXPLORE, DETAIL, CHECKOUT, QRIS,
    READY, ORDERS, PROFILE, EXP_HISTORY,

    // Penjual (seller): dashboard, detail pesanan, form produk, menu dagangan
    ADMIN, ADMIN_DETAIL, PRODUCT_FORM, SELLER_MENU,

    // Developer / super admin
    DEV,

    // Notifikasi, chat & halaman informasi (dipakai semua peran)
    CHAT_LIST, NOTIF, HELP, ABOUT, INFO
}
