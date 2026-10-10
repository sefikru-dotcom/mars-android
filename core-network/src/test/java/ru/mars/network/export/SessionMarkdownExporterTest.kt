package ru.mars.network.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * JVM-тесты экспорта транскрипта в Markdown: структура документа, роли (включая
 * инструменты/мысли/субагентов), опции включения, нормализация спецсимволов,
 * пустые входы и политика имени файла.
 */
class SessionMarkdownExporterTest {

    private fun session(
        id: String = "sess-1",
        title: String? = "Поездка в горы",
        sourceLabel: String? = "WebUI",
        sourceTag: String? = "webui",
        parentSessionId: String? = null,
        readOnly: Boolean = false,
    ) = SessionItem(
        id = id,
        title = title,
        preview = "preview",
        createdAt = "2026-10-01T10:00:00",
        updatedAt = "2026-10-02T11:00:00",
        messageCount = 2,
        sourceLabel = sourceLabel,
        sourceTag = sourceTag,
        parentSessionId = parentSessionId,
        readOnly = readOnly,
    )

    private fun msg(role: String, content: String, createdAt: String? = "2026-10-01T10:05:00") =
        SessionMessage(id = null, role = role, content = content, createdAt = createdAt, eventId = null)

    private fun export(
        messages: List<SessionMessage>,
        session: SessionItem? = session(),
        options: SessionExportOptions = SessionExportOptions(),
    ) = SessionMarkdownExporter(options).export(session, messages)

    // --- структура документа -----------------------------------------------------------------------

    @Test
    fun `exports heading metadata and transcript structure`() {
        val md = export(
            listOf(
                msg("user", "Привет, как дела?"),
                msg("assistant", "Всё хорошо! **Отлично**."),
            ),
        )

        assertTrue(md.startsWith("# Поездка в горы\n"))
        assertTrue(md.contains("- **ID:** `sess-1`"))
        assertTrue(md.contains("- **Источник:** WebUI"))
        assertTrue(md.contains("- **Создана:** 2026-10-01T10:00:00"))
        assertTrue(md.contains("- **Обновлена:** 2026-10-02T11:00:00"))
        assertTrue(md.contains("- **Сообщений в транскрипте:** 2"))
        assertTrue(md.contains("\n---\n"))
        assertTrue(md.contains("## \uD83D\uDC64 Пользователь · 2026-10-01T10:05:00\n\nПривет, как дела?\n"))
        assertTrue(md.contains("## \uD83E\uDD16 Ассистент · 2026-10-01T10:05:00\n\nВсё хорошо! **Отлично**.\n"))
        // документ заканчивается ровно одним переводом строки
        assertTrue(md.endsWith("\n"))
        assertFalse(md.endsWith("\n\n"))
    }

    @Test
    fun `empty session exports placeholder without crashing`() {
        val md = export(emptyList())

        assertTrue(md.contains("# Поездка в горы"))
        assertTrue(md.contains("_В сессии нет сообщений._"))
        assertFalse(md.contains("## "))
    }

    @Test
    fun `all-filtered transcript also reports emptiness`() {
        val md = export(
            listOf(msg("thought", "думаю"), msg("tool", "{}")),
            options = SessionExportOptions(includeThoughts = false, includeTools = false),
        )
        assertTrue(md.contains("_В сессии нет сообщений._"))
    }

    @Test
    fun `null session still exports valid document`() {
        val md = export(listOf(msg("user", "текст")), session = null)

        assertTrue(md.startsWith("# Экспорт диалога\n"))
        assertFalse(md.contains("- **ID:**"))
        assertTrue(md.contains("текст"))
    }

    @Test
    fun `blank title falls back to session id heading`() {
        val md = export(listOf(msg("user", "текст")), session = session(title = "  "))
        assertTrue(md.startsWith("# Сессия sess-1\n"))
    }

    // --- роли: инструменты, мысли, система, неизвестные ---------------------------------------------

    @Test
    fun `tool output goes to fenced code block and long backtick runs extend fence`() {
        val md = export(listOf(msg("tool", "результат ``` команды")))
        assertTrue("fence длиннее собственных бэктиков контента", md.contains("````text\nрезультат ``` команды\n````"))
        assertTrue(md.contains("## \uD83D\uDEE0\uFE0F Инструмент"))
    }

    @Test
    fun `plain tool output uses triple fence`() {
        val md = export(listOf(msg("tool", "{\"exit_code\": 0}")))
        assertTrue(md.contains("```text\n{\"exit_code\": 0}\n```"))
    }

    @Test
    fun `thoughts and system render as blockquotes and can be filtered`() {
        val md = export(listOf(msg("thought", "Шаг 1\nШаг 2"), msg("system", "политика")))
        assertTrue(md.contains("## \uD83D\uDCAD Мысль"))
        assertTrue(md.contains("> Шаг 1\n> Шаг 2"))
        assertTrue(md.contains("## ⚙️ Система"))
        assertTrue(md.contains("> политика"))

        val noThoughts = export(
            listOf(msg("thought", "Шаг 1"), msg("user", "видимое")),
            options = SessionExportOptions(includeThoughts = false),
        )
        assertFalse(noThoughts.contains("Мысль"))
        assertFalse(noThoughts.contains("Шаг 1"))
        assertTrue(noThoughts.contains("видимое"))
    }

    @Test
    fun `tools can be filtered out`() {
        val md = export(
            listOf(msg("tool", "вывод"), msg("user", "вопрос")),
            options = SessionExportOptions(includeTools = false),
        )
        assertFalse(md.contains("вывод"))
        assertTrue(md.contains("вопрос"))
    }

