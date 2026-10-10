package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary

/**
 * JVM-тесты офлайн-кэша снимков todo_state ([TodosOfflineCache]): round-trip глобального
 * и сессионных снимков, лимиты (число задач, длина строк), LRU-вытеснение сессий,
 * timestamp снимка, устойчивость к битым данным и падающему хранилищу, гигиена очистки.
 */
class TodosOfflineCacheTest {

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
        TodosOfflineCache(store, clock = { now })

    private fun todo(
        id: String? = "t1",
        content: String = "задача",
        status: String = "pending",
    ) = TodoItem(id = id, content = content, status = status)

    private fun snapshot(
        todos: List<TodoItem> = listOf(todo()),
        summary: TodoSummary = TodoSummary(total = todos.size),
        version: Int = 1,
        tsEpochSeconds: Double? = 1_700_000_000.5,
    ) = TodoSnapshot(
        todos = todos,
        summary = summary,
        version = version,
        tsEpochSeconds = tsEpochSeconds,
    )

    // --- round-trip ----------------------------------------------------------------------------

    @Test
    fun `global snapshot survives save and load with all fields`() {
        val cache = newCache()
        val snap = snapshot(
            todos = listOf(
                todo("t1", "первая", "pending"),
                todo("t2", "вторая", "in_progress"),
                todo("t3", "третья", "completed"),
                todo("t4", "четвёртая", "cancelled"),
            ),
            summary = TodoSummary(total = 4, pending = 1, inProgress = 1, completed = 1, cancelled = 1),
            version = 1,
            tsEpochSeconds = 1_700_000_123.25,
        )

        assertTrue(cache.saveTodos(null, snap))
        // sessionId/streamId/source глобального снимка остаются null.
        assertEquals(snap, cache.loadTodos(null))
    }

    @Test
    fun `session snapshot survives save and load and restores sessionId`() {
        val cache = newCache()
        val snap = snapshot(todos = listOf(todo("t1", "задача сессии", "in_progress")))

        assertTrue(cache.saveTodos("s1", snap))
        val loaded = cache.loadTodos("s1")
        assertEquals("s1", loaded?.sessionId)
        assertEquals(snap.todos, loaded?.todos)
        assertEquals(snap.summary, loaded?.summary)
        assertEquals(snap.tsEpochSeconds, loaded?.tsEpochSeconds)
    }

    @Test
    fun `live envelope fields streamId and source are deliberately not cached`() {
        val cache = newCache()
        val snap = snapshot().copy(sessionId = "s1", streamId = "stream-9", source = "tool")

        assertTrue(cache.saveTodos("s1", snap))
        val loaded = cache.loadTodos("s1")
        // Рантайм-метаданные живого хода устаревают за время офлайна — не кэшируются.
        assertNull(loaded?.streamId)
        assertNull(loaded?.source)
        assertEquals("s1", loaded?.sessionId)
    }

    @Test
    fun `empty todos list round trips as empty snapshot not null`() {
        val cache = newCache()
        // todos == [] — ВАЛИДНЫЙ снимок (последняя запись очистила список).
        val snap = snapshot(todos = listOf(), summary = TodoSummary())

        assertTrue(cache.saveTodos("s1", snap))
        assertEquals(emptyList<TodoItem>(), cache.loadTodos("s1")?.todos)
    }

    @Test
    fun `unicode quotes and newlines survive json round trip`() {
        val cache = newCache()
        val snap = snapshot(
            todos = listOf(todo("t1", "Кавычки \" и 'переносы'\n— ünicode 🔥\nвторая строка", "pending")),
        )

        assertTrue(cache.saveTodos(null, snap))
        assertEquals(snap.todos, cache.loadTodos(null)?.todos)
    }

    @Test
    fun `re-save overwrites previous snapshot`() {
        val cache = newCache()
        cache.saveTodos("s1", snapshot(todos = listOf(todo(content = "старая"))))

        cache.saveTodos("s1", snapshot(todos = listOf(todo(content = "новая"))))

        assertEquals("новая", cache.loadTodos("s1")?.todos?.single()?.content)
    }

    @Test
    fun `different sessions do not collide and global slot is separate`() {
        val cache = newCache()
        cache.saveTodos("s1", snapshot(todos = listOf(todo(content = "сессия 1"))))
        cache.saveTodos("s2", snapshot(todos = listOf(todo(content = "сессия 2"))))
        cache.saveTodos(null, snapshot(todos = listOf(todo(content = "глобальный"))))

        assertEquals("сессия 1", cache.loadTodos("s1")?.todos?.single()?.content)
        assertEquals("сессия 2", cache.loadTodos("s2")?.todos?.single()?.content)
        assertEquals("глобальный", cache.loadTodos(null)?.todos?.single()?.content)
    }

