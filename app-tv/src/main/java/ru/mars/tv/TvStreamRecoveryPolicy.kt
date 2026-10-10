package ru.mars.tv

import kotlin.math.min
import kotlin.math.pow

/** Exponential-backoff tuning for [TvStreamRecoveryPolicy]; every field is a plain bound. */
data class TvBackoffConfig(
    /** Delay of the first retry attempt. */
    val baseDelayMs: Long = 1_000L,
    /** Growth factor applied per attempt (2.0 -> 1s, 2s, 4s, ...). */
    val multiplier: Double = 2.0,
    /** Hard ceiling for a single delay, jitter included. */
    val maxDelayMs: Long = 30_000L,
    /** After this many failed attempts the policy gives up until a manual reset. */
    val maxAttempts: Int = 6,
    /** Max relative delay perturbation (0.2 -> +/-20%) used to de-sync TV fleet retries. */
    val jitterFraction: Double = 0.2,
)

/**
 * What the host should do next per [TvStreamRecoveryPolicy.planNextAttempt]:
 * - [NONE]: stream is fine (or only warned) — do nothing;
 * - [WAIT_FOR_NETWORK]: radio is down — schedule the retry but don't hammer the socket;
 * - [RECONNECT_KEEP_SESSION]: reopen the stream bound to the SAME session id (chat turn context survives);
 * - [RESUME_PLAYBACK]: reopen the stream AND restore the audio position (music never restarts a track);
 * - [GIVE_UP]: attempts exhausted — surface an error card; state is still preserved for a manual retry.
 */
enum class TvRecoveryAction { NONE, WAIT_FOR_NETWORK, RECONNECT_KEEP_SESSION, RESUME_PLAYBACK, GIVE_UP }

/**
 * One scheduled recovery attempt. [keepSessionId]/[keepPlaybackPosition] are explicit
 * contracts to the host: recovery must never drop the bound session or the audio
 * position — the TV user is watching from across the room and loses context otherwise.
 */
data class TvRecoveryPlan(
    val action: TvRecoveryAction,
    val delayMs: Long,
    val attempt: Int,
    val keepSessionId: Boolean,
    val keepPlaybackPosition: Boolean,
    val reason: String,
)

/**
 * Pure reconnection policy for TV chat/music streams (no Android, no sockets, no timers —
 * the host owns the clock and the transport, this object only decides). Pairs with
 * [TvNetworkMonitorPolicy]: feed its [TvStreamHealth] verdict here and execute the plan.
 *
 * Guarantees:
 * - exponential backoff `base * multiplier^(attempt-1)` capped at [TvBackoffConfig.maxDelayMs],
 *   with optional deterministic jitter in [0.0 .. 1.0] (the host may pass a random draw);
 * - the active session id and the audio playback position are NEVER discarded by recovery —
 *   every plan states [TvRecoveryPlan.keepSessionId]/[TvRecoveryPlan.keepPlaybackPosition];
 * - after [TvBackoffConfig.maxAttempts] the policy gives up instead of burning the radio,
 *   but keeps the state so a manual retry ([TvStreamRecoveryTracker.onRecovered]) can resume.
 */
object TvStreamRecoveryPolicy {

    /**
     * Backoff delay for a 1-based [attempt]. Non-positive attempts are clamped to the
     * first attempt; the delay never exceeds [TvBackoffConfig.maxDelayMs]. [jitter] is a
     * deterministic draw in [0.0 .. 1.0] (0.5 -> no perturbation; values outside the
     * range are clamped), mapped to +/-[TvBackoffConfig.jitterFraction] of the delay.
     */
    fun backoffDelayMs(
        attempt: Int,
        config: TvBackoffConfig = TvBackoffConfig(),
        jitter: Double = 0.0,
    ): Long {
        val a = attempt.coerceAtLeast(1)
        val raw = config.baseDelayMs.toDouble() * config.multiplier.pow((a - 1).toDouble())
        val capped = min(raw, config.maxDelayMs.toDouble())
        val j = (jitter.coerceIn(0.0, 1.0) * 2.0 - 1.0) * config.jitterFraction.coerceIn(0.0, 1.0)
        return (capped * (1.0 + j)).toLong().coerceIn(0L, config.maxDelayMs)
    }

