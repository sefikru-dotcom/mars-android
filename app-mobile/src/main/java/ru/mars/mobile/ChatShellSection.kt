package ru.mars.mobile

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionUsage
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionToolsetsOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionAutonomyRepository
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.TranscribeOutcome
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.network.repository.readBoundedUploadPayload

/**
 * Оболочка экрана чата: каркас, шторка разделов, чипы, скролл, мини-плеер.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

internal fun ChatActivity.buildView(): View {
    // Вуаль экрана живёт ЗДЕСЬ и только здесь: под ней оконный кадр
    // (@drawable/mars_window_bg). Контент чата прозрачный (ChatScreenScaffold),
    // поэтому кадр виден на всём экране; шторка разделов остаётся плотной
    // (SIDEBAR) — ей нужна своя подложка, иначе подписи поедут по картинке.
    //
    // Почему это важно: раньше ту же вуаль ставил ещё и корень каркаса чата
    // (ChatScreenScaffold), то есть она накладывалась дважды. 0x6B два раза —
    // это альфа 0.66 вместо 0.42: кадр давал 13/255 на диске планеты, и экран
    // читался как чёрный, а «стеклянные» карточки просвечивали пустотой.
    drawer = DrawerLayout(this).apply { setBackgroundColor(MarsTheme.SCREEN_VEIL) }
    drawer.addView(buildChatContent(), DrawerLayout.LayoutParams(-1, -1))
    drawer.addView(buildSectionsDrawer(), DrawerLayout.LayoutParams(dp(310), -1, GravityCompat.START))
    return drawer
}

/**
 * Шторка левого бара: ТОЛЬКО приложенческие разделы (AppSectionsBar/
 * AppSectionsPolicy). Прежняя смешанная панель («Сессии» + новый диалог +
 * поиск + сетка разделов + список диалогов) разделена двухуровневым
 * навигатором: разделы остаются здесь, список диалогов — отдельный уровень
 * ChatSessionsActivity (корень раздела CHAT). Дубль входа «Настройки» в
 * хедере сокращён: настройки (и logout) доступны только своим разделом бара.
 */
internal fun ChatActivity.buildSectionsDrawer(): View {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setBackgroundColor(MarsTheme.SIDEBAR)
        isClickable = true
    }
    ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(dp(16), systemBars.top + dp(8), dp(16), systemBars.bottom + dp(8))
        insets
    }

    val headerRow = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))
    }
    headerRow.addView(TextView(this).apply {
        text = "Разделы"
        textSize = 20f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.05f
    }, LinearLayout.LayoutParams(0, -2, 1f))

    val closeBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_close,
        "Закрыть панель",
        bg = MarsTheme.SURFACE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 36
    ).apply {
        setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
    }
    headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
    panel.addView(headerRow)

    panel.addView(
        AppSectionsBar(
            context = this,
            currentSection = { AppDestination.CHAT },
            onSectionClick = ::onSectionSelected,
        ).build(),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    )
    return panel
}

/**
 * Клик по разделу левого бара — живой [AppNavPolicy.selectSection]: раздел
 * становится текущим и сбрасывается на корень. CHAT → корень раздела
 * (уровень списка диалогов, отдельный экран); остальные разделы → свои
 * Activity через [AppNavIntents.sectionIntent] (legacy-маршрутизатор
 * NavTarget → Activity из ChatActivity удалён).
 */
internal fun ChatActivity.onSectionSelected(destination: AppDestination) {
    drawer.closeDrawer(GravityCompat.START)
    val current = chatNavState()
    val next = AppNavPolicy.selectSection(current, destination)
    if (next == current) return // идемпотентно: уже на корне раздела
    if (next.section == AppDestination.CHAT) {
        navigateToSessionList()
        return
    }
    startActivity(AppNavIntents.sectionIntent(this, destination, currentSessionId))
}

/**
 * Живое навигационное состояние экрана ([AppNavState]): ChatActivity —
 * уровень диалога раздела CHAT, всегда [ChatLevel.Session]. «Новый чат»,
 * ещё не созданный на сервере, представляется UI-ключом [ChatActivity.NEW_CHAT_NAV_KEY]
 * (плейсхолдер не покидает UI-слой), поэтому ChatActivity никогда не
 * является корнем раздела — корень хостит ChatSessionsActivity.
 */
