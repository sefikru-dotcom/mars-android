package ru.mars.tv

import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent

/**
 * What a reattach attempt to a session's active turn should do
 * ([TvStreamReattachPolicy.planFor]) — TV parity of app-mobile's `StreamReattachPolicy`:
 * - [Attach]: the turn is live server-side and not yet attached locally — open the SSE
 *   stream (with the `Last-Event-ID` cursor when one was recorded);
 * - [None]: nothing to attach (turn finished, ids absent, or this stream is already open).
 */
sealed interface TvReattachPlan {
    data class Attach(val streamId: String) : TvReattachPlan
    data object None : TvReattachPlan
}

/**
 * Outcome classification of one event inside a reattached stream
 * ([TvStreamReattachPolicy.outcomeOf]):
 * - [StreamGone]: the server answered 404 "stream not found" — the journal expired or the
 *   stream was already reaped; reattaching again is pointless, the host must reload the
 *   session history instead;
 * - [Continue]: anything else (content events, other errors) — keep consuming the stream.
 */
sealed interface TvReattachEventOutcome {
    data object StreamGone : TvReattachEventOutcome
    data object Continue : TvReattachEventOutcome
}

/**
 * Pure reattach decisions of the TV chat screen (no Android, no I/O — JVM-testable,
 * [TvChatLifecyclePolicy] parity). The host ([TvChatActivity]) owns the state and the
 * transport; this object only classifies:
 *
 * - [planFor]: whether an [ActiveTurnInfo] fetched from `GET /api/session` deserves an
 *   `attachToStream` call (never a cancel — a technical recreation or opening an active
 *   dialog must CONTINUE the server turn, not kill it);
 * - [outcomeOf]: anchored 404/"not found" detection on `ChatEvent.Error` texts, so a
 *   gone journal turns into ONE history reload instead of an endless reconnect loop;
 * - [shouldAttemptRecovery]: which `ChatEvent.Error` texts may trigger the mid-turn
 *   recovery loop ([TvChatStreamRecoveryLoop]) — only transport-class failures. A
 *   definitive `HTTP <code>` verdict (business/auth/server errors) is terminal for the
 *   turn and must NEVER become a reconnect, and a `Done` never schedules anything.
 */
object TvStreamReattachPolicy {

    /** Canonical gone-stream marker fed to the recovery coordinator after an anchored 404. */
    const val STREAM_GONE_MESSAGE = "HTTP 404: stream not found"

    /**
     * Plan for one [ActiveTurnInfo]: [TvReattachPlan.Attach] only when the turn is
     * reattach-ready (`is_streaming` with a non-blank `active_stream_id`), otherwise
     * [TvReattachPlan.None]. A turn that finished while the screen was away is NOT an
     * attach target — its answer already lives in the persisted history.
     */
    fun planFor(activeTurn: ActiveTurnInfo?): TvReattachPlan {
        if (activeTurn == null || !activeTurn.canReattach) return TvReattachPlan.None
        val streamId = activeTurn.activeStreamId?.trim().orEmpty()
        if (streamId.isEmpty()) return TvReattachPlan.None
        return TvReattachPlan.Attach(streamId)
    }

    /**
     * Anchored classification of one reattached-stream event: [TvReattachEventOutcome.StreamGone]
     * for an anchored `\b404\b` word or a literal "stream not found" (case-insensitive)
     * inside a `ChatEvent.Error`, [TvReattachEventOutcome.Continue] otherwise. Anchoring
     * keeps body text like "processed 14041 rows" or "4040 items" from masquerading as a
     * gone stream.
     */
    fun outcomeOf(event: ChatEvent): TvReattachEventOutcome {
        if (event !is ChatEvent.Error) return TvReattachEventOutcome.Continue
        val text = event.message
        val is404 = Regex("""\b404\b""").containsMatchIn(text)
        val isNotFound = text.contains("not found", ignoreCase = true) ||
            text.contains("не найден", ignoreCase = true)
        return if (is404 || isNotFound) TvReattachEventOutcome.StreamGone
        else TvReattachEventOutcome.Continue
    }

    /**
     * True when an `ChatEvent.Error` text may trigger the mid-turn recovery loop
     * ([TvChatStreamRecoveryLoop]):
     * - `null` (the stream ended silently without any terminal event — a dropped
     *   connection) counts as transport-class;
     * - texts WITHOUT an `HTTP <code>` verdict are `describeNetworkError` output
     *   ("Сетевая ошибка: …", "Превышено время ожидания…") — transport-class;
     * - retryable HTTP verdicts (408, 429, 5xx — gateway/server hiccups) get the
     *   coordinator's BOUNDED retry budget;
     * - definitive client-side HTTP verdicts (400 business, 401/403 auth, other 4xx)
     *   are terminal: the server already answered, reconnecting would just re-ask a
     *   question that has an answer — so they must never become a reconnect.
     */
    fun shouldAttemptRecovery(message: String?): Boolean {
        if (message == null) return true
        val status = HTTP_CODE_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull()
            ?: return true // no HTTP verdict: transport-class failure
        return status == 408 || status == 429 || status in 500..599
    }

    /** First `HTTP <code>` occurrence (core `describeHttpError` / coordinator parity). */
    private val HTTP_CODE_REGEX = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
}
