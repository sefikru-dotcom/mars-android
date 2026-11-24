package ru.mars.mobile

/**
 * Что считать артефактом: длинный структурированный ответ, а не любое сообщение.
 *
 * Панель артефакта (как Canvas у ChatGPT) имеет смысл только тогда, когда ответ перестал
 * быть репликой в ленте и стал документом: таблицей на десяток строк, листингом кода или
 * связным текстом на несколько экранов. Поэтому вид артефакта — это ещё и указание, в
 * каком формате его сохранять: таблица просит Excel, листинг — обычный текст, всё
 * остальное — Word.
 */
enum class ArtifactKind { TABLE, CODE, DOCUMENT, NONE }

/**
 * Готовый формат для сохранения: DOCX, XLSX, TXT.
 *
 * Имена совпадают с теми строками, которыми уже пользуется сохранение файла в
 * [LocalChatActivity] (`"DOCX"`, `"XLSX"`, `"TXT"`), поэтому сопоставление в вызывающем
 * коде — это `format.name`, а расширения отдаёт [ArtifactPolicy.extension].
 */
enum class ArtifactFormat { DOCX, XLSX, TXT }

/**
 * Решение панели: что это за артефакт, как его назвать и в каком формате сохранить.
 *
 * [suggestedTitle] — не заголовок сообщения, а первая содержательная строка без
 * markdown-мусора: человек узнаёт документ по первой строке, а не по «# Отчёт».
 */
data class ArtifactDecision(
    val kind: ArtifactKind,
    val suggestedTitle: String,
    /** Готовый формат для сохранения: DOCX, XLSX, TXT. */
    val suggestedFormat: ArtifactFormat,
) {
    val isArtifact: Boolean get() = kind != ArtifactKind.NONE
}

/**
 * Политика артефакт-панели: стоит ли открывать длинный ответ в отдельной панели и чем
 * его потом сохранять.
 *
 * Здесь нет ни одного `android.*`-импорта: решение — это арифметика над строками, поэтому
 * оно проверяется JVM-тестом (`ArtifactPolicyTest`), а лист ([ArtifactSheet]) остаётся
 * тонким — он только рисует текст и кнопку по готовому [ArtifactDecision].
 *
 * Разбор markdown повторяет правила [MarkdownFormatter], а не тянет вторую библиотеку:
 * markdown-таблица в проекте — это строка с `|`, за которой идёт строка-разделитель
 * `|---|---|`, а блок кода — это парные ```` ``` ```` (см. `MarkdownFormatter.parseBlocks`).
 * Если бы панель считала таблицей любой набор строк с `|`, расхождение с лентой было бы
 * видно человеку: в чате текст, в панели — «таблица».
 *
 * Формат сохранения строится по уже существующему [DocumentBuilder] (DOCX — обычный
 * текст по абзацам, XLSX — строки и ячейки, TXT — как есть). Политика его не переписывает
 * и ничего в нём не меняет: она только говорит, какой из трёх сборщиков вызывать.
 * Отсюда важное следствие, которое надо знать вызывающему: длинный текст с ОДНОЙ маленькой
 * таблицей внутри тоже уходит в XLSX — правило порядка зафиксировано контрактом волны
 * (таблица приоритетнее кода и документа).
 */
object ArtifactPolicy {

    /** Порог «это уже не сообщение, а документ»: 12 строк или 900 символов. */
    const val MIN_LINES: Int = 12
    const val MIN_CHARS: Int = 900

    /**
     * Минимум строк в markdown-таблице, чтобы считать её артефактом.
     *
     * Считаются шапка и строки данных вместе, строка-разделитель `|---|---|` — нет: она не
     * данные, её [DocumentBuilder.rowsFromText] тоже выбрасывает. Два — это «шапка плюс
     * хотя бы одна строка»: одна шапка без данных в Excel бессмысленна, а «таблица» из
     * одной строки — это обычная строка текста.
     */
    const val MIN_TABLE_ROWS: Int = 2

    /** Заголовок панели в две строки диалога (17sp) — длиннее не влезает. */
    const val TITLE_MAX_CHARS: Int = 60

    /** Подпись, когда в тексте нет ни одной содержательной строки (одни пробелы и разметка). */
    const val FALLBACK_TITLE: String = "Без названия"

    /** Имена видов артефакта для бейджа в шапке панели. */
    const val KIND_TABLE_LABEL: String = "Таблица"
    const val KIND_CODE_LABEL: String = "Код"
    const val KIND_DOCUMENT_LABEL: String = "Документ"

    // ─────────────────────────────────── решение ───────────────────────────────────

