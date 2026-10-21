package ru.mars.mobile

/**
 * Режет поток дельт ответа на фразы, готовые к озвучке, по границам предложений.
 *
 * Зачем: озвучка не должна ждать весь ответ. Хост кормит [feed] каждую дельту стрима, а
 * готовые фразы сразу уходят в синтез — первая фраза начинает звучать, пока остальной
 * ответ ещё генерируется. Класс чистый JVM: ни Android, ни сети, ни потоков.
 *
 * Правила границ:
 * - конец предложения — `.`, `!`, `?`, `…` (серии `?!`, `...` считаются одной границей);
 * - перевод строки — тоже граница, но только для достаточно длинного куска: строка короче
 *   порога склеивается со следующей, иначе мягкий перенос рвал бы предложение посередине;
 * - НЕ граница: точка внутри версии/дроби (`1.7.6`, `3.14`), после сокращения
 *   (`т.е.`, `и т.д.`, `см.`, `г.`), после одиночной буквы-инициала (`А.`), а также точка,
 *   за которой сразу идёт буква или цифра без пробела;
 * - точка после числа в конце фразы (`порт 8090.`) — настоящая граница: там пробел/конец.
 *
 * Склейка и разрезание:
 * - фраза короче [minPhraseLength] не выпускается отдельно — она ждёт следующую границу и
 *   уходит вместе с ней (короткие обрывки звучат рвано и стоят лишнего запроса синтеза);
 * - первая фраза выпускается как можно раньше: на знаке конца предложения она уходит даже
 *   короткой, на переводе строки — от [firstPhraseMinLength] символов;
 * - кусок длиннее [maxPhraseLength] режется по запятой/точке с запятой/двоеточию, а если их
 *   нет — по последнему пробелу, чтобы синтез не получал абзац целиком.
 *
 * Что в озвучку не попадает: содержимое блоков кода ```, строки таблиц, горизонтальные
 * линейки, картинки, адреса ссылок, inline-код. Из markdown-разметки остаётся только
 * читаемый текст (заголовки, списки и цитаты теряют маркеры).
 *
 * Дельты режут текст где угодно, в том числе посреди строки, поэтому строка отдаётся в
 * накопитель посимвольно (фразы выходят внутри абзаца, не дожидаясь перевода строки), а
 * распознавание забора кода и таблиц идёт по началу строки.
 *
 * Экземпляр одноразовый на ход: [flush] в конце хода дочитывает остаток и сбрасывает
 * состояние, чтобы следующий ответ начался с чистого листа.
 */
