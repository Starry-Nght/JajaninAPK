package com.jajanin.app.feature.seller

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.data.repository.PhoneRepository
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.refreshMyOrders
import com.jajanin.app.feature.buyer.refreshProducts
import com.jajanin.app.feature.chat.startChatObserver
import com.jajanin.app.feature.inbox.refreshInbox
import com.jajanin.app.feature.inbox.startInboxObserver
import kotlinx.coroutines.launch

/** Beralih dari Mode Pembeli ke Mode Penjual (tanpa kode developer, tanpa logout). */
internal fun MainActivity.switchToSellerMode() {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk membuka Mode Penjual.", Toast.LENGTH_SHORT).show()
        showAuthForm(false)
        return
    }
    if (!ui.myIsSellerVerified) {
        showSellerOtpActivationDialog()
        return
    }
    ui.myRole = "seller"
    ui.isSellerProfile = false
    ui.sellerTab = "Dashboard"
    ui.showAdmin()
    startAdminOrders()
    refreshSellerProducts()
    refreshSellerSettings()
    refreshInbox()
    startInboxObserver()
    startChatObserver()
    registerMyDevice()
    ensureOnlinePresence("seller")
    Toast.makeText(this, "Beralih ke Mode Penjual", Toast.LENGTH_SHORT).show()
}

/** Beralih dari Mode Penjual kembali ke Mode Pembeli (1 akun dual-mode). */
internal fun MainActivity.switchToBuyerMode() {
    ui.myRole = "buyer"
    ui.isSellerProfile = false
    ui.tab = "Home"
    ui.showHome()
    refreshProducts()
    refreshMyOrders()
    refreshInbox()
    startInboxObserver()
    startChatObserver()
    registerMyDevice()
    ensureOnlinePresence("buyer")
    Toast.makeText(this, "Beralih ke Mode Pembeli", Toast.LENGTH_SHORT).show()
}

/**
 * Dialog aktivasi mandiri Mode Penjual dengan verifikasi Email OTP 6 digit
 * + wajib isi Nomor WhatsApp aktif & Nama Warung (tanpa perlu menghubungi developer).
 */
