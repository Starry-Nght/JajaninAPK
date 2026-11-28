package com.jajanin.app.feature.buyer

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.adaptiveDialogMaxHeightPx
import com.jajanin.app.core.ui.adaptiveDialogWidthPx
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.FeedbackRepository
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.store_discovery.StoreSearchViewModel
import com.jajanin.app.feature.buyer.store_profile.StoreProfileViewModel
import com.jajanin.app.feature.seller.compressForUpload
import com.jajanin.app.feature.seller.loadProductImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

internal data class ActiveSellerReportHolder(
    val stallId: String,
    val stallName: String,
    val orderId: String?,
    val source: String,
    var uri1: Uri? = null,
    var uri2: Uri? = null,
    var preview1: ImageView? = null,
    var preview2: ImageView? = null,
    var badge1: TextView? = null,
    var badge2: TextView? = null,
    var dialog: Dialog? = null,
)

private val REPORT_CATEGORIES = listOf(
    "Pesanan Tidak Sesuai",
    "Makanan Tidak Layak",
    "Penjual Tidak Responsif",
    "Masalah Pembayaran",
    "Lainnya",
)

// ==================== 1. BUYER PROFILE AVATAR ====================

internal fun MainActivity.refreshMyAvatarUrl() {
    if (!SupabaseRepository.hasSession()) return
    authScope.launch {
        val url = FeedbackRepository.fetchMyAvatarUrl()
        if (url != ui.myAvatarUrl) {
            ui.myAvatarUrl = url
            ui.invalidate()
        }
        if (url.startsWith("https://")) {
            loadProductImage(url)
        }
    }
}

