package ru.mars.network.repository

import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary

/**
 * The SINGLE deserialization point for `todo_state` snapshots (hermes-webui
 * api/todo_state.py `_normalize_snapshot` → `{todos, summary, version}`,
 * VERSION = 1). Both producers share it — no duplicated parsing logic:
 *
 * - Cold: `GET /api/session` → `session.todo_state` (SessionRepository),
 *   snapshot without the live envelope.
 * - Live: SSE `event: todo_state` whose `data:` is the same snapshot inside a
 *   `{session_id, stream_id, source, ts}` envelope (StreamEventMapper.todoState
 *   → the ChatRepository `onTodoState` transport callback).
 *
 * Contract (server-side todo_state.py + WebUI parity static/ui.js):
 * - `todos` MUST be a list — anything else is not a snapshot (`null`, never a
 *   crash); an EMPTY list IS a valid snapshot (the latest write cleared it),
 *   never treat empty as absent;
 * - item `status` is preserved VERBATIM (including unknown values): mapping a
 *   status onto a rendering fallback is UI-layer work (`todoStatusKey` in the
 *   WebUI), not a data-layer decision; `text` is tolerated as a `content`
 *   alias; missing status defaults to `pending`;
 * - `summary` counts default to 0 when absent.
 */
object TodoStateParser {

    /** Parses an already-decoded JSON object (cold detail field or live SSE `data:`). */
    fun parse(o: Map<*, *>?): TodoSnapshot? {
        o ?: return null
        val todos = o["todos"] as? List<*> ?: return null
        val summary = o["summary"] as? Map<*, *>
        return TodoSnapshot(
            todos = todos.mapNotNull { (it as? Map<*, *>)?.let(::todoItem) },
            summary = TodoSummary(
                total = intOf(summary, "total"),
                pending = intOf(summary, "pending"),
                inProgress = intOf(summary, "in_progress"),
                completed = intOf(summary, "completed"),
                cancelled = intOf(summary, "cancelled"),
            ),
            version = (o["version"] as? Number)?.toInt() ?: 1,
            tsEpochSeconds = (o["ts"] as? Number)?.toDouble(),
            // Live envelope; absent (null) on the cold path by design.
            sessionId = string(o, "session_id"),
            streamId = string(o, "stream_id"),
            source = string(o, "source"),
        )
    }

    /** Parses the raw JSON payload of a live `todo_state` SSE frame (`data:` line). */
    fun parsePayload(payload: String): TodoSnapshot? = parse(StreamEventMapper.parseObject(payload))

    private fun todoItem(o: Map<*, *>) = TodoItem(
        // Moshi decodes JSON numbers as Double; normalize integral ids so `1` stays "1" (WebUI String(t.id) parity).
        id = (o["id"] as? Number)?.let { if (it.toDouble() == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
            ?: string(o, "id"),
        // WebUI parity (ui.js todoContent): `text` is tolerated as a content alias.
        content = string(o, "content", "text").orEmpty(),
        status = string(o, "status") ?: "pending",
    )

    private fun intOf(o: Map<*, *>?, key: String) = (o?.get(key) as? Number)?.toInt() ?: 0

    private fun string(o: Map<*, *>, vararg keys: String) =
        keys.firstNotNullOfOrNull { o[it]?.toString()?.takeIf(String::isNotBlank) }
}
