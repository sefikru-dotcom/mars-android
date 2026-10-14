package ru.mars.mobile

/**
 * Легаси-идентификаторы целевых экранов старого бокового меню (паритет с
 * WebUI). Единственная живая модель навигации — [AppDestination]/
 * [AppSectionsPolicy]; этот enum сохранён только как цель миграционного
 * соответствия [AppDestination.toLegacyNavTarget] (экраны старого меню,
 * которым сопоставлен раздел бара). Значения без потребителя удалены вместе
 * с legacy-политикой: «Пространства» (экран демонтирован) и «Логи»
 * (живут внутри экрана мониторинга, цель [INSIGHTS]).
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
    SETTINGS,
}

/**
 * Приложенческая навигация левого бара: разделы уровня приложения, а не диалоги.
 *
 * Единственная модель навигации (legacy-политика бокового меню удалена):
 * порядок и состав здесь — продуктовый контракт левого бара (10 разделов,
 * «Чат» первым, «Настройки» всегда последние). Дубли входов сокращены:
 * «Пространства» и «Логи» не являются разделами бара в этой модели. Список
 * диалогов разделом НЕ является — он живёт уровнем внутри
 * [AppDestination.CHAT] (см. [AppNavState], [ChatLevel]).
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
        SETTINGS -> NavTarget.SETTINGS
    }

    companion object {
        /** Раздел по deep-link-ключу; null для неизвестного ключа. */
        fun fromKey(key: String): AppDestination? = entries.firstOrNull { it.key == key }
    }
}
