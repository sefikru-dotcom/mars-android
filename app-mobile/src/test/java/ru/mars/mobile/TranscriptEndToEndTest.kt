package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionMessage
import ru.mars.network.repository.StreamEventMapper

/**
 * P0-регрессия транскрипта END-TO-END: сырые SSE-кадры сервера → [StreamEventMapper]
 * → [ChatUiState] → [CardSnapshotPolicy] → [IncrementalTranscriptPolicy] — та же
 * цепочка, что и в ChatActivity (render-снапшоты повторяют currentSnapshots()).
 *
 * Закрытые сценарии:
 * 1. пустой assistant → первый chunk;
 * 2. несколько chunk подряд;
 * 3. ToolCall/ToolResult МЕЖДУ chunk;
 * 4. Done (включая повторный Done из replay);
 * 5. recovery/reattach поверх истории (placeholder + replay + 404-отмена);
 * 6. история с несколькими assistant;
 * 7. исключение служебного tool-output из пользовательского текста.
 *
 * Сквозной инвариант: события стрима НЕ плодят новые карточки (appendFrom всегда
 * равен размеру списка) и не вызывают FullRebuild посреди хода.
 */
class TranscriptEndToEndTest {

    /** Реплика ChatActivity.currentSnapshots(): день-разделители + стрим-флаг последнего. */
    private fun snapshots(state: ChatUiState, chromeEpoch: Long = 0L): List<CardSnapshot> {
        var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        val lastIndex = state.messages.size - 1
        val typing = state.status == ChatStatus.TYPING
        return state.messages.mapIndexed { index, message ->
            val snapshot = CardSnapshotPolicy.of(
                message = message,
                previousTimestamp = previousTimestamp,
                chromeEpoch = chromeEpoch,
                streamingFlag = typing && index == lastIndex,
            )
            if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
            snapshot
        }
    }

    /** Кадр через ПРОДАКШН-маппер wire-событий, как в ChatRepository/recovery. */
    private fun feed(state: ChatUiState, event: String?, payload: String, eventId: String? = null): ChatEvent? =
        StreamEventMapper.map(event, eventId, payload)?.also(state::onEvent)

    private fun assertPatch(plan: TranscriptPlan, expected: List<CardPatch>, appendFrom: Int) {
        assertTrue("ожидался Patch, получен $plan", plan is TranscriptPlan.Patch)
        plan as TranscriptPlan.Patch
        assertEquals(expected, plan.updates)
        assertEquals("событие не должно плодить новые карточки", appendFrom, plan.appendFrom)
    }

    // ---- 1. Пустой assistant → первый chunk -----------------------------------------------

    @Test
    fun emptyAssistantTakesFirstWireChunkAsFullCard() {
        val state = ChatUiState()
        assertTrue(state.begin("привет"))
        assertEquals("", state.messages[1].text)
        assertEquals(MessageStatus.STREAMING, state.messages[1].status)
        val before = snapshots(state)

        val event = feed(state, "token", """{"text":"Отве"}""", eventId = "e1")
        assertTrue(event is ChatEvent.Chunk)

        val plan = IncrementalTranscriptPolicy.plan(before, snapshots(state))
        // Пустой→непустой текст — НЕ TEXT_ONLY (markdown-секции ещё нет): карточка целиком.
        assertPatch(plan, listOf(CardPatch(1, CardUpdateKind.FULL_CARD)), appendFrom = 2)
        assertEquals("Отве", state.messages[1].text)
        assertEquals(ChatStatus.TYPING, state.status)
        assertEquals(2, state.messages.size)
    }

    // ---- 2. Несколько chunk ----------------------------------------------------------------

    @Test
    fun consecutiveChunksAccumulateIntoOneBubbleWithTextOnlyPatches() {
        val state = ChatUiState()
        state.begin("привет")
        feed(state, "token", """{"text":"Раз "}""", eventId = "e0")
        var before = snapshots(state)

        listOf("два ", "три ", "четыре").forEachIndexed { i, piece ->
            feed(state, "token", """{"text":"$piece"}""", eventId = "e${i + 1}")
            val after = snapshots(state)
            assertPatch(
                IncrementalTranscriptPolicy.plan(before, after),
                listOf(CardPatch(1, CardUpdateKind.TEXT_ONLY)),
                appendFrom = 2,
            )
            before = after
        }
        assertEquals("Раз два три четыре", state.messages[1].text)
        assertEquals(2, state.messages.size)
        assertEquals(ChatStatus.TYPING, state.status)
    }

