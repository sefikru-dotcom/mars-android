package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.SessionRepository

/**
 * P0-регрессия wire-маппинга сохранённого транскрипта `GET /api/session`:
 * реальные строки истории несут `role:"tool"` с `tool_name`/`name` и
 * `tool_call_id`, а время — в `timestamp` (epoch-секунды, строка sidecar'а
 * ИЛИ число state.db), а не в `created_at`. Без этих полей клиент рисовал
 * сырой JSON инструментов пузырями assistant и терял часы/день-разделители.
 */
class SessionMessageWireMappingTest {

    private val credential = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)

    private fun detail(server: MockWebServer, body: String): SessionDetail = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")!!
    }

    @Test
    fun `tool rows keep name and call id, timestamp alias feeds createdAt`() = MockWebServer().use { server ->
        val detail = detail(
            server,
            """{"session_id":"s1","messages":[
                {"role":"user","content":"проверь","timestamp":"1791921200.0"},
                {"role":"assistant","content":"Смотрю.","timestamp":"1791921201.5"},
                {"role":"tool","content":"{\"ok\":true}","tool_name":"terminal","tool_call_id":"call_9","timestamp":"1791921202.25"}
            ]}""",
        )
        assertEquals(3, detail.messages.size)
        val user = detail.messages[0]
        assertEquals("1791921200.0", user.createdAt)
        assertNull(user.toolName)
        assertNull(user.toolCallId)
        val assistant = detail.messages[1]
        assertEquals("Смотрю.", assistant.content)
        assertEquals("1791921201.5", assistant.createdAt)
        assertNull(assistant.toolName)
        val tool = detail.messages[2]
        assertEquals("tool", tool.role)
        assertEquals("""{"ok":true}""", tool.content)
        assertEquals("terminal", tool.toolName)
        assertEquals("call_9", tool.toolCallId)
        assertEquals("1791921202.25", tool.createdAt)
    }

    @Test
    fun `name alias fills toolName and created_at wins over timestamp`() = MockWebServer().use { server ->
        val detail = detail(
            server,
            """{"session_id":"s1","messages":[
                {"role":"tool","content":"wall","name":"read_file","tool_call_id":"call_1","created_at":"2026-10-11T14:05:03+00:00","timestamp":"1791921202"}
            ]}""",
        )
        val tool = detail.messages.single()
        assertEquals("read_file", tool.toolName)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("2026-10-11T14:05:03+00:00", tool.createdAt)
    }

    @Test
    fun `numeric timestamp is normalized to a plain epoch string, never scientific notation`() =
        MockWebServer().use { server ->
            // state.db отдаёт REAL-числа; Moshi декодирует их Double, чей toString —
            // «1.7919212E9». Экспоненциальная форма не парсится ни одним display-парсером,
            // поэтому wire-число нормализуется через BigDecimal.toPlainString().
            val detail = detail(
                server,
                """{"session_id":"s1","messages":[
                    {"role":"user","content":"q","timestamp":1791921200},
                    {"role":"assistant","content":"a","timestamp":1791921201.5}
                ]}""",
            )
            assertEquals("1791921200", detail.messages[0].createdAt)
            assertEquals("1791921201.5", detail.messages[1].createdAt)
        }

    @Test
    fun `legacy rows without tool or timestamp fields parse to nulls`() = MockWebServer().use { server ->
        val detail = detail(server, """{"session_id":"s1","messages":[{"id":"m1","role":"user","content":"Hello"}]}""")
        val message = detail.messages.single()
        assertEquals("Hello", message.content)
        assertNull(message.createdAt)
        assertNull(message.toolName)
        assertNull(message.toolCallId)
    }
}
