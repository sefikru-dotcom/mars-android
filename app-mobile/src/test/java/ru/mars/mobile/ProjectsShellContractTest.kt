package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт перевода экрана «Проекты» на общий shell разделов ([SectionScaffold]):
 * shell используется и НЕ дублируется экраном, а офлайн-кэш и retry/статусная
 * семантика сохранены при миграции.
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена как
 * контракт по РЕАЛЬНОМУ production-исходнику (паттерн [ChatClickPathContractTest]):
 * любое удаление shell-вызова, возврат самодельной шапки/insets или затирание
 * кэша на сбое сети ломает тест. Поведенческая часть (что именно решает политика
 * свежести) проверяется на чистом [OfflineCacheUiPolicy] в последнем тесте.
 */
class ProjectsShellContractTest {

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

    private val projects by lazy { source("src/main/java/ru/mars/mobile/ProjectsActivity.kt") }
    private val onCreate by lazy {
        projects.substringAfter("override fun onCreate").substringBefore("override fun onDestroy")
    }
    private val onFailure by lazy {
        projects.substringAfter("private fun onFetchFailure").substringBefore("private fun showCachedMetrics")
    }

    // --- Общий shell ---------------------------------------------------------------------

    @Test
    fun `projects screen is built on the shared shell`() {
        assertTrue(
            "Экран обязан создаваться через SectionScaffold с заголовком «Проекты» и refresh-колбэком loadMetrics",
            projects.contains("SectionScaffold.create(this, \"Проекты\", ::loadMetrics)")
        )
        assertTrue("Контент обязан добавляться в scaffold.content", projects.contains("scaffold.content.addView"))
        assertTrue("Корень экрана — scaffold.root", projects.contains("return scaffold.root"))
    }

    @Test
    fun `projects screen no longer hand-rolls header, insets or spinner`() {
        assertFalse(
            "Своя обработка insets запрещена — её делает shell",
            projects.contains("ViewCompat.setOnApplyWindowInsetsListener")
        )
        assertFalse(
            "Свои кнопки шапки запрещены — «Назад»/«Обновить» даёт shell",
            projects.contains("MarsTheme.circleIconButton") || projects.contains("R.drawable.ic_arrow_back")
        )
        assertFalse(
            "Свой ProgressBar запрещён — спиннер даёт shell",
            projects.contains("ProgressBar(this)")
        )
        assertTrue(
            "Статус-строка и спиннер shell обязаны использоваться экраном",
            projects.contains("scaffold.statusLine") && projects.contains("scaffold.progress")
        )
    }

    @Test
    fun `shell status line is made visible for the always-on metrics status`() {
        // В общем shell статус-строка по умолчанию скрыта (Cron/Spaces прячут её),
        // а экран метрик показывает статус всегда — контракт фиксирует раскрытие.
        val buildView = projects.substringAfter("private fun buildView").substringBefore("private fun loadMetrics")
        assertTrue("buildView обязан раскрывать статус-строку shell", buildView.contains("updated.visibility = View.VISIBLE"))
        assertTrue("Стартовый статус сохранён", buildView.contains("Загрузка метрик…"))
    }

    // --- Офлайн-кэш ----------------------------------------------------------------------

    @Test
    fun `cache-first start is preserved before the network call`() {
        assertTrue("onCreate обязан показывать кэш", onCreate.contains("showCachedMetrics()"))
        assertTrue("onCreate обязан запускать загрузку", onCreate.contains("loadMetrics()"))
        assertTrue(
            "Кэш показывается ДО сети (мгновенный старт)",
            onCreate.indexOf("showCachedMetrics()") < onCreate.indexOf("loadMetrics()")
        )
        assertTrue(
            "Автообновление раз в 25 с сохранено",
            onCreate.contains("delay(25_000)") && onCreate.contains("refreshJob")
        )
    }

