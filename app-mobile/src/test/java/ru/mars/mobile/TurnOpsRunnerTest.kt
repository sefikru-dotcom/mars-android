package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionBranchResult
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionTitleRegenerateResult
import ru.mars.network.model.SessionTurnOpResult
import ru.mars.network.repository.SessionBranchOutcome
import ru.mars.network.repository.SessionMutationOutcome
import ru.mars.network.repository.SessionTitleRegenerateOutcome
import ru.mars.network.repository.SessionTurnOpOutcome

/**
 * A13: мапперы исходов репозитория в готовый к показу [TurnOpOutcome].
 *
 * Проверяется именно решение «куда вести экран после успеха»: ветка/копия создают НОВЫЙ
 * диалог, а undo/retry/truncate/clear меняют ТЕКУЩИЙ — перепутать эти два случая значит
 * показать пользователю чужой транскрипт. Тексты берутся из [TurnOpsPresenter], поэтому
 * тест на них не завязан: он проверяет флаги и идентификаторы.
 */
class TurnOpsRunnerTest {

    // ── undo / retry ──

    @Test
    fun `undo success charges the current session for reload`() {
        val outcome = TurnOpsRunner.fromTurnOp(
            TurnOpKind.UNDO,
            SessionTurnOpOutcome.Success(SessionTurnOpResult(ok = true, removedCount = 2)),
        )

        assertTrue(outcome.ok)
        assertEquals(TurnOpKind.UNDO, outcome.kind)
        assertEquals(2, outcome.removedCount)
        assertNull("undo не создаёт диалог", outcome.newSessionId)
    }

    @Test
    fun `retry exposes no removed count and stays in the current session`() {
        val outcome = TurnOpsRunner.fromTurnOp(
            TurnOpKind.RETRY,
            SessionTurnOpOutcome.Success(SessionTurnOpResult(ok = true)),
        )

        assertTrue(outcome.ok)
        assertEquals(0, outcome.removedCount)
        assertNull(outcome.newSessionId)
    }

    @Test
    fun `in-band refusal is shown verbatim and marked not ok`() {
        // Сервер отвечает 200 с телом {"error": "Nothing to undo."} — это отказ, не сбой сети.
        val outcome = TurnOpsRunner.fromTurnOp(
            TurnOpKind.UNDO,
            SessionTurnOpOutcome.Failure("Nothing to undo."),
        )

        assertFalse(outcome.ok)
        assertEquals("Nothing to undo.", outcome.message)
        assertNull(outcome.newSessionId)
    }

    @Test
    fun `blank refusal text falls back to a readable message instead of empty toast`() {
        val outcome = TurnOpsRunner.fromTurnOp(TurnOpKind.RETRY, SessionTurnOpOutcome.Failure("   "))

        assertFalse(outcome.ok)
        assertTrue("пустой отказ не должен давать пустой Toast", outcome.message.isNotBlank())
    }

    // ── truncate / clear / duplicate ──

    @Test
    fun `truncate success keeps the user in the same session with a reload`() {
        val outcome = TurnOpsRunner.fromMutation(
            TurnOpKind.TRUNCATE,
            "sess-1",
            SessionMutationOutcome.Success(SessionItem(id = "sess-1")),
        )

        assertTrue(outcome.ok)
        assertEquals("sess-1", outcome.reloadSessionId)
        assertNull("обрезка не создаёт диалог", outcome.newSessionId)
    }

    @Test
    fun `truncate with a missing server row still reloads the known session`() {
        // Сервер может ответить `ok` без строки сессии — цель перезагрузки известна заранее.
        val outcome = TurnOpsRunner.fromMutation(
            TurnOpKind.CLEAR,
            "sess-7",
            SessionMutationOutcome.Success(session = null),
        )

        assertTrue(outcome.ok)
        assertEquals("sess-7", outcome.reloadSessionId)
    }

    @Test
    fun `duplicate success sends the screen to the new session and not to a reload`() {
        val outcome = TurnOpsRunner.fromMutation(
            TurnOpKind.DUPLICATE,
            "sess-1",
            SessionMutationOutcome.Success(SessionItem(id = "sess-2")),
        )

        assertTrue(outcome.ok)
        assertEquals("sess-2", outcome.newSessionId)
        assertNull("переключение на копию отменяет перезагрузку исходной", outcome.reloadSessionId)
    }

