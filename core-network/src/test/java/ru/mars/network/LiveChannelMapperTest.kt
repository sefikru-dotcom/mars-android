package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionListAction
import ru.mars.network.repository.StreamEventMapper

/**
 * Wire contract of the three live channels the client subscribes to instead of polling.
 *
 * Payloads are copied from the real handlers (read-only against `/opt/hermes-webui`):
 * - `api/session_events.py::_sessions_changed_payload` — `sessions_changed` invalidation,
 * - `api/route_approvals.py::_approval_sse_notify_locked` — `{pending, pending_count}`,
 * - `api/clarify.py::_clarify_sse_notify` — `{pending, pending_count}`.
 *
 * The point of these tests is that the parsers NEVER invent data: a malformed body must map to
 * null (transport keeps waiting), while `pending: null` must map to a real "queue is empty"
 * update (the card has to come down — that is the whole reason the stream exists).
 */
class LiveChannelMapperTest {

    // ── /api/sessions/events ────────────────────────────────────────────────────────────────

    @Test
    fun sessionNewMapsToAddedWithSessionId() {
        val event = StreamEventMapper.sessionListEvent(
            "sessions_changed",
            """{"type":"sessions_changed","version":42,"reason":"session_new","session_id":"s-9","profile":"default"}""",
        )
        assertEquals(SessionListAction.ADDED, event?.action)
        assertEquals("session_new", event?.reason)
        assertEquals(42L, event?.version)
        assertEquals("s-9", event?.sessionId)
        // Root/default aliases are never scoped on the wire (session_events.py strips them);
        // the client keeps whatever the server sent and lets the caller decide.
        assertEquals("default", event?.profile)
        assertEquals("sessions_changed", event?.rawType)
    }

    @Test
    fun sessionDeleteMapsToRemoved() {
        val event = StreamEventMapper.sessionListEvent(
            "sessions_changed",
            """{"type":"sessions_changed","version":7,"reason":"session_delete","session_id":"s-3"}""",
        )
        assertEquals(SessionListAction.REMOVED, event?.action)
    }

    @Test
    fun attentionReasonsMapToUpdatedWhenScopedToASession() {
        for (reason in listOf("attention_pending", "attention_resolved", "attention_cleared")) {
            val event = StreamEventMapper.sessionListEvent(
                "sessions_changed",
                """{"type":"sessions_changed","version":1,"reason":"$reason","session_id":"s-1"}""",
            )
            assertEquals("reason=$reason", SessionListAction.UPDATED, event?.action)
        }
    }

    @Test
    fun unscopedAttentionEventRefreshesTheWholeList() {
        val event = StreamEventMapper.sessionListEvent(
            "sessions_changed",
            """{"type":"sessions_changed","version":5,"reason":"attention_pending"}""",
        )
        assertEquals(SessionListAction.REFRESH, event?.action)
        assertNull(event?.sessionId)
    }

    @Test
    fun unknownReasonWithSessionIdStaysUpdatedAndWithoutItRefreshes() {
        assertEquals(
            SessionListAction.UPDATED,
            StreamEventMapper.sessionListEvent(
                "sessions_changed",
                """{"type":"sessions_changed","reason":"future_reason","session_id":"s-2"}""",
            )?.action,
        )
        assertEquals(
            SessionListAction.REFRESH,
            StreamEventMapper.sessionListEvent(
                "sessions_changed",
                """{"type":"sessions_changed","reason":"future_reason"}""",
            )?.action,
        )
    }

    @Test
    fun sessionEventRejectsForeignFrames() {
        // Another event name on the same socket, a JSON body that is not an invalidation, and a
        // non-JSON body (proxy error page) must all be ignored — a blind refresh on garbage
        // would turn a broken connection into a request storm.
        assertNull(StreamEventMapper.sessionListEvent("approval", """{"pending":null}"""))
        assertNull(StreamEventMapper.sessionListEvent("sessions_changed", "not json"))
        assertNull(StreamEventMapper.sessionListEvent("sessions_changed", "{}"))
    }

    @Test
    fun sessionEventWithoutTypeFallsBackToTheEventName() {
        val event = StreamEventMapper.sessionListEvent(
            "sessions_changed",
            """{"reason":"session_import","session_id":"s-4"}""",
        )
        assertEquals(SessionListAction.ADDED, event?.action)
        assertNull(event?.version)
    }

    // ── /api/approval/stream ────────────────────────────────────────────────────────────────

