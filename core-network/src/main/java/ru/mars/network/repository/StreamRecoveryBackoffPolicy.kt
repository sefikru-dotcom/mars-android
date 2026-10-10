package ru.mars.network.repository

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Pure retry/backoff policy for recovering a broken SSE stream. No Android or OkHttp
 * dependencies — same contract as [ServerHealthPolicy]: plain numbers in, decisions out,
 * trivially unit-testable.
 *
 * Delay schedule: exponential growth `initialDelayMs * factor^attempt` capped at
 * [maxDelayMs], plus symmetric jitter of ±[jitterFraction] (default ±20 %). Jitter is
 * mandatory in practice: after a cell-tower handover or a server restart EVERY connected
 * phone in the fleet fails at the same instant, and identical retry timers would stampede
 * the backend in lockstep. Randomizing the delay spreads the reconnection wave.
 *
 * Network-change awareness: [onNetworkChanged] tracks connectivity; an
 * unavailable -> available transition (Wi-Fi <-> LTE handover finished, tunnel came back)
 * arms a one-shot immediate retry — [nextDelayMs] returns 0 once, because waiting out a
 * backoff that was accumulated for the OLD network makes no sense on a fresh one. The
 * caller is expected to also reset its own attempt counter on that transition.
 *
 * Retryability ([shouldRetry]) walks the cause chain (OkHttp wraps the real failure, same
 * approach as [describeNetworkError]) and decides:
 * - HTTP 401/403/404 -> NO. Auth is rejected or the stream id is gone; hammering the
 *   server cannot fix that (codes arrive as [describeHttpError] messages "HTTP <code>: …"
 *   or as retrofit `HttpException`, whose message has the same shape).
 * - HTTP 408/425/429 and 5xx -> YES: transient server/gateway state.
 * - Other 4xx -> NO: a client-side bug, retrying repeats it.
 * - [SocketTimeoutException] -> YES: the radio stalled mid-stream, classic mobile case.
 * - Plain [InterruptedIOException] -> NO: that is a deliberate call cancellation /
 *   thread interrupt and must be respected, not retried.
 * - Any other [IOException] -> YES: this module has no dedicated `NetworkException`
 *   type, IOException (UnknownHostException, SocketException, EOFException, SSL errors,
 *   HTTP/2 stream resets) IS the network-failure bucket here.
 * - `null` throwable -> YES (stream ended without a classified error; safe to reattach).
 * - Anything else (IllegalStateException, JSON parse bugs) -> NO: retrying a code bug
 *   only loops it.
 *
 * [attempt] is the 0-based index of the failure that just happened; retries stop once
 * `attempt >= maxAttempts`.
 */
