package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Задача C (P0-3): привязка поиска к РЕАЛЬНОМУ сообщению.
 *
 * Регрессия: навигация «1/14 -> 2/14» была привязана к порядковому индексу
 * карточки/позиции в списке. На группировке «один ход = одна карточка» несколько
 * совпадений живут в ОДНОЙ карточке, и переход k -> k+1 каждый раз «прыгал» к её
 * первому сообщению. Здесь результат адресуется парой
 * (messageId реального сообщения, ordinal совпадения внутри его текста) —
 * адрес не зависит от группировки/склейки карточек.
 *
 * Семантика поиска — та же, что у app-mobile TranscriptSearchPolicy
 * (регистронезависимо, без перекрывающихся вхождений, MIN_QUERY_LENGTH,
 * MAX_MATCHES, кольцевая навигация), чтобы интеграция была перестановкой адреса,
 * а не сменой поведения поиска.
 */
class MessageSearchPolicyTest {

    /** 6 реальных сообщений; группировка в карточки: A=[m1,m2,m3], B=[m4], C=[m5,m6]. */
    private val messages = listOf(
        SearchableMessage("m1", "Марс говорит марс."),            // 2 совпадения: 0, 13
        SearchableMessage("m2", "марс марс МАРС марс"),            // 4 совпадения: 0, 5, 10, 15
        SearchableMessage("m3", "обычный текст без совпадений"),   // 0
        SearchableMessage("m4", "марс"),                           // 1
        SearchableMessage("m5", "снова марс и ещё марс"),          // 2: 6, 17
        SearchableMessage("m6", "марс марс марс марс марс"),       // 5: 0,5,10,15,20
    ) // Итого 14 совпадений; 6 из них (позиции 0..5) — в одной «карточке» A.

    private val matches = MessageSearchPolicy.findMatches(messages, "марс")

    // ---- Якоря: 14 совпадений, несколько в одной карточке -----------------------------------------

    @Test
    fun fourteenMatchesAddressedByRealMessageAndOrdinal() {
        assertEquals(14, matches.size)
        // Адрес каждого совпадения — (messageId, ordinal внутри сообщения).
        val expectedAddresses = listOf(
            "m1" to 0, "m1" to 1,
            "m2" to 0, "m2" to 1, "m2" to 2, "m2" to 3,
            "m4" to 0,
            "m5" to 0, "m5" to 1,
            "m6" to 0, "m6" to 1, "m6" to 2, "m6" to 3, "m6" to 4,
        )
        assertEquals(expectedAddresses, matches.map { it.messageId to it.ordinalInMessage })
        // Все 14 якорей различимы — карточная адресация схлопнула бы их в 3.
        assertEquals(14, matches.map { it.messageId to it.ordinalInMessage }.distinct().size)
    }

    @Test
    fun matchSpansAreValidAndKeepOriginalCase() {
        val byId = messages.associate { it.messageId to it.text }
        for (match in matches) {
            val text = byId.getValue(match.messageId)
            assertEquals("марс", text.substring(match.matchStart, match.matchEnd).lowercase())
            assertEquals(match.matchEnd - match.matchStart, 4)
        }
        // Регистр исходного текста сохраняется в matchedText (подсветка как есть).
        assertEquals("МАРС", matches.first { it.messageId == "m2" && it.ordinalInMessage == 2 }.matchedText)
        assertEquals("Марс", matches.first { it.messageId == "m1" && it.ordinalInMessage == 0 }.matchedText)
    }

    // ---- k -> k+1: следующее совпадение, а НЕ первая карточка --------------------------------------

    @Test
    fun stepForwardMovesToNextMatchNotToCardFirst() {
        val first = MessageSearchPolicy.anchorAt(matches, 0)!!
        val second = MessageSearchPolicy.anchorAt(matches, 1)!!
        val third = MessageSearchPolicy.anchorAt(matches, 2)!!
        // 1/14 -> 2/14: то же реальное сообщение m1, следующее вхождение.
        // При карточной адресации оба попали бы в «карточку A» и экран прыгал бы к её началу.
        assertEquals("m1", first.messageId)
        assertEquals(0, first.ordinalInMessage)
        assertEquals("m1", second.messageId)
        assertEquals(1, second.ordinalInMessage)
        assertTrue(first != second)
        // 2/14 -> 3/14: ДРУГОЕ реальное сообщение (m2) внутри той же карточки A.
        assertEquals("m2", third.messageId)
        assertEquals(0, third.ordinalInMessage)
        assertTrue(second != third)
        // Полный проход: ни один шаг не повторяет якорь.
        val walked = (0 until 14).map { MessageSearchPolicy.anchorAt(matches, it) }
        assertEquals(14, walked.distinct().size)
        walked.forEachIndexed { position, anchor -> assertEquals(position, MessageSearchPolicy.positionOf(matches, anchor)) }
    }

