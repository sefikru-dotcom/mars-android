package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройка «перебивать ответ голосом» (L3): дефолт, сохранение и то, что значение реально
 * доходит до [VoiceDialogLoop] — в том числе на лету, без пересоздания цикла.
 *
 * Флаг раньше только читался: writer'а не было, поэтому `barge_in` нельзя было ни выключить,
 * ни включить обратно. Здесь проверяется весь путь настройки: хранилище → [VoiceBargeInSetting] →
 * цикл. Хранилище подменено парой лямбд, потому что `SharedPreferences` на чистой JVM недоступен
 * (инструментального слоя в проекте нет), а поведение дефолта и записи от этого не меняется.
 */
class VoiceBargeInSettingTest {

    private companion object {
        const val ANSWER = "Сегодня в Ставрополе плюс двадцать, ветер слабый"
    }

    /** Фейковое prefs-хранилище: только булевы значения, как у настоящего файла настроек. */
    private class FakePrefs {
        val values = mutableMapOf<String, Boolean>()

        fun setting(): VoiceBargeInSetting = VoiceBargeInSetting(
            read = { key, default -> values[key] ?: default },
            write = { key, value -> values[key] = value },
        )
    }

    private class FakeStt : SpeechToText {
        override val id: String = "fake-asr"
        override var isReady: Boolean = true
        override var events: SpeechToText.Events? = null

        fun speechStarted() = events?.onSpeechStarted()
        fun final(text: String) = events?.onFinal(text)

        override fun startUtterance() = Unit
        override fun acceptPcm(pcm: ByteArray, length: Int) = Unit
        override fun finishUtterance(): String = ""
        override fun cancelUtterance() = Unit
        override fun release() = Unit
    }

    private class Host : VoiceDialogLoop.Listener {
        var stopSpeakingCalls = 0

        override fun onStopSpeaking() {
            stopSpeakingCalls++
        }
    }

    private var clock = 1_000_000L

    /**
     * Цикл в состоянии «Марс говорит» с флагом ровно из настройки — как в ChatInitSection:
     * `bargeInEnabled = setting.isEnabled()`. Маршрут — наушники, чтобы подтверждения
     * перебивания не зависели от эхо-политики динамика.
     */
    private fun speakingLoop(
        setting: VoiceBargeInSetting,
        stt: FakeStt,
    ): Pair<Host, VoiceDialogLoop> {
        val host = Host()
        val loop = VoiceDialogLoop(
            listener = host,
            bargeInEnabled = setting.isEnabled(),
            stt = stt,
            audioRoute = { AudioRoute.HEADPHONES },
            nowMs = { clock },
        )
        loop.start()
        stt.final("какая погода")
        loop.onAnswerStarted(ANSWER)
        clock += 1_000L
        return host to loop
    }

    @Test
    fun `перебивание включено, когда ключа в prefs нет`() {
        val prefs = FakePrefs()

        assertTrue(
            "дефолт обязан остаться включённым: иначе улучшения голосового чата не работают из коробки",
            prefs.setting().isEnabled(),
        )
        assertTrue("чтение не должно писать ключ", prefs.values.isEmpty())
    }

    @Test
    fun `сохранённый false не затирается дефолтом`() {
        val prefs = FakePrefs()
        prefs.setting().setEnabled(false)

        assertEquals(false, prefs.values[KEY_VOICE_BARGE_IN])
        assertFalse(
            "выключенное пользователем перебивание не должно возвращаться к дефолту",
            prefs.setting().isEnabled(),
        )
        // Повторное чтение (новый экран/новый цикл) видит то же значение.
        assertFalse(prefs.setting().isEnabled())
    }

    @Test
    fun `сохранённый true читается обратно`() {
        val prefs = FakePrefs()
        prefs.setting().setEnabled(false)
        prefs.setting().setEnabled(true)

        assertEquals(true, prefs.values[KEY_VOICE_BARGE_IN])
        assertTrue(prefs.setting().isEnabled())
    }

    @Test
    fun `выключенная настройка доходит до цикла - перебивания нет`() {
        val prefs = FakePrefs().apply { setting().setEnabled(false) }
        val stt = FakeStt()
        val (host, loop) = speakingLoop(prefs.setting(), stt)

        stt.speechStarted()
        clock += 400L
        assertFalse("флаг из настройки обязан гасить перебивание", loop.onSpeechProgress(400L))
        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `включённая настройка доходит до цикла - перебивание работает`() {
        val prefs = FakePrefs()
        val stt = FakeStt()
        val (host, loop) = speakingLoop(prefs.setting(), stt)

        stt.speechStarted()
        clock += 400L
        assertTrue(loop.onSpeechProgress(400L))
        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
    }

    @Test
    fun `смена настройки применяется на уже созданный цикл`() {
        val prefs = FakePrefs()
        val stt = FakeStt()
        val (host, loop) = speakingLoop(prefs.setting(), stt)

        // Человек ушёл в настройки и выключил перебивание: возврат в чат перечитывает флаг
        // (ChatActivity.resumeChat) — цикл не пересоздаётся.
        prefs.setting().setEnabled(false)
        loop.bargeInEnabled = prefs.setting().isEnabled()

        stt.speechStarted()
        clock += 400L
        assertFalse(loop.onSpeechProgress(400L))
        assertEquals("выключенный на лету флаг не перебивает", 0, host.stopSpeakingCalls)

        // Включили обратно — тот же цикл снова перебивает.
        prefs.setting().setEnabled(true)
        loop.bargeInEnabled = prefs.setting().isEnabled()

        clock += 400L
        assertTrue(loop.onSpeechProgress(800L))
        assertEquals(1, host.stopSpeakingCalls)
    }
}
