package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * Версионирование асинхронных кадров: устаревший фоновый парсинг не публикуется
 * поверх более свежего рендера; invalidateInFlight гасит все незакоммиченные кадры.
 */
class FrameVersionGateTest {

    @Test
    fun freshFrameIsApplicable() {
        val gate = FrameVersionGate()
        val v = gate.beginFrame()
        assertTrue(gate.canApply(v))
    }

    @Test
    fun committedFrameIsNotApplicableTwice() {
        val gate = FrameVersionGate()
        val v = gate.beginFrame()
        gate.commit(v)
        assertFalse("закоммиченный кадр нельзя применить повторно", gate.canApply(v))
    }

    @Test
    fun newerFrameAfterCommitIsApplicable() {
        val gate = FrameVersionGate()
        val v1 = gate.beginFrame()
        gate.commit(v1)
        val v2 = gate.beginFrame()
        assertTrue(gate.canApply(v2))
    }

    @Test
    fun staleFrameIsRejectedAfterNewerCommit() {
        // Порядок публикации: старый кадр не применяется после нового (текст не мигает назад).
        val gate = FrameVersionGate()
        val stale = gate.beginFrame()
        val fresh = gate.beginFrame()
        gate.commit(fresh)
        assertFalse(gate.canApply(stale))
    }

    @Test
    fun invalidateInFlightRejectsAllIssuedFrames() {
        val gate = FrameVersionGate()
        val v1 = gate.beginFrame()
        val v2 = gate.beginFrame()
        gate.invalidateInFlight() // прошёл синхронный renderMessages()
        assertFalse(gate.canApply(v1))
        assertFalse(gate.canApply(v2))
    }

    @Test
    fun frameIssuedAfterInvalidationIsApplicable() {
        val gate = FrameVersionGate()
        gate.beginFrame()
        gate.invalidateInFlight()
        val v = gate.beginFrame()
        assertTrue(gate.canApply(v))
    }

    @Test
    fun versionsAreMonotonic() {
        val gate = FrameVersionGate()
        val a = gate.beginFrame()
        val b = gate.beginFrame()
        assertTrue(b > a)
    }
}
