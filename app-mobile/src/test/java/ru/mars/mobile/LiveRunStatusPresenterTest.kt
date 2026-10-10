package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRunStatusPresenterTest {

    @Test
    fun `plate is hidden whenever no turn is streaming`() {
        assertSame(LiveRunStatusView.HIDDEN, LiveRunStatusPresenter.resolve(ChatStatus.IDLE))
        assertSame(LiveRunStatusView.HIDDEN, LiveRunStatusPresenter.resolve(ChatStatus.RECEIVED))
        assertSame(LiveRunStatusView.HIDDEN, LiveRunStatusPresenter.resolve(ChatStatus.ERROR))
        assertFalse(LiveRunStatusView.HIDDEN.visible)
        assertEquals("", LiveRunStatusView.HIDDEN.label)
        assertFalse(LiveRunStatusView.HIDDEN.showSpinner)
    }

    @Test
    fun `idle streaming turn falls back to the neutral think label`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING)
        assertTrue(view.visible)
        assertEquals(LiveRunPhase.THINK, view.phase)
        assertEquals("Думаю…", view.label)
        assertTrue(view.showSpinner)
        assertNull(view.detailLabel)
    }

    @Test
    fun `server phase maps tool and command markers to execute`() {
        assertEquals(
            "Выполняю команду…",
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "tool_call").label,
        )
        assertEquals(
            LiveRunPhase.EXECUTE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "running_command").phase,
        )
        assertEquals(
            LiveRunPhase.EXECUTE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "exec_bash").phase,
        )
    }

    @Test
    fun `server phase maps thinking markers to analyze`() {
        assertEquals(
            "Анализирую код…",
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "thinking").label,
        )
        assertEquals(
            LiveRunPhase.ANALYZE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "reasoning").phase,
        )
    }

    @Test
    fun `server phase maps response markers to compose`() {
        assertEquals(
            "Формирую ответ…",
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "responding").label,
        )
        assertEquals(
            LiveRunPhase.COMPOSE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "stream_answer").phase,
        )
    }

    @Test
    fun `phase mapping is case-insensitive and ignores unknown markers`() {
        assertEquals(LiveRunPhase.EXECUTE, LiveRunStatusPresenter.phaseFromServer("TOOL_CALL"))
        assertEquals(LiveRunPhase.ANALYZE, LiveRunStatusPresenter.phaseFromServer(" Thinking "))
        assertNull(LiveRunStatusPresenter.phaseFromServer("whatever"))
        assertNull(LiveRunStatusPresenter.phaseFromServer(null))
        assertNull(LiveRunStatusPresenter.phaseFromServer("   "))
    }

    @Test
    fun `execute markers win over analyze markers in one phase string`() {
        assertEquals(
            LiveRunPhase.EXECUTE,
            LiveRunStatusPresenter.phaseFromServer("thinking_then_tool_exec"),
        )
    }

    @Test
    fun `running tool implies execute when the server sends no phase`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "bash")
        assertEquals(LiveRunPhase.EXECUTE, view.phase)
        assertEquals("Выполняю команду…", view.label)
    }

    @Test
    fun `detail hint names the tool family and raw name`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "bash")
        assertEquals("Терминал · bash", view.detailLabel)
        assertEquals(" · ", LiveRunStatusPresenter.DETAIL_SEPARATOR)
    }

    @Test
    fun `detail hint works for unknown and qualified tool names`() {
        assertEquals(
            "MCP интеграция · mcp__github__get_issue",
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "mcp__github__get_issue").detailLabel,
        )
        assertEquals(
            "Инструмент · flux",
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "flux").detailLabel,
        )
    }

    @Test
    fun `blank running tool name is ignored everywhere`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "   ", hasText = true)
        assertEquals(LiveRunPhase.COMPOSE, view.phase)
        assertNull(view.detailLabel)
    }

    @Test
    fun `explicit server phase wins over streaming signals`() {
        val view = LiveRunStatusPresenter.resolve(
            ChatStatus.TYPING, phase = "thinking", runningToolName = "bash", hasText = true, hasThoughts = true,
        )
        assertEquals(LiveRunPhase.ANALYZE, view.phase)
        // The tool hint still rides along even when the phase came from the server.
        assertEquals("Терминал · bash", view.detailLabel)
    }

    @Test
    fun `signal priority is tool, then text, then thoughts`() {
        assertEquals(
            LiveRunPhase.EXECUTE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, runningToolName = "patch", hasText = true, hasThoughts = true).phase,
        )
        assertEquals(
            LiveRunPhase.COMPOSE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, hasText = true, hasThoughts = true).phase,
        )
        assertEquals(
            LiveRunPhase.ANALYZE,
            LiveRunStatusPresenter.resolve(ChatStatus.TYPING, hasThoughts = true).phase,
        )
    }

    @Test
    fun `unknown server phase falls through to streaming signals`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING, phase = "whatever", hasThoughts = true)
        assertEquals(LiveRunPhase.ANALYZE, view.phase)
        assertEquals("Анализирую код…", view.label)
    }

    @Test
    fun `every phase carries a human label without glyphs`() {
        assertEquals("Анализирую код…", LiveRunPhase.ANALYZE.label)
        assertEquals("Выполняю команду…", LiveRunPhase.EXECUTE.label)
        assertEquals("Формирую ответ…", LiveRunPhase.COMPOSE.label)
        assertEquals("Думаю…", LiveRunPhase.THINK.label)
        LiveRunPhase.entries.forEach { phase ->
            assertTrue(phase.label.none { it.code > 0x2100 })
        }
    }

    @Test
    fun `palette comes from theme constants, never hardcoded`() {
        val view = LiveRunStatusPresenter.resolve(ChatStatus.TYPING)
        assertEquals(MarsTheme.TEXT_EM, view.labelColor)
        assertEquals(MarsTheme.TEXT_MUTED, view.detailColor)
        assertEquals(MarsTheme.ACCENT, view.accentColor)
        assertEquals(MarsTheme.SURFACE_CARD, view.cardColor)
        assertEquals(MarsTheme.BORDER, view.borderColor)
        assertEquals(MarsTheme.ACCENT, LiveRunStatusView.HIDDEN.accentColor)
    }
}
