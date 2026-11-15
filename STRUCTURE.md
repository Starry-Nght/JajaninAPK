# Struktur Proyek Jajanin

Peta folder dan file agar cepat menemukan kode yang mau diubah. Urutan baca
disarankan: **fondasi -> pembeli -> penjual -> developer -> notifikasi**.

```text
app/src/main/java/com/jajanin/app/
├── MainActivity.kt            # Host activity: lifecycle, sesi, auth, tema, dialog dasar
├── JajaninView.kt             # Renderer canvas + dispatcher layar & sentuhan (onDraw/onTouch)
│
├── core/                      # Fondasi lintas fitur
│   ├── constants/             # Status pesanan, fee, lokasi
│   ├── navigation/Screen.kt   # Enum rute (dikelompokkan per peran)
│   ├── ui/                    # DrawKit, Icons, BottomNav, dialogs, helper UI
│   └── util/                  # Format Rupiah, label status
│
├── data/                      # Model & akses backend (Supabase)
│   ├── model/                 # ProductRow, MenuItem
│   └── repository/            # Supabase, Order, Developer, Phone
│
├── notifications/             # FCM service + NotificationHelper
│
└── feature/
    ├── auth/                  # Welcome, pilih role, login
    ├── onboarding/            # Splash + walkthrough
    │
    ├── buyer/                 # ===== PEMBELI =====
    │   ├── BuyerActions.kt        # Aksi Activity pembeli (katalog, keranjang, checkout, EXP, profil)
    │   ├── home/                  # HomeScreen
    │   ├── explore/               # ExploreScreen
    │   ├── cart/                  # Detail, cart, checkout, QRIS
    │   ├── orders/                # Status & riwayat pesanan
    │   ├── profile/               # Profil pembeli
    │   ├── exp/                   # Sistem level/EXP
    │   └── notifications/         # NotifScreen
    │
    ├── seller/                # ===== PENJUAL =====
    │   ├── SellerActions.kt       # Aksi Activity penjual (produk, QRIS warung, dashboard, verifikasi pesanan)
    │   ├── dashboard/             # SellerDashboardScreen
    │   ├── orders/                # SellerOrderScreens
    │   └── menu/                  # ProductFormScreen
    │
    ├── developer/             # ===== DEVELOPER =====
    │   ├── DeveloperActions.kt    # Aksi Activity developer (telemetri, warning, announcement, reset)
    │   └── DeveloperScreens.kt    # DeveloperScreens
    │
    └── inbox/                 # Notifikasi/inbox bersama semua peran
        └── InboxActions.kt
```

## Mau ubah apa? Cek di sini

| Kebutuhan | File utama |
| --- | --- |
| Alur awal / sesi / login | `MainActivity.kt`, `feature/auth/`, `feature/onboarding/` |
| Tambah/ubah layar & sentuhan | `JajaninView.kt`, `core/navigation/Screen.kt` |
| UI pembeli (home, explore, cart, order, profil, EXP) | `feature/buyer/` |
| UI penjual (dashboard, pesanan, form produk) | `feature/seller/` |
| Konsol developer/super admin | `feature/developer/` |
| Aksi/logika yang dipanggil dari UI | `feature/*/*Actions.kt` |
| Query & realtime Supabase | `data/repository/` |
| Warna, ikon, dialog, bottom nav | `core/ui/` |
| Status pesanan, fee, format angka | `core/constants/`, `core/util/` |

## Catatan penting

- Nama package Kotlin/Java **tidak boleh diawali angka**, jadi folder tidak
  bisa dinamai `1-buyer`, `2-seller`, dst. Pemisahan peran dijaga lewat nama
  folder `buyer`, `seller`, `developer` dan file `*Actions.kt` di masing-masing.
- Logika Activity dipecah per peran menjadi extension function
  (`MainActivity.refreshMyOrders()`, dst) agar `MainActivity.kt` tetap ringkas.
- Jangan mengubah kontrak tabel/kolom/RPC Supabase saat merapikan UI.
