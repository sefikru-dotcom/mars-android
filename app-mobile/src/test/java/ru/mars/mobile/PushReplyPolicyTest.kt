package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ответ из push-уведомления: когда кнопку вообще можно показать.
 *
 * Кнопка «Ответить» в шторке — это отправка сообщения без открытого экрана, поэтому
 * ошибка здесь дороже обычной: человек думает, что ответил, а сообщение ушло в никуда
 * или в чужой диалог. Отсюда жёсткие проверки на пустой адресат и на сессии, куда
 * писать нельзя.
 */
class PushReplyPolicyTest {

    @Test
    fun `без адресата отвечать некуда`() {
        assertFalse(PushReplyPolicy.canReply(null, readOnly = false, subagent = false))
        assertFalse(PushReplyPolicy.canReply("", readOnly = false, subagent = false))
        assertFalse(PushReplyPolicy.canReply("   ", readOnly = false, subagent = false))
    }

    @Test
    fun `обычная сессия принимает ответ`() {
        assertTrue(PushReplyPolicy.canReply("s1", readOnly = false, subagent = false))
        // Пробелы по краям — не повод отказать: id приходит из data-payload как есть.
        assertTrue(PushReplyPolicy.canReply(" s1 ", readOnly = false, subagent = false))
    }

    @Test
    fun `в сессии только для чтения и у субагента ответа нет`() {
        assertFalse(PushReplyPolicy.canReply("s1", readOnly = true, subagent = false))
        assertFalse(PushReplyPolicy.canReply("s1", readOnly = false, subagent = true))
        assertFalse(PushReplyPolicy.canReply("s1", readOnly = true, subagent = true))
    }

    @Test
    fun `пустой текст не отправляется`() {
        assertNull(PushReplyPolicy.normalizeReply(null))
        assertNull(PushReplyPolicy.normalizeReply(""))
        assertNull(PushReplyPolicy.normalizeReply("   "))
        assertNull(PushReplyPolicy.normalizeReply("\n\n"))
    }

    @Test
    fun `текст нормализуется`() {
        assertEquals("а б", PushReplyPolicy.normalizeReply("  а\n\n б  "))
        assertEquals("а б", PushReplyPolicy.normalizeReply("а\t\tб"))
        assertEquals("уже сжато", PushReplyPolicy.normalizeReply("уже  сжато"))
    }

    @Test
    fun `текст отказа читаем человеку`() {
        assertEquals(PushReplyPolicy.FAILURE_DEFAULT, PushReplyPolicy.failureNotice(null))
        assertEquals(PushReplyPolicy.FAILURE_DEFAULT, PushReplyPolicy.failureNotice("  "))
        assertEquals(
            "Не удалось отправить ответ: HTTP 502",
            PushReplyPolicy.failureNotice("HTTP 502"),
        )
    }

    @Test
    fun `действие распознаётся по строке`() {
        assertTrue(PushReplyPolicy.matchesAction(PushReplyPolicy.ACTION_REPLY))
        assertFalse(PushReplyPolicy.matchesAction(null))
        assertFalse(PushReplyPolicy.matchesAction(""))
        assertFalse(PushReplyPolicy.matchesAction("ru.mars.mobile.ЧТО_ТО_ДРУГОЕ"))
    }

    @Test
    fun `ключи совпадают с теми, что читает экран`() {
        // Ключи — часть протокола между уведомлением и активностью: смена любой из
        // этих строк молча ломает ответ (интент придёт, текста в нём не будет).
        assertEquals("ru.mars.mobile.PUSH_REPLY", PushReplyPolicy.ACTION_REPLY)
        assertEquals("push_reply_input", PushReplyPolicy.KEY_TEXT)
        assertEquals("push_session_id", PushReplyPolicy.EXTRA_SESSION_ID)
        assertEquals("push_reply_text", PushReplyPolicy.EXTRA_TEXT)
        assertEquals("push_notification_id", PushReplyPolicy.EXTRA_NOTIFICATION_ID)
    }
}
