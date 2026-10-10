package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты офлайн-кэша списка проектов ([ProjectsOfflineCache]): round-trip сырого JSON,
 * валидация содержимого (пустое, превышение лимита), timestamp, устойчивость к битым
 * данным и падающему хранилищу, гигиена очистки.
 */
class ProjectsOfflineCacheTest {

    /** In-memory [KeyValueStore]; [failing] имитирует падающее хранилище (диск/IO). */
    private class FakeStore(var failing: Boolean = false) : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? {
            if (failing) throw IllegalStateException("store down")
            return map[key]
        }

        override fun putString(key: String, value: String) {
            if (failing) throw IllegalStateException("store down")
            map[key] = value
        }

        override fun remove(key: String) {
            if (failing) throw IllegalStateException("store down")
            map.remove(key)
        }

        override fun keys(): Set<String> {
            if (failing) throw IllegalStateException("store down")
            return map.keys.toSet()
        }

        override fun clearAll() {
            if (failing) throw IllegalStateException("store down")
            map.clear()
        }
    }

    private var now = 1_700_000_000_000L

    private fun newCache(store: KeyValueStore = FakeStore()) =
        ProjectsOfflineCache(store, clock = { now })

    private val sampleJson = """{"projects":[{"id":"p1","name":"Квартал","emoji":"🏠"}],"total":1}"""

    // --- round-trip ----------------------------------------------------------------------------

    @Test
    fun `json survives save and load byte for byte`() {
        val cache = newCache()

        assertTrue(cache.saveProjectsJson(sampleJson))
        assertEquals(sampleJson, cache.loadProjectsJson())
    }

    @Test
    fun `unicode quotes and newlines survive round trip`() {
        val cache = newCache()
        val json = """{"projects":[{"name":"Проект \"Марс\"","note":"строка1\nстрока2 — ünicode 🔥"}]}"""

        assertTrue(cache.saveProjectsJson(json))
        assertEquals(json, cache.loadProjectsJson())
    }

    @Test
    fun `re-save overwrites previous json and refreshes timestamp`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveProjectsJson("""{"old":true}""")

        now += 60_000L
        cache.saveProjectsJson("""{"new":true}""")

        assertEquals("""{"new":true}""", cache.loadProjectsJson())
        assertEquals(now, cache.getSavedTimestamp())
        assertTrue(store.map["projects_json"]!!.contains("\"saved_at\":$now"))
    }

    // --- валидация содержимого -------------------------------------------------------------------

    @Test
    fun `empty and blank json are rejected and do not touch store`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveProjectsJson(sampleJson)

        assertFalse(cache.saveProjectsJson(""))
        assertFalse(cache.saveProjectsJson("   \n\t "))
        // Живой снимок не вытеснен мусором.
        assertEquals(sampleJson, cache.loadProjectsJson())
    }

    @Test
    fun `json longer than limit is rejected whole not truncated`() {
        val store = FakeStore()
        val cache = newCache(store)

        val oversize = "{\"data\":\"" + "ы".repeat(200_001) + "\"}"
        assertTrue(oversize.length > 200_000)
        assertFalse(cache.saveProjectsJson(oversize))
        assertFalse(store.map.containsKey("projects_json"))
        assertNull(cache.loadProjectsJson())
    }

    @Test
    fun `json exactly at limit is accepted`() {
        val cache = newCache()
        val skeleton = "{\"data\":\"\"}" // 11 символов без наполнителя
        val exact = "{\"data\":\"" + "x".repeat(200_000 - skeleton.length) + "\"}"
        assertEquals(200_000, exact.length)

        assertTrue(cache.saveProjectsJson(exact))
        assertEquals(exact, cache.loadProjectsJson())
    }

    // --- timestamp -------------------------------------------------------------------------------

    @Test
    fun `timestamp reflects clock at save time and is null before any save`() {
        val cache = newCache()
        assertNull(cache.getSavedTimestamp())

        now = 1_700_000_777_000L
        cache.saveProjectsJson(sampleJson)

        assertEquals(1_700_000_777_000L, cache.getSavedTimestamp())
    }

    // --- устойчивость к битым данным --------------------------------------------------------------

    @Test
    fun `corrupt stored value reads as absent and never throws`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["projects_json"] = "{not json at all"

        assertNull(cache.loadProjectsJson())
        assertNull(cache.getSavedTimestamp())
    }

    @Test
    fun `foreign cache version reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["projects_json"] = """{"v":99,"saved_at":1,"json":"{\"a\":1}"}"""

        assertNull(cache.loadProjectsJson())
        assertNull(cache.getSavedTimestamp())
    }

    @Test
    fun `envelope without json field reads as absent but timestamp survives`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["projects_json"] = """{"v":1,"saved_at":42}"""

        assertNull(cache.loadProjectsJson())
        assertEquals(42L, cache.getSavedTimestamp())
    }

    @Test
    fun `failing store gives false on save and null on reads without throwing`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertFalse(cache.saveProjectsJson(sampleJson))
        assertNull(cache.loadProjectsJson())
        assertNull(cache.getSavedTimestamp())
        cache.clear() // не бросает
    }

    @Test
    fun `successful save recovers after corrupt value`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["projects_json"] = "мусор"
        assertNull(cache.loadProjectsJson())

        // Следующая успешная запись перетирает мусор.
        assertTrue(cache.saveProjectsJson(sampleJson))
        assertEquals(sampleJson, cache.loadProjectsJson())
    }

    // --- гигиена ---------------------------------------------------------------------------------

    @Test
    fun `clear wipes json and timestamp`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveProjectsJson(sampleJson)

        cache.clear()

        assertTrue(store.map.isEmpty())
        assertNull(cache.loadProjectsJson())
        assertNull(cache.getSavedTimestamp())
    }
}
