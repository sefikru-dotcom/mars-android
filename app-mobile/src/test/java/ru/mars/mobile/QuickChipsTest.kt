package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickChipsTest {

    @Test
    fun `all six chips are exposed with the required labels`() {
        assertEquals(
            listOf("Ок", "Продолжай", "Откат", "Поясни", "План", "Статус"),
            QuickChips.ALL.map { it.label }
        )
        assertEquals(
            listOf(
                QuickChipId.OK, QuickChipId.CONTINUE, QuickChipId.ROLLBACK,
                QuickChipId.EXPLAIN, QuickChipId.PLAN, QuickChipId.STATUS,
            ),
            QuickChips.ALL.map { it.id }
        )
        QuickChips.ALL.forEach { assertTrue(it.message.isNotBlank()) }
    }

    @Test
    fun `ribbon is hidden until the assistant has replied`() {
        for (status in ChatStatus.entries) {
            assertEquals(
                emptyList<QuickChip>(),
                QuickChips.visibleChips(status, hasAssistantReply = false, lastTurnFailed = false)
            )
        }
    }

    @Test
    fun `ribbon is hidden while the stream is typing`() {
        val chips = QuickChips.visibleChips(ChatStatus.TYPING, hasAssistantReply = true, lastTurnFailed = false)
        assertEquals(emptyList<QuickChip>(), chips)
    }

    @Test
    fun `full ribbon after a normal received answer`() {
        val chips = QuickChips.visibleChips(ChatStatus.RECEIVED, hasAssistantReply = true, lastTurnFailed = false)
        assertEquals(QuickChips.ALL, chips)
    }

    @Test
    fun `full ribbon in idle with an existing reply`() {
        val chips = QuickChips.visibleChips(ChatStatus.IDLE, hasAssistantReply = true, lastTurnFailed = false)
        assertEquals(QuickChips.ALL, chips)
    }

    @Test
    fun `after a failed turn rollback and explain lead the ribbon`() {
        val chips = QuickChips.visibleChips(ChatStatus.ERROR, hasAssistantReply = true, lastTurnFailed = true)
        assertEquals(listOf("Повторить", "Откат", "Поясни", "Статус", "Продолжай"), chips.map { it.label })

        val afterFailureInIdle = QuickChips.visibleChips(ChatStatus.IDLE, hasAssistantReply = true, lastTurnFailed = true)
        assertEquals(listOf("Повторить", "Откат", "Поясни", "Статус", "Продолжай"), afterFailureInIdle.map { it.label })
    }

    @Test
    fun `chip messages carry the intended one-tap commands`() {
        assertEquals("Ок", QuickChips.OK.message)
        assertEquals("Продолжай", QuickChips.CONTINUE.message)
        assertTrue(QuickChips.ROLLBACK.message.startsWith("Откат"))
        assertTrue(QuickChips.EXPLAIN.message.startsWith("Поясни"))
        assertTrue(QuickChips.PLAN.message.startsWith("Составь пошаговый план"))
        assertTrue(QuickChips.STATUS.message.startsWith("Краткий статус"))
        assertTrue(QuickChips.RETRY.message.startsWith("Повтори"))
    }
}
