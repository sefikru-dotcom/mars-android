package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт shell-миграции экрана «Мониторинг» (InsightsActivity, волна 4).
 *
 * Проверяет, что экран:
 * - сидит на общем [SectionScaffold] с ОСОБОЙ компактной шапкой монитора
 *   (HeaderStyle.COMPACT: бессрочные кнопки, заголовок 17sp, точка-индикатор
 *   вместо спиннера) и не дублирует safe-area/кнопки/прогресс локально;
 * - сохраняет отдельные статусы вкладок Insights/Logs (свои презентеры и свои
 *   статус-боксы), retry/offline/no-auth семантику (общие [SectionStateViews]
 *   через [InsightsPolicy.statusStateFor]/[LogsPolicy.statusStateFor],
 *   «Повторить» отдельным чипом при offline-safe stale-данных);
 * - доступен: notice в статус-строке shell — polite live-region, вкладки и
 *   чипы раскрывают selected-состояние (isSelected), retry-focus по [RetryFocusPolicy];
 * - сохраняет focus/motion behavior: reveal списка и анимация переключения
 *   вкладок через [MotionPolicy] с уважением reduce-motion.
 *
 * Instrumented-слоя нет, поэтому проводка закреплена как source-контракт
 * (стиль KanbanShellContractTest): тест читает РЕАЛЬНЫЙ production-исходник.
 * Поведенческая часть — маппинг фаз в [SectionScreenState].
 */
class InsightsShellContractTest {

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

    private val insights by lazy { source("src/main/java/ru/mars/mobile/InsightsActivity.kt") }
    private val scaffold by lazy { source("src/main/java/ru/mars/mobile/SectionScaffold.kt") }
    private val stateViews by lazy { source("src/main/java/ru/mars/mobile/SectionStateViews.kt") }

    // --- Экран на общем shell, особая шапка сохранена в shell-варианте --------------

    @Test
    fun `monitor screen is built on the shared shell with the compact header`() {
        assertTrue(
            "Экран обязан создаваться через SectionScaffold.create с заголовком политики",
            insights.contains("SectionScaffold.create(") && insights.contains("InsightsPolicy.SCREEN_TITLE")
        )
        assertTrue(
            "Особая шапка монитора обязана запрашиваться компактным стилем shell",
            insights.contains("SectionScaffold.HeaderStyle.COMPACT")
        )
        assertTrue(
            "«Обновить» shell обязан перезагружать активную вкладку",
            insights.contains("::refreshActiveTab")
        )
        assertTrue(
            "Контент собирается в общий слот shell",
            insights.contains("scaffold.content.addView") && insights.contains("return scaffold.root")
        )
    }

    @Test
    fun `screen no longer hand-rolls insets, header buttons or its own progress view`() {
        assertFalse(
            "Shell владеет safe-area — локальный insets-листенер запрещён",
            insights.contains("ViewCompat.setOnApplyWindowInsetsListener")
        )
        assertFalse(
            "Shell владеет кнопками шапки — локальный circleIconButton запрещён",
            insights.contains("MarsTheme.circleIconButton") || insights.contains("R.drawable.ic_arrow_back")
        )
        assertFalse(
            "Shell владеет индикатором загрузки — локальный ProgressBar/progressView запрещён",
            insights.contains("ProgressBar(this)") || insights.contains("progressView")
        )
        assertTrue("Индикатор шапки обязан браться из shell", insights.contains("scaffold.progress"))
    }

    @Test
    fun `shell keeps the special compact header and the standard one intact`() {
        assertTrue(
            "SectionScaffold обязан предлагать вариант шапки",
            scaffold.contains("enum class HeaderStyle") &&
                scaffold.contains("STANDARD") && scaffold.contains("COMPACT")
        )
        assertTrue(
            "COMPACT-шапка обязана сохранять точку-индикатор монитора (INVISIBLE в покое)",
            scaffold.contains("MarsTheme.statusDot(activity, MarsTheme.ACCENT)") &&
                scaffold.contains("View.INVISIBLE")
        )
        assertTrue(
            "COMPACT-заголовок обязан остаться компактным (17sp)",
            scaffold.contains("textSize = 17f")
        )
        assertTrue(
            "COMPACT-кнопки обязаны остаться без фона/обводки",
            scaffold.contains("bg = if (compact) 0 else MarsTheme.SURFACE") &&
                scaffold.contains("strokeColor = if (compact) 0 else MarsTheme.BORDER")
        )
        assertTrue(
            "STANDARD-шапка обязана сохранить спиннер с озвученной подписью",
            scaffold.contains("ProgressBar(activity)") && scaffold.contains("contentDescription = \"Загрузка\"")
        )
        assertTrue(
            "Заголовок шапки обязан оставаться заголовком доступности",
            scaffold.contains("isAccessibilityHeading = true")
        )
    }

