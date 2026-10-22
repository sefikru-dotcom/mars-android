package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ru.mars.mobile.InteractiveDialogSpec.ClarifyAction
import ru.mars.mobile.InteractiveDialogSpec.ClarifyMode
import ru.mars.mobile.InteractiveDialogSpec.ClarifyOption
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * Custom Mars-themed dialog implementation of [InteractiveRequestView] — a full replacement of
 * the stock Android AlertDialog. Both prompts are hand-built obsidian modals ([MarsDialog]):
 *
 * - approval → card with a ruby header ("Требуется подтверждение" + queue badge), description,
 *   the command in a clean monospace code block (tool · risk caption, no emojis), pattern-key
 *   pills and tiered decision buttons: ruby primary "Разрешить один раз", quiet secondary
 *   session/always grants and a ruby-tinted destructive "Отклонить";
 * - clarify → question + choice pills; the trailing accent pill "Свой ответ" swaps the dialog
 *   in place into a free-text editor (obsidian field + ruby submit + quiet back to presets)
 *   instead of stacking a second system dialog.
 *
 * Respond failures surface inline in the open dialog (ruby error strip) instead of a bare toast;
 * the toast remains only as a fallback when no dialog is open. Controls lock while a respond
 * call is in flight ([MarsDialog.setResponding]) and BACK is blocked mid-flight so an in-progress
 * answer cannot be cancelled into a half-state.
 *
 * Both dialogs are non-cancelable on outside touch; BACK cancels and routes to the listener's
 * dismissed callbacks (no respond is sent — silence ≠ consent). Set [listener] right after
 * constructing the controller (see the wiring snippet in [InteractiveRequestController]).
 */
class MarsInteractiveView(private val activity: Activity) : InteractiveRequestView {
    var listener: InteractiveRequestListener? = null

    /** Supplies the session's current autonomy state; the approval dialog hides the autonomy grant when on. */
    var autonomyEnabledProvider: () -> Boolean = { false }

    private var approvalDialog: Dialog? = null
    private var approvalControls: List<View> = emptyList()
    private var approvalError: TextView? = null
    private var approvalResponding = false
    /** In-flight progress row ("Отправка…") and the retry action of the current approval card. */
    private var approvalProgress: View? = null
    private var approvalRetry: Button? = null
    private var approvalRetryAttempt: ApprovalAttempt? = null
    private var approvalPayload: ApprovalPayload? = null

    private var clarifyDialog: Dialog? = null
    private var clarifyControls: List<View> = emptyList()
    private var clarifyError: TextView? = null
    private var clarifyResponding = false
    /** Last shown clarify payload: reused when the free-text editor navigates back to presets. */
    private var currentClarifyPayload: ClarifyPayload? = null
    private var clarifyProgress: View? = null
    private var clarifyRetry: Button? = null
    private var clarifyRetryAnswer: String? = null

    override fun showApproval(payload: ApprovalPayload) = onUi {
        dismissDialog(approvalDialog); approvalDialog = null
        approvalError = null; approvalControls = emptyList(); approvalResponding = false
        approvalProgress = null; approvalRetry = null; approvalRetryAttempt = null
        approvalPayload = payload
        approvalDialog = MarsDialog.show(activity, onCancel = { listener?.onApprovalDismissed() }) { root ->
            buildApproval(root, payload)
        }
    }

    override fun showClarify(payload: ClarifyPayload) = onUi {
        dismissDialog(clarifyDialog); clarifyDialog = null
        clarifyError = null; clarifyControls = emptyList(); clarifyResponding = false
        clarifyProgress = null; clarifyRetry = null; clarifyRetryAnswer = null
        currentClarifyPayload = payload
        showClarifyMode(InteractiveDialogSpec.clarifyMode(payload))
    }

    override fun setApprovalResponding(responding: Boolean) = onUi {
        approvalResponding = responding
        approvalDialog?.setCancelable(!responding)
        MarsDialog.setResponding(approvalControls, responding)
        // Sending indicator and the retry action are mutually exclusive with the in-flight call.
        MarsDialog.setProgress(approvalProgress, responding)
        renderApprovalRetry()
    }

