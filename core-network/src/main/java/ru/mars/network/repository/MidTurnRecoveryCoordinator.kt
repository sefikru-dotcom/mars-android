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
 * - [ServerHealthPolicy] maps the latest `GET /health` probe onto a [HealthLevel]; while
 *   the server measures OFFLINE an armed immediate retry is NOT consumed (the radio came
 *   back but the backend is still down — a 0 ms hammer helps nobody), so the one-shot
 *   survives until the server actually recovers.
 *
 * Decision table ([decide]), first match wins:
 * 0. server-side terminal error frame
 *    (`ChatEvent.Error` with `ErrorOrigin.SERVER`,
 *    reported via [StreamFailure.Origin.SERVER_EVENT]) → [RecoveryDecision.GiveUp]
 *    (the agent/runtime itself failed on a HEALTHY connection — reconnecting cannot
 *    fix it, and its arbitrary message text must never be parsed as a network verdict)
 * 1. local network unavailable                → [RecoveryDecision.WaitForNetwork]
 * 2. HTTP 404 / anchored "stream not found"   → [RecoveryDecision.ReloadSession]
 *    (the journal expired server-side — nothing to resume; re-read history instead)
 * 3. failure class is non-retryable (401/403,
 *    other 4xx, deliberate cancel, code bug)   → [RecoveryDecision.GiveUp]
 * 4. per-turn reattach cap reached without
 *    VERIFIED stream progress                  → [RecoveryDecision.GiveUp]
 *    (safety net: replay-only reconnects that keep resetting the streak via id-less
 *    frames can otherwise loop forever — see [onStreamFrame])
 * 5. reconnect budget exhausted                → [RecoveryDecision.GiveUp]
 * 6. otherwise                                 → [RecoveryDecision.ReattachAfter] with the
 *    policy delay, the 1-based attempt number and the resume cursor to put into
 *    `Last-Event-ID` (from [StreamResumeTracker.lastEventId], fed via [StreamFailure.lastEventId]).
 *    A server-directed wait (`Retry-After` on 429/503, carried as the anchored
 *    `"(retry-after <N>s)"` marker in the error message — [RetryAfterPolicy]) FLOORS the
 *    delay and overrides the network-transition immediate retry for that decision.
 *
 * [RecoveryDecision.ReattachAfter] ALWAYS means re-subscribing to the existing stream
 * with `attachToStream(…, lastEventId)` — a GET. It must NEVER be implemented as
 * re-running `sendMessage` (`POST /api/chat/start`): an automatic POST replay would
 * start a SECOND turn. A failure of the start POST itself (before any `StreamStarted`)
 * is not a mid-turn failure and must not be fed to [decide] at all.
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
    /**
     * Hard per-turn cap on [RecoveryDecision.ReattachAfter] decisions. The streak budget
     * alone is not enough: id-less liveness frames reset it ([onStreamHealthy]), so a
     * pathological attach that keeps delivering replayed/unusable frames and dying could
     * loop forever. Only a frame with VERIFIED forward progress ([onStreamFrame] with an
     * event id newer than the high-water cursor) or a manual [reset] (new turn) re-arms
     * the cap. Appended last with a default → source-compatible for existing callers.
     */
    private val maxReattachesPerTurn: Int = DEFAULT_MAX_REATTACHES_PER_TURN,
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
     * found"` — status codes and the anchored 404 marker are recognized there too), the
     * resume cursor ([lastEventId], straight from [StreamResumeTracker]) and — the
     * transport failure seam — WHERE the failure came from ([origin]).
     *
     * [origin] contract (default `null` keeps pre-seam call sites source-compatible):
     * - `null` — unknown/legacy: the failure is presumed transport-level (a throwable
     *   from the SSE read, or `describeNetworkError`/`describeHttpError` text produced by
     *   `ChatRepository` itself). Message-only failures are classified through an
     *   IOException carrier, exactly as before.
     * - [Origin.TRANSPORT] — explicitly transport-level, same treatment as `null`.
     * - [Origin.SERVER_EVENT] — a `ChatEvent.Error` with `ErrorOrigin.SERVER` (the
     *   server's SSE `error`/`apperror` frame): TERMINAL, always [RecoveryDecision.GiveUp].
     *   Its message is arbitrary server text (tool output, "file not found", tracebacks)
     *   and is never parsed for HTTP codes or 404 markers.
     * Callers MUST forward `ChatEvent.Error.origin == SERVER` as [Origin.SERVER_EVENT];
     * otherwise an agent error becomes a pointless network retry loop.
     */
    data class StreamFailure(
        val throwable: Throwable? = null,
        val errorMessage: String? = null,
        val lastEventId: String? = null,
        val origin: Origin? = null,
    ) {
        /** Provenance seam; see [StreamFailure] KDoc. Appended with a default. */
        enum class Origin { TRANSPORT, SERVER_EVENT }
    }

    @Volatile
    private var networkAvailable: Boolean = true

    @Volatile
    private var lastHealth: HealthLevel? = null

    /**
     * High-water resume cursor: the newest event id VERIFIED as delivered this turn
     * (from [StreamFailure.lastEventId] — the tracker acks it — or from [onStreamFrame]).
     * Replayed frames at or below it are duplicates and must not reset the retry budget.
     */
    private var highWaterEventId: String? = null

    /** [RecoveryDecision.ReattachAfter] decisions handed out since the last verified progress/[reset]. */
    private var reattachesThisTurn: Int = 0

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
        // 0. A server-side terminal error frame (`error`/`apperror` → ChatEvent.Error with
        //    ErrorOrigin.SERVER): the connection was healthy, the AGENT failed. Reattaching
        //    cannot fix it and its arbitrary message text (tool output, "… not found",
        //    tracebacks) must never be parsed for HTTP codes or gone-stream markers.
        if (failure.origin == StreamFailure.Origin.SERVER_EVENT) {
            val label = failure.errorMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { ": ${it.take(120)}" }
                .orEmpty()
            return RecoveryDecision.GiveUp("server-side stream error$label")
        }

        if (!networkAvailable) return RecoveryDecision.WaitForNetwork

        // The tracker's acked cursor is at least as fresh as the last DELIVERED frame:
        // keep the high-water mark in sync even if the caller never feeds [onStreamFrame].
        if (RunJournalCursor.isAfter(failure.lastEventId, highWaterEventId)) {
            highWaterEventId = failure.lastEventId
        }

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

        // Hard per-turn safety net: without VERIFIED forward progress ([onStreamFrame]
        // with an id newer than the high-water cursor) the streak budget alone can be
        // kept alive forever by id-less liveness frames on a replay-only reconnect.
        if (reattachesThisTurn >= maxReattachesPerTurn.coerceAtLeast(0)) {
            return RecoveryDecision.GiveUp(
                "per-turn reattach cap (${maxReattachesPerTurn.coerceAtLeast(0)}) reached " +
                    "without verified stream progress"
            )
        }

        val plan = reconnect.onStreamFailure()
            ?: return RecoveryDecision.GiveUp(
                "reconnect budget exhausted after ${reconnect.consecutiveFailures()} attempts"
            )

        // A server-directed wait (`Retry-After` on 429/503, carried by describeHttpError
        // as the anchored "(retry-after <N>s)" message marker — see RetryAfterPolicy)
        // FLOORS the delay: hammering a throttling gateway burns the reconnect budget in
        // minutes and loses a turn whose journal is still resumable. The one-shot
        // immediate-retry arm does NOT override the directive and is left unconsumed for
        // the next failure.
        val retryAfterMs = retryAfterMillis(failure)

        // Network just came back → one-shot 0 ms retry, UNLESS the latest probe says the
        // SERVER is still down: then keep the arm (unconsumed) and use the ladder delay.
        val delayMs = when {
            retryAfterMs != null -> maxOf(plan.delayMs, retryAfterMs)
            lastHealth != HealthLevel.OFFLINE && backoff.isImmediateRetryArmed() ->
                backoff.nextDelayMs(plan.attempt - 1) // consumes the arm, returns 0
            else -> plan.delayMs
        }
        return RecoveryDecision.ReattachAfter(
            delayMs = delayMs,
            attempt = plan.attempt,
            resumeFromEventId = failure.lastEventId,
        ).also { reattachesThisTurn += 1 }
    }

    /**
     * The server-directed wait of [failure] in ms, from the anchored
     * `"(retry-after <N>s)"` marker (RetryAfterPolicy) in the mapped error message or any
     * throwable message of the cause chain; `null` when no directive is present. Values
     * are already clamped to [RetryAfterPolicy.MAX_RETRY_AFTER_SECONDS] by the parser.
     */
    private fun retryAfterMillis(failure: StreamFailure): Long? {
        val texts = buildList {
            failure.errorMessage?.let { add(it) }
            generateSequence(failure.throwable) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .mapNotNull { it.message }
                .forEach { add(it) }
        }
        // Longest directive wins when wrappers stacked several messages.
        val seconds = texts.mapNotNull { RetryAfterPolicy.parseMarkerSeconds(it) }.maxOrNull()
        return seconds?.times(1_000L)
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
     * Feeds a `GET /health` probe result; returns the computed [HealthLevel] (pure
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
     *
     * LEGACY, unconditional: prefer [onStreamFrame] with the frame's SSE id — a reattach
     * that only REPLAYS already-acked journal frames must not reset the budget (that is
     * the infinite-loop hole this seam closes). [onStreamHealthy] stays for callers that
     * genuinely observed live liveness without an event id, and it never re-arms the
     * per-turn reattach cap.
     * @return true when a non-zero streak was actually reset (useful for logging).
     */
    @Synchronized
    fun onStreamHealthy(): Boolean = reconnect.onStreamEvent()

    /**
     * Progress-aware health signal: feed EVERY id-bearing frame's SSE `id:` here (the same
     * value the `onEventId` transport callback receives — wiring `StreamResumeTracker` and
     * this method from one lambda is the intended usage), or `null` for an id-less frame.
     *
     * Budget-reset rule (anti-loop):
     * - id NEWER than the high-water cursor ([RunJournalCursor.isAfter]) → verified forward
     *   progress: the streak resets AND the per-turn reattach cap re-arms;
     * - id at/below the high-water cursor → REPLAYED duplicate (already delivered before
     *   the drop): nothing resets, a reconnect loop feeding only replays still exhausts
     *   the budget and ends in [RecoveryDecision.GiveUp];
     * - `null` (id-less frame) → live-liveness proof: the streak resets (journaled frames
     *   always carry ids, so an id-less frame came from the live tail), but the per-turn
     *   cap does NOT re-arm — id-less frames are not verifiable progress.
     *
     * @return true when this call actually reset a non-zero failure streak.
     */
    @Synchronized
    fun onStreamFrame(eventId: String? = null): Boolean {
        if (eventId.isNullOrBlank()) return reconnect.onStreamEvent()
        if (!RunJournalCursor.isAfter(eventId, highWaterEventId)) return false
        highWaterEventId = eventId
        reattachesThisTurn = 0
        reconnect.onStreamEvent()
        return true
    }

    /** Newest event id verified as delivered this turn (see [onStreamFrame]); `null` before the first. */
    @Synchronized
    fun highWaterEventId(): String? = highWaterEventId

    /** [RecoveryDecision.ReattachAfter] decisions since the last verified progress / [reset]. */
    @Synchronized
    fun reattachesSinceProgress(): Int = reattachesThisTurn

    /**
     * Manual reset for a NEW user-initiated turn: forgets the failure streak, the health
     * verdict, the cursor high-water mark and the per-turn reattach cap. Connectivity
     * state is an environment fact and is NOT touched.
     */
    @Synchronized
    fun reset() {
        reconnect.reset()
        lastHealth = null
        highWaterEventId = null
        reattachesThisTurn = 0
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
     * Gone-stream detection, ANCHORED on shapes this client/server pair actually produces
     * (verified read-only against `/opt/hermes-webui/api/routes.py` `_handle_sse_stream`):
     * - `HTTP 404` / `HTTP 404: stream not found` — `describeHttpError` output for the
     *   server's `{"error":"stream not found"}` 404 (caught by [httpStatus], kept here
     *   only as belt-and-braces);
     * - the exact server reason phrases: `stream not found`, `no such stream`,
     *   `unknown stream`;
     * - a bare `404` ONLY when the message also talks about a `stream` (legacy shapes).
     *
     * Deliberately NOT matched: broad `not found` / bare `404` — tool output and browsed
     * web content routinely contain "file not found", "404 page not found" etc., and
     * misreading those as a gone journal would throw away a resumable live turn
     * (ReloadSession) mid-answer. Server `error` frames never even reach this check —
     * [StreamFailure.Origin.SERVER_EVENT] short-circuits to GiveUp first.
     */
    private fun isStreamNotFoundMarker(failure: StreamFailure): Boolean {
        val text = buildString {
            failure.errorMessage?.let { append(it).append('\n') }
            generateSequence(failure.throwable) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .mapNotNull { it.message }
                .forEach { append(it).append('\n') }
        }.lowercase()
        if (text.isBlank()) return false
        if (STREAM_NOT_FOUND_REGEX.containsMatchIn(text)) return true
        return BARE_404_REGEX.containsMatchIn(text) && "stream" in text
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

        /**
         * Default per-turn cap on [RecoveryDecision.ReattachAfter] without verified
         * progress. Generous enough for a long turn with many handovers, small enough
         * that a replay-only reconnect loop terminates in seconds, not forever.
         */
        const val DEFAULT_MAX_REATTACHES_PER_TURN: Int = 24

        /** Matches `describeHttpError` output ("HTTP 502: bad gateway") and retrofit's HttpException. */
        private val HTTP_CODE_REGEX = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)

        /** Anchored gone-stream phrases (server JSON reason + legacy shapes). */
        private val STREAM_NOT_FOUND_REGEX =
            Regex("""\b(stream not found|no such stream|unknown stream)\b""")

        /** Bare 404 — only counts as gone-stream together with the word "stream". */
        private val BARE_404_REGEX = Regex("""\b404\b""")
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
