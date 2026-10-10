package ru.mars.network

import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ErrorOrigin
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.StreamResumeTracker
import ru.mars.network.repository.UploadOutcome
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Request-level transport contract tests for the SSE/recovery seam (MockWebServer):
 *
 * 1. IDLE GAP — a stream that goes silent past the (injected) read timeout surfaces a
 *    TRANSPORT-origin [ChatEvent.Error]; the repository defaults to the shared
 *    `HttpClients.stream` whose 90 s read timeout is sized for long tool calls, and the
 *    client stays injectable so this test can shrink the gap.
 * 2. CANCEL — cancelling the collector must NOT ack the cursor of a frame that was read
 *    but never delivered: on resume the server replay would skip exactly that frame.
 * 3. REST BOUND — control/upload calls go through the bounded `restClient`, not the
 *    long-lived stream client (a hung cancel must not sit for 90 s).
 * 4. HTTP FAILURE — non-2xx responses carry the `HTTP <code>: <reason>` shape with
 *    ErrorOrigin.TRANSPORT; server `error` frames carry ErrorOrigin.SERVER. These are
 *    the inputs the mid-turn recovery policy regression (MidTurnRecoveryCoordinatorTest)
 *    classifies — arbitrary server text must never become a network retry.
 */
class ChatRepositorySseRecoveryTransportTest {

    private lateinit var server: MockWebServer
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
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

    private fun tokenFrame(id: String, text: String) =
        "id: $id\nevent: token\ndata: {\"text\":\"$text\"}\n\n"

    // ---- 1. idle gap --------------------------------------------------------------------

    @Test
    fun `idle gap past the read timeout surfaces a TRANSPORT-origin error`() = runBlocking {
        // 20 bytes per 800 ms — with a 300 ms injected read timeout the client hits a
        // silent gap mid-frame, exactly like a dead radio on a healthy-looking stream.
        server.enqueue(
            sse(tokenFrame("st-1:1", "aaaa"), tokenFrame("st-1:2", "bbbb"))
                .throttleBody(20, 800, TimeUnit.MILLISECONDS)
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val events = ChatRepository(client).attachToStream(
            baseUrl = server.url("/").toString(), credential = credential, streamId = "st-1",
        ).toList()

        val error = events.last()
        assertTrue("expected terminal ChatEvent.Error, got $error", error is ChatEvent.Error)
        error as ChatEvent.Error
        assertEquals(ErrorOrigin.TRANSPORT, error.origin)
        // describeNetworkError(SocketTimeoutException) mapping — retryable network bucket
        assertTrue(error.message.contains("ожидания"))
    }

    // ---- 2. cancel vs cursor ack ---------------------------------------------------------

    @Test
    fun `cancelling the collector does not ack the undelivered frame`() = runBlocking {
        // Frame 1 is delivered at once; frame 2 arrives 1 s later. The collector cancels
        // on the first Chunk. The producer still READS frame 2 (blocking socket read is
        // not cancellable), but its emit must fail on the cancelled flow — and the cursor
        // must stay at st-1:1 so the server replay covers frame 2 on resume.
        // (Pre-fix behaviour acked st-1:2 BEFORE emitting — the frame was lost.)
        val f1 = tokenFrame("st-1:1", "aaaa")
        val f2 = tokenFrame("st-1:2", "bbbb")
        assertEquals(f1.length, f2.length) // throttle period boundary lands between frames
        server.enqueue(sse(f1, f2).throttleBody(f1.length.toLong(), 1, TimeUnit.SECONDS))

        val tracker = StreamResumeTracker()
        val job = launch {
            ChatRepository().attachToStream(
                baseUrl = server.url("/").toString(), credential = credential, streamId = "st-1",
                onEventId = tracker::onFrameId,
            ).collect { event ->
                if (event is ChatEvent.Chunk) throw CancellationException("stop after first chunk")
            }
        }
        withTimeout(15_000) { job.join() } // join waits for the flowOn producer to unwind
        assertTrue(job.isCancelled)
        assertEquals("st-1:1", tracker.lastEventId)
    }

    // ---- 3. REST bound --------------------------------------------------------------------

    @Test
    fun `control calls fail bounded through the injected restClient`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val rest = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()
        val repository = ChatRepository(restClient = rest)
        val request = repository.cancelRequest(server.url("/").toString(), "st-1", credential.accessToken)

        val startedAt = System.nanoTime()
        val outcome = runCatching { repository.executeControl(request) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("expected IOException, got $outcome", outcome.exceptionOrNull() is IOException)
        assertTrue("control call must not inherit the SSE read timeout, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `upload fails bounded through the injected restClient`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val rest = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()
        val startedAt = System.nanoTime()
        val outcome = ChatRepository(restClient = rest).uploadAttachment(
            server.url("/").toString(), credential, "f.txt", "text/plain", ByteArray(16), null,
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("expected Failure, got $outcome", outcome is UploadOutcome.Failure)
        assertTrue("upload must fail bounded, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    // ---- 4. HTTP failure / server event origins --------------------------------------------

    @Test
    fun `gone stream 404 keeps the anchored TRANSPORT shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"stream not found\"}"))
        val events = ChatRepository().attachToStream(
            baseUrl = server.url("/").toString(), credential = credential, streamId = "gone",
        ).toList()
        // HTTP-level failure of the request itself → TRANSPORT (retry policy classifies
        // it via the "HTTP 404" anchor; the coordinator maps it to ReloadSession).
        assertEquals(ChatEvent.Error("HTTP 404: stream not found", ErrorOrigin.TRANSPORT), events.last())
    }

    @Test
    fun `stream GET 5xx is a TRANSPORT-origin error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"agent backend exploded\"}"))
        val events = ChatRepository().attachToStream(
            baseUrl = server.url("/").toString(), credential = credential, streamId = "st-1",
        ).toList()
        assertEquals(ChatEvent.Error("HTTP 500: agent backend exploded", ErrorOrigin.TRANSPORT), events.last())
    }

    @Test
    fun `server error frame is SERVER-origin even when its text looks like a network failure`() = runBlocking {
        // The agent runtime may fail with arbitrary text — including words that a broad
        // classifier used to read as transport verdicts ("connection reset", "not found").
        server.enqueue(
            sse(
                tokenFrame("st-1:1", "ok"),
                "id: st-1:2\nevent: error\n" +
                    "data: {\"message\":\"tool 'read_file' failed: file not found (connection reset)\"}\n\n",
            )
        )
        val events = ChatRepository().attachToStream(
            baseUrl = server.url("/").toString(), credential = credential, streamId = "st-1",
            onEventId = { },
        ).toList()
        val error = events.last()
        assertTrue(error is ChatEvent.Error)
        error as ChatEvent.Error
        assertEquals(ErrorOrigin.SERVER, error.origin)
        assertEquals("tool 'read_file' failed: file not found (connection reset)", error.message)
        // the delivered frame before the terminal error is still acked
        assertEquals(1, events.filterIsInstance<ChatEvent.Chunk>().size)
    }
}
