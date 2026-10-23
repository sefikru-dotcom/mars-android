package ru.mars.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проводка переключателя перебивания голосом (L3) — source-контракт, как
 * [SettingsSourceContractTest] и [ChatVoiceUiWiringContractTest]: инструментального слоя в
 * проекте нет, поэтому экран и цикл проверяются чтением РЕАЛЬНЫХ production-исходников.
 *
 * Что закрепляется:
 *  - флаг `barge_in` пишется из экрана настроек через единый [VoiceBargeInSetting], а не
 *    собственным ключом экрана — иначе настройка и цикл разъедутся по разным prefs;
 *  - цикл получает флаг из того же источника при создании (а не из литерала `false`);
 *  - возврат в чат перечитывает флаг: без этого переключатель «пишет, но ни на что не влияет»
 *    до перезапуска приложения;
 *  - сам флаг цикла — `var`: значение, зафиксированное в конструкторе, живое применение
 *    исключает.
 */
class VoiceBargeInToggleContractTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подстраховка —
        // подъём вверх до корня репозитория (worktree/CI могут ставить другой cwd).
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val settings by lazy { source("src/main/java/ru/mars/mobile/SettingsActivity.kt") }
    private val initSection by lazy { source("src/main/java/ru/mars/mobile/ChatInitSection.kt") }
    private val loop by lazy { source("src/main/java/ru/mars/mobile/VoiceDialogLoop.kt") }

    @Test
    fun `settings screen writes the barge-in flag through the shared setting`() {
        assertTrue(
            "переключатель обязан писать флаг через VoiceBargeInSetting (тот же prefs-файл, что читает цикл)",
            settings.contains("VoiceBargeInSetting.of(") && settings.contains(".setEnabled("),
        )
        assertTrue(
            "подпись переключателя — строка ресурсов, а не литерал экрана",
            settings.contains("R.string.settings_voice_barge_in"),
        )
        assertFalse(
            "свой второй ключ prefs в настройках запрещён: он разошёлся бы с константой цикла",
            settings.contains("\"barge_in\""),
        )
    }

    @Test
    fun `chat reads the same setting at loop creation and on every return`() {
        assertTrue(
            "цикл создаётся со значением из настройки, а не из литерала",
            initSection.contains("bargeInEnabled = bargeInSetting().isEnabled()"),
        )
        val resumeBody = initSection
            .substringAfter("internal fun ChatActivity.resumeChat()")
            .substringBefore("\n}")
        assertTrue(
            "возврат из настроек обязан перечитать флаг и отдать его живому циклу",
            resumeBody.contains("voiceLoop.bargeInEnabled = bargeInSetting().isEnabled()"),
        )
    }

    @Test
    fun `loop flag is mutable so a toggle applies without restart`() {
        assertTrue(
            "флаг цикла должен быть var: значение из конструктора не даёт применить настройку на лету",
            Regex("""\bvar bargeInEnabled: Boolean""").containsMatchIn(loop),
        )
        assertFalse(
            "private val bargeInEnabled замораживает флаг на всю жизнь экрана",
            loop.contains("private val bargeInEnabled"),
        )
    }
}
