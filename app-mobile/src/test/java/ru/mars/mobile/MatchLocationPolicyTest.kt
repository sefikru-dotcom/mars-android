package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Локализация ordinal-го совпадения по строкам рендера пузыря (волна C, фича C):
 * скролл должен приходить к самому совпадению, в том числе когда их несколько
 * в одном сообщении, а не к верхушке карточки.
 */
class MatchLocationPolicyTest {

    @Test
    fun `first ordinal lands in the first rendered line`() {
        val location = MatchLocationPolicy.locate(listOf("Марс на связи", "второй Марс"), "марс", 0)
        assertEquals(MatchLocationPolicy.Location(0, 0, 4), location)
    }

    @Test
    fun `ordinals count across rendered lines in order`() {
        val lines = listOf("Марс раз", "Марс два", "Марс три")
        assertEquals(MatchLocationPolicy.Location(1, 0, 4), MatchLocationPolicy.locate(lines, "марс", 1))
        assertEquals(MatchLocationPolicy.Location(2, 0, 4), MatchLocationPolicy.locate(lines, "марс", 2))
    }

    @Test
    fun `multiple occurrences inside one line are separate ordinals`() {
        val lines = listOf("кот и кот и кот")
        assertEquals(MatchLocationPolicy.Location(0, 0, 3), MatchLocationPolicy.locate(lines, "кот", 0))
        assertEquals(MatchLocationPolicy.Location(0, 6, 9), MatchLocationPolicy.locate(lines, "кот", 1))
        assertEquals(MatchLocationPolicy.Location(0, 12, 15), MatchLocationPolicy.locate(lines, "кот", 2))
    }

    @Test
    fun `match is case-insensitive and keeps rendered-line offsets`() {
        val lines = listOf("Ответ МАРСА", "обычная строка")
        assertEquals(MatchLocationPolicy.Location(0, 6, 11), MatchLocationPolicy.locate(lines, "Марса", 0))
    }

    @Test
    fun `overlapping occurrences are not counted twice`() {
        val lines = listOf("ааааа")
        assertEquals(MatchLocationPolicy.Location(0, 0, 2), MatchLocationPolicy.locate(lines, "аа", 0))
        assertEquals(MatchLocationPolicy.Location(0, 2, 4), MatchLocationPolicy.locate(lines, "аа", 1))
        assertNull(MatchLocationPolicy.locate(lines, "аа", 2))
    }

    @Test
    fun `out-of-range ordinal, blank needle or negative ordinal give null`() {
        val lines = listOf("Марс")
        assertNull(MatchLocationPolicy.locate(lines, "марс", 5))
        assertNull(MatchLocationPolicy.locate(lines, "  ", 0))
        assertNull(MatchLocationPolicy.locate(lines, "", 0))
        assertNull(MatchLocationPolicy.locate(lines, "марс", -1))
    }

    @Test
    fun `needle is trimmed like the search query`() {
        val lines = listOf("Ответ Марса")
        assertEquals(MatchLocationPolicy.Location(0, 6, 11), MatchLocationPolicy.locate(lines, "  марса  ", 0))
    }
}
