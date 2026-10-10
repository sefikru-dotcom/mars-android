package ru.mars.tv

/** Kind of live stream the network monitor watches (chat SSE vs. music playback). */
enum class TvStreamKind { CHAT, MUSIC }

/**
 * Health verdict of a watched stream:
 * - [HEALTHY]: events/frames are flowing (or the stream is idle — nothing to heal);
 * - [STALL_WARNING]: silence is approaching the stall threshold — informational only;
 * - [STALLED]: no events for longer than the threshold — recovery is required;
 * - [NETWORK_LOST]: the network itself is gone — reconnect attempts must wait for it.
 */
enum class TvStreamHealth { HEALTHY, STALL_WARNING, STALLED, NETWORK_LOST }

/**
 * Outcome of [TvNetworkMonitorPolicy.classify]: the health verdict plus a stable
 * machine-readable [reason] tag (`idle`, `network_lost`, `events_flowing`,
 * `stall_warning`, `stall_threshold_reached`) for tests and logs.
 */
data class TvStreamHealthResult(val health: TvStreamHealth, val reason: String) {
    /** True when the verdict must be handed to [TvStreamRecoveryPolicy] for action. */
    val requiresRecovery: Boolean
        get() = health == TvStreamHealth.STALLED || health == TvStreamHealth.NETWORK_LOST
}

/**
 * Pure network/stream health policy for the TV chat and music screens (no Android,
 * no Connectivity callbacks, no I/O — JVM-testable, [TvPlaybackModePolicy] parity).
 * The host feeds the observable facts (is the stream active, is the network up, how
 * long since the last event) and gets a [TvStreamHealthResult] verdict; every
 * recovery decision derived from it lives in [TvStreamRecoveryPolicy].
 *
 * Thresholds are kind-specific because the two surfaces stall differently: the chat
 * SSE stream emits token/reasoning events continuously, while music playback buffers
 * ahead and tolerates longer gaps between progress callbacks.
 */
object TvNetworkMonitorPolicy {

    /** Silence budget of the chat SSE stream before it counts as stalled. */
    const val CHAT_STALL_THRESHOLD_MS = 8_000L

    /** Silence budget of music playback before it counts as stalled. */
    const val MUSIC_STALL_THRESHOLD_MS = 15_000L

    /** Fraction of the stall threshold at which a warning (not an action) is raised. */
    const val WARNING_FRACTION = 0.6

    /** Stall threshold for [kind]. */
    fun stallThresholdMs(kind: TvStreamKind): Long = when (kind) {
        TvStreamKind.CHAT -> CHAT_STALL_THRESHOLD_MS
        TvStreamKind.MUSIC -> MUSIC_STALL_THRESHOLD_MS
    }

    /** Silence at which [kind] escalates from HEALTHY to STALL_WARNING. */
    fun warningThresholdMs(kind: TvStreamKind): Long =
        (stallThresholdMs(kind) * WARNING_FRACTION).toLong()

    /**
     * Full verdict from the raw monitor facts:
     * - stream not active -> HEALTHY/`idle` (nothing to heal, even without network);
     * - network down -> NETWORK_LOST/`network_lost` (beats any silence measurement —
     *   a stall caused by a dead radio must be recovered as a network loss);
     * - otherwise the silence span decides (see [classifyBySilence]).
     *
     * Negative [msSinceLastEvent] (clock adjustments) is clamped to zero, never guessed around.
     */
    fun classify(
        streamActive: Boolean,
        networkAvailable: Boolean,
        msSinceLastEvent: Long,
        kind: TvStreamKind,
    ): TvStreamHealthResult {
        if (!streamActive) return TvStreamHealthResult(TvStreamHealth.HEALTHY, "idle")
        if (!networkAvailable) return TvStreamHealthResult(TvStreamHealth.NETWORK_LOST, "network_lost")
        return classifyBySilence(msSinceLastEvent, kind)
    }

    /**
     * Verdict from the silence span alone (stream assumed active, network assumed up):
     * `>= stallThreshold` -> STALLED, `>= warningThreshold` -> STALL_WARNING,
     * otherwise HEALTHY. Boundaries are inclusive on the escalation side.
     */
    fun classifyBySilence(msSinceLastEvent: Long, kind: TvStreamKind): TvStreamHealthResult {
        val elapsed = msSinceLastEvent.coerceAtLeast(0L)
        val stall = stallThresholdMs(kind)
        val warning = warningThresholdMs(kind)
        return when {
            elapsed >= stall -> TvStreamHealthResult(TvStreamHealth.STALLED, "stall_threshold_reached")
            elapsed >= warning -> TvStreamHealthResult(TvStreamHealth.STALL_WARNING, "stall_warning")
            else -> TvStreamHealthResult(TvStreamHealth.HEALTHY, "events_flowing")
        }
    }

    /**
     * Verdict from absolute timestamps for hosts that track "last event at" instead of
     * pre-computed silence. [lastEventAtMs] `null` means "no event arrived yet", so the
     * stream start ([streamStartedAtMs]) becomes the silence baseline. Backwards clocks
     * (now < baseline, NTP corrections) clamp to zero silence instead of going negative.
     */
    fun classifyByTimestamps(
        lastEventAtMs: Long?,
        streamStartedAtMs: Long,
        nowMs: Long,
        kind: TvStreamKind,
    ): TvStreamHealthResult {
        val baseline = lastEventAtMs ?: streamStartedAtMs
        return classifyBySilence(nowMs - baseline, kind)
    }

    /** Whether [health] on its own demands recovery (host-side shortcut, mirrors the data class property). */
    fun requiresRecovery(health: TvStreamHealth): Boolean =
        health == TvStreamHealth.STALLED || health == TvStreamHealth.NETWORK_LOST
}
