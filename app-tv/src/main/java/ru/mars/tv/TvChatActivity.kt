package ru.mars.tv

import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import ru.mars.network.model.SessionItem
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
 *
 * «Диалоги» opens the session browser overlay: the list fetch
 * ([SessionRepository.getSessions]) and the detail load ([SessionRepository.getSessionDetail])
 * happen here, while every DECISION — which rows are listed (subagent children hidden),
 * how each row reads, where the D-pad focus opens and how it steps, how a loaded
 * [ru.mars.network.model.SessionDetail] turns into transcript bubbles — lives in the pure
 * [TvSessionBrowserPolicy] (JVM unit-tested). Opening a session tears the current turn down
 * exactly like «Новый чат» does, then binds the loaded id into [sessionFlow] so the next
 * send continues that dialog.
 */
class TvChatActivity : AppCompatActivity() {
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var newChat: Button
    private lateinit var statusView: TextView
    private lateinit var contextView: TextView
    private lateinit var chatRoot: LinearLayout
    private lateinit var sessionsButton: Button
    private lateinit var sessionsOverlay: View
    private lateinit var sessionsStatus: TextView
    private lateinit var sessionList: LinearLayout
    private lateinit var closeSessions: Button
    private val sessionRowViews = mutableListOf<View>()
    private var browserRows: List<TvSessionBrowserPolicy.SessionRow> = emptyList()
    private var browserReturnFocus: View? = null
    private lateinit var browserBackCallback: OnBackPressedCallback

