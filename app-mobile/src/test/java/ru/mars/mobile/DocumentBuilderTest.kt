package ru.mars.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/**
 * Круговые тесты сборки документов ([DocumentBuilder]) без Android и без Gradle-плагинов Office.
 *
 * Смысл: собранный файл нельзя проверить «на глаз» — ошибка в структуре ZIP или в XML видна
 * только владельцу телефона, когда Word/Excel покажут «файл повреждён». Поэтому каждый тест
 * распаковывает байты обратно ([ZipInputStream] / [ZipFile]) и разбирает части штатным
 * JAXP-парсером: ZIP-структура, имена частей, объявления `[Content_Types].xml`, связи `_rels`,
 * экранирование текста и количество абзацев/ячеек.
 *
 * Дополнительно проверяется пара «собрал → прочитал»: [DocumentTextExtractor] читает ровно те
 * конструкции, которые пишет [DocumentBuilder] (`w:t`/`w:tab`/`w:p`, `t="inlineStr"`/`<v>`),
 * и расхождение между ними означало бы, что отправленный моделью документ вернётся другим
 * текстом.
 *
 * Парсер настроен безопасно (`disallow-doctype-decl`, внешние сущности запрещены): тесты не
 * должны ни ходить в сеть, ни падать на XXE-проверках JDK.
 */
class DocumentBuilderTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ─────────────────────────────── DOCX: структура пакета ───────────────────────────────

    @Test
    fun docxZipContainsExactlyRequiredParts() {
        val docx = DocumentBuilder.buildDocx("Привет")
        val names = unzipEntries(docx).map { it.first }

        assertEquals(
            "DOCX — это ровно четыре части; лишняя или пропавшая ломает открытие",
            listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels"),
            names,
        )
        // Ведущий слэш или обратный слэш в имени части — классическая причина «файл повреждён»:
        // Word ищет часть по имени и не находит её.
        names.forEach { name ->
            assertFalse("имя части не должно начинаться со слэша: $name", name.startsWith("/"))
            assertFalse("в ZIP-имени не должно быть обратных слэшей: $name", name.contains('\\'))
        }
    }

    @Test
    fun docxAllPartsAreWellFormedXmlAndDeclareMainPart() {
        val parts = unzip(DocumentBuilder.buildDocx("Текст"))

        parts.forEach { (name, bytes) -> assertNotNull("часть $name не разобралась как XML", parseXml(bytes)) }

        val types = parseXml(parts.getValue("[Content_Types].xml"))
        val overrides = elements(types, CT_NS, "Override")
        assertEquals(1, overrides.size)
        assertEquals("/word/document.xml", overrides[0].getAttribute("PartName"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            overrides[0].getAttribute("ContentType"),
        )
        val defaults = elements(types, CT_NS, "Default").map { it.getAttribute("Extension") }
        assertTrue("нет Default для rels — связи не откроются", defaults.contains("rels"))
        assertTrue("нет Default для xml", defaults.contains("xml"))

        val rels = parseXml(parts.getValue("_rels/.rels"))
        val root = elements(rels, REL_NS, "Relationship")
        assertEquals(1, root.size)
        assertEquals("word/document.xml", root[0].getAttribute("Target"))
        assertEquals(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
            root[0].getAttribute("Type"),
        )
    }

    @Test
    fun docxSurvivesRealZipCentralDirectory() {
        // ZipInputStream читает локальные заголовки; Word/Excel читают центральный каталог.
        // ZipFile проверяет именно его — и CRC при чтении части.
        val file = folder.newFile("проверка.docx")
        file.writeBytes(DocumentBuilder.buildDocx("Первый абзац\nВторой абзац"))

        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }.map { it.name }
            assertEquals(4, entries.size)
            zip.getInputStream(zip.getEntry("word/document.xml")).use { it.readBytes() }
            zip.getInputStream(zip.getEntry("[Content_Types].xml")).use { it.readBytes() }
        }
    }

    // ─────────────────────────────── DOCX: текст ───────────────────────────────

    @Test
    fun docxKeepsCyrillicAndXmlSpecialCharacters() {
        val line = "Цена < 100 & \"скидка\" > 0, не забудь 'кавычки'"
        val paragraphs = docxParagraphs(DocumentBuilder.buildDocx(line))

        assertEquals(listOf(line), paragraphs)
    }

    @Test
    fun docxMultiLineTextGivesOneParagraphPerLine() {
        val text = "Первая строка\nВторая строка\nТретья строка"
        val paragraphs = docxParagraphs(DocumentBuilder.buildDocx(text))

        assertEquals("строки не должны склеиваться в один абзац", 3, paragraphs.size)
        assertEquals(listOf("Первая строка", "Вторая строка", "Третья строка"), paragraphs)
    }

    @Test
    fun docxEscapesMarkupCharacters() {
        val text = "a & b < c > d \" e ' f"
        val raw = String(unzip(DocumentBuilder.buildDocx(text)).getValue("word/document.xml"), Charsets.UTF_8)

        assertNoRawAmpersand(raw)
        assertTrue("нет экранированного &amp;", raw.contains("&amp;"))
        assertTrue("нет экранированного &lt;", raw.contains("&lt;"))
        assertTrue("нет экранированного &gt;", raw.contains("&gt;"))
        assertTrue("нет экранированного &quot;", raw.contains("&quot;"))
        assertTrue("нет экранированного &apos;", raw.contains("&apos;"))
        // И обратное чтение даёт ровно исходную строку, а не «двойное экранирование».
        assertEquals(listOf(text), docxParagraphs(DocumentBuilder.buildDocx(text)))
    }

    @Test
    fun docxRoundTripsThroughDocumentTextExtractor() {
        val text = "Отчёт за март\nВыручка: 1 234 567 руб.\nПлан <факт & прогноз>"

        val extracted = DocumentTextExtractor.extract(
            DocumentBuilder.buildDocx(text),
            "отчёт.docx",
            DocumentBuilder.DOCX_MIME,
        )

        assertTrue("извлечение упало: ${extracted.exceptionOrNull()?.message}", extracted.isSuccess)
        assertEquals(text, extracted.getOrThrow())
    }

    @Test
    fun docxEmptyTextGivesValidDocumentWithSingleEmptyParagraph() {
        val docx = DocumentBuilder.buildDocx("")

        val paragraphs = docxParagraphs(docx)
        assertEquals(1, paragraphs.size)
        assertEquals("", paragraphs[0])
        // Пустой абзац — самозакрывающийся w:p, без «висящего» run.
        assertTrue(String(unzip(docx).getValue("word/document.xml"), Charsets.UTF_8).contains("<w:p/>"))
    }

    @Test
    fun docxNormalizesCrLfAndCr() {
        val paragraphs = docxParagraphs(DocumentBuilder.buildDocx("a\r\nb\rc\nd"))

        assertEquals(listOf("a", "b", "c", "d"), paragraphs)
    }

    @Test
    fun docxTabBecomesTabNodeAndSurvivesRoundTrip() {
        val text = "Колонка1\tКолонка2\tКолонка3"
        val docx = DocumentBuilder.buildDocx(text)

        val raw = String(unzip(docx).getValue("word/document.xml"), Charsets.UTF_8)
        assertEquals("две табуляции — два w:tab", 2, Regex("<w:tab/>").findAll(raw).count())
        assertEquals(listOf(text), docxParagraphs(docx))

        val extracted = DocumentTextExtractor.extract(docx, "т.docx", DocumentBuilder.DOCX_MIME)
        assertEquals(text, extracted.getOrThrow())
    }

    @Test
    fun docxSectPrIsLastElementOfBody() {
        val doc = parseXml(unzip(DocumentBuilder.buildDocx("x")).getValue("word/document.xml"))
        val body = elements(doc, W_NS, "body").single()

        val children = childElements(body)
        assertTrue("тело не должно быть пустым", children.isNotEmpty())
        val last = children.last()
        assertEquals("w:sectPr обязан быть последним — иначе часть читалок считает документ неполным", "sectPr", last.localName)
        // И он не абзац: абзацев ровно столько, сколько строк.
        assertEquals(1, elements(doc, W_NS, "p").size)
    }

    @Test
    fun docxStripsControlCharactersAndLoneSurrogates() {
        // Управляющие символы и одиночный суррогат в XML 1.0 недопустимы: файл «валиден»,
        // но не открывается вовсе. Строка должна остаться читаемой, а не упасть.
        val docx = DocumentBuilder.buildDocx("a\u0000b\u0007c\uD800d")

        val paragraphs = docxParagraphs(docx)
        assertEquals(listOf("abcd"), paragraphs)
    }

    /**
     * ФИКСИРУЕТ ДЕФЕКТ (не эталон): `sanitize` (DocumentBuilder.kt:317-347) вырезает только
     * управляющие `\u0000`–`\u001F` и одиночные суррогаты, но не символы-неперсонажи
     * U+FFFE/U+FFFF, которые XML 1.0 запрещает. Файл собирается «успешно», часть не разбирается,
     * Word/Excel показывают «файл повреждён». Дефект одинаков для DOCX и XLSX.
     * После исправления `sanitize` ассерты ниже переворачиваются: части обязаны парситься.
     */
    @Test
    fun docxDropsNoncharactersAndKeepsPartsParsable() {
        // Был дефект: sanitize вырезал управляющие \u0000–\u001F и одиночные суррогаты,
        // но пропускал неперсонажи U+FFFE/U+FFFF, запрещённые в XML 1.0. Файл собирался
        // «успешно», часть не разбиралась, Word/Excel показывали «файл повреждён».
        // Теперь такие символы вырезаются — обе части обязаны парситься.
        val text = "a\uFFFFb"
        val docxPart = unzip(DocumentBuilder.buildDocx(text)).getValue("word/document.xml")
        val xlsxPart = unzip(DocumentBuilder.buildXlsx(listOf(listOf(text)))).getValue("xl/worksheets/sheet1.xml")

        listOf(docxPart, xlsxPart).forEach { part ->
            val error = runCatching { parseXml(part) }.exceptionOrNull()
            assertNull("часть не разобралась — неперсонаж не вырезан: $error", error)
        }
    }

    @Test
    fun docxPreservesSignificantSpaces() {
        val docx = DocumentBuilder.buildDocx("   отступ   ")
        val raw = String(unzip(docx).getValue("word/document.xml"), Charsets.UTF_8)

        assertTrue("без xml:space=\"preserve\" Word съедает пробелы", raw.contains("xml:space=\"preserve\""))
        assertEquals(listOf("   отступ   "), docxParagraphs(docx))
    }

    @Test
    fun docxIsByteStableForSameText() {
        // Фиксированная дата в ZIP: одинаковый текст — одинаковые байты (иначе кэш и
        // сравнение вложений не работают, а тест на равенство становится flaky).
        val first = DocumentBuilder.buildDocx("Одинаковый текст\nвторая строка")
        val second = DocumentBuilder.buildDocx("Одинаковый текст\nвторая строка")

        assertArrayEquals(first, second)
        assertEquals(4, unzipEntries(first).size)
    }

    @Test
    fun docxTwoThousandLinesBuildsOpenableFile() {
        val text = (1..2000).joinToString("\n") { "Строка номер $it" }
        val docx = DocumentBuilder.buildDocx(text)

        assertEquals(2000, docxParagraphs(docx).size)
        assertTrue("файл подозрительно большой: ${docx.size} байт", docx.size < 200_000)
        assertTrue("файл подозрительно маленький: ${docx.size} байт", docx.size > 2_000)
        assertTrue(
            "ZIP обязан сжимать повторяющийся текст: ${docx.size} >= ${text.toByteArray(Charsets.UTF_8).size}",
            docx.size < text.toByteArray(Charsets.UTF_8).size,
        )
        // Центральный каталог тоже должен быть целым на большом вводе.
        val file = folder.newFile("большой.docx")
        file.writeBytes(docx)
        ZipFile(file).use { zip -> assertEquals(4, zip.size()) }
    }

    // ─────────────────────────────── XLSX ───────────────────────────────

    @Test
    fun xlsxZipContainsExactlyRequiredParts() {
        val xlsx = DocumentBuilder.buildXlsx(listOf(listOf("Товар", "Цена")))
        val names = unzipEntries(xlsx).map { it.first }

        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
            names,
        )
        names.forEach { assertFalse("имя части не должно начинаться со слэша: $it", it.startsWith("/")) }
    }

    @Test
    fun xlsxAllPartsAreWellFormedXmlAndDeclareSheet() {
        val parts = unzip(DocumentBuilder.buildXlsx(listOf(listOf("a"))))
        parts.forEach { (name, bytes) -> assertNotNull("часть $name не разобралась как XML", parseXml(bytes)) }

        val types = parseXml(parts.getValue("[Content_Types].xml"))
        val overrides = elements(types, CT_NS, "Override")
            .associate { it.getAttribute("PartName") to it.getAttribute("ContentType") }
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            overrides["/xl/workbook.xml"],
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            overrides["/xl/worksheets/sheet1.xml"],
        )

        val rootRels = elements(parseXml(parts.getValue("_rels/.rels")), REL_NS, "Relationship").single()
        assertEquals("xl/workbook.xml", rootRels.getAttribute("Target"))

        val sheetRel = elements(parseXml(parts.getValue("xl/_rels/workbook.xml.rels")), REL_NS, "Relationship").single()
        assertEquals("worksheets/sheet1.xml", sheetRel.getAttribute("Target"))
        assertEquals("rId1", sheetRel.getAttribute("Id"))

        val workbook = parseXml(parts.getValue("xl/workbook.xml"))
        val sheet = elements(workbook, SML_NS, "sheet").single()
        assertEquals("Лист1", sheet.getAttribute("name"))
        assertEquals("rId1", sheet.getAttribute("r:id"))
    }

    @Test
    fun xlsxGridShapeAndValuesMatchInput() {
        val rows = listOf(
            listOf("Товар", "Цена", "Кол-во"),
            listOf("Молоко", "42", "2"),
            listOf("Хлеб", "38.5", "1"),
        )
        val doc = parseXml(unzip(DocumentBuilder.buildXlsx(rows)).getValue("xl/worksheets/sheet1.xml"))

        assertEquals("число строк не совпало с входом", 3, elements(doc, SML_NS, "row").size)
        assertEquals("число ячеек не совпало с входом", 9, elements(doc, SML_NS, "c").size)

        val cells = cellMap(doc)
        assertEquals(listOf("A1", "B1", "C1"), rowCells(doc, 1).map { it.getAttribute("r") })
        assertEquals("Товар", cells["A1"])
        assertEquals("Молоко", cells["A2"])
        assertEquals("42", cells["B2"])
        assertEquals("38.5", cells["B3"])
        assertEquals("1", cells["C3"])
    }

    @Test
    fun xlsxEmptyCellsAndEmptyRowDoNotBreakFile() {
        val rows = listOf(
            listOf("a", "", "c"),
            emptyList(),
            listOf("d", "", ""),
        )
        val xlsx = DocumentBuilder.buildXlsx(rows)
        val doc = parseXml(unzip(xlsx).getValue("xl/worksheets/sheet1.xml"))

        assertEquals("пустая строка тоже занимает номер строки", 3, elements(doc, SML_NS, "row").size)
        val emptyRow = elements(doc, SML_NS, "row")[1]
        assertEquals("2", emptyRow.getAttribute("r"))
        assertEquals("у пустой строки не должно быть ячеек", 0, emptyRow.childNodes.length)

        val cells = cellMap(doc)
        assertEquals("a", cells["A1"])
        assertEquals("", cells["B1"])
        assertEquals("c", cells["C1"])
        assertEquals("d", cells["A3"])

        // И файл всё равно открывается как ZIP с целым каталогом.
        val file = folder.newFile("пустые.xlsx")
        file.writeBytes(xlsx)
        ZipFile(file).use { zip -> assertEquals(5, zip.size()) }
    }

    @Test
    fun xlsxNumbersAndIdentifiersAreWrittenDifferently() {
        val rows = listOf(listOf("42", "007", "+7 (999) 123-45-67", "-3.5", "1e5"))
        val doc = parseXml(unzip(DocumentBuilder.buildXlsx(rows)).getValue("xl/worksheets/sheet1.xml"))
        val cells = elements(doc, SML_NS, "c")

        // Число — <v> без атрибута t: Excel посчитает его формулой.
        assertEquals("", cells[0].getAttribute("t"))
        assertEquals("42", cells[0].textContent)
        assertEquals("-3.5", cells[3].textContent)
        // Телефон и код с ведущим нулём — текст: Excel не должен превратить «007» в 7.
        listOf(1, 2, 4).forEach { index ->
            assertEquals("ячейка ${cells[index].getAttribute("r")} должна быть текстом", "inlineStr", cells[index].getAttribute("t"))
        }
        assertEquals("007", cellMap(doc)["B1"])
        assertEquals("+7 (999) 123-45-67", cellMap(doc)["C1"])
        assertEquals("1e5", cellMap(doc)["E1"])
    }

    @Test
    fun xlsxRoundTripsThroughDocumentTextExtractor() {
        val rows = listOf(
            listOf("Товар", "Цена"),
            listOf("Молоко", "42"),
            listOf("Хлеб", "38.5"),
        )

        val extracted = DocumentTextExtractor.extract(
            DocumentBuilder.buildXlsx(rows),
            "смета.xlsx",
            DocumentBuilder.XLSX_MIME,
        )

        assertTrue("извлечение упало: ${extracted.exceptionOrNull()?.message}", extracted.isSuccess)
        assertEquals("Товар\tЦена\nМолоко\t42\nХлеб\t38.5", extracted.getOrThrow())
    }

    @Test
    fun xlsxColumnReferencesGoBeyondZ() {
        val rows = listOf(List(28) { "c$it" })
        val doc = parseXml(unzip(DocumentBuilder.buildXlsx(rows)).getValue("xl/worksheets/sheet1.xml"))

        val refs = rowCells(doc, 1).map { it.getAttribute("r") }
        assertEquals(28, refs.size)
        assertEquals("A1", refs[0])
        assertEquals("Z1", refs[25])
        assertEquals("AA1", refs[26])
        assertEquals("AB1", refs[27])
    }

    @Test
    fun xlsxEmptyInputGivesValidEmptySheet() {
        val xlsx = DocumentBuilder.buildXlsx(emptyList())
        val doc = parseXml(unzip(xlsx).getValue("xl/worksheets/sheet1.xml"))

        assertEquals(0, elements(doc, SML_NS, "row").size)
        assertNotNull(elements(doc, SML_NS, "sheetData").single())
        assertEquals(5, unzipEntries(xlsx).size)
    }

    @Test
    fun xlsxRejectsInputOverExcelLimits() {
        // Пределы проверяются до сборки: иначе файл соберётся и не откроется.
        assertThrows(IllegalArgumentException::class.java) {
            DocumentBuilder.buildXlsx(List(DocumentBuilder.MAX_ROWS + 1) { emptyList() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentBuilder.buildXlsx(listOf(List(DocumentBuilder.MAX_COLUMNS + 1) { "x" }))
        }
    }

    // ─────────────────────────────── TXT ───────────────────────────────

    @Test
    fun txtIsExactUtf8WithoutBom() {
        val text = "Привет, мир!\nВторая строка — тире, ё, 😀"
        val bytes = DocumentBuilder.buildTxt(text)

        assertArrayEquals(text.toByteArray(Charsets.UTF_8), bytes)
        assertEquals(text, String(bytes, Charsets.UTF_8))
        assertFalse("BOM не должен попадать в файл", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
    }

    @Test
    fun txtEmptyTextGivesEmptyArray() {
        assertEquals(0, DocumentBuilder.buildTxt("").size)
    }

    // ───────────────────────── текст модели → строки таблицы ─────────────────────────

    @Test
    fun rowsFromTextSplitsTsv() {
        val rows = DocumentBuilder.rowsFromText("Товар\tЦена\nМолоко\t42")

        assertEquals(listOf(listOf("Товар", "Цена"), listOf("Молоко", "42")), rows)
    }

    @Test
    fun rowsFromTextParsesMarkdownTableAndDropsSeparatorRow() {
        val text = """
            | Имя | Город |
            | --- | --- |
            | Иван | Москва |
            | Пётр | Тверь |
        """.trimIndent()

        val rows = DocumentBuilder.rowsFromText(text)

        assertEquals(
            listOf(listOf("Имя", "Город"), listOf("Иван", "Москва"), listOf("Пётр", "Тверь")),
            rows,
        )
    }

    @Test
    fun rowsFromTextKeepsCsvLineAsSingleCell() {
        // CSV по запятым намеренно не режется (документированное правило: таб → `|` → одна
        // ячейка). Важно, что строки не теряются и запятые не съедаются.
        val rows = DocumentBuilder.rowsFromText("a,b,c\nd,e,f")

        assertEquals(listOf(listOf("a,b,c"), listOf("d,e,f")), rows)
    }

    @Test
    fun rowsFromTextPlainTextGivesOneCellPerLineAndSkipsBlankLines() {
        val rows = DocumentBuilder.rowsFromText("  первый пункт  \n\n   \nвторой пункт")

        assertEquals(listOf(listOf("первый пункт"), listOf("второй пункт")), rows)
    }

    @Test
    fun rowsFromTextEmptyTextGivesEmptyList() {
        assertEquals(emptyList<List<String>>(), DocumentBuilder.rowsFromText(""))
        assertEquals(emptyList<List<String>>(), DocumentBuilder.rowsFromText("  \n\t\n"))
    }

    @Test
    fun rowsFromTextMixedTabsAndPlainLinesDoesNotCrash() {
        val rows = DocumentBuilder.rowsFromText("a\tb\nпросто строка")

        assertEquals(listOf(listOf("a", "b"), listOf("просто строка")), rows)
    }

    @Test
    fun rowsFromMarkdownThenXlsxRoundTrips() {
        val text = """
            | Имя | Город |
            | --- | --- |
            | Иван | Москва |
        """.trimIndent()

        val xlsx = DocumentBuilder.buildXlsx(DocumentBuilder.rowsFromText(text))
        val extracted = DocumentTextExtractor.extract(xlsx, "таблица.xlsx", DocumentBuilder.XLSX_MIME)

        assertEquals("Имя\tГород\nИван\tМосква", extracted.getOrThrow())
    }

    // ─────────────────────────────── помощники ───────────────────────────────

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = unzipEntries(bytes).toMap()

    /** Элементы ZIP по порядку; каталоги отбрасываются, но проверяются на «без слэшей» выше. */
    private fun unzipEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = zip.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                out.add(entry.name to buffer.toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isExpandEntityReferences = false
        // Без своего ErrorHandler парсер печатает «[Fatal Error] …» в stderr, и отказ выглядит
        // как шум сборки, а не как исключение теста.
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
        return builder.parse(ByteArrayInputStream(bytes))
    }

    /** Потомки узла (Document или Element) по namespace+localName, в порядке документа. */
    private fun elements(node: Node, namespace: String, localName: String): List<Element> {
        val list = when (node) {
            is Document -> node.getElementsByTagNameNS(namespace, localName)
            is Element -> node.getElementsByTagNameNS(namespace, localName)
            else -> return emptyList()
        }
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun childElements(node: Node): List<Element> {
        val out = ArrayList<Element>()
        for (i in 0 until node.childNodes.length) {
            val child = node.childNodes.item(i)
            if (child is Element) out.add(child)
        }
        return out
    }

    /** Текст абзацев DOCX: `w:t` как есть, `w:tab` — табуляцией. Независимо от экстрактора. */
    private fun docxParagraphs(docx: ByteArray): List<String> =
        elements(parseXml(unzip(docx).getValue("word/document.xml")), W_NS, "p").map { paragraph ->
            val sb = StringBuilder()
            collectDocxText(paragraph, sb)
            sb.toString()
        }

    private fun collectDocxText(node: Node, sb: StringBuilder) {
        for (i in 0 until node.childNodes.length) {
            val child = node.childNodes.item(i)
            when {
                child is Element && child.namespaceURI == W_NS && child.localName == "t" -> sb.append(child.textContent)
                child is Element && child.namespaceURI == W_NS && child.localName == "tab" -> sb.append('\t')
                else -> collectDocxText(child, sb)
            }
        }
    }

    private fun rowCells(doc: Document, rowNumber: Int): List<Element> {
        val row = elements(doc, SML_NS, "row").firstOrNull { it.getAttribute("r") == rowNumber.toString() }
        assertNotNull("нет строки $rowNumber", row)
        return childElements(row!!).filter { it.localName == "c" }
    }

    /** Адрес ячейки → значение: inline-строка из `is/t`, иначе содержимое `v`. */
    private fun cellMap(doc: Document): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (cell in elements(doc, SML_NS, "c")) {
            val ref = cell.getAttribute("r")
            val value = if (cell.getAttribute("t") == "inlineStr") {
                elements(cell, SML_NS, "t").joinToString("") { it.textContent }
            } else {
                elements(cell, SML_NS, "v").joinToString("") { it.textContent }
            }
            out[ref] = value
        }
        return out
    }

    /** Сырой `&` без имени сущности — так XML ломается, а файл «повреждается». */
    private fun assertNoRawAmpersand(xml: String) {
        val allowed = listOf("&amp;", "&lt;", "&gt;", "&quot;", "&apos;")
        var from = 0
        while (true) {
            val at = xml.indexOf('&', from)
            if (at < 0) return
            val ok = allowed.any { xml.startsWith(it, at) }
            assertTrue("сырой & без имени сущности на позиции $at: «${xml.substring(at, minOf(at + 12, xml.length))}»", ok)
            from = at + 1
        }
    }
}

private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val SML_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
