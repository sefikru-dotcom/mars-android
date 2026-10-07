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
import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.SessionRepository

class ChatActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var cancel: Button
    private lateinit var statusView: TextView
    private val uiState = ChatUiState()
    private var stream: Job? = null
    private var currentSessionId: String? = null
    private var currentStreamId: String? = null
    private var selectedModel: String = "gemini-3.8-flash"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = java.util.Locale.getDefault() }
        setContentView(buildView())
        MarsMusicPlayer.registerListener(this)
        loadSessions()
    }

    override fun onDestroy() {
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
            setOnClickListener { showSessions() }
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
            text = "⚡ $selectedModel"
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
            "🧹 Новый чат" to { uiState.startNewSession(); currentSessionId = null; renderMessages() }
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
        renderMessages()
        updateControls()
        stream = lifecycleScope.launch {
            try {
                ChatRepository().sendMessage(BASE_URL, credential, text, currentSessionId, outgoingAttachments).collect { event ->
                    uiState.onEvent(event)
                    if (event is ChatEvent.SessionBound) currentSessionId = event.sessionId
                    if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
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
        currentStreamId = null
        uiState.cancel()
        setStatus()
        updateControls()
        renderMessages()
    }

    private fun steerMessage() {
        val text = input.text.toString().trim()
        val credential = store.read()
        val id = currentStreamId
        if (text.isBlank() || credential == null || id == null) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ChatRepository().executeControl(ChatRepository().steerRequest(BASE_URL, id, text, credential.accessToken))
        }
        input.text.clear()
        statusView.text = "Направление отправлено"
    }

    private fun updateControls() {
        val active = uiState.status == ChatStatus.TYPING
        send.text = if (active) "↻" else "➤"
        send.isEnabled = true
        cancel.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun setStatus() {
        statusView.text = uiState.phase?.let { "Марс: ${phaseLabel(it)}" } ?: when (uiState.status) {
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
                val toggle = Button(this).apply {
                    text = "${if (message.thoughtsExpanded) "⌃" else "⌄"}  Мысли Марса"
                    textSize = 12f
                    setTextColor(0xff9cb4d8.toInt())
                    background = buttonBg(0x223759a8.toInt())
                    setOnClickListener { uiState.toggleThoughts(index); renderMessages() }
                }
                card.addView(toggle, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(6) })
                if (message.thoughtsExpanded) {
                    card.addView(TextView(this).apply {
                        text = message.thoughts.joinToString("\n")
                        setTextColor(MUTED)
                        textSize = 13f
                        setPadding(dp(8), dp(4), 0, dp(5))
                    })
                }
            }

            message.tools.forEachIndexed { toolIndex, tool ->
                val toggle = Button(this).apply {
                    text = "${if (tool.expanded) "⌃" else "⌄"}  Инструмент: ${tool.name}"
                    textSize = 12f
                    setTextColor(0xffffb86c.toInt())
                    background = buttonBg(0x22443020.toInt())
                    setOnClickListener { uiState.toggleTool(index, toolIndex); renderMessages() }
                }
                card.addView(toggle, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(6) })
                if (tool.expanded) {
                    card.addView(TextView(this).apply {
                        text = "Аргументы:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"
                        setTextColor(MUTED)
                        textSize = 12f
                        setTypeface(Typeface.MONOSPACE)
                        setPadding(dp(8), dp(4), 0, dp(6))
                    })
                }
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

    private fun showSessions() {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(SURFACE)
        }
        box.addView(TextView(this).apply {
            text = "Сессии"; textSize = 22f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10))
        })
        box.addView(Button(this).apply {
            text = "+ Новый чат"
            setTextColor(Color.WHITE)
            background = buttonBg(ACCENT)
            setOnClickListener {
                uiState.startNewSession()
                currentSessionId = null
                renderMessages()
                dialog.dismiss()
            }
        })
        val filter = EditText(this).apply {
            hint = "Поиск сессий…"; setTextColor(Color.WHITE); setHintTextColor(MUTED)
        }
        box.addView(filter)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)

        fun populate(query: String = "") {
            list.removeAllViews()
            val filtered = uiState.sessions.filter { "${it.title.orEmpty()} ${it.preview.orEmpty()}".contains(query, true) }
            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "Нет подходящих сессий"
                    setTextColor(MUTED)
                    setPadding(0, dp(14), 0, dp(14))
                })
            }
            filtered.forEach { session ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(Button(this).apply {
                    text = session.title ?: session.preview ?: "Новый диалог"
                    setTextColor(Color.WHITE)
                    background = null
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { dialog.dismiss(); loadSession(session) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Button(this).apply {
                    text = "✎"
                    background = null
                    setTextColor(MUTED)
                    setOnClickListener { renameSession(session, dialog) }
                })
                row.addView(Button(this).apply {
                    text = "×"
                    background = null
                    setTextColor(0xffff7d87.toInt())
                    setOnClickListener { deleteSession(session, dialog) }
                })
                list.addView(row)
            }
        }
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        populate()
        dialog.setContentView(box)
        dialog.window?.setLayout(dp(340), -1)
        dialog.show()
    }

    private fun loadSessions() {
        val c = store.read() ?: return
        lifecycleScope.launch { uiState.setSessions(SessionRepository().getSessions(BASE_URL, c)) }
    }

    private fun loadSession(session: SessionItem) {
        val c = store.read() ?: return
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

    private fun deleteSession(session: SessionItem, dialog: Dialog) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            if (SessionRepository().deleteSession(BASE_URL, c, session.id)) {
                uiState.setSessions(uiState.sessions.filterNot { it.id == session.id })
                if (currentSessionId == session.id) uiState.startNewSession()
                dialog.dismiss()
                showSessions()
            }
        }
    }

    private fun renameSession(session: SessionItem, dialog: Dialog) {
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
                            dialog.dismiss()
                            showSessions()
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
        lifecycleScope.launch {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "attachment"
                ChatRepository().uploadAttachment(BASE_URL, credential, name, mime, bytes, currentSessionId)
            }.getOrNull()
            if (result != null) {
                attachments += result
                renderAttachmentChips()
                statusView.text = "Прикреплено: ${result.name}"
            } else {
                statusView.text = "Не удалось прикрепить файл"
            }
        }
    }

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
        lifecycleScope.launch {
            val live = if (c != null) runCatching { ModelsRepository().getModels(BASE_URL, c).models.map { it.id } }.getOrNull() else null
            val models = (live ?: listOf("gemini-3.8-flash", "gemini-3.8-pro", "claude-sonnet")).toTypedArray()
            android.app.AlertDialog.Builder(this@ChatActivity)
                .setTitle("Модель Марса")
                .setSingleChoiceItems(models, models.indexOf(selectedModel).coerceAtLeast(0)) { d, which ->
                    selectedModel = models[which]
                    modelBtn?.text = "⚡ $selectedModel"
                    d.dismiss()
                    statusView.text = "Модель: $selectedModel"
                }
                .show()
        }
    }

    private fun scrollToBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

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
