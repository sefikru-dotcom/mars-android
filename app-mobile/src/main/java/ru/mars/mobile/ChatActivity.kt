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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.app.Dialog
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import ru.mars.network.repository.ModelsRepository
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.SessionRepository

class ChatActivity : ComponentActivity() {
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
    private val attachments = mutableListOf<ru.mars.network.model.ChatAttachment>()
    private var speech: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var pendingAttachmentUri: Uri? = null
    private val pickAttachment = 4101
    private val recognizeVoice = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = java.util.Locale.getDefault() }
        setContentView(buildView())
        loadSessions()
    }
    override fun onDestroy() { stream?.cancel(); speech?.destroy(); tts.shutdown(); super.onDestroy() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == recognizeVoice && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input.setText(it); input.setSelection(input.length()) }
        }
        if (requestCode == pickAttachment && resultCode == RESULT_OK) data?.data?.let { uploadAttachment(it) }
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(10)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "☰"; textSize = 18f; contentDescription = "Сессии"; setOnClickListener { showSessions() } })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(4), 0) }
        titleBox.addView(TextView(this).apply { text = "☄ Марс"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        modelBtn = Button(this).apply {
            text = "⚡ $selectedModel"
            textSize = 11f
            contentDescription = "Модель"
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { chooseModel() }
        }
        titleBox.addView(modelBtn)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "🎵"; contentDescription = "Музыка"; setOnClickListener { startActivity(android.content.Intent(this@ChatActivity, MusicActivity::class.java)) } })
        header.addView(Button(this).apply { text = "📊"; contentDescription = "Проекты"; setOnClickListener { startActivity(android.content.Intent(this@ChatActivity, ProjectsActivity::class.java)) } })
        header.addView(Button(this).apply { text = "🚪"; contentDescription = "Выйти"; setOnClickListener { leaveChat() } })
        root.addView(header)
        statusView = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(dp(8), dp(5), 0, dp(5)) }
        root.addView(statusView)
        attachmentChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        root.addView(attachmentChips)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setPadding(dp(2), dp(4), dp(2), dp(8)) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2)); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Сообщение…"; setTextColor(Color.WHITE); setHintTextColor(MUTED)
            maxLines = 4; isSingleLine = false
            imeOptions = 4
            setOnEditorActionListener { _, action, _ -> if (action == 4) { sendMessage(); true } else false }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(Button(this).apply { text = "📎"; contentDescription = "Прикрепить файл"; setOnClickListener { chooseAttachment() } })
        composer.addView(Button(this).apply { text = "🎙"; contentDescription = "Голосовой ввод"; setOnClickListener { startVoiceInput() } })
        composer.addView(Button(this).apply { text = "🔊"; contentDescription = "Озвучить последний ответ"; setOnClickListener { speakLastMessage() } })
        send = Button(this).apply { text = "Отправить"; setOnClickListener { sendMessage() } }; composer.addView(send)
        cancel = Button(this).apply { text = "Отмена"; visibility = View.GONE; setOnClickListener { cancelStream() } }; composer.addView(cancel)
        root.addView(composer); return root
    }

    private fun sendMessage() {
        if (uiState.status == ChatStatus.TYPING) { steerMessage(); return }
        val text = input.text.toString().trim(); val outgoingAttachments = attachments.toList(); if (!uiState.begin(text, outgoingAttachments.map { it.name })) return
        attachments.clear(); renderAttachmentChips()
        val credential = store.read() ?: run { uiState.cancel(); leaveChat(); return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.text.clear(); renderMessages(); updateControls()
        stream = lifecycleScope.launch {
            try {
                ChatRepository().sendMessage(BASE_URL, credential, text, currentSessionId, outgoingAttachments).collect { event ->
                    uiState.onEvent(event); if (event is ChatEvent.SessionBound) currentSessionId = event.sessionId; if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                    renderMessages(); setStatus(); scrollToBottom()
                }
                if (uiState.status == ChatStatus.TYPING) uiState.complete()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { uiState.fail() }
            finally { renderMessages(); setStatus(); updateControls() }
        }
    }
    private fun cancelStream() {
        val credential = store.read(); val id = currentStreamId
        if (credential != null && id != null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { ChatRepository().executeControl(ChatRepository().cancelRequest(BASE_URL, id, credential.accessToken)) } }
        stream?.cancel(); currentStreamId = null; uiState.cancel(); setStatus(); updateControls(); renderMessages()
    }
    private fun steerMessage() {
        val text = input.text.toString().trim(); val credential = store.read(); val id = currentStreamId
        if (text.isBlank() || credential == null || id == null) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { ChatRepository().executeControl(ChatRepository().steerRequest(BASE_URL, id, text, credential.accessToken)) }
        input.text.clear(); statusView.text = "Направление отправлено"
    }
    private fun updateControls() { val active = uiState.status == ChatStatus.TYPING; send.text = if (active) "Подрулить" else "Отправить"; send.isEnabled = true; cancel.text = "■ Стоп"; cancel.visibility = if (active) View.VISIBLE else View.GONE }
    private fun setStatus() { statusView.text = uiState.phase?.let { "Марс: ${phaseLabel(it)}" } ?: when (uiState.status) { ChatStatus.TYPING -> "Марс печатает…"; ChatStatus.RECEIVED -> "Ответ получен"; ChatStatus.ERROR -> "Ошибка сети"; else -> "" } }
    private fun phaseLabel(value: String) = when { value.contains("tool", true) || value.contains("command", true) -> "Выполняет команду…"; value.contains("think", true) || value.contains("reason", true) -> "Анализирует код…"; else -> value }

    private fun renderMessages() {
        messages.removeAllViews()
        uiState.messages.forEachIndexed { index, message ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = bubble(message.role == "user") }
            val label = TextView(this).apply { text = if (message.role == "user") "Вы" else "☄ Марс  ·  ${message.status.name.lowercase()}"; setTextColor(MUTED); textSize = 12f }
            card.addView(label)
            if (message.attachments.isNotEmpty()) card.addView(TextView(this).apply { text = message.attachments.joinToString("\n") { "[Вложение: $it]" }; setTextColor(MUTED); setPadding(0, dp(5), 0, 0) })
            if (message.text.isNotBlank()) {
                renderMarkdown(message.text, card, message.role == "user")
                card.addView(Button(this).apply { text = "Копировать"; setOnClickListener { copyMessage(message.text) } })
            }
            if (message.thoughts.isNotEmpty()) {
                val toggle = Button(this).apply { text = "${if (message.thoughtsExpanded) "⌃" else "⌄"}  Мысли Марса"; setOnClickListener { uiState.toggleThoughts(index); renderMessages() } }; card.addView(toggle)
                if (message.thoughtsExpanded) card.addView(TextView(this).apply { text = markdown(message.thoughts.joinToString("\n")); setTextColor(MUTED); setPadding(dp(8), 0, 0, dp(5)) })
            }
            message.tools.forEachIndexed { toolIndex, tool ->
                val toggle = Button(this).apply { text = "${if (tool.expanded) "⌃" else "⌄"}  ${tool.name}"; setOnClickListener { uiState.toggleTool(index, toolIndex); renderMessages() } }; card.addView(toggle)
                if (tool.expanded) card.addView(TextView(this).apply { text = "Аргументы:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"; setTextColor(MUTED); setTypeface(Typeface.MONOSPACE); setPadding(dp(8), 0, 0, dp(6)) })
            }
            val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .88).toInt(), -2).apply { topMargin = dp(8); gravity = if (message.role == "user") Gravity.END else Gravity.START }
            messages.addView(card, params)
        }; scrollToBottom()
    }
    private fun bubble(user: Boolean) = GradientDrawable().apply { setColor(if (user) USER_BUBBLE else SURFACE); cornerRadius = dp(18).toFloat() }

    private fun showSessions() {
        val dialog = Dialog(this); val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(SURFACE) }
        box.addView(TextView(this).apply { text = "Сессии"; textSize = 22f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10)) })
        box.addView(Button(this).apply { text = "+ Новый чат"; setOnClickListener { uiState.startNewSession(); currentSessionId = null; renderMessages(); dialog.dismiss() } })
        val filter = EditText(this).apply { hint = "Поиск сессий…"; setTextColor(Color.WHITE); setHintTextColor(MUTED) }
        box.addView(filter)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun populate(query: String = "") { list.removeAllViews(); val filtered = uiState.sessions.filter { "${it.title.orEmpty()} ${it.preview.orEmpty()}".contains(query, true) }; if (filtered.isEmpty()) list.addView(TextView(this).apply { text = "Нет подходящих сессий"; setTextColor(MUTED); setPadding(0, dp(14), 0, dp(14)) }); filtered.forEach { session ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply { text = session.title ?: session.preview ?: "Новый диалог"; setOnClickListener { dialog.dismiss(); loadSession(session) } }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "✎"; setOnClickListener { renameSession(session, dialog) } })
            row.addView(Button(this).apply { text = "×"; setOnClickListener { deleteSession(session, dialog) } }); list.addView(row)
        } }
        filter.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString().orEmpty()) } override fun afterTextChanged(s: android.text.Editable?) {} })
        populate()
        dialog.setContentView(box); dialog.window?.setLayout(dp(340), -1); dialog.show(); dialog.window?.setLayout(dp(340), -1)
    }
    private fun loadSessions() { val c = store.read() ?: return; lifecycleScope.launch { uiState.setSessions(SessionRepository().getSessions(BASE_URL, c)) } }
    private fun loadSession(session: SessionItem) { val c = store.read() ?: return; lifecycleScope.launch { val detail = SessionRepository().getSessionDetail(BASE_URL, c, session.id); if (detail != null) { val history = detail.messages.map { ChatMessage(if (it.role == "user") "user" else "assistant", it.content, timestamp = System.currentTimeMillis()) }; uiState.bindSession(detail.id, detail.title, history); currentSessionId = detail.id; renderMessages() } } }
    private fun deleteSession(session: SessionItem, dialog: Dialog) { val c = store.read() ?: return; lifecycleScope.launch { if (SessionRepository().deleteSession(BASE_URL, c, session.id)) { uiState.setSessions(uiState.sessions.filterNot { it.id == session.id }); if (currentSessionId == session.id) uiState.startNewSession(); dialog.dismiss(); showSessions() } } }
    private fun renameSession(session: SessionItem, dialog: Dialog) {
        val c = store.read() ?: return
        val input = EditText(this).apply { setText(session.title.orEmpty()); setSingleLine(true); setTextColor(Color.WHITE) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Переименовать диалог")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = input.text.toString().trim()
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
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false); addCategory(Intent.CATEGORY_OPENABLE) }, pickAttachment)
    }

    private fun uploadAttachment(uri: Uri) {
        val credential = store.read() ?: return
        lifecycleScope.launch {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"
                ChatRepository().uploadAttachment(BASE_URL, credential, name, mime, bytes, currentSessionId)
            }.getOrNull()
            if (result != null) { attachments += result; renderAttachmentChips(); statusView.text = "Прикреплено: ${result.name}" } else statusView.text = "Не удалось прикрепить файл"
        }
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { statusView.text = "Распознавание речи недоступно"; return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), recognizeVoice); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault()) }
        startActivityForResult(intent, recognizeVoice)
    }

    private fun speakLastMessage() {
        uiState.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text?.let { tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "mars-last-response") }
    }

    @Suppress("DEPRECATION")
    private fun copyMessage(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setText(text)
        statusView.text = "Скопировано"
    }

    private fun renderMarkdown(value: String, parent: LinearLayout, user: Boolean) {
        val parts = Regex("```(?:[^\\n]*)\\n([\\s\\S]*?)```").findAll(value)
        var cursor = 0
        fun text(s: String) { if (s.isNotBlank()) parent.addView(TextView(this).apply { this.text = markdownInline(s); setTextColor(if (user) Color.WHITE else BOT_TEXT); textSize = 16f; setPadding(0, dp(5), 0, 0) }) }
        parts.forEach { match -> text(value.substring(cursor, match.range.first)); val code = match.groupValues[1].trimEnd('\n'); val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(6), dp(4)); setBackgroundColor(0xff11151d.toInt()) }; val bar = LinearLayout(this).apply { gravity = Gravity.END }; bar.addView(Button(this).apply { text = "Копировать"; setOnClickListener { copyMessage(code); Toast.makeText(this@ChatActivity, "Код скопирован", Toast.LENGTH_SHORT).show() } }); box.addView(bar); box.addView(HorizontalScrollView(this).apply { addView(TextView(this@ChatActivity).apply { text = code; setTextColor(0xffe6edf3.toInt()); typeface = Typeface.MONOSPACE; textSize = 14f; setPadding(0, 0, dp(20), dp(8)) }) }); parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }); cursor = match.range.last + 1 }; text(value.substring(cursor))
    }
    private fun markdownInline(value: String): Spanned { val s = SpannableString(value); Regex("\\*\\*(.+?)\\*\\*|(?<!\\*)\\*(.+?)(?<!\\*)\\*|`([^`]+)`").findAll(value).forEach { m -> when { m.value.startsWith("**") -> s.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, 33); m.value.startsWith("*") -> s.setSpan(StyleSpan(Typeface.ITALIC), m.range.first, m.range.last + 1, 33); else -> { s.setSpan(TypefaceSpan(Typeface.MONOSPACE), m.range.first, m.range.last + 1, 33); s.setSpan(BackgroundColorSpan(0xff303846.toInt()), m.range.first, m.range.last + 1, 33) } } }; return s }
    private fun renderAttachmentChips() { attachmentChips.removeAllViews(); attachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE; attachments.forEachIndexed { i, a -> attachmentChips.addView(Button(this).apply { text = "${a.name} ✕"; setOnClickListener { attachments.removeAt(i); renderAttachmentChips() } }) } }
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
    private fun markdown(value: String): Spanned = markdownInline(value)

    private fun scrollToBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    private fun leaveChat() { stream?.cancel(); lifecycleScope.launch { val c = store.read(); if (c != null && PairingRepository(BASE_URL).revokeCredential(c.deviceId, c.accessToken).isSuccess) store.clear(); finish() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val BASE_URL = "https://marscore.ru"; private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val USER_BUBBLE = 0xff3759a8.toInt(); private const val BOT_TEXT = 0xfff0f2f6.toInt(); private const val MUTED = 0xffaab2c0.toInt() }
}
