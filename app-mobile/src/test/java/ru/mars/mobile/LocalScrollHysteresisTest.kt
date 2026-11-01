package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гистерезис автоскролла ленты: два порога и underflow.
 *
 * Пороги взяты в пикселях с тем же смыслом, что в бою (`dp`, ×3): держим низ, пока до него
 * [leavePx] и меньше; возвращаемся, только когда человек подошёл ближе [stickPx]. Проверяется
 * чистая [LocalScrollPolicy] — эмулятора для этого не нужно.
 */
class LocalScrollHysteresisTest {

    /** dp(24) на экране ×3. */
    private val stickPx = LocalScrollPolicy.STICK_DP * 3

    /** dp(40) на экране ×3. */
    private val leavePx = LocalScrollPolicy.LEAVE_DP * 3

    private fun decide(
        distanceToBottom: Int,
        wasSticking: Boolean,
        scrollY: Int = 1_000,
    ): Boolean = LocalScrollPolicy.shouldStick(
        scrollY = scrollY,
        distanceToBottom = distanceToBottom,
        wasSticking = wasSticking,
        stickPx = stickPx,
        leavePx = leavePx,
    )

    @Test
    fun `underflow always sticks`() {
        // Контент короче вьюпорта: прокручивать нечего, лента обязана держать низ —
        // иначе первые же дельты стрима уехали бы за кадр.
        assertTrue(decide(distanceToBottom = -400, wasSticking = false))
        assertTrue(decide(distanceToBottom = -1, wasSticking = true))
        assertTrue(decide(distanceToBottom = 0, wasSticking = false))
        // Вьюха ещё не разложена: оба числа нулевые, решать не по чему — держим низ.
        assertTrue(decide(distanceToBottom = 0, wasSticking = false, scrollY = 0))
    }

    @Test
    fun `reading the top of a long answer is not underflow`() {
        // scrollY == 0 здесь значит «человек в начале ответа», а не «прокручивать нечего»:
        // прилипание к низу выдернуло бы его из чтения на первой же дельте.
        assertFalse(decide(distanceToBottom = 3_000, wasSticking = true, scrollY = 0))
        assertFalse(decide(distanceToBottom = 3_000, wasSticking = false, scrollY = 0))
    }

    @Test
    fun `while sticking a small distance keeps the tail`() {
        assertTrue(decide(distanceToBottom = 1, wasSticking = true))
        assertTrue(decide(distanceToBottom = stickPx, wasSticking = true))
        // Ровно на пороге ухода — ещё держим: мёртвая зона начинается за ним.
        assertTrue(decide(distanceToBottom = leavePx, wasSticking = true))
    }

    @Test
    fun `sticking stops once the reader is above the leave threshold`() {
        assertFalse(decide(distanceToBottom = leavePx + 1, wasSticking = true))
        assertFalse(decide(distanceToBottom = 5_000, wasSticking = true))
    }

    @Test
    fun `a detached reader is not re-attached from the dead zone`() {
        // Между STICK и LEAVE поведение зависит от прошлого состояния — это и есть
        // гистерезис: одного порога не хватило бы ни в одну, ни в другую сторону.
        val deadZone = (stickPx + leavePx) / 2
        assertTrue(decide(distanceToBottom = deadZone, wasSticking = true))
        assertFalse(decide(distanceToBottom = deadZone, wasSticking = false))
    }

    @Test
    fun `coming back to the tail re-attaches the reader`() {
        assertTrue(decide(distanceToBottom = stickPx - 1, wasSticking = false))
        // Ровно 24dp — ещё не «меньше 24dp»: человек не долистал до низа.
        assertFalse(decide(distanceToBottom = stickPx, wasSticking = false))
        assertFalse(decide(distanceToBottom = 9_000, wasSticking = false))
    }

    @Test
    fun `a burst of deltas never yanks a scrolled-up reader down`() {
        // Человек ушёл вверх, стрим продолжает расти: каждый рендер решает заново.
        var sticking = decide(distanceToBottom = 2_000, wasSticking = true)
        repeat(20) { step ->
            sticking = decide(distanceToBottom = 2_000 + step * 12, wasSticking = sticking)
            assertFalse(sticking)
        }
    }

    @Test
    fun `a pinned reader follows the whole burst`() {
        var sticking = true
        repeat(20) {
            sticking = decide(distanceToBottom = 0, wasSticking = sticking)
            assertTrue(sticking)
        }
    }
}
