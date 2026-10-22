package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushSilentPolicy] — разбор тихого push-сообщения (синхронизация без уведомления).
 *
 * Контракт: сервер шлёт data-only `{"silent":"1","kind":"sync","action":...,
 * "session_id":...}`; клиент обязан (1) не показывать такое сообщение,
 * (2) понять, что именно обновить, (3) не терять команду с неизвестным action.
 */
class PushSilentPolicyTest {

    @Test
    fun `silent flag marks the message silent`() {
        for (flag in listOf("1", "true", "YES", " on ")) {
            assertTrue("flag=$flag", PushSilentPolicy.isSilent(mapOf("silent" to flag)))
        }
    }

    @Test
    fun `kind sync is silent even without the flag`() {
        assertTrue(PushSilentPolicy.isSilent(mapOf("kind" to "SYNC")))
    }

    @Test
    fun `a plain notification is not silent`() {
        assertFalse(PushSilentPolicy.isSilent(mapOf("kind" to "approval", "title" to "t")))
        assertFalse(PushSilentPolicy.isSilent(emptyMap()))
        assertFalse(PushSilentPolicy.isSilent(mapOf("silent" to "0")))
    }

    @Test
    fun `explicit action is honoured and normalized`() {
        val command = PushSilentPolicy.parse(
            mapOf("silent" to "1", "action" to " REFRESH_SESSIONS ", "session_id" to "s-1"),
        )
        requireNotNull(command)
        assertEquals(PushSilentPolicy.ACTION_REFRESH_SESSIONS, command.action)
        assertTrue(command.refreshesSessions)
        assertFalse(command.refreshesPending)
        assertEquals("s-1", command.sessionId)
    }

    @Test
    fun `token resync action is recognised`() {
        val command = PushSilentPolicy.parse(mapOf("silent" to "true", "action" to "token_resync"))
        requireNotNull(command)
        assertTrue(command.refreshesToken)
    }

    @Test
    fun `unknown action falls back to pending refresh when a session is present`() {
        val command = PushSilentPolicy.parse(mapOf("silent" to "1", "action" to "nonsense", "session_id" to "s-9"))
        requireNotNull(command)
        assertEquals(PushSilentPolicy.ACTION_REFRESH_PENDING, command.action)
        assertTrue(command.refreshesPending)
    }

    @Test
    fun `unknown action without a session falls back to sessions refresh`() {
        val command = PushSilentPolicy.parse(mapOf("silent" to "1"))
        requireNotNull(command)
        assertEquals(PushSilentPolicy.ACTION_REFRESH_SESSIONS, command.action)
        assertNull(command.sessionId)
    }

    @Test
    fun `blank session id is not a session`() {
        val command = PushSilentPolicy.parse(mapOf("silent" to "1", "session_id" to "   "))
        requireNotNull(command)
        assertNull(command.sessionId)
        assertEquals(PushSilentPolicy.ACTION_REFRESH_SESSIONS, command.action)
    }

    @Test
    fun `a visible message yields no sync command`() {
        assertNull(PushSilentPolicy.parse(mapOf("kind" to "approval", "title" to "t")))
        assertNull(PushSilentPolicy.parse(emptyMap()))
    }

    @Test
    fun `silent messages never render a notification even with a title`() {
        assertNull(
            "тихий пуш не должен превращаться в уведомление",
            PushMessagePolicy.parse(
                notificationTitle = null,
                notificationBody = null,
                data = mapOf("silent" to "1", "title" to "Синхронизация", "body" to "…"),
            ),
        )
    }
}
