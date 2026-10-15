package ru.mars.network.repository

import ru.mars.network.model.KanbanTaskEvent

/**
 * Maps decoded SSE frames of `GET /api/kanban/events/stream` onto [KanbanStreamFrame].
 *
 * Wire contract (verified against the bridge's stream handler):
 * - `event: hello` + `data: {"cursor": N, "board": "default"}` — opening frame;
 *   tells the client the stream is live and which cursor to resume from;
 * - `id: N` + `event: events` + `data: {"events": [...], "cursor": N}` — a batch;
 *   the `id` field is the resume cursor the browser would send back automatically.
 *
 * Kept separate from [SseChunkParser] (transport: byte fragmentation, BOM, comments)
 * and from the repository (transport I/O) because this is PURE JSON-shape mapping and
 * must be JVM-testable without a socket. A `: keepalive` comment never reaches here —
 * the chunk parser drops data-less frames — so an unknown frame means the server grew
 * a new frame kind, and it is surfaced as [KanbanStreamFrame.Unknown] rather than
 * silently mis-parsed as an empty batch (which would look like "stream is fine, board
 * just stopped changing" and hide the drift).
 */
internal object KanbanStreamParser {

    /** Maps one decoded chunk of frames, in order, skipping data-less ones. */
    fun frames(frames: List<SseChunkParser.Frame>): List<KanbanStreamFrame> =
        frames.mapNotNull { frame(it) }

    /** Maps a single decoded frame, or null when it carries nothing actionable. */
    fun frame(frame: SseChunkParser.Frame): KanbanStreamFrame? {
        val payload = TolerantJson.parseMap(frame.data) ?: return null
        return when (frame.event) {
            "hello" -> KanbanStreamFrame.Hello(
                cursor = TolerantJson.long(payload, "cursor"),
                board = TolerantJson.string(payload, "board"),
            )

            "events" -> KanbanStreamFrame.Events(
                events = TolerantJson.listField(payload, "events").mapNotNull(::parseEvent),
                // Prefer the frame's own `id:` — that is the value a browser would echo
                // back as Last-Event-ID, so trusting it keeps manual-resume and
                // auto-resume identical. Fall back to the body cursor when absent.
                cursor = frame.id?.toLongOrNull() ?: TolerantJson.long(payload, "cursor"),
            )

            else -> KanbanStreamFrame.Unknown(frame.event)
        }
    }

    /**
     * One task event. Only [KanbanTaskEvent.id] and [KanbanTaskEvent.taskId] are load
     * bearing on the board (identity + which card to refresh); a malformed entry is
     * dropped instead of aborting the batch, so one bad row cannot stall the live feed.
     */
    private fun parseEvent(entry: Any?): KanbanTaskEvent? {
        val map = entry as? Map<*, *> ?: return null
        val kind = TolerantJson.string(map, "kind").orEmpty()
        val id = TolerantJson.longOrNull(map, "id") ?: return null
        return KanbanTaskEvent(
            id = id,
            taskId = TolerantJson.string(map, "task_id").orEmpty(),
            runId = TolerantJson.longOrNull(map, "run_id"),
            kind = kind,
            payload = TolerantJson.mapField(map, "payload"),
            createdAt = TolerantJson.longOrNull(map, "created_at"),
        )
    }
}
