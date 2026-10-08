package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent

/**
 * Streaming activity display modes (WebUI parity: stream_full / stream_window /
 * stream_quiet) — expansion policy of [ChatUiState] plus the pure
 * [ActivityDisplayMode] value semantics and [ActivityModeSelection] persistence.
 */
class ActivityDisplayModeTest {

    // --- enum semantics -----------------------------------------------------

    @Test fun defaultModeIsStreamWindow() {
        assertEquals(ActivityDisplayMode.STREAM_WINDOW, ActivityDisplayMode.DEFAULT)
    }

    @Test fun wireValuesMatchWebUiSettingsKeys() {
        assertEquals("stream_full", ActivityDisplayMode.STREAM_FULL.wire)
        assertEquals("stream_window", ActivityDisplayMode.STREAM_WINDOW.wire)
        assertEquals("stream_quiet", ActivityDisplayMode.STREAM_QUIET.wire)
    }

    @Test fun headerLabelsAreRussianPlates() {
        assertEquals("Всё", ActivityDisplayMode.STREAM_FULL.label)
        assertEquals("Окно", ActivityDisplayMode.STREAM_WINDOW.label)
        assertEquals("Тишина", ActivityDisplayMode.STREAM_QUIET.label)
    }

    @Test fun nextCyclesFullWindowQuietFull() {
        assertEquals(ActivityDisplayMode.STREAM_WINDOW, ActivityDisplayMode.STREAM_FULL.next())
        assertEquals(ActivityDisplayMode.STREAM_QUIET, ActivityDisplayMode.STREAM_WINDOW.next())
        assertEquals(ActivityDisplayMode.STREAM_FULL, ActivityDisplayMode.STREAM_QUIET.next())
    }

    @Test fun fromWireParsesKnownValuesAndFallsBackToDefault() {
        assertEquals(ActivityDisplayMode.STREAM_FULL, ActivityDisplayMode.fromWire("stream_full"))
        assertEquals(ActivityDisplayMode.STREAM_QUIET, ActivityDisplayMode.fromWire(" stream_quiet "))
        assertEquals(ActivityDisplayMode.DEFAULT, ActivityDisplayMode.fromWire(null))
        assertEquals(ActivityDisplayMode.DEFAULT, ActivityDisplayMode.fromWire(""))
        assertEquals(ActivityDisplayMode.DEFAULT, ActivityDisplayMode.fromWire("stream_everything"))
    }

    // --- selection + storage port -------------------------------------------

    private class FakeModeStorage : ActivityModeStorage {
        var saved: ActivityDisplayMode? = null
        var saveCount = 0
        override fun load(): ActivityDisplayMode = saved ?: ActivityDisplayMode.DEFAULT
        override fun save(mode: ActivityDisplayMode) { saved = mode; saveCount++ }
    }

    @Test fun selectionStartsFromStoredValueOrDefault() {
        assertEquals(ActivityDisplayMode.STREAM_WINDOW, ActivityModeSelection(FakeModeStorage()).current)
        val storage = FakeModeStorage().apply { saved = ActivityDisplayMode.STREAM_FULL }
        val selection = ActivityModeSelection(storage)
        assertEquals(ActivityDisplayMode.STREAM_FULL, selection.current)
        assertEquals("Всё", selection.buttonLabel())
    }

    @Test fun selectPersistsPickAndSurvivesReload() {
        val storage = FakeModeStorage()
        ActivityModeSelection(storage).select(ActivityDisplayMode.STREAM_QUIET)
        assertEquals(ActivityDisplayMode.STREAM_QUIET, storage.saved)
        assertEquals(1, storage.saveCount)
        assertEquals(ActivityDisplayMode.STREAM_QUIET, ActivityModeSelection(storage).current)
    }

    @Test fun cycleRotatesAndPersists() {
        val storage = FakeModeStorage()
        val selection = ActivityModeSelection(storage)
        assertEquals(ActivityDisplayMode.STREAM_QUIET, selection.cycle())
        assertEquals(ActivityDisplayMode.STREAM_FULL, selection.cycle())
        assertEquals(ActivityDisplayMode.STREAM_WINDOW, selection.cycle())
        assertEquals(3, storage.saveCount)
    }

    // --- ChatUiState expansion policy ----------------------------------------

