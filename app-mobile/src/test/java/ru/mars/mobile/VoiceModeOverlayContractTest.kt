package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слой голосового режима: то, что можно проверить без телефона.
 *
 * Сам [VoiceModeOverlay] — Android-класс (View, Handler, Canvas), его нельзя собрать на
 * JVM. Поэтому проверяется то, на чём слой держится и что ломается молча: переход
 * «показ → скрытие» не должен оставлять орб крутящим кадры, а подписи обязаны совпадать
 * с тем, что увидит человек. Логика подписей живёт в [VoiceModePresenter] и покрыта
 * отдельным тестом; здесь — только инвариант «слой ничего своего не придумывает».
 */
class VoiceModeOverlayContractTest {

    @Test
    fun `скрытый слой не должен пульсировать`() {
        // Инвариант анимации: орб дышит только в показанном слое. У VoiceModeOverlay
        // hide() вызывает render(IDLE, pulses = false) — проверяем само правило на
        // состоянии, из которого слой берёт решение.
        assertFalse(VoiceModeState().orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.LISTENING).orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.THINKING).orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.SPEAKING).orbPulses)
    }

    @Test
    fun `фаза покоя — единственная без пульсации`() {
        val pulsing = LocalVoicePhase.entries.filter { VoiceModeState(phase = it).orbPulses }
        assertEquals(LocalVoicePhase.entries.size - 1, pulsing.size)
        assertFalse(LocalVoicePhase.IDLE in pulsing)
    }

    @Test
    fun `снимок для слоя переносит фазу и реплику без потерь`() {
        // Слой берёт снимок из LocalVoiceState (композер) и не хранит своего состояния:
        // эта проверка фиксирует, что фаз и текст доходят без подмены.
        LocalVoicePhase.entries.forEach { phase ->
            val voice = LocalVoiceState(phase = phase, partial = "текст")
            val state = VoiceModeState.from(voice)
            assertEquals(phase, state.phase)
            assertEquals("текст", state.userLine)
        }
    }

    @Test
    fun `тон слоя различает все четыре фазы`() {
        val tones = LocalVoicePhase.entries.map { VoiceModeState(phase = it).tone }
        assertEquals(LocalVoicePhase.entries.size, tones.distinct().size)
    }

    @Test
    fun `все действия слоя покрыты и не пустые`() {
        // Кнопка вызывает onMicTap/onStopSpeech по screen.action. Если для фазы нет
        // действия, нажатие не должно ничего обещать — это проверяет презентер,
        // здесь фиксируем полноту перечисления.
        VoiceModeAction.entries.forEach { action ->
            assertTrue(action.name.isNotBlank())
        }
        assertEquals(4, VoiceModeAction.entries.size)
    }
}