class SentenceChunker(
    private val minPhraseLength: Int = DEFAULT_MIN_PHRASE_LENGTH,
    private val firstPhraseMinLength: Int = DEFAULT_FIRST_PHRASE_MIN_LENGTH,
    private val maxPhraseLength: Int = DEFAULT_MAX_PHRASE_LENGTH,
) {

    /** Накопленный текст (сырой, до markdown-чистки), из которого вырезаются фразы. */
    private val buffer = StringBuilder()

    /** Текущая строка, ещё не завершённая переводом строки (дельты режут где угодно). */
    private val pendingLine = StringBuilder()

    /** Внутри блока кода: строки до закрывающего ``` игнорируются целиком. */
    private var inCodeFence = false

    /** Текущая строка уже отдана в [buffer] посимвольно — повторно её не добавляем. */
    private var lineCommitted = false

    /** Первая фраза выпущена — дальше действует обычный порог длины. */
    private var firstEmitted = false

    /**
     * Принять очередную дельту ответа.
     *
     * @return фразы, готовые к синтезу прямо сейчас (обычно ноль или одна).
     */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        for (ch in delta) {
            if (ch == '\n') {
                finishLine()
            } else {
                pendingLine.append(ch)
            }
        }
        commitPartialLine()
        return drain()
    }

    /**
     * Конец хода: дочитать остаток (даже короткий) и сбросить состояние.
     *
     * @return последние фразы ответа; пусто, если читаемого текста не осталось.
     */
    fun flush(): List<String> {
        finishLine()
        val ready = ArrayList<String>(drain())
        val rest = stripMarkdown(buffer.toString().replace('\n', ' ')).trim()
        buffer.setLength(0)
        if (rest.isNotEmpty()) ready += splitLong(rest)
        reset()
        return ready
    }

    /** Сбросить накопленное без выдачи фраз (перебили озвучку, ход отменён). */
    fun reset() {
        buffer.setLength(0)
        pendingLine.setLength(0)
        inCodeFence = false
        lineCommitted = false
        firstEmitted = false
    }

    /** Текст, ещё не превращённый в фразы (диагностика и тесты). */
    val pendingText: String
        get() = stripMarkdown(buffer.toString() + pendingLine.toString()).trim()

    // --- строки и markdown ---------------------------------------------------------------------

    /** Строка закончилась переводом строки: решить, читать её или пропустить. */
    private fun finishLine() {
        val raw = pendingLine.toString()
        pendingLine.setLength(0)
        val trimmed = raw.trim()
        if (trimmed.startsWith("```")) {
            inCodeFence = !inCodeFence
            lineCommitted = false
            return
        }
        if (inCodeFence || trimmed.isEmpty() || isSkippableLine(trimmed)) {
            lineCommitted = false
            return
        }
        // Строка уже отдана в накопитель посимвольно — второй раз её не добавляем.
        if (!lineCommitted) buffer.append(trimmed)
        lineCommitted = false
        if (buffer.isNotEmpty() && buffer.last() != '\n') buffer.append('\n')
    }

    /**
     * Отдать в [buffer] текущую незавершённую строку: обычный абзац — одна длинная строка,
     * и ждать её перевода строки значило бы ждать конец ответа.
     */
    private fun commitPartialLine() {
        if (pendingLine.isEmpty() || inCodeFence) return
        val probe = pendingLine.toString().trimStart()
        // Забор кода и строку таблицы распознаём до того, как они попадут в озвучку.
        if (probe.startsWith("```")) return
        if (probe.startsWith("|")) return
        if (isSkippableLine(probe)) return
        buffer.append(pendingLine)
        pendingLine.setLength(0)
        lineCommitted = true
    }

    /** Строка не несёт речи: таблица, линейка, одинокий маркер списка. */
    private fun isSkippableLine(trimmed: String): Boolean {
        if (trimmed.startsWith("|")) return true
        if (TABLE_SEPARATOR.matches(trimmed)) return true
        if (HORIZONTAL_RULE.matches(trimmed)) return true
        if (LONE_LIST_NUMBER.matches(trimmed)) return true
        return false
    }

    /** Убрать markdown-разметку, оставив только произносимый текст. */
    private fun stripMarkdown(line: String): String {
        var text = line
        text = IMAGE_LINK.replace(text, " ")
        text = MD_LINK.replace(text, "$1")
        text = FOOTNOTE_REF.replace(text, " ")
        text = BARE_URL.replace(text, " ")
        // Содержимое inline-кода техническое (идентификаторы, пути) — в озвучку не идёт.
        text = INLINE_CODE.replace(text, " ")
        text = HTML_TAG.replace(text, " ")
        text = HEADING_PREFIX.replace(text, "")
        text = LIST_MARKER.replace(text, "")
        text = BLOCKQUOTE_PREFIX.replace(text, "")
        text = EMPHASIS.replace(text, "")
        text = LEFTOVER_MARKUP.replace(text, " ")
        return WHITESPACE.replace(text, " ").trim()
    }

    // --- вырезание фраз ------------------------------------------------------------------------

    /** Граница предложения: индекс ЗА границей и её тип (знак конца или перевод строки). */
    private class Boundary(val end: Int, val terminal: Boolean)

    /** Выпустить всё, что уже можно произносить. */
    private fun drain(): List<String> {
        val ready = ArrayList<String>()
        dropLeadingBreaks()
        while (true) {
            // Слишком короткий кандидат не выбрасываем: ищем следующую границу и склеиваем
            // его со следующим предложением — иначе короткая первая реплика теряется.
            var from = 0
            var boundary = findBoundary(from)
            var candidate = ""
            while (boundary != null) {
                candidate = stripMarkdown(buffer.substring(0, boundary.end))
                if (shouldEmit(candidate, boundary.terminal)) break
                from = boundary.end
                boundary = findBoundary(from)
            }
            if (boundary != null) {
                buffer.delete(0, boundary.end)
                if (candidate.isNotEmpty()) {
                    ready += candidate
                    firstEmitted = true
                }
                dropLeadingBreaks()
                continue
            }
            val cut = hardSplitIndex()
            if (cut <= 0) break
            val piece = stripMarkdown(buffer.substring(0, cut))
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) {
                ready += piece
                firstEmitted = true
            }
            dropLeadingBreaks()
        }
        return ready
    }

    /** Убрать ведущие переводы строк и пробелы: это не текст и не граница предложения. */
    private fun dropLeadingBreaks() {
        var i = 0
        while (i < buffer.length && buffer[i].isWhitespace()) i++
        if (i > 0) buffer.delete(0, i)
    }

    /** Пора произносить этот кусок или он ещё слишком короткий. */
    private fun shouldEmit(candidate: String, terminal: Boolean): Boolean {
        if (candidate.isEmpty()) return false
        // Верхний порог сильнее границы предложения: абзац без точек всё равно режем.
        if (candidate.length > maxPhraseLength) return false
        // Первая фраза — как можно раньше: на знаке конца предложения даже в два слова.
        if (!firstEmitted && terminal) return true
        val threshold = if (firstEmitted) minPhraseLength else firstPhraseMinLength
        return candidate.length >= threshold
    }

    /** Ближайшая безопасная граница предложения начиная с [from] или null, если её ещё нет. */
    private fun findBoundary(from: Int = 0): Boundary? {
        var i = from
        while (i < buffer.length) {
            val ch = buffer[i]
            if (ch == '\n') return Boundary(i + 1, terminal = false)
            if (isTerminalPunct(ch)) {
                var end = i
                while (end + 1 < buffer.length && isTerminalPunct(buffer[end + 1])) end++
                if (ch == '.' && !isSentenceDot(i)) {
                    i = end + 1
                    continue
                }
                if (end + 1 < buffer.length && !buffer[end + 1].isWhitespace()) {
                    i = end + 1
                    continue
                }
                if (end + 1 >= buffer.length) {
                    // Дельта оборвалась ровно на точке: после цифры это может быть 3.14,
                    // поэтому ждём следующий кусок, а не режем вслепую.
                    val prev = buffer.getOrNull(end - 1)
                    if (prev != null && prev.isDigit()) {
                        i = end + 1
                        continue
                    }
                }
                return Boundary(end + 1, terminal = true)
            }
            i++
        }
        return null
    }

    /** Точка — настоящий конец предложения (а не версия, дробь, сокращение или инициал). */
    private fun isSentenceDot(index: Int): Boolean {
        val prev = buffer.getOrNull(index - 1) ?: return false
        val next = buffer.getOrNull(index + 1)
        // 1.7.6 и 3.14: цифры с обеих сторон — это версия или дробь.
        if (prev.isDigit() && next != null && next.isDigit()) return false
        val token = buffer.substring(wordStartBefore(index), index).lowercase()
        if (token.isEmpty()) return false
        if (token in ABBREVIATIONS) return false
        // Одиночная буква — инициал или сокращение из одной буквы (т., г., е.).
        if (token.length == 1) return false
        return true
    }

    /** Начало слова перед [index] (буквы и цифры). */
    private fun wordStartBefore(index: Int): Int {
        var start = index
        while (start > 0 && (buffer[start - 1].isLetterOrDigit())) start--
        return start
    }

    /** Индекс разреза слишком длинного куска или -1, если резать ещё рано. */
    private fun hardSplitIndex(): Int {
        if (buffer.length <= maxPhraseLength) return -1
        var space = -1
        var i = maxPhraseLength - 1
        while (i >= minPhraseLength) {
            when (buffer[i]) {
                ',', ';', ':', '\n' -> return i + 1
                ' ' -> if (space < 0) space = i
            }
            i--
        }
        if (space > 0) return space + 1
        // Ни запятой, ни пробела в окне (длинный токен, текст без пробелов) — режем ровно
        // по лимиту. Иначе накопитель рос бы без границы и первая фраза ждала бы конца хода:
        // это и есть «bounded chunk», когда пунктуации нет вовсе.
        return maxPhraseLength
    }

    /** Разрезать остаток хода на куски не длиннее [maxPhraseLength]. */
    private fun splitLong(text: String): List<String> {
        if (text.length <= maxPhraseLength) return listOf(text)
        val pieces = ArrayList<String>()
        var rest = text
        while (rest.length > maxPhraseLength) {
            val cut = cutIndex(rest)
            if (cut <= 0) break
            val piece = rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
            if (piece.isNotEmpty()) pieces += piece
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /** Последняя запятая/двоеточие/пробел в пределах лимита. */
    private fun cutIndex(text: String): Int {
        var space = -1
        var i = minOf(maxPhraseLength, text.length) - 1
        while (i > 0) {
            when (text[i]) {
                ',', ';', ':', '\n' -> return i + 1
                ' ' -> if (space < 0) space = i
            }
            i--
        }
        return if (space > 0) space + 1 else minOf(maxPhraseLength, text.length)
    }

    private fun isTerminalPunct(ch: Char): Boolean =
        ch == '.' || ch == '!' || ch == '?' || ch == '…'

    companion object {
        /** Нижний порог длины обычной фразы: короче — склеиваем со следующей. */
        const val DEFAULT_MIN_PHRASE_LENGTH: Int = 25

        /** Порог для первой фразы: она должна зазвучать как можно раньше. */
        const val DEFAULT_FIRST_PHRASE_MIN_LENGTH: Int = 12

        /** Верхний порог: длиннее — режем, чтобы синтез не ждал абзац. */
        const val DEFAULT_MAX_PHRASE_LENGTH: Int = 220

        /** Сокращения, после которых точка НЕ заканчивает предложение. */
        private val ABBREVIATIONS: Set<String> = setOf(
            "т", "е", "д", "п", "др", "пр", "см", "рис", "стр", "г", "гг", "ул", "им",
            "проф", "акад", "доц", "канд", "руб", "тыс", "млн", "млрд", "напр", "ок",
            "ред", "ср", "в", "вв", "т.е", "т.д", "т.п",
        )

        private val IMAGE_LINK = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
        private val FOOTNOTE_REF = Regex("""\[\d{1,3}]""")
        private val BARE_URL = Regex("""(?:https?://|www\.)\S+""")
        private val INLINE_CODE = Regex("""`[^`]*`""")
        private val HTML_TAG = Regex("""<[^<>]{1,120}>""")
        private val HEADING_PREFIX = Regex("""^#{1,6}\s*""")
        private val LIST_MARKER = Regex("""^(?:[-*+]\s+|\d{1,2}[.)]\s+)""")
        private val BLOCKQUOTE_PREFIX = Regex("""^>\s*""")
        private val EMPHASIS = Regex("""\*\*|__|~~|\*|_""")
        private val LEFTOVER_MARKUP = Regex("""[`#>|\\]+""")
        private val WHITESPACE = Regex("""\s+""")
        private val TABLE_SEPARATOR = Regex("""^\|?[\s:|-]+\|[\s:|-]*$""")
        private val HORIZONTAL_RULE = Regex("""^(?:[-*_]\s*){3,}$""")
        private val LONE_LIST_NUMBER = Regex("""^\d{1,2}[.)]?$""")
    }
}
