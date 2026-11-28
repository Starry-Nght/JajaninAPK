package com.jajanin.app.feature.seller.menu

import android.content.Context
import android.graphics.Color
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.ResponsiveLayout
import com.jajanin.app.core.ui.adaptiveHorizontalPaddingPx
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.DEFAULT_PICKUP_SESSIONS
import com.jajanin.app.data.model.MAX_PRODUCT_IMAGES
import com.jajanin.app.data.model.ProductVariantSection
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.formatCalendarShortChip
import com.jajanin.app.data.repository.formatShortIdDate
import com.jajanin.app.feature.seller.confirmDeleteProduct
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.pickProductImage
import com.jajanin.app.feature.seller.saveProductForm
import com.jajanin.app.feature.seller.showSellerProductPoDatePicker

/**
 * Full-screen native Android form overlay for `Screen.PRODUCT_FORM` (Tambah / Ubah Produk).
 *
 * Uses real Android `EditText` widgets inside a native `ScrollView` so sellers type
 * directly inside every field (Nama, Deskripsi, Bahan, Nama & Pilihan Varian, Kelas/Ruangan,
 * Kuota PO, Harga, Stok) with zero input pop-up dialogs.
 */
internal class NativeProductFormOverlay(
    private val activity: MainActivity,
    private val parent: FrameLayout,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private var darkMode = true
    private var suppressWatchers = false
    private var baseLeftInset = 0
    private var baseTopInset = 0
    private var baseRightInset = 0
    private var baseBottomInset = 0
    private var baseImeBottom = 0

    // Palette
    private val bgColor get() = if (darkMode) Color.rgb(11, 15, 25) else Color.rgb(248, 250, 253)
    private val cardColor get() = if (darkMode) Color.rgb(20, 27, 42) else Color.WHITE
    private val inkColor get() = if (darkMode) Color.rgb(241, 245, 252) else Color.rgb(20, 27, 42)
    private val mutedColor get() = if (darkMode) Color.rgb(142, 154, 176) else Color.rgb(104, 114, 134)
    private val borderColor get() = if (darkMode) Color.rgb(42, 54, 78) else Color.rgb(220, 227, 238)
    private val dividerColor get() = if (darkMode) Color.rgb(34, 45, 66) else Color.rgb(232, 237, 245)
    private val blueColor get() = if (darkMode) Color.rgb(66, 153, 255) else Color.rgb(0, 99, 214)
    private val tintColor get() = if (darkMode) Color.rgb(22, 42, 74) else Color.rgb(232, 242, 255)
    private val warnBgColor get() = if (darkMode) Color.rgb(48, 36, 16) else Color.rgb(255, 248, 232)
    private val warnAccent get() = if (darkMode) Color.rgb(245, 178, 56) else Color.rgb(183, 116, 0)
    private val successColor get() = if (darkMode) Color.rgb(74, 222, 128) else Color.rgb(31, 158, 95)
    private val dangerColor get() = if (darkMode) Color.rgb(248, 113, 113) else Color.rgb(206, 44, 44)
    private val dangerBorderColor get() = if (darkMode) Color.rgb(110, 40, 44) else Color.rgb(246, 198, 198)

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fill)
            if (stroke != null) setStroke(dpF(strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    // Root views
    private val rootContainer: LinearLayout
    private val topBar: LinearLayout
    private val topDivider: View
    private val backBtn: ImageView
    private val titleTv: TextView
    private val scrollView: ScrollView
    private val contentCol: LinearLayout
    private val bottomBar: LinearLayout
    private val bottomDivider: View
    private val errorTv: TextView
    private val saveBtn: TextView

    // Section 1: Info
    private val secInfoHeader: TextView
    private val nameLabel: TextView
    private val nameInput: EditText
    private val catLabel: TextView
    private val catFoodBtn: TextView
    private val catDrinkBtn: TextView
    private val descLabel: TextView
    private val descInput: EditText
    private val ingLabel: TextView
    private val ingInput: EditText

    // Section 2: Variants (inline, zero popups)
    private val varHeaderTv: TextView
    private val varTopAddBtn: TextView
    private val varHintTv: TextView
    private val varListContainer: LinearLayout
    private val varBottomAddBtn: TextView
    private val pendingOptInputs = mutableListOf<Pair<Int, EditText>>()

    // Section 3: Pickup Room
    private val secRoomTitle: TextView
    private val secRoomTrailing: TextView
    private val roomLabel: TextView
    private val roomInput: EditText
    private val roomQuickLabel: TextView
    private val roomQuickWrap: FlowWrapLayout

    // Section 4: Schedule & PO
    private val secSchedTitle: TextView
    private val secSchedTrailing: TextView
    private val schedSubLabel: TextView
    private val schedSameBtn: TextView
    private val schedPoBtn: TextView
    private val schedBothBtn: TextView
    private val poContainer: LinearLayout
    private val poDateHeaderTv: TextView
    private val poCalCard: LinearLayout
    private val poCalTitleTv: TextView
    private val poCalSubTv: TextView
    private val poCalBtn: TextView
    private val poQuickRow: LinearLayout
    private val poSelectedChipsWrap: FlowWrapLayout
    private val poCutoffLabel: TextView
    private val poCutoffRow: LinearLayout
    private val poQuotaCard: LinearLayout
    private val poQuotaLabel: TextView
    private val poQuotaInput: EditText
    private val poQrisRow: LinearLayout
    private val poQrisTitle: TextView
    private val poQrisSub: TextView
    private val poQrisSwitch: PillSwitchView
    private val sessLabel: TextView
    private val sessRow: LinearLayout

    // Section 5: Price & Stock
    private val secPriceTitle: TextView
    private val priceLabel: TextView
    private val priceInput: EditText
    private val priceHintTv: TextView
    private val stockLabel: TextView
    private val stockInput: EditText
    private val stockHintTv: TextView

    // Section 6: Multi-Photo Gallery (max 5 photos)
    private val secPhotoTitle: TextView
    private val photoCountTv: TextView
    private val photoHintTv: TextView
    private val photoCard: LinearLayout
    private val photoGridWrap: FlowWrapLayout
    private val photoFooterRow: LinearLayout
    private val photoProgress: ProgressBar
    private val photoStatusTv: TextView

    // Section 7: Visibility
    private val secVisTitle: TextView
    private val visCard: LinearLayout
    private val visTitleTv: TextView
    private val visSubTv: TextView
    private val visSwitch: PillSwitchView

    // Section 8: Delete (edit mode only)
    private val deleteCard: LinearLayout
    private val deleteIcon: ImageView
    private val deleteTitleTv: TextView
    private val deleteSubTv: TextView

    private val allInputs = mutableListOf<EditText>()
    private val sectionHeaders = mutableListOf<TextView>()
    private val fieldLabels = mutableListOf<TextView>()

    init {
        rootContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // --- Sticky Top Header ---
        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        backBtn = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = AppIcon.contentDescription("chevronLeft")
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                hideKeyboard()
                activity.ui.goBack()
            }
        }
        titleTv = TextView(activity).apply {
            text = "Tambah Produk"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        }
        topBar.addView(backBtn)
        topBar.addView(titleTv)
        rootContainer.addView(topBar)

        topDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
        }
        rootContainer.addView(topDivider)

        // --- Main Native ScrollView ---
        scrollView = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // 1. INFORMASI PRODUK
        secInfoHeader = makeSectionHeader("INFORMASI PRODUK")
        contentCol.addView(secInfoHeader)

        nameLabel = makeFieldLabel("Nama produk")
        contentCol.addView(nameLabel)
        nameInput = makeInput(
            hint = "cth. Nasi goreng",
            maxLen = 60,
            multiline = false,
            numeric = false,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            activity.ui.productName = value
            clearError()
        }
        contentCol.addView(nameInput)

        catLabel = makeFieldLabel("Kategori", topMarginDp = 12)
        contentCol.addView(catLabel)
        val catRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(6)
            }
        }
        catFoodBtn = makePillButton("Makanan") {
            activity.ui.productCategory = "Food"
            refreshCategoryUi()
        }
        catDrinkBtn = makePillButton("Minuman") {
            activity.ui.productCategory = "Drinks"
            refreshCategoryUi()
        }
        catRow.addView(catFoodBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(4) })
        catRow.addView(catDrinkBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
        contentCol.addView(catRow)

        descLabel = makeFieldLabel("Deskripsi", topMarginDp = 12)
        contentCol.addView(descLabel)
        descInput = makeInput(
            hint = "Tulis deskripsi singkat produk",
            maxLen = 200,
            multiline = true,
            numeric = false,
            imeAction = EditorInfo.IME_ACTION_NONE,
        ) { value ->
            activity.ui.productDescription = value
            clearError()
        }
        contentCol.addView(descInput)

        ingLabel = makeFieldLabel("Bahan (opsional)", topMarginDp = 12)
        contentCol.addView(ingLabel)
        ingInput = makeInput(
            hint = "Opsional — bahan utama",
            maxLen = 120,
            multiline = false,
            numeric = false,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            activity.ui.productIngredients = value
        }
        contentCol.addView(ingInput)

        // 2. PILIHAN VARIAN / RASA (100% INLINE)
        val varHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            }
        }
        varHeaderTv = TextView(activity).apply {
            text = "Pilihan Varian / Rasa"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        varTopAddBtn = TextView(activity).apply {
            text = "+ Tambah Section"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { addNewVariantSectionInline() }
        }
        varHeaderRow.addView(varHeaderTv)
        varHeaderRow.addView(varTopAddBtn)
        contentCol.addView(varHeaderRow)

        varHintTv = TextView(activity).apply {
            text = "Opsional · Nama section & pilihannya langsung bisa diketik di sini."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }
        contentCol.addView(varHintTv)

        varListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        contentCol.addView(varListContainer)

        varBottomAddBtn = TextView(activity).apply {
            text = "+ Tambah Section Baru"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(4)
            }
            setOnClickListener { addNewVariantSectionInline() }
        }
        contentCol.addView(varBottomAddBtn)

        // 3. AMBIL DI KELAS / RUANGAN
        val roomHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
            }
        }
        secRoomTitle = makeSectionHeader("AMBIL DI KELAS / RUANGAN", topMarginDp = 0)
        secRoomTrailing = TextView(activity).apply {
            text = "Tampil di pesanan pembeli"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        roomHeaderRow.addView(secRoomTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        roomHeaderRow.addView(secRoomTrailing)
        contentCol.addView(roomHeaderRow)

        roomLabel = makeFieldLabel("Kelas / ruangan tempat pembeli mengambil pesanan (wajib)", topMarginDp = 8)
        contentCol.addView(roomLabel)
        roomInput = makeInput(
            hint = "cth. Kelas XII RPL 1 / Kantin Utama",
            maxLen = 45,
            multiline = false,
            numeric = false,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            activity.ui.productRoom = value
            clearError()
            refreshRoomQuickChips()
        }
        contentCol.addView(roomInput)

        roomQuickLabel = makeFieldLabel("Pilih cepat kelas / ruangan:", topMarginDp = 10)
        contentCol.addView(roomQuickLabel)
        roomQuickWrap = FlowWrapLayout(activity, dp(6), dp(6)).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        contentCol.addView(roomQuickWrap)

        // 4. JADWAL & SESI PENGAMBILAN
        val schedHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
            }
        }
        secSchedTitle = makeSectionHeader("JADWAL & SESI PENGAMBILAN", topMarginDp = 0)
        secSchedTrailing = TextView(activity).apply {
            text = "Hari Ini / Pre-Order"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        schedHeaderRow.addView(secSchedTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        schedHeaderRow.addView(secSchedTrailing)
        contentCol.addView(schedHeaderRow)

        schedSubLabel = makeFieldLabel("Tersedia untuk", topMarginDp = 8)
        contentCol.addView(schedSubLabel)
        val schedBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(6)
            }
        }
        schedSameBtn = makePillButton("Hari Ini") {
            activity.ui.productOrderSchedule = "same_day"
            refreshScheduleUi()
        }
        schedPoBtn = makePillButton("Pre-Order") {
            activity.ui.productOrderSchedule = "next_day"
            refreshScheduleUi()
        }
        schedBothBtn = makePillButton("Keduanya") {
            activity.ui.productOrderSchedule = "both"
            refreshScheduleUi()
        }
        schedBtnRow.addView(schedSameBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(4) })
        schedBtnRow.addView(schedPoBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        schedBtnRow.addView(schedBothBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
        contentCol.addView(schedBtnRow)

        // Conditional PO Section
        poContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        poDateHeaderTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        poContainer.addView(poDateHeaderTv)

        poCalCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                hideKeyboard()
                activity.showSellerProductPoDatePicker()
            }
        }
        val poCalTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        poCalTitleTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        poCalSubTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        poCalTextCol.addView(poCalTitleTv)
        poCalTextCol.addView(poCalSubTv)
        poCalBtn = TextView(activity).apply {
            text = "+ Kalender"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        poCalCard.addView(poCalTextCol)
        poCalCard.addView(poCalBtn)
        poContainer.addView(poCalCard)

        poQuickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        poContainer.addView(poQuickRow)

        poSelectedChipsWrap = FlowWrapLayout(activity, dp(6), dp(6)).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        poContainer.addView(poSelectedChipsWrap)

        poCutoffLabel = makeFieldLabel("Batas tutup PO (H-1 sebelum hari ambil)", topMarginDp = 12)
        poContainer.addView(poCutoffLabel)
        poCutoffRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
                topMargin = dp(6)
            }
        }
        poContainer.addView(poCutoffRow)

        // Inline PO Daily Quota numeric input (ZERO popup!)
        poQuotaCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        poQuotaLabel = makeFieldLabel("Kuota PO per tanggal (opsional)", topMarginDp = 0)
        poQuotaInput = makeInput(
            hint = "Kosongkan = ikut stok produk (cth. 15)",
            maxLen = 3,
            multiline = false,
            numeric = true,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            activity.ui.productPoDailyQuota = value.filter(Char::isDigit)
        }
        poQuotaCard.addView(poQuotaLabel)
        poQuotaCard.addView(poQuotaInput)
        poContainer.addView(poQuotaCard)

        // Mandatory QRIS toggle row
        poQrisRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            setOnClickListener {
                activity.ui.productPoRequireQris = !activity.ui.productPoRequireQris
                refreshScheduleUi()
            }
        }
        val poQrisCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        poQrisTitle = TextView(activity).apply {
            text = "Wajib Midtrans untuk semua PO"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        poQrisSub = TextView(activity).apply {
            text = "PO > 3 hari otomatis wajib Midtrans · aktifkan agar PO 1–3 hari juga wajib."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }
        poQrisCol.addView(poQrisTitle)
        poQrisCol.addView(poQrisSub)
        poQrisSwitch = PillSwitchView(activity)
        poQrisRow.addView(poQrisCol)
        poQrisRow.addView(poQrisSwitch, LinearLayout.LayoutParams(dp(44), dp(26)))
        poContainer.addView(poQrisRow)

        contentCol.addView(poContainer)

        sessLabel = makeFieldLabel("Sesi pengambilan (bisa pilih lebih dari 1)", topMarginDp = 12)
        contentCol.addView(sessLabel)
        sessRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(6)
            }
        }
        contentCol.addView(sessRow)

        // 5. HARGA & STOK (side-by-side real numeric EditTexts)
        secPriceTitle = makeSectionHeader("HARGA & STOK", topMarginDp = 22)
        contentCol.addView(secPriceTitle)

        val priceStockRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val priceCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(5)
            }
        }
        priceLabel = makeFieldLabel("Harga (Rp)", topMarginDp = 0)
        priceInput = makeInput(
            hint = "cth. 12000",
            maxLen = 9,
            multiline = false,
            numeric = true,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            val digits = value.filter(Char::isDigit)
            activity.ui.productPrice = digits
            clearError()
            refreshPriceStockHints()
        }
        priceHintTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        priceCol.addView(priceLabel)
        priceCol.addView(priceInput)
        priceCol.addView(priceHintTv)

        val stockCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(5)
            }
        }
        stockLabel = makeFieldLabel("Stok (Wajib 1–100)", topMarginDp = 0)
        stockInput = makeInput(
            hint = "1–100",
            maxLen = 3,
            multiline = false,
            numeric = true,
            imeAction = EditorInfo.IME_ACTION_DONE,
        ) { value ->
            val digits = value.filter(Char::isDigit)
            activity.ui.productStock = digits
            clearError()
            refreshPriceStockHints()
        }
        stockHintTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        stockCol.addView(stockLabel)
        stockCol.addView(stockInput)
        stockCol.addView(stockHintTv)

        priceStockRow.addView(priceCol)
        priceStockRow.addView(stockCol)
        contentCol.addView(priceStockRow)

        // 6. FOTO PRODUK (Multi-foto maks. 5)
        val photoHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            }
        }
        secPhotoTitle = makeSectionHeader("FOTO PRODUK", topMarginDp = 0)
        photoCountTv = TextView(activity).apply {
            text = "0/$MAX_PRODUCT_IMAGES foto"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        photoHeaderRow.addView(secPhotoTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        photoHeaderRow.addView(photoCountTv)
        contentCol.addView(photoHeaderRow)

        photoHintTv = TextView(activity).apply {
            text = "Upload hingga $MAX_PRODUCT_IMAGES foto makanan (JPG/PNG maks 5MB). Foto pertama jadi sampul utama; ketuk foto lain untuk jadikan utama."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        }
        contentCol.addView(photoHintTv)

        photoCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        photoGridWrap = FlowWrapLayout(activity, dp(8), dp(8)).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        photoFooterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        photoProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) }
        }
        photoStatusTv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        photoFooterRow.addView(photoProgress)
        photoFooterRow.addView(photoStatusTv)
        photoCard.addView(photoGridWrap)
        photoCard.addView(photoFooterRow)
        contentCol.addView(photoCard)

        // 7. VISIBILITAS
        secVisTitle = makeSectionHeader("VISIBILITAS", topMarginDp = 20)
        contentCol.addView(secVisTitle)

        visCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                activity.ui.productAvailable = !activity.ui.productAvailable
                refreshVisibilityUi()
            }
        }
        val visCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        visTitleTv = TextView(activity).apply {
            text = "Tampil di etalase"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        visSubTv = TextView(activity).apply {
            text = "Pembeli bisa melihat dan memesan produk ini"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        }
        visCol.addView(visTitleTv)
        visCol.addView(visSubTv)
        visSwitch = PillSwitchView(activity)
        visCard.addView(visCol)
        visCard.addView(visSwitch, LinearLayout.LayoutParams(dp(44), dp(26)))
        contentCol.addView(visCard)

        // 8. HAPUS PRODUK (Edit mode only)
        deleteCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            }
            setOnClickListener {
                val id = activity.ui.productEditId ?: return@setOnClickListener
                if (!activity.ui.productSaving && !activity.ui.productImageUploading) {
                    hideKeyboard()
                    activity.confirmDeleteProduct(id)
                }
            }
        }
        deleteIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) }
        }
        val deleteCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        deleteTitleTv = TextView(activity).apply {
            text = "Hapus produk"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        deleteSubTv = TextView(activity).apply {
            text = "Tindakan ini tidak bisa dibatalkan"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }
        deleteCol.addView(deleteTitleTv)
        deleteCol.addView(deleteSubTv)
        deleteCard.addView(deleteIcon)
        deleteCard.addView(deleteCol)
        contentCol.addView(deleteCard)

        scrollView.addView(contentCol)
        rootContainer.addView(scrollView)

        // --- Sticky Bottom CTA Bar ---
        bottomDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
        }
        rootContainer.addView(bottomDivider)

        bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        errorTv = TextView(activity).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        saveBtn = TextView(activity).apply {
            text = "Simpan Produk"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                flushPendingVariantInputs()
                hideKeyboard()
                activity.saveProductForm()
            }
        }
        bottomBar.addView(errorTv)
        bottomBar.addView(saveBtn)
        rootContainer.addView(bottomBar)

        parent.addView(rootContainer)

        // Edge-to-Edge WindowInsets (status bar + nav bar + display cutout + IME keyboard)
        rootContainer.setOnApplyWindowInsetsListener { _, insets ->
            val safe = ResponsiveLayout.extractSafeInsets(insets)
            baseLeftInset = safe.left
            baseTopInset = safe.top
            baseRightInset = safe.right
            baseBottomInset = safe.bottom
            baseImeBottom = safe.imeBottom
            applyInsetPadding()
            insets
        }
        rootContainer.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if ((r - l) != (or - ol)) {
                rootContainer.post { applyInsetPadding() }
            }
        }

        applyTheme(activity.ui.darkMode)
    }

    private fun applyInsetPadding() {
        val availW = (rootContainer.width - baseLeftInset - baseRightInset)
            .takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val curDensity = activity.resources.displayMetrics.density
        val sidePad = adaptiveHorizontalPaddingPx(
            availableWidthPx = availW,
            density = curDensity,
            basePadDp = 18f,
            maxContentWidthDp = 560f,
        )
        val topSidePad = adaptiveHorizontalPaddingPx(
            availableWidthPx = availW,
            density = curDensity,
            basePadDp = 12f,
            maxContentWidthDp = 580f,
        )
        val effectiveBottom = maxOf(baseBottomInset, baseImeBottom)
        topBar.setPadding(
            baseLeftInset + topSidePad,
            dp(8) + baseTopInset,
            baseRightInset + topSidePad,
            dp(8),
        )
        contentCol.setPadding(
            baseLeftInset + sidePad,
            dp(14),
            baseRightInset + sidePad,
            dp(24),
        )
        bottomBar.setPadding(
            baseLeftInset + sidePad,
            dp(10),
            baseRightInset + sidePad,
            dp(12) + effectiveBottom,
        )
    }

    fun onConfigChanged() {
        rootContainer.requestApplyInsets()
        rootContainer.post { applyInsetPadding() }
    }

    fun setVisible(visible: Boolean) {
        if (visible) {
            applyTheme(activity.ui.darkMode)
            populateFromState()
            rootContainer.visibility = View.VISIBLE
            rootContainer.requestApplyInsets()
            scrollView.post { scrollView.scrollTo(0, 0) }
        } else {
            if (rootContainer.visibility == View.VISIBLE) {
                hideKeyboard()
                rootContainer.visibility = View.GONE
            }
        }
    }

    fun isVisible(): Boolean = rootContainer.visibility == View.VISIBLE

    fun handleBack(): Boolean {
        hideKeyboard()
        return false
    }

    fun applyTheme(dark: Boolean) {
        darkMode = dark
        rootContainer.setBackgroundColor(bgColor)
        topBar.setBackgroundColor(bgColor)
        topDivider.setBackgroundColor(dividerColor)
        bottomBar.setBackgroundColor(bgColor)
        bottomDivider.setBackgroundColor(dividerColor)

        backBtn.setImageDrawable(AppIcon.drawable(activity, "chevronLeft", 22f, blueColor))
        titleTv.setTextColor(inkColor)

        sectionHeaders.forEach { it.setTextColor(mutedColor) }
        fieldLabels.forEach { it.setTextColor(mutedColor) }

        allInputs.forEach { styleEditText(it, it.hasFocus()) }

        varHeaderTv.setTextColor(inkColor)
        varTopAddBtn.background = rounded(tintColor, 7f, blueColor)
        varTopAddBtn.setTextColor(blueColor)
        varHintTv.setTextColor(mutedColor)
        varBottomAddBtn.background = rounded(cardColor, 10f, blueColor)
        varBottomAddBtn.setTextColor(blueColor)

        secRoomTrailing.setTextColor(blueColor)
        secSchedTrailing.setTextColor(blueColor)

        poQrisTitle.setTextColor(inkColor)
        poQrisSub.setTextColor(mutedColor)
        poQrisSwitch.applyPalette(blueColor, borderColor)

        photoCard.background = rounded(cardColor, 12f, borderColor)
        photoCountTv.setTextColor(blueColor)
        photoHintTv.setTextColor(mutedColor)

        visCard.background = rounded(cardColor, 12f, borderColor)
        visTitleTv.setTextColor(inkColor)
        visSubTv.setTextColor(mutedColor)
        visSwitch.applyPalette(blueColor, borderColor)

        deleteCard.background = rounded(cardColor, 10f, dangerBorderColor)
        deleteIcon.setImageDrawable(AppIcon.drawable(activity, "close", 16f, dangerColor))
        deleteTitleTv.setTextColor(dangerColor)
        deleteSubTv.setTextColor(mutedColor)

        errorTv.setTextColor(dangerColor)

        refreshCategoryUi()
        refreshRoomQuickChips()
        refreshScheduleUi()
        refreshPriceStockHints()
        refreshPhotoUi()
        refreshVisibilityUi()
        refreshSaveCtaUi()
        rebuildVariantCards(focusSectionIndex = null)
    }

    /** Full bind from `JajaninView` state when opening Tambah / Ubah Produk. */
    private fun populateFromState() {
        val ui = activity.ui
        val editing = ui.productEditId != null
        titleTv.text = if (editing) "Ubah Produk" else "Tambah Produk"
        deleteCard.visibility = if (editing) View.VISIBLE else View.GONE

        suppressWatchers = true
        setTextIfChanged(nameInput, ui.productName)
        setTextIfChanged(descInput, ui.productDescription)
        setTextIfChanged(ingInput, ui.productIngredients)
        setTextIfChanged(roomInput, ui.productRoom)
        setTextIfChanged(poQuotaInput, ui.productPoDailyQuota)
        setTextIfChanged(priceInput, ui.productPrice)
        setTextIfChanged(stockInput, ui.productStock)
        suppressWatchers = false

        refreshCategoryUi()
        rebuildVariantCards(focusSectionIndex = null)
        refreshRoomQuickChips()
        refreshScheduleUi()
        refreshPriceStockHints()
        refreshPhotoUi()
        refreshVisibilityUi()
        refreshSaveCtaUi()
    }

    /** Lightweight refresh for external/async changes (photo upload, PO calendar date pick, save state, error). */
    fun syncAsyncState() {
        if (rootContainer.visibility != View.VISIBLE) return
        refreshScheduleUi()
        refreshPhotoUi()
        refreshSaveCtaUi()
    }

    // ================= SECTION BUILDERS & REFRESHERS =================

    private fun makeSectionHeader(label: String, topMarginDp: Int = 0): TextView {
        return TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (topMarginDp > 0) topMargin = dp(topMarginDp)
                bottomMargin = dp(6)
            }
        }.also { sectionHeaders.add(it) }
    }

    private fun makeFieldLabel(label: String, topMarginDp: Int = 4): TextView {
        return TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (topMarginDp > 0) topMargin = dp(topMarginDp)
            }
        }.also { fieldLabels.add(it) }
    }

    private fun makeInput(
        hint: String,
        maxLen: Int,
        multiline: Boolean,
        numeric: Boolean,
        imeAction: Int,
        trackInList: Boolean = true,
        onChanged: (String) -> Unit,
    ): EditText {
        val et = EditText(activity).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            if (multiline) {
                isSingleLine = false
                minLines = 3
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                inputType = if (numeric) {
                    InputType.TYPE_CLASS_NUMBER
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                if (imeAction != EditorInfo.IME_ACTION_NONE) {
                    imeOptions = imeAction
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (multiline) ViewGroup.LayoutParams.WRAP_CONTENT else dp(46),
            ).apply {
                topMargin = dp(6)
            }
            setOnFocusChangeListener { v, hasFocus ->
                styleEditText(v as EditText, hasFocus)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatchers) return
                    onChanged(s?.toString().orEmpty())
                }
            })
        }
        if (trackInList) allInputs.add(et)
        styleEditText(et, false)
        return et
    }

    private fun styleEditText(et: EditText, focused: Boolean, forceError: Boolean = false) {
        val stroke = when {
            forceError -> dangerColor
            focused -> blueColor
            else -> borderColor
        }
        et.background = rounded(cardColor, 10f, stroke, if (focused) 1.5f else 1f)
        et.setTextColor(inkColor)
        et.setHintTextColor(mutedColor)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                et.textCursorDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setSize(dp(2).coerceAtLeast(2), dp(18))
                    setColor(blueColor)
                }
            }
        }
    }

    private fun makePillButton(label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun refreshCategoryUi() {
        val food = activity.ui.productCategory == "Food"
        styleSegment(catFoodBtn, food)
        styleSegment(catDrinkBtn, !food)
    }

    private fun styleSegment(btn: TextView, active: Boolean, warn: Boolean = false) {
        val accent = if (warn) warnAccent else blueColor
        val fill = when {
            !active -> cardColor
            warn -> warnBgColor
            else -> tintColor
        }
        btn.background = rounded(fill, 10f, if (active) accent else borderColor)
        btn.setTextColor(if (active) accent else mutedColor)
        btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // --- Inline Variant Sections (ZERO popups) ---

    private fun addNewVariantSectionInline() {
        val list = activity.ui.productVariantSections
        if (list.size >= 5) {
            Toast.makeText(activity, "Maksimal 5 section varian per produk.", Toast.LENGTH_SHORT).show()
            return
        }
        flushPendingVariantInputs()
        val nextName = if (list.isEmpty()) "Rasa" else ""
        list.add(ProductVariantSection(nextName, emptyList()))
        val newIdx = list.lastIndex
        rebuildVariantCards(focusSectionIndex = newIdx)
    }

    private fun rebuildVariantCards(focusSectionIndex: Int?) {
        varListContainer.removeAllViews()
        pendingOptInputs.clear()
        val sections = activity.ui.productVariantSections

        sections.forEachIndexed { secIdx, sec ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cardColor, 12f, borderColor)
                setPadding(dp(12), dp(10), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }

            // Top row: Inline editable section name + Hapus button
            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val secNameEt = EditText(activity).apply {
                hint = "Nama section (cth. Rasa, Topping)"
                setText(sec.name)
                setSelection(sec.name.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true
                maxLines = 1
                filters = arrayOf(InputFilter.LengthFilter(40))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setOnFocusChangeListener { v, hasFocus -> styleEditText(v as EditText, hasFocus) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (suppressWatchers) return
                        val clean = s?.toString().orEmpty().replace("||", " ")
                        val cur = activity.ui.productVariantSections.getOrNull(secIdx) ?: return
                        activity.ui.productVariantSections[secIdx] = cur.copy(name = clean)
                    }
                })
            }
            styleEditText(secNameEt, false)

            val removeSecBtn = TextView(activity).apply {
                text = "Hapus"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(dangerColor)
                background = rounded(bgColor, 7f, dangerBorderColor)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    if (secIdx in activity.ui.productVariantSections.indices) {
                        flushPendingVariantInputs()
                        activity.ui.productVariantSections.removeAt(secIdx)
                        rebuildVariantCards(focusSectionIndex = null)
                    }
                }
            }
            topRow.addView(secNameEt, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
            topRow.addView(removeSecBtn)
            card.addView(topRow)

            // Chips container (removable option tags)
            val chipsWrap = FlowWrapLayout(activity, dp(6), dp(6)).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            }
            val emptyChipsTv = TextView(activity).apply {
                text = "Belum ada pilihan. Ketik di bawah lalu tekan Tambah."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(mutedColor)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            }
            fun populateChips() {
                chipsWrap.removeAllViews()
                val curOpts = activity.ui.productVariantSections.getOrNull(secIdx)?.options.orEmpty()
                if (curOpts.isEmpty()) {
                    chipsWrap.visibility = View.GONE
                    emptyChipsTv.visibility = View.VISIBLE
                } else {
                    chipsWrap.visibility = View.VISIBLE
                    emptyChipsTv.visibility = View.GONE
                    curOpts.forEachIndexed { optIdx, opt ->
                        val chip = TextView(activity).apply {
                            text = "$opt  ×"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(blueColor)
                            background = rounded(tintColor, 7f, blueColor)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                            setOnClickListener {
                                val cur = activity.ui.productVariantSections.getOrNull(secIdx) ?: return@setOnClickListener
                                if (optIdx in cur.options.indices) {
                                    val next = cur.options.toMutableList().apply { removeAt(optIdx) }
                                    activity.ui.productVariantSections[secIdx] = cur.copy(options = next)
                                    populateChips()
                                }
                            }
                        }
                        chipsWrap.addView(chip)
                    }
                }
            }
            populateChips()
            card.addView(emptyChipsTv)
            card.addView(chipsWrap)

            // Bottom inline add-option row (EditText + Tambah button — ZERO popup!)
            val addOptRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            }
            val optInput = EditText(activity).apply {
                hint = "Tambah pilihan, pisah koma (cth. Cokelat, Matcha)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                isSingleLine = true
                maxLines = 1
                filters = arrayOf(InputFilter.LengthFilter(180))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnFocusChangeListener { v, hasFocus -> styleEditText(v as EditText, hasFocus) }
            }
            styleEditText(optInput, false)
            pendingOptInputs.add(secIdx to optInput)

            fun commitTypedOptions() {
                val raw = optInput.text?.toString().orEmpty()
                val added = commitOptionsToSection(secIdx, raw)
                if (added > 0) {
                    optInput.setText("")
                    populateChips()
                }
            }
            optInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                    commitTypedOptions()
                    true
                } else false
            }

            val addOptBtn = TextView(activity).apply {
                text = "+ Tambah"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(blueColor, 8f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { commitTypedOptions() }
            }
            addOptRow.addView(optInput, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
            addOptRow.addView(addOptBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
            card.addView(addOptRow)

            varListContainer.addView(card)

            if (focusSectionIndex == secIdx) {
                secNameEt.post {
                    secNameEt.requestFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(secNameEt, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun commitOptionsToSection(secIdx: Int, raw: String): Int {
        val cur = activity.ui.productVariantSections.getOrNull(secIdx) ?: return 0
        val parsed = raw.split(',', '\n')
            .map { it.trim().replace("||", " ").take(40) }
            .filter { it.isNotBlank() }
        if (parsed.isEmpty()) return 0
        val merged = cur.options.toMutableList()
        var added = 0
        for (opt in parsed) {
            if (merged.size >= 12) break
            if (merged.none { it.equals(opt, ignoreCase = true) }) {
                merged.add(opt)
                added++
            }
        }
        if (added > 0) {
            activity.ui.productVariantSections[secIdx] = cur.copy(options = merged)
        }
        return added
    }

    private fun flushPendingVariantInputs() {
        pendingOptInputs.forEach { (secIdx, et) ->
            val txt = et.text?.toString().orEmpty()
            if (txt.isNotBlank()) {
                commitOptionsToSection(secIdx, txt)
                et.setText("")
            }
        }
    }

    // --- Quick Room Chips ---

    private fun refreshRoomQuickChips() {
        val ui = activity.ui
        val recentRooms = buildList {
            addAll(ui.recentSellerRooms)
            if (ui.myStallRoom.isNotBlank()) add(ui.myStallRoom.trim())
            if (ui.myClass.isNotBlank()) add("Kelas ${ui.myClass.trim()}")
            ui.sellerProducts.forEach { prod ->
                val r = prod.pickupRoom.trim()
                if (r.isNotBlank()) add(r)
            }
        }.distinctBy { it.lowercase() }.take(4)

        roomQuickWrap.removeAllViews()
        if (recentRooms.isEmpty()) {
            roomQuickLabel.visibility = View.GONE
            roomQuickWrap.visibility = View.GONE
            return
        }
        roomQuickLabel.visibility = View.VISIBLE
        roomQuickWrap.visibility = View.VISIBLE
        recentRooms.forEach { room ->
            val active = ui.productRoom.equals(room, ignoreCase = true)
            val chip = TextView(activity).apply {
                text = room
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else inkColor)
                background = rounded(if (active) blueColor else cardColor, 7f, if (active) null else borderColor)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                setOnClickListener {
                    ui.productRoom = room
                    suppressWatchers = true
                    roomInput.setText(room)
                    roomInput.setSelection(room.length)
                    suppressWatchers = false
                    clearError()
                    refreshRoomQuickChips()
                }
            }
            roomQuickWrap.addView(chip)
        }
    }

    // --- Schedule, PO Calendar & Pickup Sessions ---

    private fun refreshScheduleUi() {
        val ui = activity.ui
        val sched = ui.productOrderSchedule
        styleSegment(schedSameBtn, sched == "same_day", warn = false)
        styleSegment(schedPoBtn, sched == "next_day", warn = true)
        styleSegment(schedBothBtn, sched == "both", warn = false)

        val showPo = sched == "next_day" || sched == "both"
        poContainer.visibility = if (showPo) View.VISIBLE else View.GONE

        if (showPo) {
            val sortedDates = ui.productPoDates.filter {
                OrderRepository.isValidPreOrderDate(it) && !OrderRepository.isWeekendWib(it)
            }.sorted()
            val mandatory = sched == "next_day"
            val missing = mandatory && sortedDates.isEmpty()

            poDateHeaderTv.text = if (mandatory) {
                "Tanggal Pre-Order (Wajib · Senin–Jumat)"
            } else {
                "Tanggal Pre-Order (Kalender · Senin–Jumat)"
            }
            poDateHeaderTv.setTextColor(if (missing) dangerColor else warnAccent)

            poCalCard.background = rounded(warnBgColor, 10f, if (missing) dangerColor else warnAccent)
            poCalTitleTv.text = when {
                missing -> "Wajib pilih min. 1 tanggal PO"
                sortedDates.isEmpty() -> "Semua hari sekolah (Sen–Jum)"
                else -> "${sortedDates.size} tanggal PO dipilih"
            }
            poCalTitleTv.setTextColor(if (missing) dangerColor else inkColor)
            poCalSubTv.text = if (sortedDates.isEmpty()) {
                "Ketuk untuk pilih tanggal kalender (Sabtu & Minggu libur)"
            } else {
                sortedDates.take(3).joinToString(" · ") { formatCalendarShortChip(it) } +
                    if (sortedDates.size > 3) " +${sortedDates.size - 3}" else ""
            }
            poCalSubTv.setTextColor(mutedColor)
            poCalBtn.background = rounded(cardColor, 8f, warnAccent)
            poCalBtn.setTextColor(warnAccent)

            // Quick open school day toggles
            poQuickRow.removeAllViews()
            val openSchool = OrderRepository.nextOpenSchoolDatesWib(3, ui.productPoCutoffHour)
            val tomorrowIso = OrderRepository.tomorrowWibDateString()
            val quickSpecs = buildList {
                openSchool.forEach { iso ->
                    val lbl = if (iso == tomorrowIso) "Besok (${formatShortIdDate(iso)})" else formatCalendarShortChip(iso)
                    add(iso to lbl)
                }
                if (!mandatory || sortedDates.isNotEmpty()) {
                    add("__CLEAR__" to if (mandatory) "Reset" else "Bebas")
                }
            }
            quickSpecs.forEachIndexed { idx, (key, label) ->
                val on = if (key == "__CLEAR__") !mandatory && sortedDates.isEmpty() else key in sortedDates
                val btn = TextView(activity).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (on) warnAccent else inkColor)
                    background = rounded(if (on) warnBgColor else cardColor, 7f, if (on) warnAccent else borderColor)
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    setOnClickListener {
                        if (key == "__CLEAR__") {
                            ui.productPoDates.clear()
                        } else if (key in ui.productPoDates) {
                            ui.productPoDates.remove(key)
                        } else if (ui.productPoDates.size < 14) {
                            ui.productPoDates.add(key)
                        }
                        refreshScheduleUi()
                    }
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(5)
                }
                poQuickRow.addView(btn, lp)
            }

            // Selected PO dates chips
            poSelectedChipsWrap.removeAllViews()
            if (sortedDates.isEmpty()) {
                poSelectedChipsWrap.visibility = View.GONE
            } else {
                poSelectedChipsWrap.visibility = View.VISIBLE
                sortedDates.forEach { iso ->
                    val chip = TextView(activity).apply {
                        text = "${formatCalendarShortChip(iso)}  ×"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(warnAccent)
                        background = rounded(warnBgColor, 6f, warnAccent)
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        setOnClickListener {
                            ui.productPoDates.remove(iso)
                            refreshScheduleUi()
                        }
                    }
                    poSelectedChipsWrap.addView(chip)
                }
            }

            // Cutoff pills
            poCutoffRow.removeAllViews()
            listOf(16, 18, 20, 21).forEachIndexed { idx, hr ->
                val on = ui.productPoCutoffHour == hr
                val pill = TextView(activity).apply {
                    text = "${hr}:00 WIB"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    setTextColor(if (on) warnAccent else inkColor)
                    background = rounded(if (on) warnBgColor else cardColor, 7f, if (on) warnAccent else borderColor)
                    setOnClickListener {
                        ui.productPoCutoffHour = hr
                        refreshScheduleUi()
                    }
                }
                poCutoffRow.addView(
                    pill,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx > 0) leftMargin = dp(6)
                    },
                )
            }

            poQrisRow.background = rounded(cardColor, 10f, if (ui.productPoRequireQris) warnAccent else borderColor)
            poQrisSwitch.checked = ui.productPoRequireQris
        }

        // Pickup sessions
        sessRow.removeAllViews()
        DEFAULT_PICKUP_SESSIONS.forEachIndexed { idx, sess ->
            val on = sess in ui.productPickupSessions
            val pill = TextView(activity).apply {
                text = sess
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else inkColor)
                background = rounded(if (on) blueColor else cardColor, 9f, if (on) null else borderColor)
                setOnClickListener {
                    if (sess in ui.productPickupSessions) {
                        if (ui.productPickupSessions.size > 1) ui.productPickupSessions.remove(sess)
                    } else {
                        ui.productPickupSessions.add(sess)
                    }
                    refreshScheduleUi()
                }
            }
            sessRow.addView(
                pill,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
    }

    // --- Price & Stock live hints ---

    private fun refreshPriceStockHints() {
        val ui = activity.ui
        val pVal = ui.productPrice.filter(Char::isDigit).toIntOrNull()
        if (pVal != null && pVal > 0) {
            priceHintTv.text = formatRupiah(pVal)
            priceHintTv.setTextColor(blueColor)
        } else {
            priceHintTv.text = "Isi angka tanpa titik"
            priceHintTv.setTextColor(mutedColor)
        }

        val sVal = ui.productStock.filter(Char::isDigit).toIntOrNull()
        val invalidStock = sVal != null && sVal !in 1..100
        styleEditText(stockInput, stockInput.hasFocus(), forceError = invalidStock)
        when {
            invalidStock -> {
                stockHintTv.text = "Harus antara 1–100 porsi"
                stockHintTv.setTextColor(dangerColor)
            }
            sVal != null -> {
                stockHintTv.text = "$sVal porsi tersedia"
                stockHintTv.setTextColor(successColor)
            }
            else -> {
                stockHintTv.text = "Wajib 1–100 porsi"
                stockHintTv.setTextColor(mutedColor)
            }
        }
    }

    // --- Multi-Photo Gallery Preview (up to 5 photos) ---

    private fun refreshPhotoUi() {
        val ui = activity.ui
        val urls = ui.productImageUrls.filter { it.startsWith("https://") }.take(MAX_PRODUCT_IMAGES)
        photoCountTv.text = "${urls.size}/$MAX_PRODUCT_IMAGES foto"
        photoCountTv.setTextColor(if (urls.size >= MAX_PRODUCT_IMAGES) successColor else blueColor)

        photoGridWrap.removeAllViews()
        val tilePx = dp(78)

        urls.forEachIndexed { idx, url ->
            val tile = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(tilePx, tilePx)
                background = rounded(tintColor, 10f, if (idx == 0) blueColor else borderColor, if (idx == 0) 1.5f else 1f)
                clipToOutline = true
                setOnClickListener {
                    if (idx > 0 && idx in ui.productImageUrls.indices) {
                        val picked = ui.productImageUrls.removeAt(idx)
                        ui.productImageUrls.add(0, picked)
                        ui.productImageUrl = picked
                        Toast.makeText(activity, "Dijadikan foto utama.", Toast.LENGTH_SHORT).show()
                        refreshPhotoUi()
                    }
                }
            }
            val bmp = ProductImageCache.images[url]
            if (bmp != null) {
                val iv = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                tile.addView(iv)
            } else {
                val ph = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setImageDrawable(AppIcon.drawable(activity, "image", 22f, blueColor))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                tile.addView(ph)
                activity.loadProductImage(url)
            }

            // Cover tag on photo #1
            if (idx == 0) {
                val coverTag = TextView(activity).apply {
                    text = "Utama"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(blueColor, 5f)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.START,
                    ).apply {
                        leftMargin = dp(4)
                        bottomMargin = dp(4)
                    }
                }
                tile.addView(coverTag)
            }

            // Remove button (top-right)
            val removeBtn = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(AppIcon.drawable(activity, "close", 11f, Color.WHITE))
                background = rounded(Color.argb(195, 16, 22, 36), 11f)
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    if (!ui.productImageUploading && idx in ui.productImageUrls.indices) {
                        ui.productImageUrls.removeAt(idx)
                        ui.productImageUrl = ui.productImageUrls.firstOrNull().orEmpty()
                        refreshPhotoUi()
                    }
                }
            }
            tile.addView(removeBtn)
            photoGridWrap.addView(tile)
        }

        // "+ Tambah" tile while under 5 photos
        if (urls.size < MAX_PRODUCT_IMAGES) {
            val addTile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(tilePx, tilePx)
                background = rounded(tintColor, 10f, blueColor)
                setOnClickListener {
                    if (!ui.productImageUploading) {
                        hideKeyboard()
                        activity.pickProductImage()
                    }
                }
            }
            if (ui.productImageUploading) {
                addTile.addView(
                    ProgressBar(activity, null, android.R.attr.progressBarStyleSmall),
                    LinearLayout.LayoutParams(dp(22), dp(22)),
                )
                addTile.addView(
                    TextView(activity).apply {
                        text = "Unggah..."
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(blueColor)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
                    },
                )
            } else {
                addTile.addView(
                    ImageView(activity).apply {
                        setImageDrawable(AppIcon.drawable(activity, "plus", 20f, blueColor))
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)),
                )
                addTile.addView(
                    TextView(activity).apply {
                        text = if (urls.isEmpty()) "Tambah\nFoto" else "+ Tambah"
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(blueColor)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(3) }
                    },
                )
            }
            photoGridWrap.addView(addTile)
        }

        photoProgress.visibility = if (ui.productImageUploading) View.VISIBLE else View.GONE
        photoStatusTv.text = when {
            ui.productImageUploading -> "Mengunggah foto produk..."
            urls.isEmpty() -> "Belum ada foto · bisa pilih sekaligus hingga $MAX_PRODUCT_IMAGES foto"
            urls.size >= MAX_PRODUCT_IMAGES -> "Batas maksimal $MAX_PRODUCT_IMAGES foto tercapai"
            else -> "${urls.size} foto terpasang · sisa ${MAX_PRODUCT_IMAGES - urls.size} slot foto lagi"
        }
        photoStatusTv.setTextColor(
            when {
                ui.productImageUploading -> blueColor
                urls.isEmpty() -> mutedColor
                else -> successColor
            },
        )
    }

    // --- Visibility & Save CTA ---

    private fun refreshVisibilityUi() {
        visSwitch.checked = activity.ui.productAvailable
    }

    private fun refreshSaveCtaUi() {
        val ui = activity.ui
        if (ui.productError.isNotBlank()) {
            errorTv.text = ui.productError
            errorTv.visibility = View.VISIBLE
        } else {
            errorTv.visibility = View.GONE
        }
        val editing = ui.productEditId != null
        val busy = ui.productSaving || ui.productImageUploading
        saveBtn.text = when {
            ui.productImageUploading -> "Mengunggah gambar..."
            ui.productSaving -> "Menyimpan..."
            editing -> "Simpan Perubahan"
            else -> "Simpan Produk"
        }
        saveBtn.background = rounded(if (busy) mutedColor else blueColor, 12f)
        saveBtn.setTextColor(Color.WHITE)
        saveBtn.isEnabled = !busy
    }

    private fun clearError() {
        if (activity.ui.productError.isNotBlank()) {
            activity.ui.productError = ""
            errorTv.visibility = View.GONE
        }
    }

    private fun setTextIfChanged(et: EditText, value: String) {
        if (et.text?.toString() != value) {
            et.setText(value)
        }
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(rootContainer.windowToken, 0)
        rootContainer.clearFocus()
    }

    /** Compact custom wrap-flow ViewGroup for chips (recent rooms, variant options, PO dates). */
    private class FlowWrapLayout(
        context: Context,
        private val hGap: Int,
        private val vGap: Int,
    ) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val availW = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else (maxW - paddingLeft - paddingRight).coerceAtLeast(0)
            var curX = 0
            var curY = 0
            var rowH = 0
            var usedW = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                measureChild(
                    child,
                    MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (curX > 0 && curX + cw > availW) {
                    curY += rowH + vGap
                    curX = 0
                    rowH = 0
                }
                curX += cw + hGap
                usedW = maxOf(usedW, curX - hGap)
                rowH = maxOf(rowH, ch)
            }
            val totalH = curY + rowH + paddingTop + paddingBottom
            val resolvedW = if (widthMode == MeasureSpec.EXACTLY) maxW else (usedW + paddingLeft + paddingRight)
            setMeasuredDimension(resolvedW, resolveSize(totalH, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val availW = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
            var curX = paddingLeft
            var curY = paddingTop
            var rowH = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (curX > paddingLeft && curX - paddingLeft + cw > availW) {
                    curY += rowH + vGap
                    curX = paddingLeft
                    rowH = 0
                }
                child.layout(curX, curY, curX + cw, curY + ch)
                curX += cw + hGap
                rowH = maxOf(rowH, ch)
            }
        }
    }

    /** Crisp pill toggle matching `sellerSwitch` in dark and light mode. */
    private class PillSwitchView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var onColor = Color.rgb(66, 153, 255)
        private var offColor = Color.rgb(42, 54, 78)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        var checked: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        fun applyPalette(active: Int, inactive: Int) {
            onColor = active
            offColor = inactive
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            paint.color = if (checked) onColor else offColor
            canvas.drawRoundRect(rect, r, r, paint)

            val pad = 3f * density
            val knobR = (h / 2f - pad).coerceAtLeast(4f)
            val cx = if (checked) w - pad - knobR else pad + knobR
            paint.color = Color.WHITE
            canvas.drawCircle(cx, h / 2f, knobR, paint)
        }
    }
}
