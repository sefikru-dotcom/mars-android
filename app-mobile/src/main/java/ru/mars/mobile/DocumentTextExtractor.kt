package ru.mars.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Локальное извлечение текста из документов — чистый JVM-код.
 *
 * Провайдер [OI]-совместимого формата не принимает произвольные бинарники, но текст
 * PDF/Word/Excel/PowerPoint можно вытащить на устройстве и отправить как текст. Здесь нет
 * ни `android.*`, ни новых зависимостей: только `java.util.zip` и регулярные выражения,
 * поэтому объект проверяется обычным JVM-тестом.
 *
 * Форматы:
 * - DOCX — ZIP, текст в `word/document.xml`, узлы `<w:t>`; `</w:p>`, `<w:br/>` и `<w:tab/>`
 *   дают переводы строк и табуляции;
 * - XLSX — ZIP: `xl/sharedStrings.xml` разрешается в значения ячеек листов
 *   (`xl/worksheets/sheet*.xml`), плюс inline-строки (`<is><t>`); строка таблицы — строка
 *   текста, ячейки — через табуляцию;
 * - PPTX — ZIP, `ppt/slides/slideN.xml`, узлы `<a:t>`, слайды по номеру;
 * - PDF — поиск `stream ... endstream`, распаковка FlateDecode через [Inflater], разбор
 *   операторов `Tj`/`TJ`/`'`/`"` и позиционирования `Td`/`TD`/`T*`/`Tm`. Коды CID-шрифтов
 *   (`/Identity-H`, так пишут почти все современные генераторы) разрешаются через
 *   `/ToUnicode`-CMap документа; без неё — эвристика UTF-16BE / windows-1252 / cp1251.
 *
 * Честный отказ вместо пустоты: скан без текстового слоя, нечитаемая кодировка шрифта,
 * пустой документ и незнакомый формат возвращают [Result.failure] с русской причиной —
 * вызывающий код показывает её пользователю.
 */
object DocumentTextExtractor {

    /** Предел извлечённого текста; всё сверх обрезается с пометкой [TRUNCATION_MARK]. */
    const val MAX_CHARS: Int = 200_000

    /** Пометка, добавляемая в конец обрезанного текста. */
    const val TRUNCATION_MARK: String = "\n[…обрезано]"

    /** Сообщение для скана без текстового слоя — самая частая причина отказа по PDF. */
    const val PDF_NO_TEXT_LAYER: String =
        "PDF без текстового слоя (вероятно, скан) — приложите фото страницы"

    /**
     * Текстовый слой есть, но шрифт без таблицы символов (`/ToUnicode`): коды глифов
     * нельзя превратить в буквы. Честный отказ лучше мусора в промпте.
     */
    const val PDF_UNREADABLE_ENCODING: String =
        "текст в PDF не читается (нестандартная кодировка шрифта) — приложите фото страницы"

    /** Сообщение для незнакомого формата. */
    const val UNSUPPORTED_FORMAT: String = "формат не поддержан"

    /** Потолок распакованного ZIP-энтри: защита от zip-бомб (документ в 200 КБ не бывает на 500 МБ). */
    private const val MAX_ENTRY_BYTES: Int = 24 * 1024 * 1024

    /** Потолок распакованного PDF-потока. */
    private const val MAX_INFLATE_BYTES: Int = 8 * 1024 * 1024

    /** Сколько потоков PDF просматривать — дальше это уже не документ, а мусор. */
    private const val MAX_PDF_STREAMS: Int = 4096

    /** Сколько символов PDF-потоков держать в памяти до обрезки. */
    private const val MAX_PDF_BUFFER: Int = MAX_CHARS * 8

    /** Сколько символов таблицы держать в памяти до обрезки. */
    private const val MAX_XLSX_BUFFER: Int = MAX_CHARS * 4

    /** Минимум букв/цифр, чтобы считать PDF текстовым (скан даёт 0). */
    private const val MIN_PDF_TEXT_CHARS: Int = 2

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val PDF_MIME = "application/pdf"

