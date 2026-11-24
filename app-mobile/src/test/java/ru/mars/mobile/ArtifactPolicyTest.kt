package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Артефакт-панель: когда сообщение перестаёт быть сообщением.
 *
 * Проверяется то, из-за чего фича раздражает: если панель открывается на каждый длинный
 * абзац — это помеха; если не открывается на таблицу — потерянная возможность. Границы
 * (12 строк, 900 символов) проверяются ровно на краю, потому что «примерно» здесь
 * превращается в «всегда» или «никогда».
 */
class ArtifactPolicyTest {

    private val table = "| Модель | Цена |\n|---|---|\n| A | 100 |\n| B | 200 |"

    @Test
    fun `пустой текст не артефакт`() {
        assertFalse(ArtifactPolicy.decide("").isArtifact)
        assertFalse(ArtifactPolicy.decide("   \n\n ").isArtifact)
    }

    @Test
    fun `таблица становится таблицей и сохраняется в xlsx`() {
        val decision = ArtifactPolicy.decide(table)
        assertEquals(ArtifactKind.TABLE, decision.kind)
        assertEquals(ArtifactFormat.XLSX, decision.suggestedFormat)
        assertEquals("Модель | Цена", decision.suggestedTitle)
    }

    @Test
    fun `шапка без данных не считается таблицей`() {
        // Одна строка с разделителем — это заготовка, а не таблица: открывать панель
        // ради неё значило бы показывать пустой документ.
        assertFalse(ArtifactPolicy.decide("| a | b |\n|---|---|").isArtifact)
    }

    @Test
    fun `таблица с CRLF распознаётся так же`() {
        assertEquals(ArtifactKind.TABLE, ArtifactPolicy.decide(table.replace("\n", "\r\n")).kind)
    }

    @Test
    fun `короткий текст не артефакт`() {
        assertFalse(ArtifactPolicy.decide((1..5).joinToString("\n") { "строка $it" }).isArtifact)
    }

    @Test
    fun `граница двенадцати строк`() {
        val eleven = (1..11).joinToString("\n") { "строка $it" }
        assertFalse(ArtifactPolicy.decide(eleven).isArtifact)

        val twelve = (1..12).joinToString("\n") { "строка $it" }
        val decision = ArtifactPolicy.decide(twelve)
        assertEquals(ArtifactKind.DOCUMENT, decision.kind)
        assertEquals(ArtifactFormat.DOCX, decision.suggestedFormat)
    }

    @Test
    fun `длинный однострочный текст — документ по символам`() {
        // Порог по символам нужен для ответов без переводов строк: сплошная простыня.
        val long = "а".repeat(ArtifactPolicy.MIN_CHARS)
        assertEquals(ArtifactKind.DOCUMENT, ArtifactPolicy.decide(long).kind)
        assertFalse(ArtifactPolicy.decide("а".repeat(ArtifactPolicy.MIN_CHARS - 1)).isArtifact)
    }

    @Test
    fun `блок кода достаточной длины — код`() {
        val code = "```kotlin\n" + (1..20).joinToString("\n") { "val x$it = $it" } + "\n```"
        val decision = ArtifactPolicy.decide(code)
        assertEquals(ArtifactKind.CODE, decision.kind)
        assertEquals(ArtifactFormat.TXT, decision.suggestedFormat)
    }

    @Test
    fun `короткий блок кода не артефакт`() {
        val code = "```\n" + (1..11).joinToString("\n") { "строка $it" } + "\n```"
        assertFalse(ArtifactPolicy.decide(code).isArtifact)
    }

    @Test
    fun `таблица внутри блока кода — это код, а не таблица`() {
        // Иначе таблица из примеров кода уехала бы в Excel как настоящие данные.
        val code = "```md\n| a | b |\n|---|---|\n| 1 | 2 |\n" +
            (1..15).joinToString("\n") { "строка $it" } + "\n```"
        assertEquals(ArtifactKind.CODE, ArtifactPolicy.decide(code).kind)
    }

    @Test
    fun `заголовок чистится от markdown`() {
        val title = ArtifactPolicy.title("# Отчёт за **май** `код`")
        assertEquals("Отчёт за май код", title)
        listOf('#', '*', '`').forEach { mark -> assertFalse(title.contains(mark)) }
    }

    @Test
    fun `заголовок есть всегда`() {
        assertEquals(ArtifactPolicy.FALLBACK_TITLE, ArtifactPolicy.title(""))
        assertEquals(ArtifactPolicy.FALLBACK_TITLE, ArtifactPolicy.title("---\n***\n___"))
        assertTrue(ArtifactPolicy.title("а".repeat(200)).length <= ArtifactPolicy.TITLE_MAX_CHARS + 1)
    }

    @Test
    fun `подписи сохранения по формату`() {
        assertEquals("Сохранить DOCX", ArtifactPolicy.saveLabel(ArtifactFormat.DOCX))
        assertEquals("Сохранить XLSX", ArtifactPolicy.saveLabel(ArtifactFormat.XLSX))
        assertEquals("Сохранить TXT", ArtifactPolicy.saveLabel(ArtifactFormat.TXT))
    }

    @Test
    fun `строки для xlsx берутся только из таблицы`() {
        // Главная ловушка: rowsFromText тянет в Excel весь текст, включая заборы кода.
        val mixed = "Пролог про модели.\n\n" + table + "\n\n```kotlin\nval a = 1\n```"
        assertEquals(
            listOf(listOf("Модель", "Цена"), listOf("A", "100"), listOf("B", "200")),
            ArtifactPolicy.tableRows(mixed),
        )
        assertTrue(ArtifactPolicy.tableRows("просто текст").isEmpty())
    }

    @Test
    fun `подпись строк согласуется с числом`() {
        assertEquals("1 строка", ArtifactPolicy.linesLabel(1))
        assertEquals("2 строки", ArtifactPolicy.linesLabel(2))
        assertEquals("5 строк", ArtifactPolicy.linesLabel(5))
        assertEquals("12 строк", ArtifactPolicy.linesLabel(12))
        assertEquals("21 строка", ArtifactPolicy.linesLabel(21))
        assertEquals("111 строк", ArtifactPolicy.linesLabel(111))
    }

    @Test
    fun `счётчики блоков`() {
        assertEquals(1, ArtifactPolicy.countBlocks(table, ArtifactKind.TABLE))
        assertEquals(0, ArtifactPolicy.countBlocks(table, ArtifactKind.DOCUMENT))
        assertEquals(0, ArtifactPolicy.countBlocks("", ArtifactKind.TABLE))
    }
}
