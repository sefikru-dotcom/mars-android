package ru.mars.mobile

/**
 * Сводка экрана проектов для статус-строки под шапкой.
 *
 * Чистая логика (без Android): считает онлайн-сервисы из распарсенных метрик
 * и формирует русский текст строки. [ProjectsActivity] только выводит результат
 * и красит его в цвет темы.
 */
object ProjectsSummary {
    data class Summary(val online: Int, val total: Int) {
        /** Есть хотя бы один недоступный сервис — строка подсвечивается предупреждением. */
        val hasOffline: Boolean get() = total > online
    }

    fun of(services: List<ParsedService>): Summary =
        Summary(online = services.count { it.active }, total = services.size)

    /** «Сервисы: 3 из 4 онлайн · Обновлено в 12:00:05»; без сервисов — только время. */
    fun statusLine(summary: Summary, clock: String): String =
        if (summary.total == 0) "Обновлено в $clock"
        else "Сервисы: ${summary.online} из ${summary.total} онлайн · Обновлено в $clock"

    /**
     * Метрики не загрузились: явно говорим, что список сохранённый (статусы могут
     * быть устаревшими), вместо прежнего двусмысленного «Офлайн-режим».
     */
    fun offlineLine(projectCount: Int): String =
        "Метрики недоступны — показан сохранённый список · проектов: $projectCount"
}