internal fun ChatActivity.chatNavState(): AppNavState = AppNavPolicy.openSession(
    AppNavState.INITIAL,
    currentSessionId?.trim().orEmpty().ifEmpty { ChatActivity.NEW_CHAT_NAV_KEY },
)

/**
 * чат → диалоги: корневой уровень раздела CHAT. CLEAR_TOP|SINGLE_TOP
 * переиспользует уже существующий ChatSessionsActivity из стека и
 * закрывает диалог над ним — стек остаётся двухуровневым.
 */
internal fun ChatActivity.navigateToSessionList() {
    startActivity(AppNavIntents.sessionListIntent(this))
    finish()
}

/**
 * Каркас экрана чата собирает [ChatScreenScaffold] (чистый рефакторинг: вид и поведение
 * прежние — тот же порядок вставки, те же LayoutParams, тексты и обработчики). Activity
 * остаётся тонким делегатом: отдаёт каркасу колбэки и подписи чипов, а полученные view
 * раскладывает по своим полям — на них по-прежнему смотрят update*-методы и рендер
 * транскрипта в этом классе.
 */
internal fun ChatActivity.buildChatContent(): View {
    val v = ChatScreenScaffold.build(this, scaffoldHost)
    networkBanner = v.networkBanner
    sessionTitleView = v.sessionTitle
    miniPlayerBox = v.miniPlayer.box
    miniPlayerTitle = v.miniPlayer.title
    miniPlayerPlayBtn = v.miniPlayer.playBtn
    statusView = v.statusView
    searchPlate = v.search.plate
    searchInput = v.search.input
    searchCounter = v.search.counter
    attachmentChips = v.attachmentChips
    scroll = v.transcript.scroll
    messages = v.transcript.messages
    scrollToBottomFab = v.transcript.fab
    quickChipsScroll = v.quickChipsScroll
    smartChipsRow = v.smartChipsRow
    todoWidgetPlate = v.todo.plate
    todoWidgetHeader = v.todo.header
    todoWidgetTitle = v.todo.title
    todoWidgetChevron = v.todo.chevron
    todoWidgetContainer = v.todo.container
    liveRunPlate = v.liveRun.plate
    liveRunLabel = v.liveRun.label
    liveRunDetail = v.liveRun.detail
    queuePlate = v.queue.plate
    queueTitle = v.queue.title
    queueRows = v.queue.rows
    queueDiscardAll = v.queue.discardAll
    goalPlate = v.goal.plate
    goalDot = v.goal.dot
    goalTitle = v.goal.title
    goalDetail = v.goal.detail
    composerQuoteView = v.composer.quoteView
    composerQuoteTitle = v.composer.quoteTitle
    composerQuoteExcerpt = v.composer.quoteExcerpt
    input = v.composer.input
    // O7: реальный фокус поля — вход в [ComposerInputGuardPolicy]. Без него политика
    // не знает, был ли фокус у композера до обрыва связи, и не восстановит его.
    input.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
        composerGuard.onComposerFocusChanged(focused)
    }
    modelBtn = v.composer.modelBtn
    reasoningBtn = v.composer.reasoningBtn
    activityModeBtn = v.composer.activityModeBtn
    workspaceBtn = v.composer.workspaceBtn
    contextRing = v.composer.contextRing
    contextRow = v.composer.contextRow
    contextCaption = v.composer.contextCaption
    usageIndicator = v.composer.usageIndicator
    toolsetsChip = v.composer.toolsetsChip
    quotaChip = v.composer.quotaChip
    voiceBtn = v.composer.voiceBtn
    volumeBtn = v.composer.volumeBtn
    sendButton = v.composer.sendButton
    // Прежний вызов в конце сборки композера: поля уже разложены, кнопка озвучки
    // получает тон по текущему режиму автоозвучивания (до attach, как и раньше).
    updateVolumeBtn()
    // Кнопка микрофона получает нейтральный вид (режим выключен) до первого кадра.
    renderVoiceButton()
    return v.root
}

/**
 * Хост каркаса экрана: каркас не держит Activity, поэтому всё, что раньше он дёргал
 * напрямую, приходит отсюда. Вызовы с теми же именами, что у методов Activity,
 * квалифицированы `this@createScaffoldHost` — иначе был бы рекурсивный вызов себя.
 */
