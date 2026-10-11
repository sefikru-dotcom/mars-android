package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent

/**
 * Unit tests for [TvStreamReattachPolicy] — the pure reattach decisions of the TV chat
 * screen (no Android, no I/O): the attach plan for a session's active turn, the anchored
 * gone-journal (404) classification, and — critically — WHICH failures may enter the
 * mid-turn recovery loop: transport-class texts and retryable HTTP verdicts do, while
 * definitive client-side verdicts (business/auth 4xx) must NEVER become a reconnect.
 */
class TvStreamReattachPolicyTest {

    // --- planFor ------------------------------------------------------------------------

    @Test
    fun `a live streaming turn with an id is an attach target`() {
        val plan = TvStreamReattachPolicy.planFor(
            ActiveTurnInfo(activeStreamId = "st1", isStreaming = true),
        )
        assertEquals(TvReattachPlan.Attach("st1"), plan)
    }

    @Test
    fun `settled pending queued and id-less turns are never attach targets`() {
        // Turn finished while the screen was away: the answer lives in persisted history.
        assertEquals(TvReattachPlan.None, TvStreamReattachPolicy.planFor(ActiveTurnInfo()))
        // Queued but not streaming yet: there is no journal to attach to.
        assertEquals(
            TvReattachPlan.None,
            TvStreamReattachPolicy.planFor(ActiveTurnInfo(isStreaming = false, hasPendingUserMessage = true)),
        )
        // Streaming flag without an id cannot build the attach URL.
        assertEquals(
            TvReattachPlan.None,
            TvStreamReattachPolicy.planFor(ActiveTurnInfo(activeStreamId = null, isStreaming = true)),
        )
        assertEquals(TvReattachPlan.None, TvStreamReattachPolicy.planFor(null))
    }

    // --- outcomeOf ----------------------------------------------------------------------

    @Test
    fun `anchored 404 marks the journal gone`() {
        val gone = TvStreamReattachPolicy.outcomeOf(ChatEvent.Error(TvStreamReattachPolicy.STREAM_GONE_MESSAGE))
        assertEquals(TvReattachEventOutcome.StreamGone, gone)
        assertEquals(
            TvReattachEventOutcome.StreamGone,
            TvStreamReattachPolicy.outcomeOf(ChatEvent.Error("HTTP 404: Not Found")),
        )
        assertEquals(
            TvReattachEventOutcome.StreamGone,
            TvStreamReattachPolicy.outcomeOf(ChatEvent.Error("поток не найден (stream not found)")),
        )
    }

    @Test
    fun `404-looking body numbers do not masquerade as a gone stream`() {
        assertEquals(
            TvReattachEventOutcome.Continue,
            TvStreamReattachPolicy.outcomeOf(ChatEvent.Error("Сетевая ошибка: processed 14041 rows, 4040 items")),
        )
    }

    @Test
    fun `non-error events and transport errors continue`() {
        assertEquals(TvReattachEventOutcome.Continue, TvStreamReattachPolicy.outcomeOf(ChatEvent.Done))
        assertEquals(TvReattachEventOutcome.Continue, TvStreamReattachPolicy.outcomeOf(ChatEvent.Chunk("текст")))
        assertEquals(
            TvReattachEventOutcome.Continue,
            TvStreamReattachPolicy.outcomeOf(ChatEvent.Error("Сетевая ошибка: connection reset")),
        )
    }

    // --- shouldAttemptRecovery -----------------------------------------------------------

    @Test
    fun `silent end and transport-class texts may trigger recovery`() {
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery(null)) // dropped connection
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("Сетевая ошибка: unexpected end of stream"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("Превышено время ожидания ответа"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery(""))
    }

    @Test
    fun `retryable HTTP verdicts get the bounded budget`() {
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 502"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 503: service unavailable"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 500: internal"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 408: timeout"))
        assertTrue(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 429: rate limited"))
    }

    @Test
    fun `definitive client verdicts never become a reconnect`() {
        assertFalse(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 400: message rejected"))
        assertFalse(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 401: unauthorized"))
        assertFalse(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 403: forbidden"))
        assertFalse(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 404: stream not found"))
        assertFalse(TvStreamReattachPolicy.shouldAttemptRecovery("HTTP 409: conflict"))
    }
}
