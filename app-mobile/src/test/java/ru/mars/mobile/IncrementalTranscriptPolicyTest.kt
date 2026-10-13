package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * План инкрементального рендера: кадр стрима = ровно одна грязная карточка (TEXT_ONLY),
 * контейнер не сносится; FullRebuild только на смену сессии/подрезку/эпоху окружения.
 */
class IncrementalTranscriptPolicyTest {

    private fun snapshot(text: String, epoch: Long = 1L, streaming: Boolean = false) = CardSnapshot(
        chromeEpoch = epoch,
        showDaySeparator = false,
        separatorTimestamp = 0L,
        isTrimAnchor = false,
        trimmedCount = 0,
        isUser = false,
        attachments = emptyList(),
        textLength = text.length,
        textHash = text.hashCode(),
        status = MessageStatus.COMPLETE,
        streamingFlag = streaming,
        thoughtsExpanded = false,
        thoughtsFingerprint = 0L,
        toolsFingerprint = 0L,
        timestamp = 1000L,
    )

    @Test
    fun identicalListsProduceNoChange() {
        val list = listOf(snapshot("a"), snapshot("b"), snapshot("c"))
        assertEquals(TranscriptPlan.NoChange, IncrementalTranscriptPolicy.plan(list, list))
    }

    @Test
    fun bothEmptyProduceNoChange() {
        assertEquals(TranscriptPlan.NoChange, IncrementalTranscriptPolicy.plan(emptyList(), emptyList()))
    }

    @Test
    fun firstRenderIsFullRebuild() {
        assertEquals(TranscriptPlan.FullRebuild, IncrementalTranscriptPolicy.plan(emptyList(), listOf(snapshot("a"))))
    }

    @Test
    fun emptiedListIsFullRebuild() {
        assertEquals(TranscriptPlan.FullRebuild, IncrementalTranscriptPolicy.plan(listOf(snapshot("a")), emptyList()))
    }

    @Test
    fun shrinkIsFullRebuild() {
        val previous = listOf(snapshot("a"), snapshot("b"), snapshot("c"))
        assertEquals(TranscriptPlan.FullRebuild, IncrementalTranscriptPolicy.plan(previous, previous.take(2)))
    }

    @Test
    fun streamFrameTouchesOnlyLastBubble() {
        // Ядро ANR-фикса: рост текста последнего пузыря → Patch ровно с одной TEXT_ONLY-позицией.
        val previous = listOf(snapshot("история 1"), snapshot("история 2"), snapshot("стрим: привет"))
        val current = listOf(snapshot("история 1"), snapshot("история 2"), snapshot("стрим: привет, мир"))
        val plan = IncrementalTranscriptPolicy.plan(previous, current)
        assertTrue("ожидался Patch", plan is TranscriptPlan.Patch)
        plan as TranscriptPlan.Patch
        assertEquals(listOf(CardPatch(2, CardUpdateKind.TEXT_ONLY)), plan.updates)
        assertEquals(3, plan.appendFrom)
    }

    @Test
    fun appendedMessageIsDeliveredInTail() {
        val previous = listOf(snapshot("a"))
        val current = listOf(snapshot("a"), snapshot("b"))
        val plan = IncrementalTranscriptPolicy.plan(previous, current)
        assertTrue(plan is TranscriptPlan.Patch)
        plan as TranscriptPlan.Patch
        assertTrue(plan.updates.isEmpty())
        assertEquals(1, plan.appendFrom)
    }

    @Test
    fun emptyToNonEmptyTextIsFullCard() {
        val old = snapshot("")
        val new = snapshot("первый текст")
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun chromeEpochChangeForcesFullCards() {
        val previous = listOf(snapshot("a", epoch = 1), snapshot("b", epoch = 1))
        val current = listOf(snapshot("a", epoch = 2), snapshot("b", epoch = 2))
        // Смена эпохи (переключатель режима/фолды code) — полный пересбор, не Patch.
        assertEquals(TranscriptPlan.FullRebuild, IncrementalTranscriptPolicy.plan(previous, current))
    }

    @Test
    fun thoughtsArrivalIsFullCardNotTextOnly() {
        val old = snapshot("текст")
        val new = snapshot("текст").copy(thoughtsFingerprint = 999L)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun toolResultArrivalIsFullCard() {
        val old = snapshot("текст")
        val new = snapshot("текст").copy(toolsFingerprint = 777L)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun statusChangeIsFullCard() {
        val old = snapshot("текст")
        val new = snapshot("текст").copy(status = MessageStatus.STREAMING)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun streamingFlagChangeIsFullCard() {
        val old = snapshot("текст")
        val new = snapshot("текст").copy(streamingFlag = true)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun daySeparatorChangeIsFullCard() {
        val old = snapshot("текст")
        val new = snapshot("текст").copy(showDaySeparator = true)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun trimAnchorNeverTakesTextOnlyFastPath() {
        val old = snapshot("текст").copy(isTrimAnchor = true)
        val new = snapshot("другой текст").copy(isTrimAnchor = true)
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(old, new))
    }

    @Test
    fun missingOldCardIsFullCard() {
        assertEquals(CardUpdateKind.FULL_CARD, IncrementalTranscriptPolicy.classify(null, snapshot("a")))
    }

    @Test
    fun wholeSessionSwapIsFullRebuild() {
        val previous = listOf(snapshot("a1"), snapshot("a2"), snapshot("a3"))
        val current = listOf(snapshot("b1"), snapshot("b2"), snapshot("b3"))
        assertEquals(TranscriptPlan.FullRebuild, IncrementalTranscriptPolicy.plan(previous, current))
    }

    @Test
    fun textChangeInMiddleOfHistoryIsPatchedLocally() {
        val previous = listOf(snapshot("a"), snapshot("b"), snapshot("c"))
        val current = listOf(snapshot("a"), snapshot("B"), snapshot("c"))
        val plan = IncrementalTranscriptPolicy.plan(previous, current)
        assertTrue(plan is TranscriptPlan.Patch)
        plan as TranscriptPlan.Patch
        assertEquals(listOf(CardPatch(1, CardUpdateKind.TEXT_ONLY)), plan.updates)
    }
}
