package ru.mars.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.AdaptiveSseReconnectPolicy
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.StreamRecoveryBackoffPolicy

/**
 * Регрессии швов [MidTurnRecoveryController], которые основной
 * MidTurnRecoveryControllerTest не фиксирует (новый файл — чтобы не пересекаться с
 * параллельными правками общего теста):
 *
 * 1. Per-turn cap координатора работает СКВОЗЬ контроллер: id-less «liveness»-кадры
 *    (реплей журнала без `id:`/без подтверждённого прогресса) сбрасывают streak, но НЕ
 *    переустанавливают cap — цикл обязан завершиться GiveUp, а не крутиться вечно.
 * 2. Зеркально: подтверждённый прогресс (кадры с новыми id) переустанавливает cap —
 *    длинный честный ход с частыми обрывами не упирается в потолок.
 * 3. Локальный [ChatEvent.StreamStarted] не считается «здоровьем стрима» и не сбрасывает
 *    лестницу: номера attempt растут даже когда скрипт начинается со StreamStarted.
 * 4. Структурный guard «recovery не должен повторять POST»: единственный verb транспорта
 *    — `attach`; появление любого send/start/post-метода в [RecoveryStreamTransport]
 *    роняет этот тест.
 */
class MidTurnRecoveryControllerSeamRegressionTest {

    private val socketDrop = "Соединение прервано (сеть переключилась или сервер закрыл сокет): EOF"

    // --- Минимальные фейки (самодостаточные, без импорта приватных из общего теста) --------------

    private class FakeTransport : RecoveryStreamTransport {
        val calls = mutableListOf<Pair<String, String?>>()
        private val scripts = ArrayDeque<(onEventId: (String) -> Unit) -> List<ChatEvent>>()

        fun script(block: (onEventId: (String) -> Unit) -> List<ChatEvent>) {
            scripts.addLast(block)
        }

        fun scriptEvents(events: List<ChatEvent>) = script { events }

        override fun attach(
            streamId: String,
            lastEventId: String?,
            onEventId: (String) -> Unit,
        ): Flow<ChatEvent> {
            calls += streamId to lastEventId
            val next = scripts.removeFirstOrNull() ?: throw IllegalStateException("unexpected attach")
            return flow { next(onEventId).forEach { emit(it) } }
        }
    }

    private class RecordingListener : MidTurnRecoveryController.Listener {
        val events = mutableListOf<ChatEvent>()
        val attempts = mutableListOf<Pair<Int, String?>>()
        var parkedCount = 0
        var completedCount = 0
        var endedInErrorCount = 0
        var reloadedCount = 0
        val giveUps = mutableListOf<Pair<String, ChatEvent.Error?>>()

        override fun onStreamEvent(event: ChatEvent) { events += event }
        override fun onAttachAttempt(attempt: Int, resumeFromEventId: String?) { attempts += attempt to resumeFromEventId }
        override fun onRecoveryParkedOffline() { parkedCount++ }
        override fun onTurnCompleted() { completedCount++ }
        override fun onTurnEndedInError() { endedInErrorCount++ }
        override fun onReloadSession() { reloadedCount++ }
        override fun onGiveUp(reason: String, suppressedTransportError: ChatEvent.Error?) {
            giveUps += reason to suppressedTransportError
        }
    }