    @Test
    fun `unknown session loads as null`() {
        val cache = newCache()
        cache.saveTodos("s1", snapshot())

        assertNull(cache.loadTodos("s-missing"))
        assertNull(cache.loadTodos(null))
    }

    @Test
    fun `blank sessionId is normalized to the global slot`() {
        val cache = newCache()
        assertTrue(cache.saveTodos("   ", snapshot(todos = listOf(todo(content = "как глобальный")))))

        assertEquals("как глобальный", cache.loadTodos(null)?.todos?.single()?.content)
        assertEquals("как глобальный", cache.loadTodos("")?.todos?.single()?.content)
    }

    // --- лимиты --------------------------------------------------------------------------------

    @Test
    fun `todos beyond max count are truncated`() {
        val cache = newCache()
        val many = (1..250).map { todo("t$it", "задача $it") }

        assertTrue(cache.saveTodos(null, snapshot(todos = many)))
        val loaded = cache.loadTodos(null)?.todos
        assertEquals(200, loaded?.size)
        // Хвост отброшен, голова сохранена в исходном порядке.
        assertEquals("задача 1", loaded?.first()?.content)
        assertEquals("задача 200", loaded?.last()?.content)
    }

    @Test
    fun `long content is truncated to limit`() {
        val cache = newCache()
        val long = "ы".repeat(600)

        cache.saveTodos(null, snapshot(todos = listOf(todo(content = long))))
        assertEquals(500, cache.loadTodos(null)?.todos?.single()?.content?.length)
    }

    @Test
    fun `long id and status are truncated to limits`() {
        val cache = newCache()
        cache.saveTodos(null, snapshot(todos = listOf(todo(id = "i".repeat(200), status = "s".repeat(50)))))

        val loaded = cache.loadTodos(null)?.todos?.single()
        assertEquals(128, loaded?.id?.length)
        assertEquals(32, loaded?.status?.length)
    }

    // --- timestamp -----------------------------------------------------------------------------

    @Test
    fun `snapshot timestamp reflects clock at save time`() {
        val store = FakeStore()
        val cache = newCache(store)
        assertNull(cache.getSnapshotTimestamp(null))

        now = 1_700_000_555_000L
        cache.saveTodos("s1", snapshot())
        cache.saveTodos(null, snapshot())

        assertEquals(1_700_000_555_000L, cache.getSnapshotTimestamp("s1"))
        assertEquals(1_700_000_555_000L, cache.getSnapshotTimestamp(null))
        assertTrue(store.map["todos:s1"]!!.contains("\"saved_at\":$now"))
    }

    @Test
    fun `timestamp updates on re-save and is null for unknown session`() {
        val cache = newCache()
        cache.saveTodos("s1", snapshot())
        now += 60_000L
        cache.saveTodos("s1", snapshot())

        assertEquals(now, cache.getSnapshotTimestamp("s1"))
        assertNull(cache.getSnapshotTimestamp("s-unknown"))
    }

    @Test
    fun `timestamp does not touch lru order`() {
        val store = FakeStore()
        val cache = newCache(store)
        // Заполняем LRU под завязку: s1..s20.
        (1..20).forEach { cache.saveTodos("s$it", snapshot()) }

        // Чтение timestamp s1 НЕ продвигает его в голову: следующий слот вытеснит именно s1.
        cache.getSnapshotTimestamp("s1")
        cache.saveTodos("s21", snapshot())

        assertNull(cache.loadTodos("s1"))
        assertFalse(store.map.containsKey("todos:s1"))
        assertTrue(store.map.containsKey("todos:s2"))
    }

    // --- LRU-вытеснение -------------------------------------------------------------------------

    @Test
    fun `session snapshots beyond lru capacity are evicted oldest first`() {
        val store = FakeStore()
        val cache = newCache(store)

        (1..21).forEach { assertTrue(cache.saveTodos("s$it", snapshot())) }

        // Самый давно сохранённый вытеснен вместе с данными.
        assertNull(cache.loadTodos("s1"))
        assertFalse(store.map.containsKey("todos:s1"))
        // Остальные 20 на месте.
        (2..21).forEach { assertTrue("s$it evicted", store.map.containsKey("todos:s$it")) }
    }

    @Test
    fun `load promotes session in lru so it survives eviction`() {
        val store = FakeStore()
        val cache = newCache(store)
        (1..20).forEach { cache.saveTodos("s$it", snapshot()) }

        // s1 была хвостом; успешное чтение продвигает её в голову.
        cache.loadTodos("s1")
        cache.saveTodos("s21", snapshot())

        assertTrue(store.map.containsKey("todos:s1"))
        // Теперь хвост — s2, она и вытеснена.
        assertNull(cache.loadTodos("s2"))
        assertFalse(store.map.containsKey("todos:s2"))
    }

