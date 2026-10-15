package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт перевода экрана «Канбан» на общий shell разделов ([SectionScaffold])
 * и общие состояния ([SectionStateViews]): shell используется и НЕ дублируется
 * экраном, polling onResume/onPause, offline/no-auth/error/retry-семантика и
 * read-only-контракт доски сохранены при миграции.
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена как
 * контракт по РЕАЛЬНОМУ production-исходнику (паттерн [ProjectsShellContractTest]
 * из [ChatClickPathContractTest]): любое удаление shell-вызова, возврат
 * самодельной шапки/insets/спиннера, отмена polling или появление мутаций
 * kanban ломает тест. Поведенческая часть (сопоставление фазы экрана с общим
 * [SectionScreenState]) проверяется на чистой [KanbanPolicy] в последнем тесте.
 */
class KanbanShellContractTest {

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

    private val kanban by lazy { source("src/main/java/ru/mars/mobile/KanbanActivity.kt") }
    private val buildView by lazy {
        kanban.substringAfter("private fun buildView").substringBefore("private fun render(")
    }
    private val render by lazy {
        kanban.substringAfter("private fun render(").substringBefore("private fun renderStats")
    }

    // --- Общий shell ---------------------------------------------------------------------

    @Test
    fun `kanban screen is built on the shared shell`() {
        assertTrue(
            "Экран обязан создаваться через SectionScaffold с заголовком KanbanPolicy.SCREEN_TITLE и refresh-колбэком reload",
            kanban.contains("SectionScaffold.create(this, KanbanPolicy.SCREEN_TITLE, ::load)")
        )
        assertTrue("Контент обязан добавляться в scaffold.content", kanban.contains("scaffold.content.addView"))
        assertTrue("Корень экрана — scaffold.root", kanban.contains("return scaffold.root"))
    }

    @Test
    fun `kanban screen no longer hand-rolls header, insets or spinner`() {
        assertFalse(
            "Своя обработка insets запрещена — её делает shell",
            kanban.contains("ViewCompat.setOnApplyWindowInsetsListener")
        )
        assertFalse(
            "Свои кнопки шапки запрещены — «Назад»/«Обновить» даёт shell",
            kanban.contains("MarsTheme.circleIconButton") || kanban.contains("R.drawable.ic_arrow_back")
        )
        assertFalse(
            "Свой ProgressBar запрещён — спиннер даёт shell",
            kanban.contains("ProgressBar(this)")
        )
        assertTrue(
            "Спиннер и статус-строка shell обязаны использоваться экраном",
            kanban.contains("scaffold.progress") && kanban.contains("scaffold.statusLine")
        )
    }

    @Test
    fun `board and stats content is added into scaffold content, not shadowing it`() {
        // Правило миграции: экран НЕ перекрывает scaffold.content (нет setContentView
        // поверх shell и нет removeView у content) — только добавляет детей.
        assertFalse("setContentView вызывается ровно один раз с shell-корнем", kanban.contains("setContentView(buildView()).also"))
        assertFalse("scaffold.content нельзя очищать целиком", kanban.contains("scaffold.content.removeAllViews"))
        assertTrue("Доска добавляется в scaffold.content", buildView.contains("scaffold.content.addView"))
        assertTrue("Бейдж read-only сохранён", buildView.contains("KanbanPolicy.NOTICE_READ_ONLY"))
        assertTrue("Горизонтальный скролл колонок сохранён", buildView.contains("HorizontalScrollView"))
    }

    // --- Polling -------------------------------------------------------------------------

    @Test
    fun `polling keeps onResume start and onPause cancel with the same interval`() {
        val onResume = kanban.substringAfter("override fun onResume").substringBefore("override fun onPause")
        val onPause = kanban.substringAfter("override fun onPause").substringBefore("private fun buildView")
        assertTrue("onResume перезапускает pollJob", onResume.contains("pollJob?.cancel()") && onResume.contains("pollJob = lifecycleScope.launch"))
        assertTrue("Интервал polling прежний — POLL_INTERVAL_MS", onResume.contains("delay(POLL_INTERVAL_MS)"))
        assertTrue("Тик polling — presenter.refresh() (changed:false-конверт без мигания)", onResume.contains("presenter.refresh()"))
        assertTrue("onPause гасит polling", onPause.contains("pollJob?.cancel()") && onPause.contains("pollJob = null"))
        assertTrue("Интервал 30 с сохранён", kanban.contains("const val POLL_INTERVAL_MS = 30_000L"))
    }

    // --- Состояния фаз через SectionStateViews --------------------------------------------

    @Test
    fun `phase messages render through shared SectionStateViews`() {
        assertTrue(
            "Фаза сопоставляется чистой политикой KanbanPolicy.statusStateFor",
            render.contains("KanbanPolicy.statusStateFor(state)")
        )
        assertTrue("LOADING — общий loading-view", render.contains("SectionStateViews.loading"))
        assertTrue("EMPTY — общий empty-view", render.contains("SectionStateViews.empty"))
        assertTrue("ERROR — общий error-view с retry", render.contains("SectionStateViews.error(this, screen.message, ::load)"))
        assertTrue("NO_AUTH/offline — общий offline-view", render.contains("SectionStateViews.offline"))
    }

