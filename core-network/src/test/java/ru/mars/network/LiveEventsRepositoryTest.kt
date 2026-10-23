package ru.mars.network

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalStreamUpdate
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.LiveChannelEvent
import ru.mars.network.model.LiveChannelMode
import ru.mars.network.model.SessionListAction
import ru.mars.network.repository.AdaptiveSseReconnectPolicy
import ru.mars.network.repository.LiveEventsRepository
import ru.mars.network.repository.updatesOnly

/**
 * Transport contract of the three live channels: SSE first, reconnect with backoff, and a
 * polling fallback that keeps the SAME element type so the UI path never forks.
 *
 * The SSE bodies are byte-for-byte what the handlers write (see
 * `api/route_approvals.py::_handle_approval_sse_stream` — `event: initial`, `event: approval`,
 * `: keepalive` comments) and the polling bodies are what `GET /api/approval/pending` and
 * `GET /api/clarify/pending` return.
 */
class LiveEventsRepositoryTest {

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token",
        tokenType = "Bearer",
        deviceId = "device-1",
        scopes = listOf("chat:send"),
        expiresIn = 3600,
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** Zero-retry policy: the first failure goes straight to the polling fallback. */
    private fun noReconnect() = { AdaptiveSseReconnectPolicy(maxRetries = 0) }

    /** Instant ladder: the first failure waits ~1 ms, so a reconnect test finishes fast. */
    private fun fastReconnect(maxRetries: Int = 3) = {
        AdaptiveSseReconnectPolicy(
            initialDelayMs = 1L,
            maxDelayMs = 5L,
            factor = 1.0,
            maxRetries = maxRetries,
            jitterFraction = 0.0,
        )
    }

