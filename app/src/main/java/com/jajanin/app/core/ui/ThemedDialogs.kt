package com.jajanin.app.core.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jajanin.app.MainActivity
import com.jajanin.app.core.ui.components.AppIcon

/**
 * Dialog bertema Jajanin (pengganti AlertDialog Material putih generik).
 * Permukaan kaca gelap di mode gelap, putih di mode terang; sudut 22dp,
 * tipografi dan tombol konsisten dengan brand. Semua teks plain (tanpa HTML)
 * dan dipotong aman sehingga konten notifikasi tak dikenal tidak merusak UI.
 */

private fun MainActivity.dialogSurface(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = 22f * resources.displayMetrics.density
    if (isDarkMode()) {
        setColor(Color.rgb(27, 32, 59))
        setStroke((1f * resources.displayMetrics.density).toInt(), Color.argb(60, 255, 255, 255))
    } else {
        setColor(Color.WHITE)
        setStroke((1f * resources.displayMetrics.density).toInt(), Color.rgb(224, 228, 236))
    }
}

private fun MainActivity.dialogInk(): Int =
    if (isDarkMode()) Color.rgb(235, 238, 245) else Color.rgb(27, 35, 53)

private fun MainActivity.dialogMuted(): Int =
    if (isDarkMode()) Color.rgb(150, 158, 175) else Color.rgb(112, 120, 137)

private fun MainActivity.dialogBlue(): Int =
    if (isDarkMode()) Color.rgb(80, 150, 255) else Color.rgb(0, 99, 214)

private fun MainActivity.dialogDanger(): Int =
    if (isDarkMode()) Color.rgb(235, 110, 110) else Color.rgb(206, 60, 60)

private fun MainActivity.baseDialog(): Dialog = Dialog(this).apply {
    window?.setBackgroundDrawableResource(android.R.color.transparent)
}

private data class DialogParts(val dialog: Dialog, val root: LinearLayout, val buttons: LinearLayout)

private fun MainActivity.dialogBody(title: String, severity: String? = null): DialogParts {
    val d = resources.displayMetrics.density
    val dialog = baseDialog()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = dialogSurface()
        setPadding((24 * d).toInt(), (22 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
    }
    val titleView = TextView(this).apply {
        text = title.take(80)
        setTextColor(dialogInk())
        textSize = 18f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    root.addView(titleView)
    if (severity != null) {
        val sevColor = when (severity.lowercase()) {
            "critical" -> dialogDanger()
            "warning" -> if (isDarkMode()) Color.rgb(225, 170, 60) else Color.rgb(183, 116, 0)
            else -> dialogBlue()
        }
        val pill = TextView(this).apply {
            text = severity.take(16).uppercase()
            setTextColor(sevColor)
            textSize = 10f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        root.addView(pill)
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    dialog.setContentView(root)
    dialog.window?.setLayout(adaptiveDialogWidthPx(0.92f), ViewGroup.LayoutParams.WRAP_CONTENT)
    return DialogParts(dialog, root, buttons)
}

private fun MainActivity.dialogButton(label: String, color: Int, onClick: (() -> Unit)?): TextView =
    TextView(this).apply {
        text = label.take(20)
        setTextColor(color)
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val d = resources.displayMetrics.density
        gravity = Gravity.CENTER
        minWidth = (48 * d).toInt()
        minHeight = (48 * d).toInt()
        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick?.invoke() }
    }

/**
 * Dialog info bertema: judul + deskripsi scroll + aksi. Aman untuk konten
 * notifikasi tak dikenal (plain text, max 2000 char).
 */
internal fun MainActivity.showThemedInfo(
    title: String,
    message: String,
    severity: String? = null,
    posLabel: String = "Tutup",
    onPos: (() -> Unit)? = null,
    negLabel: String? = null,
    onNeg: (() -> Unit)? = null,
    destructivePos: Boolean = false,
) {
    val d = resources.displayMetrics.density
    val parts = dialogBody(title, severity)
    val dialog = parts.dialog
    val root = parts.root
    val buttons = parts.buttons
    val scroll = ScrollView(this).apply {
        val maxH = (380 * d).toInt()
        root.addView(this, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (10 * d).toInt() })
        post { if (height > maxH) layoutParams.height = maxH; requestLayout() }
    }
    scroll.addView(TextView(this).apply {
        text = message.take(2000)
        setTextColor(dialogMuted())
        textSize = 14f
        setLineSpacing(0f, 1.25f)
    })
    if (negLabel != null) buttons.addView(dialogButton(negLabel, dialogMuted()) { dialog.dismiss(); onNeg?.invoke() })
    buttons.addView(dialogButton(posLabel, if (destructivePos) dialogDanger() else dialogBlue()) { dialog.dismiss(); onPos?.invoke() })
    root.addView(buttons)
    dialog.show()
}

/** Dialog pilih satu opsi bertema (pengganti single-choice Material). */
internal fun MainActivity.showThemedOptions(
    title: String,
    options: List<String>,
    checked: Int,
    onPick: (Int) -> Unit,
) {
    val d = resources.displayMetrics.density
    val parts = dialogBody(title)
    val dialog = parts.dialog
    val root = parts.root
    options.forEachIndexed { index, option ->
        val selected = index == checked
        val row = TextView(this).apply {
            text = option.take(36)
            setTextColor(if (selected) dialogBlue() else dialogInk())
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * d).toInt()
            setPadding((4 * d).toInt(), (13 * d).toInt(), (4 * d).toInt(), (13 * d).toInt())
            val radioIcon = AppIcon.drawable(
                this@showThemedOptions,
                if (selected) "radioChecked" else "radioUnchecked",
                17f,
                if (selected) dialogBlue() else dialogMuted(),
            )
            setCompoundDrawables(radioIcon, null, null, null)
            compoundDrawablePadding = (10 * d).toInt()
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss(); onPick(index) }
        }
        root.addView(row)
        if (index < options.size - 1) {
            root.addView(android.view.View(this).apply {
                setBackgroundColor(if (isDarkMode()) Color.rgb(48, 56, 76) else Color.rgb(232, 234, 240))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt())
            })
        }
    }
    dialog.show()
}
