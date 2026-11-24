package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конец реплики офлайн-синтеза: готовность движка и конец речи — РАЗНЫЕ вещи.
 *
 * Это регрессия на главный баг Волны 19. `OfflineTtsBackend.isReady()` возвращает `true` всё
 * время, пока движок поднят (то есть и во время речи), а `OfflineTtsSpeechEngine.watchSpeech`
 * принимал его за «договорил». Итог на телефоне: через 100 мс после первого слова движок объявлял
 * тишину, хост открывал микрофон, и Марс записывал и отправлял в чат собственный ответ.
 *
 * Проверяется чистая функция [OfflineTtsSpeechWatch.isSpeechRunning] — ровно то решение, которое
 * принимает опрос, без `Handler` и без нативного синтеза.
 */
class OfflineTtsSpeechWatchTest {

    @Test
    fun `готовый движок во время речи не считается концом реплики`() {
        // Самый важный случай: isReady() == true, но реплика ещё звучит.
        assertTrue(
            "готовность движка не означает тишину",
            OfflineTtsSpeechWatch.isSpeechRunning(backendSpeaking = true, ready = true),
        )
    }

    @Test
    fun `после последнего сэмпла реплика заканчивается`() {
        // Бэкенд снимает активную реплику только после того, как доигран хвост AudioTrack.
        assertFalse(
            OfflineTtsSpeechWatch.isSpeechRunning(backendSpeaking = false, ready = true),
        )
    }

    @Test
    fun `освобождённый бэкенд не держит фазу говорящего`() {
        // release()/stop() снимают реплику сразу, а isReady() становится false: опрос обязан
        // завершиться, иначе кнопка навсегда осталась бы в «Говорю».
        assertFalse(
            OfflineTtsSpeechWatch.isSpeechRunning(backendSpeaking = true, ready = false),
        )
    }

    @Test
    fun `порт без сигнала активности сохраняет прежнее поведение`() {
        // Тестовые фейки и другие реализации OfflineTtsPort сигнала не дают: для них правда о
        // конце речи — только isReady(), и менять её вслепую нельзя.
        assertFalse(OfflineTtsSpeechWatch.isSpeechRunning(backendSpeaking = null, ready = true))
        assertTrue(OfflineTtsSpeechWatch.isSpeechRunning(backendSpeaking = null, ready = false))
    }
}
