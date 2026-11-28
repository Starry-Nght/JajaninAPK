package com.jajanin.app.core.update

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.jajanin.app.BuildConfig
import com.jajanin.app.MainActivity
import com.jajanin.app.core.ui.adaptiveDialogWidthPx
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
internal data class OtaRelease(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    val changelog: String = "",
    val mandatory: Boolean = false,
    val sha256: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
private data class OtaReleaseInsert(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    val changelog: String,
    val mandatory: Boolean,
    val sha256: String,
    @SerialName("created_by") val createdBy: String?,
)

internal data class OtaPublishDraft(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val mandatory: Boolean,
)

private val otaJson = Json { ignoreUnknownKeys = true }
private const val PREFS_OTA = "jajanin_ota"
private const val PREF_LAST_PROMPTED_CODE = "last_prompted_version_code"

@Volatile
private var cachedInstalledSha256: String? = null

internal fun MainActivity.installedVersionCode(): Int {
    val pkgCode = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(BuildConfig.VERSION_CODE)
    return maxOf(BuildConfig.VERSION_CODE, pkgCode)
}

internal fun MainActivity.installedVersionName(): String {
    val pkgName = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName?.trim().orEmpty()
    }.getOrDefault("")
    return pkgName.ifBlank { BuildConfig.VERSION_NAME.trim() }
}

private suspend fun MainActivity.installedApkSha256(): String? = withContext(Dispatchers.IO) {
    cachedInstalledSha256?.let { return@withContext it }
    runCatching {
        val src = File(applicationInfo.sourceDir)
        if (!src.isFile) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        src.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.also { cachedInstalledSha256 = it }
    }.getOrNull()
}

