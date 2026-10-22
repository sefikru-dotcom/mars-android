package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.offline.OfflineQueueLimits
import ru.mars.mobile.offline.QueuedMessageRow

/**
 * JVM-тесты слоя 2 (очередь неотправленных): лимиты, FIFO, переживание перезапуска,
 * очистка при logout и отображение строк БД.
 *
 * SQLite-адаптер ([ru.mars.mobile.offline.SqlitePendingMessageStore]) в JVM не поднять
 * (SQLiteOpenHelper — Android), поэтому проверяется всё, что от него не зависит и где
 * живут решения: чистая политика очереди поверх порта [PendingMessageStore], границы
 * лимита ([OfflineQueueLimits]) и маппинг строк ([QueuedMessageRow]). SQLite-часть —
 * тонкая: SELECT/INSERT по колонкам, покрыта контрактом порта.
 */
class OfflineQueueStorageTest {

    /** Персистентный in-memory store: имитирует переживание смерти процесса. */
    private class FakeStore : PendingMessageStore, ClearableLocalStore {
        var saved: List<QueuedMessage> = emptyList()
        var failing = false
        var saves = 0
        override fun load(): List<QueuedMessage> = if (failing) throw IllegalStateException("db down") else saved
        override fun save(messages: List<QueuedMessage>) {
            if (failing) throw IllegalStateException("db down")
            saves++
            saved = messages
        }
        override fun clearAll() {
            saved = emptyList()
        }
    }

    private var now = 1_700_000_000_000L
    private val store = FakeStore()
    private fun queue(maxSize: Int = OfflineMessageQueuePolicy.DEFAULT_MAX_QUEUE_SIZE) =
        OfflineMessageQueuePolicy(store, maxSize, clock = { now })

    // --- Лимиты ---------------------------------------------------------------------------------

    @Test
    fun `default limit is one hundred messages`() {
        assertEquals(100, OfflineMessageQueuePolicy.DEFAULT_MAX_QUEUE_SIZE)
    }

    @Test
    fun `overflow keeps newest hundred in fifo order`() {
        val q = queue()
        repeat(105) { index ->
            now += 1
            q.enqueue("сообщение $index", sessionId = "s1")
        }

        assertEquals(100, q.size)
        val snapshot = q.snapshot()
        assertEquals("сообщение 5", snapshot.first().text)   // первые пять вытеснены
        assertEquals("сообщение 104", snapshot.last().text)  // новое — в хвосте
        // Порядок FIFO: текст каждого следующего — ровно на единицу больше.
        snapshot.zipWithNext().forEach { (a, b) ->
            assertEquals(
                a.text.substringAfterLast(' ').toInt() + 1,
                b.text.substringAfterLast(' ').toInt(),
            )
        }
        assertEquals("очередь подрезана и в хранилище", 100, store.saved.size)
    }

    @Test
    fun `storage bound keeps last rows and order`() {
        val messages = (0 until 10).map { index ->
            QueuedMessage(id = "q$index", text = "t$index", sessionId = null, enqueuedAtEpochMillis = index.toLong())
        }

        val bounded = OfflineQueueLimits.bound(messages, 3)

        assertEquals(listOf("q7", "q8", "q9"), bounded.map { it.id })
        // Без ограничения и при недоборе — исходный список без потерь.
        assertEquals(messages, OfflineQueueLimits.bound(messages, 10))
        assertEquals(messages, OfflineQueueLimits.bound(messages, 0))
    }

    // --- FIFO и переживание перезапуска ---------------------------------------------------------

    @Test
    fun `queue survives recreation with order and attempts`() {
        val q = queue()
        q.enqueue("первое", "s1", voiceMode = true)
        q.enqueue("второе", "s1")
        q.enqueue("третье", "s2")
        // Первое отправлено неудачно — вернулось в голову с attempts+1.
        val first = q.nextFor("s1")!!
        assertTrue(q.requeue(first))

        // «Смерть процесса»: новая политика читает тот же store.
        val restored = queue()

        assertEquals(3, restored.size)
        assertEquals(listOf("первое", "второе", "третье"), restored.snapshot().map { it.text })
        assertEquals(1, restored.snapshot().first().attempts)
        assertTrue(restored.snapshot().first().voiceMode)
        assertEquals(listOf("первое", "второе"), restored.pendingFor("s1").map { it.text })
    }

    @Test
    fun `hundred messages survive recreation in exact order`() {
        val q = queue()
        repeat(100) { index ->
            now += 1
            q.enqueue("m$index", "s1")
        }

        val restored = queue()

        assertEquals(100, restored.size)
        assertEquals((0 until 100).map { "m$it" }, restored.snapshot().map { it.text })
    }

    // --- Logout ---------------------------------------------------------------------------------

    @Test
    fun `logout clears queue and store`() {
        val q = queue()
        q.enqueue("секрет", "s1")
        q.enqueue("ещё секрет", null)
        assertTrue(store.saved.isNotEmpty())

        // Контракт выхода: политика чистит память, хранилище — таблицу (одна транзакция в SQLite).
        q.clear()
        store.clearAll()

        assertEquals(0, q.size)
        assertTrue(store.saved.isEmpty())
        // Новая сессия после logout не должна увидеть ни одного сообщения предыдущей.
        assertEquals(0, queue().size)
    }

    @Test
    fun `failing store does not break in memory queue`() {
        store.failing = true
        val q = queue()

        val decision = q.onSendRequested(canSend = false, text = "без сети", sessionId = "s1")

        assertTrue(decision is SendDecision.Buffer)
        assertEquals(1, q.size)
        assertEquals(0, store.saves)
    }

    // --- Маппинг строк БД -----------------------------------------------------------------------

    @Test
    fun `row round trip keeps every field`() {
        val message = QueuedMessage(
            id = "q-1",
            text = "многострочный\nтекст \"с кавычками\" 🙂",
            sessionId = "s1",
            enqueuedAtEpochMillis = 1_700_000_000_123L,
            attempts = 2,
            voiceMode = true,
        )

        assertEquals(message, QueuedMessageRow.from(message).toMessage())
    }

    @Test
    fun `row with null session stays null and blank rows are dropped`() {
        val newChat = QueuedMessage(id = "q-2", text = "новый чат", sessionId = null, enqueuedAtEpochMillis = 1L)
        assertNull(QueuedMessageRow.from(newChat).toMessage()?.sessionId)

        // Пустой текст/id (обрезанная или чужая запись) не должен всплыть пустым эхом.
        assertNull(QueuedMessageRow("q-3", "   ", null, 0L, 0, false).toMessage())
        assertNull(QueuedMessageRow("", "текст", null, 0L, 0, false).toMessage())
        // Отрицательный счётчик попыток из битой строки нормализуется в ноль.
        assertEquals(0, QueuedMessageRow("q-4", "т", null, 0L, -3, false).toMessage()?.attempts)
    }

    @Test
    fun `blank text is never queued`() {
        val q = queue()
        assertEquals(SendDecision.Rejected, q.onSendRequested(canSend = false, text = "  ", sessionId = "s1"))
        assertEquals(0, q.size)
        assertFalse(store.saves > 0)
    }
}
