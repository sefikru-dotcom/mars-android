package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TvOfflineAgeBadge] — чистого форматтера возрастных плашек офлайн-кэша.
 * Покрывают все диапазоны («только что» / минуты / часы / дни), flooring до целых
 * единиц, clamp отрицательного возраста (откат часов) и композицию плашек.
 */
class TvOfflineAgeBadgeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // --- «только что»: возраст меньше минуты, включая отрицательный (skewed clock) ---

    @Test
    fun `zero age reads just now`() {
        assertEquals("только что", TvOfflineAgeBadge.ageText(0))
    }

    @Test
    fun `sub-minute ages read just now`() {
        assertEquals("только что", TvOfflineAgeBadge.ageText(1))
        assertEquals("только что", TvOfflineAgeBadge.ageText(30_000))
        assertEquals("только что", TvOfflineAgeBadge.ageText(minute - 1))
    }

    @Test
    fun `negative age is clamped to just now, never a negative count`() {
        assertEquals("только что", TvOfflineAgeBadge.ageText(-1))
        assertEquals("только что", TvOfflineAgeBadge.ageText(-10 * minute))
    }

    // --- минуты: [1 мин, 1 ч) ---

    @Test
    fun `minute ages floor to whole minutes`() {
        assertEquals("1 мин. назад", TvOfflineAgeBadge.ageText(minute))
        assertEquals("5 мин. назад", TvOfflineAgeBadge.ageText(5 * minute))
        assertEquals("5 мин. назад", TvOfflineAgeBadge.ageText(5 * minute + 30_000))
        assertEquals("59 мин. назад", TvOfflineAgeBadge.ageText(hour - 1))
    }

    // --- часы: [1 ч, 24 ч) ---

    @Test
    fun `hour ages floor to whole hours`() {
        assertEquals("1 ч. назад", TvOfflineAgeBadge.ageText(hour))
        assertEquals("1 ч. назад", TvOfflineAgeBadge.ageText(90 * minute))
        assertEquals("5 ч. назад", TvOfflineAgeBadge.ageText(5 * hour))
        assertEquals("23 ч. назад", TvOfflineAgeBadge.ageText(day - 1))
    }

    // --- дни: [24 ч, +inf) ---

    @Test
    fun `day ages floor to whole days`() {
        assertEquals("1 дн. назад", TvOfflineAgeBadge.ageText(day))
        assertEquals("1 дн. назад", TvOfflineAgeBadge.ageText(36 * hour))
        assertEquals("3 дн. назад", TvOfflineAgeBadge.ageText(3 * day + 5 * hour))
        assertEquals("30 дн. назад", TvOfflineAgeBadge.ageText(30 * day))
    }

    // --- возраст по абсолютным меткам времени ---

    @Test
    fun `ageMs subtracts timestamps and clamps backwards clocks to zero`() {
        val now = 1_760_000_000_000L
        assertEquals(5 * minute, TvOfflineAgeBadge.ageMs(now - 5 * minute, now))
        assertEquals(0L, TvOfflineAgeBadge.ageMs(now, now))
        assertEquals("откат часов не даёт отрицательный возраст", 0L, TvOfflineAgeBadge.ageMs(now + minute, now))
    }

    // --- композиция плашек ---

    @Test
    fun `cached badge composes prefix with age text`() {
        assertEquals("Снимок из кэша (5 мин. назад)", TvOfflineAgeBadge.cachedBadge(5 * minute))
        assertEquals("Снимок из кэша (2 ч. назад)", TvOfflineAgeBadge.cachedBadge(2 * hour))
        assertEquals("Снимок из кэша (3 дн. назад)", TvOfflineAgeBadge.cachedBadge(3 * day))
        assertEquals("Снимок из кэша (только что)", TvOfflineAgeBadge.cachedBadge(10_000))
    }

    @Test
    fun `cached badge overload takes absolute timestamps`() {
        val now = 1_760_000_000_000L
        assertEquals(
            "Снимок из кэша (10 мин. назад)",
            TvOfflineAgeBadge.cachedBadge(now - 10 * minute, now),
        )
        // Skewed clock (сохранение «в будущем») — честное «только что», не минус.
        assertEquals(
            "Снимок из кэша (только что)",
            TvOfflineAgeBadge.cachedBadge(now + minute, now),
        )
    }

    @Test
    fun `every cached badge carries the stable prefix hosts can match on`() {
        listOf(0L, minute, hour, day, 100 * day).forEach { age ->
            assertTrue(
                "плашка должна начинаться с префикса: $age",
                TvOfflineAgeBadge.cachedBadge(age).startsWith(TvOfflineAgeBadge.CACHED_BADGE_PREFIX),
            )
        }
    }

    @Test
    fun `live badge is the fixed fresh-answer copy`() {
        assertEquals("Обновлено только что", TvOfflineAgeBadge.LIVE_BADGE)
    }
}
