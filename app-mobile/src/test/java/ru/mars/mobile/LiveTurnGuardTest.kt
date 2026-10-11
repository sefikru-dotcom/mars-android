package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [LiveTurnGuard]: токен send-стрима живёт до смены контекста; уход с экрана
 * (переключение диалога, «новый чат», «стоп», logout) гасит токен — поздние SSE-события
 * старого хода структурно не попадают в новый транскрипт.
 */
class LiveTurnGuardTest {

    private val guard = LiveTurnGuard()

    @Test
    fun `fresh turn token is current`() {
        val token = guard.beginTurn()

        assertTrue(guard.isCurrent(token))
    }

    @Test
    fun `zero token is never current`() {
        assertFalse(guard.isCurrent(0L))

        guard.beginTurn()

        assertFalse(guard.isCurrent(0L))
    }

    @Test
    fun `new turn supersedes previous turn token`() {
        val first = guard.beginTurn()
        val second = guard.beginTurn()

        assertFalse("поздние события старого хода отсекаются", guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `invalidate kills the live turn - late SSE events must not touch the new screen`() {
        val token = guard.beginTurn()

        guard.invalidate()

        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun `turn started after invalidate is current again`() {
        val stale = guard.beginTurn()
        guard.invalidate()

        val fresh = guard.beginTurn()

        assertFalse(guard.isCurrent(stale))
        assertTrue(guard.isCurrent(fresh))
    }

    @Test
    fun `repeated invalidate without a live turn is safe`() {
        guard.invalidate()
        val token = guard.beginTurn()

        guard.invalidate()
        guard.invalidate()

        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun `session switch during collect leaves stale token dead forever`() {
        val stale = guard.beginTurn()
        // Уход в другой диалог: invalidate, затем новый ход уже в новом контексте.
        guard.invalidate()
        val newContext = guard.beginTurn()

        assertFalse("старый collect больше не владеет экраном", guard.isCurrent(stale))
        assertTrue(guard.isCurrent(newContext))
        // Даже повторный invalidate нового контекста не «оживляет» старый токен.
        guard.invalidate()
        assertFalse(guard.isCurrent(stale))
    }

    @Test
    fun `tokens never repeat across turns and invalidations`() {
        val seen = mutableSetOf<Long>()
        repeat(10) {
            seen.add(guard.beginTurn())
            guard.invalidate()
        }
        // Все выданные токены различны: устаревший collect не может «угадать» поколение.
        assertEquals(10, seen.size)
    }
}