    @Test
    fun `header indicator follows the active tab and refresh reloads it`() {
        assertTrue(
            "Индикатор вкладки Insights обязан отражать её LOADING (точка: INVISIBLE в покое)",
            insights.contains(
                "scaffold.progress.visibility = " +
                    "if (state.phase == InsightsPhase.LOADING) View.VISIBLE else View.INVISIBLE"
            )
        )
        assertTrue(
            "Индикатор вкладки Logs обязан отражать её LOADING",
            insights.contains(
                "scaffold.progress.visibility = " +
                    "if (state.phase == LogsPhase.LOADING) View.VISIBLE else View.INVISIBLE"
            )
        )
        assertTrue(
            "refreshActiveTab обязан перезагружать именно активную вкладку",
            Regex("private fun refreshActiveTab\\(\\)[\\s\\S]{0,220}?insightsPresenter\\.load\\(\\)[\\s\\S]{0,220}?logsPresenter\\.load\\(\\)")
                .containsMatchIn(insights)
        )
    }

    // --- Live-регионы: notice в статус-строке shell, тексты фаз — общие --------------

    @Test
    fun `notice rides the shell status line which is a polite live region`() {
        assertTrue(
            "Notice активной вкладки обязан публиковаться в статус-строке shell",
            insights.contains("scaffold.statusLine.text = notice") &&
                insights.contains("scaffold.statusLine.visibility = View.GONE") &&
                insights.contains("MarsTheme.STATUS_WARNING")
        )
        assertTrue(
            "Статус-строка shell обязана быть polite live-region (TalkBack озвучивает notice)",
            Regex("statusLine = TextView\\(activity\\)\\.apply \\{[\\s\\S]{0,400}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE")
                .containsMatchIn(scaffold)
        )
    }

    @Test
    fun `phase messages render through shared live-region state views`() {
        assertTrue(
            "Центральные тексты фаз обязаны рендериться общими SectionStateViews",
            insights.contains("SectionStateViews.loading(") &&
                insights.contains("SectionStateViews.empty(") &&
                insights.contains("SectionStateViews.error(") &&
                insights.contains("SectionStateViews.offline(")
        )
        assertTrue(
            "Общие статус-виды обязаны озвучивать смену текста (polite live region)",
            stateViews.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE")
        )
    }

    // --- Отдельные статусы вкладок, retry/offline/no-auth -----------------------------

    @Test
    fun `each tab keeps its own presenter and its own status box`() {
        assertTrue(
            "У вкладок обязаны остаться раздельные презентеры",
            insights.contains("insightsPresenter.subscribe { state -> runOnUiThread { renderInsights(state) } }") &&
                insights.contains("logsPresenter.subscribe { state -> runOnUiThread { renderLogs(state) } }")
        )
        assertTrue(
            "У вкладок обязаны остаться раздельные статус-боксы",
            insights.contains("insightsStatusBox") && insights.contains("logsStatusBox") &&
                insights.contains("if (tab == MonitorTab.INSIGHTS) insightsStatusBox else logsStatusBox")
        )
        assertTrue(
            "Маппинг фаз в общие состояния обязан идти через политику каждой вкладки",
            insights.contains("InsightsPolicy.statusStateFor(state)") &&
                insights.contains("LogsPolicy.statusStateFor(state)")
        )
    }

    @Test
    fun `retry stays per tab and offline-safe stale data keeps a standalone chip`() {
        assertTrue(
            "«Повторить» вкладки Insights обязан грузить её презентер",
            Regex("onRetry = \\{ lifecycleScope\\.launch \\{ insightsPresenter\\.load\\(\\) \\} \\}")
                .containsMatchIn(insights)
        )
        assertTrue(
            "«Повторить» вкладки Logs обязан грузить её презентер",
            Regex("onRetry = \\{ lifecycleScope\\.launch \\{ logsPresenter\\.load\\(\\) \\} \\}")
                .containsMatchIn(insights)
        )
        assertTrue(
            "Offline-safe stale-данные обязаны показывать «Повторить» отдельным чипом",
            insights.contains("InsightsPolicy.RETRY_LABEL") &&
                insights.contains("LogsPolicy.RETRY_LABEL") &&
                insights.contains("SectionStateViews.RETRY_TAG")
        )
        assertTrue(
            "Контрол «Повторить» общего error-вида обязан носить тег для поиска фокусом",
            stateViews.contains("const val RETRY_TAG") && stateViews.contains("tag = RETRY_TAG")
        )
        assertTrue(
            "Offline-детектор экрана обязан остаться (семантика презентеров не меняется)",
            insights.contains("isDeviceOffline()") &&
                insights.contains("isOffline = ::isDeviceOffline")
        )
    }

    // --- isSelected: вкладки и чипсы --------------------------------------------------