    override fun setClarifyResponding(responding: Boolean) = onUi {
        clarifyResponding = responding
        clarifyDialog?.setCancelable(!responding)
        MarsDialog.setResponding(clarifyControls, responding)
        MarsDialog.setProgress(clarifyProgress, responding)
        renderClarifyRetry()
    }

    override fun setApprovalRetry(attempt: ApprovalAttempt?) = onUi {
        approvalRetryAttempt = attempt
        renderApprovalRetry()
    }

    override fun setClarifyRetry(answer: String?) = onUi {
        clarifyRetryAnswer = answer
        renderClarifyRetry()
    }

    override fun dismissApproval() = onUi {
        dismissDialog(approvalDialog); approvalDialog = null
        approvalControls = emptyList(); approvalError = null; approvalResponding = false
        approvalProgress = null; approvalRetry = null; approvalRetryAttempt = null
        approvalPayload = null
    }

    override fun dismissClarify() = onUi {
        dismissDialog(clarifyDialog); clarifyDialog = null
        clarifyControls = emptyList(); clarifyError = null; clarifyResponding = false
        currentClarifyPayload = null
        clarifyProgress = null; clarifyRetry = null; clarifyRetryAnswer = null
    }

    /** Inline ruby strip inside the open dialog; toast only as a fallback when none is open. */
    override fun showError(message: String) = onUi {
        val strip = when {
            approvalDialog?.isShowing == true -> approvalError
            clarifyDialog?.isShowing == true -> clarifyError
            else -> null
        }
        if (strip != null) MarsDialog.setError(strip, message) else toast(message)
    }

    override fun showNotice(message: String) = onUi { toast(message) }

    /** Call from Activity#onDestroy to avoid window-leaked warnings. */
    fun dismissAllNow() {
        dismissDialog(approvalDialog); approvalDialog = null
        dismissDialog(clarifyDialog); clarifyDialog = null
        approvalControls = emptyList(); clarifyControls = emptyList()
        approvalError = null; clarifyError = null; currentClarifyPayload = null
        approvalProgress = null; approvalRetry = null; approvalRetryAttempt = null; approvalPayload = null
        clarifyProgress = null; clarifyRetry = null; clarifyRetryAnswer = null
    }

    // --- approval ------------------------------------------------------------------------------

    private fun buildApproval(root: LinearLayout, payload: ApprovalPayload): List<View> {
        root.addView(MarsDialog.header(
            activity,
            InteractiveDialogSpec.APPROVAL_TITLE,
            InteractiveDialogSpec.pendingBadge(payload.pendingCount),
        ))
        InteractiveDialogSpec.approvalDescription(payload)
            .takeIf { it.isNotEmpty() }
            ?.let { root.addView(MarsDialog.paragraph(activity, it)) }
        MarsDialog.codeBlock(
            activity,
            InteractiveDialogSpec.approvalCodeLabel(payload),
            InteractiveDialogSpec.approvalCodeBlock(payload),
        )?.let { root.addView(it) }
        val keys = InteractiveDialogSpec.approvalPatternKeys(payload)
        if (keys.isNotEmpty()) {
            root.addView(MarsDialog.caption(activity, InteractiveDialogSpec.PATTERN_PREFIX))
            root.addView(MarsDialog.pillRow(activity, keys))
        }
        val error = MarsDialog.errorStrip(activity)
        approvalError = error
        root.addView(MarsDialog.block(activity, error, bottomDp = 12))

        // Sending indicator (hidden until a respond is in flight) and the retry action that
        // re-sends the retained choice after a transport failure (hidden until one fails).
        val progress = MarsDialog.progressRow(activity)
        approvalProgress = progress
        root.addView(progress)
        val retry = MarsDialog.retryButton(activity, InteractiveDialogSpec.RETRY_PREFIX) { onApprovalRetryTap() }
        retry.visibility = View.GONE
        approvalRetry = retry
        root.addView(MarsDialog.block(activity, retry, bottomDp = 8))

        val controls = mutableListOf<View>()
        InteractiveDialogSpec.approvalOptions(payload, autonomyEnabledProvider()).forEach { option ->
            // Style comes from the shared card mapping, so the rendered roles and the tested
            // UI-state model can never drift apart.
            val button = when (InteractiveCardPresenter.approvalOptionKind(option)) {
                InteractiveOptionKind.DESTRUCTIVE ->
                    MarsDialog.denyButton(activity, option.label) { onApprovalTap(option.choice, option.grantsAutonomy) }
                InteractiveOptionKind.PRIMARY ->
                    MarsDialog.primaryButton(activity, option.label) { onApprovalTap(option.choice, option.grantsAutonomy) }
                else ->
                    MarsDialog.secondaryButton(activity, option.label) { onApprovalTap(option.choice, option.grantsAutonomy) }
            }
            controls += button
            root.addView(MarsDialog.block(activity, button, bottomDp = 8))
        }
        approvalControls = controls
        return controls
    }

