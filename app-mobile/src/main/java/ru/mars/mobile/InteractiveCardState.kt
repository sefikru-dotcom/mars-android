package ru.mars.mobile

import ru.mars.mobile.InteractiveDialogSpec.ClarifyOption
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * Immutable UI state of an approval/clarify card, derived from the payload plus the phase of the
 * respond call. Pure Kotlin (no Android framework) so the whole "what does the card look like
 * now" decision is unit-testable on the JVM — [MarsInteractiveView] only maps this state onto
 * views.
 *
 * The card has exactly one of three shapes:
 * - [InteractiveCardPhase.Idle] — options enabled, no progress, no error;
 * - [InteractiveCardPhase.Sending] — every option disabled (a decision is in flight) and the
 *   indeterminate "Отправка…" row visible, so a slow network never looks like a dead dialog;
 * - [InteractiveCardPhase.Failed] — error strip visible; when the failure is transport-ish the
 *   retained choice is offered back as a single retry action ("Повторить: <label>"), so the
 *   user does not have to find and re-tap the same button.
 */
data class InteractiveCardState(
    val options: List<InteractiveOptionState>,
    val sending: Boolean,
    /** Visible while [sending]; null hides the progress row. */
    val sendingLabel: String?,
    /** Inline error strip text; null hides the strip. */
    val errorMessage: String?,
    /** Label of the retry action; null hides it (nothing retained or the failure is terminal). */
    val retryLabel: String?,
) {
    val retryVisible: Boolean get() = retryLabel != null

    /** Options are tappable only while nothing is in flight. */
    val optionsEnabled: Boolean get() = !sending
}

/** One tappable decision/answer with its visual role. */
data class InteractiveOptionState(
    val label: String,
    val kind: InteractiveOptionKind,
    val enabled: Boolean,
)

/** Visual role of an option: drives the button style in [MarsInteractiveView]. */
enum class InteractiveOptionKind {
    /** Ruby primary action (approve once / submit). */
    PRIMARY,

    /** Quiet secondary action (session/always grant, autonomy grant). */
    SECONDARY,

    /** Destructive action (deny) — ruby-tinted quiet button. */
    DESTRUCTIVE,

    /** Clarify preset pill. */
    PRESET,

    /** Clarify "own answer" pill: switches the card into the free-text editor. */
    OWN_ANSWER,
}

/** Phase of the respond call behind a card. */
sealed interface InteractiveCardPhase {
    data object Idle : InteractiveCardPhase

    /** A respond POST is in flight (options locked, progress row visible). */
    data object Sending : InteractiveCardPhase

    /**
     * The respond call failed. [retryable] comes from
     * [ru.mars.network.repository.InteractiveRetryPolicy] — a terminal failure shows the message
     * without a retry action.
     */
    data class Failed(val message: String, val retryable: Boolean) : InteractiveCardPhase
}

/**
 * The choice the user already submitted and that may be re-sent unchanged. Retained by
 * [InteractiveRequestController] across a failed respond call — that is what "не терять выбор"
 * means concretely: the retry posts the same [ApprovalChoice]/answer, never a re-typed guess.
 */
data class ApprovalAttempt(
    val choice: ApprovalChoice,
    val grantsAutonomy: Boolean = false,
)

/** Maps payload + phase into [InteractiveCardState]. No side effects, no Android types. */
object InteractiveCardPresenter {

    /** Longest clarify answer echoed inside the retry label (a free-text answer can be huge). */
    const val RETRY_ANSWER_MAX_CHARS = 24

    fun approval(
        payload: ApprovalPayload,
        autonomyEnabled: Boolean,
        phase: InteractiveCardPhase,
        retained: ApprovalAttempt?,
    ): InteractiveCardState {
        val options = InteractiveDialogSpec.approvalOptions(payload, autonomyEnabled).map { option ->
            InteractiveOptionState(
                label = option.label,
                kind = approvalOptionKind(option),
                enabled = phase !is InteractiveCardPhase.Sending,
            )
        }
        return card(
            options = options,
            phase = phase,
            retainedLabel = retained?.let { approvalOptionLabel(payload, autonomyEnabled, it) },
        )
    }

