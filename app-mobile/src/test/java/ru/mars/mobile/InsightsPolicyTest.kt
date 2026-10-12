package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.InsightsDailyTokens
import ru.mars.network.model.InsightsHourActivity
import ru.mars.network.model.InsightsModelUsage
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.InsightsWeekdayActivity
import ru.mars.network.repository.InsightsResult

/**
 * Чистые тесты [InsightsPolicy] против реального серверного контракта
 * `GET /api/insights` (деплойд `route_insights.py`): whitelist периодов с
 * серверным default 30, форматтеры, isEmpty по серверным totals и раскладка
 * totals/models/daily_tokens/activity_by_day/activity_by_hour в строки экрана
 * (isEmpty — только по серверным totals: daily/weekday/hour ряды сервер
 * zero-fill'ит, и по их наличию пустоту периода судить нельзя).
 */
class InsightsPolicyTest {

    /** Снимок, каким сервер отвечает за период БЕЗ активности: totals нулевые, ряды zero-filled. */
    private fun zeroFilledSnapshot() = InsightsSnapshot(
        periodDays = 7,
        totalSessions = 0,
        totalMessages = 0,
        totalInputTokens = 0L,
        totalOutputTokens = 0L,
        totalCacheReadTokens = 0L,
        totalCacheHitPercent = null,
        totalTokens = 0L,
        totalCost = 0.0,
        models = emptyList(),
        dailyTokens = (6 downTo 0).map { InsightsDailyTokens(date = "2026-10-${12 - it}") },
        activityByDay = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            .map { InsightsWeekdayActivity(it, 0) },
        activityByHour = (0..23).map { InsightsHourActivity(it, 0) },
    )

    /** Числа из реального payload, снятого с деплойд-обработчика (days=7, 2026-10-12). */
    private fun realUsageSnapshot() = InsightsSnapshot(
        periodDays = 7,
        totalSessions = 4,
        totalMessages = 72,
        totalInputTokens = 304_540L,
        totalOutputTokens = 89_640L,
        totalCacheReadTokens = 914_110L,
        totalCacheHitPercent = 75,
        totalTokens = 394_180L,
        totalCost = 1.8555,
        models = listOf(
            InsightsModelUsage(
                model = "claude-sonnet-4-5", sessions = 2,
                inputTokens = 202_740L, outputTokens = 57_320L, cacheReadTokens = 902_110L,
                cacheHitPercent = 82, totalTokens = 260_060L, cost = 1.4355,
                sessionShare = 50, tokenShare = 66, costShare = 77,
            ),
            InsightsModelUsage(
                model = "gpt-5.5", sessions = 1,
                inputTokens = 94_500L, outputTokens = 30_210L, cacheReadTokens = 12_000L,
                cacheHitPercent = 11, totalTokens = 124_710L, cost = 0.42,
                sessionShare = 25, tokenShare = 32, costShare = 23,
            ),
            InsightsModelUsage(
                model = "unknown", sessions = 1,
                inputTokens = 7_300L, outputTokens = 2_110L, cacheReadTokens = 0L,
                cacheHitPercent = null, totalTokens = 9_410L, cost = 0.0,
                sessionShare = 25, tokenShare = 2, costShare = 0,
            ),
        ),
        dailyTokens = listOf(
            InsightsDailyTokens("2026-10-06"),
            InsightsDailyTokens("2026-10-07", 7_300L, 2_110L, 0L, 1, 0.0),
            InsightsDailyTokens("2026-10-08"),
            InsightsDailyTokens("2026-10-09"),
            InsightsDailyTokens("2026-10-10", 94_500L, 30_210L, 12_000L, 1, 0.42),
            InsightsDailyTokens("2026-10-11"),
            InsightsDailyTokens("2026-10-12", 202_740L, 57_320L, 902_110L, 2, 1.4355),
        ),
        activityByDay = listOf(
            InsightsWeekdayActivity("Mon", 2), InsightsWeekdayActivity("Tue", 0),
            InsightsWeekdayActivity("Wed", 1), InsightsWeekdayActivity("Thu", 0),
            InsightsWeekdayActivity("Fri", 0), InsightsWeekdayActivity("Sat", 1),
            InsightsWeekdayActivity("Sun", 0),
        ),
        activityByHour = (0..23).map { InsightsHourActivity(it, if (it in setOf(4, 9, 14, 21)) 1 else 0) },
    )

    // ── периоды: серверный default 30, клиентский whitelist, clamp — на сервере ──

