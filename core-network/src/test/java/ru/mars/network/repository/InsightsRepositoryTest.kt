package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * [InsightsRepository] contract tests on MockWebServer, against the shapes verified in
 * `/opt/hermes-webui/api/route_insights.py`: period/filter query mapping, tolerant
 * parsing of incomplete snapshots (missing counters, absent `knowledge`, partial
 * session rows) and the error ladder (400 invalid filter, 503 pipeline unavailable).
 */
class InsightsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: InsightsRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = InsightsRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(code: Int = 200, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `getInsights maps period and filters onto query params with bearer auth`() = runBlocking {
        enqueue(body = """{"ok":true}""")
        repository.getInsights(baseUrl(), credential, days = 30, limit = 100, status = "error", scope = "mobile")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/insights?days=30&limit=100&status=error&scope=mobile", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        Unit
    }

    @Test
    fun `getInsights without filters sends a bare path`() = runBlocking {
        enqueue(body = """{"ok":true}""")
        repository.getInsights(baseUrl(), credential)
        assertEquals("/api/insights", server.takeRequest().path)
        Unit
    }

    @Test
    fun `getInsights parses a full snapshot`() = runBlocking {
        enqueue(
            body = """
            {"ok":true,"generated_at":"2026-10-12T10:00:00Z","days":7,"scope":"","limit":50,
             "requested_limit":null,"effective_limit":null,"max_limit":null,
             "total_sessions":120,"filtered_sessions":40,"returned_sessions":3,
             "sessions_scanned":200,"sessions_imported":1,"sessions_import_failed":0,
             "active":2,"stuck":1,"long_running":0,"error_states":4,
             "errors":[{"sample":"tool crashed","kind":"runtime_error","count":3,
                        "first_seen":"2026-10-10T08:00:00Z","last_seen":"2026-10-12T09:00:00Z"}],
             "kinds":{"runtime_error":3,"timeout":1},
             "timeline":[{"day":"2026-10-11","count":2},{"day":"2026-10-12","count":2}],
             "sessions":[{"session_id":"s-1","source":"jsonl","when":1760000000.5,
                          "iso_time":"2026-10-12T09:00:00","title":"Сессия","model":"qwen",
                          "message_count":12,"has_errors":true,"error_count":2,
                          "first_error":"boom","error_sample":"boom","error_kinds":["runtime_error"]},
                         {"session_id":"s-2","source":"sqlite","when":null,"title":null}],
             "knowledge":{"indexed":10,"queued":1,"failed":0,"queue_size":1,"pending_chunks":2,
                          "chunk_error_count":1,"total_chunks":500,"total_documents":40,
                          "total_projects":3,"session_count":30,"status":"ok","status_message":"",
                          "last_indexed_at":"2026-10-12T09:30:00Z","last_indexed_relative":"30m ago",
                          "last_index_error":null,
                          "chunk_errors":[{"session_id":"s-2","error_count":1,"first_error":"bad chunk","last_error":"bad chunk"}],
                          "recent":[{"session_id":"s-1","chunks":12,"documents":2,"project":"mars",
                                     "status":"indexed","error":null,"indexed_at":"2026-10-12T09:30:00Z",
                                     "indexed_at_relative":"30m ago"}]}}
            """.trimIndent(),
        )
        val snapshot = (repository.getInsights(baseUrl(), credential) as InsightsResult.Snapshot).snapshot
        assertTrue(snapshot.ok)
        assertEquals(7, snapshot.days)
        assertEquals(120, snapshot.totalSessions)
        assertEquals(2, snapshot.active)
        assertEquals(4, snapshot.errorStates)
        assertEquals(1, snapshot.errors.size)
        assertEquals("runtime_error", snapshot.errors[0].kind)
        assertEquals(3, snapshot.errors[0].count)
        assertEquals(mapOf("runtime_error" to 3, "timeout" to 1), snapshot.kinds)
        assertEquals(listOf("2026-10-11", "2026-10-12"), snapshot.timeline.map { it.day })

        val s1 = snapshot.sessions[0]
        assertEquals("s-1", s1.sessionId)
        assertEquals(1760000000L, s1.whenEpoch) // fractional epoch truncates
        assertTrue(s1.hasErrors)
        assertEquals(listOf("runtime_error"), s1.errorKinds)

        // Incomplete session row: absent fields degrade, never crash.
        val s2 = snapshot.sessions[1]
        assertEquals("s-2", s2.sessionId)
        assertNull(s2.whenEpoch)
        assertNull(s2.title)
        assertFalse(s2.hasErrors)
        assertEquals(0, s2.errorCount)

        val knowledge = snapshot.knowledge!!
        assertEquals(10, knowledge.indexed)
        assertEquals("s-2", knowledge.chunkErrors.single().sessionId)
        assertEquals("indexed", knowledge.recent.single().status)
        Unit
    }

    @Test
    fun `getInsights tolerates a minimal snapshot without knowledge or lists`() = runBlocking {
        enqueue(body = """{"ok":true,"days":7,"total_sessions":0}""")
        val snapshot = (repository.getInsights(baseUrl(), credential) as InsightsResult.Snapshot).snapshot
        assertTrue(snapshot.ok)
        assertNull(snapshot.knowledge)
        assertTrue(snapshot.sessions.isEmpty())
        assertTrue(snapshot.errors.isEmpty())
        assertTrue(snapshot.kinds.isEmpty())
        assertEquals("", snapshot.scope)
        Unit
    }

    @Test
    fun `getInsights tolerates null knowledge and junk rows`() {
        val root = TolerantJson.parseMap(
            """{"ok":true,"knowledge":null,"sessions":["junk",{"session_id":"s","when":"1760000000"}],
                "errors":[{"sample":"e"}],"timeline":[{"count":1}],"kinds":5}"""
        )!!
        val snapshot = repository.parseSnapshot(root)
        assertNull(snapshot.knowledge)
        assertEquals(1, snapshot.sessions.size)
        assertEquals(1760000000L, snapshot.sessions[0].whenEpoch) // numeric string epoch
        assertEquals("", snapshot.sessions[0].source)
        assertEquals("e", snapshot.errors.single().sample)
        assertEquals("", snapshot.timeline.single().day)
        assertTrue(snapshot.kinds.isEmpty()) // kinds:5 is junk → empty map
    }

    @Test
    fun `getInsights maps 400 invalid filter onto InvalidRequest with the server message`() = runBlocking {
        enqueue(400, """{"error":"invalid days (must be 1..365)"}""")
        val result = repository.getInsights(baseUrl(), credential, days = 0)
        assertEquals(InsightsResult.InvalidRequest("invalid days (must be 1..365)"), result)
        Unit
    }

    @Test
    fun `getInsights maps 503 onto Unavailable and 500 onto Failed`() = runBlocking {
        enqueue(503, """{"error":"insights unavailable: boom"}""")
        assertEquals(
            InsightsResult.Unavailable("insights unavailable: boom"),
            repository.getInsights(baseUrl(), credential),
        )
        enqueue(500, """{"error":"failed to build insights"}""")
        assertEquals(
            InsightsResult.Failed("failed to build insights"),
            repository.getInsights(baseUrl(), credential),
        )
        // 500 without a JSON error body still reports the status.
        enqueue(500, "")
        assertEquals(InsightsResult.Failed("HTTP 500"), repository.getInsights(baseUrl(), credential))
        Unit
    }

    @Test
    fun `getInsights maps 2xx with unparseable body onto Failed, not Snapshot`() = runBlocking {
        enqueue(200, "not json")
        assertEquals(
            InsightsResult.Failed("HTTP 200: unparseable body"),
            repository.getInsights(baseUrl(), credential),
        )
        Unit
    }

    @Test
    fun `getInsights maps transport failure onto Failed`() = runBlocking {
        val port = server.port
        server.shutdown()
        val result = repository.getInsights("http://127.0.0.1:$port/", credential)
        assertTrue(result is InsightsResult.Failed)
        Unit
    }
}
