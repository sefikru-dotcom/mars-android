package ru.mars.mobile

sealed class InlineSpan {
    data class Normal(val text: String) : InlineSpan()
    data class Bold(val text: String) : InlineSpan()
    data class Italic(val text: String) : InlineSpan()
    data class Monospace(val text: String) : InlineSpan()
    data class Link(val title: String, val url: String) : InlineSpan()
    data class Strikethrough(val text: String) : InlineSpan()

    /**
     * Markdown-изображение `![alt](url)`: в транскрипте становится кликабельной
     * подписью, которая открывает полноэкранный lightbox (ChatActivity).
     */
    data class Image(val alt: String, val url: String) : InlineSpan()
}

sealed class RichLine {
    data class Header(val level: Int, val spans: List<InlineSpan>) : RichLine()
    data class Bullet(val spans: List<InlineSpan>) : RichLine()
    data class Quote(val spans: List<InlineSpan>) : RichLine()
    data class Numbered(val number: String, val spans: List<InlineSpan>) : RichLine()
    data class TaskItem(val checked: Boolean, val spans: List<InlineSpan>) : RichLine()
    data class Normal(val spans: List<InlineSpan>) : RichLine()
    /** Horizontal rule (---, ***, ___). Rendered as a thin divider. */
    object Divider : RichLine()
}

sealed class ContentBlock {
    data class Text(val lines: List<RichLine>) : ContentBlock()
    data class Code(val language: String, val code: String) : ContentBlock()
    /**
     * Pipe table: a header row, a `|---|---|` separator and zero or more data rows.
     * Cells keep inline formatting, so each cell is a list of spans.
     */
    data class Table(
        val headers: List<List<InlineSpan>>,
        val rows: List<List<List<InlineSpan>>>,
    ) : ContentBlock()
}

