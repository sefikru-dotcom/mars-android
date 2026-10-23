package ru.mars.mobile

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ApprovalStreamUpdate
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ClarifyStreamUpdate
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.AutonomyToggleResult
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.InteractiveRetryPolicy
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.SessionAutonomyRepository
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Dialog surface for interactive attention requests. Implemented by [MarsInteractiveView]
 * (custom Mars-themed modals — no stock AlertDialog) on device and by fakes in unit tests.
 * The controller invokes these methods on its [UiDispatcher] thread (the UI thread in
 * production); [MarsInteractiveView] still marshals defensively via `runOnUiThread` so a
 * stray background call can never crash.
 */
interface InteractiveRequestView {
    fun showApproval(payload: ApprovalPayload)
    fun showClarify(payload: ClarifyPayload)
    /** Lock/unlock the approval dialog controls while a respond call is in flight. */
    fun setApprovalResponding(responding: Boolean)
    /** Lock/unlock the clarify dialog controls while a respond call is in flight. */
    fun setClarifyResponding(responding: Boolean)

    /**
     * Offer (non-null) or hide (null) the retry action for a failed approval respond, keeping
     * the choice the user already made. Default: no-op, so surfaces without a retry affordance
     * stay valid implementations.
     */
    fun setApprovalRetry(attempt: ApprovalAttempt?) = Unit

    /** Same for clarify: the retained free-text/preset answer that may be re-sent unchanged. */
    fun setClarifyRetry(answer: String?) = Unit

    fun dismissApproval()
    fun dismissClarify()
    fun showError(message: String)
    fun showNotice(message: String)
}

/** User actions surfaced by [InteractiveRequestView] implementations back to the controller. */
interface InteractiveRequestListener {
    /**
     * User picked an approval option. [grantsAutonomy] is true for the autonomy grant: the
     * respond carries the grant flag (wire mapping in core-network), which flips session-wide
     * autonomy server-side (web parity).
     */
    fun onApprovalChoice(choice: ApprovalChoice, grantsAutonomy: Boolean = false)

    /**
     * User tapped "retry" after a transport failure: re-send the retained choice unchanged.
     * Default: no-op (a surface without retry never calls it).
     */
    fun onApprovalRetry() = Unit

    /** User cancelled the approval dialog. MUST NOT be interpreted as a decision (silence ≠ consent). */
    fun onApprovalDismissed()
    fun onClarifyResponse(text: String)

    /** User tapped "retry" after a failed clarify respond: re-send the retained answer. */
    fun onClarifyRetry() = Unit

    /** User cancelled the clarify dialog without answering. */
    fun onClarifyDismissed()
}

/**
 * Data seam between the controller and hermes-webui. Implemented by [RepositoryInteractiveGateway]
 * on device; unit tests substitute a fake to exercise dialog flows without HTTP.
 */
interface InteractiveRequestsGateway {
    fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, grantAutonomy: Boolean = false): InteractiveRespondResult
    fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult
    fun fetchPendingApproval(sessionId: String): PendingApprovalLookup
    fun fetchPendingClarify(sessionId: String): PendingClarifyLookup
    /** Server toggle of session-wide autonomy (explicit pill/command); wire contract lives in the core-network adapter. */
    fun setSessionAutonomy(sessionId: String, enabled: Boolean): AutonomyToggleResult
}

/**
 * Production gateway: binds [InteractiveRequestsRepository] to the base URL and the paired-device
 * credential. A missing credential is reported as a rejection — never as a crash and never by
 * falling back to an unauthenticated call.
 */
