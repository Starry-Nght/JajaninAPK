package com.jajanin.app.feature.auth

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.jajanin.app.core.ui.adaptiveDialogWidthPx
import com.jajanin.app.core.ui.showThemedOptions
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.seller.switchToBuyerMode
import kotlinx.coroutines.launch

/**
 * Alur hapus akun mandiri dari Profil Pembeli maupun Profil Penjual:
 * - Bila akun punya Mode Penjual aktif: pilih hapus Mode Penjual saja (tutup warung) atau hapus seluruh akun permanen.
 * - Bila akun hanya Pembeli: langsung konfirmasi hapus akun permanen dengan kata sandi.
 */
internal fun MainActivity.showDeleteAccountFlow(fromSellerProfile: Boolean) {
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk mengelola akun.", Toast.LENGTH_SHORT).show()
        return
    }
    if (ui.myRole == "developer") {
        Toast.makeText(this, "Akun developer tidak dapat dihapus dari aplikasi.", Toast.LENGTH_LONG).show()
        return
    }
    val hasSellerMode = ui.myIsSellerVerified || fromSellerProfile
    if (hasSellerMode) {
        val options = listOf(
            "Hapus Akun Penjual (Tutup Warung saja)",
            "Hapus Seluruh Akun Pembeli & Penjual (Permanen)",
        )
        showThemedOptions("Pilih jenis penghapusan akun", options, -1) { which ->
            when (which) {
                0 -> showDeleteAccountConfirmDialog(mode = "seller_only")
                1 -> showDeleteAccountConfirmDialog(mode = "full_account")
            }
        }
    } else {
        showDeleteAccountConfirmDialog(mode = "full_account")
    }
}

private fun MainActivity.showDeleteAccountConfirmDialog(mode: String) {
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

    val isSellerOnly = mode == "seller_only"
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    var busy = false
    var passVisible = false

    val scroll = ScrollView(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(kit.card, 18f, kit.dangerBorder)
        setPadding(dp(20), dp(20), dp(20), dp(18))
    }

    val titleTv = TextView(this).apply {
        text = if (isSellerOnly) "Hapus Akun Penjual?" else "Hapus Akun Permanen?"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18.5f)
        setTextColor(kit.danger)
        typeface = Typeface.DEFAULT_BOLD
    }
    val descTv = TextView(this).apply {
        text = if (isSellerOnly) {
            "Tindakan ini akan menghapus warung, semua produk di etalase, dan menonaktifkan Mode Penjual.\n\nAkun pembeli & riwayat belanja kamu tetap aman dan kamu bisa mengaktifkan Mode Penjual lagi nanti."
        } else {
            "Tindakan ini TIDAK BISA dibatalkan. Seluruh data akun pembeli, warung & produk penjual (jika ada), riwayat pesanan, dan chat akan dihapus permanen.\n\nNIS kamu akan kembali berstatus belum terdaftar jika ingin mendaftar ulang."
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(kit.muted)
        setPadding(0, dp(8), 0, dp(14))
    }

    val passLabel = TextView(this).apply {
        text = "Konfirmasi dengan Kata Sandi Akun"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(kit.ink)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(6))
    }

    val passBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(kit.bg, 10f, kit.border)
        setPadding(dp(12), dp(2), dp(8), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    val passInput = EditText(this).apply {
        hint = "Masukkan kata sandi kamu"
        setHintTextColor(kit.muted)
        setTextColor(kit.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        background = null
        setPadding(dp(2), dp(10), dp(6), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val toggleBtn = TextView(this).apply {
        text = "Lihat"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(kit.blue)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(8), dp(6), dp(8))
        setOnClickListener {
            passVisible = !passVisible
            val pos = passInput.selectionStart.coerceAtLeast(0)
            passInput.inputType = if (passVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passInput.setSelection(pos.coerceAtMost(passInput.text?.length ?: 0))
            text = if (passVisible) "Sembunyi" else "Lihat"
        }
    }
    passBox.addView(passInput)
    passBox.addView(toggleBtn)

    val statusTv = TextView(this).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(kit.danger)
        setPadding(dp(2), dp(2), dp(2), dp(10))
    }

    val confirmBtn = TextView(this).apply {
        text = if (isSellerOnly) "Hapus Akun Penjual" else "Hapus Akun Permanen"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(kit.danger, 12f)
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
        setOnClickListener { if (!busy) dialog.dismiss() }
    }

    root.addView(titleTv)
    root.addView(descTv)
    root.addView(passLabel)
    root.addView(passBox)
    root.addView(statusTv)
    root.addView(confirmBtn)
    root.addView(cancelBtn)
    scroll.addView(root)
    dialog.setContentView(scroll)

    fun setBusyState(loading: Boolean) {
        busy = loading
        dialog.setCancelable(!loading)
        passInput.isEnabled = !loading
        confirmBtn.isEnabled = !loading
        cancelBtn.isEnabled = !loading
        confirmBtn.text = when {
            loading -> "Menghapus..."
            isSellerOnly -> "Hapus Akun Penjual"
            else -> "Hapus Akun Permanen"
        }
        confirmBtn.background = rounded(if (loading) kit.muted else kit.danger, 12f)
    }

    confirmBtn.setOnClickListener {
        if (busy) return@setOnClickListener
        val pass = passInput.text.toString()
        if (pass.isEmpty()) {
            statusTv.visibility = View.VISIBLE
            statusTv.text = "Masukkan kata sandi akun kamu untuk konfirmasi."
            passInput.requestFocus()
            return@setOnClickListener
        }
        statusTv.visibility = View.GONE
        setBusyState(true)
        authScope.launch {
            try {
                val msg = SupabaseRepository.deleteMyAccount(mode = mode, password = pass)
                dialog.dismiss()
                if (isSellerOnly) {
                    adminObserver?.close()
                    adminObserver = null
                    ui.myIsSellerVerified = false
                    ui.mySellerVerifiedEmail = ""
                    ui.myStallId = null
                    ui.myStallName = ""
                    ui.myStallRoom = ""
                    ui.myStallQris = ""
                    ui.sellerProducts = emptyList()
                    ui.adminOrders = emptyList()
                    ui.adminDetailOrder = null
                    ui.isSellerProfile = false
                    switchToBuyerMode()
                    Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(self, msg, Toast.LENGTH_LONG).show()
                    doLogout()
                }
            } catch (t: Throwable) {
                setBusyState(false)
                statusTv.visibility = View.VISIBLE
                statusTv.text = SupabaseRepository.errorMessage(t)
            }
        }
    }

    dialog.show()
    dialog.window?.setLayout(
        adaptiveDialogWidthPx(0.92f),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    @Suppress("DEPRECATION")
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    passInput.requestFocus()
}