    /**
     * Navigation token: a session-list/session-detail fetch that returns AFTER the user
     * opened something else (another dialog, «Новый чат», closed overlay) must not clobber
     * the current screen (mobile `sessionNavigation` parity).
     */
    private var sessionLoadToken = 0

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
        chatRoot = findViewById(R.id.chatRoot)
        sessionsButton = findViewById(R.id.sessionsButton)
        sessionsOverlay = findViewById(R.id.sessionsOverlay)
        sessionsStatus = findViewById(R.id.sessionsStatus)
        sessionList = findViewById(R.id.sessionList)
        closeSessions = findViewById(R.id.closeSessionsButton)
        back.setOnClickListener { finish() }
        newChat.setOnClickListener { startNewChat() }
        sessionsButton.setOnClickListener { openSessionBrowser() }
        closeSessions.setOnClickListener { closeSessionBrowser() }
        TvFocusLift.attachAll(input, send, newChat, back, sessionsButton, closeSessions)
        // BACK closes the session dialog first; only a closed overlay exits the screen.
        browserBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeSessionBrowser() }
        }
        onBackPressedDispatcher.addCallback(this, browserBackCallback)
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
        // Per-turn answer state machine: the «Марс: » placeholder is dropped exactly once
        // (first chunk), chunks concatenate verbatim, and the FIRST terminal event (Done/Error)
        // locks the bubble — late frames can no longer clobber the outcome. A new turn builds
        // a fresh accumulator, so the next answer streams normally.
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
                            // Terminal lock lives in the accumulator (first Done/Error wins):
                            // a late chunk after Error no longer clobbers «Ошибка: …», a late
                            // chunk after Done no longer mutates the finished answer, and a
                            // tail Error after Done no longer overwrites it. null = ignore.
                            is ChatEvent.Chunk -> {
                                accumulator.onChunk(event.text)?.let { rendered ->
                                    answer.text = rendered
                                    scrollDown()
                                }
                            }
                            is ChatEvent.Error -> accumulator.onError(event.message)?.let { answer.text = it }
                            is ChatEvent.Done -> accumulator.onDone()
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
        // Any in-flight browser fetch or session load is stale from this point on.
        sessionLoadToken++
        closeSessionBrowser()
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

    // --- Session browser («Диалоги») ---------------------------------------------------------------

    /**
     * Opens the session dialog: shows the overlay, freezes the chat behind it (focus cannot
     * escape into the transcript/composer) and fetches the session list. All fetch/render
     * decisions come from [TvSessionBrowserPolicy]; a stale fetch (token moved on) is dropped.
     */
    private fun openSessionBrowser() {
        val credential = store.read() ?: return
        browserReturnFocus = currentFocus
        chatRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browserBackCallback.isEnabled = true
        sessionsOverlay.visibility = View.VISIBLE
        sessionRowViews.clear()
        browserRows = emptyList()
        sessionList.removeAllViews()
        sessionsStatus.text = LOADING_SESSIONS
        sessionsStatus.visibility = View.VISIBLE
        closeSessions.requestFocus()
        lifecycleScope.launch {
            val token = ++sessionLoadToken
            val sessions = SessionRepository().getSessions(BASE_URL, credential)
            if (token != sessionLoadToken) return@launch
            renderSessionBrowser(sessions)
        }
    }

    /** Hides the overlay, re-enables chat focus and restores it where the dialog was opened. */
    private fun closeSessionBrowser() {
        if (!::sessionsOverlay.isInitialized || sessionsOverlay.visibility != View.VISIBLE) return
        sessionLoadToken++ // an in-flight list fetch must not re-render a closed dialog
        sessionsOverlay.visibility = View.GONE
        browserBackCallback.isEnabled = false
        chatRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val restore = browserReturnFocus?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        browserReturnFocus = null
        (restore ?: sessionsButton).requestFocus()
    }

    /** Builds the D-pad focusable rows; the initial focus lands per policy (current session, else first). */
    private fun renderSessionBrowser(sessions: List<SessionItem>) {
        browserRows = TvSessionBrowserPolicy.rows(sessions, sessionFlow.sessionId)
        sessionList.removeAllViews()
        sessionRowViews.clear()
        if (browserRows.isEmpty()) {
            sessionsStatus.text = TvSessionBrowserPolicy.EMPTY_LIST
            sessionsStatus.visibility = View.VISIBLE
            closeSessions.requestFocus()
            return
        }
        sessionsStatus.visibility = View.GONE
        browserRows.forEachIndexed { index, row ->
            val card = buildSessionCard(index, row)
            sessionRowViews += card
            sessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val selection = TvSessionBrowserPolicy.initialSelection(browserRows, sessionFlow.sessionId)
        sessionRowViews.getOrNull(selection)?.requestFocus() ?: closeSessions.requestFocus()
    }

    /**
     * One dialog row: title + preview + meta card. UP/DOWN stepping is explicit through
     * [TvSessionBrowserPolicy.move] (deterministic, clamped at the edges — focus never
     * escapes the list into the chat behind); at an edge the key falls through to the
     * platform focus search (towards «Закрыть»).
     */
    private fun buildSessionCard(index: Int, row: TvSessionBrowserPolicy.SessionRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_focus_sidebar_item)
            isFocusable = true
            isClickable = true
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { onSessionRowPicked(row) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@setOnKeyListener false
                }
                val target = TvSessionBrowserPolicy.move(index, sessionRowViews.size, delta)
                val view = sessionRowViews.getOrNull(target)
                if (view != null && target != index) {
                    view.requestFocus()
                    true
                } else false
            }
            TvFocusLift.attach(this)
        }
        card.addView(TextView(this).apply {
            text = row.title
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.mars_text_strong))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        row.subtitle?.let { subtitle ->
            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.meta?.let { meta ->
            card.addView(TextView(this).apply {
                text = meta
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.mars_status_info))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        return card
    }

    /** A row was activated: close the dialog and load that session. */
    private fun onSessionRowPicked(row: TvSessionBrowserPolicy.SessionRow) {
        closeSessionBrowser()
        openSession(row.id)
    }

    /**
     * Loads one session and continues the chat inside it (mobile `loadSessionById` parity,
     * minus drafts/reattach which the TV client does not have): the current turn is torn
     * down exactly like «Новый чат» (server cancel first — the TV has no stream re-attach),
     * the fetched detail binds into [sessionFlow] via [ChatEvent.SessionBound] so the next
     * send reuses that session_id, and the persisted transcript renders as bubbles.
     */
    private fun openSession(sessionId: String) {
        val credential = store.read() ?: return
        cancelServerTurnBestEffort()
        stream?.cancel()
        stream = null
        stopping = false
        if (::interactive.isInitialized) interactive.dismissAll()
        val token = ++sessionLoadToken
        statusView.text = OPENING_SESSION
        statusView.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = SessionRepository().getSessionDetail(BASE_URL, credential, sessionId)
            // The user opened something newer mid-fetch («Новый чат», another dialog):
            // that navigation owns the screen now — never clobber it with a stale load.
            if (token != sessionLoadToken) return@launch
            when (val outcome = TvSessionBrowserPolicy.loadOutcome(detail)) {
                is TvSessionBrowserPolicy.LoadOutcome.Failed -> {
                    statusView.text = outcome.message
                    statusView.visibility = View.VISIBLE
                    input.requestFocus()
                }
                is TvSessionBrowserPolicy.LoadOutcome.Open -> {
                    sessionFlow.startNewChat()
                    sessionFlow.onEvent(ChatEvent.SessionBound(outcome.detail.id))
                    messages.removeAllViews()
                    TvSessionBrowserPolicy.transcript(outcome.detail).forEach { row ->
                        append(row.author, row.text, row.isUser)
                    }
                    renderSendControl()
                    renderStatus()
                    input.text.clear()
                    input.requestFocus()
                    scrollDown()
                }
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val SEND_LABEL = "Отправить"
        private const val STOP_LABEL = "Остановить"
        private const val STOPPING = "Останавливаю…"
        private const val LOADING_SESSIONS = "Загружаю список…"
        private const val OPENING_SESSION = "Открываю диалог…"

        /** Process-wide scope for fire-and-forget server cancels that must outlive the Activity. */
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
