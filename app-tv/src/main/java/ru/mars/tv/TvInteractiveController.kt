package ru.mars.tv

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Card surface for TV interactive attention requests. Implemented by [TvChatInteractiveSurface]
 * (inline focusable cards in the transcript) on device and by fakes in unit tests. The controller
 * invokes these methods on its [TvUiDispatcher] thread (the UI thread in production).
 *
 * TV parity with mobile dialogs: a card is shown INSIDE the message flow (D-pad reachable) rather
 * than as a modal — the transcript stays visible while the agent waits for a decision.
 */
interface TvInteractiveSurface {
    fun showApproval(payload: ApprovalPayload)
    fun showClarify(payload: ClarifyPayload)
    /** Lock/unlock the approval card controls while a respond call is in flight. */
    fun setApprovalResponding(responding: Boolean)
    /** Lock/unlock the clarify card controls while a respond call is in flight. */
    fun setClarifyResponding(responding: Boolean)
    fun dismissApproval()
    fun dismissClarify()
    /** Error rendered inline on the open card (controls already re-enabled). */
    fun showCardError(message: String)
    /** Non-fatal notice after a terminal dismissal (stale prompt etc.). */
    fun showCardNotice(message: String)
}

/** User actions surfaced by [TvInteractiveSurface] implementations back to the controller. */
interface TvInteractiveListener {
    /**
     * User activated an approval option. [yolo] is true for the YOLO grant: the respond is sent
     * with `yolo:true`, which flips the session's YOLO flag server-side (web parity).
     */
    fun onApprovalChoice(choice: ApprovalChoice, yolo: Boolean = false)

    /** User cancelled the approval card (Отмена / BACK). MUST NOT be interpreted as a decision. */
    fun onApprovalCancelled()
    fun onClarifyResponse(text: String)
    /** User cancelled the clarify card without answering. */
    fun onClarifyCancelled()
}

/**
 * Data seam between the TV controller and hermes-webui. Implemented by
 * [RepositoryTvInteractiveGateway] on device; unit tests substitute a fake to exercise card
 * flows without HTTP.
 */
interface TvInteractiveGateway {
    fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice, yolo: Boolean = false): InteractiveRespondResult
    fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult
    fun fetchPendingApproval(sessionId: String): PendingApprovalLookup
    fun fetchPendingClarify(sessionId: String): PendingClarifyLookup
}

/**
 * Drives the TV approval/clarify cards for the chat stream, mirroring hermes-webui semantics
 * (static/messages.js respondApproval/respondClarify/dismissApprovalCard) and the proven mobile
 * [InteractiveRequestController] contract:
 *
 * - Approval: single choice out of once/session/always/deny (options gated by `allow_session` /
 *   `allow_permanent`, see [TvInteractiveCardSpec.approvalOptions]); queued prompts
 *   (`pending_count > 1`) are shown one by one — after an accepted response the next head is
 *   re-fetched via `GET /api/approval/pending`, exactly like the web does after `stale_cleared`.
 * - Clarify: single-select from `choices_offered` or free text; EMPTY choices degrade to the
 *   free-text editor (open question); the trimmed answer is echoed via [onClarifyAnswered] so
 *   the transcript shows it (web pushes a user message).
 * - Cancellation: dismissing a card NEVER posts a response — the agent keeps waiting and the
 *   server-side timeout decides (silence is not consent; web `dismissApprovalCard` parity).
 * - Errors: rejected/network failures keep the card open with re-enabled controls and surface the
 *   server message inline; clarify HTTP 409 (stale) is terminal — the card is dismissed with a notice.
 * - Races: a response result is ignored when the card it belongs to was superseded by a newer SSE
 *   event or dismissed in flight (identity guard, mirroring `_approvalResponseOwnerIsCurrent`).
 * - Terminal stream events ([ChatEvent.Done], [ChatEvent.Error]) dismiss both cards: prompts
 *   cannot outlive their stream — the turn returns to the plain transcript.
 *
 * Secret hygiene: the controller never sees the access token (the gateway holds it) and never logs
 * payloads; only user-visible strings are forwarded to [TvInteractiveSurface].
 *
 * Threading: [onEvent] and the listener callbacks arrive on the UI thread. Gateway HTTP runs on
 * [executor] — and NOTHING else does. Every background result is handed to [uiDispatcher] before
 * any state is touched: the identity guard, the generation guard and all controller fields are
 * confined to the dispatcher thread, so a late executor callback can never mutate state or
 * resurface a card from the background.
 *
 * Lifecycle: the host calls [dismissAll] on stream cancel and «Новый чат» ([dismissAll] also
 * invalidates every in-flight background result via the generation counter), and [destroy] from
 * `onDestroy` — after [destroy] all late callbacks and events are ignored.
 *
 * Wiring (see TvChatActivity#onCreate):
 * ```
 * val surface = TvChatInteractiveSurface(messagesContainer) { input.requestFocus() }
 * val controller = TvInteractiveController(
 *     surface = surface,
 *     gateway = RepositoryTvInteractiveGateway(InteractiveRequestsRepository(), BASE_URL) { store.read() },
 *     sessionProvider = { sessionFlow.sessionId },
 *     uiDispatcher = MainThreadTvDispatcher(),
 * ).also { surface.listener = it }
 * controller.onClarifyAnswered = { answer -> append("Вы", answer, isUser = true) }
 * ```
 * In the stream collect loop feed every event to BOTH `sessionFlow.onEvent(event)` and
 * `controller.onEvent(event)` — approval/clarify must never fall into a silent `else -> Unit`.
 */
