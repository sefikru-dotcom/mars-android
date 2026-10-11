package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * JVM-тесты офлайн-кэша сессий: round-trip, лимиты, устойчивость к битым данным и
 * падающему хранилищу, reconcile «свежий ответ или кэш», гигиена очистки.
 */
class SessionOfflineCacheTest {

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
        SessionOfflineCache(store, clock = { now })

    private fun session(id: String, title: String = "Диалог $id") = SessionItem(
        id = id,
        title = title,
        preview = "последний ответ $id",
        createdAt = "2026-10-01T10:00:00",
        updatedAt = "2026-10-02T11:00:00",
        messageCount = 7,
        sessionSource = "mobile",
        sourceTag = "tag",
        rawSource = "raw",
        sourceLabel = "Мобильный",
        readOnly = true,
        parentSessionId = "parent-$id",
        model = "@custom:plusvibe-api:gpt-6.1-sol",
        modelProvider = "custom:plusvibe-api",
    )

    private fun message(id: String, role: String = "user", content: String = "текст $id") =
        SessionMessage(id = id, role = role, content = content, createdAt = "2026-10-02T11:00:0$id", eventId = "ev-$id")

    // --- round-trip -------------------------------------------------------------------------

    @Test
    fun `sessions survive save and load with all scalar fields`() {
        val cache = newCache()
        val items = listOf(session("s1"), session("s2", "Второй"))

        assertTrue(cache.saveSessions(items))
        val snapshot = cache.loadSessions()

        assertEquals(now, snapshot?.savedAtEpochMillis)
        assertEquals(items, snapshot?.sessions)
    }

    @Test
    fun `active turn metadata is intentionally not cached`() {
        val cache = newCache()
        val live = session("s1").copy(
            activeTurn = ActiveTurnInfo(
                activeStreamId = "st-1",
                isStreaming = true,
                hasPendingUserMessage = true,
                attention = SessionAttention(kind = "approval", count = 2, severity = "critical"),
            ),
        )

        cache.saveSessions(listOf(live))
        val loaded = cache.loadSessions()?.sessions?.single()

        // Кэшированная строка загружается idle: stale stream_id/attention не должны
        // управлять reattach или баннерами — решает только живой ответ сервера.
        assertEquals(ActiveTurnInfo(), loaded?.activeTurn)
    }

    @Test
    fun `unicode quotes and newlines survive json round trip`() {
        val cache = newCache()
        val tricky = session("s1", title = "Кавычки \" и\nпереносы — ünicode 🔥")

        cache.saveSessions(listOf(tricky))

        assertEquals(tricky, cache.loadSessions()?.sessions?.single())
    }

    @Test
    fun `messages survive save and load per session`() {
        val cache = newCache()
        val messages = listOf(message("1"), message("2", role = "assistant", content = "ответ"))

        assertTrue(cache.saveMessages("s1", messages))
        assertEquals(messages, cache.loadMessages("s1"))
        // Чужой диалог не затронут.
        assertNull(cache.loadMessages("s2"))
    }

    @Test
    fun `empty session list round trips as empty snapshot not null`() {
        val cache = newCache()

        assertTrue(cache.saveSessions(emptyList()))
        assertEquals(emptyList<SessionItem>(), cache.loadSessions()?.sessions)
    }

    @Test
    fun `saved at follows injected clock`() {
        val store = FakeStore()
        val cache = newCache(store)
        now = 1_800_000_000_000L

        cache.saveSessions(listOf(session("s1")))

        assertEquals(1_800_000_000_000L, cache.loadSessions()?.savedAtEpochMillis)
    }

    // --- лимиты ------------------------------------------------------------------------------

    @Test
    fun `session list is capped keeping the newest prefix`() {
        val cache = newCache()
        val many = (1..150).map { session("s$it") }

        cache.saveSessions(many)
        val loaded = cache.loadSessions()?.sessions

        assertEquals(100, loaded?.size)
        assertEquals(many.take(100), loaded)
    }

    @Test
    fun `message list keeps only the tail within the cap`() {
        val cache = newCache()
        val many = (1..80).map { message("$it") }

        cache.saveMessages("s1", many)
        val loaded = cache.loadMessages("s1")

        assertEquals(50, loaded?.size)
        assertEquals(many.takeLast(50), loaded)
    }

