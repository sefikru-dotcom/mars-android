package ru.mars.network.repository

/**
 * Retry policy for approval/clarify responses: which failures may be re-sent with the SAME
 * choice, and which are terminal for that choice.
 *
 * The client never silently re-posts a decision (an approval is a consent, a clarify answer is
 * user text): the UI only OFFERS a retry button, and only when the same payload can still be
 * accepted. This object is the single place that decides whether such an offer makes sense.
 *
 * Rules (wire contract in [InteractiveRequestsRepository]):
 * - [InteractiveRespondResult.NetworkError] — no HTTP response was observed, the request either
 *   never left the device or its outcome is unknown; a manual retry is the only recovery path.
 * - [InteractiveRespondResult.Rejected] — retryable only for transport-ish statuses: 0 (local
 *   pre-flight rejection, nothing was sent), 408 (timeout), 429 (rate limit) and 5xx (server
 *   fault). 4xx validation/gateway errors (400 invalid choice, 401/403 auth, 409
 *   `gateway_run_unavailable`) are deterministic for this payload — retrying the same body would
 *   fail identically, so the dialog hides the retry action instead of offering a dead end.
 * - [InteractiveRespondResult.Accepted] / [InteractiveRespondResult.Stale] — the prompt is
 *   already resolved server-side; nothing to retry.
 */
object InteractiveRetryPolicy {

    fun isRetryable(result: InteractiveRespondResult): Boolean = when (result) {
        is InteractiveRespondResult.NetworkError -> true
        is InteractiveRespondResult.Rejected -> isRetryableStatus(result.httpStatus)
        is InteractiveRespondResult.Accepted -> false
        is InteractiveRespondResult.Stale -> false
    }

    /** Transport-ish HTTP statuses whose failure is not caused by the payload itself. */
    fun isRetryableStatus(httpStatus: Int): Boolean =
        httpStatus == 0 || httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
}
