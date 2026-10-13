package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.StreamBufferGuard

/**
 * Integration of [StreamBufferGuard] into [ChatUiState]: streamed chunk accumulation must be
 * hard-limited (per-frame clamp + sliding window), keep the FRESHEST tail, carry exactly one
 * truncation marker, and leave thought/tool/Done handling untouched.
 */
class ChatUiStateStreamBufferTest {

    private val marker = StreamBufferGuard.TRUNCATION_MARKER

    private fun stateWith(frameChars: Int, accumulatedChars: Int) =
        ChatUiState(StreamBufferGuard(maxFrameChars = frameChars, maxAccumulatedChars = accumulatedChars))

    private fun markerCount(text: String): Int {
        var count = 0; var idx = text.indexOf(marker)
        while (idx >= 0) { count++; idx = text.indexOf(marker, idx + marker.length) }
        return count
    }

    @Test fun smallChunksAccumulateVerbatim() {
        val state = stateWith(100, 1_000)
        state.begin("hi")
        state.onEvent(ChatEvent.Chunk("hello "))
        state.onEvent(ChatEvent.Chunk("world"))
        assertEquals("hello world", state.messages.last().text)
        assertEquals(0L, state.streamDroppedChars)
    }

    @Test fun oversizedFrameIsClampedWithMarker() {
        val state = stateWith(10, 1_000)
        state.begin("hi")
        state.onEvent(ChatEvent.Chunk("X".repeat(500)))
        val text = state.messages.last().text
        assertTrue(text.length <= 1_000)
        assertTrue(text.startsWith("XXXXXXXXXX"))
        assertEquals(1, markerCount(text))
        assertEquals(490L, state.streamDroppedChars)
    }

    @Test fun windowSlideKeepsFreshestTailWithSingleMarker() {
        val state = stateWith(100, 200)
        state.begin("hi")
        // 40 chunks of 20 chars → 800 chars total, far over the 200-char window.
        repeat(40) { i -> state.onEvent(ChatEvent.Chunk("%02d".format(i).repeat(10))) }
        val text = state.messages.last().text
        // Hard invariant: the buffer never exceeds the accumulated cap.
        assertTrue("text length ${text.length} must be <= 200", text.length <= 200)
        // Exactly one marker, at the head.
        assertEquals(1, markerCount(text))
        assertTrue(text.startsWith(marker))
        // The freshest tail survives: the last chunk (39…) is still visible.
        assertTrue(text.endsWith("39".repeat(10)))
        assertTrue(state.streamDroppedChars > 0)
    }

    @Test fun repeatedTruncationNeverStacksMarkers() {
        val state = stateWith(30, 80)
        state.begin("hi")
        repeat(50) { state.onEvent(ChatEvent.Chunk("Y".repeat(30))) }
        val text = state.messages.last().text
        assertTrue(text.length <= 80)
        assertEquals(1, markerCount(text))
    }

    @Test fun thoughtsToolsAndDoneAreUnaffectedByGuard() {
        val state = stateWith(5, 40)
        state.begin("hi")
        state.onEvent(ChatEvent.Thought("a long thought that must survive the text guard"))
        state.onEvent(ChatEvent.ToolCall("1", "terminal", "{cmd: ls}"))
        state.onEvent(ChatEvent.ToolResult("1", "terminal", "ok"))
        state.onEvent(ChatEvent.Chunk("Z".repeat(500)))
        state.onEvent(ChatEvent.Done)
        val message = state.messages.last()
        assertEquals(listOf("a long thought that must survive the text guard"), message.thoughts)
        assertEquals("ok", message.tools.single().result)
        assertEquals(MessageStatus.COMPLETE, message.status)
        assertEquals(ChatStatus.RECEIVED, state.status)
        assertTrue(message.text.length <= 40)
    }

    @Test fun droppedCharsResetOnTurnAndSessionBoundaries() {
        val state = stateWith(10, 100)
        state.begin("hi")
        state.onEvent(ChatEvent.Chunk("X".repeat(200)))
        assertTrue(state.streamDroppedChars > 0)
        state.complete()
        state.begin("next turn")
        assertEquals(0L, state.streamDroppedChars)
        state.onEvent(ChatEvent.Chunk("X".repeat(200)))
        assertTrue(state.streamDroppedChars > 0)
        state.complete()
        state.startNewSession()
        assertEquals(0L, state.streamDroppedChars)
    }

    @Test fun productionDefaultsKeepTranscriptVerbatimForNormalStreams() {
        val state = ChatUiState() // production guard: 32K frame / 96K accumulated (TranscriptCapPolicy.MAX_BUBBLE_CHARS)
        state.begin("hi")
        val normal = "обычный ответ ассистента. "
        repeat(1_000) { state.onEvent(ChatEvent.Chunk(normal)) } // ~26K chars — well under cap
        assertEquals(normal.repeat(1_000), state.messages.last().text)
        assertEquals(0L, state.streamDroppedChars)
    }
}
