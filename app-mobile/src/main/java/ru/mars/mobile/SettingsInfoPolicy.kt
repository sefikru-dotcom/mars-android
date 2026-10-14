package ru.mars.mobile

/**
 * Строки карточек «Сервер» и «О приложении» на экране «Настройки»: профиль, клиент
 * и хост.
 *
 * Зачем политика вынесена из `SettingsActivity`: раньше строки «Профиль» и «Клиент»
 * были захардкожены текстами конкретного устройства разработчика («Stef (Ставрополь)»,
 * «Android / HyperOS (POCO F5)») и врали на любом другом телефоне, а хост Марса
 * дублировался литералом рядом с [ru.mars.network.api.MarsApi.BASE_URL]. Теперь
 * значения строятся из реальных источников — полей `android.os.Build`, credential
 * Марса и `MarsApi.BASE_URL` — через чистые функции (JVM-тесты: `SettingsInfoPolicyTest`);
 * экран только передаёт в них факты устройства.
 *
 * Тексты намеренно не содержат запрещённых дизайн-системой глифов
 * (см. `theme-parity-audit.py`); разделитель «·» и многоточие «…» разрешены —
 * они уже используются `ConnectionStatusPolicy`.
 */
object SettingsInfoPolicy {

    /** Замена пустых Build-полей (эмуляторы, урезанные ROM) в строке «Клиент». */
    const val UNKNOWN_DEVICE = "устройство"

    /** Предел короткого идентификатора устройства в строках настроек. */
    const val MAX_SHORT_ID_CHARS = 16

    /**
     * Строка «Хост Марса»: значение показывается как есть — единственный источник
     * правды о базовом URL ([ru.mars.network.api.MarsApi.BASE_URL]) не переписывается
     * и не маскируется, иначе строка перестанет отражать реальный сервер приложения.
     */
    fun serverLabel(baseUrl: String): String = baseUrl

    /**
     * Строка «Клиент»: реальное устройство из Build-полей. Производитель не
     * дублируется, когда модель уже начинается с него (POCO + «POCO F5»); пустые
     * поля деградируют в [UNKNOWN_DEVICE], а не в выдуманное имя.
     */
    fun clientLabel(release: String?, manufacturer: String?, model: String?): String {
        val rel = release?.trim().orEmpty()
        val man = manufacturer?.trim().orEmpty()
        val mod = model?.trim().orEmpty()
        val device = when {
            mod.isEmpty() && man.isEmpty() -> UNKNOWN_DEVICE
            mod.isEmpty() -> man
            man.isEmpty() || mod.startsWith(man, ignoreCase = true) -> mod
            else -> "$man $mod"
        }
        val android = if (rel.isEmpty()) "Android" else "Android $rel"
        return "$android · $device"
    }

    /**
     * Строка «Профиль»: фактическое состояние credential Марса на устройстве.
     * Профиль в нативном клиенте — это привязка устройства (device credential),
     * никакого имени пользователя сервер не выдаёт, поэтому строка показывает
     * привязку и короткий идентификатор устройства, а не выдуманное имя.
     */
    fun profileLabel(deviceId: String?): String {
        val id = deviceId?.trim().orEmpty()
        return if (id.isEmpty()) "Не привязано" else "Привязано · ${shortId(id)}"
    }

    /** Короткий идентификатор устройства: длинный UUID усекается с многоточием. */
    fun shortId(deviceId: String, maxChars: Int = MAX_SHORT_ID_CHARS): String =
        if (deviceId.length <= maxChars) deviceId else deviceId.take(maxChars) + "…"
}
