package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переходы [ChatOfflineQueuePolicy]: что буферизуется, когда очередь флашится и под каким
 * ключом возвращается недоставленное сообщение.
 */
class ChatOfflineQueuePolicyTest {

    private val policy = ChatOfflineQueuePolicy()

    private fun queued(text: String, sessionId: String? = null) = QueuedMessage(
        id = "id-$text",
        text = text,
        sessionId = sessionId,
        enqueuedAtEpochMillis = 0L,
    )

    // --- Вердикт отправки -------------------------------------------------------------------

    @Test
    fun `network available means the normal send path`() {
        assertEquals(
            ChatOfflineQueuePolicy.SendVerdict.SEND_NOW,
            policy.sendVerdict(networkSendAllowed = true, attachmentCount = 0)
        )
        assertEquals(
            ChatOfflineQueuePolicy.SendVerdict.SEND_NOW,
            policy.sendVerdict(networkSendAllowed = true, attachmentCount = 2)
        )
    }

    @Test
    fun `offline text is buffered`() {
        assertEquals(
            ChatOfflineQueuePolicy.SendVerdict.BUFFER,
            policy.sendVerdict(networkSendAllowed = false, attachmentCount = 0)
        )
    }

    @Test
    fun `offline attachments are refused instead of buffered`() {
        // multipart-загрузку офлайн не повторить: чипы остаются в композиторе.
        assertEquals(
            ChatOfflineQueuePolicy.SendVerdict.BLOCK_ATTACHMENT,
            policy.sendVerdict(networkSendAllowed = false, attachmentCount = 1)
        )
    }

    // --- Гейт автофлаша ---------------------------------------------------------------------

    @Test
    fun `flush requires restored session, network and idle turn`() {
        assertTrue(policy.canFlush(initialRestoreDone = true, networkSendAllowed = true, typing = false))
        assertFalse("до первичного восстановления флашить некуда", policy.canFlush(false, true, false))
        assertFalse("без сети флаш бессмысленен", policy.canFlush(true, false, false))
        assertFalse("во время хода очередь ждёт его finally", policy.canFlush(true, true, true))
    }

    // --- Ключ возврата и эхо ----------------------------------------------------------------

    @Test
    fun `undelivered message is rebound to the current session`() {
        // Сессия могла создаться уже после буферизации: строка под null не дождалась бы флаша.
        assertEquals("s-new", policy.rebindSession(currentSessionId = "s-new", queuedSessionId = null))
        assertEquals("s-new", policy.rebindSession(currentSessionId = "s-new", queuedSessionId = "s-old"))
    }

    @Test
    fun `without a current session the original key is kept`() {
        assertEquals("s-old", policy.rebindSession(currentSessionId = null, queuedSessionId = "s-old"))
        assertNull(policy.rebindSession(currentSessionId = null, queuedSessionId = null))
    }

    @Test
    fun `pending rows echo their texts in fifo order`() {
        val pending = listOf(queued("первое"), queued("второе"))
        assertEquals(listOf("первое", "второе"), policy.echoTexts(pending))
        assertTrue(policy.echoTexts(emptyList()).isEmpty())
    }
}
