package ru.mars.tv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.MidTurnRecoveryCoordinator
import ru.mars.network.repository.MidTurnRecoveryCoordinator.RecoveryDecision
import ru.mars.network.repository.MidTurnRecoveryCoordinator.StreamFailure

/**
 * Android connectivity seam of the mid-turn recovery loop (host: [TvAndroidConnectivityProbe]
 * in production, a fake in unit tests). The loop parks on [awaitNetworkAvailable] instead of
 * burning the retry budget against a dead radio; [isNetworkAvailable] is the instantaneous
 * snapshot fed to [MidTurnRecoveryCoordinator.onNetworkChanged].
 */
interface TvConnectivityProbe {
    /** Instantaneous snapshot: is any INTERNET-capable network available right now? */
    fun isNetworkAvailable(): Boolean

    /** Suspends until [isNetworkAvailable] flips true (returns immediately when already up). */
    suspend fun awaitNetworkAvailable()
}

/**
 * Transport seam of the mid-turn recovery loop (host: [TvChatActivity] over core-network
 * in production, a scripted fake in unit tests). Deliberately has NO `sendMessage`:
 * the loop may only ever REATTACH to a stream the server already accepted — a delivered
 * `POST /api/chat/start` is never repeated by recovery.
 */
interface TvRecoveryTransport {
    /** Live `GET /api/session` detail; `null` when the fetch itself failed. */
    suspend fun fetchSessionDetail(sessionId: String): SessionDetail?

    /**
     * Opens `attachToStream(streamId, lastEventId = …)` and consumes it, forwarding every
     * event to [onEvent]; returns why the stream ended. Cancelling the calling coroutine
     * cancels the attach (the call must not swallow [kotlinx.coroutines.CancellationException]).
     */
    suspend fun attachStream(
        streamId: String,
        lastEventId: String?,
        onEvent: (ChatEvent) -> Unit,
    ): TvAttachOutcome
}

/** Why one recovery attach ended ([TvRecoveryTransport.attachStream]). */
sealed interface TvAttachOutcome {
    /** The turn reached its terminal `Done` — recovery is finished, nothing to redo. */
    data object Settled : TvAttachOutcome

    /** The journal is gone server-side (anchored 404) — history reload, never another attach. */
    data object Gone : TvAttachOutcome

    /** The stream broke again; [message] is the mapped `ChatEvent.Error` text. */
    data class Lost(val message: String) : TvAttachOutcome
}

/**
 * Single-job mid-turn stream recovery for the TV chat screen: ONE bounded loop over the
 * core [MidTurnRecoveryCoordinator] (pure JVM, no Android — the host injects the
 * [TvRecoveryTransport]/[TvConnectivityProbe] seams and the clock, so the whole
 * park/backoff/reattach/give-up contract is unit-testable with fakes).
 *
 * Contract (task invariants):
 * - the loop NEVER posts `chat/start` again: a delivered turn is only ever reattached via
 *   [TvRecoveryTransport.attachStream] with the coordinator's `Last-Event-ID` cursor
 *   ([cursorProvider] reads the host's [ru.mars.network.repository.StreamResumeTracker]);
 * - offline parking: while the connectivity snapshot is down the loop suspends in
 *   [TvConnectivityProbe.awaitNetworkAvailable] and consumes NO retry budget; the network
 *   return arms the coordinator's one-shot immediate retry;
 * - bounded backoff: every attach failure re-enters [MidTurnRecoveryCoordinator.decide],
 *   whose reconnect budget (core `AdaptiveSseReconnectPolicy`) eventually answers
 *   [RecoveryDecision.GiveUp] — a dead server never produces an endless reconnect;
 * - `Done` ([TvAttachOutcome.Settled]) and definitive server verdicts (404 ->
 *   [RecoveryDecision.ReloadSession], auth/4xx -> [RecoveryDecision.GiveUp]) end the loop;
 * - a turn that finished while the client was away (session detail shows no active
 *   stream) resolves to ONE history reload — never a re-POST, never a reconnect;
 * - cancellation: the host runs [run] inside its single recovery job; cancelling that
 *   job (navigation, «Новый чат», «Остановить», BACK, logout) tears the loop down at the
 *   next suspension point ([ensureActive]).
 */
