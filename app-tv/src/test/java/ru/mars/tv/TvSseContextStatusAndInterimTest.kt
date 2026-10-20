package ru.mars.tv

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.repository.ChatRepository
import ru.mars.tv.chat.TvContextStatusPresenter
import ru.mars.tv.chat.TvContextTone

/**
 * Сквозной контракт ТВ-чата для двух кадров, которые раньше терялись: `context_status`
 * и `interim_assistant`.
 *
 * Тест гоняет РЕАЛЬНЫЙ транспорт ([ChatRepository] + MockWebServer) и применяет к его
 * выдаче ровно те ТВ-компоненты, которые рисуют экран: бейдж контекста
 * ([TvContextStatusPresenter]) и аккумулятор пузыря ответа ([TvAnswerAccumulator]).
 * Смысл — зафиксировать, что кадр доезжает до ТВ и попадает в свою зону, а не
 * проваливается в `else -> Unit`:
 *
 * - `context_status` → [ContextStatusUpdate] в side-канале → текст и тон бейджа;
 * - `interim_assistant` → [ChatEvent.Chunk] (контракт мобильного чата, транспорт
 *   отбрасывает дубль с `already_streamed=true`) → промежуточная реплика в зоне
 *   ответа, финальный текст дописывается, [ChatEvent.Done] фиксирует пузырь.
 */
class TvSseContextStatusAndInterimTest {

    private val credential = ConsumedDeviceCredential("tok", "Bearer", "d", emptyList(), 1)

    /** Транскрипт сервера: контекст, промежуточная реплика (и её дубль), финал, конец. */
    private val body: String =
        "event: session_bound\ndata: {\"session_id\":\"s1\"}\n\n" +
            "event: context_status\ndata: {\"session_id\":\"s1\",\"prefill\":{\"status\":\"loaded\"," +
            "\"source\":\"recall\",\"label\":\"session recall\",\"message_count\":12}}\n\n" +
            "event: interim_assistant\ndata: {\"text\":\"Смотрю файлы.\",\"already_streamed\":false}\n\n" +
            "event: interim_assistant\ndata: {\"text\":\"Смотрю файлы.\",\"already_streamed\":true}\n\n" +
            "event: chunk\ndata: {\"text\":\"Готово: файл a.txt\"}\n\n" +
            "event: done\ndata: {}\n\n"

    private class Run(
        val events: List<ChatEvent>,
        val sideEvents: List<StreamSideEvent>,
    )

