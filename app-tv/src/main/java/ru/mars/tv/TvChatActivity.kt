package ru.mars.tv

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.SessionRepository

class TvChatActivity : AppCompatActivity() {
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var newChat: Button
    private lateinit var statusView: TextView
    private lateinit var contextView: TextView
    private var stream: Job? = null
    private val store by lazy { AndroidKeyStoreCredentialStore(this) }

    /**
     * Pure session + stream state (WebUI parity): chat/start requires a real
     * session_id, so the flow mints one via POST /api/session/new before the
     * first send and formats Thought/ToolCall/ContextUsage into TV status text.
     */
    private val sessionFlow = TvChatSessionFlow(SessionRepository(), BASE_URL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_chat)
        messages = findViewById(R.id.messages)
        input = findViewById(R.id.messageInput)
        send = findViewById(R.id.sendButton)
        newChat = findViewById(R.id.newChatButton)
        statusView = findViewById(R.id.statusText)
        contextView = findViewById(R.id.contextText)
        val back = findViewById<Button>(R.id.backButton)
        back.setOnClickListener { finish() }
        newChat.setOnClickListener { startNewChat() }
        TvFocusLift.attachAll(input, send, newChat, back)
        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        input.requestFocus()
    }

    override fun onDestroy() {
        stream?.cancel()
        super.onDestroy()
    }

    private fun sendMessage(): Boolean {
        val text = input.text.toString().trim()
        if (text.isEmpty() || stream?.isActive == true) return false
        val credential = store.read() ?: return false
        input.text.clear()
        append("Вы", text, isUser = true)
        val answer = append("Марс", "", isUser = false)
        send.isEnabled = false
        sessionFlow.beginTurn()
        renderStatus()
        stream = lifecycleScope.launch {
            try {
                // Preflight: the server never creates sessions — a null id must not
                // reach chat/start; the flow reuses the bound id or mints a new one.
                when (val ensured = sessionFlow.ensureSession(credential)) {
                    is TvSessionResult.Failed -> {
                        answer.text = "Сессия не создана: ${ensured.message}"
                        return@launch
                    }
                    is TvSessionResult.Ready -> renderStatus()
                }
                ChatRepository().sendMessage(BASE_URL, credential, text, sessionFlow.sessionId)
                    .collect { event ->
                        sessionFlow.onEvent(event)
                        when (event) {
                            is ChatEvent.Chunk -> {
                                answer.text = "${answer.text.toString().removePrefix("Марс: ")}${event.text}"
                                scrollDown()
                            }
                            is ChatEvent.Error -> answer.text = "Ошибка: ${event.message}"
                            else -> Unit
                        }
                        renderStatus()
                    }
            } finally {
                renderStatus()
                send.isEnabled = true
                input.requestFocus()
            }
        }
        return true
    }

    /** «Новый чат» from the header: aborts any live stream and resets session + transcript. */
    private fun startNewChat() {
        stream?.cancel()
        stream = null
        sessionFlow.startNewChat()
        messages.removeAllViews()
        send.isEnabled = true
        renderStatus()
        input.text.clear()
        input.requestFocus()
    }

    /**
     * Header status strip: live reasoning/tool status line plus the context-fill pill
     * (green <=50%, orange <=75%, red above — WebUI ring parity). Both hide when unknown.
     */
    private fun renderStatus() {
        val status = sessionFlow.statusText
        statusView.text = status
        statusView.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        val usageText = sessionFlow.contextUsageText
        contextView.text = usageText.orEmpty()
        contextView.visibility = if (usageText == null) View.GONE else View.VISIBLE
        val percent = sessionFlow.contextUsage?.percent ?: 0
        contextView.setTextColor(ContextCompat.getColor(this, when {
            percent > 75 -> R.color.mars_status_error
            percent > 50 -> R.color.mars_status_warning
            else -> R.color.mars_status_online
        }))
    }

    /**
     * WebUI-parity chat bubble: user side gets the deep-navy bubble
     * (USER_BUBBLE + USER_BORDER), Mars side gets the bot card
     * (BOT_CARD + BOT_BORDER). Both are focusable — under D-pad focus the
     * bg_bubble_* StateListDrawable cross-fades to a ruby border.
     */
    private fun append(author: String, text: String, isUser: Boolean): TextView = TextView(this).apply {
        this.text = "$author: $text"
        textSize = 22f
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
        isFocusable = true
        TvFocusLift.attach(this)
        messages.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun scrollDown() { findViewById<ScrollView>(R.id.messageScroll).post { findViewById<ScrollView>(R.id.messageScroll).fullScroll(View.FOCUS_DOWN) } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
    }
}
