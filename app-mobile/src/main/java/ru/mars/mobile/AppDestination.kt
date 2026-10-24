package ru.mars.mobile

/**
 * Легаси-идентификаторы целевых экранов старого бокового меню (паритет с
 * WebUI). Единственная живая модель навигации — [AppDestination]/
 * [AppSectionsPolicy]; этот enum сохранён только как цель миграционного
 * соответствия [AppDestination.toLegacyNavTarget] (экраны старого меню,
 * которым сопоставлен раздел бара). Значение «Логи» удалено: логи живут внутри
 * экрана мониторинга, цель [INSIGHTS]. «Пространства» вернулись в бар вместе с
 * экраном [SpacesActivity], поэтому легаси-цель [SPACES] снова нужна — иначе
 * биекция «раздел бара ↔ экран старого меню» перестала бы быть полной.
 */
enum class NavTarget {
    TODOS,
    CRON,
    PROJECTS,
    MUSIC,
    MEMORY,
    SKILLS,
    KANBAN,
    INSIGHTS,
    SPACES,
    SETTINGS,
}

/**
 * Приложенческая навигация левого бара: разделы уровня приложения, а не диалоги.
 *
 * Единственная модель навигации (legacy-политика бокового меню удалена):
 * порядок и состав здесь — продуктовый контракт левого бара (11 разделов,
 * «Чат» первым, «Настройки» всегда последние). «Логи» разделом бара не
 * являются: они живут внутри экрана мониторинга ([ANALYTICS]). Список диалогов
 * разделом НЕ является — он живёт уровнем внутри [AppDestination.CHAT] (см.
 * [AppNavState], [ChatLevel]).
 *
 * «Пространства» ([SPACES]) — снова раздел бара: экран вернулся в mobile-клиент
 * (решение владельца, паритет с TV), и вход в него один — строка бара, а не
 * кнопка внутри чата. Раздел стоит непосредственно перед «Настройками», потому
 * что это настройка рабочего контекста, а не повседневный экран.
 *
 * Идентификаторы — строковые ключи, никаких Activity/Intent: модель чистая и
 * тестируется на JVM, сопоставление экранам выполняет UI-слой
 * ([AppNavIntents]).
 */
enum class AppDestination(val key: String, val title: String) {
    CHAT("chat", "Чат"),
    TASKS("tasks", "Задачи"),
    KANBAN("kanban", "Канбан"),
    SCHEDULER("scheduler", "Планировщик"),
    PROJECTS("projects", "Проекты"),
    MUSIC("music", "Музыка"),
    MEMORY("memory", "Память"),
    SKILLS("skills", "Скиллы"),
    ANALYTICS("analytics", "Аналитика"),
    SPACES("spaces", "Пространства"),
    SETTINGS("settings", "Настройки");

    /**
     * Соответствие легаси-целям [NavTarget] старого бокового меню для
     * миграции без рассинхрона экранов. [CHAT] не имеет легаси-цели: чат —
     * базовый экран текущего стека, а не раздел старого меню.
     */
    fun toLegacyNavTarget(): NavTarget? = when (this) {
        CHAT -> null
        TASKS -> NavTarget.TODOS
        KANBAN -> NavTarget.KANBAN
        SCHEDULER -> NavTarget.CRON
        PROJECTS -> NavTarget.PROJECTS
        MUSIC -> NavTarget.MUSIC
        MEMORY -> NavTarget.MEMORY
        SKILLS -> NavTarget.SKILLS
        ANALYTICS -> NavTarget.INSIGHTS
        SPACES -> NavTarget.SPACES
        SETTINGS -> NavTarget.SETTINGS
    }

    companion object {
        /** Раздел по deep-link-ключу; null для неизвестного ключа. */
        fun fromKey(key: String): AppDestination? = entries.firstOrNull { it.key == key }
    }
}