    @Test
    fun `offline notice banner is rendered on the shell status line`() {
        assertTrue(
            "Плашка notice (offline/stale) живёт в статус-строке shell",
            render.contains("scaffold.statusLine.text = notice") && render.contains("MarsTheme.STATUS_WARNING")
        )
        assertTrue("Без notice статус-строка скрыта", render.contains("scaffold.statusLine.visibility = View.GONE"))
    }

    // --- Retry ----------------------------------------------------------------------------

    @Test
    fun `retry routes to presenter load and stale board keeps its retry chip`() {
        assertTrue(
            "«Обновить» shell и retry ошибки — это presenter.load()",
            kanban.contains("private fun load() { lifecycleScope.launch { presenter.load() } }")
        )
        assertTrue(
            "Offline-safe stale-доска (message=null, canRetry) сохраняет «Повторить»",
            render.contains("state.canRetry") && render.contains("KanbanPolicy.RETRY_LABEL")
        )
    }

    // --- Read-only ------------------------------------------------------------------------

    @Test
    fun `screen stays a read-only showcase`() {
        val repoCalls = Regex("(?<![.\\w])repository\\.(\\w+)").findAll(kanban).map { it.groupValues[1] }.toSet()
        assertEquals(
            "Допустимы только GET-вызовы доски/статистики/карточки/досок/исполнителей",
            setOf("getBoard", "getStats", "getTaskDetail", "getBoards", "getAssignees", "streamEvents"),
            repoCalls
        )
        assertFalse(
            "Никаких мутаций kanban в UI (вызовы функций)",
            Regex("(?i)\\b(createTask|editTask|moveTask|deleteTask|dispatch)\\w*\\s*\\(").containsMatchIn(kanban)
        )
        assertTrue(
            "Диалог задачи — только «Закрыть»",
            kanban.contains("KanbanPolicy.CLOSE_LABEL") && kanban.contains("MarsDialog.quietButton")
        )
        assertFalse("Диалог задачи не содержит кнопок действия", kanban.contains("MarsDialog.primaryButton"))
    }

    @Test
    fun `live stream is torn down with the screen like polling`() {
        // Живой SSE-поток — сетевой ресурс: если его не снимать в onPause,
        // соединение переживёт уход экрана и будет тянуть трафик в фоне.
        assertTrue("Поток гасится в onPause", kanban.contains("liveJob?.cancel()"))
        assertTrue("Поток поднимается при возврате на экран", kanban.contains("liveJob = presenter.bindLiveStream"))
    }

    // --- Поведенческая часть: сопоставление фазы с общим SectionScreenState ---------------

    @Test
    fun `status mapping keeps kanban phase semantics`() {
        // READY и тихий refresh (message=null) не дают центрального состояния.
        assertNull(KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.READY)))
        assertNull(
            "LOADING при живых колонках — без центрального message (refresh без мигания)",
            KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.LOADING, message = null))
        )

        val loading = KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.LOADING, message = KanbanPolicy.MSG_LOADING))
        assertEquals("LOADING", loading?.kind)

        val empty = KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.EMPTY, message = KanbanPolicy.MSG_EMPTY))
        assertEquals("EMPTY", empty?.kind)
        assertEquals(KanbanPolicy.MSG_EMPTY, (empty as SectionScreenState.Empty).message)

        val noAuth = KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.NO_AUTH, message = KanbanPolicy.MSG_NO_AUTH))
        assertEquals("NO_AUTH показывается offline-тоном без retry", "OFFLINE", noAuth?.kind)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, (noAuth as SectionScreenState.Offline).message)

        val error = KanbanPolicy.statusStateFor(KanbanUiState(KanbanPhase.ERROR, message = KanbanPolicy.MSG_ERROR, canRetry = true))
        assertEquals("ERROR", error?.kind)
        assertEquals(KanbanPolicy.MSG_ERROR, (error as SectionScreenState.Error).message)

        val errorOffline = KanbanPolicy.statusStateFor(
            KanbanUiState(KanbanPhase.ERROR, message = KanbanPolicy.MSG_ERROR_OFFLINE, canRetry = true)
        )
        assertEquals("Offline-ошибка остаётся ERROR-видом — retry обязан быть доступен", "ERROR", errorOffline?.kind)

        // Stale-доска: ERROR с колонками, без message — центрального вида нет,
        // баннер notice и «Повторить» рендерит экран.
        val stale = KanbanUiState(
            phase = KanbanPhase.ERROR,
            columns = listOf(KanbanColumnUi(name = "Todo", count = 1, cards = emptyList())),
            notice = KanbanPolicy.NOTICE_STALE,
            message = null,
            canRetry = true,
        )
        assertNull(KanbanPolicy.statusStateFor(stale))
    }
}
