package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionItem

class ChatUiStateTest {
    @Test fun startsOnlyWithNonBlankMessage() { val state = ChatUiState(); assertFalse(state.begin("   ")); assertTrue(state.begin("hello")); assertFalse(state.begin("again")) }
    @Test fun richEventsAttachToAssistantMessage() {
        val state = ChatUiState(); state.begin("hello")
        state.onEvent(ChatEvent.Thought("inspect code")); state.onEvent(ChatEvent.ToolCall("1", "terminal", "{cmd: ls}")); state.onEvent(ChatEvent.ToolResult("1", "terminal", "ok")); state.onEvent(ChatEvent.Chunk("done")); state.onEvent(ChatEvent.Done)
        val message = state.messages.last(); assertEquals("done", message.text); assertEquals(listOf("inspect code"), message.thoughts); assertEquals("ok", message.tools.single().result); assertEquals(MessageStatus.COMPLETE, message.status); assertEquals(ChatStatus.RECEIVED, state.status)
    }
    @Test fun sessionsAndNewChatResetConversation() {
        val state = ChatUiState(); state.setSessions(listOf(SessionItem("s1", "Работа"))); state.begin("hello"); state.onEvent(ChatEvent.SessionBound("s1")); assertEquals("s1", state.activeSessionId); assertEquals(1, state.sessions.size)
        state.startNewSession(); assertNull(state.activeSessionId); assertTrue(state.messages.isEmpty()); assertEquals(ChatStatus.IDLE, state.status)
    }
    @Test fun collapsibleRichBlocksToggle() {
        val state = ChatUiState(); state.begin("hello"); state.onEvent(ChatEvent.Thought("why")); state.onEvent(ChatEvent.ToolCall("1", "read_file", "x")); state.toggleThoughts(1); state.toggleTool(1, 0); assertTrue(state.messages[1].thoughtsExpanded); assertTrue(state.messages[1].tools[0].expanded)
    }
    @Test fun completionAndFailureAllowNextMessage() { val state = ChatUiState(); state.begin("hello"); state.complete(); assertEquals(ChatStatus.RECEIVED, state.status); assertTrue(state.begin("again")); state.fail(); assertEquals(ChatStatus.ERROR, state.status); assertTrue(state.begin("retry")) }
    @Test fun cancelMarksStreamIdleAndAllowsNextMessage() { val state = ChatUiState(); state.begin("hello"); state.cancel(); assertEquals(ChatStatus.IDLE, state.status); assertTrue(state.begin("again")) }
}
