package ru.mars.mobile

/**
 * Чистая проекция левого бара для UI-слоя: состав, порядок и иконочные ключи
 * разделов [AppDestination].
 *
 * Контракт волны 2: бар содержит ТОЛЬКО приложенческие разделы — список
 * диалогов разделом не является (это уровень внутри [AppDestination.CHAT],
 * см. [ChatLevel.SessionList]). «Чат» первым, «Настройки» последними; «Логи»
 * разделом не являются (живут внутри «Аналитики»), logout доступен только в
 * «Настройках» ([AppNavPolicy.LOGOUT_HOST]). «Пространства» — раздел бара: экран
 * вернулся в mobile как самостоятельный экран рабочего контекста.
 *
 * Резолвинг `iconKey → R.drawable` и сопоставление разделов Intent выполняет
 * UI-слой ([AppSectionsBar], [AppNavIntents]); здесь — только строковые ключи,
 * поэтому политика тестируется на JVM.
 */
object AppSectionsPolicy {

    /** Разделы бара в контрактном порядке (стабильный список [AppDestination]). */
    val sections: List<AppDestination> = AppDestination.entries

    /** Строковый ключ drawable для раздела (резолвинг — в UI-слое). */
    fun iconKeyFor(destination: AppDestination): String = when (destination) {
        AppDestination.CHAT -> "ic_nav_chat"
        AppDestination.TASKS -> "ic_nav_todos"
        AppDestination.KANBAN -> "ic_nav_kanban"
        AppDestination.SCHEDULER -> "ic_nav_cron"
        AppDestination.PROJECTS -> "ic_nav_projects"
        AppDestination.MUSIC -> "ic_nav_music"
        AppDestination.MEMORY -> "ic_nav_memory"
        AppDestination.SKILLS -> "ic_nav_skills"
        AppDestination.ANALYTICS -> "ic_nav_insights"
        AppDestination.SPACES -> "ic_nav_spaces"
        AppDestination.SETTINGS -> "ic_nav_settings"
    }

    /** Единственный раздел бара, хостящий logout (продуктовое решение волны 2). */
    val logoutSection: AppDestination = AppNavPolicy.LOGOUT_HOST
}
