# AGENTS.md - Jajanin Android Architecture & Organization Guide

## Overview
Jajanin is an Android application built for school/campus canteen ordering with three primary user roles: **Buyer**, **Seller**, and **Developer/Super Admin**.

The codebase follows a modular, feature-based package structure under `app/src/main/java/com/jajanin/app/` with clear separation of concerns across core capabilities, data repositories, notifications, and role-based feature modules.

---

## Final Package & Directory Structure

```text
app/src/main/java/com/jajanin/app/
├── MainActivity.kt                      # Root single-activity host & lifecycle controller
├── JajaninView.kt                       # Canvas UI rendering engine & screen dispatcher
│
├── core/                                # Shared framework & cross-cutting foundations
│   ├── navigation/
│   │   └── Screen.kt                    # Global navigation route enum (SPLASH, HOME, DEV, etc.)
│   ├── constants/
│   │   └── OrderConstants.kt            # Order lifecycle statuses, fees, and location options
│   ├── util/
│   │   └── FormatUtils.kt               # Rupiah formatting and status label helpers
│   └── ui/
│       ├── DrawKit.kt                   # Low-level 2D canvas drawing kit, colors, and image cache
│       ├── Icons.kt                     # Vector icon path definitions
│       ├── BottomNav.kt                 # Floating glassmorphism bottom navigation bar
│       ├── OrderUiHelpers.kt            # Shared UI status pills, badges, QR, and touch helpers
│       ├── ThemedDialogs.kt             # Brand-styled custom dialogs (Info, Options, Confirm)
│       └── InfoScreens.kt               # Static informational screens (Help, About, Info)
│
├── data/                                # Data persistence, remote backend, and models
│   ├── model/
│   │   └── ProductModel.kt              # Core product & menu item models (ProductRow, MenuItem)
│   └── repository/
│       ├── SupabaseRepository.kt        # Supabase client, auth (GoTrue), session management
│       ├── OrderRepository.kt           # Order queries, realtime channels, mutations, and models
│       ├── DeveloperRepository.kt       # Super-admin metrics, user moderation, audit logs
│       └── PhoneRepository.kt           # User phone number normalizer and RPC queries
│
├── notifications/                       # Push notification infrastructure
│   ├── NotificationHelper.kt            # Android NotificationChannel & system notifications
│   └── JajaninFcmService.kt             # Firebase Cloud Messaging service for incoming orders
│
└── feature/                             # Role-based & lifecycle feature modules
    ├── auth/
    │   └── AuthScreens.kt               # Welcome, Role selection, and Login canvas screens
    ├── onboarding/
    │   └── OnboardingScreens.kt         # Splash screen and first-time onboarding walkthrough
    ├── buyer/                           # Buyer role experience
    │   ├── BuyerActions.kt              # Buyer Activity actions (catalog, cart, checkout, EXP, profile)
    │   ├── home/
    │   │   └── HomeScreen.kt            # Buyer catalog home, categories, and stall banner
    │   ├── explore/
    │   │   └── ExploreScreen.kt         # Menu search, category filters, and grid browsing
    │   ├── cart/
    │   │   └── BuyerCartScreens.kt      # Item detail, cart summary, and checkout / QRIS payment
    │   ├── orders/
    │   │   └── BuyerOrderScreens.kt     # Active order status, pickup code, and order history
    │   ├── profile/
    │   │   └── BuyerProfileScreen.kt    # Buyer account profile and settings
    │   ├── exp/
    │   │   ├── ExpSystem.kt             # Buyer level thresholds, calculation logic, and ledger repo
    │   │   └── ExpScreens.kt            # EXP transaction history and tier milestones
    │   └── notifications/
    │       └── NotifScreen.kt           # Inbox & alerts screen for buyers, sellers, and admins
    ├── seller/                          # Seller (canteen partner) role experience
    │   ├── SellerActions.kt             # Seller Activity actions (products, stall QRIS, dashboard, payment verification)
    │   ├── dashboard/
    │   │   └── SellerDashboardScreen.kt # Live order kanban, daily sales revenue, and navigation
    │   ├── orders/
    │   │   └── SellerOrderScreens.kt    # Seller order inspection, progress workflow, and payment verification
    │   └── menu/
    │       └── ProductFormScreen.kt     # Menu item creation and modification form
    ├── developer/                       # Super admin / DevOps console
    │   ├── DeveloperActions.kt          # Developer Activity actions (telemetry, warnings, announcements, reset)
    │   └── DeveloperScreens.kt          # Realtime telemetry, user management, order inspection, and moderation
    └── inbox/                           # Shared notification/inbox experience (all roles)
        └── InboxActions.kt              # Inbox, invites, warnings, order-notification handling
```

---

## Architectural Rules & Guidelines

1. **Role-Based Placement**:
   - Always put new Buyer features under `feature/buyer/<subfeature>/`.
   - Always put new Seller features under `feature/seller/<subfeature>/`.
   - Always put new Developer/Admin features under `feature/developer/`.
   - Keep authentication and role selection under `feature/auth/`.
   - Keep splash and onboarding walkthroughs under `feature/onboarding/`.

2. **Shared UI & Primitives**:
   - Shared UI widgets, drawing utilities, navigation bars, and dialogs must reside under `core/ui/`.
   - Universal constants (such as order statuses and fees) belong in `core/constants/`.
   - Formatting and pure utility functions belong in `core/util/`.

3. **Backend & Repositories**:
   - Supabase PostgREST, Auth, and Realtime communication must go through repositories in `data/repository/`.
   - Do NOT duplicate backend queries or database access inside individual screen renderers.
   - Database table contracts, column names, and RPC schemas must never be modified during UI refactoring.

4. **Notifications**:
   - Push notification handlers and FCM messaging services belong strictly under `notifications/`.

5. **Discipline & Verification**:
   - Inspect the existing package structure before creating new files.
   - Always update package declarations and imports when moving files.
   - Verify every navigation route in `Screen.kt` maps to its destination.
   - After any refactoring, run `./gradlew assembleDebug` to verify that the build packages cleanly without warnings or errors.