    @Test
    fun `duplicate without a row in the response does not strand the screen`() {
        // Строки нет — переключаться некуда; экран не должен получить ни id, ни reload.
        val outcome = TurnOpsRunner.fromMutation(
            TurnOpKind.DUPLICATE,
            "sess-1",
            SessionMutationOutcome.Success(session = null),
        )

        assertTrue(outcome.ok)
        assertNull(outcome.newSessionId)
        assertNull(outcome.reloadSessionId)
    }

    @Test
    fun `mutation failure carries the server text and no navigation`() {
        val outcome = TurnOpsRunner.fromMutation(
            TurnOpKind.CLEAR,
            "sess-1",
            SessionMutationOutcome.Failure("Session is read-only"),
        )

        assertFalse(outcome.ok)
        assertEquals("Session is read-only", outcome.message)
        assertNull(outcome.newSessionId)
        assertNull(outcome.reloadSessionId)
    }

    // ── branch ──

    @Test
    fun `branch success opens the fork and carries its title`() {
        val outcome = TurnOpsRunner.fromBranch(
            SessionBranchOutcome.Success(
                SessionBranchResult(sessionId = "fork-1", title = "Ветка от хода", parentSessionId = "sess-1"),
            ),
        )

        assertTrue(outcome.ok)
        assertEquals("fork-1", outcome.newSessionId)
        assertEquals("Ветка от хода", outcome.newTitle)
    }

    @Test
    fun `branch without a title does not overwrite the header with an empty string`() {
        val outcome = TurnOpsRunner.fromBranch(
            SessionBranchOutcome.Success(SessionBranchResult(sessionId = "fork-1", title = "   ")),
        )

        assertNull("пробельное название — это отсутствие названия", outcome.newTitle)
    }

    @Test
    fun `branch failure stays in the current session`() {
        val outcome = TurnOpsRunner.fromBranch(SessionBranchOutcome.Failure("Subagent sessions are view-only"))

        assertFalse(outcome.ok)
        assertNull(outcome.newSessionId)
    }

    // ── title regeneration ──

    @Test
    fun `title regeneration updates the header without reloading the transcript`() {
        val outcome = TurnOpsRunner.fromTitle(
            SessionTitleRegenerateOutcome.Success(SessionTitleRegenerateResult(title = "Новое имя")),
        )

        assertTrue(outcome.ok)
        assertEquals("Новое имя", outcome.newTitle)
        assertNull("текст диалога не менялся", outcome.reloadSessionId)
        assertNull(outcome.newSessionId)
    }

    @Test
    fun `title regeneration failure keeps the old title`() {
        val outcome = TurnOpsRunner.fromTitle(
            SessionTitleRegenerateOutcome.Failure("Не удалось улучшить название"),
        )

        assertFalse(outcome.ok)
        assertNull(outcome.newTitle)
    }

    // ── политика доступности ──

    @Test
    fun `turn ops are hidden entirely in a read-only or subagent session`() {
        for (subagent in listOf(false, true)) {
            assertEquals(
                "read-only показ не предлагает серверных операций",
                emptyList<TurnOpKind>(),
                TurnOpsPolicy.messageOps(1, 4, "assistant", readOnly = true, subagent = subagent),
            )
        }
        assertEquals(
            emptyList<TurnOpKind>(),
            TurnOpsPolicy.messageOps(1, 4, "assistant", readOnly = false, subagent = true),
        )
    }

    @Test
    fun `first message offers no rollback because nothing would survive it`() {
        val ops = TurnOpsPolicy.messageOps(0, 4, "user", readOnly = false, subagent = false)

        assertFalse("откат до нуля — это clear, а не операция над ходом", TurnOpKind.TRUNCATE in ops)
        assertFalse(TurnOpKind.BRANCH in ops)
    }

    @Test
    fun `retry is offered only on the last assistant message`() {
        val onLast = TurnOpsPolicy.messageOps(3, 4, "assistant", readOnly = false, subagent = false)
        val onEarlier = TurnOpsPolicy.messageOps(1, 4, "assistant", readOnly = false, subagent = false)

        assertTrue(TurnOpKind.RETRY in onLast)
        assertFalse(TurnOpKind.RETRY in onEarlier)
    }
}