    @Test
    fun `unknown role is preserved with raw label`() {
        val md = export(listOf(msg("observer", "наблюдаю")))
        assertTrue(md.contains("## ❓ observer"))
        assertTrue(md.contains("наблюдаю"))
    }

    @Test
    fun `role vocabulary is classified`() {
        assertEquals(ExportRole.USER, SessionExportPolicy.roleOf("User"))
        assertEquals(ExportRole.ASSISTANT, SessionExportPolicy.roleOf(" assistant "))
        assertEquals(ExportRole.TOOL, SessionExportPolicy.roleOf("tool_call"))
        assertEquals(ExportRole.TOOL, SessionExportPolicy.roleOf("function"))
        assertEquals(ExportRole.THOUGHT, SessionExportPolicy.roleOf("reasoning"))
        assertEquals(ExportRole.THOUGHT, SessionExportPolicy.roleOf("thinking"))
        assertEquals(ExportRole.SYSTEM, SessionExportPolicy.roleOf("system"))
        assertEquals(ExportRole.UNKNOWN, SessionExportPolicy.roleOf(""))
    }

    // --- субагенты ----------------------------------------------------------------------------------

    @Test
    fun `subagent session gets lineage note with parent and read-only`() {
        val md = export(
            listOf(msg("assistant", "отчёт субагента")),
            session = session(sourceLabel = "Subagent", sourceTag = "subagent", parentSessionId = "parent-9", readOnly = true),
        )
        assertTrue(md.contains("> \uD83E\uDDE9 **Сессия субагента** · родитель: `parent-9` · только чтение"))
        assertTrue(md.contains("- **Источник:** Subagent"))
        assertTrue(md.contains("отчёт субагента"))
    }

    @Test
    fun `subagent without parent id renders note alone`() {
        val md = export(
            listOf(msg("user", "x")),
            session = session(sourceTag = "subagent", parentSessionId = null),
        )
        assertTrue(md.contains("> \uD83E\uDDE9 **Сессия субагента**\n"))
        assertFalse(md.contains("родитель:"))
    }

    // --- спецсимволы и нормализация -------------------------------------------------------------------

    @Test
    fun `special characters and emoji survive export intact`() {
        val md = export(listOf(msg("assistant", "🚀 emoji, \"кавычки\", `код`, #заголовок, \\слэш")))
        assertTrue(md.contains("🚀 emoji, \"кавычки\", `код`, #заголовок, \\слэш"))
    }

    @Test
    fun `crlf is normalized and control characters stripped`() {
        val md = export(listOf(msg("assistant", "строка1\r\nстрока2\u0000конец\u0007")))
        assertFalse("в документе не остаётся CR", md.contains("\r"))
        assertTrue(md.contains("строка1\nстрока2 конец"))
    }

    @Test
    fun `blank message content renders placeholder`() {
        val md = export(listOf(msg("user", "   \n  ")))
        assertTrue(md.contains("_пусто_"))
    }

    @Test
    fun `timestamps option hides times from headings`() {
        val md = export(
            listOf(msg("user", "текст")),
            options = SessionExportOptions(includeTimestamps = false),
        )
        assertTrue(md.contains("## \uD83D\uDC64 Пользователь\n"))
        assertFalse(md.contains("10:05"))
    }

    @Test
    fun `metadata option hides the info block`() {
        val md = export(
            listOf(msg("user", "текст")),
            options = SessionExportOptions(includeMetadata = false),
        )
        assertFalse(md.contains("- **ID:**"))
        assertTrue(md.contains("# Поездка в горы"))
    }

    // --- SessionDetail overload -----------------------------------------------------------------------

    @Test
    fun `session detail overload exports id title and messages`() {
        val detail = SessionDetail(
            id = "det-42",
            title = "Детальная сессия",
            createdAt = "2026-10-01T09:00:00",
            updatedAt = "2026-10-01T09:30:00",
            messages = listOf(msg("user", "вопрос"), msg("assistant", "ответ")),
        )

        val md = SessionMarkdownExporter().export(detail)

        assertTrue(md.startsWith("# Детальная сессия\n"))
        assertTrue(md.contains("- **ID:** `det-42`"))
        assertTrue(md.contains("- **Сообщений в транскрипте:** 2"))
        assertTrue(md.contains("вопрос"))
        assertTrue(md.contains("ответ"))
    }

    // --- политика имени файла ----------------------------------------------------------------------------

    @Test
    fun `file name is sanitized bounded and falls back without title`() {
        val named = SessionExportPolicy.fileNameFor("abcdef12345678", "../../etc/passwd: Злой <файл>?")
        assertFalse("нет разделителей пути", named.contains('/') || named.contains('\\'))
        assertFalse("нет служебных символов", named.contains(':') || named.contains('?') || named.contains("<"))
        assertTrue(named.endsWith(".md"))
        assertTrue("хвост id различает файлы", named.contains("abcdef12"))
        assertTrue(named.length <= SessionExportPolicy.MAX_FILE_NAME_CHARS + 3)

        val untitled = SessionExportPolicy.fileNameFor("abcdef12345678", "   ")
        assertEquals("session-abcdef12.md", untitled)

        val longTitle = SessionExportPolicy.fileNameFor("id-1", "очень длинный заголовок ".repeat(10))
        assertTrue(longTitle.length <= SessionExportPolicy.MAX_FILE_NAME_CHARS + 3)
    }

    @Test
    fun `file name keeps unicode letters and digits`() {
        val name = SessionExportPolicy.fileNameFor("sess-0001", "Поездка в горы 2026")
        assertEquals("Поездка-в-горы-2026-sess-000.md", name)
    }
}
