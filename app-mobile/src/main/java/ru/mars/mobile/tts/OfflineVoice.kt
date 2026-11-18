package ru.mars.mobile.tts

import android.content.Context
import ru.mars.mobile.OfflineVoiceSignal

/**
 * ЕДИНЫЙ офлайн-голос процесса: одна модель Piper, один нативный движок, один `AudioTrack`.
 *
 * ## Зачем синглтон
 * Офлайн-синтез нужен трём владельцам сразу:
 * - ручная озвучка сообщения в локальном чате (`LocalChatActivity.speak`);
 * - голосовой диалог в локальном чате (`LocalVoiceController` → [ru.mars.mobile.LocalVoiceTts]);
 * - фоновый сервис «Марс, слушай» (`ru.mars.mobile.LocalVoiceEngine`).
 *
 * Раньше связка «модель + бэкенд + прогрев» собиралась в `LocalChatActivity`, а сервис
 * получал порт лямбдой от экрана. Пока экран был один, это работало; как только офлайн-резерв
 * понадобился серверному чату (`ChatActivity`), появился бы ВТОРОЙ экземпляр бэкенда — со
 * своим `AudioTrack`, своей копией модели в памяти и своей гонкой за аудиофокус: два голоса
 * одновременно, оба «первый». Здесь ровно один экземпляр на процесс.
 *
 * ## Кто освобождает
 * НИКТО из экранов. [release] вызывается только явно (выход из аккаунта, нехватка памяти),
 * потому что `OfflineTtsBackend.release()` гасит нативный движок навсегда: экран локального
 * чата, закрываясь, убивал бы голос серверного чата, если бы освобождал «свой» порт. Поэтому
 * экраны создают движки с `releasePort = false` (см. `OfflineTtsSpeechEngine`,
 * `LocalVoiceTts`) и не зовут `OfflineTtsWarmup.release()` в `onDestroy`.
 *
 * Плата за это названа прямо: модель живёт в памяти до конца процесса (десятки мегабайт).
 * Альтернатива — гонка двух `AudioTrack` и двух скачиваний, то есть дефект, который дороже
 * памяти.
 *
 * ## Прогрев
 * [prepare] идемпотентен: `OfflineTtsWarmup` зовёт `prepare()` порта ровно один раз за
 * процесс и сериализует параллельные вызовы мьютексом. Прогресс скачивания (21 МиБ) идёт
 * через тот же [ProgressRelay], что и раньше, поэтому экран показывает «Готовлю голос… N %».
 */
object OfflineVoice {

    /** Единственная связка «модель + порт + политика подготовки» на процесс. */
    @Volatile
    private var shared: OfflineTtsWarmup? = null

    private val lock = Any()

    /**
     * Связка прогрева для этого процесса (создаётся при первом обращении).
     *
     * Создание дешёвое: модель и нативный движок поднимаются только в [prepare], поэтому
     * звать можно с главного потока при открытии экрана.
     */
    fun warmup(context: Context): OfflineTtsWarmup = shared ?: synchronized(lock) {
        shared ?: build(context.applicationContext).also { shared = it }
    }

    /**
     * Порт синтеза или `null`, если бэкенд не создался (нет нативного слоя, битая сборка).
     *
     * Отдаётся ЖИВОЙ [OfflineTtsBackend], а не обёртка: `OfflineTtsSpeechEngine` проверяет
     * фактический тип, чтобы применить темп (`setSpeed`) и узнать «реально ли идёт речь»
     * ([OfflineTtsBackend.isSpeaking]). Обёртка сломала бы оба этих признака.
     */
    fun port(context: Context): OfflineTtsPort? = runCatching { warmup(context).port }.getOrNull()

    /** Движок уже поднят и может синтезировать. Без сети и без блокировки. */
    fun isReady(context: Context): Boolean = shared?.isReady() ?: false

    /** Причина последнего отказа подготовки — та же строка, что видит человек. */
    fun lastFailure(context: Context): String? = shared?.lastFailure

    /**
     * Подготовить голос (скачать модель при первом запуске и поднять движок).
     *
     * @return `true` — движок готов. Повторный вызов после успеха возвращает `true` сразу.
     */
    suspend fun prepare(context: Context, onProgress: ((Long, Long) -> Unit)? = null): Boolean =
        warmup(context).warmUp(onProgress)

    /** Освободить нативный движок навсегда. Только явные пути (logout, нехватка памяти). */
    fun release() {
        val current = shared ?: return
        synchronized(lock) {
            if (shared === current) shared = null
        }
        runCatching { current.release() }
    }

    /**
     * Сборка связки: модель с релеем прогресса → бэкенд с сигналом «дочитал» → прогрев.
     *
     * `onSpeakingChanged` — единственный источник события «реплика кончилась» для фонового
     * сервиса и для [ru.mars.mobile.LocalVoiceEngine]; он идёт в [OfflineVoiceSignal], потому
     * что колбэк задаётся в конструкторе бэкенда и позже подменить его нельзя.
     */
    private fun build(context: Context): OfflineTtsWarmup {
        val relay = ProgressRelay()
        val model = OfflineTtsModel.forApp(context) { downloaded, total ->
            relay.emit(downloaded, total)
        }
        val port = OfflineTtsBackend(
            context,
            model = model,
            onSpeakingChanged = { speaking -> OfflineVoiceSignal.publish(speaking) },
        )
        return OfflineTtsWarmup(
            port = port,
            model = model,
            diagnostics = AndroidWarmUpDiagnostics(context),
            relay = relay,
        )
    }
}
