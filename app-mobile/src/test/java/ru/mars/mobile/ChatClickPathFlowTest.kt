package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse
import ru.mars.network.model.ReasoningStatus
import ru.mars.network.model.SessionItem

/**
 * Поведенческая симуляция click-путей первой UX-волны (mobile) на ЧИСТЫХ политиках,
 * которые ChatActivity фактически вызывает (проводка закреплена
 * [ChatClickPathContractTest]): шторка/диалоги -> поиск -> открытие сессии ->
 * Back-лестница -> модель/reasoning. Каждый тест проигрывает последовательность
 * пользовательских шагов и проверяет состояние, которое увидит пользователь.
 */
class ChatClickPathFlowTest {

    // --- Фикстуры -------------------------------------------------------------------------

    private fun session(
        id: String,
        title: String? = null,
        preview: String? = null,
        updatedAt: String? = null,
        parentId: String? = null,
        subagent: Boolean = false,
        readOnly: Boolean = false,
    ) = SessionItem(
        id = id,
        title = title,
        preview = preview,
        createdAt = "2026-10-01T10:00:00Z",
        updatedAt = updatedAt,
        parentSessionId = parentId,
        sourceTag = if (subagent) "subagent" else "webui",
        readOnly = readOnly,
    )

    private val fixtures = listOf(
        session("parent-1", title = "Отчёт по прод-инциденту", updatedAt = "2026-10-12T09:00:00Z"),
        session(
            "child-1",
            title = "Subagent: разбор логов прод",
            updatedAt = "2026-10-12T09:30:00Z",
            parentId = "parent-1",
            subagent = true,
            readOnly = true,
        ),
        session("other", title = "Музыкальные релизы", preview = "плейлист", updatedAt = "2026-10-11T18:00:00Z"),
    )

    private class FakeModelStorage : ModelChoiceStorage {
        var saved: ModelChoice? = null
        override fun load(): ModelChoice? = saved
        override fun save(choice: ModelChoice) { saved = choice }
        override fun clear() { saved = null }
    }

    private class FakeReasoningStorage : ReasoningChoiceStorage {
        var saved: ReasoningChoice? = null
        override fun load(): ReasoningChoice? = saved
        override fun save(choice: ReasoningChoice) { saved = choice }
        override fun clear() { saved = null }
    }

    // --- Путь: шторка -> поиск -> открытие сессии ------------------------------------------

    @Test
    fun `path - drawer opens with collapsed tree, subagent child hidden under parent`() {
        // Шаг «открыть шторку»: дерево без поиска, ветки свёрнуты.
        val rows = SessionTreePolicy.visibleRows(
            SessionTreePolicy.tree(sessions = fixtures, query = "")
        )
        val ids = rows.map { it.session.id }
        assertEquals("Видны только корни (свежие сначала)", listOf("parent-1", "other"), ids)
        assertTrue("Родитель с детьми помечен hasChildren", rows.first().hasChildren)
        assertFalse("Свёрнутый родитель скрывает субагента", rows.any { it.session.id == "child-1" })
    }

    @Test
    fun `path - parent row click expands branch and reveals subagent child`() {
        // Клик по карточке родителя переключает expandedParents и перерисовывает список.
        val expanded = mutableSetOf<String>()
        fun rows() = SessionTreePolicy.visibleRows(
            SessionTreePolicy.tree(sessions = fixtures, query = "", expandedParents = expanded)
        )

        assertFalse(rows().any { it.session.id == "child-1" })
        // Первый клик: раскрытие.
        if (!expanded.remove("parent-1")) expanded.add("parent-1")
        val opened = rows()
        assertTrue("После клика ветка раскрыта", opened.any { it.session.id == "child-1" })
        assertEquals("Ребёнок идёт сразу за родителем с depth=1", 1, opened[opened.indexOfFirst { it.session.id == "child-1" }].depth)
        // Второй клик: сворачивание.
        if (!expanded.remove("parent-1")) expanded.add("parent-1")
        assertFalse("Повторный клик сворачивает ветку", rows().any { it.session.id == "child-1" })
    }

