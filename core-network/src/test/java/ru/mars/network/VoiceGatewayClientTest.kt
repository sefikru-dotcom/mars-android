package ru.mars.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.AgentFinishedReason
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.VoiceCloseResult
import ru.mars.network.model.VoiceEvent
import ru.mars.network.model.VoiceGatewayError
import ru.mars.network.model.VoiceInterruptResult
import ru.mars.network.model.VoiceResult
import ru.mars.network.model.VoiceSession
import ru.mars.network.repository.AdaptiveSseReconnectPolicy
import ru.mars.network.repository.VoiceGatewayClient
import java.util.concurrent.TimeUnit

/**
 * Транспортный контракт [VoiceGatewayClient] против MockWebServer:
 *
 * 1. `POST api/voice/session` — форма запроса (Bearer + JSON `session_id`) и разбор ответа;
 * 2. поток SSE — доставка событий и завершение по `agent.finished`;
 * 3. реконнект с `Last-Event-ID` — сервер продолжает поток, а не начинает заново;
 * 4. терминальные 409/401 — один запрос, [VoiceEvent.StreamError], без ретраев;
 * 5. 503 — ретраится (слишком много потоков временно);
 * 6. отмена сбора закрывает соединение, не дожидаясь read-timeout;
 * 7. `interrupt`/`close` — тела и заголовки POST.
 */
class VoiceGatewayClientTest {

    private lateinit var server: MockWebServer

    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    /** Быстрая политика: короткие паузы, чтобы реконнект-тесты не спали секундами. */
    private fun fastPolicy() = AdaptiveSseReconnectPolicy(
        initialDelayMs = 10, maxDelayMs = 20, maxRetries = 3, jitterFraction = 0.0,
    )

    private fun client() = VoiceGatewayClient(
        streamClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
        restClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        baseUrl = server.url("/").toString(),
        reconnectPolicyFactory = ::fastPolicy,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body.joinToString(""))

    private fun frame(id: String, event: String, data: String) = "id: $id\nevent: $event\ndata: $data\n\n"

    private fun readyFrame(id: String = "v1_abc:1") = frame(
        id, "session.ready",
        """{"protocol":"hermes.voice.v1","voice_id":"v1_abc","session_id":"s-1",""" +
            """"server_events":["session.ready","agent.text.delta"],"keepalive_seconds":5}""",
    )

    private fun finishedFrame(id: String = "v1_abc:9", reason: String = "done") =
        frame(id, "agent.finished", """{"reason":"$reason","session_id":"s-1"}""")

    // ---- 1. createSession ---------------------------------------------------------------

