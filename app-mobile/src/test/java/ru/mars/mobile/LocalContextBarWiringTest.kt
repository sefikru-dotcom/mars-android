package ru.mars.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки полосы контекста в локальном чате.
 *
 * Диагноз §3 контракта волны 10: владелец видел «3.1k / 1M · 0%» — цифру без шкалы.
 * Поведение процента и порогов уже закреплено JVM-тестами ([LocalContextPolicyTest]);
 * instrumented-слоя нет, поэтому проводка закреплена как source-контракт (тот же приём,
 * что в [ContextCaptionContractTest]): тест читает production-исходники и проверяет, что
 * полоса действительно создаётся, кладётся в строку контекста и получает цвет из
 * [LocalContextPolicy]. Так «нарисовали класс, но никто его не зовёт» не вернётся молча.
 */
class LocalContextBarWiringTest {

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

    private val bar by lazy { source("src/main/java/ru/mars/mobile/ContextFillBarView.kt") }
    private val composer by lazy { source("src/main/java/ru/mars/mobile/LocalChatComposerBar.kt") }
    private val picker by lazy { source("src/main/java/ru/mars/mobile/LocalChatModelPicker.kt") }

    @Test
    fun `bar is a View that draws percent by threshold colour`() {
        assertTrue(
            "Полоса — View, а не ProgressBar с drawable: цвет порога меняется программно",
            bar.contains("class ContextFillBarView(context: Context) : View(context)"),
        )
        assertTrue("Процент и цвет приходят готовыми", bar.contains("fun render(percent: Int?, color: Int)"))
        assertTrue(
            "Цвет порога берётся из уровня политики, а не из второй палитры",
            bar.contains("LocalContextLevel.WARNING -> MarsTheme.STATUS_WARNING") &&
                bar.contains("LocalContextLevel.CRITICAL -> MarsTheme.STATUS_ERROR"),
        )
        assertTrue(
            "Ширина ограничена, чтобы на широком экране полоса не тянулась через строку",
            bar.contains("MAX_WIDTH_DP"),
        )
    }

    @Test
    fun `composer puts bar, tokens label and percent into the context row`() {
        assertTrue("Полоса создаётся в композере", composer.contains("bar = ContextFillBarView(context)"))
        assertTrue("Полоса добавляется в строку контекста ровно одним addView", composer.contains("contextRow.addView(\n            bar,"))
        assertTrue("Подпись токенов «3.1k / 1M» рядом", composer.contains("contextRow.addView(tokensLabel"))
        assertTrue("Процент рядом", composer.contains("contextRow.addView(percentLabel"))
        assertTrue(
            "Полоса взвешена и ограничена по высоте: строку на POCO F5 не растягивает",
            composer.contains("LayoutParams(0, dp(ContextFillBarView.BAR_HEIGHT_DP), 1f)"),
        )
        assertTrue(
            "min-width 0 у подписей — иначе длинная подпись выдавливает полосу за край",
            composer.contains("minimumWidth = 0"),
        )
        assertTrue(
            "Цвет процента и заливки — один и тот же",
            composer.contains("val tone = ContextFillBarView.toneOf(view.level)") &&
                composer.contains("percentLabel.setTextColor(tone)"),
        )
    }

    @Test
    fun `picker draws sections by family and passes whole entry to the row`() {
        assertTrue(
            "Секции считает политика, а не разметка",
            picker.contains("LocalModelPickerPolicy.sections(filtered, preferredId = current)"),
        )
        assertTrue("Заголовок группы рисуется", picker.contains("groupHeader("))
        assertTrue(
            "Заголовок получает секцию целиком: площадка и счётчик — из политики",
            picker.contains("section = section,"),
        )
        assertTrue(
            "Группа сворачиваема: состояние раскрытости уходит в заголовок",
            picker.contains("expanded = isOpen"),
        )
        // Сравнение по фрагментам, а не по многострочному литералу: файл на Windows
        // хранится с CRLF, и `contains` со строкой через \n падал на живом, верном коде.
        assertTrue(
            "Строка получает entry целиком: название, назначение, зрение и окно из одного источника",
            picker.contains("private fun row(") && picker.contains("entry: LocalPickerEntry,"),
        )
        assertTrue("Метка зрения в строке", picker.contains("pillLabel(activity, \"vision\""))
    }
}