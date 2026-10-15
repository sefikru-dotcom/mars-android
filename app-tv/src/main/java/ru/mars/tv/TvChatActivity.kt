package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.StreamResumeTracker
import ru.mars.tv.chat.TvActivityDisplayMode
import ru.mars.tv.chat.TvActivityDisplayPolicy
import ru.mars.tv.chat.TvActivityModePrefs
import ru.mars.tv.chat.TvActivityModeSelection
import ru.mars.tv.chat.TvLiveRenderBatchPolicy
import ru.mars.tv.chat.TvMarkdownBlocks
import ru.mars.tv.chat.TvMarkdownRenderer
import ru.mars.tv.chat.TvModelChoicePrefs
import ru.mars.tv.chat.TvModelPickerEntry
import ru.mars.tv.chat.TvModelSelection
import ru.mars.tv.chat.TvReasoningChoicePrefs
import ru.mars.tv.chat.TvReasoningSelection
import ru.mars.tv.chat.TvSteerPolicy
import ru.mars.tv.chat.TvTranscriptRetentionPolicy
import ru.mars.tv.chat.TvTurnParamsPolicy

/**
 * TV chat screen: transcript, composer and the live turn status strip.
 *
 * A running turn is STOPPABLE (mobile cancelStream parity): while the turn is live the
 * primary button becomes «Остановить» and posts /api/chat/cancel ([TvChatControlGateway])
 * before tearing the local stream down. «Новый чат» and an EXPLICIT exit (BACK/finish,
 * i.e. onDestroy with isFinishing) cancel the server turn best-effort as well — a merely
 * abandoned turn would keep executing invisibly (tokens, commands, or blocked on an
 * approval card). NAVIGATION (opening another dialog) cancels only the LOCAL jobs: the
 * server turn survives and the opened screen reattaches to it when it is the live one.
 *
 * A TECHNICAL destroy (configuration change / system-initiated recreation) must NOT
 * cancel the turn: the agent keeps running server-side, and the minimal session/stream
 * binding is persisted through onSaveInstanceState ([TvChatLifecyclePolicy]) so the
 * recreated screen rebinds the same session, restores the transcript via a LIVE
 * `GET /api/session` ([SessionRepository.getSessionDetail]) and reattaches the running
 * turn with `attachToStream` + the `Last-Event-ID` resume cursor ([StreamResumeTracker]).
 *
 * Mid-turn resilience (core [MidTurnRecoveryCoordinator] + [TvChatStreamRecoveryLoop]):
 * a dropped stream never re-POSTs chat/start — a SINGLE bounded recovery job parks on
 * the connectivity observer ([TvConnectivityProbe]) while offline, backs off between
 * reattach attempts and reattaches with the resume cursor so the server replays only
 * the journal gap (no transcript/approval duplication). `Done` and definitive server
 * verdicts (business/auth 4xx) end recovery; an expired journal (404) reloads history.
 *
 * «Диалоги» opens the session browser overlay: the list fetch
 * ([SessionRepository.getSessions]) and the detail load ([SessionRepository.getSessionDetail])
 * happen here, while every DECISION — which rows are listed (subagent children hidden),
 * how each row reads, where the D-pad focus opens and how it steps, how a loaded
 * [ru.mars.network.model.SessionDetail] turns into transcript bubbles — lives in the pure
 * [TvSessionBrowserPolicy] (JVM unit-tested). Opening a session cancels the LOCAL stream
 * work (never the server turn), binds the loaded id into [sessionFlow] so the next send
 * continues that dialog, and reattaches the session's live turn when it has one.
 */
class TvChatActivity : AppCompatActivity() {
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var newChat: Button
    private lateinit var statusView: TextView
    private lateinit var contextView: TextView
    private lateinit var chatRoot: LinearLayout
    private lateinit var sessionsButton: Button
    private lateinit var sessionsOverlay: View
    private lateinit var sessionsStatus: TextView
    private lateinit var sessionList: LinearLayout
    private lateinit var closeSessions: Button
    private lateinit var displayModeButton: Button

    /** Режим показа хода (паритет с мобильным ActivityDisplayMode): выбор + персистентность. */
    private lateinit var activityMode: TvActivityModeSelection

    /** Последнее событие живого хода — вход политики статус-полосы («Тишина» глушит детали). */
    private var lastLiveEvent: ChatEvent? = null

    /** Явный статус (исход steer, смена режима); живёт до следующего события/отправки. */
    private var explicitStatus: String? = null

    /** «К последнему» jump control: visible only while the reader is away from the live edge. */
    private lateinit var jumpToBottom: Button

    /**
     * Pin-aware auto-scroll (pure decisions: [TvAutoScrollPolicy]). A streaming answer
     * follows to the bottom ONLY while the user is pinned there; scrolling up releases
     * the pin, shows [jumpToBottom] and stops the per-token yank. Programmatic scrolls
     * use `scrollTo` (never `fullScroll(FOCUS_DOWN)`), so the D-pad focus stays put.
     */
    private val autoScroll = TvAutoScrollPolicy()
    private var jumpThresholdPx = 0

    // Mini player in the header: mirrors the global TvMusicPlayer transport
    // (rules: TvMiniPlayerSpec). Hidden while no track is bound.
    private lateinit var miniPlayerRow: View
    private lateinit var miniPlayerText: TextView
    private lateinit var miniPlayerToggle: Button

    // Quick chips above the composer (content/activation/D-pad rules: TvQuickChipsPolicy).
    private lateinit var quickChipsRow: LinearLayout
    private val chipButtons = mutableListOf<Button>()
    private val sessionRowViews = mutableListOf<View>()
    private var browserRows: List<TvSessionBrowserPolicy.SessionRow> = emptyList()
    private var browserReturnFocus: View? = null
    private lateinit var browserBackCallback: OnBackPressedCallback

    /**
     * Параметры хода (паритет с мобильным чатом): модель из живого каталога
     * GET /api/models и уровень размышлений из GET/POST /api/reasoning. Доменные
     * держатели выбора — чистые классы ru.mars.tv.chat (JVM-тестируемые); экран
     * только рендерит их решения и возит HTTP через репозитории core-network.
     */
    private lateinit var modelSelection: TvModelSelection
    private lateinit var reasoningSelection: TvReasoningSelection
    private lateinit var modelButton: Button
    private lateinit var reasoningButton: Button

    // Пикер параметров хода: один модальный оверлей на модель и уровни размышлений
    // (механика та же, что у браузера диалогов: блокировка фокуса чата, BACK, возврат).
    private lateinit var paramsOverlay: FrameLayout
    private lateinit var paramsTitle: TextView
    private lateinit var paramsList: LinearLayout
    private lateinit var closeParamsButton: Button
    private lateinit var pickerBackCallback: OnBackPressedCallback
    private val pickerRowViews = mutableListOf<View>()
    private var pickerSelect: ((Int) -> Unit)? = null
    private var pickerReturnFocus: View? = null

    /**
     * Navigation token: a session-list/session-detail fetch that returns AFTER the user
     * opened something else (another dialog, «Новый чат», closed overlay) must not clobber
     * the current screen (mobile `sessionNavigation` parity).
     */
    private var sessionLoadToken = 0

    private var stream: Job? = null
    private val store by lazy { AndroidKeyStoreCredentialStore(this) }

    // --- stream autonomy wiring ---------------------------------------------------------

    /** The SINGLE mid-turn recovery job ([TvChatStreamRecoveryLoop]); at most one at a time. */
    private var recovery: Job? = null

    /**
     * Stream generation: bumped by every explicit local teardown («Новый чат», opening
     * another dialog, «Остановить», выход). Stream/recovery callbacks carrying a stale
     * generation are dropped — late events of a dead stream never reach the transcript,
     * the status strip or the interactive cards. A TECHNICAL recreate does not bump it
     * (the new instance starts at 0 with no stale locals to guard against).
     */
    private var streamGeneration = 0

    /** Live SSE resume cursor (`Last-Event-ID`): fed by `onEventId` of EVERY stream. */
    private val resumeTracker = StreamResumeTracker()

    /** Core recovery brain: failure classification, bounded backoff, offline parking. */
    private val recoveryCoordinator = MidTurnRecoveryCoordinator()

    /** Android connectivity snapshot + observer for the recovery loop's offline parking. */
    private val connectivityProbe: TvConnectivityProbe by lazy {
        TvAndroidConnectivityProbe(applicationContext)
    }

    /**
     * Per-turn answer rendering state shared by a fresh send and every reattach: a still
     * streaming bubble is CONTINUED by a cursor-resumed attach (never duplicated); a
     * locked (Done/Error) bubble is replaced by a fresh one ([beginReattachedTurn]).
     */
    private var liveAnswer: TextView? = null
    private var liveAccumulator: TvAnswerAccumulator? = null

    private val recoveryLoop: TvChatStreamRecoveryLoop by lazy {
        TvChatStreamRecoveryLoop(
            transport = RecoveryTransport(),
            connectivity = connectivityProbe,
            cursorProvider = { resumeTracker.lastEventId },
            coordinator = recoveryCoordinator,
        )
    }

    /**
     * Pure session + stream state (WebUI parity): chat/start requires a real
     * session_id, so the flow mints one via POST /api/session/new before the
     * first send and formats Thought/ToolCall/ContextUsage into TV status text.
     */
    private val sessionFlow = TvChatSessionFlow(SessionRepository(), BASE_URL)

    /**
     * Interactive attention requests (P0): approval/clarify arrive as SSE events and must NOT be
     * silently swallowed — they block the turn until answered. The controller owns the state
     * machine (queue refresh, stale/identity guards, cancel-without-respond); the surface renders
     * inline D-pad-focusable cards in the transcript. All HTTP stays in core-network
     * ([InteractiveRequestsRepository] via [RepositoryTvInteractiveGateway]) — no network logic here.
     */
    private lateinit var interactiveSurface: TvChatInteractiveSurface
    private lateinit var interactive: TvInteractiveController

    /** Server-side turn stop (/api/chat/cancel) — all HTTP/auth stays in core-network. */
    private lateinit var controlGateway: TvChatControlGateway

    /** True while an explicit «Остановить» teardown is in flight (the stream finally defers to it). */
    private var stopping = false

    // --- устойчивость транскрипта (caps/подрезка/батчинг — решения в chat-политиках) ----

