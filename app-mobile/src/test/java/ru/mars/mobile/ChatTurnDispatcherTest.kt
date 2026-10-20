package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent

/**
 * Переходы [ChatTurnDispatcher]: маршрут «отправить», защита от повторной отправки,
 * учёт итогов стрима и выбор recovery/re-auth/завершение.
 */
class ChatTurnDispatcherTest {

    private val dispatcher = ChatTurnDispatcher()

    private fun input(
        readOnly: Boolean = false,
        typing: Boolean = false,
        goal: Boolean = false,
    ) = ChatTurnDispatcher.SendInput(readOnly = readOnly, typing = typing, goalCommand = goal)

    // --- Маршрут нажатия «отправить» -------------------------------------------------------

    @Test
    fun `read-only session blocks send before anything else`() {
        assertEquals(
            ChatTurnDispatcher.Route.BLOCKED_READ_ONLY,
            dispatcher.route(input(readOnly = true, typing = true, goal = true))
        )
    }

    @Test
    fun `active turn turns the tap into steer - no second message in one turn`() {
        // Это и есть дедупликация повторной отправки: пока ход TYPING, повторный тап
        // не создаёт второй POST, а уходит в steer.
        assertEquals(ChatTurnDispatcher.Route.STEER, dispatcher.route(input(typing = true)))
        assertEquals(ChatTurnDispatcher.Route.STEER, dispatcher.route(input(typing = true, goal = true)))
    }

    @Test
    fun `goal command is handled locally instead of the model`() {
        assertEquals(ChatTurnDispatcher.Route.GOAL_COMMAND, dispatcher.route(input(goal = true)))
    }

    @Test
    fun `plain text goes to the normal send path`() {
        assertEquals(ChatTurnDispatcher.Route.SEND, dispatcher.route(input()))
    }

    // --- Итоги кадров стрима ---------------------------------------------------------------

    @Test
    fun `any non-error frame proves delivery and is applied`() {
        val tally = ChatTurnDispatcher.TurnTally()
        assertEquals(
            ChatTurnDispatcher.FrameDecision.APPLY,
            dispatcher.onFrame(ChatEvent.Chunk("привет"), tally, transportShaped = false)
        )
        assertTrue(tally.delivered)
        assertFalse(tally.sawDone)
        assertNull(tally.suppressedTransportError)
    }

    @Test
    fun `done frame is terminal and marked as seen`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Done, tally, transportShaped = false)
        assertTrue(tally.sawDone)
        assertTrue(tally.delivered)
    }

    @Test
    fun `error before delivery fails the turn and is applied to the transcript`() {
        val tally = ChatTurnDispatcher.TurnTally()
        val decision = dispatcher.onFrame(ChatEvent.Error("HTTP 500"), tally, transportShaped = true)
        assertEquals(ChatTurnDispatcher.FrameDecision.APPLY, decision)
        assertTrue(tally.turnFailed)
        assertTrue(tally.sawAppError)
        assertFalse(tally.delivered)
        // Сообщение не дошло — очередь вернёт его на повтор.
        assertTrue(dispatcher.shouldRetryQueued(tally))
        assertFalse(dispatcher.shouldConfirmQueued(tally))
    }

    @Test
    fun `transport drop after delivery is suppressed and kept for recovery`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        val decision = dispatcher.onFrame(ChatEvent.Error("read timed out"), tally, transportShaped = true)
        assertEquals(ChatTurnDispatcher.FrameDecision.SUPPRESS_TRANSPORT, decision)
        assertNotNull(tally.suppressedTransportError)
        // Подавленный обрыв не считается серверной ошибкой хода.
        assertFalse(tally.sawAppError)
        // Доставленное сообщение не дублируется в очередь.
        assertFalse(dispatcher.shouldRetryQueued(tally))
    }

    @Test
    fun `credential rejection is never suppressed as a transport drop`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        val decision = dispatcher.onFrame(
            ChatEvent.Error("HTTP 401: unauthorized"),
            tally,
            transportShaped = true,
        )
        assertEquals(ChatTurnDispatcher.FrameDecision.APPLY, decision)
        assertTrue(tally.credentialRejected)
        assertNull(tally.suppressedTransportError)
        assertFalse(dispatcher.shouldRetryQueued(tally))
    }

    // --- Решение после закрытия стрима ------------------------------------------------------

    @Test
    fun `credential rejection routes to re-auth`() {
        val tally = ChatTurnDispatcher.TurnTally()
        tally.credentialRejected = true
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.REAUTH,
            dispatcher.resolveAfterStream(tally, streamId = "s1", recoveryAvailable = true)
        )
    }

    @Test
    fun `delivered turn without done recovers by last event id`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        dispatcher.onFrame(ChatEvent.Error("read timed out"), tally, transportShaped = true)
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.RECOVER,
            dispatcher.resolveAfterStream(tally, streamId = "stream-1", recoveryAvailable = true)
        )
    }

    @Test
    fun `no stream id means no attach - turn completes locally`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.COMPLETE,
            dispatcher.resolveAfterStream(tally, streamId = null, recoveryAvailable = true)
        )
    }

    @Test
    fun `terminal app error is not replayed by recovery`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        dispatcher.onFrame(ChatEvent.Error("tool crashed"), tally, transportShaped = false)
        assertTrue(tally.appErrorTerminal)
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.COMPLETE,
            dispatcher.resolveAfterStream(tally, streamId = "stream-1", recoveryAvailable = true)
        )
    }

    @Test
    fun `done turn completes and is not recovered`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Done, tally, transportShaped = false)
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.COMPLETE,
            dispatcher.resolveAfterStream(tally, streamId = "stream-1", recoveryAvailable = true)
        )
        assertTrue(dispatcher.shouldConfirmQueued(tally))
    }

    @Test
    fun `recovery without controller falls back to local completion`() {
        val tally = ChatTurnDispatcher.TurnTally()
        dispatcher.onFrame(ChatEvent.Chunk("токен"), tally, transportShaped = false)
        assertEquals(
            ChatTurnDispatcher.StreamOutcome.COMPLETE,
            dispatcher.resolveAfterStream(tally, streamId = "stream-1", recoveryAvailable = false)
        )
    }
}
