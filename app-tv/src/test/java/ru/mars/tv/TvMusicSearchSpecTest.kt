package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvMusicSearchSpec] — the pure search rules of the TV music screen:
 * query normalization (what the IME/«Найти» submit actually sends) and the charts-vs-search
 * empty wording.
 */
class TvMusicSearchSpecTest {

    @Test
    fun normalizedQueryTrimsAndKeepsNonBlankText() {
        assertEquals("radio", TvMusicSearchSpec.normalizedQuery("  radio  "))
        assertEquals("ночь & день", TvMusicSearchSpec.normalizedQuery(" ночь & день "))
    }

    @Test
    fun blankOrNullQueryNormalizesToNull() {
        assertNull(TvMusicSearchSpec.normalizedQuery(null))
        assertNull(TvMusicSearchSpec.normalizedQuery(""))
        assertNull(TvMusicSearchSpec.normalizedQuery("   \t "))
    }

    @Test
    fun emptyMessageDistinguishesChartsFromSearch() {
        val charts = TvMusicSearchSpec.emptyMessage(null)
        val search = TvMusicSearchSpec.emptyMessage("ночь")
        assertEquals("Треков пока нет", charts)
        assertTrue("search-empty must quote the query, got: $search", search.contains("ночь"))
        assertTrue("the two empties must read differently", charts != search)
    }
}
