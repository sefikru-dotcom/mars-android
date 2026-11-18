package ru.mars.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проводка окна «Контекст и параметры» локального чата — source-контракт, как
 * [VoiceBargeInToggleContractTest] и [SettingsSourceContractTest]: инструментального слоя
 * в проекте нет, поэтому связь «окно ↔ экран» проверяется чтением РЕАЛЬНЫХ
 * production-исходников.
 *
 * Что закрепляется:
 *  - политика окна остаётся чистой (без `android.*`), иначе её нельзя проверить
 *    JVM-тестом, а подписи снова начнут расходиться с поведением;
 *  - само окно ничего не сохраняет и не пишет флаг `wake_word`: у каждой настройки
 *    ровно один писатель, иначе переключатель в настройках приложения и строка в чате
 *    разъедутся по значению;
 *  - строки собираются политикой, а не литералами в UI — единственный способ удержать
 *    «одна строка на вопрос»;
 *  - устаревшие формулировки не вернулись в КОД (в KDoc они остаются как объяснение,
 *    почему подпись убрали, — поэтому проверяются строки без комментариев);
 *  - у каждого колбэка окна есть живой обработчик на экране: мёртвая строка (тап в
 *    пустоту) — это дефект приёмки, а не косметика.
 */
class LocalChatSettingsWiringContractTest {

    private fun source(relative: String): String {
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

    /**
     * Исходник без комментариев.
     *
     * Комментарий — это объяснение («подпись с названием офлайн-движка врала»), а не
     * поведение: запрещать в нём устаревшие слова значило бы запретить документировать
     * саму правку. Проверяем только код и строковые литералы в нём.
     */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    private val policy by lazy { source("src/main/java/ru/mars/mobile/LocalChatSettingsPolicy.kt") }
    private val sheet by lazy { source("src/main/java/ru/mars/mobile/LocalChatSettingsSheet.kt") }
    private val activity by lazy { source("src/main/java/ru/mars/mobile/LocalChatActivity.kt") }

    @Test
    fun `политика окна не тянет Android`() {
        assertFalse(
            "политика обязана оставаться чистой: android.* в ней убивает JVM-тесты подписей",
            Regex("""^import android\.""", RegexOption.MULTILINE).containsMatchIn(policy),
        )
        assertFalse(
            "политика ничего не читает и не пишет — иначе её нельзя проверить без телефона",
            codeOnly(policy).contains("getSharedPreferences"),
        )
    }

    @Test
    fun `окно не заводит второго писателя настроек`() {
        val code = codeOnly(sheet)
        assertFalse(
            "флаг wake_word пишет только настройка приложения: второй писатель развёл бы значение",
            code.contains("wake_word"),
        )
        assertFalse(
            "окно не сохраняет настройки само — это делает вызывающий через колбэк",
            code.contains("getSharedPreferences") || code.contains(".edit()"),
        )
        assertFalse(
            "тембр пишется через VoiceProsodyStore, а не из окна",
            code.contains("VoiceProsodyPrefs"),
        )
    }

    @Test
    fun `строки окна собирает политика, а не литералы UI`() {
        val code = codeOnly(sheet)
        assertTrue(code.contains("LocalChatSettingsPolicy.modelRows("))
        assertTrue(code.contains("LocalChatSettingsPolicy.voiceRows("))
        assertTrue(code.contains("LocalChatSettingsPolicy.memoryRow()"))
        // Заголовок окна — тоже из политики: две копии строки разъехались бы при правке.
        assertTrue(code.contains("LocalChatSettingsPolicy.TITLE"))
    }

    @Test
    fun `устаревшие формулировки не вернулись в код`() {
        // Экран тоже проверяется: после подключения [LocalChatSettingsSheet] подписи строк
        // живут только в политике, и любое их возвращение в Activity означает вторую копию
        // окна, которая со временем разойдётся с первой.
        listOf(policy, sheet, activity).map(::codeOnly).forEach { code ->
            assertFalse("движок в заголовке строки — устаревшая формулировка", code.contains("Piper офлайн"))
            assertFalse("частота дискретизации не описывает текущий голос", code.contains("22 кГц"))
            assertFalse("«готов» рядом с выбранным голосом читается как «звучит он»", code.contains("Голос Ирины"))
        }
    }

    @Test
    fun `у каждого действия окна есть живой обработчик на экране`() {
        // Порядок тот же, что в Callbacks: новое действие обязано прийти с экрана, иначе
        // строка окна станет мёртвой кнопкой. Ссылка на метод (`::chooseModel`) и вызов
        // (`chooseModel(`) равнозначны: важно, что обработчик существует и передан окну.
        listOf(
            "startNewLocalChat",
            "openSettings",
            "chooseModel",
            "chooseReasoning",
            "openTurnSettings",
            "openVoiceMode",
            "openVoiceProsody",
            "toggleWakeService",
            "LocalMemoryActivity.newIntent",
        ).forEach { handler ->
            assertTrue(
                "на экране нет обработчика окна: $handler",
                Regex("""(::|\.)${Regex.escape(handler)}\b|\b${Regex.escape(handler)}\s*\(""")
                    .containsMatchIn(activity),
            )
        }
    }

    @Test
    fun `экран отдаёт окно шторке, а не собирает его сам`() {
        val code = codeOnly(activity)
        assertTrue(
            "окно обязано открываться через LocalChatSettingsSheet.show",
            code.contains("LocalChatSettingsSheet.show("),
        )
        assertTrue(code.contains("LocalChatSettingsSheet.Content("))
        assertTrue(code.contains("LocalChatSettingsSheet.Callbacks("))

        // Вёрстка карточки контекста и подписи строк переехали в шторку и политику. Если они
        // вернутся в экран, появятся две копии окна — и разойдутся по значениям, как это уже
        // случилось с подписью про офлайн-голос.
        listOf(
            "Заполнение контекста",
            "Живой диалог с орбом и перебиванием",
            "Память владельца и факты",
            "Слух с погашенным экраном",
        ).forEach { literal ->
            assertFalse("литерал окна вернулся в экран: $literal", code.contains(literal))
        }
    }

    @Test
    fun `все девять действий окна переданы с экрана`() {
        val code = codeOnly(activity)
        listOf(
            "onNewDialog",
            "onProviderSettings",
            "onModel",
            "onReasoning",
            "onSampling",
            "onVoiceMode",
            "onProsody",
            "onWakeService",
            "onMemory",
        ).forEach { callback ->
            assertTrue(
                "окно осталось без действия: $callback",
                Regex("""\b$callback\s*=""").containsMatchIn(code),
            )
        }
    }
}
