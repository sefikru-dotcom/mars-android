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
}
