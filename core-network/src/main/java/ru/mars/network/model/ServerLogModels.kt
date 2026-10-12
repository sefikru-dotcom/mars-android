package ru.mars.network.model

/**
 * Wire models for `GET /api/logs` (contract verified read-only against
 * `/opt/hermes-webui/api/route_logs.py`).
 *
 * The server whitelists exactly three log files and four tail sizes; anything else
 * is a 400 `{"error": "Unknown log file"}` or a silent tail reset to 200. The
 * client-side enums mirror both whitelists so a request can never be malformed.
 *
 * Response: `{file, tail, lines[], truncated, total_bytes, mtime, hint}`. A log file
 * that does not exist yet is a VALID 200 answer with `lines: []` and a non-empty
 * `hint` — not an error.
 */

/** Server-side whitelist keys of the `?file=` query param. */
enum class ServerLogFile(val wireKey: String) {
    AGENT("agent"),
    ERRORS("errors"),
    GATEWAY("gateway"),
}

/** Server-side whitelist of the `?tail=` query param (anything else resets to 200). */
enum class ServerLogTail(val lines: Int) {
    TAIL_100(100),
    TAIL_200(200),
    TAIL_500(500),
    TAIL_1000(1000),
}

/** One bounded tail window of a whitelisted Hermes log file. */
data class ServerLogWindow(
    val file: String = "",
    val tail: Int = 0,
    val lines: List<String> = emptyList(),
    /** `true` when the file exceeded the server's 4 MiB read cap and older lines were cut. */
    val truncated: Boolean = false,
    val totalBytes: Long = 0L,
    /** File mtime, epoch seconds (fractional); null when the file does not exist yet. */
    val mtime: Double? = null,
    /** Non-empty when the log file is missing ("Log file for X not found yet."). */
    val hint: String = "",
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * `true` when the server answered its "file does not exist yet" 200 payload
     * (empty tail, zero bytes, null mtime) — a valid settled state, not an outage.
     */
    val missingOnServer: Boolean get() = lines.isEmpty() && totalBytes == 0L && mtime == null
}
