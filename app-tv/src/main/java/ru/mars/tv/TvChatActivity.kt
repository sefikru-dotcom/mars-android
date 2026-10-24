package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.StreamResumeTracker
import ru.mars.network.repository.SubagentStatusPolicy
import ru.mars.tv.chat.TvActivityModeSelection
import ru.mars.tv.chat.TvContextBadge
import ru.mars.tv.chat.TvHistoryReloadPolicy
import ru.mars.tv.chat.TvLiveRenderBatchPolicy
import ru.mars.tv.chat.TvModelSelection
import ru.mars.tv.chat.TvQuotaPolicy
import ru.mars.tv.chat.TvReasoningSelection
import ru.mars.tv.chat.TvTranscriptRetentionPolicy
import ru.mars.tv.chat.TvTurnStatePolicy

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
 *
 * Host shape (W2 decomposition): the screen is split into `TvChat*Section.kt` files —
 * shell/binding, turn, recovery, params, header, transcript, live answer, session browser,
 * turn notice — plus the SSE frame trace ([TvSseFrameTrace]). Those files are
 * extension-functions on this Activity: state (view fields, jobs, session binding) and
 * the lifecycle stay here, bodies moved verbatim. The host budget (<= 600 lines) and the
 * "sections hold no state" invariant are pinned by [TvChatDecompositionTest].
 */
class TvChatActivity : AppCompatActivity() {

    internal lateinit var messages: LinearLayout
    internal lateinit var messageScroll: ScrollView
    internal lateinit var input: EditText
    internal lateinit var send: Button
    internal lateinit var newChat: Button
    internal lateinit var statusView: TextView
    internal lateinit var contextView: TextView

    /**
     * Бейдж статуса префилл-контекста (`context_status` side-канала): отдельный
     * крупный чип рядом со строкой статуса — на ТВ его читают с дистанции, поэтому
     * он не сливается с мелкой статус-цепочкой хода.
     */
    internal lateinit var contextStatusBadge: TextView

    /** Доска живых субагентов в шапке (SubagentStatusUpdate); скрыта, когда детей нет. */
    internal lateinit var subagentBoard: TextView

    /**
     * Чип квоты провайдера (mobile A10 parity) — статус-строка рядом с индикатором
     * контекста. Числа квоты относятся к АККАУНТУ провайдера, а не к диалогу, и
     * локальный бюджет контекста ([contextView]) остаётся отдельным источником.
     */
    internal lateinit var quotaChip: TextView
    internal lateinit var chatRoot: LinearLayout
    internal lateinit var sessionsButton: Button
    internal lateinit var sessionsOverlay: View
    internal lateinit var sessionsStatus: TextView
    internal lateinit var sessionList: LinearLayout
    internal lateinit var closeSessions: Button
    internal lateinit var displayModeButton: Button

    /** Режим показа хода (паритет с мобильным ActivityDisplayMode): выбор + персистентность. */
    internal lateinit var activityMode: TvActivityModeSelection

    /** Последнее событие живого хода — вход политики статус-полосы («Тишина» глушит детали). */
    internal var lastLiveEvent: ChatEvent? = null

    /** Явный статус (исход steer, смена режима); живёт до следующего события/отправки. */
    internal var explicitStatus: String? = null

    /** «К последнему» jump control: visible only while the reader is away from the live edge. */
    internal lateinit var jumpToBottom: Button

    /**
     * Pin-aware auto-scroll (pure decisions: [TvAutoScrollPolicy]). A streaming answer
     * follows to the bottom ONLY while the user is pinned there; scrolling up releases
     * the pin, shows [jumpToBottom] and stops the per-token yank. Programmatic scrolls
     * use `scrollTo` (never `fullScroll(FOCUS_DOWN)`), so the D-pad focus stays put.
     */
    internal val autoScroll = TvAutoScrollPolicy()
    internal var jumpThresholdPx = 0

    // Mini player in the header: mirrors the global TvMusicPlayer transport
    // (rules: TvMiniPlayerSpec). Hidden while no track is bound.
    internal lateinit var miniPlayerRow: View
    internal lateinit var miniPlayerText: TextView
    internal lateinit var miniPlayerToggle: Button

    // Quick chips above the composer (content/activation/D-pad rules: TvQuickChipsPolicy).
    internal lateinit var quickChipsRow: LinearLayout
    internal val chipButtons = mutableListOf<Button>()
    internal val sessionRowViews = mutableListOf<View>()
    internal var browserRows: List<TvSessionBrowserPolicy.SessionRow> = emptyList()
    internal var browserReturnFocus: View? = null
    internal lateinit var browserBackCallback: OnBackPressedCallback

