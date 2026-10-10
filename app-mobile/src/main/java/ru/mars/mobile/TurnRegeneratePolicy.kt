package ru.mars.mobile

/** Why a regenerate request was turned down (see [TurnRegeneratePolicy.requestRegenerate]). */
enum class RegenerateRejectReason { ALREADY_STREAMING, NO_USER_TURN }

/** The last user turn extracted for a retry: prompt text, attachment names and its transcript index. */
data class UserTurn(val text: String, val attachments: List<String>, val messageIndex: Int)

sealed interface RegenerateOutcome {
    /** Resend this turn; the policy has already marked the stream active. */
    data class Retry(val text: String, val attachments: List<String>) : RegenerateOutcome

    /** The regenerate must not start; [reason] says why. */
    data class Rejected(val reason: RegenerateRejectReason) : RegenerateOutcome
}

/**
 * Pure logic of «regenerate / retry the last user turn» (WebUI parity). ChatActivity owns
 * the coroutine/IO wiring; this class answers:
 *
 * 1. [lastUserTurn] — which transcript message is the retryable prompt (the LAST user
 *    bubble carrying text or attachments; blank bubbles and assistant rows are skipped);
 * 2. [requestRegenerate] — may the retry start right now? Guards against double triggers:
 *    a live stream ([ChatStatus.TYPING] or an unsettled previous retry) rejects, so a
 *    double tap on «Повторить» can never open two assistant bubbles.
 *
 * The stream-active flag is set by a granted [RegenerateOutcome.Retry] and MUST be cleared
 * by [onTurnSettled] when the turn completes/fails/cancels — same lifecycle as
 * [ChatUiState.complete]/[ChatUiState.fail]/[ChatUiState.cancel].
 */
class TurnRegeneratePolicy {

    /** True between a granted retry and [onTurnSettled] — the double-trigger guard. */
    var streamActive: Boolean = false
        private set

    /**
     * The retryable user turn: last «user» message with non-blank text or attachments.
     * Null when the transcript has none (fresh chat / history of assistant-only rows).
     */
    fun lastUserTurn(messages: List<ChatMessage>): UserTurn? {
        val index = messages.indexOfLast {
            it.role == "user" && (it.text.isNotBlank() || it.attachments.isNotEmpty())
        }
        if (index < 0) return null
        val message = messages[index]
        return UserTurn(message.text.trim(), message.attachments, index)
    }

    /**
     * Decides a regenerate tap. On [RegenerateOutcome.Retry] the policy marks the stream
     * active itself, so an immediate second call is rejected until [onTurnSettled].
     */
    fun requestRegenerate(messages: List<ChatMessage>, status: ChatStatus): RegenerateOutcome {
        if (streamActive || status == ChatStatus.TYPING) {
            return RegenerateOutcome.Rejected(RegenerateRejectReason.ALREADY_STREAMING)
        }
        val turn = lastUserTurn(messages)
            ?: return RegenerateOutcome.Rejected(RegenerateRejectReason.NO_USER_TURN)
        streamActive = true
        return RegenerateOutcome.Retry(turn.text, turn.attachments)
    }

    /** The retried turn settled (complete/fail/cancel) — a new regenerate may start. */
    fun onTurnSettled() {
        streamActive = false
    }

    /** Full reset (session swap / new chat): drops the guard so state never leaks across transcripts. */
    fun reset() {
        streamActive = false
    }
}