    private fun coordinator(maxRetries: Int, maxReattachesPerTurn: Int = MidTurnRecoveryCoordinator.DEFAULT_MAX_REATTACHES_PER_TURN) =
        MidTurnRecoveryCoordinator(
            backoff = StreamRecoveryBackoffPolicy(initialDelayMs = 0L, jitterFraction = 0.0),
            reconnect = AdaptiveSseReconnectPolicy(initialDelayMs = 0L, jitterFraction = 0.0, maxRetries = maxRetries),
            maxReattachesPerTurn = maxReattachesPerTurn,
        )

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, transport: RecoveryStreamTransport, listener: MidTurnRecoveryController.Listener, coordinator: MidTurnRecoveryCoordinator) =
        MidTurnRecoveryController(scope = scope, transport = transport, listener = listener, coordinator = coordinator)

    private fun drop() = ChatEvent.Error(socketDrop)

    // --- 1. Per-turn cap сквозь контроллер ---------------------------------------------------------

    @Test
    fun idLessLivenessLoopTerminatesAtPerTurnCap() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        // Бюджет reconnect заведомо больше cap: единственный возможный GiveUp — «per-turn cap».
        val cap = MidTurnRecoveryCoordinator.DEFAULT_MAX_REATTACHES_PER_TURN
        val controller = controller(this, transport, listener, coordinator(maxRetries = cap * 4))

        repeat(cap) { i ->
            // Каждый attach «живой» (id-less кадр сбрасывает streak), но обрывается:
            // без cap это вечный цикл.
            transport.scriptEvents(listOf(ChatEvent.Chunk("alive-$i"), drop()))
        }

        controller.beginRecoveryAfterDrop("st-1", drop()).join()

        assertEquals(cap, transport.calls.size)
        val (reason, _) = listener.giveUps.single()
        assertTrue("reason=$reason", reason.contains("per-turn reattach cap"))
        assertEquals(0, listener.completedCount)
        // Транспортные ошибки подавлены до самого GiveUp — транскрипт не замусорен.
        assertTrue(listener.events.none { it is ChatEvent.Error })
    }

    // --- 2. Подтверждённый прогресс переустанавливает cap ------------------------------------------

    @Test
    fun verifiedProgressRearmsPerTurnCapAcrossManyReattaches() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = controller(this, transport, listener, coordinator(maxRetries = 100, maxReattachesPerTurn = 3))

        // 5 обрывов подряд (больше cap=3), но каждый attach двигает курсор новым id —
        // cap переустанавливается, цикл не сдаётся.
        repeat(5) { i ->
            transport.script { onEventId ->
                onEventId("evt-${i + 1}")
                listOf(ChatEvent.Chunk("part-${i + 1}"), drop())
            }
        }
        transport.scriptEvents(listOf(ChatEvent.Done))

        controller.beginRecoveryAfterDrop("st-1", drop()).join()

        assertEquals(6, transport.calls.size)
        assertEquals(1, listener.completedCount)
        assertEquals(0, listener.giveUps.size)
        // Каждый reattach идёт с последним подтверждённым id (Last-Event-ID).
        assertEquals("evt-5", transport.calls.last().second)
    }

    // --- 3. Локальный StreamStarted не сбрасывает лестницу -----------------------------------------

    @Test
    fun localStreamStartedDoesNotResetFailureStreak() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = controller(this, transport, listener, coordinator(maxRetries = 8))

        // StreamStarted — ЛОКАЛЬНОЕ событие (синтез хоста), а не кадр провода: оно не должно
        // дёргать coordinator.onStreamHealthy, иначе лестница retry вечно стоит на attempt 1.
        transport.scriptEvents(listOf(ChatEvent.StreamStarted("st-1"), drop()))
        transport.scriptEvents(listOf(ChatEvent.StreamStarted("st-1"), drop()))
        transport.scriptEvents(listOf(ChatEvent.Done))

        controller.beginRecoveryAfterDrop("st-1", drop()).join()

        // attempt растёт 1 → 2 → 3: streak НЕ сброшен локальным StreamStarted.
        assertEquals(listOf(1, 2, 3), listener.attempts.map { it.first })
        assertEquals(1, listener.completedCount)
    }

    // --- 4. Структурный guard шва -------------------------------------------------------------------

    @Test
    fun recoveryTransportSeamExposesAttachVerbOnly() {
        // Инвариант «recovery никогда не повторяет POST /api/chat/start» держится на том,
        // что у транспорта физически нет send-verb'а. Любое новое имя метода = регресс.
        val methodNames = RecoveryStreamTransport::class.java.declaredMethods.map { it.name }.distinct()
        assertEquals(listOf("attach"), methodNames.sorted())
    }
}
