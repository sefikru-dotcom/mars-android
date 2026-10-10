package ru.mars.tv.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineDashboardCachePolicy

/**
 * Unit-тесты [TvProjectsOfflinePolicy] — чистой политики офлайн-кэша экрана
 * «Мониторинг». Покрывают: LIVE на успешный ответ, CACHED с сырым JSON снимка и
 * возрастной плашкой при сетевом сбое, ERROR при пустом/просроченном/неверифицируемом
 * снимке, границы суточного окна свежести и clamp отката часов.
 */
class TvProjectsOfflinePolicyTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private val cachedJson = """{"ts": "08.10.2026 в 22:19:34 (МСК)", "prod": {"services": []}}"""

    // --- LIVE: успешный сетевой ответ ---

    @Test
    fun `live success yields LIVE with the fresh badge and no cached json`() {
        val decision = TvProjectsOfflinePolicy.onLiveSuccess()
        assertEquals(TvOfflineCacheStatus.LIVE, decision.status)
        assertNull("живое тело контроллер уже имеет — политика его не дублирует", decision.cachedJson)
        assertEquals(TvOfflineAgeBadge.LIVE_BADGE, decision.badgeText)
        assertEquals("Обновлено только что", decision.badgeText)
        assertEquals("live_data", decision.reason)
    }

    // --- CACHED: сетевой сбой, снимок пригоден ---

    @Test
    fun `fetch failure with a fresh snapshot yields CACHED with json and age badge`() {
        val savedAt = now - 15 * minute
        val decision = TvProjectsOfflinePolicy.onFetchFailure(cachedJson, savedAt, now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("snapshot_shown", decision.reason)
        assertEquals(cachedJson, decision.cachedJson)
        assertEquals("Снимок из кэша (15 мин. назад)", decision.badgeText)
    }

    @Test
    fun `sub-minute snapshot reads just now and hour ages format alike`() {
        assertEquals(
            "Снимок из кэша (только что)",
            TvProjectsOfflinePolicy.onFetchFailure(cachedJson, now - 30_000, now).badgeText,
        )
        assertEquals(
            "Снимок из кэша (6 ч. назад)",
            TvProjectsOfflinePolicy.onFetchFailure(cachedJson, now - 6 * hour, now).badgeText,
        )
        // Ровно сутки — граница окна: плашка уходит в дни (формат «N дн.» покрыт
        // TvOfflineAgeBadgeTest, а всё старшее отклоняется — см. expired-тест).
        assertEquals(
            "Снимок из кэша (1 дн. назад)",
            TvProjectsOfflinePolicy.onFetchFailure(cachedJson, now - day, now).badgeText,
        )
    }

    @Test
    fun `snapshot exactly at the max age is still shown`() {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(
            cachedJson,
            now - TvProjectsOfflinePolicy.MAX_AGE_MS,
            now,
        )
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("Снимок из кэша (1 дн. назад)", decision.badgeText)
    }

    @Test
    fun `backwards clock is clamped to just now instead of a negative count`() {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(cachedJson, now + hour, now)
        assertEquals(TvOfflineCacheStatus.CACHED, decision.status)
        assertEquals("Снимок из кэша (только что)", decision.badgeText)
    }

    // --- ERROR: показывать нечего ---

    @Test
    fun `fetch failure with an empty cache yields ERROR without json or badge`() {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(null, null, now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull(decision.cachedJson)
        assertNull(decision.badgeText)
        assertEquals("no_snapshot", decision.reason)
    }

    @Test
    fun `blank cached json is treated as no snapshot`() {
        listOf("", "   ").forEach { blank ->
            val decision = TvProjectsOfflinePolicy.onFetchFailure(blank, now - minute, now)
            assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
            assertEquals("no_snapshot", decision.reason)
        }
    }

    @Test
    fun `expired snapshot is rejected even though it exists`() {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(
            cachedJson,
            now - TvProjectsOfflinePolicy.MAX_AGE_MS - 1,
            now,
        )
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull("устаревшие метрики не должны показываться", decision.cachedJson)
        assertNull(decision.badgeText)
        assertEquals("snapshot_expired", decision.reason)
    }

    @Test
    fun `snapshot without a timestamp is unverifiable and rejected`() {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(cachedJson, null, now)
        assertEquals(TvOfflineCacheStatus.ERROR, decision.status)
        assertNull(decision.cachedJson)
        assertEquals("snapshot_unverified", decision.reason)
    }

    // --- инварианты ---

    @Test
    fun `isSnapshotUsable is false without a timestamp and true within the window`() {
        assertFalse(TvProjectsOfflinePolicy.isSnapshotUsable(null, now))
        assertTrue(TvProjectsOfflinePolicy.isSnapshotUsable(now - day, now))
        assertTrue(TvProjectsOfflinePolicy.isSnapshotUsable(now - TvProjectsOfflinePolicy.MAX_AGE_MS, now))
        assertFalse(TvProjectsOfflinePolicy.isSnapshotUsable(now - TvProjectsOfflinePolicy.MAX_AGE_MS - 1, now))
    }

    @Test
    fun `freshness window keeps dashboard projects parity - 24 hours`() {
        assertEquals(24L * 60 * 60 * 1000, TvProjectsOfflinePolicy.MAX_AGE_MS)
        assertEquals(TvOfflineDashboardCachePolicy.PROJECTS_MAX_AGE_MS, TvProjectsOfflinePolicy.MAX_AGE_MS)
    }
}
