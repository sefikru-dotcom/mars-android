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
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * Контрактные тесты [ProjectsRepository] на MockWebServer против формы, проверенной в
 * `/opt/hermes-webui/api/routes.py` (`GET /api/projects`):
 * `{projects: [...], all_profiles, active_profile, other_profile_count}`.
 *
 * Покрывают: маппинг запроса (путь, Bearer, `?all_profiles=1` только по запросу),
 * разбор валидного ответа, ВАЛИДНЫЙ пустой список (не отказ), терпимость к неполным
 * строкам и отказ на битом теле/не-2xx/обрыве транспорта.
 */
class ProjectsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ProjectsRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = ProjectsRepository()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(code: Int = 200, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private val validBody = """
        {"projects": [
          {"project_id": "a1b2c3d4e5f6", "name": "Квартал", "path": "/root/kvartal",
           "profile": "default", "color": "#6366f1", "created_at": 1750000000.5},
          {"project_id": "0f0e0d0c0b0a", "name": "Альпен", "path": "/root/alpen", "profile": null}
        ],
        "all_profiles": false,
        "active_profile": "default",
        "other_profile_count": 2}
    """.trimIndent()

    // ── запрос ──────────────────────────────────────────────────────────────

    @Test
    fun `sends device bearer to api-projects without all_profiles by default`() = runBlocking {
        enqueue(body = validBody)

        val result = repository.getProjects(baseUrl(), credential)

        assertTrue("ожидался Loaded, получено $result", result is ProjectsResult.Loaded)
        val request = server.takeRequest()
        assertEquals("/api/projects", request.path)
        assertEquals("Bearer native-token", request.getHeader("Authorization"))
    }

    @Test
    fun `all_profiles flag adds aggregate query param`() = runBlocking {
        enqueue(body = validBody)

        repository.getProjects(baseUrl(), credential, allProfiles = true)

        assertEquals("/api/projects?all_profiles=1", server.takeRequest().path)
    }

    // ── разбор ──────────────────────────────────────────────────────────────

    @Test
    fun `parses valid response into domain snapshot`() = runBlocking {
        enqueue(body = validBody)

        val loaded = repository.getProjects(baseUrl(), credential) as ProjectsResult.Loaded

        assertEquals(2, loaded.snapshot.projects.size)
        assertEquals("default", loaded.snapshot.activeProfile)
        assertEquals(false, loaded.snapshot.allProfiles)
        assertEquals(2, loaded.snapshot.otherProfileCount)
        assertEquals("Квартал", loaded.snapshot.projects[0].name)
        assertEquals("a1b2c3d4e5f6", loaded.snapshot.projects[0].id)
        assertEquals("/root/kvartal", loaded.snapshot.projects[0].path)
        assertEquals("default", loaded.snapshot.projects[0].profile)
        assertEquals("#6366f1", loaded.snapshot.projects[0].color)
        assertNull("null profile не должен превращаться в строку \"null\"", loaded.snapshot.projects[1].profile)
        assertEquals("сырое тело нужно для кэша как есть", validBody, loaded.rawJson)
    }

    @Test
    fun `empty projects list is a valid answer not a failure`() = runBlocking {
        enqueue(body = """{"projects": [], "all_profiles": false, "active_profile": "default", "other_profile_count": 0}""")

        val loaded = repository.getProjects(baseUrl(), credential) as ProjectsResult.Loaded

        assertTrue("пустой список — валидное состояние сервера", loaded.snapshot.projects.isEmpty())
        assertEquals("default", loaded.snapshot.activeProfile)
    }

    @Test
    fun `entries without name are dropped and missing fields degrade`() = runBlocking {
        enqueue(
            body = """
                {"projects": [
                  {"project_id": 7, "name": "Хост Aeza"},
                  {"project_id": "no-name", "path": "/root/x"},
                  "garbage",
                  {"name": "  "}
                ], "active_profile": "default"}
            """.trimIndent(),
        )

        val loaded = repository.getProjects(baseUrl(), credential) as ProjectsResult.Loaded

        assertEquals("без имени строку рисовать нечем", 1, loaded.snapshot.projects.size)
        assertEquals("Хост Aeza", loaded.snapshot.projects.single().name)
        assertEquals("7", loaded.snapshot.projects.single().id)
        assertEquals("", loaded.snapshot.projects.single().path)
        assertNull(loaded.snapshot.projects.single().profile)
    }

    // ── отказы ──────────────────────────────────────────────────────────────

    @Test
    fun `malformed body is a failure not an empty list`() = runBlocking {
        enqueue(body = "<html>502 gateway {oops")

        val result = repository.getProjects(baseUrl(), credential)

        assertTrue("битый JSON не должен выглядеть как «проектов нет»", result is ProjectsResult.Failed)
    }

    @Test
    fun `http error surfaces server reason`() = runBlocking {
        enqueue(code = 503, body = """{"error": "backend warming up"}""")

        val failed = repository.getProjects(baseUrl(), credential) as ProjectsResult.Failed

        assertEquals("HTTP 503: backend warming up", failed.message)
    }

    @Test
    fun `transport failure is reported as network error`() = runBlocking {
        enqueue(body = validBody)
        server.shutdown()

        val result = repository.getProjects(baseUrl(), credential)

        assertTrue("обрыв транспорта — отказ, а не пустой список", result is ProjectsResult.Failed)
        assertTrue((result as ProjectsResult.Failed).message.isNotBlank())
    }
}
