package ru.mars.network.repository

/**
 * Pure SSE resume cursor: remembers the last `id:` field seen on the wire so a broken
 * stream can be reattached with the `Last-Event-ID` header and the server can replay its
 * event journal from that point instead of starting live-only.
 *
 * Wire it through the `ChatRepository.sendMessage` / `attachToStream` `onEventId`
 * callback, which fires for EVERY SSE frame that carries an id (tokens, tool frames,
 * todo_state, title — not only chunks), then feed the value back into
 * `attachToStream(lastEventId = tracker.lastEventId)`.
 *
 * Semantics follow the WHATWG EventSource "last event ID" buffer:
 * - a frame WITHOUT an `id:` field ([onFrameId] with `null`) leaves the buffer untouched —
 *   the id persists across id-less frames, so a reconnect resumes from the last frame
 *   that actually carried one;
 * - an EMPTY/blank `id:` line resets the buffer (spec behaviour); this is represented as
 *   `null` here so the caller sends no `Last-Event-ID` header rather than a blank one;
 * - any other value replaces the buffer verbatim — ids are opaque to the client
 *   (in practice `<stream_id>:<seq>`, but the tracker never parses them).
 *
 * Pure JVM: no Android, no coroutines. Thread-safe via [@Synchronized] — the SSE reader
 * thread feeds ids while the UI/recovery thread may read [lastEventId] or [reset].
 */
class StreamResumeTracker {

    private var lastId: String? = null

    /**
     * Records the `id:` field of the next SSE frame. `null` means "frame carried no id"
     * (buffer kept); a blank value clears the buffer per spec; anything else becomes the
     * new resume cursor.
     */
    @Synchronized
    fun onFrameId(id: String?) {
        when {
            id == null -> Unit // no id field on this frame: keep the current cursor
            id.isBlank() -> lastId = null // explicit empty `id:` resets the buffer (WHATWG)
            else -> lastId = id
        }
    }

    /** Current resume cursor, or `null` when nothing resumable was seen since [reset]. */
    val lastEventId: String?
        @Synchronized get() = lastId

    /**
     * Drops the cursor. Call on a fresh turn/stream: ids are only meaningful within the
     * journal of ONE stream, so a cursor from a previous stream must never leak into a
     * new `Last-Event-ID` header.
     */
    @Synchronized
    fun reset() {
        lastId = null
    }
}