    private fun run(body: String = this.body): Run = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
            )
            val side = CopyOnWriteArrayList<StreamSideEvent>()
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(),
                credential,
                "привет",
                onSideEvent = { event -> side += event },
            ).toList()
            Run(events, side.toList())
        }
    }

    // --- context_status: бейдж префилл-контекста -------------------------------------

    @Test fun contextStatusFrameReachesTheTvBadge() {
        val run = run()
        val update = run.sideEvents.filterIsInstance<ContextStatusUpdate>().single()
        assertEquals("loaded", update.status)
        assertEquals("session recall", update.label)
        assertEquals(12, update.messageCount)

        val badge = TvContextStatusPresenter.badge(update, activeSessionId = "s1")
        assertNotNull("бейдж контекста должен быть показан", badge)
        assertEquals("Контекст загружен · session recall · 12 сообщ.", badge!!.text)
        assertEquals(TvContextTone.LOADED, badge.tone)
    }

    @Test fun contextStatusOfAForeignSessionDoesNotPaintTheTvScreen() {
        // Кадр чужого диалога доставляется транспортом, но бейдж не занимает.
        val run = run()
        val update = run.sideEvents.filterIsInstance<ContextStatusUpdate>().single()
        assertNull(TvContextStatusPresenter.badge(update, activeSessionId = "s-other"))
    }

    @Test fun contextStatusIsNotLoggedAsUnhandled() {
        val update = run().sideEvents.filterIsInstance<ContextStatusUpdate>().single()
        assertNull(TvSseFrameTrace.unhandledSideEvent(update))
    }

    // --- interim_assistant: зона ответа ----------------------------------------------

    @Test fun interimAssistantTextLandsInTheAnswerZone() {
        val run = run()
        val interim = run.events.filterIsInstance<ChatEvent.Chunk>().first()
        assertEquals("Смотрю файлы.", interim.text)
        // Тот же путь, что и у токенов: имя кадра в логе это фиксирует.
        assertEquals("chunk/interim_assistant", TvSseFrameTrace.chatEventName(interim))
    }

    @Test fun alreadyStreamedInterimIsNotDuplicatedInTheAnswerZone() {
        val run = run()
        // Дубль с already_streamed=true отбрасывает транспорт: в потоке два чанка, не три.
        assertEquals(2, run.events.filterIsInstance<ChatEvent.Chunk>().size)
    }

    @Test fun finalAnswerKeepsTheInterimTextAndDoneLocksTheBubble() {
        val run = run()
        val accumulator = TvAnswerAccumulator("Марс: ")
        var locked = false
        for (event in run.events) {
            when (event) {
                is ChatEvent.Chunk -> accumulator.onChunk(event.text)
                ChatEvent.Done -> locked = accumulator.onDone()
                else -> Unit
            }
        }
        // Мобильный контракт (appendChunk) без изобретений: промежуточная реплика —
        // часть того же пузыря, финальный текст дописывается следом.
        assertEquals("Смотрю файлы.Готово: файл a.txt", accumulator.text())
        assertTrue("Done обязан зафиксировать пузырь", locked)
        assertEquals(TvAnswerAccumulator.State.DONE, accumulator.state)
        // Опоздавший чанк после Done в пузырь уже не попадает — и это видно в логе.
        assertNull(accumulator.onChunk("хвост"))
        assertTrue(TvSseFrameTrace.droppedChunk(hasAccumulator = true, chars = 5).contains("терминален"))
    }

    @Test fun interimOnlyTurnShowsSomethingInsteadOfAnEmptyBubble() {
        // Сервер может закончить ход сразу после промежуточной реплики: пузырь не пустой.
        val interimOnly = "event: interim_assistant\n" +
            "data: {\"text\":\"Проверил — всё чисто.\",\"already_streamed\":false}\n\n" +
            "event: done\ndata: {}\n\n"
        val run = run(interimOnly)
        val accumulator = TvAnswerAccumulator("Марс: ")
        for (event in run.events) {
            when (event) {
                is ChatEvent.Chunk -> accumulator.onChunk(event.text)
                ChatEvent.Done -> accumulator.onDone()
                else -> Unit
            }
        }
        assertEquals("Проверил — всё чисто.", accumulator.text())
    }

    // --- ни один кадр не разобран молча ---------------------------------------------

    @Test fun everyFrameOfTheTurnIsAccountedFor() {
        val run = run()
        for (event in run.events) {
            assertNull("кадр ${TvSseFrameTrace.chatEventName(event)} не должен проваливаться молча",
                TvSseFrameTrace.unhandledChatEvent(event))
        }
        for (event in run.sideEvents) {
            val line = TvSseFrameTrace.unhandledSideEvent(event)
            // Контекст и субагенты обрабатываются; остальные (title/goal) — осознанно
            // без реакции, но с записью в лог.
            if (event is ContextStatusUpdate) assertNull(line)
            else assertNotNull("кадр ${TvSseFrameTrace.sideEventName(event)} должен оставить след", line)
        }
    }

    @Test fun titleFrameIsDeliveredAndTraceable() {
        val withTitle = "event: title\ndata: {\"session_id\":\"s1\",\"title\":\"Отладка\"}\n\n" +
            "event: done\ndata: {}\n\n"
        val run = run(withTitle)
        val title = run.sideEvents.filterIsInstance<SessionTitleUpdate>().single()
        assertEquals("Отладка", title.title)
        val line = TvSseFrameTrace.unhandledSideEvent(title)
        assertTrue(line!!.contains("title"))
    }
}
