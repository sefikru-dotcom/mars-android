package ru.mars.mobile

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.CronRunSummary
import ru.mars.network.model.CronRunUsage

/**
 * JVM-тесты политики CRUD планировщика: валидация формы по правилам серверного
 * parse_schedule (hermes-agent cron/jobs.py), форматирование истории прогонов
 * и тексты подтверждений. Без Android и сети.
 */
class CronCrudPolicyTest {

    // ── Классификация расписания (зеркалит серверный parse_schedule) ───────────

    @Test
    fun classifySchedule_interval() {
        assertEquals(CronScheduleKind.INTERVAL, CronCrudPolicy.classifySchedule("30m"))
        assertEquals(CronScheduleKind.INTERVAL, CronCrudPolicy.classifySchedule("every 30m"))
        assertEquals(CronScheduleKind.INTERVAL, CronCrudPolicy.classifySchedule("every 2h"))
        assertEquals(CronScheduleKind.INTERVAL, CronCrudPolicy.classifySchedule("  45m  "))
    }

    @Test
    fun classifySchedule_oneShotDelay() {
        assertEquals(CronScheduleKind.ONE_SHOT_DELAY, CronCrudPolicy.classifySchedule("in 30m"))
        assertEquals(CronScheduleKind.ONE_SHOT_DELAY, CronCrudPolicy.classifySchedule("in 2h"))
    }

    @Test
    fun classifySchedule_cronExpression() {
        assertEquals(CronScheduleKind.CRON_EXPR, CronCrudPolicy.classifySchedule("0 9 * * *"))
        assertEquals(CronScheduleKind.CRON_EXPR, CronCrudPolicy.classifySchedule("*/15 * * * 1-5"))
        assertEquals(CronScheduleKind.CRON_EXPR, CronCrudPolicy.classifySchedule("0 7 * * MON-FRI"))
    }

    @Test
    fun classifySchedule_naturalWeekly() {
        assertEquals(CronScheduleKind.NATURAL_WEEKLY, CronCrudPolicy.classifySchedule("every monday 9am"))
        assertEquals(CronScheduleKind.NATURAL_WEEKLY, CronCrudPolicy.classifySchedule("weekdays at 9am"))
    }

    @Test
    fun classifySchedule_timestamp() {
        assertEquals(CronScheduleKind.TIMESTAMP, CronCrudPolicy.classifySchedule("2026-10-14T07:00:00"))
        assertEquals(CronScheduleKind.TIMESTAMP, CronCrudPolicy.classifySchedule("2026-11-01"))
    }

    @Test
    fun classifySchedule_invalid() {
        assertEquals(CronScheduleKind.INVALID, CronCrudPolicy.classifySchedule(""))
        assertEquals(CronScheduleKind.INVALID, CronCrudPolicy.classifySchedule("   "))
        assertEquals(CronScheduleKind.INVALID, CronCrudPolicy.classifySchedule("hello world"))
        assertEquals(CronScheduleKind.INVALID, CronCrudPolicy.classifySchedule("every blah"))
        assertEquals(CronScheduleKind.INVALID, CronCrudPolicy.classifySchedule("in soon"))
    }

    // ── Валидация формы создания/правки ────────────────────────────────────────

    @Test
    fun validateForm_acceptsValidInput() {
        val result = CronCrudPolicy.validateForm(prompt = "Сводка утра", schedule = "0 7 * * *")
        assertTrue(result.ok)
        assertNull(result.promptError)
        assertNull(result.scheduleError)
    }

    @Test
    fun validateForm_rejectsBlankPrompt() {
        val result = CronCrudPolicy.validateForm(prompt = "   ", schedule = "30m")
        assertFalse(result.ok)
        assertNotNull(result.promptError)
    }

    @Test
    fun validateForm_rejectsInvalidSchedule_withHint() {
        val result = CronCrudPolicy.validateForm(prompt = "P", schedule = "когда-нибудь")
        assertFalse(result.ok)
        assertNotNull(result.scheduleError)
        // Подсказка перечисляет поддерживаемые сервером формы.
        assertTrue(result.scheduleError!!.contains("30m"))
        assertTrue(result.scheduleError!!.contains("* * *"))
    }

    @Test
    fun validateForm_reportsBothErrors() {
        val result = CronCrudPolicy.validateForm(prompt = "", schedule = "")
        assertFalse(result.ok)
        assertNotNull(result.promptError)
        assertNotNull(result.scheduleError)
    }

    // ── Форматирование истории прогонов ────────────────────────────────────────

    @Test
    fun formatBytes_sizes() {
        assertEquals("", CronCrudPolicy.formatBytes(null))
        assertEquals("0 B", CronCrudPolicy.formatBytes(0))
        assertEquals("345 B", CronCrudPolicy.formatBytes(345))
        assertEquals("1.0 KB", CronCrudPolicy.formatBytes(1024))
        assertEquals("1.5 KB", CronCrudPolicy.formatBytes(1536))
        assertEquals("1.0 MB", CronCrudPolicy.formatBytes(1048576))
        assertEquals("2.5 MB", CronCrudPolicy.formatBytes(2621440))
    }

    @Test
    fun formatUsage_joinsKnownParts() {
        assertEquals("", CronCrudPolicy.formatUsage(null))
        assertEquals("", CronCrudPolicy.formatUsage(CronRunUsage()))
        assertEquals(
            "gpt-x · 12.5s · \$0.002 · 100 tok",
            CronCrudPolicy.formatUsage(
                CronRunUsage(model = "gpt-x", durationSeconds = 12.5, estimatedCostUsd = 0.002, totalTokens = 100),
            ),
        )
        assertEquals("30s", CronCrudPolicy.formatUsage(CronRunUsage(durationSeconds = 30.0)))
    }

    @Test
    fun formatRunRow_withModifiedAndSize() {
        val summary = CronRunSummary(
            filename = "2026-10-14T07-00-00.md",
            sizeBytes = 1536L,
            modifiedEpochSeconds = 1792000000.0,
            usage = null,
        )
        val row = CronCrudPolicy.formatRunRow(summary, ZoneOffset.UTC)
        val expectedTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochSecond(1792000000))
        assertTrue(row.contains(expectedTime))
        assertTrue(row.contains("1.5 KB"))
    }

    @Test
    fun formatRunRow_fallbackToFilenameAndUsage() {
        val noMeta = CronRunSummary(filename = "run-77.md", sizeBytes = null, modifiedEpochSeconds = null, usage = null)
        assertEquals("run-77.md", CronCrudPolicy.formatRunRow(noMeta, ZoneOffset.UTC))
        val withUsage = noMeta.copy(usage = CronRunUsage(model = "m1"))
        assertEquals("run-77.md · m1", CronCrudPolicy.formatRunRow(withUsage, ZoneOffset.UTC))
    }

    @Test
    fun emptyHistoryText_isRussian() {
        assertTrue(CronCrudPolicy.emptyHistoryText().isNotBlank())
    }

    // ── Подтверждение удаления ─────────────────────────────────────────────────

    @Test
    fun deleteConfirmText_mentionsJobName() {
        val text = CronCrudPolicy.deleteConfirmText("Утренняя сводка")
        assertTrue(text.contains("Утренняя сводка"))
        assertTrue(text.lowercase().contains("необратимо"))
    }
}
