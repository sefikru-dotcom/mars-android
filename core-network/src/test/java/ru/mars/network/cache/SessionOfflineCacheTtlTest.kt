package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * JVM-тесты TTL и сериализации офлайн-кэша (слой 3: «кэш переживает сбой сети, но не
 * притворяется живыми данными»).
 *
 * Покрыто:
 * - round-trip сериализация/десериализация снимка списка и транскрипта, включая тексты
 *   с кавычками, переводами строк, эмодзи и не-ASCII;
 * - TTL: свежий снимок отдаётся, просроченный — нет (и не участвует в поиске);
 * - `maxAgeMillis = 0` — прежнее поведение без срока годности;
 * - `loadMessagesWithMeta` отдаёт момент сохранения для индикатора «Кэш (N мин назад)»;
 * - битые данные не ломают чтение.
 */
class SessionOfflineCacheTtlTest {

    /** In-memory [KeyValueStore]: тесты не трогают ни диск, ни SharedPreferences. */
    private class FakeStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
        override fun clearAll() = map.clear()
    }

    private var now = 1_700_000_000_000L
    private val store = FakeStore()
    private fun cache(maxAgeMillis: Long = OfflineCacheTtl.DEFAULT_MILLIS) =
        SessionOfflineCache(store, clock = { now }, maxAgeMillis = maxAgeMillis)

    private fun session(id: String) = SessionItem(
        id = id,
        title = "Диалог $id",
        preview = "последний ответ $id",
        createdAt = "2026-10-01T10:00:00",
        updatedAt = "2026-10-02T11:00:00",
        messageCount = 3,
    )

    private fun message(id: String, role: String = "user", content: String = "текст $id") =
        SessionMessage(id = id, role = role, content = content, createdAt = "2026-10-02T11:00:00")

    // --- Сериализация / десериализация ----------------------------------------------------------

    @Test
    fun `sessions snapshot survives round trip with tricky text`() {
        val tricky = "кавычки \" и ' , перевод\nстроки, эмодзи 🙂, таб\tи обратный слэш \\"
        val items = listOf(
            SessionItem(id = "s1", title = tricky, preview = tricky, messageCount = 2),
            session("s2"),
        )
        val cache = cache()

        assertTrue(cache.saveSessions(items))
        val loaded = cache.loadSessions()

        assertEquals(2, loaded?.sessions?.size)
        assertEquals(tricky, loaded?.sessions?.first()?.title)
        assertEquals(tricky, loaded?.sessions?.first()?.preview)
        assertEquals(now, loaded?.savedAtEpochMillis)
    }

    @Test
    fun `messages snapshot survives round trip and keeps order`() {
        val cache = cache()
        val messages = listOf(
            message("m1", "user", "первый\nвопрос"),
            message("m2", "assistant", "ответ с \"кавычками\" и 🙂"),
            message("m3", "tool", "{\"json\": true}"),
        )

        assertTrue(cache.saveMessages("s1", messages))
        val loaded = cache.loadMessages("s1")

        assertEquals(listOf("m1", "m2", "m3"), loaded?.map { it.id })
        assertEquals(messages.map { it.content }, loaded?.map { it.content })
        assertEquals(listOf("user", "assistant", "tool"), loaded?.map { it.role })
    }

    @Test
    fun `loadMessagesWithMeta returns snapshot moment for offline badge`() {
        val cache = cache()
        cache.saveMessages("s1", listOf(message("m1")))

        now += 5 * 60_000L // прошло 5 минут
        val snapshot = cache.loadMessagesWithMeta("s1")

        assertEquals(1_700_000_000_000L, snapshot?.savedAtEpochMillis)
        assertEquals(1, snapshot?.messages?.size)
        // loadMessages — тот же контракт, но без метаданных.
        assertEquals(snapshot?.messages, cache.loadMessages("s1"))
    }

    @Test
    fun `corrupted payload is read as no cache`() {
        val cache = cache()
        cache.saveSessions(listOf(session("s1")))
        cache.saveMessages("s1", listOf(message("m1")))
        // Портим РОВНО те записи, что записал кэш: имена ключей — его приватная деталь.
        store.map.keys.forEach { key -> store.map[key] = "{не json" }

        assertNull(cache.loadSessions())
        assertNull(cache.loadMessages("s1"))
    }

    // --- TTL ------------------------------------------------------------------------------------

    @Test
    fun `fresh snapshot within ttl is served`() {
        val cache = cache(maxAgeMillis = 60 * 60_000L) // 1 час
        cache.saveMessages("s1", listOf(message("m1")))
        cache.saveSessions(listOf(session("s1")))

        now += 59 * 60_000L

        assertEquals(1, cache.loadMessages("s1")?.size)
        assertEquals(1, cache.loadSessions()?.sessions?.size)
    }

    @Test
    fun `expired snapshot is not served`() {
        val cache = cache(maxAgeMillis = 60 * 60_000L)
        cache.saveMessages("s1", listOf(message("m1")))
        cache.saveSessions(listOf(session("s1")))

        now += 61 * 60_000L

        assertNull("просроченный транскрипт не показывается", cache.loadMessages("s1"))
        assertNull("просроченный список не показывается", cache.loadSessions())
        assertNull(cache.loadMessagesWithMeta("s1"))
    }

    @Test
    fun `expired snapshot is not searchable`() {
        val cache = cache(maxAgeMillis = 60 * 60_000L)
        cache.saveMessages("s1", listOf(message("m1", content = "уникальное слово quantum")))
        now += 2 * 60 * 60_000L

        assertTrue(cache.search("quantum").isEmpty())
    }

    @Test
    fun `zero max age disables expiry`() {
        val cache = cache(maxAgeMillis = 0L)
        cache.saveMessages("s1", listOf(message("m1")))

        now += 365L * 24 * 60 * 60_000L

        assertEquals(1, cache.loadMessages("s1")?.size)
    }

    @Test
    fun `default ttl is seventy two hours`() {
        assertEquals(72, OfflineCacheTtl.DEFAULT_HOURS)
        assertEquals(72L * 60 * 60 * 1000, OfflineCacheTtl.DEFAULT_MILLIS)
    }

    @Test
    fun `freshness treats unknown timestamp as usable and clock skew as fresh`() {
        // Снимок без saved_at (0) — старые записи: не выкидываем из-за отсутствия метки.
        assertTrue(OfflineCacheTtl.isFresh(0L, now, OfflineCacheTtl.DEFAULT_MILLIS))
        // Часы сдвинулись назад (NTP): отрицательный возраст — не повод скрывать копию.
        assertTrue(OfflineCacheTtl.isFresh(now + 10 * 60_000L, now, OfflineCacheTtl.DEFAULT_MILLIS))
        // Ровно на границе TTL снимок ещё годен, на миллисекунду позже — уже нет.
        assertTrue(OfflineCacheTtl.isFresh(now - 3_600_000L, now, 3_600_000L))
        assertFalse(OfflineCacheTtl.isFresh(now - 3_600_001L, now, 3_600_000L))
    }
}