    @Test
    fun `createSession posts session id with bearer and parses the voice id`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"voice_id":"v1_abc","session_id":"s-1","expires_in":300}""")
        )
        val result = client().createSession("s-1", credential)

        assertTrue("expected Success, got $result", result is VoiceResult.Success)
        result as VoiceResult.Success
        assertEquals(VoiceSession(voiceId = "v1_abc", sessionId = "s-1", expiresInSeconds = 300), result.value)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/voice/session", request.path)
        assertEquals("Bearer native-token", request.getHeader("Authorization"))
        assertEquals("""{"session_id":"s-1"}""", request.body.readUtf8())
    }

    @Test
    fun `createSession maps 404 to a typed http failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"unknown session"}"""))
        val result = client().createSession("gone", credential)

        assertTrue(result is VoiceResult.Failure)
        val error = (result as VoiceResult.Failure).error
        assertEquals(VoiceGatewayError.Http(404, "HTTP 404: unknown session"), error)
        assertFalse("404 must not be retried", error.isRetryable)
    }

    // ---- 2. happy-path stream ------------------------------------------------------------

    @Test
    fun `events streams session, deltas and the finish frame`() = runBlocking {
        server.enqueue(
            sse(
                readyFrame(),
                frame("v1_abc:2", "transcript.final", """{"text":"привет","session_id":"s-1","final":true}"""),
                frame("v1_abc:3", "agent.text.delta", """{"text":"Здравствуй"}"""),
                ": keepalive\n\n",
                finishedFrame("v1_abc:4"),
            ).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        val events = withTimeout(15_000) {
            client().events("v1_abc", credential).toList()
        }

        assertEquals(4, events.size)
        assertTrue(events[0] is VoiceEvent.SessionReady)
        assertEquals(
            VoiceEvent.TranscriptFinal("привет", "s-1", final = true),
            events[1],
        )
        assertEquals(VoiceEvent.AgentTextDelta("Здравствуй"), events[2])
        assertEquals(AgentFinishedReason.DONE, (events[3] as VoiceEvent.AgentFinished).reason)

        val request = server.takeRequest()
        assertEquals("/api/voice/stream?voice_id=v1_abc", request.path)
        assertEquals("Bearer native-token", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals(null, request.getHeader("Last-Event-ID"))
    }

    // ---- 3. reconnect with Last-Event-ID -------------------------------------------------

    @Test
    fun `events resumes with Last-Event-ID after a dropped connection`() = runBlocking {
        // Первая попытка: привязка сессии и один кадр, затем сокет закрыт.
        server.enqueue(
            sse(
                readyFrame("v1_abc:1"),
                frame("v1_abc:2", "agent.text.delta", """{"text":"Здрав"}"""),
            ).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        // Вторая попытка: сервер продолжает с того же места и завершает ход.
        server.enqueue(
            sse(
                frame("v1_abc:3", "agent.text.delta", """{"text":"ствуй"}"""),
                finishedFrame("v1_abc:4"),
            ).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )

        val events = withTimeout(20_000) { client().events("v1_abc", credential).toList() }

        assertEquals(4, events.size)
        assertEquals(
            "Здравствуй",
            events.filterIsInstance<VoiceEvent.AgentTextDelta>().joinToString("") { it.text },
        )
        assertEquals(AgentFinishedReason.DONE, (events.last() as VoiceEvent.AgentFinished).reason)

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        assertEquals(null, first.getHeader("Last-Event-ID"))
        val second = server.takeRequest()
        assertEquals("v1_abc:2", second.getHeader("Last-Event-ID"))
        assertEquals("/api/voice/stream?voice_id=v1_abc", second.path)
    }

    // ---- 4. terminal statuses ------------------------------------------------------------

    @Test
    fun `events treats 409 as terminal without retrying`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"error":"voice stream already attached"}""")
        )
        val events = withTimeout(15_000) { client().events("v1_abc", credential).toList() }

        assertEquals(1, events.size)
        val error = (events.single() as VoiceEvent.StreamError).error
        assertEquals(VoiceGatewayError.Http(409, "HTTP 409: voice stream already attached"), error)
        assertFalse(error.isRetryable)
        assertEquals("409 must be terminal", 1, server.requestCount)
    }

    @Test
    fun `events treats 401 as terminal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val events = withTimeout(15_000) { client().events("v1_abc", credential).toList() }

        val error = (events.single() as VoiceEvent.StreamError).error
        assertEquals(401, (error as VoiceGatewayError.Http).code)
        assertEquals(1, server.requestCount)
    }

    // ---- 5. retryable status -------------------------------------------------------------

    @Test
    fun `events retries 503 and then follows the stream`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"too many streams"}"""))
        server.enqueue(
            sse(readyFrame(), finishedFrame("v1_abc:2")).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )

        val events = withTimeout(20_000) { client().events("v1_abc", credential).toList() }

        assertTrue(events.first() is VoiceEvent.SessionReady)
        assertEquals(AgentFinishedReason.DONE, (events.last() as VoiceEvent.AgentFinished).reason)
        assertEquals(2, server.requestCount)
    }

    // ---- 6. cancellation -----------------------------------------------------------------

    @Test
    fun `cancelling the collector closes the connection promptly`() = runBlocking {
        // Сервер принимает запрос и молчит: без отмены чтение висело бы до read-timeout.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = launch {
            client().events("v1_abc", credential).collect { }
        }
        withTimeout(10_000) {
            while (server.requestCount == 0) delay(10)
        }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    // ---- 7. interrupt / close ------------------------------------------------------------

    @Test
    fun `interrupt posts voice and session ids and parses the flags`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"voice_id":"v1_abc","interrupted":false,"already_interrupted":true}"""
            )
        )
        val result = client().interrupt("v1_abc", "s-1", credential)

        assertTrue(result is VoiceResult.Success)
        assertEquals(
            VoiceInterruptResult(voiceId = "v1_abc", interrupted = false, alreadyInterrupted = true),
            (result as VoiceResult.Success).value,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/voice/interrupt", request.path)
        assertEquals("Bearer native-token", request.getHeader("Authorization"))
        assertTrue(
            "unexpected Content-Type: ${request.getHeader("Content-Type")}",
            request.getHeader("Content-Type")!!.startsWith("application/json"),
        )
        assertEquals("""{"voice_id":"v1_abc","session_id":"s-1"}""", request.body.readUtf8())
    }

    @Test
    fun `close posts voice and session ids and parses closed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"voice_id":"v1_abc","closed":true}""")
        )
        val result = client().close("v1_abc", "s-1", credential)

        assertEquals(
            VoiceCloseResult(voiceId = "v1_abc", closed = true),
            (result as VoiceResult.Success).value,
        )
        val request = server.takeRequest()
        assertEquals("/api/voice/close", request.path)
        assertEquals("""{"voice_id":"v1_abc","session_id":"s-1"}""", request.body.readUtf8())
    }

    @Test
    fun `interrupt maps a server error to a typed failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"too many streams"}"""))
        val result = client().interrupt("v1_abc", "s-1", credential)

        val error = (result as VoiceResult.Failure).error
        assertEquals(VoiceGatewayError.Http(503, "HTTP 503: too many streams"), error)
        assertTrue("503 is worth retrying", error.isRetryable)
    }
}
