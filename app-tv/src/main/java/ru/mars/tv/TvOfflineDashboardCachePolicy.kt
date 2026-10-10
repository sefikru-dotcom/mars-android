package ru.mars.tv

/** Surface whose offline fallback is being decided (dashboard feed vs. projects metrics). */
enum class TvOfflineSurface { DASHBOARD, PROJECTS }

/**
 * Lifecycle state of a TV surface around a connectivity loss/recovery:
 * - [LIVE]: network up and fresh data rendered — no badge, no fallback;
 * - [RECOVERING]: network is back but the live payload has not landed yet — the cached
 *   snapshot stays on screen (with its age badge) so the UI never blanks mid-recovery;
 * - [OFFLINE_FALLBACK]: network down, a usable snapshot exists — show it with the badge;
 * - [OFFLINE_EMPTY]: network down and nothing usable is cached — the host renders its
 *   "no data / no connection" placeholder instead of stale content.
 */
enum class TvOfflineRecoveryState { LIVE, RECOVERING, OFFLINE_FALLBACK, OFFLINE_EMPTY }

/**
 * Outcome of [TvOfflineDashboardCachePolicy.evaluate]: the recovery [state], whether the
 * cached fallback should be shown, the ready-to-render [badgeText] (`null` when no badge
 * applies) and a stable machine-readable [reason] tag (`live_data`, `refreshing`,
 * `refreshing_no_snapshot`, `refreshing_expired`, `snapshot_shown`, `snapshot_expired`,
 * `no_snapshot`) for tests and logs.
 */
data class TvOfflineFallbackDecision(
    val state: TvOfflineRecoveryState,
    val showFallback: Boolean,
    val badgeText: String?,
    val reason: String,
)

/**
 * Pure offline-cache display policy for the TV dashboard and projects metrics screens
 * (no Android, no SharedPreferences, no I/O — JVM-testable, [TvNetworkMonitorPolicy]
 * parity). The host feeds the observable facts (is the network up, has the live payload
 * landed, when was the cached snapshot taken) and gets a [TvOfflineFallbackDecision]:
 * show the snapshot or not, in which recovery state, and with which Russian age badge
 * ("Офлайн-снимок (N мин. назад)").
 *
 * Staleness windows are surface-specific: the dashboard feed (chats/todos/projects
 * activity) goes stale faster than the projects metrics summary, which is mostly
 * aggregate numbers that stay meaningful longer.
 */
object TvOfflineDashboardCachePolicy {

    /** Max age of a dashboard snapshot before it must not be shown anymore. */
    const val DASHBOARD_MAX_AGE_MS = 12L * 60 * 60 * 1000 // 12 hours

    /** Max age of a projects-metrics snapshot — aggregate numbers stay useful longer. */
    const val PROJECTS_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

    /** Prefix shared by every offline badge, so hosts/tests can match on it. */
    const val BADGE_PREFIX = "Офлайн-снимок"

    /** Badge shown for snapshots younger than one minute. */
    const val BADGE_JUST_NOW = "Офлайн-снимок (только что)"

    private const val MINUTE_MS = 60_000L

    /** Staleness window for [surface]. */
    fun maxSnapshotAgeMs(surface: TvOfflineSurface): Long = when (surface) {
        TvOfflineSurface.DASHBOARD -> DASHBOARD_MAX_AGE_MS
        TvOfflineSurface.PROJECTS -> PROJECTS_MAX_AGE_MS
    }

    /**
     * Age of a snapshot taken at [snapshotTakenAtMs] as of [nowMs], clamped to zero for
     * backwards clocks (NTP corrections) — never negative, never guessed around.
     */
    fun snapshotAgeMs(snapshotTakenAtMs: Long, nowMs: Long): Long =
        (nowMs - snapshotTakenAtMs).coerceAtLeast(0L)

    /** Whether a snapshot of [surface] taken at [snapshotTakenAtMs] is still within its window. */
    fun isSnapshotUsable(surface: TvOfflineSurface, snapshotTakenAtMs: Long, nowMs: Long): Boolean =
        snapshotAgeMs(snapshotTakenAtMs, nowMs) <= maxSnapshotAgeMs(surface)

    /**
     * Render-ready age badge for a snapshot of the given age:
     * - under a minute -> "Офлайн-снимок (только что)";
     * - otherwise -> "Офлайн-снимок (N мин. назад)" with N = whole minutes (floored).
     *
     * Negative [ageMs] is clamped to zero, so a skewed clock still yields "только что"
     * instead of a nonsensical negative count.
     */
    fun offlineBadgeText(ageMs: Long): String {
        val age = ageMs.coerceAtLeast(0L)
        val minutes = age / MINUTE_MS
        return if (minutes < 1) BADGE_JUST_NOW else "$BADGE_PREFIX ($minutes мин. назад)"
    }

    /** Badge for a snapshot with absolute timestamps (see [snapshotAgeMs]). */
    fun offlineBadgeText(snapshotTakenAtMs: Long, nowMs: Long): String =
        offlineBadgeText(snapshotAgeMs(snapshotTakenAtMs, nowMs))

    /**
     * Full decision from the raw host facts:
     * - network up and live data rendered -> LIVE/`live_data` (no fallback, no badge —
     *   graceful recovery completes here);
     * - network up, live data still loading -> RECOVERING: keep the snapshot on screen
     *   while the refresh runs so the UI never blanks (`refreshing`), unless nothing
     *   usable is cached (`refreshing_no_snapshot`, `refreshing_expired`);
     * - network down -> show a usable snapshot with its age badge (OFFLINE_FALLBACK/
     *   `snapshot_shown`), or fall to OFFLINE_EMPTY when there is none (`no_snapshot`)
     *   or it outlived its window (`snapshot_expired`).
     *
     * [snapshotTakenAtMs] `null` means "no cached snapshot". Expired snapshots are never
     * shown, in recovery or offline — stale metrics are worse than an honest placeholder.
     */
    fun evaluate(
        surface: TvOfflineSurface,
        networkAvailable: Boolean,
        liveDataReady: Boolean,
        snapshotTakenAtMs: Long?,
        nowMs: Long,
    ): TvOfflineFallbackDecision {
        val hasSnapshot = snapshotTakenAtMs != null
        val usable = hasSnapshot && isSnapshotUsable(surface, snapshotTakenAtMs!!, nowMs)
        val badge = if (usable) offlineBadgeText(snapshotTakenAtMs!!, nowMs) else null

        if (networkAvailable) {
            if (liveDataReady) {
                return TvOfflineFallbackDecision(
                    state = TvOfflineRecoveryState.LIVE,
                    showFallback = false,
                    badgeText = null,
                    reason = "live_data",
                )
            }
            return when {
                !hasSnapshot -> recovering(false, null, "refreshing_no_snapshot")
                !usable -> recovering(false, null, "refreshing_expired")
                else -> recovering(true, badge, "refreshing")
            }
        }

        return when {
            !hasSnapshot -> offlineEmpty("no_snapshot")
            !usable -> offlineEmpty("snapshot_expired")
            else -> TvOfflineFallbackDecision(
                state = TvOfflineRecoveryState.OFFLINE_FALLBACK,
                showFallback = true,
                badgeText = badge,
                reason = "snapshot_shown",
            )
        }
    }

    private fun recovering(show: Boolean, badge: String?, reason: String) =
        TvOfflineFallbackDecision(TvOfflineRecoveryState.RECOVERING, show, badge, reason)

    private fun offlineEmpty(reason: String) =
        TvOfflineFallbackDecision(TvOfflineRecoveryState.OFFLINE_EMPTY, false, null, reason)
}
