package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SkillItem
import ru.mars.network.repository.SkillMutationResult
import ru.mars.network.repository.SkillsRepository

/**
 * Контракты мутаций каталога скиллов (hermes-webui api/route_skills.py):
 * - POST /api/skills/save (`_handle_skill_save`, :464) — `{"name","content"[,"category"]}`;
 *   успех `{"ok":true,"name","path"}`; невалидное имя → 400 `{"error":"Invalid skill name"}`;
 * - POST /api/skills/delete (`_handle_skill_delete`, :495) — `{"name"}`;
 *   не найден → 404 `{"error":"Skill not found"}`; успех `{"ok":true,"name"}`;
 * - POST /api/skills/toggle (`_handle_skill_toggle`, :536) — `{"name","enabled":bool}`;
 *   `enabled:false` обязан проходить require (helpers.py: `False == 0`);
 *   не найден → 404 `{"error":"Skill 'x' not found"}`; успех `{"ok":true,"name","enabled"}`;
 * - GET /api/skills несёт `"disabled": bool` на элемент (:251).
 */
class SkillsRepositoryMutationTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)
    private val repo = SkillsRepository()

    // --- disabled-флаг в каталоге ------------------------------------------------------------

    @Test
    fun parseSkillsReadsDisabledFlag() {
        val parsed = repo.parseSkills(
            """{"skills": [
                {"name":"a","description":"","category":"","disabled":true},
                {"name":"b","description":"","category":""},
                {"name":"c","description":"","category":"","disabled":"yes"}
            ]}"""
        )
        assertEquals(listOf(true, false, false), parsed.map(SkillItem::disabled))
    }

    // --- save ----------------------------------------------------------------------------------

    @Test
    fun saveSkillPostsNameContentCategory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok": true, "name": "my-skill", "path": "/skills/my-skill/SKILL.md"}"""))
            val result = repo.saveSkill(server.url("/").toString(), credential, "my-skill", "# Заголовок\nтело", "devops")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/skills/save", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("""{"name":"my-skill","content":"# Заголовок\nтело","category":"devops"}""", request.body.readUtf8())
            assertEquals(SkillMutationResult.Ok, result)
        }
    }

    @Test
    fun saveSkillOmitsBlankCategory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok": true}"""))
            repo.saveSkill(server.url("/").toString(), credential, "x", "c", "  ")
            assertEquals("""{"name":"x","content":"c"}""", server.takeRequest().body.readUtf8())
        }
    }

    @Test
    fun saveSkillCarries400InvalidName() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": "Invalid skill name"}"""))
            assertEquals(
                SkillMutationResult.Failed("Invalid skill name"),
                repo.saveSkill(server.url("/").toString(), credential, "..", "c", null),
            )
        }
    }

    // --- delete --------------------------------------------------------------------------------

    @Test
    fun deleteSkillPostsNameAndParsesOk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok": true, "name": "old-skill"}"""))
            val result = repo.deleteSkill(server.url("/").toString(), credential, "old-skill")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/skills/delete", request.path)
            assertEquals("""{"name":"old-skill"}""", request.body.readUtf8())
            assertEquals(SkillMutationResult.Ok, result)
        }
    }

    @Test
    fun deleteSkillCarries404NotFound() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "Skill not found"}"""))
            assertEquals(
                SkillMutationResult.Failed("Skill not found"),
                repo.deleteSkill(server.url("/").toString(), credential, "ghost"),
            )
        }
    }

    // --- toggle --------------------------------------------------------------------------------

    @Test
    fun toggleSkillSendsEnabledFalseAsJsonBoolean() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok": true, "name": "mars-toolkit", "enabled": false}"""))
            val result = repo.toggleSkill(server.url("/").toString(), credential, "mars-toolkit", enabled = false)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/skills/toggle", request.path)
            assertEquals("""{"name":"mars-toolkit","enabled":false}""", request.body.readUtf8())
            assertEquals(SkillMutationResult.Ok, result)
        }
    }

    @Test
    fun toggleSkillCarries404Message() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "Skill 'ghost' not found"}"""))
            assertEquals(
                SkillMutationResult.Failed("Skill 'ghost' not found"),
                repo.toggleSkill(server.url("/").toString(), credential, "ghost", enabled = true),
            )
        }
    }

    // --- локальные guard'ы ---------------------------------------------------------------------

    @Test
    fun blankNameOrContentNeverHitsNetwork() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            assertTrue(repo.saveSkill(base, credential, "", "c", null) is SkillMutationResult.Failed)
            assertTrue(repo.saveSkill(base, credential, "x", "", null) is SkillMutationResult.Failed)
            assertTrue(repo.deleteSkill(base, credential, "  ") is SkillMutationResult.Failed)
            assertTrue(repo.toggleSkill(base, credential, "", true) is SkillMutationResult.Failed)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun networkFailureMapsToFailedNotThrow() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            server.shutdown()
            assertTrue(repo.saveSkill(base, credential, "x", "c", null) is SkillMutationResult.Failed)
            assertTrue(repo.deleteSkill(base, credential, "x") is SkillMutationResult.Failed)
            assertTrue(repo.toggleSkill(base, credential, "x", true) is SkillMutationResult.Failed)
        }
    }

    // --- чистые билдеры тел ---------------------------------------------------------------------

    @Test
    fun bodyBuildersEscapeAndCompose() {
        assertEquals("""{"name":"a-b","content":"q\"w","category":"ecc"}""", repo.saveBody("a-b", "q\"w", "ecc"))
        assertEquals("""{"name":"a-b","content":"q\"w"}""", repo.saveBody("a-b", "q\"w", null))
        assertEquals("""{"name":"a-b"}""", repo.nameBody("a-b"))
        assertEquals("""{"name":"a-b","enabled":true}""", repo.toggleBody("a-b", true))
        assertEquals("""{"name":"a-b","enabled":false}""", repo.toggleBody("a-b", false))
    }

    @Test
    fun parseMutationReadsOkAndError() {
        assertEquals(SkillMutationResult.Ok, repo.parseMutation(200, """{"ok": true, "name": "x"}"""))
        assertEquals(SkillMutationResult.Failed("Invalid skill name"), repo.parseMutation(400, """{"error": "Invalid skill name"}"""))
        assertEquals(SkillMutationResult.Failed("HTTP 500"), repo.parseMutation(500, "boom"))
        assertEquals(SkillMutationResult.Failed("HTTP 200"), repo.parseMutation(200, """{"ok": false}"""))
        assertFalse(repo.parseMutation(200, """{"ok": true}""") is SkillMutationResult.Failed)
    }
}
