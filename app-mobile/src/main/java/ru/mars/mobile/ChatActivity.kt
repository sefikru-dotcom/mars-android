package ru.mars.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.model.ChatEvent

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
    }

    override fun onDestroy() {
        stream?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply { text = "Чат с Марсом"; textSize = 22f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Выйти"; setOnClickListener { leaveChat() } })
        root.addView(header)
        statusView = TextView(this).apply { text = ""; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(6)) }
        root.addView(statusView)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setPadding(0, dp(4), 0, dp(8)) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply { hint = "Сообщение…"; setTextColor(Color.WHITE); setHintTextColor(MUTED); setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND; setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false } }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = Button(this).apply { text = "Отправить"; setOnClickListener { sendMessage() } }
        composer.addView(send)
        cancel = Button(this).apply { text = "Отмена"; visibility = View.GONE; setOnClickListener { cancelStream() } }
        composer.addView(cancel)
        root.addView(composer)
        return root
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (!uiState.begin(text)) return
        val credential = store.read() ?: run { uiState.cancel(); leaveChat(); return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        input.text.clear()
        append("Вы", text, isUser = true)
        val answer = append("", "", isUser = false)
        setStatus(uiState.status)
        updateControls()
        scrollToBottom()
        stream = lifecycleScope.launch {
            try {
                ChatRepository().sendMessage(BASE_URL, credential, text).collect { event ->
                    when (event) {
                        is ChatEvent.Chunk -> { answer.append(event.text); scrollToBottom() }
                        ChatEvent.Done -> Unit
                        is ChatEvent.Error -> { answer.append("\nОшибка: ${event.message}"); uiState.fail(); setStatus(uiState.status) }
                    }
                }
                if (uiState.status == ChatStatus.TYPING) uiState.complete()
                setStatus(uiState.status)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                uiState.fail()
                setStatus(uiState.status)
            } finally {
                if (uiState.status == ChatStatus.TYPING) uiState.complete()
                updateControls()
            }
        }
    }

    private fun cancelStream() {
        if (stream?.isActive != true) return
        stream?.cancel()
        uiState.cancel()
        setStatus(uiState.status)
        updateControls()
    }

    private fun updateControls() {
        val active = uiState.status == ChatStatus.TYPING
        send.isEnabled = !active
        cancel.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun setStatus(status: ChatStatus) {
        statusView.text = when (status) {
            ChatStatus.IDLE -> ""
            ChatStatus.TYPING -> "Марс печатает…"
            ChatStatus.RECEIVED -> "Ответ получен"
            ChatStatus.ERROR -> "Ошибка сети"
        }
    }

    private fun append(author: String, text: String, isUser: Boolean): TextView {
        val view = TextView(this).apply {
            this.text = if (author.isEmpty()) text else "$author: $text"
            textSize = 16f
            setTextColor(if (isUser) Color.WHITE else BOT_TEXT)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(if (isUser) USER_BUBBLE else SURFACE); cornerRadius = dp(18).toFloat() }
        }
        val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(dp(120)), -2).apply {
            topMargin = dp(8)
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        messages.addView(view, params)
        return view
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun leaveChat() {
        stream?.cancel()
        lifecycleScope.launch {
            val credential = store.read()
            if (credential != null) {
                val result = PairingRepository(BASE_URL).revokeCredential(credential.deviceId, credential.accessToken)
                if (result.isFailure) return@launch
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
        private const val USER_BUBBLE = 0xff3759a8.toInt()
        private const val BOT_TEXT = 0xfff0f2f6.toInt()
        private const val MUTED = 0xffaab2c0.toInt()
    }
}
