package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCardPresenterTest {

    @Test
    fun `no thoughts renders the shared hidden card`() {
        val view = ThinkingCardPresenter.present(emptyList(), expanded = false, streaming = false)
        assertSame(ThinkingCardView.HIDDEN, view)
        assertFalse(view.visible)
        assertEquals(0, view.fragmentCount)
        assertNull(view.statusLabel)
    }

    @Test
    fun `blank-only thoughts are also hidden`() {
        val view = ThinkingCardPresenter.present(listOf("", "   ", "\n\t"), expanded = true, streaming = true)
        assertSame(ThinkingCardView.HIDDEN, view)
    }

    @Test
    fun `chip carries the fixed reflection label`() {
        val view = ThinkingCardPresenter.present(listOf("мысль"), expanded = false, streaming = false)
        assertTrue(view.visible)
        assertEquals("Размышления Марса", view.chipLabel)
        assertEquals(ThinkingCardPresenter.CHIP_LABEL, view.chipLabel)
    }

    @Test
    fun `streaming turn shows the thinking indicator`() {
        val view = ThinkingCardPresenter.present(listOf("думаю над задачей"), expanded = false, streaming = true)
        assertTrue(view.streaming)
        assertEquals("Думает…", view.statusLabel)
        assertEquals(ThinkingCardPresenter.THINKING_LABEL, view.statusLabel)
    }

    @Test
    fun `settled turn shows fragment summary instead of thinking indicator`() {
        val view = ThinkingCardPresenter.present(
            listOf("первая", "вторая", "третья"), expanded = false, streaming = false,
        )
        assertFalse(view.streaming)
        assertEquals("3 фрагмента", view.statusLabel)
    }

    @Test
    fun `fragment summary respects russian plurals`() {
        assertEquals("фрагмент", ThinkingCardPresenter.fragmentsWord(1))
        assertEquals("фрагмента", ThinkingCardPresenter.fragmentsWord(2))
        assertEquals("фрагментов", ThinkingCardPresenter.fragmentsWord(5))
        assertEquals("фрагментов", ThinkingCardPresenter.fragmentsWord(11))
        assertEquals("фрагментов", ThinkingCardPresenter.fragmentsWord(14))
        assertEquals("фрагмент", ThinkingCardPresenter.fragmentsWord(21))
        assertEquals("фрагмента", ThinkingCardPresenter.fragmentsWord(22))
        assertEquals("фрагментов", ThinkingCardPresenter.fragmentsWord(25))
        assertEquals("фрагментов", ThinkingCardPresenter.fragmentsWord(111))
        assertEquals("фрагмент", ThinkingCardPresenter.fragmentsWord(101))
        val view = ThinkingCardPresenter.present(List(21) { "м" }, expanded = false, streaming = false)
        assertEquals("21 фрагмент", view.statusLabel)
    }

    @Test
    fun `chevron follows the expanded state`() {
        val collapsed = ThinkingCardPresenter.present(listOf("мысль"), expanded = false, streaming = false)
        assertFalse(collapsed.chevronUp)
        assertFalse(collapsed.expanded)
        val expanded = ThinkingCardPresenter.present(listOf("мысль"), expanded = true, streaming = false)
        assertTrue(expanded.chevronUp)
        assertTrue(expanded.expanded)
    }

    @Test
    fun `body drops blank fragments and trims the rest`() {
        val view = ThinkingCardPresenter.present(
            listOf("  первая  ", "", "вторая", "   "), expanded = true, streaming = false,
        )
        assertEquals("первая\nвторая", view.bodyText)
        assertEquals(2, view.fragmentCount)
    }

    @Test
    fun `body collapses newline ladders into a single paragraph break`() {
        val view = ThinkingCardPresenter.present(
            listOf("начало\n\n\n\nсередина", "продолжение"), expanded = true, streaming = false,
        )
        assertEquals("начало\n\nсередина\nпродолжение", view.bodyText)
    }

    @Test
    fun `copy action always carries the full normalized text`() {
        val view = ThinkingCardPresenter.present(
            listOf("альфа", "бета"), expanded = false, streaming = true,
        )
        assertEquals("Скопировать мысли", view.copyLabel)
        assertEquals(ThinkingCardPresenter.COPY_LABEL, view.copyLabel)
        assertEquals("альфа\nбета", view.copySource)
        assertEquals(view.bodyText, view.copySource)
    }

    @Test
    fun `body keeps the special indent token for the render layer`() {
        val view = ThinkingCardPresenter.present(listOf("мысль"), expanded = true, streaming = false)
        assertEquals(12, view.bodyIndentDp)
        assertEquals(ThinkingCardPresenter.BODY_INDENT_DP, view.bodyIndentDp)
    }

    @Test
    fun `palette comes from theme constants, never hardcoded`() {
        val view = ThinkingCardPresenter.present(listOf("мысль"), expanded = true, streaming = false)
        assertEquals(MarsTheme.ACCENT_TEXT, view.chipColor)
        assertEquals(MarsTheme.TEXT_EM, view.bodyColor)
        assertEquals(MarsTheme.THOUGHT_BG, view.cardColor)
        assertEquals(MarsTheme.THOUGHT_BORDER, view.borderColor)
        assertEquals(MarsTheme.ACCENT_TEXT, ThinkingCardView.HIDDEN.chipColor)
    }

    @Test
    fun `streaming empty thoughts stay hidden even while thinking`() {
        val view = ThinkingCardPresenter.present(emptyList(), expanded = false, streaming = true)
        assertSame(ThinkingCardView.HIDDEN, view)
        assertNull(ThinkingCardPresenter.statusLabel(emptyList(), streaming = true))
    }
}
