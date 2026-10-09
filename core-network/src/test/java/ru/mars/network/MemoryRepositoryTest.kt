package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.MemoryContent
import ru.mars.network.repository.MemoryRepository

class MemoryRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    /** Real GET /api/memory payload shape: four markdown/text sections. */
    private val realPayload = """
        {
          "memory": "# Память\n- устойчивый факт",
          "user": "Стеф, ответы на русском",
          "soul": "soul.md body",
          "project_context": "AGENTS.md контекст проекта"
        }
    """.trimIndent()

    @Test
    fun loadsMemorySectionsWithBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(realPayload))
            val result = MemoryRepository().getMemory(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/memory", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))

            assertEquals(
                MemoryContent(
                    memory = "# Память\n- устойчивый факт",
                    user = "Стеф, ответы на русском",
                    soul = "soul.md body",
                    projectContext = "AGENTS.md контекст проекта",
                ),
                result,
            )
        }
    }

    @Test
    fun httpFailureMapsToNullNotThrow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertNull(MemoryRepository().getMemory(server.url("/").toString(), credential))
        }
    }

    @Test
    fun parseMapsProjectContextKeyAndDefaultsMissingOrNullSectionsToEmpty() {
        // `project_context` on the wire maps to `projectContext`; null/absent → "".
        assertEquals(
            MemoryContent(memory = "m", user = "", soul = "", projectContext = "pc"),
            MemoryRepository().parse("""{"memory":"m","soul":null,"project_context":"pc"}"""),
        )
        assertEquals(MemoryContent(), MemoryRepository().parse("{}"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(MemoryRepository().parse("not json"))
        assertNull(MemoryRepository().parse(""))
    }
}
