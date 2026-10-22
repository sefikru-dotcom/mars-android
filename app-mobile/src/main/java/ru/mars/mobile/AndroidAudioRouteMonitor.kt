package ru.mars.mobile

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Наблюдатель за маршрутом вывода (наушники/динамик) — Android-часть, решения принимает
 * [AudioRoutePolicy].
 *
 * Зачем нужен: barge-in на наушниках и на динамике — разные задачи. В наушниках микрофон не слышит
 * озвучку, и достаточно короткой подтверждённой речи; на динамике эхо почти гарантировано, и нужен
 * текстовый признак ([BargeInGate]). Без маршрута политике пришлось бы всегда считать худший
 * случай, и в наушниках перебивание работало бы хуже, чем может.
 *
 * Класс терпим к отказам: на устройствах/TV, где `AudioManager` недоступен или колбэк не
 * регистрируется, маршрут остаётся [AudioRoute.UNKNOWN] — это эхо-опасный вариант, а не «наушники
 * по умолчанию». Ошибиться в сторону «эха нет» дороже: цикл начнёт перебивать сам себя.
 */
class AndroidAudioRouteMonitor(context: Context) {

    private val audioManager: AudioManager? = runCatching {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    /** Текущий маршрут. Читается с любого потока. */
    @Volatile
    var route: AudioRoute = AudioRoute.UNKNOWN
        private set

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
}
