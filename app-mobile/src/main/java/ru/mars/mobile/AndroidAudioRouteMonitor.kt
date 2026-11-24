package ru.mars.mobile

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Наблюдатель за маршрутом вывода (наушники/динамик) — Android-часть, решения принимает
 * [AudioRoutePolicy].
 *
 * Зачем нужен: barge-in на наушниках и на динамике — разные задачи. В наушниках микрофон не слышит
 * озвучку, и достаточно короткой подтверждённой речи; на динамике эхо почти гарантировано, и нужен
 * текстовый признак ([BargeInGate]). Без маршрута политике пришлось бы всегда считать худший
 * случай, и в наушниках перебивание работало бы хуже, чем может.
 *
 * ## Два разных вопроса — [route] и [activeOutput]
 * [route] отвечает на вопрос «какие выходы вообще доступны»: он строится по
 * `getDevices(GET_DEVICES_OUTPUTS)` и потому видит воткнутую гарнитуру, даже когда звук идёт в
 * динамик. Для «куда рисовать индикатор» этого достаточно, для решения «открывать ли микрофон во
 * время озвучки» — нет: там ошибка означает, что Марс услышит сам себя. Поэтому у монитора есть
 * второй, строгий ответ — [activeOutput], который берётся у самой платформы и имеет признак
 * доказанности.
 *
 * Класс терпим к отказам: на устройствах/TV, где `AudioManager` недоступен или колбэк не
 * регистрируется, маршрут остаётся [AudioRoute.UNKNOWN] — это эхо-опасный вариант, а не «наушники
 * по умолчанию». Ошибиться в сторону «эха нет» дороже: цикл начнёт перебивать сам себя.
 */
class AndroidAudioRouteMonitor(context: Context) {

    private val audioManager: AudioManager? = runCatching {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    /** Текущий маршрут по ДОСТУПНЫМ выходам. Читается с любого потока. */
    @Volatile
    var route: AudioRoute = AudioRoute.UNKNOWN
        private set

    /** Кэш активного выхода: спрашивать платформу на каждый тик речи незачем. */
    @Volatile
    private var activeCache: ActiveOutput = ActiveOutput.UNKNOWN

    @Volatile
    private var activeCheckedAt: Long = 0L

    private var registered: AudioDeviceCallback? = null

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Пересчитать маршрут и (один раз) подписаться на смену устройств.
     *
     * @return маршрут сразу после пересчёта — хост может передать его в [VoiceDialogLoop] как
     *   `audioRoute = { monitor.route }`.
     */
    fun start(): AudioRoute {
        refresh()
        if (registered == null) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    refresh()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    refresh()
                }
            }
            val ok = runCatching {
                audioManager?.registerAudioDeviceCallback(callback, handler)
                true
            }.getOrDefault(false)
            if (ok) registered = callback
        }
        return route
    }

    /** Отписаться: колбэк держит ссылку на монитор и не должен пережить голосовой режим. */
    fun stop() {
        val callback = registered ?: return
        registered = null
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    /** Пересчитать маршрут по активным выходам. */
    fun refresh(): AudioRoute {
        val manager = audioManager ?: return AudioRoute.UNKNOWN
        val types = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .orEmpty()
                .map { it.type }
                .toSet()
        }.getOrNull()
        route = if (types.isNullOrEmpty()) {
            // getDevices пуст (бывает на старых/TV-прошивках): спросим хотя бы про провод и BT.
            legacyRoute(manager)
        } else {
            AudioRoutePolicy.routeForDeviceTypes(types)
        }
        return route
    }

    /**
     * Резервный путь для прошивок без `getDevices`: только явные признаки. Ничего не знаем —
     * [AudioRoute.UNKNOWN], то есть «считаем эхо-опасным».
     */
    private fun legacyRoute(manager: AudioManager): AudioRoute {
        val kinds = buildSet {
            if (runCatching { manager.isWiredHeadsetOn }.getOrDefault(false)) add(OutputKind.HEADPHONES)
            if (runCatching { manager.isBluetoothA2dpOn }.getOrDefault(false)) add(OutputKind.HEADPHONES)
            if (runCatching { manager.isSpeakerphoneOn }.getOrDefault(false)) add(OutputKind.SPEAKER)
        }
        return AudioRoutePolicy.routeFor(kinds)
    }

    /**
     * Активный выход — то, куда звук идёт СЕЙЧАС, и доказан ли этот ответ.
     *
     * ## Почему не [route]
     * [route] строится по доступным выходам: воткнутая гарнитура попадает в список, даже когда
     * ответ читает встроенный динамик. На такой «наушниковой» картинке микрофон открывался во
     * время озвучки — и записывал динамик.
     *
     * ## Откуда берётся активный выход
     * - API 31+ (`AudioManager.getCommunicationDevice`) — платформа сама называет устройство,
     *   которое обслуживает связь/голос; оно же обслуживает и речь ассистента
     *   (`VOICE_COMMUNICATION`-захват переводит гарнитуру в это состояние). Тип устройства
     *   переводит чистая [AudioRoutePolicy.confirmedRouteFor].
     * - До Android 12 достоверного ответа нет: доказательством остаются только явные состояния
     *   маршрутизации (`isBluetoothScoOn`, `isSpeakerphoneOn`). «Гарнитура просто воткнута» —
     *   НЕ доказательство, и тогда возвращается [ActiveOutput.UNKNOWN].
     *
     * Недоказанный маршрут — это не «наушники по умолчанию», а выключенное автоперебивание:
     * обещать дуплекс на динамике нельзя, а ошибиться в сторону «эха нет» дороже всего.
     */
    fun activeOutput(): ActiveOutput {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - activeCheckedAt < ACTIVE_OUTPUT_TTL_MS) return activeCache
            activeCheckedAt = now
            activeCache = queryActiveOutput()
            return activeCache
        }
    }

    /** Один запрос к платформе. Ошибки — это [ActiveOutput.UNKNOWN], а не исключение. */
    private fun queryActiveOutput(): ActiveOutput {
        val manager = audioManager ?: return ActiveOutput.UNKNOWN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { manager.communicationDevice }.getOrNull()
                ?: return ActiveOutput.UNKNOWN
            // Устройство ввода (микрофон) не отвечает на вопрос «куда звучит ответ».
            if (!runCatching { device.isSink }.getOrDefault(false)) return ActiveOutput.UNKNOWN
            val route = AudioRoutePolicy.confirmedRouteFor(device.type)
            if (route == AudioRoute.UNKNOWN) return ActiveOutput.UNKNOWN
            return ActiveOutput(route, confirmed = true)
        }
        if (runCatching { manager.isBluetoothScoOn }.getOrDefault(false)) {
            return ActiveOutput(AudioRoute.HEADPHONES, confirmed = true)
        }
        if (runCatching { manager.isSpeakerphoneOn }.getOrDefault(false)) {
            return ActiveOutput(AudioRoute.SPEAKER, confirmed = true)
        }
        return ActiveOutput.UNKNOWN
    }

    private companion object {
        /** Кэш активного выхода: смена маршрута без смены устройств приходит не мгновенно. */
        const val ACTIVE_OUTPUT_TTL_MS: Long = 300L
    }
}
