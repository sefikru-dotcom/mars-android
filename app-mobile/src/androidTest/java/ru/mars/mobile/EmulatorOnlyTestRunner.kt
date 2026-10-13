package ru.mars.mobile

import android.app.Activity
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner инструментальных тестов с жёстким guard GAP-2: на физическом
 * устройстве instrumentation завершается ЗДЕСЬ — до загрузки тестов, до любых
 * вызовов [UiTestHarness], до любого изменения Keystore/SharedPreferences/радио.
 *
 * Подключается через `testInstrumentationRunner` в app-mobile/build.gradle.kts;
 * wiring регрессионно проверяет [DeviceSafetyGuardTest]. Если тесты всё же
 * запущены со стоковым runner, guard продублирован в каждом изменяющем методе
 * [UiTestHarness] ([DeviceSafetyGuard.requireEmulator]).
 */
class EmulatorOnlyTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        val verdict = DeviceSafetyGuard.evaluateCurrentDevice()
        if (!verdict.isEmulator) {
            val message = buildString {
                append("\n*** UI-ТЕСТЫ МАРСА ОТКЛОНЕНЫ: ПОДКЛЮЧЕНО ФИЗИЧЕСКОЕ УСТРОЙСТВО ***\n")
                append("Вердикт guard: ").append(verdict.reason).append('\n')
                append("Инструментальные тесты выключают радио и чистят хранилище credential/")
                append("SharedPreferences — на боевом устройстве (например, POCO) это недопустимо.\n")
                append("Ни один тест не выполнен; состояние устройства не изменено.\n")
                append("Запускайте connectedAndroidTest только на эмуляторе ")
                append("(вариант uiTest, изолированный пакет ru.mars.mobile.uitest).\n")
            }
            val results = Bundle().apply {
                putString("id", "DeviceSafetyGuard")
                putString("test", "instrumentation-startup")
                putString("class", EmulatorOnlyTestRunner::class.java.name)
                putString("stream", message)
                putString("error", message)
                putString("stack", message)
                putString("shortMsg", "physical device rejected: ${verdict.reason}")
            }
            // RESULT_CANCELED → am instrument/Gradle сообщают о провале (никакого
            // «зелёного» прогона без тестов); super.onCreate НЕ вызывается, поэтому
            // тесты не загружаются и не выполняются вовсе.
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        super.onCreate(arguments)
    }
}
