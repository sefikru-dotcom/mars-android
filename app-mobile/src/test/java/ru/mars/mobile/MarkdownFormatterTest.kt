package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTest {

    @Test
    fun testParseBlocksSingleText() {
        val raw = "Привет, Марс!\nКак дела?"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        val textBlock = blocks[0] as ContentBlock.Text
        assertEquals(2, textBlock.lines.size)
    }

    @Test
    fun testParseBlocksWithCode() {
        val raw = "Текст до кода\n```kotlin\nval x = 42\n```\nТекст после кода"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        assertTrue(blocks[1] is ContentBlock.Code)
        val codeBlock = blocks[1] as ContentBlock.Code
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val x = 42", codeBlock.code)
        assertTrue(blocks[2] is ContentBlock.Text)
    }

    @Test
    fun testFormatRichTextHeadersAndLists() {
        val raw = "# Заголовок 1\n## Заголовок 2\n- Пункт 1\n1. Шаг первый\n> Важная цитата"
        val lines = MarkdownFormatter.parseLines(raw)
        assertEquals(5, lines.size)
        assertTrue(lines[0] is RichLine.Header && (lines[0] as RichLine.Header).level == 1)
        assertTrue(lines[1] is RichLine.Header && (lines[1] as RichLine.Header).level == 2)
        assertTrue(lines[2] is RichLine.Bullet)
        assertTrue(lines[3] is RichLine.Numbered && (lines[3] as RichLine.Numbered).number == "1.")
        assertTrue(lines[4] is RichLine.Quote)
    }

    @Test
    fun testFormatInlineLinksAndFormatting() {
        val raw = "Проверь сайт [Квартал](https://kvartalpro.ru) с **жирным** текстом и `кодом`"
        val spans = MarkdownFormatter.parseInline(raw)
        assertTrue(spans.any { it is InlineSpan.Link && it.title == "Квартал" && it.url == "https://kvartalpro.ru" })
        assertTrue(spans.any { it is InlineSpan.Bold && it.text == "жирным" })
        assertTrue(spans.any { it is InlineSpan.Monospace && it.text == "кодом" })
    }

    @Test
    fun testHeaderLevelsUpToSix() {
        val lines = MarkdownFormatter.parseLines("# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6")
        assertEquals(6, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue(line is RichLine.Header && (line as RichLine.Header).level == i + 1)
        }
    }

    @Test
    fun testHorizontalRuleVariants() {
        for (variant in listOf("---", "***", "___", "- - -")) {
            val lines = MarkdownFormatter.parseLines(variant)
            assertEquals(1, lines.size)
            assertTrue("'$variant' should be a Divider", lines[0] is RichLine.Divider)
        }
        // A rule inside text stays a rule, and text around it survives.
        val lines = MarkdownFormatter.parseLines("до\n---\nпосле")
        assertEquals(3, lines.size)
        assertTrue(lines[1] is RichLine.Divider)
    }

    @Test
    fun testDividerRegexDoesNotEatBulletsOrText() {
        val lines = MarkdownFormatter.parseLines("- пункт\nтекст - с дефисом -- не hr")
        assertTrue(lines[0] is RichLine.Bullet)
        assertTrue(lines[1] is RichLine.Normal)
    }

    @Test
    fun testItalicNotConfusedByBold() {
        val spans = MarkdownFormatter.parseInline("обычный *курсив* и **жирный** рядом")
        assertTrue(spans.any { it is InlineSpan.Italic && it.text == "курсив" })
        assertTrue(spans.any { it is InlineSpan.Bold && it.text == "жирный" })
        assertTrue(spans.none { it is InlineSpan.Italic && it.text.contains("жирный") })
    }

    @Test
    fun testItalicInsideBoldIsNotDoubleCounted() {
        // The stray-asterisk regex must not turn **x** into an italic span.
        val spans = MarkdownFormatter.parseInline("**только жирный**")
        assertEquals(1, spans.size)
        assertTrue(spans[0] is InlineSpan.Bold && (spans[0] as InlineSpan.Bold).text == "только жирный")
    }

    @Test
    fun testStrayAsterisksAreNotItalic() {
        // CommonMark: "a * b * c" has no emphasis.
        val spans = MarkdownFormatter.parseInline("a * b * c")
        assertTrue(spans.none { it is InlineSpan.Italic })
        val text = spans.joinToString("") { it.text() }
        assertEquals("a * b * c", text)
    }

    @Test
    fun testCodeSpanProtectsAsterisks() {
        val spans = MarkdownFormatter.parseInline("вызов `**not bold**` внутри")
        val mono = spans.filterIsInstance<InlineSpan.Monospace>()
        assertEquals(1, mono.size)
        assertEquals("**not bold**", mono[0].text)
        assertTrue(spans.none { it is InlineSpan.Bold })
    }

    @Test
    fun testNumberedListKeepsNumbers() {
        val lines = MarkdownFormatter.parseLines("1. первый\n2. второй\n10. десятый")
        assertEquals(3, lines.size)
        assertEquals("1.", (lines[0] as RichLine.Numbered).number)
        assertEquals("2.", (lines[1] as RichLine.Numbered).number)
        assertEquals("10.", (lines[2] as RichLine.Numbered).number)
    }

    @Test
    fun testBulletVariants() {
        val lines = MarkdownFormatter.parseLines("- минус\n* звезда\n+ плюс")
        assertEquals(3, lines.size)
        lines.forEach { assertTrue(it is RichLine.Bullet) }
    }

    @Test
    fun testQuoteWithoutSpaceAfterMarker() {
        val lines = MarkdownFormatter.parseLines(">цитата без пробела")
        assertEquals(1, lines.size)
        assertTrue(lines[0] is RichLine.Quote)
    }

    @Test
    fun testInlineFormattingInsideHeaderAndBullet() {
        val header = MarkdownFormatter.parseLines("# **жирный** заголовок")[0] as RichLine.Header
        assertTrue(header.spans.any { it is InlineSpan.Bold && it.text == "жирный" })
        val bullet = MarkdownFormatter.parseLines("- пункт с [ссылкой](https://example.com)")[0] as RichLine.Bullet
        assertTrue(bullet.spans.any { it is InlineSpan.Link && it.url == "https://example.com" })
    }

    @Test
    fun testUnclosedCodeFenceRendersAsCodeWhileStreaming() {
        val raw = "Текст\n```json\n{\"a\": 1"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        val code = blocks[1] as ContentBlock.Code
        assertEquals("json", code.language)
        assertEquals("{\"a\": 1", code.code)
    }

    @Test
    fun testCodeBlockWithoutLanguageAndCrlf() {
        val blocks = MarkdownFormatter.parseBlocks("до\r\n```\r\nplain\r\n```\r\nпосле")
        assertEquals(3, blocks.size)
        val code = blocks[1] as ContentBlock.Code
        assertEquals("", code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun testTwoCodeBlocksInOneMessage() {
        val raw = "```a\n1\n```\nмежду\n```b\n2\n```"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(3, blocks.size)
        assertEquals("a", (blocks[0] as ContentBlock.Code).language)
        assertTrue(blocks[1] is ContentBlock.Text)
        assertEquals("b", (blocks[2] as ContentBlock.Code).language)
    }

    @Test
    fun testEmptyInputProducesNoBlocks() {
        assertEquals(0, MarkdownFormatter.parseBlocks("").size)
        assertEquals(0, MarkdownFormatter.parseInline("").size)
    }

    // ── Таблицы и зачёркивание ───────────────────────────────────────────────

    @Test
    fun testStrikethroughSpan() {
        val spans = MarkdownFormatter.parseInline("~~зачеркнуто~~")
        assertEquals(1, spans.size)
        assertTrue(spans[0] is InlineSpan.Strikethrough)
        assertEquals("зачеркнуто", (spans[0] as InlineSpan.Strikethrough).text)
    }

    @Test
    fun testStrikethroughMixedWithOtherFormatting() {
        val spans = MarkdownFormatter.parseInline("было ~~old~~ стало **new**")
        assertTrue(spans.any { it is InlineSpan.Strikethrough && it.text == "old" })
        assertTrue(spans.any { it is InlineSpan.Bold && it.text == "new" })
        assertEquals("было old стало new", spans.plainText())
    }

    @Test
    fun testCodeSpanProtectsStrikethrough() {
        val spans = MarkdownFormatter.parseInline("синтаксис `~~not strike~~` виден")
        val mono = spans.filterIsInstance<InlineSpan.Monospace>()
        assertEquals(1, mono.size)
        assertEquals("~~not strike~~", mono[0].text)
        assertTrue(spans.none { it is InlineSpan.Strikethrough })
    }

    @Test
    fun testTableTwoByTwo() {
        val raw = "| Заголовок 1 | Заголовок 2 |\n|---|---|\n| Данные 1 | Данные 2 |"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(1, blocks.size)
        val table = blocks[0] as ContentBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals("Заголовок 1", table.headers[0].plainText())
        assertEquals("Заголовок 2", table.headers[1].plainText())
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals("Данные 1", table.rows[0][0].plainText())
        assertEquals("Данные 2", table.rows[0][1].plainText())
    }

    @Test
    fun testTableWithAlignmentSeparatorAndMultipleRows() {
        val raw = "| a | b |\n| :--- | ---: |\n| 1 | 2 |\n| 3 | 4 |"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(1, blocks.size)
        val table = blocks[0] as ContentBlock.Table
        assertEquals(listOf("a", "b"), table.headers.map { it.plainText() })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.plainText() })
        assertEquals(listOf("3", "4"), table.rows[1].map { it.plainText() })
    }

    @Test
    fun testTableWithoutLeadingAndTrailingPipes() {
        val raw = "a | b\n--- | ---\n1 | 2"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(1, blocks.size)
        val table = blocks[0] as ContentBlock.Table
        assertEquals(listOf("a", "b"), table.headers.map { it.plainText() })
        assertEquals(listOf("1", "2"), table.rows.single().map { it.plainText() })
    }

    @Test
    fun testTableWithInlineFormatting() {
        val raw = "| Колонка | Значение |\n|---|---|\n" +
            "| **жирный** | [ссылка](https://example.com) |\n" +
            "| `код` | ~~старое~~ |\n" +
            "| *курсив* | обычный |"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(1, blocks.size)
        val table = blocks[0] as ContentBlock.Table
        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0][0].any { it is InlineSpan.Bold && it.text == "жирный" })
        assertTrue(table.rows[0][1].any { it is InlineSpan.Link && it.title == "ссылка" && it.url == "https://example.com" })
        assertTrue(table.rows[1][0].any { it is InlineSpan.Monospace && it.text == "код" })
        assertTrue(table.rows[1][1].any { it is InlineSpan.Strikethrough && it.text == "старое" })
        assertTrue(table.rows[2][0].any { it is InlineSpan.Italic && it.text == "курсив" })
        assertEquals("обычный", table.rows[2][1].plainText())
    }

    @Test
    fun testTableBetweenParagraphs() {
        val raw = "Вводный абзац\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nЗаключительный абзац"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        assertTrue(blocks[1] is ContentBlock.Table)
        assertTrue(blocks[2] is ContentBlock.Text)
        val before = blocks[0] as ContentBlock.Text
        assertEquals("Вводный абзац", before.lines.filterIsInstance<RichLine.Normal>().joinToString("") { it.spans.plainText() }.trim())
        val after = blocks[2] as ContentBlock.Text
        assertEquals("Заключительный абзац", after.lines.filterIsInstance<RichLine.Normal>().joinToString("") { it.spans.plainText() }.trim())
    }

    @Test
    fun testTableAfterCodeBlock() {
        val raw = "```kotlin\nval x = 1\n```\n| a |\n|---|\n| b |"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertTrue(blocks[0] is ContentBlock.Code)
        assertTrue(blocks.last() is ContentBlock.Table)
        val table = blocks.last() as ContentBlock.Table
        assertEquals("a", table.headers.single().plainText())
        assertEquals("b", table.rows.single().single().plainText())
    }

    @Test
    fun testDividerAndTextPipesAreNotTables() {
        // A divider without pipes stays a divider; no table is created.
        val blocks = MarkdownFormatter.parseBlocks("до\n---\nпосле")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        // A pipe line without a following separator row stays plain text.
        val text = MarkdownFormatter.parseBlocks("итого | 5").single() as ContentBlock.Text
        assertEquals("итого | 5", text.lines.single().let { (it as RichLine.Normal).spans.plainText() })
    }

    @Test
    fun testTableCellWithEscapedPipe() {
        val raw = "| a | b |\n|---|---|\n| x \\| y | z |"
        val table = MarkdownFormatter.parseBlocks(raw).single() as ContentBlock.Table
        assertEquals("x | y", table.rows.single()[0].plainText())
        assertEquals("z", table.rows.single()[1].plainText())
    }

    @Test
    fun testImageSyntaxBecomesImageSpan() {
        val spans = MarkdownFormatter.parseInline("![Скриншот](https://x.ru/a.png)")
        assertEquals(1, spans.size)
        val image = spans.single() as InlineSpan.Image
        assertEquals("Скриншот", image.alt)
        assertEquals("https://x.ru/a.png", image.url)
    }

    @Test
    fun testImageMixedWithTextAndLinks() {
        val spans = MarkdownFormatter.parseInline("до ![alt](https://x.ru/i.jpg) после [ссылка](https://y.ru)")
        assertEquals("до ", (spans[0] as InlineSpan.Normal).text)
        val image = spans[1] as InlineSpan.Image
        assertEquals("alt", image.alt)
        assertEquals("https://x.ru/i.jpg", image.url)
        assertEquals(" после ", (spans[2] as InlineSpan.Normal).text)
        val link = spans[3] as InlineSpan.Link
        assertEquals("ссылка", link.title)
    }

    @Test
    fun testImageWithEmptyAltAndNonHttpUrlStaysText() {
        // Empty alt is allowed: ![](url) -> Image("", url).
        val empty = MarkdownFormatter.parseInline("![](https://x.ru/i.png)").single() as InlineSpan.Image
        assertEquals("", empty.alt)
        // Non-http image paths do not open a viewer; they stay literal text (no link either).
        val literal = MarkdownFormatter.parseInline("![a](/local/i.png)").plainText()
        assertEquals("![a](/local/i.png)", literal)
    }

    private fun InlineSpan.text(): String = when (this) {
        is InlineSpan.Normal -> text
        is InlineSpan.Bold -> text
        is InlineSpan.Italic -> text
        is InlineSpan.Monospace -> text
        is InlineSpan.Link -> title
        is InlineSpan.Strikethrough -> text
        is InlineSpan.Image -> alt
    }

    private fun List<InlineSpan>.plainText(): String = joinToString("") { it.text() }
}
