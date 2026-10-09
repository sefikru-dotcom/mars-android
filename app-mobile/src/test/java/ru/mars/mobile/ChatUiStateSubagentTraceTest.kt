package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.StreamEventMapper

/**
 * Leak-free routing of delegation/subagent traffic through [ChatUiState.onEvent]:
 * the main bubble text is written ONLY by [ChatEvent.Chunk]; subagent lifecycle
 * frames (mapped by [StreamEventMapper] into [ChatEvent.Thought]) land in the
 * collapsible thoughts tray, and `delegate_task` tool frames stay in the tool tray.
 * Regression pin for the «streaming as a sheet of gibberish» complaint: raw child
 * output / SSE JSON must never be appended to the assistant message text.
 */
class ChatUiStateSubagentTraceTest {

    @Test fun subagentThoughtTraceNeverEntersMainText() {
        val state = ChatUiState()
        state.begin("собери релиз")
        state.onEvent(ChatEvent.Thought("Субагент 1/2 запущен: Сборка APK"))
        state.onEvent(ChatEvent.Chunk("Готово."))
        state.onEvent(ChatEvent.Thought("Субагент 1/2 завершён (completed): APK собран"))
        val assistant = state.messages.last { it.role == "assistant" }
        assertEquals("Готово.", assistant.text)
        assertEquals(
            listOf("Субагент 1/2 запущен: Сборка APK", "Субагент 1/2 завершён (completed): APK собран"),
            assistant.thoughts,
        )
    }

    @Test fun delegateTaskToolFramesStayInToolTrayNotText() {
        val state = ChatUiState()
        state.begin("делегируй задачу")
        state.onEvent(ChatEvent.ToolCall("t1", "delegate_task", """{"tasks":[{"goal":"Сборка"}]}"""))
        state.onEvent(ChatEvent.ToolResult("t1", "delegate_task", """{"status":"dispatched","delegation_id":"d1"}"""))
        state.onEvent(ChatEvent.Chunk("Задача делегирована."))
        val assistant = state.messages.last { it.role == "assistant" }
        assertEquals("Задача делегирована.", assistant.text)
        assertEquals(1, assistant.tools.size)
        assertEquals("delegate_task", assistant.tools.single().name)
    }

    @Test fun wireSubagentFramesThroughMapperEndUpInThoughtsOnly() {
        // End-to-end: real wire payloads → StreamEventMapper → ChatUiState.
        val state = ChatUiState()
        state.begin("сложная задача")
        val frames = listOf(
            "subagent.start" to """{"goal":"Проверить CI","task_index":1,"task_count":1,"child_session_id":"c1"}""",
            "subagent.text" to """{"text":"стена сырого текста дочернего агента"}""",
            "subagent.complete" to """{"status":"completed","goal":"Проверить CI","summary":"CI зелёный","output_tail":"{\"raw\":\"json wall\"}"}""",
            "token" to """{"text":"Всё готово."}""",
        )
        for ((name, payload) in frames) {
            StreamEventMapper.map(name, null, payload)?.let(state::onEvent)
        }
        val assistant = state.messages.last { it.role == "assistant" }
        assertEquals("Всё готово.", assistant.text)
        assertEquals(2, assistant.thoughts.size)
        assertTrue(assistant.thoughts[0].contains("Субагент 1/1 запущен: Проверить CI"))
        assertTrue(assistant.thoughts[1].contains("CI зелёный"))
        val transcript = assistant.text + assistant.thoughts.joinToString()
        assertTrue("raw child output leaked", "стена сырого" !in transcript)
        assertTrue("raw output_tail json leaked", "json wall" !in transcript)
    }
}
