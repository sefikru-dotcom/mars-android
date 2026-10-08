package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.provider.OpenableColumns
import android.text.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
    private lateinit var send: Button
    private lateinit var cancel: Button
    private lateinit var statusView: TextView
    private val uiState = ChatUiState()
    // Interactive attention prompts (approval/clarify). The controller is confined to the UI
    // thread via MainThreadDispatcher; gateway HTTP runs on its background executor.
    private lateinit var interactiveView: AlertDialogInteractiveView
    private lateinit var interactiveController: InteractiveRequestController
    private var stream: Job? = null
    private var currentSessionId: String? = null
    private var currentStreamId: String? = null
    // First-message gate: the live /api/chat/start requires a real session_id, so a null
    // id is minted via POST /api/session/new before the very first ChatRepository.sendMessage.
    private val firstSessionFlow = FirstSessionFlow(SessionRepository(), BASE_URL)
    // Explicit one-shot status text (session creation in flight / failure reason). Survives
    // the finally-block setStatus() reset; cleared on the next send, cancel, or new chat.
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
    private lateinit var miniPlayerPlayBtn: Button
    private lateinit var quickChipsScroll: HorizontalScrollView

    // Sessions side panel (DrawerLayout replaces the old floating Dialog)
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerSessionList: LinearLayout
    private lateinit var drawerFilter: EditText
    private var drawerFilterQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        interactiveView = AlertDialogInteractiveView(this)
        interactiveController = InteractiveRequestController(
            view = interactiveView,
            gateway = RepositoryInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            state = uiState,
            uiDispatcher = MainThreadDispatcher(),
        ).also { interactiveView.listener = it }
        // Accepted clarify answers echo into the transcript as user messages (web parity);
        // the controller invokes this hook on the UI dispatcher thread.
        interactiveController.onClarifyAnswered = { answer -> echoClarifyAnswer(answer) }
        modelSelection = ModelSelection(PrefsModelChoiceStorage(getSharedPreferences("mars-model-choice", MODE_PRIVATE)))
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = java.util.Locale.getDefault() }
        setContentView(buildView())
        // Back press: an open drawer consumes the press and closes instead of leaving chat.
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
        // Terminal cleanup: ignore late callbacks, then dismiss dialogs synchronously so no
        // window-leaked warning survives the Activity.
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
            miniPlayerPlayBtn.text = if (isPlaying) "⏸" else "▶"
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
        drawer = DrawerLayout(this).apply { setBackgroundColor(BACKGROUND) }
        drawer.addView(buildChatContent(), DrawerLayout.LayoutParams(-1, -1))
        drawer.addView(buildSessionsDrawer(), DrawerLayout.LayoutParams(dp(300), -1, GravityCompat.START))
        return drawer
    }

    /** Sessions side panel: header, new chat, filter, quick jumps, session list. */
    private fun buildSessionsDrawer(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(SURFACE)
            isClickable = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(6), dp(16), systemBars.bottom + dp(6))
            insets
        }

        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "Сессии"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(Button(this).apply {
            text = "✕"
            contentDescription = "Закрыть панель сессий"
            background = null
            setTextColor(MUTED)
            setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(headerRow)

        panel.addView(Button(this).apply {
            text = "+ Новый чат"
            setTextColor(Color.WHITE)
            background = buttonBg(ACCENT)
            setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                startNewChat()
            }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        drawerFilter = EditText(this).apply {
            hint = "Поиск сессий…"
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
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
        panel.addView(drawerFilter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val quickRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(
            "📊 Проекты" to { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) },
            "🎵 Музыка" to { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        ).forEach { (title, action) ->
            quickRow.addView(Button(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xffd0d8e8.toInt())
                background = buttonBg(BACKGROUND)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    action()
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        }
        panel.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        drawerSessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { addView(drawerSessionList, ViewGroup.LayoutParams(-1, -2)) }
        panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        renderSessionList()
        return panel
    }

    private fun buildChatContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(BACKGROUND)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), systemBars.top + dp(6), dp(12), systemBars.bottom + dp(6))
            insets
        }

        // Header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "☰"
            textSize = 18f
            contentDescription = "Сессии"
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    drawer.openDrawer(GravityCompat.START)
                }
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "☄ Марс"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        modelBtn = Button(this).apply {
            text = modelSelection.buttonLabel()?.let { "⚡ $it" } ?: "⚡ Модель"
            textSize = 11f
            contentDescription = "Модель"
            background = null
            setPadding(0, 0, 0, 0)
            setTextColor(ACCENT)
            setOnClickListener { chooseModel() }
        }
        titleBox.addView(modelBtn)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(Button(this).apply {
            text = "🎵"
            contentDescription = "Музыка"
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })

        header.addView(Button(this).apply {
            text = "📊"
            contentDescription = "Проекты"
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })

        header.addView(Button(this).apply {
            text = "🚪"
            contentDescription = "Выйти"
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener { leaveChat() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        root.addView(header)

        // Mini-player strip
        miniPlayerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = cardBg(0xff182232.toInt(), ACCENT)
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) }
        }
        miniPlayerBox.addView(TextView(this).apply {
            text = "🎵"
            textSize = 15f
            setPadding(0, 0, dp(8), 0)
        })
        miniPlayerTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniPlayerBox.addView(miniPlayerTitle, LinearLayout.LayoutParams(0, -2, 1f))

        miniPlayerPlayBtn = Button(this).apply {
            text = "▶"
            textSize = 14f
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener { MarsMusicPlayer.togglePlay() }
        }
        miniPlayerBox.addView(miniPlayerPlayBtn, LinearLayout.LayoutParams(dp(40), dp(36)))

        val miniNextBtn = Button(this).apply {
            text = "⏭"
            textSize = 14f
            background = buttonBg(SURFACE)
            setTextColor(Color.WHITE)
            setOnClickListener { MarsMusicPlayer.next() }
        }
        miniPlayerBox.addView(miniNextBtn, LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginStart = dp(4) })

        root.addView(miniPlayerBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Status bar
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(8), dp(4), 0, dp(4))
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
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Quick suggestions / chips
        quickChipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val quickBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quickPrompts = listOf(
            "📊 Проекты" to { startActivity(Intent(this@ChatActivity, ProjectsActivity::class.java)) },
            "🎵 Музыка" to { startActivity(Intent(this@ChatActivity, MusicActivity::class.java)) },
            "🌤 Погода в Ставрополе" to { quickSend("Какая сейчас погода и прогноз на день в Ставрополе?") },
            "⚙ Статус хоста" to { quickSend("Марс, проверь аптайм, диск и состояние сервисов") },
            "🧹 Новый чат" to { startNewChat() }
        )
        quickPrompts.forEach { (title, action) ->
            quickBox.addView(Button(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xffd0d8e8.toInt())
                background = buttonBg(SURFACE)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        quickChipsScroll.addView(quickBox)
        root.addView(quickChipsScroll)

        // Composer bar
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = cardBg(SURFACE, 0xff283143.toInt())
        }

        input = EditText(this).apply {
            hint = "Сообщение Марсу…"; setTextColor(Color.WHITE); setHintTextColor(MUTED)
            maxLines = 4; isSingleLine = false
            background = null
            setPadding(dp(8), dp(6), dp(8), dp(6))
            imeOptions = 4
            setOnEditorActionListener { _, action, _ ->
                if (action == 4) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        composer.addView(Button(this).apply {
            text = "📎"
            textSize = 16f
            contentDescription = "Прикрепить файл"
            background = null
            setTextColor(Color.WHITE)
            setOnClickListener { chooseAttachment() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        composer.addView(Button(this).apply {
            text = "🎙"
            textSize = 16f
            contentDescription = "Голосовой ввод"
            background = null
            setTextColor(Color.WHITE)
            setOnClickListener { startVoiceInput() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        composer.addView(Button(this).apply {
            text = "🔊"
            textSize = 16f
            contentDescription = "Озвучить последний ответ"
            background = null
            setTextColor(Color.WHITE)
            setOnClickListener { speakLastMessage() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        send = Button(this).apply {
            text = "➤"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = buttonBg(ACCENT)
            setOnClickListener { sendMessage() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(44), dp(38)))

        cancel = Button(this).apply {
            text = "■"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = buttonBg(0xffd9534f.toInt())
            visibility = View.GONE
            setOnClickListener { cancelStream() }
        }
        composer.addView(cancel, LinearLayout.LayoutParams(dp(44), dp(38)).apply { marginStart = dp(4) })

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
                // Session gate: never POST /api/chat/start with a null session_id. The
                // creation runs on IO inside the repository; cancelling the stream job
                // (cancel button / new chat) cancels it before any chat/start is sent.
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
                        // Explicit failure: chat/start is NOT sent, state returns to idle
                        // and the reason stays visible (the finally-block setStatus()
                        // renders explicitStatus instead of clearing it).
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
                    // Session/stream ids are captured BEFORE the controller sees the event:
                    // it resolves sessionProvider() when showing or answering a prompt.
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
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ChatRepository().executeControl(ChatRepository().cancelRequest(BASE_URL, id, credential.accessToken)) }
            }
        }
        stream?.cancel()
        stream = null
        currentStreamId = null
        // Prompts of a cancelled stream are dead; also invalidates in-flight respond results.
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
            val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
        send.text = if (active) "↻" else "➤"
        send.isEnabled = true
        cancel.visibility = if (active) View.VISIBLE else View.GONE
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
        value.contains("think", true) || value.contains("reason", true) -> "Анализирует код…"
        else -> value
    }

    private fun renderMessages() {
        messages.removeAllViews()
        uiState.messages.forEachIndexed { index, message ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = bubble(message.role == "user")
            }
            val label = TextView(this).apply {
                text = if (message.role == "user") "Вы" else "☄ Марс  ·  ${message.status.name.lowercase()}"
                setTextColor(MUTED)
                textSize = 12f
            }
            card.addView(label)

            if (message.attachments.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = message.attachments.joinToString("\n") { "[Вложение: $it]" }
                    setTextColor(MUTED)
                    setPadding(0, dp(5), 0, 0)
                })
            }

            if (message.text.isNotBlank()) {
                renderMarkdownBlocks(message.text, card, message.role == "user")
                val copyBtn = Button(this).apply {
                    text = "Копировать"
                    textSize = 11f
                    setTextColor(MUTED)
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
                    background = cardBg(0x143759a8, 0xff5b7fc7.toInt())
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val toggle = Button(this).apply {
                    text = "${if (message.thoughtsExpanded) "⌃" else "⌄"}  Мысли Марса"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xff9cb4d8.toInt())
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
                        setTextColor(0xffb8c6e0.toInt())
                        textSize = 13f
                        setTypeface(null, Typeface.ITALIC)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(0, dp(2), 0, 0)
                    })
                    thoughtBox.addView(Button(this).apply {
                        text = "Копировать мысли"
                        textSize = 11f
                        setTextColor(MUTED)
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
                    background = cardBg(0xff0d1117.toInt(), 0xff30363d.toInt())
                    setPadding(dp(10), dp(4), dp(6), dp(4))
                }
                val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val toggle = Button(this).apply {
                    text = "${if (tool.expanded) "⌃" else "⌄"}  ⚙ ${tool.name}"
                    textSize = 12f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(0xffffb86c.toInt())
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
                    text = if (done) "✓ готово" else "… выполняется"
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(if (done) 0xff7ee787.toInt() else MUTED)
                    setPadding(dp(6), 0, dp(6), 0)
                })
                header.addView(Button(this).apply {
                    text = "⧉"
                    contentDescription = "Копировать вызов инструмента"
                    textSize = 13f
                    setTextColor(MUTED)
                    background = null
                    minimumHeight = 0
                    minHeight = 0
                    setOnClickListener { copyMessage(toolDump(tool)) }
                }, LinearLayout.LayoutParams(dp(34), dp(30)))
                box.addView(header, LinearLayout.LayoutParams(-1, -2))
                if (tool.expanded) {
                    val body = TextView(this).apply {
                        text = toolDump(tool)
                        setTextColor(0xffc9d1d9.toInt())
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setPadding(dp(2), dp(2), dp(8), dp(6))
                    }
                    box.addView(HorizontalScrollView(this).apply { addView(body) })
                }
                card.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }

            val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .88).toInt(), -2).apply {
                topMargin = dp(8)
                gravity = if (message.role == "user") Gravity.END else Gravity.START
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
                            setTextColor(if (user) Color.WHITE else BOT_TEXT)
                            textSize = 15f
                            setPadding(0, dp(2), 0, dp(2))
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
                                tv.setTextColor(0xff3a4356.toInt())
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
                        setPadding(dp(10), dp(8), dp(6), dp(4))
                        background = cardBg(0xff0d1117.toInt(), 0xff30363d.toInt())
                    }
                    val bar = LinearLayout(this).apply { gravity = Gravity.END }
                    if (block.language.isNotEmpty()) {
                        bar.addView(TextView(this).apply {
                            text = block.language
                            textSize = 11f
                            setTextColor(MUTED)
                            setPadding(0, 0, dp(8), 0)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    bar.addView(Button(this).apply {
                        text = "Копировать"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        background = buttonBg(SURFACE)
                        setOnClickListener {
                            copyMessage(code)
                            Toast.makeText(this@ChatActivity, "Код скопирован", Toast.LENGTH_SHORT).show()
                        }
                    })
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
                    builder.setSpan(TypefaceSpan(Typeface.MONOSPACE), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(0xff2d333b.toInt()), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Link -> {
                    builder.append(span.title)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(span.url))
                                startActivity(intent)
                            }
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    /** Human-readable dump of a tool call for the expanded body and the copy button. */
    private fun toolDump(tool: ToolExecution): String =
        "Аргументы:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"

    private fun bubble(user: Boolean) = GradientDrawable().apply {
        setColor(if (user) USER_BUBBLE else SURFACE)
        cornerRadius = dp(18).toFloat()
    }

    private fun buttonBg(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun cardBg(bg: Int, border: Int) = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), border)
    }

    /** Renders the drawer session list: filtered by the search query, newest first by updatedAt/createdAt. */
    private fun renderSessionList() {
        if (!::drawerSessionList.isInitialized) return
        drawerSessionList.removeAllViews()
        val filtered = uiState.sessions
            .filter { "${it.title.orEmpty()} ${it.preview.orEmpty()}".contains(drawerFilterQuery, true) }
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
        if (filtered.isEmpty()) {
            drawerSessionList.addView(TextView(this).apply {
                text = "Нет подходящих сессий"
                setTextColor(MUTED)
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }
        filtered.forEach { session ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg(BACKGROUND, 0xff283143.toInt())
                setPadding(dp(10), dp(6), dp(6), dp(6))
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply {
                text = session.title ?: "Новый диалог"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = null
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    loadSession(session)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "✎"
                contentDescription = "Переименовать сессию"
                background = null
                setTextColor(MUTED)
                setOnClickListener { renameSession(session) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(Button(this).apply {
                text = "×"
                contentDescription = "Удалить сессию"
                background = null
                setTextColor(0xffff7d87.toInt())
                setOnClickListener { deleteSession(session) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            card.addView(row)
            if (!session.preview.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = session.preview
                    textSize = 12f
                    setTextColor(MUTED)
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
        // Prompts of the previous session must not survive the switch; in-flight responds for
        // it are invalidated by the generation bump inside dismissAll().
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
            // Resolver reads and the OkHttp call all happen on IO — never a
            // content:// read or a synchronous execute() on the Main thread.
            // The stream is read with a hard cap (server limit mirrored from
            // api/upload.py), so an oversized file is rejected before upload.
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

    /** Display-name lookup for a content:// uri; only ever called from Dispatchers.IO. */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Распознавание речи недоступно"; return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                text = "${a.name} ✕"
                textSize = 11f
                setTextColor(Color.WHITE)
                background = buttonBg(SURFACE)
                setOnClickListener { attachments.removeAt(i); renderAttachmentChips() }
            })
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
                // No fabricated fallback list: an unavailable catalog is an error, not a guess.
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

    /** Loads the live /api/models catalog once at startup and resolves the effective choice. */
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
        modelBtn?.text = modelSelection.buttonLabel()?.let { "⚡ $it" } ?: "⚡ Модель"
    }

    private fun scrollToBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    /** "New chat" from any entry point: interactive prompts die together with the old session. */
    private fun startNewChat() {
        // Full teardown of the outgoing conversation: a live stream (including an in-flight
        // POST /api/session/new) must not collect into the freshly cleared state, and its
        // session/stream ids must not leak into the next first-message gate.
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

    /** Transcript echo of an accepted clarify answer (web pushes it as a user message). UI thread. */
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val BACKGROUND = 0xff10131a.toInt()
        private const val SURFACE = 0xff1b202b.toInt()
        private const val USER_BUBBLE = 0xff2e4a88.toInt()
        private const val BOT_TEXT = 0xfff0f2f6.toInt()
        private const val MUTED = 0xffaab2c0.toInt()
        private const val ACCENT = 0xffff5c36.toInt()
    }
}