    @Test
    fun approvalInitialFrameCarriesHeadAndQueueLength() {
        val update = StreamEventMapper.approvalStreamUpdate(
            "initial",
            """{"pending":{"approval_id":"a-1","command":"rm -rf /tmp/x","description":"delete temp",""" +
                """"pattern_keys":["rm"],"allow_permanent":true,"allow_session":true},"pending_count":2}""",
        )
        val payload = update?.payload
        assertEquals("a-1", payload?.approvalId)
        assertEquals("rm -rf /tmp/x", payload?.command)
        assertEquals("delete temp", payload?.description)
        assertEquals(listOf("rm"), payload?.patternKeys)
        assertTrue(payload?.allowPermanent == true)
        // Queue length from the envelope wins over the payload default (1) — the card shows
        // "1 of N" and the answer path must not lie about it.
        assertEquals(2, payload?.pendingCount)
        assertEquals(2, update?.pendingCount)
        assertFalse(update?.isEmpty ?: true)
    }

    @Test
    fun approvalEmptyQueueIsARealStateNotAParseFailure() {
        // Exactly what _approval_sse_notify_locked(session, None, 0) pushes when the queue
        // empties — the card must be taken down.
        val update = StreamEventMapper.approvalStreamUpdate(
            "approval",
            """{"pending":null,"pending_count":0}""",
        )
        assertEquals(0, update?.pendingCount)
        assertNull(update?.payload)
        assertTrue(update?.isEmpty ?: false)
    }

    @Test
    fun approvalPayloadWithoutEnvelopeCountDefaultsToOne() {
        val update = StreamEventMapper.approvalStreamUpdate(
            "approval",
            """{"pending":{"command":"ls","description":"list"}}""",
        )
        assertEquals(1, update?.pendingCount)
        assertEquals(1, update?.payload?.pendingCount)
    }

    @Test
    fun approvalStreamRejectsGarbageAndForeignFrames() {
        assertNull(StreamEventMapper.approvalStreamUpdate("approval", "{"))
        // A keepalive-shaped or unrelated payload has neither `pending` nor `pending_count`.
        assertNull(StreamEventMapper.approvalStreamUpdate("approval", """{"ok":true}"""))
        assertNull(StreamEventMapper.approvalStreamUpdate("sessions_changed", """{"pending":null}"""))
    }

    @Test
    fun approvalStreamIgnoresClarifyShapedPending() {
        // Defensive: the two channels share the `initial` event name, so a clarify snapshot
        // must not fabricate an approval card.
        val update = StreamEventMapper.approvalStreamUpdate(
            "initial",
            """{"pending":{"clarify_id":"c-1","question":"Which branch?","choices_offered":["main"]},"pending_count":1}""",
        )
        assertNull(update?.payload)
        assertTrue(update?.isEmpty ?: false)
    }

    // ── /api/clarify/stream ─────────────────────────────────────────────────────────────────

    @Test
    fun clarifyFrameCarriesQuestionChoicesAndTiming() {
        val update = StreamEventMapper.clarifyStreamUpdate(
            "clarify",
            """{"pending":{"clarify_id":"c-7","question":"Какую ветку?","choices_offered":["main","dev"],""" +
                """"session_id":"s-1","requested_at":1700000000.5,"timeout_seconds":120,"expires_at":1700000120.5},"pending_count":1}""",
        )
        val payload = update?.payload
        assertEquals("c-7", payload?.clarifyId)
        assertEquals("Какую ветку?", payload?.question)
        assertEquals(listOf("main", "dev"), payload?.choices)
        assertEquals("s-1", payload?.sessionId)
        assertEquals(120, payload?.timeoutSeconds)
        assertEquals(1700000000.5, payload?.requestedAtEpochSeconds ?: 0.0, 0.001)
        assertFalse(update?.isEmpty ?: true)
    }

    @Test
    fun clarifyEmptyQueueHidesTheCard() {
        val update = StreamEventMapper.clarifyStreamUpdate("clarify", """{"pending":null,"pending_count":0}""")
        assertNull(update?.payload)
        assertTrue(update?.isEmpty ?: false)
    }

    @Test
    fun clarifyStreamRejectsGarbageAndForeignFrames() {
        assertNull(StreamEventMapper.clarifyStreamUpdate("clarify", "nope"))
        assertNull(StreamEventMapper.clarifyStreamUpdate("clarify", """{"version":3}"""))
        assertNull(StreamEventMapper.clarifyStreamUpdate("approval", """{"pending":null,"pending_count":0}"""))
    }
}
