package ru.mars.network.repository

import ru.mars.network.model.HealthLevel
import java.io.IOException

/**
 * Pure-JVM decision core for recovering a CHAT TURN whose SSE stream broke mid-flight
 * (cell handover, server restart, proxy drop). Same contract as the policies it builds
 * on — [StreamRecoveryBackoffPolicy], [AdaptiveSseReconnectPolicy], [ServerHealthPolicy]:
 * plain facts in, a sealed [RecoveryDecision] out. No Android, no coroutines, no clocks —
 * the caller owns threading and sleeping, which keeps every path deterministically
 * unit-testable.
 *
 * Division of labour (no duplicated ladders):
 * - [StreamRecoveryBackoffPolicy] classifies the failure (retryable? auth? bug?) via
 *   [StreamRecoveryBackoffPolicy.shouldRetry] and owns the network-transition one-shot
 *   immediate retry ([StreamRecoveryBackoffPolicy.onNetworkChanged]);
 * - [AdaptiveSseReconnectPolicy] owns the failure streak: its budget resets on the first
 *   healthy stream event ([onStreamHealthy]), and it supplies the jittered delay for the
 *   next reattach ([AdaptiveSseReconnectPolicy.onStreamFailure]);
 * - [ServerHealthPolicy] maps the latest `/api/health` probe onto a [HealthLevel]; while
 *   the server measures OFFLINE an armed immediate retry is NOT consumed (the radio came
 *   back but the backend is still down — a 0 ms hammer helps nobody), so the one-shot
 *   survives until the server actually recovers.
 *
 * Decision table ([decide]), first match wins:
 * 1. local network unavailable                → [RecoveryDecision.WaitForNetwork]
 * 2. HTTP 404 / "stream not found"            → [RecoveryDecision.ReloadSession]
 *    (the journal expired server-side — nothing to resume; re-read history instead)
 * 3. failure class is non-retryable (401/403,
 *    other 4xx, deliberate cancel, code bug)   → [RecoveryDecision.GiveUp]
 * 4. reconnect budget exhausted                → [RecoveryDecision.GiveUp]
 * 5. otherwise                                 → [RecoveryDecision.ReattachAfter] with the
 *    policy delay, the 1-based attempt number and the resume cursor to put into
 *    `Last-Event-ID` (from [StreamResumeTracker.lastEventId], fed via [StreamFailure.lastEventId]).
 *
 * Expected caller loop:
 * ```
 * val failure = StreamFailure(throwable, errorMessage, tracker.lastEventId)
 * when (val d = coordinator.decide(failure)) {
 *     is ReattachAfter -> { sleep(d.delayMs); attachToStream(..., d.resumeFromEventId) { ... } }
 *     WaitForNetwork   -> { /* park until onNetworkChanged(true), then decide(failure) again */ }
 *     ReloadSession    -> { /* GET /api/session, re-render transcript */ }
 *     is GiveUp        -> { /* surface d.reason, stop retrying */ }
 * }
 * ```
 * All mutating methods are [@Synchronized]: connectivity callbacks and the stream reader
 * run on different threads.
 */
class MidTurnRecoveryCoordinator(
    private val backoff: StreamRecoveryBackoffPolicy = StreamRecoveryBackoffPolicy(),
    private val reconnect: AdaptiveSseReconnectPolicy = AdaptiveSseReconnectPolicy(),
) {

    /**
     * What the caller should do next after a broken stream. Sealed on purpose: app-layer
     * `when` expressions stay exhaustive and a new variant is a compile error downstream,
     * never a silent fallthrough.
     */
    sealed interface RecoveryDecision {
        /**
         * Re-open the stream after [delayMs] (attempt is 1-based within the current
         * failure streak), passing [resumeFromEventId] as the `Last-Event-ID` header so
         * the server replays the journal gap. `resumeFromEventId == null` means no cursor
         * was recorded — the reattach is live-only plus the server's snapshot backfill.
         */
        data class ReattachAfter(
            val delayMs: Long,
            val attempt: Int,
            val resumeFromEventId: String? = null,
        ) : RecoveryDecision

        /**
         * Local connectivity is down: reattaching now cannot work. The caller parks the
         * recovery, feeds [onNetworkChanged] from its connectivity listener and re-runs
         * [decide] with the SAME failure once the network is back (the backoff policy
         * then grants the one-shot immediate retry).
         */
        data object WaitForNetwork : RecoveryDecision

        /**
         * The stream is gone server-side (404 / expired journal). Resume is impossible —
         * reload the session (`GET /api/session`) and re-render the transcript; a turn
         * still running re-binds via `active_stream_id`.
         */
        data object ReloadSession : RecoveryDecision

        /** Stop retrying; [reason] is a short machine-readable-ish label for logs/UI. */
        data class GiveUp(val reason: String) : RecoveryDecision
    }

    /**
     * Facts about one stream failure. All fields optional so any layer can report what it
     * has: the transport exception ([throwable], from the `catch` around the SSE read),
     * the mapped `ChatEvent.Error` text ([errorMessage], e.g. `"HTTP 404: stream not
     * found"` — status codes and the 404 marker are recognized there too), and the resume
     * cursor ([lastEventId], straight from [StreamResumeTracker]).
     */
    data class StreamFailure(
        val throwable: Throwable? = null,
        val errorMessage: String? = null,
        val lastEventId: String? = null,
    )

    @Volatile
    private var networkAvailable: Boolean = true

    @Volatile
    private var lastHealth: HealthLevel? = null

    /** Latest health verdict from [onServerProbe]; `null` until the first probe. */
    val lastHealthLevel: HealthLevel? get() = lastHealth

    /** Current consecutive-failure streak (0 = healthy / freshly reset). */
    fun consecutiveFailures(): Int = reconnect.consecutiveFailures()

    /**
     * Decides the next recovery step for [failure]; see the class KDoc for the table.
     * Mutates the reconnect streak (and possibly consumes the armed immediate retry),
     * so call it exactly ONCE per failure — not in a polling loop.
     */
    @Synchronized
    fun decide(failure: StreamFailure): RecoveryDecision {
        if (!networkAvailable) return RecoveryDecision.WaitForNetwork

        val status = httpStatus(failure)
        if (status == HTTP_STREAM_NOT_FOUND || isStreamNotFoundMarker(failure)) {
            // The journal expired: streaks and ladders are meaningless for a NEW turn.
            reconnect.reset()
            return RecoveryDecision.ReloadSession
        }

        // Classification only: attempt 0 isolates "this KIND of failure is hopeless"
        // from "the budget ran out" (checked separately via the reconnect policy).
        // Message-only failures (the mapped ChatEvent.Error text, no throwable) are
        // classified through an IOException carrier so the policy's HTTP-code regex and
        // IOException bucket apply verbatim: "HTTP 401: …" → non-retryable, "HTTP 502: …"
        // → retryable, code-less text → plain network bucket → retryable.
        val classifier = failure.throwable
            ?: failure.errorMessage?.takeIf { it.isNotBlank() }?.let { MessageCarrier(it) }
        if (!backoff.shouldRetry(0, classifier)) {
            return RecoveryDecision.GiveUp(giveUpReason(status, classifier))
        }

        val plan = reconnect.onStreamFailure()
            ?: return RecoveryDecision.GiveUp(
                "reconnect budget exhausted after ${reconnect.consecutiveFailures()} attempts"
            )

        // Network just came back → one-shot 0 ms retry, UNLESS the latest probe says the
        // SERVER is still down: then keep the arm (unconsumed) and use the ladder delay.
        val delayMs =
            if (lastHealth != HealthLevel.OFFLINE && backoff.isImmediateRetryArmed()) {
                backoff.nextDelayMs(plan.attempt - 1) // consumes the arm, returns 0
            } else {
                plan.delayMs
            }
        return RecoveryDecision.ReattachAfter(
            delayMs = delayMs,
            attempt = plan.attempt,
            resumeFromEventId = failure.lastEventId,
        )
    }

    /**
     * Feeds a connectivity change (from `ConnectivityManager`/`NetworkCallback`). The
     * unavailable → available transition arms the one-shot immediate retry inside
     * [backoff]; the next [decide] then reattaches with delay 0 (health permitting).
     */
    @Synchronized
    fun onNetworkChanged(available: Boolean) {
        networkAvailable = available
        backoff.onNetworkChanged(available)
    }

    /**
     * Feeds a `/api/health` probe result; returns the computed [HealthLevel] (pure
     * [ServerHealthPolicy.evaluateStatus] — error or non-2xx means OFFLINE regardless of
     * latency). An OFFLINE verdict suppresses immediate-retry consumption in [decide].
     */
    @Synchronized
    fun onServerProbe(isSuccessful: Boolean, latencyMs: Long, error: Throwable? = null): HealthLevel {
        val level = ServerHealthPolicy.evaluateStatus(isSuccessful, latencyMs, error)
        lastHealth = level
        return level
    }

    /**
     * The stream proved alive (first frame/token of a successful attach): resets the
     * failure streak, the backoff ladder and the retry budget.
     * @return true when a non-zero streak was actually reset (useful for logging).
     */
    @Synchronized
    fun onStreamHealthy(): Boolean = reconnect.onStreamEvent()

    /**
     * Manual reset for a NEW user-initiated turn: forgets the failure streak and the
     * health verdict. Connectivity state is an environment fact and is NOT touched.
     */
    @Synchronized
    fun reset() {
        reconnect.reset()
        lastHealth = null
    }

    /** First `HTTP <code>` occurrence in the error message or the throwable cause chain. */
    private fun httpStatus(failure: StreamFailure): Int? {
        failure.errorMessage?.let { message ->
            HTTP_CODE_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        val chain = generateSequence(failure.throwable) { it.cause }.take(MAX_CAUSE_DEPTH)
        return chain.mapNotNull { it.message }.firstNotNullOfOrNull { message ->
            HTTP_CODE_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * App-level 404 marker parity (`StreamReattachPolicy`): the mapped `ChatEvent.Error`
     * text can carry the gone-stream verdict as a bare `404` or "not found" without the
     * `HTTP` prefix (legacy shapes), so the substring markers are checked too.
     */
    private fun isStreamNotFoundMarker(failure: StreamFailure): Boolean {
        val text = failure.errorMessage?.lowercase() ?: return false
        return Regex("""\b404\b""").containsMatchIn(text) || "not found" in text
    }

    private fun giveUpReason(status: Int?, throwable: Throwable?): String = when {
        status == 401 || status == 403 -> "auth rejected (HTTP $status)"
        status != null -> "non-retryable HTTP $status"
        throwable != null -> "non-retryable failure: ${throwable.javaClass.simpleName}"
        else -> "non-retryable failure"
    }

    companion object {
        /** Server's verdict for an expired/unknown stream journal (`routes.py`: 404 "stream not found"). */
        const val HTTP_STREAM_NOT_FOUND: Int = 404

        /** Matches `describeHttpError` output ("HTTP 502: bad gateway") and retrofit's HttpException. */
        private val HTTP_CODE_REGEX = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
        private const val MAX_CAUSE_DEPTH = 10
    }

    /**
     * Adapter that makes a message-only failure (the mapped `ChatEvent.Error` text, no
     * throwable) classifiable by [StreamRecoveryBackoffPolicy.shouldRetry]: as an
     * IOException it lands in the retryable network bucket, while an embedded
     * "HTTP <code>" is picked up by the policy's status regex (401/403/4xx → non-retryable,
     * 408/425/429/5xx → retryable).
     */
    private class MessageCarrier(message: String) : IOException(message)
}
