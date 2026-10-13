package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.ErrorOrigin
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionTitleUpdate
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

    @Test fun structuredToolResultIsJsonNotKotlinMapString() {
        val event = StreamEventMapper.map(
            "tool_result", null,
            """{"id":"t2","name":"search","content":{"items":[{"title":"Ставрополь"}]}}"""
        ) as ChatEvent.ToolResult
        assertTrue(event.result.contains("\"items\""))
        assertTrue(event.result.contains("Ставрополь"))
        assertFalse(event.result.contains("items="))
    }

    // ---- числовые идентификаторы: wire вправе дать tid/session_id как JSON number ----

    @Test fun numericTidIsCanonicalizedAndCorrelatesWithStringTid() {
        val started = StreamEventMapper.map(
            "tool", "e1",
            """{"event_type":"tool.started","name":"terminal","args":{"command":"ls"},"tid":42}"""
        ) as ChatEvent.ToolCall
        assertEquals("42", started.id)
        val completed = StreamEventMapper.map(
            "tool_complete", "e2",
            """{"event_type":"tool.completed","name":"terminal","preview":"total 0","tid":"42"}"""
        ) as ChatEvent.ToolResult
        // Корреляция call↔result в ChatUiState идёт по id: числовая и строковая
        // формы одного tid обязаны нормализоваться одинаково (без «42.0»).
        assertEquals(started.id, completed.id)
    }

    @Test fun numericIdsNeverSurfaceAsDoubleArtifacts() {
        // Moshi декодирует JSON-числа в Double: целый id не должен стать «…123.0».
        val event = StreamEventMapper.map("tool", null, """{"name":"bash","tid":1759870000123}""") as ChatEvent.ToolCall
        assertEquals("1759870000123", event.id)
    }

    @Test fun numericSessionIdSurvivesSessionAndTitleFrames() {
        assertEquals(ChatEvent.SessionBound("123"), StreamEventMapper.map("session", null, """{"session_id":123}"""))
        assertEquals(ChatEvent.SessionBound("456"), StreamEventMapper.map("title", null, """{"session_id":456,"title":"Отладка"}"""))
        assertEquals(
            SessionTitleUpdate(sessionId = "456", title = "Отладка"),
            StreamEventMapper.sideEvent("title", """{"session_id":456,"title":"Отладка"}""")
        )
    }

    @Test fun numericApprovalAndClarifyIdsArePreserved() {
        val approval = StreamEventMapper.map(
            "approval", null, """{"approval_id":77,"command":"rm x","description":""}"""
        ) as ChatEvent.ApprovalRequired
        assertEquals("77", approval.payload.approvalId)
        val clarify = StreamEventMapper.map(
            "clarify", null, """{"clarify_id":8,"question":"Профиль?","session_id":99}"""
        ) as ChatEvent.ClarifyRequired
        assertEquals("8", clarify.payload.clarifyId)
        assertEquals("99", clarify.payload.sessionId)
    }

    @Test fun numericRunIdAndMirrorTokenArePreserved() {
        // run_id/_gateway_mirror_token — идентификаторы того же класса: числовая форма
        // с провода не должна теряться при корреляции approval-рейса.
        val approval = StreamEventMapper.map(
            "approval", null,
            """{"approval_id":77,"run_id":1234,"_gateway_mirror_token":567,"command":"rm x"}"""
        ) as ChatEvent.ApprovalRequired
        assertEquals("1234", approval.payload.runId)
        assertEquals("567", approval.payload.mirrorToken)
    }

    @Test fun structuredIdsAreStillNeverCoerced() {
        // Анти-джиббериш остаётся в силе: Map/List-идентификатор отбрасывается, а не toString()-дамнится.
        assertNull(StreamEventMapper.map("session", null, """{"session_id":{"nested":1}}"""))
        val event = StreamEventMapper.map("tool", null, """{"name":"bash","tid":["a"]}""") as ChatEvent.ToolCall
        assertNull(event.id)
    }

    // ---- ограничение размера: гигантский однострочный tool JSON не должен течь в UI целиком ----

    @Test fun giantSingleLineToolArgsAreCappedWithReadableMarker() {
        val huge = "x".repeat(200_000)
        val event = StreamEventMapper.map(
            "tool", null,
            """{"name":"write_file","tid":"t9","args":{"path":"a.txt","content":"$huge"}}"""
        ) as ChatEvent.ToolCall
        assertTrue(
            "args length ${event.arguments.length} exceeds the cap",
            event.arguments.length < StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS + 64
        )
        // Начало сохраняется: detail-подсказка и ключи читаются.
        assertTrue(event.arguments.startsWith("""{"path":"a.txt","content":"xxx"""))
        // Маркер объясняет усечение и хранит полную длину — текст остаётся понятным.
        assertTrue(event.arguments.contains("обрезано: полная длина"))
        assertTrue(event.arguments.contains("${huge.length + """{"path":"a.txt","content":""}""".length} симв."))
    }

    @Test fun giantSingleLineToolResultAndPreviewAreCapped() {
        val huge = "y".repeat(50_000)
        val structured = StreamEventMapper.map(
            "tool_result", null, """{"name":"search","tid":"t10","content":{"blob":"$huge"}}"""
        ) as ChatEvent.ToolResult
        assertTrue(structured.result.length < StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS + 64)
        assertTrue(structured.result.contains("обрезано: полная длина"))

        val preview = StreamEventMapper.map(
            "tool_complete", null, """{"name":"terminal","tid":"t11","preview":"$huge"}"""
        ) as ChatEvent.ToolResult
        assertTrue(preview.result.length < StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS + 64)
        assertTrue(preview.result.startsWith("yyy"))
        assertTrue(preview.result.contains("обрезано: полная длина"))
    }

    @Test fun shortToolPayloadsAreNotTouchedByTheCap() {
        val event = StreamEventMapper.map("tool", null, """{"name":"bash","args":{"command":"ls"}}""") as ChatEvent.ToolCall
        assertEquals("""{"command":"ls"}""", event.arguments)
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
        assertEquals(ChatEvent.Error("boom", ErrorOrigin.SERVER), StreamEventMapper.map("error", null, """{"message":"boom"}"""))
        assertEquals(ChatEvent.Error("kaboom", ErrorOrigin.SERVER), StreamEventMapper.map("apperror", null, """{"error":"kaboom"}"""))
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

    // ---- subagent/delegation frames: readable trace in thoughts, NEVER body text ----
    // Wire verified against hermes-agent tools/delegate_tool_progress.py (_ChildProgressRelay)
    // and gateway api_server_runs.py `_SUBAGENT_EVENT_KEYS`.

    @Test fun mapsSubagentStartToThoughtTraceNotChunk() {
        val payload = """{"event":"subagent.start","run_id":"r1","preview":"Собрать релиз","goal":"Собрать релиз",
            "task_index":1,"task_count":2,"child_session_id":"c1","delegation_id":"d1"}"""
        val event = StreamEventMapper.map("subagent.start", "e1", payload)
        assertTrue(event is ChatEvent.Thought)
        assertTrue((event as ChatEvent.Thought).text.contains("Субагент 1/2 запущен"))
        assertTrue(event.text.contains("Собрать релиз"))
    }

    @Test fun mapsSubagentCompleteWithSummaryToThoughtAndNeverLeaksOutputTail() {
        val wall = "СТЕНА СЫРОГО ТЕКСТА ДОЧЕРНЕГО АГЕНТА ".repeat(4)
        val payload = """{"event":"subagent.complete","status":"completed","goal":"Сборка APK",
            "summary":"APK собран, тесты зелёные","output_tail":"$wall","task_index":2,"task_count":2}"""
        val event = StreamEventMapper.map("subagent.complete", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("Субагент 2/2 завершён (completed)"))
        assertTrue(event.text.contains("APK собран"))
        assertFalse(event.text.contains("СТЕНА"))
    }

    @Test fun mapsSubagentFailureWithReasonToThought() {
        val payload = """{"event":"subagent.complete","status":"failed","goal":"Деплой","failure_reason":"child timeout","output_tail":"x"}"""
        val event = StreamEventMapper.map("subagent.complete", null, payload) as ChatEvent.Thought
        assertTrue(event.text.contains("не справился"))
        assertTrue(event.text.contains("child timeout"))
    }

    @Test fun mapsDelegateLifecycleAliasesLikeSubagentFrames() {
        assertTrue(StreamEventMapper.map("delegate.task_spawned", null, """{"goal":"Проверить CI"}""") is ChatEvent.Thought)
        assertTrue(StreamEventMapper.map("delegate.task_completed", null, """{"status":"completed","summary":"ok"}""") is ChatEvent.Thought)
        assertTrue(StreamEventMapper.map("delegate.task_failed", null, """{"status":"failed","failure_reason":"boom"}""") is ChatEvent.Thought)
    }

    @Test fun subagentChildNoiseFramesAreDroppedAndNeverLeakIntoText() {
        val noisy = """{"text":"child wall of output","content":"more raw text","output_tail":"raw","preview":"p"}"""
        for (name in listOf(
            "subagent.text", "subagent.thinking", "subagent.tool", "subagent.progress",
            "subagent_progress", "delegate.task_progress", "delegate.task_thinking",
            "delegate.tool_started", "delegate.tool_completed",
        )) {
            assertNull("frame '$name' leaked into the ChatEvent flow", StreamEventMapper.map(name, null, noisy))
        }
    }

    @Test fun mapsGatewayRunMessageDeltaToChunkStringOnly() {
        assertEquals(
            ChatEvent.Chunk("Привет", "r:3"),
            StreamEventMapper.map("message.delta", "r:3", """{"event":"message.delta","run_id":"r1","delta":"Привет"}""")
        )
        // Structured delta → dropped, never toString()-dumped into the bubble.
        assertNull(StreamEventMapper.map("message.delta", null, """{"delta":{"parts":["a","b"]}}"""))
    }

    // ---- anti-gibberish contract: structured text fields never reach the bubble ----

    @Test fun structuredTextFieldsNeverDumpIntoChunk() {
        // A snapshot-shaped payload delivered under the server's `message` fallback event
        // name (routes.py _runner_event_name / _replay_run_journal): `content` is a LIST.
        val snapshot = """{"messages":[{"role":"assistant","content":"hi"}],"content":[{"text":"a"}],"last_assistant_text":"x"}"""
        assertNull(StreamEventMapper.map("message", null, snapshot))
        assertNull(StreamEventMapper.map("token", null, """{"text":{"nested":"obj"}}"""))
        assertNull(StreamEventMapper.map(null, null, """{"content":["wall","of","json"]}"""))
        assertNull(StreamEventMapper.map("interim_assistant", null, """{"text":["list"],"already_streamed":false}"""))
        // Plain strings keep flowing exactly as before.
        assertEquals(ChatEvent.Chunk("ok", null), StreamEventMapper.map("message", null, """{"content":"ok"}"""))
        assertEquals(ChatEvent.Chunk("привет", null), StreamEventMapper.map(null, null, """{"text":"привет"}"""))
    }

    @Test fun reasoningWithStructuredContentIsDroppedNotDumped() {
        assertNull(StreamEventMapper.map("reasoning", null, """{"content":{"parts":["a"]}}"""))
    }

    @Test fun phaseNeverShowsRawJsonPayload() {
        // Parsed JSON without a readable field → human label from the event name.
        assertEquals(ChatEvent.Phase("Сжатие контекста"), StreamEventMapper.map("compressing", null, """{"session_id":"s1"}"""))
        assertEquals(ChatEvent.Phase("Контекст сжат"), StreamEventMapper.map("compressed", null, """{"session_id":"s1","usage":{}}"""))
        assertEquals(ChatEvent.Phase("status"), StreamEventMapper.map("status", null, """{"session_id":"s1","code":7}"""))
        // Legacy plain-text payload stays verbatim.
        assertEquals(ChatEvent.Phase("final"), StreamEventMapper.map("status", null, "final"))
    }

    @Test fun errorWithoutMessageFieldsNeverDumpsRawJson() {
        assertEquals(ChatEvent.Error("Ошибка потока", ErrorOrigin.SERVER), StreamEventMapper.map("apperror", null, """{"code":500}"""))
        assertEquals(ChatEvent.Error("Gateway rejected", ErrorOrigin.SERVER), StreamEventMapper.map("error", null, """{"label":"Gateway rejected","type":"gateway_auth_error"}"""))
        // Non-JSON body preserved verbatim (nothing structured to leak).
        assertEquals(ChatEvent.Error("raw failure", ErrorOrigin.SERVER), StreamEventMapper.map("error", null, "raw failure"))
        // message/error keep priority.
        assertEquals(ChatEvent.Error("boom", ErrorOrigin.SERVER), StreamEventMapper.map("error", null, """{"message":"boom","label":"L"}"""))
    }

    // ---- side-channel metadata frames: title text / goal / goal_continue -----
    // Verified against hermes-webui api/streaming.py: put_event('title', {session_id,
    // title}), put('goal', {session_id, state, message, message_key, message_args?,
    // decision?}), put('goal_continue', {session_id, continuation_prompt, text,
    // message, message_key, message_args, decision}).

    @Test fun sideEventDeliversTitleTextThatSessionBoundDrops() {
        val payload = """{"session_id":"s9","title":"Отладка чата"}"""
        // The sealed flow keeps the session binding (backward compatible)...
        assertEquals(ChatEvent.SessionBound("s9"), StreamEventMapper.map("title", null, payload))
        // ...and the side channel adds the title TEXT.
        assertEquals(
            SessionTitleUpdate(sessionId = "s9", title = "Отладка чата"),
            StreamEventMapper.sideEvent("title", payload)
        )
    }

    @Test fun sideEventDropsTitleWithoutUsableText() {
        assertNull(StreamEventMapper.sideEvent("title", """{"session_id":"s9","title":"   "}"""))
        assertNull(StreamEventMapper.sideEvent("title", """{"session_id":"s9"}"""))
        // Malformed payload: no crash, nothing delivered.
        assertNull(StreamEventMapper.sideEvent("title", "not-json"))
    }

    @Test fun sideEventDeliversGoalEvaluating() {
        val payload = """{"session_id":"s1","state":"evaluating","message":"Evaluating goal progress…","message_key":"goal_evaluating_progress"}"""
        assertEquals(
            GoalUpdate(
                sessionId = "s1",
                state = "evaluating",
                message = "Evaluating goal progress…",
                messageKey = "goal_evaluating_progress",
            ),
            StreamEventMapper.sideEvent("goal", payload)
        )
        // No sealed carrier: goal frames must NOT enter the ChatEvent flow.
        assertNull(StreamEventMapper.map("goal", null, payload))
    }

    @Test fun sideEventDeliversGoalContinuingWithRawDecision() {
        val payload = """{"session_id":"s1","state":"continuing","message":"Цель не завершена","message_key":"goal_continuing","message_args":["шаг 2"],"decision":{"should_continue":true,"continuation_prompt":"Продолжи"}}"""
        val update = StreamEventMapper.sideEvent("goal", payload) as GoalUpdate
        assertEquals("s1", update.sessionId)
        assertEquals("continuing", update.state)
        assertEquals("Цель не завершена", update.message)
        assertEquals("goal_continuing", update.messageKey)
        assertEquals(listOf("шаг 2"), update.messageArgs)
        // decision is an opaque server payload, preserved as compact JSON.
        assertTrue(update.decisionJson!!.contains("\"should_continue\":true"))
        assertTrue(update.decisionJson!!.contains("Продолжи"))
    }

    @Test fun sideEventDeliversGoalContinuationPrompt() {
        val payload = """{"session_id":"s1","continuation_prompt":"Продолжи шаг 2","text":"Продолжи шаг 2","message":"Цель не завершена","message_key":"goal_continuing","message_args":[],"decision":{"should_continue":true}}"""
        val continuation = StreamEventMapper.sideEvent("goal_continue", payload) as GoalContinuation
        assertEquals("s1", continuation.sessionId)
        assertEquals("Продолжи шаг 2", continuation.continuationPrompt)
        assertEquals("Цель не завершена", continuation.message)
        assertEquals("goal_continuing", continuation.messageKey)
        assertTrue(continuation.decisionJson!!.contains("\"should_continue\":true"))
        // Delivery only — the sealed flow never sees the continuation prompt.
        assertNull(StreamEventMapper.map("goal_continue", null, payload))
    }

    @Test fun sideEventFallsBackToTextAliasAndDropsPromptlessContinuation() {
        // `text` is the wire alias of `continuation_prompt`.
        assertEquals(
            "Продолжи",
            (StreamEventMapper.sideEvent("goal_continue", """{"session_id":"s1","text":"Продолжи"}""") as GoalContinuation).continuationPrompt
        )
        // No prompt at all → nothing to deliver.
        assertNull(StreamEventMapper.sideEvent("goal_continue", """{"session_id":"s1","message":"..."}"""))
        // Gateway mirror without session_id → delivered with null sessionId.
        assertNull(
            (StreamEventMapper.sideEvent("goal_continue", """{"continuation_prompt":"go"}""") as GoalContinuation).sessionId
        )
    }

    @Test fun sideEventIgnoresUnrelatedFrames() {
        assertNull(StreamEventMapper.sideEvent("chunk", """{"text":"hi"}"""))
        assertNull(StreamEventMapper.sideEvent("todo_state", """{"todos":[],"summary":{"total":0},"version":1}"""))
        assertNull(StreamEventMapper.sideEvent("state_saved", "{}"))
        assertNull(StreamEventMapper.sideEvent(null, """{"title":"x"}"""))
    }

    // ---- `context_status`: статус префилл-контекста хода (api/streaming.py
    // put('context_status', {session_id, prefill: _public_prefill_context_status(...)})).
    // Android-клиент терял кадр молча; теперь он доставляется через side-канал
    // ([ContextStatusUpdate]), не расширяя sealed ChatEvent.

    /** `loaded`-префилл: поля доставляются дословно; бюджетные хвосты (original_*, max_chars) в контракт не входят. */
    @Test fun sideEventDeliversContextStatusLoadedPrefill() {
        val payload = """{"session_id":"s7","prefill":{"status":"loaded","source":"script","label":"session recall","message_count":3,"compacted":true,"original_source":"file","original_message_count":9,"original_char_count":52000,"max_chars":24000}}"""
        assertEquals(
            ContextStatusUpdate(
                sessionId = "s7",
                status = "loaded",
                label = "session recall",
                source = "script",
                messageCount = 3,
                error = null,
                compacted = true,
            ),
            StreamEventMapper.sideEvent("context_status", payload)
        )
        // Sealed-контракт не расширяем: кадр живёт ТОЛЬКО в side-канале.
        assertNull(StreamEventMapper.map("context_status", null, payload))
    }

    /** `error`-префилл: текст серверной ошибки сохраняется для предупреждения в UI. */
    @Test fun sideEventDeliversContextStatusErrorWithReason() {
        val update = StreamEventMapper.sideEvent(
            "context_status",
            """{"session_id":"s7","prefill":{"status":"error","source":"none","label":"session recall","message_count":0,"error":"prefill script failed"}}"""
        ) as ContextStatusUpdate
        assertEquals("error", update.status)
        assertEquals("prefill script failed", update.error)
        assertEquals(0, update.messageCount)
        assertFalse(update.compacted)
    }

    /** `not_configured` доставляется (UI сам решает не показывать); кадр без prefill/status и не-JSON отбрасываются. */
    @Test fun sideEventDeliversNotConfiguredAndDropsFramesWithoutStatus() {
        assertEquals(
            ContextStatusUpdate(sessionId = "s7", status = "not_configured", label = "session recall", source = "none"),
            StreamEventMapper.sideEvent(
                "context_status",
                """{"session_id":"s7","prefill":{"status":"not_configured","source":"none","label":"session recall","message_count":0}}"""
            )
        )
        assertNull(StreamEventMapper.sideEvent("context_status", """{"session_id":"s7"}"""))
        assertNull(StreamEventMapper.sideEvent("context_status", """{"session_id":"s7","prefill":{"label":"x"}}"""))
        assertNull(StreamEventMapper.sideEvent("context_status", "not-json"))
    }

    // ---- SSE edge payloads reaching the mapper (SseChunkParser boundary) ----

    /** JSON, склеенный из multiline `data:`-строк (SseChunkParser джойнит через `\n` — валидный whitespace). */
    @Test fun mapsChunkFromJsonSplitAcrossDataLines() {
        assertEquals(
            ChatEvent.Chunk("hi", "e5"),
            StreamEventMapper.map("token", "e5", "{\"text\":\n\"hi\"}")
        )
    }

    /** Пустой data-кадр (`data:` без значения / EOF-хвост): без падений и без мусора в чате. */
    @Test fun emptyPayloadFramesAreDroppedSafely() {
        assertNull(StreamEventMapper.map("token", null, ""))
        assertNull(StreamEventMapper.map(null, null, ""))
        assertNull(StreamEventMapper.map("warning", null, ""))
        assertNull(StreamEventMapper.map("reasoning", null, ""))
        assertNull(StreamEventMapper.map("interim_assistant", null, ""))
        assertEquals(ChatEvent.Error("Ошибка потока", ErrorOrigin.SERVER), StreamEventMapper.map("error", null, ""))
        assertEquals(ChatEvent.Done, StreamEventMapper.map("done", null, ""))
    }
}
