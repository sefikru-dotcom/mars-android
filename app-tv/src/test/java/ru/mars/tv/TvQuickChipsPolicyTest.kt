package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvQuickChipsPolicy] — pure JVM, no Android framework. Covers the
 * chip row content contract (ids, labels, texts actually sent), the activation rule
 * (chips are live only while the composer is live: no stream, input not blocked)
 * and the clamped D-pad LEFT/RIGHT stepping (no wrap at the edges).
 */
class TvQuickChipsPolicyTest {

    private val size = TvQuickChipsPolicy.chips.size

    // --- row content ------------------------------------------------------------------------------

    @Test
    fun `row holds the five canned chips in D-pad order`() {
        assertEquals(
            listOf("ok", "continue", "rollback", "explain", "finish"),
            TvQuickChipsPolicy.chips.map { it.id },
        )
    }

    @Test
    fun `every chip has a non-blank label and text`() {
        TvQuickChipsPolicy.chips.forEach { chip ->
            assertTrue("label of ${chip.id} is blank", chip.label.isNotBlank())
            assertTrue("text of ${chip.id} is blank", chip.text.isNotBlank())
        }
    }

    @Test
    fun `chip ids are unique`() {
        val ids = TvQuickChipsPolicy.chips.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `finish chip sends the closing message, not its label`() {
        val finish = TvQuickChipsPolicy.byId("finish")
        assertEquals("Финиш", finish?.label)
        assertEquals("Ок, закрываю чат", finish?.text)
    }

    @Test
    fun `one-tap chips send their label as the message`() {
        listOf("ok", "continue", "rollback", "explain").forEach { id ->
            val chip = TvQuickChipsPolicy.byId(id)
            assertEquals(id, chip?.id)
            assertEquals(chip?.label, chip?.text)
        }
    }

    @Test
    fun `unknown id resolves to null`() {
        assertNull(TvQuickChipsPolicy.byId("nope"))
        assertNull(TvQuickChipsPolicy.byId(""))
    }

    // --- activation rule ---------------------------------------------------------------------------

    @Test
    fun `chips are live when the input is free and no turn is streaming`() {
        assertTrue(TvQuickChipsPolicy.chipsEnabled(streamActive = false, inputBlocked = false))
    }

    @Test
    fun `chips die while a turn is streaming`() {
        assertFalse(TvQuickChipsPolicy.chipsEnabled(streamActive = true, inputBlocked = false))
    }

    @Test
    fun `chips die while the input is blocked`() {
        assertFalse(TvQuickChipsPolicy.chipsEnabled(streamActive = false, inputBlocked = true))
    }

    @Test
    fun `chips die when a stream and a block are active together`() {
        assertFalse(TvQuickChipsPolicy.chipsEnabled(streamActive = true, inputBlocked = true))
    }

    // --- D-pad stepping -----------------------------------------------------------------------------

    @Test
    fun `right steps forward between chips`() {
        assertEquals(1, TvQuickChipsPolicy.move(0, size, 1))
        assertEquals(size - 1, TvQuickChipsPolicy.move(size - 2, size, 1))
    }

    @Test
    fun `right clamps at the last chip without wrapping`() {
        assertEquals(size - 1, TvQuickChipsPolicy.move(size - 1, size, 1))
    }

    @Test
    fun `left steps back between chips`() {
        assertEquals(size - 2, TvQuickChipsPolicy.move(size - 1, size, -1))
        assertEquals(0, TvQuickChipsPolicy.move(1, size, -1))
    }

    @Test
    fun `left clamps at the first chip without wrapping`() {
        assertEquals(0, TvQuickChipsPolicy.move(0, size, -1))
    }

    @Test
    fun `out-of-range selection snaps to the edge the delta points at`() {
        assertEquals(0, TvQuickChipsPolicy.move(TvQuickChipsPolicy.NO_SELECTION, size, 1))
        assertEquals(size - 1, TvQuickChipsPolicy.move(size, size, -1))
        assertEquals(0, TvQuickChipsPolicy.move(-5, size, 1))
    }

    @Test
    fun `empty row has no selection`() {
        assertEquals(TvQuickChipsPolicy.NO_SELECTION, TvQuickChipsPolicy.move(0, 0, 1))
        assertEquals(TvQuickChipsPolicy.NO_SELECTION, TvQuickChipsPolicy.move(0, 0, -1))
    }
}
