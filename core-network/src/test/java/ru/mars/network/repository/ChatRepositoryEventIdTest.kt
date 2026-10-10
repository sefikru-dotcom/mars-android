package ru.mars.network.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * Contract tests for the `onEventId` resume-cursor callback on MockWebServer:
 * - EVERY id-bearing SSE frame fires it — tokens, `todo_state` (diverted away from the
 *   ChatEvent flow), `done` — not only frames mapped to [ChatEvent.Chunk];
 * - frames without an `id:` never fire it;
 * - it runs BEFORE mapping/diversion, in wire order;
 * - `attachToStream(lastEventId = …)` sends the `Last-Event-ID` header;
 * - the pre-existing signatures (no callback) behave exactly as before.
 */
class ChatRepositoryEventIdTest {

    private lateinit var server: MockWebServer
    private val repository = ChatRepository()
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

    /**
     * Wire fixture: 5 frames, 4 of them id-bearing — including the `todo_state` frame
     * (diverted, never a ChatEvent) and a token frame WITHOUT an id (must not fire).
     */
    private val sseBody =
        "id: st-1:1\nevent: session\ndata: {\"session_id\":\"s-1\"}\n\n" +
            "id: st-1:2\nevent: token\ndata: {\"text\":\"привет\"}\n\n" +
            "id: st-1:3\nevent: todo_state\n" +
            "data: {\"todos\":[{\"id\":1,\"content\":\"Шаг\",\"status\":\"in_progress\"}]," +
            "\"summary\":{\"total\":1,\"pending\":0,\"in_progress\":1,\"completed\":0,\"cancelled\":0},\"version\":1}\n\n" +
            "event: token\ndata: {\"text\":\"без-id\"}\n\n" +
            "id: st-1:5\nevent: done\ndata: {}\n\n"

    private val expectedIds = listOf("st-1:1", "st-1:2", "st-1:3", "st-1:5")

    private fun enqueueSse() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )
    }

    @Test
    fun `attachToStream fires onEventId for every id-bearing frame including todo_state`() = runBlocking {
        enqueueSse()
        val ids = mutableListOf<String>()
        val todos = mutableListOf<ru.mars.network.model.TodoSnapshot>()

        val events = repository.attachToStream(
            baseUrl = server.url("/").toString(),
            credential = credential,
            streamId = "st-1",
            onTodoState = { todos.add(it) },
            onEventId = { ids.add(it) },
        ).toList()

        assertEquals(expectedIds, ids)
        // the todo_state frame was diverted (no ChatEvent carrier) but STILL fired onEventId
        assertEquals(1, todos.size)
        assertEquals("s-1", (events.filterIsInstance<ChatEvent.SessionBound>().single()).sessionId)
        // the id-less token frame still produced a Chunk — with a null eventId
        val chunks = events.filterIsInstance<ChatEvent.Chunk>()
        assertEquals(listOf("привет", "без-id"), chunks.map { it.text })
        assertNull(chunks[1].eventId)
        assertEquals("st-1:2", chunks[0].eventId)
        assertTrue(events.contains(ChatEvent.Done))
        assertTrue(events.first() is ChatEvent.StreamStarted)
        // no explicit cursor -> no Last-Event-ID header
        val request = server.takeRequest()
        assertEquals("api/chat/stream", request.path?.removePrefix("/")?.substringBefore("?"))
        assertNull(request.getHeader("Last-Event-ID"))
    }

    @Test
    fun `attachToStream sends Last-Event-ID header when a cursor is provided`() = runBlocking {
        enqueueSse()
        val ids = mutableListOf<String>()
        repository.attachToStream(
            baseUrl = server.url("/").toString(),
            credential = credential,
            streamId = "st-1",
            lastEventId = "st-1:7",
            onEventId = { ids.add(it) },
        ).toList()
        assertEquals("st-1:7", server.takeRequest().getHeader("Last-Event-ID"))
        // the replayed/live ids keep flowing into the callback
        assertEquals(expectedIds, ids)
    }

    @Test
    fun `tracker wired through onEventId ends at the newest cursor`() = runBlocking {
        enqueueSse()
        val tracker = StreamResumeTracker()
        repository.attachToStream(
            baseUrl = server.url("/").toString(),
            credential = credential,
            streamId = "st-1",
            onEventId = tracker::onFrameId,
        ).toList()
        assertEquals("st-1:5", tracker.lastEventId)
    }

    @Test
    fun `sendMessage two-step path fires onEventId for every frame`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("{\"stream_id\":\"st-1\",\"session_id\":\"s-1\"}")
        )
        enqueueSse()
        val ids = mutableListOf<String>()

        val events = repository.sendMessage(
            endpoint = server.url("/").toString(),
            credential = credential,
            message = "привет",
            onEventId = { ids.add(it) },
        ).toList()

        assertEquals(expectedIds, ids)
        assertEquals("st-1", (events.filterIsInstance<ChatEvent.StreamStarted>().single()).streamId)
        // SessionBound arrives TWICE by design: from the start response and from the
        // SSE `session` frame — both must bind the same session.
        val bound = events.filterIsInstance<ChatEvent.SessionBound>()
        assertTrue(bound.isNotEmpty() && bound.all { it.sessionId == "s-1" })
        assertTrue(events.contains(ChatEvent.Done))
        assertEquals("api/chat/start", server.takeRequest().path?.removePrefix("/"))
        val streamRequest = server.takeRequest()
        assertEquals("api/chat/stream", streamRequest.path?.removePrefix("/")?.substringBefore("?"))
        assertTrue(streamRequest.path!!.contains("stream_id=st-1"))
    }

    @Test
    fun `sendMessage legacy chat path fires onEventId`() = runBlocking {
        enqueueSse()
        val ids = mutableListOf<String>()
        repository.sendMessage(
            endpoint = server.url("/chat").toString(),
            credential = credential,
            message = "привет",
            onEventId = { ids.add(it) },
        ).toList()
        assertEquals(expectedIds, ids)
        assertEquals("/chat", server.takeRequest().path)
    }

    @Test
    fun `callbacks stay optional - existing call shapes are untouched`() = runBlocking {
        enqueueSse()
        // The pre-onEventId signature (named onTodoState only) must compile and work.
        val events = repository.attachToStream(
            baseUrl = server.url("/").toString(),
            credential = credential,
            streamId = "st-1",
            onTodoState = { },
        ).toList()
        assertTrue(events.contains(ChatEvent.Done))
        assertTrue(events.none { it is ChatEvent.Error })
    }
}
