package ru.mars.mobile

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сборка файлов DOCX/XLSX/TXT из текста прямо на телефоне.
 *
 * Модель не умеет возвращать файл — только текст, поэтому файл собирается здесь, из её
 * ответа. Зависимостей нет: OOXML — это обычный ZIP с XML-частями, и `java.util.zip`
 * достаточно. Никаких `android.*` в объекте нет, поэтому он проверяется обычным JVM-тестом.
 *
 * Что важно для валидности (иначе Word/Excel показывают «файл повреждён»):
 * - имена частей в ZIP — с прямыми слэшами и **без ведущего слэша** (`word/document.xml`,
 *   а не `/word/document.xml`);
 * - каждая часть — валидный XML в UTF-8, текст экранирован (`& < > " '`);
 * - `[Content_Types].xml` объявляет и сами части, и `rels`-расширение;
 * - `_rels/.rels` указывает на главную часть (`word/document.xml` / `xl/workbook.xml`);
 * - в DOCX последний элемент `w:body` — `w:sectPr` (параметры страницы), иначе часть
 *   читалок считает документ неполным.
 *
 * Кодировка имён в ZIP задаётся явно (UTF-8) — иначе часть архиваторов пишет имена в
 * CP437, и не-ASCII имена частей ломаются.
 *
 * Соответствие сборки и разбора: [DocumentTextExtractor] читает ровно те конструкции,
 * которые пишет этот объект (`w:t`/`w:tab`/`w:p`, `t="inlineStr"`/`<v>`), поэтому пара
 * «собрал → прочитал» даёт исходный текст — это и проверяется круговым тестом.
 */
object DocumentBuilder {

    /** MIME собранного DOCX — для `ActivityResultContracts.CreateDocument`. */
    const val DOCX_MIME: String =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** MIME собранного XLSX. */
    const val XLSX_MIME: String =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** MIME обычного текста. */
    const val TXT_MIME: String = "text/plain"

    /** Предел листа Excel: больше строк файл всё равно не откроется. */
    const val MAX_ROWS: Int = 1_048_576

    /** Предел колонок Excel. */
    const val MAX_COLUMNS: Int = 16_384

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val SML_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val OFFICE_DOCUMENT_REL = "$R_NS/officeDocument"
    private const val WORKSHEET_REL = "$R_NS/worksheet"

    private const val RELS_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.relationships+xml"
    private const val XML_CONTENT_TYPE = "application/xml"
    private const val DOCX_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val XLSX_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val XLSX_SHEET_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"

    /** Фиксированная дата в ZIP (1980-01-01): одинаковый текст — одинаковые байты. */
    private const val FIXED_TIME: Long = 315_532_800_000L

    /** Имя листа по умолчанию: Excel пишет так же для русской локали. */
    const val DEFAULT_SHEET_NAME: String = "Лист1"

    // ─────────────────────────────────── DOCX ───────────────────────────────────

    /**
     * Собрать минимальный корректный DOCX из обычного текста.
     *
     * Каждая строка — отдельный абзац (`w:p`), табуляция внутри строки — `w:tab`, поэтому
     * документ читается обратно тем же текстом (см. [DocumentTextExtractor]).
     * Пустой текст даёт валидный документ с одним пустым абзацем, а не исключение.
     *
     * @param text текст ответа модели; `\r\n` и `\r` считаются переводом строки.
     */
    fun buildDocx(text: String): ByteArray {
        val body = StringBuilder()
        for (line in splitLines(text)) body.append(docxParagraph(line))
        // sectPr обязателен и должен быть последним элементом тела.
        body.append(
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                "<w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1707\" " +
                "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>",
        )
        val document = "$XML_DECL\n" +
            "<w:document xmlns:w=\"$W_NS\"><w:body>$body</w:body></w:document>"

        return zip(
            "[Content_Types].xml" to DOCX_CONTENT_TYPES,
            "_rels/.rels" to rootRels("word/document.xml", OFFICE_DOCUMENT_REL),
            "word/document.xml" to document,
            // Пустые связи части документа: Word ждёт файл, даже если связей нет.
            "word/_rels/document.xml.rels" to
                "$XML_DECL\n<Relationships xmlns=\"$REL_NS\"></Relationships>",
        )
    }

    private val DOCX_CONTENT_TYPES: String = "$XML_DECL\n" +
        "<Types xmlns=\"$CT_NS\">" +
        "<Default Extension=\"rels\" ContentType=\"$RELS_CONTENT_TYPE\"/>" +
        "<Default Extension=\"xml\" ContentType=\"$XML_CONTENT_TYPE\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"$DOCX_MAIN_CONTENT_TYPE\"/>" +
        "</Types>"

