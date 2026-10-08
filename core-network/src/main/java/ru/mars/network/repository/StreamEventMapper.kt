package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.TodoSnapshot

/**
 * Maps raw SSE frames (event name + opaque frame id + JSON payload) from
 * `/api/chat/stream` and the legacy `/chat` endpoint onto the sealed [ChatEvent] model.
 *
 * Wire contract (server: hermes-webui `api/streaming.py`):
 * - `tool`            — `{event_type, name, preview, args, tid?}` (tool started; `tid` only on the structured path)
 * - `tool_complete`   — `{event_type, name, preview, args, tid?, duration?, is_error?}` (`preview` is the result snippet)
 * - `interim_assistant` — `{text, already_streamed, reasoning_echo?}`; when `already_streamed` is true the text
 *   was already delivered via token chunks and must NOT be emitted again (frontend behaves the same).
 * - `approval`        — `{approval_id, command, description, pattern_key(s), allow_permanent,
 *   allow_session, run_id?, _gateway_mirror_token?, pending_count, ...}` → [ChatEvent.ApprovalRequired]
 *   preserving the structured payload (ids/options) required by `POST /api/approval/respond`.
 * - `clarify`         — `{clarify_id, question, choices_offered, session_id, requested_at,
 *   timeout_seconds, expires_at}` → [ChatEvent.ClarifyRequired] preserving the structured payload
 *   required by `POST /api/clarify/respond`.
 * - `title`           — `{session_id, title}`; re-binds the session id ([ChatEvent.SessionBound]);
 *   the title TEXT has no sealed carrier and travels via [sideEvent] → [SessionTitleUpdate].
 * - `warning`         — `{type, message}` non-fatal notice (e.g. provider fallback).
 * - `pending_steer_leftover` — `{session_id, text}`: steer text the agent never consumed; typed as [ru.mars.network.model.ChatEvent.SteerLeftover].
 * - `metering`        — `{tps, tps_available, estimated, high, low, active, session_id, usage: {input_tokens,
 *   output_tokens, context_length, threshold_tokens, last_prompt_tokens,
 *   post_compression_context_tokens_estimate, ...}}` (api/metering.py `get_stats` +
 *   api/streaming.py `_live_usage_snapshot`): the `usage` block feeds the context-window
 *   indicator ([ChatEvent.ContextUsage]); percent parity with WebUI `_syncCtxIndicator`
 *   (static/ui.js): prompt tokens (`post_compression_context_tokens_estimate` when present,
 *   else `last_prompt_tokens` — NEVER the cumulative `input_tokens`, #1436) over
 *   `context_length`, clamped to 0..100.
 * - `done` is NOT terminal: `title` and `stream_end` follow it; `stream_end`/`cancel`/`error`/`apperror` are terminal.
 */
object StreamEventMapper {

    /** @return mapped event or `null` when the frame carries nothing representable (metadata/duplicate). */
    fun map(eventName: String?, eventId: String?, payload: String): ChatEvent? {
        val obj = parseObject(payload)
        return when (eventName?.trim()?.ifEmpty { null }) {
            "session", "session_bound" -> sessionId(obj, payload)?.let { ChatEvent.SessionBound(it) }
            // {session_id, title}: keep the session binding; the title TEXT has no sealed
            // carrier and is delivered via [sideEvent] → [SessionTitleUpdate].
            "title" -> sessionId(obj, payload)?.let { ChatEvent.SessionBound(it) }
            "done", "stream_end", "cancel" -> ChatEvent.Done
            "error", "apperror" -> ChatEvent.Error(errorMessage(obj, payload))
            "reasoning", "thought", "thinking" -> textOf(obj, payload)?.let { ChatEvent.Thought(it) }
            "interim_assistant" -> interimAssistant(obj, eventId)
            "tool_call", "tool" -> toolCall(obj)
            "tool_result", "tool_complete" -> toolResult(obj)
            "approval" -> approvalPayload(obj)?.let { ChatEvent.ApprovalRequired(it) }
            "clarify" -> clarifyPayload(obj)?.let { ChatEvent.ClarifyRequired(it) }
            "warning" -> (stringField(obj, "message") ?: payload.trim().ifBlank { null })
                ?.let { ChatEvent.Thought("Предупреждение: $it") }
            "pending_steer_leftover" -> stringField(obj, "text")
                ?.let { ChatEvent.SteerLeftover(it) }
            "status", "phase", "compressing", "compressed" -> phase(obj, payload, eventName)
            "metering", "context", "context_usage", "usage" -> contextUsage(obj)
            "token", "delta", "chunk", "message", null -> chunk(obj, payload, eventId)
            // Unknown frames (state_saved, ...) carry no chat content;
            // `todo_state` is deliberately NOT mapped onto ChatEvent — it is
            // delivered via [todoState] on the side channel (see below), and the
            // `title` text / `goal` / `goal_continue` frames travel via [sideEvent].
            else -> null
        }
    }

