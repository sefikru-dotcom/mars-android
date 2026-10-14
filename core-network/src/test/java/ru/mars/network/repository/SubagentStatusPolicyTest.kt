package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentUpdate

/**
 * Чистый редьюсер состояний детей-субагентов (P0-2, клиентская половина).
 *
 * Правила, зафиксированные здесь (консистентны с серверным api/worker_status.py):
 * завершённый/упавший/остановленный ребёнок не выглядит активным; повторный старт
 * того же id не плодит дубли и не воскрешает терминального ребёнка; неизвестное
 * событие не переводит ребёнка в Running; пустой вход — пустая доска.
 */
class SubagentStatusPolicyTest {

    private fun start(
        id: String? = "sa-1",
        name: String? = "собрать отчёт",
        model: String? = "gpt-6.1-sol",
        phase: String? = null,
        rawStatus: String? = null,
    ) = SubagentUpdate(SubagentUpdate.Kind.Start, id, displayName = name, model = model, phase = phase, rawStatus = rawStatus)

    private fun progress(
        id: String? = "sa-1",
        phase: String? = "terminal: ls -la",
        model: String? = null,
        rawStatus: String? = null,
    ) = SubagentUpdate(SubagentUpdate.Kind.Progress, id, phase = phase, model = model, rawStatus = rawStatus)

    private fun waiting(id: String? = "sa-1", rawStatus: String? = "waiting") =
        SubagentUpdate(SubagentUpdate.Kind.Waiting, id, rawStatus = rawStatus)

    private fun complete(
        id: String? = "sa-1",
        rawStatus: String? = "completed",
        failureReason: String? = null,
        phase: String? = null,
    ) = SubagentUpdate(SubagentUpdate.Kind.Complete, id, rawStatus = rawStatus, failureReason = failureReason, phase = phase)

    private val turnStopped = SubagentUpdate(SubagentUpdate.Kind.TurnStopped, childId = null)

    private fun unknown(id: String? = "sa-1") = SubagentUpdate(SubagentUpdate.Kind.Unknown, id)

    // ---- пустой вход ----------------------------------------------------------------------------

    @Test
    fun emptyInputYieldsEmptyBoard() {
        assertTrue(SubagentStatusPolicy.reduce(emptyList()).isEmpty())
    }

    // ---- Running --------------------------------------------------------------------------------

    @Test
    fun startCreatesRunningChildWithIdentityFields() {
        val board = SubagentStatusPolicy.reduce(listOf(start()))
        assertEquals(
            SubagentStatus(childId = "sa-1", state = SubagentState.Running, displayName = "собрать отчёт", model = "gpt-6.1-sol"),
            board.getValue("sa-1")
        )
        assertTrue(board.getValue("sa-1").isActive)
        assertFalse(board.getValue("sa-1").isTerminal)
    }