internal fun ChatActivity.createScaffoldHost(): ChatScreenScaffold.Host = object : ChatScreenScaffold.Host {
    override fun onMenuClick() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            drawer.openDrawer(GravityCompat.START)
        }
    }

    override fun onNewChat() {
        this@createScaffoldHost.startNewChat()
    }

    override fun openMusic() {
        startActivity(Intent(this@createScaffoldHost, MusicActivity::class.java))
    }

    override fun navigateTranscriptSearch(forward: Boolean) {
        this@createScaffoldHost.navigateTranscriptSearch(forward)
    }

    override fun refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean) {
        this@createScaffoldHost.refreshTranscriptSearch(resetPosition = resetPosition, scrollToMatch = scrollToMatch)
    }

    override fun closeTranscriptSearch() {
        this@createScaffoldHost.closeTranscriptSearch()
    }

    // Прокрутка транскрипта питает политики так же, как прежний inline-листенер:
    // расстояние до низа -> auto-scroll -> видимость FAB.
    override fun onTranscriptScrolled() {
        val distance = distanceFromBottomPx()
        autoScroll.onViewportDistanceFromBottom(distance)
        scrollToBottomPolicy.onViewportDistanceFromBottom(distance)
        syncScrollToBottomFab()
    }

    override fun onJumpToLatest() {
        jumpToLatestFromFab()
    }

    override fun quickSend(text: String) {
        this@createScaffoldHost.quickSend(text)
    }

    override fun finishSession() {
        this@createScaffoldHost.finishSession()
    }

    override fun toggleTodoWidget() {
        todoWidgetExpanded = !todoWidgetExpanded
        updateTodoWidget()
    }

    override fun retryQueuedMessage(messageId: String) {
        this@createScaffoldHost.retryQueuedMessage(messageId)
    }

    override fun discardQueuedMessage(messageId: String) {
        this@createScaffoldHost.requestDiscardQueued(messageId)
    }

    override fun discardAllQueuedMessages() {
        this@createScaffoldHost.requestDiscardAllQueued()
    }

    override fun dismissGoalCard() {
        goalCard = GoalLoopPolicy.hidden()
        updateGoalCard()
    }

    override fun onComposerTextChanged(text: String) {
        if (composerTextProgrammatic) return
        // O7: текст набран человеком — с этого момента программные черновики его не
        // перезаписывают (решение принимает [ComposerInputGuardPolicy]).
        composerGuard.onUserEdited()
        scheduleServerDraftSync(text)
    }

    override fun sendMessage() {
        this@createScaffoldHost.sendMessage()
    }

    override fun clearQuote() {
        composerLayoutPresenter.clearQuote()
        updateQuotePlate()
    }

    override fun modelChipLabel(): String = modelSelection.buttonLabel() ?: "Модель"

    override fun activityModeChipLabel(): String = activityModeSelection.buttonLabel()

    override fun workspaceChipLabel(): String =
        workspaceSelection.buttonLabel() ?: "Workspace"

    override fun toolsetsChipLabel(): String = toolsetsPresenter.chipLabel()

    override fun toolsetsChipDescription(): String = toolsetsPresenter.chipDescription()

    override fun chooseModel() {
        this@createScaffoldHost.chooseModel()
    }

    override fun chooseReasoningEffort() {
        this@createScaffoldHost.chooseReasoningEffort()
    }

    override fun chooseActivityMode() {
        this@createScaffoldHost.chooseActivityMode()
    }

    override fun chooseWorkspace() {
        this@createScaffoldHost.chooseWorkspace()
    }

    override fun showContextUsageDetails() {
        this@createScaffoldHost.showContextUsageDetails()
    }

    override fun showSessionUsageDetails() {
        this@createScaffoldHost.showSessionUsageDetails()
    }

    override fun showQuotaDetails() {
        this@createScaffoldHost.showQuotaDetails()
    }

    override fun showToolsetsDialog() {
        this@createScaffoldHost.showToolsetsDialog()
    }

    override fun openTranscriptSearch() {
        this@createScaffoldHost.openTranscriptSearch()
    }

    override fun chooseAttachment() {
        this@createScaffoldHost.chooseAttachment()
    }

    override fun startVoiceInput() {
        this@createScaffoldHost.startVoiceInput()
    }

    override fun toggleHandsFree(): Boolean = this@createScaffoldHost.toggleHandsFree()

    override fun speakLastMessage() {
        this@createScaffoldHost.speakLastMessage()
    }

    override fun toggleAutoSpeak(): Boolean {
        voiceOutput.setAutoSpeak(!voiceOutput.autoSpeakEnabled)
        updateVolumeBtn()
        return voiceOutput.autoSpeakEnabled
    }

    override fun isTyping(): Boolean = uiState.status == ChatStatus.TYPING

    override fun cancelStream() {
        this@createScaffoldHost.cancelStream()
    }
}

