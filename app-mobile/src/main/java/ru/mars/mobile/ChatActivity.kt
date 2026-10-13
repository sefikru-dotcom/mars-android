package ru.mars.mobile

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
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
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.ApprovalPayload
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
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionYoloRepository
import ru.mars.network.repository.YoloLookup
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.network.repository.readBoundedUploadPayload
import java.net.HttpURLConnection
import java.net.URL

class ChatActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var statusView: TextView
    private val uiState = ChatUiState()

    private val composerLayoutPresenter = ComposerLayoutPresenter()
    private lateinit var composerQuoteView: LinearLayout
    private lateinit var composerQuoteTitle: TextView
    private lateinit var composerQuoteExcerpt: TextView
    private lateinit var liveRunPlate: LinearLayout
    private lateinit var liveRunLabel: TextView
    private lateinit var liveRunDetail: TextView

    private lateinit var todoWidgetPlate: LinearLayout
    private lateinit var todoWidgetTitle: TextView
    private lateinit var todoWidgetChevron: ImageView
    private lateinit var todoWidgetContainer: LinearLayout
    private var todoWidgetExpanded: Boolean = false

    private lateinit var interactiveView: MarsInteractiveView
    private lateinit var interactiveController: InteractiveRequestController

    /** Transcript auto-scroll decisions (pure policy): a reader scrolled up must survive SSE tokens. */
    private var autoScroll = AutoScrollPolicy()

    // --- Устойчивость к обрыву связи -------------------------------------------------------------
    // Чистые политики (покрыты JVM-тестами): состояние сети, баннер, очередь неотправленных
    // сообщений; платформенная обвязка — NetworkConnectivityObserver (детектор) и
    // SharedPreferences-адаптеры хранилищ. Офлайн-кэш сессий/транскриптов — SessionOfflineCache
    // из core-network: список диалогов и последние сообщения переживают обрыв связи.
    private val networkPolicy = NetworkStatePolicy()
    private val bannerPolicy = NetworkBannerPolicy()
    private lateinit var networkObserver: NetworkConnectivityObserver
    private lateinit var offlineCache: SessionOfflineCache
    private lateinit var offlineQueue: OfflineMessageQueuePolicy
    private lateinit var networkBanner: TextView

    /**
     * Первичное восстановление открытой сессии завершено (транскрипт привязан). До него
     * автофлаш офлайн-очереди запрещён: отправка перекрасила бы чужой загружаемый диалог.
     */
    private var initialRestoreDone = false
    /** Scroll-to-bottom FAB visibility (pure policy): hysteresis over the distance from the tail. */
    private var scrollToBottomPolicy = ScrollToBottomPolicy()
    private var scrollToBottomFab: ImageButton? = null
    /** Stream-recovery decisions (pure policy): reattach plan + dead-stream (404) detection. */
    private val reattachPolicy = StreamReattachPolicy()

    /**
     * Авто-recovery доставленного SSE-хода: единственный recovery-job (чистый JVM-цикл
     * [MidTurnRecoveryController] поверх core-координатора MidTurnRecoveryCoordinator),
     * классификатор транспортных обрывов (серверный tool error ≠ обрыв сети) и защита от
     * шторма обновлений списка сессий на восстановлении сети.
     */
    private val transportFailureClassifier = TransportFailureClassifier()
    private lateinit var recovery: MidTurnRecoveryController
    private val sessionRefreshThrottle = SessionListRefreshThrottle()
    private var sessionRefreshJob: Job? = null

    private var stream: Job? = null
    private var currentSessionId: String? = null
    private var currentSessionReadOnly = false
    private var currentStreamId: String? = null
    /** Черновики несент-сообщений по диалогам: чистая политика [SessionDraftController]. */
    private lateinit var drafts: SessionDraftController
    /** Guard асинхронной навигации по диалогам (pure): побеждает последняя загрузка. */
    private val sessionNavigation = SessionNavigationPolicy()
    /** Guard живого хода send-пути (pure): поздние SSE-события старого хода не попадают в новый экран. */
    private val liveTurnGuard = LiveTurnGuard()
    /** Сохранение открытого диалога при пересоздании экрана (pure policy). */
    private val recreationPolicy = SessionRecreationPolicy()
    /** Одноразовая очистка локальных plaintext-данных при выходе из авторизации. */
    private lateinit var authExitCleanup: AuthExitCleanup

    private val firstSessionFlow = FirstSessionFlow(SessionRepository(), BASE_URL)
    private var explicitStatus: String? = null

    /**
     * Транзиентная строка статуса префилл-контекста (SSE `context_status`,
     * [ContextStatusPresenter]): видна в статус-баре ограниченное время и
     * сбрасывается/перезаписывается каждым новым кадром — не накапливается.
     */
    private var contextStatusLine: String? = null
    private var contextStatusJob: Job? = null
    private lateinit var modelSelection: ModelSelection
    private var modelBtn: Button? = null
    private lateinit var reasoningSelection: ReasoningEffortSelection
    private var reasoningBtn: Button? = null
    private lateinit var activityModeSelection: ActivityModeSelection
    private var activityModeBtn: Button? = null
    private lateinit var workspaceSelection: WorkspaceSelection
    private var workspaceBtn: Button? = null
    private var contextIndicator: TextView? = null
    private lateinit var compressionController: ContextCompressionController
    private lateinit var autoCompressionStorage: AutoCompressionStorage
    private var compressionRunning = false
    private lateinit var attachmentChips: LinearLayout
    private val attachments = mutableListOf<ChatAttachment>()
    private var speech: SpeechRecognizer? = null
    // Voice output: policy lives in VoiceOutputController (pure JVM), the platform
    // TextToSpeech is isolated behind AndroidSpeechEngine — ChatActivity only wires both.
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var voiceOutput: VoiceOutputController
    /** TTS init callback may fire before the controller exists (sync construction failure). */
    private var speechEngineInitResult: Boolean? = null
    private lateinit var voiceSession: VoiceSessionController
    private var volumeBtn: ImageButton? = null
    private var voiceBtn: ImageButton? = null
    private val pickAttachment = 4101
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
    private lateinit var drawer: DrawerLayout

    // --- Поиск по текущему диалогу (transcript search) -----------------------------------------
    // Совпадения, кольцевая навигация и подпись счётчика — чистая TranscriptSearchPolicy;
    // хост лишь рисует панель, подсвечивает карточку найденного сообщения и скроллит к ней.
    private lateinit var searchPlate: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchCounter: TextView
    private var searchMatches: List<TranscriptMatch> = emptyList()
    private var searchPosition: Int = -1
    private var searchHighlightedCard: View? = null

    // --- ANR-устойчивость транскрипта (инкрементальный рендер + троттлинг стрима) ---------------
    // Чистые политики (JVM-тесты): StreamRenderThrottle (кадровый троттлинг SSE-событий),
    // CardSnapshotPolicy/IncrementalTranscriptPolicy (что перерисовывать: «грязные» карточки,
    // а не весь контейнер), FrameVersionGate (устаревший фоновый кадр не публикуется поверх
    // свежего). Хост здесь — только view-обвязка.
    /** Карточка транскрипта: wrapper («день-разделитель + пузырь») и последний ОТРИСОВАННЫЙ снимок. */
    private class CardEntry(
        val wrapper: LinearLayout,
        var snapshot: CardSnapshot,
        /** Секция markdown внутри пузыря; null, пока текст пуст (секции ещё нет). */
        var markdownHost: LinearLayout?,
    )

    /** Нарисованные карточки: children контейнера [messages] ↔ cardEntries 1:1 по индексу. */
    private val cardEntries = mutableListOf<CardEntry>()

    /** Снимки последнего завершённого рендера (вход [IncrementalTranscriptPolicy.plan]). */
    private var renderedSnapshots: List<CardSnapshot> = emptyList()

    /**
     * Эпоха «хрома»: события, влияющие сразу на ВСЕ карточки (режим активности,
     * свёртка code-блока). Инкремент в [CardSnapshot.chromeEpoch] делает все снимки
     * «грязными» → политика выбирает FullRebuild (как и было до инкрементального рендера).
     */
    private var transcriptChromeEpoch = 0L

    private val streamThrottle = StreamRenderThrottle()

    /** Отложенный trailing-flush стрима (ровно один; отменяется любым состоявшимся рендером). */
    private var streamFlushJob: Job? = null

    private val frameGate = FrameVersionGate()

    /** Фоновый парсинг markdown стримящегося пузыря (ровно один в полёте). */
    private var markdownParseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        interactiveView = MarsInteractiveView(this)
        interactiveController = InteractiveRequestController(
            view = interactiveView,
            gateway = RepositoryInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            state = uiState,
            uiDispatcher = MainThreadDispatcher(),
        ).also { interactiveView.listener = it }

        interactiveController.onClarifyAnswered = { answer -> echoClarifyAnswer(answer) }
        interactiveView.yoloEnabledProvider = { uiState.yoloEnabled }
        interactiveController.onYoloChanged = { enabled ->
            Toast.makeText(this, if (enabled) "Автономный режим включён" else "Автономный режим выключен", Toast.LENGTH_SHORT).show()
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
        authExitCleanup = AuthExitCleanup(
            listOf(
                PrefsClearableStore(getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE)),
                PrefsClearableStore(getSharedPreferences(MUSIC_HISTORY_PREFS, MODE_PRIVATE)),
                PrefsClearableStore(offlineCachePrefs),
                // Офлайн-кэши задач и метрик проектов — тоже приватные plaintext-данные.
                PrefsClearableStore(getSharedPreferences("mars_todos_offline", MODE_PRIVATE)),
                PrefsClearableStore(getSharedPreferences("mars_projects_offline", MODE_PRIVATE)),
                pendingMessageStore,
            )
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
        speechEngine = AndroidSpeechEngine(this) { available ->
            if (::voiceOutput.isInitialized) voiceOutput.onEngineReady(available)
            else speechEngineInitResult = available
        }
        voiceOutput = VoiceOutputController(
            engine = speechEngine,
            autoSpeakInitially = voiceOutputPrefs.getBoolean(KEY_AUTO_SPEAK, false),
            onAutoSpeakChanged = { enabled -> voiceOutputPrefs.edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply() },
        )
        speechEngineInitResult?.let { available ->
            speechEngineInitResult = null
            voiceOutput.onEngineReady(available)
        }
        // Voice-input state machine: guards the recognizer session, delivers haptic feedback on
        // start/finish and routes results into the composer. Pure logic lives in the controller.
        voiceSession = VoiceSessionController(object : VoiceSessionController.Listener {
            override fun onListeningStarted() {
                voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                statusView.text = "Слушаю…"
            }

            override fun onResult(text: String) {
                input.setText(text)
                input.setSelection(text.length)
                // The user explicitly spoke — arm reading of this turn's completed answer.
                voiceOutput.armSpeakNextAnswer()
            }

            override fun onError(message: String) {
                statusView.text = message
            }
        })
        setContentView(buildView())
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
        speech?.destroy()
        if (::voiceOutput.isInitialized) voiceOutput.onDestroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::voiceOutput.isInitialized) voiceOutput.onResume()
        // Возврат на передний план: хвост стрима не должен ждать trailing-окно —
        // отложенная перерисовка выполняется немедленно (renderMessages гасит job сам).
        if (streamFlushJob != null) renderMessages()
        updateMiniPlayer(MarsMusicPlayer.currentTrack, MarsMusicPlayer.isPlaying)
        // Выбор пространства мог измениться на экране SpacesActivity (общее
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
        if (requestCode == recognizeVoice) {
            // Haptic feedback on voice-input finish (success or dismissal).
            voiceBtn?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (resultCode == RESULT_OK) {
                val recognized = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (voiceSession.onResult(recognized.orEmpty())) voiceSession.onProcessingComplete()
            } else {
                voiceSession.cancel()
                statusView.text = ""
            }
        }
        if (requestCode == pickAttachment && resultCode == RESULT_OK) {
            data?.data?.let { uploadAttachment(it) }
        }
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

    private fun buildChatContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), systemBars.top + dp(6), dp(12), systemBars.bottom + dp(6))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        val menuBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_menu,
            "Разделы",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    drawer.openDrawer(GravityCompat.START)
                }
            }
        }
        header.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val logoDot = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_mars))
        }
        titleBox.addView(logoDot, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        titleBox.addView(TextView(this).apply {
            text = "МАРС"
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        })
        val headerStatusDot = MarsTheme.glowingStatusDot(this, MarsTheme.STATUS_ONLINE)
        titleBox.addView(headerStatusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8) })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val newChatBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_edit,
            "Новый диалог",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { startNewChat() }
        }
        header.addView(newChatBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })

        root.addView(header)

        // Баннер сетевого статуса: видимость/текст/тон решает чистая NetworkBannerPolicy,
        // хост лишь мапит тон на токены MarsTheme (полоса под хедером, над мини-плеером).
        networkBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
        }
        root.addView(networkBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Mini-player strip
        miniPlayerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        }
        val miniIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_music))
            imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        }
        miniPlayerBox.addView(miniIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })

        miniPlayerTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniPlayerBox.addView(miniPlayerTitle, LinearLayout.LayoutParams(0, -2, 1f))

        val miniPrevBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_prev,
            "Предыдущий",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.previous() }
        }
        miniPlayerBox.addView(miniPrevBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        miniPlayerPlayBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            "Воспроизведение",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = Color.WHITE,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.togglePlay() }
        }
        miniPlayerBox.addView(miniPlayerPlayBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        val miniNextBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_next,
            "Следующий",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.next() }
        }
        miniPlayerBox.addView(miniNextBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        root.addView(miniPlayerBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Status bar
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(4), dp(4), 0, dp(4))
        }
        root.addView(statusView)

        // Панель поиска по диалогу: поле, счётчик «N из M», кольцевая навигация и закрытие.
        // Решение о совпадениях/подписи — чистая TranscriptSearchPolicy (JVM-тесты).
        searchPlate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
        }
        val searchPlateIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_search))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }
        searchPlate.addView(searchPlateIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        searchInput = EditText(this).apply {
            hint = "Искать в диалоге…"
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 14f
            isSingleLine = true
            background = null
            contentDescription = "Поиск по диалогу"
            imeOptions = 3 // IME_ACTION_SEARCH: кнопка «Найти» листает совпадения вперёд.
            setOnEditorActionListener { _, _, _ -> navigateTranscriptSearch(forward = true); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshTranscriptSearch(resetPosition = true, scrollToMatch = true)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchPlate.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchCounter = TextView(this).apply {
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            setPadding(dp(6), 0, dp(6), 0)
        }
        searchPlate.addView(searchCounter, LinearLayout.LayoutParams(-2, -2))
        val searchPrevBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_chevron_up,
            "Предыдущее совпадение",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { navigateTranscriptSearch(forward = false) } }
        searchPlate.addView(searchPrevBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val searchNextBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_chevron_down,
            "Следующее совпадение",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { navigateTranscriptSearch(forward = true) } }
        searchPlate.addView(searchNextBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val searchCloseBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_close,
            "Закрыть поиск",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { closeTranscriptSearch() } }
        searchPlate.addView(searchCloseBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(searchPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Attachment chips
        attachmentChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(attachmentChips)

        // Chat scroll view
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
        }
        // Feed live scroll positions into the policies so scrolling back down re-pins the
        // viewport and hides the scroll-to-bottom FAB.
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val distance = distanceFromBottomPx()
            autoScroll.onViewportDistanceFromBottom(distance)
            scrollToBottomPolicy.onViewportDistanceFromBottom(distance)
            syncScrollToBottomFab()
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        // Transcript frame: overlays the scroll-to-bottom FAB on the transcript's bottom-right,
        // right above the chips rows and the composer, so it never covers the input bar.
        val transcriptFrame = FrameLayout(this)
        transcriptFrame.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        scrollToBottomFab = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_chevron_down,
            "К последнему сообщению",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40,
            strokeColor = MarsTheme.BORDER
        ).apply {
            visibility = View.GONE
            setOnClickListener { jumpToLatestFromFab() }
        }
        transcriptFrame.addView(
            scrollToBottomFab,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(12)
                bottomMargin = dp(10)
            }
        )
        root.addView(transcriptFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // Quick suggestions / chips
        quickChipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, dp(6))
        }
        val quickBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quickPrompts = listOf(
            "Проекты" to { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) },
            "Музыка" to { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) },
            "Погода в Ставрополе" to { quickSend("Какая сейчас погода и прогноз на день в Ставрополе?") },
            "Статус хоста" to { quickSend("Марс, проверь аптайм, диск и состояние сервисов") },
            "Новый чат" to { startNewChat() },
            "Финиш" to { finishSession() }
        )
        quickPrompts.forEach { (title, action) ->
            quickBox.addView(Button(this).apply {
                text = title
                textSize = 12f
                setTextColor(MarsTheme.TEXT)
                background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) })
        }
        quickChipsScroll.addView(quickBox)
        root.addView(quickChipsScroll)

        // Smart quick chips: context-aware one-tap replies right above the composer.
        smartChipsRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, 0, 0, dp(2))
        }
        root.addView(smartChipsRow)

        // Interactive Session Todos Widget (Accordion)
        todoWidgetPlate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_CARD, radiusDp = 10, strokeColor = MarsTheme.ACCENT_BORDER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val todoHeaderRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                todoWidgetExpanded = !todoWidgetExpanded
                updateTodoWidget()
            }
        }
        val todoIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_plan))
            imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        }
        todoHeaderRow.addView(todoIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })

        todoWidgetTitle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
        }
        todoHeaderRow.addView(todoWidgetTitle, LinearLayout.LayoutParams(0, -2, 1f))

        todoWidgetChevron = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_chevron_down))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }
        todoHeaderRow.addView(todoWidgetChevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        todoWidgetPlate.addView(todoHeaderRow)

        todoWidgetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        todoWidgetPlate.addView(todoWidgetContainer)
        root.addView(todoWidgetPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Live Run Status Plate: active runner pulse right above the composer
        liveRunPlate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_0, radiusDp = 10, strokeColor = MarsTheme.ACCENT_BORDER)
        }
        val liveRunDot = MarsTheme.glowingStatusDot(this, MarsTheme.STATUS_RUNNING)
        liveRunPlate.addView(liveRunDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        liveRunLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.ACCENT_TEXT)
            setTypeface(null, Typeface.BOLD)
        }
        liveRunPlate.addView(liveRunLabel)
        liveRunDetail = TextView(this).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(8), 0, 0, 0)
        }
        liveRunPlate.addView(liveRunDetail)
        root.addView(liveRunPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Two-Level Floating Glass Composer Capsule (WebUI Parity)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = MarsTheme.composerCapsule(this@ChatActivity)
        }

        // Top level: Quote/Reply Plate (if active)
        composerQuoteView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_1, radiusDp = 8, strokeColor = MarsTheme.ACCENT_BORDER)
        }
        val quoteAccentBar = View(this).apply {
            setBackgroundColor(MarsTheme.ACCENT)
        }
        composerQuoteView.addView(quoteAccentBar, LinearLayout.LayoutParams(dp(3), dp(24)).apply { marginEnd = dp(6) })
        val quoteTextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        composerQuoteTitle = TextView(this).apply {
            textSize = 11f
            setTextColor(MarsTheme.ACCENT_TEXT)
            setTypeface(null, Typeface.BOLD)
        }
        quoteTextBox.addView(composerQuoteTitle)
        composerQuoteExcerpt = TextView(this).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        quoteTextBox.addView(composerQuoteExcerpt)
        composerQuoteView.addView(quoteTextBox, LinearLayout.LayoutParams(0, -2, 1f))

        val quoteCancelBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_close,
            "Отменить цитату",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 24,
            strokeColor = 0
        ).apply {
            setOnClickListener {
                composerLayoutPresenter.clearQuote()
                updateQuotePlate()
            }
        }
        composerQuoteView.addView(quoteCancelBtn, LinearLayout.LayoutParams(dp(24), dp(24)))
        composer.addView(composerQuoteView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Input field: multiline, clean, responsive
        input = EditText(this).apply {
            hint = ComposerLayoutPresenter.HINT
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 15f
            maxLines = ComposerLayoutPresenter.MAX_INPUT_LINES
            isSingleLine = false
            background = null
            setPadding(dp(6), dp(4), dp(6), dp(4))
            imeOptions = 4
            setOnEditorActionListener { _, action, _ ->
                if (action == 4) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))

        // Bottom level: Session Chips on the left + Action Buttons on the right
        val composerBottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        // Left: compact controls for model, reasoning level, and always-visible context.
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        modelBtn = Button(this).apply {
            text = modelSelection.buttonLabel() ?: "Модель"
            textSize = 10.5f
            contentDescription = "Модель"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { chooseModel() }
        }
        chipsRow.addView(modelBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        reasoningBtn = Button(this).apply {
            text = "· Авто"
            textSize = 10.5f
            contentDescription = "Уровень размышлений"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { chooseReasoningEffort() }
        }
        chipsRow.addView(reasoningBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        activityModeBtn = Button(this).apply {
            text = "· ${activityModeSelection.buttonLabel()}"
            textSize = 10.5f
            contentDescription = "Режим активности"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { chooseActivityMode() }
        }
        chipsRow.addView(activityModeBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        workspaceBtn = Button(this).apply {
            text = workspaceSelection.buttonLabel()?.let { "· $it" } ?: "· Workspace"
            textSize = 10.5f
            contentDescription = "Рабочее пространство"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            isSingleLine = true
            maxEms = 11
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setOnClickListener { chooseWorkspace() }
        }
        chipsRow.addView(workspaceBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        contextIndicator = TextView(this).apply {
            textSize = 9.5f
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            visibility = View.GONE
            setOnClickListener { showContextUsageDetails() }
        }
        chipsRow.addView(contextIndicator, LinearLayout.LayoutParams(-2, -2))

        chipsScroll.addView(chipsRow)
        composerBottomBar.addView(chipsScroll, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })

        // Right: Action buttons (Search, Attach, Voice, Volume, Send/Stop)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Вход в поиск по диалогу: панель раскрывается над транскриптом (под статус-строкой).
        val searchEntryBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_search,
            "Поиск по диалогу",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { openTranscriptSearch() }
        }
        actionsRow.addView(searchEntryBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val attachBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_attach,
            "Прикрепить файл",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { chooseAttachment() }
        }
        actionsRow.addView(attachBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val voiceButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_mic,
            "Голосовой ввод",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { startVoiceInput() }
        }
        voiceBtn = voiceButton
        actionsRow.addView(voiceButton, LinearLayout.LayoutParams(dp(34), dp(34)))

        val volumeButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_volume,
            "Озвучить ответ, удержание — автоозвучивание",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { speakLastMessage() }
            // Явное действие пользователя: долгое нажатие переключает режим «читать ответы
            // вслух» (persisted). Автоматически режим никогда не включается.
            setOnLongClickListener {
                voiceOutput.setAutoSpeak(!voiceOutput.autoSpeakEnabled)
                updateVolumeBtn()
                Toast.makeText(
                    this@ChatActivity,
                    if (voiceOutput.autoSpeakEnabled) "Озвучивание ответов включено"
                    else "Озвучивание ответов выключено",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
        volumeBtn = volumeButton
        actionsRow.addView(volumeButton, LinearLayout.LayoutParams(dp(34), dp(34)))
        updateVolumeBtn()

        sendButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_send,
            "Отправить",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 36,
            strokeColor = 0
        ).apply {
            setOnClickListener {
                if (uiState.status == ChatStatus.TYPING) {
                    cancelStream()
                } else {
                    sendMessage()
                }
            }
        }
        actionsRow.addView(sendButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) })

        composerBottomBar.addView(actionsRow, LinearLayout.LayoutParams(-2, -2))
        composer.addView(composerBottomBar, LinearLayout.LayoutParams(-1, -2))

        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return root
    }

    private fun quickSend(text: String) {
        input.setText(text)
        sendMessage()
    }

    private fun sendMessage() {
        if (currentSessionReadOnly) {
            statusView.text = "Сессия доступна только для чтения"
            return
        }
        if (uiState.status == ChatStatus.TYPING) { steerMessage(); return }
        val rawInput = input.text.toString().trim()
        val text = composerLayoutPresenter.outgoingText(rawInput)
        val outgoingAttachments = attachments.toList()
        // Офлайн-буферизация (OfflineMessageQueuePolicy): без сети обычный текст уходит в
        // персистентную очередь с локальным эхом и будет отправлен автоматически после
        // восстановления связи. Вложения не буферизуются — multipart-загрузку офлайн не
        // повторить, чипы остаются в композиторе до восстановления.
        if (!networkPolicy.state.sendAllowed) {
            if (outgoingAttachments.isNotEmpty()) {
                explicitStatus = OFFLINE_ATTACHMENT_STATUS
                setStatus()
                return
            }
            when (val decision = offlineQueue.onSendRequested(canSend = false, text = text, sessionId = currentSessionId)) {
                is SendDecision.Buffer -> { onMessageBuffered(decision.message); return }
                else -> return // Пустой текст — отправлять/буферизовать нечего
            }
        }
        if (!uiState.begin(text, outgoingAttachments.map { it.name })) return
        attachments.clear()
        renderAttachmentChips()
        composerLayoutPresenter.clearQuote()
        updateQuotePlate()
        val credential = store.read() ?: run { uiState.cancel(); leaveChat(); return }
        hideKeyboard()
        input.text.clear()
        // Отправка принята локально (текст ушёл в поток) — черновик диалога очищается.
        drafts.onSendAccepted(currentSessionId)
        explicitStatus = null
        // Own outgoing message always takes the reader to the latest.
        autoScroll.pinToBottom()
        renderMessages()
        updateControls()
        stream = startSendStream(text, outgoingAttachments, currentSessionId, credential)
    }

    /**
     * Общая корутина отправки для прямого send и флаша офлайн-очереди (раньше — inline
     * в [sendMessage]). [onTurnFailed] вызывается, только если сообщение НЕ дошло до
     * сервера (сессия не создана / стрим не стартовал / ошибка до первого события):
     * очередь возвращает такое сообщение на повтор, а доставленное не дублирует.
     */
    private fun startSendStream(
        text: String,
        outgoingAttachments: List<ChatAttachment>,
        targetSessionId: String?,
        credential: ConsumedDeviceCredential,
        onTurnFailed: (() -> Unit)? = null,
    ): Job {
        // Новый пользовательский ход: предыдущий recovery-job прерывается (generation
        // guard), курсор Last-Event-ID и серии отказов координатора сбрасываются.
        if (::recovery.isInitialized) recovery.onNewTurn()
        // Новый ход: озвучка предыдущего финального ответа обрывается, не перетекая в новый.
        if (::voiceOutput.isInitialized) voiceOutput.onNewTurn()
        // Токен выдаётся синхронно с регистрацией хода: любой последующий уход с контекста
        // (teardownLiveTurn) гасит его — поздние SSE-кадры этого collect не перекрасят новый экран.
        val turnToken = liveTurnGuard.beginTurn()
        return lifecycleScope.launch {
        var credentialRejected = false
        var turnFailed = false
        var delivered = false
        // Итоги первичного collect для передачи хода в recovery-цикл: терминальный Done
        // (никогда не ретраится), серверная app/tool-ошибка (терминальна для хода) и
        // подавленная ТРАНСПОРТНАЯ ошибка обрыва (recoverable — в транскрипт не пишется).
        var sawDone = false
        var sawAppError = false
        var suppressedTransportError: ChatEvent.Error? = null
        try {
            if (targetSessionId == null) {
                explicitStatus = "Создаю сессию…"
                setStatus()
            }
            when (val ensured = firstSessionFlow.ensureSession(targetSessionId, credential)) {
                is EnsureSessionResult.Ready -> {
                    currentSessionId = ensured.sessionId
                    if (targetSessionId == null) {
                        modelSelection.setSessionModel(ensured.model, ensured.modelProvider)
                        modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
                    }
                    explicitStatus = null
                    // Первая отправка нового чата создала сессию: остальные сообщения
                    // офлайн-всплеска (ключ null) перепривязываются к ней — продолжают
                    // тот же диалог вместо плодения отдельных сессий.
                    if (targetSessionId == null && ::offlineQueue.isInitialized) {
                        offlineQueue.adoptSession(null, ensured.sessionId)
                    }
                }
                is EnsureSessionResult.Failed -> {
                    uiState.cancel()
                    turnFailed = true
                    // A 401 here means the stored credential is dead server-side: route
                    // to login instead of leaving the user retrying the same failure.
                    if (AuthRejectionPolicy.isCredentialRejected(ensured.message)) {
                        credentialRejected = true
                        requestReauthentication()
                    } else {
                        explicitStatus = "Сессия не создана: ${ensured.message}"
                    }
                    return@launch
                }
            }
            val choice = modelSelection.displayedChoice()
            // WebUI parity: the wire message carries the `[Workspace::v1: <path>]`
            // sentinel; the transcript above keeps showing the raw text.
            val wireText = workspaceSelection.outgoingMessage(text)
            ChatRepository().sendMessage(
                BASE_URL, credential, wireText, currentSessionId, outgoingAttachments,
                model = choice?.modelId,
                modelProvider = choice?.providerId,
                explicitModelPick = choice?.explicitPick == true,
                onTodoState = todoCallback(currentSessionId),
                onSideEvent = sideEventCallback(currentSessionId),
                // Курсор Last-Event-ID продвигается на КАЖДОМ id-кадре (до маппинга),
                // чтобы оборвавшийся доставленный ход восстанавливался без потерь.
                onEventId = { id -> if (::recovery.isInitialized) recovery.resumeTracker.onFrameId(id) },
            ).collect { event ->
                // Смена контекста убила ход (переключение диалога/«новый чат»/«стоп»/logout):
                // поздние кадры структурно не допускаются в новый экран — collect сразу
                // завершается, а finally видит отменённый job и не запускает автофлаш очереди.
                if (!liveTurnGuard.isCurrent(turnToken)) throw CancellationException("live turn superseded")
                // Любое не-ошибочное событие доказывает: сервер принял запрос, сообщение
                // доставлено. Ошибка ДО доставки (HTTP-отказ вместо стрима) — turnFailed.
                if (event !is ChatEvent.Error) delivered = true
                if (event is ChatEvent.Done) sawDone = true
                // Транспортный обрыв ПОСЛЕ доставки: не пишем «Ошибка: …» в пузырь и не
                // роняем статус в ERROR — ход жив на сервере, его подхватит recovery-цикл
                // через attachToStream. Ошибка всплывёт, только если восстановление
                // окончательно провалится (Listener.onGiveUp). 401 не подавляется: это
                // существующий путь принудительной реавторизации. Серверные tool/app-ошибки
                // (журнал хода) классификатором НЕ подавляются и проходят в транскрипт.
                val suppressAsTransportDrop = event is ChatEvent.Error && delivered &&
                    currentStreamId != null &&
                    transportFailureClassifier.isTransportShaped(event.message) &&
                    !AuthRejectionPolicy.isCredentialRejected(event.message)
                if (suppressAsTransportDrop) {
                    suppressedTransportError = event as ChatEvent.Error
                    explicitStatus = STREAM_RECONNECT_STATUS
                } else {
                    uiState.onEvent(event)
                    // Терминальный Done: ход завершён — голосовой режим озвучивает ФИНАЛЬНЫЙ
                    // ответ (промежуточные Chunk/Thought/ToolCall сюда не попадают никогда).
                    if (event is ChatEvent.Done && ::voiceOutput.isInitialized) {
                        voiceOutput.onTurnComplete(latestAssistantAnswer())
                    }
                    if (event is ChatEvent.Error) {
                        sawAppError = true
                        if (AuthRejectionPolicy.isCredentialRejected(event.message)) credentialRejected = true
                        if (!delivered) turnFailed = true
                    }
                    if (event is ChatEvent.SessionBound) {
                        currentSessionId = event.sessionId
                        refreshYoloState()
                    }
                    if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                    if (event is ChatEvent.ContextUsage) updateContextIndicator()
                    if (event is ChatEvent.SteerLeftover) restoreSteerText(event.text)
                    interactiveController.onEvent(event)
                    if (::compressionController.isInitialized) compressionController.onEvent(event)
                    // ANR-фикс: НЕ на каждый токен — троттлинг кадров стрима (StreamRenderThrottle).
                    // Терминальные события (Done/Error → status != TYPING) рисуются немедленно внутри.
                    scheduleStreamRender()
                    setStatus()
                }
            }
            if (credentialRejected) { requestReauthentication(); return@launch }
            // Доставленный ход оборвался без терминального Done → ЕДИНСТВЕННЫЙ recovery-job:
            // только attach c Last-Event-ID, никогда повторный POST chat/start и никогда
            // повторная отправка сообщения. Серверная ошибка, закрывшая ход (app-ошибка без
            // транспортного сбоя), — терминальна: reconnect лишь отреплеил бы её же.
            val recoveryStreamId = currentStreamId
            val appErrorTerminal = sawAppError && suppressedTransportError == null
            if (delivered && !sawDone && !appErrorTerminal && recoveryStreamId != null &&
                ::recovery.isInitialized
            ) {
                explicitStatus = STREAM_RECONNECT_STATUS
                stream = recovery.beginRecoveryAfterDrop(recoveryStreamId, suppressedTransportError)
                return@launch
            }
            if (uiState.status == ChatStatus.TYPING) {
                uiState.complete()
                // Стрим закрылся без терминального Done и без ухода в recovery: ход завершён
                // здесь — голосовой режим озвучивает финальный ответ (Done-путь уже озвучил).
                if (::voiceOutput.isInitialized) voiceOutput.onTurnComplete(latestAssistantAnswer())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (!delivered) turnFailed = true
            uiState.fail()
            if (credentialRejected) { requestReauthentication(); return@launch }
        } finally {
            renderMessages()
            setStatus()
            updateControls()
            updateNetworkBanner()
            // Отменённый пользователем стрим (кнопка «стоп», выход с экрана) автофлаш НЕ
            // запускает: осознанная остановка не должна продолжать очередь — остальные
            // сообщения ждут следующего триггера (переход сети, открытие диалога, отправка).
            if (coroutineContext[Job]?.isCancelled != true) {
                if (turnFailed && !credentialRejected) onTurnFailed?.invoke()
                flushOfflineQueue()
            }
        }
        }
    }

    // --- Сеть и офлайн-очередь -------------------------------------------------------------------

    /** Обработчик перехода сети: баннер обновляется всегда, восстановление связи запускает флаш. */
    private fun onNetworkTransition(transition: NetworkTransition) {
        updateNetworkBanner()
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

    /** Баннер: решение (видимость/текст/тон) — чистая NetworkBannerPolicy; хост мапит тон на MarsTheme. */
    private fun updateNetworkBanner() {
        if (!::networkBanner.isInitialized) return
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

    /**
     * Автоотправка буферизованных сообщений ТЕКУЩЕГО диалога (FIFO, по одному; следующее
     * диспетчеризуется из finally предыдущего хода). Не выполняется до первичного
     * восстановления диалога ([initialRestoreDone]), без сети и во время активного хода.
     */
    private fun flushOfflineQueue() {
        if (!initialRestoreDone || !::offlineQueue.isInitialized) return
        if (!networkPolicy.state.sendAllowed) return
        if (uiState.status == ChatStatus.TYPING) return
        val queued = offlineQueue.nextFor(currentSessionId) ?: run { updateNetworkBanner(); return }
        dispatchQueuedMessage(queued)
    }

    /** Флаш одного сообщения очереди: PENDING-эхо становится обычным ходом; недоставка — обратно в очередь. */
    private fun dispatchQueuedMessage(queued: QueuedMessage) {
        // Нет креденшелов (logout/реавторизация): отправлять некому. Сообщение сбрасывается
        // БЕЗ persist — иначе requeue перезапишет хранилище поверх logout-cleanup.
        val credential = store.read() ?: return
        if (!uiState.beginQueuedSend(queued.text)) {
            offlineQueue.requeue(queued)
            return
        }
        explicitStatus = null
        autoScroll.pinToBottom()
        renderMessages()
        setStatus()
        updateControls()
        updateNetworkBanner()
        stream = startSendStream(
            text = queued.text,
            outgoingAttachments = emptyList(),
            targetSessionId = queued.sessionId,
            credential = credential,
            onTurnFailed = {
                // Сессия могла создаться до сбоя потока: возврат в очередь под актуальным
                // ключом, иначе сообщение застрянет под null и не дождётся флаша диалога.
                val retry = queued.copy(sessionId = currentSessionId ?: queued.sessionId)
                if (!offlineQueue.requeue(retry)) {
                    explicitStatus = MESSAGE_NOT_DELIVERED_STATUS
                    setStatus()
                }
            },
        )
    }

    /** Сообщение ушло в очередь: локальное PENDING-эхо в транскрипте, композитор чист, баннер со счётчиком. */
    private fun onMessageBuffered(message: QueuedMessage) {
        uiState.beginQueuedEcho(message.text)
        composerLayoutPresenter.clearQuote()
        updateQuotePlate()
        hideKeyboard()
        input.text.clear()
        drafts.onSendAccepted(currentSessionId)
        explicitStatus = null
        autoScroll.pinToBottom()
        renderMessages()
        updateControls()
        updateNetworkBanner()
    }

    /** Локальное эхо сообщений очереди текущего диалога (после привязки транскрипта/«нового чата»). */
    private fun echoPendingOfflineMessages() {
        if (!::offlineQueue.isInitialized) return
        offlineQueue.pendingFor(currentSessionId).forEach { uiState.beginQueuedEcho(it.text) }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * Единый локальный разрыв живого хода при любом уходе из контекста диалога: гасит
     * токен send-пути ([LiveTurnGuard] — поздние SSE-кадры убитого хода не попадут в новый
     * экран), прерывает recovery-цикл и отменяет collect отправочного стрима. Серверный ход
     * при этом не трогается: при возвращении в диалог его подхватит reattachActiveTurn.
     */
    private fun teardownLiveTurn() {
        liveTurnGuard.invalidate()
        if (::recovery.isInitialized) recovery.cancelRecovery()
        // Отложенный trailing-flush и фоновый парсинг убитого хода отменяются: контекст
        // (сессия/экран) сменился, публиковать их результат больше некуда и незачем.
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamThrottle.onTrailingCancelled()
        markdownParseJob?.cancel()
        markdownParseJob = null
        // Смена диалога / «Стоп» / новый чат: озвучка предыдущего ответа не должна перетекать
        // в следующий контекст (one-shot «ответ на голос» тоже сбрасывается).
        if (::voiceOutput.isInitialized) voiceOutput.onSessionSwitch()
        stream?.cancel()
        stream = null
        currentStreamId = null
        // Строка статуса префилл-контекста принадлежит убитому ходу/диалогу — в новый
        // контекст она перетекать не должна (сброс, не накопление).
        clearContextStatus()
    }

    private fun cancelStream() {
        val credential = store.read()
        val id = currentStreamId
        if (credential != null && id != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ChatRepository().executeControl(ChatRepository().cancelRequest(BASE_URL, id, credential.accessToken)) }
            }
        }
        // «Стоп» убивает ход локально целиком: токен send-пути гаснет, recovery-цикл
        // прерывается — после осознанной остановки reattach не должен «воскрешать» стрим.
        teardownLiveTurn()
        interactiveController.dismissAll()
        uiState.cancel()
        explicitStatus = null
        setStatus()
        updateControls()
        renderMessages()
    }

    private fun steerMessage() {
        if (currentSessionReadOnly) {
            statusView.text = "Сессия доступна только для чтения"
            return
        }
        val text = input.text.toString().trim()
        val credential = store.read()
        val id = currentSessionId
        if (text.isBlank() || credential == null || id == null) return
        lifecycleScope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching {
                    val repository = ChatRepository()
                    repository.executeControl(repository.steerRequest(BASE_URL, id, text, credential.accessToken))
                }.getOrDefault(false)
            }
            if (accepted) {
                if (input.text.toString().trim() == text) input.text.clear()
                drafts.onSendAccepted(id)
                statusView.text = "Направление отправлено"
            } else {
                statusView.text = "Направление не принято — текст сохранён"
            }
        }
    }

    private fun updateControls() {
        val active = uiState.status == ChatStatus.TYPING
        sendButton.setImageDrawable(
            ContextCompat.getDrawable(this, if (active) R.drawable.ic_stop else R.drawable.ic_send)
        )
        sendButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        updateContextIndicator()
        updateLiveRunPlate()
        updateQuotePlate()
        updateTodoWidget()
        renderSmartChips()
    }

    /**
     * Smart Quick Chips ribbon (WebUI parity): one-tap conversation actions above
     * the composer. Visibility is decided by the pure [QuickChips] policy; icons are
     * monochrome vectors per the design system (no emoji).
     */
    private fun renderSmartChips() {
        if (!::smartChipsRow.isInitialized) return
        smartChipsRow.removeAllViews()
        val lastAssistant = uiState.messages.lastOrNull { it.role == "assistant" }
        val chips = QuickChips.visibleChips(
            status = uiState.status,
            hasAssistantReply = lastAssistant != null &&
                (lastAssistant.text.isNotBlank() || lastAssistant.tools.isNotEmpty() || lastAssistant.thoughts.isNotEmpty()),
            lastTurnFailed = lastAssistant?.status == MessageStatus.ERROR,
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

    private fun buildSmartChip(chip: QuickChip): Button = Button(this).apply {
        text = chip.label
        textSize = 12f
        isAllCaps = false
        setTextColor(MarsTheme.TEXT)
        background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER_MUTED)
        setPadding(dp(12), dp(4), dp(14), dp(4))
        setCompoundDrawablesRelativeWithIntrinsicBounds(smartChipIcon(chip.id), 0, 0, 0)
        compoundDrawablePadding = dp(6)
        compoundDrawableTintList = ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
        gravity = Gravity.CENTER
        minimumHeight = 0
        minHeight = 0
        stateListAnimator = null
        contentDescription = chip.label
        setOnClickListener { quickSend(chip.message) }
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

    private fun todoCallback(sessionId: String?): (ru.mars.network.model.TodoSnapshot) -> Unit = { snapshot ->
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
    private fun sideEventCallback(boundSessionId: String?): (ru.mars.network.model.StreamSideEvent) -> Unit = { event ->
        if (event is ContextStatusUpdate) {
            runOnUiThread {
                if (boundSessionId == null || currentSessionId == boundSessionId) {
                    onContextStatus(event)
                }
            }
        }
    }

    /**
     * Строка статуса префилл-контекста: каждый кадр ПЕРЕЗАПИСЫВАЕТ предыдущую строку
     * (не накапливает), видна [ContextStatusPresenter.DISPLAY_DURATION_MS] и гаснет,
     * возвращая обычную статус-цепочку («Марс печатает…» и т.п.). Кадр, который
     * показывать нечего (`not_configured`/чужая сессия), гасит строку сразу.
     */
    private fun onContextStatus(update: ContextStatusUpdate) {
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
    private fun clearContextStatus() {
        contextStatusJob?.cancel()
        contextStatusJob = null
        contextStatusLine = null
    }

    private fun updateQuotePlate() {
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

    private fun updateTodoWidget() {
        if (!::todoWidgetPlate.isInitialized) return
        val card = TodoPresenter.present(uiState.todoState)
        if (!card.visible) {
            todoWidgetPlate.visibility = View.GONE
            return
        }

        todoWidgetPlate.visibility = View.VISIBLE
        todoWidgetTitle.text = card.title
        todoWidgetChevron.setImageDrawable(
            ContextCompat.getDrawable(this, if (todoWidgetExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
        )

        todoWidgetContainer.removeAllViews()
        if (todoWidgetExpanded) {
            card.items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                val dot = MarsTheme.glowingStatusDot(
                    this,
                    when {
                        item.isCompleted -> MarsTheme.STATUS_ONLINE
                        item.isInProgress -> MarsTheme.STATUS_RUNNING
                        else -> MarsTheme.TEXT_MUTED
                    }
                )
                row.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })

                val itemText = TextView(this).apply {
                    textSize = 12f
                    if (item.isCompleted) {
                        val ssb = SpannableStringBuilder(item.content)
                        ssb.setSpan(StrikethroughSpan(), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text = ssb
                        setTextColor(MarsTheme.TEXT_MUTED)
                    } else {
                        text = item.content
                        setTextColor(if (item.isInProgress) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
                        if (item.isInProgress) setTypeface(null, Typeface.BOLD)
                    }
                }
                row.addView(itemText, LinearLayout.LayoutParams(0, -2, 1f))
                todoWidgetContainer.addView(row)
            }

            val moreBtn = Button(this).apply {
                text = "Все задачи подробнее →"
                textSize = 11.5f
                setTextColor(MarsTheme.ACCENT_TEXT)
                background = null
                setPadding(0, dp(4), 0, 0)
                gravity = Gravity.START
                setOnClickListener {
                    startActivity(Intent(this@ChatActivity, TodosActivity::class.java).apply {
                        putExtra("EXTRA_SESSION_ID", currentSessionId)
                    })
                }
            }
            todoWidgetContainer.addView(moreBtn)
        } else if (card.inProgressContent != null) {
            val previewRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            val pulse = MarsTheme.glowingStatusDot(this, MarsTheme.STATUS_RUNNING)
            previewRow.addView(pulse, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })

            val activeLabel = TextView(this).apply {
                text = card.inProgressContent
                textSize = 12f
                setTextColor(MarsTheme.TEXT_STRONG)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            previewRow.addView(activeLabel, LinearLayout.LayoutParams(0, -2, 1f))
            todoWidgetContainer.addView(previewRow)
        }
    }

    private fun setStatus() {
        val quiet = uiState.activityMode == ActivityDisplayMode.STREAM_QUIET
        statusView.text = explicitStatus ?: contextStatusLine ?: when {
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

    /**
     * Отрисовка транскрипта: ИНКРЕМЕНТАЛЬНАЯ (ANR-фикс волны 1310). Контейнер больше не
     * сносится целиком на каждый кадр — чистая IncrementalTranscriptPolicy сравнивает
     * снимки карточек ([CardSnapshotPolicy]) и выбирает план:
     * - NoChange: ничего не рисуем (политика скролла всё равно применяется — pin-to-bottom жив);
     * - FullRebuild: сессия сменилась / список укоротился / эпоха хрома — разовая полная сборка;
     * - Patch: пересобираются ТОЛЬКО «грязные» карточки и дозапускается хвост. На кадре стрима
     *   грязным обычно является ровно один пузырь (TEXT_ONLY) — его markdown парсится в фоне.
     *
     * Любой состоявшийся рендер закрывает окно троттлинга, гасит отложенный trailing-flush
     * и инвалидирует фоновые кадры: этот рендер синхронный и более свежий, чем они.
     */
    private fun renderMessages() {
        if (!::messages.isInitialized) return
        // Take the auto-scroll decision from the PRE-render viewport (synchronously, before any
        // mutation): a user scrolled up reading history stays put while SSE tokens keep coming;
        // only a pinned viewport follows the stream to the bottom.
        autoScroll.onViewportDistanceFromBottom(distanceFromBottomPx())
        scrollToBottomPolicy.onViewportDistanceFromBottom(distanceFromBottomPx())

        streamFlushJob?.cancel()
        streamFlushJob = null
        streamThrottle.onRendered(SystemClock.elapsedRealtime())
        markdownParseJob?.cancel()
        markdownParseJob = null
        frameGate.invalidateInFlight()

        val current = currentSnapshots()
        when (val plan = IncrementalTranscriptPolicy.plan(renderedSnapshots, current)) {
            TranscriptPlan.FullRebuild -> rebuildTranscript(current)
            is TranscriptPlan.Patch -> {
                // Fast path: только текст стримящегося пузыря — тяжёлый парсинг уходит в фон.
                if (tryAsyncTextOnlyUpdate(plan, current)) return
                applyTranscriptPatch(plan, current)
            }
            TranscriptPlan.NoChange -> Unit
        }
        renderedSnapshots = current
        finishTranscriptRender()
    }

    /**
     * Перерисовка кадров стрима: НЕ на каждый токен (это и был ANR), а через
     * StreamRenderThrottle — передний кадр сразу + ровно один trailing-flush на окно (~20 к/с).
     * Нетипинговые состояния (Done/Error → RECEIVED/ERROR, интерактив → IDLE) рисуются
     * немедленно: хвост терминального события не должен теряться. Вызывается из двух точек:
     * первичный collect и RecoveryListener.onStreamEvent (attachToStream + Last-Event-ID).
     */
    private fun scheduleStreamRender() {
        if (!::messages.isInitialized) return
        if (uiState.status != ChatStatus.TYPING) {
            renderMessages()
            return
        }
        when (val decision = streamThrottle.onStreamEvent(SystemClock.elapsedRealtime())) {
            StreamRenderDecision.RenderNow -> renderMessages()
            is StreamRenderDecision.ScheduleAfter -> {
                streamFlushJob?.cancel()
                streamFlushJob = lifecycleScope.launch {
                    delay(decision.delayMs)
                    streamFlushJob = null
                    renderMessages() // сам сбрасывает окно (onRendered) и гасит этот job
                }
            }
            StreamRenderDecision.Ignore -> Unit
        }
    }

    /** Актуальные снимки всех сообщений: сообщение + день-разделитель + стрим-флаг + эпоха хрома. */
    private fun currentSnapshots(): List<CardSnapshot> {
        var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        val lastIndex = uiState.messages.size - 1
        val typing = uiState.status == ChatStatus.TYPING
        return uiState.messages.mapIndexed { index, message ->
            val snapshot = CardSnapshotPolicy.of(
                message = message,
                previousTimestamp = previousTimestamp,
                chromeEpoch = transcriptChromeEpoch,
                streamingFlag = typing && index == lastIndex,
            )
            // Разделители суток: первая известная метка и каждая смена локального дня
            // получают день-чип («Сегодня»/«Вчера»/«9 октября») — чистая MessageTimePolicy.
            if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
            snapshot
        }
    }

    /** Хвост рендера (общий для синхронного и фонового путей): поиск, чипы, автоскролл. */
    private fun finishTranscriptRender() {
        // Открытый поиск переживает перерисовку (стрим/новый ход): совпадения пересчитываются,
        // позиция сохраняется, скролл не дёргается — пользователь остаётся там, где читал.
        if (::searchPlate.isInitialized && searchPlate.visibility == View.VISIBLE) {
            refreshTranscriptSearch(resetPosition = false, scrollToMatch = false)
        }
        renderSmartChips()
        if (autoScroll.shouldScrollOnContentChanged()) scrollToBottom() else syncScrollToBottomFab()
    }


    /** Разовая полная сборка (смена сессии / эпоха хрома / укорочение списка) — как старый рендер. */
    private fun rebuildTranscript(current: List<CardSnapshot>) {
        messages.removeAllViews()
        cardEntries.clear()
        uiState.messages.forEachIndexed { index, message ->
            val entry = buildCardEntry(index, message, current[index])
            messages.addView(entry.wrapper, LinearLayout.LayoutParams(-1, -2))
            cardEntries.add(entry)
        }
    }

    /** Точечный патч: грязные карточки заменяются на месте, новые дозапускаются в хвост. */
    private fun applyTranscriptPatch(plan: TranscriptPlan.Patch, current: List<CardSnapshot>) {
        for (patch in plan.updates) {
            val message = uiState.messages.getOrNull(patch.index) ?: continue
            val old = cardEntries.getOrNull(patch.index) ?: continue
            val newSnapshot = current[patch.index]
            // Text-only: карточка живёт, пересобирается только markdown-секция (без шапки/кнопок).
            val host = old.markdownHost
            if (patch.kind == CardUpdateKind.TEXT_ONLY && host != null) {
                host.removeAllViews()
                renderMarkdownBlocks(message.text, host, message.role == "user", patch.index)
                old.snapshot = newSnapshot
                continue
            }
            val replacement = buildCardEntry(patch.index, message, newSnapshot)
            val position = messages.indexOfChild(old.wrapper).takeIf { it >= 0 } ?: patch.index
            messages.removeViewAt(position)
            messages.addView(replacement.wrapper, position, LinearLayout.LayoutParams(-1, -2))
            cardEntries[patch.index] = replacement
        }
        for (index in plan.appendFrom until current.size) {
            val message = uiState.messages.getOrNull(index) ?: continue
            val entry = buildCardEntry(index, message, current[index])
            messages.addView(entry.wrapper, LinearLayout.LayoutParams(-1, -2))
            cardEntries.add(entry)
        }
    }

    /**
     * Fast path стрима: грязная карточка ровно одна и изменился только её текст. Тяжёлый
     * MarkdownFormatter.parseBlocks уходит на Dispatchers.Default; на UI-поток возвращается
     * только публикация готовых блоков. Версионирование кадров ([FrameVersionGate]): устаревший
     * кадр не публикуется поверх более свежего рендера. Job живёт в lifecycleScope — умирает
     * вместе с активити, висящих корутин не оставляет.
     *
     * @return true — обновление запланировано фоном; синхронный патч в этот кадр не нужен.
     */
    private fun tryAsyncTextOnlyUpdate(plan: TranscriptPlan.Patch, current: List<CardSnapshot>): Boolean {
        if (plan.appendFrom != current.size) return false // есть новые карточки — общий синхронный патч
        val only = plan.updates.singleOrNull() ?: return false
        if (only.kind != CardUpdateKind.TEXT_ONLY) return false
        val entry = cardEntries.getOrNull(only.index) ?: return false
        val host = entry.markdownHost ?: return false // секции ещё нет (пустой пузырь) — полная сборка
        val message = uiState.messages.getOrNull(only.index) ?: return false
        val text = message.text
        val isUser = message.role == "user"
        val messageIndex = only.index
        val version = frameGate.beginFrame()
        markdownParseJob = lifecycleScope.launch(Dispatchers.Default) {
            val blocks = MarkdownFormatter.parseBlocks(text)
            withContext(Dispatchers.Main.immediate) {
                if (!frameGate.canApply(version)) return@withContext // кадр устарел (прошёл синхронный рендер)
                markdownParseJob = null
                if (isDestroyed || !::messages.isInitialized) return@withContext
                // Карточку могли заменить синхронным патчем, пока парсинг летел, — обновляем только «свою».
                if (cardEntries.getOrNull(messageIndex) !== entry) return@withContext
                host.removeAllViews()
                renderParsedMarkdownBlocks(blocks, host, isUser, messageIndex)
                entry.snapshot = current[messageIndex]
                renderedSnapshots = current
                frameGate.commit(version)
                finishTranscriptRender()
            }
        }
        return true
    }

    /** Собирает одну карточку: wrapper = необязательный день-разделитель + пузырь (метрики прежние). */
    private fun buildCardEntry(index: Int, message: ChatMessage, snapshot: CardSnapshot): CardEntry {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (snapshot.showDaySeparator) {
            wrapper.addView(
                buildDaySeparator(snapshot.separatorTimestamp),
                LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                }
            )
        }
        if (message.isTrimAnchor) {
            wrapper.addView(buildTrimAnchorCard(message), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            return CardEntry(wrapper, snapshot, null)
        }
        val views = buildMessageCard(message, index, snapshot)
        wrapper.addView(views.root, cardLayoutParams(snapshot))
        return CardEntry(wrapper, snapshot, views.markdownHost)
    }

    /** Геометрия пузыря: 88% ширины экрана, справа у пользователя, слева у ассистента, отступ 8dp. */
    private fun cardLayoutParams(snapshot: CardSnapshot): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .88).toInt(), -2)
        params.topMargin = dp(8)
        params.gravity = if (snapshot.isUser) Gravity.END else Gravity.START
        return params
    }

    /** Карточка-якорь «старые сообщения подрезаны» ([TranscriptCapPolicy]). */
    private fun buildTrimAnchorCard(message: ChatMessage): View = TextView(this).apply {
        text = TranscriptCapPolicy.anchorLabel(message.trimmedCount)
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /** Корень пузыря + его markdown-секция (null, если текст пуст — секции нет). */
    private class CardViews(val root: View, val markdownHost: LinearLayout?)

    /**
     * Сборка ОДНОЙ карточки сообщения. Тело перенесено из прежнего монолитного renderMessages
     * почти дословно; отличия: snapshot.streamingFlag вместо пересчёта, markdown в отдельной
     * секции (для text-only обновлений), текст/действия резолвятся по индексу в момент клика
     * (карточка теперь переживает кадры стрима и захваченная копия устаревает).
     */
    private fun buildMessageCard(message: ChatMessage, index: Int, snapshot: CardSnapshot): CardViews {
        val isUser = message.role == "user"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (isUser) {
                MarsTheme.userBubble(this@ChatActivity)
            } else {
                MarsTheme.botMessageCard(this@ChatActivity)
            }
        }

        val labelRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!isUser) {
            // Vector status dot with ambient glow (WebUI parity)
            val dot = MarsTheme.glowingStatusDot(this, MarsTheme.STATUS_ONLINE)
            labelRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        }
        val label = TextView(this).apply {
            text = if (isUser) "Вы" else "Марс"
            setTextColor(if (isUser) MarsTheme.TEXT_MUTED else MarsTheme.TEXT_STRONG)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        labelRow.addView(label)

        if (!isUser) {
            modelSelection.buttonLabel()?.let { modelName ->
                val modelPill = TextView(this).apply {
                    text = modelName
                    textSize = 9.5f
                    setTextColor(MarsTheme.ACCENT_TEXT)
                    background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER_SUBTLE)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                }
                labelRow.addView(modelPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }
        }

        // Час сообщения справа в шапке карточки; при неизвестной метке не показываем.
        MessageTimePolicy.clockLabel(message.timestamp)?.let { clock ->
            labelRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            labelRow.addView(TextView(this).apply {
                text = clock
                textSize = 10.5f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
            })
        }
        card.addView(labelRow)

        if (message.attachments.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = message.attachments.joinToString("\n") { "[Файл: $it]" }
                setTextColor(MarsTheme.TEXT_MUTED)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }

        var markdownHost: LinearLayout? = null
        if (message.text.isNotBlank()) {
            // markdown — в отдельную секцию: кадры стрима пересобирают только её
            // (text-only), не трогая шапку/кнопки карточки. Ядро ANR-фикса.
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            renderMarkdownBlocks(message.text, host, isUser, index)
            card.addView(host, LinearLayout.LayoutParams(-1, -2))
            markdownHost = host
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val copyBtn = Button(this).apply {
                text = "Копировать"
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                background = null
                setPadding(0, 0, 0, 0)
                minimumHeight = 0
                minHeight = 0
                gravity = Gravity.START
                setOnClickListener { copyMessage(uiState.messages.getOrNull(index)?.text ?: message.text) }
            }
            actionsRow.addView(copyBtn)
            // «⋮» button: full message-actions sheet (copy/quote/share), WebUI parity.
            val moreBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_more_vert,
                "Действия с сообщением",
                bg = Color.TRANSPARENT,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 30,
                strokeColor = 0
            ).apply {
                setOnClickListener { showMessageActions(uiState.messages.getOrNull(index) ?: message) }
            }
            actionsRow.addView(moreBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) })
            if (!isUser) {
                // Speaker button: read Mars's answer aloud through the speech filter.
                val speakBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_volume,
                    "Озвучить сообщение",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 30,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { speakMessage(uiState.messages.getOrNull(index)?.text ?: message.text) }
                }
                actionsRow.addView(speakBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) })
            }
            card.addView(actionsRow)
        }

        if (message.thoughts.isNotEmpty()) {
            val isStreaming = snapshot.streamingFlag
            val thinkingView = ThinkingCardPresenter.present(
                thoughts = message.thoughts,
                expanded = message.thoughtsExpanded,
                streaming = isStreaming
            )
            if (thinkingView.visible) {
                val thoughtBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = MarsTheme.thinkingCard(this@ChatActivity)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val header = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener { uiState.toggleThoughts(index); renderMessages() }
                }
                val chevron = ImageView(this).apply {
                    setImageDrawable(ContextCompat.getDrawable(
                        this@ChatActivity,
                        if (thinkingView.chevronUp) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                    ))
                    imageTintList = ColorStateList.valueOf(thinkingView.chipColor)
                }
                header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

                val chipTitle = TextView(this).apply {
                    text = thinkingView.chipLabel
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(thinkingView.chipColor)
                }
                header.addView(chipTitle, LinearLayout.LayoutParams(0, -2, 1f))

                if (thinkingView.statusLabel != null) {
                    val statusBadge = TextView(this).apply {
                        text = thinkingView.statusLabel
                        textSize = 10.5f
                        setTextColor(if (thinkingView.streaming) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_0, strokeColor = 0)
                    }
                    header.addView(statusBadge)
                }
                thoughtBox.addView(header, LinearLayout.LayoutParams(-1, -2))

                if (thinkingView.expanded) {
                    val body = TextView(this).apply {
                        text = thinkingView.bodyText
                        setTextColor(thinkingView.bodyColor)
                        textSize = 12.5f
                        setTypeface(null, Typeface.ITALIC)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(dp(thinkingView.bodyIndentDp), dp(6), 0, dp(4))
                    }
                    thoughtBox.addView(body)

                    val copyBtn = Button(this).apply {
                        text = thinkingView.copyLabel
                        textSize = 11f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        background = null
                        isAllCaps = false
                        setPadding(dp(thinkingView.bodyIndentDp), dp(2), 0, 0)
                        minimumHeight = 0
                        minHeight = 0
                        gravity = Gravity.START
                        setOnClickListener { copyMessage(thinkingView.copySource) }
                    }
                    thoughtBox.addView(copyBtn)
                }
                card.addView(thoughtBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }

        message.tools.forEachIndexed { toolIndex, tool ->
            val toolView = ToolCardPresenter.present(tool)
            val toolBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = MarsTheme.toolCallCard(this@ChatActivity)
                setPadding(dp(10), dp(6), dp(8), dp(6))
            }
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { uiState.toggleTool(index, toolIndex); renderMessages() }
            }
            val toolIconRes = when (toolView.kind) {
                ToolKind.TERMINAL -> R.drawable.ic_chip
                ToolKind.FILESYSTEM -> R.drawable.ic_plan
                ToolKind.WEB -> R.drawable.ic_search
                ToolKind.MCP -> R.drawable.ic_refresh
                ToolKind.GENERIC -> R.drawable.ic_status
            }
            val toolIcon = ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, toolIconRes))
                imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
            }
            header.addView(toolIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

            val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val kindBadge = TextView(this).apply {
                text = toolView.kindLabel
                textSize = 9.5f
                setTextColor(MarsTheme.TEXT_MUTED)
            }
            titleCol.addView(kindBadge)
            val toolTitle = TextView(this).apply {
                text = toolView.toolName
                textSize = 11.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(toolView.titleColor)
            }
            titleCol.addView(toolTitle)
            header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

            val stateBadge = TextView(this).apply {
                text = toolView.stateLabel
                textSize = 9.5f
                setTypeface(Typeface.MONOSPACE)
                val statusEnum = if (toolView.state == ToolState.DONE) MarsGradients.Status.DONE else MarsGradients.Status.RUNNING
                MarsTheme.applyStatusBadge(this, statusEnum)
            }
            header.addView(stateBadge)

            val chevron = ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(
                    this@ChatActivity,
                    if (toolView.chevronUp) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                ))
                imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            }
            header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })

            val copyBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_copy,
                "Копировать инструмент",
                bg = Color.TRANSPARENT,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 28,
                strokeColor = 0
            ).apply {
                setOnClickListener {
                    copyMessage(toolView.copySource)
                    Toast.makeText(this@ChatActivity, "Скопировано", Toast.LENGTH_SHORT).show()
                }
            }
            header.addView(copyBtn, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })
            toolBox.addView(header, LinearLayout.LayoutParams(-1, -2))

            if (!toolView.expanded && toolView.detailLabel != null) {
                toolBox.addView(TextView(this).apply {
                    text = toolView.detailLabel
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(22), dp(4), 0, 0)
                })
            }

            if (toolView.expanded) {
                toolView.argsSection?.let { section ->
                    toolBox.addView(TextView(this).apply {
                        text = section.label
                        textSize = 10.5f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, dp(6), 0, dp(2))
                    })
                    val argsScroll = HorizontalScrollView(this).apply {
                        addView(TextView(this@ChatActivity).apply {
                            text = section.visibleText
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(MarsTheme.TEXT)
                            setTextIsSelectable(true)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = MarsTheme.card(this@ChatActivity, MarsTheme.CODE_BG, radiusDp = 8, strokeColor = MarsTheme.CODE_BORDER)
                        })
                    }
                    toolBox.addView(argsScroll)
                }

                toolView.resultSection?.let { section ->
                    toolBox.addView(TextView(this).apply {
                        text = section.label
                        textSize = 10.5f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, dp(6), 0, dp(2))
                    })
                    val resScroll = HorizontalScrollView(this).apply {
                        addView(TextView(this@ChatActivity).apply {
                            text = section.visibleText
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(MarsTheme.TEXT)
                            setTextIsSelectable(true)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = MarsTheme.card(this@ChatActivity, MarsTheme.CODE_BG, radiusDp = 8, strokeColor = MarsTheme.CODE_BORDER)
                        })
                    }
                    toolBox.addView(resScroll)
                }
            }
            card.addView(toolBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        if (message.text.isNotBlank()) {
            // Long-press anywhere on the bubble opens the message-actions sheet.
            card.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Резолвим в момент клика: захваченная при сборке копия устаревает за кадры стрима.
                showMessageActions(uiState.messages.getOrNull(index) ?: message)
                true
            }
        }
        // Тег = индекс сообщения: по нему поиск находит карточку для подсветки/скролла.
        card.tag = index
        return CardViews(card, markdownHost)
    }

    // --- Поиск по диалогу: хост чистой TranscriptSearchPolicy ------------------------------------

    /** День-чип разделителя суток («Сегодня»/«Вчера»/«9 октября») — подпись из MessageTimePolicy. */
    private fun buildDaySeparator(timestampMillis: Long): TextView = TextView(this).apply {
        text = MessageTimePolicy.dayLabel(timestampMillis, System.currentTimeMillis())
        textSize = 10.5f
        setTextColor(MarsTheme.TEXT_MUTED)
        background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        gravity = Gravity.CENTER
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
        searchPlate.visibility = View.GONE
        searchInput.setText("")
        searchMatches = emptyList()
        searchPosition = -1
        clearSearchHighlight()
        hideKeyboard()
    }

    /**
     * Пересчитывает совпадения по текущему транскрипту и обновляет счётчик.
     * [resetPosition] — сброс на первое совпадение (новый запрос); без него позиция
     * сохраняется и лишь зажимается в границы (перерисовка во время стрима).
     * [scrollToMatch] — скроллить к совпадению (только явная навигация/новый запрос).
     */
    private fun refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean) {
        val query = searchInput.text?.toString().orEmpty()
        searchMatches = TranscriptSearchPolicy.findMatches(uiState.messages.map { it.text }, query)
        searchPosition = when {
            searchMatches.isEmpty() -> -1
            resetPosition || searchPosition < 0 -> 0
            else -> searchPosition.coerceAtMost(searchMatches.size - 1)
        }
        searchCounter.text = TranscriptSearchPolicy.counterLabel(query, searchPosition, searchMatches.size)
        applySearchHighlight(scrollTo = scrollToMatch && searchPosition >= 0)
    }

    /** Кольцевой шаг «следующее/предыдущее совпадение» + обновление счётчика и подсветки. */
    private fun navigateTranscriptSearch(forward: Boolean) {
        val next = TranscriptSearchPolicy.navigate(searchMatches.size, searchPosition, forward)
        if (next < 0) return
        searchPosition = next
        searchCounter.text = TranscriptSearchPolicy.counterLabel(
            searchInput.text?.toString().orEmpty(), searchPosition, searchMatches.size
        )
        applySearchHighlight(scrollTo = true)
    }

    /** Рубиновая рамка вокруг карточки текущего совпадения и плавный скролл к ней. */
    private fun applySearchHighlight(scrollTo: Boolean) {
        clearSearchHighlight()
        val match = searchMatches.getOrNull(searchPosition) ?: return
        val card = messages.findViewWithTag<View>(match.messageIndex) ?: return
        // foreground, а не background: градиент пузыря/карточки остаётся нетронутым.
        card.foreground = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(2), MarsTheme.ACCENT)
        }
        searchHighlightedCard = card
        if (scrollTo) scroll.smoothScrollTo(0, (card.top - dp(64)).coerceAtLeast(0))
    }

    private fun clearSearchHighlight() {
        searchHighlightedCard?.foreground = null
        searchHighlightedCard = null
    }

    private fun renderMarkdownBlocks(value: String, parent: LinearLayout, user: Boolean, messageIndex: Int) {
        renderParsedMarkdownBlocks(MarkdownFormatter.parseBlocks(value), parent, user, messageIndex)
    }

    /**
     * Сборка markdown-view из УЖЕ распарсенных блоков (view-инфлейт — только main-поток).
     * Тяжёлый [MarkdownFormatter.parseBlocks] делается либо синхронно выше (история/полная
     * перерисовка), либо в фоне с версионированием кадров — см. tryAsyncTextOnlyUpdate:
     * на UI-поток возвращается только публикация готового результата.
     */
    private fun renderParsedMarkdownBlocks(blocks: List<ContentBlock>, parent: LinearLayout, user: Boolean, messageIndex: Int) {
        blocks.forEachIndexed { blockIndex, block ->
            when (block) {
                is ContentBlock.Text -> {
                    block.lines.forEach { line ->
                        val tv = TextView(this).apply {
                            movementMethod = LinkMovementMethod.getInstance()
                            setTextColor(if (user) Color.WHITE else MarsTheme.TEXT)
                            textSize = 15f
                            setPadding(0, dp(2), 0, dp(2))
                            setLineSpacing(dp(2).toFloat(), 1f)
                        }
                        val ssb = SpannableStringBuilder()
                        when (line) {
                            is RichLine.Header -> {
                                tv.textSize = when (line.level) {
                                    1 -> 18f
                                    2 -> 17f
                                    else -> 16f
                                }
                                tv.setTypeface(null, Typeface.BOLD)
                                appendInlineSpans(ssb, line.spans)
                            }
                            is RichLine.Bullet -> {
                                ssb.append("•  ")
                                appendInlineSpans(ssb, line.spans)
                            }
                            is RichLine.Numbered -> {
                                ssb.append("${line.number} ")
                                appendInlineSpans(ssb, line.spans)
                            }
                            is RichLine.Quote -> {
                                val quoteStart = ssb.length
                                ssb.append("  ")
                                ssb.setSpan(
                                    android.text.style.BackgroundColorSpan(MarsTheme.ACCENT),
                                    quoteStart,
                                    quoteStart + 1,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                appendInlineSpans(ssb, line.spans)
                                tv.setTypeface(null, Typeface.ITALIC)
                            }
                            is RichLine.TaskItem -> {
                                val iconStart = ssb.length
                                ssb.append("  ")
                                val iconRes = if (line.checked) R.drawable.ic_check else R.drawable.ic_checkbox_empty
                                ssb.setSpan(
                                    ImageSpan(this@ChatActivity, iconRes, ImageSpan.ALIGN_BASELINE),
                                    iconStart,
                                    ssb.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                val start = ssb.length
                                appendInlineSpans(ssb, line.spans)
                                if (line.checked) {
                                    ssb.setSpan(StrikethroughSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    ssb.setSpan(ForegroundColorSpan(MarsTheme.TEXT_MUTED), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                            is RichLine.Normal -> {
                                appendInlineSpans(ssb, line.spans)
                            }
                            is RichLine.Divider -> {
                                tv.text = ""
                                tv.setBackgroundColor(MarsTheme.BORDER)
                                tv.layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(1)
                                ).apply {
                                    topMargin = dp(6)
                                    bottomMargin = dp(6)
                                }
                            }
                        }
                        tv.text = ssb
                        parent.addView(tv)
                    }
                }
                is ContentBlock.Code -> {
                    val expanded = uiState.isCodeBlockExpanded(messageIndex, blockIndex)
                    val view = CodeBlockPresenter.present(block, expanded)
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(6), dp(6), dp(6))
                        background = MarsTheme.card(this@ChatActivity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.CODE_BORDER)
                    }
                    // Block header: language title on the left, copy action on the right.
                    val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                    bar.addView(TextView(this).apply {
                        text = view.languageLabel
                        textSize = 11f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        setTextColor(MarsTheme.ACCENT_TEXT)
                        setPadding(dp(2), 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    val copyCodeBtn = MarsTheme.circleIconButton(
                        this,
                        R.drawable.ic_copy,
                        "Копировать код",
                        bg = MarsTheme.SURFACE,
                        iconTint = MarsTheme.TEXT,
                        sizeDp = 28,
                        strokeColor = 0
                    ).apply {
                        setOnClickListener {
                            copyMessage(view.fullCode)
                            Toast.makeText(this@ChatActivity, "Скопировано", Toast.LENGTH_SHORT).show()
                        }
                    }
                    bar.addView(copyCodeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
                    box.addView(bar)
                    box.addView(HorizontalScrollView(this).apply {
                        addView(TextView(this@ChatActivity).apply {
                            text = view.visibleCode
                            setTextColor(MarsTheme.TEXT)
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            setPadding(0, dp(4), dp(16), dp(8))
                        })
                    })
                    // Long listings are collapsed by default; the toggle flips the state.
                    val toggleLabel = view.toggleLabel
                    if (toggleLabel != null) {
                        box.addView(Button(this).apply {
                            text = toggleLabel
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(MarsTheme.ACCENT_TEXT)
                            chevronStart(
                                if (view.expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                                MarsTheme.ACCENT_TEXT,
                            )
                            background = null
                            isAllCaps = false
                            setPadding(0, 0, 0, 0)
                            minimumHeight = 0
                            minHeight = 0
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            setOnClickListener {
                                uiState.toggleCodeBlock(messageIndex, blockIndex)
                                // Свёртка code-блока живёт ВНЕ снимка сообщения (ключ messageIndex:blockIndex
                                // в ChatUiState): bumps эпохи хрома → политика даст FullRebuild, как раньше.
                                transcriptChromeEpoch++
                                renderMessages()
                            }
                        }, LinearLayout.LayoutParams(-1, dp(26)))
                    }
                    parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
                is ContentBlock.Table -> {
                    // Card with a HorizontalScrollView around a TableLayout so wide tables
                    // scroll sideways smoothly instead of breaking the phone layout.
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    val table = TableLayout(this).apply {
                        // Thin themed divider between rows instead of hardcoded paints.
                        dividerDrawable = GradientDrawable().apply {
                            setColor(MarsTheme.BORDER)
                            setSize(dp(1), dp(1))
                        }
                        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    }
                    fun buildTableRow(cells: List<List<InlineSpan>>, header: Boolean): TableRow =
                        TableRow(this).apply {
                            if (header) setBackgroundColor(MarsTheme.SURFACE_SUBTLE)
                            cells.forEach { spans ->
                                val cell = TextView(this@ChatActivity).apply {
                                    movementMethod = LinkMovementMethod.getInstance()
                                    textSize = 13f
                                    setLineSpacing(dp(2).toFloat(), 1f)
                                    setPadding(dp(12), dp(8), dp(12), dp(8))
                                    if (header) {
                                        setTextColor(MarsTheme.TEXT_STRONG)
                                        setTypeface(null, Typeface.BOLD)
                                    } else {
                                        setTextColor(MarsTheme.TEXT)
                                    }
                                    val cellText = SpannableStringBuilder()
                                    appendInlineSpans(cellText, spans)
                                    text = cellText
                                }
                                addView(cell)
                            }
                        }
                    table.addView(buildTableRow(block.headers, header = true))
                    block.rows.forEach { row -> table.addView(buildTableRow(row, header = false)) }
                    card.addView(HorizontalScrollView(this).apply { addView(table) })
                    parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
        }
    }

    private fun appendInlineSpans(builder: SpannableStringBuilder, spans: List<InlineSpan>) {
        spans.forEach { span ->
            val start = builder.length
            when (span) {
                is InlineSpan.Normal -> builder.append(span.text)
                is InlineSpan.Bold -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Italic -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Monospace -> {
                    builder.append(span.text)
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(MarsTheme.SURFACE_SUBTLE), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Link -> {
                    builder.append(span.title)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            // Image links open in the fullscreen lightbox, everything else in a browser.
                            if (ImageLightboxPolicy.isImageUrl(span.url)) {
                                openImageLightbox(span.url)
                            } else {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(span.url))) }
                            }
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Strikethrough -> {
                    builder.append(span.text)
                    builder.setSpan(StrikethroughSpan(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Image -> {
                    // Markdown image: a clickable accent label — tap opens the fullscreen viewer.
                    builder.append(ImageLightboxPolicy.inlineLabel(span.alt))
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) = openImageLightbox(span.url)
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = MarsTheme.ACCENT_TEXT
                            ds.isUnderlineText = true
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun toolDump(tool: ToolExecution): String =
        "Параметры:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"

    private fun loadSessions(onFinished: (() -> Unit)? = null) {
        val c = store.read() ?: run { onFinished?.invoke(); return }
        lifecycleScope.launch {
            try {
                val fresh = SessionRepository().getSessions(BASE_URL, c)
                // Пустой ответ (сбой сети — репозиторий не бросает исключение, — или диалогов
                // действительно нет) сверяется с офлайн-кэшем: на сбое состояние уровня
                // держит последний сохранённый снимок вместо пустого списка.
                uiState.setSessions(withContext(Dispatchers.IO) { offlineCache.reconcile(fresh) })
            } finally {
                // Throttle обновления списка (защита от шторма) отмечает завершение полёта.
                onFinished?.invoke()
            }
        }
    }

    /**
     * Открывает диалог по id — клик по drawer'у или восстановление после пересоздания экрана.
     */
    private fun loadSessionById(sessionId: String) {
        val c = store.read() ?: return
        // Черновик покидаемого диалога сохраняется до смены текущего session_id.
        persistComposerDraft()
        interactiveController.dismissAll()
        compressionController.onSessionChanged()
        // Токен навигации: устаревший callback (пользователь открыл другой диалог/новый чат
        // или закрыл сессию, пока грузились детали) не перетирает актуальное состояние.
        val token = sessionNavigation.beginNavigation()
        // Смена диалога локально убивает живой ход предыдущего ЦЕЛИКОМ: и collect send-пути,
        // и recovery-цикл. Иначе поздние SSE-кадры старого хода перекрасят только что открытый
        // диалог (чанки/SessionBound/диалоги подтверждения), а живый старый стрим заблокирует
        // reattachActiveTurn новой сессии. Серверный ход не прерывается: при возвращении в
        // диалог его подхватит reattach.
        teardownLiveTurn()
        lifecycleScope.launch {
            val detail = SessionRepository().getSessionDetail(BASE_URL, c, sessionId)
            if (detail != null) {
                // Успешная загрузка обновляет офлайн-кэш транскрипта диалога.
                withContext(Dispatchers.IO) { offlineCache.saveMessages(detail.id, detail.messages) }
            }
            if (detail != null && sessionNavigation.isCurrent(token)) {
                // Текст, набранный ВО ВРЕМЯ загрузки, сохраняется в черновик старого диалога
                // непосредственно перед присвоением нового session_id.
                drafts.onSessionSwitch(currentSessionId, detail.id, input.text.toString())
                uiState.bindSession(detail.id, detail.title, historyMessages(detail))
                modelSelection.setSessionModel(detail.model, detail.modelProvider)
                currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == detail.id }?.readOnly == true
                modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
                // Active-turn/attention banner state from the freshly loaded detail.
                uiState.setActiveTurn(detail.activeTurn)
                uiState.setTodoState(detail.todoState)
                currentSessionId = detail.id
                restoreComposerDraft()
                autoScroll.pinToBottom()
                renderMessages()
                setStatus()
                updateTodoWidget()
                refreshYoloState()
                // Stream recovery: a session that is still streaming server-side must resume
                // live instead of showing a frozen transcript.
                reattachActiveTurn(detail.activeTurn)
                // Сообщения этого диалога из офлайн-очереди возвращают локальное эхо; если
                // сеть уже есть — сразу отправляются. Первичное восстановление завершено.
                echoPendingOfflineMessages()
                initialRestoreDone = true
                flushOfflineQueue()
            } else if (detail == null && sessionNavigation.isCurrent(token)) {
                showOfflineSessionCopy(sessionId)
            }
        }
    }

    /**
     * Сбой сети при загрузке деталей диалога: вместо пустого экрана показывается
     * сохранённая копия транскрипта из офлайн-кэша ([SessionOfflineCache]); строка
     * статуса явно говорит, что это сохранённые данные, а не живой диалог.
     */
    private suspend fun showOfflineSessionCopy(sessionId: String) {
        // Флаг ставится даже без кэша: автофлаш очереди не должен блокироваться вечно.
        initialRestoreDone = true
        val cached = withContext(Dispatchers.IO) { offlineCache.loadMessages(sessionId) } ?: return
        drafts.onSessionSwitch(currentSessionId, sessionId, input.text.toString())
        currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == sessionId }?.readOnly == true
        modelSelection.setSessionModel(
            uiState.sessions.firstOrNull { it.id == sessionId }?.model,
            uiState.sessions.firstOrNull { it.id == sessionId }?.modelProvider,
        )
        uiState.bindSession(
            sessionId,
            uiState.sessions.firstOrNull { it.id == sessionId }?.title,
            historyMessages(SessionDetail(sessionId, messages = cached)),
        )
        // Кэш намеренно не хранит active_turn: копия загружается idle-состоянием.
        uiState.setActiveTurn(ActiveTurnInfo())
        currentSessionId = sessionId
        restoreComposerDraft()
        echoPendingOfflineMessages()
        autoScroll.pinToBottom()
        explicitStatus = OFFLINE_SESSION_HINT
        renderMessages()
        setStatus()
        updateControls()
        updateTodoWidget()
        flushOfflineQueue()
    }

    /**
     * Вход в уровень диалога: [EXTRA_OPEN_SESSION_ID] (открыть конкретный чат —
     * переход [NavTransition.OpenSession]) → savedInstanceState (пересоздание) →
     * холодный старт нового чата. При пересоздании чистая политика
     * [SessionRecreationPolicy] решает по savedInstanceState, открывать ли
     * сохранённый session_id заново. Загрузка идёт через общий guard
     * [sessionNavigation], а одноразовость политики исключает повторное
     * восстановление в этом же экземпляре экрана.
     */
    private fun restoreOpenSession(savedInstanceState: Bundle?) {
        val requested = intent?.getStringExtra(EXTRA_OPEN_SESSION_ID)?.trim().orEmpty()
        val target = requested.ifEmpty { savedInstanceState?.getString(KEY_OPEN_SESSION_ID) }
        val plan = recreationPolicy.planFor(target)
        if (plan is RecreationPlan.Reopen) {
            loadSessionById(plan.sessionId)
        } else {
            // Холодный старт — новый чат: эхо буферизованных сообщений нового чата и
            // разрешение автофлаша очереди (сам флаш guarded состоянием сети/хода).
            initialRestoreDone = true
            echoPendingOfflineMessages()
            flushOfflineQueue()
        }
    }

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
     * Reattaches to the session's live stream (`GET /api/chat/stream?stream_id=...`, no
     * `POST /api/chat/start`) when [activeTurn] says one exists — the server replays journaled
     * events missed while the app was closed and continues live. A terminal 404 means the turn
     * settled between the detail fetch and the attach: the placeholder bubble is dropped and the
     * settled history is reloaded instead of surfacing an error.
     */
    private fun reattachActiveTurn(activeTurn: ActiveTurnInfo) {
        val plan = reattachPolicy.planFor(activeTurn) as? ReattachPlan.Attach ?: return
        if (stream?.isActive == true) return
        if (!::recovery.isInitialized || recovery.isRecovering) return
        store.read() ?: return // транспорт recovery читает credential на каждой попытке attach
        if (!uiState.beginReattachedTurn()) return
        autoScroll.pinToBottom()
        currentStreamId = plan.streamId
        explicitStatus = REATTACH_STATUS
        renderMessages()
        setStatus()
        updateControls()
        // Recovery-цикл (MidTurnRecoveryController) владеет всем attach-жизненным циклом:
        // единственный job, backoff/офлайн-парковка, resume по Last-Event-ID, транспортный
        // 404 (стрим умер серверно) → ReloadSession с обновлением истории, терминальный
        // Done не ретраится, серверные tool-ошибки идут в транскрипт и завершают цикл без
        // reconnect'а, 401 → GiveUp(auth) → requestReauthentication (см. RecoveryListener).
        stream = recovery.attachActiveTurn(plan.streamId)
    }

    // --- Mid-turn recovery: тонкий wiring MidTurnRecoveryController.Listener --------------------

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

    /** Завершение recovery-цикла: статус/контролы/офлайн-очередь — паритет с finally прежнего attach. */
    private fun finishRecoveredTurn(speakFinalAnswer: Boolean) {
        clearRecoveryStatus()
        if (uiState.status == ChatStatus.TYPING) uiState.complete()
        // Восстановленный ход завершился успешно — голосовой режим озвучивает финальный
        // ответ; ход, умерший серверной ошибкой, не озвучивается.
        if (speakFinalAnswer && ::voiceOutput.isInitialized) {
            voiceOutput.onTurnComplete(latestAssistantAnswer())
        }
        renderMessages()
        setStatus()
        updateControls()
        flushOfflineQueue()
    }

    /** Снимает статусную строку, только если её поставил recovery-цикл (не пользователь/сервер). */
    private fun clearRecoveryStatus() {
        val status = explicitStatus ?: return
        if (status == REATTACH_STATUS || status == STREAM_PARKED_OFFLINE_STATUS ||
            status.startsWith(STREAM_RECONNECT_STATUS)
        ) {
            explicitStatus = null
        }
    }

    // --- Обновление списка сессий на восстановлении сети (защита от шторма) -----------------------

    private fun requestSessionListRefresh() {
        when (val decision = sessionRefreshThrottle.onTrigger(SystemClock.elapsedRealtime())) {
            SessionRefreshDecision.Now -> runThrottledSessionRefresh()
            is SessionRefreshDecision.Later -> {
                sessionRefreshJob?.cancel()
                sessionRefreshJob = lifecycleScope.launch {
                    delay(decision.delayMs)
                    runThrottledSessionRefresh()
                }
            }
            SessionRefreshDecision.Skip -> Unit
        }
    }

    private fun runThrottledSessionRefresh() {
        sessionRefreshThrottle.onStarted()
        loadSessions {
            // Триггеры, пришедшие во время полёта, схлопываются в один догоняющий refresh.
            if (sessionRefreshThrottle.onFinished(SystemClock.elapsedRealtime())) runThrottledSessionRefresh()
        }
    }

    /** Maps a persisted session transcript to bubbles (shared by loadSession and post-compression reload). */
    private fun historyMessages(detail: SessionDetail): List<ChatMessage> = detail.messages.map {
        // Реальное серверное время сообщения (createdAt): метки питают час на карточке и
        // разделители суток; нераспознанная/отсутствующая метка -> UNKNOWN (время не показываем).
        ChatMessage(
            if (it.role == "user") "user" else "assistant",
            it.content,
            timestamp = MessageTimePolicy.parseServerTimestamp(it.createdAt),
        )
    }

    /**
     * Re-fetches the transcript of [currentSessionId] after compression replaced the server-side
     * history (`/api/session/compress` swaps `context_messages`); the compressed transcript is
     * what the next turn continues from, so the bubbles must show the same view.
     */
    private fun reloadCurrentSession() {
        val c = store.read() ?: return
        val id = currentSessionId ?: return
        lifecycleScope.launch {
            val detail = SessionRepository().getSessionDetail(BASE_URL, c, id)
            if (detail != null && currentSessionId == id) {
                uiState.bindSession(detail.id, detail.title, historyMessages(detail))
                modelSelection.setSessionModel(detail.model, detail.modelProvider)
                currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == detail.id }?.readOnly == true
                modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
                uiState.setActiveTurn(detail.activeTurn)
                uiState.setTodoState(detail.todoState)
                autoScroll.pinToBottom()
                renderMessages()
                updateTodoWidget()
                refreshYoloState()
            }
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

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Распознавание речи недоступно"; return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), recognizeVoice); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
        }
        // Launch is part of the guarded transition: ActivityNotFound/SecurityException cannot leave
        // the controller stuck in LISTENING.
        voiceSession.start {
            startActivityForResult(intent, recognizeVoice)
        }
    }

    /** Speak a message through [VoiceOutputController] (markdown is filtered inside). */
    private fun speakMessage(text: String) {
        when (voiceOutput.speakNow(text)) {
            VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK ->
                statusView.text = "В сообщении нет текста для озвучивания"
            VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE ->
                statusView.text = VOICE_OUTPUT_UNAVAILABLE
            VoiceOutputController.SpeakOutcome.SPOKEN -> Unit
        }
    }

    /** Финальный текст последнего ассистент-сообщения — голос озвучивает только его. */
    private fun latestAssistantAnswer(): String? =
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

    @Suppress("DEPRECATION")
    private fun copyMessage(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setText(text)
        statusView.text = "Скопировано"
    }

    /**
     * Message-actions sheet (WebUI parity): copy / quote / share for one message.
     * Opened by the «⋮» button and by a long-press anywhere on the bubble.
     * Labels, quote formatting and the empty-text guard live in [MessageActionPolicy].
     */
    private fun showMessageActions(message: ChatMessage) {
        val actions = MessageActionPolicy.actionsFor(message.text)
        if (actions.isEmpty()) return
        var dialog: Dialog? = null
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Действия с сообщением", null))
            val stack = MarsDialog.pillStack(this, actions.map { kind ->
                kind.label to {
                    dialog?.dismiss()
                    performMessageAction(kind, message)
                }
            })
            root.addView(stack)
            listOf(stack)
        }
        dialog?.window?.setDimAmount(0.8f)
    }

    private fun performMessageAction(kind: MessageActionKind, message: ChatMessage) {
        when (kind) {
            MessageActionKind.COPY -> {
                copyMessage(message.text)
                Toast.makeText(this, MessageActionPolicy.COPY_TOAST, Toast.LENGTH_SHORT).show()
            }
            MessageActionKind.QUOTE -> {
                val author = if (message.role == "user") "Вы" else "Марс"
                composerLayoutPresenter.setQuote(author, message.text)
                updateQuotePlate()
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                statusView.text = "Цитата прикреплена к сообщению"
            }
            MessageActionKind.SHARE -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, MessageActionPolicy.SHARE_SUBJECT)
                    putExtra(Intent.EXTRA_TEXT, MessageActionPolicy.shareBody(message.text))
                }
                runCatching { startActivity(Intent.createChooser(intent, "Поделиться сообщением")) }
                    .onFailure { statusView.text = "Некому открыть сообщение для шаринга" }
            }
        }
    }

    /**
     * Fullscreen image viewer (lightbox): dark backdrop, async bitmap load,
     * close button + backdrop tap dismiss. URL resolution and the image-link
     * heuristic live in [ImageLightboxPolicy]/[ThumbnailUrlResolver].
     */
    private fun openImageLightbox(rawUrl: String) {
        val url = ImageLightboxPolicy.resolveUrl(rawUrl, BASE_URL)
        if (url == null) {
            statusView.text = "Изображение недоступно"
            return
        }
        val dialog = Dialog(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(MarsTheme.LIGHTBOX_SCRIM)
            fitsSystemWindows = true
            setOnClickListener { dialog.dismiss() }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Просмотр изображения"
        }
        val progress = TextView(this).apply {
            text = "Загрузка…"
            setTextColor(MarsTheme.TEXT_MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        val closeBtn = MarsTheme.circleIconButton(
            this, R.drawable.ic_close, "Закрыть просмотр",
            bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT_STRONG, sizeDp = 40
        ).apply { setOnClickListener { dialog.dismiss() } }
        root.addView(closeBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16)
            marginEnd = dp(16)
        })
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(1f)
        }
        dialog.show()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmapForViewer(url) }
            if (!dialog.isShowing) return@launch
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                progress.visibility = View.GONE
            } else {
                progress.text = "Не удалось загрузить изображение"
            }
        }
    }

    /** Bounded bitmap fetch for the lightbox: ≤25 MB payload, downscaled above 4096 px. */
    private fun fetchBitmapForViewer(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            val bytes = connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_VIEWER_IMAGE_BYTES) return null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_VIEWER_IMAGE_DIMENSION ||
                bounds.outHeight / sample > MAX_VIEWER_IMAGE_DIMENSION
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun renderAttachmentChips() {
        attachmentChips.removeAllViews()
        attachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachments.forEachIndexed { i, a ->
            val previewable = a.isImage == true || a.mime.startsWith("image/")
            attachmentChips.addView(Button(this).apply {
                text = a.name
                textSize = 11f
                setTextColor(Color.WHITE)
                contentDescription = if (previewable) {
                    "Убрать вложение ${a.name}, долгое нажатие — просмотр"
                } else {
                    "Убрать вложение ${a.name}"
                }
                // Vector `ic_close` as the trailing affordance — replaces the cross text glyph.
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null, null, ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_close), null
                )
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                compoundDrawablePadding = dp(6)
                background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.ACCENT)
                setOnClickListener { attachments.removeAt(i); renderAttachmentChips() }
                if (previewable) {
                    // Long-press previews the uploaded image in the fullscreen lightbox.
                    setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openImageLightbox(a.path)
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
            text = "· $label"
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
        activityModeBtn?.text = "· ${activityModeSelection.buttonLabel()}"
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
        workspaceBtn?.text = workspaceSelection.buttonLabel()?.let { "· $it" } ?: "· Workspace"
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
     * Composer context pill: `NN%` + used/total tokens, colored like the WebUI
     * ring (green ≤50, orange ≤75, red above). Hidden while unknown — an
     * unresolved window must not become a fake percentage (#1436 parity).
     * Text, thresholds and visibility rules live in [ContextBudgetPolicy] (JVM-tested);
     * this layer only maps the level to a theme color.
     */
    private fun updateContextIndicator() {
        val view = contextIndicator ?: return
        val pill = ContextBudgetPolicy.pill(uiState.contextUsage, compressionRunning)
        if (!pill.visible) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = pill.text
        view.setTextColor(
            when (pill.level) {
                ContextBudgetLevel.CRITICAL -> MarsTheme.STATUS_ERROR
                ContextBudgetLevel.WARNING -> MarsTheme.STATUS_WARNING
                ContextBudgetLevel.NORMAL, null -> MarsTheme.STATUS_ONLINE
            }
        )
        view.contentDescription = pill.contentDescription
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
            setTextColor(Color.WHITE)
        }
        val topicInput = EditText(this).apply {
            hint = "Фокус сжатия (необязательно)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MarsTheme.TEXT_MUTED)
        }
        val autoBox = CheckBox(this).apply {
            text = "Автосжатие при ${config.thresholdPercent}% заполнения"
            isChecked = config.enabled
            setTextColor(Color.WHITE)
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
    private fun restoreSteerText(text: String) {
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

    private fun scrollToBottom() {
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
    private fun syncScrollToBottomFab() {
        scrollToBottomFab?.visibility = if (scrollToBottomPolicy.visible) View.VISIBLE else View.GONE
    }

    /**
     * Distance (px) between the bottom of the transcript content and the bottom of the visible
     * viewport; 0 while pinned at the latest message and before the views exist.
     */
    private fun distanceFromBottomPx(): Int {
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
        modelSelection.clearSessionModel()
        modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
        currentSessionId = null
        explicitStatus = null
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

    /** Сохраняет текст композитора как черновик текущего диалога (до смены диалога/ухода с экрана). */
    private fun persistComposerDraft() {
        if (!::drafts.isInitialized || !::input.isInitialized) return
        // После logout/реавторизации сохранение запрещено: onPause закрывающегося экрана
        // не должен записывать черновик обратно в уже очищенное приватное хранилище.
        if (::authExitCleanup.isInitialized && !authExitCleanup.draftPersistenceAllowed) return
        drafts.saveDraft(currentSessionId, input.text.toString())
    }

    /** Показывает сохранённый черновик текущего диалога (null — отдельный черновик нового чата). */
    private fun restoreComposerDraft() {
        if (!::drafts.isInitialized || !::input.isInitialized) return
        val text = drafts.draftFor(currentSessionId).orEmpty()
        input.setText(text)
        if (text.isNotEmpty()) {
            input.setSelection(text.length)
        }
    }

    // --- Autonomous execution state --------------------------------------------------------------

    /** Refreshes the server-side execution approval state without exposing internal terminology in the UI. */
    private fun refreshYoloState() {
        val sid = currentSessionId
        val credential = store.read()
        if (sid == null || credential == null) {
            uiState.setYoloEnabled(false)
            return
        }
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                when (val lookup = SessionYoloRepository().fetchState(BASE_URL, credential, sid)) {
                    is YoloLookup.State -> lookup.enabled
                    is YoloLookup.Failed -> false
                }
            }
            if (currentSessionId == sid) {
                uiState.setYoloEnabled(enabled)
                }
        }
    }

    // --- Finish (graceful session close) ---------------------------------------------------------

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
                // YOLO does not outlive the session being closed; make the pill reflect reality.
                if (report != null) uiState.setYoloEnabled(false)
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
        currentSessionId = null
        currentSessionReadOnly = false
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

    private fun leaveChat() {
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
            LogoutFlow(store) { deviceId, accessToken ->
                PairingRepository(BASE_URL).revokeCredential(deviceId, accessToken)
            }.logout()
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
    private fun requestReauthentication() {
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

    private fun dp(value: Int) = MarsTheme.dp(this, value)

    /**
     * Tinted vector chevron at the start of a toggle button — replaces the triangle arrow text
     * glyphs (font-dependent rendering across OEM skins, same anti-pattern as emojis).
     */
    private fun Button.chevronStart(drawableRes: Int, tint: Int) {
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

        /** Lightbox guards: payload cap and max decoded dimension (memory discipline). */
        private const val MAX_VIEWER_IMAGE_BYTES = 25L * 1024 * 1024
        private const val MAX_VIEWER_IMAGE_DIMENSION = 4096
        /** Ключ savedInstanceState: диалог, открытый в момент пересоздания экрана. */
        private const val KEY_OPEN_SESSION_ID = "open_session_id"
        /** Privately-stored plaintext data cleared on every auth-exit path. */
        internal const val DRAFTS_PREFS = "mars-session-drafts"
        private const val MUSIC_HISTORY_PREFS = "mars-music-search-history"
        /**
         * Приватные хранилища офлайн-устойчивости: кэш сессий и очередь неотправленных.
         * internal: уровень списка диалогов ([ChatSessionsActivity]) читает тот же кэш
         * и стирает черновики/очередь удалённых диалогов (ключи не дублируются).
         */
        internal const val OFFLINE_CACHE_PREFS = "mars-offline-cache"
        internal const val PENDING_QUEUE_PREFS = "mars-pending-messages"
        /** Подсказки статуса для офлайн-сценариев (копия из кэша / вложения / лимит очереди). */
        private const val OFFLINE_SESSION_HINT = "Нет связи — показана сохранённая копия диалога"
        private const val OFFLINE_ATTACHMENT_STATUS = "Нет связи — сообщение с вложениями будет отправлено после восстановления"
        private const val MESSAGE_NOT_DELIVERED_STATUS = "Сообщение из очереди не доставлено — лимит попыток исчерпан"
        /** Status line while a reattach to the session's live stream is being established. */
        private const val REATTACH_STATUS = "Подключение к активному ходу…"
        /** Recovery-цикл переподключается к доставленному ходу (по Last-Event-ID). */
        private const val STREAM_RECONNECT_STATUS = "Связь прервана — восстанавливаю поток…"
        /** Recovery-цикл припаркован до возврата сети (офлайн-парковка). */
        private const val STREAM_PARKED_OFFLINE_STATUS = "Нет связи — поток продолжится автоматически"
        /** Приватное хранилище режима автоозвучивания ответов (голосовой вывод). */
        private const val VOICE_OUTPUT_PREFS = "mars-voice-output"
        private const val KEY_AUTO_SPEAK = "auto_speak"
        /** TTS-движок отсутствует/сломался — статус вместо краша и ретраев. */
        private const val VOICE_OUTPUT_UNAVAILABLE = "Озвучивание недоступно на этом устройстве"
    }
}