internal fun MainActivity.showBuyerAvatarOptions() {
    val self = this
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk terlebih dahulu untuk mengubah foto profil.", Toast.LENGTH_SHORT).show()
        return
    }
    val currentUrl = ui.myAvatarUrl.trim()
    val hasPhoto = currentUrl.startsWith("https://")
    val options = buildList {
        add(if (hasPhoto) "Ganti Foto dari Galeri" else "Pilih Foto dari Galeri")
        if (hasPhoto) {
            add("Lihat Foto Profil")
            add("Hapus Foto Profil")
        }
    }.toTypedArray()

    android.app.AlertDialog.Builder(this)
        .setTitle("Foto Profil")
        .setItems(options) { _, which ->
            when {
                options[which].contains("Galeri") -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    startActivityForResult(intent, REQUEST_PROFILE_AVATAR)
                }
                options[which].startsWith("Lihat") -> {
                    showPaymentProofPreview(currentUrl, "Foto Profil")
                }
                options[which].startsWith("Hapus") -> {
                    authScope.launch {
                        try {
                            FeedbackRepository.updateMyAvatar("")
                            ui.myAvatarUrl = ""
                            ui.invalidate()
                            Toast.makeText(self, "Foto profil dihapus.", Toast.LENGTH_SHORT).show()
                        } catch (error: Throwable) {
                            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        .show()
}

internal fun MainActivity.uploadBuyerAvatar(uri: Uri) {
    val self = this
    Toast.makeText(this, "Mengunggah foto profil...", Toast.LENGTH_SHORT).show()
    authScope.launch(Dispatchers.IO) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Gambar tidak bisa dibaca.")
            val bytes = compressForUpload(raw, maxEdge = 512, quality = 85)
            require(bytes.size <= 5 * 1024 * 1024) { "Ukuran foto maksimal 5MB." }
            val publicUrl = FeedbackRepository.uploadPublicImage("profile-avatars", "avatar", bytes)
            FeedbackRepository.updateMyAvatar(publicUrl)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                ProductImageCache.images[publicUrl] = bmp
            }
            launch(Dispatchers.Main) {
                ui.myAvatarUrl = publicUrl
                ui.invalidate()
                Toast.makeText(self, "Foto profil berhasil diperbarui.", Toast.LENGTH_SHORT).show()
            }
        } catch (error: Throwable) {
            launch(Dispatchers.Main) {
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ==================== 2. ORDER RATING SYSTEM (1-5: TOKO, DURASI, RASA) ====================

internal fun MainActivity.refreshMyOrderRatings() {
    if (!SupabaseRepository.hasSession()) return
    authScope.launch {
        val map = FeedbackRepository.fetchMyRatingsByOrderId()
        ui.myOrderRatings = map
        ui.invalidate()
    }
}

internal fun MainActivity.showOrderRatingDialogById(orderId: String) {
    val order = ui.myOrders.firstOrNull { it.id == orderId }
    if (order == null) {
        Toast.makeText(this, "Data pesanan sedang dimuat, coba lagi sebentar.", Toast.LENGTH_SHORT).show()
        return
    }
    showOrderRatingDialog(order)
}

internal fun MainActivity.showOrderRatingDialog(order: AdminOrder) {
    val self = this
    if (!SupabaseRepository.hasSession()) return
    if (order.status.equals("cancelled", ignoreCase = true)) {
        Toast.makeText(this, "Pesanan dibatalkan tidak bisa diberi rating.", Toast.LENGTH_SHORT).show()
        return
    }
    val canRate = order.status.equals("completed", ignoreCase = true)
    if (!canRate) {
        Toast.makeText(this, "Rating hanya bisa diberikan setelah penjual menyelesaikan pesanan.", Toast.LENGTH_SHORT).show()
        return
    }

    val kit = ui.kit
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()
    fun bgBox(fill: Int, radiusDp: Int, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * density
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    val existing = ui.myOrderRatings[order.id]
    var storeScore = existing?.ratingStore?.coerceIn(1, 5) ?: 5
    var durationScore = existing?.ratingDuration?.coerceIn(1, 5) ?: 5
    var tasteScore = existing?.ratingTaste?.coerceIn(1, 5) ?: 5

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bgBox(kit.card, 16, kit.border, 1)
        setPadding(dp(18), dp(18), dp(18), dp(16))
    }

    val titleTv = TextView(this).apply {
        text = if (existing != null) "Perbarui Rating Pesanan" else "Beri Rating Pesanan"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.ink)
    }
    val storeName = order.sellerStoreName.ifBlank { "Warung Jajanin" }
    val subTv = TextView(this).apply {
        text = "$storeName · Pesanan #${order.pickupCode}"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(kit.muted)
        setPadding(0, dp(3), 0, dp(12))
    }
    root.addView(titleTv)
    root.addView(subTv)

    val avgPill = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.rgb(183, 116, 0))
        background = bgBox(kit.warnBox, 8, Color.rgb(183, 116, 0), 1)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        gravity = Gravity.CENTER
    }
    fun updateAvgLabel() {
        val avg = (storeScore + durationScore + tasteScore) / 3.0
        avgPill.text = String.format(
            Locale.US,
            "Rata-rata: %.1f / 5.0  (Toko %d · Durasi %d · Rasa %d)",
            avg,
            storeScore,
            durationScore,
            tasteScore,
        )
    }
    updateAvgLabel()
    root.addView(
        avgPill,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        },
    )

    fun scoreWord(s: Int): String = when (s) {
        1 -> "1/5 · Sangat Kurang"
        2 -> "2/5 · Kurang"
        3 -> "3/5 · Cukup"
        4 -> "4/5 · Baik"
        else -> "5/5 · Sangat Baik"
    }

    fun buildRatingRow(
        label: String,
        desc: String,
        getVal: () -> Int,
        setVal: (Int) -> Unit,
    ): LinearLayout {
        val wrap = LinearLayout(self).apply {
            orientation = LinearLayout.VERTICAL
            background = bgBox(kit.bg, 10, kit.border, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val topLine = LinearLayout(self).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameTv = TextView(self).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(kit.ink)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valTv = TextView(self).apply {
            text = scoreWord(getVal())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.rgb(217, 119, 6))
        }
        topLine.addView(nameTv)
        topLine.addView(valTv)
        wrap.addView(topLine)

        val descTv = TextView(self).apply {
            text = desc
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(kit.muted)
            setPadding(0, dp(2), 0, dp(8))
        }
        wrap.addView(descTv)

        val starsRow = LinearLayout(self).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val starButtons = ArrayList<TextView>(5)
        fun refreshStars() {
            val cur = getVal()
            valTv.text = scoreWord(cur)
            starButtons.forEachIndexed { idx, btn ->
                val active = (idx + 1) <= cur
                btn.text = if (active) "★" else "☆"
                btn.setTextColor(if (active) Color.rgb(245, 158, 11) else kit.muted)
                btn.background = bgBox(
                    fill = if (active) kit.warnBox else kit.card,
                    radiusDp = 8,
                    stroke = if (active) Color.rgb(245, 158, 11) else kit.border,
                    strokeW = 1,
                )
            }
            updateAvgLabel()
        }
        for (i in 1..5) {
            val btn = TextView(self).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 1) marginStart = dp(6)
                }
                setOnClickListener {
                    setVal(i)
                    refreshStars()
                }
            }
            starButtons.add(btn)
            starsRow.addView(btn)
        }
        refreshStars()
        wrap.addView(starsRow)
        return wrap
    }

    val bodyScroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val bodyCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(10)
    }
    bodyCol.addView(
        buildRatingRow("Toko", "Pelayanan, keramahan & kerapian warung", { storeScore }, { storeScore = it }),
        rowLp,
    )
    bodyCol.addView(
        buildRatingRow("Durasi", "Kecepatan penyiapan & ketepatan waktu", { durationScore }, { durationScore = it }),
        rowLp,
    )
    bodyCol.addView(
        buildRatingRow("Rasa", "Cita rasa, porsi & kualitas makanan/minuman", { tasteScore }, { tasteScore = it }),
        rowLp,
    )

    val commentLabel = TextView(this).apply {
        text = "Ulasan singkat (opsional)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.ink)
        setPadding(0, dp(2), 0, dp(6))
    }
    val commentInput = EditText(this).apply {
        hint = "Tulis pengalaman jajanmu di warung ini..."
        setText(existing?.comment.orEmpty())
        setTextColor(kit.ink)
        setHintTextColor(kit.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minLines = 2
        maxLines = 4
        gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(InputFilter.LengthFilter(400))
        background = bgBox(kit.bg, 10, kit.border, 1)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    bodyCol.addView(commentLabel)
    bodyCol.addView(commentInput)
    bodyScroll.addView(bodyCol)
    root.addView(bodyScroll)

    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(14), 0, 0)
    }
    val laterBtn = TextView(this).apply {
        text = "Nanti"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.muted)
        gravity = Gravity.CENTER
        background = bgBox(kit.bg, 10, kit.border, 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
        setOnClickListener { dialog.dismiss() }
    }
    val submitBtn = TextView(this).apply {
        text = if (existing != null) "Simpan Rating" else "Kirim Rating"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = bgBox(kit.blue, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
    }
    submitBtn.setOnClickListener {
        submitBtn.isEnabled = false
        submitBtn.text = "Mengirim..."
        val comment = commentInput.text.toString().trim()
        authScope.launch {
            try {
                val saved = FeedbackRepository.submitOrderRating(
                    orderId = order.id,
                    ratingStore = storeScore,
                    ratingDuration = durationScore,
                    ratingTaste = tasteScore,
                    comment = comment,
                )
                val nextMap = HashMap(ui.myOrderRatings)
                nextMap[order.id] = saved
                ui.myOrderRatings = nextMap
                ui.invalidate()
                StoreSearchViewModel.refreshStoreDirectory(self)
                if (ui.selectedStoreId == saved.stallId && saved.stallId.isNotBlank()) {
                    StoreProfileViewModel.loadStoreProfile(self, saved.stallId)
                }
                dialog.dismiss()
                Toast.makeText(self, "Terima kasih atas ratingmu!", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                submitBtn.isEnabled = true
                submitBtn.text = if (existing != null) "Simpan Rating" else "Kirim Rating"
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
    actions.addView(laterBtn)
    actions.addView(submitBtn)
    root.addView(actions)

    dialog.setContentView(root)
    val maxH = adaptiveDialogMaxHeightPx(0.84f)
    val width = adaptiveDialogWidthPx(0.92f)
    dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    root.post {
        if (root.height > maxH) {
            dialog.window?.setLayout(width, maxH)
        }
    }
    dialog.show()
}

// ==================== 3. REPORT SELLER WITH 2 MANDATORY PHOTO PROOFS ====================

internal fun MainActivity.showReportSellerFromOrder(orderId: String) {
    val order = ui.myOrders.firstOrNull { it.id == orderId }
    if (order == null) {
        Toast.makeText(this, "Pesanan tidak ditemukan.", Toast.LENGTH_SHORT).show()
        return
    }
    val stallId = order.stallId.ifBlank {
        order.items.firstNotNullOfOrNull { it.menuItemId }
            ?.let { pid -> resolveKnownProduct(pid)?.stallId }
            .orEmpty()
    }
    if (stallId.isBlank()) {
        Toast.makeText(this, "Data warung tidak ditemukan.", Toast.LENGTH_SHORT).show()
        return
    }
    showReportSellerDialog(
        stallId = stallId,
        stallName = order.sellerStoreName.ifBlank { "Warung Jajanin" },
        orderId = order.id,
        pickupCode = order.pickupCode,
        source = "order_history",
    )
}

internal fun MainActivity.showReportSellerFromStore(stallId: String) {
    val detail = ui.storeProfileDetail?.takeIf { it.id == stallId }
    val summary = ui.storeDirectoryById[stallId]
    val stallName = detail?.name ?: summary?.name ?: "Warung"
    val ownerId = detail?.ownerId
    if (!ownerId.isNullOrBlank() && ownerId == SupabaseRepository.currentUid()) {
        Toast.makeText(this, "Kamu tidak dapat melaporkan warung milik sendiri.", Toast.LENGTH_SHORT).show()
        return
    }
    showReportSellerDialog(
        stallId = stallId,
        stallName = stallName,
        orderId = null,
        pickupCode = null,
        source = "store_profile",
    )
}

internal fun MainActivity.onReportEvidencePicked(slot: Int, uri: Uri) {
    val holder = activeSellerReport ?: return
    if (slot == 1) {
        holder.uri1 = uri
    } else {
        holder.uri2 = uri
    }
    val targetImg = if (slot == 1) holder.preview1 else holder.preview2
    val targetBadge = if (slot == 1) holder.badge1 else holder.badge2
    authScope.launch(Dispatchers.IO) {
        val bmp = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val raw = stream.readBytes()
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            }
        }.getOrNull()
        launch(Dispatchers.Main) {
            if (bmp != null) {
                targetImg?.setImageBitmap(bmp)
                targetImg?.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            targetBadge?.text = "Bukti $slot siap"
            targetBadge?.setTextColor(ui.kit.success)
        }
    }
}

internal fun MainActivity.showReportSellerDialog(
    stallId: String,
    stallName: String,
    orderId: String?,
    pickupCode: String?,
    source: String,
) {
    val self = this
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk melaporkan penjual.", Toast.LENGTH_SHORT).show()
        return
    }
    val kit = ui.kit
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()
    fun bgBox(fill: Int, radiusDp: Int, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * density
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val holder = ActiveSellerReportHolder(
        stallId = stallId,
        stallName = stallName,
        orderId = orderId,
        source = source,
        dialog = dialog,
    )
    activeSellerReport = holder
    dialog.setOnDismissListener {
        if (activeSellerReport === holder) activeSellerReport = null
    }

    var selectedCategory = REPORT_CATEGORIES.first()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bgBox(kit.card, 16, kit.dangerBorder, 1)
        setPadding(dp(18), dp(18), dp(18), dp(16))
    }

    val titleTv = TextView(this).apply {
        text = "Laporkan Penjual"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.danger)
    }
    val subText = if (!pickupCode.isNullOrBlank()) {
        "$stallName · Pesanan #$pickupCode · Laporan langsung diteruskan ke Developer."
    } else {
        "$stallName · Laporan langsung diteruskan ke Developer."
    }
    val subTv = TextView(this).apply {
        text = subText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(kit.muted)
        setPadding(0, dp(3), 0, dp(10))
    }
    root.addView(titleTv)
    root.addView(subTv)

    val scroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // 1. Category selector
    val catLabel = TextView(this).apply {
        text = "Kategori Pelanggaran"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.ink)
        setPadding(0, 0, 0, dp(6))
    }
    col.addView(catLabel)

    val catButtons = ArrayList<TextView>()
    fun refreshCats() {
        catButtons.forEachIndexed { idx, btn ->
            val on = REPORT_CATEGORIES[idx] == selectedCategory
            btn.setTextColor(if (on) Color.WHITE else kit.ink)
            btn.background = bgBox(if (on) kit.danger else kit.bg, 8, if (on) kit.danger else kit.border, 1)
        }
    }
    REPORT_CATEGORIES.forEach { cat ->
        val btn = TextView(this).apply {
            text = cat
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            setOnClickListener {
                selectedCategory = cat
                refreshCats()
            }
        }
        catButtons.add(btn)
        col.addView(btn)
    }
    refreshCats()

    // 2. Chronology / Description
    val descLabel = TextView(this).apply {
        text = "Kronologi & Keterangan (min. 10 karakter)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.ink)
        setPadding(0, dp(8), 0, dp(6))
    }
    val descInput = EditText(this).apply {
        hint = "Jelaskan secara jelas kesalahan penjual yang terjadi..."
        setTextColor(kit.ink)
        setHintTextColor(kit.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minLines = 3
        maxLines = 5
        gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(InputFilter.LengthFilter(1000))
        background = bgBox(kit.bg, 10, kit.border, 1)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    col.addView(descLabel)
    col.addView(descInput)

    // 3. 2 Mandatory Photo Proof Pickers
    val evTitle = TextView(this).apply {
        text = "Lampirkan 2 Foto Bukti (Wajib)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.ink)
        setPadding(0, dp(14), 0, dp(4))
    }
    val evSub = TextView(this).apply {
        text = "Contoh: foto produk/pesanan yang bermasalah + screenshot chat atau bukti transaksi."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(kit.muted)
        setPadding(0, 0, 0, dp(8))
    }
    col.addView(evTitle)
    col.addView(evSub)

    val evRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun buildEvidenceSlot(slot: Int): LinearLayout {
        val card = LinearLayout(self).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bgBox(kit.bg, 10, kit.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (slot == 1) marginEnd = dp(8)
            }
        }
        val img = ImageView(self).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
            background = bgBox(kit.card, 8, kit.border, 1)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val badge = TextView(self).apply {
            text = "+ Pilih Bukti $slot"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(kit.blue)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        if (slot == 1) {
            holder.preview1 = img
            holder.badge1 = badge
        } else {
            holder.preview2 = img
            holder.badge2 = badge
        }
        card.addView(img)
        card.addView(badge)
        card.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, if (slot == 1) REQUEST_REPORT_EVIDENCE_1 else REQUEST_REPORT_EVIDENCE_2)
        }
        return card
    }

    evRow.addView(buildEvidenceSlot(1))
    evRow.addView(buildEvidenceSlot(2))
    col.addView(evRow)

    scroll.addView(col)
    root.addView(scroll)

    // Bottom Actions
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(14), 0, 0)
    }
    val cancelBtn = TextView(this).apply {
        text = "Batal"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(kit.muted)
        gravity = Gravity.CENTER
        background = bgBox(kit.bg, 10, kit.border, 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
        setOnClickListener { dialog.dismiss() }
    }
    val submitBtn = TextView(this).apply {
        text = "Kirim Laporan"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = bgBox(kit.danger, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
    }
    submitBtn.setOnClickListener {
        val desc = descInput.text.toString().trim()
        val u1 = holder.uri1
        val u2 = holder.uri2
        if (desc.length < 10) {
            Toast.makeText(self, "Jelaskan kronologi masalah minimal 10 karakter.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        if (u1 == null || u2 == null) {
            Toast.makeText(self, "Wajib melampirkan 2 foto bukti (Bukti 1 dan Bukti 2).", Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        if (u1 == u2) {
            Toast.makeText(self, "Pilih 2 foto bukti yang berbeda untuk Bukti 1 dan Bukti 2.", Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        submitBtn.isEnabled = false
        cancelBtn.isEnabled = false
        submitBtn.text = "Mengunggah 2 bukti..."
        authScope.launch(Dispatchers.IO) {
            try {
                val raw1 = contentResolver.openInputStream(u1)?.use { it.readBytes() }
                    ?: error("Bukti foto 1 tidak bisa dibaca.")
                val raw2 = contentResolver.openInputStream(u2)?.use { it.readBytes() }
                    ?: error("Bukti foto 2 tidak bisa dibaca.")
                val bytes1 = compressForUpload(raw1, maxEdge = 1280, quality = 82)
                val bytes2 = compressForUpload(raw2, maxEdge = 1280, quality = 82)
                require(bytes1.size <= 5 * 1024 * 1024 && bytes2.size <= 5 * 1024 * 1024) {
                    "Ukuran tiap foto bukti maksimal 5MB."
                }
                val url1 = FeedbackRepository.uploadPublicImage("report-evidences", "ev1", bytes1)
                val url2 = FeedbackRepository.uploadPublicImage("report-evidences", "ev2", bytes2)
                FeedbackRepository.submitSellerReport(
                    stallId = stallId,
                    orderId = orderId,
                    source = source,
                    category = selectedCategory,
                    description = desc,
                    evidenceUrl1 = url1,
                    evidenceUrl2 = url2,
                )
                launch(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        self,
                        "Laporan berhasil dikirim dan langsung masuk ke Developer.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Throwable) {
                launch(Dispatchers.Main) {
                    submitBtn.isEnabled = true
                    cancelBtn.isEnabled = true
                    submitBtn.text = "Kirim Laporan"
                    Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    actions.addView(cancelBtn)
    actions.addView(submitBtn)
    root.addView(actions)

    dialog.setContentView(root)
    val maxH = adaptiveDialogMaxHeightPx(0.88f)
    val width = adaptiveDialogWidthPx(0.92f)
    dialog.window?.setLayout(width, maxH)
    dialog.show()
}
