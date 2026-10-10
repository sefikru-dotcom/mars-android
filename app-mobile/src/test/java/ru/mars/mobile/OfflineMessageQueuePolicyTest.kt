package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты [OfflineMessageQueuePolicy] — буферизации неотправленных сообщений
 * при обрыве связи: решение отправки, FIFO-порядок, привязка к диалогу, лимиты
 * (размер очереди / попытки), персистентность через порт [PendingMessageStore].
 */
class OfflineMessageQueuePolicyTest {

    /** In-memory порт: записывает снимки и позволяет проверить переживание пересоздания. */
    private class FakeStore : PendingMessageStore {
        var saved: List<QueuedMessage> = emptyList()
        var saveCount = 0
        var failOnSave = false
        override fun load(): List<QueuedMessage> = saved
        override fun save(messages: List<QueuedMessage>) {
            if (failOnSave) throw IllegalStateException("store down")
            saved = messages.toList()
            saveCount++
        }
    }

    private var now = 1_700_000_000_000L
    private val store = FakeStore()

    private fun queue(maxSize: Int = 32, maxAttempts: Int = 3) =
        OfflineMessageQueuePolicy(store, maxSize, maxAttempts, clock = { now })

    // --- onSendRequested --------------------------------------------------------------------------

    @Test
    fun `blank text is rejected regardless of network`() {
        val q = queue()
        assertEquals(SendDecision.Rejected, q.onSendRequested(canSend = true, text = "   ", sessionId = null))
        assertEquals(SendDecision.Rejected, q.onSendRequested(canSend = false, text = "", sessionId = "s1"))
        assertEquals(0, q.size)
    }

    @Test
    fun `send allowed goes through without touching queue`() {
        val q = queue()
        val decision = q.onSendRequested(canSend = true, text = "привет", sessionId = "s1")
        assertEquals(SendDecision.SendNow, decision)
        assertEquals(0, q.size)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `send blocked buffers message with session and timestamp`() {
        val q = queue()
        val decision = q.onSendRequested(canSend = false, text = "привет", sessionId = "s1")
        assertTrue(decision is SendDecision.Buffer)
        val message = (decision as SendDecision.Buffer).message
        assertEquals("привет", message.text)
        assertEquals("s1", message.sessionId)
        assertEquals(now, message.enqueuedAtEpochMillis)
        assertEquals(0, message.attempts)
        assertEquals(1, q.size)
        // Буферизация персистентна сразу.
        assertEquals(1, store.saveCount)
    }

    // --- FIFO и привязка к диалогу -----------------------------------------------------------------

    @Test
    fun `nextFor returns fifo order within session and skips other sessions`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = "s1")
        q.onSendRequested(canSend = false, text = "b", sessionId = "s2")
        q.onSendRequested(canSend = false, text = "c", sessionId = "s1")

