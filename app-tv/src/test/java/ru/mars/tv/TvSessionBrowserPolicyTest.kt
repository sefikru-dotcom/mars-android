package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage
import ru.mars.tv.TvSessionBrowserPolicy.LoadOutcome
import ru.mars.tv.TvSessionBrowserPolicy.NO_SELECTION

/**
 * Unit tests for [TvSessionBrowserPolicy] — pure JVM, no Android framework, no HTTP.
 * Covers the «Диалоги» dialog decisions: subagent suppression (mobile drawerSessions
 * parity), row rendering (title fallback / preview / meta line with Russian plurals),
 * D-pad navigation (initial focus on the current session, clamped stepping without
 * wrap, out-of-range recovery) and the loaded-detail mapping (transcript bubbles,
 * honest failure outcome).
 */
class TvSessionBrowserPolicyTest {

    private fun session(
        id: String,
        title: String? = null,
        preview: String? = null,
        messageCount: Int? = null,
        sourceTag: String? = null,
        sourceLabel: String? = null,
        readOnly: Boolean = false,
    ) = SessionItem(
        id = id, title = title, preview = preview, messageCount = messageCount,
        sourceTag = sourceTag, sourceLabel = sourceLabel, readOnly = readOnly,
    )

    private fun message(role: String, content: String) = SessionMessage(role = role, content = content)

    // --- visible sessions (subagent suppression, mobile drawerSessions parity) -------------------

    @Test
    fun `top-level sessions are all listed`() {
        val sessions = listOf(session("a"), session("b"), session("c"))
        assertEquals(listOf("a", "b", "c"), TvSessionBrowserPolicy.visibleSessions(sessions).map { it.id })
    }

    @Test
    fun `subagent children are suppressed by default`() {
        val sessions = listOf(session("a"), session("sub", sourceTag = "subagent"), session("b"))
        assertEquals(listOf("a", "b"), TvSessionBrowserPolicy.visibleSessions(sessions).map { it.id })
    }

    @Test
    fun `the current session stays visible even when it is a subagent child`() {
        val sessions = listOf(session("a"), session("sub", sourceTag = "subagent"), session("sub2", sourceTag = "subagent"))
        val visible = TvSessionBrowserPolicy.visibleSessions(sessions, currentSessionId = "sub")
        assertEquals(listOf("a", "sub"), visible.map { it.id })
    }

    @Test
    fun `read-only non-subagent rows are never hidden`() {
        val sessions = listOf(session("ro", readOnly = true))
        assertEquals(listOf("ro"), TvSessionBrowserPolicy.visibleSessions(sessions).map { it.id })
    }

    @Test
    fun `empty session list produces no rows`() {
        assertTrue(TvSessionBrowserPolicy.rows(emptyList()).isEmpty())
    }

    @Test
    fun `rows map the visible sessions in server order`() {
        val sessions = listOf(session("a", title = "A"), session("sub", sourceTag = "subagent"), session("b", title = "B"))
        assertEquals(listOf("a", "b"), TvSessionBrowserPolicy.rows(sessions).map { it.id })
    }

    // --- row rendering -----------------------------------------------------------------------------

    @Test
    fun `title is used as-is and trimmed`() {
        assertEquals("Полёт на Марс", TvSessionBrowserPolicy.rowOf(session("a", title = "  Полёт на Марс ")).title)
    }

    @Test
    fun `null or blank title falls back to the untitled label`() {
        assertEquals(TvSessionBrowserPolicy.UNTITLED, TvSessionBrowserPolicy.rowOf(session("a", title = null)).title)
        assertEquals(TvSessionBrowserPolicy.UNTITLED, TvSessionBrowserPolicy.rowOf(session("a", title = "   ")).title)
    }

    @Test
    fun `preview becomes the subtitle, blank preview stays null`() {
        assertEquals("Привет", TvSessionBrowserPolicy.rowOf(session("a", preview = " Привет ")).subtitle)
        assertNull(TvSessionBrowserPolicy.rowOf(session("a", preview = null)).subtitle)
        assertNull(TvSessionBrowserPolicy.rowOf(session("a", preview = "  ")).subtitle)
    }