class RepositoryInteractiveGateway(
    private val repository: InteractiveRequestsRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : InteractiveRequestsGateway {
    /** Autonomy client is construction-injected-free on purpose: the gateway's test seam is the interface itself. */
    private val autonomyRepository = SessionAutonomyRepository()

    override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, grantAutonomy: Boolean): InteractiveRespondResult {
        val credential = credentialProvider() ?: return InteractiveRespondResult.Rejected(AUTH_REQUIRED, 0)
        return repository.respondApproval(baseUrl, credential, sessionId, payload, choice, grantAutonomy = grantAutonomy)
    }

    override fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult {
        val credential = credentialProvider() ?: return InteractiveRespondResult.Rejected(AUTH_REQUIRED, 0)
        return repository.respondClarify(baseUrl, credential, sessionId, payload, response)
    }

    override fun fetchPendingApproval(sessionId: String): PendingApprovalLookup {
        val credential = credentialProvider() ?: return PendingApprovalLookup.Failed(AUTH_REQUIRED)
        return repository.fetchPendingApproval(baseUrl, credential, sessionId)
    }

    override fun fetchPendingClarify(sessionId: String): PendingClarifyLookup {
        val credential = credentialProvider() ?: return PendingClarifyLookup.Failed(AUTH_REQUIRED)
        return repository.fetchPendingClarify(baseUrl, credential, sessionId)
    }

    override fun setSessionAutonomy(sessionId: String, enabled: Boolean): AutonomyToggleResult {
        val credential = credentialProvider() ?: return AutonomyToggleResult.Failed(AUTH_REQUIRED)
        return autonomyRepository.setEnabled(baseUrl, credential, sessionId, enabled)
    }

    companion object {
        const val AUTH_REQUIRED = "Устройство не авторизовано — выполните сопряжение заново."
    }
}