    // ---- 3. ToolCall/ToolResult между chunk --------------------------------------------------

    @Test
    fun toolCallAndResultBetweenChunksStayOutOfBodyText() {
        val state = ChatUiState()
        state.begin("покажи файлы")
        feed(state, "token", """{"text":"Смотрю "}""")
        var before = snapshots(state)

        val call = feed(
            state, "tool",
            """{"event_type":"tool.started","name":"terminal","args":{"command":"ls"},"tid":"call_1"}""",
        )
        assertTrue(call is ChatEvent.ToolCall)
        var after = snapshots(state)
        // Прибытие инструмента меняет окружение — полная пересборка одной карточки.
        assertPatch(IncrementalTranscriptPolicy.plan(before, after), listOf(CardPatch(1, CardUpdateKind.FULL_CARD)), appendFrom = 2)
        before = after

        val result = feed(
            state, "tool_complete",
            """{"event_type":"tool.completed","name":"terminal","preview":"total 0","tid":"call_1"}""",
        )
        assertTrue(result is ChatEvent.ToolResult)
        after = snapshots(state)
        assertPatch(IncrementalTranscriptPolicy.plan(before, after), listOf(CardPatch(1, CardUpdateKind.FULL_CARD)), appendFrom = 2)
        before = after

        // Текст ПРОДОЛЖАЕТ копиться в том же пузыре после инструмента.
        feed(state, "token", """{"text":"— пусто."}""")
        after = snapshots(state)
        assertPatch(IncrementalTranscriptPolicy.plan(before, after), listOf(CardPatch(1, CardUpdateKind.TEXT_ONLY)), appendFrom = 2)

        val assistant = state.messages[1]
        assertEquals("Смотрю — пусто.", assistant.text)
        assertEquals(1, assistant.tools.size)
        val tool = assistant.tools.single()
        assertEquals("call_1", tool.id)
        assertEquals("terminal", tool.name)
        assertTrue(tool.arguments.contains("\"command\":\"ls\""))
        assertEquals("total 0", tool.result)
        assertFalse(tool.expanded)
        assertEquals(2, state.messages.size)
    }

    // ---- 4. Done ------------------------------------------------------------------------------

    @Test
    fun doneSettlesTheBubbleOnceAndReplayedDoneIsNoChange() {
        val state = ChatUiState()
        state.begin("привет")
        feed(state, "token", """{"text":"Ответ"}""")
        val before = snapshots(state)

        assertEquals(ChatEvent.Done, feed(state, "done", """{}"""))
        val after = snapshots(state)
        // Status/streamingFlag — часть «хрома»: терминальный кадр пересобирает карточку.
        assertPatch(IncrementalTranscriptPolicy.plan(before, after), listOf(CardPatch(1, CardUpdateKind.FULL_CARD)), appendFrom = 2)
        assertEquals(ChatStatus.RECEIVED, state.status)
        assertEquals(MessageStatus.COMPLETE, state.messages[1].status)
        assertEquals("Ответ", state.messages[1].text)

        // Повторный done из replay не меняет ни состояние, ни снимки.
        val settled = snapshots(state)
        feed(state, "done", """{}""")
        assertEquals(TranscriptPlan.NoChange, IncrementalTranscriptPolicy.plan(settled, snapshots(state)))
        assertEquals(2, state.messages.size)
    }

    // ---- 5. Recovery/reattach ------------------------------------------------------------------

