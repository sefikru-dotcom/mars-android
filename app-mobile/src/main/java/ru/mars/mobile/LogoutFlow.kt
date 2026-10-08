package ru.mars.mobile

import ru.mars.network.credential.DeviceCredentialStore

/**
 * Logout semantics for chat-exit paths.
 *
 * The server-side revoke is strictly best-effort: the local credential MUST be cleared
 * even when the revoke fails (401, network down, server error). Otherwise a token the
 * server already rejects would keep the client locked inside a broken auth state with
 * no way to re-login from a fresh install flow.
 */
class LogoutFlow(
    private val store: DeviceCredentialStore,
    private val revoke: suspend (deviceId: String, accessToken: String) -> Result<Unit>,
) {
    suspend fun logout() {
        val credential = runCatching { store.read() }.getOrNull()
        if (credential != null) {
            runCatching { revoke(credential.deviceId, credential.accessToken) }
        }
        store.clear()
    }
}
