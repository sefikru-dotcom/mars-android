package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт окна контекста и композера (mobile) после UX-волны 16.
 *
 * Что изменилось по сравнению с прежней версией теста (полоса под шапкой):
 * - полоса `contextRow` ПОД ШАПКОЙ убрана с экрана совсем (пункт 5 владельца) — она
 *   держится `GONE` и нулевой высоты, но поля `contextRow`/`contextCaption` остаются
 *   в `ComposerViews` и в Activity: на них по-прежнему смотрят update*-методы;
 * - кольцо контекста и подпись переехали в единственную кнопку-капсулу «Контекст»
 *   внизу композера (`composerBottomBar`), кольцо видно ВСЕГДА (без замера — 0%),
 *   иначе вместе с ним с экрана пропадал бы вход в окно контекста;
 * - всё второстепенное (модель, мышление, режим, воркспейс, инструменты, квота,
 *   расход, поиск, озвучка) собрано в стеклянное окно `showContextUsageDetails()`
 *   на [MarsDialog] и разложено по трём разделам.
 *
 * Instrumented-слоя в проекте нет, поэтому проводка закреплена как source-контракт
 * (стиль [ChatClickPathContractTest]): тест читает реальные production-исходники и
 * проверяет, что вход в окно, тексты и обработчики действительно wired в UI-код.
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
    private val ring by lazy { source("src/main/java/ru/mars/mobile/ContextRingView.kt") }

    /** Блок кода от [start] до [end] — чтобы проверять содержимое конкретного view. */
    private fun block(src: String, start: String, end: String): String {
        val from = src.indexOf(start)
        assertTrue("Не найден блок: $start", from >= 0)
        val to = src.indexOf(end, from)
        assertTrue("Не найден конец блока: $end", to > from)
        return src.substring(from, to)
    }

    @Test
    fun `scaffold builds the context capsule with the ring and the caption`() {
        assertTrue(
            "Каркас обязан создавать подпись контекста (не только кольцо)",
            scaffold.contains("val contextCaption = TextView(context)"),
        )
        assertTrue(
            "Кольцо и подпись живут в одной кнопке-капсуле «Контекст»",
            scaffold.contains("contextCapsule.addView(contextRing") &&
                scaffold.contains("contextCapsule.addView(") &&
                scaffold.contains("contextCaption,"),
        )
        assertTrue(
            "Кольцо в капсуле — компактное 24dp (не крупная кнопка)",
            scaffold.contains("contextCapsule.addView(contextRing, LinearLayout.LayoutParams(dp(24), dp(24)))"),
        )
        assertTrue(
            "Подпись и её (скрытая) строка отдаются наружу в ComposerViews",
            scaffold.contains("val contextCaption: TextView") && scaffold.contains("val contextRow: LinearLayout"),
        )
        assertTrue(
            "Подпись — одна строка с ellipsis, без переноса",
            scaffold.contains("maxLines = 1") &&
                scaffold.contains("ellipsize = android.text.TextUtils.TruncateAt.END"),
        )
    }

    @Test
    fun `context strip under the header is gone but the fields stay wired`() {
        assertTrue(
            "Полоса контекста больше не показывается под шапкой: GONE в каркасе",
            block(
                scaffold,
                "val contextRow = LinearLayout(context).apply {",
                "// Баннер сетевого статуса",
            ).contains("visibility = View.GONE"),
        )
        assertTrue(
            "Полоса добавлена в корень с нулевой высотой (места на экране не занимает)",
            scaffold.contains("root.addView(contextRow, LinearLayout.LayoutParams(-1, 0))"),
        )
        assertFalse(
            "Полоса контекста не переезжает в капсулу композера",
            scaffold.contains("composer.addView(contextRow"),
        )
        assertTrue(
            "updateContextIndicator держит полосу скрытой при любом замере",
            modelSection.contains("contextRow?.visibility = View.GONE"),
        )
        assertFalse(
            "Возврата полосы в видимое состояние быть не должно",
            modelSection.contains("contextRow?.visibility = View.VISIBLE"),
        )
        assertTrue(
            "Activity по-прежнему держит поля подписи и её строки",
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
    fun `context capsule opens the context window from the bottom of the composer`() {
        val capsule = block(scaffold, "val contextCapsule = LinearLayout(context).apply {", "// Воздух между")
        assertTrue(
            "Тап по капсуле открывает окно контекста",
            capsule.contains("setOnClickListener { host.showContextUsageDetails() }"),
        )
        assertTrue(
            "Капсула стоит в нижней панели капсулы композера",
            scaffold.contains("composerBottomBar.addView(contextCapsule, LinearLayout.LayoutParams(-2, -2))"),
        )
        assertTrue(
            "Кольцо и подпись тоже открывают окно контекста (паритет с прежним кольцом)",
            scaffold.contains("setOnClickListener { host.showContextUsageDetails() }"),
        )
    }

    @Test
    fun `context ring is visible even before the first measurement`() {
        assertTrue(
            "Кольцо обязано быть видимым всегда — это вход в окно контекста",
            ring.contains("visibility = VISIBLE"),
        )
        assertFalse(
            "Кольцо не должно скрываться по пустому замеру",
            ring.contains("visibility = GONE"),
        )
        assertTrue(
            "Без замера кольцо показывает честный 0%, а не пустое место",
            ring.contains("label = \"0\""),
        )
        assertTrue(
            "TalkBack получает описание из политики (или нейтральное, пока замера нет)",
            ring.contains("contentDescription = pill.contentDescription"),
        )
    }

    @Test
    fun `composer bottom bar keeps only context, attach, voice and the send star`() {
        assertTrue(
            "Скрепка, голосовой ввод и кнопка отправки — один размер 36dp",
            listOf("attachBtn", "voiceButton", "sendButton").all { name ->
                Regex("actionsRow\\.addView\\(\\s*$name,\\s*LinearLayout\\.LayoutParams\\(dp\\(36\\), dp\\(36\\)\\)")
                    .containsMatchIn(scaffold)
            },
        )
        assertTrue(
            "Кнопка отправки несёт фирменную звезду (ic_send)",
            scaffold.contains("R.drawable.ic_send"),
        )
        assertTrue(
            "Чипы сессии спрятаны с экрана, но живут в дереве (обработчики сохранены)",
            block(
                scaffold,
                "val chipsScroll = HorizontalScrollView(context).apply {",
                "val chipsRow = LinearLayout(context).apply {",
            ).contains("visibility = View.GONE"),
        )
        assertTrue(
            "Расход сессии и чипы собраны в скрытую легаси-полосу",
            scaffold.contains("val legacyControlsStrip = LinearLayout(context).apply {") &&
                scaffold.contains("legacyControlsStrip.addView("),
        )
        assertTrue(
            "Кнопка поиска спрятана с панели (вход — в окне контекста)",
            block(scaffold, "val searchEntryBtn = MarsTheme.circleIconButton(", "actionsRow.addView(searchEntryBtn")
                .contains("visibility = View.GONE"),
        )
        assertTrue(
            "Кнопка озвучки спрятана с панели (вход — в окне контекста)",
            block(scaffold, "val volumeButton = MarsTheme.circleIconButton(", "actionsRow.addView(volumeButton")
                .contains("visibility = View.GONE"),
        )
        assertTrue(
            "Полоса быстрых подсказок тоже скрыта: над полем ввода один ряд чипов",
            block(scaffold, "val quickChipsScroll = HorizontalScrollView(context).apply {", "val quickBox =")
                .contains("visibility = View.GONE"),
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
                modelSection.contains("caption.visibility = View.VISIBLE"),
        )
        assertTrue(
            "TalkBack получает описание из политики (порог озвучивается)",
            modelSection.contains("caption.contentDescription = pill.contentDescription"),
        )
    }

    @Test
    fun `context window lays everything out in three sections`() {
        assertTrue(
            "Окно контекста — стеклянная шторка MarsDialog, а не системный AlertDialog",
            modelSection.contains("MarsDialog.show(activity)") &&
                !modelSection.contains("setTitle(\"Окно контекста\")"),
        )
        assertTrue(
            "Три раздела окна: контекст и расход, параметры сессии, режим/поиск/озвучка",
            modelSection.contains("ОКНО КОНТЕКСТА И РАСХОД") &&
                modelSection.contains("ПАРАМЕТРЫ СЕССИИ И МОДЕЛИ") &&
                modelSection.contains("РЕЖИМ ЧАТА, ПОИСК И ОЗВУЧКА"),
        )
        assertTrue(
            "Раздел 1: заполнение, расход и стоимость сессии, ручное сжатие",
            modelSection.contains("ContextBudgetPolicy.detailsText(usage)") &&
                modelSection.contains("SessionUsagePolicy.view(sessionUsage)") &&
                modelSection.contains("Сжать контекст сейчас") &&
                modelSection.contains("Фокус сжатия (необязательно)") &&
                modelSection.contains("Автосжатие контекста при"),
        )
        assertTrue(
            "Раздел 2: строки-карточки модели, мышления, воркспейса, инструментов и квоты",
            modelSection.contains("paramRow(\"Модель\"") &&
                modelSection.contains("modelSelection.buttonLabel() ?: \"По умолчанию\"") &&
                modelSection.contains("chooseModel()") &&
                modelSection.contains("paramRow(\"Уровень размышлений\"") &&
                modelSection.contains("reasoningSelection.buttonLabel() ?: \"Авто\"") &&
                modelSection.contains("chooseReasoningEffort()") &&
                modelSection.contains("paramRow(\"Рабочее пространство\"") &&
                modelSection.contains("workspaceSelection.buttonLabel() ?: \"Авто\"") &&
                modelSection.contains("chooseWorkspace()") &&
                modelSection.contains("paramRow(\"Инструменты сессии\", toolsetsPresenter.chipLabel())") &&
                modelSection.contains("showToolsetsDialog()") &&
                modelSection.contains("paramRow(\"Квота провайдера\", quotaPresenter.model().label)") &&
                modelSection.contains("showQuotaDetails()"),
        )
        assertTrue(
            "Раздел 3: режим активности, поиск по диалогу, озвучка и автоозвучка",
            modelSection.contains("paramRow(\"Режим активности\", activityModeSelection.buttonLabel())") &&
                modelSection.contains("chooseActivityMode()") &&
                modelSection.contains("Найти текст в сообщениях") &&
                modelSection.contains("openTranscriptSearch()") &&
                modelSection.contains("Прочитать последний ответ вслух") &&
                modelSection.contains("speakLastMessage()") &&
                modelSection.contains("Автоозвучивание ответов") &&
                modelSection.contains("toggleAutoSpeak()"),
        )
        assertTrue(
            "Тап по строке-карточке закрывает окно и открывает выбор",
            modelSection.contains("closeWindow()") &&
                modelSection.contains("fun closeWindow()"),
        )
    }
}
