package ru.mars.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.tv.chat.TvActivityDisplayPolicy
import ru.mars.tv.chat.TvActivityModePrefs
import ru.mars.tv.chat.TvActivityModeSelection
import ru.mars.tv.chat.TvModelChoicePrefs
import ru.mars.tv.chat.TvModelSelection
import ru.mars.tv.chat.TvReasoningChoicePrefs
import ru.mars.tv.chat.TvReasoningSelection
import ru.mars.tv.chat.TvTurnStatePolicy
import ru.mars.tv.push.TvPushRouting

/**
 * Секция «Оболочка экрана» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Привязка разметки, слушатели вида (jump-to-bottom, мини-плеер, чипы), BACK-оверлеи,
 * маршрутизация push и режим показа хода. `onCreate` хоста только зовёт
 * [bindChatViews] и [wireChatSurface] — порядок вызовов и есть контракт проводки.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

/**
 * Маршрутизация подтверждения из push: открывает диалог, к которому относится
 * уведомление, и (для `kind=approval|clarify`) перечитывает его состояние, чтобы
 * ожидающий решения ход был виден сразу — баннером [TvTurnStatePolicy] либо
 * карточкой подтверждения из reattach.
 *
 * @return true, если интент был про диалог и экран уже переключён на него.
 */
internal fun TvChatActivity.routePushIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    val route = TvPushRouting.resolve(
        extraSessionId = intent.getStringExtra(TvPushRouting.EXTRA_SESSION_ID),
        extraKind = intent.getStringExtra(TvPushRouting.EXTRA_KIND),
        dataUri = intent.dataString,
    )
    val sessionId = route.sessionId ?: return false
    // Один и тот же интент не должен перечитывать диалог повторно (recreate,
    // повторная доставка): маршрут считается обработанным один раз.
    if (sessionId == routedPushSessionId) return true
    routedPushSessionId = sessionId
    if (sessionsOverlayReady && sessionsOverlay.visibility == View.VISIBLE) {
        closeSessionBrowser()
    }
    openSession(sessionId)
    return true
}

/** Builds the controller/surface pair: cards live in [messages], focus returns to the composer. */
internal fun TvChatActivity.wireInteractive() {
    interactiveSurface = TvChatInteractiveSurface(
        container = messages,
        onAllCardsCleared = { input.requestFocus() },
        onCardShown = { scrollDown() },
    )
    interactive = TvInteractiveController(
        surface = interactiveSurface,
        gateway = RepositoryTvInteractiveGateway(InteractiveRequestsRepository(), TvChatActivity.BASE_URL) { store.read() },
        sessionProvider = { sessionFlow.sessionId },
        uiDispatcher = MainThreadTvDispatcher(),
    ).also { interactiveSurface.listener = it }
    // WebUI parity: the accepted answer is echoed into the transcript as a user message.
    interactive.onClarifyAnswered = { answer -> append("Вы", answer, isUser = true) }
}

/** Клик кнопки режима: цикл FULL -> WINDOW -> QUIET, выбор персистентен. */
internal fun TvChatActivity.onDisplayModeClicked() {
    val picked = activityMode.cycle()
    renderDisplayModeButton()
    explicitStatus = TvActivityDisplayPolicy.switchNotice(picked)
    // «Тишина»/«Всё» применяются к живому ходу немедленно — статус-полоса перерисовывается.
    renderStatus()
}

/** Подпись кнопки режима в шапке («Режим: Окно»). */
internal fun TvChatActivity.renderDisplayModeButton() {
    displayModeButton.text = TvActivityDisplayPolicy.buttonCaption(activityMode.current)
}

internal fun TvChatActivity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/**
 * Binds the chat layout and installs the view-level listeners: jump-to-bottom control,
 * header mini player, quick chips, overlay BACK callbacks and D-pad text input.
 *
 * Extracted from `onCreate` (W2 decomposition) so the host stays a lifecycle shell —
 * the wiring itself is unchanged, only its file moved.
 */