    @Test
    fun `tabs expose selected state to accessibility services`() {
        assertTrue(
            "switchTab обязан выставлять isSelected на вкладках (TalkBack: «выбрано»)",
            insights.contains("chip.isSelected = selected")
        )
        assertTrue(
            "Вкладки обязаны остаться кликабельными и фокусируемыми",
            Regex("private fun buildTabs\\(\\)[\\s\\S]{0,900}?isClickable = true[\\s\\S]{0,200}?isFocusable = true")
                .containsMatchIn(insights)
        )
        assertTrue(
            "Выбранная вкладка обязана сохранять визуальный акцент и подпись иконкой",
            insights.contains("MarsTheme.ACCENT_BG") && insights.contains("MarsTheme.ACCENT_TEXT") &&
                insights.contains("R.drawable.ic_insights") && insights.contains("R.drawable.ic_logs")
        )
    }

    @Test
    fun `period, file and tail chips expose selected state`() {
        assertTrue(
            "styleChip обязан выставлять isSelected на чипах периода/файла/tail",
            Regex("private fun styleChip\\(chip: View\\?, selected: Boolean\\)[\\s\\S]{0,200}?textView\\.isSelected = selected")
                .containsMatchIn(insights)
        )
        assertTrue(
            "Чипы периода обязаны сравниваться с выбранным периодом состояния",
            insights.contains("selected = days == state.periodDays")
        )
        assertTrue(
            "Чипы файла/tail обязаны сравниваться с whitelisted выбором состояния",
            insights.contains("selected = file == state.file") && insights.contains("selected = tail == state.tail")
        )
    }

    // --- Retry focus ------------------------------------------------------------------

    @Test
    fun `retry focus is decided by pure policy and lands on the tagged control`() {
        assertTrue(
            "Решение о retry-focus обязано принимать чистая RetryFocusPolicy",
            insights.contains("RetryFocusPolicy.shouldMoveFocus(")
        )
        assertTrue(
            "Фокус обязан переноситься только при активном touch exploration",
            insights.contains("isTouchExplorationEnabled")
        )
        assertTrue(
            "Контрол «Повторить» обязан искаться по общему тегу",
            insights.contains("findViewWithTag<View>(SectionStateViews.RETRY_TAG)")
        )
        assertTrue(
            "Перенос фокуса обязан озвучиваться (requestFocus + TYPE_VIEW_FOCUSED)",
            insights.contains("retry.requestFocus()") &&
                insights.contains("sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)")
        )
        assertTrue(
            "Видимость «Повторить» обязана учитываться по вкладкам (фокус — на появлении)",
            insights.contains("retryVisibleByTab")
        )
    }

    // --- Focus/motion behavior сохранены ----------------------------------------------

    @Test
    fun `tab switch and list reveal keep reduce-motion-respecting animation`() {
        assertTrue(
            "Reveal списков при первом наполнении обязан остаться",
            insights.contains("revealAnimated(insightsList)") && insights.contains("revealAnimated(logsLines)")
        )
        assertTrue(
            "Анимации обязаны идти через MotionPolicy (MESSAGE_REVEAL)",
            insights.contains("MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, false)")
        )
        assertTrue(
            "Reduce-motion обязан уважаться (системная настройка анимации)",
            insights.contains("MotionPolicy.isReducedMotion(") &&
                insights.contains("Settings.Global.ANIMATOR_DURATION_SCALE")
        )
        assertTrue(
            "Анимация переключения вкладок обязана гаситься при reduce-motion",
            insights.contains("if (animate && !isReducedMotion())")
        )
    }

    @Test
    fun `logs tab still loads lazily and the tab intent contract is intact`() {
        assertTrue(
            "Логи обязаны грузиться лениво — при первом открытии вкладки",
            insights.contains("if (tab == MonitorTab.LOGS && !logsLoadedOnce)") &&
                insights.contains("logsLoadedOnce = true")
        )
        assertTrue(
            "Публичная точка входа (newIntent + EXTRA_TAB) обязана сохраниться",
            insights.contains("const val EXTRA_TAB") &&
                insights.contains("fun newIntent(context: Context, tab: MonitorTab = MonitorTab.INSIGHTS)") &&
                insights.contains("MonitorTab.valueOf(it)")
        )
    }

    @Test
    fun `screen stays read-only`() {
        val repoCalls = Regex("(?<![.\\w])(insightsRepository|logsRepository)\\.(\\w+)")
            .findAll(insights).map { it.groupValues[2] }.toSet()
        assertTrue("Экран обязан вызывать только GET-порты репозиториев", repoCalls.all { it == "getInsights" || it == "getLogs" })
        assertTrue(
            "Оба GET-порта обязаны остаться подключёнными",
            repoCalls.contains("getInsights") && repoCalls.contains("getLogs")
        )
        val credentialCalls = Regex("(?<![.\\w])credentialStore\\.(\\w+)").findAll(insights).map { it.groupValues[1] }.toSet()
        assertEquals("Credential-стор используется только на чтение", setOf("read"), credentialCalls)
    }

