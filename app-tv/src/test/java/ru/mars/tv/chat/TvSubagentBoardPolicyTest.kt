package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus

/** Доска живых субагентов на ТВ (разрыв M: кадры subagent.* уходили в никуда). */
class TvSubagentBoardPolicyTest {

    private fun child(id: String, state: SubagentState, phase: String? = null, name: String? = null) =
        SubagentStatus(childId = id, state = state, phase = phase, displayName = name)

    @Test
    fun `an empty board is invisible`() {
        val board = TvSubagentBoardPolicy.present(emptyMap())
        assertFalse(board.visible)
        assertEquals(0, board.activeCount)
    }

    @Test
    fun `a running child is visible with its name and state`() {
        val board = TvSubagentBoardPolicy.present(
            mapOf("c1" to child("c1", SubagentState.Running, phase = "читает репозиторий", name = "Исследователь")),
        )
        assertTrue(board.visible)
        assertEquals(TvSubagentBoardPolicy.Tone.RUNNING, board.tone)
        assertEquals("Исследователь — работает · шаг: читает репозиторий", board.lines.single())
    }

    @Test
    fun `a child without a display name falls back to its id - no invented names`() {
        val board = TvSubagentBoardPolicy.present(mapOf("c9" to child("c9", SubagentState.Completed)))
        assertEquals("c9 — готово", board.lines.single())
    }

    @Test
    fun `active children come first, terminal ones after`() {
        val board = TvSubagentBoardPolicy.present(
            mapOf(
                "done" to child("done", SubagentState.Completed),
                "run" to child("run", SubagentState.Running),
                "wait" to child("wait", SubagentState.Waiting),
            ),
        )
        assertTrue(board.lines.first().startsWith("run"))
        assertTrue(board.lines[1].startsWith("wait"))
        assertTrue(board.lines[2].startsWith("done"))
        assertEquals(2, board.activeCount)
    }

    @Test
    fun `the header counts working children`() {
        val board = TvSubagentBoardPolicy.present(
            mapOf(
                "a" to child("a", SubagentState.Running),
                "b" to child("b", SubagentState.Running),
                "c" to child("c", SubagentState.Failed),
            ),
        )
        assertEquals("Субагенты · 2 из 3 работают", board.header)
    }

    @Test
    fun `a board with no active children is settled`() {
        val board = TvSubagentBoardPolicy.present(
            mapOf("a" to child("a", SubagentState.Completed), "b" to child("b", SubagentState.Stopped)),
        )
        assertEquals(0, board.activeCount)
        assertEquals(TvSubagentBoardPolicy.Tone.DONE, board.tone)
        assertEquals("Субагенты · 2", board.header)
    }

    @Test
    fun `a failed child tones the settled board as an error`() {
        val board = TvSubagentBoardPolicy.present(mapOf("a" to child("a", SubagentState.Failed)))
        assertEquals(TvSubagentBoardPolicy.Tone.ERROR, board.tone)
        assertEquals("a — провал", board.lines.single())
    }

    @Test
    fun `the board never eats the transcript - overflow collapses into a counter`() {
        val board = TvSubagentBoardPolicy.present(
            (1..6).associate { "c$it" to child("c$it", SubagentState.Running) },
        )
        assertEquals(TvSubagentBoardPolicy.MAX_LINES, board.lines.size)
        assertEquals("и ещё 3", board.overflow)
        assertEquals(6, board.activeCount)
    }

    @Test
    fun `the ready-to-render text carries the header, the lines and the overflow`() {
        val board = TvSubagentBoardPolicy.present(
            (1..5).associate { "c$it" to child("c$it", SubagentState.Running) },
        )
        val lines = board.text.split("\n")
        assertEquals(board.header, lines.first())
        assertEquals(TvSubagentBoardPolicy.MAX_LINES + 2, lines.size)
        assertEquals("и ещё 2", lines.last())
    }

    @Test
    fun `every state has a russian label and an explicit tone`() {
        assertEquals(TvSubagentBoardPolicy.Tone.RUNNING, TvSubagentBoardPolicy.tone(SubagentState.Running))
        assertEquals(TvSubagentBoardPolicy.Tone.DONE, TvSubagentBoardPolicy.tone(SubagentState.Completed))
        assertEquals(TvSubagentBoardPolicy.Tone.ERROR, TvSubagentBoardPolicy.tone(SubagentState.Failed))
        assertEquals(TvSubagentBoardPolicy.Tone.WARNING, TvSubagentBoardPolicy.tone(SubagentState.Waiting))
        assertEquals(TvSubagentBoardPolicy.Tone.WARNING, TvSubagentBoardPolicy.tone(SubagentState.Stopped))
        SubagentState.entries.forEach { state ->
            assertTrue(TvSubagentBoardPolicy.statusLabel(state).isNotBlank())
        }
    }

    @Test
    fun `no glyphs in the board text - the tv design gate forbids them`() {
        val board = TvSubagentBoardPolicy.present(
            mapOf("c1" to child("c1", SubagentState.Running, phase = "шаг 1")),
        )
        assertNull(
            Regex("[\\u2190-\\u21FF\\u2600-\\u27BF\\uD83C-\\uDBFF]").find(board.text),
        )
    }
}
