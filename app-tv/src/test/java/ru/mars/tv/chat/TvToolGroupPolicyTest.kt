package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сводка вызовов инструментов для ТВ-истории (разрыв L: tool-строки были «Марс»-пузырями). */
class TvToolGroupPolicyTest {

    @Test
    fun `russian plural forms`() {
        assertEquals("инструмент", TvToolGroupPolicy.plural(1))
        assertEquals("инструмента", TvToolGroupPolicy.plural(2))
        assertEquals("инструмента", TvToolGroupPolicy.plural(4))
        assertEquals("инструментов", TvToolGroupPolicy.plural(5))
        assertEquals("инструментов", TvToolGroupPolicy.plural(11))
        assertEquals("инструмент", TvToolGroupPolicy.plural(21))
        assertEquals("инструмент", TvToolGroupPolicy.plural(101))
    }

    @Test
    fun `summary reads as a settled series`() {
        assertEquals("1 инструмент · Готово", TvToolGroupPolicy.summary(1))
        assertEquals("4 инструмента · Готово", TvToolGroupPolicy.summary(4))
        assertEquals("12 инструментов · Готово", TvToolGroupPolicy.summary(12))
    }

    @Test
    fun `repeated tools collapse into a counter`() {
        assertEquals("read_file ×4, grep", TvToolGroupPolicy.namesLine(listOf("read_file", "read_file", "read_file", "read_file", "grep")))
    }

    @Test
    fun `the names line keeps first-seen order`() {
        assertEquals("grep, read_file", TvToolGroupPolicy.namesLine(listOf("grep", "read_file")))
    }

    @Test
    fun `the tail of a long series collapses into 'и ещё N'`() {
        val names = listOf("a", "b", "c", "d", "e", "f")
        assertEquals("a, b, c, d и ещё 2", TvToolGroupPolicy.namesLine(names))
    }

    @Test
    fun `blank tool names do not leave an empty group`() {
        assertEquals("инструмент", TvToolGroupPolicy.namesLine(listOf("  ")))
        assertEquals("", TvToolGroupPolicy.namesLine(emptyList()))
    }

    @Test
    fun `tool name trims and drops blanks`() {
        assertEquals("read_file", TvToolGroupPolicy.toolName(" read_file "))
        assertEquals(null, TvToolGroupPolicy.toolName("   "))
        assertEquals(null, TvToolGroupPolicy.toolName(null))
    }

    @Test
    fun `no glyphs in the summary - the tv design gate forbids them`() {
        val text = TvToolGroupPolicy.summary(3) + TvToolGroupPolicy.namesLine(listOf("a", "b"))
        assertTrue(Regex("[\\u2190-\\u21FF\\u2600-\\u27BF]").find(text) == null)
    }
}
