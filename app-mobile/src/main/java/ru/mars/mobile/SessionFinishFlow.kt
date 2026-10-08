package ru.mars.mobile

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.InteractiveRespondResult

/**
 * Graceful session-close scenario ("Финиш") as pure orchestration — no Android framework,
 * fully unit-testable with fakes. The host (ChatActivity) supplies blocking [Actions]
 * (network calls run on an IO dispatcher) and applies the returned [Report] on the UI thread.
 *
 * Deterministic step order (each step is best-effort: a failure never aborts the close):
 * 1. Cancel the in-flight stream (`POST /api/chat/cancel`) when one is running.
 * 2. Deny every queued approval head until the server queue drains — otherwise the agent stays
 *    parked on a confirmation nobody will ever answer. A rejected/failed deny stops the loop
 *    (the message is recorded in [Report.approvalFailures]) instead of hammering the endpoint;
 *    [maxApprovalSteps] caps a runaway queue.
 * 3. Report whether a clarify prompt was pending (the cancelled stream kills the waiter
 *    server-side; the dialog is dismissed by the host — there is no clarify-cancel endpoint
 *    and silence ≠ answer).
 *
 * UI teardown (dismiss dialogs, reset state, status line) stays in the host: this flow only
 * decides WHICH server-side steps to run and reports what happened.
 */
class SessionFinishFlow(
    private val actions: Actions,
    private val maxApprovalSteps: Int = DEFAULT_MAX_APPROVAL_STEPS,
) {
    /** Blocking side effects of the close, implemented by the host (network on IO thread). */
    interface Actions {
        /** True when a stream is currently running and should be cancelled. */
        val isStreaming: Boolean
        /** Cancels the in-flight stream; returns whether the cancel was accepted/issued. */
        fun cancelStream(): Boolean
        /** Current approval-queue head; null when the queue is drained. Called repeatedly. */
        fun nextPendingApproval(): ApprovalPayload?
        /** Denies one queued approval. */
        fun denyApproval(payload: ApprovalPayload): InteractiveRespondResult
        /** Current pending clarify prompt, if any (single check — nothing is sent for it). */
        fun nextPendingClarify(): ClarifyPayload?
    }

    /** What the close actually did; surfaced to the user as a status/toast summary. */
    data class Report(
        val streamCancelled: Boolean,
        val approvalsDenied: Int,
        val approvalFailures: List<String>,
        val clarifyPending: Boolean,
    ) {
        val succeeded: Boolean get() = approvalFailures.isEmpty()
    }

    fun finish(): Report {
        val streamCancelled = if (actions.isStreaming) actions.cancelStream() else false

        var denied = 0
        val failures = mutableListOf<String>()
        var drained = false
        var steps = 0
        while (steps < maxApprovalSteps) {
            val head = actions.nextPendingApproval()
            if (head == null) {
                drained = true
                break
            }
            steps++
            when (val result = actions.denyApproval(head)) {
                is InteractiveRespondResult.Accepted,
                is InteractiveRespondResult.Stale -> denied++
                is InteractiveRespondResult.Rejected -> {
                    failures += result.message
                    break
                }
                is InteractiveRespondResult.NetworkError -> {
                    failures += result.message
                    break
                }
            }
        }
        if (!drained && failures.isEmpty()) failures += QUEUE_NOT_DRAINED

        val clarifyPending = actions.nextPendingClarify() != null
        return Report(streamCancelled, denied, failures, clarifyPending)
    }

    companion object {
        /** Cap for the deny loop: enough for any realistic queue, small enough to never spin. */
        const val DEFAULT_MAX_APPROVAL_STEPS = 8
        const val QUEUE_NOT_DRAINED = "Очередь подтверждений не очищена полностью."
    }
}
