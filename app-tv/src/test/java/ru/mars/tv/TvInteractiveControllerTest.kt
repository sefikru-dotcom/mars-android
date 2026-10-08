package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import java.util.concurrent.Executor

/**
 * Unit tests for [TvInteractiveController] card semantics (no Android framework: surface,
 * gateway, executor and ui-dispatcher are clean-seam fakes). Covers respond routing, queue
 * refresh, stale/terminal handling, error retries, cancel-without-respond, empty-clarify-choices
 * flow, Done/Error dismissal, in-flight identity guards, generation invalidation and destroy.
 */
class TvInteractiveControllerTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    /** Queued UI-dispatcher fake: result handling only runs when the test drains it (thread-confinement parity). */
    private class QueueUi : TvUiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class FakeSurface : TvInteractiveSurface {
        val shownApprovals = mutableListOf<ApprovalPayload>()
        val shownClarifies = mutableListOf<ClarifyPayload>()
        val approvalResponding = mutableListOf<Boolean>()
        val clarifyResponding = mutableListOf<Boolean>()
        var approvalDismissals = 0
        var clarifyDismissals = 0
        val errors = mutableListOf<String>()
        val notices = mutableListOf<String>()

        override fun showApproval(payload: ApprovalPayload) { shownApprovals += payload }
        override fun showClarify(payload: ClarifyPayload) { shownClarifies += payload }
        override fun setApprovalResponding(responding: Boolean) { approvalResponding += responding }
        override fun setClarifyResponding(responding: Boolean) { clarifyResponding += responding }
        override fun dismissApproval() { approvalDismissals++ }
        override fun dismissClarify() { clarifyDismissals++ }
        override fun showCardError(message: String) { errors += message }
        override fun showCardNotice(message: String) { notices += message }
    }

    private class FakeGateway : TvInteractiveGateway {
        var approvalResult: InteractiveRespondResult = InteractiveRespondResult.Accepted()
        var clarifyResult: InteractiveRespondResult = InteractiveRespondResult.Accepted()
        var nextApproval: PendingApprovalLookup = PendingApprovalLookup.Empty
        var nextClarify: PendingClarifyLookup = PendingClarifyLookup.Empty
        val approvalCalls = mutableListOf<ApprovalCall>()
        val clarifyCalls = mutableListOf<Triple<String, ClarifyPayload, String>>()
        var pendingApprovalFetches = 0
        var pendingClarifyFetches = 0

        data class ApprovalCall(val sessionId: String, val payload: ApprovalPayload, val choice: ApprovalChoice, val yolo: Boolean)

        override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, yolo: Boolean): InteractiveRespondResult {
            approvalCalls += ApprovalCall(sessionId, payload, choice, yolo)
            return approvalResult
        }
        override fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult {
            clarifyCalls += Triple(sessionId, payload, response)
            return clarifyResult
        }
        override fun fetchPendingApproval(sessionId: String): PendingApprovalLookup {
            pendingApprovalFetches++
            return nextApproval
        }
        override fun fetchPendingClarify(sessionId: String): PendingClarifyLookup {
            pendingClarifyFetches++
            return nextClarify
        }
    }

    private fun approvalEvent(
        id: String,
        pendingCount: Int = 1,
        allowSession: Boolean = true,
        allowPermanent: Boolean = false,
    ) = ChatEvent.ApprovalRequired(
        ApprovalPayload(
            approvalId = id, command = "rm -rf /tmp/x", description = "",
            allowSession = allowSession, allowPermanent = allowPermanent, pendingCount = pendingCount,
        )
    )

    private fun clarifyEvent(id: String, vararg choices: String, pendingCount: Int = 1, sessionId: String? = null) =
        ChatEvent.ClarifyRequired(
            ClarifyPayload(clarifyId = id, question = "Какой профиль?", choices = choices.toList(), pendingCount = pendingCount, sessionId = sessionId)
        )

    private class Harness(sessionId: String? = "sess-1") {
        val surface = FakeSurface()
        val gateway = FakeGateway()
        val executor = QueueExecutor()
        val ui = QueueUi()
        var session = sessionId
        val echoed = mutableListOf<String>()
        val controller = TvInteractiveController(surface, gateway, { session }, executor, ui).apply {
            onClarifyAnswered = { echoed += it }
        }

        /** Drains executor and UI queues until quiescent (respond -> accepted -> queue refetch
         *  enqueues a second executor round, so a single pass is not enough). */
        fun drain() {
            while (executor.queue.isNotEmpty() || ui.queue.isNotEmpty()) {
                executor.runAll()
                ui.runAll()
            }
        }

        /** Full activation round-trip: choice -> executor -> ui dispatch. */
        fun activateChoice(choice: ApprovalChoice, yolo: Boolean = false) {
            controller.onApprovalChoice(choice, yolo)
            drain()
        }

        fun respondClarify(text: String) {
            controller.onClarifyResponse(text)
            drain()
        }
    }

    // --- approval -------------------------------------------------------------------------------

    @Test
    fun approvalEventShowsCardAndDedupsSameId() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onEvent(approvalEvent("appr-1")) // SSE re-push of the same queued entry
        assertEquals(1, h.surface.shownApprovals.size)
        assertTrue(h.controller.hasOpenPrompt)
        // A newer queued head replaces the stale card.
        h.controller.onEvent(approvalEvent("appr-2"))
        assertEquals(2, h.surface.shownApprovals.size)
        assertEquals("appr-2", h.surface.shownApprovals.last().approvalId)
    }

    @Test
    fun approvalChoiceRoutesRespondAndAcceptDismissesCard() {
        val h = Harness()
        val event = approvalEvent("appr-9")
        h.controller.onEvent(event)
        h.activateChoice(ApprovalChoice.SESSION)
        assertEquals(1, h.gateway.approvalCalls.size)
        val call = h.gateway.approvalCalls.single()
        assertEquals("sess-1", call.sessionId)
        assertSame(event.payload, call.payload)
        assertEquals(ApprovalChoice.SESSION, call.choice)
        assertFalse(call.yolo)
        assertEquals(1, h.surface.approvalDismissals)
        assertEquals(listOf(false, true), h.surface.approvalResponding)
        assertTrue(h.surface.errors.isEmpty())
        assertFalse(h.controller.hasOpenPrompt)
    }

    @Test
    fun yoloGrantSubmitsOnceWithYoloFlag() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.activateChoice(ApprovalChoice.ONCE, yolo = true)
        assertTrue(h.gateway.approvalCalls.single().yolo)
    }

    @Test
    fun acceptedMultiEntryQueueRefetchesNextPendingHead() {
        val h = Harness()
        val next = ApprovalPayload(approvalId = "appr-10", command = "ls", description = "")
        h.gateway.nextApproval = PendingApprovalLookup.Found(next)
        h.controller.onEvent(approvalEvent("appr-9", pendingCount = 3))
        h.activateChoice(ApprovalChoice.ONCE)
        assertEquals(1, h.gateway.pendingApprovalFetches)
        assertEquals(listOf("appr-9", "appr-10"), h.surface.shownApprovals.map { it.approvalId })
        assertTrue(h.controller.hasOpenPrompt)
    }

    @Test
    fun staleClearedAlsoRefetchesQueueButEmptyLookupStaysDismissed() {
        val h = Harness()
        h.gateway.approvalResult = InteractiveRespondResult.Accepted(staleCleared = true)
        h.controller.onEvent(approvalEvent("appr-1"))
        h.activateChoice(ApprovalChoice.DENY)
        assertEquals(1, h.gateway.pendingApprovalFetches)
        assertEquals(1, h.surface.approvalDismissals)
        assertEquals(1, h.surface.shownApprovals.size) // nothing new to show
        assertFalse(h.controller.hasOpenPrompt)
    }

    @Test
    fun rejectedKeepsCardOpenWithInlineErrorForRetry() {
        val h = Harness()
        h.gateway.approvalResult = InteractiveRespondResult.Rejected("уже истекло", 409)
        h.controller.onEvent(approvalEvent("appr-1"))
        h.activateChoice(ApprovalChoice.ONCE)
        assertEquals(0, h.surface.approvalDismissals)
        assertEquals(listOf("уже истекло"), h.surface.errors)
        assertEquals(listOf(false, true, false), h.surface.approvalResponding)
        assertTrue(h.controller.hasOpenPrompt)
        // Retry after re-enabling goes through.
        h.gateway.approvalResult = InteractiveRespondResult.Accepted()
        h.activateChoice(ApprovalChoice.ONCE)
        assertEquals(2, h.gateway.approvalCalls.size)
        assertEquals(1, h.surface.approvalDismissals)
    }

    @Test
    fun networkErrorSurfacesGenericMessageAndKeepsCard() {
        val h = Harness()
        h.gateway.approvalResult = InteractiveRespondResult.NetworkError("timeout")
        h.controller.onEvent(approvalEvent("appr-1"))
        h.activateChoice(ApprovalChoice.ONCE)
        assertEquals(listOf(TvInteractiveController.NETWORK_FAILURE), h.surface.errors)
        assertEquals(0, h.surface.approvalDismissals)
    }

    @Test
    fun cancelDismissesWithoutRespondingSilenceIsNotConsent() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onApprovalCancelled()
        assertEquals(1, h.surface.approvalDismissals)
        assertTrue(h.gateway.approvalCalls.isEmpty())
        assertFalse(h.controller.hasOpenPrompt)
        // A duplicate cancel after the card is gone must not re-dismiss.
        h.controller.onApprovalCancelled()
        assertEquals(1, h.surface.approvalDismissals)
    }

    @Test
    fun missingSessionShowsErrorAndSendsNothing() {
        val h = Harness(sessionId = null)
        h.controller.onEvent(approvalEvent("appr-1"))
        h.activateChoice(ApprovalChoice.ONCE)
        assertTrue(h.gateway.approvalCalls.isEmpty())
        assertEquals(listOf(TvInteractiveController.NO_SESSION), h.surface.errors)
        assertTrue("card stays open for when the session binds", h.controller.hasOpenPrompt)
    }

    @Test
    fun doubleActivationWhileRespondingFiresSingleCall() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onApprovalChoice(ApprovalChoice.ONCE) // locks, queues executor work
        h.controller.onApprovalChoice(ApprovalChoice.DENY) // ignored while responding
        h.executor.runAll()
        h.ui.runAll()
        assertEquals(1, h.gateway.approvalCalls.size)
        assertEquals(ApprovalChoice.ONCE, h.gateway.approvalCalls.single().choice)
    }

    @Test
    fun doneAndErrorEventsDismissAllCards() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onEvent(clarifyEvent("cl-1"))
        h.controller.onEvent(ChatEvent.Done)
        assertEquals(1, h.surface.approvalDismissals)
        assertEquals(1, h.surface.clarifyDismissals)
        assertFalse(h.controller.hasOpenPrompt)

        h.controller.onEvent(clarifyEvent("cl-2"))
        h.controller.onEvent(ChatEvent.Error("boom"))
        assertEquals(2, h.surface.clarifyDismissals)
        assertFalse(h.controller.hasOpenPrompt)
    }

    @Test
    fun inFlightResultAfterDismissAllIsDroppedByGenerationGuard() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onApprovalChoice(ApprovalChoice.ONCE) // executor work queued, not run
        h.controller.dismissAll() // stream cancelled / «Новый чат»
        h.executor.runAll()
        h.ui.runAll()
        assertEquals(1, h.surface.approvalDismissals) // only the dismissAll one
        assertTrue(h.gateway.approvalCalls.isEmpty()) // pre-flight generation check skipped the call
    }

    @Test
    fun supersededCardResultIsDroppedByIdentityGuard() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.onApprovalChoice(ApprovalChoice.ONCE) // in flight for appr-1
        h.controller.onEvent(approvalEvent("appr-2")) // newer SSE head replaces it
        h.executor.runAll()
        h.ui.runAll()
        assertEquals("stale result must not dismiss the new card", 0, h.surface.approvalDismissals)
        assertTrue(h.controller.hasOpenPrompt)
    }

    @Test
    fun destroyIgnoresEventsCallbacksAndLateResults() {
        val h = Harness()
        h.controller.onEvent(approvalEvent("appr-1"))
        h.controller.destroy()
        assertEquals(1, h.surface.approvalDismissals)
        h.controller.onEvent(approvalEvent("appr-2"))
        h.controller.onApprovalChoice(ApprovalChoice.ONCE)
        h.controller.onClarifyResponse("x")
        h.executor.runAll()
        h.ui.runAll()
        assertEquals(1, h.surface.shownApprovals.size)
        assertTrue(h.gateway.approvalCalls.isEmpty())
        assertTrue(h.gateway.clarifyCalls.isEmpty())
    }

    // --- clarify --------------------------------------------------------------------------------

    @Test
    fun clarifyPresetResponseRoutesAcceptEchoesAndDismisses() {
        val h = Harness()
        h.controller.onEvent(clarifyEvent("cl-1", "да", "нет"))
        h.respondClarify("да")
        assertEquals(1, h.gateway.clarifyCalls.size)
        assertEquals("sess-1", h.gateway.clarifyCalls.single().first)
        assertEquals("да", h.gateway.clarifyCalls.single().third)
        assertEquals(1, h.surface.clarifyDismissals)
        assertEquals(listOf("да"), h.echoed)
        assertFalse(h.controller.hasOpenPrompt)
    }

    @Test
    fun clarifyUsesPayloadSessionIdWhenPresent() {
        val h = Harness(sessionId = "live-sess")
        h.controller.onEvent(clarifyEvent("cl-1", sessionId = "payload-sess"))
        h.respondClarify("ответ")
        assertEquals("payload-sess", h.gateway.clarifyCalls.single().first)
    }

    @Test
    fun clarifyBlankAnswerIsIgnoredCardStaysOpen() {
        val h = Harness()
        h.controller.onEvent(clarifyEvent("cl-1")) // empty choices → free text on the card
        h.respondClarify("   ")
        assertTrue(h.gateway.clarifyCalls.isEmpty())
        assertEquals(0, h.surface.clarifyDismissals)
        assertTrue(h.controller.hasOpenPrompt)
    }

    @Test
    fun clarifyAcceptedQueueRefetchesNextPending() {
        val h = Harness()
        val next = ClarifyPayload(clarifyId = "cl-10", question = "Ещё?")
        h.gateway.nextClarify = PendingClarifyLookup.Found(next)
        h.controller.onEvent(clarifyEvent("cl-9", pendingCount = 2))
        h.respondClarify("первый")
        assertEquals(1, h.gateway.pendingClarifyFetches)
        assertEquals(listOf("cl-9", "cl-10"), h.surface.shownClarifies.map { it.clarifyId })
    }

    @Test
    fun clarifyStaleDismissesWithNotice() {
        val h = Harness()
        h.gateway.clarifyResult = InteractiveRespondResult.Stale("запрос устарел")
        h.controller.onEvent(clarifyEvent("cl-1"))
        h.respondClarify("поздно")
        assertEquals(1, h.surface.clarifyDismissals)
        assertEquals(listOf("запрос устарел"), h.surface.notices)
        assertEquals("stale is terminal — no echo", emptyList<String>(), h.echoed)
    }

    @Test
    fun clarifyRejectedKeepsCardWithInlineError() {
        val h = Harness()
        h.gateway.clarifyResult = InteractiveRespondResult.Rejected("нет активного запроса", 404)
        h.controller.onEvent(clarifyEvent("cl-1"))
        h.respondClarify("профиль")
        assertEquals(0, h.surface.clarifyDismissals)
        assertEquals(listOf("нет активного запроса"), h.surface.errors)
        assertEquals(listOf(false, true, false), h.surface.clarifyResponding)
        assertTrue(h.controller.hasOpenPrompt)
    }

    @Test
    fun clarifyCancelDismissesWithoutResponding() {
        val h = Harness()
        h.controller.onEvent(clarifyEvent("cl-1"))
        h.controller.onClarifyCancelled()
        assertEquals(1, h.surface.clarifyDismissals)
        assertTrue(h.gateway.clarifyCalls.isEmpty())
        assertFalse(h.controller.hasOpenPrompt)
    }

    @Test
    fun inFlightClarifyResultAfterStreamTerminalIsDropped() {
        val h = Harness()
        h.controller.onEvent(clarifyEvent("cl-1"))
        h.controller.onClarifyResponse("ответ") // queued, not run
        h.controller.onEvent(ChatEvent.Done) // terminal dismiss bumps generation
        h.executor.runAll()
        h.ui.runAll()
        assertTrue(h.gateway.clarifyCalls.isEmpty())
        assertEquals("no echo for a dropped result", emptyList<String>(), h.echoed)
    }

    private fun assertSame(expected: Any?, actual: Any?) =
        assertTrue("expected the identical payload instance", expected === actual)
}