internal fun MainActivity.showSellerOtpActivationDialog() {
    val self = this
    val kit = ui.kit
    val d = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()
    fun rounded(fill: Int, r: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r * d
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    var otpProvider = "supabase_auth"
    var otpRequested = false
    var busy = false

    val scroll = ScrollView(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(kit.card, 18f, kit.border)
        setPadding(dp(20), dp(20), dp(20), dp(18))
    }

    val titleTv = TextView(this).apply {
        text = "Aktifkan Mode Penjual"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(kit.ink)
        typeface = Typeface.DEFAULT_BOLD
    }
    val subTv = TextView(this).apply {
        text = "Verifikasi email aktif dengan kode OTP 6 digit & isi nomor WA warungmu — tanpa kode developer."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(kit.muted)
        setPadding(0, dp(4), 0, dp(14))
    }
    root.addView(titleTv)
    root.addView(subTv)

    fun makeField(hintText: String, initial: String, inputTypeFlag: Int, maxLen: Int = 80): EditText =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            setHintTextColor(kit.muted)
            setTextColor(kit.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = inputTypeFlag
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            background = rounded(kit.bg, 10f, kit.border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

    val currentRealEmail = SupabaseRepository.currentEmail()
        ?.takeIf { !it.endsWith("@siswa.jajanin.app", ignoreCase = true) }
        .orEmpty()

    val emailInput = makeField(
        "Email aktif untuk OTP (cth. nama@gmail.com)",
        currentRealEmail,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        100,
    )
    val stallInput = makeField(
        "Nama Warung / Toko (cth. Warung Athala)",
        ui.myStallName,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        40,
    )
    val phoneInput = makeField(
        "Nomor WhatsApp aktif (wajib, cth. 0812xxx)",
        ui.myPhone,
        InputType.TYPE_CLASS_PHONE,
        18,
    )

    val requestOtpBtn = TextView(this).apply {
        text = "Kirim Kode OTP ke Email"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(kit.blue)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(kit.tint, 12f, kit.blue)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(10)
        }
    }

    val otpContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(kit.tint, 12f, kit.border)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(10)
        }
    }
    val otpHintTv = TextView(this).apply {
        text = "Kode OTP 6 Digit (dari email):"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(kit.blue)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }
    val otpInput = makeField(
        "• • • • • •",
        "",
        InputType.TYPE_CLASS_NUMBER,
        6,
    ).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.35f
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(2)
    }
    otpContainer.addView(otpHintTv)
    otpContainer.addView(otpInput)

    val statusTv = TextView(this).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(4), dp(2), dp(4), dp(10))
    }

    val verifyBtn = TextView(this).apply {
        text = "Verifikasi OTP & Buka Warung"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(kit.blue, 12f)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    val cancelBtn = TextView(this).apply {
        text = "Batal"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(kit.muted)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(4))
        setOnClickListener { dialog.dismiss() }
    }

    root.addView(emailInput)
    root.addView(stallInput)
    root.addView(phoneInput)
    root.addView(requestOtpBtn)
    root.addView(otpContainer)
    root.addView(statusTv)
    root.addView(verifyBtn)
    root.addView(cancelBtn)
    scroll.addView(root)
    dialog.setContentView(scroll)

    fun showStatus(msg: String, isError: Boolean) {
        statusTv.visibility = View.VISIBLE
        statusTv.text = msg
        statusTv.setTextColor(if (isError) kit.danger else kit.success)
    }

    fun updateBusy(loading: Boolean, actionLabel: String? = null) {
        busy = loading
        requestOtpBtn.isEnabled = !loading
        verifyBtn.isEnabled = !loading
        emailInput.isEnabled = !loading
        requestOtpBtn.text = when {
            loading && actionLabel == "sending" -> "Mengirim OTP..."
            otpRequested -> "Kirim Ulang Kode OTP ke Email"
            else -> "Kirim Kode OTP ke Email"
        }
        verifyBtn.text = if (loading && actionLabel == "verifying") "Memverifikasi OTP..." else "Verifikasi OTP & Buka Warung"
        verifyBtn.background = rounded(if (loading) kit.muted else kit.blue, 12f)
    }

    fun requestOtpFlow() {
        if (busy) return
        val emailVal = emailInput.text.toString().trim().lowercase()
        val stallVal = stallInput.text.toString().trim()
        val phoneVal = phoneInput.text.toString().trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailVal).matches() || emailVal.endsWith("@siswa.jajanin.app")) {
            showStatus("Masukkan email aktif valid (mis. Gmail) untuk menerima kode OTP.", true)
            return
        }
        if (stallVal.length < 3) {
            showStatus("Nama warung minimal 3 karakter.", true)
            return
        }
        if (PhoneRepository.normalizePhone(phoneVal) == null) {
            showStatus("Nomor WhatsApp aktif wajib diisi (cth. 0812xxxxxxx).", true)
            return
        }
        updateBusy(true, "sending")
        authScope.launch {
            try {
                val res = SupabaseRepository.requestSellerEmailOtp(emailVal)
                otpProvider = res.provider
                otpRequested = true
                otpContainer.background = rounded(kit.tint, 12f, kit.blue)
                showStatus(res.message, false)
                updateBusy(false)
                otpInput.requestFocus()
            } catch (t: Throwable) {
                showStatus(SupabaseRepository.errorMessage(t), true)
                updateBusy(false)
            }
        }
    }

    fun verifyOtpFlow() {
        if (busy) return
        val emailVal = emailInput.text.toString().trim().lowercase()
        val codeVal = otpInput.text.toString().filter(Char::isDigit).trim()
        val stallVal = stallInput.text.toString().trim()
        val phoneVal = phoneInput.text.toString().trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailVal).matches() || emailVal.endsWith("@siswa.jajanin.app")) {
            showStatus("Masukkan email aktif valid terlebih dahulu.", true)
            return
        }
        if (codeVal.length != 6) {
            showStatus("Masukkan 6 digit angka OTP dari email kamu.", true)
            otpInput.requestFocus()
            return
        }
        if (stallVal.length < 3) {
            showStatus("Nama warung minimal 3 karakter.", true)
            return
        }
        if (PhoneRepository.normalizePhone(phoneVal) == null) {
            showStatus("Nomor WhatsApp aktif wajib valid.", true)
            return
        }
        updateBusy(true, "verifying")
        authScope.launch {
            try {
                SupabaseRepository.verifySellerEmailOtpAndActivate(
                    provider = otpProvider,
                    email = emailVal,
                    code = codeVal,
                    stallName = stallVal,
                    phone = phoneVal,
                    room = "",
                )
                ui.myIsSellerVerified = true
                ui.mySellerVerifiedEmail = emailVal
                ui.myStallName = stallVal
                PhoneRepository.normalizePhone(phoneVal)?.let { ui.myPhone = it }
                dialog.dismiss()
                Toast.makeText(self, "Mode Penjual aktif! Selamat berjualan.", Toast.LENGTH_LONG).show()
                switchToSellerMode()
            } catch (t: Throwable) {
                showStatus(SupabaseRepository.errorMessage(t), true)
                updateBusy(false)
            }
        }
    }

    requestOtpBtn.setOnClickListener { requestOtpFlow() }
    verifyBtn.setOnClickListener { verifyOtpFlow() }

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    @Suppress("DEPRECATION")
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}
