package com.jajanin.app.feature.auth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.jajanin.app.MainActivity
import com.jajanin.app.data.repository.PhoneRepository
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layar Login & Aktivasi Akun NATIVE full-screen (bukan pajangan kanvas / popup dialog).
 *
 * Fitur utama:
 * - Input NIS langsung di layar penuh dengan dropdown saran siswa live (prefix search) ala Snaps.
 * - Begitu NIS dipilih / cocok, kartu identitas siswa (NIS, Nama Lengkap, Kelas, status Sudah Terdaftar / Siap Aktivasi)
 *   otomatis terisi dan terkunci dari database `public.students`.
 * - Mode cadangan Email untuk Developer / akun lama tersedia langsung di layar yang sama.
 */
internal class NativeLoginOverlay(
    private val activity: MainActivity,
    parent: FrameLayout,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val rootContainer: FrameLayout
    private val scrollView: ScrollView
    private val contentCol: LinearLayout

    // Header
    private val backBtn: TextView
    private val brandTv: TextView
    private val headingTv: TextView
    private val subtitleTv: TextView

    // NIS Mode widgets
    private val nisSection: LinearLayout
    private val nisFieldLabel: TextView
    private val nisBox: LinearLayout
    private val nisBadgeIcon: TextView
    private val nisInput: EditText
    private val nisTrailingBtn: TextView

    // Live Dropdown (Snaps-style prefix suggestions)
    private val dropdownCard: LinearLayout
    private val dropdownList: LinearLayout

    // Locked Verified Student Identity Card (Snaps-style auto-fill)
    private val identityCard: LinearLayout
    private val identityNisPill: TextView
    private val identityNameTv: TextView
    private val identityClassTv: TextView
    private val identityStatusPill: TextView
    private val identityLockIcon: LockVectorView

    // Phone field (for first-time NIS activation)
    private val phoneBox: LinearLayout
    private val phoneIcon: TextView
    private val phoneInput: EditText

    // Email Mode widgets
    private val emailSection: LinearLayout
    private val emailBox: LinearLayout
    private val emailIcon: TextView
    private val emailInput: EditText

    // Shared Password + Terms + Error + CTA
    private val passwordBox: LinearLayout
    private val passwordIcon: LockVectorView
    private val passwordInput: EditText
    private val showPassBtn: TextView
    private val forgotBtn: TextView

    private val termsRow: LinearLayout
    private val termsCheck: CheckBox
    private val termsLabel: TextView

    private val errorTv: TextView
    private val ctaContainer: LinearLayout
    private val ctaBtn: TextView
    private val ctaProgress: ProgressBar
    private val switchModeBtn: TextView
    private val footerNoteTv: TextView

    // State
    private var darkMode = true
    private var useEmailMode = false
    private var passVisible = false
    private var busy = false
    private var suppressNisWatcher = false
    private var dropdownExpanded = false
    private var selectedStudent: SupabaseRepository.StudentNisLookupResult? = null
    private var lastMatches: List<SupabaseRepository.StudentNisLookupResult> = emptyList()
    private var searchJob: Job? = null

    private var baseTopInset = 0
    private var baseBottomInset = 0

    // Palette helpers
    private val bgColor get() = if (darkMode) Color.rgb(11, 15, 25) else Color.rgb(248, 250, 253)
    private val surfaceColor get() = if (darkMode) Color.rgb(20, 27, 42) else Color.WHITE
    private val surfaceSoft get() = if (darkMode) Color.rgb(26, 35, 54) else Color.rgb(241, 245, 252)
    private val inkColor get() = if (darkMode) Color.rgb(241, 245, 252) else Color.rgb(20, 27, 42)
    private val mutedColor get() = if (darkMode) Color.rgb(142, 154, 176) else Color.rgb(104, 114, 134)
    private val borderColor get() = if (darkMode) Color.rgb(42, 54, 78) else Color.rgb(220, 227, 238)
    private val dividerColor get() = if (darkMode) Color.rgb(34, 45, 66) else Color.rgb(232, 237, 245)
    private val blueColor get() = if (darkMode) Color.rgb(66, 153, 255) else Color.rgb(0, 99, 214)
    private val tintColor get() = if (darkMode) Color.rgb(22, 42, 74) else Color.rgb(232, 242, 255)
    private val greenFg get() = if (darkMode) Color.rgb(74, 222, 128) else Color.rgb(21, 128, 61)
    private val greenBg get() = if (darkMode) Color.rgb(19, 48, 36) else Color.rgb(220, 252, 231)
    private val amberFg get() = if (darkMode) Color.rgb(251, 191, 36) else Color.rgb(180, 83, 9)
    private val amberBg get() = if (darkMode) Color.rgb(54, 39, 14) else Color.rgb(254, 243, 199)
    private val dangerFg get() = if (darkMode) Color.rgb(248, 113, 113) else Color.rgb(206, 44, 44)
    private val dangerBg get() = if (darkMode) Color.rgb(54, 22, 26) else Color.rgb(254, 242, 242)

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fill)
            if (stroke != null) setStroke(dpF(strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    init {
        rootContainer = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        scrollView = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // 1. Top Bar (Back circle + JAJANIN brand)
        val topBar = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            )
        }
        backBtn = TextView(activity).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener {
                if (!handleBack()) {
                    hideKeyboard()
                    activity.ui.goBack()
                }
            }
        }
        brandTv = TextView(activity).apply {
            text = "JAJANIN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        topBar.addView(backBtn)
        topBar.addView(brandTv)
        contentCol.addView(topBar)

        // 2. Centered Heading + Subtitle (like Snaps)
        headingTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) }
        }
        subtitleTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(22)
            }
        }
        contentCol.addView(headingTv)
        contentCol.addView(subtitleTv)

        // 3. NIS Auto-Fill Section
        nisSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        nisFieldLabel = TextView(activity).apply {
            text = "Nomor Induk Siswa (NIS)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
        nisSection.addView(nisFieldLabel)

        nisBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
            )
        }
        nisBadgeIcon = TextView(activity).apply {
            text = "ID"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(10) }
        }
        nisInput = EditText(activity).apply {
            hint = "Masukkan NIS kamu (cth. 11111)"
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_NEXT
            filters = arrayOf(InputFilter.LengthFilter(20))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        nisTrailingBtn = TextView(activity).apply {
            text = "⌄"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                if (nisInput.text.isNotEmpty() && selectedStudent != null && !dropdownExpanded) {
                    // Reset pilihan atau buka ulang dropdown
                    if (lastMatches.size > 1) {
                        dropdownExpanded = true
                        renderDropdownAndIdentity()
                    } else {
                        nisInput.setText("")
                        nisInput.requestFocus()
                    }
                } else if (lastMatches.isNotEmpty()) {
                    dropdownExpanded = !dropdownExpanded
                    renderDropdownAndIdentity()
                }
            }
        }
        nisBox.addView(nisBadgeIcon)
        nisBox.addView(nisInput)
        nisBox.addView(nisTrailingBtn)
        nisSection.addView(nisBox)

        // 3a. Live Prefix Dropdown (Snaps Foto 3)
        dropdownCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dpF(6f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        dropdownList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dropdownCard.addView(dropdownList)
        nisSection.addView(dropdownCard)

        // 3b. Locked Verified Student Identity Card (Snaps Foto 2)
        identityCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        identityNisPill = TextView(activity).apply {
            text = "NIS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(12) }
        }
        val idCenter = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        identityNameTv = TextView(activity).apply {
            text = "Nama Lengkap (Otomatis)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val idSubRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) }
        }
        identityClassTv = TextView(activity).apply {
            text = "Kelas terisi otomatis dari NIS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        identityStatusPill = TextView(activity).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) }
        }
        idSubRow.addView(identityClassTv)
        idSubRow.addView(identityStatusPill)
        idCenter.addView(identityNameTv)
        idCenter.addView(idSubRow)

        identityLockIcon = LockVectorView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                leftMargin = dp(8)
            }
        }
        identityCard.addView(identityNisPill)
        identityCard.addView(idCenter)
        identityCard.addView(identityLockIcon)
        nisSection.addView(identityCard)

        // 3c. WhatsApp Phone Field (for unregistered student activation)
        phoneBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { topMargin = dp(12) }
        }
        phoneIcon = TextView(activity).apply {
            text = "WA"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(10) }
        }
        phoneInput = EditText(activity).apply {
            hint = "Nomor WhatsApp aktif (cth. 0812...)"
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_PHONE
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        phoneBox.addView(phoneIcon)
        phoneBox.addView(phoneInput)
        nisSection.addView(phoneBox)

        contentCol.addView(nisSection)

        // 4. Email Mode Section (Developer / Akun Lama)
        emailSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        emailBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
            )
        }
        emailIcon = TextView(activity).apply {
            text = "@"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }
        emailInput = EditText(activity).apply {
            hint = "Email akun Developer / lama"
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        emailBox.addView(emailIcon)
        emailBox.addView(emailInput)
        emailSection.addView(emailBox)
        contentCol.addView(emailSection)

        // 5. Shared Password Box
        passwordBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply { topMargin = dp(12) }
        }
        passwordIcon = LockVectorView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                leftMargin = dp(4)
                rightMargin = dp(10)
            }
        }
        passwordInput = EditText(activity).apply {
            hint = "Kata Sandi"
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        showPassBtn = TextView(activity).apply {
            text = "Lihat"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { togglePasswordVisibility() }
        }
        passwordBox.addView(passwordIcon)
        passwordBox.addView(passwordInput)
        passwordBox.addView(showPassBtn)
        contentCol.addView(passwordBox)

        // Forgot password link (Email mode)
        forgotBtn = TextView(activity).apply {
            text = "Lupa kata sandi?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            visibility = View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                activity.showForgotPasswordDialog(emailInput.text.toString().trim())
            }
        }
        contentCol.addView(forgotBtn)

        // 6. Consent Row (like Snaps)
        termsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        termsCheck = CheckBox(activity).apply {
            isChecked = true
            setOnCheckedChangeListener { _, _ -> updateCtaState() }
        }
        termsLabel = TextView(activity).apply {
            text = "Saya menyetujui Ketentuan & Privasi Jajanin SMKN 8"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener { termsCheck.isChecked = !termsCheck.isChecked }
        }
        termsRow.addView(termsCheck)
        termsRow.addView(termsLabel)
        contentCol.addView(termsRow)

        // 7. Inline Error Box
        errorTv = TextView(activity).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        contentCol.addView(errorTv)

        // 8. Full-width Rounded Pill CTA Button
        ctaContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { topMargin = dp(18) }
            setOnClickListener { submit() }
        }
        ctaBtn = TextView(activity).apply {
            text = "Masuk / Aktifkan Akun"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        ctaProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(10) }
        }
        ctaContainer.addView(ctaBtn)
        ctaContainer.addView(ctaProgress)
        contentCol.addView(ctaContainer)

        // 9. Mode Toggle (NIS <-> Email Developer)
        switchModeBtn = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                if (busy) return@setOnClickListener
                useEmailMode = !useEmailMode
                clearError()
                applyModeUi()
                focusPrimaryField(false)
            }
        }
        contentCol.addView(switchModeBtn)

        footerNoteTv = TextView(activity).apply {
            text = "1 akun otomatis menjadi Pembeli — bisa aktifkan Mode Penjual kapan saja di Profil."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        contentCol.addView(footerNoteTv)

        scrollView.addView(contentCol)
        rootContainer.addView(scrollView)
        parent.addView(rootContainer)

        // WindowInsets for Android 15/16 Edge-to-Edge (status bar + nav bar + keyboard IME)
        rootContainer.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsets.Type.ime())
                baseTopInset = sys.top
                baseBottomInset = maxOf(sys.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                baseTopInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                baseBottomInset = insets.systemWindowInsetBottom
            }
            applyContentPadding()
            insets
        }

        // Input Listeners
        nisInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressNisWatcher || useEmailMode) return
                onNisTyped(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        nisInput.setOnFocusChangeListener { _, hasFocus ->
            updateNisBoxStroke(hasFocus)
        }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        applyTheme(activity.ui.darkMode)
        applyModeUi()
    }

    private fun applyContentPadding() {
        val topPad = maxOf(baseTopInset, dp(24)) + dp(14)
        val bottomPad = baseBottomInset + dp(28)
        contentCol.setPadding(dp(22), topPad, dp(22), bottomPad)
    }

    fun applyTheme(dark: Boolean) {
        darkMode = dark
        rootContainer.setBackgroundColor(bgColor)
        backBtn.background = rounded(surfaceColor, 20f, borderColor)
        backBtn.setTextColor(inkColor)
        brandTv.setTextColor(blueColor)
        headingTv.setTextColor(inkColor)
        subtitleTv.setTextColor(mutedColor)

        nisFieldLabel.setTextColor(blueColor)
        updateNisBoxStroke(nisInput.hasFocus())
        nisBadgeIcon.background = rounded(tintColor, 7f)
        nisBadgeIcon.setTextColor(blueColor)
        nisInput.setTextColor(inkColor)
        nisInput.setHintTextColor(mutedColor)
        nisTrailingBtn.setTextColor(mutedColor)

        phoneBox.background = rounded(surfaceColor, 14f, borderColor)
        phoneIcon.background = rounded(greenBg, 7f)
        phoneIcon.setTextColor(greenFg)
        phoneInput.setTextColor(inkColor)
        phoneInput.setHintTextColor(mutedColor)

        emailBox.background = rounded(surfaceColor, 14f, borderColor)
        emailIcon.setTextColor(blueColor)
        emailInput.setTextColor(inkColor)
        emailInput.setHintTextColor(mutedColor)

        passwordBox.background = rounded(surfaceColor, 14f, borderColor)
        passwordIcon.iconColor = mutedColor
        passwordInput.setTextColor(inkColor)
        passwordInput.setHintTextColor(mutedColor)
        showPassBtn.setTextColor(blueColor)
        forgotBtn.setTextColor(blueColor)

        termsLabel.setTextColor(mutedColor)
        errorTv.background = rounded(dangerBg, 12f, dangerFg)
        errorTv.setTextColor(dangerFg)
        switchModeBtn.setTextColor(blueColor)
        footerNoteTv.setTextColor(mutedColor)

        dropdownCard.background = rounded(surfaceColor, 14f, borderColor)
        renderDropdownAndIdentity()
        updateCtaState()
    }

    private fun updateNisBoxStroke(focused: Boolean) {
        val stroke = if (focused || selectedStudent != null) blueColor else borderColor
        val width = if (focused || selectedStudent != null) 1.6f else 1f
        nisBox.background = rounded(surfaceColor, 14f, stroke, width)
    }

    fun setVisible(visible: Boolean) {
        if (visible) {
            applyTheme(activity.ui.darkMode)
            backBtn.visibility = if (activity.ui.canGoBack()) View.VISIBLE else View.INVISIBLE
            rootContainer.visibility = View.VISIBLE
            rootContainer.requestApplyInsets()
        } else {
            searchJob?.cancel()
            hideKeyboard()
            rootContainer.visibility = View.GONE
        }
    }

    fun openMode(emailMode: Boolean = false, focusPassword: Boolean = false) {
        useEmailMode = emailMode
        clearError()
        applyModeUi()
        setVisible(true)
        focusPrimaryField(focusPassword)
    }

    fun handleBack(): Boolean {
        if (rootContainer.visibility != View.VISIBLE) return false
        if (dropdownExpanded) {
            dropdownExpanded = false
            renderDropdownAndIdentity()
            return true
        }
        if (useEmailMode) {
            useEmailMode = false
            clearError()
            applyModeUi()
            return true
        }
        return false
    }

    private fun focusPrimaryField(focusPassword: Boolean) {
        val target = when {
            focusPassword -> passwordInput
            useEmailMode -> emailInput
            else -> nisInput
        }
        target.requestFocus()
        target.post {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootContainer.windowToken, 0)
    }

    private fun togglePasswordVisibility() {
        passVisible = !passVisible
        val cursor = passwordInput.selectionStart
        passwordInput.inputType = if (passVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordInput.setSelection(cursor.coerceIn(0, passwordInput.text.length))
        showPassBtn.text = if (passVisible) "Sembunyi" else "Lihat"
    }

    private fun applyModeUi() {
        if (useEmailMode) {
            headingTv.text = "Masuk dengan Email"
            subtitleTv.text = "Khusus akun Developer atau akun lama berbasis email"
            nisSection.visibility = View.GONE
            emailSection.visibility = View.VISIBLE
            forgotBtn.visibility = View.VISIBLE
            termsRow.visibility = View.GONE
            passwordInput.hint = "Kata sandi"
            switchModeBtn.text = "Kembali ke Masuk / Daftar dengan NIS"
        } else {
            headingTv.text = "Masuk / Daftar Siswa"
            subtitleTv.text = "Ketik NIS — Nama Lengkap & Kelas terisi otomatis"
            nisSection.visibility = View.VISIBLE
            emailSection.visibility = View.GONE
            forgotBtn.visibility = View.GONE
            termsRow.visibility = View.VISIBLE
            switchModeBtn.text = "Masuk dengan Email (Developer / Akun Lama)"
            renderDropdownAndIdentity()
        }
        updateCtaState()
    }

    private fun onNisTyped(raw: String) {
        searchJob?.cancel()
        clearError()
        val digits = raw.filter(Char::isDigit)

        if (selectedStudent != null && selectedStudent?.nis != digits) {
            selectedStudent = null
        }

        if (digits.length < 2) {
            lastMatches = emptyList()
            selectedStudent = null
            dropdownExpanded = false
            renderDropdownAndIdentity()
            updateCtaState()
            return
        }

        dropdownExpanded = true
        showDropdownLoading(digits)

        searchJob = activity.authScope.launch {
            delay(150L)
            try {
                val results = SupabaseRepository.searchStudentsNis(digits)
                if (nisInput.text.toString().filter(Char::isDigit) != digits) return@launch
                lastMatches = results
                val exact = results.firstOrNull { it.nis == digits }
                if (exact != null && results.size == 1) {
                    selectedStudent = exact
                    dropdownExpanded = false
                } else {
                    selectedStudent = exact
                    dropdownExpanded = results.isNotEmpty()
                }
                renderDropdownAndIdentity()
                if (results.isEmpty() && digits.length >= 4) {
                    showNotFoundState(digits)
                }
                updateCtaState()
            } catch (t: Throwable) {
                if (nisInput.text.toString().filter(Char::isDigit) != digits) return@launch
                lastMatches = emptyList()
                selectedStudent = null
                dropdownExpanded = false
                renderDropdownAndIdentity()
                showError("Gagal memeriksa NIS: ${SupabaseRepository.errorMessage(t)}")
                updateCtaState()
            }
        }
    }

    private fun selectStudentFromDropdown(student: SupabaseRepository.StudentNisLookupResult) {
        selectedStudent = student
        dropdownExpanded = false
        clearError()
        suppressNisWatcher = true
        nisInput.setText(student.nis)
        nisInput.setSelection(student.nis.length)
        suppressNisWatcher = false
        renderDropdownAndIdentity()
        updateCtaState()
        // Arahkan fokus ke field berikutnya secara otomatis
        if (!student.registered) {
            phoneInput.requestFocus()
        } else {
            passwordInput.requestFocus()
        }
    }

    private fun showDropdownLoading(digits: String) {
        dropdownList.removeAllViews()
        dropdownCard.visibility = View.VISIBLE
        dropdownCard.background = rounded(surfaceColor, 14f, borderColor)
        val loadingTv = TextView(activity).apply {
            text = "Mencari NIS $digits..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(mutedColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        dropdownList.addView(loadingTv)
        identityCard.visibility = View.GONE
    }

    private fun showNotFoundState(digits: String) {
        dropdownCard.visibility = View.GONE
        identityCard.visibility = View.VISIBLE
        identityCard.background = rounded(dangerBg, 14f, dangerFg)
        identityNisPill.background = rounded(surfaceColor, 8f)
        identityNisPill.setTextColor(dangerFg)
        identityNisPill.text = digits
        identityNameTv.setTextColor(dangerFg)
        identityNameTv.text = "NIS tidak ditemukan"
        identityClassTv.setTextColor(mutedColor)
        identityClassTv.text = "Pastikan NIS terdaftar di database siswa"
        identityStatusPill.visibility = View.GONE
        identityLockIcon.iconColor = dangerFg
        phoneBox.visibility = View.GONE
    }

    private fun renderDropdownAndIdentity() {
        nisTrailingBtn.text = when {
            dropdownExpanded -> "⌃"
            selectedStudent != null -> "✕"
            else -> "⌄"
        }
        updateNisBoxStroke(nisInput.hasFocus())

        if (dropdownExpanded && lastMatches.isNotEmpty()) {
            dropdownList.removeAllViews()
            dropdownCard.background = rounded(surfaceColor, 14f, borderColor)
            lastMatches.forEachIndexed { idx, stu ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { selectStudentFromDropdown(stu) }
                }
                val nisPill = TextView(activity).apply {
                    text = stu.nis
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(inkColor)
                    background = rounded(surfaceSoft, 7f)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { rightMargin = dp(12) }
                }
                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(activity).apply {
                    text = stu.fullName.uppercase()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(inkColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val subRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                }
                val clsTv = TextView(activity).apply {
                    text = stu.className
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(mutedColor)
                }
                val badge = TextView(activity).apply {
                    text = if (stu.registered) "Sudah Terdaftar" else "Siap Aktivasi"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (stu.registered) amberFg else greenFg)
                    background = rounded(if (stu.registered) amberBg else greenBg, 6f)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(8) }
                }
                subRow.addView(clsTv)
                subRow.addView(badge)
                textCol.addView(nameTv)
                textCol.addView(subRow)

                val arrow = TextView(activity).apply {
                    text = "↗"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(mutedColor)
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(nisPill)
                row.addView(textCol)
                row.addView(arrow)
                dropdownList.addView(row)

                if (idx < lastMatches.lastIndex) {
                    val div = View(activity).apply {
                        setBackgroundColor(dividerColor)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            leftMargin = dp(14)
                            rightMargin = dp(14)
                        }
                    }
                    dropdownList.addView(div)
                }
            }
            dropdownCard.visibility = View.VISIBLE
            identityCard.visibility = View.GONE
            phoneBox.visibility = View.GONE
            return
        }

        dropdownCard.visibility = View.GONE
        identityCard.visibility = View.VISIBLE

        val stu = selectedStudent
        if (stu != null && stu.found) {
            identityCard.background = rounded(surfaceColor, 14f, blueColor, 1.3f)
            identityNisPill.background = rounded(tintColor, 8f)
            identityNisPill.setTextColor(blueColor)
            identityNisPill.text = stu.nis
            identityNameTv.setTextColor(inkColor)
            identityNameTv.text = stu.fullName.uppercase()
            identityClassTv.setTextColor(mutedColor)
            identityClassTv.text = stu.className
            identityStatusPill.visibility = View.VISIBLE
            if (stu.registered) {
                identityStatusPill.text = "Sudah Terdaftar"
                identityStatusPill.setTextColor(amberFg)
                identityStatusPill.background = rounded(amberBg, 6f)
                phoneBox.visibility = View.GONE
                passwordInput.hint = "Masukkan kata sandi akunmu"
            } else {
                identityStatusPill.text = "Siap Aktivasi"
                identityStatusPill.setTextColor(greenFg)
                identityStatusPill.background = rounded(greenBg, 6f)
                phoneBox.visibility = View.VISIBLE
                passwordInput.hint = "Buat kata sandi baru (min. 6 karakter)"
            }
            identityLockIcon.iconColor = amberFg
        } else {
            identityCard.background = rounded(surfaceSoft, 14f, borderColor)
            identityNisPill.background = rounded(surfaceColor, 8f, borderColor)
            identityNisPill.setTextColor(mutedColor)
            identityNisPill.text = "NIS"
            identityNameTv.setTextColor(mutedColor)
            identityNameTv.text = "Nama Lengkap (Otomatis)"
            identityClassTv.setTextColor(mutedColor)
            identityClassTv.text = "Kelas otomatis terisi saat NIS dipilih"
            identityStatusPill.visibility = View.GONE
            identityLockIcon.iconColor = mutedColor
            phoneBox.visibility = View.GONE
            passwordInput.hint = "Kata Sandi"
        }
    }

    private fun updateCtaState() {
        val ready = when {
            busy -> false
            useEmailMode -> true
            else -> selectedStudent != null && selectedStudent?.found == true && termsCheck.isChecked
        }
        val bg = if (ready) blueColor else if (darkMode) Color.rgb(34, 46, 70) else Color.rgb(212, 222, 236)
        val fg = if (ready) Color.WHITE else mutedColor
        ctaContainer.background = rounded(bg, 26f)
        ctaBtn.setTextColor(fg)
        ctaProgress.visibility = if (busy) View.VISIBLE else View.GONE

        ctaBtn.text = when {
            busy && !useEmailMode && selectedStudent?.registered == false -> "Mengaktifkan..."
            busy -> "Memproses..."
            useEmailMode -> "Masuk dengan Email"
            selectedStudent != null && selectedStudent?.registered == true -> "Masuk ke Akun"
            selectedStudent != null && selectedStudent?.registered == false -> "Aktifkan Akun & Masuk"
            else -> "Aktifkan Akun & Masuk"
        }
    }

    private fun showError(msg: String) {
        errorTv.text = msg
        errorTv.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorTv.visibility = View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        nisInput.isEnabled = !value
        phoneInput.isEnabled = !value
        emailInput.isEnabled = !value
        passwordInput.isEnabled = !value
        termsCheck.isEnabled = !value
        switchModeBtn.isEnabled = !value
        updateCtaState()
    }

    private fun submit() {
        if (busy) return
        clearError()
        val passVal = passwordInput.text.toString()
        if (!SupabaseRepository.isConfigured()) {
            showError("Supabase belum dikonfigurasi.")
            return
        }

        if (useEmailMode) {
            val emailVal = emailInput.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailVal).matches()) {
                showError("Masukkan alamat email yang valid.")
                return
            }
            if (passVal.length < 6) {
                showError("Kata sandi minimal 6 karakter.")
                return
            }
            hideKeyboard()
            setBusy(true)
            activity.authScope.launch {
                try {
                    SupabaseRepository.login(emailVal, passVal)
                    setBusy(false)
                    activity.routeSignedInUser()
                } catch (e: Throwable) {
                    showError(SupabaseRepository.errorMessage(e))
                    setBusy(false)
                }
            }
            return
        }

        // NIS Auto-Fill Mode
        val digits = nisInput.text.toString().filter(Char::isDigit)
        if (digits.length < 3) {
            showError("Ketik dan pilih NIS kamu terlebih dahulu.")
            nisInput.requestFocus()
            return
        }
        if (!termsCheck.isChecked) {
            showError("Centang persetujuan ketentuan terlebih dahulu.")
            return
        }
        if (passVal.length < 6) {
            showError("Kata sandi minimal 6 karakter.")
            passwordInput.requestFocus()
            return
        }

        hideKeyboard()
        setBusy(true)
        activity.authScope.launch {
            try {
                val info = selectedStudent?.takeIf { it.nis == digits && it.found }
                    ?: SupabaseRepository.lookupStudentNis(digits)
                selectedStudent = info
                dropdownExpanded = false
                renderDropdownAndIdentity()
                if (!info.found) {
                    showNotFoundState(digits)
                    showError("NIS $digits tidak ditemukan di database siswa.")
                    setBusy(false)
                    return@launch
                }

                if (info.registered) {
                    SupabaseRepository.login(info.authEmail, passVal)
                } else {
                    val phoneVal = phoneInput.text.toString().trim()
                    if (phoneVal.isNotBlank() && PhoneRepository.normalizePhone(phoneVal) == null) {
                        showError("Nomor WhatsApp tidak valid (gunakan format 08... atau +62...).")
                        setBusy(false)
                        return@launch
                    }
                    SupabaseRepository.registerWithNis(info.nis, passVal, phoneVal)
                }
                passwordInput.setText("")
                setBusy(false)
                activity.routeSignedInUser()
            } catch (e: Throwable) {
                showError(SupabaseRepository.errorMessage(e))
                setBusy(false)
            }
        }
    }

    /** Ikon gembok vektor bersih (tanpa emoji sistem). */
    private class LockVectorView(context: Context) : View(context) {
        var iconColor: Int = Color.GRAY
            set(value) {
                field = value
                paint.color = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = iconColor
        }
        private val shackleRect = RectF()
        private val bodyRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val s = minOf(w, h)
            paint.strokeWidth = (s * 0.105f).coerceAtLeast(1.8f)

            val shackleW = s * 0.40f
            val shackleLeft = (w - shackleW) / 2f
            val shackleTop = h * 0.14f
            val shackleBottom = h * 0.56f
            shackleRect.set(shackleLeft, shackleTop, shackleLeft + shackleW, shackleBottom)
            canvas.drawArc(shackleRect, 180f, 180f, false, paint)

            val bodyW = s * 0.64f
            val bodyH = s * 0.42f
            val bodyLeft = (w - bodyW) / 2f
            val bodyTop = h * 0.44f
            bodyRect.set(bodyLeft, bodyTop, bodyLeft + bodyW, bodyTop + bodyH)
            canvas.drawRoundRect(bodyRect, s * 0.10f, s * 0.10f, paint)
        }
    }
}
