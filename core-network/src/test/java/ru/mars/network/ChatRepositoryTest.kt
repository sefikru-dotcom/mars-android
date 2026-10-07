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
    fun uploadsAttachmentAsMultipartAndParsesResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"filename\":\"photo.jpg\",\"path\":\"/uploads/photo.jpg\",\"size\":3,\"mime\":\"image/jpeg\",\"is_image\":true}"))
            val bytes = byteArrayOf(1, 2, 3)
            val result = ChatRepository().uploadAttachment(server.url("/").toString(), credential(), "photo.jpg", "image/jpeg", bytes, "session-1")
            assertEquals(ru.mars.network.model.ChatAttachment("photo.jpg", "/uploads/photo.jpg", "image/jpeg", 3L, true), result)
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
                ChatEvent.Thought("Требуется подтверждение: Выполнить rm?"),
                ChatEvent.Thought("Требуется уточнение: Какой файл? (варианты: a / b)"),
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
