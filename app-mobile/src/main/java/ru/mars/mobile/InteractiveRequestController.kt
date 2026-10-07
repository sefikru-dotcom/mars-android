package ru.mars.mobile

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Dialog surface for interactive attention requests. Implemented by [AlertDialogInteractiveView]
 * on device and by fakes in unit tests. All methods may be invoked from a background thread —
 * implementations must marshal to the UI thread themselves.
 */
interface InteractiveRequestView {
    fun showApproval(payload: ApprovalPayload)
    fun showClarify(payload: ClarifyPayload)
    /** Lock/unlock the approval dialog controls while a respond call is in flight. */
    fun setApprovalResponding(responding: Boolean)
    /** Lock/unlock the clarify dialog controls while a respond call is in flight. */
    fun setClarifyResponding(responding: Boolean)
    fun dismissApproval()
    fun dismissClarify()
    fun showError(message: String)
    fun showNotice(message: String)
}

/** User actions surfaced by [InteractiveRequestView] implementations back to the controller. */
interface InteractiveRequestListener {
    fun onApprovalChoice(choice: ApprovalChoice)
    /** User cancelled the approval dialog. MUST NOT be interpreted as a decision (silence ≠ consent). */
    fun onApprovalDismissed()
    fun onClarifyResponse(text: String)
    /** User cancelled the clarify dialog without answering. */
    fun onClarifyDismissed()
}

/**
 * Data seam between the controller and hermes-webui. Implemented by [RepositoryInteractiveGateway]
 * on device; unit tests substitute a fake to exercise dialog flows without HTTP.
 */
