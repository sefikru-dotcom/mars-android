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
import ru.mars.network.repository.InteractiveRequestsRepository
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

    /**
     * Interactive attention requests (P0): approval/clarify arrive as SSE events and must NOT be
     * silently swallowed — they block the turn until answered. The controller owns the state
     * machine (queue refresh, stale/identity guards, cancel-without-respond); the surface renders
     * inline D-pad-focusable cards in the transcript. All HTTP stays in core-network
     * ([InteractiveRequestsRepository] via [RepositoryTvInteractiveGateway]) — no network logic here.
     */
    private lateinit var interactiveSurface: TvChatInteractiveSurface
    private lateinit var interactive: TvInteractiveController

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
        wireInteractive()
        input.requestFocus()
    }

    /** Builds the controller/surface pair: cards live in [messages], focus returns to the composer. */
    private fun wireInteractive() {
        interactiveSurface = TvChatInteractiveSurface(
            container = messages,
            onAllCardsCleared = { input.requestFocus() },
            onCardShown = { scrollDown() },
        )
        interactive = TvInteractiveController(
            surface = interactiveSurface,
            gateway = RepositoryTvInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
            sessionProvider = { sessionFlow.sessionId },
            uiDispatcher = MainThreadTvDispatcher(),
        ).also { interactiveSurface.listener = it }
        // WebUI parity: the accepted answer is echoed into the transcript as a user message.
        interactive.onClarifyAnswered = { answer -> append("Вы", answer, isUser = true) }
    }

    override fun onDestroy() {
        stream?.cancel()
        if (::interactive.isInitialized) interactive.destroy()
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
                        // Interactive gates are handled explicitly — they must never fall into
                        // a silent `else -> Unit` (P0: the turn would hang with no way to answer).
                        interactive.onEvent(event)
                        when (event) {
                            is ChatEvent.Chunk -> {
                                answer.text = "${answer.text.toString().removePrefix("Марс: ")}${event.text}"
                                scrollDown()
                            }
                            is ChatEvent.Error -> answer.text = "Ошибка: ${event.message}"
                            is ChatEvent.ApprovalRequired -> scrollDown() // card rendered by the surface
                            is ChatEvent.ClarifyRequired -> scrollDown()
                            else -> Unit
                        }
                        renderStatus()
                    }
            } finally {
                // Stream finished/cancelled: prompts cannot outlive their stream (Done/Error
                // already dismissed via the controller; this covers abnormal termination).
                if (::interactive.isInitialized) interactive.dismissAll()
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
        // Cards of the dead stream go first (invalidates in-flight responds), then the transcript.
        if (::interactive.isInitialized) interactive.dismissAll()
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
