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

    private fun present(value: String): String? =
        value.takeIf { it.isNotBlank() && it != ProjectsMetricsParser.MISSING }
}
