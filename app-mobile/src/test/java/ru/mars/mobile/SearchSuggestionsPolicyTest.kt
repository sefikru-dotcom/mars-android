package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракты живых подсказок поиска (MUS-SUGG): дебаунс, минимальная длина запроса,
 * чистка списка и видимость панели — всё на чистой политике без Android-зависимостей.
 */
class SearchSuggestionsPolicyTest {

    @Test
    fun normalizeTrimsAndReturnsNullForBlank() {
        assertNull(SearchSuggestionsPolicy.normalize(null))
        assertNull(SearchSuggestionsPolicy.normalize(""))
        assertNull(SearchSuggestionsPolicy.normalize("   "))
        assertEquals("rock", SearchSuggestionsPolicy.normalize("  rock  "))
    }

    @Test
    fun shouldFetchRequiresMinLength() {
        assertFalse(SearchSuggestionsPolicy.shouldFetch(null))
        assertFalse(SearchSuggestionsPolicy.shouldFetch(""))
        assertFalse(SearchSuggestionsPolicy.shouldFetch("р"))
        assertFalse(SearchSuggestionsPolicy.shouldFetch("  "))
        assertTrue(SearchSuggestionsPolicy.shouldFetch("ро"))
        assertTrue(SearchSuggestionsPolicy.shouldFetch("rock & roll"))
    }

    @Test
    fun panelVisibleOnlyWhenFocusedWithQueryAndNonEmptySuggestions() {
        assertFalse(SearchSuggestionsPolicy.isPanelVisible("ro", fieldFocused = false, suggestionsEmpty = false))
        assertFalse(SearchSuggestionsPolicy.isPanelVisible("ro", fieldFocused = true, suggestionsEmpty = true))
        assertFalse(SearchSuggestionsPolicy.isPanelVisible("r", fieldFocused = true, suggestionsEmpty = false))
        assertTrue(SearchSuggestionsPolicy.isPanelVisible("ro", fieldFocused = true, suggestionsEmpty = false))
    }

    @Test
    fun visibleSuggestionsTrimsDropsBlanksAndDedupesCaseInsensitive() {
        assertEquals(
            listOf("Rock Live", "РОК ЖИВЬЁМ"),
            SearchSuggestionsPolicy.visibleSuggestions(
                listOf(" Rock Live ", "", "   ", "rock LIVE", "РОК ЖИВЬЁМ", "рок живьём"),
                fieldText = null,
            ),
        )
    }

    @Test
    fun visibleSuggestionsExcludesTheTypedQueryItself() {
        assertEquals(
            listOf("rock album"),
            SearchSuggestionsPolicy.visibleSuggestions(
                listOf("Rock & Roll", "rock album"),
                fieldText = "Rock & Roll",
            ),
        )
    }

    @Test
    fun visibleSuggestionsCapsAtMaxVisible() {
        val raw = (1..20).map { "item $it" }
        assertEquals(8, SearchSuggestionsPolicy.visibleSuggestions(raw, fieldText = null).size)
    }

    @Test
    fun visibleSuggestionsEmptyInputYieldsEmptyList() {
        assertEquals(emptyList<String>(), SearchSuggestionsPolicy.visibleSuggestions(emptyList(), fieldText = null))
    }
}
