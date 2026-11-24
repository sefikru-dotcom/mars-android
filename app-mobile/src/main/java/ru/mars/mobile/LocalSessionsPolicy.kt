package ru.mars.mobile

import java.util.Locale

/**
 * Политика поиска и заголовков локальных диалогов — чистые функции, ни одного
 * обращения к Android и к файлам. Поэтому проверяется на JVM строками
 * (`LocalSessionsPolicyTest`), а хранилище ([LocalSessionStore]) остаётся тонким
 * слоем ввода-вывода.
 *
 * Регистронезависимость — через `lowercase(Locale.ROOT)`, а не `equalsIgnoreCase`:
 * турецкая локаль превращает `I` в `ı`, и поиск «ИИ» перестал бы находить «ии» на
 * части устройств. `Locale.ROOT` одинаков на любом телефоне.
 */
object LocalSessionsPolicy {

    /**
     * Потолок заголовка: переименование приходит из поля ввода, а заголовок рисуется
     * одной строкой в списке. 200 символов — с большим запасом и без риска раздуть файл.
     */
    const val MAX_TITLE_LENGTH = 200

    private val WHITESPACE = Regex("\\s+")

    /**
     * Запрос к виду, в котором его сравнивают: обрезан, в нижнем регистре.
     * `null` — запрос пустой, то есть фильтра нет и показывать надо всё
     * (а не «ничего не найдено»).
     */
    fun normalize(query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /**
     * Вхождение [needle] в [haystack] без учёта регистра.
     * [needle] должен быть уже нормализован ([normalize]); пустой не совпадает ни с чем,
     * чтобы случайный `""` не подсвечивал весь список.
     */
    fun contains(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return false
        return haystack.lowercase(Locale.ROOT).contains(needle)
    }

    /**
     * Совпадение диалога с запросом: по заголовку **или** по тексту любого сообщения.
     * Пустой запрос — совпадение (фильтра нет).
     */
    fun matches(normalizedQuery: String, title: String, texts: List<String>): Boolean {
        if (normalizedQuery.isEmpty()) return true
        if (contains(normalizedQuery, title)) return true
        return texts.any { text -> contains(normalizedQuery, text) }
    }

    /** То же, но с нормализацией запроса внутри — для экрана, где запрос сырой. */
    fun matchesQuery(query: String, title: String, texts: List<String>): Boolean {
        val needle = normalize(query) ?: return true
        return matches(needle, title, texts)
    }

    /**
     * Заголовок к сохранению: обрезанные края, внутренние переводы строк и пачки
     * пробелов сжаты в один пробел (заголовок — одна строка), пустой — [fallback],
     * длиннее [MAX_TITLE_LENGTH] — обрезан по границе.
     */
    fun cleanTitle(raw: String, fallback: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return fallback
        if (collapsed.length <= MAX_TITLE_LENGTH) return collapsed
        return collapsed.take(MAX_TITLE_LENGTH).trim()
    }
}
