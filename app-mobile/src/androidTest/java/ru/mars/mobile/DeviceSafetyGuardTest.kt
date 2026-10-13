package ru.mars.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import ru.mars.mobile.DeviceSafetyGuard.DeviceProfile
import ru.mars.mobile.DeviceSafetyGuard.Verdict
import ru.mars.network.credential.AndroidKeyStoreCredentialStore

/**
 * Регрессионные проверки самого guard GAP-2 ([DeviceSafetyGuard],
 * [EmulatorOnlyTestRunner], изоляция пакетом `ru.mars.mobile.uitest`).
 *
 * Классификатор проверяется на СИНТЕТИЧЕСКИХ профилях — включая fingerprint
 * реальных физических устройств (POCO/Xiaomi, Samsung, Pixel, OnePlus): если
 * кто-то ослабит whitelist или добавит ложное «эмуляторное» правило, тесты
 * покраснеют. Поведенческий тест «отказ без изменения состояния» форсирует
 * физический verdict через test-seam и доказывает, что resetState/radioOff/
 * seed…/shell не трогают prefs, Keystore и радио.
 *
 * Сами тесты выполняются только на эмуляторе: на физическом устройстве их не
 * пустит [EmulatorOnlyTestRunner] (а при обходе runner — guard в
 * [UiTestHarness]), поэтому проверка «текущее устройство — эмулятор» здесь
 * всегда обязана проходить.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSafetyGuardTest {

    @After
    fun tearDown() {
        // Никакой тест не должен оставить override: иначе весь остальной
        // инструментарный прогон будет отклонён.
        DeviceSafetyGuard.overrideVerdictForTestOnly(null)
        // Если guard когда-либо сломается и радио всё же выключится на
        // эмуляторе — возвращаем сеть, чтобы следующие классы не зависли.
        runCatching { UiTestHarness.radioOn() }
    }

    // --- 1. Классификатор: whitelist эмуляторных профилей ---

    @Test
    fun classify_acceptsKnownEmulatorProfiles() {
        val emulators = listOf(
            // Современный AVD (API 31+): fingerprint содержит "sdk", hardware ranchu, qemu=1.
            DeviceProfile(
                fingerprint = "google/sdk_gphone64_x86_64/emu64xa:14/UE1A.231105.008/11207885:userdebug/dev-keys",
                hardware = "ranchu",
                product = "sdk_gphone64_x86_64",
                manufacturer = "Google",
                model = "sdk_gphone64_x86_64",
                qemuKernelProp = "1",
            ),
            // Классический generic-образ AVD.
            DeviceProfile(
                fingerprint = "generic/system/generic_x86:11/RSR1.210210.001/7122084:userdebug/test-keys",
                hardware = "goldfish",
                product = "google_sdk",
                manufacturer = "unknown",
                model = "Android SDK built for x86",
            ),
            // Cuttlefish (aosp_cf) — по product/hardware.
            DeviceProfile(
                fingerprint = "aosp_cf_x86_64_phone/cf_x86_64_phone/cf_x86_64:14/UD1A.240105.004/eng.user:userdebug/dev-keys",
                hardware = "vsoc_x86_64",
                product = "aosp_cf_x86_64_phone",
                manufacturer = "Google",
                model = "Cuttlefish x86_64 phone",
            ),
            // Genymotion — по manufacturer/fingerprint vbox.
            DeviceProfile(
                fingerprint = "generic/vbox86p/vbox86p:7.0/NRD90M/eng.build:userdebug/test-keys",
                hardware = "vbox86",
                product = "vbox86p",
                manufacturer = "Genymotion",
                model = "Samsung Galaxy S7",
            ),
            // Требование GAP-2: qemu-свойство = 1 достаточно само по себе,
            // даже если fingerprint ничем не примечателен.
            DeviceProfile(
                fingerprint = "vendor/odd-image/odd:13/TQ3A.230901.001/1:userdebug/dev-keys",
                hardware = "odd_hw",
                product = "odd_product",
                manufacturer = "Odd",
                model = "Odd",
                qemuKernelProp = "1",
            ),
            // ro.boot.qemu — альтернативное qemu-свойство.
            DeviceProfile(
                fingerprint = "vendor/odd-image/odd:13/TQ3A.230901.001/1:userdebug/dev-keys",
                qemuBootProp = "1",
            ),
        )
        emulators.forEachIndexed { index, profile ->
            val verdict = DeviceSafetyGuard.classify(profile)
            assertTrue(
                "Профиль #$index (${profile.fingerprint}) должен считаться эмулятором: $verdict",
                verdict.isEmulator,
            )
        }
    }

    // --- 2. Классификатор: реальные физические устройства отклоняются ---

    @Test
    fun classify_rejectsKnownPhysicalDeviceProfiles() {
        val physical = listOf(
            // POCO (боевое устройство Стефа) — регрессия самого инцидента GAP-2.
            DeviceProfile(
                fingerprint = "POCO/missi_ru/missi:14/UKQ1.231003.001/V816.0.3.0.UNARUXM:user/release-keys",
                hardware = "mt6789",
                product = "missi_ru",
                manufacturer = "POCO",
                model = "23117RA68G",
            ),
            DeviceProfile(
                fingerprint = "POCO/vayu_ru/vayu:13/TKQ1.220829.002/V14.0.3.0.TJURUXM:user/release-keys",
                hardware = "qcom",
                product = "vayu_ru",
                manufacturer = "POCO",
                model = "M2102K2G",
            ),
            // Xiaomi.
            DeviceProfile(
                fingerprint = "Xiaomi/fuxi/fuxi:14/UKQ1.230804.001/V816.0.4.0.UMCRUXM:user/release-keys",
                hardware = "taro",
                product = "fuxi_ru",
                manufacturer = "Xiaomi",
                model = "2211133C",
            ),
            // Samsung.
            DeviceProfile(
                fingerprint = "samsung/a53xser/a53x:14/UP1A.231005.007/A536FXXU8DXA3:user/release-keys",
                hardware = "exynos980",
                product = "a53xser",
                manufacturer = "samsung",
                model = "SM-A536F",
            ),
            // Pixel (физический, не AVD).
            DeviceProfile(
                fingerprint = "google/cheetah/cheetah:14/UQ1A.240205.002/11269751:user/release-keys",
                hardware = "cheetah",
                product = "cheetah",
                manufacturer = "Google",
                model = "Pixel 7 Pro",
            ),
            // OnePlus.
            DeviceProfile(
                fingerprint = "OnePlus/OnePlus8Pro_EEA/OnePlus8Pro:11/RP1A.201005.001/2105210530:user/release-keys",
                hardware = "kona",
                product = "OnePlus8Pro_EEA",
                manufacturer = "OnePlus",
                model = "IN2023",
            ),
        )
        physical.forEachIndexed { index, profile ->
            val verdict = DeviceSafetyGuard.classify(profile)
            assertTrue(
                "Профиль #$index (${profile.manufacturer} ${profile.model}) обязан считаться " +
                    "физическим устройством, но вердикт: $verdict",
                !verdict.isEmulator,
            )
        }
    }

    // --- 3. Fail-closed: неизвестное/пустое — физическое устройство ---

    @Test
    fun classify_failsClosedOnUnknownAndBlankProfiles() {
        val unknown = listOf(
            DeviceProfile(), // полностью пустой профиль
            DeviceProfile(fingerprint = "unknown"), // «unknown» намеренно НЕ эмуляторный признак
            DeviceProfile(
                fingerprint = "realme/RMX2020EEA/RMX2020:11/RP1A.200720.011/1:user/release-keys",
                hardware = "mt6768",
                product = "RMX2020EEA",
                manufacturer = "realme",
                model = "RMX2020",
            ),
            DeviceProfile(fingerprint = "qemu-looking-but-prop-is-zero", qemuKernelProp = "0"),
        )
        unknown.forEachIndexed { index, profile ->
            assertTrue(
                "Профиль #$index ($profile) должен fail-closed считаться физическим",
                !DeviceSafetyGuard.classify(profile).isEmulator,
            )
        }
    }

    // --- 4. Текущее устройство прогона обязано быть эмулятором ---

    @Test
    fun evaluateCurrentDevice_onThisRun_isEmulator() {
        // Если этот тест когда-нибудь выполнится на физическом устройстве,
        // он покраснеет сам (а мутаторы всё равно заблокированы guard).
        val verdict = DeviceSafetyGuard.evaluateCurrentDevice()
        assertTrue("Прогон UI-тестов обязан идти на эмуляторе, вердикт: $verdict", verdict.isEmulator)
    }

    @Test
    fun instrumentation_usesGuardedRunner() {
        // Регрессия wiring в build.gradle.kts: testInstrumentationRunner должен
        // указывать на EmulatorOnlyTestRunner, иначе стартовый guard пропадёт.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "Ожидался EmulatorOnlyTestRunner, фактически ${instrumentation.javaClass.name}",
            instrumentation is EmulatorOnlyTestRunner,
        )
    }

    @Test
    fun testRunsAgainstIsolatedUiTestPackage() {
        // Регрессия изоляции (applicationIdSuffix ".uitest" варианта uiTest):
        // тесты не должны быть нацелены на боевой пакет ru.mars.mobile —
        // иначе resetState чистил бы реальные данные установленного приложения.
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertEquals("ru.mars.mobile.uitest", targetPackage)
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        assertEquals("ru.mars.mobile.uitest.test", testPackage)
    }

    // --- 5. Поведенческий guard: отказ мутаторов БЕЗ изменения состояния ---

    @Test
    fun harness_mutators_refuseUnderPhysicalVerdict_andTouchNothing() {
        val context = UiTestHarness.targetContext

        // Маркер кладётся в pref, который resetState() очищает одним из первых.
        val markerPrefs = context.getSharedPreferences("mars-model-choice", Context.MODE_PRIVATE)
        markerPrefs.edit().putString("guard-marker", "alive").commit()
        // Настоящий credential в (изолированном) AndroidKeyStore-хранилище.
        UiTestHarness.seedCredential()
        val wifiBefore = globalSetting("wifi_on")
        val dataBefore = globalSetting("mobile_data")

        // Имитируем вердикт «физическое устройство» (например, боевой POCO).
        DeviceSafetyGuard.overrideVerdictForTestOnly(
            Verdict(false, "регрессионный тест: синтетическое физическое устройство"),
        )
        try {
            assertRefused { UiTestHarness.resetState() }
            // Самые разрушительные операции — радио и произвольный shell:
            // даже одна исполненная команда на POCO = инцидент.
            assertRefused { UiTestHarness.radioOff() }
            assertRefused { UiTestHarness.radioOn() }
            assertRefused { UiTestHarness.shell("svc wifi disable") }
            assertRefused { UiTestHarness.seedCredential() }
            assertRefused { UiTestHarness.seedSessionCache() }
        } finally {
            DeviceSafetyGuard.overrideVerdictForTestOnly(null)
        }

        // Ничего не изменено: маркер на месте, credential читается, радио-настройки прежние.
        assertEquals(
            "resetState очистил prefs, несмотря на физический verdict — guard сломан",
            "alive",
            markerPrefs.getString("guard-marker", null),
        )
        assertNotNull(
            "credential очищен, несмотря на физический verdict — guard сломан",
            AndroidKeyStoreCredentialStore(context).read(),
        )
        assertEquals("wifi_on изменился — guard пропустил радио-команду", wifiBefore, globalSetting("wifi_on"))
        assertEquals("mobile_data изменился — guard пропустил радио-команду", dataBefore, globalSetting("mobile_data"))

        // Уборка (override снят, мы на эмуляторе — guard разрешает).
        UiTestHarness.resetState()
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            val message = expected.message.orEmpty()
            assertTrue(
                "Исключение должно исходить от DeviceSafetyGuard: $message",
                message.contains(DeviceSafetyGuard.REJECTION_PREFIX),
            )
            return
        }
        fail("Guard пропустил изменяющую операцию при вердикте «физическое устройство»")
    }

    /**
     * Read-only запрос `settings get global …` напрямую через UiAutomation —
     * намеренно мимо guarded [UiTestHarness.shell], чтобы наблюдать состояние
     * радио даже при активном override.
     */
    private fun globalSetting(name: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand("settings get global $name").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }.decodeToString().trim()
    }
}