    /**
     * Live `todo_state` SSE frame (server contract: `event: todo_state`,
     * `data:` = the normalized snapshot `{todos, summary, version}` inside the
     * envelope `{session_id, stream_id, source, ts}` — hermes-webui
     * api/todo_state.py `_broadcast`).
     *
     * It has NO carrier in the sealed [ChatEvent]: app modules hold exhaustive
     * `when` expressions over ChatEvent (ChatUiState, app-tv), so a new variant
     * would break their compile. Delivery instead goes through the
     * `ChatRepository.onTodoState` transport callback, which calls this method —
     * the frame never reaches [map] and never pollutes the chat transcript.
     *
     * Parsing is delegated to [TodoStateParser] — the SAME single point the cold
     * `SessionDetail.todoState` uses (no duplicated contract).
     *
     * @return the snapshot when [eventName] is `todo_state` and the payload is a
     *   snapshot (`todos` list present); `null` for any other event name or a
     *   non-snapshot payload.
     */
    fun todoState(eventName: String?, payload: String): TodoSnapshot? =
        if (eventName?.trim() == "todo_state") TodoStateParser.parsePayload(payload) else null

    /**
     * SSE metadata frames with no sealed [ChatEvent] carrier → typed
     * [StreamSideEvent] for the `ChatRepository.onSideEvent` transport callback
     * (same delivery pattern as [todoState]), or null for any other frame.
     *
     * Handled frames (verified against hermes-webui api/streaming.py):
     * - `title` → [SessionTitleUpdate]: `{session_id, title}` — the title text
     *   [ChatEvent.SessionBound] drops. Blank/missing title → null (nothing to
     *   deliver; the session binding itself still flows through [map]).
     * - `goal` → [GoalUpdate]: `{session_id, state, message, message_key,
     *   message_args?, decision?}` with `state` ∈ `evaluating`/`continuing`/
     *   `idle` preserved verbatim.
     * - `goal_continue` → [GoalContinuation]: `{session_id, continuation_prompt,
     *   text, message, message_key, message_args, decision}` (`text` is a wire
     *   alias of `continuation_prompt`). DELIVERY ONLY — the continuation is
     *   never auto-sent by the transport; queuing it as the next user message
     *   is an explicit UI-layer decision (WebUI /goal parity).
     *
     * Independent of [map]: a `title` frame yields BOTH [ChatEvent.SessionBound]
     * (via [map]) and a [SessionTitleUpdate] (via this function).
     */
    fun sideEvent(eventName: String?, payload: String): StreamSideEvent? {
        val name = eventName?.trim()
        if (name != "title" && name != "goal" && name != "goal_continue") return null
        val obj = parseObject(payload) ?: return null
        return when (name) {
            "title" -> stringField(obj, "title")
                ?.let { SessionTitleUpdate(sessionId(obj, payload), it) }
            "goal" -> goalUpdate(obj, payload)
            else -> goalContinuation(obj, payload)
        }
    }

    /**
     * SSE `goal` → [GoalUpdate]. A frame without `state` is tolerated only when
     * it still carries a message; a frame with neither is dropped (null).
     */
    private fun goalUpdate(obj: Map<String, Any?>, payload: String): GoalUpdate? {
        val state = stringField(obj, "state")
        val message = stringField(obj, "message")
        if (state == null && message == null) return null
        return GoalUpdate(
            sessionId = sessionId(obj, payload),
            state = state.orEmpty(),
            message = message,
            messageKey = stringField(obj, "message_key"),
            messageArgs = stringList(obj["message_args"]),
            decisionJson = decisionJson(obj),
        )
    }