    @Test
    fun anchorIsBoundToMessageIdNotToContentOrIndex() {
        val anchor = MessageSearchPolicy.anchorAt(matches, 6)!! // m4
        // Тот же текст, но другое реальное сообщение — адрес НЕ совпадает.
        val renamed = SearchableMessage("m4-copy", anchor.matchedText)
        val otherMatches = MessageSearchPolicy.findMatches(listOf(renamed), "марс")
        assertEquals(-1, MessageSearchPolicy.positionOf(otherMatches, anchor))
        assertEquals(0, MessageSearchPolicy.positionOf(otherMatches, MessageSearchPolicy.anchorAt(otherMatches, 0)))
    }

    // ---- Границы и пустые входы --------------------------------------------------------------------

    @Test
    fun navigateWrapsAroundBothBounds() {
        assertEquals(1, MessageSearchPolicy.navigate(14, 0, forward = true))
        assertEquals(13, MessageSearchPolicy.navigate(14, 12, forward = true))
        assertEquals(0, MessageSearchPolicy.navigate(14, 13, forward = true))   // последнее -> первое
        assertEquals(13, MessageSearchPolicy.navigate(14, 0, forward = false))  // первое -> последнее
        assertEquals(12, MessageSearchPolicy.navigate(14, 13, forward = false))
        assertEquals(0, MessageSearchPolicy.navigate(14, -1, forward = true))   // не инициализирована
        assertEquals(-1, MessageSearchPolicy.navigate(0, 0, forward = true))
        assertEquals(-1, MessageSearchPolicy.navigate(0, -1, forward = false))
    }

    @Test
    fun anchorAtRejectsOutOfRangePositions() {
        assertEquals(matches.first(), MessageSearchPolicy.anchorAt(matches, 0))
        assertEquals(matches.last(), MessageSearchPolicy.anchorAt(matches, 13))
        assertNull(MessageSearchPolicy.anchorAt(matches, 14))
        assertNull(MessageSearchPolicy.anchorAt(matches, -1))
        assertNull(MessageSearchPolicy.anchorAt(emptyList(), 0))
    }

    @Test
    fun emptyAndNoMatchInputsYieldNothing() {
        assertTrue(MessageSearchPolicy.findMatches(emptyList(), "марс").isEmpty())
        assertTrue(MessageSearchPolicy.findMatches(messages, "").isEmpty())
        assertTrue(MessageSearchPolicy.findMatches(messages, " ").isEmpty())
        assertTrue(MessageSearchPolicy.findMatches(messages, "м").isEmpty()) // короче MIN_QUERY_LENGTH
        val noHits = MessageSearchPolicy.findMatches(messages, "пингвин")
        assertTrue(noHits.isEmpty())
        assertNull(MessageSearchPolicy.anchorAt(noHits, 0))
        assertEquals(-1, MessageSearchPolicy.navigate(noHits.size, 0, forward = true))
        assertEquals(-1, MessageSearchPolicy.positionOf(noHits, TextMatch("m1", 0, 0, 4, "Марс")))
        assertEquals(-1, MessageSearchPolicy.positionOf(matches, null))
    }

    @Test
    fun overlappingOccurrencesAreNotDoubleCounted() {
        val overlapped = MessageSearchPolicy.findMatches(listOf(SearchableMessage("m1", "ааааа")), "аа")
        assertEquals(listOf(0 to 2, 2 to 4), overlapped.map { it.matchStart to it.matchEnd })
        assertEquals(listOf(0, 1), overlapped.map { it.ordinalInMessage })
    }

    @Test
    fun caseAndTrimSemanticsMatchTranscriptPolicy() {
        assertTrue(MessageSearchPolicy.shouldSearch("ма"))
        assertTrue(!MessageSearchPolicy.shouldSearch("м"))
        assertTrue(!MessageSearchPolicy.shouldSearch("   "))
        assertEquals(matches, MessageSearchPolicy.findMatches(messages, "  МАРС "))
        assertEquals(matches, MessageSearchPolicy.findMatches(messages, "МАРС"))
    }

    @Test
    fun pathologicalTranscriptIsCapped() {
        val huge = SearchableMessage("big", "марс ".repeat(600))
        val capped = MessageSearchPolicy.findMatches(listOf(huge), "марс")
        assertEquals(MessageSearchPolicy.MAX_MATCHES, capped.size)
        assertEquals((0 until MessageSearchPolicy.MAX_MATCHES).toList(), capped.map { it.ordinalInMessage })
        assertEquals(MessageSearchPolicy.MAX_MATCHES - 1, MessageSearchPolicy.positionOf(capped, capped.last()))
    }

    @Test
    fun counterLabelKeepsPanelSignature() {
        assertEquals("", MessageSearchPolicy.counterLabel("м", 0, 0))
        assertEquals(MessageSearchPolicy.NO_MATCHES_LABEL, MessageSearchPolicy.counterLabel("пингвин", -1, 0))
        assertEquals("1 из 14", MessageSearchPolicy.counterLabel("марс", 0, 14))
        assertEquals("14 из 14", MessageSearchPolicy.counterLabel("марс", 13, 14))
    }
}