class StreamRecoveryBackoffPolicy(
    initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    factor: Double = DEFAULT_FACTOR,
    maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    private val random: Random = Random.Default,
) {
    /** First retry delay (attempt 0) before jitter, ms. */
    val initialDelayMs: Long = initialDelayMs.coerceAtLeast(0L)

    /** Hard ceiling for any computed delay, ms. Always >= [initialDelayMs]. */
    val maxDelayMs: Long = maxDelayMs.coerceAtLeast(this.initialDelayMs)

    /** Exponential growth factor per attempt. Always >= 1.0. */
    val factor: Double = factor.coerceAtLeast(1.0)

    /** Retry budget: `shouldRetry` returns false once `attempt >= maxAttempts`. */
    val maxAttempts: Int = maxAttempts.coerceAtLeast(0)

    /** Symmetric jitter amplitude as a fraction of the base delay, in 0.0..1.0. */
    val jitterFraction: Double = jitterFraction.coerceIn(0.0, 1.0)

    // Connectivity callbacks and retry loops run on different threads; volatile is enough
    // (a lost race on the arm flag merely costs one extra backoff wait, never correctness).
    @Volatile
    private var networkAvailable: Boolean = true

    @Volatile
    private var immediateRetryArmed: Boolean = false

    /**
     * Delay before retry number [attempt] (0-based). Returns 0 exactly once after an
     * unavailable -> available network transition, otherwise the jittered exponential
     * schedule, always within `0..maxDelayMs`.
     */
    fun nextDelayMs(attempt: Int): Long {
        if (consumeImmediateRetry()) return 0L
        val base = baseDelayMs(attempt)
        if (jitterFraction <= 0.0) return base
        val spread = base * jitterFraction
        val jitter = (random.nextDouble() * 2.0 - 1.0) * spread
        return (base + jitter).roundToLong().coerceIn(0L, maxDelayMs)
    }

    /**
     * Feeds a connectivity change (e.g. from `ConnectivityManager`/`NetworkCallback`).
     * Only the unavailable -> available transition matters: it arms the one-shot
     * immediate retry. Repeated `available = true` calls change nothing.
     */
    fun onNetworkChanged(available: Boolean) {
        val wasAvailable = networkAvailable
        networkAvailable = available
        if (available && !wasAvailable) immediateRetryArmed = true
    }

    /** Whether an immediate (0 ms) retry is currently armed by a network recovery. */
    fun isImmediateRetryArmed(): Boolean = immediateRetryArmed

    /**
     * Classifies a stream failure: may another attempt help? See the class KDoc for the
     * full decision table. `null` [throwable] means "stream broke without a classified
     * error" and is retried.
     */
    fun shouldRetry(attempt: Int, throwable: Throwable?): Boolean {
        if (attempt >= maxAttempts) return false
        if (throwable == null) return true
        val chain = generateSequence(throwable) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

        httpStatusCode(chain)?.let { return isRetryableStatus(it) }

        // Order matters (most specific first): SocketTimeoutException IS an
        // InterruptedIOException, but a timeout is transient while a plain
        // InterruptedIOException means "cancelled on purpose".
        if (chain.any { it is SocketTimeoutException }) return true
        if (chain.any { it is InterruptedIOException }) return false
        if (chain.any { it is IOException }) return true
        return false
    }

    private fun consumeImmediateRetry(): Boolean {
        if (!immediateRetryArmed) return false
        immediateRetryArmed = false
        return true
    }

    /** `initialDelayMs * factor^attempt` capped at [maxDelayMs]; iterative to avoid overflow. */
    private fun baseDelayMs(attempt: Int): Long {
        if (maxDelayMs <= 0L) return 0L
        var delay = initialDelayMs.coerceAtMost(maxDelayMs)
        repeat(attempt.coerceAtLeast(0)) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay = (delay.toDouble() * factor).roundToLong().coerceAtMost(maxDelayMs)
        }
        return delay
    }

    /** First "HTTP <code>" occurrence in the cause-chain messages, if any. */
    private fun httpStatusCode(chain: List<Throwable>): Int? =
        chain.mapNotNull { it.message }.firstNotNullOfOrNull { message ->
            HTTP_CODE_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull()
        }

    private fun isRetryableStatus(code: Int): Boolean = when {
        code in NON_RETRYABLE_STATUS_CODES -> false
        code in RETRYABLE_STATUS_CODES -> true
        code in 500..599 -> true
        code in 400..499 -> false
        else -> true // exotic codes (proxy weirdness, 3xx on a stream) — treat as transient
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS: Long = 500L
        const val DEFAULT_MAX_DELAY_MS: Long = 30_000L
        const val DEFAULT_FACTOR: Double = 2.0
        const val DEFAULT_MAX_ATTEMPTS: Int = 10
        const val DEFAULT_JITTER_FRACTION: Double = 0.2

        /** Auth/permission/gone-stream: no retry can succeed. */
        private val NON_RETRYABLE_STATUS_CODES = setOf(401, 403, 404)

        /** Explicitly retryable client-side codes: timeout, too-early, rate limit. */
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429)

        /** Matches `describeHttpError` output ("HTTP 502: bad gateway") and retrofit's HttpException. */
        private val HTTP_CODE_REGEX = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)

        private const val MAX_CAUSE_DEPTH = 10
    }
}
