package com.jajanin.app.data.repository

import com.jajanin.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.exception.AuthRestException
import io.github.jan.supabase.gotrue.exception.AuthWeakPasswordException
import io.github.jan.supabase.gotrue.handleDeeplinks
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.Realtime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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

    /** Base URL + publishable key untuk panggilan HTTP mentah (mis. upload Storage). */
    val baseUrl: String get() = projectUrl
    val publishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    /** JWT sesi aktif untuk header Authorization panggilan HTTP mentah. */
    fun currentAccessToken(): String? =
        if (configured) client.auth.currentSessionOrNull()?.accessToken else null

    /**
     * Menunggu Auth selesai memulihkan session dari penyimpanan, lalu melaporkan
     * apakah user sudah login.
     *
     * Wajib dipakai saat aplikasi dibuka: tanpa [Auth.awaitInitialization] status
     * masih `LoadingFromStorage` sehingga [hasSession] bisa salah mengembalikan
     * false walau session valid tersimpan.
     */
    /**
     * Memulihkan session yang tersimpan (persistent login).
     *
     * Dipakai sekali saat aplikasi dibuka: menunggu Auth selesai memuat
     * penyimpanan, lalu mencoba refresh token bila access token kedaluwarsa.
     * True = ada session valid → langsung masuk dashboard tanpa login ulang.
     * Tidak pernah menyimpan kata sandi; sepenuhnya milik Supabase Auth.
     */
    suspend fun restoreSession(): Boolean {
        if (!configured) return false
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() != null) return true
        return runCatching {
            client.auth.refreshCurrentSession()
            client.auth.currentSessionOrNull() != null
        }.getOrDefault(false)
    }

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

    @Serializable
    private data class NameRow(@SerialName("display_name") val displayName: String? = null)

    @Serializable
    private data class StatusRow(
        val role: String = "buyer",
        @SerialName("account_status") val accountStatus: String = "active",
    )

    /** Status akun pemilik session (dipakai banner penangguhan). Null bila belum login. */
    suspend fun myAccountStatus(): String? {
        val uid = currentUid() ?: return null
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("role", "account_status")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<StatusRow>()?.accountStatus
        }.getOrNull()
    }

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

    /** True bila akun ini jadi seller lewat undangan developer (bebas kode akses). */
    suspend fun isSellerInvited(): Boolean {
        val uid = currentUid() ?: return false
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("seller_invited")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<SellerInvitedRow>()?.invited == true
        }.getOrDefault(false)
    }

    @Serializable
    private data class SellerInvitedRow(@SerialName("seller_invited") val invited: Boolean = false)

    /** Nama asli + tag NIS mentah pemilik session dari tabel profiles (null/blank bila belum diisi). */
    suspend fun myRawDisplayName(): String? {
        val uid = currentUid() ?: return null
        return runCatching {
            client.postgrest.from("profiles")
                .select(Columns.list("display_name")) {
                    filter { eq("id", uid) }
                    limit(1)
                }
                .decodeSingleOrNull<NameRow>()?.displayName
        }.getOrNull()
    }

    /** Nama asli bersih pemilik session (tanpa tag [NIS:...]). */
    suspend fun myDisplayName(): String? =
        myRawDisplayName()?.let { extractCleanName(it) }?.takeIf { it.isNotBlank() }

    /** NIS pemilik session yang tersimpan pada profil. */
    suspend fun myNis(): String? =
        myRawDisplayName()?.let { extractNis(it) }?.takeIf { it.isNotBlank() }

    /**
     * Menyimpan nama asli (beserta NIS & Kelas bila ada) milik sendiri (validasi 3-50 karakter di server).
     */
    suspend fun setMyName(name: String, nis: String = "", kelas: String = "") {
        val packed = if (nis.isNotBlank() || kelas.isNotBlank()) packNameAndNis(name, nis, kelas) else name.trim()
        val params = mapOf("p_name" to kotlinx.serialization.json.JsonPrimitive(packed))
        client.postgrest.rpc("set_my_name", params)
    }

    private val NIS_TAG_REGEX = Regex("\\s*\\[NIS:\\s*([0-9]+)\\]", RegexOption.IGNORE_CASE)
    private val KELAS_TAG_REGEX = Regex("\\s*\\[Kelas:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE)

    fun extractCleanName(raw: String): String =
        raw.trim()
            .replace(KELAS_TAG_REGEX, "")
            .replace(NIS_TAG_REGEX, "")
            .trim()

    fun extractNis(raw: String): String =
        NIS_TAG_REGEX.find(raw.trim())?.groupValues?.getOrNull(1)?.trim().orEmpty()

    fun extractClass(raw: String): String =
        KELAS_TAG_REGEX.find(raw.trim())?.groupValues?.getOrNull(1)?.trim().orEmpty()

    fun normalizeNis(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        return if (digits.length in 4..15) digits else null
    }

    fun normalizeClass(raw: String): String? {
        val clean = raw.replace("[", "").replace("]", "").trim().replace(Regex("\\s+"), " ")
        return if (clean.length in 2..15) clean else null
    }

    fun packNameAndNis(name: String, nis: String, kelas: String = ""): String {
        val cleanName = extractCleanName(name)
        val cleanNis = nis.filter(Char::isDigit).take(12)
        val cleanKelas = normalizeClass(kelas).orEmpty()
        val suffix = buildString {
            if (cleanNis.isNotBlank()) append(" [NIS:$cleanNis]")
            if (cleanKelas.isNotBlank()) append(" [Kelas:$cleanKelas]")
        }
        val maxNameLen = (50 - suffix.length).coerceAtLeast(10)
        return (cleanName.take(maxNameLen).trim() + suffix).trim()
    }

    /**
     * Mendaftarkan akun baru lewat Supabase Auth.
     *
     * Tidak ada OTP dan tidak ada tabel password sendiri; kata sandi hanya dikirim
     * ke endpoint Supabase Auth. Bila "Confirm email" sudah dimatikan di dashboard,
     * Supabase langsung mengembalikan session sehingga user masuk tanpa langkah
     * tambahan.
     */
    suspend fun register(email: String, password: String, name: String = ""): RegisterResult {
        check(configured) { NOT_CONFIGURED }
        val cleanName = name.trim()
        client.auth.signUpWith(Email, redirectUrl = AUTH_REDIRECT_URL) {
            this.email = email
            this.password = password
            if (cleanName.isNotBlank()) {
                this.data = kotlinx.serialization.json.buildJsonObject {
                    put("display_name", kotlinx.serialization.json.JsonPrimitive(cleanName))
                }
            }
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

    /** Kirim email pemulihan kata sandi (berisi link deep-link + token/OTP recovery). */
    suspend fun sendPasswordReset(email: String) {
        check(configured) { NOT_CONFIGURED }
        client.auth.resetPasswordForEmail(
            email = email.trim(),
            redirectUrl = AUTH_REDIRECT_URL
        )
    }

    /** Verifikasi kode OTP recovery dari email lalu langsung perbarui kata sandi baru. */
    suspend fun verifyRecoveryOtpAndSetPassword(email: String, token: String, newPassword: String) {
        check(configured) { NOT_CONFIGURED }
        client.auth.verifyEmailOtp(
            type = OtpType.Email.RECOVERY,
            email = email.trim(),
            token = token.trim()
        )
        client.auth.updateUser {
            password = newPassword
        }
    }

    /** Perbarui kata sandi akun yang sudah memiliki session recovery aktif. */
    suspend fun updatePassword(newPassword: String) {
        check(configured) { NOT_CONFIGURED }
        client.auth.updateUser {
            password = newPassword
        }
    }

    /** Impor session dari Intent deep-link (PKCE / Implicit) untuk login & recovery link. */
    fun importDeepLinkSession(intent: android.content.Intent, onSessionReady: () -> Unit) {
        if (!configured) return
        client.handleDeeplinks(intent) {
            onSessionReady()
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

            has("otp_expired", "token has expired", "invalid otp", "otp_disabled") ->
                "Kode/tautan reset salah atau sudah kedaluwarsa. Minta kode baru."

            has("same_password", "different from the old password") ->
                "Kata sandi baru harus berbeda dari kata sandi sebelumnya."

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
        val raw = candidate ?: throwable.message ?: return null
        val clean = raw.substringBefore("URL:").substringBefore("Headers:").trim()
        return clean.takeIf { it.isNotBlank() }
    }
}
