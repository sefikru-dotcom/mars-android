package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionAttention

/**
 * Unit tests for the reattach/active-turn half of [ChatUiState]: the reattached streaming
 * bubble, the attention/pending banner state and their lifecycle across session swaps and
 * turn settling (the 404-recovery path drops an empty placeholder, never real content).
 */
class ChatUiStateReattachTest {

    private val state = ChatUiState()

    private fun bindWithHistory() {
        state.bindSession(
            "s-1", "Диалог",
            listOf(ChatMessage("user", "привет"), ChatMessage("assistant", "здорово")),
        )
    }

    // --- beginReattachedTurn -----------------------------------------------------------------

    @Test
    fun `reattach opens a streaming bubble without appending a user message`() {
        bindWithHistory()
        assertTrue(state.beginReattachedTurn())
        assertEquals(ChatStatus.TYPING, state.status)
        assertTrue(state.reattachedTurn)
        // History untouched + exactly one new assistant bubble.
        assertEquals(3, state.messages.size)
        assertEquals("assistant", state.messages.last().role)
        assertEquals(MessageStatus.STREAMING, state.messages.last().status)
    }

    @Test
    fun `double attach cannot create a second bubble`() {
        bindWithHistory()
        assertTrue(state.beginReattachedTurn())
        assertFalse(state.beginReattachedTurn())
        assertEquals(3, state.messages.size)
    }

    @Test
    fun `locally started turn blocks a reattach bubble`() {
        bindWithHistory()
        assertTrue(state.begin("новый вопрос"))
        assertFalse(state.beginReattachedTurn())
        assertFalse(state.reattachedTurn)
    }

    @Test
    fun `streamed chunks land in the reattached bubble`() {
        bindWithHistory()
        state.beginReattachedTurn()
        state.onEvent(ChatEvent.Chunk("часть "))
        state.onEvent(ChatEvent.Chunk("ответа"))
        assertEquals("часть ответа", state.messages.last().text)
    }

    @Test
    fun `done settles the reattached turn and clears banner flags`() {
        bindWithHistory()
        state.setActiveTurn(ActiveTurnInfo(activeStreamId = "st-1", isStreaming = true, attention = SessionAttention("approval")))
        state.beginReattachedTurn()
        state.onEvent(ChatEvent.Chunk("готово"))
        state.onEvent(ChatEvent.Done)
        assertEquals(ChatStatus.RECEIVED, state.status)
        assertFalse(state.reattachedTurn)
        assertNull(state.attention)
        assertFalse(state.hasPendingUserMessage)
        assertEquals(MessageStatus.COMPLETE, state.messages.last().status)
    }

    // --- cancelReattachedTurn (404 recovery) -------------------------------------------------

    @Test
    fun `404 recovery drops the empty placeholder bubble`() {
        bindWithHistory()
        state.beginReattachedTurn()
        state.cancelReattachedTurn()
        assertEquals(2, state.messages.size)
        assertEquals(ChatStatus.IDLE, state.status)
        assertFalse(state.reattachedTurn)
    }

    @Test
    fun `404 recovery keeps partially streamed content`() {
        bindWithHistory()
        state.beginReattachedTurn()
        state.onEvent(ChatEvent.Chunk("успело доехать"))
        state.cancelReattachedTurn()
        assertEquals(3, state.messages.size)
        assertEquals("успело доехать", state.messages.last().text)
        assertEquals(MessageStatus.COMPLETE, state.messages.last().status)
        assertEquals(ChatStatus.IDLE, state.status)
    }

    // --- active-turn banner state -------------------------------------------------------------

    @Test
    fun `setActiveTurn projects attention and queued message`() {
        bindWithHistory()
        state.setActiveTurn(
            ActiveTurnInfo(
                activeStreamId = "st-9",
                isStreaming = false,
                hasPendingUserMessage = true,
                attention = SessionAttention(kind = "clarify", count = 2, severity = "question"),
            )
        )
        assertEquals(SessionAttention("clarify", 2, "question"), state.attention)
        assertTrue(state.hasPendingUserMessage)
    }

    @Test
    fun `session swap clears the previous session banner`() {
        bindWithHistory()
        state.setActiveTurn(ActiveTurnInfo(activeStreamId = "st-1", attention = SessionAttention("approval")))
        state.bindSession("s-2", "Другой", emptyList())
        assertNull(state.attention)
        assertFalse(state.hasPendingUserMessage)
        assertFalse(state.reattachedTurn)
    }

    @Test
    fun `new chat clears the banner too`() {
        bindWithHistory()
        state.setActiveTurn(ActiveTurnInfo(hasPendingUserMessage = true))
        state.startNewSession()
        assertNull(state.attention)
        assertFalse(state.hasPendingUserMessage)
    }

    @Test
    fun `error settles the reattached turn into the error state`() {
        bindWithHistory()
        state.beginReattachedTurn()
        state.onEvent(ChatEvent.Error("HTTP 500"))
        assertEquals(ChatStatus.ERROR, state.status)
        assertFalse(state.reattachedTurn)
        assertEquals(MessageStatus.ERROR, state.messages.last().status)
    }
}
