package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

class RichChatRepositoryTest {
    @Test fun parsesRichEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: session_bound\ndata: {\"session_id\":\"s1\"}\n\n" +
                "id: c1\nevent: chunk\ndata: {\"text\":\"hi\"}\n\n" +
                "event: reasoning\ndata: {\"text\":\"thinking\"}\n\n" +
                "event: tool_call\ndata: {\"id\":\"t1\",\"name\":\"search\",\"arguments\":{\"q\":\"x\"}}\n\n" +
                "event: tool_result\ndata: {\"id\":\"t1\",\"name\":\"search\",\"result\":\"ok\"}\n\n" +
                "event: phase\ndata: {\"name\":\"final\"}\n\n" +
                "event: done\ndata: {}\n\n"))
            assertEquals(listOf(ChatEvent.SessionBound("s1"), ChatEvent.Chunk("hi", "c1"), ChatEvent.Thought("thinking"),
                ChatEvent.ToolCall("t1", "search", "{\"q\":\"x\"}"), ChatEvent.ToolResult("t1", "search", "ok"), ChatEvent.Phase("final"), ChatEvent.Done),
                ChatRepository().sendMessage(server.url("/chat").toString(), credential(), "x").toList())
        }
    }
    private fun credential() = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 3600)
}
