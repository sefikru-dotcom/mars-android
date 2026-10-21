package ru.mars.mobile

/**
 * Куда сейчас звучит ответ Марса. Нужен barge-in'у, потому что от маршрута зависит, слышит ли
 * микрофон собственный динамик: на наушниках акустической обратной связи нет, на встроенном
 * динамике она есть почти всегда.
 *
 * Значения намеренно грубые (три штуки): точный тип устройства ничего не меняет в решении, а
 * ошибиться в «наушники или нет» дешевле, чем построить политику на десятке типов.
 */
enum class AudioRoute {
    /** Проводные/BT/USB-наушники или гарнитура: динамик далеко от микрофона. */
    HEADPHONES,

    /** Встроенный динамик (или громкая связь): эхо озвучки почти гарантировано. */
    SPEAKER,

    /** Маршрут не определён (нет разрешения, старый API, экзотическое устройство). */
    UNKNOWN;

    /**
     * Есть ли риск услышать собственную озвучку. `UNKNOWN` считается риском: ошибиться в сторону
     * «перебью сам себя» хуже, чем в сторону «не перебил».
     */
    val isEchoRisk: Boolean get() = this != HEADPHONES
}

/** Класс устройства вывода в терминах домена (Android-free). */
enum class OutputKind {
    /** Наушники/гарнитура любого вида: проводные, BT, USB, слуховой аппарат. */
    HEADPHONES,

    /** Встроенный динамик / громкая связь / док. */
    SPEAKER,

    /** Что-то ещё (HDMI, line-out): маршрут считаем неопределённым. */
    OTHER,
}

/**
 * Чистая политика маршрута: перевод типов устройств Android в [AudioRoute].
 *
 * Android-константы `AudioDeviceInfo.TYPE_*` продублированы числами намеренно: файл не должен
 * импортировать `android.*`, иначе политику нельзя проверить JVM-тестом. Значения стабильны с
 * API 23, а расхождение поймает интеграционный прогон на устройстве.
 */
object AudioRoutePolicy {

    const val TYPE_BUILTIN_SPEAKER: Int = 2
    const val TYPE_WIRED_HEADSET: Int = 3
    const val TYPE_WIRED_HEADPHONES: Int = 4
    const val TYPE_LINE_ANALOG: Int = 5
    const val TYPE_LINE_DIGITAL: Int = 6
    const val TYPE_BLUETOOTH_SCO: Int = 7
    const val TYPE_BLUETOOTH_A2DP: Int = 8
    const val TYPE_HDMI: Int = 9
    const val TYPE_DOCK: Int = 13
    const val TYPE_USB_DEVICE: Int = 11
    const val TYPE_USB_HEADSET: Int = 22
    const val TYPE_HEARING_AID: Int = 23
    const val TYPE_BLE_HEADSET: Int = 26

    /** Класс одного устройства вывода. */
    fun kindFor(deviceType: Int): OutputKind = when (deviceType) {
        TYPE_WIRED_HEADSET,
        TYPE_WIRED_HEADPHONES,
        TYPE_BLUETOOTH_SCO,
        TYPE_BLUETOOTH_A2DP,
        TYPE_USB_HEADSET,
        TYPE_HEARING_AID,
        TYPE_BLE_HEADSET,
        -> OutputKind.HEADPHONES

        TYPE_BUILTIN_SPEAKER,
        TYPE_DOCK,
        -> OutputKind.SPEAKER

        else -> OutputKind.OTHER
    }

    /**
     * Маршрут по всем активным устройствам вывода.
     *
     * Приоритет: наушники > динамик > неопределённость. Причина — микрофон слышит не «маршрут», а
     * физическую близость динамика; если в списке есть наушники, встроенный динамик либо не звучит,
     * либо звучит тихо и не формирует эха.
     */
    fun routeFor(kinds: Collection<OutputKind>): AudioRoute = when {
        kinds.isEmpty() -> AudioRoute.UNKNOWN
        kinds.contains(OutputKind.HEADPHONES) -> AudioRoute.HEADPHONES
        kinds.contains(OutputKind.SPEAKER) -> AudioRoute.SPEAKER
        else -> AudioRoute.UNKNOWN
    }

    /** Маршрут по сырым типам устройств (`AudioDeviceInfo.type`). */
    fun routeForDeviceTypes(types: Collection<Int>): AudioRoute = routeFor(types.map(::kindFor))
}
