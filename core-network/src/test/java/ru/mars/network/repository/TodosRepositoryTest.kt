package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionItem

/**
 * [TodosRepository] contract tests on MockWebServer: the facade adds no transport of
 * its own, so what is verified here is the RESOLUTION logic — subagent filtering,
 * "current session" picking, the two-request fetch (list, then detail) and the honest
 * nulls (`no sessions`, `session never wrote todos`).
 */
class TodosRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: TodosRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = TodosRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private val sessionsBody = """
        {"sessions":[
          {"id":"s-new","title":"Новая"},
          {"id":"s-busy","title":"Занятая","active_stream_id":"st-1"},
          {"id":"s-sub","title":"worker","source_tag":"subagent"},
          {"id":"s-old","title":"Старая"}
        ]}
    """.trimIndent()

    private val detailWithTodos = """
        {"id":"s-busy","title":"Занятая",
         "todo_state":{"todos":[{"id":"1","content":"Шаг один","status":"in_progress"}],
                       "summary":{"total":1,"pending":0,"in_progress":1,"completed":0,"cancelled":0},
                       "version":1}}
    """.trimIndent()

    private val detailWithoutTodos = """{"id":"s-new","title":"Новая"}"""

    @Test
    fun `listTodoSessions drops subagent children and keeps server order`() = runBlocking {
        enqueue(sessionsBody)
        val sessions = repository.listTodoSessions(baseUrl(), credential)
        assertEquals(listOf("s-new", "s-busy", "s-old"), sessions.map { it.id })
        assertEquals("api/sessions", server.takeRequest().path?.removePrefix("/"))
    }

    @Test
    fun `pickCurrentSession prefers a live turn, then the newest visible session`() {
        val sessions = listOf(
            SessionItem(id = "s-new", title = "Новая"),
            SessionItem(id = "s-busy", title = "Занятая", activeTurn = ActiveTurnInfo(activeStreamId = "st-1")),
            SessionItem(id = "s-sub", title = "worker", sourceTag = "subagent"),
        )
        assertEquals("s-busy", TodosRepository.pickCurrentSession(sessions)?.id)
        // No live turn anywhere: newest visible wins; subagents never do.
        val idle = listOf(
            SessionItem(id = "s-sub", title = "worker", sourceTag = "subagent"),
            SessionItem(id = "s-a", title = "A"),
            SessionItem(id = "s-b", title = "B"),
        )
        assertEquals("s-a", TodosRepository.pickCurrentSession(idle)?.id)
        assertNull(TodosRepository.pickCurrentSession(emptyList()))
        assertNull(TodosRepository.pickCurrentSession(listOf(SessionItem(id = "s-sub", sourceTag = "subagent"))))
    }

    @Test
    fun `fetchCurrentTodos resolves the active session and its snapshot in two requests`() = runBlocking {
        enqueue(sessionsBody)
        enqueue(detailWithTodos)
        val result = repository.fetchCurrentTodos(baseUrl(), credential)
        assertEquals("s-busy", result?.session?.id)
        val snapshot = result?.snapshot
        assertEquals(1, snapshot?.todos?.size)
        assertEquals("Шаг один", snapshot?.todos?.single()?.content)
        assertEquals(1, snapshot?.summary?.total)
        assertEquals("api/sessions", server.takeRequest().path?.removePrefix("/"))
        val detail = server.takeRequest()
        assertEquals("/api/session", detail.path?.substringBefore("?"))
        assertTrue("detail must target the picked session: ${detail.path}", detail.path?.contains("session_id=s-busy") == true)
    }

    @Test
    fun `fetchCurrentTodos returns null without inventing a session when the list is empty`() = runBlocking {
        enqueue("""{"sessions":[]}""")
        assertNull(repository.fetchCurrentTodos(baseUrl(), credential))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fetchTodos keeps the valid never-wrote-todos state as null snapshot`() = runBlocking {
        enqueue(sessionsBody)
        enqueue(detailWithoutTodos)
        // Force the newest session (no live turn in this pick) to read its detail.
        val result = repository.fetchCurrentTodos(baseUrl(), credential) { list -> list.first { it.id == "s-new" } }
        assertEquals("s-new", result?.session?.id)
        assertNull("session never wrote todos must stay null, not an empty snapshot", result?.snapshot)
    }

    @Test
    fun `transport failure fails soft to empty candidates, never throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(repository.fetchCurrentTodos(baseUrl(), credential))
    }
}
