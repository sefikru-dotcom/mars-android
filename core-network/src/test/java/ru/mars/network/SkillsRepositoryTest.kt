package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SkillDetail
import ru.mars.network.model.SkillItem
import ru.mars.network.repository.SkillsRepository

class SkillsRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    /** Real GET /api/skills payload shape: `skills[]` of `{name, description, category}`. */
    private val realPayload = """
        {
          "skills": [
            {"name": "mars-toolkit", "description": "RAG-память и карта кода", "category": "devops"},
            {"name": "weather-stavropol", "description": "Погода Ставрополя", "category": "productivity"}
          ]
        }
    """.trimIndent()

    @Test
    fun loadsSkillCatalogWithBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(realPayload))
            val result = SkillsRepository().getSkills(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/skills", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))

            assertEquals(
                listOf(
                    SkillItem("mars-toolkit", "RAG-память и карта кода", "devops"),
                    SkillItem("weather-stavropol", "Погода Ставрополя", "productivity"),
                ),
                result,
            )
        }
    }

    @Test
    fun categoryFilterBecomesQueryParameter() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"skills":[]}"""))
            SkillsRepository().getSkills(server.url("/").toString(), credential, category = "devops")
            assertEquals("/api/skills?category=devops", server.takeRequest().path)
        }
    }

    @Test
    fun loadsSkillContentByName() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"mars-toolkit","content":"# SKILL.md body"}"""))
            val result = SkillsRepository().getSkillContent(server.url("/").toString(), credential, "mars-toolkit")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/skills/content?name=mars-toolkit", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals(SkillDetail("mars-toolkit", "# SKILL.md body"), result)
        }
    }

    @Test
    fun blankSkillNameNeverHitsNetwork() = runBlocking {
        MockWebServer().use { server ->
            assertNull(SkillsRepository().getSkillContent(server.url("/").toString(), credential, "  "))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun httpFailuresMapToEmptyCatalogAndNullDetail() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            val repo = SkillsRepository()
            assertEquals(emptyList<SkillItem>(), repo.getSkills(server.url("/").toString(), credential))
            assertNull(repo.getSkillContent(server.url("/").toString(), credential, "mars-toolkit"))
        }
    }

    @Test
    fun parseSkillsSkipsBrokenEntriesAndDefaultsMetadata() {
        val parsed = SkillsRepository().parseSkills(
            """{"skills":[{"name":"a"},{"description":"no-name"},{"name":"  "},"junk",{"name":"b","category":"devops"}]}"""
        )
        assertEquals(listOf(SkillItem("a"), SkillItem("b", "", "devops")), parsed)
    }

    @Test
    fun parseSkillsRejectsGarbage() {
        val repo = SkillsRepository()
        assertEquals(emptyList<SkillItem>(), repo.parseSkills("not json"))
        assertEquals(emptyList<SkillItem>(), repo.parseSkills(""))
        assertEquals(emptyList<SkillItem>(), repo.parseSkills("""{"other":1}"""))
    }

    @Test
    fun parseSkillDetailRequiresName() {
        val repo = SkillsRepository()
        assertEquals(SkillDetail("x", "body"), repo.parseSkillDetail("""{"name":"x","content":"body"}"""))
        assertEquals(SkillDetail("x", ""), repo.parseSkillDetail("""{"name":"x"}"""))
        assertNull(repo.parseSkillDetail("""{"content":"body"}"""))
        assertNull(repo.parseSkillDetail("not json"))
        assertNull(repo.parseSkillDetail(""))
    }
}
