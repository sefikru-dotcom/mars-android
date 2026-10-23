package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.cache.KeyValueStore
import ru.mars.network.cache.ProjectsOfflineCache
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * Тесты [ProjectsFeedLoader] — контур «сеть, иначе офлайн-снимок»: реальный
 * [ProjectsRepository] на MockWebServer + настоящий [ProjectsOfflineCache] поверх
 * in-memory [KeyValueStore] (паттерн `TvProjectsControllerTest`), без Android.
 *
 * Проверяется лестница источников: успех -> Live + запись сырого JSON в кэш; сбой сети
 * при пригодном снимке -> Cached с моментом сохранения; просроченный/нечитаемый/битый
 * снимок -> Unavailable (фолбэк на статику решает уже экран); мгновенный старт из кэша
 * не ходит в сеть.
 */
class ProjectsFeedLoaderTest {

    /** In-memory порт хранилища: настоящая сериализация [ProjectsOfflineCache]. */
    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
        override fun clearAll() { map.clear() }
    }

    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var cache: ProjectsOfflineCache
    private var now = 1_700_000_000_000L

    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    private val body = """
        {"projects": [
          {"project_id": "p1", "name": "Квартал", "path": "/root/kvartal", "profile": "default"}
        ], "all_profiles": false, "active_profile": "default", "other_profile_count": 0}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeStore()
        cache = ProjectsOfflineCache(store, clock = { now })
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun loader() = ProjectsFeedLoader(
        repository = ProjectsRepository(),
        cache = cache,
        clock = { now },
    )

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(code: Int = 200, payload: String = body) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(payload),
        )
    }

    // ── успех ───────────────────────────────────────────────────────────────

    @Test
    fun `success returns live snapshot and saves raw json to cache`() = runBlocking {
        enqueue()

        val feed = loader().load(baseUrl(), credential)

        assertTrue("ожидался Live, получено $feed", feed is ProjectsFeed.Live)
        assertEquals("Квартал", (feed as ProjectsFeed.Live).snapshot.projects.single().name)
        assertEquals("в кэш пишется тело сервера как есть", body, cache.loadProjectsJson())
        assertEquals(now, cache.getSavedTimestamp())
    }

    @Test
    fun `empty live list is live and overwrites stale cache`() = runBlocking {
        cache.saveProjectsJson(body)
        enqueue(payload = """{"projects": [], "active_profile": "default"}""")

        val feed = loader().load(baseUrl(), credential)

        assertTrue("пустой ответ сервера — валидное состояние", feed is ProjectsFeed.Live)
        assertTrue((feed as ProjectsFeed.Live).snapshot.projects.isEmpty())
        assertEquals(
            "свежий пустой ответ должен вытеснять устаревший снимок",
            """{"projects": [], "active_profile": "default"}""",
            cache.loadProjectsJson(),
        )
    }

    // ── фолбэк на кэш ───────────────────────────────────────────────────────

    @Test
    fun `network failure falls back to cached snapshot with saved timestamp`() = runBlocking {
        enqueue()
        loader().load(baseUrl(), credential) // наполняем кэш живым ответом
        now += 5 * 60_000L
        enqueue(code = 503, payload = """{"error": "upstream down"}""")

        val feed = loader().load(baseUrl(), credential)

        assertTrue("ожидался Cached, получено $feed", feed is ProjectsFeed.Cached)
        val cached = feed as ProjectsFeed.Cached
        assertEquals("Квартал", cached.snapshot.projects.single().name)
        assertEquals("метка нужна для бейджа возраста", now - 5 * 60_000L, cached.savedAtMs)
    }

    @Test
    fun `transport failure falls back to cached snapshot`() = runBlocking {
        cache.saveProjectsJson(body)
        server.shutdown()

        val feed = loader().load(baseUrl(), credential)

        assertTrue("обрыв сети при снимке в кэше — Cached, получено $feed", feed is ProjectsFeed.Cached)
    }

    @Test
    fun `read cache returns snapshot without touching the network`() {
        cache.saveProjectsJson(body)

        val feed = loader().readCache()

        assertTrue(feed is ProjectsFeed.Cached)
        assertEquals("мгновенный старт не ходит в сеть", 0, server.requestCount)
    }

    // ── «данных нет» ────────────────────────────────────────────────────────

    @Test
    fun `no cache and failed network is unavailable`() = runBlocking {
        enqueue(code = 500, payload = """{"error": "boom"}""")

        val feed = loader().load(baseUrl(), credential)

        assertEquals(ProjectsFeed.Unavailable(ProjectsFeedLoader.REASON_NO_SNAPSHOT, "HTTP 500: boom"), feed)
    }

    @Test
    fun `expired snapshot is never shown as live`() = runBlocking {
        cache.saveProjectsJson(body)
        now += ProjectsFeedLoader.DEFAULT_MAX_AGE_MS + 1
        enqueue(code = 500, payload = """{"error": "boom"}""")

        val feed = loader().load(baseUrl(), credential)

        assertTrue("просроченный снимок хуже честного фолбэка, получено $feed", feed is ProjectsFeed.Unavailable)
        assertEquals(ProjectsFeedLoader.REASON_EXPIRED, (feed as ProjectsFeed.Unavailable).reason)
    }

    @Test
    fun `snapshot exactly at the age limit is still usable`() = runBlocking {
        cache.saveProjectsJson(body)
        now += ProjectsFeedLoader.DEFAULT_MAX_AGE_MS
        enqueue(code = 500, payload = """{"error": "boom"}""")

        assertTrue(loader().load(baseUrl(), credential) is ProjectsFeed.Cached)
    }

    @Test
    fun `unreadable snapshot is unavailable not an empty list`() = runBlocking {
        cache.saveProjectsJson("<html>captive portal")
        enqueue(code = 500, payload = """{"error": "boom"}""")

        val feed = loader().load(baseUrl(), credential)

        assertEquals(ProjectsFeedLoader.REASON_UNREADABLE, (feed as ProjectsFeed.Unavailable).reason)
    }

    @Test
    fun `cache without timestamp is unavailable`() = runBlocking {
        // Служебная запись без saved_at: возраст снимка недоказуем.
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val envelope = Moshi.Builder().build()
            .adapter<Map<String, Any?>>(mapType)
            .toJson(mapOf("v" to 1, "json" to body))
        store.putString("projects_json", envelope)
        enqueue(code = 500, payload = """{"error": "boom"}""")

        val feed = loader().load(baseUrl(), credential)

        assertEquals(ProjectsFeedLoader.REASON_UNVERIFIED, (feed as ProjectsFeed.Unavailable).reason)
    }

    @Test
    fun `clock skew backwards does not expire a fresh snapshot`() = runBlocking {
        cache.saveProjectsJson(body)
        now -= 60_000L // часы уехали назад (NTP/ручная правка)
        enqueue(code = 500, payload = """{"error": "boom"}""")

        assertTrue("отрицательный возраст клампится в ноль", loader().load(baseUrl(), credential) is ProjectsFeed.Cached)
    }
}
