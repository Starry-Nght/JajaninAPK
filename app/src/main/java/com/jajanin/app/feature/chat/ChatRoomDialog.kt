package com.jajanin.app.feature.chat

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.MainActivity
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.data.model.ChatMessage
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.model.MessageDeliveryState
import com.jajanin.app.data.model.formatChatBubbleTime
import com.jajanin.app.data.repository.ChatRepository
import com.jajanin.app.data.repository.ChatRoomSession
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.seller.loadProductImage
import com.jajanin.app.feature.seller.openOrderDetail
import com.jajanin.app.notifications.NotificationHelper
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class ChatRoomDialog(
    private val activity: MainActivity,
    initialSummary: ConversationSummary,
) {
    private var summary: ConversationSummary = initialSummary
    private val conversationId: String = initialSummary.id
    private val myUid: String = SupabaseRepository.currentUid().orEmpty()
    private val viewerIsSeller: Boolean = activity.ui.myRole == "seller"

    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val messagesById = LinkedHashMap<String, ChatMessage>()
    private var roomSession: ChatRoomSession? = null
    private var previousRoomCleanup: Job? = null
    private var loadJob: Job? = null
    private var fastSyncJob: Job? = null
    private var typingExpiryJob: Job? = null

    private var isLoadingInitial = true
    private var isLoadingOlder = false
    private var hasMoreHistory = false
    private var loadError: String? = null
    private var partnerTyping = false
    private var realtimeStatus: RealtimeChannel.Status = RealtimeChannel.Status.SUBSCRIBING

    private lateinit var subtitleView: TextView
    private lateinit var onlineDotView: View
    private lateinit var muteBtnView: TextView
    private lateinit var contextBannerContainer: FrameLayout
    private lateinit var scrollView: ScrollView
    private lateinit var messageListLayout: LinearLayout
    private lateinit var loadOlderBtn: TextView
    private lateinit var stateBannerView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendBtn: TextView
    private lateinit var charCounterView: TextView

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val kit get() = activity.ui.kit

    fun show() {
        NotificationHelper.activeOpenConversationId = conversationId
        NotificationHelper.cancelChatNotification(activity, conversationId)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(kit.bg))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            statusBarColor = kit.card
            @Suppress("DEPRECATION")
            navigationBarColor = kit.card
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }

        val root = buildContentView()
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            onDialogClosed()
        }
        dialog.show()

        startRoomRealtime()
        loadInitialMessages()
        startFastSyncLoop()
    }

    fun dismiss() {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    fun isShowingFor(targetConversationId: String): Boolean =
        dialog.isShowing && conversationId == targetConversationId

    fun refreshPresenceIndicator() {
        if (!dialog.isShowing) return
        updateHeaderStatusText()
    }

    private fun onDialogClosed() {
        val draft = inputField.text?.toString().orEmpty()
        if (draft.isNotBlank()) {
            ChatDraftStore.saveDraft(conversationId, draft)
        } else {
            ChatDraftStore.clearDraft(conversationId)
        }
        if (NotificationHelper.activeOpenConversationId == conversationId) {
            NotificationHelper.activeOpenConversationId = null
        }
        typingExpiryJob?.cancel()
        fastSyncJob?.cancel()
        loadJob?.cancel()
        previousRoomCleanup = roomSession?.close()
        roomSession = null
        if (activity.activeChatDialog === this) {
            activity.activeChatDialog = null
        }
        activity.refreshChatList(silent = true)
        activity.refreshUnreadChats()
    }

    private fun buildContentView(): View {
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
            setPadding(dp(12), dp(12), dp(14), dp(12))
        }

        val backBtn = TextView(activity).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { dismiss() }
        }
        header.addView(backBtn)

        // Avatar with Online Dot
        val avatarFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                rightMargin = dp(10)
            }
        }
        val title = summary.displayTitle(viewerIsSeller)
        val initial = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "J"
        val avatarView = TextView(activity).apply {
            text = initial
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(kit.tint, dpF(10f))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        avatarFrame.addView(avatarView)

        onlineDotView = View(activity).apply {
            background = roundedDrawable(kit.success, dpF(6f), strokeColor = kit.card, strokeWidthPx = dp(2))
            layoutParams = FrameLayout.LayoutParams(dp(11), dp(11), Gravity.BOTTOM or Gravity.END)
        }
        avatarFrame.addView(onlineDotView)
        header.addView(avatarFrame)

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(kit.ink)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(nameView)
        titleCol.addView(subtitleView)
        header.addView(titleCol)

        muteBtnView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                val muted = activity.toggleConversationMuted(conversationId)
                updateMuteButtonUI()
                val msg = if (muted) "Notifikasi percakapan ini dibisukan." else "Notifikasi percakapan ini diaktifkan."
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(muteBtnView)
        root.addView(header)

        // Hairline Divider
        root.addView(View(activity).apply {
            setBackgroundColor(kit.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // 2. Context Card Container (Product Inquiry or Order Context)
        contextBannerContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(contextBannerContainer)
        renderContextBanner()

        // 3. Connection / Error Status Banner
        stateBannerView = TextView(activity).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.danger)
            gravity = Gravity.CENTER
            setBackgroundColor(kit.dangerBorder)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                if (loadError != null) loadInitialMessages()
            }
        }
        root.addView(stateBannerView)

        // 4. Scrollable Message List
        scrollView = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val scrollInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        loadOlderBtn = TextView(activity).apply {
            visibility = View.GONE
            text = "Muat pesan sebelumnya"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(kit.card, dpF(8f), strokeColor = kit.border, strokeWidthPx = dp(1))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            setOnClickListener { loadOlderMessages() }
        }
        scrollInner.addView(loadOlderBtn)

        messageListLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollInner.addView(messageListLayout)
        scrollView.addView(scrollInner)
        root.addView(scrollView)

        // Bottom Divider above Composer
        root.addView(View(activity).apply {
            setBackgroundColor(kit.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // 5. Message Composer Bar
        val composerWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.card)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        charCounterView = TextView(activity).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(kit.muted)
            gravity = Gravity.END
            setPadding(0, 0, dp(4), dp(4))
        }
        composerWrap.addView(charCounterView)

        val composerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        inputField = EditText(activity).apply {
            hint = "Tulis pesan..."
            setHintTextColor(kit.muted)
            setTextColor(kit.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedDrawable(kit.bg, dpF(18f), strokeColor = kit.border, strokeWidthPx = dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 4
            minLines = 1
            filters = arrayOf(InputFilter.LengthFilter(500))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
            val savedDraft = ChatDraftStore.getDraft(conversationId)
            if (savedDraft.isNotBlank()) {
                setText(savedDraft)
                setSelection(savedDraft.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val text = s?.toString().orEmpty()
                    updateComposerState(text)
                    if (text.trim().isNotEmpty()) {
                        roomSession?.notifyTyping(myUid, isTyping = true)
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        composerRow.addView(inputField)

        sendBtn = TextView(activity).apply {
            text = "Kirim"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { submitComposerMessage() }
        }
        composerRow.addView(sendBtn)
        composerWrap.addView(composerRow)
        root.addView(composerWrap)

        updateMuteButtonUI()
        updateHeaderStatusText()
        updateComposerState(inputField.text?.toString().orEmpty())
        applyWindowInsets(root, header, composerWrap)
        return root
    }

    private fun applyWindowInsets(root: View, header: View, composerWrap: View) {
        val fallbackStatusTop = runCatching {
            val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) activity.resources.getDimensionPixelSize(resId) else dp(28)
        }.getOrDefault(dp(28))

        val fallbackNavBottom = runCatching {
            val resId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resId > 0) activity.resources.getDimensionPixelSize(resId) else dp(48)
        }.getOrDefault(dp(48))

        header.setPadding(dp(12), fallbackStatusTop + dp(10), dp(14), dp(12))
        composerWrap.setPadding(dp(12), dp(8), dp(12), fallbackNavBottom + dp(10))

        var lastImeBottom = 0
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val navBottom: Int
            val imeBottom: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val sysBars = insets.getInsets(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                val navBars = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                topInset = maxOf(sysBars.top, fallbackStatusTop)
                navBottom = navBars.bottom
                imeBottom = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = maxOf(insets.systemWindowInsetTop, fallbackStatusTop)
                @Suppress("DEPRECATION")
                navBottom = insets.systemWindowInsetBottom
                imeBottom = 0
            }
            val effectiveBottom = maxOf(navBottom, imeBottom)
            header.setPadding(dp(12), topInset + dp(10), dp(14), dp(12))
            composerWrap.setPadding(dp(12), dp(8), dp(12), effectiveBottom + dp(10))

            if (imeBottom > lastImeBottom && messagesById.isNotEmpty()) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
            lastImeBottom = imeBottom
            insets
        }
        root.post { root.requestApplyInsets() }
    }

    private fun renderContextBanner() {
        contextBannerContainer.removeAllViews()
        val orderCtx = summary.orderContext
        val prodCtx = summary.productContext

        if (orderCtx != null) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(kit.tint, dpF(8f), strokeColor = kit.border, strokeWidthPx = dp(1))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(4))
                }
            }

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val topLine = TextView(activity).apply {
                val stLabel = statusLabelShort(orderCtx.status)
                text = "Pesanan #${orderCtx.pickupCode} · $stLabel · ${formatRupiah(orderCtx.totalIdr)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(topLine)

            if (orderCtx.itemsSummary.isNotBlank()) {
                val subLine = TextView(activity).apply {
                    text = orderCtx.itemsSummary
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(kit.ink)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                infoCol.addView(subLine)
            }
            card.addView(infoCol)

            val actionBtn = TextView(activity).apply {
                text = "Lihat Pesanan →"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = roundedDrawable(kit.blue, dpF(6f))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    dismiss()
                    if (viewerIsSeller) {
                        activity.openOrderDetail(orderCtx.id)
                    } else {
                        activity.ui.selectedBuyerOrderId = orderCtx.id
                        activity.ui.lastOrderId = orderCtx.id
                        activity.ui.go(Screen.READY)
                    }
                }
            }
            card.addView(actionBtn)
            contextBannerContainer.addView(card)
            return
        }

        if (prodCtx != null) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = if (prodCtx.deleted || !prodCtx.available) kit.dangerBorder else kit.tint
                background = roundedDrawable(bg, dpF(8f), strokeColor = kit.border, strokeWidthPx = dp(1))
                setPadding(dp(10), dp(8), dp(12), dp(8))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(4))
                }
            }

            // Product Image or Initial Box
            val imgUrl = prodCtx.imageUrl?.takeIf { it.startsWith("https://") }
            val cachedBmp = imgUrl?.let { ProductImageCache.images[it] }
            if (cachedBmp != null) {
                val iv = ImageView(activity).apply {
                    setImageBitmap(cachedBmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(10)
                    }
                }
                card.addView(iv)
            } else {
                if (imgUrl != null) activity.loadProductImage(imgUrl)
                val placeholder = TextView(activity).apply {
                    text = (prodCtx.name.trim().firstOrNull() ?: 'P').uppercaseChar().toString()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(kit.blue)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = roundedDrawable(kit.card, dpF(8f))
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(10)
                    }
                }
                card.addView(placeholder)
            }

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameLine = TextView(activity).apply {
                text = prodCtx.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(kit.ink)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val metaLine = TextView(activity).apply {
                val statusTag = when {
                    prodCtx.deleted -> "Produk telah dihapus"
                    !prodCtx.available -> "Stok sedang habis"
                    else -> prodCtx.sellerName
                }
                val pricePart = if (prodCtx.priceIdr > 0) "${formatRupiah(prodCtx.priceIdr)} · " else ""
                text = "$pricePart$statusTag"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (prodCtx.deleted || !prodCtx.available) kit.danger else kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(nameLine)
            infoCol.addView(metaLine)
            card.addView(infoCol)

            val matchedProduct = prodCtx.id?.let { pid ->
                activity.ui.products.firstOrNull { it.id == pid }
                    ?: activity.ui.sellerProducts.firstOrNull { it.id == pid }
            }
            if (matchedProduct != null && !prodCtx.deleted && !viewerIsSeller) {
                val viewBtn = TextView(activity).apply {
                    text = "Lihat Menu"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(kit.blue)
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedDrawable(kit.card, dpF(6f), strokeColor = kit.blue, strokeWidthPx = dp(1))
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    setOnClickListener {
                        dismiss()
                        activity.ui.selectedProduct = matchedProduct
                        activity.ui.quantity = 1
                        activity.ui.go(Screen.DETAIL)
                    }
                }
                card.addView(viewBtn)
            }

            contextBannerContainer.addView(card)
        }
    }

    private fun updateMuteButtonUI() {
        val muted = activity.isConversationMuted(conversationId)
        muteBtnView.text = if (muted) "Dibisukan" else "Notif Aktif"
        muteBtnView.setTextColor(if (muted) kit.muted else kit.blue)
        muteBtnView.background = roundedDrawable(
            if (muted) kit.bg else kit.tint,
            dpF(8f),
            strokeColor = kit.border,
            strokeWidthPx = dp(1),
        )
    }

    private fun updateHeaderStatusText() {
        val isOnline = summary.counterpartyId.isNotBlank() && summary.counterpartyId in activity.ui.onlineUserIds
        onlineDotView.visibility = if (isOnline) View.VISIBLE else View.GONE

        val baseSub = summary.displaySubtitle(viewerIsSeller)
        when {
            partnerTyping -> {
                subtitleView.text = "Sedang mengetik..."
                subtitleView.setTextColor(kit.blue)
            }
            isOnline -> {
                subtitleView.text = "Online · $baseSub"
                subtitleView.setTextColor(kit.success)
            }
            else -> {
                subtitleView.text = baseSub
                subtitleView.setTextColor(kit.muted)
            }
        }
    }

    private fun updateComposerState(text: String) {
        val trimmed = text.trim()
        val len = text.length
        if (len >= 400) {
            charCounterView.visibility = View.VISIBLE
            charCounterView.text = "$len / 500"
            charCounterView.setTextColor(if (len >= 500) kit.danger else kit.muted)
        } else {
            charCounterView.visibility = View.GONE
        }

        val enabled = trimmed.isNotEmpty() && trimmed.length <= 500
        sendBtn.isEnabled = enabled
        sendBtn.background = roundedDrawable(
            if (enabled) kit.blue else kit.border,
            dpF(18f),
        )
        sendBtn.setTextColor(if (enabled) Color.WHITE else kit.muted)
    }

    private fun loadInitialMessages() {
        loadJob?.cancel()
        isLoadingInitial = true
        loadError = null
        renderMessageList(scrollToBottom = false)

        loadJob = activity.authScope.launch {
            try {
                // Segarkan metadata percakapan & pesan secara paralel
                runCatching {
                    summary = ChatRepository.fetchConversation(conversationId)
                    renderContextBanner()
                    updateHeaderStatusText()
                }
                val pageSize = 45
                val fetched = ChatRepository.fetchMessages(
                    conversationId = conversationId,
                    limit = pageSize,
                    beforeIso = null,
                )
                messagesById.clear()
                fetched.forEach { msg -> messagesById[msg.id] = msg }
                hasMoreHistory = fetched.size >= pageSize
                isLoadingInitial = false
                loadError = null
                renderMessageList(scrollToBottom = true)

                markIncomingAsReadIfNeeded()
            } catch (t: Throwable) {
                isLoadingInitial = false
                loadError = t.message ?: "Gagal memuat pesan."
                renderMessageList(scrollToBottom = false)
            }
        }
    }

    private fun loadOlderMessages() {
        if (isLoadingOlder || !hasMoreHistory) return
        val oldest = messagesById.values.firstOrNull { it.createdAt.isNotBlank() } ?: return
        isLoadingOlder = true
        loadOlderBtn.text = "Memuat..."

        activity.authScope.launch {
            try {
                val pageSize = 45
                val older = ChatRepository.fetchMessages(
                    conversationId = conversationId,
                    limit = pageSize,
                    beforeIso = oldest.createdAt,
                )
                hasMoreHistory = older.size >= pageSize
                if (older.isNotEmpty()) {
                    val combined = LinkedHashMap<String, ChatMessage>()
                    older.forEach { msg -> combined[msg.id] = msg }
                    messagesById.forEach { (k, v) -> combined[k] = v }
                    messagesById.clear()
                    messagesById.putAll(combined)
                }
            } catch (t: Throwable) {
                Toast.makeText(activity, t.message ?: "Gagal memuat riwayat pesan.", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingOlder = false
                renderMessageList(scrollToBottom = false)
            }
        }
    }

    private fun startRoomRealtime() {
        previousRoomCleanup = roomSession?.close()
        roomSession = ChatRepository.observeConversationRoom(
            scope = activity.authScope,
            conversationId = conversationId,
            currentUserId = myUid,
            previousCleanup = previousRoomCleanup,
            onMessageInserted = { incoming ->
                onRealtimeMessageInserted(incoming)
            },
            onMessageUpdated = { updated ->
                onRealtimeMessageUpdated(updated)
            },
            onPartnerReadAll = { readAtIso ->
                onPartnerReadAllMessages(readAtIso)
            },
            onPartnerTyping = { isTyping ->
                onPartnerTypingEvent(isTyping)
            },
            onConnectionStatusChanged = { st ->
                realtimeStatus = st
                updateStateBanner()
            },
        )
    }

    /**
     * Sinkronisasi latar belakang berkecepatan tinggi secara berurutan (non-overlapping)
     * selama ruang chat terbuka, agar pesan baru dan status dibaca (`✓✓`) selalu
     * sinkron seketika tanpa kedipan UI maupun antrean request menumpuk.
     */
    private fun startFastSyncLoop() {
        fastSyncJob?.cancel()
        fastSyncJob = activity.authScope.launch {
            while (dialog.isShowing) {
                delay(350L)
                if (!dialog.isShowing || isLoadingInitial || isLoadingOlder) continue
                runCatching {
                    val latest = ChatRepository.fetchMessages(
                        conversationId = conversationId,
                        limit = 45,
                        beforeIso = null,
                    )
                    var hasNewMessage = false
                    var hasReadUpdate = false
                    var hasUnreadIncoming = false

                    latest.forEach { msg ->
                        if (msg.senderId != myUid && !msg.isRead) {
                            hasUnreadIncoming = true
                        }
                        val existing = messagesById[msg.id]
                        if (existing == null) {
                            // Cek apakah pesan ini adalah hasil konfirmasi pesan optimistik
                            val tempKey = messagesById.entries.firstOrNull { (_, cur) ->
                                cur.deliveryState == MessageDeliveryState.SENDING &&
                                    cur.senderId == msg.senderId &&
                                    cur.body == msg.body
                            }?.key
                            if (tempKey != null) {
                                messagesById.remove(tempKey)
                            }
                            messagesById[msg.id] = msg
                            hasNewMessage = true
                        } else if (existing.isRead != msg.isRead || existing.readAt != msg.readAt) {
                            messagesById[msg.id] = msg
                            hasReadUpdate = true
                        }
                    }

                    if (hasNewMessage) {
                        renderMessageList(scrollToBottom = true)
                    } else if (hasReadUpdate) {
                        renderMessageList(scrollToBottom = false)
                    }
                    if (hasUnreadIncoming) {
                        markIncomingAsReadIfNeeded()
                    }
                }
            }
        }
    }

    private fun onPartnerReadAllMessages(readAtIso: String) {
        if (!dialog.isShowing) return
        var changed = false
        val effectiveReadAt = readAtIso.ifBlank {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }
        messagesById.entries.toList().forEach { (id, msg) ->
            if (msg.senderId == myUid && !msg.isRead && msg.deliveryState == MessageDeliveryState.SENT) {
                messagesById[id] = msg.copy(isRead = true, readAt = effectiveReadAt)
                changed = true
            }
        }
        if (changed) {
            renderMessageList(scrollToBottom = false)
        }
    }

    private fun onRealtimeMessageInserted(incoming: ChatMessage) {
        if (!dialog.isShowing) return
        // Deduplikasi bila pesan ini adalah echo dari pesan optimistik kita sendiri
        val tempMatchKey = messagesById.entries.firstOrNull { (_, existing) ->
            existing.deliveryState == MessageDeliveryState.SENDING &&
                existing.senderId == incoming.senderId &&
                existing.body == incoming.body
        }?.key
        if (tempMatchKey != null && tempMatchKey != incoming.id) {
            messagesById.remove(tempMatchKey)
        }
        val isNew = !messagesById.containsKey(incoming.id)
        messagesById[incoming.id] = incoming
        if (incoming.senderId != myUid) {
            partnerTyping = false
            typingExpiryJob?.cancel()
            updateHeaderStatusText()
            markIncomingAsReadIfNeeded()
        }
        if (isNew || tempMatchKey != null) {
            renderMessageList(scrollToBottom = true)
        }
    }

    private fun onRealtimeMessageUpdated(updated: ChatMessage) {
        if (!dialog.isShowing) return
        val existing = messagesById[updated.id] ?: return
        if (existing.isRead != updated.isRead || existing.readAt != updated.readAt || existing.body != updated.body) {
            messagesById[updated.id] = updated
            renderMessageList(scrollToBottom = false)
        }
    }

    private fun onPartnerTypingEvent(isTyping: Boolean) {
        if (!dialog.isShowing) return
        partnerTyping = isTyping
        updateHeaderStatusText()
        typingExpiryJob?.cancel()
        if (isTyping) {
            typingExpiryJob = activity.authScope.launch {
                delay(3_500L)
                partnerTyping = false
                updateHeaderStatusText()
            }
        }
    }

    private fun markIncomingAsReadIfNeeded() {
        val hasUnreadIncoming = messagesById.values.any { it.senderId != myUid && !it.isRead }
        if (!hasUnreadIncoming && summary.unreadCount <= 0) return
        activity.authScope.launch {
            val marked = ChatRepository.markConversationRead(conversationId)
            if (marked > 0) {
                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                roomSession?.broadcastReadReceipt(myUid, nowIso)
                activity.refreshUnreadChats()
                activity.refreshChatList(silent = true)
            }
        }
    }

    private fun submitComposerMessage() {
        val raw = inputField.text?.toString().orEmpty()
        val clean = raw.trim()
        if (clean.isEmpty() || clean.length > 500) return

        inputField.setText("")
        ChatDraftStore.clearDraft(conversationId)
        roomSession?.notifyTyping(myUid, isTyping = false)

        val tempId = "temp_${UUID.randomUUID()}"
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val optimistic = ChatMessage(
            id = tempId,
            conversationId = conversationId,
            senderId = myUid,
            body = clean,
            messageType = "text",
            isRead = false,
            readAt = null,
            createdAt = nowIso,
            timeLabel = formatChatBubbleTime(nowIso),
            deliveryState = MessageDeliveryState.SENDING,
            localTempId = tempId,
        )
        messagesById[tempId] = optimistic
        renderMessageList(scrollToBottom = true)

        sendOptimisticMessage(optimistic)
    }

    private fun sendOptimisticMessage(optimistic: ChatMessage) {
        val key = optimistic.localTempId ?: optimistic.id
        activity.authScope.launch {
            try {
                val confirmed = ChatRepository.sendMessage(
                    conversationId = conversationId,
                    body = optimistic.body,
                    localTempId = key,
                )
                messagesById.remove(key)
                val sentMsg = confirmed.copy(deliveryState = MessageDeliveryState.SENT)
                messagesById[confirmed.id] = sentMsg
                roomSession?.broadcastNewMessage(sentMsg)
                renderMessageList(scrollToBottom = true)
                activity.refreshChatList(silent = true)
            } catch (t: Throwable) {
                val current = messagesById[key]
                if (current != null) {
                    messagesById[key] = current.copy(deliveryState = MessageDeliveryState.FAILED)
                    renderMessageList(scrollToBottom = false)
                }
                Toast.makeText(
                    activity,
                    t.message ?: "Gagal mengirim pesan. Ketuk pesan untuk coba lagi.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun retryFailedMessage(failed: ChatMessage) {
        val key = failed.localTempId ?: failed.id
        val retrying = failed.copy(deliveryState = MessageDeliveryState.SENDING)
        messagesById[key] = retrying
        renderMessageList(scrollToBottom = false)
        sendOptimisticMessage(retrying)
    }

    private fun updateStateBanner() {
        when {
            loadError != null -> {
                stateBannerView.visibility = View.VISIBLE
                stateBannerView.text = "${loadError} · Ketuk untuk muat ulang"
                stateBannerView.setTextColor(kit.danger)
                stateBannerView.setBackgroundColor(kit.dangerBorder)
            }
            else -> {
                stateBannerView.visibility = View.GONE
            }
        }
    }

    private fun renderMessageList(scrollToBottom: Boolean) {
        updateStateBanner()
        loadOlderBtn.visibility = if (hasMoreHistory && messagesById.isNotEmpty()) View.VISIBLE else View.GONE
        if (!isLoadingOlder) {
            loadOlderBtn.text = "Muat pesan sebelumnya"
        }

        messageListLayout.removeAllViews()

        if (isLoadingInitial && messagesById.isEmpty()) {
            val loadingTv = TextView(activity).apply {
                text = "Memuat percakapan..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(kit.muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(48))
            }
            messageListLayout.addView(loadingTv)
            return
        }

        if (messagesById.isEmpty()) {
            val emptyTv = TextView(activity).apply {
                val targetName = summary.cleanCounterpartyName
                text = "Mulai percakapan dengan $targetName.\nTanyakan detail menu, stok, atau koordinasi pesanan di sini."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(kit.muted)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(52), dp(24), dp(52))
            }
            messageListLayout.addView(emptyTv)
            return
        }

        val sortedMessages = messagesById.values.toList()
        sortedMessages.forEach { msg ->
            val isMine = msg.senderId == myUid
            val rowWrap = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isMine) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val bubbleBgColor = when {
                !isMine -> kit.card
                msg.deliveryState == MessageDeliveryState.FAILED -> kit.dangerBorder
                else -> kit.blue
            }
            val bubbleStrokeColor = when {
                !isMine -> kit.border
                msg.deliveryState == MessageDeliveryState.FAILED -> kit.danger
                else -> null
            }
            val bubbleTextColor = when {
                !isMine -> kit.ink
                msg.deliveryState == MessageDeliveryState.FAILED -> kit.danger
                else -> Color.WHITE
            }
            val metaTextColor = when {
                !isMine -> kit.muted
                msg.deliveryState == MessageDeliveryState.FAILED -> kit.danger
                else -> Color.argb(215, 235, 244, 255)
            }

            val bubble = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(
                    fillColor = bubbleBgColor,
                    radiusPx = dpF(14f),
                    strokeColor = bubbleStrokeColor,
                    strokeWidthPx = dp(1),
                )
                setPadding(dp(13), dp(9), dp(13), dp(8))
                val maxBubbleW = (activity.resources.displayMetrics.widthPixels * 0.76f).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (isMine) leftMargin = dp(48) else rightMargin = dp(48)
                }
                minimumWidth = dp(72)
                if (msg.deliveryState == MessageDeliveryState.FAILED) {
                    setOnClickListener { retryFailedMessage(msg) }
                }
            }

            val bodyTv = TextView(activity).apply {
                text = msg.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(bubbleTextColor)
                maxWidth = (activity.resources.displayMetrics.widthPixels * 0.72f).toInt()
            }
            bubble.addView(bodyTv)

            val metaText = if (isMine) {
                val receipt = when (msg.deliveryState) {
                    MessageDeliveryState.SENDING -> "Mengirim..."
                    MessageDeliveryState.FAILED -> "Gagal · Ketuk untuk coba lagi"
                    MessageDeliveryState.SENT -> if (msg.isRead) "✓✓ Dibaca" else "✓ Terkirim"
                }
                if (msg.timeLabel.isNotBlank()) "${msg.timeLabel} · $receipt" else receipt
            } else {
                msg.timeLabel
            }

            val metaTv = TextView(activity).apply {
                text = metaText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(metaTextColor)
                gravity = Gravity.END
                setPadding(0, dp(3), 0, 0)
            }
            bubble.addView(metaTv)

            rowWrap.addView(bubble)
            messageListLayout.addView(rowWrap)
        }

        if (scrollToBottom) {
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        radiusPx: Float,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeColor != null && strokeWidthPx > 0) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }
}

internal object ChatDraftStore {
    private val drafts = HashMap<String, String>()

    fun getDraft(conversationId: String): String = drafts[conversationId].orEmpty()

    fun saveDraft(conversationId: String, text: String) {
        if (text.isBlank()) {
            drafts.remove(conversationId)
        } else {
            drafts[conversationId] = text
        }
    }

    fun clearDraft(conversationId: String) {
        drafts.remove(conversationId)
    }

    fun clearAll() {
        drafts.clear()
    }
}
