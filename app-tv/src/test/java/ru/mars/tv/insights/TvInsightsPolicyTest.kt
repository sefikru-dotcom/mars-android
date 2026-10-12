package ru.mars.tv.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.InsightsDailyTokens
import ru.mars.network.model.InsightsHourActivity
import ru.mars.network.model.InsightsModelUsage
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.InsightsWeekdayActivity

/**
 * Unit-тесты [TvInsightsPolicy] без Android-фреймворка: чипы периода,
 * форматтеры (mobile `InsightsPolicy` parity), пустой снимок и раскладка
 * строк вкладки «Аналитика». Нулевые бары не выдумываются — полностью
 * нулевые секции активности не рендерятся.
 */
class TvInsightsPolicyTest {

    // ── период ──

    @Test
    fun `period sanitizing accepts only whitelisted days`() {
        TvInsightsPolicy.PERIOD_DAYS.forEach { assertEquals(it, TvInsightsPolicy.sanitizePeriod(it)) }
        assertEquals(TvInsightsPolicy.DEFAULT_DAYS, TvInsightsPolicy.sanitizePeriod(3))
        assertEquals(TvInsightsPolicy.DEFAULT_DAYS, TvInsightsPolicy.sanitizePeriod(0))
        assertEquals(TvInsightsPolicy.DEFAULT_DAYS, TvInsightsPolicy.sanitizePeriod(-7))
    }

    @Test
    fun `days label declines correctly`() {
        assertEquals("1 день", TvInsightsPolicy.daysLabel(1))
        assertEquals("7 дней", TvInsightsPolicy.daysLabel(7))
        assertEquals("30 дней", TvInsightsPolicy.daysLabel(30))
        assertEquals("90 дней", TvInsightsPolicy.daysLabel(90))
        assertEquals("22 дня", TvInsightsPolicy.daysLabel(22))
    }

    // ── форматтеры ──

    @Test
    fun `count formatting groups digits with a thin space`() {
        assertEquals("0", TvInsightsPolicy.formatCount(0))
        assertEquals("902 110", TvInsightsPolicy.formatCount(902_110))
        assertEquals("1 000 000", TvInsightsPolicy.formatCount(1_000_000))
    }

    @Test
    fun `cost formatting keeps two decimals for dollars and trims small values`() {
        assertEquals("$0", TvInsightsPolicy.formatCost(0.0))
        assertEquals("$0", TvInsightsPolicy.formatCost(-1.0))
        assertEquals("$0", TvInsightsPolicy.formatCost(Double.NaN))
        assertEquals("$1.5", TvInsightsPolicy.formatCost(1.5))
        assertEquals("$12.35", TvInsightsPolicy.formatCost(12.3456))
        assertEquals("$0.000123", TvInsightsPolicy.formatCost(0.000123))
    }

    @Test
    fun `short date converts only the ISO form`() {
        assertEquals("12.10", TvInsightsPolicy.shortDate("2026-10-12"))
        assertEquals("garbage-10-12", TvInsightsPolicy.shortDate("garbage-10-12"))
        assertEquals("не дата", TvInsightsPolicy.shortDate("не дата"))
    }

    @Test
    fun `weekday labels translate server buckets and keep unknown ones verbatim`() {
        assertEquals("Пн", TvInsightsPolicy.weekdayLabel("Mon"))
        assertEquals("Вс", TvInsightsPolicy.weekdayLabel("Sun"))
        assertEquals("Funday", TvInsightsPolicy.weekdayLabel("Funday"))
    }

    @Test
    fun `hour label is zero padded`() {
        assertEquals("00:00", TvInsightsPolicy.hourLabel(0))
        assertEquals("09:00", TvInsightsPolicy.hourLabel(9))
        assertEquals("23:00", TvInsightsPolicy.hourLabel(23))
    }

    // ── пустой снимок ──

