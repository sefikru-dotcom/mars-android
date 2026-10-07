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
}