/** Checks Supabase `app_releases` for a newer APK and prompts for in-place update. */
internal fun MainActivity.checkForOtaUpdate(silent: Boolean) {
    if (!SupabaseRepository.isConfigured()) {
        if (!silent) Toast.makeText(this, "Supabase belum dikonfigurasi.", Toast.LENGTH_SHORT).show()
        return
    }
    if (!silent) {
        Toast.makeText(this, "Memeriksa pembaruan...", Toast.LENGTH_SHORT).show()
    }
    authScope.launch {
        val installedCode = installedVersionCode()
        val installedName = installedVersionName()
        val result = runCatching { fetchLatestOtaRelease() }
        val release = result.getOrNull()
        if (result.isFailure) {
            if (!silent) {
                val msg = result.exceptionOrNull()?.message?.take(120) ?: "Gagal memeriksa pembaruan"
                Toast.makeText(this@checkForOtaUpdate, msg, Toast.LENGTH_LONG).show()
            }
            return@launch
        }
        if (release == null) {
            ui.otaUpdateAvailable = false
            ui.otaLatestVersion = null
            ui.otaLatestChangelog = null
            ui.invalidate()
            if (!silent) {
                Toast.makeText(
                    this@checkForOtaUpdate,
                    "Aplikasi sudah versi terbaru (v$installedName)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@launch
        }

        val localSha = installedApkSha256()
        val sameSha = !release.sha256.isNullOrBlank() &&
            !localSha.isNullOrBlank() &&
            release.sha256.equals(localSha, ignoreCase = true)
        val sameVerName = release.versionName.trim().removePrefix("v")
            .equals(installedName.removePrefix("v"), ignoreCase = true)
        val hasNewer = release.versionCode > installedCode && !sameSha && !sameVerName

        ui.otaUpdateAvailable = hasNewer
        ui.otaLatestVersion = if (hasNewer) "v${release.versionName}" else "v$installedName"
        ui.otaLatestChangelog = release.changelog.trim().takeIf { it.isNotEmpty() }
        ui.invalidate()

        val prefs = getSharedPreferences(PREFS_OTA, android.content.Context.MODE_PRIVATE)
        if (!hasNewer) {
            prefs.edit().putInt(PREF_LAST_PROMPTED_CODE, maxOf(installedCode, release.versionCode)).apply()
            if (!silent) {
                Toast.makeText(
                    this@checkForOtaUpdate,
                    "Aplikasi sudah versi terbaru (v$installedName)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@launch
        }

        val lastPrompted = prefs.getInt(PREF_LAST_PROMPTED_CODE, -1)
        if (silent && !release.mandatory && (otaPromptedVersion >= release.versionCode || lastPrompted >= release.versionCode)) {
            return@launch
        }
        otaPromptedVersion = release.versionCode
        showOtaAvailableDialog(release)
    }
}

/** Automatically continues OTA download after the user enables Unknown App Installs in Settings. */
internal fun MainActivity.resumePendingOtaIfPermitted() {
    val pending = pendingOtaRelease ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
        pendingOtaRelease = null
        startOtaDownloadAndInstall(pending)
    }
}

internal fun MainActivity.showOtaAvailableDialog(release: OtaRelease) {
    val d = resources.displayMetrics.density
    val dialog = otaDialog()
    val root = otaContainer()

    root.addView(otaTitle("Update Tersedia · v${release.versionName}"))
    val dateSuffix = release.createdAt.take(10).takeIf { it.length == 10 }?.let { "  ·  Rilis $it" }.orEmpty()
    val mandatoryTag = if (release.mandatory) "  ·  Wajib" else ""
    root.addView(
        otaSubtle(
            "Terpasang: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})  ke  Baru: v${release.versionName} (build ${release.versionCode})$dateSuffix$mandatoryTag",
        ).apply {
            setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
        },
    )

    val formattedNotes = formatChangelogBullets(release.changelog)
    root.addView(
        otaSectionLabel("Yang Diperbarui:").apply {
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        },
    )
    val notesBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * d
            setColor(if (isDarkMode()) Color.argb(38, 255, 255, 255) else Color.rgb(244, 247, 252))
        }
        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        val scroll = ScrollView(this@showOtaAvailableDialog).apply {
            isVerticalScrollBarEnabled = true
            addView(otaBodyText(formattedNotes))
        }
        addView(scroll)
    }
    root.addView(
        notesBox,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (14 * d).toInt()
        },
    )

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    if (!release.mandatory) {
        buttons.addView(
            otaTextButton("Nanti", mutedColor()) {
                getSharedPreferences(PREFS_OTA, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_LAST_PROMPTED_CODE, release.versionCode)
                    .apply()
                dialog.dismiss()
            },
        )
    }
    buttons.addView(
        otaTextButton("Update Sekarang", accentColor(), bold = true) {
            dialog.dismiss()
            ensureInstallPermissionAndStart(release)
        },
    )
    root.addView(buttons)
    dialog.setCancelable(!release.mandatory)
    dialog.setCanceledOnTouchOutside(!release.mandatory)
    dialog.setContentView(root)
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.88f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    dialog.show()
}

private fun MainActivity.ensureInstallPermissionAndStart(release: OtaRelease) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pendingOtaRelease = release
        val d = resources.displayMetrics.density
        val dialog = otaDialog()
        val root = otaContainer()
        root.addView(otaTitle("Izinkan Pemasangan Aplikasi"))
        root.addView(
            otaBodyText(
                "Android membutuhkan izin \"Install unknown apps\" untuk Jajanin agar update bisa dipasang langsung dari aplikasi. Aktifkan izin lalu kembali ke sini.",
            ).apply {
                setPadding(0, (8 * d).toInt(), 0, (14 * d).toInt())
            },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            otaTextButton("Batal", mutedColor()) {
                pendingOtaRelease = null
                dialog.dismiss()
            },
        )
        buttons.addView(
            otaTextButton("Buka Pengaturan", accentColor(), bold = true) {
                dialog.dismiss()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
                startActivity(intent)
            },
        )
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            adaptiveDialogWidthPx(0.88f),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
        return
    }
    startOtaDownloadAndInstall(release)
}

