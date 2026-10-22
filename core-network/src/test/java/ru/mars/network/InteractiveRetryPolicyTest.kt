package ru.mars.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.InteractiveRetryPolicy

/**
 * Retry policy of approval/clarify responses: which failures may re-send the SAME decision.
 *
 * The rule that matters for UX: a transport failure must offer a retry (the user's choice is
 * kept), while a deterministic 4xx must NOT — re-sending the identical body can only fail again,
 * so the dialog would advertise a dead-end button.
 */
class InteractiveRetryPolicyTest {

    @Test
    fun networkErrorIsAlwaysRetryable() {
        assertTrue(InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.NetworkError("offline")))
    }

    @Test
    fun transportishStatusesAreRetryable() {
        listOf(0, 408, 429, 500, 502, 503, 504).forEach { status ->
            assertTrue(
                "status $status must be retryable",
                InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.Rejected("boom", status)),
            )
            assertTrue(InteractiveRetryPolicy.isRetryableStatus(status))
        }
    }

    @Test
    fun deterministicClientErrorsAreNotRetryable() {
        listOf(400, 401, 403, 404, 409, 422).forEach { status ->
            assertFalse(
                "status $status must be terminal for the same payload",
                InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.Rejected("nope", status)),
            )
        }
    }

    @Test
    fun gatewayRelayFailureIsTerminal() {
        // 409 gateway_run_unavailable: the queued run is gone; the same choice can never apply.
        val result = InteractiveRespondResult.Rejected("gateway run unavailable", 409, code = "gateway_run_unavailable")
        assertFalse(InteractiveRetryPolicy.isRetryable(result))
    }

    @Test
    fun acceptedAndStalePromptsAreNeverRetryable() {
        assertFalse(InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.Accepted()))
        assertFalse(InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.Accepted(staleCleared = true)))
        assertFalse(InteractiveRetryPolicy.isRetryable(InteractiveRespondResult.Stale("expired")))
    }
}
