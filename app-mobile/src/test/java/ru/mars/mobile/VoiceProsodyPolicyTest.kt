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
    fun `список голосов непустой и первый — основной`() {
        val options = VoiceProsodyPolicy.options()
        assertTrue(options.isNotEmpty())
        // Порядок из ServerTtsVoices.VOICE_DIALOG: Алиса (PlusVibe) основная, дальше
        // бесплатные edge-голоса и локальный резерв. id — это id ПРОФИЛЯ
        // (plusvibe-alice), а не имя движка: по нему хранится выбор.
        assertEquals(ServerTtsVoices.PLUSVIBE_ALICE.id, options.first().id)
        assertEquals("Алиса (PlusVibe)", options.first().label)
    }

    @Test
    fun `восемь голосов различаются подписью, а не флагом natural`() {
        val options = VoiceProsodyPolicy.options()

        // Все сетевые профили помечены natural (все — нейро-голоса), поэтому подпись обязана
        // различаться по id: иначе «Дмитрий» показался бы человеку Светланой.
        assertEquals(
            listOf(
                ServerTtsVoices.PLUSVIBE_ALICE.id,
                ServerTtsVoices.NATURAL_SVETLANA.id,
                ServerTtsVoices.NATURAL_DMITRY.id,
                ServerTtsVoices.NATURAL_AVA.id,
                ServerTtsVoices.PLUSVIBE_ONYX.id,
                ServerTtsVoices.PLUSVIBE_LARISA.id,
                ServerTtsVoices.PLUSVIBE_KATE.id,
                ServerTtsVoices.LOCAL_IRINA.id,
            ),
            options.map { it.id },
        )
        assertEquals("Алиса (PlusVibe)", options[0].label)
        assertEquals(
            "Основной живой голос: ElevenLabs turbo, платный (≈5 ₽/мин)",
            options[0].hint,
        )
        assertEquals("Светлана", options[1].label)
        assertEquals("Женский нейросетевой голос, живая интонация", options[1].hint)
        assertEquals("Дмитрий", options[2].label)
        assertEquals("Мужской нейросетевой голос, глубокий тембр", options[2].hint)
        assertEquals("Ава (ChatGPT)", options[3].label)
        assertEquals("Тёплый мультиязычный нейро-голос (RU / EN)", options[3].hint)
        assertEquals("Оникс (PlusVibe)", options[4].label)
        assertEquals("Дешёвый облачный голос gpt-4o-mini-tts (≈0,06 ₽/мин)", options[4].hint)
        // Русские голоса из библиотеки ElevenLabs: подпись обязана называть и цену, и резерв.
        assertEquals("Лариса (PlusVibe)", options[5].label)
        assertTrue(options[5].hint, options[5].hint.contains("платный"))
        assertTrue(options[5].hint, options[5].hint.contains("Дмитрий"))
        assertEquals("Катя (PlusVibe)", options[6].label)
        assertTrue(options[6].hint, options[6].hint.contains("платный"))
        assertEquals("Ирина (офлайн)", options[7].label)
        assertEquals("Локальный голос на телефоне, работает без сети", options[7].hint)
        // Ни одной повторяющейся подписи: владелец жаловался, что «Светлана» и «как в вебе» —
        // один и тот же голос двумя строками.
        assertEquals(options.size, options.map { it.label }.distinct().size)
    }

    @Test
    fun `русский голос выбирается как остальные и доезжает до профиля`() {
        // Требование владельца: в локальном чате должен быть РУССКИЙ голос, а не британская
        // Алиса. Выбор обязан работать тем же путём, что и остальные: id профиля уезжает в
        // настройки, а клиент синтеза ставит профиль первым.
        val chosen = VoiceProsodyPolicy.selected(
            VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_LARISA.id),
        )

        assertEquals(ServerTtsVoices.PLUSVIBE_LARISA.id, chosen?.id)
        assertEquals(ServerTtsVoices.PLUSVIBE_LARISA.id, chosen?.prosody?.voiceId)
        assertEquals(
            "Лариса (PlusVibe)",
            VoiceProsodyPolicy.voiceLabel(VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_LARISA.id)),
        )
        // И это НЕ дефолт: по умолчанию по-прежнему звучит основной голос приложения.
        assertEquals(ServerTtsVoices.PLUSVIBE_ALICE.id, VoiceProsodyPolicy.selected(VoiceProsody())?.id)
    }

    @Test
    fun `выбор Дмитрия подсвечивается и доезжает до профиля`() {
        val chosen = VoiceProsodyPolicy.selected(
            VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id),
        )

        assertEquals("Дмитрий", chosen?.label)
        // voiceId строки списка — id профиля: по нему клиент синтеза ставит голос первым.
        assertEquals(ServerTtsVoices.NATURAL_DMITRY.id, chosen?.prosody?.voiceId)
        assertEquals(
            "Дмитрий",
            VoiceProsodyPolicy.voiceLabel(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_DMITRY.id)),
        )
    }

    @Test
    fun `выбор голоса по неизвестному id даёт null, а не первый из списка`() {
        assertNull(VoiceProsodyPolicy.selected(VoiceProsody(voiceId = "удалённый-голос")))
        assertEquals(
            ServerTtsVoices.NATURAL_SVETLANA.id,
            VoiceProsodyPolicy.selected(
                VoiceProsody(voiceId = ServerTtsVoices.NATURAL_SVETLANA.id),
            )?.id,
        )
    }

    @Test
    fun `невыбранный голос подсвечивает основной, а не пустоту`() {
        // `voiceId == null` («как в вебе») — законное состояние, и подсвечивать нужно тот
        // голос, который реально звучит: сегодня это Алиса (PlusVibe), основной голос
        // приложения. Светлана подсвечивалась бы, только если бы звучала она.
        val selected = VoiceProsodyPolicy.selected(VoiceProsody())

        assertEquals(ServerTtsVoices.DEFAULT_VOICE.id, selected?.id)
        assertEquals(ServerTtsVoices.PLUSVIBE_ALICE.id, selected?.id)
        assertEquals("Алиса (PlusVibe)", selected?.label)
        assertEquals("Алиса (PlusVibe)", VoiceProsodyPolicy.voiceLabel(VoiceProsody()))
    }

    @Test
    fun `выбор Авы, Оникса и Ирины доезжает до профиля`() {
        assertEquals(
            "Ава (ChatGPT)",
            VoiceProsodyPolicy.selected(VoiceProsody(voiceId = ServerTtsVoices.NATURAL_AVA.id))?.label,
        )
        // Платный голос выбирается так же, как остальные: id профиля уезжает в настройки, а
        // клиент синтеза ставит его первым (и уходит в асинхронный media-контракт).
        assertEquals(
            "Оникс (PlusVibe)",
            VoiceProsodyPolicy.selected(
                VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ONYX.id),
            )?.label,
        )
        assertEquals(
            ServerTtsVoices.PLUSVIBE_ONYX.id,
            VoiceProsodyPolicy.selected(
                VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ONYX.id),
            )?.prosody?.voiceId,
        )
        assertEquals(
            "Ирина (офлайн)",
            VoiceProsodyPolicy.selected(VoiceProsody(voiceId = ServerTtsVoices.LOCAL_IRINA.id))?.label,
        )
    }

    @Test
    fun `подпись темпа объясняет, что человек услышит`() {
        assertEquals(VoiceProsody.DEFAULT_SPEED_LABEL, VoiceProsodyPolicy.speedHint(1.0f))
        assertTrue(VoiceProsodyPolicy.speedHint(1.5f).contains("быстрее"))
        assertTrue(VoiceProsodyPolicy.speedHint(0.75f).contains("медленнее"))
    }
}
