package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.MAX_UPLOAD_BYTES
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.readBoundedUploadPayload
import java.io.ByteArrayInputStream
import java.io.InputStream

class ChatRepositoryTest {
    @Test
    fun streamsChunksAndDoneFromServerSentEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: chunk\ndata: {\"text\":\"Hel\"}\n\ndata: {\"text\":\"lo\"}\n\nevent: stream_end\ndata: {}\n\n"))
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList()

            assertEquals(listOf(ChatEvent.Chunk("Hel"), ChatEvent.Chunk("lo"), ChatEvent.Done), events)
            val request = server.takeRequest()
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("\"message\":\"Hi\""))
        }
    }

    @Test
    fun emitsErrorForUnauthorizedCredential() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList()

            assertEquals(1, events.size)
            assertTrue(events.single() is ChatEvent.Error)
            assertEquals("HTTP 401", (events.single() as ChatEvent.Error).message)
        }
    }

    @Test
    fun startsThenStreamsWithStreamIdAndOpaqueEventId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"opaque-stream\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: evt-opaque\nevent: chunk\ndata: {\"text\":\"Hi\"}\n\nevent: stream_end\ndata: {}\n\n"
            ))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            assertEquals(listOf(ChatEvent.StreamStarted("opaque-stream"), ChatEvent.Chunk("Hi", "evt-opaque"), ChatEvent.Done), events)
            val start = server.takeRequest()
            assertEquals("POST", start.method)
            assertEquals("/api/chat/start", start.path)
            assertEquals("Bearer native-token", start.getHeader("Authorization"))
            assertTrue(start.body.readUtf8().contains("\"message\":\"Hi\""))
            val stream = server.takeRequest()
            assertEquals("GET", stream.method)
            assertEquals("/api/chat/stream?stream_id=opaque-stream", stream.path)
            assertEquals("Bearer native-token", stream.getHeader("Authorization"))
        }
    }

    @Test
    fun sendsAttachmentMetadataInStartPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"s\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
            val attachment = ru.mars.network.model.ChatAttachment("photo.jpg", "/uploads/photo.jpg", "image/jpeg", 12L, true)
            ChatRepository().sendMessage(server.url("/").toString(), credential(), "Look", attachments = listOf(attachment)).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"attachments\":[{\"name\":\"photo.jpg\",\"path\":\"/uploads/photo.jpg\",\"mime\":\"image/jpeg\",\"size\":12,\"is_image\":true}]"))
        }
    }

    @Test
    fun uploadsAttachmentAsMultipartAndParsesResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"filename\":\"photo.jpg\",\"path\":\"/uploads/photo.jpg\",\"size\":3,\"mime\":\"image/jpeg\",\"is_image\":true}"))
            val bytes = byteArrayOf(1, 2, 3)
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "photo.jpg", "image/jpeg", bytes, "session-1")
            assertEquals(
                UploadOutcome.Success(ru.mars.network.model.ChatAttachment("photo.jpg", "/uploads/photo.jpg", "image/jpeg", 3L, true)),
                outcome
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/upload", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"session_id\""))
            assertTrue(body.contains("session-1"))
            assertTrue(body.contains("name=\"file\"; filename=\"photo.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
        }
    }

    /** Real server rejection for an unknown session: 404 `{"error": "Session not found"}` (api/upload.py). */
    @Test
    fun uploadSessionNotFoundSurfacesServerMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\": \"Session not found\"}"))
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "a.txt", "text/plain", "hi".toByteArray(), "ghost")
            assertEquals(UploadOutcome.Failure("Session not found"), outcome)
        }
    }

    /** Real server limit response: 413 `{"error": "File too large (max 20MB)"}` (api/upload.py handle_upload). */
    @Test
    fun upload413SurfacesServerLimitMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(413).setBody("{\"error\": \"File too large (max 20MB)\"}"))
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "big.bin", "application/octet-stream", ByteArray(1024), "session-1")
            assertEquals(UploadOutcome.TooLarge("File too large (max 20MB)"), outcome)
        }
    }

    @Test
    fun uploadHttpFailureIsExplicitInsteadOfSilentNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "a.txt", "text/plain", "hi".toByteArray(), null)
            assertEquals(UploadOutcome.Failure("HTTP 500"), outcome)
        }
    }

    /** Client-side mirror of the server cap: oversized payloads never reach the network. */
    @Test
    fun oversizedUploadRejectedClientSideWithoutNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            val outcome = ChatRepository().uploadAttachment(
                server.url("/").toString(), credential(), "big.bin", "application/octet-stream",
                ByteArray((MAX_UPLOAD_BYTES + 1).toInt()), null
            )
            assertTrue(outcome is UploadOutcome.TooLarge)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun uploadCapMirrorsServerDefault() {
        // api/config.py: MAX_UPLOAD_BYTES = _env_mb_bytes("HERMES_WEBUI_MAX_UPLOAD_MB", 20)
        assertEquals(20L * 1024 * 1024, MAX_UPLOAD_BYTES)
    }

    @Test
    fun malformedUploadSuccessIsExplicitFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not json"))
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "a.txt", "text/plain", "hi".toByteArray(), null)
            assertEquals(UploadOutcome.Failure("Malformed upload response"), outcome)
        }
    }

    @Test
    fun uploadSuccessWithoutPathIsExplicitFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"filename\":\"a.txt\",\"size\":2,\"mime\":\"text/plain\",\"is_image\":false}"))
            val outcome = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "a.txt", "text/plain", "hi".toByteArray(), null)
            assertEquals(UploadOutcome.Failure("Upload response missing path"), outcome)
        }
    }

    @Test
    fun readBoundedAcceptsPayloadAtTheCap() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        assertTrue(bytes.contentEquals(readBoundedUploadPayload(ByteArrayInputStream(bytes), bytes.size.toLong())))
        assertEquals(0, readBoundedUploadPayload(ByteArrayInputStream(ByteArray(0)), 16).size)
    }

    @Test
    fun readBoundedStopsAtTheCapAndRejectsLargerStreams() {
        val source = CountingInputStream(10_000_000)
        val error = try {
            readBoundedUploadPayload(source, maxBytes = 1024)
            throw AssertionError("expected UploadTooLargeException")
        } catch (expected: UploadTooLargeException) {
            expected
        }
        assertEquals(1024L, error.maxBytes)
        // Bounded: at most cap+1 bytes were pulled from the stream, never a full readBytes().
        assertTrue("read ${source.delivered} bytes", source.delivered <= 1025)
    }

    @Test
    fun chatStartCarriesSelectedModelAndProvider() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"st-1\",\"session_id\":\"s-1\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: token\ndata: {\"text\":\"ok\"}\n\nevent: stream_end\ndata: {}\n\n"))

            ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                model = "gemini-3.8-flash", modelProvider = "custom:vertex-gemini", explicitModelPick = true
            ).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"model\":\"gemini-3.8-flash\""))
            assertTrue(body.contains("\"model_provider\":\"custom:vertex-gemini\""))
            assertTrue(body.contains("\"explicit_model_pick\":true"))
        }
    }

    /** A server-default (non user-picked) model is sent without the explicit_model_pick flag. */
    @Test
    fun chatStartSendsServerDefaultModelWithoutExplicitPick() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"st-1\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: stream_end\ndata: {}\n\n"))

            ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                model = "@custom:plusvibe-api:gpt-6.1-sol", modelProvider = "custom:plusvibe-api"
            ).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"model\":\"@custom:plusvibe-api:gpt-6.1-sol\""))
            assertTrue(body.contains("\"model_provider\":\"custom:plusvibe-api\""))
            assertFalse(body.contains("explicit_model_pick"))
        }
    }

    /** Regression: the default request (no session, no model selection) keeps its exact JSON body. */
    @Test
    fun defaultChatStartRequestIsUnchanged() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"st-1\",\"session_id\":\"s-1\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: stream_end\ndata: {}\n\n"))

            ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/chat/start", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertEquals("{\"message\":\"Hi\",\"attachments\":[]}", request.body.readUtf8())
        }
    }

    /** Regression: with a session id the default request also carries no model keys. */
    @Test
    fun defaultChatStartWithSessionIdOmitsModelFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"stream_id\":\"st-1\",\"session_id\":\"s-9\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: stream_end\ndata: {}\n\n"))

            ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi", sessionId = "s-9").toList()

            val body = server.takeRequest().body.readUtf8()
            assertEquals("{\"message\":\"Hi\",\"session_id\":\"s-9\",\"attachments\":[]}", body)
        }
    }

    /**
     * Request-level parity with the real GET /api/models payload (captured fixture):
     * the catalog's default row (id + group provider_id) flows into chat/start verbatim.
     */
    @Test
    fun chatStartCarriesRealCatalogDefaultIntoRequest() = runBlocking {
        val fixture = javaClass.classLoader!!.getResource("fixtures/api_models.json")!!.readText()
        MockWebServer().use { modelsServer ->
            modelsServer.enqueue(MockResponse().setBody(fixture))
            val catalog = ModelsRepository().getModels(modelsServer.url("/").toString(), credential())
            val defaultRow = catalog.models.first { it.id == catalog.defaultModel }
            assertEquals("gemini-3.8-flash", defaultRow.id)
            assertEquals("custom:vertex-gemini", defaultRow.providerId)

            MockWebServer().use { chatServer ->
                chatServer.enqueue(MockResponse().setBody("{\"stream_id\":\"st-1\",\"session_id\":\"s-1\"}"))
                chatServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: stream_end\ndata: {}\n\n"))

                ChatRepository().sendMessage(
                    chatServer.url("/").toString(), credential(), "Hi",
                    model = defaultRow.id, modelProvider = defaultRow.providerId
                ).toList()

                val body = chatServer.takeRequest().body.readUtf8()
                assertTrue(body.contains("\"model\":\"gemini-3.8-flash\""))
                assertTrue(body.contains("\"model_provider\":\"custom:vertex-gemini\""))
            }
        }
    }

    /** InputStream that counts how many bytes were actually pulled, to prove bounded reads. */
    private class CountingInputStream(private val total: Int) : InputStream() {
        var delivered = 0
            private set
        override fun read(): Int =
            if (delivered >= total) -1 else { delivered++; 'M'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered >= total || len <= 0) return -1
            val n = minOf(len, total - delivered)
            delivered += n
            return n
        }
    }

    @Test
    fun buildsCancelAndSteerRequestsWithExactRoutes() {
        val repository = ChatRepository()
        val cancel = repository.cancelRequest("https://mars.test", "stream/opaque", "native-token")
        assertEquals("GET", cancel.method)
        assertEquals("/api/chat/cancel", cancel.url.encodedPath)
        assertEquals("stream/opaque", cancel.url.queryParameter("stream_id"))
        assertEquals("Bearer native-token", cancel.newBuilder().header("Authorization", "Bearer native-token").build().header("Authorization"))
        val steer = repository.steerRequest("https://mars.test", "session-1", "new direction", "native-token")
        assertEquals("POST", steer.method)
        assertEquals("/api/chat/steer", steer.url.encodedPath)
        assertEquals("Bearer native-token", steer.header("Authorization"))
        assertTrue(steer.body!!.contentType().toString().startsWith("application/json"))
        val steerBody = okio.Buffer().also { steer.body!!.writeTo(it) }.readUtf8()
        assertTrue("steer body must use session_id/text per server contract: $steerBody",
            steerBody.contains("\"session_id\":\"session-1\"") && steerBody.contains("\"text\":\"new direction\""))
        assertTrue(!steerBody.contains("stream_id") && !steerBody.contains("\"message\""))
    }

    @Test
    fun steerAcceptedFalseIsReportedAsFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"accepted\": false, \"fallback\": \"no_cached_agent\", \"stream_id\": null}"))
            val repository = ChatRepository()
            val ok = repository.executeControl(repository.steerRequest(server.url("/").toString(), "session-1", "do it", "native-token"))
            assertFalse("HTTP 200 with accepted:false must be a failure", ok)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/chat/steer", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"session-1\""))
            assertTrue(body.contains("\"text\":\"do it\""))
        }
    }

    @Test
    fun steerAcceptedTrueIsReportedAsSuccess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"accepted\": true, \"fallback\": null, \"stream_id\": \"st-1\"}"))
            val repository = ChatRepository()
            assertTrue(repository.executeControl(repository.steerRequest(server.url("/").toString(), "s1", "go", "t")))
        }
    }

    @Test
    fun controlFailureWithoutAcceptedFieldStillFollowsHttpStatus() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\": true, \"cancelled\": true, \"stream_id\": \"st-1\"}"))
            server.enqueue(MockResponse().setResponseCode(502).setBody("{\"ok\": false, \"error\": \"Gateway stop failed\"}"))
            val repository = ChatRepository()
            assertTrue(repository.executeControl(repository.cancelRequest(server.url("/").toString(), "st-1", "t")))
            assertFalse(repository.executeControl(repository.cancelRequest(server.url("/").toString(), "st-1", "t")))
        }
    }

    @Test
    fun streamsRealServerEventSequenceWithoutLosingEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: session_bound\ndata: {\"session_id\":\"s1\"}\n\n" +
                "id: e1\nevent: token\ndata: {\"text\":\"Привет\"}\n\n" +
                "event: tool\ndata: {\"event_type\":\"tool.started\",\"name\":\"terminal\",\"preview\":null,\"args\":{\"command\":\"ls\"},\"tid\":\"call_1\"}\n\n" +
                "event: tool_complete\ndata: {\"event_type\":\"tool.completed\",\"name\":\"terminal\",\"preview\":\"a.txt\",\"args\":{\"command\":\"ls\"},\"tid\":\"call_1\",\"is_error\":false}\n\n" +
                "event: interim_assistant\ndata: {\"text\":\"Смотрю файлы.\",\"already_streamed\":false}\n\n" +
                "event: interim_assistant\ndata: {\"text\":\"Привет\",\"already_streamed\":true}\n\n" +
                "event: approval\ndata: {\"kind\":\"approval\",\"session_id\":\"s1\",\"description\":\"Выполнить rm?\",\"pending_count\":1}\n\n" +
                "event: clarify\ndata: {\"question\":\"Какой файл?\",\"choices_offered\":[\"a\",\"b\"],\"session_id\":\"s1\",\"kind\":\"clarify\"}\n\n" +
                "event: warning\ndata: {\"type\":\"fallback\",\"message\":\"fallback model\"}\n\n" +
                "event: metering\ndata: {\"session_id\":\"s1\"}\n\n" +
                "event: pending_steer_leftover\ndata: {\"session_id\":\"s1\",\"text\":\"еще тесты\"}\n\n" +
                "event: done\ndata: {}\n\n" +
                "event: title\ndata: {\"session_id\":\"s1\",\"title\":\"Отладка\"}\n\n" +
                "event: stream_end\ndata: {}\n\n"))
            val events = ChatRepository().sendMessage(server.url("/chat").toString(), credential(), "x").toList()
            assertEquals(listOf(
                ChatEvent.SessionBound("s1"),
                ChatEvent.Chunk("Привет", "e1"),
                ChatEvent.ToolCall("call_1", "terminal", "{\"command\":\"ls\"}"),
                ChatEvent.ToolResult("call_1", "terminal", "a.txt"),
                ChatEvent.Chunk("Смотрю файлы.", null),
                // approval/clarify now map to typed events preserving ids/options (interactive contracts).
                ChatEvent.ApprovalRequired(ru.mars.network.model.ApprovalPayload(
                    approvalId = null, command = "", description = "Выполнить rm?", pendingCount = 1)),
                ChatEvent.ClarifyRequired(ru.mars.network.model.ClarifyPayload(
                    clarifyId = null, question = "Какой файл?", choices = listOf("a", "b"), sessionId = "s1")),
                ChatEvent.Thought("Предупреждение: fallback model"),
                ChatEvent.Thought("Направление не доставлено: еще тесты"),
                ChatEvent.Done,
                ChatEvent.SessionBound("s1"),
                ChatEvent.Done,
            ), events)
        }
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )
}