internal fun TvChatActivity.bindChatViews() {
    messages = findViewById(R.id.messages)
    messageScroll = findViewById(R.id.messageScroll)
    input = findViewById(R.id.messageInput)
    send = findViewById(R.id.sendButton)
    newChat = findViewById(R.id.newChatButton)
    statusView = findViewById(R.id.statusText)
    contextView = findViewById(R.id.contextText)
    contextStatusBadge = findViewById(R.id.contextStatusBadge)
    subagentBoard = findViewById(R.id.subagentBoard)
    quotaChip = findViewById(R.id.quotaChip)
    // Чип квоты интерактивен только там, где повтор осмыслен (политика решает это в
    // canRefresh); клик — повторное чтение квоты, ничего не пишем.
    quotaChip.setOnClickListener {
        if (quotaPolicy.model().canRefresh) refreshProviderQuota()
    }
    val back = findViewById<Button>(R.id.backButton)
    chatRoot = findViewById(R.id.chatRoot)
    sessionsButton = findViewById(R.id.sessionsButton)
    sessionsOverlay = findViewById(R.id.sessionsOverlay)
    sessionsStatus = findViewById(R.id.sessionsStatus)
    sessionList = findViewById(R.id.sessionList)
    closeSessions = findViewById(R.id.closeSessionsButton)
    displayModeButton = findViewById(R.id.displayModeButton)
    // Режим показа хода: восстановленный из SharedPreferences выбор + переключение по клику.
    activityMode = TvActivityModeSelection(
        TvActivityModePrefs(getSharedPreferences(TvActivityModePrefs.PREFS_FILE, Context.MODE_PRIVATE))
    )
    renderDisplayModeButton()
    jumpToBottom = findViewById(R.id.jumpToBottomButton)
    jumpThresholdPx = dp(64)
    jumpToBottom.setOnClickListener {
        renderJumpButton(autoScroll.pinToBottom())
        scrollToBottom()
    }
    // The pin follows the reader: any scroll observation re-evaluates it and drives
    // the jump-control visibility (pure decision — TvAutoScrollPolicy.onScroll).
    messageScroll.setOnScrollChangeListener { _, _, _, _, _ ->
        val child = messageScroll.getChildAt(0) ?: return@setOnScrollChangeListener
        val distance = TvAutoScrollPolicy.distanceFromBottom(
            child.bottom, messageScroll.scrollY, messageScroll.height, messageScroll.paddingBottom,
        )
        renderJumpButton(autoScroll.onScroll(distance, jumpThresholdPx))
    }
    miniPlayerRow = findViewById(R.id.miniPlayerRow)
    miniPlayerText = findViewById(R.id.miniPlayerText)
    miniPlayerToggle = findViewById(R.id.miniPlayerToggle)
    quickChipsRow = findViewById(R.id.quickChipsRow)
    quickChipsScroll = findViewById(R.id.quickChipsScroll)
    modelButton = findViewById(R.id.modelButton)
    reasoningButton = findViewById(R.id.reasoningButton)
    back.setOnClickListener { finish() }
    newChat.setOnClickListener { startNewChat() }
    sessionsButton.setOnClickListener { openSessionBrowser() }
    closeSessions.setOnClickListener { closeSessionBrowser() }
    // Параметры хода: доменные держатели выбора (персистентность совпадает с мобильным
    // клиентом — те же файлы настроек и ключи, если оба клиента живут на одном устройстве).
    modelSelection = TvModelSelection(
        TvModelChoicePrefs(getSharedPreferences(TvModelChoicePrefs.PREFS_FILE, Context.MODE_PRIVATE))
    )
    reasoningSelection = TvReasoningSelection(
        TvReasoningChoicePrefs(getSharedPreferences(TvReasoningChoicePrefs.PREFS_FILE, Context.MODE_PRIVATE))
    )
    modelButton.setOnClickListener { chooseModel() }
    reasoningButton.setOnClickListener { chooseReasoningEffort() }
    wireParamsPicker()
    TvFocusLift.attachAll(reducedMotion, input, send, newChat, back, sessionsButton, closeSessions, displayModeButton, miniPlayerToggle, jumpToBottom, modelButton, reasoningButton, quotaChip, closeParamsButton)
    // Стартовое состояние чипа квоты: скрыт до открытия диалога (провайдер неизвестен).
    renderQuotaChip()
    miniPlayerToggle.setOnClickListener {
        TvMusicPlayer.togglePlayPause()
        renderMiniPlayer()
    }
    // BACK closes the session dialog first; only a closed overlay exits the screen.
}

