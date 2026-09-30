package com.jajanin.app

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.exception.AuthRestException
import io.github.jan.supabase.gotrue.exception.AuthWeakPasswordException
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.Realtime
import kotlinx.serialization.Serializable
import java.io.IOException

/** Hasil dari [SupabaseRepository.register]. */
enum class RegisterResult {
    /** Supabase Auth langsung mengembalikan session, sehingga user langsung masuk. */
    LOGGED_IN,

    /** Akun berhasil dibuat, tapi Supabase masih meminta konfirmasi email. */
    CONFIRMATION_REQUIRED,
}

/**
 * Satu-satunya tempat aplikasi berbicara dengan Supabase Auth.
 *
 * Kredensial hanya diproses oleh Supabase Auth (GoTrue): kata sandi di-hash di
 * server, tidak pernah ditulis ke tabel mana pun, tidak pernah di-log, dan tidak
 * pernah disimpan oleh kelas ini. Tabel `profiles` hanya menyimpan id, email dan
 * display_name yang dibuat oleh trigger, tanpa kolom kata sandi.
 */
object SupabaseRepository {
    private const val AUTH_REDIRECT_URL = "jajanin://auth-callback"
    private const val NOT_CONFIGURED =
        "Supabase belum dikonfigurasi. Tambahkan SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY di ~/.gradle/gradle.properties, lalu build ulang."

    private val projectUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val configured
        get() = projectUrl.matches(Regex("https://[a-zA-Z0-9-]+\\.supabase\\.co")) &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

    val client by lazy {
        check(configured) { NOT_CONFIGURED }
        createSupabaseClient(projectUrl, BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()) {
            install(Auth) {
                scheme = "jajanin"
                host = "auth-callback"
            }
            install(Postgrest)
            install(Realtime)
        }
    }

    fun isConfigured() = configured

    /**
     * Menunggu Auth selesai memulihkan session dari penyimpanan, lalu melaporkan
     * apakah user sudah login.
     *
     * Wajib dipakai saat aplikasi dibuka: tanpa [Auth.awaitInitialization] status
     * masih `LoadingFromStorage` sehingga [hasSession] bisa salah mengembalikan
     * false walau session valid tersimpan.
     */
    suspend fun awaitLoggedIn(): Boolean {
        if (!configured) return false
        client.auth.awaitInitialization()
        return client.auth.currentSessionOrNull() != null
    }

    fun hasSession() = configured && client.auth.currentSessionOrNull() != null

    fun currentEmail(): String? = if (configured) client.auth.currentUserOrNull()?.email else null

    fun currentUid(): String? = if (configured) client.auth.currentUserOrNull()?.id else null

    @Serializable
    private data class RoleRow(val role: String)

    /**
     * Membaca role pemilik session dari tabel profiles (baris milik sendiri).
     *
     * Dipakai untuk memutuskan rute setelah login/restore: role 'seller' = admin.
     * Mengembalikan null bila session belum siap atau query gagal.
     */
    suspend fun currentRole(): String? {
        val uid = currentUid() ?: return null
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("role")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<RoleRow>()?.role
        }.getOrNull()
    }

    /**
     * Mendaftarkan akun baru lewat Supabase Auth.
     *
     * Tidak ada OTP dan tidak ada tabel password sendiri; kata sandi hanya dikirim
     * ke endpoint Supabase Auth. Bila "Confirm email" sudah dimatikan di dashboard,
     * Supabase langsung mengembalikan session sehingga user masuk tanpa langkah
     * tambahan.
     */
    suspend fun register(email: String, password: String): RegisterResult {
        check(configured) { NOT_CONFIGURED }
        client.auth.signUpWith(Email, redirectUrl = AUTH_REDIRECT_URL) {
            this.email = email
            this.password = password
        }
        return if (client.auth.currentSessionOrNull() != null) {
            RegisterResult.LOGGED_IN
        } else {
            RegisterResult.CONFIRMATION_REQUIRED
        }
    }

    suspend fun login(email: String, password: String) {
        check(configured) { NOT_CONFIGURED }
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /**
     * Mengakhiri sesi. Memakai scope LOCAL sehingga session langsung dibersihkan
     * di perangkat walau sedang offline.
     */
    suspend fun logout() {
        if (!configured) return
        client.auth.signOut()
    }

    /**
     * Mengubah kegagalan Supabase Auth menjadi pesan yang aman dan mudah dipahami.
     *
     * Pemetaan memakai string mentah karena enum [io.github.jan.supabase.gotrue.exception.AuthErrorCode]
     * pada supabase-kt 2.6.0 belum memuat kode `invalid_credentials`, dan versi
     * GoTrue lama mengembalikan `invalid_grant` tanpa `error_code` sama sekali.
     */
    fun errorMessage(throwable: Throwable): String {
        val rest = throwable as? RestException
        val haystack = listOfNotNull(rest?.error, rest?.description, rest?.message, throwable.message)
            .joinToString(" ")
            .lowercase()
        fun has(vararg needles: String) = needles.any { haystack.contains(it) }

        return when {
            throwable is AuthWeakPasswordException ||
                has("weak_password", "password should be", "password must be") ->
                "Kata sandi terlalu lemah. Gunakan minimal 6 karakter."

            has("user_already_exists", "email_exists", "already registered", "already been registered") ->
                "Email sudah terdaftar. Silakan masuk dengan email tersebut."

            has("invalid_credentials", "invalid_grant", "invalid login credentials") ->
                "Email atau kata sandi salah."

            has("user_not_found") ->
                "Email belum terdaftar. Silakan daftar terlebih dahulu."

            has("email_not_confirmed") ->
                "Email belum dikonfirmasi. Matikan Confirm email di Supabase Dashboard agar bisa langsung masuk."

            has("rate_limit", "too many request", "over email send") ->
                "Terlalu banyak percobaan. Tunggu sebentar lalu coba lagi."

            throwable is HttpRequestException || throwable is IOException ||
                has("unable to resolve host", "failed to connect", "connection reset", "connection refused", "timed out") ->
                "Tidak bisa terhubung ke server. Periksa koneksi internet kamu."

            else -> detailOf(rest, throwable)
                ?: "Terjadi kesalahan. Silakan coba lagi."
        }
    }

    /** Detail teknis yang masih aman ditampilkan, tanpa membocorkan URL/header. */
    private fun detailOf(rest: RestException?, throwable: Throwable): String? {
        val candidate = if (rest is AuthRestException) rest.message else rest?.description
        return (candidate ?: throwable.message)
            ?.takeIf { it.isNotBlank() && !it.contains("URL:") }
    }
}