object MarkdownFormatter {
    // Tolerates CRLF fences and keeps an unclosed fence parseable (streaming replies).
    private val CODE_BLOCK_REGEX = Regex("```(\\w*)\\r?\\n([\\s\\S]*?)```")
    private val OPEN_CODE_BLOCK_REGEX = Regex("```(\\w*)\\r?\\n([\\s\\S]*)$")
    private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")
    private val IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\((https?://[^\\s)]+)\\)")

    /**
     * Символы, которые не могут быть частью голого URL при разборе сообщения:
     * маркеры разметки (`` ` `` `*`), кавычки, угловые скобки и `|`/`[]`
     * (ссылка не должна «съедать» соседние ячейки таблицы или скобки ссылок).
     */
    private const val URL_BODY_EXCLUDE = "\\s<>\"'`*|\\[\\]"

    /**
     * Голые URL. Схема http(s) — любой хост (в т.ч. `localhost` и IP);
     * «голый домен» — только известные зоны, иначе `файл.pdf` или `версия 1.5.4`
     * в обычном тексте стали бы ссылками. Левый lookbehind не даёт линковать
     * URL, приклеенный к слову или к `@` (адрес почты).
     */
    private val SCHEME_URL_REGEX = Regex("(?<![\\p{L}\\p{N}@])https?://[^$URL_BODY_EXCLUDE]+")
    private val BARE_DOMAIN_REGEX = Regex(
        "(?<![\\p{L}\\p{N}@./-])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:ru|com|net|org|io|dev|me|ai|su|рф)(?![\\p{L}\\p{N}_-])(?:/[^$URL_BODY_EXCLUDE]*)?"
    )
    private val URL_REGEXES = listOf(SCHEME_URL_REGEX, BARE_DOMAIN_REGEX)

    /** Хвостовая пунктуация прозы не является частью URL (`см. https://x.ru.`). */
    private const val URL_TAIL_PUNCTUATION = ".,;:!?«»\"'"
    private val BOLD_REGEX = Regex("\\*\\*(.+?)\\*\\*")
    private val CODE_INLINE_REGEX = Regex("`([^`]+)`")
    private val STRIKE_REGEX = Regex("~~(.+?)~~")
    private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER_REGEX = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val NUMBERED_REGEX = Regex("^(\\d+)\\.\\s+(.*)$")
    private val TASK_ITEM_REGEX = Regex("^[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val BULLET_REGEX = Regex("^[-*+]\\s+(.*)$")
    private val QUOTE_REGEX = Regex("^>\\s?(.*)$")
    private val ITALIC_AFTER_TRIM = Regex("\\*(.+?)\\*")
    private val ESCAPED_PIPE_SPLIT_REGEX = Regex("(?<!\\\\)\\|")
    private val TABLE_SEPARATOR_CELL_REGEX = Regex("^:?-+:?$")

    /** Ёмкость LRU-кэша результатов разбора: последние ~64 сообщения/состояния стрима. */
    internal const val PARSE_CACHE_MAX_ENTRIES = 64

    /**
     * Тексты длиннее этого не кэшируются: промежуточные состояния очень длинного
     * стрима иначе заняли бы весь кэш и удерживали бы мегабайты строк в памяти.
     */
    internal const val PARSE_CACHE_MAX_LENGTH = 64_000

    // Кэш thread-safe (ThreadSafeLru): парсинг может вызываться и с UI-потока,
    // и из фонового потока. Ключ — сам текст: точное совпадение без коллизий
    // fingerprint-хэшей; строки сообщений и так живут в ChatUiState, поэтому
    // 64 записи не создают заметного давления на память.
    private val PARSE_CACHE = ThreadSafeLru<String, List<ContentBlock>>(PARSE_CACHE_MAX_ENTRIES)

    /** Тестовые хуки (internal, обратно совместимое добавление). */
    internal fun parseCacheSize(): Int = PARSE_CACHE.size()

    internal fun clearParseCache() = PARSE_CACHE.clear()

    /**
     * Очистка кэша разбора под системным давлением памяти (вызывает [MarsApplication]
     * из onTrimMemory/onLowMemory). Разбор восстанавливается лениво при следующем
     * рендере — видимый результат не меняется, только теряется разогрев кэша.
     */
    fun trimCache() {
        PARSE_CACHE.clear()
    }

    /**
     * Разбирает сообщение на блоки. Результат кэшируется по точному тексту:
     * при стриме каждый кадр перерисовывает ВСЕ видимые сообщения, но меняется
     * только последнее — остальные получают готовый разбор из кэша вместо
     * ~16 проходов регексов по всему тексту. Возвращаемый список read-only
     * и разделяется между вызовами — вызывающая сторона его не мутирует.
     */
    fun parseBlocks(raw: String): List<ContentBlock> {
        if (raw.isEmpty()) return emptyList()
        PARSE_CACHE.get(raw)?.let { return it }
        // Fast path: в тексте нет ни одного маркера markdown-синтаксиса —
        // полный регекс-проход (code fences, таблицы) заведомо ничего не найдёт.
        val parsed = if (hasMarkdownMarkers(raw)) parseBlocksUncached(raw) else plainTextBlocks(raw)
        if (raw.length <= PARSE_CACHE_MAX_LENGTH) PARSE_CACHE.put(raw, parsed)
        return parsed
    }

    /**
     * Полный разбор без кэша и fast path. Сохранён как internal: на нём
     * тесты сверяют эквивалентность кэша и быстрой дорожки (behavioral parity).
     */
    internal fun parseBlocksUncached(raw: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var lastIndex = 0
        for (match in CODE_BLOCK_REGEX.findAll(raw)) {
            val textBefore = raw.substring(lastIndex, match.range.first)
            blocks.addAll(parseTextBlocks(textBefore))
            blocks.add(ContentBlock.Code(match.groupValues[1].trim(), match.groupValues[2].trimEnd('\n', '\r')))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            val remaining = raw.substring(lastIndex)
            // Unclosed fence: treat the tail as a code block so partial streams render sanely.
            val open = OPEN_CODE_BLOCK_REGEX.find(remaining)
            if (open != null) {
                val before = remaining.substring(0, open.range.first)
                blocks.addAll(parseTextBlocks(before))
                blocks.add(ContentBlock.Code(open.groupValues[1].trim(), open.groupValues[2].trimEnd('\n', '\r')))
            } else {
                blocks.addAll(parseTextBlocks(remaining))
            }
        }

        return blocks
    }

    /**
     * Текст без markdown-маркеров: ровно один [ContentBlock.Text] — тот же
     * результат, что дал бы полный путь (code fence и таблица требуют
     * символов, которых здесь нет по определению [hasMarkdownMarkers]).
     */
    private fun plainTextBlocks(raw: String): List<ContentBlock> =
        listOf(ContentBlock.Text(parseLineList(raw.split("\n"))))

    /**
     * Однократный скан текста: есть ли хоть один символ, с которого может
     * начинаться markdown-синтаксис. Любой блок/строка требует один из:
     * - в любой позиции: `` ` `` (код), `*` (bold/italic/divider), `[` (ссылки
     *   и картинки — `![` тоже содержит `[`), `|` (таблицы), `~` (strikethrough);
     * - в начале строки (после переводов строк и пробельных символов, как в
     *   `trim()`): `#` `>` `-` `+` `_` (заголовки, цитаты, буллиты, divider)
     *   или ASCII-цифра (`1.` — нумерованный список; `\d` в Java-регексе это
     *   только `0-9`).
     * Всё остальное разбор игнорирует, поэтому отсутствие маркеров гарантирует
     * идентичность «быстрого» результата полному. Стоимость — один проход
     * с посимвольными сравнениями вместо ~16 findAll/substring по всему тексту.
     */
    internal fun hasMarkdownMarkers(raw: String): Boolean {
        var atLineStart = true
        for (c in raw) {
            when {
                c == '`' || c == '*' || c == '[' || c == '|' || c == '~' -> return true
                c == '#' || c == '>' || c == '-' || c == '+' || c == '_' -> {
                    if (atLineStart) return true
                    atLineStart = false
                }
                c == '\n' -> atLineStart = true
                // Пробельные символы (включая \r, \t, unicode-пробелы) не сбрасывают
                // «начало строки»: trim() в parseLines убирает их так же.
                c.isWhitespace() -> Unit
                else -> {
                    if (atLineStart && c in '0'..'9') return true
                    atLineStart = false
                }
            }
        }
        return false
    }

    /**
     * Маркеры только inline-синтаксиса: код, bold/italic, ссылки/картинки, strike —
     * плюс признаки, по которым может сработать автолинк голых URL.
     *
     * URL-признаки намеренно узкие, иначе медленный путь включался бы почти на
     * каждой строке: `версия 1.5.4` (точка перед цифрой) и `итого: 5` (одиночное
     * двоеточие) остаются быстрыми — регексы по ним ничего не найдут.
     */
    internal fun hasInlineMarkers(raw: String): Boolean {
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '`', '*', '[', '~' -> return true
                // Схема URL: только "://" — одиночное ":" это обычная пунктуация.
                ':' -> if (raw.startsWith("://", i)) return true
                // Начало «голого» домена: точка, сразу за которой буква
                // (`marscore.ru`). Точка перед пробелом/цифрой — не кандидат.
                '.' -> if (i + 1 < raw.length && raw[i + 1].isLetter()) return true
            }
            i++
        }
        return false
    }

    /**
     * Splits a non-code text segment into [ContentBlock.Text] and [ContentBlock.Table] blocks.
     * A table starts at a pipe row immediately followed by a `|---|---|` separator row;
     * consecutive pipe rows after the separator become data rows. Cells are trimmed
     * and parsed with [parseInline], so they keep bold/italic/code/link/strike formatting.
     */
    private fun parseTextBlocks(raw: String): List<ContentBlock> {
        if (raw.isEmpty()) return emptyList()
        val blocks = mutableListOf<ContentBlock>()
        val lines = raw.split("\n")
        val pending = mutableListOf<String>()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                // Строки передаются списком напрямую: joinToString("\n") + повторный
                // split("\n") внутри parseLines были лишней парой проходов/аллокаций.
                blocks.add(ContentBlock.Text(parseLineList(pending)))
                pending.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            if (i + 1 < lines.size && isTableRowLine(lines[i]) && isTableSeparatorLine(lines[i + 1])) {
                flushPending()
                val headers = splitTableCells(lines[i])
                val rows = mutableListOf<List<List<InlineSpan>>>()
                var j = i + 2
                while (j < lines.size && isTableRowLine(lines[j])) {
                    rows.add(splitTableCells(lines[j]))
                    j++
                }
                blocks.add(ContentBlock.Table(headers, rows))
                i = j
            } else {
                pending.add(lines[i])
                i++
            }
        }
        flushPending()
        return blocks
    }

