package ru.mars.mobile

/**
 * Одно совпадение поискового запроса в транскрипте: индекс сообщения в списке
 * экрана и полуинтервал [start, end) символов внутри текста сообщения.
 */
data class TranscriptMatch(val messageIndex: Int, val start: Int, val end: Int)

/**
 * Чистая политика поиска по текущему диалогу: регистронезависимый поиск всех
 * вхождений запроса по текстам сообщений, кольцевая навигация «следующее /
 * предыдущее» и подпись счётчика для поисковой панели.
 * Без Android-импортов — тестируется на JVM.
 */
object TranscriptSearchPolicy {
    /** Запросы короче этого числа символов не ищем: слишком много шума. */
    const val MIN_QUERY_LENGTH = 2

    /** Жёсткий потолок совпадений: защита от патологических транскриптов. */
    const val MAX_MATCHES = 500

    /** Подпись счётчика, когда запрос валиден, но совпадений нет. */
    const val NO_MATCHES_LABEL = "Совпадений нет"

    /** Искать ли по такому запросу (после trim, без учёта регистра). */
    fun shouldSearch(query: String): Boolean = query.trim().length >= MIN_QUERY_LENGTH

    /**
     * Все вхождения [query] в [texts] (регистронезависимо, перекрывающиеся
     * вхождения не считаем), упорядоченные по сообщениям и позициям;
     * невалидный/короткий запрос -> пустой список; не более [MAX_MATCHES].
     */
    fun findMatches(texts: List<String>, query: String): List<TranscriptMatch> {
        if (!shouldSearch(query)) return emptyList()
        val needle = query.trim().lowercase()
        val matches = mutableListOf<TranscriptMatch>()
        texts.forEachIndexed { messageIndex, text ->
            val haystack = text.lowercase()
            var from = 0
            while (from <= haystack.length - needle.length) {
                val idx = haystack.indexOf(needle, from)
                if (idx < 0) break
                matches += TranscriptMatch(messageIndex, idx, idx + needle.length)
                if (matches.size >= MAX_MATCHES) return matches
                from = idx + needle.length
            }
        }
        return matches
    }

    /**
     * Кольцевая навигация: следующая/предыдущая позиция совпадения.
     * Пустой список -> -1; неинициализированная текущая (<0) -> 0.
     */
    fun navigate(total: Int, current: Int, forward: Boolean): Int {
        if (total <= 0) return -1
        if (current < 0 || current >= total) return 0
        return if (forward) (current + 1) % total else (current - 1 + total) % total
    }

    /** Подпись счётчика: «3 из 12»; пустой запрос -> «», валидный без совпадений -> [NO_MATCHES_LABEL]. */
    fun counterLabel(query: String, current: Int, total: Int): String = when {
        !shouldSearch(query) -> ""
        total <= 0 -> NO_MATCHES_LABEL
        else -> "${current + 1} из $total"
    }
}