interface InteractiveRequestsGateway {
    fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice): InteractiveRespondResult
    fun respondClarify(sessionId: String, payload: ClarifyPayload, response: String): InteractiveRespondResult
    fun fetchPendingApproval(sessionId: String): PendingApprovalLookup
    fun fetchPendingClarify(sessionId: String): PendingClarifyLookup
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
    override fun respondApproval(sessionId: String, payload: ApprovalPayload, choice: ApprovalChoice): InteractiveRespondResult {
        val credential = credentialProvider() ?: return InteractiveRespondResult.Rejected(AUTH_REQUIRED, 0)
        return repository.respondApproval(baseUrl, credential, sessionId, payload, choice)
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
 * Threading: [onEvent] and the listener callbacks run on the UI thread; gateway calls run on
 * [executor]. View implementations marshal back to the UI thread.
 */
class InteractiveRequestController(
    private val view: InteractiveRequestView,
    private val gateway: InteractiveRequestsGateway,
    private val sessionProvider: () -> String?,
    private val state: ChatUiState? = null,
    private val executor: Executor = SHARED_EXECUTOR,
) : InteractiveRequestListener {

    private var currentApproval: ChatEvent.ApprovalRequired? = null
    private var currentClarify: ChatEvent.ClarifyRequired? = null
    private var approvalResponding = false
    private var clarifyResponding = false

    /** Optional echo hook: invoked with the accepted clarify answer (web shows it as a user message). */
    var onClarifyAnswered: ((String) -> Unit)? = null

    /** Feed every stream event here from the Activity's collect loop. */
    fun onEvent(event: ChatEvent) {
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
        // an already open dialog (web: `_renderPendingApprovalForActiveSession` same-id check).
        val id = event.payload.approvalId
        if (id != null && current?.payload?.approvalId == id) return
        currentApproval = event
        approvalResponding = false
        view.setApprovalResponding(false)
        view.showApproval(event.payload)
    }

    private fun showClarify(event: ChatEvent.ClarifyRequired) {
        val current = currentClarify
        if (current === event) return
        val id = event.payload.clarifyId
        if (id != null && current?.payload?.clarifyId == id) return
        currentClarify = event
        clarifyResponding = false
        view.setClarifyResponding(false)
        view.showClarify(event.payload)
    }

    override fun onApprovalChoice(choice: ApprovalChoice) {
        val current = currentApproval ?: return
        if (approvalResponding) return
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) {
            view.showError(NO_SESSION)
            return
        }
        approvalResponding = true
        view.setApprovalResponding(true)
        executor.execute {
            val result = gateway.respondApproval(sid, current.payload, choice)
            // Identity guard: dialog was superseded or dismissed while the call was in flight.
            if (currentApproval !== current) return@execute
            when (result) {
                is InteractiveRespondResult.Accepted -> {
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
                    approvalResponding = false
                    view.setApprovalResponding(false)
                    view.showError(result.message)
                }
                is InteractiveRespondResult.NetworkError -> {
                    approvalResponding = false
                    view.setApprovalResponding(false)
                    view.showError(NETWORK_FAILURE)
                }
            }
        }
    }

    override fun onApprovalDismissed() {
        // Deliberately no respond call: dismissing hides the prompt without deciding (web parity,
        // silence ≠ consent). The server-side timeout keeps blocking the agent.
        clearApproval()
        view.setApprovalResponding(false)
        view.dismissApproval()
    }

    override fun onClarifyResponse(rawText: String) {
        val current = currentClarify ?: return
        if (clarifyResponding) return
        val text = rawText.trim()
        if (text.isEmpty()) return // Nothing to send; the dialog stays open (web: focus input, no-op).
        val sid = current.payload.sessionId?.takeIf { it.isNotBlank() } ?: sessionProvider()
        if (sid.isNullOrBlank()) {
            view.showError(NO_SESSION)
            return
        }
        clarifyResponding = true
        view.setClarifyResponding(true)
        executor.execute {
            val result = gateway.respondClarify(sid, current.payload, text)
            if (currentClarify !== current) return@execute
            when (result) {
                is InteractiveRespondResult.Accepted -> {
                    clearClarify()
                    view.dismissClarify()
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
                }
                is InteractiveRespondResult.NetworkError -> {
                    clarifyResponding = false
                    view.setClarifyResponding(false)
                    view.showError(NETWORK_FAILURE)
                }
            }
        }
    }

    override fun onClarifyDismissed() {
        clearClarify()
        view.setClarifyResponding(false)
        view.dismissClarify()
    }

    /** Terminal hook: dismiss everything without responding (stream done/failed/cancelled, session switch). */
    fun dismissAll() {
        clearApproval()
        clearClarify()
        view.setApprovalResponding(false)
        view.setClarifyResponding(false)
        view.dismissApproval()
        view.dismissClarify()
    }

    private fun showNextPendingApproval(sid: String) {
        if (currentApproval != null) return // A newer SSE event already took over the dialog.
        val found = gateway.fetchPendingApproval(sid) as? PendingApprovalLookup.Found ?: return
        val next = ChatEvent.ApprovalRequired(found.payload)
        currentApproval = next
        approvalResponding = false
        view.setApprovalResponding(false)
        view.showApproval(found.payload)
    }

    private fun showNextPendingClarify(sid: String) {
        if (currentClarify != null) return
        val found = gateway.fetchPendingClarify(sid) as? PendingClarifyLookup.Found ?: return
        val next = ChatEvent.ClarifyRequired(found.payload)
        currentClarify = next
        clarifyResponding = false
        view.setClarifyResponding(false)
        view.showClarify(found.payload)
    }

    private fun clearApproval() {
        currentApproval = null
        approvalResponding = false
        state?.resolveApproval()
    }

    private fun clearClarify() {
        currentClarify = null
        clarifyResponding = false
        state?.resolveClarify()
    }

    companion object {
        const val NO_SESSION = "Нет активной сессии — ответ не отправлен."
        const val NETWORK_FAILURE = "Сеть недоступна — попробуйте ещё раз."

        // Shared daemon executor: default single-thread pool for respond calls, no Activity
        // lifecycle cleanup required (daemon threads die with the process).
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "interactive-requests").apply { isDaemon = true }
        }
    }
}

/**
 * Real dialog implementation for [InteractiveRequestView]:
 * - approval → single-select list dialog (once / session / always / deny, gated by payload flags)
 *   with the command and pattern keys in the message;
 * - clarify → choices list with a trailing "own answer" item that opens a free-text input dialog,
 *   or the free-text dialog directly when no choices were offered.
 *
 * Both dialogs are non-cancelable on outside touch; BACK cancels and routes to the listener's
 * dismissed callbacks (no respond is sent). Set [listener] right after constructing the
 * controller (see the wiring snippet in the class KDoc of [InteractiveRequestController]).
 */
