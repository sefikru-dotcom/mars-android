package ru.mars.network.model

/**
 * Coarse health verdict for a server probe, produced by
 * `ru.mars.network.repository.ServerHealthPolicy` and carried inside
 * [ServerHealthReport].
 */
enum class HealthLevel {
    /** Reachable and fast enough (latency within the healthy threshold). */
    HEALTHY,

    /** Reachable but slow: responses arrive above the healthy threshold. */
    DEGRADED,

    /** Unreachable, failed (non-2xx), timed out or absurdly slow. */
    OFFLINE,
}

/**
 * Result of a single server health probe.
 *
 * @param status verdict computed by the health policy.
 * @param latencyMs wall-clock round-trip time of the probe in milliseconds;
 *   for failed probes it is the time spent before the failure was detected.
 * @param checkedUrl absolute URL that was actually requested.
 * @param timestampMs moment the report was created (epoch millis).
 * @param message optional human-readable detail: `HTTP <code>` for completed
 *   calls or the exception message for network failures.
 */
data class ServerHealthReport(
    val status: HealthLevel,
    val latencyMs: Long,
    val checkedUrl: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String? = null,
)
