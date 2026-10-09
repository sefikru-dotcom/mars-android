package ru.mars.mobile

import ru.mars.network.model.SkillItem

/**
 * Чистая фильтрация каталога скиллов для экрана «Скиллы Марса» (без Android,
 * тестируется на JVM).
 *
 * Правила:
 * - поиск регистронезависимый по подстроке в имени ИЛИ описании; пробельный
 *   запрос не фильтрует ничего;
 * - категория [ALL] (или пустая) не фильтрует; иначе сравнение регистронезависимое;
 * - чипсы категорий: «Все» + непустые категории без дублей, по алфавиту;
 * - порядок списка стабилен: категория, затем имя (обе регистронезависимо).
 */
object SkillsFilterPolicy {

    /** Идентификатор чипса «Все» — не путать с отображаемой подписью. */
    const val ALL = "all"

    /** Чипсы категорий: [ALL] первым, затем уникальные непустые категории по алфавиту. */
    fun categories(skills: List<SkillItem>): List<String> =
        listOf(ALL) + skills.asSequence()
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .toList()

    /** Подпись чипса: «Все» для [ALL], иначе категория с заглавной буквы. */
    fun categoryLabel(raw: String): String =
        if (raw == ALL) "Все" else raw.replaceFirstChar(Char::uppercaseChar)

    /** Подходит ли скилл под поисковый запрос (имя или описание, без учёта регистра). */
    fun matches(skill: SkillItem, query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return true
        return normalized in skill.name.lowercase() || normalized in skill.description.lowercase()
    }

    /** Подходит ли скилл под выбранную категорию (ALL/пустая — подходят все). */
    fun inCategory(skill: SkillItem, category: String): Boolean {
        val normalized = category.trim()
        if (normalized.isEmpty() || normalized == ALL) return true
        return skill.category.equals(normalized, ignoreCase = true)
    }

    /** Комбинированный фильтр: категория И запрос. Порядок входа сохраняется. */
    fun filter(skills: List<SkillItem>, query: String, category: String): List<SkillItem> =
        skills.filter { inCategory(it, category) && matches(it, query) }

    /** Стабильная сортировка карточек: категория, затем имя. */
    fun sorted(skills: List<SkillItem>): List<SkillItem> =
        skills.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
}