    private fun onApprovalTap(choice: ApprovalChoice, grantsAutonomy: Boolean = false) {
        if (approvalResponding) return
        approvalError?.let { MarsDialog.setError(it, null) }
        listener?.onApprovalChoice(choice, grantsAutonomy)
    }

    private fun onApprovalRetryTap() {
        if (approvalResponding) return
        approvalError?.let { MarsDialog.setError(it, null) }
        listener?.onApprovalRetry()
    }

    /**
     * Retry label carries the retained decision ("Повторить: Разрешить один раз") so the user
     * re-sends exactly what they chose — never a guess. Hidden while the respond is in flight
     * (the progress row replaces it) and when nothing is retained.
     */
    private fun renderApprovalRetry() {
        val button = approvalRetry ?: return
        val label = if (!approvalResponding) {
            InteractiveCardPresenter.approvalRetryLabel(
                approvalPayload,
                autonomyEnabledProvider(),
                approvalRetryAttempt,
            )
        } else null
        button.text = label
        button.visibility = if (label != null) View.VISIBLE else View.GONE
    }

    private fun renderClarifyRetry() {
        val button = clarifyRetry ?: return
        val label = if (!clarifyResponding) {
            InteractiveCardPresenter.clarifyRetryLabel(clarifyRetryAnswer)
        } else null
        button.text = label
        button.visibility = if (label != null) View.VISIBLE else View.GONE
    }

    private fun onClarifyRetryTap() {
        if (clarifyResponding) return
        clarifyError?.let { MarsDialog.setError(it, null) }
        listener?.onClarifyRetry()
    }

    // --- clarify ------------------------------------------------------------------------------

    private fun showClarifyMode(mode: ClarifyMode) {
        val payload = currentClarifyPayload ?: return
        dismissDialog(clarifyDialog); clarifyDialog = null
        clarifyError = null; clarifyControls = emptyList()
        val responding = clarifyResponding
        clarifyDialog = MarsDialog.show(activity, onCancel = { listener?.onClarifyDismissed() }) { root ->
            val controls = when (mode) {
                is ClarifyMode.Presets -> buildClarifyPresets(root, mode.options, payload)
                is ClarifyMode.FreeText -> buildClarifyFreeText(root, mode.presets, payload)
            }
            MarsDialog.setResponding(controls, responding)
            controls
        }.also { it.setCancelable(!responding) }
        applyClarifyState()
    }

    /** Re-applies in-flight/retry state to the freshly rebuilt clarify dialog. */
    private fun applyClarifyState() {
        MarsDialog.setProgress(clarifyProgress, clarifyResponding)
        renderClarifyRetry()
    }

    private fun buildClarifyPresets(
        root: LinearLayout,
        options: List<ClarifyOption>,
        payload: ClarifyPayload,
    ): List<View> {
        root.addView(clarifyHeader(payload))
        InteractiveDialogSpec.clarifyQuestion(payload)
            .takeIf { it.isNotEmpty() }
            ?.let { root.addView(MarsDialog.paragraph(activity, it)) }
        root.addView(MarsDialog.block(activity, clarifyErrorStrip(), bottomDp = 12))

        val pills = options.map { option ->
            option.label to { onClarifyOption(ClarifyMode.Presets(options), option) }
        }
        val stack = MarsDialog.pillStack(activity, pills, accentLast = true)
        root.addView(stack)
        addClarifyStatusRows(root)
        val controls = collectClickables(stack)
        clarifyControls = controls
        return controls
    }

