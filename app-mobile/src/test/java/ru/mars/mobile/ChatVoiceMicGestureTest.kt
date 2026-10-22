package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Жесты микрофона: тап = звук в режиме диалога, удержание = выход из режима в любой фазе.
 *
 * Два регресса, которые этот тест закрывает:
 * - тап в активном диалоге пытался открыть ВТОРУЮ запись (микрофон занят захватом → «Микрофон
 *   недоступен»), а звук продолжал играть;
 * - жест выключения режима терялся, если цикл был не в LISTENING, — человек оставался в режиме
 *   без выхода (кнопка красная, микрофон слушает, выключить нечем).
 */
class ChatVoiceMicGestureTest {

    private val policy = VoiceMicGesturePolicy()

    @Test
    fun `tap outside the dialog starts manual input`() {
        assertEquals(VoiceMicTapAction.START_MANUAL_INPUT, policy.onTap(dialogActive = false))
    }

    @Test
    fun `tap while recording stops the recording`() {
        assertEquals(
            VoiceMicTapAction.STOP_MANUAL_INPUT,
            policy.onTap(dialogActive = false, manualRecording = true),
        )
    }

    @Test
    fun `tap during transcription is ignored in every mode`() {
        // Одно нажатие не должно запускать второе распознавание поверх первого.
        assertEquals(VoiceMicTapAction.IGNORE, policy.onTap(dialogActive = false, transcribing = true))
        assertEquals(
            VoiceMicTapAction.IGNORE,
            policy.onTap(dialogActive = true, speaking = true, transcribing = true),
        )
    }

    @Test
    fun `tap inside the dialog stops the sound first`() {
        // Пользователь просил: тап остановки прерывает звук надёжно.
        assertEquals(
            VoiceMicTapAction.STOP_SPEECH,
            policy.onTap(dialogActive = true, speaking = true),
        )
        // Даже если ручная запись формально ещё «открыта» — звук важнее.
        assertEquals(
            VoiceMicTapAction.STOP_SPEECH,
            policy.onTap(dialogActive = true, speaking = true, manualRecording = true),
        )
    }

    @Test
    fun `tap inside the dialog with no sound closes the utterance and never opens a second mic`() {
        val action = policy.onTap(dialogActive = true, speaking = false)
        assertEquals(VoiceMicTapAction.STOP_UTTERANCE, action)
        // Никакого START_MANUAL_INPUT в режиме: второй микрофон физически не открыть.
        assertEquals(false, action == VoiceMicTapAction.START_MANUAL_INPUT)
    }

    @Test
    fun `hold outside the dialog enables it`() {
        assertEquals(
            VoiceMicHoldAction.ENABLE_DIALOG,
            policy.onHold(dialogActive = false, permissionGranted = true),
        )
    }

    @Test
    fun `hold asks for the permission once when it is missing`() {
        assertEquals(
            VoiceMicHoldAction.ENABLE_DIALOG_AFTER_PERMISSION,
            policy.onHold(dialogActive = false, permissionGranted = false),
        )
    }

    @Test
    fun `hold while recording does not open a second microphone`() {
        assertEquals(
            VoiceMicHoldAction.IGNORE,
            policy.onHold(dialogActive = false, permissionGranted = true, manualRecording = true),
        )
    }

    @Test
    fun `hold always disables the dialog - every phase, every other flag`() {
        // Единственный выход из режима не должен зависеть от того, что цикл делает сейчас.
        val combinations = listOf(
            Triple(true, true, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(true, true, true),
            Triple(false, false, true),
        )
        combinations.forEach { (permission, recording, _) ->
            assertEquals(
                "выключение потеряно при permission=$permission recording=$recording",
                VoiceMicHoldAction.DISABLE_DIALOG,
                policy.onHold(
                    dialogActive = true,
                    permissionGranted = permission,
                    manualRecording = recording,
                ),
            )
        }
    }

    @Test
    fun `hold on an active dialog is never ignored`() {
        // Прямая защита от «застряли в режиме»: при dialogActive=true IGNORE невозможен.
        listOf(true, false).forEach { permission ->
            listOf(true, false).forEach { recording ->
                assertEquals(
                    VoiceMicHoldAction.DISABLE_DIALOG,
                    policy.onHold(true, permission, recording),
                )
            }
        }
    }
}