class TvChatStreamRecoveryLoop(
    private val transport: TvRecoveryTransport,
    private val connectivity: TvConnectivityProbe,
    /** Current `Last-Event-ID` resume cursor (host-owned [ru.mars.network.repository.StreamResumeTracker]). */
    private val cursorProvider: () -> String?,
    private val coordinator: MidTurnRecoveryCoordinator = MidTurnRecoveryCoordinator(),
    /** Clock seam: production is [delay]; tests record instead of sleeping. */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /** Host reactions (UI-confined; called on the loop's coroutine context — Main in production). */
    interface Hooks {
        /** Recovery status line changed (parked / attempt N). */
        fun onStatus(status: String)

        /** An attach is about to open: the host prepares/continues the answer bubble. */
        fun onAttachStarting(streamId: String)

        /** One event of a reattached stream — feed the regular chat event pipeline. */
        fun onStreamEvent(event: ChatEvent)

        /** The turn is over server-side: re-render the transcript from [detail] (may be null). */
        fun onReloadHistory(detail: SessionDetail?)

        /** Recovery is terminally over; [reason] is the coordinator's machine-readable label. */
        fun onGiveUp(reason: String)
    }

    /**
     * Runs recovery for the turn of [sessionId]. [knownStreamId] is the stream the host
     * last saw (may be null when the `chat/start` response never arrived);
     * [initialErrorMessage] is the mapped error that broke the stream (null = the stream
     * ended silently — treated as a dropped connection). Returns when the turn settles,
     * history reloads, the budget runs out or the coroutine is cancelled.
     */
    suspend fun run(
        sessionId: String,
        knownStreamId: String?,
        initialErrorMessage: String?,
        hooks: Hooks,
    ) {
        var failure = StreamFailure(
            errorMessage = initialErrorMessage ?: UNEXPECTED_END_MESSAGE,
            lastEventId = cursorProvider(),
        )
        while (true) {
            currentCoroutineContext().ensureActive()
            // Feed the live connectivity snapshot: a dead radio parks the coordinator.
            coordinator.onNetworkChanged(connectivity.isNetworkAvailable())
            when (val decision = coordinator.decide(failure)) {
                RecoveryDecision.WaitForNetwork -> {
                    hooks.onStatus(STATUS_PARKED)
                    connectivity.awaitNetworkAvailable() // parks WITHOUT burning budget
                    coordinator.onNetworkChanged(true)   // arms the one-shot immediate retry
                    // same failure, re-decide
                }

                RecoveryDecision.ReloadSession -> {
                    hooks.onReloadHistory(transport.fetchSessionDetail(sessionId))
                    return
                }

                is RecoveryDecision.GiveUp -> {
                    hooks.onGiveUp(decision.reason)
                    return
                }

                is RecoveryDecision.ReattachAfter -> {
                    val detail = transport.fetchSessionDetail(sessionId)
                    if (detail != null && !detail.activeTurn.canReattach &&
                        !detail.activeTurn.hasPendingUserMessage
                    ) {
                        // The turn finished (Done raced the drop) or never started:
                        // the honest outcome is ONE history reload — never a re-POST.
                        hooks.onReloadHistory(detail)
                        return
                    }
                    val streamId = knownStreamId?.takeIf { it.isNotBlank() }
                        ?: detail?.activeTurn?.activeStreamId?.takeIf { it.isNotBlank() }
                    if (streamId == null) {
                        if (detail != null) {
                            hooks.onReloadHistory(detail)
                            return
                        }
                        // Detail fetch failed too (server down): stay inside the
                        // coordinator's bounded budget instead of giving up early.
                        failure = StreamFailure(
                            errorMessage = DETAIL_UNAVAILABLE_MESSAGE,
                            lastEventId = cursorProvider(),
                        )
                        continue
                    }
                    hooks.onStatus(recoveringStatus(decision.attempt))
                    if (decision.delayMs > 0) sleeper(decision.delayMs)
                    currentCoroutineContext().ensureActive()
                    hooks.onAttachStarting(streamId)
                    val cursorBefore = cursorProvider()
                    val outcome = transport.attachStream(streamId, decision.resumeFromEventId) { event ->
                        hooks.onStreamEvent(event)
                    }
                    // The stream proved alive (journal frames advanced the resume
                    // cursor): reset the failure streak so the NEXT drop gets a fresh
                    // bounded budget — progress was made, this is not a dead loop.
                    if (cursorProvider() != cursorBefore) coordinator.onStreamHealthy()
                    failure = when (outcome) {
                        TvAttachOutcome.Settled -> return
                        TvAttachOutcome.Gone -> StreamFailure(
                            errorMessage = TvStreamReattachPolicy.STREAM_GONE_MESSAGE,
                            lastEventId = cursorProvider(),
                        )
                        is TvAttachOutcome.Lost -> StreamFailure(
                            errorMessage = outcome.message,
                            lastEventId = cursorProvider(),
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Status line when recovery has just been scheduled (before the first decision). */
        const val STATUS_STARTING = "Восстанавливаю соединение…"

        /** Status line while recovery waits for connectivity (offline parking). */
        const val STATUS_PARKED = "Сеть недоступна — жду подключения…"

        /** Status line of a scheduled reattach attempt (1-based within the streak). */
        fun recoveringStatus(attempt: Int): String = "Восстанавливаю поток (попытка $attempt)…"

        /** Failure text for a silent stream end (no terminal event) — transport-class. */
        const val UNEXPECTED_END_MESSAGE = "stream ended unexpectedly"

        /** Failure text for a failed session-detail fetch — transport-class, budget-bounded. */
        const val DETAIL_UNAVAILABLE_MESSAGE = "Сетевая ошибка: session detail unavailable"
    }
}
