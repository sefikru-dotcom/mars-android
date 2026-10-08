package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionAttention

/**
 * Unit tests for [StreamReattachPolicy] — the pure decision core of stream recovery:
 * when a loaded session must be reattached to its live stream, and when a stream event
 * means "the stream is gone (404), reload settled history" instead of an error bubble.
 */
class StreamReattachPolicyTest {

    private val policy = StreamReattachPolicy()

    // --- planFor ---------------------------------------------------------------------------

    @Test
    fun `live active turn plans an attach with its stream id`() {
        val turn = ActiveTurnInfo(activeStreamId = "stream-42", isStreaming = true)
        assertEquals(ReattachPlan.Attach("stream-42"), policy.planFor(turn))
    }

    @Test
    fun `queued turn with a stream id also reattaches`() {
        // has_pending_user_message sessions still expose active_stream_id — the server
        // replays journaled events once the turn starts, so attaching is correct.
        val turn = ActiveTurnInfo(activeStreamId = "s-1", isStreaming = false, hasPendingUserMessage = true)
        assertEquals(ReattachPlan.Attach("s-1"), policy.planFor(turn))
    }

    @Test
    fun `idle session plans nothing`() {
        assertEquals(ReattachPlan.None, policy.planFor(ActiveTurnInfo()))
    }

    @Test
    fun `blank stream id plans nothing despite canReattach shape`() {
        assertEquals(ReattachPlan.None, policy.planFor(ActiveTurnInfo(activeStreamId = "  ")))
        assertEquals(ReattachPlan.None, policy.planFor(ActiveTurnInfo(activeStreamId = "")))
    }

    @Test
    fun `attention alone does not trigger an attach`() {
        val turn = ActiveTurnInfo(attention = SessionAttention(kind = "approval", count = 1))
        assertEquals(ReattachPlan.None, policy.planFor(turn))
    }

    // --- outcomeOf -------------------------------------------------------------------------

    @Test
    fun `terminal HTTP 404 error means the stream is gone`() {
        assertEquals(ReattachEventOutcome.StreamGone, policy.outcomeOf(ChatEvent.Error("HTTP 404")))
    }

    @Test
    fun `textual not-found phrasing also counts as gone`() {
        assertEquals(ReattachEventOutcome.StreamGone, policy.outcomeOf(ChatEvent.Error("Stream not found")))
    }

    @Test
    fun `other errors are surfaced, not swallowed into a reload`() {
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Error("HTTP 500")))
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Error("Сеть недоступна")))
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Error("HTTP 4040")))
    }

    @Test
    fun `normal stream events continue into the transcript`() {
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.StreamStarted("s-1")))
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Chunk("привет")))
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Phase("tool_run")))
        assertEquals(ReattachEventOutcome.Continue, policy.outcomeOf(ChatEvent.Done))
    }
}
