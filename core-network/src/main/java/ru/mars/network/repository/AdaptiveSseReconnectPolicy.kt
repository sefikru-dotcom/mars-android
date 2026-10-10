package ru.mars.network.repository

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Stateful reconnect policy for a live SSE stream: jittered exponential backoff with a
 * retry budget that RESETS as soon as the stream proves alive again.
 *
 * Difference from [StreamRecoveryBackoffPolicy]: that one is a stateless calculator
 * (the caller owns the attempt counter and must remember to reset it); this class owns
 * the failure streak itself and encodes the one rule that matters for long-lived chat
 * streams — the FIRST successfully received token/event means the connection is healthy,
 * so the backoff ladder and the retry budget start over from scratch. Without that reset
 * a stream that reconnects fine for days would eventually exhaust a lifetime budget and
 * refuse to recover from an ordinary cell-tower handover.
 *
 * Loop contract (single caller thread expected; all state transitions are synchronized,
 * so a callback from another thread is still safe):
 * ```
 * while (true) {
 *     try {
 *         openStream(onEvent = { policy.onStreamEvent() })   // resets the streak
 *         break
 *     } catch (t: Throwable) {
 *         val plan = policy.onStreamFailure() ?: break        // budget exhausted -> give up
 *         sleep(plan.delayMs)
 *     }
 * }
 * ```
 *
 * Delay schedule: `initialDelayMs * factor^attempt` capped at [maxDelayMs] with symmetric
 * jitter of ±[jitterFraction] — identical to the fleet-wide convention (jitter is what
 * stops every phone from stampeding the backend in lockstep after a server restart).
 * The attempt index is 0-based over the CURRENT failure streak: first failure →
 * ~[initialDelayMs], and the growth is iterative so absurd streak counts can never
 * overflow [Long].
 *
 * Pure JVM: no Android/OkHttp dependencies, deterministic with a seeded [Random] and an
 * injected clock — covered by JVM unit tests.
 */
class AdaptiveSseReconnectPolicy(
    initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    factor: Double = DEFAULT_FACTOR,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    private val random: Random = Random.Default,
) {

    /** First reconnect delay (failure #1) before jitter, ms. */
    val initialDelayMs: Long = initialDelayMs.coerceAtLeast(0L)

    /** Hard ceiling for any computed delay, ms. Always >= [initialDelayMs]. */
    val maxDelayMs: Long = maxDelayMs.coerceAtLeast(this.initialDelayMs)

    /** Exponential growth factor per consecutive failure. Always >= 1.0. */
    val factor: Double = factor.coerceAtLeast(1.0)

    /** Reconnect budget within one failure streak; 0 disables reconnecting entirely. */
    val maxRetries: Int = maxRetries.coerceAtLeast(0)

    /** Symmetric jitter amplitude as a fraction of the base delay, in 0.0..1.0. */
    val jitterFraction: Double = jitterFraction.coerceIn(0.0, 1.0)

    /** Length of the current failure streak; 0 = healthy / freshly reset. */
    @Volatile
    private var failureStreak: Int = 0

    /**
     * Decision returned by [onStreamFailure]: which reconnect attempt comes next
     * (1-based within the streak) and how long to wait before it.
     */
    data class ReconnectPlan(val attempt: Int, val delayMs: Long)

    /**
     * Registers a failed stream attempt and decides the next step: a [ReconnectPlan]
     * while the budget lasts, `null` once [maxRetries] consecutive failures accumulated
     * (the caller should surface "connection lost" and stop retrying until the user or a
     * network change restarts the flow via [reset]).
     */
    @Synchronized
    fun onStreamFailure(): ReconnectPlan? {
        if (failureStreak >= maxRetries) return null
        val plan = ReconnectPlan(attempt = failureStreak + 1, delayMs = delayForStreak(failureStreak))
        failureStreak += 1
        return plan
    }

    /**
     * Registers the FIRST successful stream token/event (or a clean stream open): the
     * connection proved alive, so the failure streak — and with it the backoff ladder
     * and the retry budget — resets to zero. Idempotent; cheap enough to call on every
     * event, but calling it once per successful attach is the intended usage.
     *
     * @return true when this call actually reset a non-zero streak (useful for logging).
     */
    @Synchronized
    fun onStreamEvent(): Boolean {
        if (failureStreak == 0) return false
        failureStreak = 0
        return true
    }

    /** Delay for the next reconnect after [failureStreak] recorded failures (no mutation). */
    @Synchronized
    fun nextDelayMs(): Long = delayForStreak(failureStreak)

    /** Whether another reconnect is still within budget for the current streak. */
    fun canRetry(): Boolean = failureStreak < maxRetries

    /** Current consecutive-failure count; 0 right after [onStreamEvent]/[reset]. */
    fun consecutiveFailures(): Int = failureStreak

    /**
     * Manual full reset (new user-initiated send, network switch, app foreground):
     * forgets the streak so the next failure starts the ladder over.
     */
    @Synchronized
    fun reset() {
        failureStreak = 0
    }

    /** `initialDelayMs * factor^streakIndex` capped at [maxDelayMs], plus symmetric jitter. */
    private fun delayForStreak(streakIndex: Int): Long {
        val base = baseDelayMs(streakIndex.coerceAtLeast(0))
        if (jitterFraction <= 0.0) return base
        val spread = base * jitterFraction
        val jitter = (random.nextDouble() * 2.0 - 1.0) * spread
        return (base + jitter).roundToLong().coerceIn(0L, maxDelayMs)
    }

    /** Iterative growth: huge streak indices saturate at the cap instead of overflowing. */
    private fun baseDelayMs(streakIndex: Int): Long {
        if (maxDelayMs <= 0L) return 0L
        var delay = initialDelayMs.coerceAtMost(maxDelayMs)
        repeat(streakIndex) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay = (delay.toDouble() * factor).roundToLong().coerceAtMost(maxDelayMs)
        }
        return delay
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS: Long = 500L
        const val DEFAULT_MAX_DELAY_MS: Long = 30_000L
        const val DEFAULT_FACTOR: Double = 2.0
        const val DEFAULT_MAX_RETRIES: Int = 8
        const val DEFAULT_JITTER_FRACTION: Double = 0.2
    }
}
