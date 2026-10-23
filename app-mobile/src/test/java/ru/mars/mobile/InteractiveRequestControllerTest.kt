package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ApprovalStreamUpdate
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ClarifyStreamUpdate
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.AutonomyToggleResult
import java.util.concurrent.Executor

/**
 * Unit tests for [InteractiveRequestController] dialog semantics (no Android framework:
 * view/gateway/executor/ui-dispatcher are clean-seam fakes). Covers respond routing, queue
 * refresh, stale/terminal handling, error retries, cancellation-without-respond, in-flight
 * identity guards, UI-dispatch of result/state handling and lifecycle (destroy/session switch)
 * late-callback drop.
 */
class InteractiveRequestControllerTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    /** UI-thread stand-in: runs dispatched blocks inline (single-thread confinement semantics). */
    private val inlineUi = UiDispatcher { it() }

    /** Queued UI-dispatcher fake: proves WHICH blocks are UI-dispatched and lets tests order them. */
    private class QueueUiDispatcher : UiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class FakeView : InteractiveRequestView {
        val shownApprovals = mutableListOf<ApprovalPayload>()
        val shownClarifies = mutableListOf<ClarifyPayload>()
        val approvalResponding = mutableListOf<Boolean>()
        val clarifyResponding = mutableListOf<Boolean>()
        var approvalDismissals = 0
        var clarifyDismissals = 0
        val errors = mutableListOf<String>()
        val notices = mutableListOf<String>()
        /** Every retry offer, including the nulls that hide the action. */
        val approvalRetries = mutableListOf<ApprovalAttempt?>()
        val clarifyRetries = mutableListOf<String?>()

        override fun showApproval(payload: ApprovalPayload) { shownApprovals += payload }
        override fun showClarify(payload: ClarifyPayload) { shownClarifies += payload }
        override fun setApprovalResponding(responding: Boolean) { approvalResponding += responding }
        override fun setClarifyResponding(responding: Boolean) { clarifyResponding += responding }
        override fun setApprovalRetry(attempt: ApprovalAttempt?) { approvalRetries += attempt }
        override fun setClarifyRetry(answer: String?) { clarifyRetries += answer }
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
        val approvalCalls = mutableListOf<ApprovalCall>()
        val clarifyCalls = mutableListOf<Triple<String, ClarifyPayload, String>>()
        val autonomyCalls = mutableListOf<Pair<String, Boolean>>()
        var pendingApprovalFetches = 0
        var pendingClarifyFetches = 0

        data class ApprovalCall(val sessionId: String, val payload: ApprovalPayload, val choice: ApprovalChoice, val grantAutonomy: Boolean)

        override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, grantAutonomy: Boolean): InteractiveRespondResult {
            approvalCalls += ApprovalCall(sessionId, payload, choice, grantAutonomy)
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

        override fun setSessionAutonomy(sessionId: String, enabled: Boolean): AutonomyToggleResult {
            autonomyCalls += sessionId to enabled
            return autonomyResult
        }
    }

    private fun approvalEvent(id: String, pendingCount: Int = 1) = ChatEvent.ApprovalRequired(
        ApprovalPayload(approvalId = id, command = "rm -rf /tmp/x", description = "", pendingCount = pendingCount)
    )

    private fun clarifyEvent(id: String, vararg choices: String) = ChatEvent.ClarifyRequired(
        ClarifyPayload(clarifyId = id, question = "Какой профиль?", choices = choices.toList())
    )

    // --- approval -------------------------------------------------------------------------------

    @Test
    fun approvalEventShowsDialogAndRecordsPendingState() {
        val view = FakeView(); val state = ChatUiState()
        state.begin("привет")
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, state, QueueExecutor(), inlineUi)

        val event = approvalEvent("appr-9")
        // Activity wiring: uiState.onEvent(event) runs alongside controller.onEvent(event).
        state.onEvent(event)
        controller.onEvent(event)

        assertEquals(listOf(event.payload), view.shownApprovals)
        assertEquals(event.payload, state.pendingApproval)
    }

    @Test
    fun duplicateApprovalEventWithSameIdDoesNotRebuildDialog() {
        val view = FakeView()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, null, QueueExecutor(), inlineUi)

        controller.onEvent(approvalEvent("appr-9"))
        controller.onEvent(approvalEvent("appr-9"))

        assertEquals(1, view.shownApprovals.size)
    }

    @Test
    fun approvalChoicePostsResponseAndDismissesOnAccept() {
        val view = FakeView(); val gateway = FakeGateway(); val state = ChatUiState()
        state.begin("x")
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, inlineUi)
        val event = approvalEvent("appr-9")
        state.onEvent(event)
        controller.onEvent(event)
        assertEquals(event.payload, state.pendingApproval)

        controller.onApprovalChoice(ApprovalChoice.ALWAYS)
        assertEquals(1, executor.queue.size)
        executor.runAll()

        assertEquals(
            FakeGateway.ApprovalCall("sess-1", view.shownApprovals[0], ApprovalChoice.ALWAYS, grantAutonomy = false),
            gateway.approvalCalls.single()
        )
        assertEquals(1, view.approvalDismissals)
        assertNull(state.pendingApproval)
        assertTrue(view.errors.isEmpty())
    }

    @Test
    fun approvalAcceptedWithStaleClearedFetchesNextQueuedHead() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Accepted(staleCleared = true)
        val nextPayload = ApprovalPayload(approvalId = "appr-10", command = "next", description = "")
        gateway.nextApproval = PendingApprovalLookup.Found(nextPayload)
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        assertEquals(1, gateway.pendingApprovalFetches)
        assertEquals(nextPayload, view.shownApprovals.last())
        // Queue indicator in the dialog title comes from pendingCount; controller only re-shows.
        assertEquals(2, view.shownApprovals.size)
    }

    @Test
    fun approvalRejectedKeepsDialogOpenAndAllowsRetry() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Rejected("Invalid choice", 400)
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        assertEquals(listOf("Invalid choice"), view.errors)
        assertEquals(0, view.approvalDismissals)
        assertEquals(false, view.approvalResponding.last())

        gateway.approvalResult = InteractiveRespondResult.Accepted()
        controller.onApprovalChoice(ApprovalChoice.DENY)
        executor.runAll()
        assertEquals(2, gateway.approvalCalls.size)
        assertEquals(1, view.approvalDismissals)
    }

    @Test
    fun approvalStaleResultIsTerminalWithNotice() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Stale("Approval expired")
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        assertEquals(1, view.approvalDismissals)
        assertEquals(listOf("Approval expired"), view.notices)
        assertTrue(view.errors.isEmpty())
    }

    @Test
    fun approvalNetworkErrorShowsGenericMessageWithoutDetails() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.NetworkError("connect timed out to https://host/api")
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        assertEquals(listOf(InteractiveRequestController.NETWORK_FAILURE), view.errors)
        assertEquals(0, view.approvalDismissals)
    }

    @Test
    fun approvalDismissalNeverResponds() {
        val view = FakeView(); val gateway = FakeGateway()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, QueueExecutor(), inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalDismissed()

        assertTrue(gateway.approvalCalls.isEmpty())
        assertEquals(1, view.approvalDismissals)
        assertEquals(false, view.approvalResponding.last())
    }

    @Test
    fun approvalChoiceWithoutSessionIsRejectedLocally() {
        val view = FakeView(); val gateway = FakeGateway()
        val controller = InteractiveRequestController(view, gateway, { null }, null, QueueExecutor(), inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)

        assertTrue(gateway.approvalCalls.isEmpty())
        assertEquals(listOf(InteractiveRequestController.NO_SESSION), view.errors)
    }

    @Test
    fun approvalDoubleSubmitWhileInFlightIsIgnored() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        controller.onApprovalChoice(ApprovalChoice.DENY)
        executor.runAll()

        assertEquals(1, gateway.approvalCalls.size)
        assertEquals(ApprovalChoice.ONCE, gateway.approvalCalls.single().choice)
    }

    @Test
    fun approvalResultAfterDismissalIsIgnored() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Rejected("too late", 400)
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        controller.onApprovalDismissed()
        executor.runAll()

        // Dismissed while in flight: the stale rejection must not surface an error toast.
        assertTrue(view.errors.isEmpty())
        assertEquals(1, view.approvalDismissals) // only the explicit dismissal
    }

    @Test
    fun supersedingApprovalEventReplacesDialogAndStaleResultIgnored() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        // New SSE approval arrives before the response lands.
        controller.onEvent(approvalEvent("appr-10"))
        gateway.approvalResult = InteractiveRespondResult.Accepted()
        executor.runAll()

        assertEquals(listOf("appr-9", "appr-10"), view.shownApprovals.map { it.approvalId })
        // The accepted result belonged to appr-9 whose dialog was already replaced by appr-10:
        // the identity guard must swallow it (no extra dismissal, appr-10 stays open).
        assertEquals(0, view.approvalDismissals)
    }

    // --- clarify --------------------------------------------------------------------------------

    @Test
    fun clarifyChoicePostsResponseAndEchoesAnswer() {
        val view = FakeView(); val gateway = FakeGateway(); val state = ChatUiState()
        state.begin("x")
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, inlineUi)
        var echoed: String? = null
        controller.onClarifyAnswered = { echoed = it }
        val event = clarifyEvent("cl-7", "default", "office")
        state.onEvent(event)
        controller.onEvent(event)
        assertEquals(event.payload, state.pendingClarify)

        controller.onClarifyResponse("default")
        executor.runAll()

        assertEquals(Triple("sess-1", view.shownClarifies[0], "default"), gateway.clarifyCalls.single())
        assertEquals(1, view.clarifyDismissals)
        assertEquals("default", echoed)
        assertNull(state.pendingClarify)
    }

    @Test
    fun clarifyPrefersPayloadSessionIdOverProvider() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "fallback-session" }, null, executor, inlineUi)
        val event = ChatEvent.ClarifyRequired(
            ClarifyPayload(clarifyId = "cl-1", question = "q", choices = listOf("a"), sessionId = "payload-session")
        )
        controller.onEvent(event)

        controller.onClarifyResponse("a")
        executor.runAll()

        assertEquals("payload-session", gateway.clarifyCalls.single().first)
    }

    @Test
    fun clarifyFreeTextIsTrimmedAndBlankIgnored() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(clarifyEvent("cl-7"))

        controller.onClarifyResponse("   ")
        executor.runAll()
        assertTrue(gateway.clarifyCalls.isEmpty())

        controller.onClarifyResponse("  свой текст  ")
        executor.runAll()
        assertEquals("свой текст", gateway.clarifyCalls.single().third)
    }

    @Test
    fun clarifyStaleIsTerminalWithoutEcho() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.clarifyResult = InteractiveRespondResult.Stale("Prompt expired")
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        var echoed = false
        controller.onClarifyAnswered = { echoed = true }
        controller.onEvent(clarifyEvent("cl-7", "default"))

        controller.onClarifyResponse("default")
        executor.runAll()

        assertEquals(1, view.clarifyDismissals)
        assertEquals(listOf("Prompt expired"), view.notices)
        assertTrue(view.errors.isEmpty())
        assertTrue(!echoed)
    }

    @Test
    fun clarifyRejectedKeepsDialogOpenForRetry() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.clarifyResult = InteractiveRespondResult.Rejected("session mismatch", 409)
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(clarifyEvent("cl-7", "default"))

        controller.onClarifyResponse("default")
        executor.runAll()

        assertEquals(listOf("session mismatch"), view.errors)
        assertEquals(0, view.clarifyDismissals)

        gateway.clarifyResult = InteractiveRespondResult.Accepted()
        controller.onClarifyResponse("default")
        executor.runAll()
        assertEquals(2, gateway.clarifyCalls.size)
        assertEquals(1, view.clarifyDismissals)
    }

    @Test
    fun clarifyDismissalNeverResponds() {
        val view = FakeView(); val gateway = FakeGateway()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, QueueExecutor(), inlineUi)
        controller.onEvent(clarifyEvent("cl-7", "default"))

        controller.onClarifyDismissed()

        assertTrue(gateway.clarifyCalls.isEmpty())
        assertEquals(1, view.clarifyDismissals)
    }

    // --- lifecycle ------------------------------------------------------------------------------

    @Test
    fun terminalStreamEventsDismissBothDialogsWithoutResponding() {
        val view = FakeView(); val gateway = FakeGateway()
        val state = ChatUiState()
        state.begin("x")
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, QueueExecutor(), inlineUi)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onEvent(clarifyEvent("cl-7", "a"))

        controller.onEvent(ChatEvent.Done)

        assertEquals(1, view.approvalDismissals)
        assertEquals(1, view.clarifyDismissals)
        assertTrue(gateway.approvalCalls.isEmpty())
        assertTrue(gateway.clarifyCalls.isEmpty())
        assertNull(state.pendingApproval)
        assertNull(state.pendingClarify)
    }

    @Test
    fun errorEventDismissesDialogs() {
        val view = FakeView(); val gateway = FakeGateway()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, QueueExecutor(), inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onEvent(ChatEvent.Error("stream failed"))

        assertEquals(1, view.approvalDismissals)
        assertTrue(gateway.approvalCalls.isEmpty())
    }

    @Test
    fun nonInteractiveEventsAreIgnored() {
        val view = FakeView()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, null, QueueExecutor(), inlineUi)

        controller.onEvent(ChatEvent.Chunk("текст"))
        controller.onEvent(ChatEvent.Thought("думаю"))

        assertTrue(view.shownApprovals.isEmpty())
        assertTrue(view.shownClarifies.isEmpty())
        assertEquals(0, view.approvalDismissals)
    }

    // --- threading: UI dispatch of results and state ----------------------------------------------

    @Test
    fun respondResultHandlingIsDispatchedToUiNotAppliedOnExecutor() {
        val view = FakeView(); val gateway = FakeGateway(); val state = ChatUiState()
        state.begin("x")
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val event = approvalEvent("appr-9")
        state.onEvent(event)
        controller.onEvent(event)

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll() // HTTP completes on the "background" executor…

        // …but nothing may be applied there: ChatUiState and dialog cleanup wait for UI dispatch.
        assertEquals(1, gateway.approvalCalls.size)
        assertEquals(event.payload, state.pendingApproval)
        assertEquals(0, view.approvalDismissals)
        assertEquals(1, ui.queue.size)

        ui.runAll() // result handling on the UI thread
        assertNull(state.pendingApproval)
        assertEquals(1, view.approvalDismissals)
    }

    @Test
    fun identityGuardIsEvaluatedOnUiDispatchAgainstLatestState() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, ui)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll() // accepted result for appr-9 is in flight to the UI thread
        // A newer SSE event supersedes the dialog BEFORE the UI block runs: the guard must be
        // evaluated against the state as of the dispatcher thread, not the executor thread.
        controller.onEvent(approvalEvent("appr-10"))
        ui.runAll()

        assertEquals(listOf("appr-9", "appr-10"), view.shownApprovals.map { it.approvalId })
        assertEquals(0, view.approvalDismissals) // stale acceptance dropped, appr-10 stays open
    }

    @Test
    fun clarifyEchoIsDeliveredOnUiDispatch() {
        val view = FakeView(); val gateway = FakeGateway(); val state = ChatUiState()
        state.begin("x")
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        var echoed: String? = null
        controller.onClarifyAnswered = { echoed = it }
        val event = clarifyEvent("cl-7", "default")
        state.onEvent(event)
        controller.onEvent(event)

        controller.onClarifyResponse("default")
        executor.runAll()
        assertNull(echoed) // never echoed from the executor thread
        assertEquals(event.payload, state.pendingClarify)

        ui.runAll()
        assertEquals("default", echoed) // accepted clarify echoes on the UI dispatch (main)
        assertNull(state.pendingClarify)
        assertEquals(1, view.clarifyDismissals)
    }

    // --- lifecycle: late callbacks after destroy / cancel / session switch --------------------------

    @Test
    fun destroyIgnoresLateRespondResult() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Rejected("too late", 400)
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, ui)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        controller.destroy() // host onDestroy while the tap is still queued
        executor.runAll()
        ui.runAll()

        assertTrue(gateway.approvalCalls.isEmpty()) // invalidated before the HTTP call
        assertTrue(view.errors.isEmpty())           // no toast after destroy
        assertEquals(1, view.approvalDismissals)    // only the destroy cleanup
    }

    @Test
    fun destroyIgnoresEventsAndListenerCallbacks() {
        val view = FakeView(); val gateway = FakeGateway()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, QueueExecutor(), inlineUi)
        controller.destroy()

        controller.onEvent(approvalEvent("appr-9"))
        controller.onEvent(clarifyEvent("cl-7", "a"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        controller.onClarifyResponse("a")
        controller.onApprovalDismissed()
        controller.onClarifyDismissed()
        controller.destroy() // idempotent

        assertTrue(view.shownApprovals.isEmpty())
        assertTrue(view.shownClarifies.isEmpty())
        assertTrue(gateway.approvalCalls.isEmpty())
        assertTrue(gateway.clarifyCalls.isEmpty())
    }

    @Test
    fun queuedPendingFetchAfterDestroyNeverShowsDialog() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Accepted(staleCleared = true)
        gateway.nextApproval = PendingApprovalLookup.Found(
            ApprovalPayload(approvalId = "appr-10", command = "queued", description = "")
        )
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, ui)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll() // respond HTTP done, result awaits UI
        ui.runAll()       // accepted applied → next-head fetch enqueued
        executor.runAll() // fetch HTTP done, result awaits UI
        controller.destroy() // host dies while the queue head is in flight
        ui.runAll()

        assertEquals(1, gateway.pendingApprovalFetches)
        assertEquals(listOf("appr-9"), view.shownApprovals.map { it.approvalId }) // stale head dropped
    }

    @Test
    fun queuedPendingFetchAfterSessionSwitchNeverShowsDialog() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Accepted(staleCleared = true)
        gateway.nextApproval = PendingApprovalLookup.Found(
            ApprovalPayload(approvalId = "appr-10", command = "queued", description = "")
        )
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, ui)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()
        ui.runAll()       // accepted → fetch for the OLD session enqueued
        executor.runAll() // fetch HTTP done, result awaits UI
        controller.dismissAll() // user started a new session / bound another one
        ui.runAll()

        assertEquals(listOf("appr-9"), view.shownApprovals.map { it.approvalId })
    }

    @Test
    fun cancelWhileRespondQueuedDropsSendAndLateResult() {
        val view = FakeView(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Accepted(staleCleared = true)
        gateway.nextApproval = PendingApprovalLookup.Found(
            ApprovalPayload(approvalId = "appr-10", command = "queued", description = "")
        )
        val executor = QueueExecutor()
        val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, ui)
        controller.onEvent(approvalEvent("appr-9"))
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        controller.dismissAll() // stream cancelled before the executor even started
        executor.runAll()
        ui.runAll()

        // Invalidated generation: the respond is never sent and no queue head resurfaces.
        assertTrue(gateway.approvalCalls.isEmpty())
        assertEquals(0, gateway.pendingApprovalFetches)
        assertEquals(listOf("appr-9"), view.shownApprovals.map { it.approvalId })
        assertEquals(1, view.approvalDismissals) // only dismissAll's cleanup
    }

    // --- wiring contract (mirrors ChatActivity) -----------------------------------------------------

    @Test
    fun activityWiringEchoAppendsUserMessageToTranscriptOnUiDispatch() {
        // Mirrors the ChatActivity wiring: uiState.onEvent + controller.onEvent per SSE event and
        // onClarifyAnswered appending the accepted answer to the transcript (web pushes a user
        // message). The echo must run on the UI dispatch, not on the executor thread.
        val state = ChatUiState()
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        controller.onClarifyAnswered = { answer -> state.messages += ChatMessage("user", answer) }
        state.begin("спроси профиль")

        val event = clarifyEvent("cl-7", "default", "office")
        state.onEvent(event)      // records pendingClarify + thought trace
        controller.onEvent(event) // shows the dialog

        controller.onClarifyResponse("office")
        executor.runAll()
        assertEquals(2, state.messages.size) // no echo before the UI dispatch
        ui.runAll()

        assertNull(state.pendingClarify)
        val last = state.messages.last()
        assertEquals("user", last.role)
        assertEquals("office", last.text)
    }

    // --- Autonomy mode ----------------------------------------------------------------------------

    @Test
    fun autonomyGrantSendsGrantFlagAndProjectsEnabledState() {
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Accepted(autonomyEnabled = true)
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val changes = mutableListOf<Boolean>()
        controller.onAutonomyChanged = { changes += it }
        state.begin("запусти деплой")
        state.onEvent(approvalEvent("appr-1"))
        controller.onEvent(approvalEvent("appr-1"))

        assertFalse(state.autonomyEnabled)
        controller.onApprovalChoice(ApprovalChoice.ONCE, grantsAutonomy = true)
        executor.runAll()
        ui.runAll()

        // The respond carried the autonomy grant and the settled server state was projected locally.
        assertTrue(gateway.approvalCalls.single().grantAutonomy)
        assertTrue(state.autonomyEnabled)
        assertEquals(listOf(true), changes)
        assertEquals(1, view.approvalDismissals)
    }

    @Test
    fun plainApprovalChoiceNeverSendsGrantFlag() {
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        state.begin("привет")
        state.onEvent(approvalEvent("appr-2"))
        controller.onEvent(approvalEvent("appr-2"))

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll(); ui.runAll()

        assertFalse(gateway.approvalCalls.single().grantAutonomy)
        assertFalse(state.autonomyEnabled)
    }

    @Test
    fun rejectedRespondStillProjectsAutonomyStateFromErrorPayload() {
        // Web parity: gateway relay failures (e.g. gateway_run_unavailable) still commit the
        // autonomy flag — the error body's settled flag must be applied before the error surfaces.
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        gateway.approvalResult = InteractiveRespondResult.Rejected(
            "gateway relay unavailable", httpStatus = 409, code = "gateway_run_unavailable", autonomyEnabled = true
        )
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val changes = mutableListOf<Boolean>()
        controller.onAutonomyChanged = { changes += it }
        state.begin("деплой")
        state.onEvent(approvalEvent("appr-3"))
        controller.onEvent(approvalEvent("appr-3"))

        controller.onApprovalChoice(ApprovalChoice.ONCE, grantsAutonomy = true)
        executor.runAll(); ui.runAll()

        assertTrue(state.autonomyEnabled)
        assertEquals(listOf(true), changes)
        assertEquals(1, view.errors.size) // dialog stays open with the server message
        assertEquals(1, view.shownApprovals.size)
    }

    @Test
    fun setAutonomyEnabledAppliesServerSettledState() {
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        gateway.autonomyResult = AutonomyToggleResult.Applied(true)
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val changes = mutableListOf<Boolean>()
        controller.onAutonomyChanged = { changes += it }

        controller.setAutonomyEnabled(true)
        executor.runAll()
        assertEquals(listOf("sess-1" to true), gateway.autonomyCalls)
        assertFalse(state.autonomyEnabled) // not applied before the UI dispatch
        ui.runAll()

        assertTrue(state.autonomyEnabled)
        assertEquals(listOf(true), changes)
        assertTrue(view.errors.isEmpty())
    }

    @Test
    fun setAutonomyEnabledFailureAppliesKnownStateAndShowsError() {
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        state.setAutonomyEnabled(true)
        // Server refused the disable but reported the committed flag (web applies it from errors).
        gateway.autonomyResult = AutonomyToggleResult.Failed("Переход уже выполняется", knownState = true)
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val changes = mutableListOf<Boolean>()
        controller.onAutonomyChanged = { changes += it }

        controller.setAutonomyEnabled(false)
        executor.runAll(); ui.runAll()

        assertTrue(state.autonomyEnabled) // knownState projection kept the flag on
        assertTrue(changes.isEmpty())   // no change → no notification
        assertEquals(1, view.errors.size)
    }

    @Test
    fun setAutonomyEnabledWithoutSessionShowsErrorAndSendsNothing() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { null }, null, executor, inlineUi)

        controller.setAutonomyEnabled(true)

        assertEquals(1, view.errors.size)
        assertTrue(gateway.autonomyCalls.isEmpty())
        assertTrue(executor.queue.isEmpty())
    }

    @Test
    fun autonomyToggleResultDroppedAfterSessionSwitch() {
        var sid = "sess-1"
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        gateway.autonomyResult = AutonomyToggleResult.Applied(true)
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { sid }, state, executor, ui)

        controller.setAutonomyEnabled(true)
        sid = "sess-2" // session switched while the POST was in flight
        executor.runAll(); ui.runAll()

        assertFalse(state.autonomyEnabled) // stale-session result never touches the new session's state
    }

    @Test
    fun onAutonomyChangedFiresOnlyOnActualChange() {
        val view = FakeView(); val state = ChatUiState(); val gateway = FakeGateway()
        gateway.autonomyResult = AutonomyToggleResult.Applied(true)
        val executor = QueueExecutor(); val ui = QueueUiDispatcher()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, state, executor, ui)
        val changes = mutableListOf<Boolean>()
        controller.onAutonomyChanged = { changes += it }

        controller.setAutonomyEnabled(true)
        executor.runAll(); ui.runAll()
        controller.setAutonomyEnabled(true) // already on — server confirms the same state
        executor.runAll(); ui.runAll()

        assertEquals(listOf(true), changes)
    }

    // --- retry (choice retention) ---------------------------------------------------------------

    @Test
    fun approvalNetworkFailureKeepsTheChoiceAndRetryRepostsIt() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.NetworkError("offline")

        controller.onApprovalChoice(ApprovalChoice.SESSION)
        executor.runAll()

        assertEquals(1, gateway.approvalCalls.size)
        assertEquals(ApprovalAttempt(ApprovalChoice.SESSION), view.approvalRetries.last())
        assertEquals(0, view.approvalDismissals) // the dialog stays open for the retry
        assertEquals(InteractiveRequestController.NETWORK_FAILURE, view.errors.last())

        gateway.approvalResult = InteractiveRespondResult.Accepted()
        controller.onApprovalRetry()
        executor.runAll()

        assertEquals(2, gateway.approvalCalls.size)
        // The retry re-sent exactly the retained decision — not a default and not a new tap.
        assertEquals(ApprovalChoice.SESSION, gateway.approvalCalls.last().choice)
        assertFalse(gateway.approvalCalls.last().grantAutonomy)
        assertEquals(1, view.approvalDismissals)
        assertNull(view.approvalRetries.last())
    }

    @Test
    fun approvalRetryKeepsTheAutonomyGrantFlag() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.NetworkError("offline")

        controller.onApprovalChoice(ApprovalChoice.ONCE, grantsAutonomy = true)
        executor.runAll()
        gateway.approvalResult = InteractiveRespondResult.Accepted()
        controller.onApprovalRetry()
        executor.runAll()

        assertTrue(gateway.approvalCalls.last().grantAutonomy)
        assertEquals(ApprovalChoice.ONCE, gateway.approvalCalls.last().choice)
    }

    @Test
    fun deterministicRejectionDoesNotOfferRetry() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.Rejected("Invalid choice", 400)

        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        assertNull(view.approvalRetries.last()) // no dead-end button
        controller.onApprovalRetry()
        executor.runAll()
        assertEquals(1, gateway.approvalCalls.size) // nothing retained → nothing sent
    }

    @Test
    fun serverFaultOffersRetry() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.Rejected("upstream timeout", 503)

        controller.onApprovalChoice(ApprovalChoice.DENY)
        executor.runAll()

        assertEquals(ApprovalAttempt(ApprovalChoice.DENY), view.approvalRetries.last())
        gateway.approvalResult = InteractiveRespondResult.Accepted()
        controller.onApprovalRetry()
        executor.runAll()
        assertEquals(ApprovalChoice.DENY, gateway.approvalCalls.last().choice)
    }

    @Test
    fun retryIsIgnoredWhileARespondIsAlreadyInFlight() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.NetworkError("offline")
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        gateway.approvalResult = InteractiveRespondResult.Accepted()
        controller.onApprovalRetry()
        controller.onApprovalRetry() // second tap while the retry POST is queued
        executor.runAll()

        assertEquals(2, gateway.approvalCalls.size)
    }

    @Test
    fun supersedingPromptDropsTheRetainedChoice() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.NetworkError("offline")
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        controller.onEvent(approvalEvent("appr-2")) // queue moved on
        assertNull(view.approvalRetries.last())

        controller.onApprovalRetry()
        executor.runAll()
        assertEquals(1, gateway.approvalCalls.size) // the old decision is never replayed onto appr-2
    }

    @Test
    fun dismissalDropsTheRetainedChoice() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(approvalEvent("appr-1"))
        gateway.approvalResult = InteractiveRespondResult.NetworkError("offline")
        controller.onApprovalChoice(ApprovalChoice.ONCE)
        executor.runAll()

        controller.onApprovalDismissed()
        assertNull(view.approvalRetries.last())

        controller.onApprovalRetry()
        executor.runAll()
        assertEquals(1, gateway.approvalCalls.size)
    }

    @Test
    fun clarifyNetworkFailureKeepsTheAnswerAndRetryResendsIt() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(clarifyEvent("clr-1", "Кратко"))
        gateway.clarifyResult = InteractiveRespondResult.NetworkError("offline")

        controller.onClarifyResponse("Подробно, с примерами")
        executor.runAll()

        assertEquals("Подробно, с примерами", view.clarifyRetries.last())
        assertEquals(0, view.clarifyDismissals)

        gateway.clarifyResult = InteractiveRespondResult.Accepted()
        controller.onClarifyRetry()
        executor.runAll()

        assertEquals(2, gateway.clarifyCalls.size)
        assertEquals("Подробно, с примерами", gateway.clarifyCalls.last().third)
        assertEquals(1, view.clarifyDismissals)
        assertNull(view.clarifyRetries.last())
    }

    @Test
    fun clarifyStaleDropsTheRetainedAnswer() {
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onEvent(clarifyEvent("clr-1"))
        gateway.clarifyResult = InteractiveRespondResult.NetworkError("offline")
        controller.onClarifyResponse("да")
        executor.runAll()

        gateway.clarifyResult = InteractiveRespondResult.Stale("expired")
        controller.onClarifyRetry()
        executor.runAll()

        // Stale is terminal: dialog dismissed, answer forgotten, no third POST possible.
        assertEquals(1, view.clarifyDismissals)
        assertNull(view.clarifyRetries.last())
        controller.onClarifyRetry()
        executor.runAll()
        assertEquals(2, gateway.clarifyCalls.size)
    }
    // --- живые каналы /api/approval/stream и /api/clarify/stream ---------------------------------

    @Test
    fun approvalStreamShowsTheCardWithoutAnyTurnEvent() {
        // Сервер пушит очередь сам: карточка обязана появиться, даже когда в приложении
        // не идёт стрим ответа (ход завершён, экран открыли заново).
        val view = FakeView(); val state = ChatUiState()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, state, QueueExecutor(), inlineUi)

        val payload = ApprovalPayload(approvalId = "appr-7", command = "ls", description = "list")
        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(payload, pendingCount = 1))

        assertEquals(listOf(payload), view.shownApprovals)
        assertEquals(payload, state.pendingApproval)
    }

    @Test
    fun approvalStreamRepeatsAreDedupedById() {
        val view = FakeView()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, null, QueueExecutor(), inlineUi)
        val payload = ApprovalPayload(approvalId = "appr-7", command = "ls", description = "")

        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(payload, 1))
        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(payload, 1))

        assertEquals(1, view.shownApprovals.size)
    }

    @Test
    fun approvalStreamEmptyQueueTakesTheCardDown() {
        // pending: null — реальное состояние (очередь опустела), карточка не должна висеть.
        val view = FakeView(); val state = ChatUiState()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, state, QueueExecutor(), inlineUi)
        controller.onApprovalStreamUpdate(
            ApprovalStreamUpdate(ApprovalPayload(approvalId = "appr-7", command = "ls", description = ""), 1)
        )

        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(null, pendingCount = 0))

        assertEquals(1, view.approvalDismissals)
        assertNull(state.pendingApproval)
    }

    @Test
    fun approvalStreamFirstEmptySnapshotDoesNotKillTheCardFromTheTurnStream() {
        // Подписка берёт снапшот ДО того, как approval появился: пустой initial не должен
        // гасить карточку, которую уже показал кадр хода. Следующий push её подтвердит.
        val view = FakeView(); val state = ChatUiState()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, state, QueueExecutor(), inlineUi)
        controller.onEvent(approvalEvent("appr-9"))

        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(null, pendingCount = 0))

        assertEquals(0, view.approvalDismissals)
        assertEquals("appr-9", state.pendingApproval?.approvalId)
    }

    @Test
    fun approvalStreamEmptySnapshotIsIgnoredWhileTheAnswerIsInFlight() {
        // Ответ уже улетел: карточку снимет результат respond'а, а не гонка со снапшотом.
        val view = FakeView(); val gateway = FakeGateway()
        val executor = QueueExecutor()
        val controller = InteractiveRequestController(view, gateway, { "sess-1" }, null, executor, inlineUi)
        controller.onApprovalStreamUpdate(
            ApprovalStreamUpdate(ApprovalPayload(approvalId = "appr-7", command = "ls", description = ""), 1)
        )
        controller.onApprovalChoice(ApprovalChoice.ONCE) // ответ в полёте (executor ещё не выполнен)

        controller.onApprovalStreamUpdate(ApprovalStreamUpdate(null, pendingCount = 0))

        assertEquals(0, view.approvalDismissals)
        executor.runAll()
        assertEquals(1, view.approvalDismissals)
    }

    @Test
    fun clarifyStreamShowsAndThenClearsThePrompt() {
        val view = FakeView(); val state = ChatUiState()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, state, QueueExecutor(), inlineUi)
        val payload = ClarifyPayload(clarifyId = "clr-7", question = "Ветка?", choices = listOf("main"))

        controller.onClarifyStreamUpdate(ClarifyStreamUpdate(payload, pendingCount = 1))
        assertEquals(listOf(payload), view.shownClarifies)

        controller.onClarifyStreamUpdate(ClarifyStreamUpdate(null, pendingCount = 0))
        assertEquals(1, view.clarifyDismissals)
        assertNull(state.pendingClarify)
    }

    @Test
    fun clarifyStreamFirstEmptySnapshotKeepsTheTurnStreamCard() {
        val view = FakeView()
        val controller = InteractiveRequestController(view, FakeGateway(), { "sess-1" }, null, QueueExecutor(), inlineUi)
        controller.onEvent(clarifyEvent("clr-1", "main"))

        controller.onClarifyStreamUpdate(ClarifyStreamUpdate(null, pendingCount = 0))

        assertEquals(0, view.clarifyDismissals)
    }

}
