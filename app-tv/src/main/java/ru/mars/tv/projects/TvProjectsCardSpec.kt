package ru.mars.tv.projects

/**
 * Текстовые состояния экрана проектов (TV P1). Все лейблы выводятся ИЗ ДАННЫХ:
 * заглушка «Онлайн» запрещена — недоступность отображается явно.
 */
object TvProjectsCardSpec {

    /** Явный текст для отсутствующей секции (сервисы/хост без данных). */
    const val SERVICES_MISSING = "Данные недоступны"

    fun serviceStateLabel(active: Boolean, state: String): String =
        if (active) "Активен" else "Не активен"

    fun hostStatus(available: Boolean): String =
        if (available) "Данные получены" else "Данные недоступны"
}
