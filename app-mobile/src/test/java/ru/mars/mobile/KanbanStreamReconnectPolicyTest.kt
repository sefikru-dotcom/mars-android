package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Расписание переподключения SSE канбана (долг O2): пауза растёт и упирается в потолок. */
class KanbanStreamReconnectPolicyTest {

    @Test
    fun delayGrowsExponentially() {
        assertEquals(1_000L, KanbanStreamReconnectPolicy.delayMs(0))
        assertEquals(2_000L, KanbanStreamReconnectPolicy.delayMs(1))
        assertEquals(4_000L, KanbanStreamReconnectPolicy.delayMs(2))
        assertEquals(8_000L, KanbanStreamReconnectPolicy.delayMs(3))
    }

    @Test
    fun delayIsCappedAndNeverOverflows() {
        assertEquals(KanbanStreamReconnectPolicy.MAX_DELAY_MS, KanbanStreamReconnectPolicy.delayMs(4))
        // Отрицательный/огромный номер ступени не должен ломать shl.
        assertEquals(1_000L, KanbanStreamReconnectPolicy.delayMs(-3))
        assertEquals(KanbanStreamReconnectPolicy.MAX_DELAY_MS, KanbanStreamReconnectPolicy.delayMs(99))
    }

    @Test
    fun failedStreamAdvancesAttempt() {
        assertEquals(1, KanbanStreamReconnectPolicy.nextAttempt(0, failed = true))
        assertEquals(2, KanbanStreamReconnectPolicy.nextAttempt(1, failed = true))
    }

    @Test
    fun attemptStopsAtCeiling() {
        assertEquals(
            KanbanStreamReconnectPolicy.MAX_ATTEMPT,
            KanbanStreamReconnectPolicy.nextAttempt(KanbanStreamReconnectPolicy.MAX_ATTEMPT, failed = true),
        )
    }

    @Test
    fun cleanCloseResetsBackoff() {
        // Сервер штатно закрыл поток (таймаут): следующее подключение идёт сразу
        // с минимальной паузой, а не ждёт пятнадцать секунд.
        assertEquals(0, KanbanStreamReconnectPolicy.nextAttempt(4, failed = false))
        assertTrue(KanbanStreamReconnectPolicy.delayMs(0) < KanbanStreamReconnectPolicy.MAX_DELAY_MS)
    }
}