    @Test
    fun sessionsFeedParsesInvalidationsAndSendsTheDeviceBearer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sse(
                    ": keepalive\n\n" +
                        "event: sessions_changed\n" +
                        "data: {\"type\":\"sessions_changed\",\"version\":11,\"reason\":\"session_new\",\"session_id\":\"s-1\"}\n\n" +
                        "event: sessions_changed\n" +
                        "data: {\"type\":\"sessions_changed\",\"version\":12,\"reason\":\"session_delete\",\"session_id\":\"s-0\"}\n\n",
                )
            )
            val events = LiveEventsRepository(reconnectPolicyFactory = noReconnect())
                .subscribeToSessionsEvents(server.url("/").toString(), credential())
                .updatesOnly()
                .take(2)
                .toList()

            assertEquals(SessionListAction.ADDED, events[0].action)
            assertEquals("s-1", events[0].sessionId)
            assertEquals(11L, events[0].version)
            assertEquals(SessionListAction.REMOVED, events[1].action)

            val request = server.takeRequest()
            assertEquals("/api/sessions/events", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertEquals("text/event-stream", request.getHeader("Accept"))
        }
    }

    @Test
    fun approvalStreamEmitsSnapshotThenTheEmptyQueueState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sse(
                    "event: initial\n" +
                        "data: {\"pending\":{\"approval_id\":\"a-1\",\"command\":\"rm -rf /tmp/x\",\"description\":\"cleanup\",\"pattern_keys\":[\"rm\"]},\"pending_count\":1}\n\n" +
                        "event: approval\n" +
                        "data: {\"pending\":null,\"pending_count\":0}\n\n",
                )
            )
            val updates = LiveEventsRepository(reconnectPolicyFactory = noReconnect())
                .subscribeToApprovalStream(server.url("/").toString(), credential(), "s-1")
                .updatesOnly()
                .take(2)
                .toList()

            assertEquals("a-1", updates[0].payload?.approvalId)
            assertEquals(1, updates[0].pendingCount)
            assertNull(updates[1].payload)
            assertTrue(updates[1].isEmpty)

            assertEquals("/api/approval/stream?session_id=s-1", server.takeRequest().path)
        }
    }

    @Test
    fun clarifyStreamDeliversTheQuestion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sse(
                    "event: clarify\n" +
                        "data: {\"pending\":{\"clarify_id\":\"c-1\",\"question\":\"Ветка?\",\"choices_offered\":[\"main\",\"dev\"],\"session_id\":\"s-1\"},\"pending_count\":1}\n\n",
                )
            )
            val updates = LiveEventsRepository(reconnectPolicyFactory = noReconnect())
                .subscribeToClarifyStream(server.url("/").toString(), credential(), "s-1")
                .updatesOnly()
                .take(1)
                .toList()

            assertEquals("Ветка?", updates.single().payload?.question)
            assertEquals(listOf("main", "dev"), updates.single().payload?.choices)
            assertEquals("/api/clarify/stream?session_id=s-1", server.takeRequest().path)
        }
    }

    @Test
    fun approvalStreamFallsBackToPollingWhenSseIsRejected() = runBlocking {
        MockWebServer().use { server ->
            // 401 is what the native client sees when the server's allow-list has not admitted
            // the route yet — the exact case the fallback exists for.
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Authentication required"}"""))
            server.enqueue(json("""{"pending":null,"pending_count":0}"""))
            server.enqueue(json("""{"pending":{"approval_id":"a-9","command":"ls","description":"list"},"pending_count":1}"""))

            // CONNECTING, POLLING, the empty-queue poll result, then the real card.
            val collected = withTimeout(10_000) {
                LiveEventsRepository(
                    reconnectPolicyFactory = noReconnect(),
                    attentionPollIntervalMs = 1L,
                )
                    .subscribeToApprovalStream(server.url("/").toString(), credential(), "s-1")
                    .take(4)
                    .toList()
            }

            val modes = collected.filterIsInstance<LiveChannelEvent.Mode>().map { it.mode }
            assertTrue("stream failed → POLLING expected, got $modes", LiveChannelMode.POLLING in modes)
            val updates = collected.filterIsInstance<LiveChannelEvent.Update<*>>()
                .map { it.value as ApprovalStreamUpdate }
            // The first poll answers the 401-era empty queue, the second delivers the card: the
            // fallback is transparent — same element type, same consumer code.
            assertNull(updates.first().payload)
            assertEquals("a-9", updates.last().payload?.approvalId)

            assertEquals("/api/approval/stream?session_id=s-1", server.takeRequest().path)
            assertEquals(
                "/api/approval/pending?session_id=s-1",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun clarifyStreamFallsBackToPollingToo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(json("""{"pending":{"clarify_id":"c-9","question":"Куда?","session_id":"s-1"}}"""))

            val update = withTimeout(10_000) {
                LiveEventsRepository(
                    reconnectPolicyFactory = noReconnect(),
                    attentionPollIntervalMs = 1L,
                )
                    .subscribeToClarifyStream(server.url("/").toString(), credential(), "s-1")
                    .updatesOnly()
                    .take(1)
                    .toList()
                    .single()
            }

            assertEquals("c-9", update.payload?.clarifyId)
            assertEquals("Куда?", update.payload?.question)
        }
    }

    @Test
    fun closedStreamReconnectsWithBackoffAndDeliversTheNextEvent() = runBlocking {
        MockWebServer().use { server ->
            // A feed that closes cleanly is a failure for a long-lived stream: reconnect.
            server.enqueue(sse(""))
            server.enqueue(
                sse(
                    "event: approval\n" +
                        "data: {\"pending\":{\"approval_id\":\"a-2\",\"command\":\"pwd\",\"description\":\"where\"},\"pending_count\":1}\n\n",
                )
            )

            val update = withTimeout(10_000) {
                LiveEventsRepository(reconnectPolicyFactory = fastReconnect())
                    .subscribeToApprovalStream(server.url("/").toString(), credential(), "s-1")
                    .updatesOnly()
                    .take(1)
                    .toList()
                    .single()
            }

            assertEquals("a-2", update.payload?.approvalId)
            assertEquals("/api/approval/stream?session_id=s-1", server.takeRequest().path)
            assertEquals("/api/approval/stream?session_id=s-1", server.takeRequest().path)
        }
    }

    @Test
    fun pollingRecoversToStreamingAndAnnouncesTheTransition() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)) // SSE unavailable
            server.enqueue(json("""{"pending":null,"pending_count":0}""")) // fallback poll
            server.enqueue( // SSE is back: the first event after recovery
                sse(
                    "event: approval\n" +
                        "data: {\"pending\":{\"approval_id\":\"a-3\",\"command\":\"id\",\"description\":\"whoami\"},\"pending_count\":1}\n\n",
                )
            )

            val collected = withTimeout(10_000) {
                LiveEventsRepository(
                    reconnectPolicyFactory = noReconnect(),
                    attentionPollIntervalMs = 1L,
                    sseRetryAfterPolls = 1,
                )
                    .subscribeToApprovalStream(server.url("/").toString(), credential(), "s-1")
                    .take(5)
                    .toList()
            }

            val modes = collected.filterIsInstance<LiveChannelEvent.Mode>().map { it.mode }
            assertEquals(
                "expected POLLING then STREAMING (no CONNECTING flicker on retry), got $modes",
                listOf(LiveChannelMode.CONNECTING, LiveChannelMode.POLLING, LiveChannelMode.STREAMING),
                modes,
            )
            val payloads = collected.filterIsInstance<LiveChannelEvent.Update<*>>()
                .map { (it.value as ApprovalStreamUpdate).payload?.approvalId }
            assertEquals(listOf(null, "a-3"), payloads)
        }
    }

    @Test
    fun updatesOnlyDropsModeTransitions() = runBlocking {
        val events: List<LiveChannelEvent<String>> = listOf(
            LiveChannelEvent.Mode(LiveChannelMode.CONNECTING),
            LiveChannelEvent.Update("a"),
            LiveChannelEvent.Mode(LiveChannelMode.STREAMING),
            LiveChannelEvent.Update("b"),
        )
        val values = kotlinx.coroutines.flow.flowOf(*events.toTypedArray()).updatesOnly().toList()
        assertEquals(listOf("a", "b"), values)
    }
}