    @Test
    fun reattachReplaysWholeTurnIntoOnePlaceholderOverHistory() {
        val state = ChatUiState()
        state.bindSession(
            "s1", "Тест",
            HistoryTranscriptPolicy.of(
                listOf(
                    SessionMessage(role = "user", content = "старый вопрос"),
                    SessionMessage(role = "assistant", content = "старый ответ"),
                    SessionMessage(role = "tool", content = """{"raw":"json"}""", toolName = "terminal", toolCallId = "c0"),
                )
            ),
        )
        assertEquals(2, state.messages.size)
        val bound = snapshots(state)

        // Открытие placeholder'а — единственная новая карточка, история не пересобирается.
        assertTrue(state.beginReattachedTurn())
        val opened = snapshots(state)
        assertPatch(IncrementalTranscriptPolicy.plan(bound, opened), emptyList(), appendFrom = 2)
        assertEquals(ChatStatus.TYPING, state.status)

        // Replay хода с начала: chunks и инструменты ложятся в placeholder.
        var before = opened
        for ((name, payload) in listOf(
            "token" to """{"text":"Продолжаю "}""",
            "tool" to """{"event_type":"tool.started","name":"terminal","args":{"command":"ls"},"tid":"c9"}""",
            "tool_complete" to """{"event_type":"tool.completed","name":"terminal","preview":"ok","tid":"c9"}""",
            "token" to """{"text":"после инструмента."}""",
        )) {
            feed(state, name, payload)
            val after = snapshots(state)
            val plan = IncrementalTranscriptPolicy.plan(before, after)
            assertTrue(plan is TranscriptPlan.Patch)
            assertEquals(3, (plan as TranscriptPlan.Patch).appendFrom)
            before = after
        }
        assertEquals(3, state.messages.size)
        val replayed = state.messages[2]
        assertEquals("Продолжаю после инструмента.", replayed.text)
        assertEquals(1, replayed.tools.size)
        assertEquals("ok", replayed.tools.single().result)

        assertEquals(ChatEvent.Done, feed(state, "done", "{}"))
        assertEquals(ChatStatus.RECEIVED, state.status)
        assertFalse(state.reattachedTurn)
        assertEquals(MessageStatus.COMPLETE, state.messages[2].status)
    }

    @Test
    fun reattachDoubleAttachCannotOpenSecondBubble() {
        val state = ChatUiState()
        state.bindSession("s1", "Тест", HistoryTranscriptPolicy.of(listOf(SessionMessage(role = "user", content = "вопрос"))))
        assertTrue(state.beginReattachedTurn())
        assertFalse("повторный attach не должен плодить второй пузырь", state.beginReattachedTurn())
        assertEquals(2, state.messages.size)
    }

    @Test
    fun reattach404DropsEmptyPlaceholderAndKeepsHistoryUntouched() {
        val state = ChatUiState()
        state.bindSession(
            "s1", "Тест",
            HistoryTranscriptPolicy.of(
                listOf(
                    SessionMessage(role = "user", content = "вопрос"),
                    SessionMessage(role = "assistant", content = "ответ"),
                )
            ),
        )
        val bound = snapshots(state)
        assertTrue(state.beginReattachedTurn())
        // Стрим умер серверно (404) до единого события — placeholder снимается.
        state.cancelReattachedTurn()
        assertEquals(ChatStatus.IDLE, state.status)
        assertEquals(2, state.messages.size)
        assertEquals(TranscriptPlan.NoChange, IncrementalTranscriptPolicy.plan(bound, snapshots(state)))
    }

    @Test
    fun reattach404KeepsPartiallyReplayedContent() {
        val state = ChatUiState()
        state.bindSession("s1", "Тест", HistoryTranscriptPolicy.of(listOf(SessionMessage(role = "user", content = "вопрос"))))
        assertTrue(state.beginReattachedTurn())
        feed(state, "token", """{"text":"успело "}""")
        feed(state, "token", """{"text":"прийти."}""")
        state.cancelReattachedTurn()
        assertEquals(2, state.messages.size)
        assertEquals("успело прийти.", state.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, state.messages[1].status)
    }

    // ---- 6. История с несколькими assistant ------------------------------------------------------

