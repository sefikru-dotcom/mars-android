package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ErrorOrigin

/**
 * Регрессия по блокеру «409 unsupported_voice_mode_backend не распознаётся».
 *
 * Что было сломано (HEAD c666a04): `describeHttpError` собирал строку из ПЕРВОГО непустого поля
 * тела — `error`, а `code` подставлял только если `error` пуст. Реальный сервер отвечает ДВУМЯ
 * полями:
 *
 * ```
 * HTTP 409 {"error": "voice_mode is not supported by the runner backend.",
 *           "code": "unsupported_voice_mode_backend"}
 * ```
 *
 * поэтому в строке ошибки оказывался человеческий текст, `VoiceModeRejectionPolicy` не находил
 * литерал кода и отказ уходил в общий путь «сообщение не дошло»: ход возвращался в офлайн-очередь
 * и повторялся с `voice_mode`, который сервер отвергает детерминированно.
 *
 * Фикс — структурированное поле [ChatEvent.Error.errorCode] по всему пути (тело → событие →
 * политика). Классификация больше не зависит от формулировки человека-читаемого текста, а обычные
 * HTTP-ошибки (500, 409 «поток занят», 401) идут прежним путём.
 */
class VoiceModeRejectionStructuredTest {

    private companion object {
        /** Ровно то, что отдаёт сервер: человеческий текст и машинный код в одном теле. */
        const val SERVER_HUMAN_TEXT = "voice_mode is not supported by the runner backend."

        /** Строка, которую видит человек в транскрипте до подмены на NOTICE. */
        const val WIRE_MESSAGE = "HTTP 409: $SERVER_HUMAN_TEXT"
    }

    private val dispatcher = ChatTurnDispatcher()

    private fun error(
        message: String,
        code: String? = null,
        origin: ErrorOrigin = ErrorOrigin.TRANSPORT,
    ) = ChatEvent.Error(message = message, origin = origin, errorCode = code)

    // --- 1. отказ распознаётся по коду ---------------------------------------------------------

    @Test
    fun `реальный отказ сервера не уходит в очередь на повтор`() {
        val tally = ChatTurnDispatcher.TurnTally()

        val decision = dispatcher.onFrame(
            error(WIRE_MESSAGE, VoiceModeRejectionPolicy.CODE),
            tally,
            transportShaped = false,
        )

        assertEquals(ChatTurnDispatcher.FrameDecision.REJECT_VOICE_MODE, decision)
        assertTrue(tally.voiceModeRejected)
        assertFalse("отказ бэкенда — не «сообщение не дошло»", tally.turnFailed)
        assertEquals(ChatTurnDispatcher.QueueOutcome.REJECTED, dispatcher.queueOutcome(tally))
        assertFalse(dispatcher.shouldRetryQueued(tally))
        assertFalse(dispatcher.shouldConfirmQueued(tally))
        assertEquals(
            "переподключаться некуда: ход не создан",
            ChatTurnDispatcher.StreamOutcome.COMPLETE,
            dispatcher.resolveAfterStream(tally, streamId = null, recoveryAvailable = true),
        )
    }

    @Test
    fun `классификация не зависит от человеческой формулировки`() {
        val tally = ChatTurnDispatcher.TurnTally()

        // Тот же код, но текст сервера переписан (перевод, смена формулировки, локализация).
        val decision = dispatcher.onFrame(
            error("HTTP 409: этот бэкенд не умеет голосовой режим", VoiceModeRejectionPolicy.CODE),
            tally,
            transportShaped = false,
        )

        assertEquals(ChatTurnDispatcher.FrameDecision.REJECT_VOICE_MODE, decision)
        assertEquals(ChatTurnDispatcher.QueueOutcome.REJECTED, dispatcher.queueOutcome(tally))
    }

    @Test
    fun `код важнее текста и в политике`() {
        val structured = error(WIRE_MESSAGE, VoiceModeRejectionPolicy.CODE)
        assertTrue(VoiceModeRejectionPolicy.isVoiceModeRejected(structured))
        assertFalse(VoiceModeRejectionPolicy.isRetryable(structured))
    }

    // --- 2. прежние пути не сломаны ------------------------------------------------------------

    @Test
    fun `старый контракт с кодом в error тоже распознаётся`() {
        val tally = ChatTurnDispatcher.TurnTally()

        // Тело без `code`: текст ошибки — сам код (обратная совместимость).
        val decision = dispatcher.onFrame(
            error("HTTP 409: unsupported_voice_mode_backend"),
            tally,
            transportShaped = false,
        )

        assertEquals(ChatTurnDispatcher.FrameDecision.REJECT_VOICE_MODE, decision)
    }

    @Test
    fun `обычная серверная ошибка остаётся обычной ошибкой`() {
        val tally = ChatTurnDispatcher.TurnTally()

        val decision = dispatcher.onFrame(error("HTTP 500: internal error"), tally, transportShaped = false)

        assertEquals(ChatTurnDispatcher.FrameDecision.APPLY, decision)
        assertFalse(tally.voiceModeRejected)
        assertTrue(tally.turnFailed)
        assertEquals(ChatTurnDispatcher.QueueOutcome.RETRY, dispatcher.queueOutcome(tally))
        assertTrue(dispatcher.shouldRetryQueued(tally))
    }

    @Test
    fun `другой 409 не считается отказом голосового режима`() {
        val tally = ChatTurnDispatcher.TurnTally()

        val decision = dispatcher.onFrame(
            error("HTTP 409: stream already active"),
            tally,
            transportShaped = false,
        )

        assertEquals(ChatTurnDispatcher.FrameDecision.APPLY, decision)
        assertFalse(tally.voiceModeRejected)
        assertEquals(ChatTurnDispatcher.QueueOutcome.RETRY, dispatcher.queueOutcome(tally))
    }

    @Test
    fun `отказ авторизации по-прежнему ведёт на реавторизацию`() {
        val tally = ChatTurnDispatcher.TurnTally()

        dispatcher.onFrame(error("HTTP 401: unauthorized"), tally, transportShaped = false)

        assertEquals(
            ChatTurnDispatcher.StreamOutcome.REAUTH,
            dispatcher.resolveAfterStream(tally, streamId = null, recoveryAvailable = true),
        )
        assertEquals(ChatTurnDispatcher.QueueOutcome.NONE, dispatcher.queueOutcome(tally))
    }

    @Test
    fun `отказ бэкенда после доставки всё равно не ретраится`() {
        val tally = ChatTurnDispatcher.TurnTally()
        // Ход уже доставлен (был кадр до ошибки), затем пришёл отказ контракта.
        dispatcher.onFrame(ChatEvent.Chunk("начало"), tally, transportShaped = false)

        val decision = dispatcher.onFrame(
            error(WIRE_MESSAGE, VoiceModeRejectionPolicy.CODE),
            tally,
            transportShaped = true,
        )

        assertEquals(ChatTurnDispatcher.FrameDecision.REJECT_VOICE_MODE, decision)
        assertEquals(ChatTurnDispatcher.QueueOutcome.REJECTED, dispatcher.queueOutcome(tally))
        assertFalse(dispatcher.shouldRetryQueued(tally))
    }
}
