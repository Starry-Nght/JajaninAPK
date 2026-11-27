package com.jajanin.app.feature.buyer.cart

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.onMidtransPaymentCompleted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Standard In-App Midtrans Snap Payment Popup (`WebView` modal).
 *
 * Displays the official Midtrans Snap interface directly inside the application (supporting
 * GoPay/QRIS, ShopeePay, DANA, Virtual Account BCA/BNI/BRI/Mandiri, Indomaret, etc.),
 * automatically polls for settlement in the background, and provides a 1-tap Sandbox
 * payment completion button so users never have to leave the app or copy-paste codes
 * into an external simulator browser.
 */
internal class MidtransSnapDialog(
    private val activity: MainActivity,
    private val orderId: String,
    private val snapUrl: String,
    private val amountIdr: Int,
    private val pickupCode: String?,
    private val isSandbox: Boolean,
) {
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var pollingJob: Job? = null
    private var simBusy: Boolean = false
    private var completed: Boolean = false

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val kit = activity.ui.kit
        val dlg = Dialog(activity)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.bg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // 1. Top Header Bar
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(kit.card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(activity).apply {
            text = if (isSandbox) "Midtrans Snap (Sandbox)" else "Pembayaran Midtrans"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(kit.ink)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subView = TextView(activity).apply {
            val codeTag = pickupCode?.takeIf { it.isNotBlank() }?.let { "Pesanan #$it  ·  " }.orEmpty()
            text = "${codeTag}Total ${formatRupiah(amountIdr)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleCol.addView(titleView)
        titleCol.addView(subView)
        header.addView(titleCol)

        val closeBtn = TextView(activity).apply {
            text = "Tutup"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(kit.muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener { dismiss() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Loading Progress Bar
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        root.addView(progressBar)

        // 2. Official Midtrans Snap WebView
        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val wv = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    return handleUrlNavigation(uri)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
                    return handleUrlNavigation(parsed)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val lower = url.orEmpty().lowercase()
                    if (lower.contains("transaction_status=settlement") ||
                        lower.contains("transaction_status=capture") ||
                        lower.contains("status_code=200")
                    ) {
                        checkStatusNow(simulateSandbox = false)
                    }
                }
            }
        }
        webView = wv
        webContainer.addView(wv)
        root.addView(webContainer)

        // 3. Bottom Action Strip (1-Click Sandbox Instant Pay + Manual Status Check)
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.card)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        if (isSandbox) {
            val hint = TextView(activity).apply {
                text = "Mode Sandbox: pilih metode bayar di atas, atau tekan tombol di bawah untuk langsung melunasi pesanan."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(kit.muted)
                setPadding(0, 0, 0, dp(8))
            }
            footer.addView(hint)

            val simBtn = TextView(activity).apply {
                text = "Bayar Lunas Sekarang (Simulasi Sandbox)"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(kit.blue)
                }
                setOnClickListener {
                    if (!simBusy) {
                        text = "Melunasi pembayaran..."
                        checkStatusNow(simulateSandbox = true, btnView = this)
                    }
                }
            }
            footer.addView(simBtn)
        } else {
            val checkBtn = TextView(activity).apply {
                text = "Saya Sudah Membayar · Cek Status"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(kit.blue)
                }
                setOnClickListener {
                    if (!simBusy) {
                        text = "Memeriksa status..."
                        checkStatusNow(simulateSandbox = false, btnView = this)
                    }
                }
            }
            footer.addView(checkBtn)
        }
        root.addView(footer)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dlg.setOnDismissListener {
            cleanup()
        }
        dialog = dlg
        dlg.show()

        wv.loadUrl(snapUrl)
        startBackgroundPoller()
    }

    private fun handleUrlNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val full = uri.toString().lowercase()
        if (full.contains("transaction_status=settlement") ||
            full.contains("transaction_status=capture") ||
            full.contains("status_code=200")
        ) {
            checkStatusNow(simulateSandbox = false)
            return true
        }
        if (scheme == "http" || scheme == "https") {
            return false
        }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        return true
    }

    private fun startBackgroundPoller() {
        pollingJob?.cancel()
        pollingJob = activity.authScope.launch {
            while (isActive && !completed) {
                delay(3_000L)
                if (completed || simBusy) continue
                val res = runCatching {
                    withTimeout(10_000L) {
                        OrderRepository.checkGatewayPaymentStatus(orderId = orderId, simulatePay = false)
                    }
                }.getOrNull() ?: continue
                if (res.paymentStatus.equals("paid", ignoreCase = true) ||
                    res.status.equals("paid", ignoreCase = true) ||
                    res.status.equals("settlement", ignoreCase = true) ||
                    res.status.equals("capture", ignoreCase = true)
                ) {
                    onSettled()
                    break
                }
            }
        }
    }

    private fun checkStatusNow(simulateSandbox: Boolean, btnView: TextView? = null) {
        if (completed || simBusy) return
        simBusy = true
        activity.authScope.launch {
            try {
                val res = withTimeout(15_000L) {
                    OrderRepository.checkGatewayPaymentStatus(orderId = orderId, simulatePay = simulateSandbox)
                }
                simBusy = false
                if (res.paymentStatus.equals("paid", ignoreCase = true) ||
                    res.status.equals("paid", ignoreCase = true) ||
                    res.status.equals("settlement", ignoreCase = true) ||
                    res.status.equals("capture", ignoreCase = true)
                ) {
                    onSettled()
                } else {
                    btnView?.text = if (isSandbox) "Bayar Lunas Sekarang (Simulasi Sandbox)" else "Saya Sudah Membayar · Cek Status"
                    Toast.makeText(
                        activity,
                        res.message?.takeIf { it.isNotBlank() } ?: "Pembayaran masih menunggu konfirmasi Midtrans.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: CancellationException) {
                simBusy = false
                throw e
            } catch (e: Throwable) {
                simBusy = false
                btnView?.text = if (isSandbox) "Bayar Lunas Sekarang (Simulasi Sandbox)" else "Saya Sudah Membayar · Cek Status"
                val msg = (e as? IllegalStateException)?.message ?: SupabaseRepository.errorMessage(e)
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onSettled() {
        if (completed) return
        completed = true
        dismiss()
        activity.onMidtransPaymentCompleted(orderId, "pending")
    }

    fun dismiss() {
        cleanup()
        runCatching {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
            }
        }
        dialog = null
    }

    private fun cleanup() {
        pollingJob?.cancel()
        pollingJob = null
        runCatching {
            webView?.stopLoading()
            webView?.destroy()
        }
        webView = null
    }
}
