package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRespondResult

/**
 * Unit tests for the «Финиш» graceful session-close orchestration ([SessionFinishFlow]).
 * Pure JVM: the [SessionFinishFlow.Actions] seam is faked; every test asserts the exact
 * step order and that a failure never aborts the close.
 */
class SessionFinishFlowTest {

    private class FakeActions(
        override val isStreaming: Boolean = false,
        private val approvals: ArrayDeque<ApprovalPayload> = ArrayDeque(),
        private val clarify: ClarifyPayload? = null,
    ) : SessionFinishFlow.Actions {
        var cancelCalls = 0
        var cancelResult = true
        val denied = mutableListOf<Pair<ApprovalPayload, ApprovalChoice>>()
        /** Per-payload overrides of the deny result (default: Accepted). */
        var denyResults: (ApprovalPayload) -> InteractiveRespondResult = { InteractiveRespondResult.Accepted() }
        /** When true the queue never drains (runaway-guard testing). */
        var endlessQueue = false
        var clarifyChecks = 0

        override fun cancelStream(): Boolean {
            cancelCalls++
            return cancelResult
        }

        override fun nextPendingApproval(): ApprovalPayload? =
            if (endlessQueue) approvals.firstOrNull() else approvals.removeFirstOrNull()

        override fun denyApproval(payload: ApprovalPayload): InteractiveRespondResult {
            denied += payload to ApprovalChoice.DENY
            return denyResults(payload)
        }

        override fun nextPendingClarify(): ClarifyPayload? {
            clarifyChecks++
            return clarify
        }
    }

    private fun approval(id: String) =
        ApprovalPayload(approvalId = id, command = "rm -rf /tmp/$id", description = "")

    @Test
    fun idleSessionWithoutQueueIsANoOpClose() {
        val actions = FakeActions(isStreaming = false)

        val report = SessionFinishFlow(actions).finish()

        assertEquals(0, actions.cancelCalls)
        assertEquals(0, actions.denied.size)
        assertEquals(SessionFinishFlow.Report(false, 0, emptyList(), false), report)
        assertTrue(report.succeeded)
    }

    @Test
    fun runningStreamIsCancelledFirst() {
        val actions = FakeActions(isStreaming = true, approvals = ArrayDeque(listOf(approval("a1"))))
        val order = mutableListOf<String>()
        val flow = SessionFinishFlow(object : SessionFinishFlow.Actions by actions {
            override fun cancelStream(): Boolean { order += "cancel"; return actions.cancelStream() }
            override fun denyApproval(payload: ApprovalPayload): InteractiveRespondResult {
                order += "deny"
                return actions.denyApproval(payload)
            }
        })

        val report = flow.finish()

        assertTrue(report.streamCancelled)
        assertEquals(listOf("cancel", "deny"), order) // cancel precedes any deny
    }

    @Test
    fun cancelFailureDoesNotAbortTheClose() {
        val actions = FakeActions(isStreaming = true, approvals = ArrayDeque(listOf(approval("a1"))))
        actions.cancelResult = false // transport failure on /api/chat/cancel

        val report = SessionFinishFlow(actions).finish()

        assertFalse(report.streamCancelled)
        assertEquals(1, report.approvalsDenied) // queue was still drained
        assertTrue(report.succeeded)
    }

    @Test
    fun drainsTheWholeApprovalQueueInOrder() {
        val queue = ArrayDeque(listOf(approval("a1"), approval("a2"), approval("a3")))
        val actions = FakeActions(approvals = queue)

        val report = SessionFinishFlow(actions).finish()

        assertEquals(listOf("a1", "a2", "a3"), actions.denied.map { it.first.approvalId })
        assertTrue(actions.denied.all { it.second == ApprovalChoice.DENY })
        assertEquals(3, report.approvalsDenied)
        assertTrue(report.approvalFailures.isEmpty())
    }

    @Test
    fun staleCountAsDenied() {
        val actions = FakeActions(approvals = ArrayDeque(listOf(approval("a1"))))
        actions.denyResults = { InteractiveRespondResult.Stale("Очередь очищена сервером") }

        val report = SessionFinishFlow(actions).finish()

        assertEquals(1, report.approvalsDenied)
        assertTrue(report.succeeded)
    }

    @Test
    fun deniedFailureStopsTheLoopAndIsRecorded() {
        val actions = FakeActions(
            approvals = ArrayDeque(listOf(approval("a1"), approval("a2"), approval("a3")))
        )
        actions.denyResults = { payload ->
            if (payload.approvalId == "a2") InteractiveRespondResult.Rejected("gateway_run_unavailable", 409, "gateway_run_unavailable")
            else InteractiveRespondResult.Accepted()
        }

        val report = SessionFinishFlow(actions).finish()

        assertEquals(1, report.approvalsDenied)                       // a1 only
        assertEquals(listOf("a1", "a2"), actions.denied.map { it.first.approvalId }) // a3 never attempted
        assertEquals(listOf("gateway_run_unavailable"), report.approvalFailures)
        assertFalse(report.succeeded)
    }

    @Test
    fun networkErrorStopsTheLoopAndIsRecorded() {
        val actions = FakeActions(approvals = ArrayDeque(listOf(approval("a1"))))
        actions.denyResults = { InteractiveRespondResult.NetworkError("timeout") }

        val report = SessionFinishFlow(actions).finish()

        assertEquals(0, report.approvalsDenied)
        assertEquals(listOf("timeout"), report.approvalFailures)
        assertFalse(report.succeeded)
    }

    @Test
    fun runawayQueueIsCappedByMaxSteps() {
        val actions = FakeActions(approvals = ArrayDeque(listOf(approval("a1"))))
        actions.endlessQueue = true

        val report = SessionFinishFlow(actions, maxApprovalSteps = 3).finish()

        assertEquals(3, actions.denied.size) // hard cap, never spins forever
        assertEquals(3, report.approvalsDenied)
        assertEquals(listOf(SessionFinishFlow.QUEUE_NOT_DRAINED), report.approvalFailures)
        assertFalse(report.succeeded)
    }

    @Test
    fun pendingClarifyIsReportedButNeverAnswered() {
        val actions = FakeActions(
            clarify = ClarifyPayload(clarifyId = "cl-1", question = "Какой профиль?")
        )

        val report = SessionFinishFlow(actions).finish()

        assertTrue(report.clarifyPending)
        assertEquals(1, actions.clarifyChecks) // single lookup, no respond call exists on the seam
        assertTrue(report.succeeded)
    }
}