    /**
     * Один абзац. Пустая строка — пустой `w:p` (а не абзац с пустым `w:t`: так Word
     * показывает именно пустую строку, без «висящего» run).
     */
    private fun docxParagraph(line: String): String {
        val clean = sanitize(line)
        if (clean.isEmpty()) return "<w:p/>"
        val run = StringBuilder("<w:p><w:r>")
        val pieces = clean.split('\t')
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) run.append("<w:tab/>")
            if (piece.isEmpty()) continue
            // xml:space="preserve" — иначе Word съедает значимые пробелы внутри абзаца.
            run.append("<w:t xml:space=\"preserve\">").append(escapeXml(piece)).append("</w:t>")
        }
        run.append("</w:r></w:p>")
        return run.toString()
    }

    // ─────────────────────────────────── XLSX ───────────────────────────────────

    /**
     * Собрать минимальный XLSX с одним листом ([DEFAULT_SHEET_NAME]).
     *
     * Числовые ячейки пишутся как `<v>` без атрибута `t` (Excel покажет их числом и
     * посчитает формулой), всё остальное — inline-строками `t="inlineStr"`. Строки без
     * ведущих нулей и без разделителей разрядов считаются числами; `007` и `+7 (999)`
     * остаются текстом — это телефоны и коды, а не арифметика.
     *
     * Пустой список строк даёт валидный файл с пустым `sheetData`.
     *
     * @param rows строки таблицы; каждая — список значений ячеек.
     */
    fun buildXlsx(rows: List<List<String>>): ByteArray {
        require(rows.size <= MAX_ROWS) { "слишком много строк: ${rows.size} (предел Excel — $MAX_ROWS)" }
        val sheetData = StringBuilder()
        for ((rowIndex, row) in rows.withIndex()) {
            require(row.size <= MAX_COLUMNS) {
                "слишком много колонок в строке ${rowIndex + 1}: ${row.size} (предел Excel — $MAX_COLUMNS)"
            }
            val number = rowIndex + 1
            if (row.isEmpty()) {
                sheetData.append("<row r=\"$number\"/>")
                continue
            }
            sheetData.append("<row r=\"$number\">")
            for ((columnIndex, value) in row.withIndex()) {
                sheetData.append(xlsxCell(columnName(columnIndex) + number, value))
            }
            sheetData.append("</row>")
        }

        val sheet = "$XML_DECL\n" +
            "<worksheet xmlns=\"$SML_NS\" xmlns:r=\"$R_NS\">" +
            "<sheetData>$sheetData</sheetData></worksheet>"
        val workbook = "$XML_DECL\n" +
            "<workbook xmlns=\"$SML_NS\" xmlns:r=\"$R_NS\"><sheets>" +
            "<sheet name=\"${escapeXml(DEFAULT_SHEET_NAME)}\" sheetId=\"1\" r:id=\"rId1\"/>" +
            "</sheets></workbook>"

        return zip(
            "[Content_Types].xml" to XLSX_CONTENT_TYPES,
            "_rels/.rels" to rootRels("xl/workbook.xml", OFFICE_DOCUMENT_REL),
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to
                "$XML_DECL\n<Relationships xmlns=\"$REL_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$WORKSHEET_REL\" " +
                "Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to sheet,
        )
    }

    private val XLSX_CONTENT_TYPES: String = "$XML_DECL\n" +
        "<Types xmlns=\"$CT_NS\">" +
        "<Default Extension=\"rels\" ContentType=\"$RELS_CONTENT_TYPE\"/>" +
        "<Default Extension=\"xml\" ContentType=\"$XML_CONTENT_TYPE\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$XLSX_MAIN_CONTENT_TYPE\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"$XLSX_SHEET_CONTENT_TYPE\"/>" +
        "</Types>"

    /** Одна ячейка: число — `<v>`, текст — inline-строка. */
    private fun xlsxCell(reference: String, value: String): String {
        val clean = sanitize(value)
        val numeric = normalizeNumber(clean)
        if (numeric != null) return "<c r=\"$reference\"><v>$numeric</v></c>"
        return "<c r=\"$reference\" t=\"inlineStr\"><is>" +
            "<t xml:space=\"preserve\">${escapeXml(clean)}</t></is></c>"
    }

    /**
     * Каноническая запись числа или `null`, если значение числом не считается.
     *
     * Намеренно узко: без экспоненты, без `NaN`/`Infinity` (их принимает `toDoubleOrNull`),
     * без ведущих нулей и плюса — «007» и «+7» это идентификаторы, и Excel не должен
     * превращать их в 7.
     */
    private fun normalizeNumber(value: String): String? {
        if (value.isEmpty() || value.length > 24) return null
        if (!NUMERIC_CELL.matches(value)) return null
        return value
    }

    private val NUMERIC_CELL = Regex("^-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,18})?$")

    /** `A`, `B`, … `Z`, `AA`, … — буквенная часть адреса ячейки. */
    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.append(('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.reverse().toString()
    }

    // ─────────────────────────────────── TXT ────────────────────────────────────

    /** Обычный текст в UTF-8 — без BOM: его не любят ни редакторы, ни парсеры. */
    fun buildTxt(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    // ───────────────────────────── текст → строки таблицы ─────────────────────────────

    /**
     * Разложить текст в строки таблицы для [buildXlsx].
     *
     * Порядок правил:
     * 1. есть табуляции — ячейки по `\t` (то, что отдаёт [DocumentTextExtractor] для XLSX
     *    и копирование из Excel);
     * 2. иначе есть `|` — markdown-таблица: ячейки по `|`, внешние `|` отброшены,
     *    строка-разделитель (`|---|---|`, `:--:`) выброшена;
     * 3. иначе — каждая непустая строка становится строкой из одной ячейки.
     *
     * Пустые строки и строки из одних пробелов не попадают в результат: пустые строки в
     * Excel выглядят как дыры в данных.
     */
    fun rowsFromText(text: String): List<List<String>> {
        val lines = splitLines(text).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val out = ArrayList<List<String>>(lines.size)
        when {
            lines.any { it.contains('\t') } -> for (line in lines) {
                out.add(line.split('\t').map { it.trim() })
            }

            lines.any { it.contains('|') } -> for (line in lines) {
                val cells = markdownCells(line)
                if (cells.isEmpty() || isSeparatorRow(cells)) continue
                out.add(cells)
            }

            else -> for (line in lines) out.add(listOf(line.trim()))
        }
        return out
    }

    /** Ячейки строки markdown-таблицы: без внешних `|`, значения обрезаны по краям. */
    private fun markdownCells(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.substring(0, body.length - 1)
        if (body.isBlank()) return emptyList()
        return body.split('|').map { it.trim() }
    }

    /** `|---|---|`, `|:--:|`, `|--|` — служебная строка markdown, в данные не идёт. */
    private fun isSeparatorRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it) }

    private val SEPARATOR_CELL = Regex("^:?-{2,}:?$")

    // ─────────────────────────────────── общее ───────────────────────────────────

    /**
     * ZIP из пар «имя части → текст». Имена — без ведущего слэша (иначе Word не находит
     * часть и ругается на повреждённый файл), содержимое — UTF-8.
     */
    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            for ((name, content) in parts) {
                val entryName = name.removePrefix("/")
                val entry = ZipEntry(entryName)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** `_rels/.rels` пакета: единственная связь на главную часть документа. */
    private fun rootRels(target: String, type: String): String = "$XML_DECL\n" +
        "<Relationships xmlns=\"$REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$type\" Target=\"$target\"/></Relationships>"

    /**
     * Убрать символы, которых не бывает в XML 1.0: управляющие (`\u0000`–`\u001F`, кроме
     * табуляции) и одиночные суррогаты. Иначе «валидный» файл не открывается вовсе.
     */
    private fun sanitize(text: String): String {
        var clean = text
        if (clean.indexOf('\r') >= 0) clean = clean.replace("\r\n", "\n").replace('\r', '\n')
        var needed = false
        for (ch in clean) {
            // Неперсонажи U+FFFE/U+FFFF — не управляющие символы, но в XML 1.0 запрещены:
            // без них проверка выше пропускала их сырыми, и собранный файл не открывался
            // («An invalid XML character (Unicode: 0xffff)»).
            if (ch == '\n' || (ch.code < 0x20 && ch != '\t') ||
                ch.code == 0xFFFE || ch.code == 0xFFFF
            ) {
                needed = true
                break
            }
        }
        if (!needed && !hasLoneSurrogate(clean)) return clean
        val sb = StringBuilder(clean.length)
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            when {
                ch == '\t' -> sb.append(ch)
                ch.code < 0x20 -> Unit
                ch.code == 0xFFFE || ch.code == 0xFFFF -> Unit
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < clean.length && Character.isLowSurrogate(clean[i + 1])) {
                        sb.append(ch).append(clean[i + 1])
                        i++
                    }
                }
                Character.isLowSurrogate(ch) -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
                    i++
                }
                Character.isLowSurrogate(ch) -> return true
            }
            i++
        }
        return false
    }

    /** Экранирование XML-энтити. `&` — первым, иначе получились бы `&amp;lt;`. */
    private fun escapeXml(text: String): String {
        var needsEscape = false
        for (ch in text) {
            if (ch == '&' || ch == '<' || ch == '>' || ch == '"' || ch == '\'') {
                needsEscape = true
                break
            }
        }
        if (!needsEscape) return text
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Строки текста: `\r\n`, `\r` и `\n` — один и тот же перевод строки. */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n')
    }
}
