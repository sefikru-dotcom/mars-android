package ru.mars.tv

import android.os.Handler
import android.os.Looper
import ru.mars.network.credential.DeviceCredentialStore
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup

/**
 * Thread seam for [TvInteractiveController] (mobile `UiDispatcher` parity): production is the
 * main looper, unit tests inject a synchronous/queued fake.
 */
fun interface TvUiDispatcher {
    fun dispatch(block: () -> Unit)
}

/** Production [TvUiDispatcher]: posts to the main looper (framework-free constructor call). */
class MainThreadTvDispatcher : TvUiDispatcher {
    private val handler = Handler(Looper.getMainLooper())
    override fun dispatch(block: () -> Unit) {
        handler.post(block)
    }
}

/**
 * Production [TvInteractiveGateway]: binds [InteractiveRequestsRepository] with the base URL and
 * a credential reader (AndroidKeyStoreCredentialStore) into the TV contract. All HTTP, token
 * handling and error mapping stay in core-network — the TV layer adds zero network logic, and the
 * token is never touched by the controller or the Activity.
 */
class RepositoryTvInteractiveGateway(
    private val repository: InteractiveRequestsRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ru.mars.network.model.ConsumedDeviceCredential?,
) : TvInteractiveGateway {

    override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, yolo: Boolean): InteractiveRespondResult {
        val credential = credentialProvider() ?: return InteractiveRespondResult.Rejected(TvInteractiveController.NO_SESSION, 0)
        return repository.respondApproval(baseUrl, credential, sessionId, payload, choice, yolo)
    }

    override fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult {
        val credential = credentialProvider() ?: return InteractiveRespondResult.Rejected(TvInteractiveController.NO_SESSION, 0)
        return repository.respondClarify(baseUrl, credential, sessionId, payload, response)
    }

    override fun fetchPendingApproval(sessionId: String): PendingApprovalLookup {
        val credential = credentialProvider() ?: return PendingApprovalLookup.Empty
        return repository.fetchPendingApproval(baseUrl, credential, sessionId)
    }

    override fun fetchPendingClarify(sessionId: String): PendingClarifyLookup {
        val credential = credentialProvider() ?: return PendingClarifyLookup.Empty
        return repository.fetchPendingClarify(baseUrl, credential, sessionId)
    }

    companion object {
        /** Convenience factory for the Activity: shared repository + KeyStore credential store. */
        fun create(baseUrl: String, credentialStore: DeviceCredentialStore): RepositoryTvInteractiveGateway =
            RepositoryTvInteractiveGateway(InteractiveRequestsRepository(), baseUrl) { credentialStore.read() }
    }
}
