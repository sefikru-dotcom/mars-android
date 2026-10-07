package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload

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
 * - `title`           — `{session_id, title}`; re-binds the session id ([ChatEvent.SessionBound]).
 * - `warning`         — `{type, message}` non-fatal notice (e.g. provider fallback).
 * - `pending_steer_leftover` — `{session_id, text}`: steer text the agent never consumed; preserved as a thought.
 * - `done` is NOT terminal: `title` and `stream_end` follow it; `stream_end`/`cancel`/`error`/`apperror` are terminal.
 */
object StreamEventMapper {

    /** @return mapped event or `null` when the frame carries nothing representable (metadata/duplicate). */
    fun map(eventName: String?, eventId: String?, payload: String): ChatEvent? {
        val obj = parseObject(payload)
        return when (eventName?.trim()?.ifEmpty { null }) {
            "session", "session_bound" -> sessionId(obj, payload)?.let { ChatEvent.SessionBound(it) }
            // {session_id, title}: the title text has no carrier in ChatEvent; keep the session binding.
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
                ?.let { ChatEvent.Thought("Направление не доставлено: $it") }
            "status", "phase", "compressing", "compressed" -> phase(obj, payload, eventName)
            "token", "delta", "chunk", "message", null -> chunk(obj, payload, eventId)
            // Unknown frames (metering, todo_state, state_saved, goal, ...) carry no chat content.
            else -> null
        }
    }

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