class TvInteractiveController(
    private val surface: TvInteractiveSurface,
    private val gateway: TvInteractiveGateway,
    private val sessionProvider: () -> String?,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) : TvInteractiveListener {

    // All mutable fields below are confined to the UI dispatcher thread: they are written from
    // onEvent/listener callbacks (UI thread by contract) and from dispatched result blocks.
    private var currentApproval: ChatEvent.ApprovalRequired? = null
    private var currentClarify: ChatEvent.ClarifyRequired? = null
    private var approvalResponding = false
    private var clarifyResponding = false

    /**
     * Bumped by every [dismissAll] (cancel / new chat / done / error / destroy). Background work
     * captures the generation it was started under; if it changed by the time the result is
     * dispatched, the result is dropped — a prompt belonging to a dead stream must never resurface
     * a card. Volatile only for the best-effort pre-flight check on the executor thread; the
     * authoritative guard is re-evaluated on the dispatcher thread.
     */
    @Volatile
    private var generation = 0

    /** Set by [destroy]; ignores every future event, callback and dispatched result. */
    @Volatile
    private var destroyed = false

    /**
     * Optional echo hook: invoked with the accepted clarify answer ON THE UI DISPATCHER THREAD
     * (web shows it as a user message; the host appends it to the transcript).
     */
    var onClarifyAnswered: ((String) -> Unit)? = null

    /** True while an approval or clarify card is open — the host keeps the composer locked. */
    val hasOpenPrompt: Boolean get() = currentApproval != null || currentClarify != null

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

    private fun showApproval(event: ChatEvent.ApprovalRequired) {
        val current = currentApproval
        if (current === event) return
        // Dedup guard: SSE re-push / pending re-fetch of the same queued entry must not rebuild
        // an already open card (web: `_renderPendingApprovalForActiveSession` same-id check).
        val id = event.payload.approvalId
        if (id != null && current?.payload?.approvalId == id) return
        currentApproval = event
        approvalResponding = false
        surface.setApprovalResponding(false)
        surface.showApproval(event.payload)
    }

    private fun showClarify(event: ChatEvent.ClarifyRequired) {
        val current = currentClarify
        if (current === event) return
        val id = event.payload.clarifyId
        if (id != null && current?.payload?.clarifyId == id) return
        currentClarify = event
        clarifyResponding = false
        surface.setClarifyResponding(false)
        surface.showClarify(event.payload)
    }

    override fun onApprovalChoice(choice: ApprovalChoice, yolo: Boolean) {
        if (destroyed) return
        val current = currentApproval ?: return
        if (approvalResponding) return
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) {
            surface.showCardError(NO_SESSION)
            return
        }
        approvalResponding = true
        surface.setApprovalResponding(true)
        val gen = generation
        executor.execute {
            // Best-effort pre-flight: a cancel/destroy that landed after the activation must not
            // fire the respond call at all (the authoritative guards run on the dispatcher below).
            if (destroyed || gen != generation) return@execute
            val result = gateway.respondApproval(sid, current.payload, choice, yolo)
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                // Identity guard: the card was superseded or dismissed while the call was in
                // flight. Evaluated on the dispatcher thread, together with the state it protects.
                if (currentApproval !== current) return@dispatch
                when (result) {
                    is InteractiveRespondResult.Accepted -> {
                        clearApproval()
                        surface.dismissApproval()
                        // Queue parity with web: after `stale_cleared` or a multi-entry queue,
                        // pull the next head so the agent is never left blocked by an unseen prompt.
                        if (result.staleCleared || current.payload.pendingCount > 1) showNextPendingApproval(sid)
                    }
                    is InteractiveRespondResult.Stale -> {
                        clearApproval()
                        surface.dismissApproval()
                        surface.showCardNotice(result.message)
                    }
                    is InteractiveRespondResult.Rejected -> {
                        // Keep the card open with re-enabled controls so the user can retry;
                        // surface the server message inline.
                        approvalResponding = false
                        surface.setApprovalResponding(false)
                        surface.showCardError(result.message)
                    }
                    is InteractiveRespondResult.NetworkError -> {
                        approvalResponding = false
                        surface.setApprovalResponding(false)
                        surface.showCardError(NETWORK_FAILURE)
                    }
                }
            }
        }
    }

    override fun onApprovalCancelled() {
        if (destroyed) return
        if (currentApproval == null) return // no card — nothing to cancel (idempotent)
        // Deliberately no respond call: cancelling hides the prompt without deciding (web parity,
        // silence ≠ consent). The server-side timeout keeps blocking the agent.
        clearApproval()
        surface.setApprovalResponding(false)
        surface.dismissApproval()
    }

    override fun onClarifyResponse(rawText: String) {
        if (destroyed) return
        val current = currentClarify ?: return
        if (clarifyResponding) return
        val text = rawText.trim()
        if (text.isEmpty()) return // Nothing to send; the card stays open (web: focus input, no-op).
        val sid = current.payload.sessionId?.takeIf { it.isNotBlank() } ?: sessionProvider()
        if (sid.isNullOrBlank()) {
            surface.showCardError(NO_SESSION)
            return
        }
        clarifyResponding = true
        surface.setClarifyResponding(true)
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
                        surface.dismissClarify()
                        // Echo on the dispatcher (UI) thread — the host appends it to the transcript.
                        onClarifyAnswered?.invoke(text)
                        if (current.payload.pendingCount > 1) showNextPendingClarify(sid)
                    }
                    is InteractiveRespondResult.Stale -> {
                        clearClarify()
                        surface.dismissClarify()
                        surface.showCardNotice(result.message)
                    }
                    is InteractiveRespondResult.Rejected -> {
                        clarifyResponding = false
                        surface.setClarifyResponding(false)
                        surface.showCardError(result.message)
                    }
                    is InteractiveRespondResult.NetworkError -> {
                        clarifyResponding = false
                        surface.setClarifyResponding(false)
                        surface.showCardError(NETWORK_FAILURE)
                    }
                }
            }
        }
    }

    override fun onClarifyCancelled() {
        if (destroyed) return
        if (currentClarify == null) return // no card — nothing to cancel (idempotent)
        clearClarify()
        surface.setClarifyResponding(false)
        surface.dismissClarify()
    }

    /**
     * Cleanup hook: dismiss everything without responding (stream cancel/done/error, «Новый чат»).
     * Invalidates all in-flight background work via [generation] — results for the dead
     * stream/session are dropped when they arrive. The controller stays usable afterwards.
     */
    fun dismissAll() {
        generation++
        clearApproval()
        clearClarify()
        surface.setApprovalResponding(false)
        surface.setClarifyResponding(false)
        surface.dismissApproval()
        surface.dismissClarify()
    }

    /**
     * Terminal hook (host `onDestroy`): dismiss everything and ignore every future event,
     * listener callback and late background result. No card can reappear after this.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        dismissAll()
    }

    private fun showNextPendingApproval(sid: String) {
        val gen = generation
        executor.execute {
            if (destroyed || gen != generation) return@execute
            val lookup = gateway.fetchPendingApproval(sid)
            uiDispatcher.dispatch {
                // The queue head belongs to the generation that ordered the fetch: a cancel or
                // destroy in between invalidates it (late-callback guard).
                if (destroyed || gen != generation) return@dispatch
                if (currentApproval != null) return@dispatch // A newer SSE event already took over the card.
                val found = lookup as? PendingApprovalLookup.Found ?: return@dispatch
                currentApproval = ChatEvent.ApprovalRequired(found.payload)
                approvalResponding = false
                surface.setApprovalResponding(false)
                surface.showApproval(found.payload)
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
                surface.setClarifyResponding(false)
                surface.showClarify(found.payload)
            }
        }
    }

    private fun clearApproval() {
        currentApproval = null
        approvalResponding = false
    }

    private fun clearClarify() {
        currentClarify = null
        clarifyResponding = false
    }

    companion object {
        const val NO_SESSION = "Нет активной сессии — ответ не отправлен."
        const val NETWORK_FAILURE = "Сеть недоступна — попробуйте ещё раз."

        // Shared daemon executor: single-thread pool for respond calls, no Activity lifecycle
        // cleanup required (daemon threads die with the process). Late results are neutralised
        // by the generation/identity guards, not by killing the executor.
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-interactive-requests").apply { isDaemon = true }
        }
    }
}