        assertEquals("a", q.nextFor("s1")?.text)
        assertEquals("c", q.nextFor("s1")?.text)
        assertNull(q.nextFor("s1"))
        // Чужой диалог не пострадал.
        assertEquals("b", q.nextFor("s2")?.text)
        assertEquals(0, q.size)
    }

    @Test
    fun `new chat messages are queued with null session`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "новый чат", sessionId = null)
        assertEquals(listOf("новый чат"), q.pendingFor(null).map { it.text })
        assertTrue(q.pendingFor("s1").isEmpty())
        assertEquals("новый чат", q.nextFor(null)?.text)
    }

    @Test
    fun `pendingFor preserves fifo order`() {
        val q = queue()
        listOf("1", "2", "3").forEach { q.onSendRequested(canSend = false, text = it, sessionId = "s1") }
        assertEquals(listOf("1", "2", "3"), q.pendingFor("s1").map { it.text })
        assertEquals(listOf("1", "2", "3"), q.snapshot().map { it.text })
    }

    @Test
    fun `nextFor extraction is persisted`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = null)
        val savesAfterEnqueue = store.saveCount
        q.nextFor(null)
        assertEquals(savesAfterEnqueue + 1, store.saveCount)
        assertTrue(store.saved.isEmpty())
    }

    // --- лимиты ------------------------------------------------------------------------------------

    @Test
    fun `overflow evicts oldest and keeps newest`() {
        val q = queue(maxSize = 2)
        q.onSendRequested(canSend = false, text = "old", sessionId = null)
        q.onSendRequested(canSend = false, text = "mid", sessionId = null)
        q.onSendRequested(canSend = false, text = "new", sessionId = null)
        assertEquals(2, q.size)
        assertEquals(listOf("mid", "new"), q.snapshot().map { it.text })
    }

    @Test
    fun `requeue puts message back to head with attempt increment`() {
        val q = queue(maxAttempts = 3)
        q.onSendRequested(canSend = false, text = "a", sessionId = null)
        q.onSendRequested(canSend = false, text = "b", sessionId = null)
        val failed = q.nextFor(null)!!
        assertTrue(q.requeue(failed))
        val head = q.snapshot().first()
        assertEquals("a", head.text)
        assertEquals(1, head.attempts)
        assertEquals(2, q.size)
    }

    @Test
    fun `requeue drops message after attempt limit`() {
        val q = queue(maxAttempts = 3)
        q.enqueue("a", sessionId = null)
        // Реалистичный цикл: извлечение → неудачная отправка → requeue.
        // attempts: 0 → 1 → 2, третья неудача (2+1 >= 3) роняет сообщение.
        var message = q.nextFor(null)!!
        assertTrue(q.requeue(message))
        message = q.nextFor(null)!!
        assertEquals(1, message.attempts)
        assertTrue(q.requeue(message))
        message = q.nextFor(null)!!
        assertEquals(2, message.attempts)
        assertFalse(q.requeue(message))
        assertEquals(0, q.size)
    }

    // --- удаление и очистка -------------------------------------------------------------------------

    @Test
    fun `adoptSession rekeys new-chat messages to created session`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = null)
        q.onSendRequested(canSend = false, text = "b", sessionId = null)
        q.onSendRequested(canSend = false, text = "x", sessionId = "other")

        // Первое сообщение ушло и создало сессию s-new: оставшееся перепривязывается.
        q.nextFor(null)
        assertEquals(1, q.adoptSession(null, "s-new"))

        assertEquals(listOf("b"), q.pendingFor("s-new").map { it.text })
        assertTrue(q.pendingFor(null).isEmpty())
        // Чужой диалог не затронут.
        assertEquals(listOf("x"), q.pendingFor("other").map { it.text })
        // FIFO-порядок сохранён.
        assertEquals("b", q.nextFor("s-new")?.text)
    }

    @Test
    fun `adoptSession is persisted and survives recreation`() {
        val first = queue()
        first.onSendRequested(canSend = false, text = "a", sessionId = null)
        first.adoptSession(null, "s1")
        val second = queue()
        assertEquals(listOf("a"), second.pendingFor("s1").map { it.text })
    }

    @Test
    fun `adoptSession no-ops on empty target, same session and empty queue`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = null)
        val saves = store.saveCount
        assertEquals(0, q.adoptSession(null, ""))
        assertEquals(0, q.adoptSession("s1", "s1"))
        assertEquals(0, q.adoptSession("s2", "s3")) // нет сообщений s2
        assertEquals(saves, store.saveCount)          // хранилище не трогали
    }

    @Test
    fun `removeMessagesFor deletes only that session`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = "s1")
        q.onSendRequested(canSend = false, text = "b", sessionId = "s2")
        q.removeMessagesFor("s1")
        assertEquals(listOf("b"), q.snapshot().map { it.text })
        // Удаление пустого набора не пишет в хранилище.
        val saves = store.saveCount
        q.removeMessagesFor("s3")
        assertEquals(saves, store.saveCount)
    }

    @Test
    fun `clear empties queue and persists`() {
        val q = queue()
        q.onSendRequested(canSend = false, text = "a", sessionId = null)
        q.clear()
        assertEquals(0, q.size)
        assertTrue(store.saved.isEmpty())
    }

    // --- персистентность ------------------------------------------------------------------------------

    @Test
    fun `queue survives recreation via store`() {
        val first = queue()
        first.onSendRequested(canSend = false, text = "a", sessionId = "s1")
        first.onSendRequested(canSend = false, text = "b", sessionId = null)

        // Поворот экрана / смерть процесса: новый экземпляр читает тот же порт.
        val second = queue()
        assertEquals(2, second.size)
        assertEquals("a", second.nextFor("s1")?.text)
        assertEquals("b", second.nextFor(null)?.text)
    }

    @Test
    fun `restored queue keeps attempts counter`() {
        val first = queue(maxAttempts = 3)
        first.onSendRequested(canSend = false, text = "a", sessionId = null)
        first.requeue(first.nextFor(null)!!)

        val second = queue(maxAttempts = 3)
        val restored = second.snapshot().first()
        assertEquals(1, restored.attempts)
        // Осталась одна попытка: 1+1 < 3 — requeue проходит, затем лимит.
        second.nextFor(null)
        assertTrue(second.requeue(restored.copy(attempts = 1)))
        val twice = second.snapshot().first()
        assertFalse(second.requeue(twice))
    }

    @Test
    fun `failing store does not break in-memory queue`() {
        store.failOnSave = true
        val q = queue()
        val decision = q.onSendRequested(canSend = false, text = "a", sessionId = null)
        assertTrue(decision is SendDecision.Buffer)
        assertEquals(1, q.size)
        assertEquals("a", q.nextFor(null)?.text)
    }

    @Test
    fun `ids are unique for same-clock enqueues`() {
        val q = queue()
        val first = q.enqueue("a", sessionId = null)
        val second = q.enqueue("b", sessionId = null)
        assertFalse(first.id == second.id)
    }
}