internal fun MainActivity.startOtaDownloadAndInstall(release: OtaRelease) {
    otaDownloadJob?.cancel()
    val d = resources.displayMetrics.density
    val dialog = otaDialog()
    val root = otaContainer()

    val title = otaTitle("Mengunduh Jajanin v${release.versionName}")
    val status = otaSubtle("Menghubungkan ke server...").apply {
        setPadding(0, (6 * d).toInt(), 0, (12 * d).toInt())
    }
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = true
    }
    root.addView(title)
    root.addView(status)
    root.addView(
        progressBar,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (10 * d).toInt() },
    )

    var job: Job? = null
    if (!release.mandatory) {
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            otaTextButton("Batal", mutedColor()) {
                job?.cancel()
                dialog.dismiss()
            },
        )
        root.addView(buttons)
    }

    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
    dialog.setContentView(root)
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.86f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    dialog.show()

    job = authScope.launch {
        val result = runCatching {
            downloadApkToCache(release) { downloadedBytes, totalBytes ->
                runOnUiThread {
                    if (totalBytes > 0L) {
                        val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        val dlMb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / 1_048_576f)
                        val totMb = String.format(java.util.Locale.US, "%.1f", totalBytes / 1_048_576f)
                        status.text = "Mengunduh $pct% ($dlMb / $totMb MB)"
                    } else {
                        val dlMb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / 1_048_576f)
                        status.text = "Mengunduh... ($dlMb MB)"
                    }
                }
            }
        }
        dialog.dismiss()
        val err = result.exceptionOrNull()
        if (err is CancellationException) return@launch
        val apkFile = result.getOrNull()
        if (apkFile == null) {
            Toast.makeText(
                this@startOtaDownloadAndInstall,
                "Gagal mengunduh update: ${err?.message ?: "unknown error"}",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        launchInPlaceApkInstaller(apkFile)
    }
    otaDownloadJob = job
}

private fun MainActivity.launchInPlaceApkInstaller(apkFile: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(installIntent) }
        .onFailure {
            Toast.makeText(this, "Tidak bisa membuka installer: ${it.message}", Toast.LENGTH_LONG).show()
        }
}

private suspend fun fetchLatestOtaRelease(): OtaRelease? = withContext(Dispatchers.IO) {
    val endpoint = "${SupabaseRepository.baseUrl}/rest/v1/app_releases" +
        "?select=version_code,version_name,apk_url,changelog,mandatory,sha256,created_at" +
        "&order=version_code.desc,created_at.desc&limit=1"
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("apikey", SupabaseRepository.publishableKey)
        setRequestProperty("Authorization", "Bearer ${SupabaseRepository.publishableKey}")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code == 404 || (code == 400 && body.contains("app_releases", ignoreCase = true))) {
            return@withContext null
        }
        if (code !in 200..299) {
            error("HTTP $code: ${body.take(120)}")
        }
        otaJson.decodeFromString<List<OtaRelease>>(body).firstOrNull()
    } finally {
        conn.disconnect()
    }
}

private suspend fun MainActivity.downloadApkToCache(
    release: OtaRelease,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): File = withContext(Dispatchers.IO) {
    val dir = File(cacheDir, "updates").apply { mkdirs() }
    dir.listFiles()?.forEach { old -> old.delete() }
    val target = File(dir, "jajanin-v${release.versionCode}.apk")

    var currentUrl = release.apkUrl
    var redirects = 0
    lateinit var conn: HttpURLConnection
    while (true) {
        conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val code = conn.responseCode
        if (code in listOf(301, 302, 303, 307, 308)) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            if (next.isNullOrBlank() || redirects >= 5) {
                error("Terlalu banyak redirect saat mengunduh APK")
            }
            currentUrl = next
            redirects++
            continue
        }
        if (code !in 200..299) {
            conn.disconnect()
            error("HTTP $code saat mengunduh APK")
        }
        break
    }

    try {
        val total = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        val buffer = ByteArray(16 * 1024)
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                var lastReportAt = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastReportAt >= 120L || downloaded == total) {
                        lastReportAt = now
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        if (target.length() < 100_000L) {
            target.delete()
            error("File APK tidak valid (${target.length()} bytes)")
        }
        val expectedSha = release.sha256?.trim()?.lowercase()?.takeIf { it.length == 64 }
        if (expectedSha != null) {
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != expectedSha) {
                target.delete()
                error("Checksum APK tidak cocok")
            }
        }
        target
    } finally {
        conn.disconnect()
    }
}

