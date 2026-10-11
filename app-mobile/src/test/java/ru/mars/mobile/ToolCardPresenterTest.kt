package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardPresenterTest {

    private fun tool(
        name: String,
        arguments: String = "",
        result: String? = null,
        expanded: Boolean = false,
        id: String? = "t1",
    ) = ToolExecution(id = id, name = name, arguments = arguments, result = result, expanded = expanded)

    private fun listing(lines: Int): String = (1..lines).joinToString("\n") { "line $it" }

    // --- classification ---

    @Test
    fun `terminal family is recognized by alias`() {
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("bash"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("terminal"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("shell"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("sh"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("console"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("execute_code"))
        assertEquals("Терминал", ToolKind.TERMINAL.label)
    }

    @Test
    fun `filesystem family is recognized by alias`() {
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("search_files"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("read_file"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("write_file"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("patch"))
        assertEquals("Файловая система", ToolKind.FILESYSTEM.label)
    }

    @Test
    fun `web family is recognized by alias and prefix`() {
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("web_search"))
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("web_extract"))
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("browser"))
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("browser_exec"))
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("browser_vault_fill"))
        assertEquals(ToolKind.WEB, ToolCardPresenter.classify("web_fetch"))
        assertEquals("Интернет и поиск", ToolKind.WEB.label)
    }

    @Test
    fun `mcp family is recognized by alias and qualified names`() {
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("mcp"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("tools"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("tool_call"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("tool_search"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("tool_describe"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("mcp__github__create_issue"))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("mcp__mars_osint__osint_inn_lookup"))
        assertEquals("MCP интеграция", ToolKind.MCP.label)
    }

    @Test
    fun `unknown and empty names fall back to the generic tool`() {
        assertEquals(ToolKind.GENERIC, ToolCardPresenter.classify("flux_capacitor"))
        assertEquals(ToolKind.GENERIC, ToolCardPresenter.classify(""))
        assertEquals(ToolKind.GENERIC, ToolCardPresenter.classify("   "))
        assertEquals("Инструмент", ToolKind.GENERIC.label)
    }

    @Test
    fun `classification is case-insensitive and trims whitespace`() {
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("BASH"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify(" Read_File "))
        assertEquals(ToolKind.MCP, ToolCardPresenter.classify("MCP__Github__Get_Issue"))
    }

    @Test
    fun `prefix heuristics catch close variants`() {
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("terminal_run"))
        assertEquals(ToolKind.TERMINAL, ToolCardPresenter.classify("bash_exec"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("file_search"))
        assertEquals(ToolKind.FILESYSTEM, ToolCardPresenter.classify("patch_apply"))
    }

    // --- state ---

    @Test
    fun `tool without result is running, with result is done`() {
        val running = ToolCardPresenter.present(tool("bash", "{\"command\":\"ls\"}"))
        assertEquals(ToolState.RUNNING, running.state)
        assertEquals("Выполняется…", running.stateLabel)

        val done = ToolCardPresenter.present(tool("bash", "{\"command\":\"ls\"}", result = "total 0"))
        assertEquals(ToolState.DONE, done.state)
        assertEquals("Выполнено", done.stateLabel)
    }

    @Test
    fun `empty result still counts as done`() {
        val view = ToolCardPresenter.present(tool("bash", "{}", result = ""))
        assertEquals(ToolState.DONE, view.state)
        assertEquals("Выполнено", view.stateLabel)
    }

    // --- sections ---

    @Test
    fun `arguments and result are separate sections`() {
        val view = ToolCardPresenter.present(
            tool("read_file", "{\"path\":\"a.kt\"}", result = "код файла", expanded = true),
        )
        assertEquals("Аргументы", view.argsSection?.label)
        assertEquals("{\"path\":\"a.kt\"}", view.argsSection?.fullText)
        assertEquals("Результат", view.resultSection?.label)
        assertEquals("код файла", view.resultSection?.fullText)
    }

    @Test
    fun `running tool has no result section and blank arguments have no args section`() {
        val running = ToolCardPresenter.present(tool("bash", "{\"command\":\"sleep 5\"}"))
        assertNull(running.resultSection)

        val noArgs = ToolCardPresenter.present(tool("page_info"))
        assertNull(noArgs.argsSection)
        assertNull(noArgs.resultSection)
    }

    @Test
    fun `long sections collapse at the threshold with a plural toggle`() {
        assertEquals(8, ToolCardPresenter.COLLAPSE_THRESHOLD_LINES)
        assertFalse(ToolCardPresenter.isCollapsible(listing(8)))
        assertTrue(ToolCardPresenter.isCollapsible(listing(9)))

        val view = ToolCardPresenter.present(tool("bash", "{}", result = listing(11)))
        val section = view.resultSection!!
        assertEquals(3, section.hiddenLines)
        assertEquals(listing(8), section.visibleText)
        assertEquals("Показать ещё 3 строки", section.toggleLabel)
        assertEquals(listing(11), section.fullText)
    }

    @Test
    fun `expanded long section keeps full text and offers collapse`() {
        val view = ToolCardPresenter.present(tool("bash", "{}", result = listing(11), expanded = true))
        val section = view.resultSection!!
        assertEquals(listing(11), section.visibleText)
        assertEquals("Свернуть", section.toggleLabel)
    }

    @Test
    fun `short sections need no toggle`() {
        val view = ToolCardPresenter.present(tool("bash", "{}", result = "ok"))
        assertNull(view.resultSection?.toggleLabel)
        assertEquals(0, view.resultSection?.hiddenLines)
    }

    @Test
    fun `toggle plural forms follow russian grammar`() {
        assertEquals("Показать ещё 1 строку", ToolCardPresenter.toggleLabel(listing(9), expanded = false))
        assertEquals("Показать ещё 2 строки", ToolCardPresenter.toggleLabel(listing(10), expanded = false))
        assertEquals("Показать ещё 5 строк", ToolCardPresenter.toggleLabel(listing(13), expanded = false))
        assertEquals("Показать ещё 11 строк", ToolCardPresenter.toggleLabel(listing(19), expanded = false))
        assertNull(ToolCardPresenter.toggleLabel(listing(8), expanded = false))
    }

    // --- символьный коллапс: гигантский однострочный JSON (строчный порог его не ловит) ---

    @Test
    fun `oversized single-line section is collapsible and char-capped when collapsed`() {
        val blob = "{\"content\":\"" + "x".repeat(3000) + "\"}"
        assertTrue(ToolCardPresenter.isCollapsible(blob))
        val view = ToolCardPresenter.present(tool("write_file", blob))
        val section = view.argsSection!!
        // Полный текст сохраняется для копирования, видимый — ограничен порогом.
        assertEquals(blob, section.fullText)
        assertTrue(section.visibleText.length <= ToolCardPresenter.COLLAPSE_THRESHOLD_CHARS)
        assertTrue(section.visibleText.endsWith("…"))
        // Одна строка: скрытых строк нет, но секция сворачиваемая по символам.
        assertEquals(0, section.hiddenLines)
        assertEquals("Показать полностью", section.toggleLabel)
    }

    @Test
    fun `expanded oversized single-line section keeps full text and offers collapse`() {
        val blob = "z".repeat(5000)
        val view = ToolCardPresenter.present(tool("bash", blob, expanded = true))
        val section = view.argsSection!!
        assertEquals(blob, section.visibleText)
        assertEquals("Свернуть", section.toggleLabel)
    }

    @Test
    fun `line-collapsed text also respects the char cap`() {
        // 20 строк по ~190 символов: после строчного фолда (8 строк) текст всё ещё
        // длиннее порога символов — включается символьное усечение.
        val text = (1..20).joinToString("\n") { "строка $it ".repeat(20) }
        val collapsed = ToolCardPresenter.visibleText(text, expanded = false)
        assertTrue(collapsed.length <= ToolCardPresenter.COLLAPSE_THRESHOLD_CHARS)
        assertTrue(collapsed.endsWith("…"))
        // Строчный счётчик скрытых линий не меняется.
        assertEquals(12, ToolCardPresenter.hiddenLineCount(text))
        assertEquals("Показать ещё 12 строк", ToolCardPresenter.toggleLabel(text, expanded = false))
    }

    @Test
    fun `char cap leaves ordinary payloads untouched`() {
        // До 8 строк и до порога символов — без усечения.
        assertEquals(listing(8), ToolCardPresenter.visibleText(listing(8), expanded = false))
        // Строчный коллапс из 60 коротких строк символьный порог не трогает.
        assertEquals(listing(8), ToolCardPresenter.visibleText(listing(60), expanded = false))
    }

    // --- detail hint ---

    @Test
    fun `detail hint picks the first meaningful argument`() {
        assertEquals("ls -la", ToolCardPresenter.detailLabel("{\"command\": \"ls -la\"}"))
        assertEquals("a.kt", ToolCardPresenter.detailLabel("{\"path\":\"a.kt\"}"))
        assertEquals("https://example.com", ToolCardPresenter.detailLabel("{\"url\":\"https://example.com\"}"))
        assertEquals("коты", ToolCardPresenter.detailLabel("{\"query\":\"коты\",\"limit\":5}"))
    }

    @Test
    fun `detail hint unescapes json values`() {
        assertEquals("echo \"hi\"\nls", ToolCardPresenter.detailLabel("{\"command\":\"echo \\\"hi\\\"\\nls\"}"))
    }

    @Test
    fun `detail hint truncates long values with an ellipsis`() {
        val long = "x".repeat(80)
        val hint = ToolCardPresenter.detailLabel("{\"command\":\"$long\"}")!!
        assertEquals(60, hint.length)
        assertTrue(hint.endsWith("…"))
        assertEquals(ToolCardPresenter.DETAIL_MAX_LENGTH, 60)
    }

    @Test
    fun `detail hint is null without a meaningful key or with an empty value`() {
        assertNull(ToolCardPresenter.detailLabel("{\"limit\":5}"))
        assertNull(ToolCardPresenter.detailLabel(""))
        assertNull(ToolCardPresenter.detailLabel("{\"command\":\"   \"}"))
        assertNull(ToolCardPresenter.detailLabel("не json"))
    }

    @Test
    fun `card exposes the detail hint for the collapsed plate`() {
        val view = ToolCardPresenter.present(tool("bash", "{\"command\":\"git status\"}"))
        assertEquals("git status", view.detailLabel)
    }

    // --- copy dump ---

    @Test
    fun `copy source is a structured dump with family label`() {
        val view = ToolCardPresenter.present(tool("bash", "{\"command\":\"ls\"}", result = "total 0"))
        assertEquals(
            "Инструмент: bash (Терминал)\n\nАргументы:\n{\"command\":\"ls\"}\n\nРезультат:\ntotal 0",
            view.copySource,
        )
    }

    @Test
    fun `copy source of a running tool marks the pending result and skips blank args`() {
        val view = ToolCardPresenter.present(tool("page_info"))
        assertEquals("Инструмент: page_info (Инструмент)\n\nРезультат:\nВыполняется…", view.copySource)
    }

    // --- header / chevron / palette ---

    @Test
    fun `chevron follows the expanded flag and title keeps the raw name`() {
        val collapsed = ToolCardPresenter.present(tool("web_search", "{\"query\":\"mars\"}"))
        assertFalse(collapsed.chevronUp)
        assertFalse(collapsed.expanded)
        assertEquals("web_search", collapsed.toolName)

        val expanded = ToolCardPresenter.present(tool("web_search", "{\"query\":\"mars\"}", expanded = true))
        assertTrue(expanded.chevronUp)
        assertTrue(expanded.expanded)
    }

    @Test
    fun `kind label mirrors the classification`() {
        assertEquals("MCP интеграция", ToolCardPresenter.present(tool("mcp__github__get_issue")).kindLabel)
        assertEquals("Файловая система", ToolCardPresenter.present(tool("write_file")).kindLabel)
    }

    @Test
    fun `palette comes from theme constants and reflects the state`() {
        val running = ToolCardPresenter.present(tool("bash", "{}"))
        val done = ToolCardPresenter.present(tool("bash", "{}", result = "ok"))
        assertEquals(MarsTheme.STATUS_INFO, running.stateColor)
        assertEquals(MarsTheme.STATUS_ONLINE, done.stateColor)
        assertEquals(MarsTheme.ACCENT_TEXT, done.titleColor)
        assertEquals(MarsTheme.TEXT, done.bodyColor)
        assertEquals(MarsTheme.CODE_BG, done.cardColor)
        assertEquals(MarsTheme.CODE_BORDER, done.borderColor)
    }
}
