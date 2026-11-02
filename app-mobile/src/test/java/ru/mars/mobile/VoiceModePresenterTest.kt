package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полноэкранный голосовой режим: единственный перевод состояния в то, что видно.
 *
 * Проверяется не разметка (её видно только на телефоне), а подписи и доступность кнопок:
 * именно здесь появляются «мёртвые» кнопки и фаза «Думаю» с надписью «Стоп».
 */
class VoiceModePresenterTest {

    @Test
    fun `в покое предлагают начать`() {
        val screen = VoiceModePresenter.render(VoiceModeState(), transcript = true)
        assertEquals(VoiceModeAction.START, screen.action)
        assertTrue(screen.primaryEnabled)
        assertTrue(screen.primaryLabel.isNotBlank())
    }

    @Test
    fun `во время слушания кнопка завершает реплику`() {
        val screen = VoiceModePresenter.render(
            VoiceModeState(phase = LocalVoicePhase.LISTENING),
            transcript = true,
        )
        assertEquals(VoiceModeAction.FINISH, screen.action)
        assertTrue(screen.primaryEnabled)
    }

    @Test
    fun `во время озвучки кнопка глушит звук`() {
        val screen = VoiceModePresenter.render(
            VoiceModeState(phase = LocalVoicePhase.SPEAKING),
            transcript = true,
        )
        assertEquals(VoiceModeAction.STOP, screen.action)
        assertTrue(screen.primaryEnabled)
    }

    @Test
    fun `в фазе размышления основной кнопки нет`() {
        // Останавливать нечего и начинать нельзя: озвучка ещё не пошла, реплика уже ушла.
        // Кнопка с мёртвым нажатием здесь была бы хуже её отсутствия.
        val screen = VoiceModePresenter.render(
            VoiceModeState(phase = LocalVoicePhase.THINKING),
            transcript = true,
        )
        assertEquals(VoiceModeAction.NONE, screen.action)
        assertFalse(screen.primaryEnabled)
    }

    @Test
    fun `каждая фаза имеет свою подпись и свой тон`() {
        val labels = LocalVoicePhase.entries.map { VoiceModePresenter.phaseLabelFor(it) }
        assertEquals(labels.size, labels.distinct().size)

        val tones = LocalVoicePhase.entries.map {
            VoiceModeState(phase = it).tone
        }
        assertEquals(tones.size, tones.distinct().size)
    }

    @Test
    fun `выключенный транскрипт убирает строки, а не оставляет пустые`() {
        val state = VoiceModeState(
            phase = LocalVoicePhase.LISTENING,
            userLine = "сколько стоит билет",
            assistantLine = "двести рублей",
        )
        val without = VoiceModePresenter.render(state, transcript = false)
        assertNull(without.userLine)
        assertNull(without.assistantLine)

        val with = VoiceModePresenter.render(state, transcript = true)
        assertEquals("сколько стоит билет", with.userLine)
        assertEquals("двести рублей", with.assistantLine)
    }

    @Test
    fun `снимок из состояния композера переносит партиал и подсказку`() {
        val voice = LocalVoiceState(
            phase = LocalVoicePhase.LISTENING,
            partial = "привет марс",
            notice = "не расслышал",
        )
        val state = VoiceModeState.from(voice)
        assertEquals(LocalVoicePhase.LISTENING, state.phase)
        assertEquals("привет марс", state.userLine)
        assertEquals("не расслышал", state.notice)
        assertTrue(state.orbPulses)
    }

    @Test
    fun `в покое орб не дышит`() {
        // Пульсирующий орб при закрытом микрофоне читается как «уже слушаю».
        assertFalse(VoiceModeState().orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.SPEAKING).orbPulses)
    }

    @Test
    fun `повтор предлагается только когда есть что повторять`() {
        val plain = VoiceModePresenter.render(VoiceModeState(), transcript = true)
        assertFalse(plain.showRetry)
        val failed = VoiceModePresenter.render(
            VoiceModeState(phase = LocalVoicePhase.IDLE, canRetry = true),
            transcript = true,
        )
        assertTrue(failed.showRetry)
    }

    @Test
    fun `описание для скринридера не пустое и говорит о действии`() {
        LocalVoicePhase.entries.forEach { phase ->
            val screen = VoiceModePresenter.render(VoiceModeState(phase = phase), transcript = true)
            assertTrue(screen.contentDescription.isNotBlank())
        }
    }
}
