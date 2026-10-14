package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.repository.MessageSearchPolicy
import ru.mars.network.repository.SearchableMessage
import ru.mars.network.repository.TextMatch

/**
 * Логика позиционирования поиска в контроллере подсветки — на JVM, без Android-view:
 * адресация совпадения (messageId + ordinalInMessage), восстановление позиции по якорю
 * при пересчёте во время стрима, кольцевая навигация и подпись счётчика.
 *
 * Контроллер берёт эти решения из чистой [MessageSearchPolicy], а сам добавляет
 * синтетический messageId пузыря («m<индекс>») — поэтому проверяется прежде всего
 * связка «индекс карточки <-> messageId» и восстановление позиции.
 */
class TranscriptSearchHighlightControllerTest {

    private fun match(messageId: String, ordinal: Int, text: String = "марс") = TextMatch(
        messageId = messageId,
        ordinalInMessage = ordinal,
        matchStart = 0,
        matchEnd = text.length,
        matchedText = text,
    )

    private fun matchesFor(texts: List<String>, query: String): List<TextMatch> =
        MessageSearchPolicy.findMatches(
            texts.mapIndexed { index, text ->
                SearchableMessage(messageId = TranscriptSearchHighlightController.messageId(index), text = text)
            },
            query,
        )

    // --- синтетическая адресация пузыря ---

    @Test
    fun `message id round-trips the card index`() {
        assertEquals("m0", TranscriptSearchHighlightController.messageId(0))
        assertEquals("m7", TranscriptSearchHighlightController.messageId(7))
        assertEquals(0, TranscriptSearchHighlightController.indexOfMessageId("m0"))
        assertEquals(7, TranscriptSearchHighlightController.indexOfMessageId("m7"))
    }

    @Test
    fun `foreign message id is not addressable as a card index`() {
        assertNull(TranscriptSearchHighlightController.indexOfMessageId("x3"))
        assertNull(TranscriptSearchHighlightController.indexOfMessageId("m"))
        assertNull(TranscriptSearchHighlightController.indexOfMessageId("m-1"))
        assertNull(TranscriptSearchHighlightController.indexOfMessageId(""))
    }

    // --- поиск совпадений: case-insensitive, ordinal внутри сообщения ---

    @Test
    fun `matching is case insensitive and anchors the ordinal inside the message`() {
        val matches = matchesFor(listOf("Марс летит", "марс и МАРС"), "марс")
        assertEquals(3, matches.size)
        // Первое сообщение: одно вхождение, ordinal 0, якорь на карточку 0.
        assertEquals("m0", matches[0].messageId)
        assertEquals(0, matches[0].ordinalInMessage)
        // Второе сообщение: два вхождения — отдельные шаги, друг друга не съедают.
        assertEquals("m1", matches[1].messageId)
        assertEquals(0, matches[1].ordinalInMessage)
        assertEquals("m1", matches[2].messageId)
        assertEquals(1, matches[2].ordinalInMessage)
    }

    @Test
    fun `short query finds nothing`() {
        assertEquals(0, matchesFor(listOf("марс"), "м").size)
    }

    @Test
    fun `query without matches returns an empty list`() {
        assertEquals(0, matchesFor(listOf("марс", "квиз"), "юпитер").size)
    }

    // --- позиция: сброс на новый запрос / восстановление по якорю ---

    @Test
    fun `new query resets to the first match`() {
        val matches = matchesFor(listOf("марс", "марс"), "марс")
        val position = TranscriptSearchHighlightController.resolvePosition(
            matches = matches,
            anchor = match("m1", 0),
            currentPosition = 1,
            resetPosition = true,
        )
        assertEquals(0, position)
    }

    @Test
    fun `recalculation without reset restores the position by anchor`() {
        // Стрим дописал текст В НАЧАЛО списка: индексы совпадений сместились,
        // но якорь (messageId + ordinalInMessage) тот же — совпадение не теряется.
        val anchor = match("m1", 0)
        val shifted = matchesFor(listOf("новое сообщение", "марс", "марс"), "марс")
        val position = TranscriptSearchHighlightController.resolvePosition(
            matches = shifted,
            anchor = anchor,
            currentPosition = 0,
            resetPosition = false,
        )
        assertEquals(0, position)
        assertEquals("m1", shifted[position].messageId)
    }

    @Test
    fun `recalculation without reset falls back to the previous position when the anchor is gone`() {
        // Текущее совпадение исчезло из стрима: якоря нет — остаёмся на прежней
        // позиции, зажатой в границы нового списка.
        val matches = matchesFor(listOf("марс", "марс"), "марс")
        val position = TranscriptSearchHighlightController.resolvePosition(
            matches = matches,
            anchor = match("m99", 0),
            currentPosition = 5,
            resetPosition = false,
        )
        assertEquals(1, position)
    }

    @Test
    fun `no matches at all yields position minus one`() {
        assertEquals(
            -1,
            TranscriptSearchHighlightController.resolvePosition(
                matches = emptyList(),
                anchor = match("m0", 0),
                currentPosition = 3,
                resetPosition = false,
            )
        )
    }

    @Test
    fun `position of an absent anchor is minus one`() {
        val matches = matchesFor(listOf("марс"), "марс")
        assertEquals(-1, MessageSearchPolicy.positionOf(matches, match("m9", 0)))
        assertNull(MessageSearchPolicy.anchorAt(matches, -1))
    }

    // --- кольцевая навигация ---

    @Test
    fun `navigation wraps around in both directions`() {
        val total = 3
        assertEquals(1, MessageSearchPolicy.navigate(total, 0, forward = true))
        assertEquals(0, MessageSearchPolicy.navigate(total, 2, forward = true))
        assertEquals(2, MessageSearchPolicy.navigate(total, 0, forward = false))
        assertEquals(1, MessageSearchPolicy.navigate(total, 2, forward = false))
    }

    @Test
    fun `navigation from uninitialised position starts at the first match`() {
        assertEquals(0, MessageSearchPolicy.navigate(3, -1, forward = true))
        assertEquals(0, MessageSearchPolicy.navigate(3, -1, forward = false))
    }

    @Test
    fun `navigation without matches is a no-op`() {
        assertEquals(-1, MessageSearchPolicy.navigate(0, -1, forward = true))
    }

    // --- счётчик ---

    @Test
    fun `counter labels the position one-based`() {
        assertEquals("1 из 3", MessageSearchPolicy.counterLabel("марс", 0, 3))
        assertEquals("3 из 3", MessageSearchPolicy.counterLabel("марс", 2, 3))
    }

    @Test
    fun `counter is empty for a short query and reports the no-match label otherwise`() {
        assertEquals("", MessageSearchPolicy.counterLabel("м", 0, 0))
        assertEquals(
            MessageSearchPolicy.NO_MATCHES_LABEL,
            MessageSearchPolicy.counterLabel("марс", -1, 0),
        )
    }

    // --- цвет подсветки берётся из палитры ---

    @Test
    fun `highlight colour is the muted palette warning colour`() {
        assertEquals(
            MarsGradients.withAlpha(MarsGradients.STATUS_WARNING, MarsGradients.SEARCH_HIGHLIGHT_ALPHA),
            TranscriptSearchHighlightController.HIGHLIGHT_COLOR,
        )
    }
}
