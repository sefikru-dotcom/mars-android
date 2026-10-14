package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsSummaryTest {
    private fun service(
        active: Boolean,
        uptime: String = "",
        restarts: String = "",
        http: String = "",
    ) = ParsedService(
        title = "t", description = "", url = "", state = if (active) "active" else "offline",
        active = active, uptime = uptime, restarts = restarts, http = http,
    )

    private fun host(
        ram: String = ProjectsMetricsParser.MISSING,
        cpu: String = ProjectsMetricsParser.MISSING,
        disk: String = ProjectsMetricsParser.MISSING,
        uptime: String = ProjectsMetricsParser.MISSING,
    ) = ParsedHost(title = "h", ram = ram, cpu = cpu, disk = disk, uptime = uptime)

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

    // --- Свежесть данных: ts ответа, а не время запроса (P0-4, дефект 3) ------------------

    @Test fun freshnessTextUsesResponseTsAndAge() {
        assertEquals(
            "Данные: 14.10.2026 в 16:30:28 (МСК) · 2 мин назад",
            ProjectsSummary.freshnessText("14.10.2026 в 16:30:28 (МСК)", "2 мин назад", "99:99:99"),
        )
    }

    @Test fun freshnessTextWithoutParsableAgeShowsTsOnly() {
        assertEquals(
            "Данные: 2026-10-07T11:32:05Z",
            ProjectsSummary.freshnessText("2026-10-07T11:32:05Z", null, "12:00:00"),
        )
    }

    @Test fun freshnessTextWithoutTsFallsBackToRequestClock() {
        assertEquals(
            "Обновлено в 12:00:05",
            ProjectsSummary.freshnessText("", null, "12:00:05"),
        )
    }

    @Test fun statusLineWithServicesCarriesFreshness() {
        val line = ProjectsSummary.statusLine(
            ProjectsSummary.Summary(3, 4),
            "Данные: 14.10.2026 в 16:30:28 (МСК) · 2 мин назад",
        )
        assertEquals(
            "Сервисы: 3 из 4 онлайн · Данные: 14.10.2026 в 16:30:28 (МСК) · 2 мин назад",
            line,
        )
    }

    @Test fun statusLineWithoutServicesShowsFreshnessOnly() {
        assertEquals(
            "Обновлено в 08:30:00",
            ProjectsSummary.statusLine(ProjectsSummary.Summary(0, 0), "Обновлено в 08:30:00"),
        )
    }

    // --- Строки карточек: только реально присутствующие поля (P0-4, дефект 2) --------------

    @Test fun serviceDetailsUsesRealServerFields() {
        assertEquals(
            "Аптайм: 3 д 16 ч 54 мин · Рестарты: 0 · HTTP: 200",
            ProjectsSummary.serviceDetails(service(true, "3 д 16 ч 54 мин", "0", "200")),
        )
    }

    @Test fun serviceDetailsSkipsAbsentFields() {
        assertEquals("Аптайм: 2 д", ProjectsSummary.serviceDetails(service(true, uptime = "2 д")))
        assertEquals("HTTP: 503", ProjectsSummary.serviceDetails(service(false, http = "503")))
        assertEquals("", ProjectsSummary.serviceDetails(service(true)))
    }

    @Test fun serviceDetailsNeverMentionsDeadPing() {
        val details = ProjectsSummary.serviceDetails(service(true, "2 д", "1", "200"))
        assertFalse("мёртвая ветка «Пинг» запрещена: $details", details.contains("Пинг"))
        assertFalse(details.contains("latency"))
    }

    @Test fun hostLinesSkipMissingValues() {
        val lines = ProjectsSummary.hostLines(
            host(
                ram = "4.0 ГБ из 15.6 ГБ (25%)",
                cpu = "1.27 (1 мин), 0.66 (5 мин)",
                disk = "Занято 37G из 50G (78%), свободно 11G",
            )
        )
        assertEquals(
            "RAM: 4.0 ГБ из 15.6 ГБ (25%)\n" +
                "Нагрузка: 1.27 (1 мин), 0.66 (5 мин)\n" +
                "Диск: Занято 37G из 50G (78%), свободно 11G",
            lines,
        )
        assertFalse("отсутствующий аптайм не выводится заглушкой", lines.contains("Аптайм"))
        assertFalse("заглушки «—» не попадают в карточку", lines.contains(ProjectsMetricsParser.MISSING))
    }

    @Test fun hostLinesAllMissingGivesEmptyString() {
        assertEquals("", ProjectsSummary.hostLines(host()))
    }

    @Test fun offlineLineMentionsStaleSavedList() {
        assertEquals(
            "Метрики недоступны — показан сохранённый список · проектов: 4",
            ProjectsSummary.offlineLine(4),
        )
    }
}
