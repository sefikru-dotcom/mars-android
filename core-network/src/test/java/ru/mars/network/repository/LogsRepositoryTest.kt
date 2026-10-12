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
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail

/**
 * [LogsRepository] contract tests on MockWebServer, against the shapes verified in
 * `/opt/hermes-webui/api/route_logs.py`: whitelisted `file`/`tail` query mapping,
 * the not-yet-existing-file 200 payload (empty lines + hint + null mtime), truncation
 * flagging and the error ladder (400 unknown file, 500 read failure).
 */
class LogsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: LogsRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = LogsRepository()
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
    fun `getLogs maps file and tail whitelist keys onto query params`() = runBlocking {
        enqueue(body = """{"file":"errors","tail":500,"lines":[]}""")
        repository.getLogs(baseUrl(), credential, file = ServerLogFile.ERRORS, tail = ServerLogTail.TAIL_500)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/logs?file=errors&tail=500", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        Unit
    }

    @Test
    fun `getLogs defaults to agent log with 200-line tail`() = runBlocking {
        enqueue(body = """{"file":"agent","tail":200,"lines":[]}""")
        repository.getLogs(baseUrl(), credential)
        assertEquals("/api/logs?file=agent&tail=200", server.takeRequest().path)
        Unit
    }

    @Test
    fun `getLogs parses a full window with truncation and float mtime`() = runBlocking {
        enqueue(
            body = """{"file":"gateway","tail":1000,
               "lines":["2026-10-12 09:00:00 INFO starting","2026-10-12 09:00:01 INFO ready"],
               "truncated":true,"total_bytes":8388608,"mtime":1760252400.123,"hint":""}"""
        )
        val window = (repository.getLogs(
            baseUrl(), credential,
            file = ServerLogFile.GATEWAY, tail = ServerLogTail.TAIL_1000,
        ) as ServerLogsResult.Window).window
        assertEquals("gateway", window.file)
        assertEquals(1000, window.tail)
        assertEquals(2, window.lines.size)
        assertTrue(window.lines[1].contains("ready"))
        assertTrue(window.truncated)
        assertEquals(8388608L, window.totalBytes)
        assertEquals(1760252400.123, window.mtime!!, 0.0001)
        assertEquals("", window.hint)
        Unit
    }

    @Test
    fun `getLogs treats a not-yet-existing file as a valid empty window with hint`() = runBlocking {
        // route_logs.py answers 200 with empty lines and null mtime for a missing file.
        enqueue(
            body = """{"file":"errors","tail":200,"lines":[],
               "truncated":false,"total_bytes":0,"mtime":null,
               "hint":"Log file for errors not found yet."}"""
        )
        val window = (repository.getLogs(baseUrl(), credential, file = ServerLogFile.ERRORS)
            as ServerLogsResult.Window).window
        assertTrue(window.lines.isEmpty())
        assertFalse(window.truncated)
        assertEquals(0L, window.totalBytes)
        assertNull(window.mtime)
        assertTrue(window.missingOnServer)
        assertEquals("Log file for errors not found yet.", window.hint)
        Unit
    }

    @Test
    fun `parseWindow tolerates absent fields and non-string line entries`() {
        val window = repository.parseWindow(
            TolerantJson.parseMap("""{"lines":["a",7,null,"b"],"total_bytes":"42"}""")!!
        )
        assertEquals("", window.file)
        assertEquals(0, window.tail)
        assertEquals(listOf("a", "7", "b"), window.lines) // null dropped, number stringified without ".0"
        assertEquals(42L, window.totalBytes)              // numeric string
        assertNull(window.mtime)
    }

    @Test
    fun `getLogs maps 400 unknown-file onto InvalidRequest with the server message`() = runBlocking {
        enqueue(400, """{"error":"Unknown log file"}""")
        assertEquals(
            ServerLogsResult.InvalidRequest("Unknown log file"),
            repository.getLogs(baseUrl(), credential),
        )
        Unit
    }

    @Test
    fun `getLogs maps 500 and unparseable bodies onto Failed`() = runBlocking {
        enqueue(500, """{"error":"<path>/agent.log: permission denied"}""")
        assertEquals(
            ServerLogsResult.Failed("<path>/agent.log: permission denied"),
            repository.getLogs(baseUrl(), credential),
        )
        enqueue(500, "")
        assertEquals(ServerLogsResult.Failed("HTTP 500"), repository.getLogs(baseUrl(), credential))
        enqueue(200, "not json")
        assertEquals(
            ServerLogsResult.Failed("HTTP 200: unparseable body"),
            repository.getLogs(baseUrl(), credential),
        )
        Unit
    }

    @Test
    fun `getLogs maps transport failure onto Failed`() = runBlocking {
        val port = server.port
        server.shutdown()
        val result = repository.getLogs("http://127.0.0.1:$port/", credential)
        assertTrue(result is ServerLogsResult.Failed)
        Unit
    }
}
