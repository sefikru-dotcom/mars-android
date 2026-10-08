package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.network.repository.SessionRepository

/**
 * Active-turn metadata (`active_stream_id`/`is_streaming`/`has_pending_user_message`/
 * `pending_started_at`/`attention`), cold `todo_state` snapshots and the user-only
 * WorkspacePrefix strip on history load — verified against the real hermes-webui wire
 * shapes (api/models.py `Session.compact(include_runtime=True)`, api/routes.py
 * `_sidebar_session_response_item`, api/todo_state.py `_normalize_snapshot`).
 */
class SessionActiveTurnTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    @Test fun detailParsesActiveTurnAndTodoState() = runBlocking {
        MockWebServer().use { server ->
            // Real GET /api/session shape: {"session": {...compact(include_runtime=True), todo_state, messages}}
            server.enqueue(MockResponse().setBody("""
                {"session":{
                  "session_id":"s1","title":"Отладка",
                  "active_stream_id":"st-42","is_streaming":true,
                  "has_pending_user_message":true,"pending_started_at":1759900000.5,
                  "todo_state":{
                    "todos":[
                      {"id":"1","content":"Шаг один","status":"completed"},
                      {"id":"2","content":"Шаг два","status":"in_progress"}
                    ],
                    "summary":{"total":2,"pending":0,"in_progress":1,"completed":1,"cancelled":0},
                    "version":1,"ts":1759900001.25
                  },
                  "messages":[]
                }}
            """.trimIndent()))
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
            assertEquals("/api/session?session_id=s1", server.takeRequest().path)
            assertEquals(
                ActiveTurnInfo(
                    activeStreamId = "st-42",
                    isStreaming = true,
                    hasPendingUserMessage = true,
                    pendingStartedAtEpochSeconds = 1759900000.5,
                    attention = null,
                ),
                detail.activeTurn,
            )
            assertTrue(detail.activeTurn.canReattach)
            assertEquals(
                TodoSnapshot(
                    todos = listOf(
                        TodoItem(id = "1", content = "Шаг один", status = "completed"),
                        TodoItem(id = "2", content = "Шаг два", status = "in_progress"),
                    ),
                    summary = TodoSummary(total = 2, pending = 0, inProgress = 1, completed = 1, cancelled = 0),
                    version = 1,
                    tsEpochSeconds = 1759900001.25,
                ),
                detail.todoState,
            )
        }
    }

    @Test fun detailWithoutRuntimeFieldsKeepsIdleDefaults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"session":{"session_id":"s1","title":"T","messages":[]}}"""))
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
            assertEquals(ActiveTurnInfo(), detail.activeTurn)
            assertFalse(detail.activeTurn.canReattach)
            assertNull(detail.todoState)
        }
    }

    @Test fun listParsesActiveTurnAndAttentionPerRow() = runBlocking {
        MockWebServer().use { server ->
            // Real /api/sessions sidebar rows: allowlisted compact fields + attention stamp.
            server.enqueue(MockResponse().setBody("""
                {"sessions":[
                  {"session_id":"a","title":"Live","message_count":3,
                   "active_stream_id":"st-1","is_streaming":true,"has_pending_user_message":false,
                   "attention":{"kind":"approval","count":2,"severity":"critical"}},
                  {"session_id":"b","title":"Idle","message_count":1,
                   "active_stream_id":null,"is_streaming":false,"has_pending_user_message":true,
                   "pending_started_at":1759900000,"attention":null}
                ]}
            """.trimIndent()))
            val items = SessionRepository().getSessions(server.url("/").toString(), credential)
            assertEquals("/api/sessions", server.takeRequest().path)
            val live = items[0]
            val idle = items[1]
            assertEquals("st-1", live.activeTurn.activeStreamId)
            assertTrue(live.activeTurn.isStreaming)
            assertTrue(live.activeTurn.canReattach)
            assertEquals(SessionAttention("approval", 2, "critical"), live.activeTurn.attention)
            assertFalse(idle.activeTurn.isStreaming)
            assertFalse(idle.activeTurn.canReattach)
            assertNull(idle.activeTurn.activeStreamId)
            assertTrue(idle.activeTurn.hasPendingUserMessage)
            assertEquals(1759900000.0, idle.activeTurn.pendingStartedAtEpochSeconds!!, 0.0)
            assertNull(idle.activeTurn.attention)
        }
    }

    /** Server contract (todo_state.py `_normalize_snapshot`): an EMPTY todos list is a valid snapshot — the latest write cleared it. */
    @Test fun emptyTodoListIsValidSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"session":{"session_id":"s1","todo_state":{"todos":[],"summary":{"total":0,"pending":0,"in_progress":0,"completed":0,"cancelled":0},"version":1}}}
            """.trimIndent()))
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
            assertEquals(TodoSnapshot(todos = emptyList(), summary = TodoSummary(), version = 1, tsEpochSeconds = null), detail.todoState)
        }
    }

    /** WebUI parity (ui.js todoContent/String(t.id)): `text` alias and numeric ids survive normalization. */
    @Test fun todoItemToleratesNumericIdAndTextAlias() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"session":{"session_id":"s1","todo_state":{"todos":[{"id":7,"text":"Через text"}],"summary":{}}}}
            """.trimIndent()))
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
            assertEquals(listOf(TodoItem(id = "7", content = "Через text", status = "pending")), detail.todoState!!.todos)
            assertEquals(TodoSummary(), detail.todoState!!.summary)
        }
    }

    /** A `todo_state` without a `todos` list is not a snapshot — parsed as absent, never a crash. */
    @Test fun malformedTodoStateParsesAsAbsent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"session":{"session_id":"s1","todo_state":{"summary":{"total":1}}}}"""))
            assertNull(SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!.todoState)
        }
    }

    /**
     * History load strips the client-injected `[Workspace::v1: ...]` sentinel from USER
     * messages only (WebUI `_strip_workspace_prefix` render parity); assistant content
     * quoting a sentinel-looking line stays verbatim.
     */
    @Test fun historyStripsWorkspacePrefixForUserMessagesOnly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"session":{"session_id":"s1","messages":[
                  {"id":"m1","role":"user","content":"[Workspace::v1: /root/workspace]\nпривет"},
                  {"id":"m2","role":"assistant","content":"[Workspace::v1: /x]\nцитата"},
                  {"id":"m3","role":"user","content":"без префикса"},
                  {"id":"m4","role":"system","content":"[Workspace::v1: /sys]\nслужебное"}
                ]}}
            """.trimIndent()))
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
            assertEquals("привет", detail.messages[0].content)
            assertEquals("[Workspace::v1: /x]\nцитата", detail.messages[1].content)
            assertEquals("без префикса", detail.messages[2].content)
            assertEquals("[Workspace::v1: /sys]\nслужебное", detail.messages[3].content)
        }
    }
}
