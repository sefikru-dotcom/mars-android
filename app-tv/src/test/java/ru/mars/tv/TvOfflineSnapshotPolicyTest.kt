package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TvOfflineSnapshotPolicy] без Android-фреймворка — общая чистая
 * политика офлайн-кэша read-only экранов (Канбан/Аналитика/Логи/Пространства).
 * Паритет правил [ru.mars.tv.todos.TvTodosOfflinePolicyTest]: LIVE на успех,
 * CACHED только для верифицированного свежего снимка, ERROR с честным reason
 * на пустой/без метки/просроченный кэш.
 */
class TvOfflineSnapshotPolicyTest {

    private val hourMs = 60L * 60 * 1000
    private val policy = TvOfflineSnapshotPolicy(maxAgeMs = 12 * hourMs)
    private val now = 1_700_000_000_000L

    @Test
    fun `live success is always LIVE with the live badge`() {
        val decision = policy.onLiveSuccess()
        assertEquals(TvOfflineCacheStatus.LIVE, decision.status)
        assertFalse("LIVE рисует свежие данные, showCached не используется", decision.showCached)
        assertEquals(TvOfflineAgeBadge.LIVE_BADGE, decision.badgeText)
        assertEquals("live_data", decision.reason)
    }

    @Test
    fun `failure with a fresh cached snapshot is CACHED with an age badge`() {
        val savedAt = now - 5 * 60_000L // 5 минут назад
        val decision = policy.onFetchFailure(hasSnapshot = true, savedAtMs = savedAt, nowMs = now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertTrue(decision.showCached)
        assertEquals("Снимок из кэша (5 мин. назад)", decision.badgeText)
        assertEquals("snapshot_shown", decision.reason)
    }

    @Test
    fun `failure without a snapshot is an explicit ERROR (no_snapshot)`() {
        val decision = policy.onFetchFailure(hasSnapshot = false, savedAtMs = null, nowMs = now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertFalse(decision.showCached)
        assertNull(decision.badgeText)
        assertEquals("no_snapshot", decision.reason)
    }

    @Test
    fun `snapshot without a timestamp is never shown (snapshot_unverified)`() {
        val decision = policy.onFetchFailure(hasSnapshot = true, savedAtMs = null, nowMs = now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertFalse(decision.showCached)
        assertEquals("snapshot_unverified", decision.reason)
    }

    @Test
    fun `expired snapshot is never shown (snapshot_expired)`() {
        val savedAt = now - (12 * hourMs + 1)
        val decision = policy.onFetchFailure(hasSnapshot = true, savedAtMs = savedAt, nowMs = now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertFalse(decision.showCached)
        assertEquals("snapshot_expired", decision.reason)
    }

    @Test
    fun `snapshot exactly at max age is still usable (boundary inclusive)`() {
        val savedAt = now - 12 * hourMs
        assertTrue(policy.isSnapshotUsable(savedAt, now))
        val decision = policy.onFetchFailure(hasSnapshot = true, savedAtMs = savedAt, nowMs = now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
    }

    @Test
    fun `clock rollback keeps the badge honest (age clamped to zero)`() {
        val savedAt = now + 60_000L // часы откатились назад
        val decision = policy.onFetchFailure(hasSnapshot = true, savedAtMs = savedAt, nowMs = now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("Снимок из кэша (только что)", decision.badgeText)
    }

    @Test
    fun `maxAge is honored per policy instance`() {
        val short = TvOfflineSnapshotPolicy(maxAgeMs = hourMs)
        assertFalse(short.isSnapshotUsable(now - 2 * hourMs, now))
        assertTrue(policy.isSnapshotUsable(now - 2 * hourMs, now))
    }
}
