package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты guard'а асинхронной навигации [SessionNavigationPolicy]: побеждает последняя
 * загрузка, устаревший callback не применяется, синхронные смены контекста («новый чат»,
 * «финиш», удаление текущего диалога, выход из аккаунта) инвалидируют pending-навигацию.
 */
class SessionNavigationPolicyTest {

    private val policy = SessionNavigationPolicy()

    @Test
    fun `single navigation token stays current`() {
        val token = policy.beginNavigation()

        assertTrue(policy.isCurrent(token))
    }

    @Test
    fun `latest load wins - stale callback must not apply`() {
        val first = policy.beginNavigation()
        val second = policy.beginNavigation()

        assertFalse("первая загрузка устарела", policy.isCurrent(first))
        assertTrue("вторая загрузка актуальна", policy.isCurrent(second))
    }

    @Test
    fun `invalidatePending kills in-flight navigation`() {
        val token = policy.beginNavigation()

        policy.invalidatePending()

        assertFalse(policy.isCurrent(token))
    }

    @Test
    fun `invalidation does not disturb a navigation started after it`() {
        policy.invalidatePending()
        val token = policy.beginNavigation()

        policy.invalidatePending()
        assertFalse(token == 0L)
        assertFalse(policy.isCurrent(token))

        val next = policy.beginNavigation()
        assertTrue(policy.isCurrent(next))
    }

    @Test
    fun `tokens never repeat across navigations and invalidations`() {
        val seen = mutableSetOf<Long>()
        repeat(10) {
            seen.add(policy.beginNavigation())
            policy.invalidatePending()
        }
        // Все выданные токены различны: устаревший callback не может «угадать» поколение.
        assertEquals(10, seen.size)
    }
}
