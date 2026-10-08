package ru.mars.mobile

/**
 * Server-side credential rejection (HTTP 401) detection over the error strings the
 * existing core-network layer already surfaces:
 * - `ChatRepository` maps a non-2xx `/api/chat/start` onto `ChatEvent.Error("HTTP 401")`;
 * - `SessionRepository.createSession` maps it onto `CreateSessionOutcome.Failure`
 *   with the server `error` body or the `"HTTP 401"` fallback.
 *
 * Why this policy exists: `MainActivity` routes to chat on credential PRESENCE only
 * (`store.read() != null`). When the server revokes or expires that credential, every
 * call fails with the same 401 forever and the user is locked in a broken auth state
 * with no path back to login. The decision "this error means the credential is dead —
 * drop it and re-authenticate" is pure and lives here; ChatActivity only wires the
 * outcome (clear store → login screen).
 *
 * Deliberately conservative: transport failures (timeouts, DNS, SSL) and other HTTP
 * statuses (404/500) are NOT rejections — only auth-specific markers match, so a
 * flaky network never logs the user out.
 */
object AuthRejectionPolicy {
    /** Notice shown by the login screen after a forced re-authentication. */
    const val REAUTH_NOTICE = "Авторизация отклонена сервером. Войдите снова."

    private val rejectionMarkers = listOf(
        "http 401",
        "unauthorized",
        "invalid token",
        "token expired",
        "expired token",
    )

    fun isCredentialRejected(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val normalized = message.lowercase()
        return rejectionMarkers.any { it in normalized }
    }
}