    /** Расширения старых бинарных Office — читать нечем, но и молчать нельзя. */
    private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "xls", "ppt", "rtf", "odt", "ods", "odp")

    private val PDF_CHARSET: Charset = runCatching { Charset.forName("windows-1252") }
        .getOrElse { Charsets.ISO_8859_1 }

    /** Кириллица для PDF без CMap: так пишут документы со встроенной 8-битной кодировкой. */
    private val CP1251: Charset = runCatching { Charset.forName("windows-1251") }
        .getOrElse { PDF_CHARSET }

    /** Латинские буквы с диакритикой — из них состоит «кракозябра» из cp1251. */
    private const val CP1252_ACCENTED: String =
        "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ"

    /** Символы, которых почти не бывает в человеческом тексте: признак разобранных кодов глифов. */
    private const val ODD_CHARS: String = "\\^`|~{}"

    /** Распознанный формат. */
    enum class Format { PDF, DOCX, XLSX, PPTX }

    /**
     * Извлечь текст документа.
     *
     * @param bytes содержимое файла.
     * @param fileName имя файла — по нему определяется формат (расширение важнее MIME:
     *   провайдеры файлов часто отдают `application/octet-stream`).
     * @param mimeType MIME от провайдера; может быть пустым или с параметрами.
     * @return текст не длиннее [MAX_CHARS] (+ [TRUNCATION_MARK] при обрезке) либо
     *   [Result.failure] с русской причиной.
     */
    fun extract(bytes: ByteArray, fileName: String, mimeType: String): Result<String> {
        if (bytes.isEmpty()) return failure("пустой файл — извлекать нечего")
        val format = detectFormat(fileName, mimeType, bytes)
            ?: return failure(unsupportedMessage(fileName, mimeType))

        val raw = try {
            when (format) {
                Format.DOCX -> extractDocx(bytes)
                Format.XLSX -> extractXlsx(bytes)
                Format.PPTX -> extractPptx(bytes)
                Format.PDF -> extractPdf(bytes)
            }
        } catch (e: Exception) {
            return failure("не удалось прочитать ${formatLabel(format)}: ${reasonOf(e)}")
        }

        val text = tidy(raw)
        if (text.isEmpty()) return failure(emptyMessage(format))
        if (format == Format.PDF) {
            if (meaningfulChars(text) < MIN_PDF_TEXT_CHARS) return failure(PDF_NO_TEXT_LAYER)
            if (looksUnreadable(text)) return failure(PDF_UNREADABLE_ENCODING)
        }
        return Result.success(limit(text))
    }

    /**
     * Поддерживается ли файл по имени и MIME — без чтения байтов.
     *
     * Нужен вызывающему коду, чтобы отличить «документ, который мы разберём» от картинки
     * и от типа, который провайдер не примет вообще.
     */
    fun isSupported(fileName: String, mimeType: String): Boolean =
        detectByName(fileName, mimeType) != null

    /** Формат по имени/MIME без чтения байтов; `null` — не документ из наших. */
    fun detectByName(fileName: String, mimeType: String): Format? {
        when (extensionOf(fileName)) {
            "pdf" -> return Format.PDF
            "docx" -> return Format.DOCX
            "xlsx" -> return Format.XLSX
            "pptx" -> return Format.PPTX
        }
        return when (normalizeMime(mimeType)) {
            PDF_MIME -> Format.PDF
            DOCX_MIME -> Format.DOCX
            XLSX_MIME -> Format.XLSX
            PPTX_MIME -> Format.PPTX
            else -> null
        }
    }

    // ───────────────────────────── определение формата ─────────────────────────────

    private fun detectFormat(fileName: String, mimeType: String, bytes: ByteArray): Format? =
        detectByName(fileName, mimeType) ?: sniff(bytes)

    /**
     * Запасное определение по содержимому: провайдер мог отдать файл без расширения и с
     * `application/octet-stream`. Ошибка здесь не страшна — вернём `null` и честный отказ.
     */
    private fun sniff(bytes: ByteArray): Format? {
        if (startsWith(bytes, "%PDF-".toByteArray(Charsets.ISO_8859_1))) return Format.PDF
        if (bytes.size > 4 && bytes[0].toInt() == 0x50 && bytes[1].toInt() == 0x4B) {
            val names = runCatching { zipEntryNames(bytes) }.getOrDefault(emptyList())
            return when {
                names.any { it.startsWith("word/") } -> Format.DOCX
                names.any { it.startsWith("xl/") } -> Format.XLSX
                names.any { it.startsWith("ppt/") } -> Format.PPTX
                else -> null
            }
        }
        return null
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && names.size < 256) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun unsupportedMessage(fileName: String, mimeType: String): String {
        val ext = extensionOf(fileName)
        if (ext in LEGACY_OFFICE_EXTENSIONS) {
            return "$UNSUPPORTED_FORMAT: старый формат .$ext не читается — " +
                "сохраните файл как .docx/.xlsx/.pptx или PDF"
        }
        val mime = normalizeMime(mimeType)
        val hint = if (ext.isEmpty() && mime.isEmpty()) "" else " (${if (ext.isEmpty()) mime else ".$ext"})"
        return "$UNSUPPORTED_FORMAT$hint — приложите PDF, DOCX, XLSX, PPTX, фото или .txt"
    }

    private fun emptyMessage(format: Format): String = when (format) {
        Format.PDF -> PDF_NO_TEXT_LAYER
        Format.DOCX -> "в документе Word нет текста (возможно, только картинки)"
        Format.XLSX -> "в таблице не найден текст — пустые листы или только числа без подписей"
        Format.PPTX -> "в презентации нет текста (возможно, только картинки)"
    }

    private fun formatLabel(format: Format): String = when (format) {
        Format.PDF -> "PDF"
        Format.DOCX -> "DOCX"
        Format.XLSX -> "XLSX"
        Format.PPTX -> "PPTX"
    }

    // ───────────────────────────── DOCX / XLSX / PPTX ─────────────────────────────

    /**
     * Узлы DOCX в порядке появления: текст `<w:t>`, конец абзаца `</w:p>`, разрыв строки,
     * табуляция, конец ячейки таблицы. Одна регулярка на всё — чтобы не потерять порядок
     * (иначе пришлось бы склеивать «сначала все тексты, потом все абзацы»).
     */
    private val DOCX_TOKEN = Regex(
        "<w:t(?:\\s[^>]*)?>(.*?)</w:t>|</w:p\\s*>|<w:br\\b[^>]*>|<w:cr\\b[^>]*>|<w:tab\\b[^>]*>|</w:tc\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val XLSX_CELL = Regex(
        "<c\\b([^>]*?)(?:/>|>(.*?)</c\\s*>)|</row\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val XLSX_SHARED_ITEM = Regex("<si\\b[^>]*>(.*?)</si\\s*>", setOf(RegexOption.DOT_MATCHES_ALL))

    private val XLSX_TEXT_NODE = Regex("<t(?:\\s[^>]*)?>(.*?)</t\\s*>", setOf(RegexOption.DOT_MATCHES_ALL))

    private val XLSX_VALUE_NODE = Regex("<v(?:\\s[^>]*)?>(.*?)</v\\s*>", setOf(RegexOption.DOT_MATCHES_ALL))

    private val XLSX_CELL_TYPE = Regex("""\bt="([^"]*)"""")

    private val SHEET_INDEX = Regex("^xl/worksheets/sheet(\\d+)\\.xml$")

    private val PPTX_TOKEN = Regex(
        "<a:t(?:\\s[^>]*)?>(.*?)</a:t>|</a:p\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val SLIDE_INDEX = Regex("^ppt/slides/slide(\\d+)\\.xml$")

    private fun extractDocx(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) { it == "word/document.xml" || it == "word/footnotes.xml" }
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        for (entry in entries) {
            if (entry.name == "word/document.xml") sb.append(docxTokens(entry.text()))
        }
        return sb.toString()
    }

    private fun docxTokens(xml: String): String {
        val sb = StringBuilder()
        for (match in DOCX_TOKEN.findAll(xml)) {
            val token = match.value
            // Порядок важен: `<w:tab/>` тоже начинается с `<w:t`, и общая ветка съела бы его.
            when {
                token.startsWith("<w:tab") -> sb.append('\t')
                token.startsWith("<w:br") || token.startsWith("<w:cr") -> sb.append('\n')
                token.startsWith("</w:p") -> sb.append('\n')
                token.startsWith("</w:tc") -> sb.append('\t')
                token.startsWith("<w:t") -> sb.append(decodeXmlEntities(match.groupValues[1]))
            }
        }
        return sb.toString()
    }

    /**
     * XLSX: общая таблица строк (`sharedStrings.xml`) разрешается в значения ячеек листов —
     * так текст идёт в порядке чтения (строка за строкой, ячейки через табуляцию), а не
     * кучей. Числа тоже берутся из `<v>`: в смете цена — это содержимое, без него таблица
     * бессмысленна.
     *
     * Если ячеек разобрать не удалось (нестандартная запись), остаётся запасной путь —
     * все строки из `sharedStrings.xml` подряд.
     */
    private fun extractXlsx(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
        }
        if (entries.isEmpty()) return ""
        val shared = entries.firstOrNull { it.name == "xl/sharedStrings.xml" }
            ?.let { parseSharedStrings(it.text()) }
            .orEmpty()
        val sheets = entries
            .filter { it.name != "xl/sharedStrings.xml" }
            .sortedBy { SHEET_INDEX.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
        val sb = StringBuilder()
        for (sheet in sheets) {
            val text = sheetCells(sheet.text(), shared)
            if (text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            if (sb.length >= MAX_XLSX_BUFFER) break
        }
        if (sb.isBlank()) return shared.joinToString("\n")
        return sb.toString()
    }

    /** Общие строки: каждый `<si>` — одна запись, внутри может быть несколько `<t>` (runs). */
    private fun parseSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        for (item in XLSX_SHARED_ITEM.findAll(xml)) {
            val sb = StringBuilder()
            for (t in XLSX_TEXT_NODE.findAll(item.groupValues[1])) {
                sb.append(decodeXmlEntities(t.groupValues[1]))
            }
            out.add(sb.toString())
        }
        return out
    }

    /** Ячейки одного листа: значение из sharedStrings, inline-строки или сырое `<v>`. */
    private fun sheetCells(xml: String, shared: List<String>): String {
        val sb = StringBuilder()
        var rowHasCells = false
        for (match in XLSX_CELL.findAll(xml)) {
            val token = match.value
            if (token.startsWith("</row")) {
                if (rowHasCells) {
                    sb.setLength(sb.length - 1) // убрать хвостовую табуляцию строки
                    sb.append('\n')
                }
                rowHasCells = false
                continue
            }
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val type = XLSX_CELL_TYPE.find(attrs)?.groupValues?.get(1).orEmpty()
            val value = when (type) {
                "s" -> XLSX_VALUE_NODE.find(body)?.groupValues?.get(1)
                    ?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                "inlineStr" -> XLSX_TEXT_NODE.findAll(body)
                    .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                "e" -> "" // #REF!/#N/A — это не текст документа
                else -> XLSX_VALUE_NODE.find(body)?.let { decodeXmlEntities(it.groupValues[1]) }.orEmpty()
            }
            if (value.isEmpty()) continue
            sb.append(sanitizeLine(value)).append('\t')
            rowHasCells = true
            if (sb.length >= MAX_XLSX_BUFFER) break
        }
        return sb.toString()
    }

    /** PPTX: слайды по номеру в имени файла, а не в порядке архива (порядок не гарантирован). */
    private fun extractPptx(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) { SLIDE_INDEX.matches(it) }
        if (entries.isEmpty()) return ""
        val slides = entries
            .map { entry -> (SLIDE_INDEX.find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0) to entry }
            .sortedBy { it.first }
        val sb = StringBuilder()
        for ((_, entry) in slides) {
            val text = pptxTokens(entry.text())
            if (text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(text)
        }
        return sb.toString()
    }

    private fun pptxTokens(xml: String): String {
        val sb = StringBuilder()
        for (match in PPTX_TOKEN.findAll(xml)) {
            val token = match.value
            when {
                token.startsWith("<a:t") -> sb.append(decodeXmlEntities(match.groupValues[1]))
                token.startsWith("</a:p") -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]+);")

    /**
     * Раскодировать XML-энтити. `&amp;` — последним, иначе `&amp;lt;` превратился бы в `<`.
     * Числовые (`&#1055;`, `&#x41F;`) тоже встречаются в Word — их разбираем отдельно.
     */
    private fun decodeXmlEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        var result = text
        if (result.contains("&#")) {
            result = NUMERIC_ENTITY.replace(result) { match ->
                val radix = if (match.groupValues[1].isEmpty()) 10 else 16
                val code = match.groupValues[2].toIntOrNull(radix)
                if (code != null && code in 0x1..0x10FFFF) {
                    String(Character.toChars(code))
                } else {
                    match.value
                }
            }
        }
        return result
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    /** Один распакованный элемент ZIP. */
    private class ZipEntryData(val name: String, val data: ByteArray) {
        fun text(): String = String(data, Charsets.UTF_8)
    }

    /**
     * Прочитать только нужные элементы ZIP: остальные пропускаются без распаковки в память.
     * Размер каждого элемента ограничен [MAX_ENTRY_BYTES].
     */
    private fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): List<ZipEntryData> {
        val out = ArrayList<ZipEntryData>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && wanted(entry.name)) {
                    out.add(ZipEntryData(entry.name, readCapped(zip, MAX_ENTRY_BYTES)))
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(chunk)
            if (read <= 0) break
            out.write(chunk, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    // ─────────────────────────────────── PDF ───────────────────────────────────

    /**
     * PDF: пройти по всем `stream ... endstream`, распаковать FlateDecode (при неудаче —
     * попробовать поток как есть: бывают несжатые), разобрать текстовые операторы.
     *
     * Если в документе есть `/ToUnicode`-CMap (так пишут все современные генераторы —
     * шрифт `/Identity-H` с двухбайтовыми кодами глифов), коды показываемых строк
     * разрешаются через него: иначе из русского PDF получался бы мусор из латинских
     * диакритик вместо букв.
     *
     * Ограничения честно оставлены снаружи: если текст не извлёкся, [extract] вернёт
     * [PDF_NO_TEXT_LAYER] — это лучше, чем пустая строка в промпте.
     */
    private fun extractPdf(bytes: ByteArray): String {
        val fonts = runCatching { parsePdfFonts(bytes) }.getOrDefault(PdfFonts.EMPTY)
        val sb = StringBuilder()
        var cursor = 0
        var streams = 0
        while (cursor < bytes.size && streams < MAX_PDF_STREAMS) {
            val streamAt = indexOf(bytes, STREAM_KEYWORD, cursor) ?: break
            // «endstream» содержит «stream» на смещении 3 — отсекаем по букве перед словом.
            if (streamAt > 0 && isPdfRegular(bytes[streamAt - 1].toInt() and 0xFF)) {
                cursor = streamAt + STREAM_KEYWORD.size
                continue
            }
            streams++
            var dataStart = streamAt + STREAM_KEYWORD.size
            if (dataStart < bytes.size && bytes[dataStart].toInt() == 0x0D) dataStart++
            if (dataStart < bytes.size && bytes[dataStart].toInt() == 0x0A) dataStart++

            val endAt = indexOf(bytes, ENDSTREAM_KEYWORD, dataStart)
            val dataEnd = if (endAt == null) bytes.size else trimEol(bytes, dataStart, endAt)
            val next = if (endAt == null) bytes.size else endAt + ENDSTREAM_KEYWORD.size

            // Картинки пропускаем не распаковывая: в PDF с фотографиями это 90% объёма
            // потоков, и распаковка каждой ради проверки «а вдруг там текст» — секунды.
            if (isImageStream(bytes, streamAt)) {
                cursor = next
                continue
            }
            if (dataEnd > dataStart && sb.length < MAX_PDF_BUFFER) {
                val inflated = inflate(bytes, dataStart, dataEnd - dataStart)
                val payload = inflated ?: bytes.copyOfRange(dataStart, dataEnd)
                if (looksLikeContentStream(payload)) {
                    sb.append(parsePdfContent(payload, fonts)).append('\n')
                }
            }
            cursor = next
        }
        return sb.toString()
    }

    private val STREAM_KEYWORD = "stream".toByteArray(Charsets.ISO_8859_1)
    private val ENDSTREAM_KEYWORD = "endstream".toByteArray(Charsets.ISO_8859_1)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || from < 0) return null
        val last = haystack.size - needle.size
        var i = from
        while (i <= last) {
            var match = true
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
                j++
            }
            if (match) return i
            i++
        }
        return null
    }

    /**
     * Похож ли поток на картинку — по словарю перед `stream`. Проверяем только хвост
     * словаря (900 байт), чтобы не читать файл целиком.
     */
    private fun isImageStream(bytes: ByteArray, streamAt: Int): Boolean {
        val from = maxOf(0, streamAt - 900)
        if (streamAt <= from) return false
        val head = String(bytes, from, streamAt - from, Charsets.ISO_8859_1)
        // Именно `/Subtype /Image`, а не подстрока `/Image`: в `/ProcSet [/PDF /Text
        // /ImageB /ImageC]` она тоже встречается — и так пропускались потоки текста.
        return PDF_IMAGE_SUBTYPE.containsMatchIn(head) ||
            head.contains("/DCTDecode") ||
            head.contains("/JPXDecode") ||
            head.contains("/CCITTFaxDecode")
    }

    private val PDF_IMAGE_SUBTYPE = Regex("/Subtype\\s*/Image\\b")

    private fun trimEol(bytes: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start && (bytes[e - 1].toInt() == 0x0A || bytes[e - 1].toInt() == 0x0D)) e--
        return e
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    /**
     * Распаковать FlateDecode. PDF пишет zlib-заголовок, но встречаются и «сырые» потоки —
     * поэтому вторая попытка с `nowrap`.
     */
    private fun inflate(data: ByteArray, offset: Int, length: Int): ByteArray? {
        for (nowrap in booleanArrayOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(data, offset, length)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var stall = 0
                while (!inflater.finished() && out.size() < MAX_INFLATE_BYTES) {
                    val produced = try {
                        inflater.inflate(buffer)
                    } catch (e: Exception) {
                        -1
                    }
                    if (produced < 0) break
                    if (produced == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        stall++
                        if (stall > 8) break
                        continue
                    }
                    stall = 0
                    out.write(buffer, 0, produced)
                }
                if (out.size() > 0) return out.toByteArray()
            } catch (e: Exception) {
                // Пробуем следующий вариант.
            } finally {
                inflater.end()
            }
        }
        return null
    }

    /** Похоже ли на поток содержимого страницы: там есть операторы показа текста. */
    private fun looksLikeContentStream(data: ByteArray): Boolean {
        if (data.size < 3) return false
        val head = String(data, 0, minOf(data.size, 4096), Charsets.ISO_8859_1)
        return head.contains("Tj") || head.contains("TJ") || head.contains("Td") || head.contains("BT")
    }

    // ───────────── PDF: /ToUnicode-CMap (Identity-H и прочие CID-шрифты) ─────────────

    /**
     * Карта «код глифа → Unicode» одного шрифта.
     *
     * @param codeBytes ширина кода в байтах (2 у `/Identity-H`, 1 у простых шрифтов).
     */
    private class PdfFontMap(val codeBytes: Int, private val map: Map<Int, String>) {
        fun decode(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size)
            var i = 0
            while (i + codeBytes <= bytes.size) {
                var code = 0
                var k = 0
                while (k < codeBytes) {
                    code = (code shl 8) or (bytes[i + k].toInt() and 0xFF)
                    k++
                }
                val mapped = map[code]
                if (mapped != null) {
                    sb.append(mapped)
                } else if (codeBytes == 1 && code >= 0x20) {
                    // Однобайтовый шрифт без записи в карте — берём как есть.
                    sb.append(code.toChar())
                }
                i += codeBytes
            }
            return sb.toString()
        }
    }

    /** Имена ресурсов (`/F1`) → карты шрифтов. */
    private class PdfFonts(
        private val byName: Map<String, PdfFontMap>,
        private val single: PdfFontMap?,
    ) {
        fun resolve(name: String?): PdfFontMap? {
            val direct = name?.let { byName[it] }
            // Имя не нашлось (Resources на странице ссылается на другой словарь) — если в
            // документе ровно один шрифт с CMap, почти наверняка это он.
            return direct ?: single
        }

        companion object {
            val EMPTY = PdfFonts(emptyMap(), null)
        }
    }

    // Ограниченный `[^<>]{0,8000}` вместо `(.*?)`: на 2 МБ файле ленивый захват до
    // ближайшего `>>` уходил в квадратичный перебор и стоил секунды.
    private val PDF_FONT_RESOURCE = Regex("/Font\\s*<<([^<>]{0,8000})>>")
    private val PDF_NAME_REF = Regex("/([A-Za-z0-9#._+-]+)\\s+(\\d+)\\s+0\\s+R")
    private val PDF_TO_UNICODE = Regex("/ToUnicode\\s+(\\d+)\\s+0\\s+R")
    private val PDF_BFCHAR = Regex("beginbfchar(.*?)endbfchar", setOf(RegexOption.DOT_MATCHES_ALL))
    private val PDF_BFRANGE = Regex("beginbfrange(.*?)endbfrange", setOf(RegexOption.DOT_MATCHES_ALL))
    private val PDF_BFCHAR_PAIR = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
    private val PDF_BFRANGE_TRIPLE = Regex(
        "<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(?:<([0-9A-Fa-f]+)>|\\[([^\\]]*)\\])",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Собрать карты шрифтов документа: `/Font << /F1 67 0 R >>` даёт имя ресурса → объект
     * шрифта, а `/ToUnicode 72 0 R` в объекте шрифта — поток CMap.
     *
     * Всё делается регулярками по сырому файлу: полноценный разбор xref здесь не нужен,
     * а сжатые объектные потоки (`/ObjStm`) встречаются в основном у генераторов, которые
     * и так пишут `/ToUnicode` обычным объектом.
     */
    private fun parsePdfFonts(bytes: ByteArray): PdfFonts {
        val hasToUnicode = indexOf(bytes, TO_UNICODE_KEYWORD, 0) != null
        if (!hasToUnicode) return PdfFonts.EMPTY
        val text = String(bytes, Charsets.ISO_8859_1)

        // Идём от `/ToUnicode`, а не от объектов: поиск «N 0 obj» регуляркой по всему
        // файлу на 2 МБ PDF стоил бы секунды, а назад от ссылки — шесть шагов.
        val cmapByObject = HashMap<Int, PdfFontMap>()
        for (ref in PDF_TO_UNICODE.findAll(text)) {
            val cmapRef = ref.groupValues[1].toIntOrNull() ?: continue
            val fontObj = objectNumberBefore(text, ref.range.first) ?: continue
            if (cmapByObject.containsKey(fontObj)) continue
            val cmapText = readObjectStream(text, cmapRef) ?: continue
            val parsed = parseCMap(cmapText) ?: continue
            cmapByObject[fontObj] = parsed
        }
        if (cmapByObject.isEmpty()) return PdfFonts.EMPTY

        val byName = HashMap<String, PdfFontMap>()
        for (resources in PDF_FONT_RESOURCE.findAll(text)) {
            for (ref in PDF_NAME_REF.findAll(resources.groupValues[1])) {
                val name = ref.groupValues[1]
                val objNum = ref.groupValues[2].toIntOrNull() ?: continue
                val map = cmapByObject[objNum] ?: continue
                if (!byName.containsKey(name)) byName[name] = map
            }
        }
        val distinct = byName.values.distinct()
        val single = if (distinct.size == 1) distinct.first() else null
        return PdfFonts(byName, single)
    }

    /** Позиция заголовка `num 0 obj` — обычный поиск подстроки, без регулярки на весь файл. */
    private fun indexOfObjectHeader(text: String, num: Int): Int? {
        val needle = "$num 0 obj"
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            if (at == 0 || !text[at - 1].isDigit()) return at
            from = at + needle.length
        }
    }

    /** Номер объекта, внутри которого стоит позиция [index] (`67 0 obj` → 67). */
    private fun objectNumberBefore(text: String, index: Int): Int? {
        val objAt = text.lastIndexOf(" obj", index)
        if (objAt < 0) return null
        var i = objAt - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        val genEnd = i + 1
        while (i >= 0 && text[i].isDigit()) i-- // поколение («0»)
        if (genEnd == i + 1) return null
        while (i >= 0 && text[i].isWhitespace()) i--
        val numEnd = i + 1
        while (i >= 0 && text[i].isDigit()) i--
        if (numEnd == i + 1) return null
        return text.substring(i + 1, numEnd).toIntOrNull()
    }

    /**
     * Тело потока объекта `num 0 obj` — распакованное, если внутри FlateDecode.
     *
     * @param text весь файл как ISO-8859-1: строку строим один раз на документ, а не на
     *   каждый шрифт (иначе 2 МБ PDF превращались бы в 2 МБ × число шрифтов).
     */
    private fun readObjectStream(text: String, num: Int): String? {
        val objAt = indexOfObjectHeader(text, num) ?: return null
        val streamAt = text.indexOf("stream", objAt)
        if (streamAt < 0) return null
        var dataStart = streamAt + 6
        if (dataStart < text.length && text[dataStart] == '\r') dataStart++
        if (dataStart < text.length && text[dataStart] == '\n') dataStart++
        val endAt = text.indexOf("endstream", dataStart)
        if (endAt < 0) return null
        var dataEnd = endAt
        while (dataEnd > dataStart && (text[dataEnd - 1] == '\n' || text[dataEnd - 1] == '\r')) dataEnd--
        val raw = text.substring(dataStart, dataEnd).toByteArray(Charsets.ISO_8859_1)
        val inflated = inflate(raw, 0, raw.size)
        return if (inflated != null) String(inflated, Charsets.ISO_8859_1) else String(raw, Charsets.ISO_8859_1)
    }

    /** Разобрать CMap: `beginbfchar`/`beginbfrange` (объявления `begincodespacerange` не нужны). */
    private fun parseCMap(cmap: String): PdfFontMap? {
        val map = HashMap<Int, String>()
        var codeBytes = 0
        for (block in PDF_BFCHAR.findAll(cmap)) {
            for (pair in PDF_BFCHAR_PAIR.findAll(block.groupValues[1])) {
                val codeHex = pair.groupValues[1]
                val dstHex = pair.groupValues[2]
                val code = codeHex.toIntOrNull(16) ?: continue
                map[code] = hexToUnicode(dstHex) ?: continue
                if (codeBytes == 0) codeBytes = codeHex.length / 2
            }
        }
        for (block in PDF_BFRANGE.findAll(cmap)) {
            for (triple in PDF_BFRANGE_TRIPLE.findAll(block.groupValues[1])) {
                val loHex = triple.groupValues[1]
                val hiHex = triple.groupValues[2]
                val lo = loHex.toIntOrNull(16) ?: continue
                val hi = hiHex.toIntOrNull(16) ?: continue
                if (hi < lo || hi - lo > 65_535) continue
                if (codeBytes == 0) codeBytes = loHex.length / 2
                val single = triple.groupValues[3]
                if (single.isNotEmpty()) {
                    val base = hexToUnicode(single) ?: continue
                    var offset = 0
                    while (lo + offset <= hi) {
                        map[lo + offset] = bumpLastChar(base, offset)
                        offset++
                    }
                } else {
                    val list = triple.groupValues[4]
                    var index = 0
                    for (item in Regex("<([0-9A-Fa-f]+)>").findAll(list)) {
                        val value = hexToUnicode(item.groupValues[1])
                        if (value != null) map[lo + index] = value
                        index++
                    }
                }
            }
        }
        if (map.isEmpty()) return null
        return PdfFontMap(if (codeBytes <= 0) 2 else codeBytes, map)
    }

    /** Приращение последнего символа — форма `<lo> <hi> <base>` в bfrange. */
    private fun bumpLastChar(base: String, offset: Int): String {
        if (offset == 0 || base.isEmpty()) return base
        val last = base[base.length - 1]
        return base.substring(0, base.length - 1) + (last.code + offset).toChar()
    }

    /** `<0410>` → «А»; длина в hex-цифрах задаёт UTF-16BE или однобайтовый код. */
    private fun hexToUnicode(hex: String): String? {
        if (hex.isEmpty()) return null
        val padded = if (hex.length % 2 == 1) "0$hex" else hex
        val bytes = ByteArray(padded.length / 2)
        var i = 0
        while (i < bytes.size) {
            val hi = Character.digit(padded[i * 2], 16)
            val lo = Character.digit(padded[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return when {
            bytes.size >= 2 -> String(bytes, Charsets.UTF_16BE).trimEnd('\u0000')
            else -> (bytes[0].toInt() and 0xFF).toChar().toString()
        }
    }

    private val TO_UNICODE_KEYWORD = "/ToUnicode".toByteArray(Charsets.ISO_8859_1)

    /**
     * Разбор потока содержимого.
     *
     * Строки копятся в `pending` и сбрасываются в результат только оператором показа
     * (`Tj`/`TJ`/`'`/`"`) — иначе служебные строки вроде имён шрифтов попадали бы в текст.
     * Позиционирование (`Td`/`TD`/`T*`/`Tm`) даёт перевод строки или пробел: по `ty`
     * видно, новая это строка или кернинг внутри неё.
     */
    private fun parsePdfContent(data: ByteArray, fonts: PdfFonts): String {
        val out = StringBuilder()
        val pending = StringBuilder()
        val numbers = ArrayList<Double>(8)
        var lastTmY = Double.NaN
        var fontName: String? = null
        var currentMap: PdfFontMap? = fonts.resolve(null)
        var i = 0
        val n = data.size
        while (i < n) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 0x25 -> while (i < n && data[i].toInt() != 0x0A && data[i].toInt() != 0x0D) i++
                b == 0x28 -> {
                    val literal = readLiteralString(data, i)
                    appendPdfText(pending, literal.first, currentMap)
                    i = literal.second
                }
                b == 0x3C -> {
                    if (i + 1 < n && data[i + 1].toInt() == 0x3C) {
                        i += 2
                    } else {
                        val hex = readHexString(data, i)
                        val bytes = hex.first
                        if (bytes != null) appendPdfText(pending, bytes, currentMap)
                        i = hex.second
                    }
                }
                // Имя ресурса (`/F1`): запоминаем — на `Tf` оно станет текущим шрифтом.
                b == 0x2F -> {
                    var start = i + 1
                    i = start
                    while (i < n && isPdfNameByte(data[i].toInt() and 0xFF)) i++
                    fontName = String(data, start, i - start, Charsets.ISO_8859_1)
                }
                b == 0x5B || b == 0x5D || b == 0x7B || b == 0x7D || b == 0x3E -> i++
                b in 0x30..0x39 || b == 0x2B || b == 0x2D || b == 0x2E -> {
                    val start = i
                    i++
                    while (i < n && isNumberByte(data[i].toInt() and 0xFF)) i++
                    val value = String(data, start, i - start, Charsets.ISO_8859_1).toDoubleOrNull()
                    if (value != null) {
                        if (numbers.size < 16) numbers.add(value)
                        // Отрицательный кернинг в TJ — это пробел между словами. Порог
                        // намеренно грубый: -120 (0.12 em) — обычное сжатие внутри слова,
                        // настоящий пробел начинается примерно с -200.
                        if (value <= -200.0) pending.append(' ')
                    }
                }
                isPdfRegular(b) -> {
                    val start = i
                    i++
                    while (i < n && isPdfRegular(data[i].toInt() and 0xFF)) i++
                    val op = String(data, start, i - start, Charsets.ISO_8859_1)
                    when (op) {
                        "Tf" -> {
                            currentMap = fonts.resolve(fontName)
                            numbers.clear()
                        }
                        "Tj", "TJ" -> flush(out, pending)
                        "'" -> {
                            newline(out)
                            flush(out, pending)
                        }
                        "\"" -> {
                            newline(out)
                            flush(out, pending)
                        }
                        "Td", "TD" -> {
                            // dy ≈ 0 — это позиционирование внутри строки (часто по одному
                            // глифу): пробел здесь не нужен, слова разделяет сам пробельный
                            // глиф или кернинг в TJ. dy ≠ 0 — новая строка.
                            if (numbers.size >= 2 && kotlin.math.abs(numbers[1]) > 0.001) newline(out)
                            pending.setLength(0)
                            numbers.clear()
                        }
                        "T*" -> {
                            newline(out)
                            pending.setLength(0)
                            numbers.clear()
                        }
                        "Tm" -> {
                            if (numbers.size >= 6) {
                                val y = numbers[5]
                                if (lastTmY.isNaN() || kotlin.math.abs(y - lastTmY) > 0.001) newline(out) else space(out)
                                lastTmY = y
                            }
                            pending.setLength(0)
                            numbers.clear()
                        }
                        else -> {
                            // Незнакомый оператор (q, cm, BT, re...): текст в `pending` не
                            // трогаем — он может относиться к следующему Tj.
                            numbers.clear()
                        }
                    }
                }
                else -> i++
            }
            if (out.length > MAX_PDF_BUFFER) break
        }
        return out.toString()
    }

    private fun flush(out: StringBuilder, pending: StringBuilder) {
        if (pending.isEmpty()) return
        out.append(pending)
        pending.setLength(0)
    }

    /**
     * Декодировать показанную строку: через CMap шрифта, если он есть, иначе эвристикой
     * по байтам (UTF-16BE / windows-1252 / cp1251).
     */
    private fun appendPdfText(sb: StringBuilder, raw: ByteArray, map: PdfFontMap?) {
        if (map != null) sb.append(map.decode(raw)) else sb.append(decodePdfBytes(raw))
    }

    private fun newline(out: StringBuilder) {
        if (out.isEmpty()) return
        if (out[out.length - 1] != '\n') out.append('\n')
    }

    private fun space(out: StringBuilder) {
        if (out.isEmpty()) return
        val last = out[out.length - 1]
        if (last != '\n' && last != ' ') out.append(' ')
    }

    private fun readLiteralString(data: ByteArray, start: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var i = start + 1
        var depth = 1
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 0x5C -> {
                    i++
                    if (i >= data.size) break
                    val esc = data[i].toInt() and 0xFF
                    when {
                        esc == 0x6E -> { out.write(0x0A); i++ }
                        esc == 0x72 -> { out.write(0x0D); i++ }
                        esc == 0x74 -> { out.write(0x09); i++ }
                        esc == 0x62 -> { out.write(0x08); i++ }
                        esc == 0x66 -> { out.write(0x0C); i++ }
                        esc == 0x28 || esc == 0x29 || esc == 0x5C -> { out.write(esc); i++ }
                        esc == 0x0D -> {
                            i++
                            if (i < data.size && data[i].toInt() == 0x0A) i++
                        }
                        esc == 0x0A -> i++
                        esc in 0x30..0x37 -> {
                            var value = 0
                            var k = 0
                            while (k < 3 && i < data.size && data[i].toInt() in 0x30..0x37) {
                                value = value * 8 + (data[i].toInt() - 0x30)
                                i++
                                k++
                            }
                            out.write(value and 0xFF)
                        }
                        else -> { out.write(esc); i++ }
                    }
                }
                b == 0x28 -> { depth++; out.write(b); i++ }
                b == 0x29 -> {
                    depth--
                    i++
                    if (depth == 0) break
                    out.write(b)
                }
                else -> { out.write(b); i++ }
            }
        }
        return out.toByteArray() to i
    }

    private fun readHexString(data: ByteArray, start: Int): Pair<ByteArray?, Int> {
        val digits = StringBuilder()
        var i = start + 1
        while (i < data.size && data[i].toInt() != 0x3E) {
            val b = data[i].toInt() and 0xFF
            if ((b in 0x30..0x39) || (b in 0x41..0x46) || (b in 0x61..0x66)) {
                digits.append(b.toChar())
            }
            i++
        }
        if (i < data.size) i++
        if (digits.isEmpty()) return null to i
        if (digits.length % 2 == 1) digits.append('0')
        val out = ByteArray(digits.length / 2)
        var k = 0
        while (k < out.size) {
            val hi = Character.digit(digits[k * 2], 16)
            val lo = Character.digit(digits[k * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null to i
            out[k] = ((hi shl 4) or lo).toByte()
            k++
        }
        return out to i
    }

    /**
     * Байты PDF-строки → текст. BOM UTF-16BE — явный признак; иначе эвристика «каждый
     * второй байт нулевой» (UTF-16BE без BOM); иначе windows-1252, а если из неё выходит
     * типичная «кракозябра» (сплошь латинские диакритики) — cp1251, которой пишут
     * русские документы со встроенным шрифтом.
     */
    private fun decodePdfBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 4) {
            var evenZero = true
            var oddZero = true
            var i = 0
            while (i + 1 < bytes.size) {
                if (bytes[i].toInt() != 0) evenZero = false
                if (bytes[i + 1].toInt() != 0) oddZero = false
                i += 2
            }
            if (evenZero && !oddZero) return String(bytes, Charsets.UTF_16BE)
        }
        val latin = String(bytes, PDF_CHARSET)
        if (looksLikeCyrillicMojibake(latin)) {
            val cyrillic = runCatching { String(bytes, CP1251) }.getOrNull()
            if (cyrillic != null && cyrillic.any { it in '\u0400'..'\u04FF' }) return cyrillic
        }
        return latin
    }

    /** Признак «cp1251 прочитали как windows-1252»: буквы почти сплошь диакритические. */
    private fun looksLikeCyrillicMojibake(text: String): Boolean {
        var letters = 0
        var accented = 0
        for (ch in text) {
            if (ch.isLetter()) {
                letters++
                if (CP1252_ACCENTED.indexOf(ch) >= 0) accented++
            }
        }
        return letters >= 3 && accented >= 3 && accented * 10 >= letters * 3
    }

    private fun isPdfNameByte(b: Int): Boolean {
        if (b <= 0x20) return false
        return when (b) {
            0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x7B, 0x7D, 0x2F, 0x25, 0x7F -> false
            else -> true
        }
    }

    private fun isPdfRegular(b: Int): Boolean {
        if (b <= 0x20) return false
        return when (b) {
            0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x7B, 0x7D, 0x2F, 0x25, 0x7F -> false
            else -> true
        }
    }

    private fun isNumberByte(b: Int): Boolean =
        (b in 0x30..0x39) || b == 0x2B || b == 0x2D || b == 0x2E

    // ─────────────────────────────── общее ───────────────────────────────

    /**
     * Привести текст в читаемый вид: убрать управляющие символы, обрезать пробелы по
     * краям строк, схлопнуть пустые строки (не больше одной подряд) и ведущие пустые.
     */
    private fun tidy(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        var pendingBreaks = 0
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val clean = sanitizeLine(line)
            if (clean.isEmpty()) {
                pendingBreaks++
                continue
            }
            if (out.isNotEmpty()) out.append(if (pendingBreaks > 1) "\n\n" else "\n")
            out.append(clean)
            pendingBreaks = 0
        }
        return out.toString().trim()
    }

    private fun sanitizeLine(line: String): String {
        val sb = StringBuilder(line.length)
        for (ch in line) {
            when {
                // Табуляция — разделитель колонок таблицы: её сохраняем, она полезна модели.
                ch == '\t' -> sb.append('\t')
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                ch == '\u00A0' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        // Схлопываем повторы пробелов (но не табуляций): PDF часто разносит слова кернингом.
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun meaningfulChars(text: String): Int {
        var count = 0
        for (ch in text) if (Character.isLetterOrDigit(ch)) count++
        return count
    }

    /**
     * Текст явно нечитаем — шрифт отдаёт коды глифов без `/ToUnicode`, и в промпт ушла бы
     * каша. Два признака:
     * 1. буквы почти сплошь латинская диакритика (cp1251, прочитанная как windows-1252);
     * 2. много «мусорных» символов (`\ ^ ` | ~ { }`) при почти полном отсутствии кириллицы —
     *    так выглядят двухбайтовые коды глифов, принятые за UTF-16.
     */
    private fun looksUnreadable(text: String): Boolean {
        var letters = 0
        var accented = 0
        var cyrillic = 0
        var nonSpace = 0
        var odd = 0
        for (ch in text) {
            if (!ch.isWhitespace()) nonSpace++
            if (ODD_CHARS.indexOf(ch) >= 0) odd++
            if (ch.isLetter()) {
                letters++
                if (CP1252_ACCENTED.indexOf(ch) >= 0) accented++
                if (ch in '\u0400'..'\u04FF') cyrillic++
            }
        }
        if (letters < 20) return false
        if (accented * 10 >= letters * 3) return true
        return cyrillic * 20 < letters && odd * 100 >= nonSpace * 3
    }

    private fun limit(text: String): String {
        if (text.length <= MAX_CHARS) return text
        return text.substring(0, MAX_CHARS).trimEnd() + TRUNCATION_MARK
    }

    private fun extensionOf(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    private fun normalizeMime(mimeType: String): String =
        mimeType.substringBefore(';').trim().lowercase()

    /** Причина отказа по-русски: имя Java-исключения пользователю ничего не говорит. */
    private fun reasonOf(e: Exception): String = when (e) {
        is java.io.EOFException -> "файл повреждён — данных не хватает (возможно, скачан не полностью)"
        is java.util.zip.ZipException -> "файл повреждён или это не документ Office"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun failure(message: String): Result<String> = Result.failure(IllegalArgumentException(message))
}
