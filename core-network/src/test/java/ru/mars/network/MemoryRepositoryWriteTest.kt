package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.MemoryRepository
import ru.mars.network.repository.MemoryWriteResult

/**
 * Контракт записи секции памяти `POST /api/memory/write`
 * (hermes-webui api/route_memory.py `_handle_memory_write`, маршрутизация routes.py):
 * - тело: `{"section": "memory"|"user"|"soul", "content": "<текст как есть>"}`;
 * - успех 200: `{"ok": true, "section": ..., "path": ...}`;
 * - ошибки: 400 (пустое поле/неверная секция), 403 (секция отключена конфигом,
 *   symlink, ro-файл) — тело `{"error": "..."}`;
 * - `require` (helpers.py) считает пустую строку отсутствующим полем, поэтому
 *   blank-ввод клиент гасит локально, не дёргая сеть.
 */
class MemoryRepositoryWriteTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)
    private val repo = MemoryRepository()

    @Test
    fun writeSectionPostsJsonBodyWithBearerAndParsesOk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"ok": true, "section": "memory", "path": "/root/.hermes/memories/MEMORY.md"}""")
            )
            val result = repo.writeSection(server.url("/").toString(), credential, "memory", "новая\nстрока")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/memory/write", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            // \n внутри строки — строго JSON-эскейп (сервер парсит json.loads STRICT).
            assertEquals("""{"section":"memory","content":"новая\nстрока"}""", request.body.readUtf8())
            assertEquals(MemoryWriteResult.Saved("memory"), result)
        }
    }

    @Test
    fun serverErrorMessageIsCarriedIntoFailed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setBody("""{"error": "Memory is disabled by configuration (memory_enabled: false)"}""")
            )
            val result = repo.writeSection(server.url("/").toString(), credential, "memory", "текст")
            assertEquals(
                MemoryWriteResult.Failed("Memory is disabled by configuration (memory_enabled: false)"),
                result,
            )
        }
    }

    @Test
    fun badSectionMaps400Message() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"error": "section must be \"memory\", \"user\", or \"soul\""}""")
            )
            val result = repo.writeSection(server.url("/").toString(), credential, "project", "текст")
            assertTrue(result is MemoryWriteResult.Failed)
            assertTrue((result as MemoryWriteResult.Failed).message.contains("section must be"))
        }
    }

    @Test
    fun nonJsonErrorBodyFallsBackToHttpCode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val result = repo.writeSection(server.url("/").toString(), credential, "user", "текст")
            assertEquals(MemoryWriteResult.Failed("HTTP 500"), result)
        }
    }

    @Test
    fun blankSectionOrContentNeverHitsNetwork() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            assertTrue(repo.writeSection(base, credential, "", "текст") is MemoryWriteResult.Failed)
            assertTrue(repo.writeSection(base, credential, "memory", "") is MemoryWriteResult.Failed)
            assertTrue(repo.writeSection(base, credential, "memory", "   ") is MemoryWriteResult.Failed)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun okFalseIsAFailureNotASave() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok": false, "error": "denied"}"""))
            assertEquals(
                MemoryWriteResult.Failed("denied"),
                repo.writeSection(server.url("/").toString(), credential, "soul", "текст"),
            )
        }
    }

    @Test
    fun writeBodyEscapesQuotesBackslashesAndControlChars() {
        assertEquals(
            """{"section":"user","content":"a\"b\\c\nd\u0001e"}""",
            repo.writeBody("user", "a\"b\\c\nd\u0001e"),
        )
    }

    @Test
    fun parseWriteResultReadsOkAndError() {
        assertEquals(true, repo.parseWriteOk("""{"ok": true, "section": "memory"}"""))
        assertEquals(false, repo.parseWriteOk("""{"ok": false}"""))
        assertEquals(false, repo.parseWriteOk("""{"error": "x"}"""))
        assertEquals(false, repo.parseWriteOk("not json"))
        assertEquals("Skill not found", repo.parseError("""{"error": "Skill not found"}"""))
        assertEquals(null, repo.parseError("""{"ok": true}"""))
        assertEquals(null, repo.parseError("garbage"))
    }
}