internal fun ChatActivity.quickSend(text: String) {
    input.setText(text)
    sendMessage()
}

/** Обработчик перехода сети: баннер обновляется всегда, восстановление связи запускает флаш. */
internal fun ChatActivity.onNetworkTransition(transition: NetworkTransition) {
    updateNetworkBanner()
    // O7: сеть — не владелец композера: текст не трогаем, но фокус, потерянный на
    // обрыве, возвращаем одноразово и только после того, как связь успокоилась.
    composerGuard.onNetworkTransition(transition)
    restoreComposerFocusIfNeeded()
    // Сеть кормится в recovery-цикл: offline → парковка (WaitForNetwork), online →
    // пробуждение и немедленный reattach. LOSING трактуется как offline (сеть сейчас умрёт).
    if (isRecoveryReady()) recovery.onNetworkChanged(transition.current.sendAllowed)
    if (transition.restored) {
        flushOfflineQueue()
        // Список сессий на восстановлении связи обновляется через throttle: моргающая
        // сеть (серия restored-переходов) не должна устраивать шторм GET /api/sessions.
        // Стартовый снимок сети (до первичного восстановления диалога) не триггерит:
        // onCreate сам грузит список — так же, как gated автофлаш очереди выше.
        if (initialRestoreDone) requestSessionListRefresh()
    }
}

/** Баннер: решение (видимость/текст/тон) — чистая NetworkBannerPolicy; хост мапит тон на MarsTheme. */
internal fun ChatActivity.updateNetworkBanner() {
    if (!isNetworkBannerReady()) return
    // Карточка очереди живёт по тем же триггерам, что и баннер (сеть + размер очереди),
    // поэтому обновляется здесь же — до раннего выхода «баннер не нужен».
    updateOfflineQueuePanel()
    val pendingCount = if (isOfflineQueueReady()) offlineQueue.size else 0
    val banner = bannerPolicy.bannerFor(networkPolicy.state, pendingCount)
    networkBanner.visibility = if (banner.visible) View.VISIBLE else View.GONE
    if (!banner.visible) return
    val tone = when (banner.tone) {
        NetworkBannerUi.Tone.OK -> MarsTheme.STATUS_ONLINE
        NetworkBannerUi.Tone.WARNING -> MarsTheme.STATUS_WARNING
        NetworkBannerUi.Tone.ERROR -> MarsTheme.STATUS_ERROR
        NetworkBannerUi.Tone.INFO -> MarsTheme.STATUS_INFO
    }
    networkBanner.text = banner.text
    networkBanner.setTextColor(tone)
    networkBanner.background = MarsTheme.card(this, MarsTheme.SURFACE_CARD, radiusDp = 10, strokeColor = tone)
}

/**
 * Доступный заголовок раскрывающейся карточки (thinking/tool/todo): роль кнопки
 * (TalkBack озвучивает «Кнопка» и активирует двойным тапом), единая подпись с
 * состоянием и stateDescription. Тексты решает чистая [ChatA11yPolicy];
 * semantics клика (toggle + renderMessages) остаётся за вызывающим кодом.
 */
internal fun ChatActivity.applyExpandableHeaderA11y(header: View, description: String, state: String) {
    header.contentDescription = description
    ViewCompat.setStateDescription(header, state)
    ViewCompat.setAccessibilityDelegate(header, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    })
}

/**
 * Делегат a11y-хелпера для секций-расширений: [applyExpandableHeaderA11y] остаётся
 * приватным (контракт проверяет его объявление и делегат в этом файле).
 */
internal fun ChatActivity.applyExpandableHeaderA11yFromSection(header: View, description: String, state: String) =
    applyExpandableHeaderA11y(header, description, state)

/**
 * Smart Quick Chips ribbon (WebUI parity): one-tap conversation actions above
 * the composer. Visibility is decided by the pure [QuickChips] policy; icons are
 * monochrome vectors per the design system (no emoji).
 */