    @Test
    fun defaultPeriodIsServerDefaultThirty() {
        assertEquals(30, InsightsPolicy.DEFAULT_DAYS)
        assertEquals(listOf(1, 7, 30, 90), InsightsPolicy.PERIOD_DAYS)
        assertEquals(30, InsightsPolicy.sanitizePeriod(30))
    }

    @Test
    fun sanitizePeriodFallsBackToDefaultForNonWhitelisted() {
        assertEquals(InsightsPolicy.DEFAULT_DAYS, InsightsPolicy.sanitizePeriod(5))
        assertEquals(InsightsPolicy.DEFAULT_DAYS, InsightsPolicy.sanitizePeriod(0))
        assertEquals(InsightsPolicy.DEFAULT_DAYS, InsightsPolicy.sanitizePeriod(-7))
        // 365 сервер бы принял (clamp 1..365), но чипов на него нет — дефолт.
        assertEquals(InsightsPolicy.DEFAULT_DAYS, InsightsPolicy.sanitizePeriod(365))
        assertEquals(1, InsightsPolicy.sanitizePeriod(1))
        assertEquals(7, InsightsPolicy.sanitizePeriod(7))
        assertEquals(90, InsightsPolicy.sanitizePeriod(90))
    }

    @Test
    fun daysLabelPluralizes() {
        assertEquals("1 день", InsightsPolicy.daysLabel(1))
        assertEquals("7 дней", InsightsPolicy.daysLabel(7))
        assertEquals("22 дня", InsightsPolicy.daysLabel(22))
        assertEquals("30 дней", InsightsPolicy.daysLabel(30))
    }

    @Test
    fun pluralHandlesRussianMod100Exceptions() {
        assertEquals("сессия", InsightsPolicy.plural(21, "сессия", "сессии", "сессий"))
        assertEquals("сессии", InsightsPolicy.plural(2, "сессия", "сессии", "сессий"))
        assertEquals("сессий", InsightsPolicy.plural(11, "сессия", "сессии", "сессий"))
        assertEquals("сессий", InsightsPolicy.plural(0, "сессия", "сессии", "сессий"))
    }

    // ── форматтеры ──

    @Test
    fun formatCountGroupsByThousands() {
        assertEquals("0", InsightsPolicy.formatCount(0L))
        assertEquals("42", InsightsPolicy.formatCount(42L))
        assertEquals("394 180", InsightsPolicy.formatCount(394_180L))
        assertEquals("914 110", InsightsPolicy.formatCount(914_110L))
        assertEquals("1 234 567 890", InsightsPolicy.formatCount(1_234_567_890L))
    }

    @Test
    fun formatCostRendersDollarsWithSanePrecision() {
        assertEquals("\$0", InsightsPolicy.formatCost(0.0))
        assertEquals("\$0", InsightsPolicy.formatCost(-1.0))
        assertEquals("\$1.86", InsightsPolicy.formatCost(1.8555))
        assertEquals("\$1.44", InsightsPolicy.formatCost(1.4355))
        assertEquals("\$0.42", InsightsPolicy.formatCost(0.42))
        assertEquals("\$0.0323", InsightsPolicy.formatCost(0.0323))
        assertEquals("\$0.000012", InsightsPolicy.formatCost(0.0000123))
        assertEquals("\$12.5", InsightsPolicy.formatCost(12.5))
        assertEquals("\$100", InsightsPolicy.formatCost(100.0))
    }

    @Test
    fun labelHelpersDegradeOnGarbage() {
        assertEquals("12.10", InsightsPolicy.shortDate("2026-10-12"))
        assertEquals("junk", InsightsPolicy.shortDate("junk"))
        assertEquals("", InsightsPolicy.shortDate(""))
        assertEquals("Пн", InsightsPolicy.weekdayLabel("Mon"))
        assertEquals("Вс", InsightsPolicy.weekdayLabel("Sun"))
        assertEquals("Xxx", InsightsPolicy.weekdayLabel("Xxx"))
        assertEquals("04:00", InsightsPolicy.hourLabel(4))
        assertEquals("00:00", InsightsPolicy.hourLabel(0))
        assertEquals("23:00", InsightsPolicy.hourLabel(23))
    }

    // ── isEmpty: именно серверные totals (ряды zero-filled всегда непустые) ──

