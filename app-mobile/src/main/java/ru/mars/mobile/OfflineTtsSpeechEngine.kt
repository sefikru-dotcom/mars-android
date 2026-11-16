package ru.mars.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.mars.mobile.tts.OfflineTtsBackend
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
 *    модели, а «звук кончился» — опросом активности реплики ([OfflineTtsBackend.isSpeaking])
 *    из главного `Handler`: у порта нет события «дочитал».
 *    **Так было не всегда, и это стоило дорого.** Раньше окончание определялось по
 *    `port.isReady()`, а у бэкенда он означает «движок поднят» и истинно ВСЁ время речи.
 *    Через 100 мс после первого слова движок объявлял тишину → хост считал, что Марс
 *    договорил, и через 350 мс открывал микрофон прямо в говорящий динамик: Марс слышал
 *    собственный ответ и отправлял его как новый вопрос. Поэтому `isReady()` здесь больше
 *    НЕ признак конца — только [OfflineTtsBackend.isSpeaking].
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
            // «Готов» ≠ «договорил»: у офлайн-бэкенда isReady() истинно и во время речи
            // (движок-то поднят), поэтому конец реплики берётся из отдельного сигнала.
            val running = OfflineTtsSpeechWatch.isSpeechRunning(
                backendSpeaking = (port as? OfflineTtsBackend)?.isSpeaking(),
                ready = port.isReady(),
            )
            if (!running) {
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

    /**
     * Темп для последующих реплик.
     *
     * Порт объявлен как [OfflineTtsPort] — в нём метода темпа нет (контракт писался под
     * вызовы, а не под настройку), поэтому проверяется фактический тип: боевой бэкенд —
     * [OfflineTtsBackend]. Проверка по типу, а не расширение интерфейса, нарочно: тестовые
     * фейки порта не должны обязаны уметь темп, иначе они перестанут компилироваться у
     * всех, кто их писал. Отказ молча — это особенность, а не сбой.
     */
    override fun setProsody(prosody: VoiceProsody) {
        val backend = port as? OfflineTtsBackend ?: return
        runCatching { backend.setSpeed(prosody.offlineSpeed()) }
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

/**
 * Чистое решение «реплика ещё звучит?» для опроса [OfflineTtsSpeechEngine].
 *
 * Зачем отдельная сущность, а не три строки внутри `Runnable`: сам движок создаёт `Handler` на
 * главном `Looper` и в JVM-тесте не инстанцируется. Проверяемая логика вынесена сюда, поэтому
 * главный баг Волны 19 (готовность движка, принятая за конец речи) закрыт юнит-тестом, а не
 * только повторной приёмкой на телефоне.
 *
 * @param backendSpeaking сигнал бэкенда ([OfflineTtsBackend.isSpeaking]); `null` — порт такого
 *   сигнала не даёт (тестовые фейки и любые другие реализации [OfflineTtsPort]).
 * @param ready результат [OfflineTtsPort.isReady].
 */
internal object OfflineTtsSpeechWatch {

    /**
     * @return `true` — реплика ещё идёт (звук идёт или вот-вот начнётся), опрос продолжается.
     *
     * Для порта с сигналом активности занятость — это «бэкенд говорит И движок не освобождён».
     * `isReady()` здесь только страховка от освобождённого бэкенда: сам по себе он `true` и во
     * время речи, и в тишине, то есть про конец реплики не говорит ничего.
     *
     * Для порта БЕЗ сигнала (`null`) поведение остаётся прежним — «свободен, когда готов»: у
     * такого порта другой правды о конце речи всё равно нет, а менять его семантику значило бы
     * менять поведение всех его реализаций вслепую.
     */
    fun isSpeechRunning(backendSpeaking: Boolean?, ready: Boolean): Boolean =
        if (backendSpeaking != null) backendSpeaking && ready else !ready
}
