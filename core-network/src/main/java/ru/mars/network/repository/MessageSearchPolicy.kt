package ru.mars.network.repository

/**
 * Реальное сообщение транскрипта для поиска: стабильный серверный id + текст.
 *
 * Адресация поиска НЕ может опираться на позицию/индекс карточки: группировка
 * «один ход = одна карточка» склеивает несколько сообщений в один элемент списка,
 * и индексная навигация «1/14 -> 2/14» каждый раз прыгает к первому сообщению
 * группы (регрессия ветки fix/5106-search-jump-fullidx).
 */
data class SearchableMessage(val messageId: String, val text: String)

/**
 * Одно совпадение поиска, адресованное парой (id реального сообщения, порядковый
 * номер совпадения ВНУТРИ его текста) — стабильный якорь, не зависящий от
 * группировки карточек, сворачивания мыслей и повторных рендеров списка.
 * [matchStart]/[matchEnd] — полуинтервал символов в тексте сообщения (подсветка),
 * [matchedText] — срез исходного текста с сохранением регистра.
 */
data class TextMatch(
    val messageId: String,
    val ordinalInMessage: Int,
    val matchStart: Int,
    val matchEnd: Int,
    val matchedText: String,
)

/**
 * Чистая политика поиска по транскрипту с адресацией по реальному сообщению (P0-3,
 * клиентская половина). Семантика поиска идентична app-mobile TranscriptSearchPolicy
 * (регистронезависимо, без перекрывающихся вхождений, тот же минимум запроса и
 * потолок совпадений, та же кольцевая навигация) — интеграция в UI является
 * перестановкой АДРЕСА совпадения, а не сменой поведения поиска.
 * Без Android-импортов — тестируется на JVM.
 */
object MessageSearchPolicy {
    /** Запросы короче этого числа символов не ищем: слишком много шума. */
    const val MIN_QUERY_LENGTH = 2

    /** Жёсткий потолок совпадений: защита от патологических транскриптов. */
    const val MAX_MATCHES = 500

    /** Подпись счётчика, когда запрос валиден, но совпадений нет. */
    const val NO_MATCHES_LABEL = "Совпадений нет"

    /** Искать ли по такому запросу (после trim, без учёта регистра). */
    fun shouldSearch(query: String): Boolean = query.trim().length >= MIN_QUERY_LENGTH

    /**
     * Все вхождения [query] в тексты [messages] (регистронезависимо, перекрывающиеся
     * вхождения не считаем), упорядоченные по сообщениям и позициям; невалидный/короткий
     * запрос -> пустой список; не более [MAX_MATCHES].
     */
    fun findMatches(messages: List<SearchableMessage>, query: String): List<TextMatch> {
        if (!shouldSearch(query)) return emptyList()
        val needle = query.trim().lowercase()
        val matches = mutableListOf<TextMatch>()
        for (message in messages) {
            val haystack = message.text.lowercase()
            var ordinal = 0
            var from = 0
            while (from <= haystack.length - needle.length) {
                val idx = haystack.indexOf(needle, from)
                if (idx < 0) break
                matches += TextMatch(
                    messageId = message.messageId,
                    ordinalInMessage = ordinal,
                    matchStart = idx,
                    matchEnd = idx + needle.length,
                    matchedText = message.text.substring(idx, idx + needle.length),
                )
                ordinal++
                if (matches.size >= MAX_MATCHES) return matches
                from = idx + needle.length
            }
        }
        return matches
    }

    /**
     * Стабильный якорь по текущей позиции поиска (0-based). Вне диапазона — null:
     * пустой результат и выход за границы — штатные ситуации, не ошибка.
     */
    fun anchorAt(matches: List<TextMatch>, position: Int): TextMatch? = matches.getOrNull(position)

    /**
     * Обратная операция: позиция якоря в списке совпадений. Якорь ищется по паре
     * (messageId, ordinalInMessage) — содержимое/регистр [TextMatch.matchedText]
     * в адрес не входят. Нет такого якоря — -1.
     */
    fun positionOf(matches: List<TextMatch>, anchor: TextMatch?): Int {
        if (anchor == null) return -1
        return matches.indexOfFirst {
            it.messageId == anchor.messageId && it.ordinalInMessage == anchor.ordinalInMessage
        }
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