    private fun isTableRowLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() && trimmed.contains('|')
    }

    /** Separator row like `|---|---|` or `:--- | ---:` — dashes, colons and pipes only. */
    private fun isTableSeparatorLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|') || !trimmed.contains('-')) return false
        val cells = splitRawCells(trimmed)
        return cells.isNotEmpty() && cells.all { TABLE_SEPARATOR_CELL_REGEX.matches(it) }
    }

    private fun splitTableCells(line: String): List<List<InlineSpan>> =
        splitRawCells(line).map { parseInline(it) }

    /** Splits a pipe row into trimmed cells, honouring `\|` escapes. */
    private fun splitRawCells(line: String): List<String> {
        var trimmed = line.trim()
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.substring(0, trimmed.length - 1)
        return trimmed.split(ESCAPED_PIPE_SPLIT_REGEX).map { it.trim().replace("\\|", "|") }
    }

    fun parseLines(raw: String): List<RichLine> = parseLineList(raw.split("\n"))

    private fun parseLineList(lines: List<String>): List<RichLine> {
        return lines.map { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> RichLine.Normal(emptyList())
                // Fast path: в строке нет маркеров — ни один из строчных
                // регексов не сматчит, сразу обычный текст.
                !hasMarkdownMarkers(trimmed) -> RichLine.Normal(parseInline(line))
                DIVIDER_REGEX.matches(trimmed) -> RichLine.Divider
                // Один проход на структуру вместо matches() + find() + find()!!:
                // fullMatch эквивалентен matches(), но возвращает уже найденный
                // результат с группами.
                else -> fullMatch(HEADER_REGEX, trimmed)?.let { m ->
                    RichLine.Header(m.groupValues[1].length, parseInline(m.groupValues[2]))
                } ?: fullMatch(QUOTE_REGEX, trimmed)?.let { m ->
                    RichLine.Quote(parseInline(m.groupValues[1]))
                } ?: fullMatch(TASK_ITEM_REGEX, trimmed)?.let { m ->
                    val isChecked = m.groupValues[1].equals("x", ignoreCase = true)
                    RichLine.TaskItem(isChecked, parseInline(m.groupValues[2]))
                } ?: fullMatch(BULLET_REGEX, trimmed)?.let { m ->
                    RichLine.Bullet(parseInline(m.groupValues[1]))
                } ?: fullMatch(NUMBERED_REGEX, trimmed)?.let { m ->
                    RichLine.Numbered("${m.groupValues[1]}.", parseInline(m.groupValues[2]))
                } ?: RichLine.Normal(parseInline(line))
            }
        }
    }

    /**
     * Точный аналог [Regex.matches] одним проходом: первый [Regex.find],
     * покрывающий всю строку. (Чистый `find` без проверки покрытия был бы
     * не эквивалентен: `$` в Java матчится и перед завершающим терминатором
     * строки, например \u0085, который `trim()` не убирает.)
     */
    private fun fullMatch(regex: Regex, s: String): MatchResult? =
        regex.find(s)?.takeIf { it.range.first == 0 && it.range.last == s.lastIndex }

    fun parseInline(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        // Fast path: нет inline-маркеров — ни один из 7 inline-регексов
        // (картинки, ссылки, код, strike, bold, italic) не сматчит.
        if (!hasInlineMarkers(raw)) return listOf(InlineSpan.Normal(raw))
        val spans = mutableListOf<InlineSpan>()

        // 0. Images first (![alt](url)) so the `!` is consumed with the image and the
        //    inner [alt](url) never degrades into a plain link.
        var imageCursor = 0
        for (m in IMAGE_REGEX.findAll(raw)) {
            if (m.range.first > imageCursor) {
                spans.addAll(parseLinkSegment(raw.substring(imageCursor, m.range.first)))
            }
            spans.add(InlineSpan.Image(m.groupValues[1], m.groupValues[2]))
            imageCursor = m.range.last + 1
        }
        if (imageCursor < raw.length) {
            spans.addAll(parseLinkSegment(raw.substring(imageCursor)))
        }
        return spans
    }

    private fun parseLinkSegment(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        val spans = mutableListOf<InlineSpan>()

        // 1. Split by links [title](url); the text between them keeps formatting.
        var cursor = 0
        for (m in LINK_REGEX.findAll(raw)) {
            if (m.range.first > cursor) {
                spans.addAll(parseInlineFormatting(raw.substring(cursor, m.range.first)))
            }
            spans.add(InlineSpan.Link(m.groupValues[1], m.groupValues[2]))
            cursor = m.range.last + 1
        }
        if (cursor < raw.length) {
            spans.addAll(parseInlineFormatting(raw.substring(cursor)))
        }
        return spans
    }

    private fun parseInlineFormatting(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        // Fast path: сегменты между ссылками/картинками часто «голые» —
        // без маркеров 4 findAll-прохода гарантированно не найдут токенов.
        if (!hasInlineMarkers(raw)) return listOf(InlineSpan.Normal(raw))
        val result = mutableListOf<InlineSpan>()

        data class Token(val range: IntRange, val span: InlineSpan)
        val tokens = mutableListOf<Token>()

        fun overlaps(r: IntRange) = tokens.any { t -> r.first <= t.range.last && r.last >= t.range.first }

        // Code spans win: nothing inside `…` is formatted.
        CODE_INLINE_REGEX.findAll(raw).forEach {
            if (!overlaps(it.range)) tokens.add(Token(it.range, InlineSpan.Monospace(it.groupValues[1])))
        }
        // Strikethrough via ~~text~~; code spans above win, so `~~x~~` stays literal.
        STRIKE_REGEX.findAll(raw).forEach {
            if (!overlaps(it.range)) tokens.add(Token(it.range, InlineSpan.Strikethrough(it.groupValues[1])))
        }
        // Bold next: **…** claims its range so italic cannot split it.
        BOLD_REGEX.findAll(raw).forEach {
            if (!overlaps(it.range)) tokens.add(Token(it.range, InlineSpan.Bold(it.groupValues[1])))
        }
        // Italic last, only outside claimed ranges (code/bold win on overlap).
        ITALIC_AFTER_TRIM.findAll(raw).forEach { match ->
            val stripped = stripOuterAsterisks(match)
            if (stripped != null && !overlaps(stripped.first)) {
                tokens.add(Token(stripped.first, InlineSpan.Italic(stripped.second)))
            }
        }
        // Голые URL — после кода и акцентов: диапазоны `` `code` ``/`**bold**`
        // уже заняты, поэтому URL внутри них не рвёт разметку (в коде ссылки быть
        // не должно вообще), а URL в обычной прозе становится кликабельным.
        // title = url: TalkBack и выделение видят сам адрес.
        for (regex in URL_REGEXES) {
            regex.findAll(raw).forEach { match ->
                val link = urlLinkOrNull(regex, match) ?: return@forEach
                val range = match.range.first until (match.range.first + link.url.length)
                if (!overlaps(range)) tokens.add(Token(range, link))
            }
        }

        tokens.sortBy { it.range.first }

        var cursor = 0
        for (token in tokens) {
            if (token.range.first < cursor) continue
            if (token.range.first > cursor) {
                result.add(InlineSpan.Normal(raw.substring(cursor, token.range.first)))
            }
            result.add(token.span)
            cursor = token.range.last + 1
        }
        if (cursor < raw.length) {
            result.add(InlineSpan.Normal(raw.substring(cursor)))
        }

        return result
    }

    /**
     * `*text*` -> italic. The loose regex also catches stray asterisks of a bold token
     * (`**x**` matches as `*` + `*x` + `*`); shrink the extra asterisks and reject matches
     * whose payload touches whitespace (CommonMark flanking: `a * b * c` is not italic).
     * Returns null when the match carries no usable content.
     */
    private fun stripOuterAsterisks(match: MatchResult): Pair<IntRange, String>? {
        var first = match.range.first
        var last = match.range.last
        var text = match.groupValues[1]
        while (text.startsWith("*") && first + 1 < last) {
            first++
            text = text.substring(1)
        }
        while (text.endsWith("*") && last - 1 > first) {
            last--
            text = text.substring(0, text.length - 1)
        }
        if (text.isEmpty()) return null
        if (text.first().isWhitespace() || text.last().isWhitespace()) return null
        return Pair(first..last, text)
    }

    /**
     * Превращает совпадение URL-регекса в ссылку, отрезая хвост прозы.
     * Возвращает null, если после срезания остаток перестал быть URL
     * (например, матч целиком состоял из пунктуации).
     *
     * Для «голого домена» видимый текст остаётся как в сообщении
     * (`marscore.ru/foo`), а в [InlineSpan.Link.url] подставляется схема:
     * `Intent.ACTION_VIEW` по адресу без схемы не открывает браузер.
     */
    private fun urlLinkOrNull(regex: Regex, match: MatchResult): InlineSpan.Link? {
        val shown = trimUrlTail(match.value)
        if (shown.isEmpty()) return null
        // Хвост обязан остаться валидным URL того же вида: проверка полным
        // совпадением отсекает вырожденные остатки вроде `https://` или `..ru`.
        if (!regex.matches(shown)) return null
        val target = if (shown.startsWith("http://") || shown.startsWith("https://")) shown else "https://$shown"
        return InlineSpan.Link(shown, target)
    }

    /**
     * Отрезает хвост, принадлежащий прозе, а не ссылке: конечную пунктуацию
     * (`См. https://x.ru.`) и закрывающие скобки без пары (`(см. https://x.ru)`).
     * Скобки внутри URL сохраняются целиком — `…/wiki/Foo_(bar)` не рвётся.
     */
    private fun trimUrlTail(raw: String): String {
        var url = raw
        while (true) {
            val before = url
            while (url.isNotEmpty() && url.last() in URL_TAIL_PUNCTUATION) {
                url = url.dropLast(1)
            }
            val opens = url.count { it == '(' }
            val closes = url.count { it == ')' }
            if (closes > opens && url.endsWith(')')) url = url.dropLast(1)
            if (url == before) return url
        }
    }
}
