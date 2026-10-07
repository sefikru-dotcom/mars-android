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

    @Test fun approvalEventRecordsPendingPayloadAndThoughtTrace() {
        val state = ChatUiState(); state.begin("hello")
        val payload = ru.mars.network.model.ApprovalPayload(approvalId = "a1", command = "rm -rf /tmp/x", description = "Удаление каталога", pendingCount = 2)
        state.onEvent(ChatEvent.ApprovalRequired(payload))
        assertEquals(payload, state.pendingApproval)
        assertTrue(state.messages.last().thoughts.any { it.contains("Требуется подтверждение") && it.contains("Удаление каталога") })
    }

    @Test fun clarifyEventRecordsPendingPayloadWithChoicesTrace() {
        val state = ChatUiState(); state.begin("hello")
        val payload = ru.mars.network.model.ClarifyPayload(clarifyId = "c1", question = "Какой профиль?", choices = listOf("default", "office"))
        state.onEvent(ChatEvent.ClarifyRequired(payload))
        assertEquals(payload, state.pendingClarify)
        assertTrue(state.messages.last().thoughts.any { it.contains("Какой профиль?") && it.contains("default / office") })
    }

    @Test fun pendingInteractiveSurvivesWithoutAssistantBubble() {
        val state = ChatUiState()
        val payload = ru.mars.network.model.ApprovalPayload(approvalId = "a2", command = "ls", description = "")
        state.onEvent(ChatEvent.ApprovalRequired(payload))
        assertEquals(payload, state.pendingApproval)
    }

    @Test fun resolveAndLifecycleTransitionsClearPendingInteractive() {
        val state = ChatUiState(); state.begin("hello")
        state.onEvent(ChatEvent.ApprovalRequired(ru.mars.network.model.ApprovalPayload(approvalId = "a1", command = "c", description = "")))
        state.resolveApproval(); assertNull(state.pendingApproval)
        state.onEvent(ChatEvent.ClarifyRequired(ru.mars.network.model.ClarifyPayload(clarifyId = "c1", question = "q")))
        state.onEvent(ChatEvent.Done); assertNull(state.pendingClarify)

        state.begin("again"); state.onEvent(ChatEvent.ClarifyRequired(ru.mars.network.model.ClarifyPayload(clarifyId = "c2", question = "q2")))
        state.fail(); assertNull(state.pendingClarify)

        state.begin("retry"); state.onEvent(ChatEvent.ApprovalRequired(ru.mars.network.model.ApprovalPayload(approvalId = "a3", command = "c", description = "")))
        state.cancel(); assertNull(state.pendingApproval)

        state.begin("next"); state.onEvent(ChatEvent.ClarifyRequired(ru.mars.network.model.ClarifyPayload(clarifyId = "c3", question = "q3")))
        state.startNewSession(); assertNull(state.pendingClarify)
    }

    @Test fun bindSessionClearsPendingInteractive() {
        val state = ChatUiState(); state.begin("hello")
        state.onEvent(ChatEvent.ApprovalRequired(ru.mars.network.model.ApprovalPayload(approvalId = "a1", command = "c", description = "")))
        state.bindSession("s2", "Другая", emptyList())
        assertNull(state.pendingApproval)
    }
}
