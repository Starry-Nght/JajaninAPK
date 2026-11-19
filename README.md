# Jajanin

Android canteen ordering application built with Kotlin, a custom hardware-accelerated Canvas renderer (`JajaninView`), native Liquid Glass bottom navigation, Supabase backend (Auth, PostgREST, Realtime, Edge Functions), and Firebase Cloud Messaging.

---

## Features by Role

### Buyer
- **Catalog & Discovery**: Search menus, filter by category, view trending items (*Paling Laris*), save favorites, and pick delivery/pickup room locations.
- **Cart & Checkout**: Multi-item cart with server-verified pricing, Cash (*Tunai*) or *QRIS* payment methods (checks whether the stall has configured a QRIS code and supports payment proof submission), and daily order quota tracking.
- **Orders & Pickup**: Live order tracking with pickup codes, status timeline, and notification alerts.
- **Buyer EXP & Profile**: Level/EXP progression system with history, theme toggle (Light / Dark), phone/account details, and forgot-password recovery flow.

### Seller
- **Dashboard & Order Queue**: Live incoming order stats, status pipeline filters, payment verification, and order detail actions.
- **Product Management**: Create, edit, and delete stall products with stock, description, ingredients, and product image uploads.
- **Stall Settings**: Configure stall name, room, and stall QRIS image.
- **Notifications**: Actionable order & verification counters with quick notification panel.

### Developer
- **Admin Console**: Multi-tab console (`Overview`, `Users`, `Sellers`, `Orders`, `Warn`, `Info`) for platform metrics, user/seller moderation and invitations, order inspection, user warnings, announcements, and daily quota resets.

### Navigation & UI
- **Floating Liquid Glass Dock**: Powered by `com.github.QWEA0:liquidglass:v2.0.11` with a spring-animated sliding oval tab indicator and drag-to-switch gestures ([LiquidGlassNavHost.kt](app/src/main/java/com/jajanin/app/core/ui/LiquidGlassNavHost.kt), [JajaninGlassTabBar.kt](app/src/main/java/com/jajanin/app/core/ui/JajaninGlassTabBar.kt)).
- **Interactive Page Swiping**: Horizontal finger swipe across docked role tabs and onboarding screens alongside vertical scrolling ([JajaninView.kt](app/src/main/java/com/jajanin/app/JajaninView.kt)).

---

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language & Build | Kotlin `2.0.21`, AGP `8.7.3`, Gradle `9.3.0`, Java 17 |
| Android SDK | `minSdk 24`, `compileSdk 35`, `targetSdk 35` |
| UI Architecture | Custom Canvas (`JajaninView`) + Native `LiquidGlassView` overlay |
| Backend | Supabase Kotlin BOM `2.6.0` (`gotrue-kt`, `postgrest-kt`, `realtime-kt`), Ktor Android `2.3.12` |
| Push Notifications | Firebase BOM `33.7.0` (`firebase-messaging`), channel `jajanin_orders` |
| Auth & Deep Links | Email/password sign-in/sign-up, password recovery, Google OAuth (`jajanin://auth-callback`) |

---

## Project Structure

See [STRUCTURE.md](STRUCTURE.md) for the complete module map. High-level layout:

```text
JajaninApp/
├── app/src/main/java/com/jajanin/app/
│   ├── MainActivity.kt            # Activity host, session restore, auth dialogs, deep links
│   ├── JajaninView.kt             # Custom Canvas renderer, swipe/scroll gestures & routing
│   ├── core/                      # Constants, Screen enum, DrawKit, icons, nav dock, helpers
│   ├── data/                      # Models & Supabase repositories (Order, Developer, Phone, Supabase)
│   ├── notifications/             # JajaninFcmService & NotificationHelper
│   └── feature/
│       ├── auth/                  # Welcome, role selection, login screens
│       ├── onboarding/            # Splash & walkthrough slides
│       ├── buyer/                 # Home, explore, cart/checkout/QRIS, orders, profile, EXP, notif
│       ├── seller/                # Dashboard, order details, product form, notif, stall profile
│       ├── developer/             # Overview, users, sellers, orders, warnings, announcements
│       └── inbox/                 # Shared inbox/notification handlers
└── supabase/
    ├── migrations/                # Chronological Postgres schema, RLS, limits, EXP & RPCs
    ├── functions/notify-order/    # Edge Function for order push notifications
    └── tests/                     # SQL & shell tests for ordering limits & seller overload
```

---

## Getting Started

### Prerequisites
- Android Studio (with Android SDK Platform 35)
- JDK 17

### 1. Configure Supabase
1. Create a Supabase project and open **Project Settings -> API**.
2. Optionally override the Gradle properties in `~/.gradle/gradle.properties` (defaults exist in [app/build.gradle.kts](app/build.gradle.kts)):

   ```properties
   SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
   SUPABASE_PUBLISHABLE_KEY=YOUR_SUPABASE_PUBLISHABLE_KEY
   ```

   > Keep only the publishable key in Android `BuildConfig`. Never embed service-role or secret keys in the client.

3. Apply the SQL migrations in `supabase/migrations/` in chronological order (`202609290001_initial_schema.sql` through `202611150001_order_buyer_phone.sql`).
4. Under **Authentication -> URL Configuration**, add `jajanin://auth-callback` to your allowed redirect URLs.
5. *(Optional)* Enable Google OAuth under **Authentication -> Providers -> Google** and deploy `supabase/functions/notify-order/index.ts`.

### 2. Configure Firebase Cloud Messaging (Optional)
- Place your `google-services.json` inside `app/` if you want FCM push notifications enabled.
- [app/build.gradle.kts](app/build.gradle.kts) automatically detects `app/google-services.json` and applies `com.google.gms.google-services` only when present, so the app still builds cleanly without it.

### 3. Build & Run

```bash
./gradlew assembleDebug
```

Or open the repository in Android Studio, let Gradle sync, and run the `app` configuration on an emulator or physical Android device (Android 7.0+ / API 24+).
