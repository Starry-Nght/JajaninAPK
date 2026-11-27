package com.jajanin.app.feature.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
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
import com.jajanin.app.core.ui.components.AppIcon
import com.jajanin.app.core.ui.components.StoreVerifiedBadge
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.core.util.statusLabelShort
import com.jajanin.app.data.model.ChatMessage
import com.jajanin.app.data.model.ChatOrderContext
import com.jajanin.app.data.model.ConversationSummary
import com.jajanin.app.data.model.MessageDeliveryState
import com.jajanin.app.data.model.OrderCardPayload
import com.jajanin.app.data.model.ProductCardPayload
import com.jajanin.app.data.model.formatChatBubbleTime
import com.jajanin.app.data.model.parseOrderCardPayload
import com.jajanin.app.data.model.parseProductCardPayload
import com.jajanin.app.data.repository.ChatRepository
import com.jajanin.app.data.repository.ChatRoomSession
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.resolveKnownProduct
import com.jajanin.app.feature.seller.openOrderDetail
import com.jajanin.app.notifications.NotificationHelper
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private data class SellerQuickChatTemplate(
    val title: String,
    val message: String,
)

private val SELLER_QUICK_CHATS = listOf(
    SellerQuickChatTemplate(
        title = "Pesanan Sedang Disiapkan",
        message = "Halo kak, pesananmu sudah kami terima dan sekarang sedang kami siapkan ya. Mohon ditunggu sebentar.",
    ),
    SellerQuickChatTemplate(
        title = "Pesanan Siap Diambil",
        message = "Halo kak, pesananmu sudah matang dan siap diambil sekarang di warung ya. Jangan lupa tunjukkan kode pengambilan saat ambil.",
    ),
    SellerQuickChatTemplate(
        title = "Pembayaran Diverifikasi",
        message = "Halo kak, pembayaran pesananmu sudah kami terima dan verifikasi lunas. Pesanan langsung kami proses ya, terima kasih.",
    ),
    SellerQuickChatTemplate(
        title = "Info Antrean Sedang Ramai",
        message = "Halo kak, saat ini antrean pesanan di warung sedang cukup ramai, mohon kesediaannya menunggu sedikit lebih lama ya. Terima kasih.",
    ),
)

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
    private val inFlightImages = HashSet<String>()
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
    private var quickChatExpanded = false
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
    private var quickChatPanel: LinearLayout? = null
    private var quickChatToggleBtn: ImageView? = null

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

        val backBtn = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = AppIcon.contentDescription("arrowLeft")
            setImageDrawable(AppIcon.drawable(activity, "arrowLeft", 20f, kit.blue))
            setPadding(dp(8), dp(6), dp(10), dp(6))
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
            if (!viewerIsSeller && !summary.stallId.isNullOrBlank()) {
                setOnClickListener {
                    val targetStall = summary.stallId ?: return@setOnClickListener
                    dismiss()
                    com.jajanin.app.feature.buyer.store_profile.StoreProfileViewModel.openStoreProfile(activity, targetStall)
                }
            }
        }
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val nameView = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(kit.ink)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        nameRow.addView(nameView)

        val peerIsDev = StoreVerifiedBadge.isDevIdentity(
            view = activity.ui,
            name = title,
            ownerId = summary.counterpartyId,
            stallId = summary.stallId.orEmpty(),
        )
        val peerVerified = peerIsDev ||
            (!summary.stallId.isNullOrBlank() && activity.ui.storeDirectoryById[summary.stallId]?.isVerified == true)
        if (peerVerified) {
            val verifiedIcon = ImageView(activity).apply {
                setImageDrawable(AppIcon.drawable(activity, "verified", 16f, kit.blue))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginStart = dp(5)
                }
            }
            nameRow.addView(verifiedIcon)
        }
        if (peerIsDev) {
            val devIcon = ImageView(activity).apply {
                setImageDrawable(AppIcon.drawable(activity, "devBadge", 16f, StoreVerifiedBadge.devBadgeColor))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginStart = dp(4)
                }
            }
            nameRow.addView(devIcon)
        }

        subtitleView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(nameRow)
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

        // 2. Context Card Container (Product Inquiry or Order Context with Product Banner)
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

        // 5. Message Composer Bar (+ Seller-Only Expandable 4 Quick-Chat Panel)
        val composerWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.card)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        if (viewerIsSeller) {
            val panel = buildSellerQuickChatPanel()
            quickChatPanel = panel
            composerWrap.addView(panel)
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

        if (viewerIsSeller) {
            val upBtn = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Pesan cepat penjual"
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener {
                    setQuickChatExpanded(!quickChatExpanded)
                }
            }
            quickChatToggleBtn = upBtn
            composerRow.addView(upBtn)
            updateQuickChatToggleAppearance()
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

    private fun buildSellerQuickChatPanel(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedDrawable(kit.bg, dpF(12f), strokeColor = kit.border, strokeWidthPx = dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val headerTitle = TextView(activity).apply {
            text = "PESAN CEPAT PENJUAL · KETUK LANGSUNG KIRIM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeLabel = TextView(activity).apply {
            text = "Tutup"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(kit.muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener { setQuickChatExpanded(false) }
        }
        headerRow.addView(headerTitle)
        headerRow.addView(closeLabel)
        panel.addView(headerRow)

        SELLER_QUICK_CHATS.forEachIndexed { idx, tpl ->
            if (idx > 0) {
                panel.addView(View(activity).apply {
                    setBackgroundColor(kit.divider)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    setQuickChatExpanded(false)
                    sendDirectTextMessage(tpl.message)
                }
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
            }
            val titleTv = TextView(activity).apply {
                text = tpl.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(kit.ink)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val msgTv = TextView(activity).apply {
                text = tpl.message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(kit.muted)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(titleTv)
            textCol.addView(msgTv)
            itemRow.addView(textCol)

            val sendBadge = TextView(activity).apply {
                text = "Kirim"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                background = roundedDrawable(kit.tint, dpF(6f), strokeColor = kit.blue, strokeWidthPx = dp(1))
                setPadding(dp(9), dp(5), dp(9), dp(5))
            }
            itemRow.addView(sendBadge)
            panel.addView(itemRow)
        }

        return panel
    }

    private fun setQuickChatExpanded(expanded: Boolean) {
        if (!viewerIsSeller) return
        quickChatExpanded = expanded
        quickChatPanel?.visibility = if (expanded) View.VISIBLE else View.GONE
        updateQuickChatToggleAppearance()
        if (expanded && messagesById.isNotEmpty()) {
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateQuickChatToggleAppearance() {
        val btn = quickChatToggleBtn ?: return
        val iconName = if (quickChatExpanded) "chevronDown" else "chevronUp"
        val iconColor = if (quickChatExpanded) Color.WHITE else kit.blue
        val bgColor = if (quickChatExpanded) kit.blue else kit.tint
        val strokeColor = if (quickChatExpanded) null else kit.blue
        btn.setImageDrawable(AppIcon.drawable(activity, iconName, 18f, iconColor))
        btn.background = roundedDrawable(bgColor, dpF(20f), strokeColor = strokeColor, strokeWidthPx = dp(1))
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

    private fun resolveOrderBannerImageUrl(orderCtx: ChatOrderContext): String? {
        orderCtx.imageUrl?.takeIf { it.startsWith("https://") }?.let { return it }
        val localOrder = activity.ui.adminOrders.firstOrNull { it.id == orderCtx.id }
            ?: activity.ui.myOrders.firstOrNull { it.id == orderCtx.id }
        val firstItem = localOrder?.items?.firstOrNull()
        if (firstItem != null) {
            activity.ui.resolveOrderItemProduct(firstItem.menuItemId, firstItem.itemName)
                ?.imageUrl
                ?.takeIf { it.startsWith("https://") }
                ?.let { return it }
        }
        orderCtx.firstProductId?.let { pid ->
            activity.resolveKnownProduct(pid)?.imageUrl?.takeIf { it.startsWith("https://") }?.let { return it }
        }
        return summary.productContext?.imageUrl?.takeIf { it.startsWith("https://") }
    }

    private fun ensureChatImageLoaded(url: String?) {
        val clean = url?.takeIf { it.startsWith("https://") } ?: return
        if (ProductImageCache.images.containsKey(clean) || !inFlightImages.add(clean)) return
        activity.authScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val conn = java.net.URL(clean).openConnection().apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val raw = conn.getInputStream().use { it.readBytes() }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            }.getOrNull()
            launch(Dispatchers.Main) {
                inFlightImages.remove(clean)
                if (bitmap != null) {
                    ProductImageCache.images[clean] = bitmap
                    activity.ui.invalidate()
                    if (dialog.isShowing) {
                        renderContextBanner()
                        renderMessageList(scrollToBottom = false)
                    }
                }
            }
        }
    }

    private fun buildRoundedThumbnailView(
        imageUrl: String?,
        fallbackLabel: String,
        sizeDp: Int,
        cornerDp: Float,
        rightMarginDp: Int,
    ): View {
        val validUrl = imageUrl?.takeIf { it.startsWith("https://") }
        val bmp = validUrl?.let { ProductImageCache.images[it] }
        if (bmp != null) {
            return ImageView(activity).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dpF(cornerDp))
                    }
                }
                background = roundedDrawable(kit.card, dpF(cornerDp), strokeColor = kit.border, strokeWidthPx = dp(1))
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                    rightMargin = dp(rightMarginDp)
                }
            }
        }
        if (validUrl != null) {
            ensureChatImageLoaded(validUrl)
        }
        val ch = (fallbackLabel.trim().firstOrNull { it.isLetterOrDigit() } ?: 'P').uppercaseChar().toString()
        return TextView(activity).apply {
            text = ch
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sizeDp >= 64) 19f else 15f)
            setTextColor(kit.blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(kit.card, dpF(cornerDp), strokeColor = kit.border, strokeWidthPx = dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                rightMargin = dp(rightMarginDp)
            }
        }
    }

    private fun renderContextBanner() {
        contextBannerContainer.removeAllViews()
        val orderCtx = summary.orderContext
        val prodCtx = summary.productContext
        val preferProduct = summary.conversationType == "product_inquiry" && prodCtx != null

        if (orderCtx != null && !preferProduct) {
            val liveStatus = resolveLiveOrderStatus(orderCtx.id, orderCtx.status)
            val isConfirmed = liveStatus.equals("confirmed", ignoreCase = true) ||
                liveStatus.equals("preparing", ignoreCase = true) ||
                liveStatus.equals("processing", ignoreCase = true)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(
                    kit.tint,
                    dpF(10f),
                    strokeColor = if (isConfirmed) kit.blue else kit.border,
                    strokeWidthPx = dp(1),
                )
                setPadding(dp(10), dp(9), dp(11), dp(9))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(4))
                }
            }

            // Product Photo Banner Thumbnail so Seller immediately recognizes the bought/inquired item
            val bannerImgUrl = resolveOrderBannerImageUrl(orderCtx)
            val fallbackTitle = orderCtx.firstItemName.ifBlank { orderCtx.itemsSummary.ifBlank { "P" } }
            card.addView(
                buildRoundedThumbnailView(
                    imageUrl = bannerImgUrl,
                    fallbackLabel = fallbackTitle,
                    sizeDp = 48,
                    cornerDp = 8f,
                    rightMarginDp = 10,
                )
            )

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
            }
            val topLine = TextView(activity).apply {
                val stLabel = statusLabelShort(liveStatus)
                text = "Pesanan #${orderCtx.pickupCode} · $stLabel · ${formatRupiah(orderCtx.totalIdr)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(topLine)

            val itemDisplay = orderCtx.itemsSummary.ifBlank {
                orderCtx.firstItemName.ifBlank { "Pesanan Warung" }
            }
            val subLine = TextView(activity).apply {
                text = itemDisplay
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(kit.ink)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(subLine)

            val askedProdName = prodCtx?.name?.trim().orEmpty()
            val extraMeta = when {
                askedProdName.isNotBlank() && !itemDisplay.contains(askedProdName, ignoreCase = true) ->
                    "Ditanyakan juga: $askedProdName"
                orderCtx.scheduleLabel.isNotBlank() -> orderCtx.scheduleLabel
                else -> ""
            }
            if (extraMeta.isNotBlank()) {
                val metaTv = TextView(activity).apply {
                    text = extraMeta
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTextColor(kit.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                infoCol.addView(metaTv)
            }
            card.addView(infoCol)

            val actionBtn = TextView(activity).apply {
                text = "Lihat Pesanan"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                val arrowIcon = AppIcon.drawable(activity, "arrowRight", 12f, Color.WHITE)
                setCompoundDrawables(null, null, arrowIcon, null)
                compoundDrawablePadding = dp(4)
                background = roundedDrawable(kit.blue, dpF(7f))
                setPadding(dp(9), dp(7), dp(9), dp(7))
                setOnClickListener {
                    openOrderScreenFromChat(orderCtx.id)
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
                background = roundedDrawable(bg, dpF(10f), strokeColor = kit.border, strokeWidthPx = dp(1))
                setPadding(dp(10), dp(8), dp(12), dp(8))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(4))
                }
            }

            card.addView(
                buildRoundedThumbnailView(
                    imageUrl = prodCtx.imageUrl,
                    fallbackLabel = prodCtx.name,
                    sizeDp = 44,
                    cornerDp = 8f,
                    rightMarginDp = 10,
                )
            )

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
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

            val matchedProduct = prodCtx.id?.let { pid -> activity.resolveKnownProduct(pid) }
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

    private fun openOrderScreenFromChat(orderId: String) {
        if (orderId.isBlank()) return
        dismiss()
        if (viewerIsSeller) {
            activity.openOrderDetail(orderId)
        } else {
            activity.ui.selectedBuyerOrderId = orderId
            activity.ui.lastOrderId = orderId
            activity.ui.go(Screen.READY)
        }
    }

    private fun resolveLiveOrderStatus(orderId: String, fallbackStatus: String): String {
        if (orderId.isNotBlank()) {
            activity.ui.adminOrders.firstOrNull { it.id == orderId }?.status?.let { return it }
            activity.ui.myOrders.firstOrNull { it.id == orderId }?.status?.let { return it }
            if (summary.orderContext?.id == orderId) {
                return summary.orderContext?.status ?: fallbackStatus
            }
        }
        return fallbackStatus
    }

    private fun formatIndonesianOrderStatus(status: String): Pair<String, Int> {
        return when (status.lowercase()) {
            "confirmed" -> "Dikonfirmasi" to kit.blue
            "preparing", "processing" -> "Sedang Disiapkan" to kit.blue
            "ready" -> "Siap Diambil" to kit.success
            "completed" -> "Selesai" to kit.success
            "cancelled" -> "Dibatalkan" to kit.danger
            else -> "Menunggu Konfirmasi" to Color.rgb(183, 116, 0)
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
        sendDirectTextMessage(clean)
    }

    private fun sendDirectTextMessage(cleanText: String) {
        val clean = cleanText.trim()
        if (clean.isEmpty() || clean.length > 500) return

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

    /**
     * Shopee Image 3 Style: Order Receipt Card automatically inserted when Buyer places an order.
     */
    private fun buildOrderCardBubble(msg: ChatMessage, card: OrderCardPayload): View {
        val outerWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val cardSurface = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(kit.card, dpF(12f), strokeColor = kit.border, strokeWidthPx = dp(1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (card.orderId.isNotBlank()) {
                setOnClickListener { openOrderScreenFromChat(card.orderId) }
            }
        }

        // Inner soft product box (Thumbnail + Item Title + Count/Total + Live Status)
        val innerProductBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(kit.bg, dpF(8f), strokeColor = kit.divider, strokeWidthPx = dp(1))
            setPadding(dp(9), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val resolvedImg = card.imageUrl.takeIf { it.startsWith("https://") }
            ?: summary.orderContext?.takeIf { it.id == card.orderId }?.let { resolveOrderBannerImageUrl(it) }
        innerProductBox.addView(
            buildRoundedThumbnailView(
                imageUrl = resolvedImg,
                fallbackLabel = card.title.ifBlank { card.itemsSummary },
                sizeDp = 56,
                cornerDp = 8f,
                rightMarginDp = 10,
            )
        )

        val prodTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prodTitleTv = TextView(activity).apply {
            text = card.itemsSummary.ifBlank { card.title.ifBlank { "Pesanan #${card.pickupCode}" } }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(kit.ink)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val prodTotalTv = TextView(activity).apply {
            text = "${card.itemCount.coerceAtLeast(1)} item, Total: ${formatRupiah(card.totalIdr)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(kit.muted)
            setPadding(0, dp(2), 0, dp(2))
        }
        val liveSt = resolveLiveOrderStatus(card.orderId, card.status)
        val (stLabel, stColor) = formatIndonesianOrderStatus(liveSt)
        val prodStatusTv = TextView(activity).apply {
            text = stLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(stColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        prodTextCol.addView(prodTitleTv)
        prodTextCol.addView(prodTotalTv)
        prodTextCol.addView(prodStatusTv)
        innerProductBox.addView(prodTextCol)
        cardSurface.addView(innerProductBox)

        // Metadata Rows: No. Pesanan + Salin, Waktu Pesan, Jadwal
        fun addMetaRow(label: String, value: String, copyableCode: String? = null) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(3), dp(2), dp(3))
            }
            val lblTv = TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(kit.muted)
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val valTv = TextView(activity).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(kit.ink)
                typeface = if (copyableCode != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(lblTv)
            row.addView(valTv)

            if (copyableCode != null) {
                val copyPill = TextView(activity).apply {
                    text = "Salin"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(kit.blue)
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedDrawable(kit.tint, dpF(5f), strokeColor = kit.blue, strokeWidthPx = dp(1))
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    setOnClickListener {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nomor Pesanan", "#$copyableCode"))
                        Toast.makeText(activity, "Nomor pesanan #$copyableCode disalin.", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(copyPill)
            }
            cardSurface.addView(row)
        }

        if (card.pickupCode.isNotBlank()) {
            addMetaRow("No. Pesanan", "#${card.pickupCode}", copyableCode = card.pickupCode)
        }
        val shownTime = card.orderTime.ifBlank { msg.timeLabel }
        if (shownTime.isNotBlank()) {
            addMetaRow("Waktu Pesan", shownTime)
        }
        if (card.schedule.isNotBlank()) {
            addMetaRow("Jadwal Ambil", card.schedule)
        }

        if (card.orderId.isNotBlank()) {
            val openBtn = TextView(activity).apply {
                text = "Lihat Rincian Pesanan"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundedDrawable(kit.tint, dpF(7f), strokeColor = kit.blue, strokeWidthPx = dp(1))
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(8)
                }
                setOnClickListener { openOrderScreenFromChat(card.orderId) }
            }
            cardSurface.addView(openBtn)
        }

        outerWrap.addView(cardSurface)

        if (msg.timeLabel.isNotBlank()) {
            val timeFooter = TextView(activity).apply {
                text = msg.timeLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(kit.muted)
                gravity = Gravity.END
                setPadding(0, dp(3), dp(4), 0)
            }
            outerWrap.addView(timeFooter)
        }

        return outerWrap
    }

    /**
     * Shopee Image 4 Style: Visual Product/Order Showcase Card sent when Buyer taps
     * "Chat Penjual" on their Order page or "Tanya Penjual" on a Product page.
     */
    private fun buildProductCardBubble(msg: ChatMessage, card: ProductCardPayload, isMine: Boolean): View {
        val screenW = activity.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.80f).toInt().coerceAtLeast(dp(250))

        val rowWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isMine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(9)
            }
        }

        val cardSurface = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(kit.card, dpF(12f), strokeColor = kit.border, strokeWidthPx = dp(1))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val resolvedImg = card.imageUrl.takeIf { it.startsWith("https://") }
            ?: card.productId.takeIf { it.isNotBlank() }?.let { pid ->
                activity.resolveKnownProduct(pid)?.imageUrl?.takeIf { it.startsWith("https://") }
            }
            ?: summary.orderContext?.takeIf { it.id == card.orderId }?.let { resolveOrderBannerImageUrl(it) }

        topRow.addView(
            buildRoundedThumbnailView(
                imageUrl = resolvedImg,
                fallbackLabel = card.title,
                sizeDp = 76,
                cornerDp = 8f,
                rightMarginDp = 10,
            )
        )

        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(activity).apply {
            text = card.title.ifBlank { "Produk Warung" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(kit.ink)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        infoCol.addView(titleTv)

        if (card.subtitle.isNotBlank()) {
            val subTv = TextView(activity).apply {
                text = "${card.subtitle} >"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(kit.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(3))
            }
            infoCol.addView(subTv)
        }

        val badgeText = if (card.orderId.isNotBlank()) {
            val liveSt = resolveLiveOrderStatus(card.orderId, "")
            if (liveSt.isNotBlank()) formatIndonesianOrderStatus(liveSt).first else card.badge
        } else {
            card.badge
        }
        if (badgeText.isNotBlank()) {
            val badgePill = TextView(activity).apply {
                text = badgeText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                background = roundedDrawable(kit.tint, dpF(4.5f), strokeColor = kit.blue, strokeWidthPx = dp(1))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(3)
                }
            }
            infoCol.addView(badgePill)
        }

        if (card.schedule.isNotBlank()) {
            val schedTv = TextView(activity).apply {
                text = card.schedule
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(kit.success)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(schedTv)
        }

        if (card.priceIdr > 0) {
            val priceTv = TextView(activity).apply {
                text = formatRupiah(card.priceIdr)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTextColor(kit.blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(priceTv)
        }

        topRow.addView(infoCol)
        cardSurface.addView(topRow)

        // Bottom Split Action Bar (Shopee Image 4 style: Icon Tile + Primary CTA Button)
        val actionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }

        val onCardClick: (View) -> Unit = {
            when {
                card.orderId.isNotBlank() -> openOrderScreenFromChat(card.orderId)
                card.productId.isNotBlank() -> {
                    val prod = activity.resolveKnownProduct(card.productId)
                    if (prod != null && !viewerIsSeller) {
                        dismiss()
                        activity.ui.selectedProduct = prod
                        activity.ui.quantity = 1
                        activity.ui.go(Screen.DETAIL)
                    } else {
                        Toast.makeText(activity, card.title.ifBlank { "Menu warung" }, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        cardSurface.setOnClickListener(onCardClick)

        val iconTile = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER
            val iconKey = if (card.orderId.isNotBlank()) "receipt" else "box"
            contentDescription = AppIcon.contentDescription(iconKey)
            setImageDrawable(AppIcon.drawable(activity, iconKey, 16f, kit.blue))
            background = roundedDrawable(kit.tint, dpF(7f), strokeColor = kit.border, strokeWidthPx = dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                rightMargin = dp(6)
            }
            setOnClickListener(onCardClick)
        }
        actionBar.addView(iconTile)

        val ctaText = when {
            card.orderId.isNotBlank() && viewerIsSeller -> "Buka Pesanan #${card.pickupCode}"
            card.ctaLabel.isNotBlank() -> card.ctaLabel
            card.orderId.isNotBlank() -> "Lihat Rincian Pesanan"
            else -> "Lihat Detail Menu"
        }
        val primaryCta = TextView(activity).apply {
            text = ctaText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(kit.blue, dpF(7f))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener(onCardClick)
        }
        actionBar.addView(primaryCta)
        cardSurface.addView(actionBar)
        rowWrap.addView(cardSurface)

        // Timestamp + Read Receipt below card (matches Image 4 "20:56 ✓✓")
        val receiptTv = TextView(activity).apply {
            text = msg.timeLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(kit.muted)
            gravity = Gravity.CENTER_VERTICAL
            if (isMine && msg.deliveryState == MessageDeliveryState.SENT) {
                val receiptIcon = AppIcon.drawable(
                    activity,
                    if (msg.isRead) "checkDouble" else "check",
                    12f,
                    if (msg.isRead) kit.blue else kit.muted,
                )
                setCompoundDrawables(null, null, receiptIcon, null)
                compoundDrawablePadding = dp(4)
            }
            setPadding(dp(4), dp(3), dp(4), 0)
        }
        rowWrap.addView(receiptTv)

        return rowWrap
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

            if (msg.messageType == "order_card") {
                val parsedOrderCard = parseOrderCardPayload(msg.body)
                if (parsedOrderCard != null) {
                    messageListLayout.addView(buildOrderCardBubble(msg, parsedOrderCard))
                    return@forEach
                }
            }

            if (msg.messageType == "product_card") {
                val parsedProductCard = parseProductCardPayload(msg.body)
                if (parsedProductCard != null) {
                    messageListLayout.addView(buildProductCardBubble(msg, parsedProductCard, isMine))
                    return@forEach
                }
            }

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
                    MessageDeliveryState.SENT -> if (msg.isRead) "Dibaca" else "Terkirim"
                }
                if (msg.timeLabel.isNotBlank()) "${msg.timeLabel} · $receipt" else receipt
            } else {
                msg.timeLabel
            }

            if (msg.messageType == "auto_reply") {
                val footerRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, 0)
                }
                val autoTagTv = TextView(activity).apply {
                    text = "Auto-Reply"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(metaTextColor)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(12)
                    }
                }
                val timeTv = TextView(activity).apply {
                    text = metaText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(metaTextColor)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                footerRow.addView(autoTagTv)
                footerRow.addView(timeTv)
                bubble.addView(footerRow)
            } else {
                val metaTv = TextView(activity).apply {
                    text = metaText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(metaTextColor)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    if (isMine && msg.deliveryState == MessageDeliveryState.SENT) {
                        val receiptIcon = AppIcon.drawable(
                            activity,
                            if (msg.isRead) "checkDouble" else "check",
                            12f,
                            metaTextColor,
                        )
                        setCompoundDrawables(null, null, receiptIcon, null)
                        compoundDrawablePadding = dp(4)
                    }
                    setPadding(0, dp(3), 0, 0)
                }
                bubble.addView(metaTv)
            }

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