    /**
     * Handler отложенного flush живого пузыря. Все отложенные задачи снимаются в
     * [onDestroy] — postDelayed не переживает Activity и не держит на неё ссылку.
     */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Ещё не отрисованный текст живого пузыря (батчинг: [TvLiveRenderBatchPolicy]). */
    private var pendingLiveRender: String? = null
    private var liveFlushScheduled = false
    private var lastLiveRenderMs = 0L
    private val liveFlushRunnable = Runnable {
        liveFlushScheduled = false
        flushLiveAnswer()
    }

    /** Строки активности («Всё») в порядке добавления: cap снимает самые старые. */
    private val activityLineViews = ArrayDeque<View>()

    /** Счётчики скрытого для маркера «…ранее скрыто» (срезы истории + живая подрезка). */
    private var hiddenMessages = 0
    private var hiddenActivity = 0
    private var hiddenMarkerView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_chat)
        // Global music transport: audio focus + network wake mode need an application
        // context (the mini player below resumes playback through TvMusicPlayer).
        TvMusicPlayer.attachContext(this)
        messages = findViewById(R.id.messages)
        messageScroll = findViewById(R.id.messageScroll)
        input = findViewById(R.id.messageInput)
        send = findViewById(R.id.sendButton)
        newChat = findViewById(R.id.newChatButton)
        statusView = findViewById(R.id.statusText)
        contextView = findViewById(R.id.contextText)
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
            TvActivityModePrefs(getSharedPreferences(TvActivityModePrefs.PREFS_FILE, MODE_PRIVATE))
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
        modelButton = findViewById(R.id.modelButton)
        reasoningButton = findViewById(R.id.reasoningButton)
        back.setOnClickListener { finish() }
        newChat.setOnClickListener { startNewChat() }
        sessionsButton.setOnClickListener { openSessionBrowser() }
        closeSessions.setOnClickListener { closeSessionBrowser() }
        // Параметры хода: доменные держатели выбора (персистентность совпадает с мобильным
        // клиентом — те же файлы настроек и ключи, если оба клиента живут на одном устройстве).
        modelSelection = TvModelSelection(
            TvModelChoicePrefs(getSharedPreferences(TvModelChoicePrefs.PREFS_FILE, MODE_PRIVATE))
        )
        reasoningSelection = TvReasoningSelection(
            TvReasoningChoicePrefs(getSharedPreferences(TvReasoningChoicePrefs.PREFS_FILE, MODE_PRIVATE))
        )
        modelButton.setOnClickListener { chooseModel() }
        reasoningButton.setOnClickListener { chooseReasoningEffort() }
        wireParamsPicker()
        TvFocusLift.attachAll(input, send, newChat, back, sessionsButton, closeSessions, displayModeButton, miniPlayerToggle, jumpToBottom, modelButton, reasoningButton, closeParamsButton)
        miniPlayerToggle.setOnClickListener {
            TvMusicPlayer.togglePlayPause()
            renderMiniPlayer()
        }
        // BACK closes the session dialog first; only a closed overlay exits the screen.
        browserBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeSessionBrowser() }
        }
        onBackPressedDispatcher.addCallback(this, browserBackCallback)
        // BACK закрывает пикер параметров хода (зарегистрирован последним — приоритет выше
        // браузера диалогов: оба оверлея одновременно открыты быть не могут).
        onBackPressedDispatcher.addCallback(this, pickerBackCallback)
        controlGateway = RepositoryTvChatControlGateway.create(BASE_URL, store)
        // Technical recreation rebinds the persisted session/stream (TvChatLifecyclePolicy):
        // the next send continues the same dialog and «Остановить» still reaches the
        // server turn whose local SSE stream died with the destroyed Activity.
        savedInstanceState?.let { saved ->
            sessionFlow.restoreBinding(
                TvChatLifecyclePolicy.sanitizedBinding(
                    saved.getString(KEY_SESSION_ID),
                    saved.getString(KEY_STREAM_ID),
                )
            )
        }
        // Live turn: one button, two roles (mobile parity) — «Уточнить» (steer) with text
        // in the composer, «Остановить» with an empty one. Idle turn: «Отправить».
        displayModeButton.setOnClickListener { onDisplayModeClicked() }
        send.setOnClickListener { dispatchSend() }
        input.setOnEditorActionListener { _, _, _ -> dispatchSend(); true }
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
        if (savedInstanceState != null) {
            sessionFlow.sessionId?.let { restored -> openSession(restored) }
        }
        input.requestFocus()
    }

    /**
     * Persists the minimal session/stream binding across a technical recreation
     * ([TvChatLifecyclePolicy.sanitizedBinding] — blank ids never reach the bundle).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        TvChatLifecyclePolicy.sanitizedBinding(sessionFlow.sessionId, sessionFlow.activeStreamId)
            ?.let { binding ->
                binding.sessionId?.let { outState.putString(KEY_SESSION_ID, it) }
                binding.activeStreamId?.let { outState.putString(KEY_STREAM_ID, it) }
            }
    }

    /**
     * Attaches the header mini player to the global transport: the chat mirrors
     * TvMusicPlayer while it is visible (the music screen does the same on its own).
     */
    override fun onResume() {
        super.onResume()
        TvMusicPlayer.onTransportChanged = { runOnUiThread { renderMiniPlayer() } }
        renderMiniPlayer()
    }

    override fun onPause() {
        TvMusicPlayer.onTransportChanged = null
        super.onPause()
    }

    /** Builds the controller/surface pair: cards live in [messages], focus returns to the composer. */
    private fun wireInteractive() {
        interactiveSurface = TvChatInteractiveSurface(
            container = messages,
            onAllCardsCleared = { input.requestFocus() },
            onCardShown = { scrollDown() },
        )
        interactive = TvInteractiveController(
            surface = interactiveSurface,
            gateway = RepositoryTvInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
            sessionProvider = { sessionFlow.sessionId },
            uiDispatcher = MainThreadTvDispatcher(),
        ).also { interactiveSurface.listener = it }
        // WebUI parity: the accepted answer is echoed into the transcript as a user message.
        interactive.onClarifyAnswered = { answer -> append("Вы", answer, isUser = true) }
    }

    override fun onDestroy() {
        // An EXPLICIT exit (BACK/finish) abandons the session for good: cancel the live
        // server turn first so the agent does not keep running invisibly. A TECHNICAL
        // destroy (recreation) must not cancel — the turn keeps running server-side and
        // the recreated screen rebinds it from the saved state (TvChatLifecyclePolicy).
        if (TvChatLifecyclePolicy.shouldCancelServerTurnOnDestroy(isFinishing, isChangingConfigurations)) {
            cancelServerTurnBestEffort()
        }
        cancelLocalStreamJobs()
        // Отложенные flush живого пузыря снимаются — postDelayed не должен переживать
        // Activity (утечка окна через Runnable в main-looper).
        uiHandler.removeCallbacksAndMessages(null)
        pendingLiveRender = null
        activityLineViews.clear()
        hiddenMarkerView = null
        browserReturnFocus = null
        pickerReturnFocus = null
        pickerSelect = null
        if (::interactive.isInitialized) interactive.destroy()
        super.onDestroy()
    }

    private fun sendMessage(): Boolean {
        val text = input.text.toString().trim()
        // Blocked while a turn is live: the local stream is active, recovery is reattaching,
        // OR a restored binding still arms the stop control (technical recreation).
        if (text.isEmpty() || stream?.isActive == true || recovery?.isActive == true || sessionFlow.canStop) return false
        val credential = store.read() ?: return false
        input.text.clear()
        append("Вы", text, isUser = true)
        val answer = appendLivePlaceholder()
        // An outgoing message re-pins: the user always sees their own turn and the
        // answer that streams after it, whatever they read before.
        pinAndScrollToBottom()
        // Per-turn answer state machine: the «Марс: » placeholder is dropped exactly once
        // (first chunk), chunks concatenate verbatim, and the FIRST terminal event (Done/Error)
        // locks the bubble — late frames can no longer clobber the outcome. A new turn builds
        // a fresh accumulator, so the next answer streams normally.
        liveAnswer = answer
        liveAccumulator = TvAnswerAccumulator(answer.text.toString())
        cancelLiveFlush(applyPending = false)
        lastLiveRenderMs = SystemClock.uptimeMillis()
        // A fresh user turn invalidates any stale local stream work and starts the resume
        // cursor + recovery budget from zero (a cursor of the previous stream must never
        // leak into a new Last-Event-ID header).
        cancelLocalStreamJobs()
        resumeTracker.reset()
        recoveryCoordinator.reset()
        stopping = false
        explicitStatus = null
        lastLiveEvent = null
        sessionFlow.beginTurn()
        renderSendControl()
        renderStatus()
        // Выбор модели для этого хода фиксируется ДО запуска потока (collect читает его
        // один раз на chat/start — смена чипа посреди хода на текущий ход не влияет).
        val choice = modelSelection.wireChoice()
        stream = lifecycleScope.launch {
            val generation = streamGeneration
            try {
                // Preflight: the server never creates sessions — a null id must not
                // reach chat/start; the flow reuses the bound id or mints a new one.
                when (val ensured = sessionFlow.ensureSession(credential)) {
                    is TvSessionResult.Failed -> {
                        answer.text = "Сессия не создана: ${ensured.message}"
                        return@launch
                    }
                    is TvSessionResult.Ready -> renderStatus()
                }
                val outcome = collectTurn(
                    ChatRepository().sendMessage(
                        BASE_URL, credential, text, sessionFlow.sessionId,
                        // Параметры хода (паритет с мобильным chat/start): модель из выбора
                        // чипа; explicitPick=true только при ЯВНОМ выборе пользователя —
                        // сервер фиксирует осознанный выбор и не «чинит» сессию обратно
                        // к сохранённому дефолту. Null-выбор = старт без модельных полей.
                        model = choice?.modelId,
                        modelProvider = choice?.providerId,
                        explicitModelPick = choice?.explicitPick == true,
                        onEventId = { resumeTracker.onFrameId(it) },
                    ),
                    generation,
                )
                finishTurn(generation, outcome)
            } finally {
                // Stream finished/cancelled: prompts cannot outlive their stream (Done/Error
                // already dismissed via the controller; this covers abnormal termination).
                // Dismissed cards are re-shown by a recovery reattach when the server
                // replays the still-pending approval/clarify from its journal.
                if (::interactive.isInitialized) interactive.dismissAll()
                if (!stopping) {
                    // An explicit «Остановить» teardown owns the final render (stopCurrentTurn).
                    sessionFlow.endTurn()
                    renderStatus()
                    renderSendControl()
                    input.requestFocus()
                }
            }
        }
        return true
    }

    /** One consumed local stream's outcome (fresh send or a reattach). */
    private class TurnOutcome(
        val cancelled: Boolean,
        val sawDone: Boolean,
        val sawGone: Boolean,
        val errorMessage: String?,
        val streamId: String?,
    )

    /**
     * Consumes [flow] through the shared event pipeline ([handleStreamEvent]) and reports
     * why it ended. Never throws: a cancellation is reported as [TurnOutcome.cancelled];
     * transport failures arrive as `ChatEvent.Error` from core-network (its resilience
     * contract maps every IOException onto an event instead of a thrown exception).
     */
    private suspend fun collectTurn(flow: Flow<ChatEvent>, generation: Int): TurnOutcome {
        var sawDone = false
        var sawGone = false
        var errorMessage: String? = null
        var streamId: String? = null
        try {
            flow.collect { event ->
                when (event) {
                    is ChatEvent.StreamStarted -> streamId = event.streamId
                    is ChatEvent.Done -> sawDone = true
                    is ChatEvent.Error -> {
                        errorMessage = event.message
                        if (TvStreamReattachPolicy.outcomeOf(event) == TvReattachEventOutcome.StreamGone) {
                            sawGone = true
                        }
                    }
                    else -> Unit
                }
                handleStreamEvent(event, generation)
            }
        } catch (cancelled: CancellationException) {
            return TurnOutcome(true, sawDone, sawGone, errorMessage, streamId)
        }
        return TurnOutcome(false, sawDone, sawGone, errorMessage, streamId)
    }

    /**
     * Shared per-event pipeline of EVERY stream source (a fresh chat/start, the reattach
     * on session open, the recovery loop's reattaches): session state, interactive gates
     * and the answer bubble. Events of a stale generation are dropped — a late callback
     * of a dead stream must never mutate the transcript, the status strip or re-show
     * cards after the user navigated/stopped.
     */
    private fun handleStreamEvent(event: ChatEvent, generation: Int) {
        if (generation != streamGeneration) return
        sessionFlow.onEvent(event)
        // Событие хода снимает явный статус (steer/смена режима) и питает политику «Тишины».
        explicitStatus = null
        lastLiveEvent = event
        // Interactive gates are handled explicitly — they must never fall into
        // a silent `else -> Unit` (P0: the turn would hang with no way to answer).
        if (::interactive.isInitialized) interactive.onEvent(event)
        // Режим «Всё»: мысли и инструменты дополнительно дублируются строками в ленту.
        TvActivityDisplayPolicy.transcriptLine(activityMode.current, event)?.let { line ->
            appendActivityLine(line)
            scrollDown()
        }
        when (event) {
            // Terminal lock lives in the accumulator (first Done/Error wins):
            // a late chunk after Error no longer clobbers «Ошибка: …», a late
            // chunk after Done no longer mutates the finished answer, and a
            // tail Error after Done no longer overwrites it. null = ignore.
            // Отрисовка батчится (TvLiveRenderBatchPolicy): не чаще одного обновления
            // текста в интервал — частота чанков не превращается в частоту релayout'ов.
            is ChatEvent.Chunk -> liveAccumulator?.onChunk(event.text)?.let { rendered ->
                onLiveAnswerText(rendered)
            }
            is ChatEvent.Error -> {
                // Недорисованный текст отбрасывается: ошибка заменяет пузырь ЦЕЛИКОМ.
                cancelLiveFlush(applyPending = false)
                liveAccumulator?.onError(event.message)?.let { liveAnswer?.text = it }
                renderSendControl() // terminal event disarms «Остановить»
            }
            is ChatEvent.Done -> {
                // Терминал вне очереди: хвостовой чанк доезжает в пузырь до фиксации.
                cancelLiveFlush(applyPending = true)
                if (liveAccumulator?.onDone() == true) finalizeLiveAnswerRich()
                renderSendControl()
            }
            // Уточнение, которое агент не успел прочитать: текст возвращается в поле
            // ввода (мобильный restoreSteerText паритет) — правка не должна теряться.
            is ChatEvent.SteerLeftover -> restoreSteerText(event.text)
            is ChatEvent.ApprovalRequired -> scrollDown() // card rendered by the surface
            is ChatEvent.ClarifyRequired -> scrollDown()
            // A reattached/recovered turn arms «Остановить» only when its StreamStarted lands.
            // Сервер сообщил фактическую модель хода — привязываем её к сессии (чип «Модель»).
            is ChatEvent.StreamStarted -> {
                bindTurnModel(event)
                renderSendControl()
            }
            else -> Unit
        }
        renderStatus()
    }

    /**
     * Post-stream verdict (the gate is pure — [TvStreamReattachPolicy.shouldAttemptRecovery]):
     * - a delivered `Done` ends everything: a finished turn NEVER reconnects;
     * - an expired journal (anchored 404) resolves to ONE live history reload;
     * - a transport-class error or a silent end starts the single bounded recovery job
     *   (offline parking + backoff + `Last-Event-ID` reattach — never a chat/start re-POST);
     * - a definitive server verdict (business/auth 4xx) stays terminal: the server already
     *   answered, so no reconnect may follow.
     */
    private fun finishTurn(generation: Int, outcome: TurnOutcome) {
        if (outcome.cancelled || stopping || generation != streamGeneration) return
        val sessionId = sessionFlow.sessionId ?: return
        when {
            outcome.sawDone -> Unit
            outcome.sawGone -> reloadHistoryAfterGone(sessionId, generation)
            TvStreamReattachPolicy.shouldAttemptRecovery(outcome.errorMessage) ->
                scheduleStreamRecovery(sessionId, outcome.streamId, outcome.errorMessage, generation)
        }
    }

    /**
     * Starts the SINGLE recovery job of a broken turn. At most one recovery job runs at
     * a time; a stale generation is never scheduled. All decisions (park/retry/reload/
     * give-up) belong to the core coordinator inside [TvChatStreamRecoveryLoop].
     */
    private fun scheduleStreamRecovery(
        sessionId: String,
        streamId: String?,
        errorMessage: String?,
        generation: Int,
    ) {
        if (generation != streamGeneration) return
        // finishTurn runs INSIDE the dying stream job, so the only meaningful guard is
        // the single-recovery-job invariant itself.
        if (recovery?.isActive == true) return
        sessionFlow.markRecovering(TvChatStreamRecoveryLoop.STATUS_STARTING)
        renderStatus()
        recovery = lifecycleScope.launch {
            recoveryLoop.run(sessionId, streamId, errorMessage, recoveryHooks(generation))
        }
    }

    /** Host reactions of the recovery loop; every one is generation-guarded. */
    private fun recoveryHooks(generation: Int) = object : TvChatStreamRecoveryLoop.Hooks {
        override fun onStatus(status: String) {
            if (generation != streamGeneration) return
            sessionFlow.markRecovering(status)
            renderStatus()
        }

        override fun onAttachStarting(streamId: String) {
            if (generation != streamGeneration) return
            beginReattachedTurn()
        }

        override fun onStreamEvent(event: ChatEvent) {
            handleStreamEvent(event, generation)
        }

        override fun onReloadHistory(detail: SessionDetail?) {
            if (generation != streamGeneration) return
            if (detail == null || detail.messages.isEmpty()) {
                // detail == null: the reload fetch itself failed (server unreachable) —
                // report honestly instead of pretending history was refreshed.
                // Empty history: nothing ever settled server-side — the turn (and possibly
                // the message itself) never reached the server. Keep the local transcript
                // honest — wiping the user's own bubble for an undelivered message would
                // be worse.
                sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
                sessionFlow.endTurn()
                renderStatus()
                renderSendControl()
                return
            }
            renderSessionDetailHistory(detail)
            sessionFlow.markRecovering(TvChatSessionFlow.HISTORY_REFRESHED)
            sessionFlow.endTurn()
            renderStatus()
            renderSendControl()
        }

        override fun onGiveUp(reason: String) {
            if (generation != streamGeneration) return
            if (reason.startsWith(GIVE_UP_AUTH_PREFIX)) {
                forceReauth()
                return
            }
            sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
            sessionFlow.endTurn()
            renderStatus()
            renderSendControl()
        }
    }

    /**
     * Prepares the answer bubble of a reattached turn: a still-STREAMING bubble is reused
     * (a cursor-resumed attach CONTINUES the same answer — the transcript is never
     * duplicated), a locked (Done/Error) or missing bubble gets a fresh placeholder.
     */
    private fun beginReattachedTurn() {
        if (liveAccumulator?.state != TvAnswerAccumulator.State.STREAMING || liveAnswer == null) {
            val answer = appendLivePlaceholder()
            liveAnswer = answer
            liveAccumulator = TvAnswerAccumulator(answer.text.toString())
            cancelLiveFlush(applyPending = false)
            lastLiveRenderMs = SystemClock.uptimeMillis()
        }
        sessionFlow.markReattaching()
        renderStatus()
    }

    /**
     * Opens the session's live server turn with `attachToStream` (+ the `Last-Event-ID`
     * cursor when one is known) — it NEVER cancels the turn and NEVER re-POSTs
     * chat/start. A mid-attach drop cascades into the single recovery job.
     */
    private fun reattachActiveTurn(sessionId: String, activeTurn: ActiveTurnInfo, generation: Int) {
        if (generation != streamGeneration) return
        if (stream?.isActive == true || recovery?.isActive == true) return
        val plan = TvStreamReattachPolicy.planFor(activeTurn) as? TvReattachPlan.Attach ?: return
        val credential = store.read() ?: return
        beginReattachedTurn()
        stream = lifecycleScope.launch {
            try {
                val outcome = collectTurn(
                    ChatRepository().attachToStream(
                        BASE_URL, credential, plan.streamId,
                        lastEventId = resumeTracker.lastEventId,
                        onEventId = { resumeTracker.onFrameId(it) },
                    ),
                    generation,
                )
                finishTurn(generation, outcome)
            } finally {
                if (!stopping && generation == streamGeneration) {
                    renderStatus()
                    renderSendControl()
                }
            }
        }
    }

    /** The journal died server-side (anchored 404): one live history reload, no reconnect. */
    private fun reloadHistoryAfterGone(sessionId: String, generation: Int) {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            val detail = runCatching {
                SessionRepository().getSessionDetail(BASE_URL, credential, sessionId)
            }.getOrNull()
            if (generation != streamGeneration) return@launch
            if (detail == null) {
                sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
            } else {
                renderSessionDetailHistory(detail)
                sessionFlow.markRecovering(TvChatSessionFlow.HISTORY_REFRESHED)
            }
            sessionFlow.endTurn()
            renderStatus()
            renderSendControl()
        }
    }

    /**
     * Replaces the transcript with the server's settled history (live `GET /api/session`).
     * Сборка — вне экранного контейнера, подмена одним проходом ([renderTranscriptRows]):
     * длинная история больше не сносит/не инфлейтит ленту по одному view на главный поток.
     */
    private fun renderSessionDetailHistory(detail: SessionDetail?) {
        detail ?: return
        if (::interactive.isInitialized) interactive.dismissAll()
        resetLiveAnswerState()
        renderTranscriptRows(TvSessionBrowserPolicy.transcript(detail))
        pinAndScrollToBottom()
    }

    /** Сброс пер-тёрн состояния ответа (новый чат/история/открытый диалог). */
    private fun resetLiveAnswerState() {
        cancelLiveFlush(applyPending = false)
        liveAnswer = null
        liveAccumulator = null
        lastLiveEvent = null
        explicitStatus = null
    }

    /**
     * Explicit local teardown of the stream work («Новый чат», opening another dialog,
     * выход/BACK, logout): invalidates the generation (late callbacks are dropped) and
     * cancels the single stream job AND the single recovery job. The SERVER turn is never
     * touched here — an explicit exit/new-chat cancels it separately via
     * [cancelServerTurnBestEffort]; navigation and a technical recreate leave it running.
     */
    private fun cancelLocalStreamJobs() {
        streamGeneration++
        stream?.cancel()
        stream = null
        recovery?.cancel()
        recovery = null
    }

    /** Recovery verdict "auth rejected": the credential is dead — re-pair (dashboard logout parity). */
    private fun forceReauth() {
        cancelLocalStreamJobs()
        store.clear()
        startActivity(Intent(this, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** Production [TvRecoveryTransport]: core repositories + the shared resume cursor. */
    private inner class RecoveryTransport : TvRecoveryTransport {
        override suspend fun fetchSessionDetail(sessionId: String): SessionDetail? {
            val credential = store.read() ?: return null
            return runCatching {
                SessionRepository().getSessionDetail(BASE_URL, credential, sessionId)
            }.getOrNull()
        }

        override suspend fun attachStream(
            streamId: String,
            lastEventId: String?,
            onEvent: (ChatEvent) -> Unit,
        ): TvAttachOutcome {
            // No credential mid-recovery = the auth verdict is already known: report it
            // as a 401-class failure so the coordinator gives up and the host re-pairs.
            val credential = store.read() ?: return TvAttachOutcome.Lost("HTTP 401: unauthorized")
            var sawDone = false
            var sawGone = false
            var errorMessage: String? = null
            ChatRepository().attachToStream(
                BASE_URL, credential, streamId,
                lastEventId = lastEventId,
                onEventId = { resumeTracker.onFrameId(it) },
            ).collect { event ->
                when (event) {
                    is ChatEvent.Done -> sawDone = true
                    is ChatEvent.Error -> {
                        errorMessage = event.message
                        if (TvStreamReattachPolicy.outcomeOf(event) == TvReattachEventOutcome.StreamGone) {
                            sawGone = true
                        }
                    }
                    else -> Unit
                }
                onEvent(event)
            }
            // Snapshot: `errorMessage` is captured by the collect closure, so the compiler
            // cannot smart-cast the var inside the `when` arm.
            val finalError = errorMessage
            return when {
                sawDone -> TvAttachOutcome.Settled
                sawGone -> TvAttachOutcome.Gone
                finalError != null -> TvAttachOutcome.Lost(finalError)
                else -> TvAttachOutcome.Lost(TvChatStreamRecoveryLoop.UNEXPECTED_END_MESSAGE)
            }
        }
    }

    /** «Новый чат» from the header: cancels the live server turn, aborts the stream, resets session + transcript. */
    private fun startNewChat() {
        // Any in-flight browser fetch or session load is stale from this point on.
        sessionLoadToken++
        closeSessionBrowser()
        // The session is dropped for good — cancel the server turn first (best-effort) so the
        // agent does not keep running for a transcript nobody will ever see again.
        cancelServerTurnBestEffort()
        // Explicit local teardown: kills the stream AND the recovery job, and invalidates
        // the generation so late callbacks of both are dropped.
        cancelLocalStreamJobs()
        resumeTracker.reset()
        recoveryCoordinator.reset()
        stopping = false
        // Cards of the dead stream go first (invalidates in-flight responds), then the transcript.
        if (::interactive.isInitialized) interactive.dismissAll()
        sessionFlow.startNewChat()
        // У нового диалога серверной модели пока нет: чип откатывается к локальному
        // выбору (персистентному) до первого StreamStarted нового хода, лестница
        // размышлений перечитывается — session-override прежнего диалога снят.
        modelSelection.clearSessionModel()
        renderModelChip()
        refreshReasoningStatus()
        clearTranscript()
        pinAndScrollToBottom()
        renderSendControl()
        renderStatus()
        input.text.clear()
        input.requestFocus()
    }

    /**
     * «Остановить»: cancels the RUNNING SERVER-SIDE turn (mobile cancelStream parity), then
     * tears the local stream down. Dropping only the SSE connection would leave the agent
     * executing invisibly — burning tokens, running commands, or blocked forever on an
     * approval card until the server timeout. The status line reports honestly whether the
     * server confirmed the cancel ([TvChatSessionFlow.markTurnStopped]).
     */
    private fun stopCurrentTurn() {
        if (!sessionFlow.canStop || stopping) return
        // Captured up front: startNewChat racing in must not change what we cancel.
        val streamId = sessionFlow.activeStreamId
        stopping = true
        send.isEnabled = false
        statusView.text = STOPPING
        statusView.visibility = View.VISIBLE
        // An explicit stop must also kill the recovery job — otherwise it would reattach
        // the very turn the user just asked to cancel.
        recovery?.cancel()
        recovery = null
        lifecycleScope.launch {
            val serverCancelled = streamId?.let { id ->
                withContext(Dispatchers.IO) { controlGateway.cancelStream(id) }
            } ?: false
            stream?.cancel()
            stream?.join()
            stream = null
            stopping = false
            // A «Новый чат» pressed mid-stop owns the screen now — its reset must not be clobbered.
            if (sessionFlow.canStop) {
                sessionFlow.markTurnStopped(serverCancelled)
                renderStatus()
                renderSendControl()
                input.requestFocus()
            }
        }
    }

    /**
     * Диспетчер основной кнопки (чистое решение — [TvSteerPolicy.actionFor]): живой ход +
     * текст в композиторе -> steer («Уточнить»), живой ход + пустой композитор -> «Остановить»,
     * иначе обычная отправка. IME-«отправить» идёт через тот же диспетчер.
     */
    private fun dispatchSend() {
        when (TvSteerPolicy.actionFor(sessionFlow.canStop, input.text.isNullOrBlank())) {
            TvSteerPolicy.SendAction.STEER -> steerCurrentTurn()
            TvSteerPolicy.SendAction.STOP -> stopCurrentTurn()
            TvSteerPolicy.SendAction.SEND -> sendMessage()
        }
    }

    /**
     * Steer («уточнение поверх активного хода», мобильный `steerMessage` паритет): POST
     * /api/chat/steer НЕ отменяет и не перезапускает ход — агент дочитывает правку внутри
     * текущего стрима. Признак успеха — поле `accepted` ответа (его уже трактует
     * core-network `executeControl`); при отказе текст остаётся в поле ввода.
     */
    private fun steerCurrentTurn() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val id = sessionFlow.sessionId
        if (id == null) {
            explicitStatus = TvSteerPolicy.STATUS_NO_SESSION
            renderStatus()
            return
        }
        send.isEnabled = false
        lifecycleScope.launch {
            val accepted = withContext(Dispatchers.IO) { controlGateway.steer(id, text) }
            if (accepted) {
                // Чистим поле только если пользователь не дописал текст, пока шёл запрос.
                if (input.text.toString().trim() == text) input.text.clear()
                explicitStatus = TvSteerPolicy.STATUS_ACCEPTED
            } else {
                explicitStatus = TvSteerPolicy.STATUS_REJECTED
            }
            renderStatus()
            renderSendControl()
        }
    }

    /**
     * Недоставленный steer (SSE `pending_steer_leftover` -> [ChatEvent.SteerLeftover]):
     * текст, который агент не успел прочитать, возвращается в поле ввода — правка не
     * теряется (мобильный `restoreSteerText` паритет); ход при этом НЕ отменяется.
     */
    private fun restoreSteerText(text: String) {
        if (TvSteerPolicy.canRestoreLeftover(text, input.text?.toString().orEmpty())) {
            input.setText(text)
        }
        explicitStatus = TvSteerPolicy.LEFTOVER_STATUS
        renderStatus()
        renderSendControl()
        input.requestFocus()
    }

    /** Клик кнопки режима: цикл FULL -> WINDOW -> QUIET, выбор персистентен. */
    private fun onDisplayModeClicked() {
        val picked = activityMode.cycle()
        renderDisplayModeButton()
        explicitStatus = TvActivityDisplayPolicy.switchNotice(picked)
        // «Тишина»/«Всё» применяются к живому ходу немедленно — статус-полоса перерисовывается.
        renderStatus()
    }

    /** Подпись кнопки режима в шапке («Режим: Окно»). */
    private fun renderDisplayModeButton() {
        displayModeButton.text = TvActivityDisplayPolicy.buttonCaption(activityMode.current)
    }

    // ---------------------------------------------------------------------------------------------
    // Параметры хода: модель (GET /api/models) и уровень размышлений (GET/POST /api/reasoning).
    // Паритет мобильного ChatActivity (chooseModel / chooseReasoningEffort / applyReasoningEffort):
    // только живые серверные данные, никаких захардкоженных списков. Выбор модели уходит в
    // payload POST /api/chat/start; уровень размышлений — самостоятельная настройка сервера
    // (chat/start reasoning не несёт: эффективный уровень сервер разрешает на каждый ход сам).
    // ---------------------------------------------------------------------------------------------

    /** Привязка пикера параметров: оверлей, «Закрыть» и BACK-колбэк. */
    private fun wireParamsPicker() {
        paramsOverlay = findViewById(R.id.paramsOverlay)
        paramsTitle = findViewById(R.id.paramsTitle)
        paramsList = findViewById(R.id.paramsList)
        closeParamsButton = findViewById(R.id.closeParamsButton)
        closeParamsButton.setOnClickListener { closeParamsPicker() }
        pickerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeParamsPicker() }
        }
    }

    /**
     * Чип «Модель»: живой каталог GET /api/models (мобильный chooseModel). Каталог
     * недоступен/пуст — честная строка статуса, пикер не открывается: выбирать не из
     * чего, а выдуманной модели не существует.
     */
    private fun chooseModel() {
        val credential = store.read()
        if (credential == null) {
            explicitStatus = getString(R.string.tv_status_models_no_auth)
            renderStatus()
            return
        }
        explicitStatus = getString(R.string.tv_status_models_loading)
        renderStatus()
        lifecycleScope.launch {
            // getModels — suspend и сам уходит на IO; сбой ловим в null (пустой каталог).
            val catalog = runCatching { ModelsRepository().getModels(BASE_URL, credential) }.getOrNull()
            val entries = catalog?.let { modelSelection.pickerEntries(it) }.orEmpty()
            if (entries.isEmpty()) {
                explicitStatus = getString(R.string.tv_status_models_unavailable)
                renderStatus()
                return@launch
            }
            explicitStatus = null
            openParamsPicker(
                title = getString(R.string.tv_picker_model_title),
                rows = TvTurnParamsPolicy.modelRows(entries, modelSelection.displayedChoice()?.modelId),
                returnFocus = modelButton,
            ) { index -> onModelPicked(entries, index) }
        }
    }

    /** Явный выбор модели: persist + немедленная привязка к сессии (payload chat/start). */
    private fun onModelPicked(entries: List<TvModelPickerEntry>, index: Int) {
        val entry = entries.getOrNull(index) ?: return
        val picked = modelSelection.select(entry.model) ?: return
        // Явный выбор сразу становится моделью сессии: следующий chat/start уходит с ним,
        // explicitPick=true — сервер фиксирует осознанный выбор (паритет с веб-клиентом).
        modelSelection.setSessionModel(picked.modelId, picked.providerId)
        renderModelChip()
        explicitStatus = getString(R.string.tv_status_model_picked, picked.modelId)
        renderStatus()
        // Лестница размышлений модельно-зависима — перечитываем после смены модели.
        refreshReasoningStatus()
    }

    /**
     * Чип «Мысли»: состояние GET /api/reasoning?model=&provider=&session_id= и пикер
     * уровней (мобильный chooseReasoningEffort). Отказ/пустая лестница — строка
     * статуса, пикер не открывается: уровней нет, выбирать нечего.
     */
    private fun chooseReasoningEffort() {
        val credential = store.read()
        if (credential == null) {
            explicitStatus = getString(R.string.tv_status_reasoning_no_auth)
            renderStatus()
            return
        }
        explicitStatus = getString(R.string.tv_status_reasoning_loading)
        renderStatus()
        lifecycleScope.launch {
            val model = modelSelection.displayedChoice()
            val fetched = withContext(Dispatchers.IO) {
                ReasoningRepository().fetchStatus(
                    BASE_URL, credential, model?.modelId, model?.providerId, sessionFlow.sessionId,
                )
            }
            when (fetched) {
                is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
                is ReasoningStatusResult.Failed -> {
                    reasoningSelection.applyStatus(null)
                    renderReasoningChip()
                    explicitStatus = getString(R.string.tv_status_reasoning_unavailable, fetched.message)
                    renderStatus()
                    return@launch
                }
            }
            renderReasoningChip()
            val options = reasoningSelection.options()
            if (options.isEmpty()) {
                explicitStatus = getString(R.string.tv_status_reasoning_no_ladder)
                renderStatus()
                return@launch
            }
            explicitStatus = null
            openParamsPicker(
                title = getString(R.string.tv_picker_reasoning_title),
                rows = TvTurnParamsPolicy.reasoningRows(options, reasoningSelection.effectiveEffort()),
                returnFocus = reasoningButton,
            ) { index ->
                options.getOrNull(index)?.let { option -> applyReasoningEffort(option.effort) }
            }
        }
    }

    /**
     * POST /api/reasoning; локально выбор сохраняется ТОЛЬКО после подтверждения сервера
     * (отказавшая запись не становится локальной правдой — мобильный applyReasoningEffort).
     */
    private fun applyReasoningEffort(effort: String?) {
        val credential = store.read()
        if (credential == null) {
            explicitStatus = getString(R.string.tv_status_reasoning_no_auth)
            renderStatus()
            return
        }
        explicitStatus = getString(R.string.tv_status_reasoning_saving)
        renderStatus()
        lifecycleScope.launch {
            val model = modelSelection.displayedChoice()
            val result = withContext(Dispatchers.IO) {
                ReasoningRepository().setEffort(
                    BASE_URL, credential, effort, model?.modelId, model?.providerId, sessionFlow.sessionId,
                )
            }
            when (result) {
                is ReasoningStatusResult.Status -> {
                    reasoningSelection.select(effort)
                    reasoningSelection.applyStatus(result.status)
                    renderReasoningChip()
                    explicitStatus = getString(
                        R.string.tv_status_reasoning_saved,
                        reasoningSelection.label(result.status.reasoningEffort),
                    )
                }
                is ReasoningStatusResult.Failed ->
                    explicitStatus = getString(R.string.tv_status_reasoning_save_failed, result.message)
            }
            renderStatus()
        }
    }

    /** Тихое чтение каталога при старте: эффективная модель (persist-выбор или серверный дефолт). */
    private fun refreshModelCatalog() {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            // getModels — suspend и сам уходит на IO; сбой сети молча пропускаем:
            // чип остаётся на восстановленном выборе/«Модель», выдуманных имён нет.
            val catalog = runCatching { ModelsRepository().getModels(BASE_URL, credential) }.getOrNull()
                ?: return@launch
            modelSelection.resolveEffective(catalog)
            renderModelChip()
        }
    }

    /** Тихая синхронизация состояния размышлений (старт, смена модели/диалога): сбой прячет чип. */
    private fun refreshReasoningStatus() {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            val model = modelSelection.displayedChoice()
            val fetched = withContext(Dispatchers.IO) {
                ReasoningRepository().fetchStatus(
                    BASE_URL, credential, model?.modelId, model?.providerId, sessionFlow.sessionId,
                )
            }
            when (fetched) {
                is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
                is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)
            }
            renderReasoningChip()
        }
    }

    /** Подпись чипа модели: серверное имя из каталога, иначе реальный id; без выбора — «Модель». */
    private fun renderModelChip() {
        if (!::modelButton.isInitialized) return
        modelButton.text = modelSelection.buttonLabel() ?: getString(R.string.tv_chip_model)
    }

    /**
     * Чип уровня размышлений: виден, только пока лестница известна (неизвестное состояние
     * скрывается вместо угадывания — мобильный паритет). Перед скрытием уводим фокус:
     * D-pad не должен оставаться на исчезающем элементе.
     */
    private fun renderReasoningChip() {
        if (!::reasoningButton.isInitialized) return
        val label = reasoningSelection.buttonLabel()
        if (label == null && reasoningButton.hasFocus()) modelButton.requestFocus()
        reasoningButton.visibility = if (label == null) View.GONE else View.VISIBLE
        reasoningButton.text = getString(R.string.tv_chip_reasoning, label.orEmpty())
    }

    /**
     * Сервер назвал фактическую модель хода (ответ chat/start -> StreamStarted):
     * привязываем её как модель сессии и перерисовываем чип. Пустое значение —
     * «сервер не сообщил», локальный выбор не подставляется (атрибуция честна).
     */
    private fun bindTurnModel(event: ChatEvent.StreamStarted) {
        if (event.model.isNullOrBlank()) return
        modelSelection.setSessionModel(event.model, event.provider)
        renderModelChip()
    }

    /**
     * Открытие пикера параметров: модальный слой поверх чата (фокус заблокирован, как в
     * браузере диалогов), строки и начальный фокус — из [TvTurnParamsPolicy]. [onSelect]
     * получает индекс строки; закрытие и возврат фокуса — [closeParamsPicker].
     */
    private fun openParamsPicker(
        title: String,
        rows: List<TvTurnParamsPolicy.PickerRow>,
        returnFocus: View,
        onSelect: (Int) -> Unit,
    ) {
        pickerSelect = onSelect
        pickerReturnFocus = returnFocus
        paramsTitle.text = title
        pickerRowViews.clear()
        paramsList.removeAllViews()
        rows.forEachIndexed { index, row ->
            val card = buildPickerCard(index, row)
            pickerRowViews += card
            paramsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        chatRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        pickerBackCallback.isEnabled = true
        paramsOverlay.visibility = View.VISIBLE
        val selection = TvTurnParamsPolicy.initialSelection(rows)
        pickerRowViews.getOrNull(selection)?.requestFocus() ?: closeParamsButton.requestFocus()
    }

    /**
     * Строка пикера: карточка в стиле браузера диалогов (bg_focus_sidebar_item + lift).
     * Текущий выбор помечен рубиновым заголовком и подписью «текущий выбор» — без глифов
     * (дизайн-гейт ТВ запрещает символы-пиктограммы). UP/DOWN — явный шаг через
     * [TvTurnParamsPolicy.move] (зажатый на краях: фокус не уходит в чат за оверлеем).
     */
    private fun buildPickerCard(index: Int, row: TvTurnParamsPolicy.PickerRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_focus_sidebar_item)
            isFocusable = true
            isClickable = true
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { onPickerRowPicked(index) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@setOnKeyListener false
                }
                val target = TvTurnParamsPolicy.move(index, pickerRowViews.size, delta)
                val view = pickerRowViews.getOrNull(target)
                if (view != null && target != index) {
                    view.requestFocus()
                    true
                } else false
            }
            TvFocusLift.attach(this)
        }
        card.addView(TextView(this).apply {
            text = row.label
            textSize = 20f
            setTextColor(ContextCompat.getColor(
                context, if (row.current) R.color.mars_accent_text else R.color.mars_text_strong))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        if (row.current) {
            card.addView(TextView(this).apply {
                text = getString(R.string.tv_picker_current)
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.mars_status_info))
                isSingleLine = true
                setPadding(0, dp(2), 0, 0)
            })
        }
        return card
    }

    /**
     * Строка пикера активирована: сначала закрываем пикер (фокус возвращается на чип),
     * затем выполняем выбор — сетевой исход ляжет в статус-полосу уже видимого чата.
     */
    private fun onPickerRowPicked(index: Int) {
        val select = pickerSelect ?: return
        closeParamsPicker()
        select(index)
    }

    /** Закрытие пикера: фокус возвращается на вызвавшую кнопку (страховка — чип «Модель»). */
    private fun closeParamsPicker() {
        if (!::paramsOverlay.isInitialized || paramsOverlay.visibility != View.VISIBLE) return
        paramsOverlay.visibility = View.GONE
        pickerBackCallback.isEnabled = false
        pickerSelect = null
        pickerRowViews.clear()
        paramsList.removeAllViews()
        chatRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val restore = pickerReturnFocus?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        pickerReturnFocus = null
        (restore ?: modelButton).requestFocus()
    }

    /**
     * Fire-and-forget cancel of the live server turn when the screen leaves the session
     * («Новый чат», BACK/onDestroy). Runs on [applicationScope] so it survives Activity
     * teardown; the outcome is intentionally not surfaced (the transcript is already gone).
     */
    private fun cancelServerTurnBestEffort() {
        if (!::controlGateway.isInitialized) return
        val streamId = sessionFlow.activeStreamId ?: return
        applicationScope.launch { controlGateway.cancelStream(streamId) }
    }

    /**
     * The primary button mirrors the turn state (mobile parity): on a live turn an empty
     * composer gives «Остановить» + stop icon, a non-empty one gives «Уточнить» (steer);
     * idle it is «Отправить». It stays enabled and focusable in ALL states — a D-pad user
     * must never lose the ability to interrupt or steer a running turn.
     */
    private fun renderSendControl() {
        val action = TvSteerPolicy.actionFor(sessionFlow.canStop, input.text.isNullOrBlank())
        send.text = TvSteerPolicy.buttonLabel(action)
        send.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (TvSteerPolicy.buttonShowsStopIcon(action)) R.drawable.ic_stop else R.drawable.ic_send,
            0, 0, 0)
        send.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.mars_on_accent)
        send.isEnabled = true
        renderChips()
    }

    /**
     * Quick-chip row above the composer: one D-pad tap sends a canned full turn.
     * Buttons are built once; activation and LEFT/RIGHT stepping follow
     * [TvQuickChipsPolicy] (clamped at the edges, no wrap).
     */
    private fun buildQuickChips() {
        quickChipsRow.removeAllViews()
        chipButtons.clear()
        val padH = dp(20)
        val padV = dp(10)
        TvQuickChipsPolicy.chips.forEachIndexed { index, chip ->
            val button = Button(this).apply {
                text = chip.label
                textSize = 18f
                isAllCaps = false
                setTextColor(ContextCompat.getColor(this@TvChatActivity, R.color.mars_text))
                setBackgroundResource(R.drawable.bg_focus_card)
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(padH, padV, padH, padV)
                isFocusable = true
                setOnClickListener { activateQuickChip(chip) }
                // D-pad LEFT/RIGHT stepping inside the row (TvQuickChipsPolicy.move).
                setOnKeyListener { _, keyCode, event ->
                    val delta = when {
                        event.action != KeyEvent.ACTION_DOWN -> null
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        else -> null
                    }
                    if (delta == null) return@setOnKeyListener false
                    val next = TvQuickChipsPolicy.move(index, chipButtons.size, delta)
                    val target = chipButtons.getOrNull(next) ?: return@setOnKeyListener false
                    target.requestFocus()
                    true
                }
            }
            TvFocusLift.attach(button)
            quickChipsRow.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) })
            chipButtons += button
        }
        renderChips()
    }

    /** A chip is a full turn: its text goes into the composer and is sent immediately. */
    private fun activateQuickChip(chip: QuickChip) {
        if (!chipsLive()) return
        input.setText(chip.text)
        sendMessage()
    }

    /** Chips follow the send rule: dead while a turn streams or the input is blocked. */
    private fun chipsLive(): Boolean = TvQuickChipsPolicy.chipsEnabled(
        streamActive = sessionFlow.canStop || stream?.isActive == true,
        inputBlocked = !input.isEnabled,
    )

    private fun renderChips() {
        val live = chipsLive()
        chipButtons.forEach { button ->
            button.isEnabled = live
            button.alpha = if (live) 1f else 0.4f
        }
    }

    /** Mirrors the global transport into the header plate (rules: [TvMiniPlayerSpec]). */
    private fun renderMiniPlayer() {
        val track = TvMusicPlayer.currentTrack
        val visible = TvMiniPlayerSpec.visible(track)
        miniPlayerRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        miniPlayerText.text = TvMiniPlayerSpec.line(track)
        val playing = TvMusicPlayer.isPlaying
        miniPlayerToggle.text = TvMiniPlayerSpec.toggleLabel(playing)
        miniPlayerToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
        miniPlayerToggle.compoundDrawableTintList =
            ContextCompat.getColorStateList(this, R.color.icon_focus_tint)
    }

    /**
     * Header status strip: live reasoning/tool status line plus the context-fill pill
     * (green <=50%, orange <=75%, red above — WebUI ring parity). Both hide when unknown.
     * Режим показа хода ([TvActivityDisplayPolicy.status]) глушит детали хода в «Тишине»;
     * явный статус (исход steer, смена режима) имеет приоритет до следующего события.
     */
    private fun renderStatus() {
        val mode = if (::activityMode.isInitialized) {
            activityMode.current
        } else {
            TvActivityDisplayMode.DEFAULT
        }
        val status = explicitStatus
            ?: TvActivityDisplayPolicy.status(mode, sessionFlow.canStop, lastLiveEvent, sessionFlow.statusText)
        statusView.text = status
        statusView.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        val usageText = sessionFlow.contextUsageText
        contextView.text = usageText.orEmpty()
        contextView.visibility = if (usageText == null) View.GONE else View.VISIBLE
        val percent = sessionFlow.contextUsage?.percent ?: 0
        contextView.setTextColor(ContextCompat.getColor(this, when {
            percent > 75 -> R.color.mars_status_error
            percent > 50 -> R.color.mars_status_warning
            else -> R.color.mars_status_online
        }))
    }

    /**
     * WebUI-parity chat bubble: user side gets the deep-navy bubble
     * (USER_BUBBLE + USER_BORDER), Mars side gets the bot card
     * (BOT_CARD + BOT_BORDER). Both are focusable — under D-pad focus the
     * bg_bubble_* StateListDrawable cross-fades to a ruby border.
     *
     * Ответ Марса из ИСТОРИИ с таблицей/чек-листом рендерится «богато»
     * ([TvMarkdownRenderer.richBubble]) вместо плоского текста.
     */
    private fun append(author: String, text: String, isUser: Boolean): View =
        buildBubble(author, text, isUser).also { addTranscriptView(it) }

    /** Единая фабрика пузыря (лента И внеэкранная сборка истории): вид, но не добавление. */
    private fun buildBubble(author: String, text: String, isUser: Boolean): View =
        if (!isUser && text.isNotEmpty() && TvMarkdownBlocks.isRich(text)) {
            TvMarkdownRenderer.richBubble(this, text, ::dp)
        } else {
            plainBubble(author, text, isUser)
        }

    /** Плоский текстовый пузырь (прежний рендер): пользователь, пустой placeholder, обычный ответ. */
    private fun plainBubble(author: String, text: String, isUser: Boolean): TextView = TextView(this).apply {
        this.text = "$author: $text"
        textSize = 22f
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
        isFocusable = true
        TvFocusLift.attach(this)
    }

    /** Общие параметры пузыря в ленте (отступ сверху). */
    private fun bubbleParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }

    /**
     * Добавление в ленту с общим cap'ом ([TvTranscriptRetentionPolicy.trimPlan]):
     * контейнер никогда не растёт бесконечно — самые старые записи подрезаются.
     */
    private fun addTranscriptView(view: View, params: LinearLayout.LayoutParams = bubbleParams()) {
        messages.addView(view, params)
        enforceTranscriptCap()
    }

    /** Пузырь-заготовка живого ответа (поток chunk'ов пишет в него через [liveAnswer]). */
    private fun appendLivePlaceholder(): TextView =
        plainBubble("Марс", "", isUser = false).also { addTranscriptView(it) }

    /**
     * Ответ ДОТЕКАЛ до конца и содержит таблицу/чек-лист: плоский стриминговый пузырь
     * заменяется «богатым» рендером того же текста (markdown-паритет с мобильным).
     * Делается ровно один раз — [TvAnswerAccumulator.onDone] возвращает true только первым.
     */
    private fun finalizeLiveAnswerRich() {
        val accumulator = liveAccumulator ?: return
        val answer = liveAnswer ?: return
        if (accumulator.state != TvAnswerAccumulator.State.DONE) return
        val text = accumulator.text()
        if (!TvMarkdownBlocks.isRich(text)) return
        val index = messages.indexOfChild(answer)
        if (index < 0) return
        val params = answer.layoutParams
        detachTranscriptView(answer)
        messages.removeViewAt(index)
        messages.addView(TvMarkdownRenderer.richBubble(this, text, ::dp), index, params)
        // Пузырь заменён — поздние chunk'и терминального ответа уже отброшены аккумулятором,
        // а reattach после Done заведёт свежий placeholder (liveAnswer == null).
        liveAnswer = null
    }

    /**
     * Режим «Всё»: мысли/инструменты дополнительно дублируются в ленту приглушёнными
     * строками на плашке «размышлений» (bg_thinking) — ТВ-эквивалент мобильного
     * раскрытого thoughts/tools блока.
     *
     * Строки capped ([TvTranscriptRetentionPolicy.ACTIVITY_LINE_CAP]): долгий ход в
     * режиме «Всё» — это тысячи событий, бесконечный рост View ронял TV-приставку.
     * Самые старые строки снимаются (со снятием колбэков), счётчик уходит в маркер
     * «…ранее скрыто».
     */
    private fun appendActivityLine(line: String) {
        val view = TextView(this).apply {
            text = line
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundResource(R.drawable.bg_thinking)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            TvFocusLift.attach(this)
            tag = TAG_ACTIVITY_LINE
        }
        activityLineViews.addLast(view)
        addTranscriptView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        enforceActivityLineCap()
    }

    // --- устойчивость транскрипта: caps, подрезка, внеэкранная подмена истории ------------

    /** Cap строк активности: снимаем самые старые, пока не вписались. */
    private fun enforceActivityLineCap() {
        val overflow = TvTranscriptRetentionPolicy.activityOverflow(activityLineViews.size)
        if (overflow <= 0) return
        repeat(overflow) {
            val oldest = activityLineViews.removeFirstOrNull() ?: return
            hiddenActivity++
            detachTranscriptView(oldest)
            messages.removeView(oldest)
        }
        updateHiddenMarker()
    }

    /** Общий cap ленты: план подрезки (чистый) + снятие самых старых View с колбэками. */
    private fun enforceTranscriptCap() {
        val count = messages.childCount
        if (count <= TvTranscriptRetentionPolicy.MAX_TRANSCRIPT_VIEWS) return
        val kinds = List(count) { kindOf(messages.getChildAt(it)) }
        val plan = TvTranscriptRetentionPolicy.trimPlan(kinds)
        if (plan.isEmpty()) return
        // Индексы плана валидны до первого удаления — снимаем с конца.
        plan.asReversed().forEach { index ->
            val victim = messages.getChildAt(index) ?: return@forEach
            when (kindOf(victim)) {
                TvTranscriptRetentionPolicy.Kind.ACTIVITY_LINE -> {
                    hiddenActivity++
                    activityLineViews.remove(victim)
                }
                TvTranscriptRetentionPolicy.Kind.PROTECTED -> Unit // план их не содержит
                TvTranscriptRetentionPolicy.Kind.MESSAGE -> hiddenMessages++
            }
            detachTranscriptView(victim)
            messages.removeViewAt(index)
        }
        updateHiddenMarker()
    }

    /**
     * Категория ребёнка ленты для чистой политики: интерактивные карточки, живой пузырь
     * и маркер скрытого защищены; строки активности помечены тегом; прочее — сообщения.
     */
    private fun kindOf(child: View): TvTranscriptRetentionPolicy.Kind = when {
        child is TvInteractiveCard -> TvTranscriptRetentionPolicy.Kind.PROTECTED
        child === hiddenMarkerView -> TvTranscriptRetentionPolicy.Kind.PROTECTED
        child === liveAnswer -> TvTranscriptRetentionPolicy.Kind.PROTECTED
        child.tag == TAG_ACTIVITY_LINE -> TvTranscriptRetentionPolicy.Kind.ACTIVITY_LINE
        else -> TvTranscriptRetentionPolicy.Kind.MESSAGE
    }

    /**
     * Снятие колбэков у удаляемой View (защита от утечек): фокус уводится в композер,
     * анимации TvFocusLift и focus-листенер гасятся — удалённая View не держит ссылок
     * на окно и не получает событий.
     */
    private fun detachTranscriptView(view: View) {
        if (view.hasFocus()) input.requestFocus()
        view.animate().cancel()
        view.onFocusChangeListener = null
        view.tag = null
    }

    /** Полная очистка ленты («Новый чат»): View, строки активности, счётчики, live-состояние. */
    private fun clearTranscript() {
        for (index in 0 until messages.childCount) detachTranscriptView(messages.getChildAt(index))
        messages.removeAllViews()
        activityLineViews.clear()
        hiddenMessages = 0
        hiddenActivity = 0
        hiddenMarkerView = null
        resetLiveAnswerState()
    }

    /**
     * Подмена транскрипта историей БЕЗ полного сноса на главном потоке: пузыри
     * собираются в offscreen-контейнере (не присоединён к окну — addView не тянет
     * measure/layout на каждую строку), затем содержимое меняется одним проходом в
     * одном кадре (пустого кадра нет). Длинная история режется до последних
     * [TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES] сообщений с маркером
     * «…ранее скрыто» (чистое решение — [TvTranscriptRetentionPolicy.historySlice]).
     * D-pad фокус возвращается на прежнюю позицию (или в композер).
     */
    private fun renderTranscriptRows(rows: List<TvSessionBrowserPolicy.TranscriptRow>) {
        val slice = TvTranscriptRetentionPolicy.historySlice(rows.size)
        val offscreen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var marker: TextView? = null
        if (slice.hiddenMessages > 0) {
            marker = buildHiddenMarker(TvTranscriptRetentionPolicy.hiddenMarkerText(slice.hiddenMessages, 0))
            offscreen.addView(marker, LinearLayout.LayoutParams(-1, -2))
        }
        rows.drop(slice.firstRowIndex).forEach { row ->
            offscreen.addView(buildBubble(row.author, row.text, row.isUser), bubbleParams())
        }
        swapTranscript(offscreen)
        activityLineViews.clear()
        hiddenMessages = slice.hiddenMessages
        hiddenActivity = 0
        hiddenMarkerView = marker
    }

    /** Атомарная подмена содержимого ленты: фокус сохраняется, пустого кадра нет. */
    private fun swapTranscript(prepared: LinearLayout) {
        val focusedIndex = (0 until messages.childCount)
            .firstOrNull { messages.getChildAt(it).hasFocus() }
        for (index in 0 until messages.childCount) detachTranscriptView(messages.getChildAt(index))
        messages.removeAllViews()
        while (prepared.childCount > 0) {
            val child = prepared.getChildAt(0)
            prepared.removeViewAt(0)
            messages.addView(child, child.layoutParams)
        }
        if (focusedIndex != null) {
            val target = messages.getChildAt(minOf(focusedIndex, messages.childCount - 1))
            if (target != null && target.isFocusable) target.requestFocus() else input.requestFocus()
        }
    }

    /** Маркер «…ранее скрыто»: приглушённая незафокусируемая строка в начале ленты. */
    private fun buildHiddenMarker(text: String?): TextView = TextView(this).apply {
        this.text = text.orEmpty()
        textSize = 15f
        setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
        setPadding(dp(24), dp(10), dp(24), dp(4))
        isFocusable = false
    }

    /** Перерисовка/снятие маркера по счётчикам скрытого. */
    private fun updateHiddenMarker() {
        val text = TvTranscriptRetentionPolicy.hiddenMarkerText(hiddenMessages, hiddenActivity)
        if (text == null) {
            hiddenMarkerView?.let { marker ->
                detachTranscriptView(marker)
                messages.removeView(marker)
            }
            hiddenMarkerView = null
            return
        }
        val existing = hiddenMarkerView
        if (existing != null && messages.indexOfChild(existing) >= 0) {
            existing.text = text
            return
        }
        val marker = buildHiddenMarker(text)
        hiddenMarkerView = marker
        messages.addView(marker, 0, LinearLayout.LayoutParams(-1, -2))
        // Маркер добавлен — общий cap проверяется заново (рекурсия ограничена: повторный
        // проход маркер уже находит и только обновляет текст).
        enforceTranscriptCap()
    }

    // --- батчинг живого пузыря (TvLiveRenderBatchPolicy) ----------------------------------

    /** Накопленный текст чанков рисуется не чаще одного раза в интервал. */
    private fun onLiveAnswerText(rendered: String) {
        pendingLiveRender = rendered
        when (val decision = TvLiveRenderBatchPolicy.decide(
            flushPending = liveFlushScheduled,
            elapsedSinceLastRenderMs = SystemClock.uptimeMillis() - lastLiveRenderMs,
        )) {
            TvLiveRenderBatchPolicy.Decision.Now -> flushLiveAnswer()
            is TvLiveRenderBatchPolicy.Decision.Defer -> {
                liveFlushScheduled = true
                uiHandler.postDelayed(liveFlushRunnable, decision.delayMs)
            }
            TvLiveRenderBatchPolicy.Decision.Nothing -> Unit
        }
    }

    /** Фактическая отрисовка: всегда ПОСЛЕДНИЙ накопленный текст (не очередь чанков). */
    private fun flushLiveAnswer() {
        uiHandler.removeCallbacks(liveFlushRunnable)
        liveFlushScheduled = false
        val rendered = pendingLiveRender ?: return
        pendingLiveRender = null
        lastLiveRenderMs = SystemClock.uptimeMillis()
        liveAnswer?.text = rendered
        scrollDown()
    }

    /**
     * Снятие отложенного flush (терминал/сброс/новый ход). [applyPending] дорисовывает
     * недоставленный текст — используется на Done, чтобы хвостовой чанк не потерялся.
     */
    private fun cancelLiveFlush(applyPending: Boolean) {
        uiHandler.removeCallbacks(liveFlushRunnable)
        liveFlushScheduled = false
        if (applyPending) {
            pendingLiveRender?.let { rendered ->
                lastLiveRenderMs = SystemClock.uptimeMillis()
                liveAnswer?.text = rendered
                scrollDown()
            }
        }
        pendingLiveRender = null
    }

    /**
     * Auto-scroll of a live transcript: runs ONLY while the reader is pinned to the
     * bottom ([TvAutoScrollPolicy.shouldAutoScroll]) — a user rereading a scrolled-up
     * line is never yanked back by an incoming chunk or card.
     */
    private fun scrollDown() {
        if (autoScroll.shouldAutoScroll()) scrollToBottom()
    }

    /**
     * Unconditional re-pin + scroll (outgoing message, opened/reloaded session, jump
     * control): hides «К последнему» and puts the transcript at the live edge.
     */
    private fun pinAndScrollToBottom() {
        renderJumpButton(autoScroll.pinToBottom())
        scrollToBottom()
    }

    /**
     * Focus-stable bottom scroll: `scrollTo` (clamped by the pure
     * [TvAutoScrollPolicy.scrollTargetForBottom]) instead of `fullScroll(FOCUS_DOWN)` —
     * the old call also moved the D-pad focus into the transcript on EVERY token.
     */
    private fun scrollToBottom() {
        messageScroll.post {
            val child = messageScroll.getChildAt(0) ?: return@post
            messageScroll.scrollTo(
                0,
                TvAutoScrollPolicy.scrollTargetForBottom(
                    child.bottom, messageScroll.height, messageScroll.paddingBottom,
                ),
            )
        }
    }

    /**
     * «К последнему» visibility. Hiding a FOCUSED control would drop the D-pad focus
     * into nowhere (unstable focus on disappearance), so the focus moves to the
     * composer first — the natural continuation after jumping to the live edge.
     */
    private fun renderJumpButton(visible: Boolean) {
        if (!visible && jumpToBottom.hasFocus()) input.requestFocus()
        jumpToBottom.visibility = if (visible) View.VISIBLE else View.GONE
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // --- Session browser («Диалоги») ---------------------------------------------------------------

    /**
     * Opens the session dialog: shows the overlay, freezes the chat behind it (focus cannot
     * escape into the transcript/composer) and fetches the session list. All fetch/render
     * decisions come from [TvSessionBrowserPolicy]; a stale fetch (token moved on) is dropped.
     */
    private fun openSessionBrowser() {
        // Guard only — the credential the fetch needs is read again inside loadSessionList.
        if (store.read() == null) return
        browserReturnFocus = currentFocus
        chatRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browserBackCallback.isEnabled = true
        sessionsOverlay.visibility = View.VISIBLE
        loadSessionList()
    }

    /**
     * One session-list fetch attempt (the first open AND every «Повторить»): resets the
     * dialog into the loading state and renders through [TvSessionBrowserPolicy.listOutcome].
     * core-network answers every failed fetch with an EMPTY list, so the connectivity probe
     * snapshot decides the honest verdict: offline reads «unavailable» + retry, never
     * «Список диалогов пуст» (a dead radio is not evidence that no dialogs exist).
     */
    private fun loadSessionList() {
        val credential = store.read() ?: return
        sessionRowViews.clear()
        browserRows = emptyList()
        sessionList.removeAllViews()
        sessionsStatus.text = LOADING_SESSIONS
        sessionsStatus.visibility = View.VISIBLE
        closeSessions.requestFocus()
        lifecycleScope.launch {
            val token = ++sessionLoadToken
            val sessions = SessionRepository().getSessions(BASE_URL, credential)
            if (token != sessionLoadToken) return@launch
            renderSessionBrowser(sessions)
        }
    }

    /** Hides the overlay, re-enables chat focus and restores it where the dialog was opened. */
    private fun closeSessionBrowser() {
        if (!::sessionsOverlay.isInitialized || sessionsOverlay.visibility != View.VISIBLE) return
        sessionLoadToken++ // an in-flight list fetch must not re-render a closed dialog
        sessionsOverlay.visibility = View.GONE
        browserBackCallback.isEnabled = false
        chatRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val restore = browserReturnFocus?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        browserReturnFocus = null
        (restore ?: sessionsButton).requestFocus()
    }

    /**
     * Renders one fetched list through [TvSessionBrowserPolicy.listOutcome]: visible rows
     * build the D-pad cards (initial focus per policy — current session, else first), the
     * server-confirmed empty state parks the focus on «Закрыть», and the OFFLINE state gets
     * the honest «unavailable» line plus a focusable «Повторить» row ([buildSessionListRetry])
     * — a flaky-TV-radio dead end must stay escapable without closing the dialog.
     */
    private fun renderSessionBrowser(sessions: List<SessionItem>) {
        val outcome = TvSessionBrowserPolicy.listOutcome(
            sessions = sessions,
            currentSessionId = sessionFlow.sessionId,
            networkAvailable = connectivityProbe.isNetworkAvailable(),
        )
        sessionList.removeAllViews()
        sessionRowViews.clear()
        browserRows = (outcome as? TvSessionBrowserPolicy.ListOutcome.Show)?.rows.orEmpty()
        val statusMessage = TvSessionBrowserPolicy.listStatusMessage(outcome)
        if (statusMessage == null) {
            sessionsStatus.visibility = View.GONE
        } else {
            sessionsStatus.text = statusMessage
            sessionsStatus.visibility = View.VISIBLE
        }
        if (browserRows.isEmpty()) {
            if (TvSessionBrowserPolicy.shouldOfferRetry(outcome)) {
                val retry = buildSessionListRetry()
                sessionList.addView(retry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                retry.requestFocus()
            } else {
                closeSessions.requestFocus()
            }
            return
        }
        browserRows.forEachIndexed { index, row ->
            val card = buildSessionCard(
                index, row,
                current = TvSessionBrowserPolicy.isCurrent(row, sessionFlow.sessionId),
            )
            sessionRowViews += card
            sessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val selection = TvSessionBrowserPolicy.initialSelection(browserRows, sessionFlow.sessionId)
        sessionRowViews.getOrNull(selection)?.requestFocus() ?: closeSessions.requestFocus()
    }

    /**
     * The offline state's escape hatch: a focusable «Повторить» row (closeSessionsButton
     * styling: theme focus selector + lift) that re-runs [loadSessionList]. Rendered only
     * for [TvSessionBrowserPolicy.ListOutcome.Unreachable] — never for a server-confirmed
     * empty list, where there is nothing to retry.
     */
    private fun buildSessionListRetry(): Button = Button(this).apply {
        text = TvSessionBrowserPolicy.RETRY_LABEL
        textSize = 18f
        isAllCaps = false
        setTextColor(ContextCompat.getColor(this@TvChatActivity, R.color.mars_text))
        setBackgroundResource(R.drawable.bg_focus_card)
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(26), dp(10), dp(26), dp(10))
        isFocusable = true
        setOnClickListener { loadSessionList() }
        TvFocusLift.attach(this)
    }

    /**
     * One dialog row: title + preview + meta card. The CURRENT dialog is marked
     * visually (ruby-tinted title + «текущий диалог» meta lead — decisions in
     * [TvSessionBrowserPolicy.isCurrent]/[TvSessionBrowserPolicy.metaWithCurrent]):
     * after the initial focus steps away, the user must still see which dialog is live.
     * UP/DOWN stepping is explicit through [TvSessionBrowserPolicy.move] (deterministic,
     * clamped at the edges — focus never escapes the list into the chat behind); at an
     * edge the key falls through to the platform focus search (towards «Закрыть»).
     */
    private fun buildSessionCard(index: Int, row: TvSessionBrowserPolicy.SessionRow, current: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_focus_sidebar_item)
            isFocusable = true
            isClickable = true
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { onSessionRowPicked(row) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@setOnKeyListener false
                }
                val target = TvSessionBrowserPolicy.move(index, sessionRowViews.size, delta)
                val view = sessionRowViews.getOrNull(target)
                if (view != null && target != index) {
                    view.requestFocus()
                    true
                } else false
            }
            TvFocusLift.attach(this)
        }
        card.addView(TextView(this).apply {
            text = row.title
            textSize = 20f
            setTextColor(ContextCompat.getColor(
                context, if (current) R.color.mars_accent_text else R.color.mars_text_strong))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        row.subtitle?.let { subtitle ->
            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        TvSessionBrowserPolicy.metaWithCurrent(row.meta, current)?.let { meta ->
            card.addView(TextView(this).apply {
                text = meta
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.mars_status_info))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        return card
    }

    /** A row was activated: close the dialog and load that session. */
    private fun onSessionRowPicked(row: TvSessionBrowserPolicy.SessionRow) {
        closeSessionBrowser()
        openSession(row.id)
    }

    /**
     * Loads one session and continues the chat inside it (mobile `loadSessionById` parity):
     * navigation cancels only the LOCAL stream work — NEVER the server turn — the fetched
     * detail binds into [sessionFlow] via [ChatEvent.SessionBound] so the next send reuses
     * that session_id, the persisted transcript renders as bubbles, and when the session's
     * turn is still streaming server-side it is REATTACHED (attachToStream + Last-Event-ID
     * cursor), never cancelled: a technical recreate or reopening an active dialog must
     * continue the same live answer.
     */
    private fun openSession(sessionId: String) {
        val credential = store.read() ?: return
        // Navigation: cancel local jobs (stream + recovery), bump the generation so their
        // late callbacks are dropped. The server turn survives and is reattached below.
        cancelLocalStreamJobs()
        resumeTracker.reset()
        recoveryCoordinator.reset()
        stopping = false
        if (::interactive.isInitialized) interactive.dismissAll()
        val token = ++sessionLoadToken
        statusView.text = OPENING_SESSION
        statusView.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = runCatching {
                SessionRepository().getSessionDetail(BASE_URL, credential, sessionId)
            }.getOrNull()
            // The user opened something newer mid-fetch («Новый чат», another dialog):
            // that navigation owns the screen now — never clobber it with a stale load.
            if (token != sessionLoadToken) return@launch
            when (val outcome = TvSessionBrowserPolicy.loadOutcome(detail)) {
                is TvSessionBrowserPolicy.LoadOutcome.Failed -> {
                    statusView.text = outcome.message
                    statusView.visibility = View.VISIBLE
                    input.requestFocus()
                }
                is TvSessionBrowserPolicy.LoadOutcome.Open -> {
                    sessionFlow.startNewChat()
                    sessionFlow.onEvent(ChatEvent.SessionBound(outcome.detail.id))
                    // Серверная модель открытого диалога (SessionDetail.model) и его
                    // лестница размышлений — оба значения привязаны к сессии.
                    modelSelection.setSessionModel(outcome.detail.model, outcome.detail.modelProvider)
                    renderModelChip()
                    refreshReasoningStatus()
                    resetLiveAnswerState()
                    // Внеэкранная сборка + подмена одним проходом (renderTranscriptRows):
                    // длинная история не блокирует главный поток и не даёт пустого кадра.
                    renderTranscriptRows(TvSessionBrowserPolicy.transcript(outcome.detail))
                    input.text.clear()
                    // A live server turn is reattached (never cancelled): the recreated
                    // or reopened screen continues the same dialog.
                    if (outcome.detail.activeTurn.canReattach) {
                        reattachActiveTurn(outcome.detail.id, outcome.detail.activeTurn, streamGeneration)
                    }
                    renderSendControl()
                    renderStatus()
                    input.requestFocus()
                    pinAndScrollToBottom()
                }
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val STOPPING = "Останавливаю…"
        private const val LOADING_SESSIONS = "Загружаю список…"
        private const val OPENING_SESSION = "Открываю диалог…"
        private const val KEY_SESSION_ID = "tv_chat_session_id"
        private const val KEY_STREAM_ID = "tv_chat_active_stream_id"

        /** Тег View строк активности — по нему чистая политика отличает их от сообщений. */
        private const val TAG_ACTIVITY_LINE = "tv-transcript-activity-line"

        /** Recovery give-up label of a dead credential ([MidTurnRecoveryCoordinator.giveUpReason]). */
        private const val GIVE_UP_AUTH_PREFIX = "auth rejected"

        /** Process-wide scope for fire-and-forget server cancels that must outlive the Activity. */
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