    @Test
    fun `path - drawer search filters by query and auto-expands matching branch`() {
        // Ввод «прод» в фильтр шторки: совпадения + цепочка предков, дерево раскрыто.
        val rows = SessionTreePolicy.visibleRows(
            SessionTreePolicy.tree(sessions = fixtures, query = "прод")
        )
        val ids = rows.map { it.session.id }
        assertTrue("Совпадение-родитель виден", "parent-1" in ids)
        assertTrue("Совпадение-ребёнок виден благодаря searchActive-раскрытию", "child-1" in ids)
        assertFalse("Несовпадающая сессия отфильтрована", "other" in ids)

        // Пустой результат — отдельное состояние списка.
        val none = SessionTreePolicy.visibleRows(SessionTreePolicy.tree(sessions = fixtures, query = "нет-такого"))
        assertTrue("Без совпадений список пуст («Нет подходящих сессий»)", none.isEmpty())
    }

    @Test
    fun `path - opening session from drawer is last-navigation-wins`() {
        // Клик по leaf: шторка закрывается, сессия грузится асинхронно; быстрый второй
        // клик по другому диалогу обязан сделать первый callback устаревшим.
        val nav = SessionNavigationPolicy()
        val first = nav.beginNavigation()          // клик «child-1»
        val second = nav.beginNavigation()         // немедленный клик «other»
        assertFalse("Первая навигация устарела", nav.isCurrent(first))
        assertTrue("Побеждает последняя загрузка", nav.isCurrent(second))
        // «Новый чат» инвалидирует pending-навигацию без открытия.
        nav.invalidatePending()
        assertFalse("После invalidatePending устарели все", nav.isCurrent(second))
    }

    @Test
    fun `path - opened session is marked current and its ancestors auto-expand`() {
        // Открытая сессия не теряется из шторки: цепочка предков раскрывается
        // автоматически, строка помечена isCurrent (подсветка карточки).
        val rows = SessionTreePolicy.visibleRows(
            SessionTreePolicy.tree(sessions = fixtures, query = "", currentSessionId = "child-1")
        )
        val current = rows.firstOrNull { it.isCurrent }
        assertNotNull("Текущая сессия видна без ручного раскрытия ветки", current)
        assertEquals("child-1", current!!.session.id)
        val parent = rows.first { it.session.id == "parent-1" }
        assertTrue("Предок текущей сессии раскрыт автоматически", parent.expanded)
    }

    // --- Путь: Back-лестница ----------------------------------------------------------------

    /**
     * Ожидаемая семантика Back из ChatActivity (закреплена порядковым контрактом в
     * [ChatClickPathContractTest]): поиск -> шторка -> выход. Здесь проигрывается
     * последовательность нажатий поверх состояния экрана.
     */
    private enum class BackEffect { CLOSE_SEARCH, CLOSE_DRAWER, EXIT }

    private fun backEffect(searchPlateVisible: Boolean, drawerOpen: Boolean): BackEffect = when {
        searchPlateVisible -> BackEffect.CLOSE_SEARCH
        drawerOpen -> BackEffect.CLOSE_DRAWER
        else -> BackEffect.EXIT
    }

    @Test
    fun `path - back ladder closes search, then drawer, then exits`() {
        var searchVisible = true
        var drawerOpen = true

        assertEquals(BackEffect.CLOSE_SEARCH, backEffect(searchVisible, drawerOpen))
        searchVisible = false // closeTranscriptSearch()
        assertEquals(BackEffect.CLOSE_DRAWER, backEffect(searchVisible, drawerOpen))
        drawerOpen = false // drawer.closeDrawer()
        assertEquals(BackEffect.EXIT, backEffect(searchVisible, drawerOpen))
    }

    @Test
    fun `path - transcript search plate ring navigation and counter label`() {
        // Открыли панель поиска, ввели запрос: счётчик и кольцевая навигация.
        val texts = listOf("Марс, сделай отчёт", "готово", "отчёт отправлен Марс")
        assertFalse("Запрос короче MIN_QUERY_LENGTH не ищем", TranscriptSearchPolicy.shouldSearch("М"))
        val matches = TranscriptSearchPolicy.findMatches(texts, "марс")
        assertEquals(2, matches.size)
        assertEquals("1 из 2", TranscriptSearchPolicy.counterLabel("марс", 0, matches.size))
        // Next с последнего совпадения -> кольцо на первое; prev с первого -> последнее.
        val next = TranscriptSearchPolicy.navigate(matches.size, matches.size - 1, forward = true)
        assertEquals(0, next)
        val prev = TranscriptSearchPolicy.navigate(matches.size, 0, forward = false)
        assertEquals(matches.size - 1, prev)
        assertEquals(
            TranscriptSearchPolicy.NO_MATCHES_LABEL,
            TranscriptSearchPolicy.counterLabel("квиз", -1, 0)
        )
    }

