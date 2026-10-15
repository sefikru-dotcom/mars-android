package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- A12: сводки новых секций полного дашборда -------------------------------------------------

    private fun entry(key: String, state: String) =
        ParsedStateEntry(key, state, state.lowercase() in setOf("active", "ok", "online"))

    private fun tunnel(online: Boolean) =
        ParsedTunnel(key = "k", name = "n", description = "d", port = "2222", online = online)

    private fun backup(time: String, size: String = "2.9M", profiles: String = "35", status: String = "Успешно") =
        ParsedBackup(time = time, archive = "a.tar.gz", size = size, profiles = profiles, status = status)

    @Test fun vpnLineCountsActiveComponents() {
        assertEquals(
            "VPN: 4 из 5 компонентов активны",
            ProjectsSummary.vpnLine(
                listOf(
                    entry("xray", "active"), entry("caddy", "active"), entry("hysteria", "active"),
                    entry("sub", "active"), entry("http", "404"),
                )
            ),
        )
        assertNull(ProjectsSummary.vpnLine(emptyList()))
    }

    @Test fun vpnOnlineFollowsEtalonXrayAndCaddyOnly() {
        // Эталон /dashboard/: vpnOk = xray==='active' && caddy==='active';
        // http-код эндпоинта подписок («404») на вердикт не влияет.
        assertTrue(
            ProjectsSummary.vpnOnline(listOf(entry("xray", "active"), entry("caddy", "active"), entry("http", "404")))
        )
        assertFalse(ProjectsSummary.vpnOnline(listOf(entry("xray", "active"), entry("caddy", "failed"))))
        assertFalse(ProjectsSummary.vpnOnline(listOf(entry("xray", "active"))))
        assertFalse(ProjectsSummary.vpnOnline(emptyList()))
    }

    @Test fun stateLinesOnePerEntryInGivenOrder() {
        assertEquals(
            "caddy: active\nxray: active",
            ProjectsSummary.stateLines(listOf(entry("caddy", "active"), entry("xray", "active"))),
        )
        assertEquals("", ProjectsSummary.stateLines(emptyList()))
    }

    @Test fun tunnelsLineCountsOnline() {
        assertEquals(
            "Туннели: 2 из 3 онлайн",
            ProjectsSummary.tunnelsLine(listOf(tunnel(true), tunnel(true), tunnel(false))),
        )
        assertNull(ProjectsSummary.tunnelsLine(emptyList()))
    }

    @Test fun tunnelDetailsOnlyRealFields() {
        assertEquals("d · Порт: 2222", ProjectsSummary.tunnelDetails(tunnel(true)))
        assertEquals("", ProjectsSummary.tunnelDetails(ParsedTunnel("k", "n", "", "", true)))
        assertEquals("Порт: 2223", ProjectsSummary.tunnelDetails(ParsedTunnel("k", "n", "", "2223", false)))
    }

    @Test fun alertsLineNullWhenEmpty() {
        assertEquals("Алерты: 2", ProjectsSummary.alertsLine(listOf("a", "b")))
        assertNull("пустая секция алертов не выводится", ProjectsSummary.alertsLine(emptyList()))
    }

    @Test fun radarLineOnlyWhenServerSentCount() {
        assertEquals("Прайс-радар: 287 позиций", ProjectsSummary.radarLine(287))
        assertNull(ProjectsSummary.radarLine(null))
    }

    @Test fun backupLinesNewestFirstCappedWithHonestTail() {
        val backups = (10..15).map { backup("2026-10-$it 01:30:01", size = "$it.0M") }
        val lines = ProjectsSummary.backupLines(backups, limit = 4).split("\n")
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("2026-10-15 01:30:01"))
        assertTrue(lines[0].contains("архив: 15.0M"))
        assertTrue(lines[0].contains("профилей: 35"))
        assertTrue(lines[0].endsWith("Успешно"))
        assertTrue(lines[3].startsWith("2026-10-12"))
        assertEquals("…ещё 2 ранних записей", lines[4])
    }

    @Test fun backupLinesSkipAbsentFields() {
        assertEquals("t · OK", ProjectsSummary.backupLines(listOf(ParsedBackup("t", "", "", "", "OK"))))
        assertEquals("", ProjectsSummary.backupLines(emptyList()))
    }

    @Test fun restartLinesCollapseLiteralDuplicates() {
        val log = List(15) {
            ParsedRestart("2026-09-29T07:42:54+03:00", "kvartal restart triggered by root pid 234739")
        }
        assertEquals(
            "2026-09-29 07:42:54 — kvartal restart triggered by root pid 234739 · x15",
            ProjectsSummary.restartLines(log),
        )
    }

    @Test fun restartLinesNewestFirstCappedWithHonestTail() {
        val log = (1..12).map {
            ParsedRestart("2026-10-${"%02d".format(it)}T01:00:00+03:00", "svc restart $it")
        }
        val lines = ProjectsSummary.restartLines(log, limit = 10).split("\n")
        assertEquals(11, lines.size)
        assertEquals("2026-10-12 01:00:00 — svc restart 12", lines[0])
        assertEquals("…ещё 2 ранних событий", lines[10])
    }

    @Test fun restartLineWithoutTimeShowsTextOnly() {
        assertEquals("boom", ProjectsSummary.restartLines(listOf(ParsedRestart("", "boom"))))
        assertEquals("", ProjectsSummary.restartLines(emptyList()))
    }

    @Test fun formatRestartTimeStripsIsoTimezone() {
        assertEquals("2026-09-29 07:42:54", ProjectsSummary.formatRestartTime("2026-09-29T07:42:54+03:00"))
        assertEquals("2026-09-29 07:42:54", ProjectsSummary.formatRestartTime("2026-09-29T07:42:54"))
        assertEquals("", ProjectsSummary.formatRestartTime(""))
    }
}
