package ru.mars.network.repository

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `Retry-After` (RFC 9110 §10.2.3) support for the chat transport and mid-turn recovery.
 *
 * Why it matters for SSE recovery: a throttling reverse proxy or a restarting backend
 * answers `429`/`503` with an explicit "come back in N seconds" directive. Without it
 * [MidTurnRecoveryCoordinator] retries on its own jittered ladder (500 ms … 30 s) and
 * burns the whole reconnect budget in ~2 minutes against a server that asked to wait —
 * ending in `GiveUp` for a turn whose journal is still perfectly resumable. Honoring the
 * directive turns that lost turn into one patient reattach.
 *
 * The header value travels from [ChatRepository] (which sees the raw response) to the
 * coordinator (which only sees the mapped [ru.mars.network.model.ChatEvent.Error] text)
 * through an ANCHORED marker appended by [describeHttpError]:
 * `"(retry-after <seconds>s)"` — [marker] formats it, [parseMarkerSeconds] reads it back.
 * The marker never shadows the app-level substring policies: it is a suffix, while
 * `AuthRejectionPolicy` ("http 401"/"unauthorized"), `StreamReattachPolicy` (`\b404\b`/
 * "not found") and the mobile transport classifier (`^http\s*\d{3}\b`) all key on the
 * message prefix or on substrings that stay intact.
 *
 * Supported wire shapes (RFC 9110): `delta-seconds` (non-negative integer) and
 * `HTTP-date` (IMF-fixdate, interpreted as GMT). Anything else — including RFC 850 and
 * asctime dates, which no current Mars server or common proxy emits — parses to `null`
 * (directive ignored, plain ladder backoff applies).
 *
 * All values are clamped to `0..`[MAX_RETRY_AFTER_SECONDS]: a bogus/huge directive must
 * not park the client forever; past the cap the stream journal has most likely expired
 * anyway and the reattach resolves cleanly via `404 -> ReloadSession`.
 *
 * Pure JVM: no Android, no OkHttp; the clock is an explicit parameter for tests.
 */
internal object RetryAfterPolicy {

    /**
     * Upper bound (10 minutes) for any honored directive — both when parsing the wire
     * header and when reading back a crafted message marker.
     */
    const val MAX_RETRY_AFTER_SECONDS: Long = 600L

    /** Anchored marker format, e.g. `"(retry-after 30s)"`. */
    private val MARKER_REGEX = Regex("""\(retry-after (\d+)s\)$""")

    private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    /**
     * Parses a raw `Retry-After` header value into wait seconds.
     *
     * @param headerValue the header as received (`null`/blank → `null`);
     * @param nowEpochSeconds the current Unix time — only needed for the `HTTP-date`
     *   shape (delta = date − now, clamped at 0 for an already-past date).
     * @return clamped `0..`[MAX_RETRY_AFTER_SECONDS] seconds, or `null` when the value is
     *   absent or unparseable (caller then applies no directive).
     */
    fun parseSeconds(
        headerValue: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): Long? {
        val value = headerValue?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { delta ->
            if (delta < 0L) return null // negative delta-seconds is not a valid directive
            return delta.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
        }
        return try {
            val dateEpochSeconds = Instant.from(HTTP_DATE.parse(value)).atZone(ZoneOffset.UTC).toEpochSecond()
            (dateEpochSeconds - nowEpochSeconds).coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_SECONDS)
        } catch (_: DateTimeParseException) {
            null
        } catch (_: RuntimeException) {
            null // e.g. DateTimeException on a malformed zone/field — treat as no directive
        }
    }

    /** Formats the message marker for [describeHttpError]; clamps defensively. */
    fun marker(seconds: Long): String =
        "(retry-after ${seconds.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)}s)"

    /**
     * Reads the [marker] back out of a `ChatEvent.Error`/exception message.
     * When several markers appear (chained wrapping), the LONGEST wait wins — the most
     * conservative reading of stacked server directives.
     * @return clamped `0..`[MAX_RETRY_AFTER_SECONDS] seconds, or `null` when absent.
     */
    fun parseMarkerSeconds(message: String?): Long? {
        message ?: return null
        return MARKER_REGEX.findAll(message)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .map { it.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) }
            .maxOrNull()
    }
}
