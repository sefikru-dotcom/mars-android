package ru.mars.network.repository

/**
 * Incremental Server-Sent-Events parser that survives arbitrary TCP fragmentation.
 *
 * Why not `BufferedReader.readLine()`: on a flaky mobile link (Wi-Fi <-> LTE handover on
 * POCO F5) a single SSE frame can be split across packets ANYWHERE — mid-line, between the
 * `\r` and `\n` of a CRLF terminator, or between two `data:` lines of one event. This
 * parser keeps every unfinished character in its internal buffer between [feed] calls, so
 * no partial state is ever dropped and no partial line is ever dispatched as complete.
 * On abrupt disconnect [end] flushes whatever complete frame was buffered, so the UI gets
 * the last parsed events before the terminal `ChatEvent.Error` instead of hanging.
 *
 * Semantics follow the SSE spec (WHATWG EventSource):
 * - line terminators: `\n`, `\r\n` and bare `\r`; a CRLF pair split across two feeds is
 *   still ONE terminator (tracked via [pendingCr]);
 * - lines starting with `:` are comments (keep-alive probes) and are ignored;
 * - fields: `data` (multi-line, joined with `\n`), `event`, `id`; unknown fields ignored;
 * - at most one leading space after the colon is stripped from the value;
 * - a frame is dispatched on a blank line, and only when at least one `data` line was
 *   seen — matches the previous behaviour: frames without data carry nothing mappable.
 */
internal class SseChunkParser {
    /** One complete SSE frame: event name, optional last-event id, joined data payload. */
    internal data class Frame(val event: String?, val id: String?, val data: String)

    private val lineBuffer = StringBuilder()
    private var pendingCr = false

    private var event: String? = null
    private var id: String? = null
    private val dataLines = mutableListOf<String>()

    /**
     * Feeds the next chunk of decoded characters (any size, split anywhere — the caller
     * decodes bytes through an InputStreamReader so multi-byte UTF-8 sequences split
     * across packets are already reassembled into chars).
     * @return frames completed by this chunk, in order.
     */
    fun feed(chunk: String): List<Frame> {
        val frames = mutableListOf<Frame>()
        for (c in chunk) {
            if (pendingCr) {
                pendingCr = false
                if (c == '\n') continue // second half of a \r\n split across packets
            }
            when (c) {
                '\n' -> endLine(frames)
                '\r' -> { endLine(frames); pendingCr = true }
                else -> lineBuffer.append(c)
            }
        }
        return frames
    }

    /**
     * Signals end-of-stream (EOF or abrupt disconnect). Flushes an unterminated trailing
     * line and a pending frame that never saw its blank line, so a server that closes the
     * socket right after the last `data:` line does not lose that final event.
     */
    fun end(): List<Frame> {
        val frames = mutableListOf<Frame>()
        if (lineBuffer.isNotEmpty()) endLine(frames)
        dispatch(frames)
        return frames
    }

    private fun endLine(frames: MutableList<Frame>) {
        val line = lineBuffer.toString()
        lineBuffer.setLength(0)
        if (line.isEmpty()) { dispatch(frames); return }
        if (line.startsWith(":")) return // SSE comment / keep-alive probe
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        // Spec: strip at most ONE leading space after the colon.
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> dataLines.add(value)
            "event" -> event = value.trim()
            "id" -> id = value.trim()
            // "retry:" and unknown fields carry no chat content — ignore.
        }
    }

    private fun dispatch(frames: MutableList<Frame>) {
        if (dataLines.isNotEmpty()) {
            frames.add(Frame(event, id, dataLines.joinToString("\n")))
        }
        event = null
        id = null
        dataLines.clear()
    }
}
