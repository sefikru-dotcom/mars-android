package ru.mars.mobile

/**
 * Сводка и тексты экрана проектов: статус-строка под шапкой и строки карточек.
 *
 * Чистая логика (без Android): считает онлайн-сервисы из распарсенных метрик и
 * формирует русский текст. [ProjectsActivity] только выводит результат и красит
 * его в цвет темы.
 *
 * P0-4: свежесть данных — всегда из «ts» ОТВЕТА (момент снятия метрик), а не из
 * времени запроса; строки карточек строятся только из реально присутствующих полей.
 *
 * A12: сводки новых секций полного дашборда (VPN, туннели, службы хоста, алерты,
 * бэкапы, журнал рестартов) — в том же стиле: пустая секция → пустая строка/null,
 * экран её просто не рисует.
 */
object ProjectsSummary {
    data class Summary(val online: Int, val total: Int) {
        /** Есть хотя бы один недоступный сервис — строка подсвечивается предупреждением. */
        val hasOffline: Boolean get() = total > online
    }

    fun of(services: List<ParsedService>): Summary =
        Summary(online = services.count { it.active }, total = services.size)

    /**
     * Текст свежести для статус-строки: «Данные: 14.10.2026 в 16:30:28 (МСК) · 2 мин назад».
     * [ageText] добавляется, только если ts удалось разобрать ([MetricsTimestamp]);
     * при пустом ts (легаси/битый ответ) — честный фолбэк «Обновлено в [clock]»
     * (время запроса: данные только что получены).
     */
    fun freshnessText(dataTs: String, ageText: String?, clock: String): String = when {
        dataTs.isBlank() -> "Обновлено в $clock"
        ageText != null -> "Данные: $dataTs · $ageText"
        else -> "Данные: $dataTs"
    }

    /** «Сервисы: 3 из 4 онлайн · Данные: …»; без сервисов — только свежесть. */
    fun statusLine(summary: Summary, freshness: String): String =
        if (summary.total == 0) freshness
        else "Сервисы: ${summary.online} из ${summary.total} онлайн · $freshness"

    /**
     * Метрики не загрузились: явно говорим, что список сохранённый (статусы могут
     * быть устаревшими), вместо прежнего двусмысленного «Офлайн-режим».
     */
    fun offlineLine(projectCount: Int): String =
        "Метрики недоступны — показан сохранённый список · проектов: $projectCount"

    /**
     * Строки карточки хоста: только реально отданные сервером поля — заглушки «—»
     * и пустые значения не выводятся (мёртвых строк на карточке нет).
     */
    fun hostLines(h: ParsedHost): String = listOfNotNull(
        present(h.ram)?.let { "RAM: $it" },
        present(h.cpu)?.let { "Нагрузка: $it" },
        present(h.disk)?.let { "Диск: $it" },
        present(h.uptime)?.let { "Аптайм: $it" },
    ).joinToString("\n")

    /**
     * Детали карточки сервиса: «Аптайм: 3 д 16 ч 54 мин · Рестарты: 0 · HTTP: 200» —
     * только из реально присутствующих полей ответа. Прежней строки «Пинг: N ms»
     * здесь нет: сервер latency не отдаёт никогда, ветка была мертва.
     */
    fun serviceDetails(s: ParsedService): String = listOfNotNull(
        present(s.uptime)?.let { "Аптайм: $it" },
        present(s.restarts)?.let { "Рестарты: $it" },
        present(s.http)?.let { "HTTP: $it" },
    ).joinToString(" · ")

    // --- A12: сводки новых секций полного дашборда -----------------------------------------

    /**
     * VPN-узел «в сети» — по эталонной странице /dashboard/: xray И caddy в состоянии
     * «active». Остальные компоненты (sub/http-проверка) на вердикт не влияют:
     * http-код эндпоинта подписок — diagnostic, а не признак жизни туннеля.
     */
    fun vpnOnline(states: List<ParsedStateEntry>): Boolean {
        val byKey = states.associate { it.key to it.active }
        return byKey["xray"] == true && byKey["caddy"] == true
    }