/** Developer form to upload an APK from device storage and publish an OTA release to Supabase. */
internal fun MainActivity.showDevOtaPublishForm() {
    val d = resources.displayMetrics.density
    val dialog = otaDialog()
    val root = otaContainer()

    root.addView(otaTitle("Rilis Update OTA"))
    root.addView(
        otaSubtle("Pilih APK dari perangkat untuk diunggah ke Supabase Storage (app-updates).").apply {
            setPadding(0, (4 * d).toInt(), 0, (10 * d).toInt())
        },
    )

    val codeField = EditText(this).apply {
        hint = "Version Code (angka > ${BuildConfig.VERSION_CODE})"
        setText((BuildConfig.VERSION_CODE + 1).toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(inkColor())
        setHintTextColor(mutedColor())
    }
    val nameField = EditText(this).apply {
        hint = "Version Name (misal 1.1)"
        setText("1.${BuildConfig.VERSION_CODE}")
        setTextColor(inkColor())
        setHintTextColor(mutedColor())
    }
    val notesField = EditText(this).apply {
        hint = "Catatan pembaruan (changelog)"
        setTextColor(inkColor())
        setHintTextColor(mutedColor())
    }
    val mandatoryCheck = CheckBox(this).apply {
        text = "Wajib update (tidak bisa dilewati)"
        setTextColor(inkColor())
    }

    root.addView(codeField)
    root.addView(nameField)
    root.addView(notesField)
    root.addView(mandatoryCheck)

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, (10 * d).toInt(), 0, 0)
    }
    buttons.addView(otaTextButton("Batal", mutedColor()) { dialog.dismiss() })
    buttons.addView(
        otaTextButton("Pilih APK & Rilis", accentColor(), bold = true) {
            val code = codeField.text.toString().trim().toIntOrNull()
            val name = nameField.text.toString().trim()
            if (code == null || code <= 0 || name.isEmpty()) {
                Toast.makeText(this, "Isi version code dan version name yang valid.", Toast.LENGTH_SHORT).show()
                return@otaTextButton
            }
            pendingOtaPublishDraft = OtaPublishDraft(
                versionCode = code,
                versionName = name,
                changelog = notesField.text.toString().trim(),
                mandatory = mandatoryCheck.isChecked,
            )
            dialog.dismiss()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(picker, REQUEST_OTA_APK)
        },
    )
    root.addView(buttons)
    dialog.setContentView(root)
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.90f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    dialog.show()
}

