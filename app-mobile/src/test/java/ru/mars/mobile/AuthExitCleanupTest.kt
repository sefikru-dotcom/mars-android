package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты одноразовой очистки локальных plaintext-данных при выходе из авторизации
 * [AuthExitCleanup]: все хранилища стираются, повторный запуск — no-op, после очистки
 * сохранение черновиков запрещено (onPause закрывающегося экрана не пишет обратно),
 * падающее хранилище не блокирует остальные (best-effort, как и revoke).
 */
class AuthExitCleanupTest {

    private class FakeStore(val name: String = "store") : ClearableLocalStore {
        var clears = 0
        var content: String? = "plaintext"

        override fun clearAll() {
            clears++
            content = null
        }
    }

    private class FailingStore : ClearableLocalStore {
        override fun clearAll() = throw IllegalStateException("prefs недоступны")
    }

    @Test
    fun `run clears every store exactly once`() {
        val drafts = FakeStore("drafts")
        val music = FakeStore("music-history")
        val cleanup = AuthExitCleanup(listOf(drafts, music))

        assertTrue(cleanup.run())

        assertEquals(1, drafts.clears)
        assertEquals(1, music.clears)
        assertFalse(cleanup.draftPersistenceAllowed)
        assertTrue(cleanup.hasRun)
    }

    @Test
    fun `second run is a no-op`() {
        val store = FakeStore()
        val cleanup = AuthExitCleanup(listOf(store))
        cleanup.run()

        assertFalse(cleanup.run())
        assertEquals(1, store.clears)
    }

    @Test
    fun `draft persistence is allowed before exit and forbidden after`() {
        val cleanup = AuthExitCleanup(listOf(FakeStore()))

        assertTrue(cleanup.draftPersistenceAllowed)
        cleanup.run()
        assertFalse(cleanup.draftPersistenceAllowed)
    }

    @Test
    fun `failing store does not block the others nor the exit flag`() {
        val after = FakeStore()
        val cleanup = AuthExitCleanup(listOf(FailingStore(), after))

        assertTrue(cleanup.run())

        assertEquals("best-effort: остальные хранилища очищены", 1, after.clears)
        assertFalse(cleanup.draftPersistenceAllowed)
    }
}