class AlertDialogInteractiveView(private val activity: Activity) : InteractiveRequestView {
    var listener: InteractiveRequestListener? = null

    private var approvalDialog: AlertDialog? = null
    private var clarifyDialog: AlertDialog? = null

    override fun showApproval(payload: ApprovalPayload) = onUi {
        dismissDialog(approvalDialog); approvalDialog = null
        val options = buildList {
            add("Разрешить один раз" to ApprovalChoice.ONCE)
            if (payload.allowSession) add("Разрешить для этой сессии" to ApprovalChoice.SESSION)
            if (payload.allowPermanent) add("Разрешать всегда" to ApprovalChoice.ALWAYS)
            add("Отклонить" to ApprovalChoice.DENY)
        }
        val title = if (payload.pendingCount > 1) {
            "Требуется подтверждение · 1 из ${payload.pendingCount}"
        } else {
            "Требуется подтверждение"
        }
        approvalDialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(approvalMessage(payload))
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                listener?.onApprovalChoice(options[which].second)
            }
            .setOnCancelListener { listener?.onApprovalDismissed() }
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    override fun showClarify(payload: ClarifyPayload) = onUi {
        dismissDialog(clarifyDialog); clarifyDialog = null
        if (payload.choices.isEmpty()) {
            showClarifyFreeText(payload, initial = "")
            return@onUi
        }
        val items = (payload.choices + FREE_TEXT_ITEM).toTypedArray()
        clarifyDialog = AlertDialog.Builder(activity)
            .setTitle(CLARIFY_TITLE)
            .setMessage(payload.question.ifBlank { null })
            .setItems(items) { _, which ->
                if (which < payload.choices.size) {
                    listener?.onClarifyResponse(payload.choices[which])
                } else {
                    showClarifyFreeText(payload, initial = "")
                }
            }
            .setOnCancelListener { listener?.onClarifyDismissed() }
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun showClarifyFreeText(payload: ClarifyPayload, initial: String) {
        dismissDialog(clarifyDialog); clarifyDialog = null
        val input = EditText(activity).apply {
            setText(initial)
            hint = "Ваш ответ…"
            setSingleLine(false)
        }
        val container = FrameLayout(activity)
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(input)
        clarifyDialog = AlertDialog.Builder(activity)
            .setTitle(CLARIFY_TITLE)
            .setMessage(payload.question.ifBlank { null })
            .setView(container)
            .setPositiveButton("Отправить") { _, _ -> listener?.onClarifyResponse(input.text.toString()) }
            .setOnCancelListener { listener?.onClarifyDismissed() }
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    override fun setApprovalResponding(responding: Boolean) = onUi {
        approvalDialog?.listView?.isEnabled = !responding
        approvalDialog?.setCanceledOnTouchOutside(false)
    }

    override fun setClarifyResponding(responding: Boolean) = onUi {
        clarifyDialog?.listView?.isEnabled = !responding
        clarifyDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !responding
    }

    override fun dismissApproval() = onUi { dismissDialog(approvalDialog); approvalDialog = null }

    override fun dismissClarify() = onUi { dismissDialog(clarifyDialog); clarifyDialog = null }

    override fun showError(message: String) = onUi { toast(message) }

    override fun showNotice(message: String) = onUi { toast(message) }

    /** Call from Activity#onDestroy to avoid window-leaked warnings. */
    fun dismissAllNow() {
        dismissDialog(approvalDialog); approvalDialog = null
        dismissDialog(clarifyDialog); clarifyDialog = null
    }

    private fun approvalMessage(payload: ApprovalPayload): String = buildString {
        val detail = payload.description.ifBlank { payload.command }
        if (detail.isNotBlank()) append(detail)
        if (payload.command.isNotBlank() && payload.command != payload.description) {
            if (isNotEmpty()) append("\n\n")
            append(payload.command)
        }
        if (payload.patternKeys.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Шаблон: ").append(payload.patternKeys.joinToString(", "))
        }
    }

    private fun onUi(block: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread(block)
    }

    private fun dismissDialog(dialog: AlertDialog?) {
        if (dialog?.isShowing == true) runCatching { dialog.dismiss() }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val CLARIFY_TITLE = "Марс уточняет"
        const val FREE_TEXT_ITEM = "Свой ответ…"
    }
}
