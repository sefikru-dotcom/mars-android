package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.repository.ChatRepository

/**
 * Transport contract for the SSE metadata side channel (`title` text, `goal`,
 * `goal_continue`) and the `workspace` field of the chat/start payload.
 *
 * The side frames have NO carrier in the sealed [ChatEvent] (exhaustive `when`s
 * in the app modules forbid new variants — the `todo_state` precedent), so they
 * ride the non-sealed `ChatRepository.onSideEvent` callback. Delivery is
 * ALONGSIDE the sealed flow: a `title` frame still emits [ChatEvent.SessionBound].
 * The transport must never react to `goal_continue` on its own (no auto-send of
 * the continuation prompt).
 */
class ChatRepositorySideEventsTest {
    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )

    /** Verified server frames (hermes-webui api/streaming.py put/put_event). */
    private val goalFrames =
        "event: goal\n" +
            "data: {\"session_id\":\"s-1\",\"state\":\"evaluating\",\"message\":\"Evaluating goal progress…\",\"message_key\":\"goal_evaluating_progress\"}\n\n" +
            "event: goal\n" +
            "data: {\"session_id\":\"s-1\",\"state\":\"continuing\",\"message\":\"Цель не завершена\",\"message_key\":\"goal_continuing\",\"decision\":{\"should_continue\":true}}\n\n" +
            "event: goal_continue\n" +
            "data: {\"session_id\":\"s-1\",\"continuation_prompt\":\"Продолжи шаг 2\",\"text\":\"Продолжи шаг 2\",\"message\":\"Цель не завершена\",\"message_key\":\"goal_continuing\",\"decision\":{\"should_continue\":true}}\n\n"

    private val titleFrame =
        "event: title\ndata: {\"session_id\":\"s-1\",\"title\":\"Отладка сети\"}\n\n"

    @Test
    fun sendMessageDeliversSideEventsInStreamOrderAlongsideSealedFlow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: chunk\ndata: {\"text\":\"привет\"}\n\n" +
                    goalFrames +
                    titleFrame +
                    "event: stream_end\ndata: {}\n\n"
            ))
            val side = mutableListOf<StreamSideEvent>()
            val events = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                onSideEvent = { side.add(it) },
            ).toList()

            // The sealed flow is unchanged: goal/goal_continue never enter it, the
            // title frame still binds the session, stream_end terminates.
            assertEquals(
                listOf(
                    ChatEvent.StreamStarted("st-1"),
                    ChatEvent.SessionBound("s-1"),
                    ChatEvent.Chunk("привет"),
                    ChatEvent.SessionBound("s-1"),
                    ChatEvent.Done,
                ),
                events,
            )
            // Side channel: three goal frames + the title TEXT, in stream order.
            assertEquals(4, side.size)
            assertEquals(GoalUpdate("s-1", "evaluating", "Evaluating goal progress…", "goal_evaluating_progress"), side[0])
            val continuing = side[1] as GoalUpdate
            assertEquals("continuing", continuing.state)
            assertTrue(continuing.decisionJson!!.contains("\"should_continue\":true"))
            val continuation = side[2] as GoalContinuation
            assertEquals("Продолжи шаг 2", continuation.continuationPrompt)
            assertEquals(SessionTitleUpdate("s-1", "Отладка сети"), side[3])
        }
    }

    @Test
    fun withoutCallbackSideFramesAreDroppedAndSealedFlowIsUnchanged() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: chunk\ndata: {\"text\":\"ok\"}\n\n" + goalFrames + titleFrame + "event: stream_end\ndata: {}\n\n"
            ))
            val events = ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            // Backward compatible: default null callback — frames vanish, no crash,
            // the sealed flow matches the pre-side-channel behaviour.
            assertEquals(
                listOf(
                    ChatEvent.StreamStarted("st-1"),
                    ChatEvent.SessionBound("s-1"),
                    ChatEvent.Chunk("ok"),
                    ChatEvent.SessionBound("s-1"),
                    ChatEvent.Done,
                ),
                events,
            )
        }
    }

    @Test
    fun attachToStreamDeliversSideEventsFromJournalReplay() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                goalFrames + titleFrame + "event: done\ndata: {}\n\n"
            ))
            val side = mutableListOf<StreamSideEvent>()
            val events = ChatRepository().attachToStream(
                server.url("/").toString(), credential(), "st-9",
                onSideEvent = { side.add(it) },
            ).toList()

            assertEquals(ChatEvent.StreamStarted("st-9"), events.first())
            assertTrue(events.contains(ChatEvent.SessionBound("s-1")))
            assertEquals(ChatEvent.Done, events.last())
            assertEquals(4, side.size)
            assertTrue(side[3] is SessionTitleUpdate)
        }
    }

    @Test
    fun todoStateDiversionStillTakesPrecedenceOverSideChannel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1","session_id":"s-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: todo_state\ndata: {\"session_id\":\"s-1\",\"todos\":[],\"summary\":{\"total\":0},\"version\":1}\n\n" +
                    titleFrame +
                    "event: stream_end\ndata: {}\n\n"
            ))
            val todos = mutableListOf<ru.mars.network.model.TodoSnapshot>()
            val side = mutableListOf<StreamSideEvent>()
            ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                onTodoState = { todos.add(it) },
                onSideEvent = { side.add(it) },
            ).toList()

            assertEquals(1, todos.size)
            // The todo frame must not leak into the side channel...
            assertEquals(listOf<StreamSideEvent>(SessionTitleUpdate("s-1", "Отладка сети")), side)
        }
    }

    @Test
    fun startPayloadCarriesWorkspaceWhenProvided() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: stream_end\ndata: {}\n\n"))
            ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Hi",
                sessionId = "s-1",
                workspace = "/srv/data",
            ).toList()

            val body = server.takeRequest().body.readUtf8()
            // Server `_handle_chat_start` reads body["workspace"] as a path string.
            assertTrue(body, body.contains("\"workspace\":\"/srv/data\""))
            assertTrue(body, body.contains("\"message\":\"Hi\""))
            assertTrue(body, body.contains("\"session_id\":\"s-1\""))
        }
    }

    @Test
    fun startPayloadOmitsWorkspaceByDefaultAndWhenBlank() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"stream_id":"st-1"}"""))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: stream_end\ndata: {}\n\n"))
            }
            ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi").toList()
            val defaultBody = server.takeRequest().body.readUtf8()
            // Backward compatibility: byte-identical to the pre-workspace payload.
            assertFalse(defaultBody, defaultBody.contains("workspace"))

            ChatRepository().sendMessage(server.url("/").toString(), credential(), "Hi", workspace = "   ").toList()
            val blankBody = server.takeRequest().body.readUtf8()
            assertFalse(blankBody, blankBody.contains("workspace"))
        }
    }
}
