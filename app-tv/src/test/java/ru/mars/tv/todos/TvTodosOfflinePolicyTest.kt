package ru.mars.tv.todos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineDashboardCachePolicy

/**
 * Unit-тесты [TvTodosOfflinePolicy] — чистой политики офлайн-кэша экрана «Задачи».
 * Покрывают: LIVE на успешный ответ, CACHED с возрастной плашкой при сетевом сбое,
 * ERROR при пустом/просроченном/неверифицируемом снимке, границы окна свежести и
 * clamp отката часов.
 */
class TvTodosOfflinePolicyTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private val snapshot = TodoSnapshot(
        todos = listOf(TodoItem(id = "1", content = "Шаг один", status = "in_progress")),
        summary = TodoSummary(total = 1, inProgress = 1),
    )

    // --- LIVE: успешный сетевой ответ ---

    @Test
    fun `live success passes the data through with the fresh badge`() {
        val data = TvTodosData("Утренний стендап", snapshot)
        val decision = TvTodosOfflinePolicy.onLiveSuccess(data)
        assertEquals(TvOfflineCacheStatus.LIVE, decision.status)
        assertEquals(data, decision.data)
        assertEquals(TvOfflineAgeBadge.LIVE_BADGE, decision.badgeText)
        assertEquals("Обновлено только что", decision.badgeText)
        assertEquals("live_data", decision.reason)
    }

    // --- CACHED: сетевой сбой, снимок пригоден ---

    @Test
    fun `fetch failure with a fresh snapshot yields CACHED with an age badge`() {
        val savedAt = now - 10 * minute
        val decision = TvTodosOfflinePolicy.onFetchFailure(snapshot, savedAt, now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("snapshot_shown", decision.reason)
        assertEquals("Снимок из кэша (10 мин. назад)", decision.badgeText)
        val shown = decision.data
        assertNull("заголовок сессии не кэшируется — честный null", shown?.sessionTitle)
        assertEquals(snapshot, shown?.snapshot)
    }

    @Test
    fun `sub-minute snapshot reads just now and hour ages format alike`() {
        assertEquals(
            "Снимок из кэша (только что)",
            TvTodosOfflinePolicy.onFetchFailure(snapshot, now - 20_000, now).badgeText,
        )
        assertEquals(
            "Снимок из кэша (3 ч. назад)",
            TvTodosOfflinePolicy.onFetchFailure(snapshot, now - 3 * hour, now).badgeText,
        )
        // Дневные плашки за 12-часовым окном недостижимы: снимок отклоняется (см. expired-тест);
        // формат «N дн. назад» покрыт TvOfflineAgeBadgeTest.
    }

    @Test
    fun `snapshot exactly at the max age is still shown`() {
        val decision = TvTodosOfflinePolicy.onFetchFailure(
            snapshot,
            now - TvTodosOfflinePolicy.MAX_AGE_MS,
            now,
        )
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("Снимок из кэша (12 ч. назад)", decision.badgeText)
    }

    @Test
    fun `backwards clock is clamped to just now instead of a negative count`() {
        val decision = TvTodosOfflinePolicy.onFetchFailure(snapshot, now + 5 * minute, now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("Снимок из кэша (только что)", decision.badgeText)
    }

    // --- ERROR: показывать нечего ---

    @Test
    fun `fetch failure with an empty cache yields ERROR without data or badge`() {
        val decision = TvTodosOfflinePolicy.onFetchFailure(null, null, now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull(decision.data)
        assertNull(decision.badgeText)
        assertEquals("no_snapshot", decision.reason)
    }

    @Test
    fun `expired snapshot is rejected even though it exists`() {
        val decision = TvTodosOfflinePolicy.onFetchFailure(
            snapshot,
            now - TvTodosOfflinePolicy.MAX_AGE_MS - 1,
            now,
        )
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull("устаревшие задачи не должны показываться", decision.data)
        assertNull(decision.badgeText)
        assertEquals("snapshot_expired", decision.reason)
    }

    @Test
    fun `snapshot without a timestamp is unverifiable and rejected`() {
        val decision = TvTodosOfflinePolicy.onFetchFailure(snapshot, null, now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull(decision.data)
        assertEquals("snapshot_unverified", decision.reason)
    }

    // --- инварианты ---

    @Test
    fun `isSnapshotUsable is false without a timestamp and true within the window`() {
        assertFalse(TvTodosOfflinePolicy.isSnapshotUsable(null, now))
        assertTrue(TvTodosOfflinePolicy.isSnapshotUsable(now - minute, now))
        assertTrue(TvTodosOfflinePolicy.isSnapshotUsable(now - TvTodosOfflinePolicy.MAX_AGE_MS, now))
        assertFalse(TvTodosOfflinePolicy.isSnapshotUsable(now - TvTodosOfflinePolicy.MAX_AGE_MS - 1, now))
    }

    @Test
    fun `freshness window keeps dashboard parity - 12 hours`() {
        assertEquals(12L * 60 * 60 * 1000, TvTodosOfflinePolicy.MAX_AGE_MS)
        assertEquals(TvOfflineDashboardCachePolicy.DASHBOARD_MAX_AGE_MS, TvTodosOfflinePolicy.MAX_AGE_MS)
    }
}