    @Test
    fun isEmptyUsesServerTotalsNotZeroFilledSeries() {
        val base = zeroFilledSnapshot()
        // Zero-filled daily/weekday/hour ряды НЕ спасают: период без активности — EMPTY.
        assertTrue(base.dailyTokens.isNotEmpty() && base.activityByHour.size == 24)
        assertTrue(InsightsPolicy.isEmpty(base))
        assertFalse(InsightsPolicy.isEmpty(base.copy(totalSessions = 1)))
        assertFalse(InsightsPolicy.isEmpty(base.copy(totalMessages = 7)))
        assertFalse(InsightsPolicy.isEmpty(base.copy(totalTokens = 1_500L)))
        assertFalse(InsightsPolicy.isEmpty(base.copy(totalCost = 0.01)))
        assertFalse(InsightsPolicy.isEmpty(realUsageSnapshot()))
    }

    // ── раскладка в строки ──

    @Test
    fun rowsRenderAllContractSectionsInStableOrder() {
        val rows = InsightsPolicy.rows(realUsageSnapshot())
        assertEquals(
            listOf(
                "Итоги за период", "Модели", "Токены по дням",
                "Активность по дням недели", "Активность по часам",
            ),
            rows.filterIsInstance<InsightsRow.Section>().map { it.title },
        )
    }

    @Test
    fun summaryStatsShowTotalsWithTones() {
        val stats = InsightsPolicy.rows(realUsageSnapshot())
            .filterIsInstance<InsightsRow.Stat>()
            .associate { it.label to it }
        assertEquals("7 дней", stats.getValue("Период").value)
        assertEquals("4", stats.getValue("Сессии").value)
        assertEquals("72", stats.getValue("Сообщения").value)
        assertEquals("394 180", stats.getValue("Токены").value)
        assertEquals("304 540", stats.getValue("Входные токены").value)
        assertEquals("89 640", stats.getValue("Выходные токены").value)
        assertEquals("914 110", stats.getValue("Кеш-прочтения").value)
        assertEquals("75%", stats.getValue("Попадание в кеш").value)
        assertEquals(StatTone.GOOD, stats.getValue("Попадание в кеш").tone)
        assertEquals("\$1.86", stats.getValue("Стоимость").value)
        assertEquals(StatTone.WARN, stats.getValue("Стоимость").tone)
    }

    @Test
    fun nullCacheHitAndZeroCacheReadsProduceNoCacheRows() {
        val snapshot = realUsageSnapshot().copy(
            totalCacheReadTokens = 0L,
            totalCacheHitPercent = null,
        )
        val labels = InsightsPolicy.rows(snapshot)
            .filterIsInstance<InsightsRow.Stat>()
            .map { it.label }
        assertFalse(labels.contains("Попадание в кеш"))
        assertFalse(labels.contains("Кеш-прочтения"))
    }

    @Test
    fun modelRowsCarryMetaSharesAndNullSafeCache() {
        val models = InsightsPolicy.rows(realUsageSnapshot())
            .filterIsInstance<InsightsRow.ModelRow>()
        assertEquals(listOf("claude-sonnet-4-5", "gpt-5.5", "unknown"), models.map { it.title })
        val top = models[0]
        assertTrue(top.meta.contains("2 сессии"))
        assertTrue(top.meta.contains("260 060 токенов"))
        assertTrue(top.meta.contains("кеш 82%"))
        assertEquals("\$1.44", top.costLabel)
        // cache_hit_percent = null — легальное серверное значение, «кеш» не показываем.
        val unknown = models[2]
        assertFalse(unknown.meta.contains("кеш"))
        assertEquals("\$0", unknown.costLabel)
    }

    @Test
    fun modelRowsAreCappedWithNote() {
        val many = (1..(InsightsPolicy.MODEL_RENDER_LIMIT + 3)).map {
            InsightsModelUsage(model = "m$it", sessions = 1, totalTokens = 10L, cost = 0.1)
        }
        val rows = InsightsPolicy.rows(realUsageSnapshot().copy(models = many))
        val rendered = rows.filterIsInstance<InsightsRow.ModelRow>()
        assertEquals(InsightsPolicy.MODEL_RENDER_LIMIT, rendered.size)
        val note = rows.filterIsInstance<InsightsRow.Note>().single()
        assertTrue(note.text.contains((InsightsPolicy.MODEL_RENDER_LIMIT + 3).toString()))
    }

    @Test
    fun dailyBarsKeepZeroFilledDaysAndWeightRelativeToMax() {
        val bars = InsightsPolicy.rows(realUsageSnapshot())
            .filterIsInstance<InsightsRow.BarRow>()
            .take(7) // секция «Токены по дням» — первая с барами
        assertEquals(listOf("06.10", "07.10", "08.10", "09.10", "10.10", "11.10", "12.10"), bars.map { it.label })
        assertEquals(0f, bars[0].barWeight, 0.0001f)
        assertEquals("9 410", bars[1].value)
        assertEquals(1f, bars[6].barWeight, 0.0001f)
        assertEquals("260 060", bars[6].value)
    }