    // --- Поведенческий маппинг фаз -----------------------------------------------------

    @Test
    fun `insights status mapping keeps phase semantics`() {
        // READY: центрального статуса нет.
        assertNull(InsightsPolicy.statusStateFor(InsightsUiState(phase = InsightsPhase.READY)))
        // LOADING без сообщения (тихий refresh поверх живых строк): общего вида нет.
        assertNull(InsightsPolicy.statusStateFor(InsightsUiState(phase = InsightsPhase.LOADING)))
        assertEquals(
            SectionScreenState.Loading,
            InsightsPolicy.statusStateFor(InsightsUiState(phase = InsightsPhase.LOADING, message = InsightsPolicy.MSG_LOADING))
        )
        assertEquals(
            SectionScreenState.Empty(InsightsPolicy.MSG_EMPTY),
            InsightsPolicy.statusStateFor(InsightsUiState(phase = InsightsPhase.EMPTY, message = InsightsPolicy.MSG_EMPTY))
        )
        // NO_AUTH: offline-тон, без retry (canRetry у состояния false).
        assertEquals(
            SectionScreenState.Offline(InsightsPolicy.MSG_NO_AUTH),
            InsightsPolicy.statusStateFor(InsightsUiState(phase = InsightsPhase.NO_AUTH, message = InsightsPolicy.MSG_NO_AUTH))
        )
        val failure = InsightsUiState(phase = InsightsPhase.ERROR, message = InsightsPolicy.MSG_ERROR, canRetry = true)
        assertEquals(SectionScreenState.Error(InsightsPolicy.MSG_ERROR), InsightsPolicy.statusStateFor(failure))
        val offlineFailure = InsightsUiState(phase = InsightsPhase.ERROR, message = InsightsPolicy.MSG_ERROR_OFFLINE, canRetry = true)
        assertEquals(SectionScreenState.Error(InsightsPolicy.MSG_ERROR_OFFLINE), InsightsPolicy.statusStateFor(offlineFailure))
        // Offline-safe stale: строки живы, сообщения нет — общего вида нет, «Повторить» чипом.
        val stale = InsightsUiState(
            phase = InsightsPhase.ERROR,
            rows = listOf(InsightsRow.Section("Итоги за период")),
            notice = InsightsPolicy.NOTICE_OFFLINE_STALE,
            message = null,
            canRetry = true,
        )
        assertNull(InsightsPolicy.statusStateFor(stale))
        assertTrue("stale-состояние обязано допускать повтор", stale.canRetry)
    }

    @Test
    fun `logs status mapping keeps phase semantics`() {
        assertNull(LogsPolicy.statusStateFor(LogsUiState(phase = LogsPhase.READY)))
        assertNull(LogsPolicy.statusStateFor(LogsUiState(phase = LogsPhase.LOADING)))
        assertEquals(
            SectionScreenState.Loading,
            LogsPolicy.statusStateFor(LogsUiState(phase = LogsPhase.LOADING, message = LogsPolicy.MSG_LOADING))
        )
        assertEquals(
            SectionScreenState.Empty(LogsPolicy.MSG_EMPTY),
            LogsPolicy.statusStateFor(LogsUiState(phase = LogsPhase.EMPTY, message = LogsPolicy.MSG_EMPTY))
        )
        assertEquals(
            SectionScreenState.Offline(LogsPolicy.MSG_NO_AUTH),
            LogsPolicy.statusStateFor(LogsUiState(phase = LogsPhase.NO_AUTH, message = LogsPolicy.MSG_NO_AUTH))
        )
        val failure = LogsUiState(phase = LogsPhase.ERROR, message = LogsPolicy.MSG_ERROR, canRetry = true)
        assertEquals(SectionScreenState.Error(LogsPolicy.MSG_ERROR), LogsPolicy.statusStateFor(failure))
        val invalid = LogsUiState(phase = LogsPhase.ERROR, message = LogsPolicy.msgInvalidRequest("Unknown log file"), canRetry = true)
        assertEquals(
            SectionScreenState.Error(LogsPolicy.msgInvalidRequest("Unknown log file")),
            LogsPolicy.statusStateFor(invalid)
        )
        // Offline-safe stale: строки живы, сообщения нет — «Повторить» отдельным чипом.
        val stale = LogsUiState(
            phase = LogsPhase.ERROR,
            lines = listOf("last line"),
            notice = LogsPolicy.NOTICE_OFFLINE_STALE,
            message = null,
            canRetry = true,
        )
        assertNull(LogsPolicy.statusStateFor(stale))
        assertTrue("stale-состояние обязано допускать повтор", stale.canRetry)
    }
}
