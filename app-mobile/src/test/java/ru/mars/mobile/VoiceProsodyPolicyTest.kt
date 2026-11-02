package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsVoices

/**
 * Темп речи: три представления одного множителя.
 *
 * Главная проверка — что «дефолт не отправляется»: `null` в [VoiceProsody.serverRate]
 * означает «поле не уходит в тело запроса», и именно на этом ломается тишина в вебе,
 * если приложение начнёт присылать `+0%` каждый раз.
 */
class VoiceProsodyPolicyTest {

    @Test
    fun `обычный темп не отправляет rate`() {
        assertNull(VoiceProsody().serverRate())
        assertNull(VoiceProsody().serverPitch())
        assertTrue(VoiceProsody().isDefault)
    }

    @Test
    fun `ускорение даёт процент для сервера`() {
        assertEquals("+15%", VoiceProsody(speed = 1.15f).serverRate())
        assertEquals("-10%", VoiceProsody(speed = 0.9f).serverRate())
    }

    @Test
    fun `высота даёт герцы для сервера`() {
        assertEquals("+10Hz", VoiceProsody(pitch = 1.1f).serverPitch())
        assertEquals("-5Hz", VoiceProsody(pitch = 0.95f).serverPitch())
    }

    @Test
    fun `множители для локальных движков совпадают с заданным темпом`() {
        val prosody = VoiceProsody(speed = 1.3f, pitch = 1.2f)
        assertEquals(1.3f, prosody.systemSpeechRate(), 0.001f)
        assertEquals(1.3f, prosody.offlineSpeed(), 0.001f)
        assertEquals(1.2f, prosody.systemPitch(), 0.001f)
    }

    @Test
    fun `темп зажимается в границы движка`() {
        assertEquals(VoiceProsody.MAX_SPEED, VoiceProsody.clampSpeed(9f), 0.001f)
        assertEquals(VoiceProsody.MIN_SPEED, VoiceProsody.clampSpeed(0.01f), 0.001f)
        assertEquals(VoiceProsody.MAX_PITCH, VoiceProsody.clampPitch(5f), 0.001f)
        // NaN не должен просочиться в нативный lengthScale: там это мусор, а не ошибка.
        assertEquals(VoiceProsody.DEFAULT_SPEED, VoiceProsody.clampSpeed(Float.NaN), 0.001f)
    }

    @Test
    fun `подпись темпа не показывает дробный мусор`() {
        assertEquals("1.15×", VoiceProsody(speed = 1.15f).speedLabel())
        assertEquals("0.9×", VoiceProsody(speed = 0.9f).speedLabel())
        assertEquals(VoiceProsody.DEFAULT_SPEED_LABEL, VoiceProsody().speedLabel())
        // 1.1499999 из хранилища — всё ещё «1.15×», а не «1.14×».
        assertEquals("1.15×", VoiceProsody(speed = 1.1499f).speedLabel())
    }

    @Test
    fun `шаг по ступеням не перескакивает и не заворачивается`() {
        assertEquals(1.15f, VoiceProsodyPolicy.stepUp(1.0f), 0.001f)
        assertEquals(0.9f, VoiceProsodyPolicy.stepDown(1.0f), 0.001f)
        // Верхняя ступень возвращает себя, а не первую: заворот читался бы как поломка.
        val top = VoiceProsody.STEP_CHOICES.last()
        assertEquals(top, VoiceProsodyPolicy.stepUp(top), 0.001f)
        assertFalse(VoiceProsodyPolicy.canStepUp(top))
        val bottom = VoiceProsody.STEP_CHOICES.first()
        assertEquals(bottom, VoiceProsodyPolicy.stepDown(bottom), 0.001f)
        assertFalse(VoiceProsodyPolicy.canStepDown(bottom))
    }

    @Test
    fun `список голосов непустой и первый — естественный`() {
        val options = VoiceProsodyPolicy.options()
        assertTrue(options.isNotEmpty())
        // Порядок из ServerTtsVoices.VOICE_DIALOG: Svetlana основная, irina резерв.
        // id — это id ПРОФИЛЯ (natural-svetlana), а не имя движка: по нему хранится выбор.
        assertEquals(ServerTtsVoices.NATURAL_SVETLANA.id, options.first().id)
        assertEquals("Светлана", options.first().label)
    }

    @Test
    fun `выбор голоса по неизвестному id даёт null, а не первый из списка`() {
        assertNull(VoiceProsodyPolicy.selected(VoiceProsody(voiceId = "удалённый-голос")))
        assertNull(VoiceProsodyPolicy.selected(VoiceProsody()))
        assertEquals(
            ServerTtsVoices.NATURAL_SVETLANA.id,
            VoiceProsodyPolicy.selected(
                VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id),
            )?.id,
        )
    }

    @Test
    fun `подпись темпа объясняет, что человек услышит`() {
        assertEquals(VoiceProsody.DEFAULT_SPEED_LABEL, VoiceProsodyPolicy.speedHint(1.0f))
        assertTrue(VoiceProsodyPolicy.speedHint(1.5f).contains("быстрее"))
        assertTrue(VoiceProsodyPolicy.speedHint(0.75f).contains("медленнее"))
    }
}
