package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * Реакция на системное давление памяти: чистка кэшей на RUNNING_LOW/CRITICAL,
 * UI_HIDDEN и глубже; RUNNING_MODERATE — ещё не повод (thrash миниатюр).
 */
class MemoryTrimPolicyTest {

    @Test
    fun runningModerateDoesNotClear() {
        assertFalse(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun runningLowClears() {
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_RUNNING_LOW))
    }

    @Test
    fun runningCriticalClears() {
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun uiHiddenClears() {
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun backgroundAndDeeperLevelsClear() {
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_BACKGROUND))
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_MODERATE))
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun lowMemoryCallbackClears() {
        assertTrue(MemoryTrimPolicy.shouldClearCaches(MemoryTrimPolicy.LOW_MEMORY_LEVEL))
    }

    @Test
    fun constantsMatchComponentCallbacks2Values() {
        // Значения продублированы из android.content.ComponentCallbacks2 (сверено с документацией API).
        assertEquals(5, MemoryTrimPolicy.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(10, MemoryTrimPolicy.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(15, MemoryTrimPolicy.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(20, MemoryTrimPolicy.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(40, MemoryTrimPolicy.TRIM_MEMORY_BACKGROUND)
        assertEquals(60, MemoryTrimPolicy.TRIM_MEMORY_MODERATE)
        assertEquals(80, MemoryTrimPolicy.TRIM_MEMORY_COMPLETE)
    }
}
