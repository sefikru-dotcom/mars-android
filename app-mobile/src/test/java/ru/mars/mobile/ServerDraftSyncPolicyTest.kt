package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A15: дебаунс серверной записи черновика и приоритет пользовательского ввода. */
class ServerDraftSyncPolicyTest {

    @Test
    fun `first keystroke is written immediately`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        assertEquals(0L, policy.delayFor("привет", now = 1_000))
    }

    @Test
    fun `typing inside the quiet window defers the write to the window end`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("привет", now = 1_000)
        // Через 500 мс после записи — ещё 1500 мс покоя.
        assertEquals(1_500L, policy.delayFor("привет, мир", now = 1_500))
    }

    @Test
    fun `typing after the quiet window is written at once`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("привет", now = 1_000)
        assertEquals(0L, policy.delayFor("привет!", now = 3_500))
    }

    @Test
    fun `unchanged text is not written again`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("привет", now = 1_000)
        assertNull(policy.delayFor("привет", now = 99_000))
    }

    @Test
    fun `failed write still closes the window so offline typing cannot flood the queue`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        // onSaved вызывается и при неуспехе: окно закрыто.
        policy.onSaved("привет", now = 1_000)
        assertEquals(1_500L, policy.delayFor("привет!", now = 1_500))
    }

    @Test
    fun `session change resets window and last saved text`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("черновик A", now = 1_000)
        policy.onSessionChanged()
        // Текст, записанный в прежнем диалоге, для нового — чужой: черновики
        // адресуются по session_id, поэтому записать его надо заново и сразу.
        assertEquals(0L, policy.delayFor("черновик A", now = 1_100))
        assertEquals(0L, policy.delayFor("черновик B", now = 1_100))
    }

    @Test
    fun `leaving the screen flushes the pending remainder outside the window`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("привет", now = 1_000)
        assertTrue(policy.drainOnPause("привет, мир"))
        assertFalse("повторная досылка того же текста не нужна", policy.drainOnPause("привет"))
    }

    @Test
    fun `clearing the composer is a write worth sending`() {
        val policy = ServerDraftSyncPolicy(quietMillis = 2_000)
        policy.onSaved("текст", now = 1_000)
        assertTrue("пустая строка очищает серверную копию", policy.drainOnPause(""))
    }

    @Test
    fun `user typing wins over both server and local drafts and the field is left alone`() {
        assertNull(ServerDraftAdoption.textFor("сервер", "локальный", composerText = "я уже печатаю"))
    }

    @Test
    fun `local draft wins over the server draft`() {
        assertEquals("локальный", ServerDraftAdoption.textFor("сервер", "локальный", composerText = ""))
    }

    @Test
    fun `server draft fills an empty composer when no local draft exists`() {
        assertEquals("сервер", ServerDraftAdoption.textFor("сервер", null, composerText = ""))
        assertEquals("сервер", ServerDraftAdoption.textFor("сервер", "   ", composerText = ""))
    }

    @Test
    fun `blank server draft never touches the composer`() {
        assertNull(ServerDraftAdoption.textFor(null, null, composerText = ""))
        assertNull(ServerDraftAdoption.textFor("   ", null, composerText = ""))
    }
}