    @Test
    fun `message count is pluralized into the meta line`() {
        assertEquals("1 сообщение", TvSessionBrowserPolicy.rowOf(session("a", messageCount = 1)).meta)
        assertEquals("3 сообщения", TvSessionBrowserPolicy.rowOf(session("a", messageCount = 3)).meta)
        assertEquals("17 сообщений", TvSessionBrowserPolicy.rowOf(session("a", messageCount = 17)).meta)
    }

    @Test
    fun `zero or missing message count adds no meta`() {
        assertNull(TvSessionBrowserPolicy.rowOf(session("a", messageCount = null)).meta)
        assertNull(TvSessionBrowserPolicy.rowOf(session("a", messageCount = 0)).meta)
    }

    @Test
    fun `subagent meta prefers the source label and falls back to the generic marker`() {
        assertEquals("Subagent", TvSessionBrowserPolicy.rowOf(session("s", sourceTag = "subagent", sourceLabel = "Subagent")).meta)
        assertEquals("субагент", TvSessionBrowserPolicy.rowOf(session("s", sourceTag = "subagent", sourceLabel = " ")).meta)
        assertEquals("субагент", TvSessionBrowserPolicy.rowOf(session("s", sourceTag = "subagent")).meta)
    }

    @Test
    fun `read-only adds the marker to the meta line`() {
        assertEquals("только чтение", TvSessionBrowserPolicy.rowOf(session("a", readOnly = true)).meta)
    }

