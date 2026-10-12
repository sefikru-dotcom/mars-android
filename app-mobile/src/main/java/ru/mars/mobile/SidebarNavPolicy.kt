package ru.mars.mobile

/**
 * Целевые экраны бокового меню (паритет с WebUI). Идентификаторы экранов,
 * а не ссылки на Activity: Policy остаётся чистой и тестируемой на JVM,
 * сопоставление NavTarget -> Activity живёт в UI-слое (ChatActivity).
 */
enum class NavTarget {
    TODOS,
    CRON,
    PROJECTS,
    MUSIC,
    MEMORY,
    SKILLS,
    SETTINGS,
}

/**
 * Один раздел бокового меню.
 *
 * [iconKey] — строковый ключ drawable (например, "ic_nav_todos"). Резолвинг
 * ключа в R.drawable выполняется в UI-слое через
 * [SidebarNavPolicy.resolveIconRes] / ресурсы Context, чтобы Policy не
 * зависела от сгенерированного R и собиралась на JVM без Android.
 * Никаких Class<Activity> и Intent здесь не хранится.
 */
data class SidebarSection(
    val id: String,
    val label: String,
    val iconKey: String,
    val target: NavTarget,
)

/**
 * Декларативный состав бокового меню (заменяет ручную сетку кнопок в
 * ChatActivity): ровно 7 разделов в порядке WebUI-паритета —
 * Задачи, Планировщик, Проекты, Музыка, Память, Скиллы, Настройки.
 * Порядок [sections] стабилен и является контрактным.
 */
object SidebarNavPolicy {

    val sections: List<SidebarSection> = listOf(
        SidebarSection(id = "todos", label = "Задачи", iconKey = "ic_nav_todos", target = NavTarget.TODOS),
        SidebarSection(id = "cron", label = "Планировщик", iconKey = "ic_nav_cron", target = NavTarget.CRON),
        SidebarSection(id = "projects", label = "Проекты", iconKey = "ic_nav_projects", target = NavTarget.PROJECTS),
        SidebarSection(id = "music", label = "Музыка", iconKey = "ic_nav_music", target = NavTarget.MUSIC),
        SidebarSection(id = "memory", label = "Память", iconKey = "ic_nav_memory", target = NavTarget.MEMORY),
        SidebarSection(id = "skills", label = "Скиллы", iconKey = "ic_nav_skills", target = NavTarget.SKILLS),
        SidebarSection(id = "settings", label = "Настройки", iconKey = "ic_nav_settings", target = NavTarget.SETTINGS),
    )

    /** Раздел по id (например, для восстановления выделения в UI). */
    fun sectionById(id: String): SidebarSection? = sections.firstOrNull { it.id == id }

    /** Раздел по целевому экрану. */
    fun sectionByTarget(target: NavTarget): SidebarSection? = sections.firstOrNull { it.target == target }

    /**
     * Резолвинг [SidebarSection.iconKey] в id ресурса в UI-слое.
     * [resourceIdLookup] — обычно `context.resources.getIdentifier(name, "drawable", packageName)`
     * либо прямой when по ключам на R.drawable. Возвращает null, если ключ неизвестен.
     */
    fun resolveIconRes(iconKey: String, resourceIdLookup: (String) -> Int): Int? {
        val res = resourceIdLookup(iconKey)
        return if (res != 0) res else null
    }
}
