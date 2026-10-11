package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSearchPolicyTest {

    private val texts = listOf(
        "Привет, Марс!",                       // 0
        "Сегодня проверяем Квартал и Марс.",   // 1
        "Марс ответил: готово. марс на связи", // 2
        "",                                    // 3
    )

    @Test
    fun `short or blank query is not searched`() {
        assertTrue(!TranscriptSearchPolicy.shouldSearch(""))
        assertTrue(!TranscriptSearchPolicy.shouldSearch("  "))
        assertTrue(!TranscriptSearchPolicy.shouldSearch("м"))
        assertTrue(TranscriptSearchPolicy.findMatches(texts, "м").isEmpty())
        assertTrue(TranscriptSearchPolicy.findMatches(texts, "").isEmpty())
    }

    @Test
    fun `finds all case-insensitive occurrences across messages in order`() {
        val matches = TranscriptSearchPolicy.findMatches(texts, "марс")
        assertEquals(
            listOf(
                TranscriptMatch(0, 8, 12),
                TranscriptMatch(1, 28, 32),
                TranscriptMatch(2, 0, 4),
                TranscriptMatch(2, 22, 26),
            ),
            matches,
        )
    }

    @Test
    fun `query is trimmed before matching`() {
        val matches = TranscriptSearchPolicy.findMatches(texts, "  квартал ")
        assertEquals(listOf(TranscriptMatch(1, 18, 25)), matches)
    }

    @Test
    fun `overlapping occurrences are not double counted`() {
        val matches = TranscriptSearchPolicy.findMatches(listOf("ааааа"), "аа")
        assertEquals(listOf(TranscriptMatch(0, 0, 2), TranscriptMatch(0, 2, 4)), matches)
    }

    @Test
    fun `match list is capped at MAX_MATCHES`() {
        val huge = List(600) { "слово" }
        val matches = TranscriptSearchPolicy.findMatches(huge, "слово")
        assertEquals(TranscriptSearchPolicy.MAX_MATCHES, matches.size)
    }

    @Test
    fun `navigation wraps around in both directions`() {
        assertEquals(1, TranscriptSearchPolicy.navigate(total = 3, current = 0, forward = true))
        assertEquals(0, TranscriptSearchPolicy.navigate(total = 3, current = 2, forward = true))
        assertEquals(2, TranscriptSearchPolicy.navigate(total = 3, current = 0, forward = false))
        assertEquals(1, TranscriptSearchPolicy.navigate(total = 3, current = 2, forward = false))
    }

    @Test
    fun `navigation with no matches or unset position`() {
        assertEquals(-1, TranscriptSearchPolicy.navigate(total = 0, current = -1, forward = true))
        assertEquals(0, TranscriptSearchPolicy.navigate(total = 5, current = -1, forward = true))
        assertEquals(0, TranscriptSearchPolicy.navigate(total = 5, current = 99, forward = false))
    }

    @Test
    fun `counter label reflects position, absence and empty query`() {
        assertEquals("", TranscriptSearchPolicy.counterLabel("", current = 0, total = 0))
        assertEquals("", TranscriptSearchPolicy.counterLabel("м", current = 0, total = 0))
        assertEquals(TranscriptSearchPolicy.NO_MATCHES_LABEL, TranscriptSearchPolicy.counterLabel("неттакогослова", 0, 0))
        assertEquals("1 из 4", TranscriptSearchPolicy.counterLabel("марс", 0, 4))
        assertEquals("4 из 4", TranscriptSearchPolicy.counterLabel("марс", 3, 4))
    }
}
