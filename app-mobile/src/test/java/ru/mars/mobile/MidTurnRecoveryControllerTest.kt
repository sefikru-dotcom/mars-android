package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.AdaptiveSseReconnectPolicy
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.StreamRecoveryBackoffPolicy

/**
 * JVM-тесты recovery-цикла с ФЕЙКОВЫМ транспортом: единственная verb транспорта —
 * `attach` (GET /api/chat/stream + Last-Event-ID), повторный POST chat/start здесь
 * структурно невозможен ([RecoveryStreamTransport] не имеет метода отправки).
 *
 * Покрыто: обрыв→resume по курсору, offline→online (парковка + немедленный reattach),
 * исчерпание бюджета, HTTP 401/404, stop/навигация (cancel + generation guard),
 * терминальный Done без ретраев, серверный tool error ≠ сетевой обрыв.
 */
class MidTurnRecoveryControllerTest {

    /** Реальные формы сообщений core-network/NetworkErrors.kt (транспортные). */
    private val socketDrop = "Соединение прервано (сеть переключилась или сервер закрыл сокет): EOF"

    // --- Фейки ----------------------------------------------------------------------------------

    private class FakeTransport : RecoveryStreamTransport {
        /** Пары (streamId, lastEventId) всех вызовов attach — других verb'ов у транспорта нет. */
        val calls = mutableListOf<Pair<String, String?>>()

        /** onEventId-колбэки каждого attach (для проверки generation guard на поздних кадрах). */
        val capturedEventIdSinks = mutableListOf<(String) -> Unit>()
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
            capturedEventIdSinks += onEventId
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

        override fun onStreamEvent(event: ChatEvent) {
            events += event
        }

        override fun onAttachAttempt(attempt: Int, resumeFromEventId: String?) {
            attempts += attempt to resumeFromEventId
        }

        override fun onRecoveryParkedOffline() {
            parkedCount++
        }

        override fun onTurnCompleted() {
            completedCount++
        }

        override fun onTurnEndedInError() {
            endedInErrorCount++
        }

        override fun onReloadSession() {
            reloadedCount++
        }

        override fun onGiveUp(reason: String, suppressedTransportError: ChatEvent.Error?) {
            giveUps += reason to suppressedTransportError
        }
    }

    private class RecordingSleeper : MidTurnRecoveryController.Sleeper {
        val slept = mutableListOf<Long>()
        override suspend fun sleep(ms: Long) {
            slept += ms
        }
    }

    /** Спит, пока тест не отпустит: детерминированная точка для cancel/stop-проверок. */
    private class BlockingSleeper : MidTurnRecoveryController.Sleeper {
        val slept = mutableListOf<Long>()
        val release = CompletableDeferred<Unit>()
        override suspend fun sleep(ms: Long) {
            slept += ms
            release.await()
        }
    }

    /** Координатор с нулевыми задержками и настраиваемым бюджетом — тесты без реального времени. */
    private fun fastCoordinator(maxRetries: Int = 8) = MidTurnRecoveryCoordinator(
        backoff = StreamRecoveryBackoffPolicy(initialDelayMs = 0L, jitterFraction = 0.0),
        reconnect = AdaptiveSseReconnectPolicy(initialDelayMs = 0L, jitterFraction = 0.0, maxRetries = maxRetries),
    )

    private fun drop(message: String = socketDrop) = ChatEvent.Error(message)

    // --- Обрыв → resume ---------------------------------------------------------------------------

