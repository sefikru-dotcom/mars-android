package ru.mars.mobile

/**
 * Локализация совпадения поиска внутри ОТОБРАЖЁННЫХ строк пузыря (волна C,
 * фича C) — чистая JVM-политика, без Android.
 *
 * Зачем: [ru.mars.network.repository.MessageSearchPolicy] адресует совпадение
 * парой (messageId, ordinalInMessage) по ИСХОДНОМУ тексту сообщения, а скроллить
 * надо к позиции совпадения внутри пузыря — к конкретной строке рендера. Пузырь
 * собран из отдельных TextView (markdown-строки); политика находит, в какой
 * строке рендера лежит ordinal-е вхождение запроса, и возвращает полуинтервал
 * символов в ней. Хост по этим данным берёт TextView, строку её Layout и
 * пиксельную высоту — скролл идёт к самому совпадению, а не к верхушке карточки.
 *
 * Семантика поиска совпадает с MessageSearchPolicy: регистронезависимо,
 * вхождения не перекрываются, порядок строк = порядок рендера. Ordinal вне
 * диапазона (рендер потерял вхождение, например внутри свёрнутого блока) —
 * null: хост откатывается к скроллу на карточку, совпадение не «съедается».
 */
object MatchLocationPolicy {

    /** Строка рендера и полуинтервал [start, end) совпадения в ней. */
    data class Location(val lineIndex: Int, val start: Int, val end: Int)

    /**
     * Находит [ordinal]-е (0-based) вхождение [needle] в [lines].
     * Пустой запрос/отрицательный ordinal/отсутствие вхождения — null.
     */
    fun locate(lines: List<String>, needle: String, ordinal: Int): Location? {
        val n = needle.trim().lowercase()
        if (n.isEmpty() || ordinal < 0) return null
        var seen = 0
        lines.forEachIndexed { lineIndex, raw ->
            val haystack = raw.lowercase()
            var from = 0
            while (from <= haystack.length - n.length) {
                val idx = haystack.indexOf(n, from)
                if (idx < 0) break
                if (seen == ordinal) return Location(lineIndex, idx, idx + n.length)
                seen++
                from = idx + n.length
            }
        }
        return null
    }
}
