package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистой политики свежести офлайн-кэша ([OfflineCacheUiPolicy]):
 * форматирование бейджа возраста (границы «только что»/мин/ч/дн, усечение дробного,
 * отрицательный возраст от перекошенного часа), окна устаревания по экранам
 * (задачи 12ч, проекты 24ч), полная матрица evaluate (LIVE/CACHED/EMPTY/ERROR/LOADING)
 * и инварианты бейджа/баннера/показа кэша, плюс сквозной сценарий старт→сеть→сбой.
 */
class OfflineCacheUiPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun evaluate(
        surface: MobileOfflineSurface = MobileOfflineSurface.TODOS,
        fetchState: OfflineFetchState = OfflineFetchState.FAILURE,
        livePayloadEmpty: Boolean = false,
        snapshotTakenAtMs: Long? = now - 5 * minute,
        nowMs: Long = now,
    ): OfflineUiDecision = OfflineCacheUiPolicy.evaluate(
        surface = surface,
        fetchState = fetchState,
        livePayloadEmpty = livePayloadEmpty,
        snapshotTakenAtMs = snapshotTakenAtMs,
        nowMs = nowMs,
    )

    // ---------------------------------------------------------------- возраст

    @Test
    fun `ageText - под минутой и ноль дают только что`() {
        assertEquals("только что", OfflineCacheUiPolicy.ageText(0L))
        assertEquals("только что", OfflineCacheUiPolicy.ageText(1L))
        assertEquals("только что", OfflineCacheUiPolicy.ageText(minute - 1))
    }

    @Test
    fun `ageText - минуты целые, дробное усечение вниз`() {
        assertEquals("1 мин назад", OfflineCacheUiPolicy.ageText(minute))
        assertEquals("1 мин назад", OfflineCacheUiPolicy.ageText(minute + 59_999))
        assertEquals("5 мин назад", OfflineCacheUiPolicy.ageText(5 * minute + 30_000))
        assertEquals("59 мин назад", OfflineCacheUiPolicy.ageText(hour - 1))
    }

    @Test
    fun `ageText - от часа до суток`() {
        assertEquals("1 ч назад", OfflineCacheUiPolicy.ageText(hour))
        assertEquals("1 ч назад", OfflineCacheUiPolicy.ageText(hour + 30 * minute))
        assertEquals("23 ч назад", OfflineCacheUiPolicy.ageText(day - 1))
    }

    @Test
    fun `ageText - от суток`() {
        assertEquals("1 дн назад", OfflineCacheUiPolicy.ageText(day))
        assertEquals("1 дн назад", OfflineCacheUiPolicy.ageText(day + 12 * hour))
        assertEquals("3 дн назад", OfflineCacheUiPolicy.ageText(3 * day + 5 * hour))
    }

    @Test
    fun `ageText - отрицательный возраст (часы назад) прижимается к нулю`() {
        assertEquals("только что", OfflineCacheUiPolicy.ageText(-1L))
        assertEquals("только что", OfflineCacheUiPolicy.ageText(-day))
    }

    @Test
    fun `snapshotAgeMs - clamp для обратного хода часов`() {
        assertEquals(0L, OfflineCacheUiPolicy.snapshotAgeMs(snapshotTakenAtMs = now + hour, nowMs = now))
        assertEquals(3 * minute, OfflineCacheUiPolicy.snapshotAgeMs(now - 3 * minute, now))
    }

    // ----------------------------------------------------------------- бейдж

    @Test
    fun `cacheBadgeText - префикс Кэш и возраст в скобках`() {
        assertEquals("Кэш (только что)", OfflineCacheUiPolicy.cacheBadgeText(now - 30_000, now))
        assertEquals("Кэш (5 мин назад)", OfflineCacheUiPolicy.cacheBadgeText(now - 5 * minute, now))
        assertEquals("Кэш (2 ч назад)", OfflineCacheUiPolicy.cacheBadgeText(now - 2 * hour, now))
        assertEquals("Кэш (3 дн назад)", OfflineCacheUiPolicy.cacheBadgeText(now - 3 * day, now))
    }

    @Test
    fun `cacheBadgeText - будущий снимок все равно только что, без отрицательных чисел`() {
        assertEquals("Кэш (только что)", OfflineCacheUiPolicy.cacheBadgeText(now + hour, now))
    }

    // ------------------------------------------------------- окна устаревания

    @Test
    fun `окна устаревания - задачи 12ч, проекты 24ч`() {
        assertEquals(12 * hour, OfflineCacheUiPolicy.maxSnapshotAgeMs(MobileOfflineSurface.TODOS))
        assertEquals(24 * hour, OfflineCacheUiPolicy.maxSnapshotAgeMs(MobileOfflineSurface.PROJECTS))

        assertTrue(OfflineCacheUiPolicy.isSnapshotUsable(MobileOfflineSurface.TODOS, now - 12 * hour, now))
        assertFalse(OfflineCacheUiPolicy.isSnapshotUsable(MobileOfflineSurface.TODOS, now - 12 * hour - 1, now))
        assertTrue(OfflineCacheUiPolicy.isSnapshotUsable(MobileOfflineSurface.PROJECTS, now - 23 * hour, now))
        assertFalse(OfflineCacheUiPolicy.isSnapshotUsable(MobileOfflineSurface.PROJECTS, now - 24 * hour - 1, now))
    }

    @Test
    fun `снимок из будущего свеж на любом экране`() {
        assertTrue(OfflineCacheUiPolicy.isSnapshotUsable(MobileOfflineSurface.TODOS, now + day, now))
    }

    // -------------------------------------------------------------- evaluate

    @Test
    fun `SUCCESS с данными - LIVE без бейджа и баннера`() {
        val d = evaluate(fetchState = OfflineFetchState.SUCCESS, livePayloadEmpty = false)
        assertEquals(OfflineDataStatus.LIVE, d.status)
        assertEquals("live_data", d.reason)
        assertFalse(d.showCachedContent)
        assertNull(d.badgeText)
        assertNull(d.bannerText)
    }

    @Test
    fun `SUCCESS с пустым снимком - EMPTY, кэш не показывается`() {
        val d = evaluate(fetchState = OfflineFetchState.SUCCESS, livePayloadEmpty = true)
        assertEquals(OfflineDataStatus.EMPTY, d.status)
        assertEquals("live_empty", d.reason)
        assertFalse(d.showCachedContent)
        assertNull(d.badgeText)
    }

    @Test
    fun `FAILURE без кэша - ERROR no_cache`() {
        val d = evaluate(snapshotTakenAtMs = null)
        assertEquals(OfflineDataStatus.ERROR, d.status)
        assertEquals("no_snapshot", d.reason)
        assertFalse(d.showCachedContent)
        assertNull(d.badgeText)
    }

    @Test
    fun `FAILURE с просроченным кэшем - ERROR cache_expired, снимок не показывается`() {
        val d = evaluate(snapshotTakenAtMs = now - 13 * hour) // TODOS окно 12ч
        assertEquals(OfflineDataStatus.ERROR, d.status)
        assertEquals("snapshot_expired", d.reason)
        assertFalse(d.showCachedContent)
        assertNull(d.badgeText)
    }

    @Test
    fun `FAILURE с пригодным кэшем - CACHED, бейдж с возрастом и баннер о сбое`() {
        val d = evaluate(snapshotTakenAtMs = now - 5 * minute)
        assertEquals(OfflineDataStatus.CACHED, d.status)
        assertEquals("snapshot_after_failure", d.reason)
        assertTrue(d.showCachedContent)
        assertEquals("Кэш (5 мин назад)", d.badgeText)
        assertEquals(OfflineCacheUiPolicy.FAILURE_BANNER, d.bannerText)
    }

    @Test
    fun `PENDING с кэшем - CACHED c бейджем, но без тревожного баннера`() {
        val d = evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = now - 90_000)
        assertEquals(OfflineDataStatus.CACHED, d.status)
        assertEquals("snapshot_prefetch", d.reason)
        assertTrue(d.showCachedContent)
        assertEquals("Кэш (1 мин назад)", d.badgeText)
        assertNull(d.bannerText)
    }

    @Test
    fun `PENDING без кэша - LOADING`() {
        val d = evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = null)
        assertEquals(OfflineDataStatus.LOADING, d.status)
        assertEquals("loading_no_snapshot", d.reason)
        assertFalse(d.showCachedContent)
    }

    @Test
    fun `PENDING с просроченным кэшем - LOADING, протухшее на старте не показываем`() {
        val d = evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = now - 13 * hour)
        assertEquals(OfflineDataStatus.LOADING, d.status)
        assertEquals("loading_expired_snapshot", d.reason)
        assertFalse(d.showCachedContent)
    }

    @Test
    fun `окна различаются по экранам - 13ч для проектов пригодно, для задач нет`() {
        val projects = evaluate(
            surface = MobileOfflineSurface.PROJECTS,
            snapshotTakenAtMs = now - 13 * hour,
        )
        val todos = evaluate(snapshotTakenAtMs = now - 13 * hour)
        assertEquals(OfflineDataStatus.CACHED, projects.status)
        assertEquals("Кэш (13 ч назад)", projects.badgeText)
        assertEquals(OfflineDataStatus.ERROR, todos.status)
    }

    // ------------------------------------------------------------- инварианты

    @Test
    fun `инвариант - бейдж только у CACHED`() {
        val decisions = listOf(
            evaluate(fetchState = OfflineFetchState.SUCCESS),
            evaluate(fetchState = OfflineFetchState.SUCCESS, livePayloadEmpty = true),
            evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = null),
            evaluate(fetchState = OfflineFetchState.FAILURE, snapshotTakenAtMs = null),
            evaluate(fetchState = OfflineFetchState.FAILURE, snapshotTakenAtMs = now - 13 * hour),
            evaluate(fetchState = OfflineFetchState.FAILURE, snapshotTakenAtMs = now - minute),
        )
        decisions.forEach { d ->
            assertEquals(
                "бейдж вне CACHED: ${d.reason}",
                d.status == OfflineDataStatus.CACHED,
                d.badgeText != null,
            )
        }
    }

    @Test
    fun `инвариант - баннер об ошибке только при CACHED после сбоя`() {
        val afterFailure = evaluate(snapshotTakenAtMs = now - minute)
        val loading = evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = now - minute)
        assertEquals(OfflineCacheUiPolicy.FAILURE_BANNER, afterFailure.bannerText)
        assertNull(loading.bannerText)
        listOf(afterFailure, loading, evaluate(fetchState = OfflineFetchState.SUCCESS)).forEach { d ->
            assertEquals(d.bannerText != null, d.status == OfflineDataStatus.CACHED && d.reason == "snapshot_after_failure")
        }
    }

    @Test
    fun `инвариант - showCachedContent только у CACHED`() {
        for (state in OfflineFetchState.entries) {
            for (snapshot in listOf<Long?>(null, now - 5 * minute, now - 2 * day)) {
                val d = evaluate(fetchState = state, snapshotTakenAtMs = snapshot)
                assertEquals(
                    "showCachedContent вне CACHED: ${d.reason}",
                    d.status == OfflineDataStatus.CACHED,
                    d.showCachedContent,
                )
            }
        }
    }

    // ------------------------------------------------------------ сценарий UI

    @Test
    fun `сценарий - старт из кэша, успех сети, затем сбой сети`() {
        val savedAt = now - 10 * minute

        // 1. Старт: кэш мгновенно на экране, запрос в полёте.
        val startup = evaluate(fetchState = OfflineFetchState.PENDING, snapshotTakenAtMs = savedAt)
        assertEquals(OfflineDataStatus.CACHED, startup.status)
        assertTrue(startup.showCachedContent)
        assertNull("на старте без сбоя тревожный баннер не нужен", startup.bannerText)

        // 2. Сеть ответила: LIVE, бейдж исчезает.
        val live = evaluate(fetchState = OfflineFetchState.SUCCESS, snapshotTakenAtMs = now)
        assertEquals(OfflineDataStatus.LIVE, live.status)
        assertFalse(live.showCachedContent)
        assertNull(live.badgeText)

        // 3. Фоновый рефреш упал: возвращаемся на кэш, содержимое не затирается.
        val failed = evaluate(fetchState = OfflineFetchState.FAILURE, snapshotTakenAtMs = now - minute)
        assertEquals(OfflineDataStatus.CACHED, failed.status)
        assertTrue("сбой сети не должен стирать показанные данные", failed.showCachedContent)
        assertEquals("Кэш (1 мин назад)", failed.badgeText)
        assertEquals(OfflineCacheUiPolicy.FAILURE_BANNER, failed.bannerText)
    }
}
