package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.ChatEvent

/**
 * Maps raw SSE frames (event name + opaque frame id + JSON payload) from
 * `/api/chat/stream` and the legacy `/chat` endpoint onto the sealed [ChatEvent] model.
 *
 * Wire contract (server: hermes-webui `api/streaming.py`):
 * - `tool`            — `{event_type, name, preview, args, tid?}` (tool started; `tid` only on the structured path)
 * - `tool_complete`   — `{event_type, name, preview, args, tid?, duration?, is_error?}` (`preview` is the result snippet)
 * - `interim_assistant` — `{text, already_streamed, reasoning_echo?}`; when `already_streamed` is true the text
 *   was already delivered via token chunks and must NOT be emitted again (frontend behaves the same).
 * - `approval` / `clarify` — attention payloads; the current sealed model has no dedicated variants and
 *   app consumers use exhaustive `when` without `else`, so they are surfaced as [ChatEvent.Thought]
 *   instead of being silently dropped.
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
            "approval" -> obj?.let { attention("Требуется подтверждение", it, "description", "title", "tool", "name", "question") }
            "clarify" -> obj?.let { clarify(it) }
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

    private fun clarify(obj: Map<String, Any?>): ChatEvent? {
        val question = stringField(obj, "question") ?: return null
        val choices = (obj["choices_offered"] as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()
        val suffix = if (choices.isEmpty()) "" else " (варианты: ${choices.joinToString(" / ")})"
        return ChatEvent.Thought("Требуется уточнение: $question$suffix")
    }

    private fun attention(prefix: String, obj: Map<String, Any?>, vararg fields: String): ChatEvent {
        val detail = fields.firstNotNullOfOrNull { stringField(obj, it) }.orEmpty()
        return ChatEvent.Thought(if (detail.isBlank()) prefix else "$prefix: $detail")
    }

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
