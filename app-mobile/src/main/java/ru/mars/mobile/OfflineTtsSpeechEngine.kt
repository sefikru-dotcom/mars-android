package ru.mars.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.mars.mobile.tts.OfflineTtsPort
import ru.mars.mobile.VoiceOutputController.SpeechEngine

/**
 * Голосовой движок, который говорит через офлайн-модель Piper (irina).
 *
 * Зачем это существует. Автоозвучка ответа в голосовом режиме идёт через
 * [LocalVoiceTts], а он строился на [VoiceOutputController.SpeechEngine]. Системный
 * `AndroidSpeechEngine` (Google TTS) даёт тот самый «роботизированный до ужаса» голос,
 * на который указал владелец, — при том что модель Piper уже скачана и работает
 * (`files/tts/vits-piper-ru_RU-irina-medium-int8`).
 *
 * Здесь офлайн-модель подана как обычный [SpeechEngine]: контроллер зовёт `speak`/`stop`,
 * а внутри это `OfflineTtsPort` со своим `AudioTrack` и стриминговым синтезом.
 *
 * ## Особенности, из-за которых нельзя было просто обернуть
 *
 * 1. `OfflineTtsPort.speak` возвращает `true`, если реплика **принята** в очередь —
 *    звук начинается чуть позже. Поэтому «звук пошёл» сообщается по факту готовности
 *    модели, а «звук кончился» — опросом `isReady()` из главного `Handler`: у порта нет
 *    события «дочитал».
 * 2. Если модель ещё не подготовлена, `speak` вернёт `false` — отдаём `false`, и
 *    хост честно покажет «озвучка недоступна», а не подменит роботом.
 * 3. `SpeechEngine.shutdown` обязан освободить движок: `AudioTrack` и нативный синтез
 *    держат аудио-фокус и память.
 */
class OfflineTtsSpeechEngine(
    context: Context,
    private val port: OfflineTtsPort,
    /** «Звук начался / кончился» — фаза SPEAKING у кнопки живёт по этому событию. */
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) : SpeechEngine {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var speaking = false

    @Volatile
    private var released = false

    /** Опрос «дочитал ли»: у порта нет колбэка окончания реплики. */
    private val watchSpeech = object : Runnable {
        override fun run() {
            if (!speaking) return
            // Порт считает себя свободным, когда реплика отзвучала.
            if (port.isReady()) {
                finishSpeaking()
                return
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun speak(text: String): Boolean {
        if (released) return false
        val body = text.trim()
        if (body.isEmpty()) return false
        if (!port.isReady()) return false
        val accepted = runCatching { port.speak(body) }.getOrDefault(false)
        if (accepted) {
            speaking = true
            onSpeakingChanged(true)
            main.removeCallbacks(watchSpeech)
            main.postDelayed(watchSpeech, POLL_MS)
        }
        return accepted
    }

    override fun stop() {
        if (!speaking) return
        runCatching { port.stop() }
        finishSpeaking()
    }

    override fun shutdown() {
        released = true
        main.removeCallbacks(watchSpeech)
        runCatching { port.stop() }
        runCatching { port.release() }
        if (speaking) finishSpeaking()
    }

    private fun finishSpeaking() {
        if (!speaking) return
        speaking = false
        main.removeCallbacks(watchSpeech)
        onSpeakingChanged(false)
    }

    private companion object {
        /** Как часто спрашивать «отзвучало ли»: 100 мс незаметно и не жжёт батарею. */
        const val POLL_MS = 100L
    }
}
