package ru.mars.mobile

/**
 * Чистая политика живых подсказок поиска (без Android-импортов, JVM-тестируемая).
 *
 * Подсказки — вспомогательный UX поверх поля поиска: они не должны спамить сервер
 * на каждый символ (дебаунс [DEBOUNCE_MS], запрос только с [MIN_QUERY_LENGTH] символов —
 * короче сервер и так отвечает пустым массивом, server.py handle_suggestions) и не
 * должны показывать мусор (пробелы, дубли, строка в строку равная уже введённой).
 */
object SearchSuggestionsPolicy {

    /** Сервер не отдаёт подсказки на запрос короче двух символов — клиент не спрашивает. */
    const val MIN_QUERY_LENGTH = 2

    /** Пауза ввода перед запросом к серверу: серия быстрых keystrokes схлопывается в один. */
    const val DEBOUNCE_MS = 350L

    /** Потолок показа; сервер отдаёт не больше 8 (handle_suggestions), клиент дублирует предел. */
    const val MAX_VISIBLE = 8

    /** Запрос для сервера/сравнений: trimmed, null для пустого ввода. */
    fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** Запрашивать ли подсказки для текущего ввода. */
    fun shouldFetch(raw: String?): Boolean =
        (normalize(raw)?.length ?: 0) >= MIN_QUERY_LENGTH

    /**
     * Панель подсказок видна, только когда поле в фокусе, ввод не короче
     * [MIN_QUERY_LENGTH] и список не пуст. История поиска ([SearchHistoryPanelPolicy])
     * показывается на пустом поле — две панели не видны одновременно.
     */
    fun isPanelVisible(fieldText: String?, fieldFocused: Boolean, suggestionsEmpty: Boolean): Boolean =
        fieldFocused && !suggestionsEmpty && shouldFetch(fieldText)

    /**
     * Список для показа: trim, без пробельных строк, без дублей (регистронезависимо,
     * первая побеждает — порядок сервера сохраняется), без строки, равной уже введённой
     * (подсказывать то, что и так набрано, бессмысленно), с потолком [MAX_VISIBLE].
     */
    fun visibleSuggestions(raw: List<String>, fieldText: String?): List<String> {
        val typed = normalize(fieldText)
        val out = ArrayList<String>(raw.size.coerceAtMost(MAX_VISIBLE))
        for (item in raw) {
            val s = item.trim()
            if (s.isEmpty()) continue
            if (typed != null && s.equals(typed, ignoreCase = true)) continue
            if (out.any { it.equals(s, ignoreCase = true) }) continue
            out.add(s)
            if (out.size == MAX_VISIBLE) break
        }
        return out
    }
}
