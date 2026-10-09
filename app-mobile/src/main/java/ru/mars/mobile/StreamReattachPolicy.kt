package ru.mars.mobile

import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent

/**
 * Pure decision core of stream recovery (platform independent, unit-tested). ChatActivity owns
 * the coroutine/IO wiring; this class answers two questions:
 *
 * 1. [planFor] — a loaded session reports [ActiveTurnInfo]; should the UI reattach to the live
 *    stream (server replays journaled events and continues) instead of showing a frozen
 *    transcript of a turn that is still running?
 * 2. [outcomeOf] — while reattached, is a stream event a normal transcript event, or the
 *    terminal "HTTP 404" telling us the stream no longer exists on the server (turn settled
 *    between the session fetch and the attach)? In that case the recovery is a history reload,
 *    not an error bubble.
 */
sealed interface ReattachPlan {
    /** Reattach via `ChatRepository.attachToStream(streamId)`. */
    data class Attach(val streamId: String) : ReattachPlan

    /** Nothing live on this session — just show the loaded history. */
    data object None : ReattachPlan
}

sealed interface ReattachEventOutcome {
    /** Feed the event into ChatUiState/interactive controller as usual. */
    data object Continue : ReattachEventOutcome

    /** The stream is gone server-side (404): abandon the reattach and reload settled history. */
    data object StreamGone : ReattachEventOutcome
}

class StreamReattachPolicy {

    fun planFor(activeTurn: ActiveTurnInfo): ReattachPlan {
        val streamId = activeTurn.activeStreamId
        return if (activeTurn.canReattach && streamId != null) {
            ReattachPlan.Attach(streamId)
        } else {
            ReattachPlan.None
        }
    }

    fun outcomeOf(event: ChatEvent): ReattachEventOutcome =
        if (event is ChatEvent.Error && isStreamGoneMessage(event.message)) {
            ReattachEventOutcome.StreamGone
        } else {
            ReattachEventOutcome.Continue
        }

    /**
     * core-network maps a non-2xx stream response to `ChatEvent.Error("HTTP <code>[: <reason>]")`
     * (`describeHttpError`), and routes.py's reason for a dead stream is `"stream not found"`,
     * so the terminal shape is always `"HTTP 404"`-prefixed and/or carries that exact reason.
     *
     * The markers MUST stay anchored: the SSE mapper maps in-stream `error`/`apperror` frames
     * (tool failures journaled mid-turn and REPLAYED on reattach — "command not found",
     * "File not found: …", "…404 retries…") onto the same `ChatEvent.Error`, while the stream
     * itself is alive and keeps running. A bare `\b404\b`/"not found" match anywhere in the
     * text misclassified those as terminal and abandoned live reattaches (regression test:
     * StreamReattachPolicyTest).
     */
    private fun isStreamGoneMessage(message: String): Boolean {
        val lower = message.trim().lowercase()
        return HTTP_404_PREFIX.containsMatchIn(lower) || STREAM_NOT_FOUND in lower
    }

    companion object {
        /** Terminal attach failure: the message STARTS with the 404 status line ("HTTP 404…"). */
        private val HTTP_404_PREFIX = Regex("""^http 404\b""")

        /** routes.py's verbatim reason for an unknown/expired stream — survives gateway rewrites. */
        private const val STREAM_NOT_FOUND = "stream not found"
    }
}
