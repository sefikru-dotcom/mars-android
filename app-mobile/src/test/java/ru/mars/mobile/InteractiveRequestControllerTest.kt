package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        override fun showApproval(payload: ApprovalPayload) { shownApprovals += payload }
        override fun showClarify(payload: ClarifyPayload) { shownClarifies += payload }
        override fun setApprovalResponding(responding: Boolean) { approvalResponding += responding }
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
        val approvalCalls = mutableListOf<Triple<String, ApprovalPayload, ApprovalChoice>>()
        val clarifyCalls = mutableListOf<Triple<String, ClarifyPayload, String>>()
        var pendingApprovalFetches = 0
        var pendingClarifyFetches = 0

        override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice): InteractiveRespondResult {
            approvalCalls += Triple(sessionId, payload, choice)
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

        assertEquals(Triple("sess-1", view.shownApprovals[0], ApprovalChoice.ALWAYS), gateway.approvalCalls.single())
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
        assertEquals(ApprovalChoice.ONCE, gateway.approvalCalls.single().third)
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
}
