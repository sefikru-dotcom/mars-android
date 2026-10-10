package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * JVM-тесты локального офлайн-поиска ([SessionOfflineCache.search]) и LRU-вытеснения
 * кэшей сообщений: совпадения по полям, сниппеты и их границы, порядок и лимит
 * результатов, деградация при падающем/битом хранилище, продвижение и вытеснение LRU.
 */
class SessionOfflineCacheSearchTest {

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

    private fun session(
        id: String,
        title: String? = "Диалог $id",
        preview: String? = "последний ответ $id",
    ) = SessionItem(id = id, title = title, preview = preview, createdAt = "2026-10-01T10:00:00")

    private fun message(role: String, content: String) =
        SessionMessage(id = null, role = role, content = content, createdAt = null, eventId = null)

    // --- поиск: совпадения по полям ---------------------------------------------------------------

    @Test
    fun `search matches title case-insensitively and reports field`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("s1", title = "Погода в Ставрополе"), session("s2")))

        val results = cache.search("ПОГОДА")

        assertEquals(1, results.size)
        assertEquals("s1", results[0].session.id)
        assertTrue(SessionSearchField.TITLE in results[0].matchedIn)
        // preview совпадения не даёт — сниппет берётся из preview как контекст
        assertNotNull(results[0].snippet)
    }

    @Test
    fun `search matches session id`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("abc-123-xyz"), session("other")))

        val results = cache.search("123")

        assertEquals(1, results.size)
        assertTrue(SessionSearchField.ID in results[0].matchedIn)
    }

    @Test
    fun `search matches preview and builds bounded snippet around match`() {
        val cache = newCache()
        // preview кэшируется с обрезкой до 300 символов — «иголка» должна быть внутри лимита,
        // с хвостом, чтобы обрезка сниппета с двух сторон помечалась многоточиями
        val long = "слово ".repeat(25) + "ИГОЛКА" + " хвост".repeat(24)
        cache.saveSessions(listOf(session("s1", title = "Заголовок", preview = long)))

        val results = cache.search("иголка")

        assertEquals(1, results.size)
        val snippet = results[0].snippet
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("ИГОЛКА", ignoreCase = true))
        assertTrue("сниппет ограничен 200 символами", snippet.length <= 200)
        assertTrue("обрезка помечается многоточием", snippet.startsWith("…") && snippet.endsWith("…"))
        assertTrue(SessionSearchField.PREVIEW in results[0].matchedIn)
    }

    @Test
    fun `search falls back to cached message content with snippet`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("s1", title = "Заголовок", preview = "обычный текст")))
        cache.saveMessages("s1", listOf(message("user", "спросил про отпуск"), message("assistant", "Ответ: марсианский\nкод 42")))

        val results = cache.search("МАРСИАНСКИЙ")

        assertEquals(1, results.size)
        assertEquals(setOf(SessionSearchField.MESSAGE), results[0].matchedIn)
        assertTrue(results[0].snippet!!.contains("марсианский"))
        // переносы в сниппете схлопнуты в пробел
        assertTrue(results[0].snippet!!.contains("марсианский код 42"))
    }

    @Test
    fun `search keeps cache order and respects limit`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("a-match"), session("b-match"), session("c-match")))

        val results = cache.search("match", limit = 2)

        assertEquals(listOf("a-match", "b-match"), results.map { it.session.id })
    }

    @Test
    fun `search trims query and truncates oversized input`() {
        val cache = newCache()
        cache.saveSessions(listOf(session("s1", title = "a".repeat(300), preview = null)))

        // пробелы по краям не мешают; запрос в 500 символов усекается до 200 и всё ещё матчит
        assertEquals(1, cache.search("  a".plus("a".repeat(497)) + "  ").size)
    }

    // --- поиск: пустые и аварийные входы -----------------------------------------------------------

    @Test
    fun `blank query and absent cache give empty results`() {
        val cache = newCache()
        assertTrue(cache.search("").isEmpty())
        assertTrue(cache.search("   ").isEmpty())
        assertTrue(cache.search("что угодно").isEmpty()) // кэш сессий не сохранён
        assertTrue(cache.search("запрос", limit = 0).isEmpty())
        assertTrue(cache.search("запрос", limit = -1).isEmpty())
    }

    @Test
    fun `search survives failing store without throwing`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveSessions(listOf(session("s1")))
        store.failing = true

        assertTrue(cache.search("s1").isEmpty())
    }

    // --- поиск и LRU ------------------------------------------------------------------------------

    @Test
    fun `search does not promote lru - scanned session is still evicted first`() {
        val cache = newCache()
        val sessions = (1..20).map { session("s$it") }
        cache.saveSessions(sessions)
        // забиваем LRU до потолка (20 кэшей): s1 — самый старый (хвост индекса),
        // сообщение с маркером — только у него, поиск прочитает его контент
        for (i in 1..20) {
            val content = if (i == 1) "секрет-маркер внутри" else "наполнитель $i"
            cache.saveMessages("s$i", listOf(message("user", content)))
        }

        val results = cache.search("секрет-маркер")
        assertEquals(1, results.size)

        // новая запись вытесняет хвост LRU; если бы поиск продвигал s1, вытеснился бы s2
        cache.saveMessages("s21", listOf(message("user", "привет")))
        assertNull("прочитанный поиском кэш не продвигается и вытесняется первым", cache.loadMessages("s1"))
        assertNotNull(cache.loadMessages("s2"))
    }

    @Test
    fun `message caches beyond cap are lru-evicted`() {
        val cache = newCache()
        for (i in 1..25) cache.saveMessages("s$i", listOf(message("user", "контент $i")))

        for (i in 1..5) assertNull("старый кэш s$i вытеснен", cache.loadMessages("s$i"))
        for (i in 6..25) assertNotNull("свежий кэш s$i на месте", cache.loadMessages("s$i"))
    }

    @Test
    fun `load promotes session in lru`() {
        val cache = newCache()
        for (i in 1..20) cache.saveMessages("s$i", listOf(message("user", "контент $i")))

        // открыли самый старый диалог — он переезжает в голову LRU
        assertNotNull(cache.loadMessages("s1"))
        cache.saveMessages("s21", listOf(message("user", "новый")))

        assertNull("хвостом стал s2 — он и вытеснен", cache.loadMessages("s2"))
        assertNotNull("продвинутый s1 выжил", cache.loadMessages("s1"))
        assertNotNull(cache.loadMessages("s21"))
    }

    @Test
    fun `removeMessages drops session from lru index`() {
        val cache = newCache()
        for (i in 1..20) cache.saveMessages("s$i", listOf(message("user", "контент $i")))

        cache.removeMessages("s20") // голова LRU убрана — индекс на 19 записей
        cache.saveMessages("s21", listOf(message("user", "новый")))

        assertNull(cache.loadMessages("s20"))
        assertNotNull("после удаления головы вытеснения нет — s1 выжил", cache.loadMessages("s1"))
        assertNotNull(cache.loadMessages("s21"))
    }

    @Test
    fun `corrupt lru index is rebuilt from store keys without data loss`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveMessages("s1", listOf(message("user", "один")))
        cache.saveMessages("s2", listOf(message("user", "два")))
        store.map["msg_lru"] = "{это не JSON список"

        assertTrue(cache.saveMessages("s3", listOf(message("user", "три"))))
        assertNotNull(cache.loadMessages("s1"))
        assertNotNull(cache.loadMessages("s2"))
        assertNotNull(cache.loadMessages("s3"))
    }

    @Test
    fun `failing store keeps lru bookkeeping silent`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertTrue(!cache.saveMessages("s1", listOf(message("user", "контент"))))
        assertNull(cache.loadMessages("s1"))
        cache.removeMessages("s1") // не бросает
    }
}
