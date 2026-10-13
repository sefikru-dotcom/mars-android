package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast path парсера: детектор markdown-маркеров и «быстрый» результат для
 * текста без маркеров. Главный инвариант — быстрый путь обязан давать ТОТ ЖЕ
 * результат, что и полный регекс-разбор ([MarkdownFormatter.parseBlocksUncached]).
 */
class MarkdownFormatterFastPathTest {

    @Test
    fun plainTextHasNoBlockMarkers() {
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("Привет, Марс! Как дела?"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("Just 42 apples, well-known fact (100%)."))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("snake_case and hyphen-ated words"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("line one\nline two\n\nline four"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("  \n\t\nпросто пробелы"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("email a+b@example.ru и восклицание!"))
    }

    @Test
    fun everySyntaxTriggerIsDetected() {
        // Маркеры в любой позиции строки.
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("```kotlin\ncode\n```"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("inline `code` here"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("**bold** и *italic*"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("~~strike~~"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("[ссылка](https://example.com)"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("![картинка](https://example.com/i.png)"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("a | b"))
        // Маркеры только в начале строки.
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("# header"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("> quote"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("- bullet"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("* bullet"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("+ bullet"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("___"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("1. numbered"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("   7. indented numbered"))
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("вторая строка\n> а тут цитата"))
    }

    @Test
    fun lineStartMarkersMidLineDoNotTrigger() {
        // Эти символы вне начала строки не запускают никакой синтаксис.
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("a - b, ok #1 done, x > y"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("версия 2 релиз 100%"))
        assertFalse(MarkdownFormatter.hasMarkdownMarkers("total of 5 items: 3 done"))
    }

    @Test
    fun digitsAtLineStartAreConservative() {
        // Цифра в начале строки — маркер (могло бы быть "2. item"), даже если
        // точки дальше нет: ложное срабатывание лишь включает медленный путь.
        assertTrue(MarkdownFormatter.hasMarkdownMarkers("50 процентов"))
    }

    @Test
    fun fastPathMatchesFullParseOnPlainCorpus() {
        val plainCorpus = listOf(
            "Привет, Марс! Как дела?",
            "multi\nline\n\ntext with 42 numbers",
            "snake_case, hyphen-ated, восклицание! И (скобки).",
            "Очень длинное сообщение стрима. ".repeat(30).trimEnd(),
            "  \n leading and trailing spaces  \n  ",
            "\n",
        )
        for (raw in plainCorpus) {
            assertFalse("в корпусе не должно быть маркеров: ${raw.take(30)}", MarkdownFormatter.hasMarkdownMarkers(raw))
            assertEquals(
                "fast path расходится с полным разбором для: ${raw.take(30)}",
                MarkdownFormatter.parseBlocksUncached(raw),
                MarkdownFormatter.parseBlocks(raw),
            )
        }
    }

    @Test
    fun plainTextStructureIsSingleTextBlockWithNormalLines() {
        val blocks = MarkdownFormatter.parseBlocks("первая\n\nтретья")
        assertEquals(1, blocks.size)
        val text = blocks[0] as ContentBlock.Text
        assertEquals(3, text.lines.size)
        assertEquals(
            RichLine.Normal(listOf(InlineSpan.Normal("первая"))),
            text.lines[0],
        )
        assertEquals(RichLine.Normal(emptyList()), text.lines[1])
        assertEquals(
            RichLine.Normal(listOf(InlineSpan.Normal("третья"))),
            text.lines[2],
        )
    }

    @Test
    fun inlineFastPathSkipsRegexesForPlainSegments() {
        assertFalse(MarkdownFormatter.hasInlineMarkers("просто текст без разметки"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a `b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a *b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a [b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a ~b"))
        val plain = "просто текст"
        assertEquals(listOf<InlineSpan>(InlineSpan.Normal(plain)), MarkdownFormatter.parseInline(plain))
        assertEquals(MarkdownFormatter.parseInline("слово (скобки), 42!"), listOf<InlineSpan>(InlineSpan.Normal("слово (скобки), 42!")))
    }

    @Test
    fun markeredTextStillParsesRich() {
        // Контроль: текст с маркерами идёт через полный путь и не теряет разметку.
        val raw = "# Заголовок\n- **bold** пункт\n```js\nvar x;\n```"
        val blocks = MarkdownFormatter.parseBlocks(raw)
        assertEquals(2, blocks.size)
        val text = blocks[0] as ContentBlock.Text
        assertEquals(RichLine.Header(1, listOf(InlineSpan.Normal("Заголовок"))), text.lines[0])
        val bullet = text.lines[1] as RichLine.Bullet
        assertEquals(InlineSpan.Bold("bold"), bullet.spans[0])
        val code = blocks[1] as ContentBlock.Code
        assertEquals("js", code.language)
        assertEquals("var x;", code.code)
    }
}
