package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentUpdate
import ru.mars.network.repository.SubagentStatusPolicy

/**
 * Доска живых статусов субагентов (волна C, фича B): презентация состояния
 * редьюсера SubagentStatusPolicy на карточке родителя — читаемость без
 * открытия дочернего экрана, порядок «активные первыми», причина провала
 * только у Failed, отпечаток доски гоняет ровно одну карточку.
 */
class SubagentBoardPresenterTest {

    private fun status(
        id: String,
        state: SubagentState,
        displayName: String? = null,
        model: String? = null,
        phase: String? = null,
        failureReason: String? = null,
    ) = SubagentStatus(
        childId = id,
        state = state,
        displayName = displayName,
        model = model,
        phase = phase,
        failureReason = failureReason,
    )

    @Test
    fun `empty board stays invisible`() {
        val board = SubagentBoardPresenter.present(emptyMap())
        assertFalse(board.visible)
        assertEquals(0, board.activeCount)
    }

    @Test
    fun `active children come first and get the live indicator`() {
        val board = SubagentBoardPresenter.present(
            linkedMapOf(
                "c-done" to status("c-done", SubagentState.Completed),
                "c-run" to status("c-run", SubagentState.Running, displayName = "Сборщик"),
            )
        )
        assertTrue(board.visible)
        assertEquals(1, board.activeCount)
        assertEquals("c-run", board.rows[0].childId)
        assertTrue(board.rows[0].active)
        assertEquals("Сборщик", board.rows[0].title)
        assertEquals(SubagentBoardPresenter.BoardBadge.RUNNING, board.rows[0].badge)
        assertEquals("работает", board.rows[0].statusLabel)
        assertFalse(board.rows[1].active)
        assertEquals("готово", board.rows[1].statusLabel)
    }

    @Test
    fun `missing display name falls back to childId`() {
        val board = SubagentBoardPresenter.present(mapOf("c-1" to status("c-1", SubagentState.Running)))
        assertEquals("c-1", board.rows.single().title)
    }

    @Test
    fun `failed row carries the reason, others never do`() {
        val board = SubagentBoardPresenter.present(
            linkedMapOf(
                "c-fail" to status("c-fail", SubagentState.Failed, failureReason = "timeout"),
                "c-ok" to status("c-ok", SubagentState.Completed, failureReason = null),
            )
        )
        assertEquals("timeout", board.rows[0].failureReason)
        assertEquals(SubagentBoardPresenter.BoardBadge.ERROR, board.rows[0].badge)
        assertNull(board.rows[1].failureReason)
    }

    @Test
    fun `detail joins phase and model`() {
        val board = SubagentBoardPresenter.present(
            mapOf("c" to status("c", SubagentState.Running, model = "qwen3", phase = "анализ"))
        )
        assertEquals("шаг: анализ · qwen3", board.rows.single().detail)
    }

    @Test
    fun `header counts active children`() {
        val board = SubagentBoardPresenter.present(
            linkedMapOf(
                "a" to status("a", SubagentState.Running),
                "b" to status("b", SubagentState.Waiting),
                "c" to status("c", SubagentState.Stopped),
            )
        )
        assertEquals(2, board.activeCount)
        assertEquals("Субагенты · 2/3 в работе", board.headerLabel)
    }

    @Test
    fun `ui state stamps reducer board onto the streaming bubble`() {
        val uiState = ChatUiState()
        uiState.begin("задача")
        assertTrue(uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Start, childId = "c-1", displayName = "Ридер")))
        val bubble = uiState.messages.last()
        assertEquals(SubagentState.Running, bubble.subagents.getValue("c-1").state)
        // Повтор того же кадра доску не меняет — карточка не перерисовывается.
        assertFalse(uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Start, childId = "c-1", displayName = "Ридер")))
        // Прогресс меняет шаг и возвращает true.
        assertTrue(uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Progress, childId = "c-1", phase = "чтение")))
        assertEquals("чтение", uiState.messages.last().subagents.getValue("c-1").phase)
        // Конец хода: терминальный кадр закрывает ребёнка.
        assertTrue(uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Complete, childId = "c-1", rawStatus = "completed")))
        assertEquals(SubagentState.Completed, uiState.messages.last().subagents.getValue("c-1").state)
    }

    @Test
    fun `turn boundary opens a clean board`() {
        val uiState = ChatUiState()
        uiState.begin("задача")
        uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Start, childId = "c-1"))
        uiState.complete()
        uiState.begin("новая задача")
        assertTrue(uiState.messages.last().subagents.isEmpty())
        // Редьюсер тоже чист: новый ребёнок не наследует состояние прежнего.
        uiState.applySubagentUpdate(SubagentUpdate(SubagentUpdate.Kind.Progress, childId = "c-1", phase = "x"))
        val board = SubagentStatusPolicy.reduce(emptyList())
        assertTrue(board.isEmpty())
        assertFalse(uiState.messages.last().subagents.containsKey("c-2"))
    }
}
