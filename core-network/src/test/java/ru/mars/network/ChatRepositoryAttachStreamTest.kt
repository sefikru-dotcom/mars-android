package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

/**
 * Request/behaviour contract of [ChatRepository.attachToStream] — the reattach path onto an
 * already-running turn (`GET /api/chat/stream?stream_id=…`, routes.py `_handle_sse_stream`),
 * which must never mint a new turn via `POST /api/chat/start`.
 */
class ChatRepositoryAttachStreamTest {
    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )

    @Test fun attachStreamsExistingTurnWithoutChatStart() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: st-9:1\nevent: chunk\ndata: {\"text\":\"живой \"}\n\n" +
                    "id: st-9:2\nevent: chunk\ndata: {\"text\":\"поток\"}\n\n" +
                    "event: stream_end\ndata: {}\n\n"
            ))
            val events = ChatRepository().attachToStream(server.url("/").toString(), credential(), "st-9").toList()
            assertEquals(listOf(
                ChatEvent.StreamStarted("st-9"),
                ChatEvent.Chunk("живой ", "st-9:1"),
                ChatEvent.Chunk("поток", "st-9:2"),
                ChatEvent.Done,
            ), events)
            // Exactly one request: the reattach GET — no POST /api/chat/start was sent.
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/chat/stream?stream_id=st-9", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertNull(request.getHeader("Last-Event-ID"))
            assertEquals(0, request.bodySize)
        }
    }

    /** The resume cursor rides as the spec `Last-Event-ID` header the server honors after explicit query params. */
    @Test fun attachSendsResumeCursorAsLastEventId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: done\ndata: {}\n\n"
            ))
            val events = ChatRepository().attachToStream(server.url("/").toString(), credential(), "st-9", lastEventId = "st-9:7").toList()
            assertEquals(listOf(ChatEvent.StreamStarted("st-9"), ChatEvent.Done), events)
            val request = server.takeRequest()
            assertEquals("/api/chat/stream?stream_id=st-9", request.path)
            assertEquals("st-9:7", request.getHeader("Last-Event-ID"))
        }
    }

    /** A blank cursor is transport noise — the header must not be sent at all. */
    @Test fun attachIgnoresBlankResumeCursor() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
            ChatRepository().attachToStream(server.url("/").toString(), credential(), "st-9", lastEventId = "  ").toList()
            assertNull(server.takeRequest().getHeader("Last-Event-ID"))
        }
    }

    /** Unknown/expired stream: the server answers 404 `{"error":"stream not found"}` — surfaced as a terminal ChatEvent.Error carrying the status AND the server reason. */
    @Test fun attachToDeadStreamSurfacesHttpError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"stream not found\"}"))
            val events = ChatRepository().attachToStream(server.url("/").toString(), credential(), "gone").toList()
            assertEquals(2, events.size)
            assertEquals(ChatEvent.StreamStarted("gone"), events[0])
            assertTrue(events[1] is ChatEvent.Error)
            assertEquals("HTTP 404: stream not found", (events[1] as ChatEvent.Error).message)
        }
    }

    /** Stream ids with reserved characters must reach the query intact (percent-encoded by the URL builder). */
    @Test fun attachEncodesStreamIdInQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
            ChatRepository().attachToStream(server.url("/").toString(), credential(), "run:42/x?y").toList()
            val path = server.takeRequest().path!!
            assertTrue(path.startsWith("/api/chat/stream?stream_id="))
            assertTrue("raw reserved chars must be encoded: $path", path.contains("run%3A42%2Fx%3Fy"))
        }
    }
}
