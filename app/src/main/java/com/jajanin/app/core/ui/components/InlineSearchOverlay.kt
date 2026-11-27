package com.jajanin.app.core.ui.components

import android.content.Context
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.jajanin.app.MainActivity
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.feature.buyer.store_discovery.StoreSearchViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Anchors a real transparent Android `EditText` directly inside the active Canvas search box
 * (`Home`, `Explore`, `StoreSearch`, and `Developer`) so users type inline in the box itself
 * without any popup dialog.
 */
internal class InlineSearchOverlay(
    private val activity: MainActivity,
    root: FrameLayout,
) {
    companion object {
        const val TARGET_MENU = "menu"
        const val TARGET_STORE = "store"
        const val TARGET_DEV = "dev"
    }

    private val density = activity.resources.displayMetrics.density
    private var activeTarget: String? = null
    private var suppressWatcher = false

    private val editText = object : EditText(activity) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && activeTarget != null) {
                if (event?.action == KeyEvent.ACTION_UP) {
                    dismiss()
                }
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        visibility = View.GONE
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        isSingleLine = true
        maxLines = 1
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(80))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val value = s?.toString().orEmpty()
                dispatchLiveText(value, immediate = false)
            }
        })

        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                dispatchLiveText(text?.toString().orEmpty().trim(), immediate = true)
                dismiss()
                true
            } else {
                false
            }
        }
    }

    init {
        root.addView(
            editText,
            FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START),
        )
    }

    fun isActive(): Boolean = activeTarget != null

    fun activeTargetOrNull(): String? = activeTarget

    fun open(target: String) {
        val ui = activity.ui
        // Ensure the search bar at the top of the page is fully visible above the keyboard
        if (ui.scroll > 6f) {
            ui.scroll = 0f
        }

        val currentText = when (target) {
            TARGET_STORE -> ui.storeSearchQuery
            TARGET_DEV -> ui.devQuery
            else -> ui.query
        }
        val hintText = when (target) {
            TARGET_STORE -> "Cari nama warung, kategori, atau menu..."
            TARGET_DEV -> if (ui.devTab == "Sellers") {
                "Cari nama penjual, NIS, atau nama toko..."
            } else {
                "Cari nama pembeli, NIS, atau email..."
            }
            else -> if (ui.currentScreen == Screen.EXPLORE) {
                "Cari nama menu..."
            } else {
                "Cari menu atau minuman..."
            }
        }

        activeTarget = target
        ui.inlineSearchActiveTarget = target

        suppressWatcher = true
        editText.hint = hintText
        if (editText.text?.toString() != currentText) {
            editText.setText(currentText)
        }
        editText.setSelection(editText.text?.length ?: 0)
        suppressWatcher = false

        applyTheme()
        editText.visibility = View.VISIBLE
        ui.invalidate()

        editText.requestFocus()
        editText.post {
            if (activeTarget == target) {
                editText.requestFocus()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun clearText() {
        if (activeTarget == null) return
        editText.setText("")
        editText.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun dismiss() {
        if (activeTarget == null && editText.visibility != View.VISIBLE) return
        activeTarget = null
        activity.ui.inlineSearchActiveTarget = null
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus()
        editText.visibility = View.GONE
        activity.ui.invalidate()
    }

    fun applyTheme() {
        val kit = activity.ui.kit
        editText.setTextColor(kit.ink)
        editText.setHintTextColor(kit.muted)
        if (Build.VERSION.SDK_INT >= 29) {
            editText.textCursorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setSize((2f * density).roundToInt().coerceAtLeast(2), (18f * density).roundToInt())
                setColor(kit.blue)
            }
        }
    }

    /** Called at the end of `JajaninView.onDraw` to lock `editText` onto the Canvas search box. */
    fun syncCanvasBounds(virtualRect: RectF?, scale: Float, virtualTextSize: Float) {
        if (activeTarget == null || virtualRect == null || scale <= 0f) {
            if (editText.visibility == View.VISIBLE && activeTarget == null) {
                editText.visibility = View.GONE
            }
            return
        }
        val leftPx = virtualRect.left * scale
        val topPx = virtualRect.top * scale
        val wPx = (virtualRect.width() * scale).roundToInt().coerceAtLeast(40)
        val hPx = (virtualRect.height() * scale).roundToInt().coerceAtLeast(24)
        val lp = editText.layoutParams as FrameLayout.LayoutParams
        if (abs(lp.width - wPx) > 1 || abs(lp.height - hPx) > 1) {
            lp.width = wPx
            lp.height = hPx
            editText.layoutParams = lp
        }
        editText.translationX = leftPx
        editText.translationY = topPx
        val targetTextPx = virtualTextSize * scale
        if (abs(editText.textSize - targetTextPx) > 0.4f) {
            editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetTextPx)
        }
    }

    private fun dispatchLiveText(value: String, immediate: Boolean) {
        val ui = activity.ui
        when (activeTarget) {
            TARGET_STORE -> {
                StoreSearchViewModel.onQueryChanged(activity, value, immediate = immediate)
            }
            TARGET_DEV -> {
                val trimmed = value.trimStart()
                if (ui.devQuery != trimmed) {
                    ui.devQuery = trimmed
                    ui.scroll = 0f
                    ui.invalidate()
                }
            }
            TARGET_MENU -> {
                val trimmed = value.trimStart()
                if (ui.query != trimmed) {
                    ui.query = trimmed
                    ui.scroll = 0f
                    ui.invalidate()
                }
            }
        }
    }
}
