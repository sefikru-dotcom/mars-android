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
     * core-network maps a non-2xx stream response to `ChatEvent.Error("HTTP <code>")`, so a dead
     * stream surfaces as "HTTP 404"; a textual "not found" is tolerated for gateway rewrites.
     */
    private fun isStreamGoneMessage(message: String): Boolean {
        val lower = message.lowercase()
        return HTTP_404.containsMatchIn(lower) || "not found" in lower
    }

    companion object {
        private val HTTP_404 = Regex("""\b404\b""")
    }
}