    /** Visual role of an approval option — the single mapping used by the card and its tests. */
    fun approvalOptionKind(option: InteractiveDialogSpec.ApprovalOption): InteractiveOptionKind = when {
        InteractiveDialogSpec.isDeny(option) -> InteractiveOptionKind.DESTRUCTIVE
        option.choice == ApprovalChoice.ONCE && !option.grantsAutonomy -> InteractiveOptionKind.PRIMARY
        else -> InteractiveOptionKind.SECONDARY
    }

    fun clarify(
        payload: ClarifyPayload,
        phase: InteractiveCardPhase,
        retainedAnswer: String?,
    ): InteractiveCardState {
        val options = InteractiveDialogSpec.clarifyOptions(payload).map { option ->
            InteractiveOptionState(
                label = option.label,
                kind = if (option is ClarifyOption.OwnAnswer) InteractiveOptionKind.OWN_ANSWER else InteractiveOptionKind.PRESET,
                enabled = phase !is InteractiveCardPhase.Sending,
            )
        }
        return card(
            options = options,
            phase = phase,
            retainedLabel = retainedAnswer?.let { clarifyAnswerLabel(it) },
        )
    }

    /** Label of the retained approval option, or null when that option is no longer offered. */
    fun approvalOptionLabel(
        payload: ApprovalPayload,
        autonomyEnabled: Boolean,
        attempt: ApprovalAttempt,
    ): String? = InteractiveDialogSpec.approvalOptions(payload, autonomyEnabled)
        .firstOrNull { it.choice == attempt.choice && it.grantsAutonomy == attempt.grantsAutonomy }
        ?.label

    /** Retry label echoing the retained clarify answer (trimmed, clipped, quoted). */
    fun clarifyAnswerLabel(answer: String): String? {
        val text = answer.trim()
        if (text.isEmpty()) return null
        val clipped = if (text.length > RETRY_ANSWER_MAX_CHARS) {
            text.take(RETRY_ANSWER_MAX_CHARS).trimEnd() + "…"
        } else {
            text
        }
        return "«$clipped»"
    }

    /** Full retry action label for a retained approval decision; null when nothing to re-send. */
    fun approvalRetryLabel(
        payload: ApprovalPayload?,
        autonomyEnabled: Boolean,
        attempt: ApprovalAttempt?,
    ): String? {
        if (payload == null || attempt == null) return null
        val option = approvalOptionLabel(payload, autonomyEnabled, attempt) ?: return null
        return retryLabel(option)
    }

    /** Full retry action label for a retained clarify answer; null when nothing to re-send. */
    fun clarifyRetryLabel(answer: String?): String? {
        val quoted = answer?.let { clarifyAnswerLabel(it) } ?: return null
        return retryLabel(quoted)
    }

    private fun card(
        options: List<InteractiveOptionState>,
        phase: InteractiveCardPhase,
        retainedLabel: String?,
    ): InteractiveCardState = when (phase) {
        InteractiveCardPhase.Idle -> InteractiveCardState(
            options = options,
            sending = false,
            sendingLabel = null,
            errorMessage = null,
            retryLabel = null,
        )
        InteractiveCardPhase.Sending -> InteractiveCardState(
            options = options,
            sending = true,
            sendingLabel = InteractiveDialogSpec.SENDING_LABEL,
            errorMessage = null,
            retryLabel = null,
        )
        is InteractiveCardPhase.Failed -> InteractiveCardState(
            options = options,
            sending = false,
            sendingLabel = null,
            errorMessage = phase.message,
            retryLabel = retainedLabel?.takeIf { phase.retryable }?.let { retryLabel(it) },
        )
    }

    private fun retryLabel(optionLabel: String): String =
        "${InteractiveDialogSpec.RETRY_PREFIX}: $optionLabel"
}
