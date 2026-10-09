package ru.mars.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.InteractiveRequestsRepository

/**
 * Request-level regression tests for hand-built JSON request bodies.
 *
 * Contract: hermes-webui parses every POST body with Python `json.loads` in
 * strict mode (api/helpers.py `read_body` → `ValueError('Invalid JSON body')`
 * → HTTP 400 in routes.py). Strict JSON (RFC 8259 §7) forbids RAW control
 * characters U+0000..U+001F inside strings — they must be escaped (`\u001b`,
 * `\n`, …). Real-world trigger: pasting terminal output (ANSI `ESC[31m`
 * sequences), text with NUL/form-feed/vertical-tab from the clipboard into
 * the composer. Before the fix the client's `jsonString` escapers passed
 * those characters through raw, so `POST /api/chat/start` died with 400 and
 * the turn never started.
 *
 * The invariant asserted here is exactly the server's rejection criterion:
 * the serialized body contains NO character below 0x20, and a strict JSON
 * parse round-trips the original value byte-for-byte.
 */
class JsonBodyControlCharRegressionTest {

    /** Terminal-paste torture string: ESC, NUL, VT, FF, BS plus normal text. */
    private val nastyText = "before\u001B[31mRED\u001B[0m\u0000\u000B\u000C\u0008after"

    // --- POST /api/chat/start ---------------------------------------------------------------

    @Test
    fun chatStartBodyEscapesRawControlCharactersInMessageAndAttachments() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: stream_end\ndata: {}\n\n")
            )
            ChatRepository().sendMessage(
                server.url("/").toString(), credential(), nastyText,
                attachments = listOf(ChatAttachment("log\u0008name.txt", "/tmp/f\u001Bile", "text/plain", 3, false)),
            ).toList()

            val start = server.takeRequest()
            assertEquals("/api/chat/start", start.path)
            val body = start.body.readUtf8()
            assertServerParsableJson(body)
            val obj = parseJsonObject(body)
            assertEquals(nastyText, obj["message"])
            val attachments = obj["attachments"] as List<*>
            val attachment = attachments.single() as Map<*, *>
            assertEquals("log\u0008name.txt", attachment["name"])
            assertEquals("/tmp/f\u001Bile", attachment["path"])
        }
    }

    @Test
    fun legacyChatBodyEscapesRawControlCharacters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: stream_end\ndata: {}\n\n")
            )
            ChatRepository().sendMessage(server.url("/chat").toString(), credential(), nastyText).toList()

            val body = server.takeRequest().body.readUtf8()
            assertServerParsableJson(body)
            assertEquals(nastyText, parseJsonObject(body)["message"])
        }
    }

    // --- POST /api/chat/steer -----------------------------------------------------------------

    @Test
    fun steerBodyEscapesRawControlCharacters() {
        val request = ChatRepository().steerRequest("https://example.com", "s-1", nastyText, "tok")
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertServerParsableJson(body)
        val obj = parseJsonObject(body)
        assertEquals("s-1", obj["session_id"])
        assertEquals(nastyText, obj["text"])
    }

    // --- POST /api/clarify/respond --------------------------------------------------------------

    @Test
    fun clarifyRespondBodyEscapesRawControlCharacters() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            InteractiveRequestsRepository().respondClarify(
                server.url("/").toString(), credential(), "s-1",
                ClarifyPayload(clarifyId = "cl-1", question = "?"),
                nastyText,
            )
            val request = server.takeRequest()
            assertEquals("/api/clarify/respond", request.path)
            val body = request.body.readUtf8()
            assertServerParsableJson(body)
            // respondClarify trims the answer; nastyText has no leading/trailing whitespace.
            assertEquals(nastyText, parseJsonObject(body)["response"])
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Mirrors the server's strict parse: Python `json.loads` rejects any raw
     * control character (code < 0x20) inside a string. Our bodies are single-line,
     * so "no character below 0x20 anywhere" is exactly that criterion.
     */
    private fun assertServerParsableJson(body: String) {
        val offenders = body.filter { it.code < 0x20 }.map { "\\u%04x".format(it.code) }
        assertTrue(
            "Body carries raw control characters $offenders — Python json.loads (strict) " +
                "answers 400 'Invalid JSON body' for these: $body",
            offenders.isEmpty()
        )
    }

    private fun parseJsonObject(body: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val map = Moshi.Builder().build()
            .adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
            .fromJson(body)
        return map!!
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )
}
