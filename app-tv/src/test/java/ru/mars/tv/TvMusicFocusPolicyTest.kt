package ru.mars.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvMusicFocusPolicy] — pure JVM, no Android framework.
 *
 * Regression cover for the D-pad stranding defect: pressing «Остановить» hid the
 * focused stop button synchronously (transport.stop() notifies observers inside the
 * click), leaving the TV window with currentFocus == null and no touch fallback —
 * further D-pad presses went nowhere. The policy must order a focus handoff to the
 * stable header BEFORE a focused affordance is hidden, and only then.
 */
class TvMusicFocusPolicyTest {

    // --- the defect scenario -------------------------------------------------------------------

    @Test
    fun `focused stop button that disappears must hand off focus first`() {
        assertTrue(
            "hiding the focused stop button without handoff strands D-pad navigation",
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = true,
                viewHasFocus = true,
                staysVisible = false,
            ),
        )
    }

    // --- no false handoffs ----------------------------------------------------------------------

    @Test
    fun `focused button that stays visible never triggers handoff`() {
        // Transport restyle while playing (prepared/completion notifications): the stop
        // button keeps focus where the user left it.
        assertFalse(
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = true,
                viewHasFocus = true,
                staysVisible = true,
            ),
        )
    }

    @Test
    fun `disappearing button without focus keeps focus where it is`() {
        // Async transport stop while D-pad focus sits on a track row: the row must not
        // be yanked up to the header.
        assertFalse(
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = true,
                viewHasFocus = false,
                staysVisible = false,
            ),
        )
    }

    @Test
    fun `already hidden button needs no handoff even if queried as focused`() {
        // Duplicate transport notifications after the button is gone: idempotent no-op.
        assertFalse(
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = false,
                viewHasFocus = true,
                staysVisible = false,
            ),
        )
    }

    @Test
    fun `fully idle button is a no-op`() {
        assertFalse(
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = false,
                viewHasFocus = false,
                staysVisible = false,
            ),
        )
    }
}
