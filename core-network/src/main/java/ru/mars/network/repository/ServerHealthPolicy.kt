package ru.mars.network.repository

import ru.mars.network.model.HealthLevel

/**
 * Pure decision rules for server health. No Android or OkHttp dependencies —
 * everything is derived from plain numbers/flags so the policy is trivially
 * unit-testable and reusable from any layer.
 *
 * Latency bands (for a given [healthyThresholdMs], default 800 ms):
 * - `latency <= threshold`                    -> [HealthLevel.HEALTHY]
 * - `threshold < latency <= threshold * 3`    -> [HealthLevel.DEGRADED]
 * - `latency > threshold * 3` or `latency < 0`-> [HealthLevel.OFFLINE]
 */
object ServerHealthPolicy {

    /** Default healthy round-trip budget in milliseconds. */
    const val DEFAULT_HEALTHY_THRESHOLD_MS: Long = 800L

    /**
     * How many multiples of the healthy threshold still count as DEGRADED
     * before the server is considered effectively OFFLINE.
     */
    const val DEGRADED_LATENCY_MULTIPLIER: Long = 3L

    /**
     * Maps a measured round-trip time onto a [HealthLevel].
     *
     * Negative latency is treated as a caller/clock error and reported as
     * [HealthLevel.OFFLINE] rather than silently accepted as healthy.
     */
    fun categorizeLatency(
        latencyMs: Long,
        healthyThresholdMs: Long = DEFAULT_HEALTHY_THRESHOLD_MS,
    ): HealthLevel = when {
        latencyMs < 0L -> HealthLevel.OFFLINE
        latencyMs <= healthyThresholdMs -> HealthLevel.HEALTHY
        latencyMs <= healthyThresholdMs * DEGRADED_LATENCY_MULTIPLIER -> HealthLevel.DEGRADED
        else -> HealthLevel.OFFLINE
    }

    /**
     * Combines transport outcome and latency into a single [HealthLevel].
     *
     * - Any [error] (timeout, DNS, connection refused, ...) -> [HealthLevel.OFFLINE],
     *   regardless of the other arguments.
     * - `!isSuccessful` (non-2xx HTTP status) -> [HealthLevel.OFFLINE].
     * - Otherwise the verdict is purely latency-based via [categorizeLatency].
     */
    fun evaluateStatus(
        isSuccessful: Boolean,
        latencyMs: Long,
        error: Throwable? = null,
    ): HealthLevel = when {
        error != null -> HealthLevel.OFFLINE
        !isSuccessful -> HealthLevel.OFFLINE
        else -> categorizeLatency(latencyMs)
    }
}