    /**
     * Решить, артефакт ли это.
     *
     * Порядок правил — как в контракте, и он же порядок «специфичности»: таблица, потом
     * код, потом документ. Текст, где есть и таблица, и листинг, становится таблицей —
     * таблица в ответе обычно и есть главный результат, а листинг рядом с ней — пояснение.
     *
     * Пустой и пробельный текст — [ArtifactKind.NONE]: панель нечего показывать.
     */
    fun decide(text: String): ArtifactDecision {
        if (text.isBlank()) return none()
        val lines = splitLines(text)
        if (tables(lines).any { it.size >= MIN_TABLE_ROWS }) {
            return ArtifactDecision(ArtifactKind.TABLE, title(text), ArtifactFormat.XLSX)
        }
        if (codeBlockLineCounts(lines).any { it >= MIN_LINES }) {
            return ArtifactDecision(ArtifactKind.CODE, title(text), ArtifactFormat.TXT)
        }
        if (lineCount(text) >= MIN_LINES || text.trim().length >= MIN_CHARS) {
            return ArtifactDecision(ArtifactKind.DOCUMENT, title(text), ArtifactFormat.DOCX)
        }
        return none()
    }

    /** Решение «не артефакт»: пустой заголовок, формат по умолчанию — TXT. */
    private fun none(): ArtifactDecision =
        ArtifactDecision(ArtifactKind.NONE, "", ArtifactFormat.TXT)