/**
 * Drives real approval/clarify dialogs for the chat stream, mirroring hermes-webui semantics
 * (static/messages.js respondApproval/respondClarify/dismissApprovalCard):
 *
 * - Approval: single choice out of once/session/always/deny (options gated by `allow_session` /
 *   `allow_permanent`); queued prompts (`pending_count > 1`) are shown one by one — after an
 *   accepted response the next head is re-fetched via `GET /api/approval/pending`, exactly like
 *   the web does after `stale_cleared`.
 * - Clarify: single-select from `choices_offered` or free text; the answer is trimmed and echoed
 *   via [onClarifyAnswered] so the transcript can show it (web pushes a user message).
 * - Cancellation: dismissing a dialog NEVER posts a response — the agent keeps waiting and the
 *   server-side timeout decides (silence is not consent; web `dismissApprovalCard` behaves the same).
 * - Errors: rejected/network failures keep the dialog open with re-enabled controls and surface the
 *   server message; clarify HTTP 409 (stale) is terminal — the dialog is dismissed with a notice.
 * - Races: a response result is ignored when the dialog it belongs to was superseded by a newer
 *   SSE event or dismissed in flight (identity guard, mirroring `_approvalResponseOwnerIsCurrent`).
 * - Terminal stream events ([ChatEvent.Done], [ChatEvent.Error]) dismiss both dialogs: the prompts
 *   cannot outlive their stream.
 *
 * Secret hygiene: the controller never sees the access token (the gateway holds it) and never logs
 * payloads; only user-visible strings are forwarded to [InteractiveRequestView].
 *
 * Threading: [onEvent] and the listener callbacks arrive on the UI thread. Gateway HTTP runs on
 * [executor] — and NOTHING else does. Every background result is handed to [uiDispatcher] before
 * any state is touched: the identity guard, the generation guard, all controller fields, the
 * [ChatUiState] writes and [onClarifyAnswered] run confined to the dispatcher thread, so a late
 * executor callback can never mutate state or show a dialog from the background.
 *
 * Lifecycle: the host calls [dismissAll] on stream cancel, new session and session bind (prompts
 * must not outlive their stream/session; [dismissAll] also invalidates every in-flight background
 * result via the generation counter), and [destroy] from `onDestroy` — after [destroy] all late
 * callbacks and events are ignored, so no dialog can reappear on a dead host.
 *
 * Wiring (see ChatActivity#onCreate):
 * ```
 * val view = MarsInteractiveView(this)
 * val controller = InteractiveRequestController(
 *     view = view,
 *     gateway = RepositoryInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
 *     sessionProvider = { currentSessionId },
 *     state = uiState,
 *     uiDispatcher = MainThreadDispatcher(),
 * ).also { view.listener = it }
 * controller.onClarifyAnswered = { answer -> /* runs on the UI thread */ }
 * ```
 * In the stream collect loop feed every event to BOTH `uiState.onEvent(event)` (after capturing
 * SessionBound/StreamStarted ids) and `controller.onEvent(event)`.
 */
class InteractiveRequestController(
    private val view: InteractiveRequestView,
    private val gateway: InteractiveRequestsGateway,
    private val sessionProvider: () -> String?,
    private val state: ChatUiState? = null,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: UiDispatcher = MainThreadDispatcher(),
) : InteractiveRequestListener {

    // All of the mutable fields below are confined to the UI dispatcher thread: they are written
    // from onEvent/listener callbacks (UI thread by contract) and from dispatched result blocks.
    private var currentApproval: ChatEvent.ApprovalRequired? = null
    private var currentClarify: ChatEvent.ClarifyRequired? = null
    private var approvalResponding = false
    private var clarifyResponding = false

    /**
     * Живой канал уже показал непустой approval/clarify. Нужен ровно для одной развилки:
     * первый пустой снапшот после подписки описывает состояние ДО неё и не должен гасить
     * карточку, показанную кадром хода; последующие пустые снапшоты — это «очередь опустела».
     */
    private var approvalStreamHadPayload = false
    private var clarifyStreamHadPayload = false

    /**
     * The decision the user already submitted, retained across a failed respond call so the
     * retry action re-sends exactly that choice — "не терять выбор" (the user never has to find
     * and re-tap the button, and the retry can never send a different decision than the one they
     * saw). Cleared on success, on a stale prompt and on dismissal/supersede.
     */
    private var lastApprovalAttempt: ApprovalAttempt? = null

    /** The clarify answer retained across a failed respond call (preset label or free text). */
    private var lastClarifyAnswer: String? = null

    /**
     * Bumped by every [dismissAll] (cancel / new session / bind / destroy). Background work
     * captures the generation it was started under; if it changed by the time the result is
     * dispatched, the result is dropped — a prompt belonging to a dead stream/session must never
     * resurface UI. Volatile only for the best-effort pre-flight check on the executor thread;
     * the authoritative guard is re-evaluated on the dispatcher thread.
     */
    @Volatile
    private var generation = 0

    /** Set by [destroy]; ignores every future event, callback and dispatched result. */
    @Volatile
    private var destroyed = false

    /**
     * Optional echo hook: invoked with the accepted clarify answer ON THE UI DISPATCHER THREAD
     * (web shows it as a user message; the host appends it to the transcript and re-renders).
     */
    var onClarifyAnswered: ((String) -> Unit)? = null

    /**
     * Optional autonomy hook: invoked ON THE UI DISPATCHER THREAD with the settled server-side
     * state whenever a respond or an explicit toggle flipped session-wide autonomy (web parity:
     * settled-flag projection + pill/toast). Fires only on actual changes.
     */
    var onAutonomyChanged: ((Boolean) -> Unit)? = null

    /** Feed every stream event here from the Activity's collect loop (UI thread). */
    fun onEvent(event: ChatEvent) {
        if (destroyed) return
        when (event) {
            is ChatEvent.ApprovalRequired -> showApproval(event)
            is ChatEvent.ClarifyRequired -> showClarify(event)
            ChatEvent.Done -> dismissAll()
            is ChatEvent.Error -> dismissAll()
            else -> Unit
        }
    }

    /**
     * Живой канал approval (`GET /api/approval/stream`): карточка появляется по push сервера,
     * а не по кадру хода — то есть и тогда, когда в приложении не идёт стрим (ход завершился,
     * экран открыли заново, агент ждёт команду в фоне). Вызывать на UI-диспетчере.
     *
     * Пустое обновление — это НЕ «нет данных»: сервер присылает `pending: null`, когда очередь
     * опустела (ответ принят, стейл-свип, сброс сессии), и карточка обязана сняться. Единственное
     * исключение — самый первый пустой снапшот сразу после подписки: он описывает состояние ДО
     * подписки и не должен гасить карточку, которую только что показал кадр хода (сервер
     * пришлёт push, если approval действительно есть).
     */
    fun onApprovalStreamUpdate(update: ApprovalStreamUpdate) {
        if (destroyed) return
        val payload = update.payload
        if (payload == null || update.isEmpty) {
            if (!approvalStreamHadPayload) return
            approvalStreamHadPayload = false
            // Ответ в полёте: карточку снимет его результат (успех/стейл/ошибка), а не гонка
            // с уже устаревшим снапшотом.
            if (approvalResponding) return
            clearApproval()
            view.dismissApproval()
            return
        }
        approvalStreamHadPayload = true
        state?.recordPendingApproval(payload)
        showApproval(ChatEvent.ApprovalRequired(payload))
    }

    /** Живой канал clarify; контракт пустого снапшота — как у [onApprovalStreamUpdate]. */
    fun onClarifyStreamUpdate(update: ClarifyStreamUpdate) {
        if (destroyed) return
        val payload = update.payload
        if (payload == null || update.isEmpty) {
            if (!clarifyStreamHadPayload) return
            clarifyStreamHadPayload = false
            if (clarifyResponding) return
            clearClarify()
            view.dismissClarify()
            return
        }
        clarifyStreamHadPayload = true
        state?.recordPendingClarify(payload)
        showClarify(ChatEvent.ClarifyRequired(payload))
    }

    private fun showApproval(event: ChatEvent.ApprovalRequired) {
        val current = currentApproval
        if (current === event) return
        // Dedup guard: SSE re-push / pending re-fetch of the same queued entry must not rebuild
        // an already open dialog (web: `_renderPendingApprovalForActiveSession` same-id check).
        val id = event.payload.approvalId
        if (id != null && current?.payload?.approvalId == id) return
        currentApproval = event
        approvalResponding = false
        lastApprovalAttempt = null
        view.setApprovalResponding(false)
        view.setApprovalRetry(null)
        view.showApproval(event.payload)
    }

    private fun showClarify(event: ChatEvent.ClarifyRequired) {
        val current = currentClarify
        if (current === event) return
        val id = event.payload.clarifyId
        if (id != null && current?.payload?.clarifyId == id) return
        currentClarify = event
        clarifyResponding = false
        lastClarifyAnswer = null
        view.setClarifyResponding(false)
        view.setClarifyRetry(null)
        view.showClarify(event.payload)
    }

    override fun onApprovalChoice(choice: ApprovalChoice, grantsAutonomy: Boolean) {
        if (destroyed) return
        val current = currentApproval ?: return
        if (approvalResponding) return
        sendApproval(current, ApprovalAttempt(choice, grantsAutonomy))
    }

    /**
     * Retry after a transport failure: re-sends the RETAINED decision, never a new one, so a
     * retry can only ever deliver what the user already agreed to. No-op while a respond is in
     * flight or when nothing is retained.
     */
    override fun onApprovalRetry() {
        if (destroyed) return
        val current = currentApproval ?: return
        if (approvalResponding) return
        val attempt = lastApprovalAttempt ?: return
        sendApproval(current, attempt)
    }

    private fun sendApproval(current: ChatEvent.ApprovalRequired, attempt: ApprovalAttempt) {
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) {
            view.showError(NO_SESSION)
            return
        }
        // Retained before the call: a failed respond keeps it for the retry action, and the
        // dialog's progress row hides that action while the retry itself is in flight.
        lastApprovalAttempt = attempt
        approvalResponding = true
        view.setApprovalResponding(true)
        val gen = generation
        executor.execute {
            // Best-effort pre-flight: a cancel/destroy that landed after the tap must not fire
            // the respond call at all (the authoritative guards run on the dispatcher below).
            if (destroyed || gen != generation) return@execute
            val result = gateway.respondApproval(sid, current.payload, attempt.choice, attempt.grantsAutonomy)
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                // Identity guard: dialog was superseded or dismissed while the call was in
                // flight. Evaluated on the dispatcher thread, together with the state it
                // protects — never on the executor thread.
                if (currentApproval !== current) return@dispatch
                when (result) {
                    is InteractiveRespondResult.Accepted -> {
                        // Autonomy projection: the server may have flipped the session flag
                        // with this respond (settled flag in the accepted body).
                        result.autonomyEnabled?.let { applyAutonomyState(it) }
                        clearApproval()
                        view.dismissApproval()
                        if (result.staleCleared || current.payload.pendingCount > 1) showNextPendingApproval(sid)
                    }
                    is InteractiveRespondResult.Stale -> {
                        clearApproval()
                        view.dismissApproval()
                        view.showNotice(result.message)
                    }
                    is InteractiveRespondResult.Rejected -> {
                        // Web parity: failed gateway relays still commit the autonomy flag —
                        // project it before surfacing the error.
                        result.autonomyEnabled?.let { applyAutonomyState(it) }
                        approvalResponding = false
                        view.setApprovalResponding(false)
                        view.showError(result.message)
                        offerApprovalRetry(attempt, InteractiveRetryPolicy.isRetryable(result))
                    }
                    is InteractiveRespondResult.NetworkError -> {
                        approvalResponding = false
                        view.setApprovalResponding(false)
                        view.showError(NETWORK_FAILURE)
                        offerApprovalRetry(attempt, InteractiveRetryPolicy.isRetryable(result))
                    }
                }
            }
        }
    }

    /**
     * Keeps the choice for a manual retry only when re-sending it can actually succeed; a
     * deterministic 4xx drops it, so the dialog never advertises a dead-end button.
     */
    private fun offerApprovalRetry(attempt: ApprovalAttempt, retryable: Boolean) {
        lastApprovalAttempt = if (retryable) attempt else null
        view.setApprovalRetry(lastApprovalAttempt)
    }

    private fun offerClarifyRetry(answer: String, retryable: Boolean) {
        lastClarifyAnswer = if (retryable) answer else null
        view.setClarifyRetry(lastClarifyAnswer)
    }

    /**
     * Explicit autonomy toggle (header pill / command). Guarded by the session identity (NOT
     * the generation counter — a stream finishing mid-flight must not drop a pill toggle),
     * applied on the UI dispatcher thread like every other state write.
     */
    fun setAutonomyEnabled(enabled: Boolean) {
        if (destroyed) return
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) {
            view.showError(NO_SESSION)
            return
        }
        executor.execute {
            if (destroyed || sid != sessionProvider()) return@execute
            val result = gateway.setSessionAutonomy(sid, enabled)
            uiDispatcher.dispatch {
                if (destroyed || sid != sessionProvider()) return@dispatch
                when (result) {
                    is AutonomyToggleResult.Applied -> applyAutonomyState(result.enabled)
                    is AutonomyToggleResult.Failed -> {
                        result.knownState?.let { applyAutonomyState(it) }
                        view.showError(result.message)
                    }
                }
            }
        }
    }

    /** Single write path for the autonomy flag; [onAutonomyChanged] fires only on actual changes. */
    private fun applyAutonomyState(value: Boolean) {
        val previous = state?.autonomyEnabled
        state?.setAutonomyEnabled(value)
        if (previous != value) onAutonomyChanged?.invoke(value)
    }

    override fun onApprovalDismissed() {
        if (destroyed) return
        // Deliberately no respond call: dismissing hides the prompt without deciding (web parity,
        // silence ≠ consent). The server-side timeout keeps blocking the agent.
        clearApproval()
        view.setApprovalResponding(false)
        view.dismissApproval()
    }

    override fun onClarifyResponse(rawText: String) {
        if (destroyed) return
        val current = currentClarify ?: return
        if (clarifyResponding) return
        val text = rawText.trim()
        if (text.isEmpty()) return // Nothing to send; the dialog stays open (web: focus input, no-op).
        sendClarify(current, text)
    }

    /** Retry with the retained answer (see [onApprovalRetry]); never sends a different text. */
    override fun onClarifyRetry() {
        if (destroyed) return
        val current = currentClarify ?: return
        if (clarifyResponding) return
        val answer = lastClarifyAnswer ?: return
        sendClarify(current, answer)
    }

    private fun sendClarify(current: ChatEvent.ClarifyRequired, text: String) {
        val sid = current.payload.sessionId?.takeIf { it.isNotBlank() } ?: sessionProvider()
        if (sid.isNullOrBlank()) {
            view.showError(NO_SESSION)
            return
        }
        lastClarifyAnswer = text
        clarifyResponding = true
        view.setClarifyResponding(true)
        val gen = generation
        executor.execute {
            if (destroyed || gen != generation) return@execute
            val result = gateway.respondClarify(sid, current.payload, text)
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                if (currentClarify !== current) return@dispatch
                when (result) {
                    is InteractiveRespondResult.Accepted -> {
                        clearClarify()
                        view.dismissClarify()
                        // Echo on the dispatcher (UI) thread — the host appends it to the transcript.
                        onClarifyAnswered?.invoke(text)
                        if (current.payload.pendingCount > 1) showNextPendingClarify(sid)
                    }
                    is InteractiveRespondResult.Stale -> {
                        clearClarify()
                        view.dismissClarify()
                        view.showNotice(result.message)
                    }
                    is InteractiveRespondResult.Rejected -> {
                        clarifyResponding = false
                        view.setClarifyResponding(false)
                        view.showError(result.message)
                        offerClarifyRetry(text, InteractiveRetryPolicy.isRetryable(result))
                    }
                    is InteractiveRespondResult.NetworkError -> {
                        clarifyResponding = false
                        view.setClarifyResponding(false)
                        view.showError(NETWORK_FAILURE)
                        offerClarifyRetry(text, InteractiveRetryPolicy.isRetryable(result))
                    }
                }
            }
        }
    }

    override fun onClarifyDismissed() {
        if (destroyed) return
        clearClarify()
        view.setClarifyResponding(false)
        view.dismissClarify()
    }

    /**
     * Cleanup hook: dismiss everything without responding (stream cancel/done/error, new session,
     * session bind). Invalidates all in-flight background work via [generation] — results for the
     * dead stream/session are dropped when they arrive. The controller stays usable afterwards.
     */
    fun dismissAll() {
        generation++
        // Новая подписка начнётся с нового снапшота: прежний «видел payload» не должен
        // разрешать первому пустому обновлению гасить чужую карточку.
        approvalStreamHadPayload = false
        clarifyStreamHadPayload = false
        clearApproval()
        clearClarify()
        view.setApprovalResponding(false)
        view.setClarifyResponding(false)
        view.dismissApproval()
        view.dismissClarify()
    }

    /**
     * Terminal hook (host `onDestroy`): dismiss everything and ignore every future event,
     * listener callback and late background result. No dialog can reappear after this.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        dismissAll()
    }

    /**
     * Перечитывает pending approval/clarify текущего диалога (тихий push, возврат в
     * приложение). Существующие диалоги не перебиваются: [showNextPendingApproval]
     * выходит, если на экране уже открыт другой запрос, поэтому вызов безопасен и
     * идемпотентен. Вызывать на UI-диспетчере (как и все остальные входы контроллера).
     */
    fun refreshPending() {
        if (destroyed) return
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) return
        showNextPendingApproval(sid)
        showNextPendingClarify(sid)
    }

    private fun showNextPendingApproval(sid: String) {
        val gen = generation
        executor.execute {
            if (destroyed || gen != generation) return@execute
            val lookup = gateway.fetchPendingApproval(sid)
            uiDispatcher.dispatch {
                // The queue head belongs to the generation that ordered the fetch: a cancel,
                // session switch or destroy in between invalidates it (late-callback guard).
                if (destroyed || gen != generation) return@dispatch
                if (currentApproval != null) return@dispatch // A newer SSE event already took over the dialog.
                val found = lookup as? PendingApprovalLookup.Found ?: return@dispatch
                currentApproval = ChatEvent.ApprovalRequired(found.payload)
                approvalResponding = false
                view.setApprovalResponding(false)
                view.showApproval(found.payload)
            }
        }
    }

    private fun showNextPendingClarify(sid: String) {
        val gen = generation
        executor.execute {
            if (destroyed || gen != generation) return@execute
            val lookup = gateway.fetchPendingClarify(sid)
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                if (currentClarify != null) return@dispatch
                val found = lookup as? PendingClarifyLookup.Found ?: return@dispatch
                currentClarify = ChatEvent.ClarifyRequired(found.payload)
                clarifyResponding = false
                view.setClarifyResponding(false)
                view.showClarify(found.payload)
            }
        }
    }

    private fun clearApproval() {
        currentApproval = null
        approvalResponding = false
        lastApprovalAttempt = null
        view.setApprovalRetry(null)
        state?.resolveApproval()
    }

    private fun clearClarify() {
        currentClarify = null
        clarifyResponding = false
        lastClarifyAnswer = null
        view.setClarifyRetry(null)
        state?.resolveClarify()
    }

    companion object {
        const val NO_SESSION = "Нет активной сессии — ответ не отправлен."
        const val NETWORK_FAILURE = "Сеть недоступна — попробуйте ещё раз."

        // Shared daemon executor: default single-thread pool for respond calls, no Activity
        // lifecycle cleanup required (daemon threads die with the process). Late results are
        // neutralised by the generation/identity guards, not by killing the executor.
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "interactive-requests").apply { isDaemon = true }
        }
    }
}


