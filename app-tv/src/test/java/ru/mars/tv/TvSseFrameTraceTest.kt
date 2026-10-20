package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.SubagentUpdate

/**
 * Трасса SSE-кадров ТВ-чата ([TvSseFrameTrace]) — контракт «неизвестный кадр не
 * проваливается молча».
 *
 * Проверяется то, что раньше нельзя было проверить никак: `else -> Unit` в диспетчерах
 * не оставлял следа, поэтому «сервер не прислал» и «ТВ-чат выбросил» выглядели одинаково.
 * Здесь зафиксировано: обработанные кадры не шумят в лог, известные-но-без-реакции
 * логируются отдельной формулировкой, незнакомый носитель side-канала (интерфейс не
 * sealed — сервер может добавить кадр без правки транспорта) логируется с типом.
 */
class TvSseFrameTraceTest {

    /** Носитель, которого ТВ-чат ещё не знает: ровно тот случай, ради которого есть трасса. */
    private class BrandNewSideFrame(val payload: String) : StreamSideEvent

    // --- обработанные кадры: молчать ---

    @Test fun contextStatusIsHandledAndProducesNoLogLine() {
        val update = ContextStatusUpdate(sessionId = "s1", status = "loaded", label = "session recall")
        assertEquals("context_status", TvSseFrameTrace.sideEventName(update))
        assertNull(TvSseFrameTrace.unhandledSideEvent(update))
    }

    @Test fun subagentLifecycleIsHandledAndProducesNoLogLine() {
        val update = SubagentUpdate(kind = SubagentUpdate.Kind.Start, childId = "c1", displayName = "child")
        val event = ru.mars.network.model.SubagentStatusUpdate(update)
        assertEquals("subagent", TvSseFrameTrace.sideEventName(event))
        assertNull(TvSseFrameTrace.unhandledSideEvent(event))
    }

    @Test fun everyKnownChatEventKindIsCoveredAndSilent() {
        for (event in allChatEvents()) {
            val name = TvSseFrameTrace.chatEventName(event)
            assertTrue("пустое имя для $event", name.isNotBlank())
            assertTrue("имя '$name' не в таблице обработанных", name in TvSseFrameTrace.HANDLED_CHAT_EVENTS)
            assertNull("событие $name не должно логироваться как необработанное", TvSseFrameTrace.unhandledChatEvent(event))
        }
    }

    @Test fun handledChatEventTableMatchesActualChatEventKinds() {
        // 13 вариантов sealed ChatEvent на сегодня: новая ветка core-network обязана
        // попасть либо в таблицу (обработана), либо в лог (не разобрана) — «между» нет.
        assertEquals(13, allChatEvents().size)
        assertEquals(
            allChatEvents().map { TvSseFrameTrace.chatEventName(it) }.toSet(),
            TvSseFrameTrace.HANDLED_CHAT_EVENTS,
        )
    }

    @Test fun interimAssistantArrivesAsChunkAndIsNamedInTheLog() {
        // Транспорт отдаёт промежуточную реплику тем же ChatEvent.Chunk
        // (StreamEventMapper.interimAssistant) — имя в логе это фиксирует.
        val chunk = ChatEvent.Chunk("Смотрю файлы.")
        assertEquals("chunk/interim_assistant", TvSseFrameTrace.chatEventName(chunk))
        assertNull(TvSseFrameTrace.unhandledChatEvent(chunk))
    }

    // --- известные кадры без реакции: логируются отдельной формулировкой ---

    @Test fun titleFrameIsLoggedAsDeliveredWithoutReaction() {
        val line = TvSseFrameTrace.unhandledSideEvent(SessionTitleUpdate(sessionId = "s1", title = "Отладка"))
        assertNotNull(line)
        assertTrue(line!!.contains("title"))
        assertTrue(line.contains("без реакции"))
    }

    @Test fun goalFramesAreLoggedWithoutReaction() {
        val goal = TvSseFrameTrace.unhandledSideEvent(GoalUpdate(sessionId = "s1", state = "evaluating"))
        val continuation = TvSseFrameTrace.unhandledSideEvent(
            GoalContinuation(sessionId = "s1", continuationPrompt = "продолжай"),
        )
        assertTrue(goal!!.contains("goal"))
        assertTrue(continuation!!.contains("goal_continue"))
    }

    // --- незнакомый кадр: тип в логе, цикл не падает ---

    @Test fun unknownSideFrameIsLoggedWithItsType() {
        val line = TvSseFrameTrace.unhandledSideEvent(BrandNewSideFrame("{}"))
        assertNotNull(line)
        assertTrue("в логе должен быть тип кадра: $line", line!!.contains("BrandNewSideFrame"))
        assertTrue(line.contains("неизвестного типа"))
    }

    @Test fun unknownSideFrameNameIsDerivedFromClassNotFromPayload() {
        val frame = BrandNewSideFrame("секретный текст кадра")
        val name = TvSseFrameTrace.sideEventName(frame)
        assertEquals("unknown:BrandNewSideFrame", name)
        assertTrue(!name.contains("секретный"))
    }

    // --- чанк без пузыря: раньше исчезал молча ---

    @Test fun droppedChunkExplainsTerminalAnswer() {
        val line = TvSseFrameTrace.droppedChunk(hasAccumulator = true, chars = 42)
        assertTrue(line.contains("42"))
        assertTrue(line.contains("терминален"))
    }

    @Test fun droppedChunkExplainsMissingBubble() {
        val line = TvSseFrameTrace.droppedChunk(hasAccumulator = false, chars = 7)
        assertTrue(line.contains("7"))
        assertTrue(line.contains("пузыря нет"))
    }

    @Test fun logTagIsStable() {
        // Тег — часть контракта с logcat-фильтром дежурного: менять только осознанно.
        assertEquals("MarsTvChat", TvSseFrameTrace.TAG)
    }

    /** Все варианты sealed [ChatEvent] на сегодня (по одному экземпляру каждого). */
    private fun allChatEvents(): List<ChatEvent> = listOf(
        ChatEvent.SessionBound("s1"),
        ChatEvent.StreamStarted("st-1", "model", "provider"),
        ChatEvent.Chunk("текст"),
        ChatEvent.Thought("мысль"),
        ChatEvent.ToolCall("call_1", "terminal", "{}"),
        ChatEvent.ToolResult("call_1", "terminal", "ok"),
        ChatEvent.Phase("thinking"),
        ChatEvent.ApprovalRequired(
            ru.mars.network.model.ApprovalPayload(approvalId = "a1", command = "ls", description = "?")
        ),
        ChatEvent.ClarifyRequired(ClarifyPayload(clarifyId = "c1", question = "Какой файл?")),
        ChatEvent.ContextUsage(usedTokens = 1_000, maxTokens = 128_000, percent = 1),
        ChatEvent.SteerLeftover("уточнение"),
        ChatEvent.Done,
        ChatEvent.Error("сбой"),
    )
}
