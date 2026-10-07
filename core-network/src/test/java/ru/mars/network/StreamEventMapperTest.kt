package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.StreamEventMapper

/**
 * Mapper tests against the real hermes-webui SSE payloads
 * (api/streaming.py: tool/tool_complete/interim_assistant/approval/clarify/
 * title/warning/pending_steer_leftover, done → title → stream_end ordering).
 */
class StreamEventMapperTest {

    @Test fun mapsToolStartedWithTidPreviewAndArgs() {
        val payload = """{"event_type":"tool.started","name":"terminal","preview":null,"args":{"command":"ls"},"tid":"call_42"}"""
        val event = StreamEventMapper.map("tool", "e1", payload)
        assertTrue(event is ChatEvent.ToolCall)
        event as ChatEvent.ToolCall
        assertEquals("call_42", event.id)
        assertEquals("terminal", event.name)
        assertTrue(event.arguments.contains("\"command\":\"ls\""))
    }

    @Test fun mapsToolStartedWithoutTidLegacyPath() {
        val payload = """{"event_type":"tool.started","name":"search_files","preview":"pattern=abc","args":{}}"""
        val event = StreamEventMapper.map("tool", null, payload) as ChatEvent.ToolCall
        assertNull(event.id)
        assertEquals("search_files", event.name)
        assertEquals("pattern=abc", event.arguments)
    }

    @Test fun mapsToolCompletePreviewAsResultWithTidAndErrorFlag() {
        val payload = """{"event_type":"tool.completed","name":"terminal","preview":"total 12\ndrwx","args":{"command":"ls"},"tid":"call_42","is_error":false}"""
        val event = StreamEventMapper.map("tool_complete", "e2", payload) as ChatEvent.ToolResult
        assertEquals("call_42", event.id)
        assertEquals("terminal", event.name)
        assertEquals("total 12\ndrwx", event.result)
    }

    @Test fun mapsToolCompleteLegacyResultField() {
        val event = StreamEventMapper.map("tool_result", null, """{"id":"t1","name":"read_file","result":"ok"}""") as ChatEvent.ToolResult
        assertEquals("t1", event.id)
        assertEquals("ok", event.result)
    }

    @Test fun mapsInterimAssistantToChunkOnce() {
        val payload = """{"text":"Проверяю файл.","already_streamed":false}"""
        assertEquals(ChatEvent.Chunk("Проверяю файл.", "e3"), StreamEventMapper.map("interim_assistant", "e3", payload))
    }

    @Test fun dropsAlreadyStreamedInterimAssistantToAvoidDuplication() {
        val payload = """{"text":"Проверяю файл.","already_streamed":true}"""
        assertNull(StreamEventMapper.map("interim_assistant", "e3", payload))
    }

    @Test fun dropsBlankInterimAssistant() {
        assertNull(StreamEventMapper.map("interim_assistant", null, """{"text":"   ","already_streamed":false}"""))
    }

    @Test fun mapsApprovalToVisibleThought() {
        val payload = """{"kind":"approval","session_id":"s1","tool":"terminal","description":"rm -rf /tmp/x","pending_count":1}"""
        val event = StreamEventMapper.map("approval", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("подтверждение", true))
        assertTrue(event.text.contains("rm -rf /tmp/x"))
    }

    @Test fun mapsClarifyWithChoices() {
        val payload = """{"question":"Какой профиль?","choices_offered":["default","office"],"session_id":"s1","kind":"clarify","timeout_seconds":120}"""
        val event = StreamEventMapper.map("clarify", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("Какой профиль?"))
        assertTrue(event.text.contains("default / office"))
    }

    @Test fun mapsTitleToSessionBoundWithoutLosingFramesAfterDone() {
        val title = StreamEventMapper.map("title", null, """{"session_id":"s9","title":"Отладка чата"}""")
        assertEquals(ChatEvent.SessionBound("s9"), title)
        assertEquals(ChatEvent.Done, StreamEventMapper.map("done", null, "{}"))
        assertEquals(ChatEvent.Done, StreamEventMapper.map("stream_end", null, "{}"))
    }

    @Test fun mapsWarningFallbackNotice() {
        val payload = """{"type":"fallback","message":"Provider offline, switched to fallback model"}"""
        val event = StreamEventMapper.map("warning", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("Provider offline"))
    }

    @Test fun mapsPendingSteerLeftover() {
        val payload = """{"session_id":"s1","text":"добавь тесты"}"""
        val event = StreamEventMapper.map("pending_steer_leftover", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("добавь тесты"))
    }

    @Test fun mapsTokenChunkAndKeepsOpaqueEventId() {
        assertEquals(ChatEvent.Chunk("Hel", "stream:7"), StreamEventMapper.map("token", "stream:7", """{"text":"Hel"}"""))
        assertEquals(ChatEvent.Chunk("lo", null), StreamEventMapper.map(null, null, """{"delta":"lo"}"""))
    }

    @Test fun mapsErrorsBothShapes() {
        assertEquals(ChatEvent.Error("boom"), StreamEventMapper.map("error", null, """{"message":"boom"}"""))
        assertEquals(ChatEvent.Error("kaboom"), StreamEventMapper.map("apperror", null, """{"error":"kaboom"}"""))
    }

    @Test fun unknownMetadataEventsMapToNullAndAreNotErrors() {
        assertNull(StreamEventMapper.map("metering", null, """{"session_id":"s1","usage":{}}"""))
        assertNull(StreamEventMapper.map("todo_state", null, """{"todos":[]}"""))
        assertNull(StreamEventMapper.map("state_saved", null, """{"kind":"file","name":"a.kt"}"""))
    }

    @Test fun mapsReasoningAndPhaseLikeBefore() {
        assertEquals(ChatEvent.Thought("thinking"), StreamEventMapper.map("reasoning", null, """{"text":"thinking"}"""))
        assertEquals(ChatEvent.Phase("final"), StreamEventMapper.map("phase", null, """{"name":"final"}"""))
        assertEquals(ChatEvent.Phase("Compressing context"), StreamEventMapper.map("compressing", null, """{"session_id":"s1","message":"Compressing context"}"""))
    }
}
