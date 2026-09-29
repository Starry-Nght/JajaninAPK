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
The app now includes Supabase email/password auth, Google OAuth launch/callback handling, and a starter Postgres schema. To connect it to your Supabase project:

1. In Supabase, open **Project Settings → API** and copy the Project URL.
2. Add these values to `~/.gradle/gradle.properties` (the publishable key is already the project default and can be overridden):

   ```properties
   SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_vj7akRru3TvuUmkugtyRBg_0oRAQck6
   ```

3. Run `supabase/migrations/202609290001_initial_schema.sql` in the Supabase SQL Editor.
4. In **Authentication → URL Configuration**, add `jajanin://auth-callback` to the allowed redirect URLs.
5. For Google sign-in, enable Google under **Authentication → Providers → Google** and add the OAuth client credentials from Google Cloud. The callback URL shown by Supabase must also be allowed in the Google OAuth client.
6. Rebuild and install the app. Email/password sign-in and sign-up are available from the login screen; email confirmation follows your Supabase Auth setting.

The Android client uses only a publishable key. Never place a secret or service-role key in the app. Menu rows can be read from Supabase after the migration; the UI keeps sample menu items as a fallback until project data is added.
