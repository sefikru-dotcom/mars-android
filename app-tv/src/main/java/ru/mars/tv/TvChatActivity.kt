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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.SessionRepository

/**
 * TV chat screen: transcript, composer and the live turn status strip.
 *
 * A running turn is STOPPABLE (mobile cancelStream parity): while the turn is live the
 * primary button becomes «Остановить» and posts /api/chat/cancel ([TvChatControlGateway])
 * before tearing the local stream down. «Новый чат» and BACK/onDestroy cancel the server
 * turn best-effort as well — the TV client has no stream re-attach, so a merely abandoned
 * turn would keep executing invisibly (tokens, commands, or blocked on an approval card).
 */
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

    /** Server-side turn stop (/api/chat/cancel) — all HTTP/auth stays in core-network. */
    private lateinit var controlGateway: TvChatControlGateway

    /** True while an explicit «Остановить» teardown is in flight (the stream finally defers to it). */
    private var stopping = false

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
        controlGateway = RepositoryTvChatControlGateway.create(BASE_URL, store)
        // While a turn is live the same button becomes «Остановить» (mobile parity).
        send.setOnClickListener { if (sessionFlow.canStop) stopCurrentTurn() else sendMessage() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        wireInteractive()
        renderSendControl()
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
        // BACK/exit abandons the session for good (no stream re-attach on TV): cancel the
        // live server turn first so the agent does not keep running invisibly.
        cancelServerTurnBestEffort()
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
        // Drops the «Марс: » placeholder exactly once (first chunk), then concatenates verbatim.
        val accumulator = TvAnswerAccumulator(answer.text.toString())
        sessionFlow.beginTurn()
        renderSendControl()
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
                                answer.text = accumulator.onChunk(event.text)
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
                if (!stopping) {
                    // An explicit «Остановить» teardown owns the final render (stopCurrentTurn).
                    sessionFlow.endTurn()
                    renderStatus()
                    renderSendControl()
                    input.requestFocus()
                }
            }
        }
        return true
    }

    /** «Новый чат» from the header: cancels the live server turn, aborts the stream, resets session + transcript. */
    private fun startNewChat() {
        // The session is dropped for good — cancel the server turn first (best-effort) so the
        // agent does not keep running for a transcript nobody will ever see again.
        cancelServerTurnBestEffort()
        stream?.cancel()
        stream = null
        stopping = false
        // Cards of the dead stream go first (invalidates in-flight responds), then the transcript.
        if (::interactive.isInitialized) interactive.dismissAll()
        sessionFlow.startNewChat()
        messages.removeAllViews()
        renderSendControl()
        renderStatus()
        input.text.clear()
        input.requestFocus()
    }

    /**
     * «Остановить»: cancels the RUNNING SERVER-SIDE turn (mobile cancelStream parity), then
     * tears the local stream down. Dropping only the SSE connection would leave the agent
     * executing invisibly — burning tokens, running commands, or blocked forever on an
     * approval card until the server timeout. The status line reports honestly whether the
     * server confirmed the cancel ([TvChatSessionFlow.markTurnStopped]).
     */
    private fun stopCurrentTurn() {
        if (!sessionFlow.canStop || stopping) return
        // Captured up front: startNewChat racing in must not change what we cancel.
        val streamId = sessionFlow.activeStreamId
        stopping = true
        send.isEnabled = false
        statusView.text = STOPPING
        statusView.visibility = View.VISIBLE
        lifecycleScope.launch {
            val serverCancelled = streamId?.let { id ->
                withContext(Dispatchers.IO) { controlGateway.cancelStream(id) }
            } ?: false
            stream?.cancel()
            stream?.join()
            stream = null
            stopping = false
            // A «Новый чат» pressed mid-stop owns the screen now — its reset must not be clobbered.
            if (sessionFlow.canStop) {
                sessionFlow.markTurnStopped(serverCancelled)
                renderStatus()
                renderSendControl()
                input.requestFocus()
            }
        }
    }

    /**
     * Fire-and-forget cancel of the live server turn when the screen leaves the session
     * («Новый чат», BACK/onDestroy). Runs on [applicationScope] so it survives Activity
     * teardown; the outcome is intentionally not surfaced (the transcript is already gone).
     */
    private fun cancelServerTurnBestEffort() {
        if (!::controlGateway.isInitialized) return
        val streamId = sessionFlow.activeStreamId ?: return
        applicationScope.launch { controlGateway.cancelStream(streamId) }
    }

    /**
     * The primary button mirrors the turn state (mobile send-becomes-stop parity):
     * «Остановить» + stop icon while the agent turn is live, «Отправить» otherwise. It stays
     * enabled and focusable in BOTH states — a D-pad user must never lose the ability to
     * interrupt a running turn.
     */
    private fun renderSendControl() {
        val liveTurn = sessionFlow.canStop
        send.text = if (liveTurn) STOP_LABEL else SEND_LABEL
        send.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (liveTurn) R.drawable.ic_stop else R.drawable.ic_send, 0, 0, 0)
        send.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.mars_on_accent)
        send.isEnabled = true
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
        private const val SEND_LABEL = "Отправить"
        private const val STOP_LABEL = "Остановить"
        private const val STOPPING = "Останавливаю…"

        /** Process-wide scope for fire-and-forget server cancels that must outlive the Activity. */
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