    @Test
    fun repeatedStartOfSameIdDoesNotDuplicate() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), start(), start()))
        assertEquals(1, board.size)
        assertEquals(SubagentState.Running, board.getValue("sa-1").state)
    }

    @Test
    fun startWithoutChildIdIsIgnored() {
        assertTrue(SubagentStatusPolicy.reduce(listOf(start(id = null))).isEmpty())
        assertTrue(SubagentStatusPolicy.reduce(listOf(start(id = "   "))).isEmpty())
    }

    // ---- Completed / Failed / Stopped: терминальные дети не активны ------------------------------

    @Test
    fun completedChildIsNotActive() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = "completed")))
        val child = board.getValue("sa-1")
        assertEquals(SubagentState.Completed, child.state)
        assertFalse("завершённый ребёнок не должен выглядеть активным", child.isActive)
        assertTrue(child.isTerminal)
    }

    @Test
    fun completeWithoutStatusIsCompleted() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = null)))
        assertEquals(SubagentState.Completed, board.getValue("sa-1").state)
        assertFalse(board.getValue("sa-1").isActive)
    }

    @Test
    fun failedChildIsNotActive() {
        val byStatus = SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = "failed")))
        assertEquals(SubagentState.Failed, byStatus.getValue("sa-1").state)
        assertFalse(byStatus.getValue("sa-1").isActive)
        // failure_reason без статуса — тоже Failed (серверная классификация worker_status.py).
        val byReason = SubagentStatusPolicy.reduce(
            listOf(start(), complete(rawStatus = null, failureReason = "child timeout"))
        )
        assertEquals(SubagentState.Failed, byReason.getValue("sa-1").state)
        for (raw in listOf("error", "failure", "timeout")) {
            assertEquals(
                "status=$raw",
                SubagentState.Failed,
                SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = raw))).getValue("sa-1").state
            )
        }
    }

    @Test
    fun stoppedChildIsNotActive() {
        for (raw in listOf("stopped", "cancelled", "canceled", "interrupted")) {
            val board = SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = raw)))
            assertEquals("status=$raw", SubagentState.Stopped, board.getValue("sa-1").state)
            assertFalse("остановленный ребёнок не должен выглядеть активным", board.getValue("sa-1").isActive)
        }
    }

    // ---- Терминальное состояние не воскрешается (реплей журнала при reattach) ---------------------

    @Test
    fun startAfterTerminalDoesNotResurrectChild() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), complete(), start()))
        assertEquals(1, board.size)
        assertEquals(SubagentState.Completed, board.getValue("sa-1").state)
        assertFalse(board.getValue("sa-1").isActive)
    }

    @Test
    fun progressAfterTerminalKeepsTerminalState() {
        val failed = SubagentStatusPolicy.reduce(
            listOf(start(), complete(rawStatus = "failed", failureReason = "boom"), progress())
        )
        assertEquals(SubagentState.Failed, failed.getValue("sa-1").state)
        val stopped = SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = "stopped"), progress()))
        assertEquals(SubagentState.Stopped, stopped.getValue("sa-1").state)
    }

    // ---- Waiting ----------------------------------------------------------------------------------

    @Test
    fun waitingChildIsWaitingAndStillActive() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), waiting()))
        assertEquals(SubagentState.Waiting, board.getValue("sa-1").state)
        assertTrue("ожидающий ребёнок жив", board.getValue("sa-1").isActive)
        assertFalse(board.getValue("sa-1").isTerminal)
    }

    @Test
    fun startWithWaitingStatusBecomesWaitingNotRunning() {
        val board = SubagentStatusPolicy.reduce(listOf(start(rawStatus = "waiting")))
        assertEquals(SubagentState.Waiting, board.getValue("sa-1").state)
    }

    @Test
    fun progressResumesWaitingChildToRunning() {
        val board = SubagentStatusPolicy.reduce(listOf(start(), waiting(), progress()))
        assertEquals(SubagentState.Running, board.getValue("sa-1").state)
    }

    @Test
    fun waitingChildCanReachEveryTerminalState() {
        assertEquals(
            SubagentState.Completed,
            SubagentStatusPolicy.reduce(listOf(start(), waiting(), complete())).getValue("sa-1").state
        )
        assertEquals(
            SubagentState.Failed,
            SubagentStatusPolicy.reduce(listOf(start(), waiting(), complete(rawStatus = "error"))).getValue("sa-1").state
        )
        assertEquals(
            SubagentState.Stopped,
            SubagentStatusPolicy.reduce(listOf(start(), waiting(), turnStopped)).getValue("sa-1").state
        )
    }

    // ---- Unknown: непонятное событие не делает ребёнка активным -----------------------------------

    @Test
    fun unknownEventNeverMovesChildToRunning() {
        // Ожидающий ребёнок после неизвестного события остаётся ожидающим (не Running).
        val waitingBoard = SubagentStatusPolicy.reduce(listOf(start(), waiting(), unknown()))
        assertEquals(SubagentState.Waiting, waitingBoard.getValue("sa-1").state)
        // Терминальный ребёнок после неизвестного события остаётся терминальным.
        val doneBoard = SubagentStatusPolicy.reduce(listOf(start(), complete(), unknown()))
        assertEquals(SubagentState.Completed, doneBoard.getValue("sa-1").state)
        assertFalse(doneBoard.getValue("sa-1").isActive)
        // Неизвестное событие про неучтённого ребёнка не создаёт запись.
        assertTrue(SubagentStatusPolicy.reduce(listOf(unknown(id = "sa-ghost"))).isEmpty())
    }

    @Test
    fun progressForUntrackedChildDoesNotFabricateActiveChild() {
        // Прогресс-шум без старта не invents активного ребёнка.
        assertTrue(SubagentStatusPolicy.reduce(listOf(progress(id = "sa-ghost"))).isEmpty())
        assertTrue(SubagentStatusPolicy.reduce(listOf(waiting(id = "sa-ghost"))).isEmpty())
    }

    @Test
    fun completeForUntrackedChildLandsTerminalAfterMissedStart() {
        // Reattach мог пропустить старт: исход ребёнка обязан приземлиться терминально.
        val board = SubagentStatusPolicy.reduce(listOf(complete(id = "sa-late", rawStatus = "failed")))
        assertEquals(SubagentState.Failed, board.getValue("sa-late").state)
        assertFalse(board.getValue("sa-late").isActive)
    }

    // ---- Остановка хода -----------------------------------------------------------------------------

    @Test
    fun turnStoppedMarksActiveChildrenStoppedAndKeepsTerminals() {
        val board = SubagentStatusPolicy.reduce(
            listOf(
                start(id = "sa-run"),
                start(id = "sa-wait"), waiting(id = "sa-wait"),
                start(id = "sa-done"), complete(id = "sa-done"),
                start(id = "sa-fail"), complete(id = "sa-fail", rawStatus = "failed"),
                turnStopped,
            )
        )
        assertEquals(SubagentState.Stopped, board.getValue("sa-run").state)
        assertEquals(SubagentState.Stopped, board.getValue("sa-wait").state)
        assertEquals(SubagentState.Completed, board.getValue("sa-done").state)
        assertEquals(SubagentState.Failed, board.getValue("sa-fail").state)
        assertTrue("после остановки хода активных детей нет", board.values.none { it.isActive })
    }

    @Test
    fun turnStoppedOnEmptyOrTerminalBoardIsNoOp() {
        assertTrue(SubagentStatusPolicy.reduce(listOf(turnStopped)).isEmpty())
        val done = SubagentStatusPolicy.reduce(listOf(start(), complete()))
        // Стоп хода после нормального завершения ничего не меняет: дети уже отчитались.
        assertEquals(done, SubagentStatusPolicy.reduce(listOf(start(), complete(), turnStopped)))
    }

    // ---- Обновление данных в реальном времени -------------------------------------------------------

    @Test
    fun refreshKeepsKnownFieldsAndUpdatesPhase() {
        val board = SubagentStatusPolicy.reduce(
            listOf(
                start(model = "gpt-6.1-sol", name = "собрать отчёт"),
                // Прогресс без модели/имени: известные поля не затираются null-ами.
                progress(phase = "web_search: stavropol"),
            )
        )
        val child = board.getValue("sa-1")
        assertEquals(SubagentState.Running, child.state)
        assertEquals("gpt-6.1-sol", child.model)
        assertEquals("собрать отчёт", child.displayName)
        assertEquals("web_search: stavropol", child.phase)
    }

    @Test
    fun severalChildrenTrackedIndependently() {
        val board = SubagentStatusPolicy.reduce(
            listOf(
                start(id = "sa-1", name = "отчёт", model = "gpt-6.1-sol"),
                start(id = "sa-2", name = "проверка CI", model = "gemini-3.8-flash"),
                progress(id = "sa-2", phase = "terminal: ./gradlew test"),
                complete(id = "sa-1"),
            )
        )
        assertEquals(2, board.size)
        assertEquals(SubagentState.Completed, board.getValue("sa-1").state)
        assertEquals(SubagentState.Running, board.getValue("sa-2").state)
        assertEquals("gemini-3.8-flash", board.getValue("sa-2").model)
        assertEquals("terminal: ./gradlew test", board.getValue("sa-2").phase)
        assertEquals(listOf("sa-2"), board.values.filter { it.isActive }.map { it.childId })
    }

    // ---- Пошаговое применение (real-time) -----------------------------------------------------------

    @Test
    fun applyUpdatesBoardStepByStep() {
        var board = SubagentStatusPolicy.apply(emptyMap(), start())
        assertEquals(SubagentState.Running, board.getValue("sa-1").state)
        board = SubagentStatusPolicy.apply(board, complete())
        assertEquals(SubagentState.Completed, board.getValue("sa-1").state)
        // Предыдущий снимок не мутирован (чистая функция).
        val stale = SubagentStatusPolicy.apply(emptyMap(), start())
        assertEquals(SubagentState.Running, stale.getValue("sa-1").state)
    }

    @Test
    fun allFiveStatesAreReachable() {
        val states = setOf(
            SubagentStatusPolicy.reduce(listOf(start())).getValue("sa-1").state,
            SubagentStatusPolicy.reduce(listOf(start(), complete())).getValue("sa-1").state,
            SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = "failed"))).getValue("sa-1").state,
            SubagentStatusPolicy.reduce(listOf(start(), waiting())).getValue("sa-1").state,
            SubagentStatusPolicy.reduce(listOf(start(), complete(rawStatus = "stopped"))).getValue("sa-1").state,
        )
        assertEquals(SubagentState.entries.toSet(), states)
        assertEquals(5, SubagentState.entries.size)
    }

    @Test
    fun statusFieldsDefaultToNullWhenServerDidNotReport() {
        val board = SubagentStatusPolicy.reduce(listOf(start(id = "sa-x", name = null, model = null)))
        val child = board.getValue("sa-x")
        assertNull(child.displayName)
        assertNull(child.model)
        assertNull(child.phase)
    }
}