internal fun ChatActivity.renderSmartChips() {
    if (!isSmartChipsRowReady()) return
    val lastAssistant = uiState.messages.lastOrNull { it.role == "assistant" }
    val hasReply = lastAssistant != null &&
        (lastAssistant.text.isNotBlank() || lastAssistant.tools.isNotEmpty() || lastAssistant.thoughts.isNotEmpty())
    val lastTurnFailed = lastAssistant?.status == MessageStatus.ERROR
    // O9: пустой диалог — стартовые подсказки [SmartSuggestionsPolicy.STARTER]
    // (вход в разговор), дальше лента принадлежит [QuickChips] (реакция на ход).
    val emptyChat = uiState.messages.isEmpty()
    // Лента зависит только от этих входов: тот же набор — перерисовка не нужна.
    // Раньше на КАЖДОМ кадре стрима сносились и заново инфлейтились кнопки с фонами.
    if (!chipsGate.shouldRender(
            QuickChipsRenderGate.Signature(uiState.status, hasReply, lastTurnFailed, emptyChat)
        )
    ) return
    smartChipsRow.removeAllViews()
    if (emptyChat) {
        val starters = SmartSuggestionsPolicy.suggestionsFor(
            status = uiState.status,
            hasMessages = false,
        )
        smartChipsRow.visibility = if (starters.isEmpty()) View.GONE else View.VISIBLE
        if (starters.isEmpty()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        starters.forEach { s ->
            row.addView(
                buildRibbonChip(s.label, starterIcon(s.id), s.message),
                LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
            )
        }
        smartChipsRow.addView(row)
        return
    }
    val chips = QuickChips.visibleChips(
        status = uiState.status,
        hasAssistantReply = hasReply,
        lastTurnFailed = lastTurnFailed,
    )
    smartChipsRow.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
    if (chips.isEmpty()) return
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    chips.forEach { chip ->
        row.addView(
            buildSmartChip(chip),
            LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        )
    }
    smartChipsRow.addView(row)
}

internal fun ChatActivity.buildSmartChip(chip: QuickChip): Button =
    buildRibbonChip(chip.label, smartChipIcon(chip.id), chip.message)

/** Одна кнопка ленты: монохромная иконка + подпись, без эмодзи и типографских глифов. */
internal fun ChatActivity.buildRibbonChip(label: String, iconRes: Int, message: String): Button = Button(this).apply {
    text = label
    textSize = 12f
    isAllCaps = false
    setTextColor(MarsTheme.TEXT)
    background = MarsTheme.pill(this@buildRibbonChip, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER_MUTED)
    setPadding(dp(12), dp(4), dp(14), dp(4))
    setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
    compoundDrawablePadding = dp(6)
    compoundDrawableTintList = ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
    gravity = Gravity.CENTER
    minimumHeight = 0
    minHeight = 0
    stateListAnimator = null
    contentDescription = label
    setOnClickListener { quickSend(message) }
}

internal fun ChatActivity.starterIcon(id: SmartSuggestionId): Int = when (id) {
    SmartSuggestionId.SERVER_STATUS -> R.drawable.ic_status
    SmartSuggestionId.DAY_SUMMARY -> R.drawable.ic_insights
    SmartSuggestionId.PROJECTS -> R.drawable.ic_projects
    SmartSuggestionId.MUSIC -> R.drawable.ic_music
    SmartSuggestionId.CONTINUE -> R.drawable.ic_play
    SmartSuggestionId.EXPLAIN -> R.drawable.ic_help
}

internal fun ChatActivity.smartChipIcon(id: QuickChipId): Int = when (id) {
    QuickChipId.OK -> R.drawable.ic_check
    QuickChipId.CONTINUE -> R.drawable.ic_play
    QuickChipId.ROLLBACK -> R.drawable.ic_undo
    QuickChipId.EXPLAIN -> R.drawable.ic_help
    QuickChipId.PLAN -> R.drawable.ic_plan
    QuickChipId.STATUS -> R.drawable.ic_status
    QuickChipId.RETRY -> R.drawable.ic_refresh
}

internal fun ChatActivity.scrollToBottom() {
    // Any explicit jump to the latest hides the FAB right away instead of waiting for the
    // posted fullScroll to report back through the scroll listener.
    scrollToBottomPolicy.onJumpToLatest()
    syncScrollToBottomFab()
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

/**
 * FAB tap: one-tap return to the latest message — re-pins the stream-following viewport
 * ([AutoScrollPolicy]) so incoming tokens pull the reader along again, then scrolls down.
 */
internal fun ChatActivity.jumpToLatestFromFab() {
    autoScroll.pinToBottom()
    scrollToBottom()
}

/** Applies the pure [ScrollToBottomPolicy] decision to the FAB; safe before the view exists. */
internal fun ChatActivity.syncScrollToBottomFab() {
    scrollToBottomFab?.visibility = if (scrollToBottomPolicy.visible) View.VISIBLE else View.GONE
}

/**
 * Distance (px) between the bottom of the transcript content and the bottom of the visible
 * viewport; 0 while pinned at the latest message and before the views exist.
 */
internal fun ChatActivity.distanceFromBottomPx(): Int {
    if (!isScrollReady()) return 0
    val child = scroll.getChildAt(0) ?: return 0
    return (child.bottom - (scroll.height + scroll.scrollY)).coerceAtLeast(0)
}

internal fun ChatActivity.updateMiniPlayer(track: MusicTrack?, isPlaying: Boolean) {
    // Presentation logic (visibility, header formatting, play/pause status) is pure — MiniPlayerPresenter.
    val state = MiniPlayerPresenter.resolve(track, isPlaying)
    // Decide from the INTENDED visibility, not View.visibility: mid-hide the view is
    // still VISIBLE, so a track arriving inside that window would be dropped and the
    // hide would land GONE on a live track. Comparing intent lets the reveal cancel it.
    when (MiniPlayerPresenter.motionAction(miniPlayerVisible, state.visible)) {
        MiniPlayerAction.HIDE -> {
            miniPlayerVisible = false
            applyMiniPlayerMotion(MiniPlayerPresenter.hideMotion(isReducedMotion()), appearing = false)
            return
        }
        MiniPlayerAction.REVEAL -> {
            miniPlayerVisible = true
            miniPlayerTitle.text = state.titleText
            miniPlayerPlayBtn.contentDescription = state.statusLabel
            miniPlayerPlayBtn.setImageDrawable(
                ContextCompat.getDrawable(this, if (state.showPauseIcon) R.drawable.ic_pause else R.drawable.ic_play)
            )
            // Only a fresh reveal restarts the fade; a status-only change must not.
            applyMiniPlayerMotion(MiniPlayerPresenter.showMotion(isReducedMotion()), appearing = true)
            return
        }
        MiniPlayerAction.NONE -> Unit
    }
    if (!state.visible) return
    miniPlayerTitle.text = state.titleText
    miniPlayerPlayBtn.contentDescription = state.statusLabel
    miniPlayerPlayBtn.setImageDrawable(
        ContextCompat.getDrawable(this, if (state.showPauseIcon) R.drawable.ic_pause else R.drawable.ic_play)
    )
}

/** Reads Settings.Global.ANIMATOR_DURATION_SCALE — the same source MusicActivity uses. */
internal fun ChatActivity.isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
)

/**
 * Calm fade+slide of the mini-player strip (alpha + translationY only, no scale).
 * Under reduced motion [MiniPlayerMotion.instant] applies the final state without
 * starting any animator.
 */
internal fun ChatActivity.applyMiniPlayerMotion(motion: MiniPlayerMotion, appearing: Boolean) {
    miniPlayerBox.animate().cancel()
    val density = resources.displayMetrics.density
    if (motion.instant) {
        miniPlayerBox.alpha = motion.alphaTo
        miniPlayerBox.translationY = motion.translationToDp * density
        miniPlayerBox.visibility = if (appearing) View.VISIBLE else View.GONE
        return
    }
    miniPlayerBox.visibility = View.VISIBLE
    miniPlayerBox.alpha = motion.alphaFrom
    miniPlayerBox.translationY = motion.translationFromDp * density
    miniPlayerBox.animate()
        .alpha(motion.alphaTo)
        .translationY(motion.translationToDp * density)
        .setDuration(motion.durationMs.toLong())
        .setInterpolator(DecelerateInterpolator())
        .withEndAction { if (!appearing) miniPlayerBox.visibility = View.GONE }
        .start()
}
