package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateCodeBlocksTest {

    @Test
    fun `code blocks are collapsed by default and toggled per key`() {
        val state = ChatUiState()
        assertFalse(state.isCodeBlockExpanded(0, 0))

        state.toggleCodeBlock(0, 0)
        assertTrue(state.isCodeBlockExpanded(0, 0))
        assertFalse(state.isCodeBlockExpanded(0, 1))
        assertFalse(state.isCodeBlockExpanded(1, 0))

        state.toggleCodeBlock(0, 0)
        assertFalse(state.isCodeBlockExpanded(0, 0))
    }

    @Test
    fun `expansion state is cleared on new session`() {
        val state = ChatUiState()
        state.toggleCodeBlock(2, 3)
        assertTrue(state.isCodeBlockExpanded(2, 3))

        state.startNewSession()
        assertFalse(state.isCodeBlockExpanded(2, 3))
    }

    @Test
    fun `expansion state is cleared when binding another session history`() {
        val state = ChatUiState()
        state.toggleCodeBlock(0, 1)
        assertTrue(state.isCodeBlockExpanded(0, 1))

        state.bindSession("session-2", "Другая сессия", emptyList())
        assertFalse(state.isCodeBlockExpanded(0, 1))
    }
}
