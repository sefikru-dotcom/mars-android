package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.describeHttpError

/**
 * Error-body contract of the chat request paths: on a non-2xx HTTP response the server
 * answers `{"error": "<reason>"}` (hermes-webui routes.py: 400 "message is required",
 * 401 "unauthorized", 404 "stream not found", 502 gateway …). That reason used to be
 * dropped — the UI showed an opaque "HTTP 400" bubble even though the actionable text
 * was already on the wire. These tests pin the fixed contract:
 * `"HTTP <code>: <server error>"` when the body carries a reason, plain `"HTTP <code>"`
 * otherwise, and BOTH shapes keep the markers the app-level policies match on
 * (AuthRejectionPolicy: "http 401"/"unauthorized"; StreamReattachPolicy: `\b404\b`/"not found").
 */
class ChatRepositoryHttpErrorTest {
    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )

    // ---- describeHttpError unit contract ----

    @Test fun describeHttpErrorKeepsCodeAndServerReason() {
        assertEquals("HTTP 400: message is required", describeHttpError(400, """{"error":"message is required"}"""))
        assertEquals("HTTP 401: unauthorized", describeHttpError(401, """{"error": "unauthorized"}"""))
        assertEquals("HTTP 502: gateway agent down", describeHttpError(502, """{"error":"gateway agent down","detail":"x"}"""))
    }

    @Test fun describeHttpErrorFallsBackToCodeWithoutUsableReason() {
        assertEquals("HTTP 500", describeHttpError(500, null))
        assertEquals("HTTP 500", describeHttpError(500, ""))
        assertEquals("HTTP 502", describeHttpError(502, "<html>Bad Gateway</html>"))
        assertEquals("HTTP 500", describeHttpError(500, "{}"))
        assertEquals("HTTP 409", describeHttpError(409, """{"error":"   "}"""))
        assertEquals("HTTP 500", describeHttpError(500, "expired"))
    }

    /**
     * Newer contract refusals carry the reason under `code`, not `error`:
     * `409 {"code": "unsupported_voice_mode_backend"}` (SERVER-CONTRACT.md §1). Without
     * reading that key the client would see a bare "HTTP 409" and could neither explain
     * the refusal to the user nor classify it as a voice-mode rejection.
     */
    @Test fun describeHttpErrorReadsTheCodeKeyOfContractRefusals() {
        assertEquals(
            "HTTP 409: unsupported_voice_mode_backend",
            describeHttpError(409, """{"code":"unsupported_voice_mode_backend"}"""),
        )
        // Legacy key keeps priority when both are present.
        assertEquals(
            "HTTP 409: unsupported_voice_mode_backend",
            describeHttpError(
                409,
                """{"error":"unsupported_voice_mode_backend","code":"something_else"}""",
            ),
        )
        // A blank `error` must not shadow a usable `code`.
        assertEquals(
            "HTTP 409: unsupported_voice_mode_backend",
            describeHttpError(409, """{"error":"  ","code":"unsupported_voice_mode_backend"}"""),
        )
        // Non-JSON bodies still fall back to the bare status.
        assertEquals("HTTP 409", describeHttpError(409, "conflict"))
    }

    // ---- POST /api/chat/start (two-phase path) ----

    @Test fun chatStartFailureSurfacesServerReasonAndStopsBeforeStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"message is required"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "").toList()
            assertEquals(1, events.size)
            assertEquals("HTTP 400: message is required", (events.single() as ChatEvent.Error).message)
            // Terminal: no follow-up GET /api/chat/stream was attempted.
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    /**
     * `runtime-runner` refuses a voice turn before any side effect:
     * `409 {"code": "unsupported_voice_mode_backend"}` (SERVER-CONTRACT.md §1). The client
     * must (a) actually send `voice_mode: true`, (b) see the reason in the error string and
     * (c) stop right there — one POST, no session materialisation, no stream GET, so the UI
     * can explain the refusal instead of retrying it.
     */
    @Test fun chatStartVoiceModeRefusalCarriesTheContractCodeAndStops() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"code":"unsupported_voice_mode_backend"}""")
            )
            val events = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Привет", voiceMode = true,
            ).toList()
            assertEquals(1, events.size)
            assertEquals(
                "HTTP 409: unsupported_voice_mode_backend",
                (events.single() as ChatEvent.Error).message,
            )
            // No follow-up GET /api/chat/stream and no second POST: the turn is over.
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue("voice_mode must ride in the start payload: $body", "\"voice_mode\":true" in body)
        }
    }

    @Test fun chatStartFailureDoesNotLeakRetryAfterMarkerToUser() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("""{"error":"rate limited"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals("HTTP 429: rate limited", (events.single() as ChatEvent.Error).message)
        }
    }

    /** 401 from start must keep BOTH AuthRejectionPolicy markers ("http 401" and "unauthorized"). */
    @Test fun chatStartUnauthorizedKeepsAuthRejectionMarkers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            val message = (events.single() as ChatEvent.Error).message
            val normalized = message.lowercase()
            assertTrue("must stay detectable as a credential rejection: $message",
                "http 401" in normalized && "unauthorized" in normalized)
        }
    }

    /** Non-JSON failure body (proxy HTML page) degrades to the status code, not to a crash. */
    @Test fun chatStartProxyHtmlErrorFallsBackToStatusCode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502)
                .setBody("<html><head><title>502 Bad Gateway</title></head></html>"))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals("HTTP 502", (events.single() as ChatEvent.Error).message)
        }
    }

    /** Failure of the second-phase SSE GET after a successful start carries the reason too. */
    @Test fun streamGetFailureAfterStartSurfacesServerReason() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"stream not found"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals(2, events.size)
            assertEquals(ChatEvent.StreamStarted("st-1"), events[0])
            val message = (events[1] as ChatEvent.Error).message
            assertEquals("HTTP 404: stream not found", message)
            // StreamReattachPolicy markers survive the enriched message.
            assertTrue(Regex("""\b404\b""").containsMatchIn(message.lowercase()))
            assertTrue("not found" in message.lowercase())
        }
    }

    // ---- legacy /chat direct-streaming path ----

    @Test fun legacyChatFailureSurfacesServerReason() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"agent backend exploded"}"""))
            val events = ChatRepository().sendMessage(server.url("/chat").toString(), credential(), "Hi").toList()
            assertEquals("HTTP 500: agent backend exploded", (events.single() as ChatEvent.Error).message)
        }
    }

    /** Regression: a 200 start with a valid stream_id still streams normally (error path untouched). */
    @Test fun successfulStartStillStreamsUnchanged() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: token\ndata: {\"text\":\"ok\"}\n\nevent: stream_end\ndata: {}\n\n"))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals(listOf(
                ChatEvent.StreamStarted("st-1"),
                ChatEvent.SessionBound("s-1"),
                ChatEvent.Chunk("ok"),
                ChatEvent.Done,
            ), events)
        }
    }

    // ---- Retry-After (RFC 9110) ----

    /** describeHttpError appends the anchored coordinator marker only when a directive was parsed. */
    @Test fun describeHttpErrorCarriesRetryAfterMarker() {
        assertEquals(
            "HTTP 429: slow down (retry-after 7s)",
            describeHttpError(429, """{"error":"slow down"}""", 7),
        )
        assertEquals("HTTP 503 (retry-after 1s)", describeHttpError(503, null, 1))
        // no directive -> the legacy shapes are untouched
        assertEquals("HTTP 429: slow down", describeHttpError(429, """{"error":"slow down"}"""))
    }

    /** A throttled SSE GET after a successful start surfaces the directive in the error message. */
    @Test fun streamGetThrottledCarriesRetryAfterMarker() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("""{"error":"rate limited"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals(2, events.size)
            val message = (events[1] as ChatEvent.Error).message
            assertEquals("HTTP 429: rate limited (retry-after 30s)", message)
            // App-level anchors survive the suffix (transport classifier ^http\d{3}, status code).
            assertTrue(message.lowercase().startsWith("http 429"))
        }
    }

    /** HTTP-date directives are converted to delta seconds against the current clock. */
    @Test fun streamGetRetryAfterHttpDateBecomesDeltaSeconds() = runBlocking {
        MockWebServer().use { server ->
            val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(45)
            )
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", date))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            val message = (events[1] as ChatEvent.Error).message
            val seconds = Regex("""\(retry-after (\d+)s\)""").find(message)
                ?.groupValues?.get(1)?.toLong()
            // ~45 s minus test latency; generous window, the point is "delta, not date".
            assertTrue("expected ~45s in: $message", seconds != null && seconds in 40..45)
        }
    }

    /** An unparseable Retry-After is ignored: the plain error message stays intact. */
    @Test fun streamGetGarbageRetryAfterIsIgnored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "soon-ish")
                .setBody("""{"error":"rate limited"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals("HTTP 429: rate limited", (events[1] as ChatEvent.Error).message)
        }
    }

    /** The start POST failure keeps the directive out of the user-visible error. */
    @Test fun chatStartThrottledDoesNotLeakRetryAfterMarker() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)
                .setHeader("Retry-After", "2")
                .setBody("""{"error":"restarting"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals("HTTP 503: restarting", (events.single() as ChatEvent.Error).message)
            assertEquals(1, server.requestCount) // terminal: no stream GET attempted
        }
    }

    /** A 404 with a directive keeps its ReloadSession anchors — the marker never shadows them. */
    @Test fun streamGetNotFoundKeepsReattachMarkersWithRetryAfter() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setResponseCode(404)
                .setHeader("Retry-After", "60")
                .setBody("""{"error":"stream not found"}"""))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            val message = (events[1] as ChatEvent.Error).message
            assertEquals("HTTP 404: stream not found (retry-after 60s)", message)
            assertTrue(Regex("""\b404\b""").containsMatchIn(message.lowercase()))
            assertTrue("not found" in message.lowercase())
        }
    }
}