    /** «VPN: 4 из 5 компонентов активны»; null — состояний нет (секция не выводится). */
    fun vpnLine(states: List<ParsedStateEntry>): String? =
        if (states.isEmpty()) null
        else "VPN: ${states.count { it.active }} из ${states.size} компонентов активны"

    /** «Туннели: 2 из 3 онлайн»; null — туннелей нет (секция не выводится). */
    fun tunnelsLine(tunnels: List<ParsedTunnel>): String? =
        if (tunnels.isEmpty()) null
        else "Туннели: ${tunnels.count { it.online }} из ${tunnels.size} онлайн"

    /** «Алерты: N»; null — алертов нет (пустая секция не выводится). */
    fun alertsLine(alerts: List<String>): String? =
        if (alerts.isEmpty()) null else "Алерты: ${alerts.size}"

    /** Построчно «ключ: состояние» для плоских секций состояний (VPN, службы хоста). */
    fun stateLines(entries: List<ParsedStateEntry>): String =
        entries.joinToString("\n") { "${it.key}: ${it.state}" }

    /** «Прайс-радар: 287 позиций»; null — сервер не отдал radar_products. */
    fun radarLine(count: Int?): String? = count?.let { "Прайс-радар: $it позиций" }

    /** Строка карточки туннеля: описание и порт — только реально отданные поля. */
    fun tunnelDetails(t: ParsedTunnel): String = listOfNotNull(
        present(t.description),
        present(t.port)?.let { "Порт: $it" },
    ).joinToString(" · ")

    /**
     * Строки бэкапов: свежие сверху, не больше [limit] (эталон /dashboard/ показывает
     * 4 последних); при усечении — честный хвост «…ещё N ранних записей».
     * В строке только реально отданные поля: «время · архив: размер · профилей: N · статус».
     */
    fun backupLines(backups: List<ParsedBackup>, limit: Int = 4): String {
        if (backups.isEmpty()) return ""
        val shown = backups.takeLast(limit).reversed()
        val lines = shown.map { b ->
            listOfNotNull(
                present(b.time),
                present(b.size)?.let { "архив: $it" },
                present(b.profiles)?.let { "профилей: $it" },
                present(b.status),
            ).joinToString(" · ")
        }.toMutableList()
        val hidden = backups.size - shown.size
        if (hidden > 0) lines += "…ещё $hidden ранних записей"
        return lines.joinToString("\n")
    }

    /**
     * Строки журнала рестартов: сервер отдаёт буквальные дубли — схлопываем в «xN»,
     * свежие сверху, не больше [limit] записей; при усечении — честный хвост.
     */
    fun restartLines(log: List<ParsedRestart>, limit: Int = 10): String {
        if (log.isEmpty()) return ""
        val counts = LinkedHashMap<ParsedRestart, Int>()
        log.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val entries = counts.entries.toList()
        val shown = entries.takeLast(limit).reversed()
        val lines = shown.map { (restart, times) ->
            val line = listOf(formatRestartTime(restart.time), restart.text)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
            if (times > 1) "$line · x$times" else line
        }.toMutableList()
        val hidden = entries.size - shown.size
        if (hidden > 0) lines += "…ещё $hidden ранних событий"
        return lines.joinToString("\n")
    }

    /** «2026-09-29T07:42:54+03:00» → «2026-09-29 07:42:54» (без ISO-пояса). */
    fun formatRestartTime(time: String): String =
        time.replace('T', ' ').replace(TZ_SUFFIX, "")

    private val TZ_SUFFIX = Regex("[+-]\\d{2}:\\d{2}$")

    private fun present(value: String): String? =
        value.takeIf { it.isNotBlank() && it != ProjectsMetricsParser.MISSING }
}
