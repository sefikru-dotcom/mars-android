package ru.mars.mobile

import android.os.Build

/**
 * Жёсткий guard инструментальных UI-тестов (GAP-2).
 *
 * Тесты через [UiTestHarness] изменяют состояние устройства: выключают радио
 * (`svc wifi/data disable` — ГЛОБАЛЬНЫЙ эффект, не ограниченный пакетом),
 * чистят хранилище credential (AndroidKeyStore + prefs) и SharedPreferences.
 * На физическом устройстве (например, боевом POCO) это недопустимо: guard
 * разрешает изменения ТОЛЬКО на эмуляторе и построен fail-closed — любое
 * неопознанное устройство считается физическим и отклоняется.
 *
 * Уровни защиты (defense in depth):
 *  1. [EmulatorOnlyTestRunner] — завершает instrumentation до загрузки и
 *     выполнения хоть одного теста (до любого кода тестов вообще);
 *  2. [requireEmulator] — вызывается каждым изменяющим методом
 *     [UiTestHarness] (shell/radioOff/radioOn/resetState/seed…): даже при
 *     запуске со стоковым runner состояние не будет тронуто;
 *  3. изолированный build-вариант `uiTest` (applicationIdSuffix `.uitest`,
 *     см. app-mobile/build.gradle.kts): тесты работают в собственном пакете
 *     `ru.mars.mobile.uitest` со своим data-каталогом и своим UID-пространством
 *     AndroidKeyStore, поэтому боевой пакет `ru.mars.mobile` не затрагивается.
 *     Радио — глобальный ресурс, поэтому уровни 1–2 обязательны независимо
 *     от изоляции пакетом.
 *
 * Регрессионные проверки самого guard: [DeviceSafetyGuardTest] (классификатор
 * на синтетических профилях реальных устройств, отказ мутаторов под
 * «физическим» verdict без изменения состояния, изоляция пакета, wiring
 * runner).
 */
object DeviceSafetyGuard {

    /** Вердикт guard: [isEmulator] + человекочитаемое основание [reason]. */
    data class Verdict(val isEmulator: Boolean, val reason: String)

    /**
     * Профиль устройства — всё, что нужно классификатору. В рантайме читается
     * из [Build] и системных свойств ([evaluateCurrentDevice]); регрессионные
     * тесты строят синтетические профили (включая fingerprint реальных
     * физических устройств) без обращения к текущему устройству.
     * Пустой профиль обязан классифицироваться как физический (fail-closed).
     */
    data class DeviceProfile(
        val fingerprint: String = "",
        val hardware: String = "",
        val product: String = "",
        val manufacturer: String = "",
        val model: String = "",
        val qemuKernelProp: String? = null,
        val qemuBootProp: String? = null,
    )

    /**
     * Seam ИСКЛЮЧИТЕЛЬНО для регрессионных тестов guard ([DeviceSafetyGuardTest]):
     * форсирует вердикт, чтобы проверить отказ мутаторов на «физическом»
     * устройстве без настоящего физического устройства. Живёт в androidTest
     * source set — в боевой APK не попадает.
     */
    @Volatile
    private var verdictOverrideForTest: Verdict? = null

    fun overrideVerdictForTestOnly(verdict: Verdict?) {
        verdictOverrideForTest = verdict
    }

    /** Вердикт для текущего устройства (или тестового override). */
    fun evaluateCurrentDevice(): Verdict =
        verdictOverrideForTest ?: classify(readCurrentProfile())

