package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт перевода экрана «Задачи агента» на общий shell разделов ([SectionScaffold])
 * и общие состояния ([SectionStateViews]): shell используется и НЕ дублируется экраном,
 * а офлайн-кэш ([ru.mars.network.cache.TodosOfflineCache]), retry-семантика «тот же
 * сеанс» и фильтр-семантика ([TodoFilterPolicy]) сохранены.
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена как
 * контракт по РЕАЛЬНОМУ production-исходнику (паттерн [ProjectsShellContractTest]):
 * любое удаление shell-вызова, возврат самодельной шапки/insets/спиннера, затирание
 * кэша на сбое сети или разъезд retry с текущей сессией ломает тест. Поведенческая
 * часть (что именно решает политика свежести для поверхности TODOS) проверяется на
 * чистом [OfflineCacheUiPolicy] в последнем тесте.
 */
class TodosShellContractTest {

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

    private val todos by lazy { source("src/main/java/ru/mars/mobile/TodosActivity.kt") }
    private fun section(start: String, end: String): String =
        todos.substringAfter(start).substringBefore(end)

    private val onCreate by lazy { section("override fun onCreate", "private fun showCachedSnapshot") }
    private val cachedStart by lazy { section("private fun showCachedSnapshot", "private fun buildView") }
    private val buildView by lazy { section("private fun buildView", "private fun loadTodos") }
    private val loadTodos by lazy { section("private fun loadTodos", "private fun onFetchSuccess") }
    private val onSuccess by lazy { section("private fun onFetchSuccess", "private fun onFetchFailure") }
    private val onFailure by lazy { section("private fun onFetchFailure", "private fun showFailureState") }
    private val failureState by lazy { section("private fun showFailureState", "private fun decisionFor") }
    private val decisionFor by lazy { section("private fun decisionFor", "private fun applyDecision") }
    private val applyDecision by lazy { section("private fun applyDecision", "private fun updateSummary") }
    private val updateSummary by lazy { section("private fun updateSummary", "private fun selectFilter") }
    private val selectFilter by lazy { section("private fun selectFilter", "private fun applyChipStyle") }
    private val renderTodos by lazy { section("private fun renderTodos", "private fun dp(") }

    // --- Общий shell -----------------------------------------------------------------------

    @Test
    fun `todos screen is built on the shared shell`() {
        assertTrue(
            "Экран обязан создаваться через SectionScaffold с заголовком «Задачи агента» и refresh-колбэком loadTodos(currentSessionId)",
            todos.contains("SectionScaffold.create(this, \"Задачи агента\") { loadTodos(currentSessionId) }")
        )
        assertTrue("Контент обязан добавляться в scaffold.content", todos.contains("scaffold.content.addView"))
        assertTrue("Корень экрана — scaffold.root", todos.contains("return scaffold.root"))
    }

    @Test
    fun `todos screen no longer hand-rolls header, insets or spinner`() {
        assertFalse(
            "Своя обработка insets запрещена — её делает shell",
            todos.contains("ViewCompat.setOnApplyWindowInsetsListener")
        )
        assertFalse(
            "Свои кнопки шапки запрещены — «Назад»/«Обновить» даёт shell",
            todos.contains("MarsTheme.circleIconButton") || todos.contains("R.drawable.ic_arrow_back")
        )
        assertFalse(
            "Свой ProgressBar запрещён — спиннер даёт shell",
            todos.contains("ProgressBar(this)") || todos.contains("import android.widget.ProgressBar")
        )
        assertTrue(
            "Спиннер и статус-строка shell обязаны использоваться экраном",
            todos.contains("scaffold.progress") && todos.contains("scaffold.statusLine")
        )
    }

    @Test
    fun `shell status line carries the session summary`() {
        assertTrue(
            "Статус-строка shell используется как сводка экрана",
            todos.contains("private val summaryText: TextView get() = scaffold.statusLine")
        )
        assertTrue(
            "buildView обязан раскрывать статус-строку shell со стартовым статусом",
            buildView.contains("summaryText.visibility = View.VISIBLE") &&
                buildView.contains("Загрузка плана задач…")
        )
    }

    // --- Офлайн-кэш: snapshot до сети, save при success, баннер при failure ------------------

    @Test
    fun `cache-first start is preserved before the network call`() {
        assertTrue("onCreate обязан показывать снимок кэша", onCreate.contains("showCachedSnapshot(sessionId)"))
        assertTrue("onCreate обязан запускать загрузку", onCreate.contains("loadTodos(sessionId)"))
        assertTrue(
            "Кэш показывается ДО сети (мгновенный старт)",
            onCreate.indexOf("showCachedSnapshot(sessionId)") < onCreate.indexOf("loadTodos(sessionId)")
        )
        assertTrue(
            "Стартовый показ снимка помечает кэш на экране и запоминает слот",
            cachedStart.contains("offlineCache.loadTodos(sessionId) ?: return") &&
                cachedStart.contains("cacheOnScreen = true") &&
                cachedStart.contains("cacheSlot = sessionId")
        )
        assertTrue(
            "Стартовое решение свежести — PENDING-фаза",
            cachedStart.contains("decisionFor(OfflineFetchState.PENDING")
        )
        assertTrue(
            "Решение принимается чистой политикой для поверхности TODOS по слоту кэша",
            decisionFor.contains("MobileOfflineSurface.TODOS") &&
                decisionFor.contains("offlineCache.getSnapshotTimestamp(cacheSlot ?: currentSessionId)")
        )
    }