    @Test
    fun dailySeriesIsCappedToLastDaysWithNote() {
        val long = (1..90).map {
            InsightsDailyTokens(date = "2026-%02d-%02d".format(7 + it / 28, it % 28 + 1), inputTokens = it.toLong())
        }
        val rows = InsightsPolicy.rows(realUsageSnapshot().copy(dailyTokens = long))
        val sectionIndex = rows.indexOfFirst {
            it is InsightsRow.Section && it.title == "Токены по дням"
        }
        val bars = rows.drop(sectionIndex + 1).takeWhile { it is InsightsRow.BarRow }
            .filterIsInstance<InsightsRow.BarRow>()
        assertEquals(InsightsPolicy.DAILY_RENDER_LIMIT, bars.size)
        // takeLast: показан хвост серии, последний бар — максимальный.
        assertEquals(1f, bars.last().barWeight, 0.0001f)
        val note = rows.filterIsInstance<InsightsRow.Note>()
            .single { it.text.contains("90") }
        assertTrue(note.text.contains(InsightsPolicy.DAILY_RENDER_LIMIT.toString()))
    }

    @Test
    fun weekdayAndHourSectionsMapServerBuckets() {
        val rows = InsightsPolicy.rows(realUsageSnapshot())
        val weekdayIndex = rows.indexOfFirst {
            it is InsightsRow.Section && it.title == "Активность по дням недели"
        }
        val weekday = rows.drop(weekdayIndex + 1).take(7).filterIsInstance<InsightsRow.BarRow>()
        assertEquals(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"), weekday.map { it.label })
        assertEquals("2", weekday[0].value)
        assertEquals(1f, weekday[0].barWeight, 0.0001f)
        assertEquals(0f, weekday[1].barWeight, 0.0001f)

        val hourIndex = rows.indexOfFirst {
            it is InsightsRow.Section && it.title == "Активность по часам"
        }
        val hours = rows.drop(hourIndex + 1).take(24).filterIsInstance<InsightsRow.BarRow>()
        assertEquals(24, hours.size)
        assertEquals("00:00", hours[0].label)
        assertEquals(1f, hours[4].barWeight, 0.0001f)
        assertEquals("21:00", hours[21].label)
    }

    @Test
    fun allZeroBucketSectionsAreSkipped() {
        val snapshot = realUsageSnapshot().copy(
            dailyTokens = listOf(InsightsDailyTokens("2026-10-12")),
            activityByDay = listOf(InsightsWeekdayActivity("Mon", 0)),
            activityByHour = listOf(InsightsHourActivity(9, 0)),
        )
        val titles = InsightsPolicy.rows(snapshot)
            .filterIsInstance<InsightsRow.Section>()
            .map { it.title }
        assertFalse(titles.contains("Токены по дням"))
        assertFalse(titles.contains("Активность по дням недели"))
        assertFalse(titles.contains("Активность по часам"))
    }

    @Test
    fun zeroFilledSnapshotRendersNoBarsAndIsEmpty() {
        val rows = InsightsPolicy.rows(zeroFilledSnapshot())
        assertTrue(rows.filterIsInstance<InsightsRow.BarRow>().isEmpty())
        assertTrue(rows.filterIsInstance<InsightsRow.ModelRow>().isEmpty())
        assertTrue(InsightsPolicy.isEmpty(zeroFilledSnapshot()))
    }

    @Test
    fun weightOfIsClamped() {
        assertEquals(0f, InsightsPolicy.weightOf(0L, 10L), 0.0001f)
        assertEquals(0.5f, InsightsPolicy.weightOf(5L, 10L), 0.0001f)
        assertEquals(1f, InsightsPolicy.weightOf(10L, 10L), 0.0001f)
        assertEquals(1f, InsightsPolicy.weightOf(50L, 10L), 0.0001f)
        assertEquals(0f, InsightsPolicy.weightOf(5L, 0L), 0.0001f)
    }

    @Test
    fun errorMessageIsGenericForAnyFailure() {
        // У маршрута нет 400/503-веток: любой сбой — общий текст, серверная
        // деталь остаётся в Failed.message для диагностики.
        assertNull(InsightsPolicy.errorMessage(InsightsResult.Snapshot(InsightsSnapshot())))
        assertEquals(
            InsightsPolicy.MSG_ERROR,
            InsightsPolicy.errorMessage(InsightsResult.Failed("Missing token")),
        )
    }
}
