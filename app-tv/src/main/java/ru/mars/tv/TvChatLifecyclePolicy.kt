package ru.mars.tv

/**
 * Minimal chat session/stream binding that survives a TECHNICAL Activity recreation
 * (config change, system-initiated destroy): the ids the recreated [TvChatActivity]
 * needs to continue the same dialog and to still control a server turn whose local SSE
 * stream died with the destroyed Activity. Persisted via `onSaveInstanceState` —
 * deliberately NOT the transcript (restoring bubbles would mean rewriting the UI; the
 * server keeps the history and «Диалоги» can reload it).
 */
data class TvChatBinding(
    val sessionId: String?,
    val activeStreamId: String?,
)

/**
 * Pure lifecycle decisions of the TV chat screen (no Android, no I/O — JVM-testable,
 * [TvChatSessionFlow] parity). [TvChatActivity] owns the framework state and asks this
 * object, so the «technical destroy must not kill the server turn» rule is unit-tested:
 *
 * - [shouldCancelServerTurnOnDestroy]: only an EXPLICIT user exit (BACK/finish) cancels
 *   the live server turn best-effort; a recreation leaves the agent running and the
 *   recreated screen rebinds via [sanitizedBinding].
 * - [sanitizedBinding]: normalizes what goes into (and comes out of) the saved-state
 *   bundle — blank ids never mint or resurrect state.
 */
object TvChatLifecyclePolicy {
    /**
     * True when `onDestroy` must fire the best-effort server cancel
     * ([TvChatActivity.cancelServerTurnBestEffort]): the user is leaving for good
     * (`isFinishing` — BACK or an explicit finish) and this is NOT a configuration-change
     * recreation. Every technical destroy (recreation, system teardown) returns false —
     * the turn keeps running server-side and the binding survives in the saved state.
     */
    fun shouldCancelServerTurnOnDestroy(isFinishing: Boolean, isChangingConfigurations: Boolean): Boolean =
        isFinishing && !isChangingConfigurations

    /**
     * The binding worth persisting/restoring: ids are trimmed, blank ids become `null`,
     * and when nothing remains the result is `null` (an empty binding must never be
     * written to the bundle nor rebind an empty session). A stream id without a session
     * is still valid — it alone lets the recreated screen stop the orphaned server turn.
     */
    fun sanitizedBinding(sessionId: String?, activeStreamId: String?): TvChatBinding? {
        val session = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val stream = activeStreamId?.trim()?.takeIf { it.isNotEmpty() }
        if (session == null && stream == null) return null
        return TvChatBinding(session, stream)
    }
}