    /**
     * Decision for the next (1-based) [attempt] given the monitor [health]:
     * - HEALTHY / STALL_WARNING -> [TvRecoveryAction.NONE]: a warning alone never tears
     *   down a live stream (`stream_healthy`, `stall_warning_hold`);
     * - attempt past [TvBackoffConfig.maxAttempts] -> [TvRecoveryAction.GIVE_UP]
     *   (`max_attempts_exceeded`) with zero delay — the host shows an error card;
     * - NETWORK_LOST -> [TvRecoveryAction.WAIT_FOR_NETWORK] (`network_lost_backoff`);
     * - STALLED -> [TvRecoveryAction.RESUME_PLAYBACK] (`stalled_resume_playback`) when a
     *   session and a playback position exist, otherwise
     *   [TvRecoveryAction.RECONNECT_KEEP_SESSION] (`stalled_reconnect_keep_session`, or
     *   `stalled_reconnect_fresh` when no session was bound yet).
     *
     * [keepSessionId] follows [hasSession] and [keepPlaybackPosition] follows a non-null
     * [playbackPositionMs] on EVERY plan, including GIVE_UP — state survives the failure.
     */
    fun planNextAttempt(
        attempt: Int,
        health: TvStreamHealth,
        hasSession: Boolean,
        playbackPositionMs: Long?,
        config: TvBackoffConfig = TvBackoffConfig(),
        jitter: Double = 0.0,
    ): TvRecoveryPlan {
        val keepPosition = playbackPositionMs != null
        if (health == TvStreamHealth.HEALTHY) {
            return TvRecoveryPlan(TvRecoveryAction.NONE, 0L, 0, hasSession, keepPosition, "stream_healthy")
        }
        if (health == TvStreamHealth.STALL_WARNING) {
            return TvRecoveryPlan(TvRecoveryAction.NONE, 0L, 0, hasSession, keepPosition, "stall_warning_hold")
        }
        val nextAttempt = attempt.coerceAtLeast(1)
        if (nextAttempt > config.maxAttempts) {
            return TvRecoveryPlan(TvRecoveryAction.GIVE_UP, 0L, nextAttempt, hasSession, keepPosition, "max_attempts_exceeded")
        }
        val delay = backoffDelayMs(nextAttempt, config, jitter)
        return when (health) {
            TvStreamHealth.NETWORK_LOST ->
                TvRecoveryPlan(TvRecoveryAction.WAIT_FOR_NETWORK, delay, nextAttempt, hasSession, keepPosition, "network_lost_backoff")
            TvStreamHealth.STALLED -> when {
                hasSession && keepPosition ->
                    TvRecoveryPlan(TvRecoveryAction.RESUME_PLAYBACK, delay, nextAttempt, true, true, "stalled_resume_playback")
                hasSession ->
                    TvRecoveryPlan(TvRecoveryAction.RECONNECT_KEEP_SESSION, delay, nextAttempt, true, false, "stalled_reconnect_keep_session")
                else ->
                    TvRecoveryPlan(TvRecoveryAction.RECONNECT_KEEP_SESSION, delay, nextAttempt, false, keepPosition, "stalled_reconnect_fresh")
            }
            TvStreamHealth.HEALTHY, TvStreamHealth.STALL_WARNING ->
                error("unreachable: handled above")
        }
    }
}

/**
 * Small stateful wrapper over [TvStreamRecoveryPolicy] for the activities: it owns the
 * attempt counter and the bound session id so recovery can never lose them. The clock
 * and the transport stay with the host — [planNext] only says what to do and when.
 *
 * Contract: [onRecovered] resets the attempt counter but KEEPS [sessionId] (the same
 * conversation continues after a reconnect); only an explicit [reset] drops both.
 */
class TvStreamRecoveryTracker(
    private val config: TvBackoffConfig = TvBackoffConfig(),
) {
    /** Number of consecutive failed attempts so far; 0 while the stream is healthy. */
    var attempt: Int = 0
        private set

    /** Session bound by [bindSession]; deliberately survives every recovery cycle. */
    var sessionId: String? = null
        private set

    val hasSession: Boolean get() = !sessionId.isNullOrBlank()

    /** Binds (or, with null/blank, unbinds) the session the recovery must preserve. */
    fun bindSession(id: String?) {
        sessionId = id?.takeIf { it.isNotBlank() }
    }

    /**
     * Plan for the current health verdict. Schedules an attempt (and bumps [attempt])
     * only for actionable verdicts; NONE plans leave the counter untouched. Once the
     * attempts are exhausted this keeps returning GIVE_UP until [onRecovered]/[reset].
     */
    fun planNext(
        health: TvStreamHealth,
        playbackPositionMs: Long? = null,
        jitter: Double = 0.0,
    ): TvRecoveryPlan {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = attempt + 1,
            health = health,
            hasSession = hasSession,
            playbackPositionMs = playbackPositionMs,
            config = config,
            jitter = jitter,
        )
        if (plan.action != TvRecoveryAction.NONE) attempt = plan.attempt.coerceAtLeast(attempt)
        return plan
    }

    /** Stream is healthy again: clear the backoff, keep the session (manual retry reuses this). */
    fun onRecovered() {
        attempt = 0
    }

    /** Full teardown (activity finish / explicit logout of the session): clear everything. */
    fun reset() {
        attempt = 0
        sessionId = null
    }
}
