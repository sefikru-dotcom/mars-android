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

    private fun InlineSpan.text(): String = when (this) {
        is InlineSpan.Normal -> text
        is InlineSpan.Bold -> text
        is InlineSpan.Italic -> text
        is InlineSpan.Monospace -> text
        is InlineSpan.Link -> title
    }
}
