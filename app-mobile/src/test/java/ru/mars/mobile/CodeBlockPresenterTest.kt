package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockPresenterTest {

    private fun listing(lines: Int): String = (1..lines).joinToString("\n") { "line $it" }

    @Test
    fun `language header maps known aliases to canonical labels`() {
        assertEquals("Kotlin", CodeBlockPresenter.languageLabel("kotlin"))
        assertEquals("Kotlin", CodeBlockPresenter.languageLabel("kt"))
        assertEquals("Python", CodeBlockPresenter.languageLabel("py"))
        assertEquals("Bash", CodeBlockPresenter.languageLabel("sh"))
        assertEquals("Bash", CodeBlockPresenter.languageLabel("shell"))
        assertEquals("JSON", CodeBlockPresenter.languageLabel("json"))
        assertEquals("SQL", CodeBlockPresenter.languageLabel("sql"))
        assertEquals("JavaScript", CodeBlockPresenter.languageLabel("js"))
        assertEquals("TypeScript", CodeBlockPresenter.languageLabel("ts"))
        assertEquals("YAML", CodeBlockPresenter.languageLabel("yml"))
        assertEquals("Docker", CodeBlockPresenter.languageLabel("dockerfile"))
    }

    @Test
    fun `language header is case-insensitive and normalizes unknown tags`() {
        assertEquals("Kotlin", CodeBlockPresenter.languageLabel("KOTLIN"))
        assertEquals("Solidity", CodeBlockPresenter.languageLabel("solidity"))
        assertEquals("Hcl", CodeBlockPresenter.languageLabel(" hcl "))
    }

    @Test
    fun `empty fence language falls back to plain text title`() {
        assertEquals("Текст", CodeBlockPresenter.languageLabel(""))
        assertEquals("Текст", CodeBlockPresenter.languageLabel("text"))
        assertEquals("Текст", CodeBlockPresenter.languageLabel("plaintext"))
    }

    @Test
    fun `line count and collapse threshold boundary`() {
        assertEquals(0, CodeBlockPresenter.lineCount(""))
        assertEquals(1, CodeBlockPresenter.lineCount("single"))
        assertEquals(18, CodeBlockPresenter.lineCount(listing(18)))

        assertFalse(CodeBlockPresenter.isCollapsible(listing(18)))
        assertTrue(CodeBlockPresenter.isCollapsible(listing(19)))
        assertEquals(18, CodeBlockPresenter.COLLAPSE_THRESHOLD_LINES)
    }

    @Test
    fun `collapsed long listing shows only the threshold prefix`() {
        val code = listing(25)
        val visible = CodeBlockPresenter.visibleCode(code, expanded = false)
        assertEquals(listing(18), visible)
        assertEquals(7, CodeBlockPresenter.hiddenLineCount(code))
    }

    @Test
    fun `expanded or short listings are shown in full`() {
        assertEquals(listing(25), CodeBlockPresenter.visibleCode(listing(25), expanded = true))
        assertEquals(listing(10), CodeBlockPresenter.visibleCode(listing(10), expanded = false))
        assertEquals(0, CodeBlockPresenter.hiddenLineCount(listing(10)))
    }

    @Test
    fun `toggle label is null for short blocks and pluralized for long ones`() {
        assertNull(CodeBlockPresenter.toggleLabel(listing(18), expanded = false))
        assertEquals("Свернуть", CodeBlockPresenter.toggleLabel(listing(25), expanded = true))
        assertEquals("Показать ещё 1 строку", CodeBlockPresenter.toggleLabel(listing(19), expanded = false))
        assertEquals("Показать ещё 2 строки", CodeBlockPresenter.toggleLabel(listing(20), expanded = false))
        assertEquals("Показать ещё 5 строк", CodeBlockPresenter.toggleLabel(listing(23), expanded = false))
        assertEquals("Показать ещё 21 строку", CodeBlockPresenter.toggleLabel(listing(39), expanded = false))
        assertEquals("Показать ещё 112 строк", CodeBlockPresenter.toggleLabel(listing(130), expanded = false))
    }

    @Test
    fun `present keeps the full snippet as copy source even when collapsed`() {
        val block = ContentBlock.Code(code = listing(30), language = "sql")
        val view = CodeBlockPresenter.present(block, expanded = false)
        assertEquals("SQL", view.languageLabel)
        assertTrue(view.collapsible)
        assertFalse(view.expanded)
        assertEquals(12, view.hiddenLines)
        assertEquals(listing(18), view.visibleCode)
        assertEquals(listing(30), view.fullCode)
        assertEquals(listing(30), view.copySource)
        assertEquals("Показать ещё 12 строк", view.toggleLabel)
    }

    @Test
    fun `present for short block without language`() {
        val view = CodeBlockPresenter.present(ContentBlock.Code(code = "echo ok", language = ""), expanded = false)
        assertEquals("Текст", view.languageLabel)
        assertFalse(view.collapsible)
        assertNull(view.toggleLabel)
        assertEquals("echo ok", view.visibleCode)
    }

    @Test
    fun `integrates with markdown fence parsing`() {
        val blocks = MarkdownFormatter.parseBlocks("```json\n{\"a\": 1}\n```")
        val code = blocks.single() as ContentBlock.Code
        val view = CodeBlockPresenter.present(code, expanded = true)
        assertEquals("JSON", view.languageLabel)
        assertEquals("""{"a": 1}""", view.copySource)
    }
}
