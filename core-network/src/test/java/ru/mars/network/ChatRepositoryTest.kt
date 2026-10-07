package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
            assertEquals(listOf(ChatEvent.Chunk("Hi", "evt-opaque"), ChatEvent.Done), events)
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
    fun buildsCancelAndSteerRequestsWithExactRoutes() {
        val repository = ChatRepository()
        val cancel = repository.cancelRequest("https://mars.test", "stream/opaque", "native-token")
        assertEquals("GET", cancel.method)
        assertEquals("/api/chat/cancel", cancel.url.encodedPath)
        assertEquals("stream/opaque", cancel.url.queryParameter("stream_id"))
        assertEquals("Bearer native-token", cancel.newBuilder().header("Authorization", "Bearer native-token").build().header("Authorization"))
        val steer = repository.steerRequest("https://mars.test", "stream/opaque", "new direction", "native-token")
        assertEquals("POST", steer.method)
        assertEquals("/api/chat/steer", steer.url.encodedPath)
        assertEquals("Bearer native-token", steer.header("Authorization"))
        assertTrue(steer.body!!.contentType().toString().startsWith("application/json"))
        assertTrue(steer.body!!.contentLength() > 0)
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )
}