    /**
     * Параметры хода (паритет с мобильным чатом): модель из живого каталога
     * GET /api/models и уровень размышлений из GET/POST /api/reasoning. Доменные
     * держатели выбора — чистые классы ru.mars.tv.chat (JVM-тестируемые); экран
     * только рендерит их решения и возит HTTP через репозитории core-network.
     */
    internal lateinit var modelSelection: TvModelSelection
    internal lateinit var reasoningSelection: TvReasoningSelection
    internal lateinit var modelButton: Button
    internal lateinit var reasoningButton: Button

    /**
     * Чип квоты провайдера: решения (видимость/состояние/тон) — в чистой
     * [TvQuotaPolicy] (JVM-тесты), экран только возит HTTP и рендерит модель.
     * Только ЧТЕНИЕ: квота нигде не пишется и не мутируется.
     */
    internal val quotaPolicy = TvQuotaPolicy()

    // Пикер параметров хода: один модальный оверлей на модель и уровни размышлений
    // (механика та же, что у браузера диалогов: блокировка фокуса чата, BACK, возврат).
    internal lateinit var paramsOverlay: FrameLayout
    internal lateinit var paramsTitle: TextView
    internal lateinit var paramsList: LinearLayout
    internal lateinit var closeParamsButton: Button
    internal lateinit var pickerBackCallback: OnBackPressedCallback
    internal val pickerRowViews = mutableListOf<View>()
    internal var pickerSelect: ((Int) -> Unit)? = null
    internal var pickerReturnFocus: View? = null

    /**
     * Navigation token: a session-list/session-detail fetch that returns AFTER the user
     * opened something else (another dialog, «Новый чат», closed overlay) must not clobber
     * the current screen (mobile `sessionNavigation` parity).
     */
    internal var sessionLoadToken = 0

    internal var stream: Job? = null
    internal val store by lazy { AndroidKeyStoreCredentialStore(this) }

    // --- stream autonomy wiring ---------------------------------------------------------

    /** The SINGLE mid-turn recovery job ([TvChatStreamRecoveryLoop]); at most one at a time. */
    internal var recovery: Job? = null

    /**
     * Stream generation: bumped by every explicit local teardown («Новый чат», opening
     * another dialog, «Остановить», выход). Stream/recovery callbacks carrying a stale
     * generation are dropped — late events of a dead stream never reach the transcript,
     * the status strip or the interactive cards. A TECHNICAL recreate does not bump it
     * (the new instance starts at 0 with no stale locals to guard against).
     */
    internal var streamGeneration = 0

    /** Live SSE resume cursor (`Last-Event-ID`): fed by `onEventId` of EVERY stream. */
    internal val resumeTracker = StreamResumeTracker()

    /** Core recovery brain: failure classification, bounded backoff, offline parking. */
    internal val recoveryCoordinator = MidTurnRecoveryCoordinator()

    /** Android connectivity snapshot + observer for the recovery loop's offline parking. */
    internal val connectivityProbe: TvConnectivityProbe by lazy {
        TvAndroidConnectivityProbe(applicationContext)
    }

    /**
     * Per-turn answer rendering state shared by a fresh send and every reattach: a still
     * streaming bubble is CONTINUED by a cursor-resumed attach (never duplicated); a
     * locked (Done/Error) bubble is replaced by a fresh one ([beginReattachedTurn]).
     */
    internal var liveAnswer: TextView? = null
    internal var liveAccumulator: TvAnswerAccumulator? = null