    /**
     * Чистый fail-closed классификатор: эмулятор — только при явном совпадении
     * с whitelist-признаком; всё неизвестное (и пустые значения) — физическое
     * устройство. Никаких обращений к Android API: тестируется синтетикой.
     */
    fun classify(profile: DeviceProfile): Verdict {
        val fingerprint = profile.fingerprint.lowercase()
        val hardware = profile.hardware.lowercase()
        val product = profile.product.lowercase()
        val manufacturer = profile.manufacturer.lowercase()
        val model = profile.model.lowercase()

        if (profile.qemuKernelProp == "1" || profile.qemuBootProp == "1") {
            return Verdict(true, "qemu-свойство (ro.kernel.qemu/ro.boot.qemu) = 1")
        }
        EMULATOR_FINGERPRINT_TOKENS.firstOrNull { fingerprint.contains(it) }?.let {
            return Verdict(true, "Build.FINGERPRINT содержит '$it': ${profile.fingerprint}")
        }
        EMULATOR_HARDWARE_TOKENS.firstOrNull { hardware.contains(it) }?.let {
            return Verdict(true, "Build.HARDWARE содержит '$it': ${profile.hardware}")
        }
        EMULATOR_PRODUCT_TOKENS.firstOrNull { product.contains(it) }?.let {
            return Verdict(true, "Build.PRODUCT содержит '$it': ${profile.product}")
        }
        if (EMULATOR_MANUFACTURERS.any { manufacturer.contains(it) }) {
            return Verdict(true, "Build.MANUFACTURER эмуляторный: ${profile.manufacturer}")
        }
        if (EMULATOR_MODELS.any { model.contains(it) }) {
            return Verdict(true, "Build.MODEL эмуляторный: ${profile.model}")
        }
        // Fail-closed: ни одного эмуляторного признака → физическое устройство.
        // Специально НЕ принимаем "test-keys"/"unknown" — они встречаются на
        // физических кастомных прошивках (ложное «эмулятор» хуже ложного «физ»).
        return Verdict(
            false,
            "физическое устройство: fingerprint=${profile.fingerprint}, " +
                "hardware=${profile.hardware}, product=${profile.product}, " +
                "manufacturer=${profile.manufacturer}, model=${profile.model}",
        )
    }

    /**
     * Точка обязательной проверки перед любым изменением состояния. Бросает
     * [IllegalStateException] ДО выполнения операции, если устройство не
     * эмулятор: Keystore/SharedPreferences/радио остаются нетронутыми.
     */
    fun requireEmulator(action: String) {
        val verdict = evaluateCurrentDevice()
        check(verdict.isEmulator) {
            "ОТКЛАДЕНО [$action]: $REJECTION_PREFIX ${verdict.reason}. " +
                "UI-тестам разрешено менять состояние (радио, Keystore, prefs) только на " +
                "эмуляторе; на физическом устройстве ни одна операция не выполнена. " +
                "Запускайте connectedAndroidTest только на эмуляторе (вариант uiTest, " +
                "изолированный пакет ru.mars.mobile.uitest)."
        }
    }

    /** Префикс сообщения отказа — по нему регрессионные тесты узнают guard. */
    const val REJECTION_PREFIX = "guard отклонил операцию на физическом устройстве:"

    private fun readCurrentProfile() = DeviceProfile(
        fingerprint = Build.FINGERPRINT,
        hardware = Build.HARDWARE,
        product = Build.PRODUCT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        qemuKernelProp = systemProperty("ro.kernel.qemu"),
        qemuBootProp = systemProperty("ro.boot.qemu"),
    )

    /**
     * Читает системное свойство рефлексией (android.os.SystemProperties).
     * Сбой чтения не страшен: классификатор останется fail-closed по полям
     * [Build], а AVD дополнительно опознаётся по fingerprint (generic/sdk).
     */
    private fun systemProperty(name: String): String? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, name, "") as String).takeIf { it.isNotBlank() }
    }.getOrNull()

    // --- Whitelist эмуляторных признаков. Основа — требование GAP-2:
    // --- Build.FINGERPRINT содержит generic/sdk ЛИБО qemu-свойство = 1;
    // --- остальные токены — стандартные AVD/cuttlefish/Genymotion-признаки.
    private val EMULATOR_FINGERPRINT_TOKENS =
        listOf("generic", "sdk", "emulator", "vbox", "cuttlefish")
    private val EMULATOR_HARDWARE_TOKENS =
        listOf("goldfish", "ranchu", "vsoc")
    private val EMULATOR_PRODUCT_TOKENS =
        listOf("sdk", "emulator", "aosp_cf")
    private val EMULATOR_MANUFACTURERS =
        listOf("genymotion")
    private val EMULATOR_MODELS =
        listOf("emulator", "android sdk built for")
}
