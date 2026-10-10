package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvOfflineDashboardCachePolicy] — pure JVM, no Android, no storage.
 * Covers per-surface staleness windows, age clamping on backwards clocks, usable-window
 * boundaries, badge formatting (just-now, whole minutes, flooring, negatives), the full
 * evaluate decision surface (live, recovering with/without snapshot, offline fallback,
 * expiration, empty cache) and the end-to-end graceful recovery transition.
 */
class TvOfflineDashboardCachePolicyTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    // --- staleness windows ---------------------------------------------------------------------------

    @Test
    fun `dashboard and projects have distinct max snapshot ages`() {
        assertEquals(12 * hour, TvOfflineDashboardCachePolicy.maxSnapshotAgeMs(TvOfflineSurface.DASHBOARD))
        assertEquals(24 * hour, TvOfflineDashboardCachePolicy.maxSnapshotAgeMs(TvOfflineSurface.PROJECTS))
        assertTrue(
            "projects metrics tolerate longer staleness than the dashboard feed",
            TvOfflineDashboardCachePolicy.maxSnapshotAgeMs(TvOfflineSurface.PROJECTS) >
                TvOfflineDashboardCachePolicy.maxSnapshotAgeMs(TvOfflineSurface.DASHBOARD),
        )
    }

    // --- snapshotAgeMs --------------------------------------------------------------------------------

    @Test
    fun `snapshot age is the forward distance between timestamps`() {
        assertEquals(5 * min, TvOfflineDashboardCachePolicy.snapshotAgeMs(now - 5 * min, now))
        assertEquals(0L, TvOfflineDashboardCachePolicy.snapshotAgeMs(now, now))
    }

    @Test
    fun `backwards clock clamps the age to zero instead of going negative`() {
        assertEquals(0L, TvOfflineDashboardCachePolicy.snapshotAgeMs(now + 30_000L, now))
    }

    // --- isSnapshotUsable ------------------------------------------------------------------------------

    @Test
    fun `snapshot exactly at the window edge is still usable`() {
        assertTrue(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.DASHBOARD, now - 12 * hour, now))
        assertTrue(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.PROJECTS, now - 24 * hour, now))
    }

    @Test
    fun `snapshot one millisecond past the window is expired`() {
        assertFalse(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.DASHBOARD, now - 12 * hour - 1, now))
        assertFalse(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.PROJECTS, now - 24 * hour - 1, now))
    }

    @Test
    fun `future-dated snapshot is usable because its age clamps to zero`() {
        assertTrue(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.DASHBOARD, now + hour, now))
    }

    @Test
    fun `13-hour-old snapshot is expired for dashboard but usable for projects`() {
        val taken = now - 13 * hour
        assertFalse(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.DASHBOARD, taken, now))
        assertTrue(TvOfflineDashboardCachePolicy.isSnapshotUsable(TvOfflineSurface.PROJECTS, taken, now))
    }

    // --- badge formatting -------------------------------------------------------------------------------

    @Test
    fun `badge under one minute reads just now`() {
        assertEquals("Офлайн-снимок (только что)", TvOfflineDashboardCachePolicy.offlineBadgeText(0L))
        assertEquals("Офлайн-снимок (только что)", TvOfflineDashboardCachePolicy.offlineBadgeText(59_999L))
        assertEquals(TvOfflineDashboardCachePolicy.BADGE_JUST_NOW, TvOfflineDashboardCachePolicy.offlineBadgeText(1L))
    }

    @Test
    fun `badge shows whole minutes with the required wording`() {
        assertEquals("Офлайн-снимок (1 мин. назад)", TvOfflineDashboardCachePolicy.offlineBadgeText(min))
        assertEquals("Офлайн-снимок (5 мин. назад)", TvOfflineDashboardCachePolicy.offlineBadgeText(5 * min))
        assertEquals("Офлайн-снимок (90 мин. назад)", TvOfflineDashboardCachePolicy.offlineBadgeText(90 * min))
    }

    @Test
    fun `badge floors partial minutes`() {
        assertEquals("Офлайн-снимок (7 мин. назад)", TvOfflineDashboardCachePolicy.offlineBadgeText(7 * min + 59_999L))
    }

    @Test
    fun `negative age still yields the just-now badge`() {
        assertEquals("Офлайн-снимок (только что)", TvOfflineDashboardCachePolicy.offlineBadgeText(-min))
    }

    @Test
    fun `badge from absolute timestamps matches the age-based one`() {
        val taken = now - 42 * min
        assertEquals(
            TvOfflineDashboardCachePolicy.offlineBadgeText(42 * min),
            TvOfflineDashboardCachePolicy.offlineBadgeText(taken, now),
        )
    }

    @Test
    fun `every badge starts with the shared prefix`() {
        for (age in listOf(0L, min, hour, 12 * hour)) {
            assertTrue(TvOfflineDashboardCachePolicy.offlineBadgeText(age).startsWith(TvOfflineDashboardCachePolicy.BADGE_PREFIX))
        }
    }

    // --- evaluate: live ---------------------------------------------------------------------------------

    @Test
    fun `network up and live data ready means LIVE with no fallback or badge`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            surface = TvOfflineSurface.DASHBOARD,
            networkAvailable = true,
            liveDataReady = true,
            snapshotTakenAtMs = now - 3 * min,
            nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.LIVE, actual.state)
        assertFalse(actual.showFallback)
        assertNull(actual.badgeText)
        assertEquals("live_data", actual.reason)
    }

    @Test
    fun `LIVE wins even without any cached snapshot`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.PROJECTS, networkAvailable = true, liveDataReady = true,
            snapshotTakenAtMs = null, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.LIVE, actual.state)
        assertEquals("live_data", actual.reason)
        assertFalse(actual.showFallback)
    }

    // --- evaluate: recovering ---------------------------------------------------------------------------

    @Test
    fun `network back but data not loaded keeps a usable snapshot with its badge`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = true, liveDataReady = false,
            snapshotTakenAtMs = now - 10 * min, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.RECOVERING, actual.state)
        assertTrue(actual.showFallback)
        assertEquals("Офлайн-снимок (10 мин. назад)", actual.badgeText)
        assertEquals("refreshing", actual.reason)
    }

    @Test
    fun `recovering without a snapshot shows nothing`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = true, liveDataReady = false,
            snapshotTakenAtMs = null, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.RECOVERING, actual.state)
        assertFalse(actual.showFallback)
        assertNull(actual.badgeText)
        assertEquals("refreshing_no_snapshot", actual.reason)
    }

    @Test
    fun `recovering never resurfaces an expired snapshot`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = true, liveDataReady = false,
            snapshotTakenAtMs = now - 13 * hour, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.RECOVERING, actual.state)
        assertFalse(actual.showFallback)
        assertNull(actual.badgeText)
        assertEquals("refreshing_expired", actual.reason)
    }

    // --- evaluate: offline -------------------------------------------------------------------------------

    @Test
    fun `offline with a fresh snapshot shows the fallback and the age badge`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.PROJECTS, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = now - 45 * min, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_FALLBACK, actual.state)
        assertTrue(actual.showFallback)
        assertEquals("Офлайн-снимок (45 мин. назад)", actual.badgeText)
        assertEquals("snapshot_shown", actual.reason)
    }

    @Test
    fun `offline right after startup shows the just-now badge for a fresh snapshot`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = now - 10_000L, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_FALLBACK, actual.state)
        assertEquals("Офлайн-снимок (только что)", actual.badgeText)
    }

    @Test
    fun `offline with an expired snapshot falls to the honest empty placeholder`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.PROJECTS, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = now - 25 * hour, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_EMPTY, actual.state)
        assertFalse(actual.showFallback)
        assertNull(actual.badgeText)
        assertEquals("snapshot_expired", actual.reason)
    }

    @Test
    fun `offline without any snapshot is OFFLINE_EMPTY`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = null, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_EMPTY, actual.state)
        assertFalse(actual.showFallback)
        assertEquals("no_snapshot", actual.reason)
    }

    @Test
    fun `offline ignores liveDataReady because nothing can load without a network`() {
        // A stale 'true' from the previous online session must not flip the state to LIVE.
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = false, liveDataReady = true,
            snapshotTakenAtMs = now - 2 * min, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_FALLBACK, actual.state)
        assertTrue(actual.showFallback)
        assertEquals("snapshot_shown", actual.reason)
    }

    @Test
    fun `skewed clock on the snapshot still yields a usable just-now fallback`() {
        val actual = TvOfflineDashboardCachePolicy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = now + 5 * min, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_FALLBACK, actual.state)
        assertEquals("Офлайн-снимок (только что)", actual.badgeText)
    }

    // --- graceful recovery journey ------------------------------------------------------------------------

    @Test
    fun `offline to recovery to live journey keeps the screen populated at every step`() {
        val taken = now - 8 * min
        val policy = TvOfflineDashboardCachePolicy

        // Step 1: startup with no network — cached snapshot with badge.
        val offline = policy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = false, liveDataReady = false,
            snapshotTakenAtMs = taken, nowMs = now,
        )
        assertEquals(TvOfflineRecoveryState.OFFLINE_FALLBACK, offline.state)
        assertTrue(offline.showFallback)

        // Step 2: network returns, refresh in flight — snapshot stays, no blank screen.
        val recovering = policy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = true, liveDataReady = false,
            snapshotTakenAtMs = taken, nowMs = now + 2_000L,
        )
        assertEquals(TvOfflineRecoveryState.RECOVERING, recovering.state)
        assertTrue(recovering.showFallback)
        assertEquals("Офлайн-снимок (8 мин. назад)", recovering.badgeText)

        // Step 3: live payload landed — badge and fallback disappear.
        val live = policy.evaluate(
            TvOfflineSurface.DASHBOARD, networkAvailable = true, liveDataReady = true,
            snapshotTakenAtMs = taken, nowMs = now + 4_000L,
        )
        assertEquals(TvOfflineRecoveryState.LIVE, live.state)
        assertFalse(live.showFallback)
        assertNull(live.badgeText)
    }

    @Test
    fun `decision exposes every state exactly once across the fact matrix`() {
        val seen = mutableSetOf<TvOfflineRecoveryState>()
        for (network in listOf(true, false)) {
            for (live in listOf(true, false)) {
                for (snapshot in listOf<Long?>(null, now - min, now - 48 * hour)) {
                    val d = TvOfflineDashboardCachePolicy.evaluate(
                        TvOfflineSurface.DASHBOARD, network, live, snapshot, now,
                    )
                    seen += d.state
                    // Invariant: a badge exists if and only if the fallback is shown.
                    assertEquals(d.showFallback, d.badgeText != null)
                    // Invariant: never show an expired (48h) dashboard snapshot.
                    if (snapshot == now - 48 * hour) assertFalse(d.showFallback)
                }
            }
        }
        assertEquals(TvOfflineRecoveryState.values().toSet(), seen)
    }
}