    @Test
    fun `success saves the snapshot to the offline cache`() {
        assertTrue(
            "Успех обязан сохранять снимок в TodosOfflineCache",
            onSuccess.contains("offlineCache.saveTodos(slotSessionId, snapshot)")
        )
        assertTrue(
            "После сохранения экран переводится в LIVE решением SUCCESS с фактом пустоты payload",
            onSuccess.contains("cacheOnScreen = false") &&
                onSuccess.contains("decisionFor(OfflineFetchState.SUCCESS, livePayloadEmpty = snapshot.todos.isEmpty())")
        )
        assertTrue(
            "Слот сохранения прежний: разрешённый текущий сеанс",
            loadTodos.contains("onFetchSuccess(currentSessionId, snapshot)")
        )
    }

    @Test
    fun `network failure never wipes the shown cache and keeps the stale banner`() {
        assertTrue(
            "Сбой решается чистой политикой OfflineCacheUiPolicy",
            onFailure.contains("decisionFor(OfflineFetchState.FAILURE, livePayloadEmpty = false)")
        )
        assertTrue(
            "Годный снимок остаётся на экране (cacheOnScreen из решения политики)",
            onFailure.contains("cacheOnScreen = decision.showCachedContent") &&
                onFailure.contains("applyDecision(decision)")
        )
        assertTrue(
            "Без кэша — общие состояние ошибки и честная сводка; показанный список не затирается",
            onFailure.contains("if (!decision.showCachedContent)") &&
                onFailure.contains("showFailureState()") &&
                onFailure.contains("summaryText.text = \"Нет сети, а сохранённый снимок отсутствует\"")
        )
        assertTrue(
            "Баннер устаревания пишет решение политики (бейдж возраста + отметка сбоя)",
            applyDecision.contains("decision.badgeText") &&
                applyDecision.contains("decision.bannerText") &&
                applyDecision.contains("cacheBanner.visibility = View.GONE") &&
                applyDecision.contains("joinToString(\"  ·  \")")
        )
    }

    @Test
    fun `offline cache is only read, saved and timestamped`() {
        val cacheCalls = Regex("offlineCache\\.(\\w+)").findAll(todos).map { it.groupValues[1] }.toSet()
        assertEquals(
            "Экран обязан лишь читать/сохранять снимок и спрашивать метаданные — стирание не его забота (logout-cleanup)",
            setOf("loadTodos", "saveTodos", "getSnapshotTimestamp"),
            cacheCalls
        )
    }

    // --- Retry: тот же сеанс ------------------------------------------------------------------

    @Test
    fun `refresh and error retry repeat the same session`() {
        assertTrue(
            "«Обновить» shell перезагружает задачи ТЕКУЩЕГО сеанса",
            todos.contains("SectionScaffold.create(this, \"Задачи агента\") { loadTodos(currentSessionId) }")
        )
        assertTrue(
            "Retry общего error-состояния повторяет тот же сеанс с прежним сообщением",
            failureState.contains("SectionStateViews.error(this, \"Не удалось загрузить задачи\") { loadTodos(currentSessionId) }")
        )
        assertTrue(
            "Параллельные загрузки отсечены guard'ом",
            loadTodos.contains("if (loading || isFinishing) return")
        )
        assertTrue(
            "Разрешение активного сеанса прежнее: активный либо последний, с запоминанием",
            loadTodos.contains("sessions.firstOrNull { it.activeTurn != null } ?: sessions.firstOrNull()") &&
                loadTodos.contains("currentSessionId = active?.id") &&
                loadTodos.contains("sessionRepo.getSessionDetail(ChatActivity.BASE_URL, cred, active.id)?.todoState")
        )
    }

    @Test
    fun `no-auth path keeps the existing failure semantics`() {
        // Backend не выдуман: без credentials — прежний путь onFetchFailure (кэш/баннер/ошибка),
        // а не отдельный «вход»-экран.
        assertTrue(
            "Загрузка читает credentials из KeyStore и при их отсутствии уходит в сбой до сети",
            loadTodos.contains("val cred = store.read()") &&
                loadTodos.contains("if (cred == null)") &&
                loadTodos.contains("onFetchFailure()")
        )
        assertTrue(
            "Проверка credentials идёт ДО guard-флагов загрузки — отказ входа не блокирует повтор",
            loadTodos.indexOf("val cred = store.read()") < loadTodos.indexOf("loading = true")
        )
    }

    @Test
    fun `spinner does not cover the shown cache`() {
        assertTrue(
            "Спиннер shell виден только когда кэш ещё не на экране (прежняя семантика)",
            loadTodos.contains("scaffold.progress.visibility = if (cacheOnScreen) View.GONE else View.VISIBLE") &&
                loadTodos.contains("scaffold.progress.visibility = View.GONE")
        )
    }

