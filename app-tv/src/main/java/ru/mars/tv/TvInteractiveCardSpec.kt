package ru.mars.tv

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * Pure presentation contract of the TV interactive card (no Android framework), shared between
 * [TvInteractiveCard] and JVM unit tests. Mirrors the mobile `InteractiveDialogSpec` semantics
 * (option gating, queue badge, clarify modes) so both clients render the same decisions, but is
 * a TV-side file on purpose: app-tv must not depend on app-mobile.
 */
object TvInteractiveCardSpec {

    /** One focusable decision button on the approval card: label + the choice it submits. */
    data class ApprovalOption(
        val label: String,
        val choice: ApprovalChoice,
        /** True for the YOLO grant: submits `choice` with `yolo:true` (server toggles the session flag). */
        val yolo: Boolean = false,
    )

    /** One focusable answer on the clarify card: either a preset choice or the free-text trigger. */
    sealed interface ClarifyOption {
        val label: String
        data class Preset(override val label: String) : ClarifyOption
        data object OwnAnswer : ClarifyOption {
            override val label: String get() = OWN_ANSWER_LABEL
        }
    }

    /**
     * What the clarify card shows right now: preset chips or the free-text editor.
     * A payload with EMPTY (or all-blank) choices can only be answered via free text —
     * the card must never render an empty preset row.
     */
    sealed interface ClarifyMode {
        data class Presets(val options: List<ClarifyOption>) : ClarifyMode
        data class FreeText(val presets: List<String>) : ClarifyMode
    }

    /** Result of activating something on the clarify card. */
    sealed interface ClarifyAction {
        data class Respond(val text: String) : ClarifyAction
        data class SwitchToFreeText(val presets: List<String>) : ClarifyAction
        data object None : ClarifyAction
    }

    const val APPROVAL_TITLE = "Требуется подтверждение"
    const val CLARIFY_TITLE = "Марс уточняет"
    const val OWN_ANSWER_LABEL = "Свой ответ"
    const val SUBMIT_LABEL = "Отправить"
    const val BACK_LABEL = "Назад"
    const val CANCEL_LABEL = "Отмена"
    const val ANSWER_HINT = "Ваш ответ…"
    const val PATTERN_PREFIX = "Шаблон"
    const val YOLO_ENABLE_LABEL = "Больше не спрашивать (YOLO)"

    /**
     * Approval options in display order; session/permanent gated by the payload flags.
     * A trailing YOLO grant offers to flip the session flag (web parity: respondApproval
     * 'once' + `yolo:true`); hidden once [yoloEnabled] is true — nothing left to offer.
     */
    fun approvalOptions(payload: ApprovalPayload, yoloEnabled: Boolean = false): List<ApprovalOption> = buildList {
        add(ApprovalOption("Разрешить один раз", ApprovalChoice.ONCE))
        if (payload.allowSession) add(ApprovalOption("Разрешить для этой сессии", ApprovalChoice.SESSION))
        if (payload.allowPermanent) add(ApprovalOption("Разрешать всегда", ApprovalChoice.ALWAYS))
        if (!yoloEnabled) add(ApprovalOption(YOLO_ENABLE_LABEL, ApprovalChoice.ONCE, yolo = true))
        add(ApprovalOption("Отклонить", ApprovalChoice.DENY))
    }

    /** Deny is rendered as the destructive action, visually separated from the grants. */
    fun isDeny(option: ApprovalOption): Boolean = option.choice == ApprovalChoice.DENY && !option.yolo

    /** Primary (ruby) action: the plain one-time grant, never the YOLO variant or deny. */
    fun isPrimary(option: ApprovalOption): Boolean =
        option.choice == ApprovalChoice.ONCE && !option.yolo

    /** Header title with the queue indicator ("1 из N") when several prompts are pending. */
    fun approvalTitle(pendingCount: Int): String =
        if (pendingCount > 1) "$APPROVAL_TITLE · 1 из $pendingCount" else APPROVAL_TITLE

    /** Queue counter text ("2 из 5") for the pending badge; null when only one prompt. */
    fun pendingBadge(pendingCount: Int): String? =
        if (pendingCount > 1) "1 из $pendingCount" else null

    /** Human description shown above the monospace command block (blank when absent). */
    fun approvalDescription(payload: ApprovalPayload): String = payload.description.trim()

    /**
     * Monospace block content: the command, or the tool label when the payload carries only a
     * tool call. Empty string means "no code block" — the card omits it entirely.
     */
    fun approvalCodeBlock(payload: ApprovalPayload): String {
        if (payload.command.isNotBlank()) return payload.command
        val tool = payload.tool?.trim().orEmpty()
        return if (tool.isNotEmpty()) tool else ""
    }

    /** Small caption above the code block: tool name · risk level when the payload has one. */
    fun approvalCodeLabel(payload: ApprovalPayload): String? = buildString {
        payload.tool?.takeIf { it.isNotBlank() }?.let { append(it) }
        payload.riskLevel?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(" · ")
            append(it.lowercase())
        }
    }.ifEmpty { null }

    /** Pattern-key chips; empty list hides the row. */
    fun approvalPatternKeys(payload: ApprovalPayload): List<String> =
        payload.patternKeys.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Initial clarify mode: preset chips when non-blank choices were offered, free text
     * otherwise (empty choices are normal — the agent asked an open question).
     */
    fun clarifyMode(payload: ClarifyPayload): ClarifyMode {
        val presets = payload.choices.map { it.trim() }.filter { it.isNotEmpty() }
        return if (presets.isEmpty()) {
            ClarifyMode.FreeText(emptyList())
        } else {
            ClarifyMode.Presets(presets.map { ClarifyOption.Preset(it) } + ClarifyOption.OwnAnswer)
        }
    }

    /**
     * Activating a chip: a preset sends its label verbatim; the own-answer chip swaps the card
     * into the free-text editor (keeping the presets for the «Назад» return path).
     */
    fun onClarifyOptionActivated(mode: ClarifyMode, option: ClarifyOption): ClarifyAction =
        when {
            option is ClarifyOption.OwnAnswer && mode is ClarifyMode.Presets ->
                ClarifyAction.SwitchToFreeText(mode.options.filterIsInstance<ClarifyOption.Preset>().map { it.label })
            option is ClarifyOption.Preset -> ClarifyAction.Respond(option.label)
            else -> ClarifyAction.None
        }

    /** Free-text submit: trimmed; blank answers are ignored (card stays open, no respond). */
    fun clarifyFreeTextSubmit(raw: String): String? = raw.trim().ifEmpty { null }

    /** Clarify question as shown in the header (blank -> falls back to the title only). */
    fun clarifyQuestion(payload: ClarifyPayload): String = payload.question.trim()
}
