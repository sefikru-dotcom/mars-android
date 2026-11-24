package ru.mars.mobile

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Единственный в процессе канал «офлайн-синтез начал говорить / дочитал».
 *
 * ## Зачем он нужен
 * У [ru.mars.mobile.tts.OfflineTtsBackend] конец реплики сообщается только колбэком
 * `onSpeakingChanged`, который задаётся В КОНСТРУКТОРЕ. `OfflineTtsWarmup.forApp` этот
 * колбэк не передавал, поэтому «дочитал» не приходило никому: экранный
 * [ru.mars.mobile.LocalVoiceTts] жил по своему опросу, а [LocalVoiceEngine] — вообще без
 * сигнала. Сервис фонового разговора из-за этого навсегда оставался в фазе SPEAKING:
 * микрофон открыт, а ключевую фразу он больше не ищет.
 *
 * Порт синтеза один на процесс (его держит [ru.mars.mobile.tts.OfflineTtsWarmup]), поэтому
 * сигнал раздаётся через этот объект: подписчик один — движок сервиса, а публикует его тот,
 * кто создал бэкенд (экран, [ru.mars.mobile.LocalChatActivity]).
 *
 * ## Почему набор слушателей, а не один
 * Экран пересоздаётся, и сервис может подняться со своим движком поверх прошлого — подписка
 * обязана быть идемпотентной и снимаемой ([attach] возвращает функцию отписки).
 *
 * Ошибка слушателя не должна ломать поток синтеза: исключения гасятся здесь.
 */
object OfflineVoiceSignal {

    private const val TAG = "MarsVoice"

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    /**
     * Подписаться на события синтеза.
     * @return функция отписки (идемпотентна).
     */
    fun attach(listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener); Unit }
    }

    /** Бэкенд сообщил «звук пошёл» (`true`) или «реплика дочитана» (`false`). */
    fun publish(speaking: Boolean) {
        for (listener in listeners) {
            runCatching { listener(speaking) }.onFailure { error ->
                Log.w(TAG, "speech signal listener failed", error)
            }
        }
    }
}