    /** Sending indicator + retry action shared by both clarify modes (presets / free text). */
    private fun addClarifyStatusRows(root: LinearLayout) {
        val progress = MarsDialog.progressRow(activity)
        clarifyProgress = progress
        root.addView(progress)
        val retry = MarsDialog.retryButton(activity, InteractiveDialogSpec.RETRY_PREFIX) { onClarifyRetryTap() }
        retry.visibility = View.GONE
        clarifyRetry = retry
        root.addView(MarsDialog.block(activity, retry, bottomDp = 8))
    }

    private fun buildClarifyFreeText(
        root: LinearLayout,
        presets: List<String>,
        payload: ClarifyPayload,
    ): List<View> {
        root.addView(clarifyHeader(payload))
        InteractiveDialogSpec.clarifyQuestion(payload)
            .takeIf { it.isNotEmpty() }
            ?.let { root.addView(MarsDialog.paragraph(activity, it)) }
        root.addView(MarsDialog.block(activity, clarifyErrorStrip(), bottomDp = 12))

        val input = MarsDialog.textField(activity)
        root.addView(MarsDialog.block(activity, input, bottomDp = 12))
        val submit = MarsDialog.primaryButton(activity, InteractiveDialogSpec.SUBMIT_LABEL) {
            submitFreeText(input)
        }
        val backLabel = if (presets.isEmpty()) InteractiveDialogSpec.CANCEL_LABEL else InteractiveDialogSpec.BACK_LABEL
        val back = MarsDialog.quietButton(activity, backLabel) {
            if (clarifyResponding) return@quietButton
            if (presets.isEmpty()) {
                clarifyDialog?.cancel() ?: listener?.onClarifyDismissed()
            } else {
                showClarifyMode(
                    ClarifyMode.Presets(presets.map { ClarifyOption.Preset(it) } + ClarifyOption.OwnAnswer)
                )
            }
        }
        root.addView(MarsDialog.buttonRow(activity, submit, back))
        addClarifyStatusRows(root)
        val controls = listOf<View>(input, submit, back)
        clarifyControls = controls
        input.requestFocus()
        return controls
    }

    private fun clarifyHeader(payload: ClarifyPayload): View = MarsDialog.header(
        activity,
        InteractiveDialogSpec.CLARIFY_TITLE,
        InteractiveDialogSpec.pendingBadge(payload.pendingCount),
    )

    private fun clarifyErrorStrip(): TextView =
        MarsDialog.errorStrip(activity).also { clarifyError = it }

    private fun onClarifyOption(mode: ClarifyMode, option: ClarifyOption) {
        if (clarifyResponding) return
        when (val action = InteractiveDialogSpec.onClarifyOptionTapped(mode, option)) {
            is ClarifyAction.Respond -> {
                clarifyError?.let { MarsDialog.setError(it, null) }
                listener?.onClarifyResponse(action.text)
            }
            is ClarifyAction.SwitchToFreeText -> showClarifyMode(ClarifyMode.FreeText(action.presets))
            ClarifyAction.None -> Unit
        }
    }

    private fun submitFreeText(input: EditText) {
        if (clarifyResponding) return
        val answer = InteractiveDialogSpec.clarifyFreeTextSubmit(input.text.toString()) ?: return
        clarifyError?.let { MarsDialog.setError(it, null) }
        listener?.onClarifyResponse(answer)
    }

    // --- plumbing ------------------------------------------------------------------------------

    private fun onUi(block: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread(block)
    }

    private fun dismissDialog(dialog: Dialog?) {
        if (dialog?.isShowing == true) runCatching { dialog.dismiss() }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun collectClickables(view: View): List<View> {
        val out = mutableListOf<View>()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += collectClickables(view.getChildAt(i))
        } else if (view.isClickable) {
            out += view
        }
        return out
    }
}
