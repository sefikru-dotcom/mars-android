package ru.mars.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проводка wake word «Марс, слушай» — source-контракт, как [VoiceBargeInToggleContractTest] и
 * [ChatVoiceUiWiringContractTest]: инструментального слоя в проекте нет, поэтому экран, чат и
 * жизненный цикл проверяются чтением РЕАЛЬНЫХ production-исходников.
 *
 * Что закрепляется:
 *  - тумблер в настройках пишет флаг через единый [ru.mars.mobile.asr.WakeWordSetting], а не своим
 *    ключом экрана — иначе экран и чат разъедутся по разным prefs;
 *  - детектор взводится на onResume и гасится на onPause/onDestroy: микрофон не должен работать
 *    при погашенном экране (фоновый режим требует foreground-сервиса и в эту волну не входит);
 *  - при активном голосовом диалоге детектор не стартует: `AudioRecord` один на приложение;
 *  - детекция уходит в СУЩЕСТВУЮЩИЙ путь startHandsFree, а не в новый голосовой цикл;
 *  - без разрешения RECORD_AUDIO и без скачанной модели микрофон не открывается.
 */
class WakeWordWiringContractTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подстраховка — подъём вверх
        // до корня репозитория (worktree/CI могут ставить другой cwd).
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
    private val chat by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }
    private val voiceInput by lazy { source("src/main/java/ru/mars/mobile/ChatVoiceInputSection.kt") }
    private val detector by lazy { source("src/main/java/ru/mars/mobile/asr/WakeWordDetector.kt") }
    private val strings by lazy { source("src/main/res/values/strings.xml") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }

    @Test
    fun `settings screen writes the flag through the shared setting`() {
        assertTrue(
            "тумблер обязан писать флаг через WakeWordSetting (тот же prefs-файл, что читает чат)",
            settings.contains("WakeWordSetting.of(") && settings.contains("wakeWordSetting.setEnabled("),
        )
        assertTrue(
            "подпись и подсказка — строки ресурсов, а не литералы экрана",
            settings.contains("R.string.settings_voice_wake_word") &&
                settings.contains("R.string.settings_voice_wake_word_hint"),
        )
        assertFalse(
            "свой второй ключ prefs в настройках запрещён: он разошёлся бы с константой чата",
            settings.contains("\"wake_word\""),
        )
        assertTrue(
            "если модели нет, подсказка обязана объяснить причину, а не обещать эффект",
            settings.contains("ModelStore") && settings.contains("R.string.settings_voice_wake_word_no_model"),
        )
    }

    @Test
    fun `chat starts the detector on resume and stops it on pause and destroy`() {
        val resume = chat.substringAfter("override fun onResume()").substringBefore("\n    }")
        assertTrue(
            "onResume обязан выставить «экран активен» и взвести детектор",
            resume.contains("wakeWordScreenActive = true") && resume.contains("startWakeWordListening()"),
        )
        val pause = chat.substringAfter("override fun onPause()").substringBefore("\n    }")
        assertTrue(
            "onPause обязан погасить детектор: микрофон при погашенном экране не работает",
            pause.contains("stopWakeWordListening()"),
        )
        val destroy = chat.substringAfter("override fun onDestroy()").substringBefore("\n    }")
        assertTrue(
            "onDestroy обязан освободить детектор вместе с нативной моделью",
            destroy.contains("releaseWakeWord()"),
        )
    }

    @Test
    fun `detector never runs while the voice dialog holds the microphone`() {
        assertTrue(
            "второй AudioRecord не открывается поверх голосового цикла",
            voiceInput.contains("if (voiceLoopActive())"),
        )
        assertTrue(
            "детекция идёт в существующий hands-free-путь",
            voiceInput.contains("internal fun ChatActivity.onWakeWordDetected()") &&
                voiceInput.contains("startHandsFree()"),
        )
        val detection = voiceInput.substringAfter("internal fun ChatActivity.onWakeWordDetected()")
            .substringBefore("\n}")
        assertTrue(
            "микрофон детектора освобождается ДО старта диалога, иначе hands-free не откроет свой",
            detection.indexOf("disarmWakeWord()") in 0 until detection.indexOf("startHandsFree()"),
        )
        assertFalse(
            "wake word не заводит второй голосовой цикл: только проброс в startHandsFree",
            detection.contains("VoiceDialogLoop"),
        )
    }

    @Test
    fun `detector requires permission and an installed model`() {
        assertTrue(
            "без RECORD_AUDIO микрофон не открывается",
            voiceInput.contains("Manifest.permission.RECORD_AUDIO"),
        )
        assertTrue(
            "детектор инициализируется только по установленной модели, а не качает её сам",
            detector.contains("modelStore.isInstalled()"),
        )
        assertFalse(
            "тихое скачивание модели из детектора запрещено: за это отвечает голосовой диалог",
            detector.contains("ModelDownloader"),
        )
    }

    @Test
    fun `wake word stays in the foreground - no microphone foreground service in this wave`() {
        assertFalse(
            "фоновый микрофон требует FOREGROUND_SERVICE_MICROPHONE и отдельного решения",
            manifest.contains("FOREGROUND_SERVICE_MICROPHONE"),
        )
        assertTrue(
            "строка настроек и обе подсказки объявлены в ресурсах",
            strings.contains("name=\"settings_voice_wake_word\"") &&
                strings.contains("name=\"settings_voice_wake_word_hint\"") &&
                strings.contains("name=\"settings_voice_wake_word_no_model\""),
        )
    }
}
