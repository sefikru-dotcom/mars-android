package ru.mars.tv

import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator
import java.util.Locale

/**
 * Result of the ensure-session gate TvChatActivity runs before the FIRST chat/start.
 * The live backend requires a real session_id on POST /api/chat/start and never mints
 * sessions itself, so a null id must never reach ChatRepository.sendMessage — it is
 * created here via POST /api/session/new (WebUI contract parity; mirrors app-mobile's
 * FirstSessionFlow).
 */
sealed interface TvSessionResult {
    /** A usable session id: either the already-bound one or a freshly created one. */
    data class Ready(val sessionId: String) : TvSessionResult
    /** Session creation failed; [message] is the reason to surface verbatim on screen. */
    data class Failed(val message: String) : TvSessionResult
}

/**
 * Pure session + stream state holder for the TV chat screen: no Android, no HTTP —
 * [SessionCreator] is the only I/O seam, so the whole "null id -> create -> bind /
 * fail with reason" contract and the status formatting are unit-testable on the JVM.
 *
 * The Activity feeds every SSE event into [onEvent] and reads back:
 * - [sessionId] — the bound session for the next chat/start call;
 * - [statusText] — the compact reasoning status line (WebUI parity:
 *   «Марс размышляет…», «Выполняет: read_file») — empty means "nothing to show";
 * - [contextUsageText] — the token-fill pill text, null while the window is unknown.
 */
class TvChatSessionFlow(
    private val creator: SessionCreator,
    private val baseUrl: String,
) {
    /** Currently bound session id; null until preflight or a SessionBound event binds one. */
    var sessionId: String? = null
        private set

    /** Compact status line for the TV status strip; empty string hides the strip. */
    var statusText: String = ""
        private set

    /** Latest metering frame; null until the stream reports usage. */
    var contextUsage: ChatEvent.ContextUsage? = null
        private set

    /** [contextUsage] formatted for the header pill; null when the window is unknown. */
    var contextUsageText: String? = null
        private set

    val hasSession: Boolean get() = !sessionId.isNullOrBlank()

    /**
     * Pre-send preflight: reuses the bound id or mints a fresh session. Guarantees that
     * after [TvSessionResult.Ready] the flow holds a non-blank [sessionId] for chat/start.
     */
    suspend fun ensureSession(credential: ConsumedDeviceCredential): TvSessionResult {
        sessionId?.takeIf { it.isNotBlank() }?.let { return TvSessionResult.Ready(it) }
        statusText = CREATING_SESSION
        return when (val outcome = creator.createSession(baseUrl, credential)) {
            is CreateSessionOutcome.Success -> {
                sessionId = outcome.session.id
                statusText = TYPING
                TvSessionResult.Ready(outcome.session.id)
            }
            is CreateSessionOutcome.Failure -> {
                statusText = ""
                TvSessionResult.Failed(outcome.message)
            }
        }
    }

    /** Called when the user message hits the screen, before the preflight/network work. */
    fun beginTurn() {
        statusText = TYPING
    }

    /**
     * Feeds one SSE event: binds the session, captures the metering frame and refreshes
     * the status line. Events without status meaning (SessionBound, StreamStarted,
     * ContextUsage, SteerLeftover) never clobber the status set by an earlier event.
     */
    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SessionBound -> sessionId = event.sessionId
            is ChatEvent.ContextUsage -> {
                contextUsage = event
                contextUsageText = formatContextUsage(event)
            }
            else -> Unit
        }
        statusFor(event)?.let { statusText = it }
    }

    /** «Новый чат»: drops the session binding, metering and status; next send mints fresh. */
    fun startNewChat() {
        sessionId = null
        contextUsage = null
        contextUsageText = null
        statusText = ""
    }

    companion object {
        const val CREATING_SESSION = "Создаю сессию…"
        const val TYPING = "Марс печатает…"

        /** Status-line text for one SSE event; null when the event carries no status. */
        fun statusFor(event: ChatEvent): String? = when (event) {
            is ChatEvent.Thought -> "Марс размышляет…"
            is ChatEvent.ToolCall -> "Выполняет: ${event.name}"
            is ChatEvent.ToolResult -> "Выполнил: ${event.name}"
            is ChatEvent.Chunk -> TYPING
            is ChatEvent.Phase -> phaseLabel(event.name)
            is ChatEvent.ApprovalRequired -> "Ожидает подтверждения"
            is ChatEvent.ClarifyRequired -> "Ожидает уточнения"
            ChatEvent.Done -> "Ответ получен"
            is ChatEvent.Error -> "Ошибка: ${event.message}"
            else -> null
        }

        /** WebUI-parity human labels for raw phase names (mirrors ChatActivity.phaseLabel). */
        fun phaseLabel(name: String): String = when {
            name.contains("tool", true) || name.contains("command", true) -> "Выполняет команду…"
            name.contains("think", true) || name.contains("reason", true) -> "Анализирует задачу…"
            else -> "Марс: $name"
        }

        /**
         * `NN% · 12.0k/128.0k` pill text; null when the context window is unknown —
         * an unresolved window must not become a fake percentage (#1436 parity).
         */
        fun formatContextUsage(usage: ChatEvent.ContextUsage): String? {
            if (usage.maxTokens <= 0) return null
            return "${usage.percent}% · ${formatTokens(usage.usedTokens)}/${formatTokens(usage.maxTokens)}"
        }

        /** WebUI `_fmtTokens` parity: `0` for <=0, `12.3k` from 1000, `1.5M` from 1e6. */
        fun formatTokens(n: Int): String = when {
            n <= 0 -> "0"
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
            else -> n.toString()
        }
    }
}
