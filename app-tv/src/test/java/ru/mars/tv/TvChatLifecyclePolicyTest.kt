package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvChatLifecyclePolicy] — the pure seam that separates a TECHNICAL
 * Activity destroy (recreation: the server turn must survive and the binding must be
 * persisted) from an EXPLICIT user exit (BACK/finish: the turn is cancelled). Also pins
 * the sanitize rules of the minimal session/stream binding ([TvChatBinding]).
 */
class TvChatLifecyclePolicyTest {

    // --- cancel-vs-recreate decision -----------------------------------------------------

    @Test
    fun `explicit user exit cancels the server turn`() {
        // BACK/finish: isFinishing without a configuration change.
        assertTrue(
            TvChatLifecyclePolicy.shouldCancelServerTurnOnDestroy(
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun `configuration-change recreation keeps the server turn running`() {
        assertFalse(
            TvChatLifecyclePolicy.shouldCancelServerTurnOnDestroy(
                isFinishing = false,
                isChangingConfigurations = true,
            )
        )
        // Defensive: a finishing-and-recreating combination must still not cancel —
        // the recreated screen rebinds the turn (TvChatSessionFlow.restoreBinding).
        assertFalse(
            TvChatLifecyclePolicy.shouldCancelServerTurnOnDestroy(
                isFinishing = true,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun `system-initiated destroy keeps the server turn running`() {
        // Neither finishing nor a config change (e.g. teardown while backgrounded):
        // not a user exit — the agent turn must not be killed.
        assertFalse(
            TvChatLifecyclePolicy.shouldCancelServerTurnOnDestroy(
                isFinishing = false,
                isChangingConfigurations = false,
            )
        )
    }

    // --- binding sanitization --------------------------------------------------------------

    @Test
    fun `nothing to bind yields no binding`() {
        assertNull(TvChatLifecyclePolicy.sanitizedBinding(null, null))
        assertNull(TvChatLifecyclePolicy.sanitizedBinding("", ""))
        assertNull(TvChatLifecyclePolicy.sanitizedBinding("   ", "\t"))
    }

    @Test
    fun `ids are trimmed and blanks become null`() {
        assertEquals(
            TvChatBinding("s-1", "st-2"),
            TvChatLifecyclePolicy.sanitizedBinding(" s-1 ", "  st-2"),
        )
        assertEquals(
            TvChatBinding("s-1", null),
            TvChatLifecyclePolicy.sanitizedBinding("s-1", "   "),
        )
    }

    @Test
    fun `a stream-only binding stays valid so the orphan turn remains stoppable`() {
        val binding = TvChatLifecyclePolicy.sanitizedBinding(null, "st-9")
        assertEquals(TvChatBinding(null, "st-9"), binding)
        assertNull(binding?.sessionId)
        assertEquals("st-9", binding?.activeStreamId)
    }
}