/**
 * Wires the surface behaviour after the views exist: technical-recreation restore, the
 * composer (send/steer), live-turn status, chips/params rendering and the push/session
 * restore at the end. Order is load-bearing — `wireInteractive` must precede the
 * reattached turn so its cards already have a surface.
 */
internal fun TvChatActivity.wireChatSurface(savedInstanceState: Bundle?) {
    browserBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeSessionBrowser() }
    }
    onBackPressedDispatcher.addCallback(this, browserBackCallback)
    // BACK закрывает пикер параметров хода (зарегистрирован последним — приоритет выше
    // браузера диалогов: оба оверлея одновременно открыты быть не могут).
    onBackPressedDispatcher.addCallback(this, pickerBackCallback)
    controlGateway = RepositoryTvChatControlGateway.create(TvChatActivity.BASE_URL, store)
    // Technical recreation rebinds the persisted session/stream (TvChatLifecyclePolicy):
    // the next send continues the same dialog and «Остановить» still reaches the
    // server turn whose local SSE stream died with the destroyed Activity.
    savedInstanceState?.let { saved ->
        sessionFlow.restoreBinding(
            TvChatLifecyclePolicy.sanitizedBinding(
                saved.getString(TvChatActivity.KEY_SESSION_ID),
                saved.getString(TvChatActivity.KEY_STREAM_ID),
            )
        )
    }
    // Live turn: one button, two roles (mobile parity) — «Уточнить» (steer) with text
    // in the composer, «Остановить» with an empty one. Idle turn: «Отправить».
    displayModeButton.setOnClickListener { onDisplayModeClicked() }
    send.setOnClickListener { dispatchSend() }
    input.setOnEditorActionListener { _, _, _ -> dispatchSend(); true }
    // D-pad в композере: Android EditText сам потребляет стрелки (LEFT/RIGHT —
    // курсор, UP/DOWN — строки), поэтому nextFocusRight на «Отправить» не срабатывал
    // и кнопка была недостижима пультом. Паттерн — ровно как в поиске
    // TvMusicActivity: клавиатура только по явному нажатию, стрелки уводят фокус,
    // при открытой клавиатуре не вмешиваемся (TvDpadTextInput).
    TvDpadTextInput.openImeOnClick(input)
    TvDpadTextInput.escapeArrows(
        input,
        imeVisible = { TvDpadTextInput.isImeVisible(input) },
        up = {
            TvDpadTextInput.focus(quickChipsRow.getChildAt(0)) ||
                TvDpadTextInput.focus(displayModeButton)
        },
        right = { TvDpadTextInput.focus(send) },
    )
    // While a turn is live the primary button flips between «Остановить» and «Уточнить»
    // as the composer gains/loses text — the label must follow every keystroke.
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (sessionFlow.canStop) renderSendControl()
        }
    })
    wireInteractive()
    buildQuickChips()
    // Чипы параметров хода: восстановленный выбор рисуется сразу, затем тихая
    // синхронизация с сервером (каталог моделей и лестница размышлений).
    renderModelChip()
    renderReasoningChip()
    refreshModelCatalog()
    refreshReasoningStatus()
    renderSendControl()
    // Technical recreate: restore the transcript via a LIVE GET /api/session and
    // reattach the turn when it is still streaming server-side (never cancel it).
    // Runs AFTER wireInteractive so the reattached turn's cards have their surface.
    // Пуш важнее восстановленного биндинга: пользователь только что нажал
    // «требуется подтверждение» и ждёт ИМЕННО тот диалог (TvPushRouting).
    if (!routePushIntent(intent) && savedInstanceState != null) {
        sessionFlow.sessionId?.let { restored -> openSession(restored) }
    }
    input.requestFocus()
}
