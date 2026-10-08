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
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
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
    private var stream: Job? = null
    private var currentSessionId: String? = null
    private var currentStreamId: String? = null

    private val firstSessionFlow = FirstSessionFlow(SessionRepository(), BASE_URL)
    private var explicitStatus: String? = null
    private lateinit var modelSelection: ModelSelection
    private var modelBtn: Button? = null
    private lateinit var attachmentChips: LinearLayout
    private val attachments = mutableListOf<ChatAttachment>()
    private var speech: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private val pickAttachment = 4101
    private val recognizeVoice = 4102

    // Mini-player in chat
    private lateinit var miniPlayerBox: LinearLayout
    private lateinit var miniPlayerTitle: TextView
    private lateinit var miniPlayerPlayBtn: ImageButton
    private lateinit var quickChipsScroll: HorizontalScrollView

    // Sessions side panel (DrawerLayout)
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerSessionList: LinearLayout
    private lateinit var drawerFilter: EditText
    private var drawerFilterQuery: String = ""

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
        modelSelection = ModelSelection(PrefsModelChoiceStorage(getSharedPreferences("mars-model-choice", MODE_PRIVATE)))
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = java.util.Locale.getDefault() }
        setContentView(buildView())

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
    }

    override fun onDestroy() {
        if (::interactiveController.isInitialized) interactiveController.destroy()
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
        if (requestCode == recognizeVoice && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                input.setText(it)
                input.setSelection(input.length())
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
        titleBox.addView(modelBtn)
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
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

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
            "Новый чат" to { startNewChat() }
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

        val voiceBtn = MarsTheme.circleIconButton(
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
        composer.addView(voiceBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

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
        explicitStatus = null
        renderMessages()
        updateControls()
        stream = lifecycleScope.launch {
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
                        explicitStatus = "Сессия не создана: ${ensured.message}"
                        return@launch
                    }
                }
                val choice = modelSelection.current
                ChatRepository().sendMessage(
                    BASE_URL, credential, text, currentSessionId, outgoingAttachments,
                    model = choice?.modelId,
                    modelProvider = choice?.providerId,
                    explicitModelPick = choice?.explicitPick == true
                ).collect { event ->
                    uiState.onEvent(event)
                    if (event is ChatEvent.SessionBound) currentSessionId = event.sessionId
                    if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                    interactiveController.onEvent(event)
                    renderMessages()
                    setStatus()
                    scrollToBottom()
                }
                if (uiState.status == ChatStatus.TYPING) uiState.complete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                uiState.fail()
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
    }

    private fun setStatus() {
        statusView.text = explicitStatus ?: uiState.phase?.let { "Марс: ${phaseLabel(it)}" } ?: when (uiState.status) {
            ChatStatus.TYPING -> "Марс печатает…"
            ChatStatus.RECEIVED -> "Ответ получен"
            ChatStatus.ERROR -> "Ошибка сети"
            else -> ""
        }
    }

    private fun phaseLabel(value: String) = when {
        value.contains("tool", true) || value.contains("command", true) -> "Выполняет команду…"
        value.contains("think", true) || value.contains("reason", true) -> "Анализирует задачу…"
        else -> value
    }

    private fun renderMessages() {
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
                renderMarkdownBlocks(message.text, card, isUser)
                val copyBtn = Button(this).apply {
                    text = "Копировать"
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    background = null
                    setPadding(0, dp(4), 0, 0)
                    gravity = Gravity.START
                    setOnClickListener { copyMessage(message.text) }
                }
                card.addView(copyBtn)
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
        scrollToBottom()
    }

    private fun renderMarkdownBlocks(value: String, parent: LinearLayout, user: Boolean) {
        val blocks = MarkdownFormatter.parseBlocks(value)
        blocks.forEach { block ->
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
                    val code = block.code
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(8), dp(6), dp(6))
                        background = MarsTheme.card(this@ChatActivity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.CODE_BORDER)
                    }
                    val bar = LinearLayout(this).apply { gravity = Gravity.END }
                    if (block.language.isNotEmpty()) {
                        bar.addView(TextView(this).apply {
                            text = block.language
                            textSize = 11f
                            setTextColor(MarsTheme.TEXT_MUTED)
                            setPadding(0, 0, dp(8), 0)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
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
                            copyMessage(code)
                            Toast.makeText(this@ChatActivity, "Код скопирован", Toast.LENGTH_SHORT).show()
                        }
                    }
                    bar.addView(copyCodeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
                    box.addView(bar)
                    box.addView(HorizontalScrollView(this).apply {
                        addView(TextView(this@ChatActivity).apply {
                            text = code
                            setTextColor(0xffe6edf3.toInt())
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            setPadding(0, dp(4), dp(16), dp(8))
                        })
                    })
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
        val filtered = uiState.sessions
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

    private fun loadSession(session: SessionItem) {
        val c = store.read() ?: return
        interactiveController.dismissAll()
        lifecycleScope.launch {
            val detail = SessionRepository().getSessionDetail(BASE_URL, c, session.id)
            if (detail != null) {
                val history = detail.messages.map {
                    ChatMessage(if (it.role == "user") "user" else "assistant", it.content, timestamp = System.currentTimeMillis())
                }
                uiState.bindSession(detail.id, detail.title, history)
                currentSessionId = detail.id
                renderMessages()
            }
        }
    }

    private fun deleteSession(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            if (SessionRepository().deleteSession(BASE_URL, c, session.id)) {
                uiState.setSessions(uiState.sessions.filterNot { it.id == session.id })
                if (currentSessionId == session.id) {
                    interactiveController.dismissAll()
                    uiState.startNewSession()
                    currentSessionId = null
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
        startActivityForResult(intent, recognizeVoice)
    }

    private fun speakLastMessage() {
        uiState.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text?.let {
            tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "mars-last-response")
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

    private fun scrollToBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun startNewChat() {
        stream?.cancel()
        stream = null
        currentStreamId = null
        interactiveController.dismissAll()
        uiState.startNewSession()
        currentSessionId = null
        explicitStatus = null
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
        lifecycleScope.launch {
            val c = store.read()
            if (c != null && PairingRepository(BASE_URL).revokeCredential(c.deviceId, c.accessToken).isSuccess) {
                store.clear()
            }
            finish()
        }
    }

    private fun dp(value: Int) = MarsTheme.dp(this, value)

    companion object {
        private const val BASE_URL = "https://marscore.ru"
    }
}
