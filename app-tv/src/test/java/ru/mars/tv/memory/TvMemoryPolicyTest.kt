package ru.mars.tv.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TvMemoryPolicy] на чистой JVM (без Android, без сети): парсинг
 * реального payload `GET /api/memory`, пустые/отсутствующие поля, тексты секций,
 * строка «Обновлено N назад» по mtime, счётчик заполненных разделов.
 */
class TvMemoryPolicyTest {

    private val now = 1_700_000_000_000L

    /** Форма реального ответа hermes-webui api/route_memory.py (mtime — epoch-секунды, null допустим). */
    private val fullPayload = """
        {
          "memory": "# Память\n- устойчивый факт",
          "user": "Профиль: Стеф",
          "soul": "",
          "project_context": "Контекст рабочего пространства",
          "memory_path": "/root/.hermes/memories/MEMORY.md",
          "user_path": "/root/.hermes/memories/USER.md",
          "soul_path": "/root/.hermes/SOUL.md",
          "project_context_path": "/root/workspace/AGENTS.md",
          "project_context_name": "AGENTS.md",
          "project_context_workspace": "/root/workspace",
          "memory_mtime": 1699998000.5,
          "user_mtime": null,
          "soul_mtime": null,
          "project_context_mtime": 1699989200.0,
          "project_context_shadowed": false,
          "external_notes_enabled": false
        }
    """.trimIndent()

    private val fullSnapshot = TvMemoryPolicy.parse(fullPayload)!!

    @Test
    fun `full payload parses into all sections and mtimes`() {
        assertEquals("# Память\n- устойчивый факт", fullSnapshot.memory)
        assertEquals("Профиль: Стеф", fullSnapshot.user)
        assertEquals("", fullSnapshot.soul)
        assertEquals("Контекст рабочего пространства", fullSnapshot.projectContext)
        assertEquals("AGENTS.md", fullSnapshot.projectContextName)
        assertEquals(1699998000.5, fullSnapshot.memoryMtime!!, 0.001)
        assertEquals(1699989200.0, fullSnapshot.projectContextMtime!!, 0.001)
        assertNull("null-mtime остаётся null", fullSnapshot.userMtime)
        assertNull(fullSnapshot.soulMtime)
    }

    @Test
    fun `broken payloads never parse into a fake snapshot`() {
        assertNull(TvMemoryPolicy.parse("не json"))
        assertNull(TvMemoryPolicy.parse(""))
        assertNull(TvMemoryPolicy.parse("[1,2,3]"))
    }

    @Test
    fun `empty json object is a valid snapshot with blank sections`() {
        val snapshot = TvMemoryPolicy.parse("{}")!!
        assertEquals("", snapshot.memory)
        assertEquals("", snapshot.user)
        assertEquals("", snapshot.soul)
        assertEquals("", snapshot.projectContext)
        assertEquals("", snapshot.projectContextName)
        assertEquals(0, TvMemoryPolicy.filledCount(snapshot))
    }

    @Test
    fun `non-numeric or null mtime fields parse as null`() {
        val snapshot = TvMemoryPolicy.parse(
            """{"memory":"x","memory_mtime":"вчера","user_mtime":null,"soul_mtime":-5}"""
        )!!
        assertNull(snapshot.memoryMtime)
        assertNull(snapshot.userMtime)
        assertNull("отрицательный mtime трактуется как отсутствующий", snapshot.soulMtime)
    }

    @Test
    fun `bodyText trims and empty sections get an explicit empty line`() {
        val snapshot = TvMemoryPolicy.parse("""{"memory":"  текст  ","user":"   "}""")!!
        assertEquals("текст", TvMemoryPolicy.bodyText(TvMemoryTab.MEMORY, snapshot))
        assertNull(TvMemoryPolicy.emptyLine(TvMemoryTab.MEMORY, snapshot))
        assertEquals("Секция USER.md пуста", TvMemoryPolicy.emptyLine(TvMemoryTab.USER, snapshot))
        assertEquals("Секция SOUL.md пуста", TvMemoryPolicy.emptyLine(TvMemoryTab.SOUL, snapshot))
    }

    @Test
    fun `project tab shows the server-provided context file name`() {
        assertEquals("AGENTS.md", TvMemoryPolicy.fileNameFor(TvMemoryTab.PROJECT, fullSnapshot))
        val noName = TvMemoryPolicy.parse("""{"project_context":"текст"}""")!!
        assertEquals(
            "пустое project_context_name откатывается к AGENTS.md",
            "AGENTS.md",
            TvMemoryPolicy.fileNameFor(TvMemoryTab.PROJECT, noName),
        )
        val named = TvMemoryPolicy.parse("""{"project_context_name":"WORKSPACE.md"}""")!!
        assertEquals("WORKSPACE.md", TvMemoryPolicy.fileNameFor(TvMemoryTab.PROJECT, named))
    }

    @Test
    fun `tab labels and file names match the section contract`() {
        assertEquals(listOf("Память", "Профиль", "Душа", "Контекст"), TvMemoryTab.values().map { it.label })
        assertEquals(
            listOf("MEMORY.md", "USER.md", "SOUL.md", "AGENTS.md"),
            TvMemoryTab.values().map { it.fileName },
        )
    }

    @Test
    fun `updatedLine renders age from epoch-second mtime`() {
        // 1_700_000_000_000 мс - 1_699_998_000_500 мс = 33 мин.
        assertEquals("Обновлено 33 мин. назад", TvMemoryPolicy.updatedLine(TvMemoryTab.MEMORY, fullSnapshot, now))
        assertEquals("Обновлено 3 ч. назад", TvMemoryPolicy.updatedLine(TvMemoryTab.PROJECT, fullSnapshot, now))
    }

    @Test
    fun `updatedLine is null without mtime and clamps a future mtime to just now`() {
        assertNull(TvMemoryPolicy.updatedLine(TvMemoryTab.USER, fullSnapshot, now))
        val future = fullSnapshot.copy(memoryMtime = (now + 600_000L) / 1000.0)
        assertEquals("Обновлено только что", TvMemoryPolicy.updatedLine(TvMemoryTab.MEMORY, future, now))
    }

    @Test
    fun `summaryLine counts only non-blank sections`() {
        assertEquals("Заполнено разделов: 3 из 4", TvMemoryPolicy.summaryLine(fullSnapshot))
        val none = TvMemoryPolicy.parse("{}")!!
        assertEquals("Заполнено разделов: 0 из 4", TvMemoryPolicy.summaryLine(none))
    }

    @Test
    fun `metaLine joins file name and age with a middle dot`() {
        assertEquals("MEMORY.md · Обновлено 33 мин. назад", TvMemoryPolicy.metaLine(TvMemoryTab.MEMORY, fullSnapshot, now))
        assertEquals("USER.md", TvMemoryPolicy.metaLine(TvMemoryTab.USER, fullSnapshot, now))
    }

    @Test
    fun `cache window matches the 24h contract of the slow-changing screens`() {
        assertEquals(24L * 60 * 60 * 1000, TvMemoryPolicy.CACHE_MAX_AGE_MS)
        assertTrue(TvMemoryPolicy.UPDATED_PREFIX.startsWith("Обновлено"))
    }
}
