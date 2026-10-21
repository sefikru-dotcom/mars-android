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

class ChatActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    internal lateinit var store: AndroidKeyStoreCredentialStore
    internal lateinit var messages: LinearLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    internal lateinit var statusView: TextView
    internal val uiState = ChatUiState()

    internal val composerLayoutPresenter = ComposerLayoutPresenter()
    private lateinit var composerQuoteView: LinearLayout
    private lateinit var composerQuoteTitle: TextView
    private lateinit var composerQuoteExcerpt: TextView
    private lateinit var liveRunPlate: LinearLayout
    private lateinit var liveRunLabel: TextView
    private lateinit var liveRunDetail: TextView

    internal lateinit var todoWidgetPlate: LinearLayout
    internal lateinit var todoWidgetHeader: LinearLayout
    internal lateinit var todoWidgetTitle: TextView
    internal lateinit var todoWidgetChevron: ImageView
    internal lateinit var todoWidgetContainer: LinearLayout
    internal var todoWidgetExpanded: Boolean = false

    /** Карточка неотправленных сообщений (OfflineQueuePresenter, долг O8). */
    internal lateinit var queuePlate: LinearLayout
    internal lateinit var queueTitle: TextView
    internal lateinit var queueRows: LinearLayout
    internal lateinit var queueDiscardAll: Button
    internal val offlineQueuePresenter = OfflineQueuePresenter()

    /** Карточка статуса цели (/goal loop, долг O14): только то, что прислал живой поток. */
    private lateinit var goalPlate: LinearLayout
    private lateinit var goalDot: View
    private lateinit var goalTitle: TextView
    private lateinit var goalDetail: TextView
    private var goalCard: GoalCardUi = GoalLoopPolicy.hidden()

    private lateinit var interactiveView: MarsInteractiveView
    internal lateinit var interactiveController: InteractiveRequestController

    /** Transcript auto-scroll decisions (pure policy): a reader scrolled up must survive SSE tokens. */
    internal var autoScroll = AutoScrollPolicy()

    // --- Устойчивость к обрыву связи -------------------------------------------------------------
    // Чистые политики (покрыты JVM-тестами): состояние сети, баннер, очередь неотправленных
    // сообщений; платформенная обвязка — NetworkConnectivityObserver (детектор) и
    // SharedPreferences-адаптеры хранилищ. Офлайн-кэш сессий/транскриптов — SessionOfflineCache
    // из core-network: список диалогов и последние сообщения переживают обрыв связи.
    internal val networkPolicy = NetworkStatePolicy()
    private val bannerPolicy = NetworkBannerPolicy()
    /**
     * O7: защита композера от программных перезаписей. Решения (текст/фокус) — чистые,
     * хост только применяет их к EditText/IME. Поток ответов и сеть текста не касаются.
     */
    internal val composerGuard = ComposerInputGuardPolicy()
    private lateinit var networkObserver: NetworkConnectivityObserver
    internal lateinit var offlineCache: SessionOfflineCache
    internal lateinit var offlineQueue: OfflineMessageQueuePolicy
    private lateinit var networkBanner: TextView

    /**
     * Первичное восстановление открытой сессии завершено (транскрипт привязан). До него
     * автофлаш офлайн-очереди запрещён: отправка перекрасила бы чужой загружаемый диалог.
     */
    internal var initialRestoreDone = false
    /** Scroll-to-bottom FAB visibility (pure policy): hysteresis over the distance from the tail. */
    internal var scrollToBottomPolicy = ScrollToBottomPolicy()
    private var scrollToBottomFab: ImageButton? = null
    /** Stream-recovery decisions (pure policy): reattach plan + dead-stream (404) detection. */
    internal val reattachPolicy = StreamReattachPolicy()

    /**
     * Авто-recovery доставленного SSE-хода: единственный recovery-job (чистый JVM-цикл
     * [MidTurnRecoveryController] поверх core-координатора MidTurnRecoveryCoordinator),
     * классификатор транспортных обрывов (серверный tool error ≠ обрыв сети) и защита от
     * шторма обновлений списка сессий на восстановлении сети.
     */
    internal val transportFailureClassifier = TransportFailureClassifier()
    internal lateinit var recovery: MidTurnRecoveryController
    internal val sessionRefreshThrottle = SessionListRefreshThrottle()
    internal var sessionRefreshJob: Job? = null

    internal var stream: Job? = null
    internal var currentSessionId: String? = null
    internal var currentSessionReadOnly = false
    /** A13: субагентские диалоги view-only — серверные операции над ходом запрещены. */
    internal var currentSessionIsSubagent = false
    internal var currentStreamId: String? = null
    /** Черновики несент-сообщений по диалогам: чистая политика [SessionDraftController]. */
    internal lateinit var drafts: SessionDraftController
    /** Guard асинхронной навигации по диалогам (pure): побеждает последняя загрузка. */
    internal val sessionNavigation = SessionNavigationPolicy()
    /** Guard живого хода send-пути (pure): поздние SSE-события старого хода не попадают в новый экран. */
    internal val liveTurnGuard = LiveTurnGuard()

    /** Решения отправки/стрима — чистая политика ([ChatTurnDispatcher]): маршрут тапа, итоги хода, recovery. */
    internal val turnDispatcher = ChatTurnDispatcher()

    /** Решения офлайн-очереди — чистая политика ([ChatOfflineQueuePolicy]): буфер, гейт флаша, ключ возврата. */
    internal val offlineQueuePolicy = ChatOfflineQueuePolicy()
    /** Сохранение открытого диалога при пересоздании экрана (pure policy). */
    internal val recreationPolicy = SessionRecreationPolicy()
    /** Одноразовая очистка локальных plaintext-данных при выходе из авторизации. */
    internal lateinit var authExitCleanup: AuthExitCleanup

    internal val firstSessionFlow = FirstSessionFlow(SessionRepository(), BASE_URL)
    internal var explicitStatus: String? = null

    /**
     * Транзиентная строка статуса префилл-контекста (SSE `context_status`,
     * [ContextStatusPresenter]): видна в статус-баре ограниченное время и
     * сбрасывается/перезаписывается каждым новым кадром — не накапливается.
     */
    private var contextStatusLine: String? = null
    private var contextStatusJob: Job? = null
    internal lateinit var modelSelection: ModelSelection
    internal var modelBtn: Button? = null
    private lateinit var reasoningSelection: ReasoningEffortSelection
    private var reasoningBtn: Button? = null
    private lateinit var activityModeSelection: ActivityModeSelection
    private var activityModeBtn: Button? = null
    internal lateinit var workspaceSelection: WorkspaceSelection
    private var workspaceBtn: Button? = null
    private var contextRing: ContextRingView? = null
    /** Живой заголовок диалога в шапке (SSE-кадр `title`, авто-титлинг на ходу). */
    private var sessionTitleView: TextView? = null
    /** Пилюля расхода/стоимости сессии (A14, `GET /api/session/usage`); скрыта при 0/недоступности. */
    internal var usageIndicator: TextView? = null
    /** Последний измеренный расход сессии; null — ещё не запрошен или недоступен. */
    internal var sessionUsage: SessionUsage? = null
    /** A9: чип набора инструментов сессии и его презентер (состояние выбора/записи). */
    internal var toolsetsChip: Button? = null
    internal val toolsetsPresenter = SessionToolsetsPresenter()
    /** A10: чип квоты провайдера и его презентер (состояние читается из полей ответа). */
    internal var quotaChip: TextView? = null
    internal val quotaPresenter = ProviderQuotaPresenter()
    /** Дебаунс серверной записи черновика composer (A15) — чистая политика. */
    internal val serverDraftSync = ServerDraftSyncPolicy()
    internal var serverDraftJob: Job? = null
    /**
     * Программная вставка текста в композитор (восстановление черновика, steer-leftover):
     * текст-вотчер не должен принимать её за ввод пользователя и слать черновик на сервер.
     */
    internal var composerTextProgrammatic = false
    internal lateinit var compressionController: ContextCompressionController
    private lateinit var autoCompressionStorage: AutoCompressionStorage
    private var compressionRunning = false
    private lateinit var attachmentChips: LinearLayout
    internal val attachments = mutableListOf<ChatAttachment>()
    // Voice output: policy lives in VoiceOutputController (pure JVM), the platform
    // TextToSpeech is isolated behind AndroidSpeechEngine — ChatActivity only wires both.
    private lateinit var speechEngine: AndroidSpeechEngine
    internal lateinit var voiceOutput: VoiceOutputController
    /** Серверный TTS (POST /api/tts) с фолбэком на [speechEngine] — движок контроллера. */
    private lateinit var serverTtsEngine: ServerTtsSpeechEngine
    private lateinit var serverTtsPlayback: AndroidServerTtsPlayback
    /**
     * Потоковая озвучка: дельты ответа → фразы → звук, не дожидаясь конца хода. Живёт
     * параллельно [voiceOutput] (он остаётся путём для готового сообщения и фолбэка), но
     * при активном стриме звучит только она — две озвучки одного текста исключены флагом
     * [streamSpeechActive].
     */
    internal lateinit var streamingSpeech: StreamingSpeechController
    /** Ход сейчас озвучивается потоково: терминальный Done не должен озвучивать его второй раз. */
    private var streamSpeechActive = false
    /** TTS init callback may fire before the controller exists (sync construction failure). */
    private var speechEngineInitResult: Boolean? = null
    private lateinit var voiceSession: VoiceSessionController
    /** Своя запись с микрофона (WAV 16 кГц моно) — вход серверного распознавания. */
    private lateinit var voiceRecorder: VoiceRecorder
    /**
     * Hands-free цикл (idle → listening → thinking → speaking → listening). Чистая логика в
     * [VoiceDialogLoop]: состояния, защита от дубля, очередь реплик, barge-in за флагом.
     */
    private lateinit var voiceLoop: VoiceDialogLoop
    /** Непрерывный микрофон hands-free: сегментер режет поток на реплики (см. [HandsFreeCapture]). */
    private lateinit var handsFreeCapture: HandsFreeCapture

    /**
     * Вид голосового диалога (кнопка/статус/a11y) — чистая [VoiceDialogPresentationPolicy].
     * Activity только подставляет ресурсы и рисует: решения о фазе, иконке и подписи живут
     * в политике, поэтому «красная кнопка» и её сброс проверяются JVM-тестами.
     */
    internal val voicePresentation = VoiceDialogPresentationPolicy()

    /** Жесты микрофона: тап (ввод/перебивание/закрыть реплику) и удержание (режим) — в одной политике. */
    internal val voiceGestures = VoiceMicGesturePolicy()

    /**
     * Что из хода произносить: ответ — да, мысли — нет, инструменты — одна фраза
     * [VoiceTurnSpeechPolicy.PROGRESS_LINE] на ход, финал — только если ответ ещё не звучал.
     */
    internal val voiceTurnSpeech = VoiceTurnSpeechPolicy()

    /**
     * Шов перебивания на сервере: `POST /api/voice/interrupt` (`{voice_id, session_id}`).
     *
     * Перебивание НЕ отменяет ход: агент продолжает работу, глохнет только звук. Пока голосовой
     * SSE-канал не подключён (в живом пути один канал — существующий chat SSE), шов пуст и
     * локальная остановка остаётся единственным эффектом — встраивать второй канал «для галочки»
     * значило бы держать два источника правды об одном ходе. Контракт — UI-CONTRACT.md.
     */
    internal var voiceInterruptHook: ((sessionId: String?) -> Unit)? = null

    /** Фаза, уже нарисованная на кнопке: перерисовка только на смене (без аллокаций на кадр стрима). */
    private var renderedVoicePhase: VoiceDialogPhase? = null

    /** Ресурсы иконок состояния — единственное, что политика получает от Android. */
    private val voiceIcons = VoiceDialogIcons(
        mic = R.drawable.ic_mic,
        thinking = R.drawable.ic_voice_thinking,
        speaking = R.drawable.ic_volume,
    )

    /**
     * Потоковый ASR на устройстве (sherpa-onnx). Создаётся один раз, модель догружается в фоне
     * при первом hands-free запуске. `null` — движок недоступен, диалог идёт через whisper.
     */
    private var streamingStt: StreamingStt? = null

    /** Порт движка: то, что видит [StreamingStt]. Отдельно — чтобы освободить модель в onDestroy. */
    private var asrPort: AsrEnginePort? = null
    /** Разрешение запросили из долгого нажатия на микрофон: после выдачи стартует hands-free. */
    private var pendingHandsFree = false
    /** Распознавание уже записанного звука: повторный тап не запускает второе. */
    private var voiceTranscribeJob: Job? = null
    private var volumeBtn: ImageButton? = null
    private var voiceBtn: ImageButton? = null
    private val pickAttachment = 4101
    /** requestCode рантайм-запроса RECORD_AUDIO (код системного распознавателя — тот же). */
    private val recognizeVoice = 4102

    // Mini-player in chat
    private lateinit var miniPlayerBox: LinearLayout
    /**
     * INTENDED visibility of the mini-player strip (not `View.visibility`, which stays
     * VISIBLE for the whole hide animation). Mirrors [miniPlayerBox]'s target state so a
     * track arriving mid-hide cancels the hide instead of being dropped. Starts false —
     * the strip is built GONE.
     */
    private var miniPlayerVisible = false
    private lateinit var miniPlayerTitle: TextView
    private lateinit var miniPlayerPlayBtn: ImageButton
    private lateinit var quickChipsScroll: HorizontalScrollView
    private lateinit var smartChipsRow: HorizontalScrollView

    // Левый бар (DrawerLayout): ТОЛЬКО приложенческие разделы (AppSectionsBar) —
    // двухуровневый навигатор волны 2; список диалогов вынесен на отдельный
    // уровень ChatSessionsActivity (корень раздела CHAT).
    internal lateinit var drawer: DrawerLayout

    // --- Поиск по текущему диалогу (transcript search) -----------------------------------------
    // Совпадения адресуются чистой MessageSearchPolicy (якорь messageId + ordinalInMessage):
    // каждое вхождение — отдельный шаг навигации, в том числе несколько в одном сообщении.
    // Хост рисует панель, подсвечивает СПАН совпадения внутри пузыря и скроллит к нему.
    internal lateinit var searchPlate: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchCounter: TextView
    // Состояние поиска и вся визуальная подсветка живут в контроллере (создаётся в onCreate):
    // Activity оставляет у себя только view-обвязку панели и узкие адаптеры к транскрипту.
    private lateinit var transcriptSearch: TranscriptSearchHighlightController
    internal lateinit var lightbox: ChatImageLightboxController

    // --- ANR-устойчивость транскрипта (инкрементальный рендер + троттлинг стрима) ---------------
    // Чистые политики (JVM-тесты): StreamRenderThrottle (кадровый троттлинг SSE-событий),
    // CardSnapshotPolicy/IncrementalTranscriptPolicy (что перерисовывать: «грязные» карточки,
    // а не весь контейнер), FrameVersionGate (устаревший фоновый кадр не публикуется поверх
    // свежего). Хост здесь — только view-обвязка.
    /** Карточка транскрипта: wrapper («день-разделитель + пузырь») и последний ОТРИСОВАННЫЙ снимок. */
    /** Нарисованные карточки: children контейнера [messages] ↔ cardEntries 1:1 по индексу. */
    internal val cardEntries = mutableListOf<CardEntry>()

    /**
     * Снимки карточек между кадрами: на кадре стрима меняется одно сообщение, остальные
     * переиспользуются по тождеству экземпляра — без календарной арифметики и аллокаций.
     */
    internal val snapshotCache = TranscriptSnapshotCache()

    /** Гейт ленты smart-чипов: её входы меняются 2-3 раза за ход, а не 20 раз в секунду. */
    private val chipsGate = QuickChipsRenderGate()

    /**
     * Хост рендерера карточки: узкие адаптеры к состоянию и колбэкам Activity вместо передачи
     * её целиком ([ChatMessageCardRenderer.Host]). Один экземпляр на Activity — аллокаций на
     * кадр стрима не добавляет; методы читают актуальное состояние в момент вызова.
     */
    internal val cardRendererHost = object : ChatMessageCardRenderer.Host {
        override fun messageAt(index: Int): ChatMessage? = uiState.messages.getOrNull(index)

        override fun modelLabel(): String? = modelSelection.buttonLabel()

        override val activityMode: ActivityDisplayMode get() = uiState.activityMode

        override fun isToolGroupExpanded(messageIndex: Int): Boolean? = uiState.isToolGroupExpanded(messageIndex)

        override fun toggleThoughts(messageIndex: Int) {
            uiState.toggleThoughts(messageIndex)
        }

        override fun toggleTool(messageIndex: Int, toolIndex: Int) {
            uiState.toggleTool(messageIndex, toolIndex)
        }

        override fun toggleToolGroup(messageIndex: Int, currentlyExpanded: Boolean) {
            uiState.toggleToolGroup(messageIndex, currentlyExpanded)
        }

        override fun isCodeBlockExpanded(messageIndex: Int, blockIndex: Int): Boolean =
            uiState.isCodeBlockExpanded(messageIndex, blockIndex)

        // Фолд code-блока меняет «эпоху хрома» (все снимки становятся «грязными») — как раньше.
        override fun toggleCodeBlock(messageIndex: Int, blockIndex: Int) {
            uiState.toggleCodeBlock(messageIndex, blockIndex)
            transcriptChromeEpoch++
            renderMessages()
        }

        override fun rerenderTranscript() {
            renderMessages()
        }

        override fun copyMessage(text: String) {
            this@ChatActivity.copyMessage(text)
        }

        override fun speakMessage(text: String) {
            this@ChatActivity.speakMessage(text)
        }

        override fun showMessageActions(message: ChatMessage, index: Int) {
            this@ChatActivity.showMessageActions(message, index)
        }

        override fun openImage(rawUrl: String) {
            lightbox.open(rawUrl)
        }

        override fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        override fun a11yHeader(header: View, description: String, state: String) {
            applyExpandableHeaderA11y(header, description, state)
        }
    }

    /** Снимки последнего завершённого рендера (вход [IncrementalTranscriptPolicy.plan]). */
    internal var renderedSnapshots: List<CardSnapshot> = emptyList()

    /**
     * Эпоха «хрома»: события, влияющие сразу на ВСЕ карточки (режим активности,
     * свёртка code-блока). Инкремент в [CardSnapshot.chromeEpoch] делает все снимки
     * «грязными» → политика выбирает FullRebuild (как и было до инкрементального рендера).
     */
    internal var transcriptChromeEpoch = 0L

    internal val streamThrottle = StreamRenderThrottle()

    /** Отложенный trailing-flush стрима (ровно один; отменяется любым состоявшимся рендером). */
    internal var streamFlushJob: Job? = null

    internal val frameGate = FrameVersionGate()

    /** Фоновый парсинг markdown стримящегося пузыря (ровно один в полёте). */
    internal var markdownParseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        // FCM push (волна 4): регистрируем токен на сервере, если есть credential
        // (покрывает первый вход, перезапуск и пропущенную офлайн ротацию), и
        // запрашиваем POST_NOTIFICATIONS на Android 13+ — без него баннеры молчат.
        PushTokenSync.syncNow(this)
        ru.mars.mobile.service.PushNotifications.ensurePostPermission(this)
        interactiveView = MarsInteractiveView(this)
        interactiveController = InteractiveRequestController(
            view = interactiveView,
            gateway = RepositoryInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            state = uiState,
            uiDispatcher = MainThreadDispatcher(),
        ).also { interactiveView.listener = it }

        interactiveController.onClarifyAnswered = { answer -> echoClarifyAnswer(answer) }
        interactiveView.autonomyEnabledProvider = { uiState.autonomyEnabled }
        interactiveController.onAutonomyChanged = { enabled ->
            Toast.makeText(this, if (enabled) "Автономность включена" else "Автономность выключена", Toast.LENGTH_SHORT).show()
        }
        modelSelection = ModelSelection(PrefsModelChoiceStorage(getSharedPreferences("mars-model-choice", MODE_PRIVATE)))
        reasoningSelection = ReasoningEffortSelection(PrefsReasoningChoiceStorage(getSharedPreferences("mars-reasoning-choice", MODE_PRIVATE)))
        activityModeSelection = ActivityModeSelection(PrefsActivityModeStorage(getSharedPreferences("mars-activity-mode", MODE_PRIVATE)))
        workspaceSelection = WorkspaceSelection(PrefsWorkspaceChoiceStorage(getSharedPreferences("mars-workspace-choice", MODE_PRIVATE)))
        autoCompressionStorage = PrefsAutoCompressionStorage(getSharedPreferences("mars-auto-compression", MODE_PRIVATE))
        drafts = SessionDraftController(PrefsSessionDraftStore(getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE)))
        // Офлайн-устойчивость: кэш сессий/транскриптов (core-network) и персистентная очередь
        // неотправленных сообщений. Оба хранилища — приватные plaintext-данные, поэтому входят
        // в cleanup при logout/реавторизации вместе с черновиками.
        val offlineCachePrefs = getSharedPreferences(OFFLINE_CACHE_PREFS, MODE_PRIVATE)
        val pendingQueuePrefs = getSharedPreferences(PENDING_QUEUE_PREFS, MODE_PRIVATE)
        offlineCache = SessionOfflineCache(SharedPreferencesKeyValueStore(offlineCachePrefs))
        val pendingMessageStore = PrefsPendingMessageStore(pendingQueuePrefs)
        offlineQueue = OfflineMessageQueuePolicy(pendingMessageStore)
        // Выход из авторизации (logout/реавторизация) обязан стереть локальные plaintext-данные
        // до finish(), и после этого черновики больше не сохраняются (onPause не пишет назад).
        // Набор хранилищ — единая фабрика AuthExitStores (P0): живой экземпляр очереди
        // передаётся override'ом, чтобы cleanup и отправка делили один объект.
        authExitCleanup = AuthExitStores.cleanup(
            context = this,
            overrides = mapOf(AuthExitStores.PENDING_QUEUE_PREFS to pendingMessageStore),
        )
        compressionController = ContextCompressionController(
            host = CompressionHostBridge(),
            gateway = RepositoryContextCompressionGateway(ContextCompressionRepository(), BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            streamingProvider = { uiState.status == ChatStatus.TYPING },
            settings = autoCompressionStorage,
        )
        uiState.setActivityMode(activityModeSelection.current)
        autoScroll = AutoScrollPolicy(bottomThresholdPx = dp(56))
        scrollToBottomPolicy = ScrollToBottomPolicy(showThresholdPx = dp(120), hideThresholdPx = dp(56))
        // Voice-output wiring: the engine reports its async init here; the controller owns
        // every speak/stop decision. The sticky auto-speak toggle is persisted across launches.
        val voiceOutputPrefs = getSharedPreferences(VOICE_OUTPUT_PREFS, MODE_PRIVATE)
        speechEngine = AndroidSpeechEngine(
            context = this,
            onInitFinished = { available ->
                if (::voiceOutput.isInitialized) voiceOutput.onEngineReady(available)
                else speechEngineInitResult = available
            },
            // Движок дочитал: контроллер снимает флаг озвучки, hands-free-цикл снова слушает.
            onSpeechFinished = {
                runOnUiThread { if (::voiceOutput.isInitialized) voiceOutput.notifySpeakingFinished() }
            },
        )
        // Серверный TTS с прозрачным фолбэком на системный: контроллер видит один движок
        // (serverTtsEngine), платформенный speechEngine остаётся путём деградации. 401 на
        // device-Bearer — известный серверный блокер, клиент обходить его не должен: при
        // недоступности сервера голос уходит в системный TTS молча.
        serverTtsPlayback = AndroidServerTtsPlayback(
            context = this,
            // Серверное аудио доиграло — тот же сигнал, что и у системного TTS.
            onFinished = {
                runOnUiThread { if (::voiceOutput.isInitialized) voiceOutput.notifySpeakingFinished() }
            },
        )
        serverTtsEngine = ServerTtsSpeechEngine(
            fallback = speechEngine,
            client = RepositoryServerTtsClient(
                baseUrl = BASE_URL,
                credentialProvider = { store.read() },
            ),
            playback = serverTtsPlayback,
            scope = lifecycleScope,
            // Платформенного TTS нет, а серверное аудио зазвучало — вывод снова возможен.
            onServerAudioAvailable = { runOnUiThread { voiceOutput.onEngineReady(true) } },
        )
        voiceOutput = VoiceOutputController(
            engine = serverTtsEngine,
            autoSpeakInitially = voiceOutputPrefs.getBoolean(KEY_AUTO_SPEAK, false),
            onAutoSpeakChanged = { enabled -> voiceOutputPrefs.edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply() },
        )
        // Потоковая озвучка ответа: фразы уходят в синтез по мере прихода дельт, первый
        // звук не ждёт конца хода. Тот же серверный синтез и свой плеер — очередь фраз
        // управляет воспроизведением строго по порядку, поэтому общий плеер с
        // serverTtsPlayback ей не годится.
        streamingSpeech = StreamingSpeechController.forAndroid(
            context = this,
            client = RepositoryServerTtsClient(
                baseUrl = BASE_URL,
                credentialProvider = { store.read() },
            ),
            scope = lifecycleScope,
            onFirstAudio = { runOnUiThread { onStreamSpeechStarted() } },
            onDrained = { runOnUiThread { onStreamSpeechDrained() } },
        )
        speechEngineInitResult?.let { available ->
            speechEngineInitResult = null
            voiceOutput.onEngineReady(available)
        }
        // Голосовой ввод: своя запись (16 кГц моно WAV) → серверное распознавание.
        // Микрофон открывается только по явному тапу (и только с разрешения).
        // Авто-стоп по тишине: человек говорит, запись закрывается сама после паузы, и дальше
        // идёт тот же путь, что у второго тапа (transcribe → sendMessage). Колбэк приходит с
        // потока чтения рекордера — на главный поток его перекладываем здесь, чтобы весь UI и
        // voiceSession жили в одном потоке.
        voiceRecorder = VoiceRecorder(
            onAutoStop = { recording -> runOnUiThread { onVoiceAutoStopped(recording) } },
        )
        // Hands-free: микрофон не закрывается между репликами, а сегментер режет поток по
        // тишине. Barge-in (L3) — за флагом и выключен по умолчанию: без AcousticEchoCanceler
        // и AudioSource.VOICE_COMMUNICATION микрофон слышит собственный динамик, и цикл
        // перебивал бы сам себя. Включать только после замеров на устройстве (POCO F5).
        // Потоковый ASR на устройстве: если движок не загрузится, цикл сам уйдёт на whisper
        // (StreamingStt.isReady останется false) — диалог не ломается.
        createAsrPort()
        voiceLoop = VoiceDialogLoop(
            listener = voiceLoopListener,
            bargeInEnabled = voiceOutputPrefs.getBoolean(KEY_VOICE_BARGE_IN, false),
            stt = streamingStt,
            // Ход уже идёт: вторая реплика уходит steer-ом, а не новым сообщением.
            turnActive = { uiState.status == ChatStatus.TYPING },
        )
        // Слушатель передаётся в start(): у захвата нет «постоянного» получателя.
        handsFreeCapture = HandsFreeCapture()
        // «Озвучка закончилась» приходит из движка (Android TTS — UtteranceProgressListener,
        // серверный — конец воспроизведения). Только после этого цикл снова слушает.
        voiceOutput.onSpeakingFinished = {
            runOnUiThread {
                if (::voiceLoop.isInitialized) voiceLoop.onAnswerFinished()
            }
        }
        // Voice-input state machine: guards the capture session, delivers haptic feedback on
        // start/finish and routes results into the composer. Pure logic lives in the controller.
        voiceSession = VoiceSessionController(object : VoiceSessionController.Listener {
            override fun onListeningStarted() {
                voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                statusView.text = VOICE_LISTENING_STATUS
            }

            override fun onResult(text: String) {
                // O7: диктовка — действие пользователя, а не программная перезапись.
                setComposerTextProgrammatically(text, TextApplyOrigin.VOICE_INPUT)
                input.setSelection(text.length)
                // The user explicitly spoke — arm reading of this turn's completed answer.
                voiceOutput.armSpeakNextAnswer()
            }

            override fun onError(message: String) {
                statusView.text = message
            }
        })
        setContentView(buildView())
        // Экранные UI-контроллеры: поиск по диалогу (состояние + подсветка) и просмотрщик
        // картинок (диалог + bounded-загрузка). Создаются после buildView(), потому что
        // получают готовую view-обвязку через узкие адаптеры хоста.
        transcriptSearch = TranscriptSearchHighlightController(transcriptSearchHost())
        lightbox = ChatImageLightboxController(
            context = this,
            scope = lifecycleScope,
            baseUrl = BASE_URL,
            onError = { message -> statusView.text = message },
        )
        // Recovery-цикл доставленного хода: транспорт — ТОЛЬКО attachToStream (повторный
        // POST chat/start структурно невозможен), listener — тонкий wiring в существующий
        // конвейер событий. Инициализация до networkObserver.start(): первый же снимок
        // сети синхронно вызовет onNetworkTransition → recovery.onNetworkChanged.
        recovery = MidTurnRecoveryController(
            scope = lifecycleScope,
            transport = RecoveryStreamTransport { streamId, lastEventId, onEventId ->
                val attachCredential = store.read()
                if (attachCredential == null) {
                    // Credential исчез прямо во время recovery (logout/принудительная
                    // реавторизация): цикл завершится GiveUp(auth rejected) → reauth.
                    flowOf(ChatEvent.Error("HTTP 401: unauthorized"))
                } else {
                    ChatRepository().attachToStream(
                        BASE_URL, attachCredential, streamId, lastEventId,
                        onTodoState = todoCallback(currentSessionId),
                        onSideEvent = sideEventCallback(currentSessionId),
                        onEventId = onEventId,
                    )
                }
            },
            listener = RecoveryListener(),
        )
        // Мониторинг сети: детектор стартует после построения UI (start() синхронно публикует
        // снимок текущего состояния — баннер корректен с первого кадра); переходы обновляют
        // баннер и запускают автофлаш офлайн-очереди.
        networkObserver = NetworkConnectivityObserver(this, networkPolicy) { transition ->
            onNetworkTransition(transition)
        }
        networkObserver.start()
        // При старте/пересоздании экрана композитор сразу получает черновик текущего диалога.
        restoreComposerDraft()

        // Системный Back — чистая NavBackPolicy (лестница: чат → диалоги → разделы →
        // выход). ChatActivity — уровень диалога: сначала закрывается панель поиска
        // по транскрипту, затем шторка разделов (оверлей над диалогом), иначе back
        // ведёт на уровень списка диалогов (ChatSessionsActivity, корень CHAT —
        // AppNavPolicy.back из ChatLevel.Session). Системный выход из чата невозможен.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (NavBackPolicy.resolve(
                    level = NavBackPolicy.Level.CHAT,
                    searchOpen = ::searchPlate.isInitialized && searchPlate.visibility == View.VISIBLE,
                    drawerOpen = drawer.isDrawerOpen(GravityCompat.START),
                )) {
                    NavBackPolicy.Action.CLOSE_SEARCH -> closeTranscriptSearch()
                    NavBackPolicy.Action.CLOSE_DRAWER -> drawer.closeDrawer(GravityCompat.START)
                    NavBackPolicy.Action.GO_TO_SESSION_LIST -> navigateToSessionList()
                    else -> Unit
                }
            }
        })
        MarsMusicPlayer.registerListener(this)
        loadSessions()
        refreshModelCatalog()
        refreshReasoningStatus()
        loadWorkspaces()
        // Пересоздание экрана (поворот/тема/смерть процесса): открытый диалог восстанавливается
        // из savedInstanceState; на холодном старте savedInstanceState == null -> новый чат.
        restoreOpenSession(savedInstanceState)
    }

    override fun onDestroy() {
        if (::networkObserver.isInitialized) networkObserver.stop()
        if (::interactiveController.isInitialized) interactiveController.destroy()
        if (::compressionController.isInitialized) compressionController.destroy()
        if (::interactiveView.isInitialized) interactiveView.dismissAllNow()
        if (::miniPlayerBox.isInitialized) miniPlayerBox.animate().cancel()
        MarsMusicPlayer.unregisterListener(this)
        // Живой ход умирает вместе с экраном (destroy — точка прерывания collect send-пути
        // и recovery-цикла).
        teardownLiveTurn()
        // Запись умирает вместе с экраном: AudioRecord освобождается, буфер не держится.
        if (::voiceRecorder.isInitialized) voiceRecorder.cancel()
        // Hands-free-цикл тоже: микрофон не должен остаться открытым после закрытия экрана.
        if (::handsFreeCapture.isInitialized) handsFreeCapture.cancel()
        if (::voiceLoop.isInitialized) voiceLoop.stop()
        // Нативный движок ASR держит модель в памяти (~50 МБ RSS): освобождаем явно.
        runCatching { streamingStt?.release() }
        runCatching { asrPort?.release() }
        if (::voiceOutput.isInitialized) voiceOutput.onDestroy()
        // Потоковая озвучка владеет своим плеером и временными файлами — освобождается тоже.
        if (::streamingSpeech.isInitialized) streamingSpeech.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::voiceOutput.isInitialized) voiceOutput.onResume()
        // Возврат на передний план: хвост стрима не должен ждать trailing-окно —
        // отложенная перерисовка выполняется немедленно (renderMessages гасит job сам).
        if (streamFlushJob != null) renderMessages()
        updateMiniPlayer(MarsMusicPlayer.currentTrack, MarsMusicPlayer.isPlaying)
        // Выбор пространства мог измениться на другом экране (общее
        // хранилище mars-workspace-choice): перечитываем chip из storage.
        if (::workspaceSelection.isInitialized) {
            workspaceSelection.reloadFromStorage()
            updateWorkspaceButton()
        }
    }

    override fun onPause() {
        // Черновик текущего диалога сохраняется до потери фокуса/пересоздания экрана.
        persistComposerDraft()
        // Экран скрыт: озвучка останавливается и в фоне ответы не читаются.
        if (::voiceOutput.isInitialized) voiceOutput.onPause()
        /*
         * Потоковая озвучка тоже молчит в фоне: это речь по ходу генерации, и дочитывать
         * её из кармана — не то, чего ждёт пользователь. Ход при этом продолжается, а
         * остаток ответа беззвучен, поэтому следующая реплика снова соберётся с нуля.
         */
        cancelStreamSpeech()
        // Hands-free в фоне не слушает: микрофон без foreground-сервиса всё равно отнимут,
        // а «слушающий» в фоне цикл — это расход батареи и лишние запросы распознавания.
        if (::voiceLoop.isInitialized && voiceLoop.isActive) stopHandsFree()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Открытый диалог обязан пережить пересоздание Activity (поворот, тёмная тема,
        // смерть процесса): session_id уходит в bundle, onCreate заново откроет его.
        recreationPolicy.stateToPersist(currentSessionId)
            ?.let { outState.putString(KEY_OPEN_SESSION_ID, it) }
    }

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) {
        runOnUiThread { updateMiniPlayer(track, isPlaying) }
    }

    override fun onProgress(currentMs: Int, totalMs: Int) {}

    private fun updateMiniPlayer(track: MusicTrack?, isPlaying: Boolean) {
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
    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Calm fade+slide of the mini-player strip (alpha + translationY only, no scale).
     * Under reduced motion [MiniPlayerMotion.instant] applies the final state without
     * starting any animator.
     */
    private fun applyMiniPlayerMotion(motion: MiniPlayerMotion, appearing: Boolean) {
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickAttachment && resultCode == RESULT_OK) {
            data?.data?.let { uploadAttachment(it) }
        }
    }

    /**
     * Результат рантайм-запроса микрофона (единственное использование [recognizeVoice]).
     *
     * Разрешение выдано — запись начинается сразу: пользователь уже выразил намерение тапом,
     * второй тап ему не нужен. Отказ — понятная причина в статусе (и куда идти включать),
     * а не молчание: без этого выдача/отказ разрешения ничего не запускал и не объяснял.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != recognizeVoice) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // Разрешение просили для hands-free (долгое нажатие) — продолжаем именно его.
        if (pendingHandsFree) {
            pendingHandsFree = false
            if (granted) startHandsFree() else statusView.text = VOICE_PERMISSION_DENIED_STATUS
            return
        }
        if (granted) beginVoiceRecording() else statusView.text = VOICE_PERMISSION_DENIED_STATUS
    }

    private fun buildView(): View {
        drawer = DrawerLayout(this).apply { setBackgroundColor(MarsTheme.BG) }
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
    private fun buildSectionsDrawer(): View {
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
    private fun onSectionSelected(destination: AppDestination) {
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
     * ещё не созданный на сервере, представляется UI-ключом [NEW_CHAT_NAV_KEY]
     * (плейсхолдер не покидает UI-слой), поэтому ChatActivity никогда не
     * является корнем раздела — корень хостит ChatSessionsActivity.
     */
    private fun chatNavState(): AppNavState = AppNavPolicy.openSession(
        AppNavState.INITIAL,
        currentSessionId?.trim().orEmpty().ifEmpty { NEW_CHAT_NAV_KEY },
    )

    /**
     * чат → диалоги: корневой уровень раздела CHAT. CLEAR_TOP|SINGLE_TOP
     * переиспользует уже существующий ChatSessionsActivity из стека и
     * закрывает диалог над ним — стек остаётся двухуровневым.
     */
    private fun navigateToSessionList() {
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
    private fun buildChatContent(): View {
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
     * квалифицированы `this@ChatActivity` — иначе был бы рекурсивный вызов себя.
     */
    private val scaffoldHost = object : ChatScreenScaffold.Host {
        override fun onMenuClick() {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START)
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }

        override fun onNewChat() {
            this@ChatActivity.startNewChat()
        }

        override fun openMusic() {
            startActivity(Intent(this@ChatActivity, MusicActivity::class.java))
        }

        override fun navigateTranscriptSearch(forward: Boolean) {
            this@ChatActivity.navigateTranscriptSearch(forward)
        }

        override fun refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean) {
            this@ChatActivity.refreshTranscriptSearch(resetPosition = resetPosition, scrollToMatch = scrollToMatch)
        }

        override fun closeTranscriptSearch() {
            this@ChatActivity.closeTranscriptSearch()
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
            this@ChatActivity.quickSend(text)
        }

        override fun finishSession() {
            this@ChatActivity.finishSession()
        }

        override fun toggleTodoWidget() {
            todoWidgetExpanded = !todoWidgetExpanded
            updateTodoWidget()
        }

        override fun retryQueuedMessage(messageId: String) {
            this@ChatActivity.retryQueuedMessage(messageId)
        }

        override fun discardQueuedMessage(messageId: String) {
            this@ChatActivity.requestDiscardQueued(messageId)
        }

        override fun discardAllQueuedMessages() {
            this@ChatActivity.requestDiscardAllQueued()
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
            this@ChatActivity.sendMessage()
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
            this@ChatActivity.chooseModel()
        }

        override fun chooseReasoningEffort() {
            this@ChatActivity.chooseReasoningEffort()
        }

        override fun chooseActivityMode() {
            this@ChatActivity.chooseActivityMode()
        }

        override fun chooseWorkspace() {
            this@ChatActivity.chooseWorkspace()
        }

        override fun showContextUsageDetails() {
            this@ChatActivity.showContextUsageDetails()
        }

        override fun showSessionUsageDetails() {
            this@ChatActivity.showSessionUsageDetails()
        }

        override fun showQuotaDetails() {
            this@ChatActivity.showQuotaDetails()
        }

        override fun showToolsetsDialog() {
            this@ChatActivity.showToolsetsDialog()
        }

        override fun openTranscriptSearch() {
            this@ChatActivity.openTranscriptSearch()
        }

        override fun chooseAttachment() {
            this@ChatActivity.chooseAttachment()
        }

        override fun startVoiceInput() {
            this@ChatActivity.startVoiceInput()
        }

        override fun toggleHandsFree(): Boolean = this@ChatActivity.toggleHandsFree()

        override fun speakLastMessage() {
            this@ChatActivity.speakLastMessage()
        }

        override fun toggleAutoSpeak(): Boolean {
            voiceOutput.setAutoSpeak(!voiceOutput.autoSpeakEnabled)
            updateVolumeBtn()
            return voiceOutput.autoSpeakEnabled
        }

        override fun isTyping(): Boolean = uiState.status == ChatStatus.TYPING

        override fun cancelStream() {
            this@ChatActivity.cancelStream()
        }
    }

    private fun quickSend(text: String) {
        input.setText(text)
        sendMessage()
    }

    /** Обработчик перехода сети: баннер обновляется всегда, восстановление связи запускает флаш. */
    private fun onNetworkTransition(transition: NetworkTransition) {
        updateNetworkBanner()
        // O7: сеть — не владелец композера: текст не трогаем, но фокус, потерянный на
        // обрыве, возвращаем одноразово и только после того, как связь успокоилась.
        composerGuard.onNetworkTransition(transition)
        restoreComposerFocusIfNeeded()
        // Сеть кормится в recovery-цикл: offline → парковка (WaitForNetwork), online →
        // пробуждение и немедленный reattach. LOSING трактуется как offline (сеть сейчас умрёт).
        if (::recovery.isInitialized) recovery.onNetworkChanged(transition.current.sendAllowed)
        if (transition.restored) {
            flushOfflineQueue()
            // Список сессий на восстановлении связи обновляется через throttle: моргающая
            // сеть (серия restored-переходов) не должна устраивать шторм GET /api/sessions.
            // Стартовый снимок сети (до первичного восстановления диалога) не триггерит:
            // onCreate сам грузит список — так же, как gated автофлаш очереди выше.
            if (initialRestoreDone) requestSessionListRefresh()
        }
    }

    /**
     * O7: возврат фокуса композеру после обрыва связи. Решение — [ComposerInputGuardPolicy]:
     * во время флаппинга (серия drop/restore в коротком окне) восстановление откладывается
     * до тишины, иначе клавиатура мигает на каждом переходе. Одноразовость — на политике.
     */
    private fun restoreComposerFocusIfNeeded() {
        if (!::input.isInitialized) return
        when (composerGuard.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED)) {
            ComposerFocusDecision.RestoreWithKeyboard -> {
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            ComposerFocusDecision.DeferUntilQuiet -> {
                val wait = composerGuard.quietMillisRemaining()
                input.postDelayed(
                    { if (!isFinishing && !isDestroyed) restoreComposerFocusIfNeeded() },
                    wait,
                )
            }
            ComposerFocusDecision.Keep, ComposerFocusDecision.Suppress -> Unit
        }
    }

    /** Баннер: решение (видимость/текст/тон) — чистая NetworkBannerPolicy; хост мапит тон на MarsTheme. */
    internal fun updateNetworkBanner() {
        if (!::networkBanner.isInitialized) return
        // Карточка очереди живёт по тем же триггерам, что и баннер (сеть + размер очереди),
        // поэтому обновляется здесь же — до раннего выхода «баннер не нужен».
        updateOfflineQueuePanel()
        val pendingCount = if (::offlineQueue.isInitialized) offlineQueue.size else 0
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

    private fun updateControls() {
        val active = uiState.status == ChatStatus.TYPING
        sendButton.setImageDrawable(
            ContextCompat.getDrawable(this, if (active) R.drawable.ic_stop else R.drawable.ic_send)
        )
        sendButton.imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
        // Доступность: подпись кнопки соответствует ДЕЙСТВИЮ клика (stop/send), а не иконке.
        sendButton.contentDescription = ChatA11yPolicy.sendButtonDescription(active)
        updateContextIndicator()
        updateLiveRunPlate()
        updateQuotePlate()
        updateTodoWidget()
        renderSmartChips()
    }

    /**
     * Доступный заголовок раскрывающейся карточки (thinking/tool/todo): роль кнопки
     * (TalkBack озвучивает «Кнопка» и активирует двойным тапом), единая подпись с
     * состоянием и stateDescription. Тексты решает чистая [ChatA11yPolicy];
     * semantics клика (toggle + renderMessages) остаётся за вызывающим кодом.
     */
    private fun applyExpandableHeaderA11y(header: View, description: String, state: String) {
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
     * Делегат для секций-расширений (`Chat*Section.kt`): сам [updateControls] остаётся
     * приватным членом Activity — контракт a11y проверяет его тело в этом файле.
     */
    internal fun updateControlsFromSection() = updateControls()

    /**
     * Делегат a11y-хелпера для секций-расширений: [applyExpandableHeaderA11y] остаётся
     * приватным (контракт проверяет его объявление и делегат в этом файле).
     */
    internal fun applyExpandableHeaderA11yFromSection(header: View, description: String, state: String) =
        applyExpandableHeaderA11y(header, description, state)

    // Проверки lateinit-полей для секций-расширений: `::x.isInitialized` компилятор разрешает
    // только внутри самого класса, поэтому хост отдаёт готовые предикаты. Поведение то же:
    // false — поле ещё не инициализировано (экран не до конца поднят / контроллер не создан).
    internal fun isVoiceOutputReady(): Boolean = ::voiceOutput.isInitialized
    internal fun isStreamSpeechReady(): Boolean = ::streamingSpeech.isInitialized
    internal fun isRecoveryReady(): Boolean = ::recovery.isInitialized
    internal fun isOfflineQueueReady(): Boolean = ::offlineQueue.isInitialized
    internal fun isMessagesReady(): Boolean = ::messages.isInitialized
    internal fun isInputReady(): Boolean = ::input.isInitialized
    internal fun isDraftsReady(): Boolean = ::drafts.isInitialized
    internal fun isAuthExitCleanupReady(): Boolean = ::authExitCleanup.isInitialized
    internal fun isTodoWidgetPlateReady(): Boolean = ::todoWidgetPlate.isInitialized
    internal fun isSearchPlateReady(): Boolean = ::searchPlate.isInitialized
    internal fun isQueuePlateReady(): Boolean = ::queuePlate.isInitialized
    internal fun isCompressionControllerReady(): Boolean = ::compressionController.isInitialized

    /**
     * Smart Quick Chips ribbon (WebUI parity): one-tap conversation actions above
     * the composer. Visibility is decided by the pure [QuickChips] policy; icons are
     * monochrome vectors per the design system (no emoji).
     */
    internal fun renderSmartChips() {
        if (!::smartChipsRow.isInitialized) return
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

    private fun buildSmartChip(chip: QuickChip): Button =
        buildRibbonChip(chip.label, smartChipIcon(chip.id), chip.message)

    /** Одна кнопка ленты: монохромная иконка + подпись, без эмодзи и типографских глифов. */
    private fun buildRibbonChip(label: String, iconRes: Int, message: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(MarsTheme.TEXT)
        background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER_MUTED)
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

    private fun starterIcon(id: SmartSuggestionId): Int = when (id) {
        SmartSuggestionId.SERVER_STATUS -> R.drawable.ic_status
        SmartSuggestionId.DAY_SUMMARY -> R.drawable.ic_insights
        SmartSuggestionId.PROJECTS -> R.drawable.ic_projects
        SmartSuggestionId.MUSIC -> R.drawable.ic_music
        SmartSuggestionId.CONTINUE -> R.drawable.ic_play
        SmartSuggestionId.EXPLAIN -> R.drawable.ic_help
    }

    private fun smartChipIcon(id: QuickChipId): Int = when (id) {
        QuickChipId.OK -> R.drawable.ic_check
        QuickChipId.CONTINUE -> R.drawable.ic_play
        QuickChipId.ROLLBACK -> R.drawable.ic_undo
        QuickChipId.EXPLAIN -> R.drawable.ic_help
        QuickChipId.PLAN -> R.drawable.ic_plan
        QuickChipId.STATUS -> R.drawable.ic_status
        QuickChipId.RETRY -> R.drawable.ic_refresh
    }

    internal fun todoCallback(sessionId: String?): (ru.mars.network.model.TodoSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            if ((sessionId == null || currentSessionId == sessionId) && (snapshot.sessionId == null || currentSessionId == snapshot.sessionId)) {
                uiState.setTodoState(snapshot)
                setStatus()
                updateTodoWidget()
            }
        }
    }

    /**
     * Side-канал SSE-метаданных ([ru.mars.network.model.StreamSideEvent]) живого хода:
     * мобильный чат показывает из него только `context_status` (статус префилл-контекста);
     * остальные носители доставляются, но остаются без реакции. Кадр приходит на IO-контексте
     * потока — прыжок на UI-поток здесь. [boundSessionId] фиксирует сессию на момент старта
     * хода: кадр, опоздавший к сменившемуся экрану, не перекрасит новый диалог (тот же
     * guard, что в [todoCallback]); сверка самого кадра с активной сессией — в
     * [ContextStatusPresenter].
     */
    internal fun sideEventCallback(boundSessionId: String?): (ru.mars.network.model.StreamSideEvent) -> Unit = { event ->
        if (event is ContextStatusUpdate) {
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    onContextStatus(event)
                }
            }
        } else if (event is SubagentStatusUpdate) {
            // Доска живых статусов субагентов (волна C, фича B): кадр проходит
            // через редьюсер SubagentStatusPolicy в состоянии хода; изменилась
            // доска — внеочередная перерисовка карточки родителя (дети читаются
            // без открытия дочернего экрана).
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    if (uiState.applySubagentUpdate(event.update)) scheduleStreamRender()
                }
            }
        } else if (event is ru.mars.network.model.SessionTitleUpdate) {
            // Авто-заголовок сессии приходит посреди хода: шапка диалога и строка в
            // списке получают новый текст сразу, без перезапроса `GET /api/session`.
            // Кому именно достаётся кадр (пустой текст, чужая сессия, повтор) —
            // решает чистая [SessionTitlePolicy]; здесь только применение на UI-потоке.
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    onSessionTitle(event, boundSessionId)
                }
            }
        } else if (event is ru.mars.network.model.GoalUpdate) {
            // Карточка статуса цели (O14): состояние и текст кадра `goal` показываются
            // над композером. Кадр чужой сессии карточку не трогает.
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    goalCard = GoalLoopPolicy.card(event)
                    updateGoalCard()
                }
            }
        } else if (event is ru.mars.network.model.GoalContinuation) {
            // Кадр `goal_continue`: сервер поставил продолжение. WebUI отправляет его
            // следующим пользовательским сообщением, отдавая приоритет вводу человека;
            // здесь для этого переиспользуется офлайн-очередь (FIFO + эхо + автофлаш),
            // а не второй путь отправки.
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    goalCard = GoalLoopPolicy.continuationCard(event)
                    updateGoalCard()
                    enqueueGoalContinuation(event.continuationPrompt)
                }
            }
        }
    }

    /**
     * Применение кадра `title` к экрану: обновляет шапку и (если список диалогов уже
     * загружен) строку сессии в нём. Заголовок текущего чата не зависит от того,
     * открыт ли список, поэтому шапка обновляется всегда.
     */
    private fun onSessionTitle(update: ru.mars.network.model.SessionTitleUpdate, boundSessionId: String?) {
        val decision = SessionTitlePolicy.decide(
            update = update,
            boundSessionId = boundSessionId,
            currentSessionId = currentSessionId,
            currentTitle = uiState.sessionTitle,
        ) ?: return
        if (uiState.applySessionTitle(decision.sessionId, decision.title)) {
            applySessionTitle(decision.title)
        }
    }

    /** Единственная точка записи живого заголовка в шапку (пусто — строки нет). */
    internal fun applySessionTitle(title: String?) {
        val view = sessionTitleView ?: return
        val text = title?.trim().orEmpty()
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Строка статуса префилл-контекста: каждый кадр ПЕРЕЗАПИСЫВАЕТ предыдущую строку
     * (не накапливает), видна [ContextStatusPresenter.DISPLAY_DURATION_MS] и гаснет,
     * возвращая обычную статус-цепочку («Марс печатает…» и т.п.). Кадр, который
     * показывать нечего (`not_configured`/чужая сессия), гасит строку сразу.
     */
    private fun onContextStatus(update: ContextStatusUpdate) {
        // Turn-start model attribution (волна C, фича A): открывающий кадр несёт
        // фактическую модель хода — штамп на текущем пузыре важнее статус-строки и
        // не зависит от неё (кадр «чужой» сессии штамп не ставит). Изменилась
        // подпись — внеочередная перерисовка карточки через общий троттлинг стрима.
        if (update.sessionId == null || update.sessionId == currentSessionId) {
            if (uiState.stampTurnModel(update.model, update.modelProvider)) scheduleStreamRender()
        }
        contextStatusJob?.cancel()
        val line = ContextStatusPresenter.statusLine(update, currentSessionId)
        if (line == null) {
            contextStatusJob = null
            if (contextStatusLine != null) {
                contextStatusLine = null
                setStatus()
            }
            return
        }
        contextStatusLine = line
        setStatus()
        contextStatusJob = lifecycleScope.launch {
            delay(ContextStatusPresenter.DISPLAY_DURATION_MS)
            contextStatusLine = null
            contextStatusJob = null
            setStatus()
        }
    }

    /** Сброс строки статуса контекста при любом уходе с контекста диалога (без setStatus — вызывающий путь обновляет статус сам). */
    internal fun clearContextStatus() {
        contextStatusJob?.cancel()
        contextStatusJob = null
        contextStatusLine = null
        // Карточка цели принадлежит диалогу: при уходе с контекста она гаснет, иначе
        // статус чужой цели остался бы висеть над композером.
        if (goalCard.visible) {
            goalCard = GoalLoopPolicy.hidden()
            lastGoalContinuation = null
            updateGoalCard()
        }
    }

    // --- Карточка цели (/goal loop, O14) ---------------------------------------------------------

    /**
     * Отрисовка карточки статуса цели. Тон мапится на цвет темы: в работе — «бегущая»
     * точка, пауза — предупреждение, закрытая цель — «онлайн». Карточка ничего не
     * запрашивает у сервера: текст приходит кадрами `goal`/`goal_continue`.
     */
    private fun updateGoalCard() {
        if (!::goalPlate.isInitialized) return
        if (!goalCard.visible) {
            goalPlate.visibility = View.GONE
            return
        }
        goalPlate.visibility = View.VISIBLE
        goalTitle.text = goalCard.title
        val detail = goalCard.detail
        goalDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        goalDetail.text = detail.orEmpty()
        val tone = when (goalCard.tone) {
            GoalCardTone.ACTIVE -> MarsTheme.STATUS_RUNNING
            GoalCardTone.PAUSED -> MarsTheme.STATUS_WARNING
            GoalCardTone.DONE -> MarsTheme.STATUS_ONLINE
        }
        (goalDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(tone)
        goalPlate.contentDescription = goalCard.contentDescription
    }

    /** Последний поставленный в очередь промпт продолжения — защита от повторного кадра. */
    private var lastGoalContinuation: String? = null

    /**
     * Промпт продолжения цели уходит в обычную очередь отправки: FIFO сохраняет
     * приоритет введённого человеком текста, эхо сразу видно в транскрипте, а
     * отправкой занимается тот же автофлаш, что и у офлайн-сообщений.
     */
    private fun enqueueGoalContinuation(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || !::offlineQueue.isInitialized) return
        if (lastGoalContinuation == text) return
        lastGoalContinuation = text
        val queued = offlineQueue.enqueue(text, currentSessionId)
        uiState.beginQueuedEcho(queued.text)
        renderMessages()
        updateNetworkBanner()
        flushOfflineQueue()
    }

    /**
     * Команда `/goal` в композере (O14).
     *
     * `/goal` и `/goal status` обслуживаются локально по последнему кадру карточки.
     * Управляющие подкоманды требуют `POST /api/goal`, которого нет в device-Bearer
     * allowlist: отправлять их обычным сообщением нельзя (сервер поймёт это как
     * текст промпта и цель не изменится), поэтому отвечаем прямо и не тратим ход.
     */
    internal fun handleGoalCommand(text: String) {
        val args = GoalLoopPolicy.commandArgs(text)
        val reply = if (GoalLoopPolicy.isStatusCommand(args)) {
            GoalLoopPolicy.statusReply(goalCard)
        } else {
            GoalLoopPolicy.controlReply(args)
        }
        // Ответ идёт строкой статуса (а не пузырём транскрипта): это отклик команды,
        // а не сообщение диалога. Статус цели уже виден на карточке, строке хватает
        // короткой переформулировки и самоскрытия.
        explicitStatus = reply
        setStatus()
        goalReplyJob?.cancel()
        goalReplyJob = lifecycleScope.launch {
            delay(GOAL_REPLY_TTL_MS)
            if (explicitStatus == reply) {
                explicitStatus = null
                setStatus()
            }
        }
        input.text.clear()
        updateControls()
    }

    /** Автоскрытие ответа на `/goal`: строка статуса не должна жить до следующего хода. */
    private var goalReplyJob: Job? = null
    private val GOAL_REPLY_TTL_MS = 6_000L

    // --- Карточка неотправленных (OfflineQueuePresenter, O8) --------------------------------------

    internal fun updateQuotePlate() {
        if (!::composerQuoteView.isInitialized) return
        val plate = composerLayoutPresenter.quotePlate
        if (plate.visible) {
            composerQuoteView.visibility = View.VISIBLE
            composerQuoteTitle.text = plate.title
            composerQuoteExcerpt.text = plate.excerpt
        } else {
            composerQuoteView.visibility = View.GONE
        }
    }

    private fun updateLiveRunPlate() {
        if (!::liveRunPlate.isInitialized) return
        val runningTool = uiState.messages.lastOrNull()?.tools?.lastOrNull { it.result == null }?.name
        val lastAssistant = uiState.messages.lastOrNull { it.role == "assistant" }
        val plate = LiveRunStatusPresenter.resolve(
            status = uiState.status,
            phase = uiState.phase,
            runningToolName = runningTool,
            hasText = lastAssistant?.text?.isNotBlank() == true,
            hasThoughts = lastAssistant?.thoughts?.isNotEmpty() == true
        )
        if (plate.visible) {
            liveRunPlate.visibility = View.VISIBLE
            liveRunLabel.text = plate.label
            liveRunLabel.setTextColor(plate.labelColor)
            if (plate.detailLabel != null) {
                liveRunDetail.visibility = View.VISIBLE
                liveRunDetail.text = plate.detailLabel
                liveRunDetail.setTextColor(plate.detailColor)
            } else {
                liveRunDetail.visibility = View.GONE
            }
        } else {
            liveRunPlate.visibility = View.GONE
        }
    }

    internal fun setStatus() {
        val quiet = uiState.activityMode == ActivityDisplayMode.STREAM_QUIET
        // Пока голосовой диалог активен, строка состояния принадлежит ему: человек в режиме
        // «слушаю/думаю/говорю» смотрит именно туда, и «Марс печатает…» вместо «Слушаю…»
        // означало бы, что микрофон открыт непонятно зачем. explicitStatus (обрыв связи,
        // восстановление, readonly) остаётся сильнее — он про то, что блокирует сам диалог.
        statusView.text = explicitStatus ?: voiceStatusLabel() ?: contextStatusLine ?: when {
            uiState.todoState?.todos?.isNotEmpty() == true && uiState.status == ChatStatus.TYPING -> {
                val todos = requireNotNull(uiState.todoState).todos
                val done = todos.count { it.status == "completed" }
                val current = todos.firstOrNull { it.status == "in_progress" }
                "Задачи: $done/${todos.size}" + (current?.let { " — ${it.content}" } ?: "")
            }
            quiet && uiState.status == ChatStatus.TYPING -> "Марс работает…"
            uiState.status != ChatStatus.TYPING && uiState.attention != null ->
                attentionLabel(requireNotNull(uiState.attention))
            uiState.status != ChatStatus.TYPING && uiState.hasPendingUserMessage ->
                "Сообщение в очереди — ждём запуска хода"
            else -> uiState.phase?.let { "Марс: ${phaseLabel(it)}" } ?: when (uiState.status) {
                ChatStatus.TYPING -> "Марс печатает…"
                ChatStatus.RECEIVED -> "Ответ получен"
                ChatStatus.ERROR -> "Ошибка сети"
                else -> ""
            }
        }
        updateLiveRunPlate()
        // Кнопка микрофона живёт по тому же источнику правды, что и строка состояния:
        // один вызов setStatus() обновляет оба, рассинхрона «красная, а уже выключено» нет.
        renderVoiceButton()
    }

    /** Human banner for the session's server-side attention (WebUI `_session_attention_summary` parity). */
    private fun attentionLabel(attention: SessionAttention): String {
        val kind = when (attention.kind) {
            "approval" -> "подтверждения"
            "clarify" -> "уточнения"
            else -> attention.kind
        }
        val count = if (attention.count > 1) " x${attention.count}" else ""
        // Textual severity marker — the exclamation emoji renders font-dependent across skins.
        return if (attention.severity == "critical") {
            "Критично — ожидает $kind$count"
        } else {
            "Ожидает $kind$count"
        }
    }

    private fun phaseLabel(value: String) = when {
        value.contains("tool", true) || value.contains("command", true) -> "Выполняет команду…"
        value.contains("think", true) || value.contains("reason", true) -> "Анализирует задачу…"
        else -> value
    }


    private fun openTranscriptSearch() {
        searchPlate.visibility = View.VISIBLE
        searchInput.setText("")
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        refreshTranscriptSearch(resetPosition = true, scrollToMatch = false)
    }

    private fun closeTranscriptSearch() {
        // Снятие подсветки и сброс состояния — задача контроллера поиска.
        transcriptSearch.clear()
        searchPlate.visibility = View.GONE
        searchInput.setText("")
        searchCounter.text = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** Панель поиска открыта — Back сначала закрывает её (см. NavBackPolicy). */
    private fun isTranscriptSearchOpen(): Boolean =
        ::searchPlate.isInitialized && searchPlate.visibility == View.VISIBLE

    /**
     * Хост-контракт контроллера поиска: узкие адаптеры к транскрипту вместо передачи
     * `CardEntry` (он остаётся приватным внутренним классом Activity) и view-обвязки панели.
     * Индексы совпадают с `card.tag`: поиск адресует карточку тем же индексом, что и рендер.
     */
    private fun transcriptSearchHost() = object : TranscriptSearchHighlightController.Host {
        override val messageTexts: List<String>
            get() = uiState.messages.map { it.text }

        override val query: String
            get() = searchInput.text?.toString().orEmpty()

        override fun setCounter(label: String) {
            searchCounter.text = label
        }

        override fun cardViewAt(index: Int): View? = messages.findViewWithTag(index)

        override fun markdownHostAt(index: Int): View? = cardEntries.getOrNull(index)?.markdownHost

        override val scrollContainer: View get() = scroll

        override fun scrollTo(y: Int) {
            scroll.smoothScrollTo(0, y)
        }

        override fun dp(value: Int) = this@ChatActivity.dp(value)
    }

    /** Пересчёт совпадений и подсветка — целиком в контроллере поиска. */
    internal fun refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean) {
        transcriptSearch.refresh(resetPosition = resetPosition, scrollToMatch = scrollToMatch)
    }

    /** Кольцевой шаг «следующее/предыдущее совпадение» — целиком в контроллере поиска. */
    private fun navigateTranscriptSearch(forward: Boolean) {
        transcriptSearch.navigate(forward)
    }

    /** Markdown-секция пузыря — реализация в [ChatMessageCardRenderer.renderMarkdownBlocks]. */
    internal fun renderMarkdownBlocks(
        value: String,
        parent: LinearLayout,
        user: Boolean,
        messageIndex: Int,
        state: ChatMarkdownRenderer.MarkdownRenderState,
    ) {
        ChatMessageCardRenderer.renderMarkdownBlocks(this, value, parent, user, messageIndex, cardRendererHost, state)
    }

    private fun toolDump(tool: ToolExecution): String =
        "Параметры:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"

    /**
     * Повторный вход в уровень диалога (корень списка под стеком переиспользуется
     * флагами CLEAR_TOP|SINGLE_TOP): новый [EXTRA_OPEN_SESSION_ID] открывает
     * соответствующий чат; без extra экран остаётся как есть.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.trim().orEmpty()
        if (requested.isNotEmpty() && requested != currentSessionId) {
            loadSessionById(requested)
        }
    }

    /**
     * Все вызовы приходят в main-диспетчере (lifecycleScope) и только из живого поколения
     * цикла: смена сессии, новый ход, «стоп», logout и destroy убивают job через
     * [MidTurnRecoveryController.cancelRecovery], и поздние события туда не доходят.
     */
    private inner class RecoveryListener : MidTurnRecoveryController.Listener {
        /** Обычное событие reattach-стрима — тот же конвейер, что и у первичного collect. */
        override fun onStreamEvent(event: ChatEvent) {
            uiState.onEvent(event)
            if (event is ChatEvent.Error && AuthRejectionPolicy.isCredentialRejected(event.message)) {
                requestReauthentication()
                return
            }
            if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
            if (event is ChatEvent.ContextUsage) updateContextIndicator()
            if (event is ChatEvent.SteerLeftover) restoreSteerText(event.text)
            interactiveController.onEvent(event)
            if (::compressionController.isInitialized) compressionController.onEvent(event)
            // Тот же троттлинг кадров, что и в первичном collect: reattach-стрим не должен
            // перерисовывать транскрипт на каждый токен.
            scheduleStreamRender()
            setStatus()
        }

        override fun onAttachAttempt(attempt: Int, resumeFromEventId: String?) {
            if (attempt > 0) {
                explicitStatus = if (attempt == 1) {
                    STREAM_RECONNECT_STATUS
                } else {
                    "$STREAM_RECONNECT_STATUS (попытка $attempt)"
                }
                setStatus()
            }
        }

        /** Офлайн-парковка: ход остаётся TYPING (пузырь, «стоп», steer), цикл ждёт сеть. */
        override fun onRecoveryParkedOffline() {
            explicitStatus = STREAM_PARKED_OFFLINE_STATUS
            setStatus()
        }

        override fun onTurnCompleted() {
            finishRecoveredTurn(speakFinalAnswer = true)
        }

        /** Серверная ошибка хода уже в транскрипте (onStreamEvent → uiState.fail()). */
        override fun onTurnEndedInError() {
            finishRecoveredTurn(speakFinalAnswer = false)
        }

        /** Стрим исчез серверно (404/истёкший журнал): placeholder снимается, история перезагружается. */
        override fun onReloadSession() {
            if (uiState.reattachedTurn) uiState.cancelReattachedTurn()
            clearRecoveryStatus()
            explicitStatus = "Ход завершён — история обновлена"
            renderMessages()
            setStatus()
            updateControls()
            reloadCurrentSession()
        }

        override fun onGiveUp(reason: String, suppressedTransportError: ChatEvent.Error?) {
            if (suppressedTransportError != null &&
                AuthRejectionPolicy.isCredentialRejected(suppressedTransportError.message)
            ) {
                requestReauthentication()
                return
            }
            // Подавленная транспортная ошибка всплывает только здесь — на окончательном провале.
            if (suppressedTransportError != null) uiState.onEvent(suppressedTransportError) else uiState.fail()
            clearRecoveryStatus()
            explicitStatus = "Поток не восстановлен: $reason"
            renderMessages()
            setStatus()
            updateControls()
            updateNetworkBanner()
            flushOfflineQueue()
        }
    }

    private fun chooseAttachment() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false); addCategory(Intent.CATEGORY_OPENABLE)
        }, pickAttachment)
    }

    private fun uploadAttachment(uri: Uri) {
        val credential = store.read() ?: return
        statusView.text = "Прикрепляю файл…"
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "attachment"
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = contentResolver.openInputStream(uri)
                        ?.use { readBoundedUploadPayload(it) }
                        ?: return@runCatching UploadOutcome.Failure("Файл недоступен")
                    ChatRepository().uploadAttachment(BASE_URL, credential, name, mime, bytes, currentSessionId)
                }.getOrElse { error ->
                    if (error is UploadTooLargeException) {
                        UploadOutcome.TooLarge(error.message ?: "Файл слишком большой")
                    } else {
                        UploadOutcome.Failure(error.message ?: "Network error")
                    }
                }
            }
            when (outcome) {
                is UploadOutcome.Success -> {
                    attachments += outcome.attachment
                    renderAttachmentChips()
                    statusView.text = "Прикреплено: ${outcome.attachment.name}"
                }
                is UploadOutcome.TooLarge ->
                    statusView.text = "Файл не прикреплён: ${outcome.message}"
                is UploadOutcome.Failure ->
                    statusView.text = "Не удалось прикрепить файл: ${outcome.message}"
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Кнопка микрофона: тап — начать запись, тап во время записи — остановить, распознать и
     * отправить. Записываем сами ([VoiceRecorder], WAV 16 кГц моно) и распознаём на сервере
     * (`POST /api/transcribe`): системный `RecognizerIntent` больше не используется — он есть
     * не на каждой сборке, а качество записи им не управляется (живой вывод ломался ровно на
     * этом: «Распознавание речи недоступно» и пустой результат).
     *
     * История переходов — в [VoiceSessionController] (LISTENING → PROCESSING → IDLE).
     * Здесь только обвязка: разрешение, микрофон, статус и отправка распознанного текста.
     */
    private fun startVoiceInput() {
        // Что делает тап — решает [VoiceMicGesturePolicy]: в режиме диалога тап не открывает
        // вторую запись, а перебивает звук (или закрывает текущую реплику), иначе «тап по
        // красной кнопке» означал бы разное в зависимости от того, что успел сделать Марс.
        when (voiceGestures.onTap(
            dialogActive = ::voiceLoop.isInitialized && voiceLoop.isActive,
            speaking = isVoiceSpeakingNow(),
            manualRecording = ::voiceSession.isInitialized &&
                voiceSession.state == VoiceSessionController.State.LISTENING,
            transcribing = voiceTranscribeJob?.isActive == true,
        )) {
            // Идёт распознавание уже записанного звука — второй записи не начинаем.
            VoiceMicTapAction.IGNORE -> statusView.text = VOICE_TRANSCRIBING_STATUS
            // Перебивание: звук глохнет сразу, ход НЕ отменяется (агент продолжает работу).
            VoiceMicTapAction.STOP_SPEECH -> bargeIn()
            VoiceMicTapAction.STOP_UTTERANCE -> {
                // Реплика закрывается вручную. Движок может отдать текст синхронно (тогда ход
                // уйдёт сразу), иначе текст придёт в Final — оба пути внутри цикла.
                // Статус — из фактического состояния цикла, а не «Слушаю…» на всякий случай:
                // если реплика уже ушла в работу, строка не должна обещать микрофон.
                if (!voiceLoop.stopUtterance()) setStatus()
            }
            // Запись идёт (или уже закончилась по лимиту длительности) — тап её закрывает.
            VoiceMicTapAction.STOP_MANUAL_INPUT -> stopVoiceInputAndTranscribe()
            VoiceMicTapAction.START_MANUAL_INPUT -> startManualVoiceInput()
        }
    }

    /** Обычный голосовой ввод (режим диалога выключен): разрешение, затем запись одной реплики. */
    private fun startManualVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recognizeVoice)
            return
        }
        beginVoiceRecording()
    }

    /** Микрофон разрешён: открываем сессию записи (состояние ведёт [VoiceSessionController]). */
    private fun beginVoiceRecording() {
        if (!voiceSession.start()) {
            statusView.text = VOICE_BUSY_STATUS
            return
        }
        // Микрофон открывается — озвучка обязана замолчать: иначе TTS говорит в микрофон,
        // серверное распознавание получает собственный голос Марса, а человек не слышит себя.
        voiceOutput.stopSpeaking()
        // Микрофон не открылся (занят другим приложением, отозван на уровне системы, сломан
        // вход): сессия честно уходит в ERROR с причиной, а не молчит под «Слушаю…».
        if (!voiceRecorder.start()) voiceSession.onError(VOICE_MIC_UNAVAILABLE_STATUS)
    }

    /**
     * Останавливает запись, отдаёт WAV на распознавание и отправляет распознанный текст
     * существующим [sendMessage]. Запись не теряется молча: и пустой результат, и любой
     * отказ распознавания ([TranscribeOutcome.Failure]) показываются в статусе.
     */
    private fun stopVoiceInputAndTranscribe() {
        // Распознавание уже идёт (в том числе начатое авто-стопом): второй раз не запускаем.
        if (voiceTranscribeJob?.isActive == true) {
            statusView.text = VOICE_TRANSCRIBING_STATUS
            return
        }
        voiceBtn?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val recording = voiceRecorder.stop()
        if (recording == null) {
            // Тап пришёл в момент авто-стопа: запись уже забрал onAutoStop, и отменять сессию
            // здесь нельзя — иначе распознавание вернётся в IDLE и текст потеряется.
            if (voiceRecorder.autoStopTaken) return
            voiceSession.cancel()
            statusView.text = VOICE_EMPTY_STATUS
            return
        }
        transcribeVoiceRecording(recording)
    }

    /**
     * Запись закончилась сама по тишине ([VoiceRecorder.onAutoStop]): второй тап не нужен.
     *
     * Путь тот же, что у ручного тапа ([transcribeVoiceRecording]); отличается только вход.
     * Поздний колбэк (сессию уже закрыли тапом или отменой) игнорируется — иначе он отправил бы
     * сообщение из записи, которую человек сознательно отменил.
     */
    private fun onVoiceAutoStopped(recording: Recording) {
        if (isFinishing || isDestroyed) return
        if (voiceSession.state != VoiceSessionController.State.LISTENING) return
        if (voiceTranscribeJob?.isActive == true) return
        transcribeVoiceRecording(recording)
    }

    // --- hands-free диалог ---------------------------------------------------------------------
    //
    // Отличие от ручного пути выше: микрофон НЕ закрывается после реплики. Сегментер режет
    // непрерывный поток по тишине, каждая реплика уходит в распознавание, ответ Марса читается
    // вслух, и после него цикл снова слушает — как в WebUI (static/boot.js:1590-1672).
    // Все решения (что принять, что выбросить, когда снова слушать) — в [VoiceDialogLoop];
    // здесь только побочные эффекты Android.

    /** Состояния цикла → строка статуса. Вызывается с главного потока. */
    private val voiceLoopListener = object : VoiceDialogLoop.Listener {
        override fun onStateChanged(state: VoiceDialogLoop.State) {
            // Текст и кнопка берутся из одной политики: смена фазы цикла обновляет и статус,
            // и красную кнопку в одном кадре (раньше статус писался напрямую, а кнопка не
            // менялась вовсе — состояние «слушаю» было видно только по тексту).
            setStatus()
        }

        override fun onTranscribeRequested(requestId: Long, recording: Recording) {
            transcribeLoopSegment(requestId, recording)
        }

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            input.setText(text)
            input.setSelection(text.length)
            // Голосом спросили — голосом и ответ: озвучиваем именно этот ответ (одноразовый
            // arm, не глобальный тумблер), иначе hands-free превращается в переписку.
            voiceOutput.armSpeakNextAnswer()
            when (mode) {
                // Ход уже идёт (в UI это TYPING): текст дописывается в живой ход через
                // steerMessage. cancelStream() здесь НЕ вызывается — он убил бы ход, и ответ
                // пришлось бы ждать заново; steer по конструкции не создаёт второго сообщения.
                VoiceDialogLoop.SendMode.STEER -> steerMessage()
                VoiceDialogLoop.SendMode.SEND -> sendMessage()
            }
        }

        override fun onStopSpeaking() {
            voiceOutput.stopSpeaking()
            // Перебивание: потоковая очередь фраз тоже глохнет, иначе первое предложение
            // нового вопроса прозвучало бы поверх дочитываемого ответа.
            cancelStreamSpeech()
            // Серверный interrupt — только если голосовой канал реально открыт (шов пуст при
            // единственном chat SSE). Ход при этом не отменяется: агент продолжает работу.
            requestVoiceInterrupt()
            setStatus()
        }

        override fun onSegmentDropped() {
            // Реплика пришла, пока Марс отвечал (barge-in выключен): говорим прямо, иначе
            // человек решит, что его не услышали.
            statusView.text = VOICE_SEGMENT_DROPPED_STATUS
        }
    }

    /** Колбэки [HandsFreeCapture] приходят с потока чтения микрофона — на UI перекладываем. */
    private val handsFreeListener = object : HandsFreeCapture.Listener {
        override fun onSpeechStarted() {
            // Колбэки захвата приходят с потока чтения микрофона, а дальше цикл меняет
            // состояние и трогает TextView — только на главном потоке.
            runOnUiThread {
                if (::voiceLoop.isInitialized) voiceLoop.onSpeechStarted()
            }
        }

        override fun onSegment(recording: Recording) {
            runOnUiThread {
                if (!::voiceLoop.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
                voiceLoop.onSegment(recording)
            }
        }

        override fun onCaptureFailed() {
            runOnUiThread {
                if (!::voiceLoop.isInitialized) return@runOnUiThread
                voiceLoop.stop()
                statusView.text = VOICE_MIC_UNAVAILABLE_STATUS
            }
        }
    }

    /** Долгое нажатие на микрофон: включить/выключить hands-free (возвращает новое состояние). */
    private fun toggleHandsFree(): Boolean {
        // Удержание — единственный жест выключения режима, поэтому оно проверяется политикой
        // первой: пока диалог активен, удержание ВСЕГДА выключает его, в любой фазе (слушаю,
        // думаю, говорю). Потерять этот жест — значит оставить человека в режиме без выхода.
        when (voiceGestures.onHold(
            dialogActive = ::voiceLoop.isInitialized && voiceLoop.isActive,
            permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            manualRecording = ::voiceSession.isInitialized &&
                voiceSession.state == VoiceSessionController.State.LISTENING,
        )) {
            VoiceMicHoldAction.DISABLE_DIALOG -> {
                stopHandsFree()
                return false
            }
            // Ручная запись уже идёт: второй микрофон не открываем и текущую реплику не рвём.
            VoiceMicHoldAction.IGNORE -> {
                statusView.text = VOICE_BUSY_STATUS
                return false
            }
            // Разрешение спросит startHandsFree(): ответ придёт в onRequestPermissionsResult.
            VoiceMicHoldAction.ENABLE_DIALOG_AFTER_PERMISSION,
            VoiceMicHoldAction.ENABLE_DIALOG -> startHandsFree()
        }
        return ::voiceLoop.isInitialized && voiceLoop.isActive
    }

    private fun startHandsFree() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Разрешение спросим один раз: ответ придёт в onRequestPermissionsResult.
            pendingHandsFree = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recognizeVoice)
            return
        }
        // Ручная запись уже идёт — второй микрофон не открываем (и не рвём текущую реплику).
        if (voiceSession.state == VoiceSessionController.State.LISTENING) {
            statusView.text = VOICE_BUSY_STATUS
            return
        }
        if (!voiceLoop.start()) return
        // Озвучка прошлого ответа не должна попасть в микрофон с первого же чанка.
        voiceOutput.stopSpeaking()
        // Модель ASR (23 МиБ) качается при первом запуске: в фоне, чтобы не задерживать
        // открытие микрофона. Пока её нет, цикл уже слушает через whisper — диалог не стоит.
        asrPort?.let { port ->
            if (!port.isReady) {
                Thread({
                    val loaded = runCatching { port.load() }.getOrDefault(false)
                    // После загрузки цикл переключается на потоковый путь со следующей реплики.
                    if (loaded) runOnUiThread { voiceLoop.refreshSttMode() }
                }, "mars-asr-load").start()
            }
        }
        if (!handsFreeCapture.start(
                handsFreeListener,
                // Сырой поток идёт в потоковый ASR параллельно с сегментером: сегментер нужен
                // только для whisper-пути (деградация), движок режет реплики сам по endpoint.
                rawPcm = rawPcmSink(),
            )
        ) {
            voiceLoop.stop()
            statusView.text = VOICE_MIC_UNAVAILABLE_STATUS
            return
        }
        voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Потребитель сырого PCM для потокового ASR: обёртка над [StreamingStt.acceptPcm].
     *
     * Возвращает `null`, если движка нет — тогда [HandsFreeCapture] работает ровно как раньше
     * (только сегменты), и whisper-путь остаётся нетронутым.
     *
     * Колбэк вызывается с потока чтения микрофона: сам [StreamingStt] потокобезопасен и держит
     * свой lock, поэтому перекладывать на главный поток здесь не нужно и вредно (потеря кадров).
     */
    private fun rawPcmSink(): HandsFreeCapture.RawPcmListener? {
        val stt = streamingStt ?: return null
        return HandsFreeCapture.RawPcmListener { pcm, length -> stt.acceptPcm(pcm, length) }
    }

    /**
     * Создать порт потокового ASR (один раз за жизнь Activity).
     *
     * Модель (23 МиБ) НЕ лежит в APK: движок скачивает её при первом [AsrEnginePort.load].
     * Загрузку зовём в фоне при старте hands-free, чтобы не блокировать открытие микрофона.
     */
    private fun createAsrPort(): AsrEnginePort? {
        asrPort?.let { return it }
        val port = runCatching { StreamingAsrEnginePort.create(applicationContext) }.getOrNull()
        asrPort = port
        streamingStt = port?.let { StreamingStt(it) }
        return port
    }

    /**
     * Выключить режим. Недоговорённая реплика НЕ отправляется ([HandsFreeCapture.cancel]):
     * человек выключил микрофон — значит, слушать больше нечего.
     */
    private fun stopHandsFree() {
        if (!::voiceLoop.isInitialized) return
        voiceLoop.stop()
        handsFreeCapture.cancel()
        voiceOutput.stopSpeaking()
        // Режим выключен: кнопка возвращается в нейтральное состояние тем же кадром, а не
        // остаётся красной до следующего случайного setStatus().
        setStatus()
    }

    /**
     * Ход закончился (Done / конец стрима / recovery-финиш). Если ответ зазвучал — цикл в
     * SPEAKING и ждёт конца озвучки; если читать нечего — сразу снова слушаем.
     */
    internal fun onVoiceTurnCompleted() {
        if (!::voiceLoop.isInitialized || !voiceLoop.isActive) return
        if (voiceOutput.speaking) voiceLoop.onAnswerStarted() else voiceLoop.onAnswerFinished()
    }

    // --- вид голосового диалога (кнопка, статус, a11y) -----------------------------------------

    /** Звучит ли ответ прямо сейчас: платформенный TTS или потоковая очередь фраз. */
    internal fun isVoiceSpeakingNow(): Boolean =
        (::voiceOutput.isInitialized && voiceOutput.speaking) ||
            (::streamingSpeech.isInitialized && streamingSpeech.isSpeaking)

    /**
     * Текущее описание вида: фаза выводится из состояния цикла и факта звука.
     *
     * `null` вместо состояния цикла, когда режим выключен: `IDLE` бывает и «цикл не запущен»,
     * и «выключен пользователем» — для интерфейса это одно и то же (кнопка нейтральная).
     */
    internal fun currentVoiceUi(): VoiceDialogUi {
        val loopState = if (::voiceLoop.isInitialized && voiceLoop.isActive) voiceLoop.state else null
        val phase = voicePresentation.phaseFor(loopState, isVoiceSpeakingNow())
        return voicePresentation.uiFor(phase, voiceIcons)
    }

    /** Подпись для статусной строки; `null` — режим выключен, строкой владеет чат. */
    internal fun voiceStatusLabel(): String? {
        val ui = currentVoiceUi()
        return if (ui.active && ui.label.isNotEmpty()) ui.label else null
    }

    /**
     * Нарисовать кнопку микрофона по текущей фазе. Перерисовка только на смене фазы: во время
     * стрима setStatus() зовётся на каждый троттлированный кадр, и собирать GradientDrawable
     * каждый раз значило бы мусорить в GC на ровном месте.
     */
    internal fun renderVoiceButton() {
        val button = voiceBtn ?: return
        val ui = currentVoiceUi()
        if (ui.phase == renderedVoicePhase) return
        renderedVoicePhase = ui.phase
        VoiceMicButtonRenderer.render(this, button, ui)
    }

    /**
     * Перебивание: локальная остановка звука + сетевой interrupt, БЕЗ отмены хода.
     *
     * Порядок важен: сначала тишина (человек уже говорит), потом сеть. `cancelStream()` здесь
     * вызывать нельзя — он убил бы ход, а перебивание означает лишь «замолчи и слушай».
     */
    internal fun bargeIn() {
        if (::voiceOutput.isInitialized) voiceOutput.stopSpeaking()
        cancelStreamSpeech()
        requestVoiceInterrupt()
        setStatus()
    }

    /**
     * Серверный interrupt текущего голосового канала. Шов пуст, пока голосовой SSE-канал не
     * подключён (единственный живой канал — существующий chat SSE), поэтому по умолчанию
     * локальная остановка остаётся единственным эффектом. Сигнатура — UI-CONTRACT.md.
     */
    internal fun requestVoiceInterrupt() {
        val hook = voiceInterruptHook ?: return
        hook(currentSessionId)
    }

    /**
     * Конец озвучки хода — единственный путь «дочитать/не дочитывать финал».
     *
     * Правило: если ответ уже звучал потоково, финал не читается второй раз
     * ([VoiceTurnSpeechPolicy.onFinal] вернёт `null`), а потоковая очередь только дочитывает
     * остаток буфера. Если потоковой озвучки не было — финал звучит обычным путём.
     */
    internal fun finishVoiceTurn(speakFinal: Boolean) {
        if (isVoiceOutputReady()) {
            val finalText = if (speakFinal) voiceTurnSpeech.onFinal(latestAssistantAnswer()) else null
            if (isStreamSpeechReady()) endStreamSpeech()
            // Условие «ответ ещё не звучал» уже внутри политики (onFinal вернул null). Проверять
            // здесь streamSpeechActive нельзя: при включённой потоковой озвучке и ходе без
            // текстовых дельт (только инструменты, ответ пришёл целиком в Done) финал иначе
            // не прозвучал бы вообще.
            if (finalText != null) voiceOutput.onTurnComplete(finalText)
        }
        onVoiceTurnCompleted()
    }

    /**
     * Начался вызов инструмента: одна короткая фраза «Проверяю…» на ход, без имени инструмента
     * и без выдуманного результата. Звучит только когда ответ вообще озвучивается
     * ([streamSpeechActive]) — иначе режим «читать вслух» выключен и лезть в динамик нечем.
     */
    internal fun announceToolProgress(toolName: String) {
        if (!streamSpeechActive || !isStreamSpeechReady()) return
        val line = voiceTurnSpeech.onToolStarted(toolName) ?: return
        streamingSpeech.speakWhole(line)
    }

    /**
     * Дельта размышлений. В речь не идёт никогда: это черновик модели, а не ответ. Метод
     * существует, чтобы правило было видно в проводке, а не только в политике.
     */
    internal fun onAssistantThought(text: String) {
        voiceTurnSpeech.onDeliberation(text)
    }

    // --- потоковая озвучка ответа -------------------------------------------------------------

    /**
     * Ход надо озвучивать потоково: авторежим или одноразовое взведение голосового ввода, и
     * микрофонный цикл при этом не в диалоге (иначе озвучка накладывалась бы на запись).
     */
    internal fun streamSpeechEnabled(): Boolean =
        ::streamingSpeech.isInitialized && isVoiceOutputReady() && voiceOutput.wantsSpeech

    /**
     * Начало хода: взять решение об озвучке ОДИН раз. Дальше все дельты этого хода идут в
     * [streamingSpeech] либо не озвучиваются вовсе — переключение на середине дало бы
     * полуфразу в динамике и вторую половину в конце.
     */
    internal fun beginStreamSpeech() {
        streamSpeechActive = streamSpeechEnabled()
        if (streamSpeechActive) voiceOutput.consumeSpeakNextAnswer()
        streamingSpeech.stop()
        // Новый ход — новая квота на «Проверяю…» и сброс «ответ уже звучал»: иначе второй ход
        // молчал бы про инструменты, а финал первого глушил бы озвучку второго.
        voiceTurnSpeech.onTurnStarted()
    }

    /**
     * Дельта ответа (только текст ассистента, не размышления и не инструменты).
     *
     * Факт «ответ уже пошёл в звук» фиксируется и тогда, когда озвучка выключена: финальный
     * Done не должен читать весь ответ целиком поверх частично произнесённого.
     */
    internal fun onAssistantDelta(text: String) {
        if (text.isEmpty()) return
        val speakable = voiceTurnSpeech.onGeneratedText(text) ?: return
        if (!streamSpeechActive) return
        streamingSpeech.onDelta(speakable)
    }

    /** Ход завершён: остаток буфера тоже должен прозвучать. */
    internal fun endStreamSpeech() {
        if (!streamSpeechActive) return
        streamingSpeech.onTurnFinished()
        // Стрим закрылся, а новых фраз нет — сигнал «доиграло» может не прийти вовсе.
        if (!streamingSpeech.isSpeaking) onStreamSpeechDrained()
    }

    /** Ход отменён/прерван/экран ушёл: тишина немедленно, хвост ответа не догоняет. */
    internal fun cancelStreamSpeech() {
        streamSpeechActive = false
        if (!::streamingSpeech.isInitialized) return
        streamingSpeech.stop()
        if (::voiceOutput.isInitialized) voiceOutput.setExternalSpeaking(false)
    }

    /** Зазвучала первая фраза: цикл в SPEAKING, микрофон не принимает собственный динамик. */
    private fun onStreamSpeechStarted() {
        if (!streamSpeechActive) { streamingSpeech.stop(); return }
        if (::voiceOutput.isInitialized) voiceOutput.setExternalSpeaking(true)
        if (::voiceLoop.isInitialized && voiceLoop.isActive) voiceLoop.onAnswerStarted()
    }

    /** Очередь фраз пуста и всё доиграно: цикл снова слушает. */
    private fun onStreamSpeechDrained() {
        if (!streamSpeechActive) return
        if (::voiceOutput.isInitialized) voiceOutput.setExternalSpeaking(false)
        streamSpeechActive = false
        if (::voiceLoop.isInitialized && voiceLoop.isActive) voiceLoop.onAnswerFinished()
    }

    /** Реплика из hands-free-цикла → `POST /api/transcribe` → [VoiceDialogLoop.onTranscript]. */
    private fun transcribeLoopSegment(requestId: Long, recording: Recording) {
        if (store.read() == null) {
            statusView.text = VOICE_NO_CREDENTIAL_STATUS
            voiceLoop.onTranscriptFailed(requestId)
            return
        }
        statusView.text = VOICE_TRANSCRIBING_STATUS
        lifecycleScope.launch {
            val outcome = ChatRepository(credentialProvider = { store.read() })
                .transcribePcmWav(recording.wav)
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                is TranscribeOutcome.Success ->
                    // Ход ещё идёт (TYPING) — текст уходит steer, а не вторым сообщением.
                    voiceLoop.onTranscript(
                        requestId,
                        outcome.transcript,
                        turnActive = uiState.status == ChatStatus.TYPING,
                    )
                is TranscribeOutcome.Failure -> {
                    statusView.text = transcribeFailureStatus(outcome)
                    // Распознавание упало — цикл остаётся живым и снова слушает.
                    voiceLoop.onTranscriptFailed(requestId)
                }
            }
        }
    }

    /** WAV → `POST /api/transcribe` → композитор → [sendMessage]. Общий хвост тапа и авто-стопа. */
    private fun transcribeVoiceRecording(recording: Recording) {
        if (store.read() == null) {
            // Тот же отказ, что вернул бы репозиторий, — но без сетевого вызова и понятнее.
            voiceSession.onError(VOICE_NO_CREDENTIAL_STATUS)
            return
        }
        statusView.text = VOICE_TRANSCRIBING_STATUS
        voiceTranscribeJob = lifecycleScope.launch {
            // credentialProvider обязателен: голый ChatRepository() отдал бы
            // Failure("No device credential"), не дойдя до сети.
            val outcome = ChatRepository(credentialProvider = { store.read() })
                .transcribePcmWav(recording.wav)
            when (outcome) {
                is TranscribeOutcome.Success -> {
                    // Текст принимается только из LISTENING и попадает в композитор через
                    // listener (setText + armSpeakNextAnswer), затем сессия закрывается.
                    if (voiceSession.onResult(outcome.transcript)) {
                        sendMessage()
                        voiceSession.onProcessingComplete()
                    }
                }
                is TranscribeOutcome.Failure -> voiceSession.onError(transcribeFailureStatus(outcome))
            }
        }
    }

    /** Отказ распознавания — короткая причина для человека вместо HTTP-кода и стектрейса. */
    private fun transcribeFailureStatus(outcome: TranscribeOutcome.Failure): String =
        when (outcome.httpCode) {
            null -> VOICE_UNREACHABLE_STATUS
            400 -> VOICE_REJECTED_STATUS
            401, 403 -> VOICE_UNAUTHORIZED_STATUS
            413 -> VOICE_TOO_LONG_STATUS
            503 -> VOICE_DISABLED_STATUS
            else -> VOICE_FAILED_STATUS
        }

    /** Speak a message through [VoiceOutputController] (markdown is filtered inside). */
    private fun speakMessage(text: String) {
        // Ручная озвучка готового сообщения — тот же речевой путь, что у стрима: разметка,
        // код и таблицы фильтруются, фразы синтезируются очередью.
        if (isStreamSpeechReady()) {
            cancelStreamSpeech()
            // Ручная озвучка тоже проходит через цикл хода (SPEAKING → снова слушание),
            // поэтому помечается активной: иначе первый же звук был бы заглушён как «чужой».
            streamSpeechActive = true
            streamingSpeech.speakWhole(text)
            return
        }
        when (voiceOutput.speakNow(text)) {
            VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK ->
                statusView.text = "В сообщении нет текста для озвучивания"
            VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE ->
                statusView.text = VOICE_OUTPUT_UNAVAILABLE
            VoiceOutputController.SpeakOutcome.SPOKEN -> Unit
        }
    }

    /** Финальный текст последнего ассистент-сообщения — голос озвучивает только его. */
    internal fun latestAssistantAnswer(): String? =
        uiState.messages.lastOrNull { it.role == "assistant" }?.text

    /** Режим автоозвучивания виден на композере: акцентная тонировка кнопки, пока включён. */
    private fun updateVolumeBtn() {
        val enabled = ::voiceOutput.isInitialized && voiceOutput.autoSpeakEnabled
        volumeBtn?.imageTintList = ColorStateList.valueOf(
            if (enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED
        )
    }

    private fun speakLastMessage() {
        uiState.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.let {
            speakMessage(it.text)
        }
    }

    internal fun renderAttachmentChips() {
        attachmentChips.removeAllViews()
        attachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachments.forEachIndexed { i, a ->
            val previewable = a.isImage == true || a.mime.startsWith("image/")
            attachmentChips.addView(Button(this).apply {
                text = a.name
                textSize = 11f
                setTextColor(MarsTheme.TEXT_STRONG)
                contentDescription = if (previewable) {
                    "Убрать вложение ${a.name}, долгое нажатие — просмотр"
                } else {
                    "Убрать вложение ${a.name}"
                }
                // Vector `ic_close` as the trailing affordance — replaces the cross text glyph.
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null, null, ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_close), null
                )
                compoundDrawableTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
                compoundDrawablePadding = dp(6)
                background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.ACCENT)
                setOnClickListener { attachments.removeAt(i); renderAttachmentChips() }
                if (previewable) {
                    // Long-press previews the uploaded image in the fullscreen lightbox.
                    setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        lightbox.open(a.path)
                        true
                    }
                }
            }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) })
        }
    }

    private fun chooseModel() {
        val c = store.read()
        if (c == null) { statusView.text = "Модели недоступны: нет авторизации"; return }
        lifecycleScope.launch {
            statusView.text = "Загружаю модели…"
            val catalog = withContext(Dispatchers.IO) {
                runCatching { ModelsRepository().getModels(BASE_URL, c) }.getOrNull()
            }
            val entries = catalog?.let { modelSelection.pickerEntries(it) }.orEmpty()
            if (entries.isEmpty()) {
                statusView.text = "Каталог моделей недоступен"
                return@launch
            }
            val labels = entries.map { it.label }.toTypedArray()
            val checked = entries.indexOfFirst { it.model.id == modelSelection.displayedChoice()?.modelId }
            android.app.AlertDialog.Builder(this@ChatActivity)
                .setTitle("Модель Марса")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    val picked = modelSelection.select(entries[which].model)
                    modelSelection.setSessionModel(picked?.modelId, picked?.providerId)
                    d.dismiss()
                    if (picked != null) {
                        updateModelButton()
                        statusView.text = "Модель: ${picked.modelId}"
                        // The effort ladder is per-model: re-read it after a model change.
                        refreshReasoningStatus()
                    }
                }
                .show()
        }
    }

    private fun refreshModelCatalog() {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                runCatching { ModelsRepository().getModels(BASE_URL, c) }.getOrNull()
            } ?: return@launch
            modelSelection.resolveEffective(catalog)
            updateModelButton()
        }
    }

    private fun updateModelButton() {
        modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
    }

    /**
     * Reasoning-effort chip (WebUI composer-chip parity, static/ui.js). The ladder is
     * server-driven (`GET /api/reasoning` -> `supported_efforts` for the active model);
     * an explicit pick is persisted server-side via `POST /api/reasoning` and only then
     * recorded locally — `chat/start` itself carries NO reasoning field: the server
     * resolves the effort per turn from its own session/override/config state.
     */
    private fun chooseReasoningEffort() {
        val c = store.read()
        if (c == null) { statusView.text = "Уровень размышлений недоступен: нет авторизации"; return }
        lifecycleScope.launch {
            statusView.text = "Загружаю уровни размышлений…"
            val model = modelSelection.displayedChoice()
            val fetched = withContext(Dispatchers.IO) {
                ReasoningRepository().fetchStatus(BASE_URL, c, model?.modelId, model?.providerId, currentSessionId)
            }
            when (fetched) {
                is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
                is ReasoningStatusResult.Failed -> {
                    reasoningSelection.applyStatus(null)
                    updateReasoningButton()
                    statusView.text = "Уровень размышлений недоступен: ${fetched.message}"
                    return@launch
                }
            }
            updateReasoningButton()
            val options = reasoningSelection.options()
            if (options.isEmpty()) {
                statusView.text = "У этой модели нет уровней размышлений"
                return@launch
            }
            val labels = options.map { it.label }.toTypedArray()
            val effective = reasoningSelection.effectiveEffort()
            val checked = options.indexOfFirst { it.effort == effective }
            android.app.AlertDialog.Builder(this@ChatActivity)
                .setTitle("Уровень размышлений")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    d.dismiss()
                    applyReasoningEffort(options[which].effort)
                }
                .show()
        }
    }

    /** POST /api/reasoning; the pick is persisted locally only after the server confirmed it. */
    private fun applyReasoningEffort(effort: String?) {
        val c = store.read()
        if (c == null) { statusView.text = "Уровень размышлений недоступен: нет авторизации"; return }
        lifecycleScope.launch {
            statusView.text = "Сохраняю уровень размышлений…"
            val model = modelSelection.displayedChoice()
            val result = withContext(Dispatchers.IO) {
                ReasoningRepository().setEffort(BASE_URL, c, effort, model?.modelId, model?.providerId, currentSessionId)
            }
            when (result) {
                is ReasoningStatusResult.Status -> {
                    reasoningSelection.select(effort)
                    reasoningSelection.applyStatus(result.status)
                    updateReasoningButton()
                    statusView.text = "Уровень размышлений: ${reasoningSelection.label(result.status.reasoningEffort)}"
                }
                is ReasoningStatusResult.Failed ->
                    statusView.text = "Не удалось изменить уровень размышлений: ${result.message}"
            }
        }
    }

    /**
     * Silent status sync (startup and after model picks): on any failure the chip hides —
     * an unknown reasoning state must never show a stale ladder.
     */
    private fun refreshReasoningStatus() {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val model = modelSelection.displayedChoice()
            val fetched = withContext(Dispatchers.IO) {
                ReasoningRepository().fetchStatus(BASE_URL, c, model?.modelId, model?.providerId, currentSessionId)
            }
            when (fetched) {
                is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
                is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)
            }
            updateReasoningButton()
        }
    }

    private fun updateReasoningButton() {
        val label = reasoningSelection.buttonLabel()
        reasoningBtn?.apply {
            visibility = if (label == null) View.GONE else View.VISIBLE
            text = label
        }
    }

    /** Activity display mode picker — parity with the WebUI header switch. */
    private fun chooseActivityMode() {
        val modes = ActivityDisplayMode.values()
        val labels = modes.map { "${it.label} — ${it.description}" }.toTypedArray()
        val checked = modes.indexOf(activityModeSelection.current)
        android.app.AlertDialog.Builder(this)
            .setTitle("Режим активности")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val picked = activityModeSelection.select(modes[which])
                d.dismiss()
                uiState.setActivityMode(picked)
                updateActivityModeButton()
                // Режим активности меняет видимость мыслей/инструментов во ВСЕХ карточках:
                // эпоха хрома → FullRebuild вместо «снимки равны, ничего не делать».
                transcriptChromeEpoch++
                renderMessages()
                setStatus()
            }
            .show()
    }

    private fun updateActivityModeButton() {
        activityModeBtn?.text = activityModeSelection.buttonLabel()
    }

    /**
     * Workspace picker (WebUI parity with the composer workspace chip): the first
     * row is "Авто" (server default, no persisted pick), then the live catalog
     * rows `name — path`. The catalog comes from GET /api/workspaces.
     */
    private fun chooseWorkspace() {
        val credential = store.read()
        if (credential == null) {
            statusView.text = "Workspaces недоступны: нет авторизации"
            return
        }
        lifecycleScope.launch {
            statusView.text = "Загружаю рабочие пространства…"
            val catalog = runCatching { WorkspaceRepository().getWorkspaces(BASE_URL, credential) }.getOrNull()
            if (catalog == null || catalog.workspaces.isEmpty()) {
                statusView.text = "Каталог рабочих пространств недоступен"
                return@launch
            }
            workspaceSelection.pickerEntries(catalog)
            val items = catalog.workspaces
            val labels = (listOf("Авто — пространство сервера по умолчанию") +
                items.map { "${it.name} — ${it.path}" }).toTypedArray()
            val checkedIndex = items.indexOfFirst { it.path == workspaceSelection.current?.path } + 1
            android.app.AlertDialog.Builder(this@ChatActivity)
                .setTitle("Рабочее пространство")
                .setSingleChoiceItems(labels, checkedIndex) { d, which ->
                    workspaceSelection.select(if (which == 0) null else items[which - 1])
                    d.dismiss()
                    updateWorkspaceButton()
                    statusView.text = workspaceSelection.current?.let { "Workspace: ${it.name}" } ?: "Workspace: авто"
                }
                .show()
        }
    }

    private fun updateWorkspaceButton() {
        workspaceBtn?.text = workspaceSelection.buttonLabel() ?: "Workspace"
    }

    /**
     * Loads GET /api/workspaces once at startup: adopts the server default
     * (`last`) when nothing is persisted and refreshes the chip label.
     */
    private fun loadWorkspaces() {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            val catalog = runCatching { WorkspaceRepository().getWorkspaces(BASE_URL, credential) }.getOrNull() ?: return@launch
            workspaceSelection.resolveEffective(catalog)
            updateWorkspaceButton()
        }
    }

    /**
     * Кольцо контекста в композере (паритет с `.ctx-ring-svg` WebUI): дуга + процент,
     * цвет по порогам. Скрыто, пока замер неизвестен — нерешённое окно не превращается
     * в фейковый процент (#1436 parity). Решение (видимость, процент, порог, описание)
     * принимает [ContextBudgetPolicy] (JVM-тесты); этот слой только отдаёт его кольцу.
     */
    internal fun updateContextIndicator() {
        val view = contextRing ?: return
        view.render(ContextBudgetPolicy.pill(uiState.contextUsage, compressionRunning))
    }

    /**
     * Context pill dialog: fill details + quick manual compression (WebUI «быстрое сжатие
     * сессии» parity) with the optional focus topic, plus the persisted auto-compression switch
     * ([PrefsAutoCompressionStorage]).
     */
    private fun showContextUsageDetails() {
        if (compressionRunning) return
        val usage = uiState.contextUsage
        val config = autoCompressionStorage.load()
        val details = TextView(this).apply {
            text = ContextBudgetPolicy.detailsText(usage)
            setTextColor(MarsTheme.TEXT_STRONG)
        }
        val topicInput = EditText(this).apply {
            hint = "Фокус сжатия (необязательно)"
            setSingleLine(true)
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
        }
        val autoBox = CheckBox(this).apply {
            text = "Автосжатие при ${config.thresholdPercent}% заполнения"
            isChecked = config.enabled
            setTextColor(MarsTheme.TEXT_STRONG)
            setOnCheckedChangeListener { _, checked ->
                autoCompressionStorage.save(autoCompressionStorage.load().copy(enabled = checked))
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, dp(8))
            addView(details)
            addView(topicInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            addView(autoBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Окно контекста")
            .setView(container)
            .setPositiveButton("Сжать контекст") { _, _ ->
                compressionController.startManualCompression(topicInput.text.toString())
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Undelivered mid-turn steer (SSE `pending_steer_leftover`): the correction is returned to
     * the composer so the user can resend it as a normal message (WebUI parity); the transcript
     * trace is recorded by [ChatUiState.onEvent].
     */
    internal fun restoreSteerText(text: String) {
        if (text.isNotBlank() && input.text.isNullOrBlank()) input.setText(text)
        explicitStatus = "Направление не доставлено — текст возвращён в поле ввода."
        setStatus()
    }

    /** Host seam of [ContextCompressionController]: pill state, status line, transcript reload. */
    private inner class CompressionHostBridge : ContextCompressionHost {
        override fun setCompressionRunning(running: Boolean) {
            compressionRunning = running
            if (running) {
                explicitStatus = "Сжимаю контекст сессии…"
            } else if (explicitStatus == "Сжимаю контекст сессии…") {
                explicitStatus = null
            }
            setStatus()
            updateContextIndicator()
        }

        override fun showCompressionError(message: String) {
            explicitStatus = message
            setStatus()
        }

        override fun onCompressionCompleted(summary: CompressionSummary?) {
            explicitStatus = summary?.display?.takeIf { it.isNotBlank() }
                ?.let { "Контекст сжат — $it" }
                ?: "Контекст сессии сжат"
            setStatus()
            reloadCurrentSession()
        }
    }

    internal fun scrollToBottom() {
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
    private fun jumpToLatestFromFab() {
        autoScroll.pinToBottom()
        scrollToBottom()
    }

    /** Applies the pure [ScrollToBottomPolicy] decision to the FAB; safe before the view exists. */
    internal fun syncScrollToBottomFab() {
        scrollToBottomFab?.visibility = if (scrollToBottomPolicy.visible) View.VISIBLE else View.GONE
    }

    /**
     * Distance (px) between the bottom of the transcript content and the bottom of the visible
     * viewport; 0 while pinned at the latest message and before the views exist.
     */
    internal fun distanceFromBottomPx(): Int {
        if (!::scroll.isInitialized) return 0
        val child = scroll.getChildAt(0) ?: return 0
        return (child.bottom - (scroll.height + scroll.scrollY)).coerceAtLeast(0)
    }

    private fun startNewChat() {
        // Черновик текущего диалога сохраняется до сброса session_id.
        persistComposerDraft()
        // Pending-загрузка диалога больше не актуальна: её callback не должен переключить
        // композитор и состояние обратно на старый session_id после «нового чата».
        sessionNavigation.invalidatePending()
        // Новый чат убивает живой ход предыдущего диалога: токен send-пути, collect и recovery-цикл.
        teardownLiveTurn()
        interactiveController.dismissAll()
        compressionController.onSessionChanged()
        uiState.startNewSession()
        composerGuard.onSessionSwitch()
        applySessionTitle(uiState.sessionTitle)
        modelSelection.clearSessionModel()
        modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
        currentSessionId = null
        explicitStatus = null
        // A15/A14: у нового чата нет ни серверного черновика, ни измеренного расхода.
        serverDraftSync.onSessionChanged()
        sessionUsage = null
        updateUsageIndicator()
        restoreComposerDraft()
        // Буферизованные сообщения нового чата (sessionId == null) возвращают эхо;
        // при наличии сети они сразу уходят в отправку.
        echoPendingOfflineMessages()
        initialRestoreDone = true
        autoScroll.pinToBottom()
        renderMessages()
        setStatus()
        updateControls()
        flushOfflineQueue()
    }

    // --- Черновики несент-сообщений ------------------------------------------------------------

    /**
     * «Финиш»: graceful close of the current session — cancels the in-flight stream on the
     * server, denies every queued approval so the agent is not left parked, drops pending
     * dialogs, and returns the chat to the fresh-session state. Orchestration lives in the
     * pure [SessionFinishFlow] (unit-tested); this is only the Android wiring.
     */
    private fun finishSession() {
        val credential = store.read()
        val sid = currentSessionId
        val streamId = currentStreamId
        val wasStreaming = uiState.status == ChatStatus.TYPING
        val capturedApproval = uiState.pendingApproval
        val capturedClarify = uiState.pendingClarify
        val textAtStart = if (::input.isInitialized) input.text.toString() else ""
        // Pending-загрузка закрываемой сессии инвалидируется: её callback не должен
        // «воскресить» диалог, который сейчас завершается.
        sessionNavigation.invalidatePending()

        // Local stream teardown first so no more events land while the close is in flight.
        // Recovery-цикл и токен send-пути завершаемого хода тоже умирают: reattach после
        // finish не нужен, поздние кадры на закрывающийся экран не пускаются.
        teardownLiveTurn()

        if (credential == null || sid == null) {
            closeSessionLocally(null)
            return
        }
        explicitStatus = "Завершаю сессию…"
        setStatus()
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val chat = ChatRepository()
                val interactive = InteractiveRequestsRepository()
                val flow = SessionFinishFlow(object : SessionFinishFlow.Actions {
                    override val isStreaming: Boolean get() = wasStreaming
                    override fun cancelStream(): Boolean =
                        streamId?.let { runCatching { chat.executeControl(chat.cancelRequest(BASE_URL, it, credential.accessToken)) }.getOrDefault(false) } ?: false

                    private var firstHead = true
                    override fun nextPendingApproval(): ApprovalPayload? {
                        if (firstHead) {
                            firstHead = false
                            return capturedApproval
                        }
                        val lookup = interactive.fetchPendingApproval(BASE_URL, credential, sid)
                        return (lookup as? PendingApprovalLookup.Found)?.payload
                    }

                    override fun denyApproval(payload: ApprovalPayload) =
                        interactive.respondApproval(BASE_URL, credential, sid, payload, ApprovalChoice.DENY)

                    override fun nextPendingClarify() = capturedClarify
                        ?: (interactive.fetchPendingClarify(BASE_URL, credential, sid) as? PendingClarifyLookup.Found)?.payload
                })
                runCatching { flow.finish() }.getOrNull()
            }
            if (currentSessionId == sid) {
                // Сессия не сменилась, пока шёл финиш: старый черновик удаляется по контракту,
                // а текст, набранный ПОСЛЕ начала операции, переживает закрытие как черновик
                // нового чата (не перетирается).
                if (authExitCleanup.draftPersistenceAllowed) {
                    drafts.onSessionFinished(sid, textAtStart, input.text.toString())
                }
                // Завершённая сессия закрывается и на сервере: её буферизованные
                // сообщения уже некому отправлять.
                offlineQueue.removeMessagesFor(sid)
                closeSessionLocally(report)
                restoreComposerDraft()
                // Autonomy does not outlive the session being closed; make the pill reflect reality.
                if (report != null) uiState.setAutonomyEnabled(false)
                } else {
                // Пользователь ушёл в другой диалог во время финиша — его состояние и
                // композитор не трогаем; черновик закрытой сессии чистим в любом случае.
                drafts.onSessionRemoved(sid)
                offlineQueue.removeMessagesFor(sid)
            }
        }
    }

    private fun closeSessionLocally(report: SessionFinishFlow.Report?) {
        interactiveController.dismissAll()
        uiState.cancel()
        uiState.startNewSession()
        composerGuard.onSessionSwitch()
        applySessionTitle(uiState.sessionTitle)
        currentSessionId = null
        currentSessionReadOnly = false
        currentSessionIsSubagent = false
        modelSelection.clearSessionModel()
        explicitStatus = when {
            report == null -> "Сессия завершена"
            report.succeeded -> buildString {
                append("Сессия завершена")
                if (report.streamCancelled) append(" · поток остановлен")
                if (report.approvalsDenied > 0) append(" · отклонено подтверждений: ${report.approvalsDenied}")
            }
            else -> "Сессия завершена с ошибками: ${report.approvalFailures.first()}"
        }
        renderMessages()
        setStatus()
        updateControls()
        // Сброс транскрипта возвращает эхо буферизованных сообщений нового чата;
        // баннер отражает изменившийся размер очереди.
        echoPendingOfflineMessages()
        updateNetworkBanner()
        flushOfflineQueue()
    }

    private fun echoClarifyAnswer(answer: String) {
        uiState.messages += ChatMessage("user", answer)
        renderMessages()
    }

    internal fun leaveChat() {
        // Logout убивает живой ход локально: цикл не должен дёргать API после отзыва
        // credentials, а поздние SSE-кадры убитого хода не должны трогать экран.
        teardownLiveTurn()
        // Локальные plaintext-данные (черновики, история поиска музыки) стираются синхронно
        // ДО finish() и независимо от асинхронного best-effort revoke; pending-навигация
        // инвалидируется, а повторное сохранение черновика в onPause блокируется.
        sessionNavigation.invalidatePending()
        authExitCleanup.run()
        lifecycleScope.launch {
            // Best-effort server revoke; the local credential is cleared regardless of the
            // outcome so a 401/network failure can never lock the user in a stale auth state.
            // Wiring — единая фабрика AuthExitStores (P0), та же, что в SettingsActivity.
            AuthExitStores.logoutFlow(store, BASE_URL).logout()
            finish()
        }
    }

    /**
     * Forced re-authentication after the server rejected the stored credential (401,
     * [AuthRejectionPolicy]): drop it locally and return to the login screen with an
     * explicit notice. No remote revoke — the server just rejected this very token, so
     * calling revoke with it is pointless. Without this path a revoked/expired credential
     * loops every action into the same 401 with no way back to login.
     */
    internal fun requestReauthentication() {
        // Принудительная реавторизация убивает живой ход: attach с отозванным credential
        // бессмыслен, поздние кадры убитого хода не должны трогать экран.
        teardownLiveTurn()
        // Черновики и история поиска стираются до finish(): новая авторизация на этом
        // устройстве не должна видеть plaintext-данные предыдущей; onPause после этого
        // черновик уже не сохраняет.
        sessionNavigation.invalidatePending()
        authExitCleanup.run()
        runCatching { store.clear() }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTH_NOTICE, AuthRejectionPolicy.REAUTH_NOTICE)
        )
        finish()
    }

    internal fun dp(value: Int) = MarsTheme.dp(this, value)

    /**
     * Tinted vector chevron at the start of a toggle button — replaces the triangle arrow text
     * glyphs (font-dependent rendering across OEM skins, same anti-pattern as emojis).
     */
    internal fun Button.chevronStart(drawableRes: Int, tint: Int) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            ContextCompat.getDrawable(context, drawableRes), null, null, null
        )
        compoundDrawableTintList = ColorStateList.valueOf(tint)
        compoundDrawablePadding = dp(6)
    }

    companion object {
        const val BASE_URL = "https://marscore.ru"

        /**
         * Extra уровня диалога: id открываемого диалога (AppNavIntents.openSessionIntent
         * — переход [NavTransition.OpenSession] модели [AppNavState]).
         */
        const val EXTRA_OPEN_SESSION_ID = "extra_open_session_id"

        /**
         * UI-ключ «нового чата» в навигационной модели: диалог ещё не создан на
         * сервере, но уровень уже открыт — [AppNavState.sessionId] не должен быть
         * пустым (уровень Session), поэтому используется этот плейсхолдер.
         */
        const val NEW_CHAT_NAV_KEY = "__new_chat__"

        /** Ключ savedInstanceState: диалог, открытый в момент пересоздания экрана. */
        internal const val KEY_OPEN_SESSION_ID = "open_session_id"
        /** Privately-stored plaintext data cleared on every auth-exit path (набор — [AuthExitStores]). */
        internal const val DRAFTS_PREFS = AuthExitStores.DRAFTS_PREFS
        private const val MUSIC_HISTORY_PREFS = AuthExitStores.MUSIC_HISTORY_PREFS
        /**
         * Приватные хранилища офлайн-устойчивости: кэш сессий и очередь неотправленных.
         * internal: уровень списка диалогов ([ChatSessionsActivity]) читает тот же кэш
         * и стирает черновики/очередь удалённых диалогов (ключи не дублируются).
         */
        internal const val OFFLINE_CACHE_PREFS = AuthExitStores.OFFLINE_CACHE_PREFS
        internal const val PENDING_QUEUE_PREFS = AuthExitStores.PENDING_QUEUE_PREFS
        /** Подсказки статуса для офлайн-сценариев (копия из кэша / вложения / лимит очереди). */
        internal const val OFFLINE_SESSION_HINT = "Нет связи — показана сохранённая копия диалога"
        internal const val OFFLINE_ATTACHMENT_STATUS = "Нет связи — сообщение с вложениями будет отправлено после восстановления"
        internal const val MESSAGE_NOT_DELIVERED_STATUS = "Сообщение из очереди не доставлено — лимит попыток исчерпан"
        /** Status line while a reattach to the session's live stream is being established. */
        internal const val REATTACH_STATUS = "Подключение к активному ходу…"
        /** Recovery-цикл переподключается к доставленному ходу (по Last-Event-ID). */
        internal const val STREAM_RECONNECT_STATUS = "Связь прервана — восстанавливаю поток…"
        /** Recovery-цикл припаркован до возврата сети (офлайн-парковка). */
        internal const val STREAM_PARKED_OFFLINE_STATUS = "Нет связи — поток продолжится автоматически"
        /** Приватное хранилище режима автоозвучивания ответов (голосовой вывод). */
        private const val VOICE_OUTPUT_PREFS = "mars-voice-output"
        private const val KEY_AUTO_SPEAK = "auto_speak"
        /** TTS-движок отсутствует/сломался — статус вместо краша и ретраев. */
        private const val VOICE_OUTPUT_UNAVAILABLE = "Озвучивание недоступно на этом устройстве"
        /** Индикация голосового ввода (запись → распознавание) и понятные отказы. */
        private const val VOICE_LISTENING_STATUS = "Слушаю… (тап — остановить и отправить)"
        private const val VOICE_TRANSCRIBING_STATUS = "Распознаю…"
        private const val VOICE_BUSY_STATUS = "Голосовой ввод уже выполняется"
        private const val VOICE_EMPTY_STATUS = "Запись пустая — скажите чуть дольше"
        private const val VOICE_PERMISSION_DENIED_STATUS = "Нет доступа к микрофону — разрешите его Марсу в настройках"
        private const val VOICE_MIC_UNAVAILABLE_STATUS = "Микрофон недоступен на этом устройстве"
        private const val VOICE_NO_CREDENTIAL_STATUS = "Устройство не привязано — распознавание недоступно"
        private const val VOICE_UNREACHABLE_STATUS = "Нет связи с сервером — запись не распознана"
        private const val VOICE_REJECTED_STATUS = "Сервер не принял запись — попробуйте ещё раз"
        private const val VOICE_UNAUTHORIZED_STATUS = "Доступ устройства истёк — войдите заново"
        private const val VOICE_TOO_LONG_STATUS = "Запись слишком длинная — не удалось распознать"
        private const val VOICE_DISABLED_STATUS = "Распознавание речи выключено на сервере"
        private const val VOICE_FAILED_STATUS = "Не удалось распознать речь — попробуйте ещё раз"
        /** Hands-free диалог: состояния цикла и отказы. */
        private const val VOICE_IDLE_STATUS = "Голосовой диалог выключен"
        private const val VOICE_LISTENING_HANDS_FREE_STATUS = "Слушаю… (долгое нажатие на микрофон — выключить)"
        private const val VOICE_SPEAKING_STATUS = "Отвечаю вслух…"
        private const val VOICE_SEGMENT_DROPPED_STATUS = "Ответ ещё звучит — реплика не отправлена"
        /**
         * L3 (перебивание озвучки голосом) — ВЫКЛЮЧЕНО по умолчанию и включается только руками.
         * Без AcousticEchoCanceler и AudioSource.VOICE_COMMUNICATION микрофон слышит собственный
         * динамик, поэтому при включённом флаге цикл перебивает сам себя на первом слове ответа.
         * Флаг заведён для замеров на живом устройстве (POCO F5): менять значение в shared prefs
         * вручную, UI-переключателя нет намеренно — пока не подтверждено AEC, включать нельзя.
         */
        private const val KEY_VOICE_BARGE_IN = "barge_in"
    }
}




