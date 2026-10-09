package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsSummaryTest {
    private fun service(active: Boolean) = ParsedService(
        title = "t", description = "", url = "", state = if (active) "active" else "offline",
        active = active, uptime = "", latency = "",
    )

    @Test fun countsOnlineServices() {
        val s = ProjectsSummary.of(listOf(service(true), service(false), service(true)))
        assertEquals(2, s.online)
        assertEquals(3, s.total)
        assertTrue(s.hasOffline)
    }

    @Test fun allOnlineHasNoWarning() {
        assertFalse(ProjectsSummary.of(listOf(service(true), service(true))).hasOffline)
    }

    @Test fun emptyServiceListIsNotOffline() {
        val s = ProjectsSummary.of(emptyList())
        assertEquals(0, s.total)
        assertFalse(s.hasOffline)
    }

    @Test fun statusLineWithServices() {
        val line = ProjectsSummary.statusLine(ProjectsSummary.Summary(3, 4), "12:00:05")
        assertEquals("Сервисы: 3 из 4 онлайн · Обновлено в 12:00:05", line)
    }

    @Test fun statusLineWithoutServicesShowsClockOnly() {
        assertEquals("Обновлено в 08:30:00", ProjectsSummary.statusLine(ProjectsSummary.Summary(0, 0), "08:30:00"))
    }

    @Test fun offlineLineMentionsStaleSavedList() {
        assertEquals(
            "Метрики недоступны — показан сохранённый список · проектов: 4",
            ProjectsSummary.offlineLine(4),
        )
    }
}
