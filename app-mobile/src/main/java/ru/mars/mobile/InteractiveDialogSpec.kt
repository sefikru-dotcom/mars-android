package ru.mars.mobile

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * Pure presentation logic of the custom Mars dialogs (no Android framework), shared between
 * [MarsInteractiveView] and unit tests: option lists, titles, code blocks and the
 * clarify mode transitions. Keeping it framework-free lets the dialog contract be tested
 * on the JVM without Robolectric.
 */
object InteractiveDialogSpec {

    /** One tappable decision in the approval dialog: label + the choice it submits. */
    data class ApprovalOption(val label: String, val choice: ApprovalChoice)

    /** One tappable answer in the clarify dialog: either a preset choice or the free-text trigger. */
    sealed interface ClarifyOption {
        val label: String
        data class Preset(override val label: String) : ClarifyOption
        data object OwnAnswer : ClarifyOption {
            override val label: String get() = OWN_ANSWER_LABEL
        }
    }

    /** What the clarify dialog shows right now: preset pills or the free-text editor. */
    sealed interface ClarifyMode {
        data class Presets(val options: List<ClarifyOption>) : ClarifyMode
        data class FreeText(val presets: List<String>) : ClarifyMode
    }

    const val APPROVAL_TITLE = "Требуется подтверждение"
    const val CLARIFY_TITLE = "Марс уточняет"
    const val OWN_ANSWER_LABEL = "Свой ответ"
    const val SUBMIT_LABEL = "Отправить"
    const val BACK_LABEL = "Назад"
    const val CANCEL_LABEL = "Отмена"
    const val ANSWER_HINT = "Ваш ответ…"
    const val PATTERN_PREFIX = "Шаблон"
    const val TOOL_PREFIX = "Инструмент"

    /** Approval options in display order; session/permanent gated by the payload flags. */
    fun approvalOptions(payload: ApprovalPayload): List<ApprovalOption> = buildList {
        add(ApprovalOption("Разрешить один раз", ApprovalChoice.ONCE))
        if (payload.allowSession) add(ApprovalOption("Разрешить для этой сессии", ApprovalChoice.SESSION))
        if (payload.allowPermanent) add(ApprovalOption("Разрешать всегда", ApprovalChoice.ALWAYS))
        add(ApprovalOption("Отклонить", ApprovalChoice.DENY))
    }

    /** Deny is visually separated from the grants: it is rendered as the destructive action. */
    fun isDeny(option: ApprovalOption): Boolean = option.choice == ApprovalChoice.DENY

    /** Header title with the queue indicator ("1 из N") when several prompts are pending. */
    fun approvalTitle(pendingCount: Int): String =
        if (pendingCount > 1) "$APPROVAL_TITLE · 1 из $pendingCount" else APPROVAL_TITLE

    /** Human description shown above the monospace command block (blank when absent). */
    fun approvalDescription(payload: ApprovalPayload): String = payload.description.trim()

    /**
     * Monospace block content: the command (or the tool label when the payload carries only a
     * tool call). Empty string means "no code block" — the dialog omits it entirely.
     */
    fun approvalCodeBlock(payload: ApprovalPayload): String {
        if (payload.command.isNotBlank()) return payload.command
        val tool = payload.tool?.trim().orEmpty()
        return if (tool.isNotEmpty()) tool else ""
    }

    /** Small caption above the code block: tool name or risk level when the payload has one. */
    fun approvalCodeLabel(payload: ApprovalPayload): String? = buildString {
        payload.tool?.takeIf { it.isNotBlank() }?.let { append(it) }
        payload.riskLevel?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(" · ")
            append(it.lowercase())
        }
    }.ifEmpty { null }

    /** Pattern-key pills; empty list hides the row. */
    fun approvalPatternKeys(payload: ApprovalPayload): List<String> =
        payload.patternKeys.map { it.trim() }.filter { it.isNotEmpty() }

    /** Queue counter text ("2 из 5") for the pending badge; null when only one prompt. */
    fun pendingBadge(pendingCount: Int): String? =
        if (pendingCount > 1) "1 из $pendingCount" else null

    /** Initial clarify mode: preset pills when choices were offered, free text otherwise. */
    fun clarifyMode(payload: ClarifyPayload): ClarifyMode =
        if (payload.choices.isEmpty()) {
            ClarifyMode.FreeText(emptyList())
        } else {
            ClarifyMode.Presets(clarifyOptions(payload))
        }

    /** Preset pills plus the trailing "own answer" trigger. */
    fun clarifyOptions(payload: ClarifyPayload): List<ClarifyOption> =
        payload.choices
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ClarifyOption.Preset(it) } + ClarifyOption.OwnAnswer

    /** Tapping a preset pill sends its label verbatim; the own-answer pill switches to the editor. */
    fun onClarifyOptionTapped(mode: ClarifyMode, option: ClarifyOption): ClarifyAction =
        when {
            option is ClarifyOption.OwnAnswer && mode is ClarifyMode.Presets ->
                ClarifyAction.SwitchToFreeText((mode.options.filterIsInstance<ClarifyOption.Preset>().map { it.label }))
            option is ClarifyOption.Preset -> ClarifyAction.Respond(option.label)
            else -> ClarifyAction.None
        }

    /** Result of tapping something in the clarify dialog. */
    sealed interface ClarifyAction {
        data class Respond(val text: String) : ClarifyAction
        data class SwitchToFreeText(val presets: List<String>) : ClarifyAction
        data object None : ClarifyAction
    }

    /** Free-text submit: trimmed; blank answers are ignored (dialog stays open, no respond). */
    fun clarifyFreeTextSubmit(raw: String): String? = raw.trim().ifEmpty { null }

    /** Clarify question as shown in the header (blank → header omitted). */
    fun clarifyQuestion(payload: ClarifyPayload): String = payload.question.trim()
}
