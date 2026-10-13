package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.AutonomyToggleResult
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import java.util.concurrent.Executor

/**
 * Duplicate-guard регрессии диалогов внимания [InteractiveRequestController] — CLARIFY-сторона.
 *
 * Основной InteractiveRequestControllerTest фиксирует дедуп только для approval
 * (`duplicateApprovalEventWithSameIdDoesNotRebuildDialog`), а контракт
 * [MidTurnRecoveryController] («дубликаты при reattach исключены … дедупликацией диалогов
 * по id в InteractiveRequestController») опирается на ОБА диалога: после reattach с
 * `Last-Event-ID` сервер может реплеить кадр `clarify` уже показанного запроса. Этот файл
 * (новый, выделенный) докрывает clarify-паритет и смежные границы guard'а:
 *
 * - повторный кадр с тем же clarify_id НЕ пересобирает открытый диалог;
 * - другой clarify_id замещает открытый диалог (ответ уходит по новому payload);
 * - кадры БЕЗ id не дедуплицируются (guard требует id) — последний замещает предыдущий;
 * - после dismissAll (Done/Error/stop/смена сессии) реплей всё ещё живого запроса
 *   показывается снова: очистка не блэклистит id, иначе pending-запрос терялся бы навсегда.
 */
class InteractiveClarifyDedupRegressionTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private val inlineUi = UiDispatcher { it() }

    private class FakeView : InteractiveRequestView {
        val shownApprovals = mutableListOf<ApprovalPayload>()
        val shownClarifies = mutableListOf<ClarifyPayload>()
        val clarifyResponding = mutableListOf<Boolean>()
        var approvalDismissals = 0
        var clarifyDismissals = 0
        val errors = mutableListOf<String>()
        val notices = mutableListOf<String>()

        override fun showApproval(payload: ApprovalPayload) { shownApprovals += payload }
        override fun showClarify(payload: ClarifyPayload) { shownClarifies += payload }
        override fun setApprovalResponding(responding: Boolean) = Unit
        override fun setClarifyResponding(responding: Boolean) { clarifyResponding += responding }
        override fun dismissApproval() { approvalDismissals++ }
        override fun dismissClarify() { clarifyDismissals++ }
        override fun showError(message: String) { errors += message }
        override fun showNotice(message: String) { notices += message }
    }

    private class FakeGateway : InteractiveRequestsGateway {
        var approvalResult: InteractiveRespondResult = InteractiveRespondResult.Accepted()
        var clarifyResult: InteractiveRespondResult = InteractiveRespondResult.Accepted()
        var nextApproval: PendingApprovalLookup = PendingApprovalLookup.Empty
        var nextClarify: PendingClarifyLookup = PendingClarifyLookup.Empty
        var autonomyResult: AutonomyToggleResult = AutonomyToggleResult.Applied(true)
        val approvalCalls = mutableListOf<Triple<String, ApprovalPayload, ApprovalChoice>>()
        val clarifyCalls = mutableListOf<Triple<String, ClarifyPayload, String>>()
        val autonomyCalls = mutableListOf<Pair<String, Boolean>>()

        override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, grantAutonomy: Boolean): InteractiveRespondResult {
            approvalCalls += Triple(sessionId, payload, choice)
            return approvalResult
        }

        override fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult {
            clarifyCalls += Triple(sessionId, payload, response)
            return clarifyResult
        }

        override fun fetchPendingApproval(sessionId: String): PendingApprovalLookup = nextApproval
        override fun fetchPendingClarify(sessionId: String): PendingClarifyLookup = nextClarify

        override fun setSessionAutonomy(sessionId: String, enabled: Boolean): AutonomyToggleResult {
            autonomyCalls += sessionId to enabled
            return autonomyResult
        }
    }

    private fun clarifyEvent(id: String?, question: String = "Какой профиль?", vararg choices: String) =
        ChatEvent.ClarifyRequired(ClarifyPayload(clarifyId = id, question = question, choices = choices.toList()))

    private fun controller(view: FakeView, gateway: FakeGateway = FakeGateway(), executor: QueueExecutor = QueueExecutor()) =
        InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)

    @Test
    fun duplicateClarifyEventWithSameIdDoesNotRebuildDialog() {
        val view = FakeView()
        val controller = controller(view)

        controller.onEvent(clarifyEvent("cl-1"))
        controller.onEvent(clarifyEvent("cl-1")) // SSE re-push / replay после reattach

        assertEquals(1, view.shownClarifies.size)
        // Пересборка диалога сбросила бы responding-флаг — её не было.
        assertEquals(listOf(false), view.clarifyResponding)
    }

    @Test
    fun differentClarifyIdSupersedesOpenDialog() {
        val view = FakeView()
        val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = controller(view, gateway, executor)

        controller.onEvent(clarifyEvent("cl-1", "Старый вопрос?"))
        controller.onEvent(clarifyEvent("cl-2", "Новый вопрос?", "a", "b"))

        assertEquals(2, view.shownClarifies.size)
        controller.onClarifyResponse("a")
        executor.runAll()

        // Ответ уходит по ПОСЛЕДНЕМУ payload: старый clarify_id не всплывает в запросе.
        val call = gateway.clarifyCalls.single()
        assertEquals("cl-2", call.second.clarifyId)
        assertEquals("Новый вопрос?", call.second.question)
    }

    @Test
    fun clarifyEventsWithoutIdAreNotDeduped() {
        val view = FakeView()
        val controller = controller(view)

        controller.onEvent(clarifyEvent(null, "Первый"))
        controller.onEvent(clarifyEvent(null, "Второй"))

        // Guard требует id: без него повтор считается новым запросом и замещает диалог.
        assertEquals(2, view.shownClarifies.size)
        assertEquals("Второй", view.shownClarifies.last().question)
    }

    @Test
    fun pendingClarifyIsShownAgainAfterDismissAllReplay() {
        val view = FakeView()
        val controller = controller(view)

        controller.onEvent(clarifyEvent("cl-9"))
        controller.dismissAll() // stop / смена сессии / Done
        controller.onEvent(clarifyEvent("cl-9")) // сервер всё ещё ждёт ответ — реплей

        // Очистка не блэклистит id: живой pending-запрос после reattach обязан снова дойти
        // до пользователя, иначе ход зависнет навсегда.
        assertEquals(2, view.shownClarifies.size)
        assertEquals(1, view.clarifyDismissals)
    }

    @Test
    fun duplicateApprovalDuringClarifyDoesNotTouchClarifyDialog() {
        val view = FakeView()
        val controller = controller(view)

        controller.onEvent(clarifyEvent("cl-1"))
        controller.onEvent(
            ChatEvent.ApprovalRequired(
                ApprovalPayload(approvalId = "appr-1", command = "git push", description = "")
            )
        )
        controller.onEvent(clarifyEvent("cl-1")) // реплей clarify под открытым approval

        assertEquals(1, view.shownClarifies.size)
        assertEquals(1, view.shownApprovals.size)
    }
}