    @Test
    fun deliveredDropResumesWithLastEventIdAndNeverReposts() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
        )
        // Attach #1: id-кадры двигают курсор, затем транспортный обрыв (реальная форма NetworkErrors).
        transport.script { onEventId ->
            onEventId("evt-1")
            onEventId("evt-2")
            listOf(ChatEvent.Chunk("Part1", "evt-2"), drop())
        }
        // Attach #2: сервер реплеит журнал от evt-2 и доводит ход до конца.
        transport.scriptEvents(listOf(ChatEvent.Chunk("Part2"), ChatEvent.Done))

        controller.attachActiveTurn("st-1").join()

        // Ровно два attach, второй — с Last-Event-ID из трекера; других вызовов транспорта нет:
        // повторный POST chat/start после доставки структурно невозможен.
        assertEquals(listOf("st-1" to null, "st-1" to "evt-2"), transport.calls)
        assertEquals(listOf(0 to null, 1 to "evt-2"), listener.attempts)
        // Текст не дублируется: одна «Part1», одна «Part2».
        assertEquals(
            listOf("Part1", "Part2"),
            listener.events.filterIsInstance<ChatEvent.Chunk>().map { it.text },
        )
        // Транспортная ошибка подавлена — в транскрипт не ушла.
        assertTrue(listener.events.none { it is ChatEvent.Error })
        assertEquals(1, listener.completedCount)
        assertEquals(0, listener.giveUps.size)
    }

    @Test
    fun cleanEofWithoutDoneIsRetriedAsDrop() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this, transport = transport, listener = listener, coordinator = fastCoordinator(),
        )
        transport.scriptEvents(listOf(ChatEvent.Chunk("Tail"), ChatEvent.Done))

        // Ход доставлен, но первичный collect закрылся чисто (EOF без Done), dropError == null.
        controller.beginRecoveryAfterDrop("st-1", dropError = null).join()

        assertEquals(listOf("st-1" to null), transport.calls)
        assertEquals(1, listener.completedCount)
    }

    // --- Offline → online -------------------------------------------------------------------------

    @Test
    fun offlineParksCycleAndNetworkReturnWakesImmediateReattach() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = RecordingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        controller.onNetworkChanged(false)
        transport.scriptEvents(listOf(ChatEvent.Chunk("Tail"), ChatEvent.Done))

        val job = controller.beginRecoveryAfterDrop("st-1", drop(socketDrop))
        // Ждём парковку и возвращаем сеть (тот же путь, что connectivity observer в Activity).
        val waker = launch {
            while (listener.parkedCount == 0) yield()
            controller.onNetworkChanged(true)
        }
        job.join()
        waker.cancel()

        assertEquals(1, listener.parkedCount)
        // Offline→online: ядро выдаёт вооружённую немедленную попытку (0 мс), без лестницы.
        assertEquals(listOf(0L), sleeper.slept)
        assertEquals(listOf("st-1" to null), transport.calls)
        assertEquals(1, listener.completedCount)
    }

    @Test
    fun repeatedOfflineFlapsDoNotStrandParkedCycle() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this, transport = transport, listener = listener, coordinator = fastCoordinator(),
        )
        controller.onNetworkChanged(false)
        transport.scriptEvents(listOf(ChatEvent.Done))

        val job = controller.beginRecoveryAfterDrop("st-1", drop(socketDrop))
        val waker = launch {
            while (listener.parkedCount == 0) yield()
            // LOSING→OFFLINE: два `false` подряд — старый CompletableDeferred-гейт здесь бы
            // навсегда оставил цикл на устаревшем объекте; StateFlow-ворота устойчивы.
            controller.onNetworkChanged(false)
            controller.onNetworkChanged(true)
        }
        job.join()
        waker.cancel()

        assertEquals(1, listener.completedCount)
    }

    // --- Исчерпание бюджета ------------------------------------------------------------------------

    @Test
    fun exhaustedRetryBudgetGivesUpWithSuppressedTransportError() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(maxRetries = 2),
        )
        // Бюджет 2: каждый attach снова рвётся транспортной ошибкой.
        repeat(2) { transport.scriptEvents(listOf(drop())) }

        controller.beginRecoveryAfterDrop("st-1", drop()).join()

        assertEquals(2, transport.calls.size)
        assertEquals(1, listener.giveUps.size)
        val (reason, suppressed) = listener.giveUps.single()
        assertTrue("reason=$reason", reason.contains("exhausted"))
        assertSame("подавленная транспортная ошибка всплывает только на GiveUp", socketDrop, suppressed?.message)
        // Подавленные ошибки ни разу не дошли до транскрипта.
        assertTrue(listener.events.none { it is ChatEvent.Error })
        assertEquals(0, listener.completedCount)
    }

    // --- HTTP 401 / 404 ---------------------------------------------------------------------------

    @Test
    fun http401GivesUpImmediatelyWithoutReattach() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this, transport = transport, listener = listener, coordinator = fastCoordinator(),
        )

        controller.beginRecoveryAfterDrop("st-1", drop("HTTP 401: unauthorized")).join()

        assertEquals("attach с отозванным credential не выполняется", 0, transport.calls.size)
        val (reason, suppressed) = listener.giveUps.single()
        assertEquals("auth rejected (HTTP 401)", reason)
        assertEquals("HTTP 401: unauthorized", suppressed?.message)
    }

    @Test
    fun http404FromAttachReloadsSessionWithoutRetry() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this, transport = transport, listener = listener, coordinator = fastCoordinator(),
        )
        transport.scriptEvents(listOf(drop("HTTP 404: stream not found")))

        controller.attachActiveTurn("st-1").join()

        assertEquals(1, transport.calls.size)
        assertEquals(1, listener.reloadedCount)
        assertEquals(0, listener.giveUps.size)
        assertEquals(0, listener.completedCount)
    }

    // --- Stop / навигация / generation guard --------------------------------------------------------

    @Test
    fun cancelDuringBackoffStopsCycleBeforeNextAttach() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = BlockingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )

        val job = controller.beginRecoveryAfterDrop("st-1", drop())
        while (sleeper.slept.isEmpty()) yield() // цикл встал на backoff-сон
        controller.cancelRecovery() // «стоп»/смена сессии/нового чата
        sleeper.release.complete(Unit)
        job.join()

        assertFalse(controller.isRecovering)
        assertEquals("после cancel следующий attach не выполняется", 0, transport.calls.size)
        assertEquals(0, listener.giveUps.size)
        assertEquals(0, listener.completedCount)
    }

    @Test
    fun generationGuardDropsStaleEventIdsAfterCancel() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = BlockingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        transport.script { onEventId ->
            onEventId("evt-1")
            listOf(drop())
        }

        val job = controller.attachActiveTurn("st-1")
        while (sleeper.slept.isEmpty()) yield()
        assertEquals("evt-1", controller.resumeTracker.lastEventId)

        controller.cancelRecovery()
        // Поздний id-кадр убитого поколения (сеть «догнала» ответом) не двигает курсор.
        transport.capturedEventIdSinks.single()("evt-STALE")
        assertEquals("evt-1", controller.resumeTracker.lastEventId)

        sleeper.release.complete(Unit)
        job.join()
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun onNewTurnKillsRecoveryAndResetsCursor() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = BlockingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        transport.script { onEventId ->
            onEventId("evt-7")
            listOf(drop())
        }

        val job = controller.attachActiveTurn("st-1")
        while (sleeper.slept.isEmpty()) yield()
        assertEquals("evt-7", controller.resumeTracker.lastEventId)

        // Новый пользовательский ход: предыдущий цикл убит, курсор чужого стрима стёрт.
        controller.onNewTurn()
        sleeper.release.complete(Unit)
        job.join()

        assertFalse(controller.isRecovering)
        assertNull(controller.resumeTracker.lastEventId)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun supersededCycleStaysSilentWhileNewOneRuns() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = BlockingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        transport.scriptEvents(listOf(drop())) // цикл A: обрыв → backoff (спит)
        transport.scriptEvents(listOf(ChatEvent.Chunk("B"), ChatEvent.Done)) // цикл B: успешен

        val jobA = controller.attachActiveTurn("st-A")
        while (sleeper.slept.isEmpty()) yield()
        val jobB = controller.attachActiveTurn("st-B") // новое поколение убивает A
        jobB.join()
        sleeper.release.complete(Unit)
        jobA.join()

        assertEquals(listOf("st-A" to null, "st-B" to null), transport.calls)
        // События только цикла B; A не дал ни GiveUp, ни дублей.
        assertEquals(listOf("B"), listener.events.filterIsInstance<ChatEvent.Chunk>().map { it.text })
        assertEquals(0, listener.giveUps.size)
        assertEquals(1, listener.completedCount)
    }

    // --- Терминальные состояния ----------------------------------------------------------------------

    @Test
    fun terminalDoneIsNeverRetried() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = RecordingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        transport.scriptEvents(listOf(ChatEvent.Chunk("All"), ChatEvent.Done))

        controller.attachActiveTurn("st-1").join()

        assertEquals(1, transport.calls.size)
        assertEquals(emptyList<Long>(), sleeper.slept)
        assertEquals(1, listener.completedCount)
        assertTrue(listener.attempts.single() == (0 to null))
    }

    @Test
    fun serverToolErrorIsNotClassifiedAsTransportDrop() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val sleeper = RecordingSleeper()
        val controller = MidTurnRecoveryController(
            scope = this,
            transport = transport,
            listener = listener,
            coordinator = fastCoordinator(),
            sleeper = sleeper,
        )
        // Серверный tool error: журнал хода, стрим закрывается штатно — НЕ сетевой обрыв.
        val toolError = ChatEvent.Error("command not found: git push --force")
        transport.scriptEvents(listOf(toolError))

        controller.attachActiveTurn("st-1").join()

        assertEquals("ошибка хода дошла до транскрипта как есть", listOf(toolError), listener.events)
        assertEquals(1, listener.endedInErrorCount)
        assertEquals("без reconnect'а: reattach отреплеил бы ту же ошибку", 1, transport.calls.size)
        assertEquals(emptyList<Long>(), sleeper.slept)
        assertEquals(0, listener.giveUps.size)
        assertEquals(0, listener.reloadedCount)
    }

    @Test
    fun toolErrorFollowedByTransportDropStillRecovers() = runBlocking {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val controller = MidTurnRecoveryController(
            scope = this, transport = transport, listener = listener, coordinator = fastCoordinator(),
        )
        // Ход ещё жив: tool error — часть журнала, но следом рвётся транспорт.
        transport.scriptEvents(listOf(ChatEvent.Error("File not found: /tmp/x"), drop()))
        transport.scriptEvents(listOf(ChatEvent.Done))

        controller.attachActiveTurn("st-1").join()

        assertEquals(2, transport.calls.size)
        assertEquals(1, listener.completedCount)
        // Tool error виден в транскрипте, транспортный — подавлен.
        assertEquals(
            listOf("File not found: /tmp/x"),
            listener.events.filterIsInstance<ChatEvent.Error>().map { it.message },
        )
    }
}