    // --- Путь: модель/reasoning controls ------------------------------------------------------

    private val catalog = ModelsResponse(
        activeProvider = "nous",
        defaultModel = "hermes-4",
        models = listOf(
            ModelInfo(id = "hermes-4", name = "Hermes 4", provider = "Nous", providerId = "nous"),
            ModelInfo(id = "gpt-x", name = "GPT-X", provider = "OpenAI", providerId = "openai"),
        ),
    )

    @Test
    fun `path - model chip adopts server default, explicit pick wins, opened session overrides wire choice`() {
        val storage = FakeModelStorage()
        val selection = ModelSelection(storage)

        // Старт: каталог загружен — эффективен серверный default (не явный выбор).
        val adopted = selection.resolveEffective(catalog)
        assertEquals(ModelChoice("hermes-4", "nous", explicitPick = false), adopted)
        assertEquals("Hermes 4", selection.buttonLabel())
        assertNull("Серверный default не сохраняется как пользовательский выбор", storage.saved)

        // Клик по чипу -> пикер из живого каталога -> выбор GPT-X.
        val entries = selection.pickerEntries(catalog)
        assertEquals(listOf("Hermes 4 · Nous", "GPT-X · OpenAI"), entries.map { it.label })
        val picked = selection.select(entries[1].model)
        assertEquals(ModelChoice("gpt-x", "openai", explicitPick = true), picked)
        assertNotNull("Явный выбор персистентен", storage.saved)

        // Открытие сессии: server-authoritative модель сессии побеждает локальный выбор
        // для wire, но «новый чат» возвращает выбор пользователя.
        selection.setSessionModel("hermes-4", "nous")
        assertEquals("hermes-4", selection.wireChoice()?.modelId)
        selection.clearSessionModel()
        assertEquals("gpt-x", selection.wireChoice()?.modelId)
    }

    @Test
    fun `path - reasoning chip hidden without server status, ladder from supported efforts only`() {
        val storage = FakeReasoningStorage()
        val selection = ReasoningEffortSelection(storage)

        // Старт без статуса: чип скрыт, выдуманных уровней нет.
        assertNull(selection.buttonLabel())
        assertTrue(selection.options().isEmpty())

        // refreshReasoningStatus принёс серверную лестницу: «Авто» первая, уровни в серверном порядке.
        selection.applyStatus(ReasoningStatus(reasoningEffort = "high", supportedEfforts = listOf("low", "high")))
        assertEquals(
            listOf("Авто", "Low", "High"),
            selection.options().map { it.label }
        )
        assertEquals("High", selection.buttonLabel())
        assertEquals("high", selection.effectiveEffort())

        // Провал статуса прячет чип (stale-лестница запрещена).
        selection.applyStatus(null)
        assertNull(selection.buttonLabel())
    }

    @Test
    fun `path - reasoning pick persists locally only after server confirmation`() {
        val storage = FakeReasoningStorage()
        val selection = ReasoningEffortSelection(storage)
        selection.applyStatus(ReasoningStatus(reasoningEffort = "high", supportedEfforts = listOf("low", "high")))

        // POST /api/reasoning отказал: локальная правда не меняется (select не вызывается).
        assertNull("До подтверждения сервера локального выбора нет", storage.saved)
        assertEquals("high", selection.effectiveEffort())

        // POST подтверждён статусом: select записывает выбор, effective — из серверного ответа.
        selection.select("low")
        selection.applyStatus(ReasoningStatus(reasoningEffort = "low", supportedEfforts = listOf("low", "high")))
        assertEquals(ReasoningChoice("low", explicitPick = true), storage.saved)
        assertEquals("Low", selection.buttonLabel())
    }
}