    /**
     * SSE `goal_continue` → [GoalContinuation]. `continuation_prompt` is
     * canonical, `text` is the wire alias; a frame with neither carries no
     * continuation and is dropped (null).
     */
    private fun goalContinuation(obj: Map<String, Any?>, payload: String): GoalContinuation? {
        val prompt = stringField(obj, "continuation_prompt") ?: stringField(obj, "text") ?: return null
        return GoalContinuation(
            sessionId = sessionId(obj, payload),
            continuationPrompt = prompt,
            message = stringField(obj, "message"),
            messageKey = stringField(obj, "message_key"),
            messageArgs = stringList(obj["message_args"]),
            decisionJson = decisionJson(obj),
        )
    }

    /** Raw compact JSON of the opaque `decision` object, null when absent. */
    private fun decisionJson(obj: Map<String, Any?>): String? =
        obj["decision"]?.let { jsonValue(it).takeIf(String::isNotBlank) }

    /**
     * Parses the context-window portion of a `metering`/`usage` frame
     * (`_live_usage_snapshot` payload, either nested under `usage` or flat).
     * Returns null when the frame carries no usable data: an unknown window
     * (`context_length` <= 0) must NOT become a fake percentage, and zero
     * prompt tokens means nothing has been measured yet (WebUI shows "·").
     */
    private fun contextUsage(obj: Map<String, Any?>?): ChatEvent.ContextUsage? {
        obj ?: return null
        @Suppress("UNCHECKED_CAST")
        val usage = (obj["usage"] as? Map<String, Any?>) ?: obj
        val maxTokens = intField(usage, "context_length", "context_window", "max_tokens") ?: return null
        if (maxTokens <= 0) return null
        // #1436 parity: the post-compression estimate (when positive) wins, then
        // last_prompt_tokens; the cumulative input_tokens is NEVER a percentage base.
        val postCompression = intField(usage, "post_compression_context_tokens_estimate")?.takeIf { it > 0 }
        val usedTokens = postCompression ?: intField(usage, "last_prompt_tokens", "tokens_used") ?: return null
        if (usedTokens <= 0) return null
        // WebUI parity: Math.round(used/window*100), clamped like the ring (pct=min(100,rawPct)).
        val percent = Math.round(usedTokens.toLong() * 100.0 / maxTokens).toInt().coerceIn(0, 100)
        return ChatEvent.ContextUsage(usedTokens, maxTokens, percent)
    }

