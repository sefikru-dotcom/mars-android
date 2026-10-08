package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun mapsApprovalToTypedEventPreservingIdsAndOptions() {
        val payload = """{"kind":"approval","session_id":"s1","approval_id":"appr-9","command":"rm -rf /tmp/x",
            "description":"Удаление каталога","pattern_key":"terminal:rm","pattern_keys":["terminal:rm","shell:rm"],
            "allow_permanent":true,"allow_session":false,"smart_denied":false,"pending_count":3}"""
        val event = StreamEventMapper.map("approval", "e7", payload) as ChatEvent.ApprovalRequired
        assertEquals("appr-9", event.payload.approvalId)
        assertEquals("rm -rf /tmp/x", event.payload.command)
        assertEquals("Удаление каталога", event.payload.description)
        assertEquals(listOf("terminal:rm", "shell:rm"), event.payload.patternKeys)
        assertTrue(event.payload.allowPermanent)
        assertFalse(event.payload.allowSession)
        assertEquals(3, event.payload.pendingCount)
    }

    @Test fun mapsGatewayApprovalWithRunAndMirrorToken() {
        val payload = """{"approval_id":"ga-1","command":"kubectl delete pod x","description":"",
            "run_id":"run-42","_gateway_mirror_token":"tok-secret","tool":"terminal","risk_level":"high",
            "choices":["once","deny"],"pending_count":1}"""
        val event = StreamEventMapper.map("approval", null, payload) as ChatEvent.ApprovalRequired
        assertEquals("run-42", event.payload.runId)
        assertEquals("tok-secret", event.payload.mirrorToken)
        assertEquals("terminal", event.payload.tool)
        assertEquals("high", event.payload.riskLevel)
        assertEquals(listOf("once", "deny"), event.payload.choices)
    }

    @Test fun approvalDefaultsNeverGrantPermanentAndKeepSession() {
        val payload = """{"command":"git push","description":""}"""
        val event = StreamEventMapper.map("approval", null, payload) as ChatEvent.ApprovalRequired
        assertNull(event.payload.approvalId)
        assertFalse(event.payload.allowPermanent)
        assertTrue(event.payload.allowSession)
        assertEquals(1, event.payload.pendingCount)
    }

    @Test fun dropsApprovalWithoutAnyRenderableField() {
        assertNull(StreamEventMapper.map("approval", null, """{"pending_count":1}"""))
    }

    @Test fun mapsClarifyToTypedEventPreservingIdAndChoices() {
        val payload = """{"clarify_id":"cl-7","question":"Какой профиль?","choices_offered":["default","office"],
            "session_id":"s1","kind":"clarify","timeout_seconds":120,"requested_at":1759870000.5,"expires_at":1759870120.5}"""
        val event = StreamEventMapper.map("clarify", "e8", payload) as ChatEvent.ClarifyRequired
        assertEquals("cl-7", event.payload.clarifyId)
        assertEquals("Какой профиль?", event.payload.question)
        assertEquals(listOf("default", "office"), event.payload.choices)
        assertEquals("s1", event.payload.sessionId)
        assertEquals(120, event.payload.timeoutSeconds)
        assertEquals(1759870120.5, event.payload.expiresAtEpochSeconds!!, 0.0001)
    }

    @Test fun mapsFreeTextClarifyWithoutChoices() {
        val payload = """{"clarify_id":"cl-8","question":"Опишите задачу","allow_free_text":true}"""
        val event = StreamEventMapper.map("clarify", null, payload) as ChatEvent.ClarifyRequired
        assertTrue(event.payload.choices.isEmpty())
    }

    @Test fun dropsClarifyWithoutQuestionOrChoices() {
        assertNull(StreamEventMapper.map("clarify", null, """{"kind":"clarify"}"""))
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
        // Typed event: ChatActivity returns the undelivered steer text to the composer input
        // (WebUI pending_steer_leftover parity), ChatUiState keeps the transcript trace.
        assertEquals(ChatEvent.SteerLeftover("добавь тесты"), StreamEventMapper.map("pending_steer_leftover", null, payload))
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
        // todo_state has no ChatEvent carrier BY DESIGN — delivery goes through
        // [StreamEventMapper.todoState] + the ChatRepository.onTodoState callback.
        assertNull(StreamEventMapper.map("todo_state", null, """{"todos":[]}"""))
        assertNull(StreamEventMapper.map("state_saved", null, """{"kind":"file","name":"a.kt"}"""))
    }

    /** Live `todo_state` frame → snapshot via the shared [ru.mars.network.repository.TodoStateParser] (envelope included). */
    @Test fun todoStateSideChannelParsesLiveEnvelope() {
        val snapshot = StreamEventMapper.todoState(
            "todo_state",
            """{"session_id":"s-1","stream_id":"st-9","source":"tool","ts":1759900001.25,
                "todos":[{"id":"1","content":"Шаг","status":"in_progress"}],
                "summary":{"total":1,"pending":0,"in_progress":1,"completed":0,"cancelled":0},"version":1}"""
        )!!
        assertEquals("s-1", snapshot.sessionId)
        assertEquals("st-9", snapshot.streamId)
        assertEquals("tool", snapshot.source)
        assertEquals(1759900001.25, snapshot.tsEpochSeconds!!, 0.0)
        assertEquals(ru.mars.network.model.TodoItem("1", "Шаг", "in_progress"), snapshot.todos.single())
        assertEquals(1, snapshot.summary.total)
        assertEquals(1, snapshot.summary.inProgress)
    }

    /** Only the `todo_state` event name opens the side channel; a non-snapshot payload yields null, never a crash. */
    @Test fun todoStateIgnoresOtherEventsAndNonSnapshots() {
        assertNull(StreamEventMapper.todoState("token", """{"todos":[]}"""))
        assertNull(StreamEventMapper.todoState(null, """{"todos":[]}"""))
        assertNull(StreamEventMapper.todoState("todo_state", """{"summary":{"total":1}}"""))
        assertNull(StreamEventMapper.todoState("todo_state", "not json"))
    }

    @Test fun mapsReasoningAndPhaseLikeBefore() {
        assertEquals(ChatEvent.Thought("thinking"), StreamEventMapper.map("reasoning", null, """{"text":"thinking"}"""))
        assertEquals(ChatEvent.Phase("final"), StreamEventMapper.map("phase", null, """{"name":"final"}"""))
        assertEquals(ChatEvent.Phase("Compressing context"), StreamEventMapper.map("compressing", null, """{"session_id":"s1","message":"Compressing context"}"""))
    }
}