    @Test
    fun multiAssistantHistoryRendersOneBubblePerVisibleRowWithServerTimestamps() {
        val rows = listOf(
            SessionMessage(role = "user", content = "задача", createdAt = "1791921200.0"),
            SessionMessage(role = "assistant", content = "Шаг 1.", createdAt = "1791921210.0"),
            SessionMessage(role = "tool", content = """{"raw":"wall of json"}""", toolName = "terminal", toolCallId = "c1", createdAt = "1791921220.0"),
            SessionMessage(role = "assistant", content = "Шаг 2.", createdAt = "1791921230.0"),
            SessionMessage(role = "tool", content = """{"more":"json"}""", toolName = "patch", toolCallId = "c2", createdAt = "1791921240.0"),
            SessionMessage(role = "system", content = "служебная системная строка"),
            SessionMessage(role = "assistant", content = "Готово.", createdAt = "1791921250.0"),
        )
        val state = ChatUiState()
        state.bindSession("s1", "Тест", HistoryTranscriptPolicy.of(rows))
        assertEquals(4, state.messages.size)
        assertEquals(listOf("user", "assistant", "assistant", "assistant"), state.messages.map { it.role })
        assertEquals(listOf("Шаг 1.", "Шаг 2.", "Готово."), state.messages.drop(1).map { it.text })
        assertEquals(listOf(1, 1, 0), state.messages.drop(1).map { it.tools.size })
        // Epoch-секунды wire-строк доходят до снимков: часы и день-разделитель живы.
        assertTrue(state.messages.all { it.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP })
        val snaps = snapshots(state)
        assertTrue(snaps[0].showDaySeparator)
        assertFalse(snaps[1].showDaySeparator)

        // Новый ход поверх такой истории открывается своим пузырём, а не допиской в хвост истории.
        assertTrue(state.begin("ещё вопрос"))
        assertEquals(6, state.messages.size)
        feed(state, "token", """{"text":"Новый ответ."}""")
        assertEquals("Новый ответ.", state.messages[5].text)
        assertEquals("Готово.", state.messages[3].text)
    }

    // ---- 7. Исключение служебного tool-output --------------------------------------------------

    @Test
    fun serviceFramesNeverLeakIntoUserVisibleBodyText() {
        val state = ChatUiState()
        state.begin("задача")
        val frames = listOf(
            "subagent.text" to """{"text":"стена сырого текста дочернего агента"}""",
            "subagent.tool" to """{"name":"terminal","preview":"child output"}""",
            "tool" to """{"event_type":"tool.started","name":"terminal","args":{"command":"cat big.bin"},"tid":"c1"}""",
            "tool_complete" to """{"event_type":"tool.completed","name":"terminal","preview":"binary wall","tid":"c1"}""",
            "interim_assistant" to """{"text":"промежуточный дубль","already_streamed":true}""",
            "token" to """{"text":"Итоговый ответ"}""",
        )
        for ((name, payload) in frames) feed(state, name, payload)

        val assistant = state.messages[1]
        assertEquals("Итоговый ответ", assistant.text)
        assertFalse(assistant.text.contains("стена"))
        assertFalse(assistant.text.contains("binary"))
        assertFalse(assistant.text.contains("промежуточный"))
        // Tool-вывод живёт ТОЛЬКО в сворачиваемой карточке.
        assertEquals(1, assistant.tools.size)
        assertEquals("binary wall", assistant.tools.single().result)
        assertFalse(assistant.tools.single().expanded)
    }

    @Test
    fun historyToolRowsLandOnlyInCollapsedCardsNeverInBubbles() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                SessionMessage(role = "user", content = "проверь"),
                SessionMessage(role = "assistant", content = "Проверяю."),
                SessionMessage(role = "tool", content = """{"status":"error","error":"BLOCKED"}""", toolName = "execute_code", toolCallId = "c1"),
                SessionMessage(role = "assistant", content = "Поправил."),
            )
        )
        assertEquals(3, bubbles.size)
        assertTrue(bubbles.none { it.text.contains("BLOCKED") || it.text.contains("status") })
        val card = bubbles[1].tools.single()
        assertEquals("execute_code", card.name)
        assertTrue(card.result!!.contains("BLOCKED"))
        assertFalse(card.expanded)
    }
}
