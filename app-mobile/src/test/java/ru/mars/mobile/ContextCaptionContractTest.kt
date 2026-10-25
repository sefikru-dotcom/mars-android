package ru.mars.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт видимости порога автосжатия в композере (mobile).
 *
 * Диагноз: [ContextBudgetPolicy.pill] формировал `text` («25% · 50.0k/200.0k») и
 * `autoCompression` («Автосжатие при 160.0k · 80%»), но НИ ОДИН view их не читал —
 * владелец видел только цифру процента внутри 24dp-кольца, а порог — лишь после тапа
 * по кольцу. Поведение текста закреплено JVM-тестами [ContextBudgetPolicyTest];
 * instrumented-слоя нет, поэтому проводка закреплена как source-контракт (стиль
 * [ChatClickPathContractTest]): тест читает реальные production-исходники и проверяет,
 * что подпись действительно создаётся в каркасе, доезжает до Activity и обновляется
 * из чистой политики. Так «никто не отрисовывает» не вернётся молча.
 */
class ContextCaptionContractTest {

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

    private val scaffold by lazy { source("src/main/java/ru/mars/mobile/ChatScreenScaffold.kt") }
    private val shell by lazy { source("src/main/java/ru/mars/mobile/ChatShellSection.kt") }
    private val modelSection by lazy { source("src/main/java/ru/mars/mobile/ChatModelSection.kt") }
    private val activity by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }

    @Test
    fun `scaffold builds a caption next to the ring and exposes it to the activity`() {
        assertTrue(
            "Каркас обязан создавать подпись контекста (не только кольцо)",
            scaffold.contains("val contextCaption = TextView(context)"),
        )
        assertTrue(
            "Кольцо и подпись живут в одной строке: подпись рядом с кольцом",
            scaffold.contains("contextRow.addView(contextRing") &&
                scaffold.contains("contextRow.addView(") &&
                scaffold.contains("contextCaption,"),
        )
        assertTrue(
            "Строка контекста обязана попадать в капсулу композера",
            scaffold.contains("composer.addView(contextRow"),
        )
        assertTrue(
            "Подпись и её строка отдаются наружу в ComposerViews",
            scaffold.contains("val contextCaption: TextView") && scaffold.contains("val contextRow: LinearLayout"),
        )
        assertTrue(
            "Подпись не обрезается в одну строку: перенос на вторую (maxLines 2)",
            scaffold.contains("maxLines = 2"),
        )
    }

    @Test
    fun `caption is carried from the scaffold to the activity`() {
        assertTrue(
            "Activity обязана держать поля подписи и её строки",
            activity.contains("internal var contextCaption: TextView?") &&
                activity.contains("internal var contextRow: LinearLayout?"),
        )
        assertTrue(
            "Проводка каркас -> Activity идёт через ChatShellSection",
            shell.contains("contextCaption = v.composer.contextCaption") &&
                shell.contains("contextRow = v.composer.contextRow"),
        )
    }

    @Test
    fun `caption text comes from the pure policy and is applied to the view`() {
        assertTrue(
            "Текст подписи решает ContextBudgetPolicy.composerLabel (JVM-тесты), а не view",
            modelSection.contains(
                "ContextBudgetPolicy.composerLabel(uiState.contextUsage, compressionRunning)"
            ),
        )
        assertTrue(
            "Подпись обязана реально отрисовываться (текст + видимость)",
            modelSection.contains("caption.text = label") &&
                modelSection.contains("caption.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE"),
        )
        assertTrue(
            "Без замера скрывается вся строка, а не остаётся пустой подписью",
            modelSection.contains(
                "contextRow?.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE"
            ),
        )
        assertTrue(
            "TalkBack получает описание из политики (порог озвучивается)",
            modelSection.contains("caption.contentDescription = pill.contentDescription"),
        )
        assertTrue(
            "Тап по подписи открывает те же детали контекста, что и кольцо",
            scaffold.contains("setOnClickListener { host.showContextUsageDetails() }"),
        )
    }
}
