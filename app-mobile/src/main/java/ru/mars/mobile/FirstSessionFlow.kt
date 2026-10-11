package ru.mars.mobile

import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator

/**
 * Result of the ensure-session step ChatActivity runs before the FIRST chat/start.
 * The live backend requires a real session_id on /api/chat/start, so a null id must
 * never reach ChatRepository.sendMessage — it is minted here via POST /api/session/new.
 */
sealed interface EnsureSessionResult {
    /**
     * A usable session id: either the already-bound one or a freshly created one.
     * [model]/[modelProvider] carry the server's effective model identity when this
     * result came from a fresh `POST /api/session/new` payload; they stay null for
     * the already-bound path (unknown here — never invented) and for older payloads
     * without the fields. Defaults keep the single-arg shape source-compatible.
     */
    data class Ready(
        val sessionId: String,
        val model: String? = null,
        val modelProvider: String? = null,
    ) : EnsureSessionResult
    /** Session creation failed; [message] is the reason to surface verbatim in the status bar. */
    data class Failed(val message: String) : EnsureSessionResult
}

/**
 * Pure first-session orchestration: no Android, no HTTP — [SessionCreator] is the seam.
 * Keeps the ChatActivity call site a single suspend call with an explicit outcome, so the
 * "null id → create → bind / fail with reason" contract is unit-tested without a device.
 */
class FirstSessionFlow(
    private val creator: SessionCreator,
    private val baseUrl: String,
) {
    suspend fun ensureSession(
        currentSessionId: String?,
        credential: ConsumedDeviceCredential,
    ): EnsureSessionResult {
        currentSessionId?.takeIf { it.isNotBlank() }?.let { return EnsureSessionResult.Ready(it) }
        return when (val outcome = creator.createSession(baseUrl, credential)) {
            is CreateSessionOutcome.Success -> EnsureSessionResult.Ready(
                outcome.session.id,
                outcome.session.model,
                outcome.session.modelProvider,
            )
            is CreateSessionOutcome.Failure -> EnsureSessionResult.Failed(outcome.message)
        }
    }
}
