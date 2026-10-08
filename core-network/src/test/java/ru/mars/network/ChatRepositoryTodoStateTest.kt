package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.network.repository.ChatRepository

/**
 * Delivery contract of the live `todo_state` SSE event through
 * [ChatRepository]: the frame has NO carrier in the sealed [ChatEvent]
 * (exhaustive `when`s in the app modules forbid a new variant), so it rides a
 * non-sealed transport callback (`onTodoState`) on `sendMessage` /
 * `attachToStream` — never silently dropped, never polluting the transcript.
 */
class ChatRepositoryTodoStateTest {
    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )

    /** The verified server broadcast: `event: todo_state` + envelope `{session_id, stream_id, source, ts}` around the snapshot. */
    private val todoFrame =
        "event: todo_state\n" +
            "data: {\"session_id\":\"s-1\",\"stream_id\":\"st-1\",\"source\":\"tool\",\"ts\":1759900001.25," +
            "\"todos\":[{\"id\":\"1\",\"content\":\"Шаг один\",\"status\":\"in_progress\"}]," +
            "\"summary\":{\"total\":1,\"pending\":0,\"in_progress\":1,\"completed\":0,\"cancelled\":0},\"version\":1}\n\n"

    private val expectedSnapshot = TodoSnapshot(
        todos = listOf(TodoItem(id = "1", content = "Шаг один", status = "in_progress")),
        summary = TodoSummary(total = 1, pending = 0, inProgress = 1, completed = 0, cancelled = 0),
        version = 1,
        tsEpochSeconds = 1759900001.25,
        sessionId = "s-1",
        streamId = "st-1",
        source = "tool",
    )

    /** chat/start + chat/stream: the callback receives the snapshot, the ChatEvent flow keeps only chat content. */
    @Test fun sendMessageDeliversTodoStateViaCallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: chunk\ndata: {\"text\":\"привет\"}\n\n" +
                    todoFrame +
                    "event: stream_end\ndata: {}\n\n"
            ))
            val snapshots = mutableListOf<TodoSnapshot>()
            val events = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                onTodoState = { snapshots.add(it) },
            ).toList()

            assertEquals(listOf(expectedSnapshot), snapshots)
            assertEquals(
                listOf(
                    ChatEvent.StreamStarted("st-1"),
                    ChatEvent.SessionBound("s-1"),
                    ChatEvent.Chunk("привет"),
                    ChatEvent.Done,
                ),
                events,
            )
        }
    }

    /** Reattach replays journaled frames — a replayed `todo_state` must reach the callback too. */
    @Test fun attachToStreamDeliversTodoStateViaCallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                todoFrame + "event: stream_end\ndata: {}\n\n"
            ))
            val snapshots = mutableListOf<TodoSnapshot>()
            val events = ChatRepository().attachToStream(
                server.url("/").toString(), credential(), "st-1",
                onTodoState = { snapshots.add(it) },
            ).toList()

            assertEquals(listOf(expectedSnapshot), snapshots)
            assertEquals(listOf(ChatEvent.StreamStarted("st-1"), ChatEvent.Done), events)
        }
    }

    /** Legacy `/chat` endpoint streams inline — same side-channel contract on that call site. */
    @Test fun legacyChatEndpointDeliversTodoStateViaCallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                todoFrame + "event: chunk\ndata: {\"text\":\"ok\"}\n\nevent: stream_end\ndata: {}\n\n"
            ))
            val snapshots = mutableListOf<TodoSnapshot>()
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi",
                onTodoState = { snapshots.add(it) },
            ).toList()

            assertEquals(listOf(expectedSnapshot), snapshots)
            assertEquals(listOf(ChatEvent.Chunk("ok"), ChatEvent.Done), events)
        }
    }

    /**
     * Backward compatibility: with no callback (all existing call sites) the
     * frame is skipped exactly like before — no error, no phantom ChatEvent,
     * the rest of the stream flows unchanged.
     */
    @Test fun withoutCallbackTodoStateIsSkippedWithoutError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                todoFrame + "event: chunk\ndata: {\"text\":\"ok\"}\n\nevent: stream_end\ndata: {}\n\n"
            ))
            val events = ChatRepository().sendMessage(server.url("/chat").toString(), credential(), "Hi").toList()
            assertEquals(listOf(ChatEvent.Chunk("ok"), ChatEvent.Done), events)
            assertTrue(events.none { it is ChatEvent.Error })
        }
    }

    /** A malformed `todo_state` payload with a callback present must not break the stream or fire a half-parsed snapshot. */
    @Test fun malformedTodoStatePayloadDoesNotBreakStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: todo_state\ndata: {\"summary\":{\"total\":1}}\n\n" +
                    "event: chunk\ndata: {\"text\":\"ok\"}\n\nevent: stream_end\ndata: {}\n\n"
            ))
            val snapshots = mutableListOf<TodoSnapshot>()
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi",
                onTodoState = { snapshots.add(it) },
            ).toList()
            assertEquals(emptyList<TodoSnapshot>(), snapshots)
            assertEquals(listOf(ChatEvent.Chunk("ok"), ChatEvent.Done), events)
        }
    }
}
