package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {
    @Test fun startsOnlyWithNonBlankMessage() {
        val state = ChatUiState()
        assertFalse(state.begin("   "))
        assertTrue(state.begin("hello"))
        assertFalse(state.begin("again"))
    }

    @Test fun completionAndFailureAllowNextMessage() {
        val state = ChatUiState()
        state.begin("hello")
        state.complete()
        assertEquals(ChatStatus.RECEIVED, state.status)
        assertTrue(state.begin("again"))
        state.fail()
        assertEquals(ChatStatus.ERROR, state.status)
        assertTrue(state.begin("retry"))
    }

    @Test fun cancelMarksStreamIdleAndAllowsNextMessage() {
        val state = ChatUiState()
        state.begin("hello")
        state.cancel()
        assertEquals(ChatStatus.IDLE, state.status)
        assertTrue(state.begin("again"))
    }
}