    @Test
    fun `oversized text fields are truncated before persisting`() {
        val cache = newCache()
        val longTitle = "t".repeat(500)
        val longContent = "c".repeat(20_000)

        cache.saveSessions(listOf(session("s1", title = longTitle)))
        cache.saveMessages("s1", listOf(message("1", content = longContent)))

        assertEquals(200, cache.loadSessions()?.sessions?.single()?.title?.length)
        assertEquals(8_000, cache.loadMessages("s1")?.single()?.content?.length)
    }

    // --- устойчивость к мусору ----------------------------------------------------------------

    @Test
    fun `corrupt json reads as absent and never throws`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["sessions"] = "{not json at all"
        store.map["messages:s1"] = "[1,2,3]"

        assertNull(cache.loadSessions())
        assertNull(cache.loadMessages("s1"))
    }

    @Test
    fun `foreign cache version reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["sessions"] = """{"v":99,"saved_at":1,"sessions":[]}"""

        assertNull(cache.loadSessions())
    }

    @Test
    fun `rows without id are dropped but valid rows survive`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["sessions"] = """
            {"v":1,"saved_at":5,"sessions":[
                {"title":"без id"},
                {"id":"","title":"пустой id"},
                {"id":"ok","title":"Живой"}
            ]}
        """.trimIndent()

        val loaded = cache.loadSessions()?.sessions

        assertEquals(listOf("ok"), loaded?.map { it.id })
        assertEquals("Живой", loaded?.single()?.title)
    }

    @Test
    fun `garbage overwrites on next successful save`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["sessions"] = "мусор"

        assertNull(cache.loadSessions())
        cache.saveSessions(listOf(session("s1")))

        assertEquals(listOf("s1"), cache.loadSessions()?.sessions?.map { it.id })
    }

    @Test
    fun `failing store degrades to absent data instead of crashing`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertFalse(cache.saveSessions(listOf(session("s1"))))
        assertNull(cache.loadSessions())
        assertFalse(cache.saveMessages("s1", listOf(message("1"))))
        assertNull(cache.loadMessages("s1"))
        cache.removeMessages("s1")
        cache.clear()
    }

    @Test
    fun `blank session id is rejected by message apis`() {
        val cache = newCache()

        assertFalse(cache.saveMessages("", listOf(message("1"))))
        assertFalse(cache.saveMessages("   ", listOf(message("1"))))
        assertNull(cache.loadMessages(""))
        cache.removeMessages("")
    }

    // --- reconcile ---------------------------------------------------------------------------

    @Test
    fun `reconcile saves and returns fresh non empty list`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("cached")))
        val fresh = listOf(session("fresh"))

        assertEquals(fresh, cache.reconcile(fresh))
        assertEquals(listOf("fresh"), cache.loadSessions()?.sessions?.map { it.id })
    }

    @Test
    fun `reconcile falls back to cache on empty fresh result`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("cached")))

        assertEquals(listOf("cached"), cache.reconcile(emptyList()).map { it.id })
    }

    @Test
    fun `reconcile without cache and without fresh returns empty`() {
        assertEquals(emptyList<SessionItem>(), newCache().reconcile(emptyList()))
    }

    @Test
    fun `reconcile with corrupt cache returns empty not crash`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["sessions"] = "битые данные"

        assertEquals(emptyList<SessionItem>(), cache.reconcile(emptyList()))
    }

    // --- удаление и очистка -------------------------------------------------------------------

    @Test
    fun `removeMessages drops only the target session`() {
        val cache = newCache()
        cache.saveMessages("s1", listOf(message("1")))
        cache.saveMessages("s2", listOf(message("2")))

        cache.removeMessages("s1")

        assertNull(cache.loadMessages("s1"))
        assertEquals(1, cache.loadMessages("s2")?.size)
    }

    @Test
    fun `clear wipes sessions and messages`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveSessions(listOf(session("s1")))
        cache.saveMessages("s1", listOf(message("1")))

        cache.clear()

        assertTrue(store.map.isEmpty())
        assertNull(cache.loadSessions())
        assertNull(cache.loadMessages("s1"))
    }
}
