package ru.mars.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionUsage
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.SessionRepository

class ChatActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    internal lateinit var store: AndroidKeyStoreCredentialStore
    internal lateinit var messages: LinearLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var input: EditText
    internal lateinit var sendButton: ImageButton
    internal lateinit var statusView: TextView
    internal val uiState = ChatUiState()

    internal val composerLayoutPresenter = ComposerLayoutPresenter()
    internal lateinit var composerQuoteView: LinearLayout
    internal lateinit var composerQuoteTitle: TextView
    internal lateinit var composerQuoteExcerpt: TextView
    internal lateinit var liveRunPlate: LinearLayout
    internal lateinit var liveRunLabel: TextView
    internal lateinit var liveRunDetail: TextView

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
    internal lateinit var goalPlate: LinearLayout
    internal lateinit var goalDot: View
    internal lateinit var goalTitle: TextView
    internal lateinit var goalDetail: TextView
    internal var goalCard: GoalCardUi = GoalLoopPolicy.hidden()

    internal lateinit var interactiveView: MarsInteractiveView
    internal lateinit var interactiveController: InteractiveRequestController

    /** Transcript auto-scroll decisions (pure policy): a reader scrolled up must survive SSE tokens. */
    internal var autoScroll = AutoScrollPolicy()

    // --- Устойчивость к обрыву связи -------------------------------------------------------------
    // Чистые политики (покрыты JVM-тестами): состояние сети, баннер, очередь неотправленных
    // сообщений; платформенная обвязка — NetworkConnectivityObserver (детектор) и
    // SharedPreferences-адаптеры хранилищ. Офлайн-кэш сессий/транскриптов — SessionOfflineCache
    // из core-network: список диалогов и последние сообщения переживают обрыв связи.
    internal val networkPolicy = NetworkStatePolicy()
    internal val bannerPolicy = NetworkBannerPolicy()
    /**
     * O7: защита композера от программных перезаписей. Решения (текст/фокус) — чистые,
     * хост только применяет их к EditText/IME. Поток ответов и сеть текста не касаются.
     */
    internal val composerGuard = ComposerInputGuardPolicy()
    internal lateinit var networkObserver: NetworkConnectivityObserver
    internal lateinit var offlineCache: SessionOfflineCache
    internal lateinit var offlineQueue: OfflineMessageQueuePolicy
    internal lateinit var networkBanner: TextView

    /**
     * Первичное восстановление открытой сессии завершено (транскрипт привязан). До него
     * автофлаш офлайн-очереди запрещён: отправка перекрасила бы чужой загружаемый диалог.
     */
    internal var initialRestoreDone = false
    /** Scroll-to-bottom FAB visibility (pure policy): hysteresis over the distance from the tail. */
    internal var scrollToBottomPolicy = ScrollToBottomPolicy()
    internal var scrollToBottomFab: ImageButton? = null
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
    /**
     * Сериализатор мятки первой сессии: первый ход и «скрепка» идут через один лок, иначе
     * два жеста в новом чате создают две сессии (R5).
     */
    internal val firstSessionGate = FirstSessionGate(firstSessionFlow)

    internal var explicitStatus: String? = null

    /**
     * Транзиентная строка статуса префилл-контекста (SSE `context_status`,
     * [ContextStatusPresenter]): видна в статус-баре ограниченное время и
     * сбрасывается/перезаписывается каждым новым кадром — не накапливается.
     */
    internal var contextStatusLine: String? = null
    internal var contextStatusJob: Job? = null
    internal lateinit var modelSelection: ModelSelection
    internal var modelBtn: Button? = null
    internal lateinit var reasoningSelection: ReasoningEffortSelection
    internal var reasoningBtn: Button? = null
    internal lateinit var activityModeSelection: ActivityModeSelection
    internal var activityModeBtn: Button? = null
    internal lateinit var workspaceSelection: WorkspaceSelection
    internal var workspaceBtn: Button? = null
    internal var contextRing: ContextRingView? = null
    /** Живой заголовок диалога в шапке (SSE-кадр `title`, авто-титлинг на ходу). */
    internal var sessionTitleView: TextView? = null
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
    internal lateinit var autoCompressionStorage: AutoCompressionStorage
    internal var compressionRunning = false
    internal lateinit var attachmentChips: LinearLayout
    internal val attachments = mutableListOf<ChatAttachment>()
    // Voice output: policy lives in VoiceOutputController (pure JVM), the platform
    // TextToSpeech is isolated behind AndroidSpeechEngine — ChatActivity only wires both.
    internal lateinit var speechEngine: AndroidSpeechEngine
    internal lateinit var voiceOutput: VoiceOutputController
    /** Серверный TTS (POST /api/tts) с фолбэком на [speechEngine] — движок контроллера. */
    internal lateinit var serverTtsEngine: ServerTtsSpeechEngine
    internal lateinit var serverTtsPlayback: AndroidServerTtsPlayback
    /**
     * Потоковая озвучка: дельты ответа → фразы → звук, не дожидаясь конца хода. Живёт
     * параллельно [voiceOutput] (он остаётся путём для готового сообщения и фолбэка), но
     * при активном стриме звучит только она — две озвучки одного текста исключены флагом
     * [streamSpeech].
     */
    internal lateinit var streamingSpeech: StreamingSpeechController
    /**
     * Жизненный цикл озвучки текущего хода: озвучивается ли ход, можно ли ещё отдавать текст в
     * синтез и пора ли циклу снова слушать. Отдельный класс, а не флаг в Activity, потому что
     * «ход озвучивается» и «аудио сейчас звучит» — разные вопросы (см. [StreamSpeechLifecycle]).
     */
    internal val streamSpeech = StreamSpeechLifecycle()
    /** TTS init callback may fire before the controller exists (sync construction failure). */
    internal var speechEngineInitResult: Boolean? = null
    internal lateinit var voiceSession: VoiceSessionController
    /** Своя запись с микрофона (WAV 16 кГц моно) — вход серверного распознавания. */
    internal lateinit var voiceRecorder: VoiceRecorder
    /**
     * Hands-free цикл (idle → listening → thinking → speaking → listening). Чистая логика в
     * [VoiceDialogLoop]: состояния, защита от дубля, очередь реплик, barge-in за флагом.
     */
    internal lateinit var voiceLoop: VoiceDialogLoop
    /** Непрерывный микрофон hands-free: сегментер режет поток на реплики (см. [HandsFreeCapture]). */
    internal lateinit var handsFreeCapture: HandsFreeCapture

    /**
     * Наблюдатель маршрута вывода (наушники/динамик/Bluetooth).
     *
     * Нужен политике перебивания: на наушниках микрофон не слышит динамик, и перебивание можно
     * принимать по одной энергии; на динамике — только по текстовому доказательству. Без него
     * [VoiceDialogLoop] считает худший случай (динамик) и не пускает перебивание вовсе.
     */
    internal var audioRouteMonitor: AndroidAudioRouteMonitor? = null

    /**
     * Жизненный цикл серверного голосового канала для перебивания (`voice_id`).
     *
     * Создаётся по фактическому API при первом перебивании в диалоге и закрывается вместе с
     * режимом. Транскрипт по-прежнему идёт ОДНИМ каналом (chat SSE) — здесь только управление.
     */
    internal lateinit var voiceInterruptChannel: VoiceInterruptChannel

    /** Показывать partial ASR в композере, не затирая набранное человеком. */
    internal val partialPreview = VoicePartialPreviewPolicy()

    /** Последнее подтверждённое перебивание — диагностика (без него решение политики не видно). */
    internal var lastBargeIn: BargeInEvent? = null


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
    internal var renderedVoicePhase: VoiceDialogPhase? = null

    /** Ресурсы иконок состояния — единственное, что политика получает от Android. */
    internal val voiceIcons = VoiceDialogIcons(
        mic = R.drawable.ic_mic,
        thinking = R.drawable.ic_voice_thinking,
        speaking = R.drawable.ic_volume,
    )

    /**
     * Потоковый ASR на устройстве (sherpa-onnx). Создаётся один раз, модель догружается в фоне
     * при первом hands-free запуске. `null` — движок недоступен, диалог идёт через whisper.
     */
    internal var streamingStt: StreamingStt? = null

    /** Порт движка: то, что видит [StreamingStt]. Отдельно — чтобы освободить модель в onDestroy. */
    internal var asrPort: AsrEnginePort? = null
    /** Разрешение запросили из долгого нажатия на микрофон: после выдачи стартует hands-free. */
    internal var pendingHandsFree = false
    /** Распознавание уже записанного звука: повторный тап не запускает второе. */
    internal var voiceTranscribeJob: Job? = null
    internal var volumeBtn: ImageButton? = null
    internal var voiceBtn: ImageButton? = null
    internal val pickAttachment = 4101
    /** requestCode рантайм-запроса RECORD_AUDIO (код системного распознавателя — тот же). */
    internal val recognizeVoice = 4102

    // Mini-player in chat
    internal lateinit var miniPlayerBox: LinearLayout
    /**
     * INTENDED visibility of the mini-player strip (not `View.visibility`, which stays
     * VISIBLE for the whole hide animation). Mirrors [miniPlayerBox]'s target state so a
     * track arriving mid-hide cancels the hide instead of being dropped. Starts false —
     * the strip is built GONE.
     */
    internal var miniPlayerVisible = false
    internal lateinit var miniPlayerTitle: TextView
    internal lateinit var miniPlayerPlayBtn: ImageButton
    internal lateinit var quickChipsScroll: HorizontalScrollView
    internal lateinit var smartChipsRow: HorizontalScrollView

    // Левый бар (DrawerLayout): ТОЛЬКО приложенческие разделы (AppSectionsBar) —
    // двухуровневый навигатор волны 2; список диалогов вынесен на отдельный
    // уровень ChatSessionsActivity (корень раздела CHAT).
    internal lateinit var drawer: DrawerLayout

    // --- Поиск по текущему диалогу (transcript search) -----------------------------------------
    // Совпадения адресуются чистой MessageSearchPolicy (якорь messageId + ordinalInMessage):
    // каждое вхождение — отдельный шаг навигации, в том числе несколько в одном сообщении.
    // Хост рисует панель, подсвечивает СПАН совпадения внутри пузыря и скроллит к нему.
    internal lateinit var searchPlate: LinearLayout
    internal lateinit var searchInput: EditText
    internal lateinit var searchCounter: TextView
    // Состояние поиска и вся визуальная подсветка живут в контроллере (создаётся в onCreate):
    // Activity оставляет у себя только view-обвязку панели и узкие адаптеры к транскрипту.
    internal lateinit var transcriptSearch: TranscriptSearchHighlightController
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
    internal val chipsGate = QuickChipsRenderGate()

    /**
     * Хост рендерера карточки: узкие адаптеры к состоянию и колбэкам Activity вместо передачи
     * её целиком ([ChatMessageCardRenderer.Host]). Один экземпляр на Activity — аллокаций на
     * кадр стрима не добавляет; методы читают актуальное состояние в момент вызова.
     */

    /** Снимки последнего завершённого рендера (вход [IncrementalTranscriptPolicy.plan]). */
    internal var renderedSnapshots: List<CardSnapshot> = emptyList()

    /**
     * Эпоха «хрома»: события, влияющие сразу на ВСЕ карточки (режим активности,
     * свёртка code-блока). Инкремент в [CardSnapshot.chromeEpoch] делает все снимки
     * «грязными» → политика выбирает FullRebuild (как и было до инкрементального рендера).
     */
    internal var transcriptChromeEpoch = 0L

    internal val streamThrottle = StreamRenderThrottle()

    /**
     * Владелец плана кадра транскрипта: FullRebuild или Patch «грязных» карточек.
     * Единственная точка, где Activity спрашивает, что перерисовывать (см. [MessageListPresenter]).
     */
    internal val messageListPresenter = MessageListPresenter()

    /**
     * Локальный буфер прерванного хода: последний видимый текст ответа переживает смерть
     * процесса/поворот. Создаётся лениво — prefs нужны только на первом кадре стрима.
     */
    internal val interruptedTurn: InterruptedTurnStore by lazy { InterruptedTurnStore(this) }

    /** Отложенный trailing-flush стрима (ровно один; отменяется любым состоявшимся рендером). */
    internal var streamFlushJob: Job? = null

    internal val frameGate = FrameVersionGate()

    /** Фоновый парсинг markdown стримящегося пузыря (ровно один в полёте). */
    internal var markdownParseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeChatStores()
        initializeVoiceStack()
        initializeScreenControllers()
        installBackNavigation()
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
        destroyChatStack()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumeChat()
    }

    override fun onPause() {
        pauseChat()
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
        handleVoicePermissionResult(requestCode, grantResults)
    }
















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
    internal fun isComposerQuoteViewReady(): Boolean = ::composerQuoteView.isInitialized
    internal fun isHandsFreeCaptureReady(): Boolean = ::handsFreeCapture.isInitialized
    internal fun isVoiceLoopReady(): Boolean = ::voiceLoop.isInitialized
    internal fun isNetworkObserverReady(): Boolean = ::networkObserver.isInitialized
    internal fun isInteractiveControllerReady(): Boolean = ::interactiveController.isInitialized
    internal fun isInteractiveViewReady(): Boolean = ::interactiveView.isInitialized
    internal fun isMiniPlayerBoxReady(): Boolean = ::miniPlayerBox.isInitialized
    internal fun isVoiceRecorderReady(): Boolean = ::voiceRecorder.isInitialized
    internal fun isVoiceInterruptChannelReady(): Boolean = ::voiceInterruptChannel.isInitialized
    internal fun isWorkspaceSelectionReady(): Boolean = ::workspaceSelection.isInitialized
    internal fun isNetworkBannerReady(): Boolean = ::networkBanner.isInitialized
    internal fun isSmartChipsRowReady(): Boolean = ::smartChipsRow.isInitialized
    internal fun isScrollReady(): Boolean = ::scroll.isInitialized
    internal fun isGoalPlateReady(): Boolean = ::goalPlate.isInitialized
    internal fun isLiveRunPlateReady(): Boolean = ::liveRunPlate.isInitialized
    internal fun isVoiceSessionReady(): Boolean = ::voiceSession.isInitialized
    /** Хост каркаса экрана: колбэки шапки/чипов/композера (объект создаётся один раз). */
    internal val scaffoldHost = createScaffoldHost()

    /** Слушатель голосового цикла диалога (создаётся один раз на экран). */
    /** Хост рендерера карточек (объект создаётся один раз). */
    internal val cardRendererHost = createCardRendererHost()

    internal val voiceLoopListener = createVoiceLoopListener()

    /** Слушатель hands-free захвата (создаётся один раз на экран). */
    internal val handsFreeListener = createHandsFreeListener()












    // --- Карточка цели (/goal loop, O14) ---------------------------------------------------------


    /** Последний поставленный в очередь промпт продолжения — защита от повторного кадра. */
    internal var lastGoalContinuation: String? = null



    /** Автоскрытие ответа на `/goal`: строка статуса не должна жить до следующего хода. */
    internal var goalReplyJob: Job? = null
    internal val GOAL_REPLY_TTL_MS = 6_000L

    // --- Карточка неотправленных (OfflineQueuePresenter, O8) --------------------------------------















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
    internal inner class RecoveryListener : MidTurnRecoveryController.Listener {
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



    // Все решения (что принять, что выбросить, когда снова слушать) — в [VoiceDialogLoop];
    // здесь только побочные эффекты Android.














    // --- вид голосового диалога (кнопка, статус, a11y) -----------------------------------------










    // --- потоковая озвучка ответа -------------------------------------------------------------
































    /** Host seam of [ContextCompressionController]: pill state, status line, transcript reload. */
    internal inner class CompressionHostBridge : ContextCompressionHost {
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






    // --- Черновики несент-сообщений ------------------------------------------------------------






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
        /** Ход был прерван (процесс убит/связь пропала) — показан локально сохранённый хвост. */
        internal const val INTERRUPTED_TURN_HINT = "Ход был прерван — показан локально сохранённый фрагмент"
        /** Recovery-цикл припаркован до возврата сети (офлайн-парковка). */
        internal const val STREAM_PARKED_OFFLINE_STATUS = "Нет связи — поток продолжится автоматически"
    }
}




