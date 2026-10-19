package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage
import ru.mars.network.model.WorkerStatusInfo
import ru.mars.tv.TvSessionBrowserPolicy.ListOutcome
import ru.mars.tv.TvSessionBrowserPolicy.LoadOutcome
import ru.mars.tv.TvSessionBrowserPolicy.NO_SELECTION
import ru.mars.tv.TvSessionBrowserPolicy.SessionRow

/**
 * Unit tests for [TvSessionBrowserPolicy] — pure JVM, no Android framework, no HTTP.
 * Covers the «Диалоги» dialog decisions: subagent suppression (mobile drawerSessions
 * parity), row rendering (title fallback / preview / meta line with Russian plurals),
 * D-pad navigation (initial focus on the current session, clamped stepping without
 * wrap, out-of-range recovery), the loaded-detail mapping (transcript bubbles,
 * honest failure outcome) and the list-fetch verdict (offline «unavailable + retry»
 * vs the server-confirmed «empty» — an empty list from a dead radio is not evidence
 * that no dialogs exist).
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
        activeTurn: ActiveTurnInfo = ActiveTurnInfo(),
        workerStatus: WorkerStatusInfo? = null,
    ) = SessionItem(
        id = id, title = title, preview = preview, messageCount = messageCount,
        sourceTag = sourceTag, sourceLabel = sourceLabel, readOnly = readOnly,
        activeTurn = activeTurn, workerStatus = workerStatus,
    )

    private fun message(role: String, content: String, toolName: String? = null) =
        SessionMessage(role = role, content = content, toolName = toolName)

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
    fun `user role reads Вы and assistant reads Марс`() {
        val rows = TvSessionBrowserPolicy.transcript(
            detail(message("user", "привет"), message("assistant", "здравствуй"))
        )
        assertEquals(
            listOf(
                TvSessionBrowserPolicy.TranscriptRow("Вы", "привет", isUser = true),
                TvSessionBrowserPolicy.TranscriptRow("Марс", "здравствуй", isUser = false),
            ),
            rows,
        )
    }

    @Test
    fun `tool rows are folded into one tool card instead of Марс bubbles`() {
        val rows = TvSessionBrowserPolicy.transcript(
            detail(
                message("assistant", "сейчас посмотрю"),
                message("tool", "raw output 1", toolName = "read_file"),
                message("tool", "raw output 2", toolName = "read_file"),
                message("tool", "raw output 3", toolName = "grep"),
                message("assistant", "готово"),
            )
        )
        assertEquals(
            listOf("сейчас посмотрю", "3 инструмента · Готово", "готово"),
            rows.map { it.text },
        )
        val group = rows[1]
        assertEquals(TvSessionBrowserPolicy.TranscriptKind.TOOL_GROUP, group.kind)
        assertEquals(ru.mars.tv.chat.TvToolGroupPolicy.TOOL_AUTHOR, group.author)
        assertEquals(listOf("read_file", "read_file", "grep"), group.toolNames)
        // Сырой вывод инструмента на ТВ не показывается вовсе.
        assertTrue(rows.none { it.text.contains("raw output") })
    }

    @Test
    fun `separate tool runs stay separate groups and keep their place in the order`() {
        val rows = TvSessionBrowserPolicy.transcript(
            detail(
                message("tool", "o1", toolName = "a"),
                message("assistant", "между"),
                message("tool", "o2", toolName = "b"),
            )
        )
        assertEquals(
            listOf(
                TvSessionBrowserPolicy.TranscriptKind.TOOL_GROUP,
                TvSessionBrowserPolicy.TranscriptKind.MESSAGE,
                TvSessionBrowserPolicy.TranscriptKind.TOOL_GROUP,
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf("1 инструмент · Готово", "между", "1 инструмент · Готово"), rows.map { it.text })
    }

    @Test
    fun `a trailing tool run is not lost`() {
        val rows = TvSessionBrowserPolicy.transcript(
            detail(message("assistant", "думаю"), message("tool", "o", toolName = "bash"))
        )
        assertEquals(2, rows.size)
        assertEquals(TvSessionBrowserPolicy.TranscriptKind.TOOL_GROUP, rows.last().kind)
        assertEquals(listOf("bash"), rows.last().toolNames)
    }

    @Test
    fun `a tool row without a name still produces a readable group`() {
        val rows = TvSessionBrowserPolicy.transcript(detail(message("tool", "o")))
        assertEquals("1 инструмент · Готово", rows.single().text)
        assertEquals(listOf(ru.mars.tv.chat.TvToolGroupPolicy.UNKNOWN_TOOL), rows.single().toolNames)
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

    // --- list outcome: offline honesty of the list fetch ------------------------------------------------

    @Test
    fun `a fetched list with visible rows shows them regardless of connectivity`() {
        // Data beats the probe: a real (even if momentarily stale) list is navigable offline.
        val outcome = TvSessionBrowserPolicy.listOutcome(
            sessions = listOf(session("a"), session("b")),
            currentSessionId = null,
            networkAvailable = false,
        )
        assertTrue("rows must win over the offline probe", outcome is ListOutcome.Show)
        assertEquals(listOf("a", "b"), (outcome as ListOutcome.Show).rows.map { it.id })
    }

    @Test
    fun `shown rows keep the subagent suppression of rows`() {
        val outcome = TvSessionBrowserPolicy.listOutcome(
            sessions = listOf(session("parent"), session("child", sourceTag = "subagent")),
            currentSessionId = null,
            networkAvailable = true,
        )
        assertEquals(listOf("parent"), (outcome as ListOutcome.Show).rows.map { it.id })
    }

    @Test
    fun `an empty list with connectivity is the server-confirmed empty state`() {
        assertSame(
            ListOutcome.Empty,
            TvSessionBrowserPolicy.listOutcome(emptyList(), currentSessionId = null, networkAvailable = true),
        )
    }

    @Test
    fun `an empty list without connectivity is unreachable - never the empty claim`() {
        // The core regression: core-network maps every failed fetch onto emptyList(), so an
        // offline TV must not read «Список диалогов пуст» — the dialogs exist, the radio is down.
        assertSame(
            ListOutcome.Unreachable,
            TvSessionBrowserPolicy.listOutcome(emptyList(), currentSessionId = "cur", networkAvailable = false),
        )
    }

    @Test
    fun `a list of only suppressed subagent children counts as empty for the honesty verdict`() {
        val hiddenOnly = listOf(session("child", sourceTag = "subagent"))
        assertSame(
            ListOutcome.Unreachable,
            TvSessionBrowserPolicy.listOutcome(hiddenOnly, currentSessionId = null, networkAvailable = false),
        )
        assertSame(
            ListOutcome.Empty,
            TvSessionBrowserPolicy.listOutcome(hiddenOnly, currentSessionId = null, networkAvailable = true),
        )
    }

    @Test
    fun `status message hides for shown rows and reads honestly for both empty-ish states`() {
        val shown = ListOutcome.Show(listOf(SessionRow("a", "Dialog", null, null)))
        assertNull(TvSessionBrowserPolicy.listStatusMessage(shown))
        assertEquals(
            TvSessionBrowserPolicy.EMPTY_LIST,
            TvSessionBrowserPolicy.listStatusMessage(ListOutcome.Empty),
        )
        assertEquals(
            TvSessionBrowserPolicy.LIST_UNREACHABLE,
            TvSessionBrowserPolicy.listStatusMessage(ListOutcome.Unreachable),
        )
        assertNotEquals(
            "offline must never reuse the empty claim",
            TvSessionBrowserPolicy.EMPTY_LIST,
            TvSessionBrowserPolicy.LIST_UNREACHABLE,
        )
        assertTrue(TvSessionBrowserPolicy.LIST_UNREACHABLE.isNotBlank())
    }

    @Test
    fun `retry is offered only for the unreachable dead end`() {
        assertFalse(
            TvSessionBrowserPolicy.shouldOfferRetry(
                ListOutcome.Show(listOf(SessionRow("a", "Dialog", null, null)))
            )
        )
        assertFalse(
            "a server-confirmed empty list is settled - a retry would be noise",
            TvSessionBrowserPolicy.shouldOfferRetry(ListOutcome.Empty),
        )
        assertTrue(TvSessionBrowserPolicy.shouldOfferRetry(ListOutcome.Unreachable))
    }

    @Test
    fun `the retry label is a non-blank D-pad action caption`() {
        assertTrue(TvSessionBrowserPolicy.RETRY_LABEL.isNotBlank())
    }

    @Test
    fun `the current dialog row is recognised by id, blank ids never match`() {
        val row = SessionRow("s-1", "Dialog", null, null)
        assertTrue(TvSessionBrowserPolicy.isCurrent(row, "s-1"))
        assertFalse(TvSessionBrowserPolicy.isCurrent(row, "s-2"))
        assertFalse(TvSessionBrowserPolicy.isCurrent(row, null))
        assertFalse(TvSessionBrowserPolicy.isCurrent(row, "  "))
        assertFalse(TvSessionBrowserPolicy.isCurrent(SessionRow("", "Dialog", null, null), ""))
    }

    @Test
    fun `the current marker leads the meta line and other rows keep theirs unchanged`() {
        assertEquals(
            "текущий диалог · 4 сообщения",
            TvSessionBrowserPolicy.metaWithCurrent("4 сообщения", current = true),
        )
        assertEquals(
            "a current row without server meta still reads as current",
            TvSessionBrowserPolicy.CURRENT_MARKER,
            TvSessionBrowserPolicy.metaWithCurrent(null, current = true),
        )
        assertEquals(
            TvSessionBrowserPolicy.CURRENT_MARKER,
            TvSessionBrowserPolicy.metaWithCurrent("  ", current = true),
        )
        assertEquals("4 сообщения", TvSessionBrowserPolicy.metaWithCurrent("4 сообщения", current = false))
        assertNull(TvSessionBrowserPolicy.metaWithCurrent(null, current = false))
    }

    @Test
    fun `the current marker is plain russian text without glyph decoration`() {
        val marker = TvSessionBrowserPolicy.CURRENT_MARKER
        assertTrue(marker.isNotBlank())
        marker.codePoints().forEach { cp ->
            assertTrue("marker must stay in the Cyrillic/ASCII letter range", cp < 0x0500)
        }
    }

    // --- badge состояния хода (mobile ChatSessionsActivity parity) -------------------------------

    @Test
    fun `a quiet session has no badge`() {
        assertNull(TvSessionBrowserPolicy.rowOf(session("a")).badge)
    }

    @Test
    fun `an approval-waiting session carries an attention badge`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("a", activeTurn = ActiveTurnInfo(attention = SessionAttention(kind = "approval", count = 1)))
        )
        val badge = row.badge!!
        assertEquals(TvSessionBrowserPolicy.BadgeTone.ATTENTION, badge.tone)
        assertTrue(badge.text.contains("подтверждения"))
    }

    @Test
    fun `several pending decisions are counted in the badge`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("a", activeTurn = ActiveTurnInfo(attention = SessionAttention(kind = "approval", count = 3)))
        )
        assertTrue(row.badge!!.text.contains("3"))
    }

    @Test
    fun `a critical attention is worded as critical`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("a", activeTurn = ActiveTurnInfo(attention = SessionAttention(kind = "clarify", severity = "critical")))
        )
        assertTrue(row.badge!!.text.contains("Критично"))
    }

    @Test
    fun `a streaming session reads as an active turn`() {
        val row = TvSessionBrowserPolicy.rowOf(session("a", activeTurn = ActiveTurnInfo(isStreaming = true)))
        assertEquals(TvSessionBrowserPolicy.BadgeTone.LIVE, row.badge!!.tone)
        assertEquals(TvSessionBrowserPolicy.LIVE_TURN, row.badge!!.text)
    }

    @Test
    fun `a queued message reads as queued`() {
        val row = TvSessionBrowserPolicy.rowOf(session("a", activeTurn = ActiveTurnInfo(hasPendingUserMessage = true)))
        assertEquals(TvSessionBrowserPolicy.BadgeTone.QUEUED, row.badge!!.tone)
        assertEquals(TvSessionBrowserPolicy.QUEUED_TURN, row.badge!!.text)
    }

    @Test
    fun `attention outranks the live badge in the list too`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session(
                "a",
                activeTurn = ActiveTurnInfo(
                    isStreaming = true,
                    attention = SessionAttention(kind = "approval"),
                ),
            )
        )
        assertEquals(TvSessionBrowserPolicy.BadgeTone.ATTENTION, row.badge!!.tone)
    }

    @Test
    fun `a subagent row shows the work goal as its caption`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("sub", sourceTag = "subagent", workerStatus = WorkerStatusInfo(goal = "правит парсер", status = "running"))
        )
        assertEquals("правит парсер", row.work)
    }

    @Test
    fun `a blank goal is not a caption`() {
        val row = TvSessionBrowserPolicy.rowOf(
            session("sub", sourceTag = "subagent", workerStatus = WorkerStatusInfo(goal = "   "))
        )
        assertNull(row.work)
    }

    @Test
    fun `a normal session never borrows a work caption`() {
        val row = TvSessionBrowserPolicy.rowOf(session("a", workerStatus = WorkerStatusInfo(goal = "не моё")))
        assertNull(row.work)
    }

    @Test
    fun `badges are plain russian text - the tv design gate forbids glyphs`() {
        listOf(
            TvSessionBrowserPolicy.LIVE_TURN,
            TvSessionBrowserPolicy.QUEUED_TURN,
            TvSessionBrowserPolicy.rowOf(
                session("a", activeTurn = ActiveTurnInfo(attention = SessionAttention(kind = "approval")))
            ).badge!!.text,
        ).forEach { text ->
            text.codePoints().forEach { cp ->
                assertTrue("badge must stay in the Cyrillic/ASCII range: $text", cp < 0x0500)
            }
        }
    }
}
