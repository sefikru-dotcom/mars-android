package ru.mars.tv.chat

/**
 * Блок ответа для ТВ-рендера markdown (паритет с мобильными `ContentBlock.Table` /
 * `RichLine.TaskItem`, но без спанов: на ТВ текст плоский, inline-разметка снимается).
 * Чистая JVM-модель — рендер живёт в тонком Android-слое `TvMarkdownRenderer`.
 */
sealed class TvBlock {
    /** Обычный текст (включая содержимое code-fence — на ТВ он показывается как есть). */
    data class Text(val text: String) : TvBlock()

    /** Markdown-таблица: заголовки + строки ячеек (ширина строк выровнена по заголовку). */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : TvBlock()

    /** Чек-лист: подряд идущие строки `- [ ]` / `- [x]`. */
    data class TaskList(val items: List<TvTask>) : TvBlock()
}

/** Пункт чек-листа. */
data class TvTask(val checked: Boolean, val text: String)

/**
 * Чистый парсер markdown-таблиц и чек-листов для экрана ТВ-чата (в app-tv своего парсера
 * не было — пузыри рендерились плоским текстом). Правила:
 *
 * - ТАБЛИЦА: строка-заголовок с '|' (>=2 ячеек), за ней разделитель `|---|:-:|...`;
 *   тело — подряд идущие строки с '|'. Ячейки чистятся от inline-разметки, строки
 *   добиваются/обрезаются до ширины заголовка. Таблица без разделителя НЕ таблица.
 * - ЧЕК-ЛИСТ: подряд идущие строки `- [ ] текст` / `- [x]` / `* [X]` (регистр любой).
 * - CODE-FENCE (```): содержимое уходит в [TvBlock.Text] дословно (без снятия разметки),
 *   таблицы/чек-листы внутри кода не распознаются.
 * - Всё остальное склеивается в текстовые блоки (пустая строка — граница блока).
 */
object TvMarkdownBlocks {

    private val FENCE = Regex("^\\s*(```|~~~)")
    private val TASK = Regex("^\\s*[-*+]\\s*\\[([ xX])]\\s+(.*)$")
    private val SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    /** Разбирает ответ на блоки. Пустой/пробельный текст -> пустой список. */
    fun parse(raw: String): List<TvBlock> {
        if (raw.isBlank()) return emptyList()
        val blocks = mutableListOf<TvBlock>()
        val lines = raw.lines()
        var i = 0
        val textPending = mutableListOf<String>()

        fun flushText() {
            val joined = textPending.joinToString("\n").trim('\n')
            if (joined.isNotEmpty()) blocks.add(TvBlock.Text(joined))
            textPending.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                FENCE.matches(line) -> {
                    // Код — дословно в текстовый блок (включая сами fence-строки: на ТВ
                    // ответ читается как есть, подсветка синтаксиса не заявлена).
                    val fence = mutableListOf(line)
                    i++
                    while (i < lines.size) {
                        fence.add(lines[i])
                        val closed = FENCE.matches(lines[i])
                        i++
                        if (closed) break
                    }
                    textPending.addAll(fence)
                }
                isTaskLine(line) -> {
                    flushText()
                    val items = mutableListOf<TvTask>()
                    while (i < lines.size && isTaskLine(lines[i])) {
                        val m = TASK.matchEntire(lines[i])!!
                        items.add(TvTask(m.groupValues[1].isNotBlank(), stripInline(m.groupValues[2]).trim()))
                        i++
                    }
                    blocks.add(TvBlock.TaskList(items))
                }
                isTableStart(lines, i) -> {
                    flushText()
                    val headers = splitRow(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows.add(normalizeRow(splitRow(lines[i]), headers.size))
                        i++
                    }
                    blocks.add(TvBlock.Table(headers, rows))
                }
                else -> {
                    textPending.add(line)
                    i++
                }
            }
        }
        flushText()
        return blocks
    }

    /** Есть ли в тексте хоть один «богатый» блок (таблица/чек-лист) — сигнал к rich-рендеру. */
    fun isRich(raw: String): Boolean = parse(raw).any { it !is TvBlock.Text }

    /** Снимает inline-разметку: **жирный**, *курсив*, `код`, ~~зачёркнутый~~, [текст](url), ![alt](url). */
    fun stripInline(value: String): String = value
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\(([^)]*)\\)"), "$1 ($2)")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("~~([^~]+)~~"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("(?<![*\\\\])\\*([^*\\n]+)\\*(?!\\*)"), "$1")

    private fun isTaskLine(line: String) = TASK.matches(line)

    /** Строка i — заголовок таблицы, строка i+1 — разделитель. */
    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        val header = lines[i]
        val separator = lines.getOrNull(i + 1) ?: return false
        if (!header.contains('|') || header.isBlank()) return false
        if (splitRow(header).size < 2) return false
        return SEPARATOR.matches(separator) && separator.contains('-') && separator.contains('|')
    }

    /** Режет строку таблицы на ячейки: крайние '|' факультативны, inline-разметка снимается. */
    private fun splitRow(line: String): List<String> {
        val body = line.trim().removePrefix("|").removeSuffix("|")
        return body.split('|').map { stripInline(it).trim() }
    }

    /** Добивает короткую строку пустыми ячейками / обрезает лишнее до ширины заголовка. */
    private fun normalizeRow(cells: List<String>, width: Int): List<String> = when {
        cells.size == width -> cells
        cells.size < width -> cells + List(width - cells.size) { "" }
        else -> cells.take(width)
    }
}