    @Test
    fun `empty snapshot is detected by all-zero totals and reads as a note`() {
        assertTrue(TvInsightsPolicy.isEmpty(InsightsSnapshot()))
        assertFalse(TvInsightsPolicy.isEmpty(InsightsSnapshot(totalSessions = 1)))
        val rows = TvInsightsPolicy.rows(InsightsSnapshot(periodDays = 30))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is TvInsightRow.Section)
        assertEquals(TvInsightRow.Note(TvInsightsPolicy.EMPTY_SNAPSHOT), rows[1])
    }

    // ── раскладка строк ──

    private val fullSnapshot = InsightsSnapshot(
        periodDays = 7,
        totalSessions = 12,
        totalMessages = 340,
        totalInputTokens = 1_000_000,
        totalOutputTokens = 50_000,
        totalCacheReadTokens = 700_000,
        totalCacheHitPercent = 41,
        totalTokens = 1_050_000,
        totalCost = 3.4567,
        models = listOf(
            InsightsModelUsage(
                model = "qwen3-max", sessions = 8, tokenShare = 60, costShare = 70,
                cacheHitPercent = 55, cost = 2.5,
            ),
            InsightsModelUsage(model = "", sessions = 4, tokenShare = 40, costShare = 30, cost = 0.9567),
        ),
        dailyTokens = listOf(
            InsightsDailyTokens(date = "2026-10-10", inputTokens = 100, outputTokens = 50, sessions = 2),
            InsightsDailyTokens(date = "2026-10-11", inputTokens = 0, outputTokens = 0, sessions = 0),
            InsightsDailyTokens(date = "2026-10-12", inputTokens = 300, outputTokens = 100, sessions = 5),
        ),
        activityByDay = listOf(
            InsightsWeekdayActivity(day = "Mon", sessions = 4),
            InsightsWeekdayActivity(day = "Sun", sessions = 0),
        ),
        activityByHour = listOf(
            InsightsHourActivity(hour = 3, sessions = 2),
            InsightsHourActivity(hour = 15, sessions = 10),
        ),
    )

    @Test
    fun `rows start with period and usage stats`() {
        val rows = TvInsightsPolicy.rows(fullSnapshot)
        assertEquals(TvInsightRow.Section(TvInsightsPolicy.SECTION_PERIOD, "7 дней"), rows[0])
        assertEquals(TvInsightRow.Section(TvInsightsPolicy.SECTION_USAGE), rows[1])
        assertTrue(rows.contains(TvInsightRow.Stat("Сессий", "12")))
        assertTrue(rows.contains(TvInsightRow.Stat("Сообщений", "340")))
        assertTrue(rows.contains(TvInsightRow.Stat("Стоимость", "$3.46")))
        assertTrue(rows.contains(TvInsightRow.Stat("Попадание в кэш", "41%")))
        assertTrue(rows.contains(TvInsightRow.Stat("Всего", "1 050 000")))
    }

    @Test
    fun `null cache hit renders a dash, never a zero`() {
        val rows = TvInsightsPolicy.rows(fullSnapshot.copy(totalCacheHitPercent = null))
        assertTrue(rows.contains(TvInsightRow.Stat("Попадание в кэш", "—")))
    }

    @Test
    fun `model rows keep the server sort, blank model reads as a dash`() {
        val rows = TvInsightsPolicy.rows(fullSnapshot).filterIsInstance<TvInsightRow.ModelRow>()
        assertEquals(2, rows.size)
        assertEquals("qwen3-max", rows[0].title)
        assertEquals("$2.5", rows[0].cost)
        assertTrue(rows[0].meta!!.contains("сессий: 8"))
        assertTrue(rows[0].meta!!.contains("кэш: 55%"))
        assertEquals("—", rows[1].title)
        assertTrue(rows[1].meta!!.endsWith("кэш: —"))
    }

    @Test
    fun `zero buckets are dropped and bar shares are relative to the section max`() {
        val bars = TvInsightsPolicy.rows(fullSnapshot).filterIsInstance<TvInsightRow.BarRow>()
        // daily: 150 (10.10), 0 — выпал, 400 (12.10); weekday: Mon 4, Sun 0 — выпал; hourly: 3→2, 15→10.
        val daily = bars.filter { it.label.contains('.') || it.label.contains("10.10") }
        assertTrue(bars.any { it.label == "10.10" && it.percentOfMax == 150f / 400f })
        assertTrue(bars.any { it.label == "12.10" && it.percentOfMax == 1f })
        assertTrue(bars.none { it.label == "11.10" })
        assertTrue(bars.any { it.label == "Пн" && it.percentOfMax == 1f })
        assertTrue(bars.none { it.label == "Вс" })
        assertTrue(bars.any { it.label == "15:00" && it.percentOfMax == 1f })
        assertTrue(bars.any { it.label == "03:00" && it.percentOfMax == 0.2f })
        assertTrue(daily.isNotEmpty())
    }

    @Test
    fun `fully zero activity sections are not rendered at all`() {
        val rows = TvInsightsPolicy.rows(
            fullSnapshot.copy(
                activityByDay = listOf(InsightsWeekdayActivity("Mon", 0)),
                activityByHour = listOf(InsightsHourActivity(0, 0)),
            ),
        )
        assertTrue(rows.none { it is TvInsightRow.Section && it.title == TvInsightsPolicy.SECTION_WEEKDAY })
        assertTrue(rows.none { it is TvInsightRow.Section && it.title == TvInsightsPolicy.SECTION_HOURLY })
    }

    @Test
    fun `no models reads as an explicit note`() {
        val rows = TvInsightsPolicy.rows(fullSnapshot.copy(models = emptyList()))
        assertTrue(rows.contains(TvInsightRow.Note(TvInsightsPolicy.NO_MODELS)))
    }

    @Test
    fun `focus restoration clamps and never wraps`() {
        assertEquals(TvInsightsPolicy.NO_SELECTION, TvInsightsPolicy.restoreSelection(3, 0))
        assertEquals(4, TvInsightsPolicy.restoreSelection(9, 5))
        assertEquals(0, TvInsightsPolicy.restoreSelection(-2, 5))
    }
}
