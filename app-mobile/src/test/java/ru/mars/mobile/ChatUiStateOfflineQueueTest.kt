package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты офлайн-очереди в [ChatUiState]: локальное эхо буферизованного
 * сообщения (PENDING без начала хода) и превращение эха в полноценный ход
 * при автоотправке после восстановления связи.
 */
class ChatUiStateOfflineQueueTest {

    private val state = ChatUiState()

    private fun lastUser() = state.messages.last { it.role == "user" }
    private fun lastAssistant() = state.messages.last { it.role == "assistant" }

    // --- beginQueuedEcho ---------------------------------------------------------------------------

    @Test
    fun `queued echo adds pending user bubble without starting a turn`() {
        assertTrue(state.beginQueuedEcho("привет"))
        assertEquals(1, state.messages.size)
        assertEquals("user", lastUser().role)
        assertEquals("привет", lastUser().text)
        assertEquals(MessageStatus.PENDING, lastUser().status)
        // Ход НЕ начинается: ни assistant-пузыря, ни TYPING.
        assertEquals(ChatStatus.IDLE, state.status)
        assertTrue(state.messages.none { it.role == "assistant" })
    }

    @Test
    fun `queued echo rejects blank text`() {
        assertFalse(state.beginQueuedEcho("   "))
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `queued echo rejected while typing`() {
        state.begin("идёт ответ", emptyList())
        assertFalse(state.beginQueuedEcho("ещё одно"))
        assertEquals(2, state.messages.size) // user + streaming assistant, без нового пузыря
    }

    @Test
    fun `several queued echoes keep order`() {
        state.beginQueuedEcho("первое")
        state.beginQueuedEcho("второе")
        assertEquals(listOf("первое", "второе"), state.messages.map { it.text })
        assertTrue(state.messages.all { it.status == MessageStatus.PENDING })
    }

    // --- beginQueuedSend ---------------------------------------------------------------------------

    @Test
    fun `queued send flips pending echo to complete and opens streaming turn`() {
        state.beginQueuedEcho("привет")
        assertTrue(state.beginQueuedSend("привет"))

        assertEquals(ChatStatus.TYPING, state.status)
        // Эхо стало COMPLETE, дубль пузыря не появился.
        assertEquals(2, state.messages.size)
        assertEquals(MessageStatus.COMPLETE, lastUser().status)
        assertEquals(MessageStatus.STREAMING, lastAssistant().status)
    }

    @Test
    fun `queued send without echo adds fresh user bubble`() {
        // Эхо могло не дожить (смерть процесса между буферизацией и отправкой).
        assertTrue(state.beginQueuedSend("потерянное эхо"))
        assertEquals(2, state.messages.size)
        assertEquals(MessageStatus.COMPLETE, lastUser().status)
        assertEquals(MessageStatus.STREAMING, lastAssistant().status)
    }

    @Test
    fun `queued send flips the latest matching echo only`() {
        state.beginQueuedEcho("одинаковый текст")
        state.beginQueuedEcho("одинаковый текст")
        assertTrue(state.beginQueuedSend("одинаковый текст"))

        val pending = state.messages.filter { it.status == MessageStatus.PENDING }
        assertEquals(1, pending.size)
        // Первое эхо осталось PENDING (его отправка ещё впереди), второе — COMPLETE.
        assertEquals(0, state.messages.indexOfFirst { it.status == MessageStatus.PENDING })
    }

    @Test
    fun `queued send does not touch unrelated pending echoes`() {
        state.beginQueuedEcho("другое")
        state.beginQueuedEcho("нужное")
        assertTrue(state.beginQueuedSend("нужное"))
        assertEquals(MessageStatus.PENDING, state.messages[0].status)
        assertEquals(MessageStatus.COMPLETE, state.messages[1].status)
    }

    @Test
    fun `queued send rejected while typing`() {
        state.begin("идёт ответ", emptyList())
        assertFalse(state.beginQueuedSend("в очередь"))
        assertEquals(2, state.messages.size)
    }

    @Test
    fun `queued send rejects blank text`() {
        assertFalse(state.beginQueuedSend(""))
        assertEquals(ChatStatus.IDLE, state.status)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `queued send survives bindSession and complete cycle`() {
        state.beginQueuedEcho("привет")
        state.beginQueuedSend("привет")
        state.complete()
        assertEquals(ChatStatus.RECEIVED, state.status)
        // Полный цикл офлайн-отправки: эхо PENDING → ход → RECEIVED.
        assertEquals(MessageStatus.COMPLETE, lastUser().status)
    }
}