    private fun streamingState(mode: ActivityDisplayMode): ChatUiState {
        val state = ChatUiState()
        state.setActivityMode(mode)
        state.begin("hello")
        state.onEvent(ChatEvent.Thought("plan"))
        state.onEvent(ChatEvent.ToolCall("1", "terminal", "{cmd: ls}"))
        state.onEvent(ChatEvent.ToolResult("1", "terminal", "ok"))
        state.onEvent(ChatEvent.ToolCall("2", "read_file", "{path: x}"))
        return state
    }

    @Test fun windowModeExpandsOnlyRunningToolAndCollapsesFinishedOnes() {
        val message = streamingState(ActivityDisplayMode.STREAM_WINDOW).messages.last()
        assertFalse(message.tools[0].expanded) // finished -> «Готово» plate
        assertTrue(message.tools[1].expanded)  // still running -> live
        assertFalse(message.thoughtsExpanded)
    }

    @Test fun windowModeCollapsesRunningToolWhenItsResultArrives() {
        val state = streamingState(ActivityDisplayMode.STREAM_WINDOW)
        state.onEvent(ChatEvent.ToolResult("2", "read_file", "content"))
        assertTrue(state.messages.last().tools.none { it.expanded })
    }

    @Test fun fullModeKeepsThoughtsAndAllToolsExpanded() {
        val message = streamingState(ActivityDisplayMode.STREAM_FULL).messages.last()
        assertTrue(message.thoughtsExpanded)
        assertTrue(message.tools.all { it.expanded })
    }

    @Test fun quietModeKeepsEverythingCollapsed() {
        val message = streamingState(ActivityDisplayMode.STREAM_QUIET).messages.last()
        assertFalse(message.thoughtsExpanded)
        assertTrue(message.tools.none { it.expanded })
    }

    @Test fun fullModeExpandsThoughtsAsTheyStreamIn() {
        val state = ChatUiState()
        state.setActivityMode(ActivityDisplayMode.STREAM_FULL)
        state.begin("hello")
        state.onEvent(ChatEvent.Thought("first"))
        assertTrue(state.messages.last().thoughtsExpanded)
        state.onEvent(ChatEvent.Thought("second"))
        assertTrue(state.messages.last().thoughtsExpanded)
    }

    @Test fun windowModeDoesNotAutoExpandThoughts() {
        val state = ChatUiState()
        state.setActivityMode(ActivityDisplayMode.STREAM_WINDOW)
        state.begin("hello")
        state.onEvent(ChatEvent.Thought("first"))
        assertFalse(state.messages.last().thoughtsExpanded)
    }

    @Test fun doneCollapsesWindowAndQuietButKeepsFullExpanded() {
        for (mode in listOf(ActivityDisplayMode.STREAM_WINDOW, ActivityDisplayMode.STREAM_QUIET)) {
            val state = streamingState(mode)
            state.onEvent(ChatEvent.Done)
            val message = state.messages.last()
            assertTrue(message.tools.none { it.expanded })
            assertFalse(message.thoughtsExpanded)
        }
        val full = streamingState(ActivityDisplayMode.STREAM_FULL)
        full.onEvent(ChatEvent.Done)
        assertTrue(full.messages.last().tools.all { it.expanded })
        assertTrue(full.messages.last().thoughtsExpanded)
    }

    @Test fun setActivityModeReappliesPolicyToTranscript() {
        val state = streamingState(ActivityDisplayMode.STREAM_QUIET)
        state.setActivityMode(ActivityDisplayMode.STREAM_FULL)
        val message = state.messages.last()
        assertTrue(message.thoughtsExpanded)
        assertTrue(message.tools.all { it.expanded })
        state.setActivityMode(ActivityDisplayMode.STREAM_WINDOW)
        val windowed = state.messages.last()
        assertFalse(windowed.tools[0].expanded)
        assertTrue(windowed.tools[1].expanded)
        assertFalse(windowed.thoughtsExpanded)
    }

    @Test fun setActivityModeIgnoresUserMessages() {
        val state = ChatUiState()
        state.setActivityMode(ActivityDisplayMode.STREAM_FULL)
        state.begin("hello")
        state.onEvent(ChatEvent.Chunk("answer"))
        state.onEvent(ChatEvent.Done)
        // Only the assistant transcript carries thoughts/tools; the user bubble is untouched.
        assertFalse(state.messages[0].thoughtsExpanded)
        assertTrue(state.messages[0].tools.isEmpty())
    }

    @Test fun uiStateDefaultsToWindowMode() {
        assertEquals(ActivityDisplayMode.STREAM_WINDOW, ChatUiState().activityMode)
    }
}
