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
                .setBody("event: chunk\ndata: {\"text\":\"Hel\"}\n\ndata: {\"text\":\"lo\"}\n\nevent: done\ndata: {}\n\n"))
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

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )
}