    @Test
    fun `network failure never wipes the shown cache`() {
        assertTrue(
            "Сбой решается чистой политикой OfflineCacheUiPolicy",
            onFailure.contains("decisionFor(OfflineFetchState.FAILURE)")
        )
        assertTrue(
            "При годном снимке кэш остаётся на экране с бейджем и баннером",
            onFailure.contains("decision.showCachedContent") &&
                onFailure.contains("decision.badgeText") &&
                onFailure.contains("decision.bannerText")
        )
        assertTrue("Без годного снимка — честный фолбэк", onFailure.contains("renderFallback()"))
        // Ключевое правило волны: экран нигде не затирает prefs-снимок.
        val cacheCalls = Regex("offlineCache\\.(\\w+)").findAll(projects).map { it.groupValues[1] }.toSet()
        assertEquals(
            "Допустимы только чтение/запись снимка; никаких clear/reset",
            setOf("saveProjectsJson", "loadProjectsJson", "getSavedTimestamp"),
            cacheCalls
        )
    }

    @Test
    fun `status line keeps cache badge and warning colors semantics`() {
        assertTrue(
            "LIVE-рендер пишет сводную статус-строку",
            projects.contains("ProjectsSummary.statusLine(summary, clock())")
        )
        assertTrue(
            "Offline/сбой подсвечиваются STATUS_WARNING",
            onFailure.contains("MarsTheme.STATUS_WARNING") &&
                projects.substringAfter("private fun renderFallback").substringBefore("private fun addService")
                    .contains("MarsTheme.STATUS_WARNING")
        )
        assertTrue(
            "Кэш-бейдж на старте — TEXT_MUTED",
            projects.substringAfter("private fun showCachedMetrics").substringBefore("private fun renderCachedContent")
                .contains("MarsTheme.TEXT_MUTED")
        )
    }

    // --- Retry ----------------------------------------------------------------------------

    @Test
    fun `retry entry points are wired to loadMetrics with a re-entry guard`() {
        assertTrue(
            "«Обновить» shell — это loadMetrics",
            projects.contains("SectionScaffold.create(this, \"Проекты\", ::loadMetrics)")
        )
        assertTrue(
            "Параллельные загрузки отсечены guard'ом",
            projects.contains("if (loading || isFinishing) return")
        )
    }

    // --- Поведенческая часть: что именно решает политика для экрана -----------------------

    @Test
    fun `policy decisions consumed by the screen keep their semantics`() {
        val now = 1_000_000L
        val fresh = now - 5 * 60_000L

        val pendingFresh = OfflineCacheUiPolicy.evaluate(
            MobileOfflineSurface.PROJECTS, OfflineFetchState.PENDING, false, fresh, now
        )
        assertTrue(pendingFresh.showCachedContent)
        assertEquals("Кэш (5 мин назад)", pendingFresh.badgeText)
        assertEquals("snapshot_prefetch", pendingFresh.reason)

        val failureFresh = OfflineCacheUiPolicy.evaluate(
            MobileOfflineSurface.PROJECTS, OfflineFetchState.FAILURE, false, fresh, now
        )
        assertTrue(failureFresh.showCachedContent)
        assertNotNull(failureFresh.bannerText)
        assertEquals("snapshot_after_failure", failureFresh.reason)
        // Экран склеивает бейдж и баннер в одну статус-строку — обе части обязаны быть.
        assertEquals(
            "Кэш (5 мин назад)  ·  ${OfflineCacheUiPolicy.FAILURE_BANNER}",
            listOfNotNull(failureFresh.badgeText, failureFresh.bannerText).joinToString("  ·  ")
        )

        val failureExpired = OfflineCacheUiPolicy.evaluate(
            MobileOfflineSurface.PROJECTS, OfflineFetchState.FAILURE, false,
            now - OfflineCacheUiPolicy.PROJECTS_MAX_AGE_MS - 1, now
        )
        assertFalse(failureExpired.showCachedContent)
        assertEquals(OfflineDataStatus.ERROR, failureExpired.status)
    }
}
