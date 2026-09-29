# Jajanin Android prototype

Prototype Android Studio project based on the supplied Jajanin Figma exports.

## Flow implemented
Welcome → Pilih Role → Home → Detail Menu → Checkout → QRIS → Ready for Pickup → Orders

The prototype uses the supplied Figma screen exports as the visual layer and adds real Android touch navigation. It is intentionally dependency-light so it can be opened in Android Studio before the Supabase backend is added.

## Open
1. Open this folder in Android Studio.
2. Let Gradle sync and install an Android SDK platform 35 if Android Studio asks.
3. Run the `app` configuration on an emulator or phone.

## Next backend phase
Replace the screenshot-backed prototype screens with native Compose UI and connect Supabase Auth + PostgreSQL tables for users, stalls, menu items, carts, orders, and order status.