    internal val recoveryLoop: TvChatStreamRecoveryLoop by lazy {
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
    internal val sessionFlow = TvChatSessionFlow(SessionRepository(), TvChatActivity.BASE_URL)

    /**
     * Interactive attention requests (P0): approval/clarify arrive as SSE events and must NOT be
     * silently swallowed — they block the turn until answered. The controller owns the state
     * machine (queue refresh, stale/identity guards, cancel-without-respond); the surface renders
     * inline D-pad-focusable cards in the transcript. All HTTP stays in core-network
     * ([InteractiveRequestsRepository] via [RepositoryTvInteractiveGateway]) — no network logic here.
     */
    internal lateinit var interactiveSurface: TvChatInteractiveSurface
    internal lateinit var interactive: TvInteractiveController

    /** Server-side turn stop (/api/chat/cancel) — all HTTP/auth stays in core-network. */
    internal lateinit var controlGateway: TvChatControlGateway

    /** True while an explicit «Остановить» teardown is in flight (the stream finally defers to it). */
    internal var stopping = false

    // --- устойчивость транскрипта (caps/подрезка/батчинг — решения в chat-политиках) ----

    /**
     * Handler отложенного flush живого пузыря. Все отложенные задачи снимаются в
     * [onDestroy] — postDelayed не переживает Activity и не держит на неё ссылку.
     */
    internal val uiHandler = Handler(Looper.getMainLooper())

    /** Ещё не отрисованный текст живого пузыря (батчинг: [TvLiveRenderBatchPolicy]). */
    internal var pendingLiveRender: String? = null
    internal var liveFlushScheduled = false
    internal var lastLiveRenderMs = 0L
    internal val liveFlushRunnable = Runnable {
        liveFlushScheduled = false
        flushLiveAnswer()
    }

    /** Строки активности («Всё») в порядке добавления: cap снимает самые старые. */
    internal val activityLineViews = ArrayDeque<View>()

    /** Счётчики скрытого для маркера «…ранее скрыто» (срезы истории + живая подрезка). */
    internal var hiddenMessages = 0
    internal var hiddenActivity = 0
    internal var hiddenMarkerView: TextView? = null

    /**
     * Сколько строк истории рисуется от конца (окно рендера). Растёт страницами по
     * нажатию на маркер «…ранее скрыто»: голова длинного диалога достижима пультом
     * ([TvTranscriptRetentionPolicy.nextWindow]). Сбрасывается при открытии другого
     * диалога и «Новом чате».
     */
    internal var historyPageRows = TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES

    /** Полный список строк открытого диалога — источник для догрузки страниц. */
    internal var fullHistoryRows: List<TvSessionBrowserPolicy.TranscriptRow> = emptyList()

    /**
     * Баннер состояния хода, который НЕЛЬЗЯ переподключить
     * ([TvTurnStatePolicy.notice]): «требуется подтверждение», «ход идёт», «в очереди».
     * null — терять нечего. Живёт в ленте (первым ребёнком) и сам является действием:
     * нажатие перечитывает диалог.
     */
    internal var turnNoticeView: TextView? = null
    internal var currentNotice: TvTurnStatePolicy.Notice? = null

    /** Фоновая перепроверка состояния невидимого хода (bounded backoff, TV-политика). */
    internal var turnWatchJob: Job? = null

    /** Доска субагентов: childId -> снимок (редьюсер core [SubagentStatusPolicy]). */
    internal var subagentBoardState: Map<String, SubagentStatus> = emptyMap()

    /** Диалог, на который уже переключил push-интент (повторная доставка не перечитывает). */
    internal var routedPushSessionId: String? = null

    /**
     * Снимок строк, РЕАЛЬНО нарисованных в ленте из истории (после среза
     * [TvTranscriptRetentionPolicy.historySlice]): база для инкрементальной
     * перезагрузки ([TvHistoryReloadPolicy]) — неизменившиеся пузыри
     * переиспользуются, а не сносятся и не инфлейтятся заново.
     */
    internal var renderedRows: List<TvSessionBrowserPolicy.TranscriptRow> = emptyList()

    /** Текущий бейдж статуса префилл-контекста (side-канал `context_status`). */
    internal var contextBadge: TvContextBadge? = null

    /** Таймер автосброса бейджа контекста; новый кадр его перезапускает. */
    internal var contextBadgeJob: Job? = null

    /**
     * Системная настройка «уменьшить движение», прочитанная ОДИН раз на экран и переданная
     * в политику движения ([TvMotionPolicy]): подъём под фокусом D-pad и кроссфейд фоновых
     * селекторов схлопываются в мгновенную смену состояния, когда она включена
     * (Settings.Global.ANIMATOR_DURATION_SCALE).
     */
    internal val reducedMotion: Boolean by lazy {
        TvMotionPolicy.isReducedMotion(
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        )
    }

    // --- lateinit-guards for the section files ------------------------------------------
    // `::field.isInitialized` is only legal inside the declaring class, so every guarded
    // field the section files ask about gets a one-line accessor here.
    internal val activityModeReady get() = ::activityMode.isInitialized
    internal val contextStatusBadgeReady get() = ::contextStatusBadge.isInitialized
    internal val controlGatewayReady get() = ::controlGateway.isInitialized
    internal val interactiveReady get() = ::interactive.isInitialized
    internal val messagesReady get() = ::messages.isInitialized
    internal val modelButtonReady get() = ::modelButton.isInitialized
    internal val paramsOverlayReady get() = ::paramsOverlay.isInitialized
    internal val quotaChipReady get() = ::quotaChip.isInitialized
    internal val reasoningButtonReady get() = ::reasoningButton.isInitialized
    internal val sessionsOverlayReady get() = ::sessionsOverlay.isInitialized
    internal val subagentBoardReady get() = ::subagentBoard.isInitialized

    /**
     * Host shell: the layout is bound in [bindChatViews] and the surface wired in
     * [wireChatSurface]; only the lifecycle-critical ordering stays here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_chat)
        // Global music transport: audio focus + network wake mode need an application
        // context (the header mini player resumes playback through TvMusicPlayer).
        TvMusicPlayer.attachContext(this)
        bindChatViews()
        // Переход с TV-экрана «Профили»: новая сессия этого чата создаётся в
        // выбранном профиле (SessionRepository.createSession принимает `profile`).
        // Уже привязанная сессия профиль не меняет — см. TvChatSessionFlow.bindProfile.
        sessionFlow.bindProfile(intent?.getStringExtra(ru.mars.tv.profiles.TvProfilesRouting.EXTRA_PROFILE))
        wireChatSurface(savedInstanceState)
    }

    /**
     * Тап по push-уведомлению при уже открытом экране (`launchMode="singleTop"`):
     * интент приходит сюда, а не в [onCreate]. Маршрут тот же — [routePushIntent].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routePushIntent(intent)
    }

    /**
     * Persists the minimal session/stream binding across a technical recreation
     * ([TvChatLifecyclePolicy.sanitizedBinding] — blank ids never reach the bundle).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        TvChatLifecyclePolicy.sanitizedBinding(sessionFlow.sessionId, sessionFlow.activeStreamId)
            ?.let { binding ->
                binding.sessionId?.let { outState.putString(TvChatActivity.KEY_SESSION_ID, it) }
                binding.activeStreamId?.let { outState.putString(TvChatActivity.KEY_STREAM_ID, it) }
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
        turnWatchJob?.cancel()
        turnWatchJob = null
        activityLineViews.clear()
        hiddenMarkerView = null
        browserReturnFocus = null
        pickerReturnFocus = null
        pickerSelect = null
        if (::interactive.isInitialized) interactive.destroy()
        super.onDestroy()
    }

    /** Production [TvRecoveryTransport]: core repositories + the shared resume cursor. */
    private inner class RecoveryTransport : TvRecoveryTransport {
        override suspend fun fetchSessionDetail(sessionId: String): SessionDetail? {
            val credential = store.read() ?: return null
            return runCatching {
                SessionRepository().getSessionDetail(TvChatActivity.BASE_URL, credential, sessionId)
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
            // Поколение фиксируется на входе: кадр side-канала, пришедший после смены
            // экрана/диалога, не должен красить бейдж нового хода.
            val boundGeneration = streamGeneration
            ChatRepository().attachToStream(
                TvChatActivity.BASE_URL, credential, streamId,
                lastEventId = lastEventId,
                onSideEvent = sideEventCallback(sessionFlow.sessionId, boundGeneration),
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

    companion object {
        internal const val BASE_URL = "https://marscore.ru"
        internal const val STOPPING = "Останавливаю…"
        internal const val LOADING_SESSIONS = "Загружаю список…"
        internal const val OPENING_SESSION = "Открываю диалог…"
        internal const val KEY_SESSION_ID = "tv_chat_session_id"
        internal const val KEY_STREAM_ID = "tv_chat_active_stream_id"

        /** Тег View строк активности — по нему чистая политика отличает их от сообщений. */
        internal const val TAG_ACTIVITY_LINE = "tv-transcript-activity-line"

        /**
         * Тег пузыря, нарисованного из истории: по нему инкрементальная перезагрузка
         * ([TvHistoryReloadPolicy]) понимает, что View можно переиспользовать как есть.
         */
        internal const val TAG_HISTORY_ROW = "tv-transcript-history-row"

        /** Тег баннера непереподключаемого хода: защищён от подрезки ленты. */
        internal const val TAG_TURN_NOTICE = "tv-transcript-turn-notice"

        /** Тег маркера «…ранее скрыто» (фокусируемое действие догрузки страницы). */
        internal const val TAG_HIDDEN_MARKER = "tv-transcript-hidden-marker"

        /** Recovery give-up label of a dead credential ([MidTurnRecoveryCoordinator.giveUpReason]). */
        internal const val GIVE_UP_AUTH_PREFIX = "auth rejected"

        /** Process-wide scope for fire-and-forget server cancels that must outlive the Activity. */
        internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
