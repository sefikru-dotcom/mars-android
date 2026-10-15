package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse
import ru.mars.network.repository.ProfilesRepository

/**
 * Разбор `GET /api/profiles` и `GET /api/profile/active` (контракт снят с
 * `api/profiles.py` `list_profiles_api` / `_build_profile_rows_fast`).
 */
class ProfilesRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)
    private val repository = ProfilesRepository()

    /** Полная форма строки профиля, как её сериализует сервер. */
    private val fullPayload = """
        {
          "profiles": [
            {
              "name": "default",
              "display_name": "Stef",
              "path": "/root/.hermes",
              "is_default": true,
              "is_active": false,
              "gateway_running": true,
              "model": "openai/gpt-5",
              "provider": "openai",
              "has_env": true,
              "visible": true,
              "skill_count": 12,
              "enabled_skills": 12,
              "total_skills": 34
            },
            {
              "name": "office",
              "display_name": null,
              "path": "/root/.hermes/profiles/office",
              "is_default": false,
              "is_active": true,
              "gateway_running": false,
              "model": null,
              "provider": null,
              "has_env": false,
              "visible": false,
              "skill_count": 0,
              "enabled_skills": 0,
              "total_skills": 0
            }
          ],
          "active": "office",
          "single_profile_mode": false
        }
    """.trimIndent()

    @Test
    fun `parses full payload`() {
        val parsed = repository.parse(fullPayload)

        assertEquals(
            ProfilesResponse(
                profiles = listOf(
                    MarsProfile(
                        name = "default",
                        displayName = "Stef",
                        path = "/root/.hermes",
                        isDefault = true,
                        isActive = false,
                        gatewayRunning = true,
                        model = "openai/gpt-5",
                        provider = "openai",
                        hasEnv = true,
                        visible = true,
                        enabledSkills = 12,
                        totalSkills = 34,
                    ),
                    MarsProfile(
                        name = "office",
                        displayName = null,
                        path = "/root/.hermes/profiles/office",
                        isDefault = false,
                        isActive = true,
                        gatewayRunning = false,
                        model = null,
                        provider = null,
                        hasEnv = false,
                        visible = false,
                        enabledSkills = 0,
                        totalSkills = 0,
                    ),
                ),
                active = "office",
                singleProfileMode = false,
            ),
            parsed,
        )
        // Подпись и короткая модель — производные, а не отдельные поля контракта.
        assertEquals("Stef", parsed!!.profiles[0].label)
        assertEquals("gpt-5", parsed.profiles[0].shortModel)
        assertEquals("office", parsed.profiles[1].label)
        assertNull(parsed.profiles[1].shortModel)
    }

    @Test
    fun `truncated profile row keeps defaults instead of crashing`() {
        val parsed = repository.parse("""{"profiles":[{"name":"work"}]}""")

        assertEquals(
            ProfilesResponse(profiles = listOf(MarsProfile(name = "work")), active = null, singleProfileMode = false),
            parsed,
        )
        // visible по умолчанию true: отсутствие ключа ≠ скрытый профиль.
        assertTrue(parsed!!.profiles.single().visible)
        assertFalse(parsed.profiles.single().isActive)
        assertEquals(0, parsed.profiles.single().totalSkills)
        assertEquals("work", parsed.profiles.single().label)
    }

    @Test
    fun `empty profile list is a valid response`() {
        val parsed = repository.parse("""{"profiles":[],"active":"default","single_profile_mode":false}""")

        assertEquals(ProfilesResponse(profiles = emptyList(), active = "default", singleProfileMode = false), parsed)
        assertTrue(parsed!!.profiles.isEmpty())
    }

    @Test
    fun `single profile mode flag is parsed`() {
        val parsed = repository.parse(
            """{"profiles":[{"name":"user1","is_active":true,"visible":false}],"active":"user1","single_profile_mode":true}"""
        )

        assertTrue(parsed!!.singleProfileMode)
        assertEquals("user1", parsed.active)
        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.profiles.single().isActive)
        assertFalse(parsed.profiles.single().visible)
    }

    @Test
    fun `rows without name and non-object entries are dropped`() {
        val parsed = repository.parse(
            """{"profiles":[{"name":""},{"display_name":"no-name"},"junk",{"name":"ok"}],"active":"ok"}"""
        )

        assertEquals(listOf("ok"), parsed!!.profiles.map { it.name })
    }

    @Test
    fun `malformed body and non-object root return null`() {
        assertNull(repository.parse("not json"))
        assertNull(repository.parse(""))
        assertNull(repository.parse("[1,2,3]"))
    }

    @Test
    fun `parses active profile endpoint`() {
        val parsed = repository.parseActive(
            """
            {
              "name": "office",
              "display_name": "Office",
              "path": "/root/.hermes/profiles/office",
              "is_default": false,
              "default_workspace": "/root/office-workspace"
            }
            """.trimIndent()
        )

        assertEquals("office", parsed!!.name)
        assertEquals("Office", parsed.label)
        assertEquals("/root/.hermes/profiles/office", parsed.path)
        assertEquals("/root/office-workspace", parsed.defaultWorkspace)
        assertFalse(parsed.isDefault)
        // Усечённый ответ: только имя.
        assertEquals("default", repository.parseActive("""{"name":"default"}""")!!.name)
        assertNull(repository.parseActive("""{"display_name":"no-name"}"""))
        assertNull(repository.parseActive("junk"))
    }

    @Test
    fun `loads profiles over HTTP with device bearer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fullPayload))
            val result = repository.getProfiles(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/profiles", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("office", result!!.active)
            assertEquals(2, result.profiles.size)
        }
    }

    @Test
    fun `loads active profile over HTTP with device bearer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"default","is_default":true}"""))
            val result = repository.getActiveProfile(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/profile/active", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("default", result!!.name)
            assertTrue(result.isDefault)
        }
    }

    @Test
    fun `http failure maps to null not throw`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertNull(repository.getProfiles(server.url("/").toString(), credential))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
            assertNull(repository.getActiveProfile(server.url("/").toString(), credential))
        }
    }
}
