package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика основной кнопки ТВ-чата: SEND/STEER/STOP, подписи и возврат
 * недоставленного steer (`pending_steer_leftover`) в поле ввода.
 */
class TvSteerPolicyTest {

    @Test
    fun `action matrix - idle sends, live steers with text and stops without it`() {
        assertEquals(TvSteerPolicy.SendAction.SEND, TvSteerPolicy.actionFor(turnLive = false, inputBlank = true))
        assertEquals(TvSteerPolicy.SendAction.SEND, TvSteerPolicy.actionFor(turnLive = false, inputBlank = false))
        assertEquals(TvSteerPolicy.SendAction.STEER, TvSteerPolicy.actionFor(turnLive = true, inputBlank = false))
        assertEquals(TvSteerPolicy.SendAction.STOP, TvSteerPolicy.actionFor(turnLive = true, inputBlank = true))
    }

    @Test
    fun `button labels follow the action`() {
        assertEquals("Отправить", TvSteerPolicy.buttonLabel(TvSteerPolicy.SendAction.SEND))
        assertEquals("Уточнить", TvSteerPolicy.buttonLabel(TvSteerPolicy.SendAction.STEER))
        assertEquals("Остановить", TvSteerPolicy.buttonLabel(TvSteerPolicy.SendAction.STOP))
    }

    @Test
    fun `stop icon only for the cancel action`() {
        assertTrue(TvSteerPolicy.buttonShowsStopIcon(TvSteerPolicy.SendAction.STOP))
        assertFalse(TvSteerPolicy.buttonShowsStopIcon(TvSteerPolicy.SendAction.STEER))
        assertFalse(TvSteerPolicy.buttonShowsStopIcon(TvSteerPolicy.SendAction.SEND))
    }

    @Test
    fun `leftover restores only into an empty composer`() {
        // Пустое поле: опоздавшая правка возвращается пользователю.
        assertTrue(TvSteerPolicy.canRestoreLeftover("проверь логи", ""))
        assertTrue(TvSteerPolicy.canRestoreLeftover("проверь логи", "   "))
        // Пользователь уже что-то набрал — его текст важнее.
        assertFalse(TvSteerPolicy.canRestoreLeftover("проверь логи", "новый черновик"))
        // Пустой leftover не подставляется.
        assertFalse(TvSteerPolicy.canRestoreLeftover("  ", ""))
    }

    @Test
    fun `status texts are user-visible Russian sentences`() {
        assertTrue(TvSteerPolicy.STATUS_ACCEPTED.isNotBlank())
        assertTrue(TvSteerPolicy.STATUS_REJECTED.contains("сохранён"))
        assertTrue(TvSteerPolicy.LEFTOVER_STATUS.contains("возвращён"))
        assertTrue(TvSteerPolicy.STATUS_NO_SESSION.isNotBlank())
    }
}