    // --- Фильтр-семантика -----------------------------------------------------------------------

    @Test
    fun `filter chips keep TodoFilterPolicy semantics`() {
        assertTrue(
            "Ряд чипов строится ровно из TodoFilterPolicy.CHIPS с тапом в selectFilter",
            buildView.contains("TodoFilterPolicy.CHIPS.forEach") &&
                buildView.contains("setOnClickListener { selectFilter(filter) }") &&
                buildView.contains("filterChips[filter] = chip")
        )
        assertTrue(
            "Подсветка при постройке ряда — по текущему фильтру",
            buildView.contains("applyChipStyle(chip, selected = filter == currentFilter)")
        )
        assertTrue(
            "Выбор чипа: идемпотентность, перечёркивание подсветки по идентичности фильтра и перерисовка",
            selectFilter.contains("if (currentFilter == filter) return") &&
                selectFilter.contains("filterChips.forEach { (chipFilter, chip) -> applyChipStyle(chip, selected = chipFilter == filter) }") &&
                selectFilter.contains("renderTodos()")
        )
        assertTrue(
            "Видимость задач решает чистая политика",
            renderTodos.contains("TodoFilterPolicy.visibleItems(allItems, currentFilter)")
        )
    }

    // --- Общие состояния -------------------------------------------------------------------------

    @Test
    fun `empty and failure states use the shared state views`() {
        assertTrue(
            "Пустой список — общее empty-состояние с текстом политики фильтра",
            renderTodos.contains("SectionStateViews.empty(this, TodoFilterPolicy.emptyText(allItems.isEmpty()))")
        )
        assertTrue(
            "Ошибка — общее error-состояние (с retry), а не самодельный TextView",
            failureState.contains("SectionStateViews.error(this, \"Не удалось загрузить задачи\")")
        )
        assertFalse(
            "Самодельный emptyText-TextView запрещён — его заменили общие состояния",
            todos.contains("emptyText = TextView(this)") || todos.contains("emptyText.visibility")
        )
    }

    @Test
    fun `session summary texts are preserved`() {
        assertTrue(
            "Сводка «задач не ставились» для пустого снимка сохранена",
            updateSummary.contains("В текущей сессии задачи не ставились")
        )
        assertTrue(
            "Сводка прогресса с процентом сохранена",
            updateSummary.contains("Выполнено \$done из \$total задач")
        )
    }

    // --- Поведенческая часть: что именно решает политика для экрана ------------------------------

    @Test
    fun `policy decisions consumed by the screen keep their semantics`() {
        val now = 1_000_000L
        val fresh = now - 5 * 60_000L
        val pendingFresh = OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = OfflineFetchState.PENDING,
            livePayloadEmpty = false,
            snapshotTakenAtMs = fresh,
            nowMs = now,
        )
        assertTrue(pendingFresh.showCachedContent)
        assertEquals(OfflineDataStatus.CACHED, pendingFresh.status)
        assertEquals("Кэш (5 мин назад)", pendingFresh.badgeText)
        assertNull("При мгновенном старте из кэша баннера нет — запрос ещё в полёте", pendingFresh.bannerText)
        assertEquals("snapshot_prefetch", pendingFresh.reason)

        val failureFresh = OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = OfflineFetchState.FAILURE,
            livePayloadEmpty = false,
            snapshotTakenAtMs = fresh,
            nowMs = now,
        )
        assertTrue(failureFresh.showCachedContent)
        assertEquals(OfflineCacheUiPolicy.FAILURE_BANNER, failureFresh.bannerText)
        assertNotNull("Бейдж возраста при сбое остаётся — экран показывает бейдж и баннер", failureFresh.badgeText)
        assertEquals("snapshot_after_failure", failureFresh.reason)

        val failureExpired = OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = OfflineFetchState.FAILURE,
            livePayloadEmpty = false,
            snapshotTakenAtMs = now - OfflineCacheUiPolicy.TODOS_MAX_AGE_MS - 1,
            nowMs = now,
        )
        assertFalse("Просроченный снимок не показывается как живой", failureExpired.showCachedContent)
        assertEquals(OfflineDataStatus.ERROR, failureExpired.status)
        assertEquals("snapshot_expired", failureExpired.reason)

        val failureNoSnapshot = OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = OfflineFetchState.FAILURE,
            livePayloadEmpty = false,
            snapshotTakenAtMs = null,
            nowMs = now,
        )
        assertEquals(OfflineDataStatus.ERROR, failureNoSnapshot.status)
        assertEquals("no_snapshot", failureNoSnapshot.reason)

        val successEmpty = OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = OfflineFetchState.SUCCESS,
            livePayloadEmpty = true,
            snapshotTakenAtMs = fresh,
            nowMs = now,
        )
        assertEquals("Пустой снимок задач — норма (EMPTY), а не ошибка", OfflineDataStatus.EMPTY, successEmpty.status)
        assertEquals("live_empty", successEmpty.reason)

        assertEquals(
            "Окно устаревания снимка задач — 12 часов",
            12L * 60 * 60 * 1000, OfflineCacheUiPolicy.TODOS_MAX_AGE_MS
        )
    }
}