    @Test
    fun `meta parts join with a middle dot in a stable order`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("s", messageCount = 5, sourceTag = "subagent", sourceLabel = "Subagent", readOnly = true)
        )
        assertEquals("5 сообщений · Subagent · только чтение", row.meta)
    }

    @Test
    fun `russian plural covers the teen and decade edges`() {
        assertEquals("сообщение", TvSessionBrowserPolicy.messageWord(1))
        assertEquals("сообщение", TvSessionBrowserPolicy.messageWord(21))
        assertEquals("сообщение", TvSessionBrowserPolicy.messageWord(101))
        assertEquals("сообщения", TvSessionBrowserPolicy.messageWord(2))
        assertEquals("сообщения", TvSessionBrowserPolicy.messageWord(24))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(5))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(11))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(12))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(14))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(111))
        assertEquals("сообщений", TvSessionBrowserPolicy.messageWord(0))
    }

    // --- D-pad navigation ---------------------------------------------------------------------------

    @Test
    fun `dialog opens focused on the current session row`() {
        val rows = TvSessionBrowserPolicy.rows(listOf(session("a"), session("b"), session("c")))
        assertEquals(1, TvSessionBrowserPolicy.initialSelection(rows, "b"))
    }

    @Test
    fun `dialog opens on the first row when nothing is bound or the id is not listed`() {
        val rows = TvSessionBrowserPolicy.rows(listOf(session("a"), session("b")))
        assertEquals(0, TvSessionBrowserPolicy.initialSelection(rows, null))
        assertEquals(0, TvSessionBrowserPolicy.initialSelection(rows, "gone"))
    }

    @Test
    fun `empty dialog has no selection`() {
        assertEquals(NO_SELECTION, TvSessionBrowserPolicy.initialSelection(emptyList(), "a"))
    }

    @Test
    fun `stepping moves one row per press and stays at the edges - never wraps`() {
        assertEquals(1, TvSessionBrowserPolicy.move(0, 3, 1))
        assertEquals(0, TvSessionBrowserPolicy.move(1, 3, -1))
        assertEquals("bottom edge stays", 2, TvSessionBrowserPolicy.move(2, 3, 1))
        assertEquals("top edge stays", 0, TvSessionBrowserPolicy.move(0, 3, -1))
    }

    @Test
    fun `an out-of-range selection recovers towards the travelled direction`() {
        assertEquals(0, TvSessionBrowserPolicy.move(NO_SELECTION, 3, 1))
        assertEquals(2, TvSessionBrowserPolicy.move(NO_SELECTION, 3, -1))
        assertEquals(0, TvSessionBrowserPolicy.move(7, 3, 1))
        assertEquals(2, TvSessionBrowserPolicy.move(-4, 3, -1))
    }

    @Test
    fun `nothing to focus means no selection`() {
        assertEquals(NO_SELECTION, TvSessionBrowserPolicy.move(0, 0, 1))
        assertEquals(NO_SELECTION, TvSessionBrowserPolicy.move(0, -1, -1))
    }

    @Test
    fun `walking down a dialog visits every row once then stops`() {
        var index = TvSessionBrowserPolicy.move(NO_SELECTION, 4, 1)
        val visited = mutableListOf(index)
        while (true) {
            val next = TvSessionBrowserPolicy.move(index, 4, 1)
            if (next == index) break
            index = next
            visited += index
        }
        assertEquals(listOf(0, 1, 2, 3), visited)
    }

    @Test
    fun `selected resolves the row under the index`() {
        val rows = TvSessionBrowserPolicy.rows(listOf(session("a"), session("b")))
        assertEquals("b", TvSessionBrowserPolicy.selected(rows, 1)?.id)
        assertNull(TvSessionBrowserPolicy.selected(rows, 2))
        assertNull(TvSessionBrowserPolicy.selected(rows, NO_SELECTION))
        assertNull(TvSessionBrowserPolicy.selected(emptyList(), 0))
    }

    // --- loaded session → transcript ----------------------------------------------------------------

    private fun detail(vararg messages: SessionMessage) =
        SessionDetail(id = "s1", title = "T", messages = messages.toList())

    @Test
    fun `user role reads Вы and everything else reads Марс`() {
        val rows = TvSessionBrowserPolicy.transcript(
            detail(message("user", "привет"), message("assistant", "здравствуй"), message("tool", "output"))
        )
        assertEquals(
            listOf(
                TvSessionBrowserPolicy.TranscriptRow("Вы", "привет", isUser = true),
                TvSessionBrowserPolicy.TranscriptRow("Марс", "здравствуй", isUser = false),
                TvSessionBrowserPolicy.TranscriptRow("Марс", "output", isUser = false),
            ),
            rows,
        )
    }

    @Test
    fun `transcript keeps the server order`() {
        val rows = TvSessionBrowserPolicy.transcript(detail(message("user", "1"), message("assistant", "2"), message("user", "3")))
        assertEquals(listOf("1", "2", "3"), rows.map { it.text })
        assertTrue(rows.map { it.isUser } == listOf(true, false, true))
    }

    @Test
    fun `blank persisted messages are dropped`() {
        val rows = TvSessionBrowserPolicy.transcript(detail(message("assistant", ""), message("user", "   "), message("assistant", "ответ")))
        assertEquals(listOf("ответ"), rows.map { it.text })
    }

    @Test
    fun `session without messages produces an empty transcript`() {
        assertTrue(TvSessionBrowserPolicy.transcript(detail()).isEmpty())
    }

    // --- load outcome --------------------------------------------------------------------------------

    @Test
    fun `a detail with a real id opens`() {
        val d = detail(message("user", "hi"))
        val outcome = TvSessionBrowserPolicy.loadOutcome(d)
        assertTrue(outcome is LoadOutcome.Open)
        assertSame(d, (outcome as LoadOutcome.Open).detail)
    }

    @Test
    fun `null detail fails with the honest message`() {
        val outcome = TvSessionBrowserPolicy.loadOutcome(null)
        assertTrue(outcome is LoadOutcome.Failed)
        assertEquals(TvSessionBrowserPolicy.LOAD_FAILED, (outcome as LoadOutcome.Failed).message)
    }

    @Test
    fun `detail without an id fails - there is nothing to bind the next turn to`() {
        val outcome = TvSessionBrowserPolicy.loadOutcome(SessionDetail(id = "  "))
        assertTrue(outcome is LoadOutcome.Failed)
    }

    @Test
    fun `failed outcome never carries a session`() {
        assertFalse(TvSessionBrowserPolicy.loadOutcome(null) is LoadOutcome.Open)
    }
}
