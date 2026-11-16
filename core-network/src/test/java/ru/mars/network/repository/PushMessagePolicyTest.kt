package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushMessagePolicy] — чистый разбор входящего FCM-сообщения в модель уведомления.
 *
 * Контракт: сервер может прислать notification-payload (title/body), data-payload
 * (title/body/url/tag/kind/session_id/notification_id) или и то и другое. Data имеет
 * приоритет; пустое сообщение не показывается вовсе; канал и приоритет выбираются
 * по `kind`; notification_id детерминирован (повторная отправка того же события
 * заменяет уведомление, а не плодит дубли).
 */
class PushMessagePolicyTest {

    @Test
    fun `data payload wins over notification payload`() {
        val spec = PushMessagePolicy.parse(
            notificationTitle = "N-title",
            notificationBody = "N-body",
            data = mapOf("title" to "D-title", "body" to "D-body"),
        )
        requireNotNull(spec)
        assertEquals("D-title", spec.title)
        assertEquals("D-body", spec.body)
    }

    @Test
    fun `notification payload is used when data has no title or body`() {
        val spec = PushMessagePolicy.parse("N-title", "N-body", emptyMap())
        requireNotNull(spec)
        assertEquals("N-title", spec.title)
        assertEquals("N-body", spec.body)
    }

    @Test
    fun `completely empty message yields null - nothing to show`() {
        assertNull(PushMessagePolicy.parse(null, null, emptyMap()))
        assertNull(PushMessagePolicy.parse("  ", "", mapOf("title" to " ", "body" to "")))
    }

    @Test
    fun `body-only message gets the default title`() {
        val spec = PushMessagePolicy.parse(null, "Ход завершён", emptyMap())
        requireNotNull(spec)
        assertEquals(PushMessagePolicy.DEFAULT_TITLE, spec.title)
        assertEquals("Ход завершён", spec.body)
    }

    @Test
    fun `chat-like kinds go to the high-priority chat channel`() {
        for (kind in listOf("chat", "approval", "clarify", "turn", "cron")) {
            val spec = PushMessagePolicy.parse("t", "b", mapOf("kind" to kind))
            requireNotNull(spec) { "kind=$kind" }
            assertEquals(PushMessagePolicy.CHANNEL_CHAT, spec.channel)
            assertTrue("kind=$kind must be high priority", spec.channel.highPriority)
        }
    }

    @Test
    fun `unknown or missing kind goes to the general default-priority channel`() {
        for (data in listOf(emptyMap(), mapOf("kind" to "promo"), mapOf("kind" to ""))) {
            val spec = PushMessagePolicy.parse("t", "b", data)
            requireNotNull(spec)
            assertEquals(PushMessagePolicy.CHANNEL_GENERAL, spec.channel)
            assertFalse(spec.channel.highPriority)
        }
    }

    @Test
    fun `kind matching is case-insensitive and trimmed`() {
        val spec = PushMessagePolicy.parse("t", "b", mapOf("kind" to " APPROVAL "))
        requireNotNull(spec)
        assertEquals(PushMessagePolicy.CHANNEL_CHAT, spec.channel)
    }

    @Test
    fun `url and tag are parsed, blank values become null`() {
        val spec = PushMessagePolicy.parse(
            null, null,
            mapOf("title" to "t", "body" to "b", "url" to " mars://chat/session/42 ", "tag" to "run-7"),
        )
        requireNotNull(spec)
        assertEquals("mars://chat/session/42", spec.url)
        assertEquals("run-7", spec.tag)

        val blank = PushMessagePolicy.parse("t", "b", mapOf("url" to "  ", "tag" to ""))
        requireNotNull(blank)
        assertNull(blank.url)
        assertNull(blank.tag)
    }

    @Test
    fun `explicit notification_id is used as-is`() {
        val spec = PushMessagePolicy.parse("t", "b", mapOf("notification_id" to "1234"))
        requireNotNull(spec)
        assertEquals(1234, spec.notificationId)
    }

    @Test
    fun `fallback notification id is deterministic and tag-scoped`() {
        val a = PushMessagePolicy.parse("t", "b", mapOf("tag" to "run-7"))
        val b = PushMessagePolicy.parse("t", "b", mapOf("tag" to "run-7"))
        val c = PushMessagePolicy.parse("t", "b", mapOf("tag" to "run-8"))
        requireNotNull(a); requireNotNull(b); requireNotNull(c)
        assertEquals(a.notificationId, b.notificationId)
        assertNotEquals(a.notificationId, c.notificationId)
    }

    @Test
    fun `non-numeric notification_id falls back to the derived id`() {
        val withJunk = PushMessagePolicy.parse("t", "b", mapOf("notification_id" to "abc", "tag" to "run-7"))
        val without = PushMessagePolicy.parse("t", "b", mapOf("tag" to "run-7"))
        requireNotNull(withJunk); requireNotNull(without)
        assertEquals(without.notificationId, withJunk.notificationId)
    }

    // --- адресат события: url/tag, а не только session_id --------------------------------

    @Test
    fun `server push with url and tag gets a session id`() {
        // Ровно тот payload, что шлёт сервер по завершении хода
        // (api/push_fcm.py, api/streaming.py):
        // {"url": "session/<sid>", "tag": "hermes-<sid>"} — без ключа session_id.
        val spec = PushMessagePolicy.parse(
            notificationTitle = "Марс: ответ готов",
            notificationBody = "Ход завершён",
            data = mapOf("ts" to "1763000000", "url" to "session/s-55", "tag" to "hermes-s-55"),
        )
        requireNotNull(spec)
        assertEquals("s-55", spec.sessionId)
        assertEquals("session/s-55", spec.url)
        assertEquals("hermes-s-55", spec.tag)
    }

    @Test
    fun `session is recovered from the tag alone`() {
        val spec = PushMessagePolicy.parse("t", "b", mapOf("tag" to "hermes-s-56"))
        requireNotNull(spec)
        assertEquals("s-56", spec.sessionId)
    }

    @Test
    fun `explicit session_id wins over url and tag`() {
        val spec = PushMessagePolicy.parse(
            "t", "b",
            mapOf(
                "session_id" to "from-key",
                "url" to "session/from-url",
                "tag" to "hermes-from-tag",
            ),
        )
        requireNotNull(spec)
        assertEquals("from-key", spec.sessionId)
    }

    @Test
    fun `test push tag alone yields no session id`() {
        // Тап по тестовому пушу не должен вести в несуществующий диалог.
        val spec = PushMessagePolicy.parse("t", "b", mapOf("tag" to "hermes-push-test"))
        requireNotNull(spec)
        assertNull(spec.sessionId)
    }

    @Test
    fun `push without any address yields no session id`() {
        val spec = PushMessagePolicy.parse("t", "b", mapOf("kind" to "chat"))
        requireNotNull(spec)
        assertNull(spec.sessionId)
    }
}
