package com.jajanin.app

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseRepository {
    private val projectUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val configured get() = projectUrl.matches(Regex("https://[a-zA-Z0-9-]+\\.supabase\\.co")) &&
        BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

    val client by lazy {
        check(configured) { "Supabase is not configured. Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in ~/.gradle/gradle.properties, then rebuild the app." }
        createSupabaseClient(projectUrl, BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()) {
            install(Auth) {
                scheme = "jajanin"
                host = "auth-callback"
            }
            install(Postgrest)
        }
    }

    fun isConfigured() = configured

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) { this.email = email; this.password = password }
    }
    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email, redirectUrl = "jajanin://auth-callback") { this.email = email; this.password = password }
    }
    suspend fun signInWithGoogle() {
        client.auth.signInWith(Google, redirectUrl = "jajanin://auth-callback")
    }
    fun hasSession() = configured && client.auth.currentSessionOrNull() != null
    suspend fun signOut() { if (configured) client.auth.signOut() }
}