    /**
     * Заголовок панели: первая содержательная строка без markdown-мусора.
     *
     * Пропускаются: пустые строки, заборы блоков кода (```` ``` ````), строки-разделители
     * таблиц, горизонтальные линейки и строки, от которых после чистки ничего не осталось.
     * Из оставшихся убираются `#`, `*`, `` ` ``, `~~`, маркеры списков и цитат, а ссылки и
     * картинки заменяются своим текстом: заголовок показывает то же, что человек видит в
     * ленте, но без синтаксиса.
     *
     * `_` намеренно не вычищается целиком: `max_tokens` и `my_file.py` — это идентификаторы,
     * и «maxtokens» в заголовке было бы порчей текста. Снимается только парное подчёркивание
     * выделения (`_курсив_`).
     *
     * Длинный первый абзац обрезается до [TITLE_MAX_CHARS] с многоточием: заголовок живёт в
     * шапке диалога, где помещаются две строки.
     */
    fun title(text: String): String {
        for (raw in splitLines(text)) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (isFence(trimmed)) continue
            if (DIVIDER.matches(trimmed)) continue
            if (isTableSeparator(trimmed)) continue
            val cleaned = cleanTitleLine(trimmed)
            if (cleaned.isEmpty()) continue
            return truncate(cleaned, TITLE_MAX_CHARS)
        }
        return FALLBACK_TITLE
    }

    /** Подпись кнопки сохранения по формату. */
    fun saveLabel(format: ArtifactFormat): String = when (format) {
        ArtifactFormat.DOCX -> "Сохранить DOCX"
        ArtifactFormat.XLSX -> "Сохранить XLSX"
        ArtifactFormat.TXT -> "Сохранить TXT"
    }

    /**
     * Сколько таблиц/блоков кода в тексте — для подписи в панели.
     *
     * Считаются только блоки, достойные артефакта (таблица от [MIN_TABLE_ROWS] строк,
     * листинг от [MIN_LINES] строк): подпись «таблиц: 1» рядом с таблицей из двух строк,
     * которая таблицей не считается, читалась бы как ошибка.
     *
     * Для [ArtifactKind.DOCUMENT] и [ArtifactKind.NONE] — `0`: документ не состоит из
     * блоков одного вида, и подпись панели ограничивается числом строк.
     */
    fun countBlocks(text: String, kind: ArtifactKind): Int {
        if (text.isBlank()) return 0
        val lines = splitLines(text)
        return when (kind) {
            ArtifactKind.TABLE -> tables(lines).count { it.size >= MIN_TABLE_ROWS }
            ArtifactKind.CODE -> codeBlockLineCounts(lines).count { it >= MIN_LINES }
            ArtifactKind.DOCUMENT, ArtifactKind.NONE -> 0
        }
    }

    // ─────────────────────────── подписи и служебные значения ───────────────────────────

    /**
     * Строки самой большой таблицы текста — готовый аргумент для [DocumentBuilder.buildXlsx].
     *
     * Зачем это здесь, а не `DocumentBuilder.rowsFromText`: тот разборщик универсальный (он
     * нужен экрану, где человек сам выбирает формат) и берёт в Excel весь текст — абзацы
     * становятся строками из одной ячейки, а заборы блоков кода — строками ```` ``` ````.
     * Для артефакта это мусор: в файл должна уйти таблица, ради которой панель и открыли.
     * Берётся самая большая таблица: если их несколько, главная — та, где больше строк.
     *
     * Пустой список — таблицы нет; вызывающему тогда XLSX собирать не из чего, и он может
     * откатиться на [DocumentBuilder.rowsFromText] или на DOCX.
     */
    fun tableRows(text: String): List<List<String>> {
        if (text.isBlank()) return emptyList()
        val best = tables(splitLines(text)).maxByOrNull { it.size } ?: return emptyList()
        return best
    }

    /** Человеческое имя вида артефакта — бейдж в шапке панели. */
    fun kindLabel(kind: ArtifactKind): String = when (kind) {
        ArtifactKind.TABLE -> KIND_TABLE_LABEL
        ArtifactKind.CODE -> KIND_CODE_LABEL
        ArtifactKind.DOCUMENT -> KIND_DOCUMENT_LABEL
        ArtifactKind.NONE -> ""
    }

    /**
     * Расширение файла без точки — ровно те строки, которые понимает сохранение в
     * [LocalChatActivity] (`docx`/`xlsx`/`txt`).
     */
    fun extension(format: ArtifactFormat): String = when (format) {
        ArtifactFormat.DOCX -> "docx"
        ArtifactFormat.XLSX -> "xlsx"
        ArtifactFormat.TXT -> "txt"
    }

    /**
     * MIME собранного файла — из [DocumentBuilder], чтобы не заводить второй список
     * констант, который однажды разойдётся с тем, что реально пишется в файл.
     */
    fun mimeType(format: ArtifactFormat): String = when (format) {
        ArtifactFormat.DOCX -> DocumentBuilder.DOCX_MIME
        ArtifactFormat.XLSX -> DocumentBuilder.XLSX_MIME
        ArtifactFormat.TXT -> DocumentBuilder.TXT_MIME
    }

    /**
     * Сколько строк в тексте — для подписи «N строк».
     *
     * Считаются содержательные строки: пустые — это воздух между абзацами, заборы блоков
     * кода и строки-разделители таблиц `|---|---|` — разметка, а не текст. Человек, глядя
     * на ответ, строки считает по содержимому, и порог [MIN_LINES] не должен срабатывать
     * на текст из двенадцати пустых строк или из одних заборов.
     */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 0
        for (line in splitLines(text)) {
            if (line.isBlank()) continue
            if (isFence(line)) continue
            if (isTableSeparator(line)) continue
            count++
        }
        return count
    }

    /**
     * Подпись «N строк» с правильным падежом: 1 строка, 2 строки, 12 строк.
     *
     * Окончание зависит от последних двух цифр (11–14 — всегда «строк»), поэтому
     * `21 строка`, но `11 строк`.
     */
    fun linesLabel(count: Int): String {
        val n = if (count < 0) 0 else count
        val tail100 = n % 100
        val tail10 = n % 10
        val word = when {
            tail100 in 11..14 -> "строк"
            tail10 == 1 -> "строка"
            tail10 in 2..4 -> "строки"
            else -> "строк"
        }
        return "$n $word"
    }

    // ─────────────────────────────── разбор markdown-структур ───────────────────────────────

    /**
     * Таблицы текста: каждая — матрица ячеек, шапка первой строкой, разделитель выброшен.
     *
     * Именно строки, а не только их число: XLSX собирается из ячеек, и общий разборщик
     * [DocumentBuilder.rowsFromText] для артефакта не подходит — он берёт ВСЕ строки текста
     * с `|` (и прозу рядом тоже, по строке на ячейку), поэтому в Excel попадали бы абзацы
     * вокруг таблицы и заборы блоков кода. Здесь берётся только таблица.
     *
     * Таблица начинается там же, где её видит лента: строка с `|`, сразу за которой идёт
     * строка-разделитель. Дальше подряд идущие строки с `|` — строки данных.
     *
     * Строки внутри блоков кода не участвуют: `| a | b |` в листинге — это пример таблицы
     * в тексте, а не таблица ответа. Лента разбирает код раньше таблиц
     * ([MarkdownFormatter.parseBlocks]), и панель обязана считать так же, иначе ответ
     * «вот как рисуется таблица» с примером внутри кода уходил бы в XLSX.
     */
    private fun tables(lines: List<String>): List<List<List<String>>> {
        val out = ArrayList<List<List<String>>>()
        val inCode = codeMask(lines)
        var i = 0
        while (i < lines.size) {
            if (!inCode[i] && isTableRow(lines[i]) &&
                i + 1 < lines.size && !inCode[i + 1] && isTableSeparator(lines[i + 1])
            ) {
                val rows = ArrayList<List<String>>()
                rows.add(splitCells(lines[i]))
                var j = i + 2
                while (j < lines.size && !inCode[j] && isTableRow(lines[j])) {
                    rows.add(splitCells(lines[j]))
                    j++
                }
                out.add(rows)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /**
     * Признак «строка принадлежит блоку кода» — включая сами заборы.
     *
     * Незакрытый забор в конце текста маскирует хвост до конца: лента тоже рисует его
     * кодом, и таблица внутри него таблицей не считается.
     */
    private fun codeMask(lines: List<String>): BooleanArray {
        val mask = BooleanArray(lines.size)
        var inside = false
        for (index in lines.indices) {
            if (isFence(lines[index])) {
                mask[index] = true
                inside = !inside
                continue
            }
            mask[index] = inside
        }
        return mask
    }

    /**
     * Блоки кода текста: для каждого — число строк внутри заборов.
     *
     * Незакрытый забор в конце текста тоже считается блоком: лента рисует такой хвост как
     * код (ответ ещё стримится), и панель не должна считать иначе. Пустые строки по краям
     * блока не считаются — они принадлежат разметке, а не листингу.
     */
    private fun codeBlockLineCounts(lines: List<String>): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < lines.size) {
            if (!isFence(lines[i])) {
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size && !isFence(lines[j])) j++
            var from = i + 1
            var to = j
            while (from < to && lines[from].isBlank()) from++
            while (to > from && lines[to - 1].isBlank()) to--
            out.add(to - from)
            i = if (j < lines.size) j + 1 else j
        }
        return out
    }

    /** Строка таблицы: непустая и содержит `|` — как `isTableRowLine` в [MarkdownFormatter]. */
    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() && trimmed.contains('|')
    }

    /** `|---|---|`, `:--- | ---:`, `|--|` — служебная строка markdown-таблицы. */
    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|') || !trimmed.contains('-')) return false
        val cells = splitCells(trimmed)
        return cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it) }
    }

    /** Ячейки строки таблицы: без внешних `|`, экранированный `\|` не режет строку. */
    private fun splitCells(line: String): List<String> {
        var trimmed = line.trim()
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        return trimmed.split(ESCAPED_PIPE).map { it.trim().replace("\\|", "|") }
    }

    /** Забор блока кода: строка, начинающаяся с ```` ``` ```` (язык после забора не важен). */
    private fun isFence(line: String): Boolean = line.trim().startsWith("```")

    // ─────────────────────────────────── заголовок ───────────────────────────────────

    /**
     * Убрать из строки markdown-синтаксис, оставив то, что человек читает.
     *
     * Порядок важен: ссылки и картинки снимаются до удаления `*`/`` ` ``, иначе адрес
     * остался бы висеть в заголовке, а маркер списка (`* пункт`) — до общей чистки `*`.
     */
    private fun cleanTitleLine(raw: String): String {
        var s = raw
        s = IMAGE_MD.replace(s, "$1")
        s = LINK_MD.replace(s, "$1")
        s = HEADER_MARK.replace(s, "")
        s = QUOTE_MARK.replace(s, "")
        s = BULLET_MARK.replace(s, "")
        s = NUMBER_MARK.replace(s, "")
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        s = s.replace("**", "").replace("~~", "").replace("`", "").replace("*", "")
        s = EMPHASIS_UNDERSCORE.replace(s, "$1")
        s = WHITESPACE_RUN.replace(s, " ")
        return s.trim()
    }

    /** Обрезка по символам с многоточием; режет по границе слова, если она рядом. */
    private fun truncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.substring(0, limit)
        val space = cut.lastIndexOf(' ')
        // Режем по слову, только если это не отнимает больше трети заголовка.
        val body = if (space >= limit / 2) cut.substring(0, space) else cut
        return body.trimEnd().trimEnd(',', '.', ';', ':', '—', '-') + "…"
    }

    /** Строки текста: `\r\n`, `\r` и `\n` — один и тот же перевод строки. */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }

    private val SEPARATOR_CELL = Regex("^:?-+:?$")
    private val ESCAPED_PIPE = Regex("(?<!\\\\)\\|")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val IMAGE_MD = Regex("!\\[([^\\]]*)\\]\\([^)\\s]*\\)")
    private val LINK_MD = Regex("\\[([^\\]]+)\\]\\([^)\\s]*\\)")
    private val HEADER_MARK = Regex("^#{1,6}\\s*")
    private val QUOTE_MARK = Regex("^>+\\s*")
    private val BULLET_MARK = Regex("^[-*+•]\\s+")
    private val NUMBER_MARK = Regex("^\\d{1,3}[.)]\\s+")
    private val DIVIDER = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val EMPHASIS_UNDERSCORE = Regex("(?<![\\p{L}\\p{N}_])_([^_\\n]+)_(?![\\p{L}\\p{N}_])")
}