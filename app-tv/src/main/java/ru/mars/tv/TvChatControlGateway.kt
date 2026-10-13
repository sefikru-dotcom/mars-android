package ru.mars.tv

import ru.mars.network.credential.DeviceCredentialStore
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

/**
 * Turn-stop seam for the TV chat screen: cancels the RUNNING server-side agent turn
 * (`GET /api/chat/cancel`, mobile `cancelStream` parity). Dropping only the local SSE
 * connection would leave the turn executing invisibly — burning tokens, running commands
 * or blocked on an approval until the server-side timeout. Blocking call: hosts run it on
 * an IO dispatcher. Returns true only when the server confirms the cancel; never throws.
 */
interface TvChatControlGateway {
    fun cancelStream(streamId: String): Boolean

    /**
     * Steer live turn: POST /api/chat/steer (мобильный `steerMessage` паритет).
     * Успех — поле `accepted` ответа, а НЕ HTTP-код: `executeControl` уже трактует
     * HTTP 200 + `{"accepted": false}` как отказ. Блокирующий вызов (хосты — IO-поток),
     * исключений не бросает.
     */
    fun steer(sessionId: String, text: String): Boolean
}

/**
 * Production [TvChatControlGateway]: binds the REAL core-network [ChatRepository] with the
 * base URL and a credential reader (AndroidKeyStoreCredentialStore). HTTP, auth headers and
 * result mapping stay in core-network (`executeControl` already treats non-2xx and
 * `accepted:false` as failure) — the TV layer adds zero network logic, and the token is
 * never touched by the Activity beyond the opaque credential provider.
 */
class RepositoryTvChatControlGateway(
    private val repository: ChatRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvChatControlGateway {

    override fun cancelStream(streamId: String): Boolean {
        val credential = credentialProvider() ?: return false
        return runCatching {
            repository.executeControl(repository.cancelRequest(baseUrl, streamId, credential.accessToken))
        }.getOrDefault(false)
    }

    override fun steer(sessionId: String, text: String): Boolean {
        val credential = credentialProvider() ?: return false
        return runCatching {
            repository.executeControl(repository.steerRequest(baseUrl, sessionId, text, credential.accessToken))
        }.getOrDefault(false)
    }

    companion object {
        /** Convenience factory for the Activity: shared repository + KeyStore credential store. */
        fun create(baseUrl: String, credentialStore: DeviceCredentialStore): RepositoryTvChatControlGateway =
            RepositoryTvChatControlGateway(ChatRepository(), baseUrl) { credentialStore.read() }
    }
}