internal fun MainActivity.publishOtaApkFromUri(uri: Uri) {
    val draft = pendingOtaPublishDraft ?: return
    pendingOtaPublishDraft = null
    val jwt = SupabaseRepository.currentAccessToken()
    if (jwt.isNullOrBlank()) {
        Toast.makeText(this, "Sesi tidak ditemukan. Masuk ulang sebagai developer.", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, "Mengunggah APK v${draft.versionName}...", Toast.LENGTH_SHORT).show()
    authScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val apkBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Gagal membaca file APK")
                if (apkBytes.size < 100_000) error("File terlalu kecil (${apkBytes.size} bytes)")
                val sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(apkBytes)
                    .joinToString("") { "%02x".format(it) }
                val objectName = "jajanin-v${draft.versionCode}.apk"
                val uploadUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/app-updates/$objectName"
                val upConn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("apikey", SupabaseRepository.publishableKey)
                    setRequestProperty("Authorization", "Bearer $jwt")
                    setRequestProperty("Content-Type", "application/vnd.android.package-archive")
                    setRequestProperty("x-upsert", "true")
                }
                upConn.outputStream.use { it.write(apkBytes) }
                val upCode = upConn.responseCode
                val upBody = (if (upCode in 200..299) upConn.inputStream else upConn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                upConn.disconnect()
                if (upCode !in 200..299) error("Upload gagal (HTTP $upCode): ${upBody.take(120)}")

                val publicUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/public/app-updates/$objectName"
                val payload = otaJson.encodeToString(
                    OtaReleaseInsert(
                        versionCode = draft.versionCode,
                        versionName = draft.versionName,
                        apkUrl = publicUrl,
                        changelog = draft.changelog,
                        mandatory = draft.mandatory,
                        sha256 = sha256,
                        createdBy = SupabaseRepository.currentUid(),
                    ),
                )
                val postConn = (URL("${SupabaseRepository.baseUrl}/rest/v1/app_releases").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("apikey", SupabaseRepository.publishableKey)
                    setRequestProperty("Authorization", "Bearer $jwt")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                postConn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val postCode = postConn.responseCode
                val postBody = (if (postCode in 200..299) postConn.inputStream else postConn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                postConn.disconnect()
                if (postCode !in 200..299) error("Simpan metadata gagal (HTTP $postCode): ${postBody.take(120)}")
            }
        }
        result.onSuccess {
            Toast.makeText(
                this@publishOtaApkFromUri,
                "OTA v${draft.versionName} berhasil dirilis!",
                Toast.LENGTH_LONG,
            ).show()
            checkForOtaUpdate(silent = true)
        }.onFailure {
            Toast.makeText(
                this@publishOtaApkFromUri,
                "Gagal merilis OTA: ${it.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private fun MainActivity.inkColor(): Int =
    if (isDarkMode()) Color.rgb(235, 238, 245) else Color.rgb(27, 35, 53)

private fun MainActivity.mutedColor(): Int =
    if (isDarkMode()) Color.rgb(150, 158, 175) else Color.rgb(112, 120, 137)

private fun MainActivity.accentColor(): Int =
    if (isDarkMode()) Color.rgb(80, 150, 255) else Color.rgb(0, 99, 214)

private fun MainActivity.otaDialog(): Dialog = Dialog(this).apply {
    window?.setBackgroundDrawableResource(android.R.color.transparent)
}

private fun MainActivity.otaContainer(): LinearLayout {
    val d = resources.displayMetrics.density
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 22f * d
        if (isDarkMode()) {
            setColor(Color.rgb(27, 32, 59))
            setStroke((1f * d).toInt(), Color.argb(60, 255, 255, 255))
        } else {
            setColor(Color.WHITE)
            setStroke((1f * d).toInt(), Color.rgb(224, 228, 236))
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg
        setPadding((24 * d).toInt(), (22 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
    }
}

private fun formatChangelogBullets(raw: String): String {
    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return "• Pembaruan aplikasi siap diunduh dan dipasang langsung."
    return lines.joinToString("\n") { line ->
        if (line.startsWith("•") || line.startsWith("-") || line.startsWith("*")) {
            "• " + line.trimStart('•', '-', '*', ' ')
        } else {
            "• $line"
        }
    }
}

private fun MainActivity.otaSectionLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(accentColor())
    textSize = 12.5f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
}

private fun MainActivity.otaTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(inkColor())
    textSize = 18f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private fun MainActivity.otaSubtle(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(mutedColor())
    textSize = 12f
}

private fun MainActivity.otaBodyText(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(inkColor())
    textSize = 14f
    setLineSpacing(0f, 1.35f)
}

private fun MainActivity.otaTextButton(
    label: String,
    color: Int,
    bold: Boolean = false,
    onClick: (View) -> Unit,
): TextView {
    val d = resources.displayMetrics.density
    return TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        setOnClickListener(onClick)
    }
}
