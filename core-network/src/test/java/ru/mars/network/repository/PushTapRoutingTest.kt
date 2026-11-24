package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.PushChannelSpec
import ru.mars.network.model.PushNotificationSpec

/**
 * [PushTapRouting] — куда ведёт тап по push-уведомлению.
 *
 * Разрыв, который закрывает тест: approval/clarify обязан открывать ИМЕННО свой
 * диалог (`session_id` из data), иначе подтверждение приходит «в пустоту».
 */
class PushTapRoutingTest {

    private fun spec(sessionId: String?, kind: String?) = PushNotificationSpec(
        title = "Марс",
        body = "Требуется подтверждение",
        url = null,
        tag = null,
        channel = PushChannelSpec("mars_push_chat", "чат", true),
        notificationId = 1,
        sessionId = sessionId,
        kind = kind,
    )

    @Test
    fun `approval tap opens its own dialog`() {
        val target = PushTapRouting.resolve(spec("s-1", "approval"))
        assertEquals("s-1", target.sessionId)
        assertTrue(target.hasSession)
        assertTrue(target.isDecision)
    }

    @Test
    fun `clarify is a decision too`() {
        assertTrue(PushTapRouting.resolve("s-1", "clarify").isDecision)
    }

    @Test
    fun `plain chat push routes the dialog without decision semantics`() {
        val target = PushTapRouting.resolve("s-2", "chat")
        assertEquals("s-2", target.sessionId)
        assertFalse(target.isDecision)
    }

    @Test
    fun `kind is normalized`() {
        assertEquals("approval", PushTapRouting.resolve("s", " APPROVAL ").kind)
    }

    @Test
    fun `blank values never become routing data`() {
        val target = PushTapRouting.resolve("   ", "  ")
        assertNull(target.sessionId)
        assertNull(target.kind)
        assertEquals(PushTapTarget.NONE, target)
    }

    @Test
    fun `session is recovered from the deep link when data has none`() {
        assertEquals("s-77", PushTapRouting.sessionIdFromUrl("mars://chat/session/s-77?kind=approval"))
        assertEquals("s-78", PushTapRouting.sessionIdFromUrl("https://mars.local/chat/session/s-78"))
    }

    @Test
    fun `non-session links yield no session`() {
        assertNull(PushTapRouting.sessionIdFromUrl("mars://settings"))
        assertNull(PushTapRouting.sessionIdFromUrl(null))
        assertNull(PushTapRouting.sessionIdFromUrl("  "))
        assertNull(PushTapRouting.sessionIdFromUrl("mars://chat/session/   "))
    }

    // --- tag-адрес сервера (`hermes-<sid>`) ---------------------------------------------

    @Test
    fun `session is recovered from the server tag`() {
        // Сервер метит push диалога тегом `hermes-<sid>` (api/push_fcm.py): тег
        // приходит даже тогда, когда в data нет ни session_id, ни url.
        assertEquals("s-91", PushTapRouting.sessionIdFromTag("hermes-s-91"))
        assertEquals("s-91", PushTapRouting.sessionIdFromTag("  hermes-s-91  "))
    }

    @Test
    fun `test push tag is not an address`() {
        // Тап по «проверке связи» не должен открывать несуществующий диалог.
        assertNull(PushTapRouting.sessionIdFromTag("hermes-push-test"))
    }

    @Test
    fun `tag without a usable tail yields no session`() {
        assertNull(PushTapRouting.sessionIdFromTag("hermes-"))
        assertNull(PushTapRouting.sessionIdFromTag("hermes-   "))
        // Пробел и `/` в хвосте — склейка, а не id диалога.
        assertNull(PushTapRouting.sessionIdFromTag("hermes-s 91"))
        assertNull(PushTapRouting.sessionIdFromTag("hermes-a/b"))
        // Чужой тег (например, тег хода/уведомления) адресатом не является.
        assertNull(PushTapRouting.sessionIdFromTag("run-7"))
        assertNull(PushTapRouting.sessionIdFromTag(null))
        assertNull(PushTapRouting.sessionIdFromTag("   "))
    }

    // --- универсальный разбор интента ---------------------------------------------------

    @Test
    fun `extract prefers the explicit extra over every raw key`() {
        assertEquals(
            "explicit",
            PushTapRouting.extractSessionId(
                explicitExtra = "explicit",
                sessionId = "sid",
                url = "session/from-url",
                tag = "hermes-from-tag",
            ),
        )
    }

    @Test
    fun `extract falls back through session id, url and tag in order`() {
        assertEquals(
            "sid",
            PushTapRouting.extractSessionId(
                sessionId = "  sid  ",
                url = "session/from-url",
                tag = "hermes-from-tag",
            ),
        )
        assertEquals(
            "from-url",
            PushTapRouting.extractSessionId(url = "session/from-url", tag = "hermes-from-tag"),
        )
        assertEquals("from-tag", PushTapRouting.extractSessionId(tag = "hermes-from-tag"))
    }

    @Test
    fun `extract treats blank sources as absent`() {
        // Пустой explicit extra не «перекрывает» рабочий url: иначе чат
        // открывался бы по пустому ключу вместо адреса из payload.
        assertEquals(
            "from-url",
            PushTapRouting.extractSessionId(
                explicitExtra = "   ",
                sessionId = "",
                url = "mars://chat/session/from-url",
                tag = "hermes-from-tag",
            ),
        )
        assertNull(PushTapRouting.extractSessionId())
        assertNull(
            PushTapRouting.extractSessionId(
                explicitExtra = "  ",
                sessionId = "",
                url = null,
                tag = "hermes-push-test",
            ),
        )
    }
}
