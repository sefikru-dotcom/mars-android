package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * Cap истории и текста пузыря: хвост сохраняется, голова заменяется якорем,
 * повторное применение идемпотентно (якоря не удваиваются).
 */
class TranscriptCapPolicyTest {

    private fun msg(i: Int, text: String = "m$i") =
        ChatMessage(role = if (i % 2 == 0) "user" else "assistant", text = text, timestamp = i.toLong() * 1000L)

    @Test
    fun shortHistoryPassesVerbatim() {
        val history = (1..10).map(::msg)
        assertEquals(history, TranscriptCapPolicy.capMessages(history))
    }

    @Test
    fun oversizedHistoryKeepsTailAndPrependsAnchor() {
        val total = TranscriptCapPolicy.MAX_MESSAGES + 50
        val history = (1..total).map(::msg)
        val capped = TranscriptCapPolicy.capMessages(history)
        assertEquals(TranscriptCapPolicy.MAX_MESSAGES + 1, capped.size)
        // Якорь — первый, счётчик = число скрытых, хвост — последние MAX_MESSAGES сообщений.
        val anchor = capped.first()
        assertTrue("head должен быть якорем", anchor.isTrimAnchor)
        assertEquals(50, anchor.trimmedCount)
        assertEquals(history.takeLast(TranscriptCapPolicy.MAX_MESSAGES), capped.drop(1))
    }

    @Test
    fun recappingIsIdempotentAndAbsorbsPreviousAnchor() {
        val total = TranscriptCapPolicy.MAX_MESSAGES * 2 + 7
        val history = (1..total).map(::msg)
        val once = TranscriptCapPolicy.capMessages(history)
        val twice = TranscriptCapPolicy.capMessages(once)
        // Второй проход не режет дальше и не удваивает якоря.
        assertEquals(once, twice)
        assertEquals(1, twice.count { it.isTrimAnchor })
        assertEquals(once.first().trimmedCount, twice.first().trimmedCount)
    }

    @Test
    fun exactCapIsNotTrimmed() {
        val history = (1..TranscriptCapPolicy.MAX_MESSAGES).map(::msg)
        val capped = TranscriptCapPolicy.capMessages(history)
        assertEquals(history, capped)
        assertTrue(capped.none { it.isTrimAnchor })
    }

    @Test
    fun oversizedBubbleKeepsTailWithAnchorPrefix() {
        val head = "H".repeat(TranscriptCapPolicy.MAX_BUBBLE_CHARS + 5_000)
        val tail = "T".repeat(TranscriptCapPolicy.MAX_BUBBLE_CHARS)
        val capped = TranscriptCapPolicy.capBubbleText(head + tail)
        assertTrue("якорь в голове", capped.startsWith(TranscriptCapPolicy.BUBBLE_TRIM_ANCHOR))
        assertTrue("свежий хвост сохранён", capped.endsWith(tail))
        assertEquals(
            TranscriptCapPolicy.BUBBLE_TRIM_ANCHOR.length + 1 + TranscriptCapPolicy.MAX_BUBBLE_CHARS,
            capped.length
        )
    }

    @Test
    fun capBubbleTextIsIdempotent() {
        val long = "X".repeat(TranscriptCapPolicy.MAX_BUBBLE_CHARS * 2)
        val once = TranscriptCapPolicy.capBubbleText(long)
        assertEquals(once, TranscriptCapPolicy.capBubbleText(once))
    }

    @Test
    fun historyWithOversizedBubbleGetsCappedInsideList() {
        val long = "Y".repeat(TranscriptCapPolicy.MAX_BUBBLE_CHARS + 10)
        val capped = TranscriptCapPolicy.capMessages(listOf(msg(1, long)))
        assertTrue(capped.single().text.length <= TranscriptCapPolicy.MAX_BUBBLE_CHARS + TranscriptCapPolicy.BUBBLE_TRIM_ANCHOR.length + 1)
        assertTrue(capped.single().text.startsWith(TranscriptCapPolicy.BUBBLE_TRIM_ANCHOR))
    }

    @Test
    fun anchorLabelMentionsCount() {
        val label = TranscriptCapPolicy.anchorLabel(42)
        assertTrue(label.contains("42"))
    }
}