    private fun intField(obj: Map<String, Any?>, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? Number)?.toInt() }

    private fun interimAssistant(obj: Map<String, Any?>?, eventId: String?): ChatEvent? {
        if (obj == null) return null
        // Duplicate guard: text already streamed as token chunks must not be appended twice.
        if (obj["already_streamed"] == true) return null
        val text = obj["text"]?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return ChatEvent.Chunk(text, eventId)
    }

    private fun toolCall(obj: Map<String, Any?>?): ChatEvent.ToolCall? = obj?.let { m ->
        val id = (m["tid"] ?: m["id"])?.toString()?.takeIf { it.isNotBlank() }
        val name = (m["name"] ?: m["tool"])?.toString().orEmpty()
        val args = jsonValue(m["arguments"] ?: m["args"])
        val preview = m["preview"]?.toString().orEmpty()
        val usableArgs = args.takeIf { it.isNotBlank() && it != "{}" && it != "[]" }.orEmpty()
        ChatEvent.ToolCall(id, name, usableArgs.ifBlank { preview })
    }

    private fun toolResult(obj: Map<String, Any?>?): ChatEvent.ToolResult? = obj?.let { m ->
        val id = (m["tid"] ?: m["id"])?.toString()?.takeIf { it.isNotBlank() }
        val name = (m["name"] ?: m["tool"])?.toString().orEmpty()
        // Server sends the result snippet as `preview`; legacy clients used `result`/`content`.
        val preview = m["preview"]?.toString()?.takeIf { it.isNotBlank() }
        val legacy = jsonValue(m["result"] ?: m["content"]).ifBlank { null }
        ChatEvent.ToolResult(id, name, preview ?: legacy.orEmpty())
    }

    private fun chunk(obj: Map<String, Any?>?, payload: String, eventId: String?): ChatEvent? {
        if (obj == null) return null
        val text = listOf("text", "delta", "content").firstNotNullOfOrNull { obj[it]?.toString() } ?: return null
        if (text.isEmpty()) return null
        return ChatEvent.Chunk(text, eventId)
    }

    /**
     * Parses a `clarify` payload (`api/clarify.py` entry data). Public: the same shape is served by
     * `GET /api/clarify/pending` (`{"pending": {...}}`) and reused by InteractiveRequestsRepository.
     * Returns null when the frame carries neither a question nor choices (nothing to render/answer).
     */
    fun clarifyPayload(obj: Map<String, Any?>?): ClarifyPayload? {
        obj ?: return null
        // Frontend parity (messages.js showClarifyCard): question falls back to description.
        val question = stringField(obj, "question") ?: stringField(obj, "description").orEmpty()
        val choices = stringList(obj["choices_offered"] ?: obj["choices"])
        if (question.isBlank() && choices.isEmpty()) return null
        return ClarifyPayload(
            clarifyId = stringField(obj, "clarify_id"),
            question = question,
            choices = choices,
            sessionId = stringField(obj, "session_id"),
            requestedAtEpochSeconds = (obj["requested_at"] as? Number)?.toDouble(),
            timeoutSeconds = (obj["timeout_seconds"] as? Number)?.toInt(),
            expiresAtEpochSeconds = (obj["expires_at"] as? Number)?.toDouble(),
            pendingCount = positiveInt(obj["pending_count"]) ?: 1,
        )
    }

    /**
     * Parses an `approval` payload (`tools/approval.py` data + `route_approvals.submit_pending`
     * head stamp). Public: the same shape is served by `GET /api/approval/pending`
     * (`{"pending": {...}, "pending_count": N}`) and reused by InteractiveRequestsRepository.
     * Returns null when the frame carries no command/description/tool at all (nothing to approve).
     */
    fun approvalPayload(obj: Map<String, Any?>?): ApprovalPayload? {
        obj ?: return null
        val command = stringField(obj, "command").orEmpty()
        val description = stringField(obj, "description").orEmpty()
        val tool = stringField(obj, "tool") ?: stringField(obj, "name")
        if (command.isBlank() && description.isBlank() && tool.isNullOrBlank()) return null
        val patternKeys = stringList(obj["pattern_keys"]).ifEmpty { listOfNotNull(stringField(obj, "pattern_key")) }
        return ApprovalPayload(
            approvalId = stringField(obj, "approval_id"),
            command = command,
            description = description,
            patternKeys = patternKeys,
            // Missing flags: never offer a permanent grant the server did not sanction; session-wide
            // approval is safe for every non-smart-denied prompt (tools/approval.py sets both explicitly).
            allowPermanent = obj["allow_permanent"] as? Boolean ?: false,
            allowSession = obj["allow_session"] as? Boolean ?: true,
            smartDenied = obj["smart_denied"] == true,
            runId = stringField(obj, "run_id"),
            mirrorToken = stringField(obj, "_gateway_mirror_token"),
            pendingCount = positiveInt(obj["pending_count"]) ?: 1,
            tool = tool,
            riskLevel = stringField(obj, "risk_level"),
            choices = stringList(obj["choices"]),
        )
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun positiveInt(value: Any?): Int? = (value as? Number)?.toInt()?.takeIf { it > 0 }

    private fun phase(obj: Map<String, Any?>?, payload: String, eventName: String?): ChatEvent.Phase? {
        val name = obj?.let { (it["name"] ?: it["phase"] ?: it["status"] ?: it["message"])?.toString() }
            ?.ifBlank { null }
        return ChatEvent.Phase(name ?: payload.trim().ifBlank { eventName ?: "status" })
    }

    private fun errorMessage(obj: Map<String, Any?>?, payload: String): String =
        stringField(obj, "message") ?: stringField(obj, "error") ?: payload.trim()

    private fun textOf(obj: Map<String, Any?>?, payload: String): String? = obj?.let { m ->
        listOf("text", "content", "message", "thought", "reasoning")
            .firstNotNullOfOrNull { stringField(m, it) }
    } ?: payload.trim().ifBlank { null }

    private fun sessionId(obj: Map<String, Any?>?, payload: String): String? =
        stringField(obj, "session_id") ?: stringField(obj, "id")

    private fun stringField(obj: Map<String, Any?>?, key: String): String? =
        obj?.get(key)?.toString()?.takeIf { it.isNotBlank() }

    fun parseObject(payload: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Moshi.Builder().build()
            .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
            .fromJson(payload)
    } catch (_: Throwable) {
        null
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> Moshi.Builder().build().adapter(Any::class.java).toJson(value)
    }
}
