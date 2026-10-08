package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.CompressionJobStatus
import ru.mars.network.repository.CompressionStartResult
import ru.mars.network.repository.CompressionStatusResult
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository

/**
 * Request-level contract tests for the manual compression endpoints of hermes-webui
 * (`POST /api/session/compress/start`, `GET /api/session/compress/status`), replayed with
 * MockWebServer against the payloads produced by `_handle_session_compress_start` /
 * `_manual_compression_status_payload` (api/routes.py).
 */
class ContextCompressionRepositoryTest {

    private val repository = ContextCompressionRepository()

    // --- start ----------------------------------------------------------------------------------

    @Test
    fun startSendsSessionIdAndFocusTopicWithBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"running","session_id":"sess-1","focus_topic":"сеть","started_at":1730000000.0,"updated_at":1730000000.0}"""))
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", "сеть")

            assertEquals(CompressionStartResult.Running("сеть"), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/compress/start", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue(body.contains("\"focus_topic\":\"сеть\""))
        }
    }

    @Test
    fun startOmitsFocusTopicWhenBlank() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"running","session_id":"sess-1"}"""))
            repository.startCompression(server.url("/").toString(), credential(), "sess-1", "   ")

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue("blank focus topic must not reach the wire: $body", !body.contains("focus_topic"))
        }
    }

    @Test
    fun startMapsFastDoneWithSummary() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"ok":true,"status":"done","session_id":"sess-1","summary":{"headline":"Compressed: 40 → 6 messages","token_line":"~12000 → ~2100 tokens","note":null,"reference_message":"[CONTEXT COMPACTION — REFERENCE ONLY] ..."},"session":{"session_id":"sess-1"}}"""
                )
            )
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", null)

            val done = result as CompressionStartResult.Done
            assertEquals(
                CompressionSummary("Compressed: 40 → 6 messages", "~12000 → ~2100 tokens", null),
                done.summary,
            )
            assertEquals("Compressed: 40 → 6 messages", done.summary?.display)
        }
    }

    @Test
    fun startMapsStreamingSessionToRejected409() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Session is still streaming; cancel or wait for it to finish before compressing."}"""))
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", null)

            val rejected = result as CompressionStartResult.Rejected
            assertEquals(409, rejected.httpStatus)
            assertTrue(rejected.message.contains("Session is still streaming"))
        }
    }

    @Test
    fun startMapsUnknownSessionToRejected404() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Session not found"}"""))
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-x", null)

            assertEquals(CompressionStartResult.Rejected("Session not found", 404), result)
        }
    }

    @Test
    fun startMapsTerminalErrorPayloadToRejected() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"ok":false,"status":"error","error":"Compression failed: No provider configured -- cannot compress.","error_status":400,"session_id":"sess-1"}"""
                )
            )
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", null)

            val rejected = result as CompressionStartResult.Rejected
            assertTrue(rejected.message.contains("No provider configured"))
        }
    }

    @Test
    fun startMapsUnknown200StatusToRejectedNotPolling() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"queued"}"""))
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", null)

            assertTrue("an unknown status must not be mistaken for Running: $result", result is CompressionStartResult.Rejected)
        }
    }

    @Test
    fun startRejectsBlankSessionIdWithoutNetworkCall() {
        MockWebServer().use { server ->
            val result = repository.startCompression(server.url("/").toString(), credential(), " ", null)

            assertTrue(result is CompressionStartResult.Rejected)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun startMapsDisconnectToNetworkError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val result = repository.startCompression(server.url("/").toString(), credential(), "sess-1", null)

            assertTrue("transport failure must surface as NetworkError: $result", result is CompressionStartResult.NetworkError)
        }
    }

    // --- status ---------------------------------------------------------------------------------

    @Test
    fun statusRequestsPathWithSessionQueryAndBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"idle","session_id":"sess-1"}"""))
            val result = repository.fetchStatus(server.url("/").toString(), credential(), "sess-1")

            assertEquals(CompressionStatusResult.Found(CompressionJobStatus.IDLE, null, null), result)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            // MockWebServer's `path` includes the query — assert on the parsed URL parts.
            assertEquals("/api/session/compress/status", request.requestUrl?.encodedPath)
            assertEquals("sess-1", request.requestUrl?.queryParameter("session_id"))
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun statusMapsRunningDoneErrorAndCancelled() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"running","session_id":"s"}"""))
            assertEquals(
                CompressionStatusResult.Found(CompressionJobStatus.RUNNING, null, null),
                repository.fetchStatus(server.url("/").toString(), credential(), "s"),
            )

            server.enqueue(
                MockResponse().setBody(
                    """{"ok":true,"status":"done","session_id":"s","summary":{"headline":"Compressed: 10 → 4 messages","token_line":"~900 → ~300 tokens","note":"Focus: сеть"}}"""
                )
            )
            val done = repository.fetchStatus(server.url("/").toString(), credential(), "s") as CompressionStatusResult.Found
            assertEquals(CompressionJobStatus.DONE, done.status)
            assertEquals("сеть", done.summary?.note?.substringAfter("Focus: "))
            assertEquals("Compressed: 10 → 4 messages", done.summary?.display)

            server.enqueue(MockResponse().setBody("""{"ok":false,"status":"error","error":"boom","error_status":500,"session_id":"s"}"""))
            val error = repository.fetchStatus(server.url("/").toString(), credential(), "s") as CompressionStatusResult.Found
            assertEquals(CompressionJobStatus.ERROR, error.status)
            assertEquals("boom", error.error)

            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"cancelled","session_id":"s"}"""))
            assertEquals(
                CompressionJobStatus.CANCELLED,
                (repository.fetchStatus(server.url("/").toString(), credential(), "s") as CompressionStatusResult.Found).status,
            )
        }
    }

    @Test
    fun statusMapsHttpFailureToFailedWithServerError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"internal"}"""))
            assertEquals(
                CompressionStatusResult.Failed("internal"),
                repository.fetchStatus(server.url("/").toString(), credential(), "s"),
            )
        }
    }

    @Test
    fun statusMapsDisconnectToFailed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertTrue(repository.fetchStatus(server.url("/").toString(), credential(), "s") is CompressionStatusResult.Failed)
        }
    }

    @Test
    fun summaryParserToleratesMissingAndBlankFields() {
        assertNull(ContextCompressionRepository.parseSummary(null))
        assertNull(ContextCompressionRepository.parseSummary("not-a-map"))
        assertNull(ContextCompressionRepository.parseSummary(mapOf("headline" to "", "token_line" to null)))
        assertEquals(
            CompressionSummary(null, "~500 → ~120 tokens", null),
            ContextCompressionRepository.parseSummary(mapOf("token_line" to "~500 → ~120 tokens", "reference_message" to "x")),
        )
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )
}
