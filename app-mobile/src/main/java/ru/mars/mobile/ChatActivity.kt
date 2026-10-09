package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.mobile.domain.util.SpeechContentFilter
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
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

class ChatActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var statusView: TextView
    private val uiState = ChatUiState()

    private lateinit var interactiveView: MarsInteractiveView
    private lateinit var interactiveController: InteractiveRequestController

    /** Transcript auto-scroll decisions (pure policy): a reader scrolled up must survive SSE tokens. */
    private var autoScroll = AutoScrollPolicy()
    /** Scroll-to-bottom FAB visibility (pure policy): hysteresis over the distance from the tail. */
    private var scrollToBottomPolicy = ScrollToBottomPolicy()
    private var scrollToBottomFab: ImageButton? = null
    /** Stream-recovery decisions (pure policy): reattach plan + dead-stream (404) detection. */
    private val reattachPolicy = StreamReattachPolicy()
    private var stream: Job? = null
    private var currentSessionId: String? = null
    private var currentStreamId: String? = null
    /** Черновики несент-сообщений по диалогам: чистая политика [SessionDraftController]. */
    private lateinit var drafts: SessionDraftController
    /** Guard асинхронной навигации по диалогам (pure): побеждает последняя загрузка. */
    private val sessionNavigation = SessionNavigationPolicy()
    /** Сохранение открытого диалога при пересоздании экрана (pure policy). */
    private val recreationPolicy = SessionRecreationPolicy()
    /** Одноразовая очистка локальных plaintext-данных при выходе из авторизации. */
    private lateinit var authExitCleanup: AuthExitCleanup

    private val firstSessionFlow = FirstSessionFlow(SessionRepository(), BASE_URL)
    private var explicitStatus: String? = null
    private lateinit var modelSelection: ModelSelection
    private var modelBtn: Button? = null
    private lateinit var reasoningSelection: ReasoningEffortSelection
    private var reasoningBtn: Button? = null
    private lateinit var activityModeSelection: ActivityModeSelection
    private var activityModeBtn: Button? = null
    private lateinit var workspaceSelection: WorkspaceSelection
    private var workspaceBtn: Button? = null
    private var yoloBtn: Button? = null
    private var contextIndicator: TextView? = null
    private lateinit var compressionController: ContextCompressionController
    private lateinit var autoCompressionStorage: AutoCompressionStorage
    private var compressionRunning = false
    private lateinit var attachmentChips: LinearLayout
    private val attachments = mutableListOf<ChatAttachment>()
    private var speech: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private val speechFilter = SpeechContentFilter()
    private lateinit var voiceSession: VoiceSessionController
    private var voiceBtn: ImageButton? = null
    private val pickAttachment = 4101
    private val recognizeVoice = 4102

    // Mini-player in chat
    private lateinit var miniPlayerBox: LinearLayout
    private lateinit var miniPlayerTitle: TextView
    private lateinit var miniPlayerPlayBtn: ImageButton
    private lateinit var quickChipsScroll: HorizontalScrollView
    private lateinit var smartChipsRow: HorizontalScrollView

    // Sessions side panel (DrawerLayout)
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerSessionList: LinearLayout
    private lateinit var drawerFilter: EditText
    private var drawerFilterQuery: String = ""

    /**
     * Reveals delegated subagent children in the drawer (WebUI parity
     * #5244/#5305: they are hidden from the main list by default).
     */
    private var drawerShowSubagents: Boolean = false
    private var drawerSubagentToggle: Button? = null

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
            updateYoloPill()
            Toast.makeText(this, if (enabled) "YOLO включён — Марс работает без подтверждений" else "YOLO выключен", Toast.LENGTH_SHORT).show()
        }
        modelSelection = ModelSelection(PrefsModelChoiceStorage(getSharedPreferences("mars-model-choice", MODE_PRIVATE)))
        reasoningSelection = ReasoningEffortSelection(PrefsReasoningChoiceStorage(getSharedPreferences("mars-reasoning-choice", MODE_PRIVATE)))
        activityModeSelection = ActivityModeSelection(PrefsActivityModeStorage(getSharedPreferences("mars-activity-mode", MODE_PRIVATE)))
        workspaceSelection = WorkspaceSelection(PrefsWorkspaceChoiceStorage(getSharedPreferences("mars-workspace-choice", MODE_PRIVATE)))
        autoCompressionStorage = PrefsAutoCompressionStorage(getSharedPreferences("mars-auto-compression", MODE_PRIVATE))
        drafts = SessionDraftController(PrefsSessionDraftStore(getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE)))
        // Выход из авторизации (logout/реавторизация) обязан стереть локальные plaintext-данные
        // до finish(), и после этого черновики больше не сохраняются (onPause не пишет назад).
        authExitCleanup = AuthExitCleanup(
            listOf(
                PrefsClearableStore(getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE)),
                PrefsClearableStore(getSharedPreferences(MUSIC_HISTORY_PREFS, MODE_PRIVATE)),
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
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = java.util.Locale.getDefault() }
        // Voice-input state machine: guards the recognizer session, delivers haptic feedback on
        // start/finish and routes results into the composer. Pure logic lives in the controller.
        voiceSession = VoiceSessionController(object : VoiceSessionController.Listener {
            override fun onListeningStarted() {
                voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                statusView.text = "Слушаю…"
            }

            override fun onResult(text: String) {
                input.setText(text)
                input.setSelection(input.length())
            }

            override fun onError(message: String) {
                statusView.text = message
            }
        })
        setContentView(buildView())
        // При старте/пересоздании экрана композитор сразу получает черновик текущего диалога.
        restoreComposerDraft()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        MarsMusicPlayer.registerListener(this)
        loadSessions()
        refreshModelCatalog()
        refreshReasoningStatus()
        loadWorkspaces()
        // Пересоздание экрана (поворот/тема/смерть процесса): открытый диалог восстанавливается
        // из savedInstanceState; на холодном старте savedInstanceState == null → новый чат.
        restoreOpenSession(savedInstanceState)
    }

    override fun onDestroy() {
        if (::interactiveController.isInitialized) interactiveController.destroy()
        if (::compressionController.isInitialized) compressionController.destroy()
        if (::interactiveView.isInitialized) interactiveView.dismissAllNow()
        MarsMusicPlayer.unregisterListener(this)
        stream?.cancel()
        speech?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateMiniPlayer(MarsMusicPlayer.currentTrack, MarsMusicPlayer.isPlaying)
    }

    override fun onPause() {
        // Черновик текущего диалога сохраняется до потери фокуса/пересоздания экрана.
        persistComposerDraft()
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
        if (track == null) {
            miniPlayerBox.visibility = View.GONE
        } else {
            miniPlayerBox.visibility = View.VISIBLE
            miniPlayerTitle.text = "${track.title} · ${track.artist}"
            miniPlayerPlayBtn.setImageDrawable(
                ContextCompat.getDrawable(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            )
        }
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
        drawer.addView(buildSessionsDrawer(), DrawerLayout.LayoutParams(dp(310), -1, GravityCompat.START))
        return drawer
    }

    /** Sessions side panel */
    private fun buildSessionsDrawer(): View {
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
            text = "Сессии"
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

        val newChatBtn = Button(this).apply {
            text = "+ Новый диалог"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = MarsTheme.button(this@ChatActivity, MarsTheme.ACCENT, radiusDp = 12)
            setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                startNewChat()
            }
        }
        panel.addView(newChatBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        // Search container
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE, radiusDp = 12)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val searchIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_search))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dp(18), dp(18)))

        drawerFilter = EditText(this).apply {
            hint = "Поиск диалогов…"
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 14f
            background = null
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    drawerFilterQuery = s?.toString().orEmpty()
                    renderSessionList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchBox.addView(drawerFilter, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Subagent visibility toggle: delegated children are hidden from the
        // main list by default (WebUI parity #5244/#5305); the toggle reveals
        // them marked with a «субагент» badge.
        drawerSubagentToggle = Button(this).apply {
            textSize = 11f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isAllCaps = false
            setOnClickListener {
                drawerShowSubagents = !drawerShowSubagents
                renderSessionList()
            }
        }
        panel.addView(drawerSubagentToggle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val quickRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(
            "Проекты" to { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) },
            "Музыка" to { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        ).forEach { (title, action) ->
            quickRow.addView(Button(this).apply {
                text = title
                textSize = 12f
                setTextColor(MarsTheme.TEXT)
                background = MarsTheme.button(this@ChatActivity, MarsTheme.SURFACE, radiusDp = 8, strokeColor = MarsTheme.BORDER)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    action()
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        }
        panel.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        drawerSessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { addView(drawerSessionList, ViewGroup.LayoutParams(-1, -2)) }
        panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        renderSessionList()
        return panel
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
            "Сессии",
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val brandRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logoDot = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_mars))
        }
        brandRow.addView(logoDot, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        brandRow.addView(TextView(this).apply {
            text = "МАРС"
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        })
        titleBox.addView(brandRow)

        modelBtn = Button(this).apply {
            text = modelSelection.buttonLabel() ?: "Модель"
            textSize = 11f
            contentDescription = "Модель"
            background = null
            setPadding(0, 0, 0, 0)
            setTextColor(MarsTheme.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            setOnClickListener { chooseModel() }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(modelBtn, LinearLayout.LayoutParams(-2, -2))
        reasoningBtn = Button(this).apply {
            text = "· Авто"
            textSize = 11f
            contentDescription = "Уровень размышлений"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            // Hidden until the server says this model has an effort ladder (unknown state is safe).
            visibility = View.GONE
            setOnClickListener { chooseReasoningEffort() }
        }
        titleRow.addView(reasoningBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginStart = dp(8) })
        activityModeBtn = Button(this).apply {
            text = "· ${activityModeSelection.buttonLabel()}"
            textSize = 11f
            contentDescription = "Режим активности"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { chooseActivityMode() }
        }
        titleRow.addView(activityModeBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginStart = dp(8) })
        workspaceBtn = Button(this).apply {
            text = workspaceSelection.buttonLabel()?.let { "· $it" } ?: "· Workspace"
            textSize = 11f
            contentDescription = "Рабочее пространство"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            isSingleLine = true
            maxEms = 14
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setOnClickListener { chooseWorkspace() }
        }
        titleRow.addView(workspaceBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginStart = dp(8) })
        yoloBtn = Button(this).apply {
            text = "YOLO"
            textSize = 11f
            contentDescription = "Режим YOLO — автономная работа без подтверждений"
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { interactiveController.setYoloEnabled(!uiState.yoloEnabled) }
        }
        titleRow.addView(yoloBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginStart = dp(6) })
        updateYoloPill()
        titleBox.addView(titleRow)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val musicBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_music,
            "Музыка",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        }
        header.addView(musicBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })

        val projectsBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_projects,
            "Проекты",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) }
        }
        header.addView(projectsBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })

        val logoutBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_logout,
            "Выйти",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { leaveChat() }
        }
        header.addView(logoutBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        root.addView(header)

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
        miniPlayerBox.addView(miniPlayerPlayBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

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

        // Floating Glass Composer Bar
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            background = MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE, radiusDp = 22, strokeColor = MarsTheme.BORDER)
        }

        input = EditText(this).apply {
            hint = "Сообщение Марсу…"
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 15f
            maxLines = 4
            isSingleLine = false
            background = null
            setPadding(dp(8), dp(6), dp(8), dp(6))
            imeOptions = 4
            setOnEditorActionListener { _, action, _ ->
                if (action == 4) { sendMessage(); true } else false
            }
        }
        // Context-window fill pill (WebUI parity with the composer ring): percent +
        // used/total tokens from the stream's `metering` frames; hidden until known.
        contextIndicator = TextView(this).apply {
            textSize = 10f
            background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            visibility = View.GONE
            setOnClickListener { showContextUsageDetails() }
        }
        composer.addView(contextIndicator, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        val attachBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_attach,
            "Прикрепить файл",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 38,
            strokeColor = 0
        ).apply {
            setOnClickListener { chooseAttachment() }
        }
        composer.addView(attachBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val voiceButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_mic,
            "Голосовой ввод",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 38,
            strokeColor = 0
        ).apply {
            setOnClickListener { startVoiceInput() }
        }
        voiceBtn = voiceButton
        composer.addView(voiceButton, LinearLayout.LayoutParams(dp(38), dp(38)))

        val volumeBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_volume,
            "Озвучить ответ",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 38,
            strokeColor = 0
        ).apply {
            setOnClickListener { speakLastMessage() }
        }
        composer.addView(volumeBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        sendButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_send,
            "Отправить",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 40,
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
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })

        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return root
    }

    private fun quickSend(text: String) {
        input.setText(text)
        sendMessage()
    }

    private fun sendMessage() {
        if (uiState.status == ChatStatus.TYPING) { steerMessage(); return }
        val text = input.text.toString().trim()
        val outgoingAttachments = attachments.toList()
        if (!uiState.begin(text, outgoingAttachments.map { it.name })) return
        attachments.clear()
        renderAttachmentChips()
        val credential = store.read() ?: run { uiState.cancel(); leaveChat(); return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.text.clear()
        // Отправка принята локально (текст ушёл в поток) — черновик диалога очищается.
        drafts.onSendAccepted(currentSessionId)
        explicitStatus = null
        // Own outgoing message always takes the reader to the latest.
        autoScroll.pinToBottom()
        renderMessages()
        updateControls()
        stream = lifecycleScope.launch {
            var credentialRejected = false
            try {
                if (currentSessionId == null) {
                    explicitStatus = "Создаю сессию…"
                    setStatus()
                }
                when (val ensured = firstSessionFlow.ensureSession(currentSessionId, credential)) {
                    is EnsureSessionResult.Ready -> {
                        currentSessionId = ensured.sessionId
                        explicitStatus = null
                    }
                    is EnsureSessionResult.Failed -> {
                        uiState.cancel()
                        // A 401 here means the stored credential is dead server-side: route
                        // to login instead of leaving the user retrying the same failure.
                        if (AuthRejectionPolicy.isCredentialRejected(ensured.message)) {
                            requestReauthentication()
                        } else {
                            explicitStatus = "Сессия не создана: ${ensured.message}"
                        }
                        return@launch
                    }
                }
                val choice = modelSelection.current
                // WebUI parity: the wire message carries the `[Workspace::v1: <path>]`
                // sentinel; the transcript above keeps showing the raw text.
                val wireText = workspaceSelection.outgoingMessage(text)
                ChatRepository().sendMessage(
                    BASE_URL, credential, wireText, currentSessionId, outgoingAttachments,
                    model = choice?.modelId,
                    modelProvider = choice?.providerId,
                    explicitModelPick = choice?.explicitPick == true,
                    onTodoState = todoCallback(currentSessionId)
                ).collect { event ->
                    uiState.onEvent(event)
                    if (event is ChatEvent.Error && AuthRejectionPolicy.isCredentialRejected(event.message)) credentialRejected = true
                    if (event is ChatEvent.SessionBound) {
                        currentSessionId = event.sessionId
                        refreshYoloState()
                    }
                    if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                    if (event is ChatEvent.ContextUsage) updateContextIndicator()
                    if (event is ChatEvent.SteerLeftover) restoreSteerText(event.text)
                    interactiveController.onEvent(event)
                    if (::compressionController.isInitialized) compressionController.onEvent(event)
                    renderMessages()
                    setStatus()
                }
                if (credentialRejected) { requestReauthentication(); return@launch }
                if (uiState.status == ChatStatus.TYPING) uiState.complete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                uiState.fail()
                if (credentialRejected) { requestReauthentication(); return@launch }
            } finally {
                renderMessages()
                setStatus()
                updateControls()
            }
        }
    }

    private fun cancelStream() {
        val credential = store.read()
        val id = currentStreamId
        if (credential != null && id != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ChatRepository().executeControl(ChatRepository().cancelRequest(BASE_URL, id, credential.accessToken)) }
            }
        }
        stream?.cancel()
        stream = null
        currentStreamId = null
        interactiveController.dismissAll()
        uiState.cancel()
        explicitStatus = null
        setStatus()
        updateControls()
        renderMessages()
    }

    private fun steerMessage() {
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
    }

    private fun todoCallback(sessionId: String?): (ru.mars.network.model.TodoSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            if ((sessionId == null || currentSessionId == sessionId) && (snapshot.sessionId == null || currentSessionId == snapshot.sessionId)) {
                uiState.setTodoState(snapshot)
                setStatus()
            }
        }
    }

    private fun setStatus() {
        val quiet = uiState.activityMode == ActivityDisplayMode.STREAM_QUIET
        statusView.text = explicitStatus ?: when {
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
    }

    /** Human banner for the session's server-side attention (WebUI `_session_attention_summary` parity). */
    private fun attentionLabel(attention: SessionAttention): String {
        val kind = when (attention.kind) {
            "approval" -> "подтверждения"
            "clarify" -> "уточнения"
            else -> attention.kind
        }
        val count = if (attention.count > 1) " ×${attention.count}" else ""
        val prefix = if (attention.severity == "critical") "❗ " else ""
        return "${prefix}Ожидает $kind$count"
    }

    private fun phaseLabel(value: String) = when {
        value.contains("tool", true) || value.contains("command", true) -> "Выполняет команду…"
        value.contains("think", true) || value.contains("reason", true) -> "Анализирует задачу…"
        else -> value
    }

    private fun renderMessages() {
        // Take the auto-scroll decision from the PRE-render viewport (synchronously, before any
        // mutation): a user scrolled up reading history stays put while SSE tokens keep coming;
        // only a pinned viewport follows the stream to the bottom.
        autoScroll.onViewportDistanceFromBottom(distanceFromBottomPx())
        scrollToBottomPolicy.onViewportDistanceFromBottom(distanceFromBottomPx())
        messages.removeAllViews()
        uiState.messages.forEachIndexed { index, message ->
            val isUser = message.role == "user"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (isUser) {
                    MarsTheme.card(this@ChatActivity, MarsTheme.USER_BUBBLE, radiusDp = 16, strokeColor = MarsTheme.USER_BORDER)
                } else {
                    MarsTheme.card(this@ChatActivity, MarsTheme.BOT_CARD, radiusDp = 16, strokeColor = MarsTheme.BOT_BORDER)
                }
            }

            val labelRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (!isUser) {
                val dot = TextView(this).apply {
                    text = "● "
                    setTextColor(MarsTheme.STATUS_ONLINE)
                    textSize = 10f
                }
                labelRow.addView(dot)
            }
            val label = TextView(this).apply {
                text = if (isUser) "Вы" else "Марс"
                setTextColor(MarsTheme.TEXT_MUTED)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            labelRow.addView(label)
            card.addView(labelRow)

            if (message.attachments.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = message.attachments.joinToString("\n") { "[Файл: $it]" }
                    setTextColor(MarsTheme.TEXT_MUTED)
                    textSize = 12f
                    setPadding(0, dp(4), 0, 0)
                })
            }

            if (message.text.isNotBlank()) {
                renderMarkdownBlocks(message.text, card, isUser, index)
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
                    setOnClickListener { copyMessage(message.text) }
                }
                actionsRow.addView(copyBtn)
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
                        setOnClickListener { speakMessage(message.text) }
                    }
                    actionsRow.addView(speakBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) })
                }
                card.addView(actionsRow)
            }

            if (message.thoughts.isNotEmpty()) {
                val thoughtBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = MarsTheme.card(this@ChatActivity, 0x143b82f6.toInt(), radiusDp = 10, strokeColor = 0x333b82f6.toInt())
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val toggle = Button(this).apply {
                    text = "${if (message.thoughtsExpanded) "▲" else "▼"}  Мысли Марса"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(MarsTheme.ACCENT_TEXT)
                    background = null
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    minimumHeight = 0
                    minHeight = 0
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { uiState.toggleThoughts(index); renderMessages() }
                }
                thoughtBox.addView(toggle, LinearLayout.LayoutParams(-1, dp(26)))
                if (message.thoughtsExpanded) {
                    thoughtBox.addView(TextView(this).apply {
                        text = message.thoughts.joinToString("\n")
                        setTextColor(MarsTheme.TEXT_EM)
                        textSize = 13f
                        setTypeface(null, Typeface.ITALIC)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(0, dp(4), 0, 0)
                    })
                    thoughtBox.addView(Button(this).apply {
                        text = "Копировать ход мыслей"
                        textSize = 11f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        background = null
                        setPadding(0, dp(2), 0, 0)
                        gravity = Gravity.START
                        setOnClickListener { copyMessage(message.thoughts.joinToString("\n")) }
                    })
                }
                card.addView(thoughtBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }

            message.tools.forEachIndexed { toolIndex, tool ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = MarsTheme.card(this@ChatActivity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.CODE_BORDER)
                    setPadding(dp(10), dp(6), dp(8), dp(6))
                }
                val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val toggle = Button(this).apply {
                    text = "${if (tool.expanded) "▲" else "▼"}  ${tool.name}"
                    textSize = 12f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(MarsTheme.ACCENT_TEXT)
                    background = null
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    minimumHeight = 0
                    minHeight = 0
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { uiState.toggleTool(index, toolIndex); renderMessages() }
                }
                header.addView(toggle, LinearLayout.LayoutParams(0, dp(30), 1f))
                val done = tool.result != null
                header.addView(TextView(this).apply {
                    text = if (done) "Готово" else "Выполняется…"
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(if (done) MarsTheme.STATUS_ONLINE else MarsTheme.TEXT_MUTED)
                    setPadding(dp(6), 0, dp(6), 0)
                })
                val copyToolBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_copy,
                    "Копировать инструмент",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 28,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { copyMessage(toolDump(tool)) }
                }
                header.addView(copyToolBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
                box.addView(header, LinearLayout.LayoutParams(-1, -2))
                if (tool.expanded) {
                    val body = TextView(this).apply {
                        text = toolDump(tool)
                        setTextColor(MarsTheme.TEXT)
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setPadding(dp(2), dp(4), dp(8), dp(6))
                    }
                    box.addView(HorizontalScrollView(this).apply { addView(body) })
                }
                card.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }

            val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .88).toInt(), -2).apply {
                topMargin = dp(8)
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            messages.addView(card, params)
        }
        renderSmartChips()
        if (autoScroll.shouldScrollOnContentChanged()) scrollToBottom() else syncScrollToBottomFab()
    }

    private fun renderMarkdownBlocks(value: String, parent: LinearLayout, user: Boolean, messageIndex: Int) {
        val blocks = MarkdownFormatter.parseBlocks(value)
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
                                ssb.append("▎ ")
                                appendInlineSpans(ssb, line.spans)
                                tv.setTypeface(null, Typeface.ITALIC)
                            }
                            is RichLine.Normal -> {
                                appendInlineSpans(ssb, line.spans)
                            }
                            is RichLine.Divider -> {
                                ssb.append("─".repeat(24))
                                tv.setTextColor(MarsTheme.BORDER)
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
                            setTextColor(0xffe6edf3.toInt())
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            setPadding(0, dp(4), dp(16), dp(8))
                        })
                    })
                    // Long listings are collapsed by default; the toggle flips the state.
                    val toggleLabel = view.toggleLabel
                    if (toggleLabel != null) {
                        box.addView(Button(this).apply {
                            text = "${if (view.expanded) "▲" else "▼"}  $toggleLabel"
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(MarsTheme.ACCENT_TEXT)
                            background = null
                            isAllCaps = false
                            setPadding(0, 0, 0, 0)
                            minimumHeight = 0
                            minHeight = 0
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            setOnClickListener {
                                uiState.toggleCodeBlock(messageIndex, blockIndex)
                                renderMessages()
                            }
                        }, LinearLayout.LayoutParams(-1, dp(26)))
                    }
                    parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
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
                    builder.setSpan(BackgroundColorSpan(0x33283143.toInt()), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Link -> {
                    builder.append(span.title)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(span.url))) }
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun toolDump(tool: ToolExecution): String =
        "Параметры:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"

    private fun renderSessionList() {
        if (!::drawerSessionList.isInitialized) return
        drawerSessionList.removeAllViews()
        // Delegated subagent children are hidden from the main list by default
        // (WebUI parity #5244/#5305); the toggle reveals them, and the
        // currently-open session always stays visible.
        val subagentCount = uiState.sessions.count { it.isSubagent }
        drawerSubagentToggle?.apply {
            text = if (drawerShowSubagents) "Субагенты: $subagentCount ✓" else "Субагенты: скрыты ($subagentCount)"
            setTextColor(if (drawerShowSubagents) MarsTheme.TEXT_STRONG else MarsTheme.TEXT_MUTED)
            background = MarsTheme.button(
                this@ChatActivity,
                if (drawerShowSubagents) MarsTheme.SURFACE_CARD else MarsTheme.SURFACE,
                radiusDp = 8,
                strokeColor = if (drawerShowSubagents) MarsTheme.ACCENT else MarsTheme.BORDER_SUBTLE,
            )
            visibility = if (subagentCount > 0 || drawerShowSubagents) View.VISIBLE else View.GONE
        }
        val filtered = uiState.drawerSessions(drawerShowSubagents, currentSessionId)
            .filter { "${it.title.orEmpty()} ${it.preview.orEmpty()}".contains(drawerFilterQuery, true) }
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
        if (filtered.isEmpty()) {
            drawerSessionList.addView(TextView(this).apply {
                text = "Нет подходящих сессий"
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }
        filtered.forEach { session ->
            val isCurrent = session.id == currentSessionId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isCurrent) {
                    MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.ACCENT)
                } else {
                    MarsTheme.card(this@ChatActivity, MarsTheme.SURFACE, radiusDp = 12, strokeColor = MarsTheme.BORDER_SUBTLE)
                }
                setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply {
                text = session.title ?: "Новый диалог"
                textSize = 14f
                setTextColor(MarsTheme.TEXT_STRONG)
                background = null
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    loadSession(session)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))

            // Rename/delete only for manageable rows; the server `read_only`
            // flag is the single authority (pure policy in SessionRowActions).
            if (SessionRowActions.canManage(session)) {
                val renameBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_edit,
                    "Переименовать",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 32,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { renameSession(session) }
                }
                row.addView(renameBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

                val deleteBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_trash,
                    "Удалить",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.STATUS_ERROR,
                    sizeDp = 32,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { deleteSession(session) }
                }
                row.addView(deleteBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            card.addView(row)

            if (!session.preview.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = session.preview
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), dp(2))
                })
            }

            // Subagent marker for revealed delegated children (read-only on the
            // server; hidden from the main list by default).
            if (session.isSubagent) {
                card.addView(TextView(this).apply {
                    text = buildString {
                        append("↳ ")
                        append(session.sourceLabel?.takeIf { it.isNotBlank() } ?: "субагент")
                        if (session.readOnly) append(" · только чтение")
                    }
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), 0)
                })
            }

            // Active-turn/attention badge: which sessions are live or waiting for the user.
            val turn = session.activeTurn
            val turnAttention = turn.attention
            val badge: Pair<String, Int>? = when {
                turnAttention != null -> "⚠ ${attentionLabel(turnAttention)}" to MarsTheme.STATUS_WARNING
                turn.isStreaming -> "● Активный ход" to MarsTheme.STATUS_ONLINE
                turn.hasPendingUserMessage -> "↑ Сообщение в очереди" to MarsTheme.STATUS_INFO
                else -> null
            }
            if (badge != null) {
                card.addView(TextView(this).apply {
                    text = badge.first
                    textSize = 11f
                    setTextColor(badge.second)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), 0)
                })
            }
            drawerSessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun loadSessions() {
        val c = store.read() ?: return
        lifecycleScope.launch {
            uiState.setSessions(SessionRepository().getSessions(BASE_URL, c))
            renderSessionList()
        }
    }

    private fun loadSession(session: SessionItem) = loadSessionById(session.id)

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
        lifecycleScope.launch {
            val detail = SessionRepository().getSessionDetail(BASE_URL, c, sessionId)
            if (detail != null && sessionNavigation.isCurrent(token)) {
                // Текст, набранный ВО ВРЕМЯ загрузки, сохраняется в черновик старого диалога
                // непосредственно перед присвоением нового session_id.
                drafts.onSessionSwitch(currentSessionId, detail.id, input.text.toString())
                uiState.bindSession(detail.id, detail.title, historyMessages(detail))
                // Active-turn/attention banner state from the freshly loaded detail.
                uiState.setActiveTurn(detail.activeTurn)
                uiState.setTodoState(detail.todoState)
                currentSessionId = detail.id
                restoreComposerDraft()
                autoScroll.pinToBottom()
                renderMessages()
                setStatus()
                refreshYoloState()
                // Stream recovery: a session that is still streaming server-side must resume
                // live instead of showing a frozen transcript.
                reattachActiveTurn(detail.activeTurn)
            }
        }
    }

    /**
     * Восстановление открытого диалога после пересоздания Activity (поворот, тёмная тема,
     * смерть процесса): чистая политика [SessionRecreationPolicy] решает по savedInstanceState,
     * открывать ли сохранённый session_id заново. Загрузка идёт через общий guard
     * [sessionNavigation], а одноразовость политики исключает повторное восстановление
     * в этом же экземпляре экрана. Холодный старт (savedInstanceState == null) — новый чат.
     */
    private fun restoreOpenSession(savedInstanceState: Bundle?) {
        val plan = recreationPolicy.planFor(savedInstanceState?.getString(KEY_OPEN_SESSION_ID))
        if (plan is RecreationPlan.Reopen) loadSessionById(plan.sessionId)
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
        val credential = store.read() ?: return
        if (!uiState.beginReattachedTurn()) return
        autoScroll.pinToBottom()
        currentStreamId = plan.streamId
        explicitStatus = REATTACH_STATUS
        renderMessages()
        setStatus()
        updateControls()
        val sessionId = currentSessionId
        stream = lifecycleScope.launch {
            var streamGone = false
            var credentialRejected = false
            try {
                ChatRepository().attachToStream(BASE_URL, credential, plan.streamId,
                    onTodoState = todoCallback(sessionId)).collect { event ->
                    if (reattachPolicy.outcomeOf(event) == ReattachEventOutcome.StreamGone) {
                        streamGone = true
                        return@collect
                    }
                    uiState.onEvent(event)
                    if (event is ChatEvent.Error && AuthRejectionPolicy.isCredentialRejected(event.message)) credentialRejected = true
                    if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                    if (event is ChatEvent.ContextUsage) updateContextIndicator()
                    if (event is ChatEvent.SteerLeftover) restoreSteerText(event.text)
                    interactiveController.onEvent(event)
                    if (::compressionController.isInitialized) compressionController.onEvent(event)
                    renderMessages()
                    setStatus()
                }
                if (credentialRejected) {
                    requestReauthentication()
                    return@launch
                }
                if (streamGone) {
                    // The stream died server-side (404): drop the empty placeholder and show
                    // what actually settled in the transcript.
                    uiState.cancelReattachedTurn()
                    explicitStatus = "Ход завершён — история обновлена"
                    if (currentSessionId == sessionId) reloadCurrentSession()
                } else if (uiState.status == ChatStatus.TYPING) {
                    uiState.complete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                uiState.fail()
                if (credentialRejected) { requestReauthentication(); return@launch }
            } finally {
                if (explicitStatus == REATTACH_STATUS) explicitStatus = null
                renderMessages()
                setStatus()
                updateControls()
            }
        }
    }

    /** Maps a persisted session transcript to bubbles (shared by loadSession and post-compression reload). */
    private fun historyMessages(detail: SessionDetail): List<ChatMessage> = detail.messages.map {
        ChatMessage(if (it.role == "user") "user" else "assistant", it.content, timestamp = System.currentTimeMillis())
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
                uiState.setActiveTurn(detail.activeTurn)
                uiState.setTodoState(detail.todoState)
                autoScroll.pinToBottom()
                renderMessages()
                refreshYoloState()
            }
        }
    }

    private fun deleteSession(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            if (SessionRepository().deleteSession(BASE_URL, c, session.id)) {
                drafts.onSessionRemoved(session.id)
                uiState.setSessions(uiState.sessions.filterNot { it.id == session.id })
                if (currentSessionId == session.id) {
                    // Удаление текущего диалога: его текст не должен «уезжать» в новый чат —
                    // композитор переключается на черновик нового чата, а pending-загрузка
                    // удалённой сессии инвалидируется (её callback уже не применится).
                    sessionNavigation.invalidatePending()
                    interactiveController.dismissAll()
                    compressionController.onSessionChanged()
                    uiState.startNewSession()
                    currentSessionId = null
                    restoreComposerDraft()
                }
                renderSessionList()
            }
        }
    }

    private fun renameSession(session: SessionItem) {
        val c = store.read() ?: return
        val nameInput = EditText(this).apply { setText(session.title.orEmpty()); setSingleLine(true); setTextColor(Color.WHITE) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Переименовать диалог")
            .setView(nameInput)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = nameInput.text.toString().trim()
                if (title.isNotBlank()) {
                    lifecycleScope.launch {
                        if (SessionRepository().renameSession(BASE_URL, c, session.id, title)) {
                            loadSessions()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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

    /** Speak a message through TTS after cleaning markdown with [SpeechContentFilter]. */
    private fun speakMessage(text: String) {
        val speakable = speechFilter.filterForSpeech(text)
        if (speakable.isBlank()) {
            statusView.text = "В сообщении нет текста для озвучивания"
            return
        }
        tts.speak(speakable, TextToSpeech.QUEUE_FLUSH, null, "mars-message")
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

    private fun renderAttachmentChips() {
        attachmentChips.removeAllViews()
        attachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachments.forEachIndexed { i, a ->
            attachmentChips.addView(Button(this).apply {
                text = "${a.name}  ✕"
                textSize = 11f
                setTextColor(Color.WHITE)
                background = MarsTheme.pill(this@ChatActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.ACCENT)
                setOnClickListener { attachments.removeAt(i); renderAttachmentChips() }
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
            val checked = entries.indexOfFirst { it.model.id == modelSelection.current?.modelId }
            android.app.AlertDialog.Builder(this@ChatActivity)
                .setTitle("Модель Марса")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    val picked = modelSelection.select(entries[which].model)
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
     * server-driven (`GET /api/reasoning` → `supported_efforts` for the active model);
     * an explicit pick is persisted server-side via `POST /api/reasoning` and only then
     * recorded locally — `chat/start` itself carries NO reasoning field: the server
     * resolves the effort per turn from its own session/override/config state.
     */
    private fun chooseReasoningEffort() {
        val c = store.read()
        if (c == null) { statusView.text = "Уровень размышлений недоступен: нет авторизации"; return }
        lifecycleScope.launch {
            statusView.text = "Загружаю уровни размышлений…"
            val model = modelSelection.current
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
            val model = modelSelection.current
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
            val model = modelSelection.current
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
     */
    private fun updateContextIndicator() {
        val view = contextIndicator ?: return
        val usage = uiState.contextUsage
        // In-flight compression job: the pill becomes the progress marker (WebUI ctx-ring parity —
        // the ring is locked while a manual compression runs).
        if (compressionRunning) {
            view.visibility = View.VISIBLE
            view.text = "Сжатие…"
            view.setTextColor(MarsTheme.STATUS_WARNING)
            view.contentDescription = "Идёт сжатие контекста"
            return
        }
        if (usage == null || usage.maxTokens <= 0) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = "${usage.percent}% · ${formatTokenCount(usage.usedTokens)}/${formatTokenCount(usage.maxTokens)}"
        view.setTextColor(
            when {
                usage.percent > 75 -> MarsTheme.STATUS_ERROR
                usage.percent > 50 -> MarsTheme.STATUS_WARNING
                else -> MarsTheme.STATUS_ONLINE
            }
        )
        view.contentDescription = "Заполнение контекста: ${usage.percent}%"
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
            text = if (usage != null && usage.maxTokens > 0) {
                "Заполнено: ${usage.percent}%\n" +
                    "Использовано токенов: ${formatTokenCount(usage.usedTokens)}\n" +
                    "Размер окна: ${formatTokenCount(usage.maxTokens)}"
            } else {
                "Заполнение ещё не измерено — данные появятся после первого хода."
            }
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
        stream?.cancel()
        stream = null
        currentStreamId = null
        interactiveController.dismissAll()
        compressionController.onSessionChanged()
        uiState.startNewSession()
        currentSessionId = null
        explicitStatus = null
        restoreComposerDraft()
        autoScroll.pinToBottom()
        renderMessages()
        setStatus()
        updateControls()
        updateYoloPill()
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

    // --- YOLO mode ------------------------------------------------------------------------------

    /** Header pill: accent + «YOLO ●» while the session runs autonomously, muted while off. */
    private fun updateYoloPill() {
        val btn = yoloBtn ?: return
        val on = uiState.yoloEnabled
        btn.text = if (on) "YOLO ●" else "YOLO"
        btn.setTextColor(if (on) Color.WHITE else MarsTheme.TEXT_MUTED)
        btn.background = MarsTheme.pill(
            this,
            if (on) MarsTheme.ACCENT else MarsTheme.SURFACE,
            strokeColor = if (on) MarsTheme.ACCENT else MarsTheme.BORDER,
        )
    }

    /**
     * Re-reads the server-side YOLO flag for [currentSessionId] (GET /api/session/yolo) and
     * projects it into the UI state — web parity with loadSession's `_yoloEnabled` reset+fetch.
     * Silently keeps the flag off when the lookup fails (session-scoped, in-memory server state).
     */
    private fun refreshYoloState() {
        val sid = currentSessionId
        val credential = store.read()
        if (sid == null || credential == null) {
            uiState.setYoloEnabled(false)
            updateYoloPill()
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
                updateYoloPill()
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
        stream?.cancel()
        stream = null
        currentStreamId = null

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
                closeSessionLocally(report)
                restoreComposerDraft()
                // YOLO does not outlive the session being closed; make the pill reflect reality.
                if (report != null) uiState.setYoloEnabled(false)
                updateYoloPill()
            } else {
                // Пользователь ушёл в другой диалог во время финиша — его состояние и
                // композитор не трогаем; черновик закрытой сессии чистим в любом случае.
                drafts.onSessionRemoved(sid)
            }
        }
    }

    private fun closeSessionLocally(report: SessionFinishFlow.Report?) {
        interactiveController.dismissAll()
        uiState.cancel()
        uiState.startNewSession()
        currentSessionId = null
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
    }

    private fun echoClarifyAnswer(answer: String) {
        uiState.messages += ChatMessage("user", answer)
        renderMessages()
    }

    private fun leaveChat() {
        stream?.cancel()
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
        stream?.cancel()
        stream = null
        currentStreamId = null
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

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        /** Ключ savedInstanceState: диалог, открытый в момент пересоздания экрана. */
        private const val KEY_OPEN_SESSION_ID = "open_session_id"
        /** Privately-stored plaintext data cleared on every auth-exit path. */
        private const val DRAFTS_PREFS = "mars-session-drafts"
        private const val MUSIC_HISTORY_PREFS = "mars-music-search-history"
        /** Status line while a reattach to the session's live stream is being established. */
        private const val REATTACH_STATUS = "Подключение к активному ходу…"
    }
}