    @Test
    fun `save promotes existing session instead of duplicating lru entry`() {
        val store = FakeStore()
        val cache = newCache(store)
        (1..20).forEach { cache.saveTodos("s$it", snapshot()) }

        // Пересохранение s1 продвигает её в голову, не плодя дубль в индексе.
        cache.saveTodos("s1", snapshot())
        cache.saveTodos("s21", snapshot())

        assertTrue(store.map.containsKey("todos:s1"))
        assertNull(cache.loadTodos("s2"))
    }

    @Test
    fun `global slot does not participate in lru eviction`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveTodos(null, snapshot(todos = listOf(todo(content = "глобальный"))))

        // 25 сессионных снимков подряд — глобальный слот не вытесняется.
        (1..25).forEach { cache.saveTodos("s$it", snapshot()) }

        assertEquals("глобальный", cache.loadTodos(null)?.todos?.single()?.content)
        assertTrue(store.map.containsKey("todos_global"))
    }

    @Test
    fun `corrupt lru index is rebuilt from store keys without data loss`() {
        val store = FakeStore()
        val cache = newCache(store)
        (1..5).forEach { cache.saveTodos("s$it", snapshot(todos = listOf(todo(content = "контент s$it")))) }

        store.map["todos_lru"] = "{это не JSON список"
        cache.saveTodos("s6", snapshot(todos = listOf(todo(content = "контент s6"))))

        // Данные не потеряны, новая сессия читается.
        (1..6).forEach { assertEquals("контент s$it", cache.loadTodos("s$it")?.todos?.singleOrNull()?.content) }
    }

    // --- устойчивость к битым данным ------------------------------------------------------------

    @Test
    fun `corrupt json reads as absent and never throws`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["todos_global"] = "{not json at all"
        store.map["todos:s1"] = "[1,2,3]"
        store.map["todos:s2"] = "мусор"

        assertNull(cache.loadTodos(null))
        assertNull(cache.loadTodos("s1"))
        assertNull(cache.loadTodos("s2"))
        assertNull(cache.getSnapshotTimestamp(null))
        assertNull(cache.getSnapshotTimestamp("s1"))
    }

    @Test
    fun `foreign cache version reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["todos_global"] = """{"v":99,"saved_at":1,"todos":[]}"""
        store.map["todos:s1"] = """{"v":99,"saved_at":1,"todos":[]}"""

        assertNull(cache.loadTodos(null))
        assertNull(cache.loadTodos("s1"))
        assertNull(cache.getSnapshotTimestamp("s1"))
    }

    @Test
    fun `snapshot without todos row reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["todos_global"] = """{"v":1,"saved_at":5}"""

        assertNull(cache.loadTodos(null))
        // Timestamp при этом доступен: конверт цел.
        assertEquals(5L, cache.getSnapshotTimestamp(null))
    }

    @Test
    fun `malformed todo rows are dropped but valid ones survive`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["todos_global"] = """
            {"v":1,"saved_at":5,"todos":[
                42,
                "строка",
                {"content":"валидная","status":"completed"},
                {"id":null,"content":null}
            ],"summary":{"total":2}}
        """.trimIndent()

        val loaded = cache.loadTodos(null)
        assertEquals(2, loaded?.todos?.size)
        assertEquals(TodoItem(id = null, content = "валидная", status = "completed"), loaded?.todos?.get(0))
        // content null → "", status отсутствует → "pending" (дефолт модели).
        assertEquals(TodoItem(id = null, content = "", status = "pending"), loaded?.todos?.get(1))
        assertEquals(2, loaded?.summary?.total)
        // Отсутствующая секция summary даёт нули.
        assertEquals(0, loaded?.summary?.pending)
    }

    @Test
    fun `failing store gives false on save and null on reads without throwing`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertFalse(cache.saveTodos(null, snapshot()))
        assertFalse(cache.saveTodos("s1", snapshot()))
        assertNull(cache.loadTodos(null))
        assertNull(cache.loadTodos("s1"))
        assertNull(cache.getSnapshotTimestamp("s1"))
        cache.clear() // не бросает
    }

    // --- гигиена --------------------------------------------------------------------------------

    @Test
    fun `clear wipes all snapshots lru index and timestamps`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveTodos(null, snapshot())
        (1..3).forEach { cache.saveTodos("s$it", snapshot()) }

        cache.clear()

        assertTrue(store.map.isEmpty())
        assertNull(cache.loadTodos(null))
        assertNull(cache.loadTodos("s1"))
        assertNull(cache.getSnapshotTimestamp(null))
    }
}
