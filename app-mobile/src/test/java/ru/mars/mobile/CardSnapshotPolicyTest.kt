package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * Снимки карточек: текст меняется → TEXT_ONLY-путь; окружение (мысли/инструменты/
 * эпоха) → FULL_CARD; отпечатки отличают разные наборы мыслей/инструментов.
 */
class CardSnapshotPolicyTest {

    private fun message(text: String = "привет", role: String = "assistant") =
        ChatMessage(role = role, text = text, timestamp = 5_000L)

    @Test
    fun identicalMessagesProduceEqualSnapshots() {
        val a = CardSnapshotPolicy.of(message(), previousTimestamp = 0L, chromeEpoch = 1L, streamingFlag = false)
        val b = CardSnapshotPolicy.of(message(), previousTimestamp = 0L, chromeEpoch = 1L, streamingFlag = false)
        assertEquals(a, b)
        assertEquals(CardUpdateKind.NONE, IncrementalTranscriptPolicy.classify(a, b))
    }

    @Test
    fun textGrowthClassifiesAsTextOnly() {
        val old = CardSnapshotPolicy.of(message("стрим"), 0L, 1L, true)
        val new = CardSnapshotPolicy.of(message("стрим продолжается"), 0L, 1L, true)
        assertEquals(CardUpdateKind.TEXT_ONLY, IncrementalTranscriptPolicy.classify(old, new))
        assertTrue(old.sameChrome(new))
    }

    @Test
    fun userRoleDiffersFromAssistant() {
        val a = CardSnapshotPolicy.of(message(role = "user"), 0L, 1L, false)
        val b = CardSnapshotPolicy.of(message(role = "assistant"), 0L, 1L, false)
        assertNotEquals(a, b)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(a, b))
    }

    @Test
    fun sameTextDifferentLengthNeverCollides() {
        // hashCode строк может совпасть; textLength страхует от ложного NONE.
        val a = CardSnapshotPolicy.of(message("abcd"), 0L, 1L, false)
        val b = a.copy(textLength = 5)
        assertNotEquals(a, b)
    }

    @Test
    fun thoughtsFingerprintDistinguishesContent() {
        val empty = CardSnapshotPolicy.thoughtsFingerprint(emptyList())
        val one = CardSnapshotPolicy.thoughtsFingerprint(listOf("думаю"))
        val two = CardSnapshotPolicy.thoughtsFingerprint(listOf("думаю", "ещё"))
        val reordered = CardSnapshotPolicy.thoughtsFingerprint(listOf("ещё", "думаю"))
        assertNotEquals(empty, one)
        assertNotEquals(one, two)
        assertNotEquals(two, reordered)
        // Пустой список не сливается с одним пустым элементом (затравка SEEDED_PRIME).
        assertNotEquals(empty, CardSnapshotPolicy.thoughtsFingerprint(listOf("")))
    }

    @Test
    fun toolsFingerprintDistinguishesResults() {
        val base = ToolExecution(id = "t1", name = "terminal", arguments = "ls")
        val withResult = base.copy(result = "ok")
        val expanded = base.copy(expanded = true)
        assertNotEquals(CardSnapshotPolicy.toolsFingerprint(listOf(base)), CardSnapshotPolicy.toolsFingerprint(listOf(withResult)))
        assertNotEquals(CardSnapshotPolicy.toolsFingerprint(listOf(base)), CardSnapshotPolicy.toolsFingerprint(listOf(expanded)))
    }

    @Test
    fun daySeparatorFollowsMessageTimePolicy() {
        // Первая известная метка → разделитель; та же сутки следом → без разделителя.
        val first = CardSnapshotPolicy.of(message(), previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP, chromeEpoch = 1L, streamingFlag = false)
        val sameDay = CardSnapshotPolicy.of(message(), previousTimestamp = 5_000L, chromeEpoch = 1L, streamingFlag = false)
        assertTrue("первое сообщение получает разделитель", first.showDaySeparator)
        assertFalse("в пределах суток разделителя нет", sameDay.showDaySeparator)
    }
}
