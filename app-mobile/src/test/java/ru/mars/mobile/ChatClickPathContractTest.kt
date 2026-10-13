package ru.mars.mobile

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт click-путей первой UX-волны (mobile): запуск -> шторка/диалоги ->
 * поиск -> открытие сессии -> Back -> модель/reasoning.
 *
 * Instrumented-слоя в проекте нет (нет androidTest/Espresso; единственный ADB-мост
 * :15555 — это ТВ, не телефон), поэтому клик-пути закреплены как контракт проводки:
 * тест читает РЕАЛЬНЫЙ production-исходник ChatActivity/MainActivity/манифеста и
 * проверяет, что каждый шаг пути действительно wired в UI-код. Это не «зелёный без
 * UI» тест: любое удаление/перестановка обработчика в ChatActivity ломает контракт.
 * Поведенческая часть пути (что именно видит пользователь после клика) покрыта
 * [ChatClickPathFlowTest] на чистых политиках, которые ChatActivity вызывает.
 */
class ChatClickPathContractTest {

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

    private val chat by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }
    private val main by lazy { source("src/main/java/ru/mars/mobile/MainActivity.kt") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }

    // --- Шаг 1: запуск ------------------------------------------------------------------

    @Test
    fun `launch - launcher entry is MainActivity and ChatActivity is declared`() {
        assertTrue(
            "LAUNCHER должен быть на MainActivity",
            manifest.contains("android:name=\".MainActivity\"") &&
                manifest.contains("android.intent.category.LAUNCHER")
        )
        assertTrue(
            "ChatActivity обязан быть объявлен в манифесте",
            manifest.contains("android:name=\".ChatActivity\"")
        )
    }

    @Test
    fun `launch - MainActivity routes to ChatActivity when credential exists`() {
        // Холодный старт: есть сохранённая авторизация -> сразу чат; нет -> форма логина.
        assertTrue(
            "MainActivity обязан открывать чат при наличии credentials",
            main.contains("store.read() != null") && main.contains("openChat()")
        )
        assertTrue(
            "openChat() обязан стартовать ChatActivity",
            Regex("fun openChat\\(\\)[\\s\\S]{0,300}ChatActivity::class\\.java").containsMatchIn(main)
        )
    }

    @Test
    fun `launch - ChatActivity onCreate loads sessions, model catalog and reasoning status`() {
        val onCreate = chat.substringAfter("override fun onCreate").substringBefore("override fun onDestroy")
        assertTrue("onCreate обязан грузить список сессий", onCreate.contains("loadSessions()"))
        assertTrue("onCreate обязан обновлять каталог моделей", onCreate.contains("refreshModelCatalog()"))
        assertTrue("onCreate обязан обновлять reasoning-статус", onCreate.contains("refreshReasoningStatus()"))
        assertTrue(
            "onCreate обязан восстанавливать открытую сессию после пересоздания",
            onCreate.contains("restoreOpenSession(savedInstanceState)")
        )
    }

    // --- Шаг 2: открытие drawer/раздела диалогов ----------------------------------------

    @Test
    fun `drawer - menu button toggles sessions drawer`() {
        val menuBtn = chat.substringAfter("val menuBtn = MarsTheme.circleIconButton")
            .substringBefore("header.addView(menuBtn")
        assertTrue("Кнопка шторки обязана иметь contentDescription «Сессии»", menuBtn.contains("\"Сессии\""))
        assertTrue(
            "Клик по кнопке обязан переключать шторку (toggle), а не только открывать",
            menuBtn.contains("drawer.isDrawerOpen(GravityCompat.START)") &&
                menuBtn.contains("drawer.closeDrawer(GravityCompat.START)") &&
                menuBtn.contains("drawer.openDrawer(GravityCompat.START)")
        )
    }

    @Test
    fun `drawer - session list renders through SessionTreePolicy with subagents`() {
        // Тело renderSessionList до следующего top-level private fun (вложенные
        // локальные функции имеют больший отступ и границу не пересекают).
        val render = chat.substringAfter("private fun renderSessionList() {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "Список шторки обязан строиться через SessionTreePolicy.tree",
            render.contains("SessionTreePolicy.tree(")
        )
        assertTrue(
            "Дерево обязано включать субагентов (showSubagents = true)",
            render.contains("drawerSessions(showSubagents = true)")
        )
        assertTrue(
            "Поиск шторки обязан передаваться в дерево как query = drawerFilterQuery",
            render.contains("query = drawerFilterQuery")
        )
        assertTrue(
            "Видимые строки — только SessionTreePolicy.visibleRows",
            render.contains("SessionTreePolicy.visibleRows(")
        )
        assertTrue(
            "Пустой результат обязан показывать «Нет подходящих сессий»",
            render.contains("Нет подходящих сессий")
        )
    }

    // --- Шаг 3: поиск в диалогах ---------------------------------------------------------

    @Test
    fun `search - drawer filter feeds drawerFilterQuery and re-renders list`() {
        val filter = chat.substringAfter("drawerFilter = EditText(this)")
            .substringBefore("searchBox.addView(drawerFilter")
        assertTrue(
            "Фильтр шторки обязан обновлять drawerFilterQuery по вводу",
            filter.contains("drawerFilterQuery = s?.toString().orEmpty()")
        )
        assertTrue(
            "Ввод в фильтр обязан перерисовывать список сессий",
            filter.contains("renderSessionList()")
        )
    }

    @Test
    fun `search - transcript search plate is wired with policy navigation and close`() {
        assertTrue(
            "Панель поиска по диалогу обязана существовать (searchPlate/searchInput/searchCounter)",
            chat.contains("searchPlate = LinearLayout(this)") &&
                chat.contains("searchInput = EditText(this)") &&
                chat.contains("searchCounter = TextView(this)")
        )
        assertTrue(
            "Next/prev поискa обязаны вызывать navigateTranscriptSearch",
            chat.contains("navigateTranscriptSearch(forward = true)") &&
                chat.contains("navigateTranscriptSearch(forward = false)")
        )
        assertTrue(
            "Закрытие панели поиска обязано быть wired",
            chat.contains("setOnClickListener { closeTranscriptSearch() }")
        )
        assertTrue(
            "Совпадения/счётчик — только чистая TranscriptSearchPolicy",
            chat.contains("TranscriptSearchPolicy")
        )
    }

    // --- Шаг 4: открытие сессии ----------------------------------------------------------

    @Test
    fun `open session - leaf click closes drawer and loads session, parent click toggles branch`() {
        val rowClick = chat.substringAfter("val onRowClick: () -> Unit = {")
            .substringBefore("card.setOnClickListener { onRowClick() }")
        assertTrue(
            "Клик по родителю с детьми обязан переключать раскрытие ветки и перерисовывать список",
            rowClick.contains("node.hasChildren") &&
                rowClick.contains("expandedSessionParents.remove(session.id)") &&
                rowClick.contains("expandedSessionParents.add(session.id)") &&
                rowClick.contains("renderSessionList()")
        )
        assertTrue(
            "Клик по leaf обязан закрывать шторку и открывать сессию",
            rowClick.contains("drawer.closeDrawer(GravityCompat.START)") &&
                rowClick.contains("loadSession(session)")
        )
    }

    @Test
    fun `open session - navigation is token-guarded so stale callbacks cannot repaint`() {
        val load = chat.substringAfter("private fun loadSessionById(sessionId: String) {")
            .substringBefore("private fun ")
        assertTrue(
            "Открытие сессии обязано брать токен SessionNavigationPolicy.beginNavigation",
            load.contains("sessionNavigation.beginNavigation()")
        )
        assertTrue(
            "Callback применяется только если токен текущий (isCurrent)",
            load.contains("sessionNavigation.isCurrent(token)")
        )
        assertTrue(
            "Открытая сессия обязана обновлять server-authoritative модель чипа",
            load.contains("modelSelection.setSessionModel(detail.model, detail.modelProvider)")
        )
    }

    // --- Шаг 5: Back-навигация -----------------------------------------------------------

    @Test
    fun `back - ladder closes search plate, then drawer, then exits`() {
        val back = chat.substringAfter("onBackPressedDispatcher.addCallback")
            .substringBefore("MarsMusicPlayer.registerListener(this)")
        val searchIdx = back.indexOf("closeTranscriptSearch()")
        val drawerCheckIdx = back.indexOf("drawer.isDrawerOpen(GravityCompat.START)")
        val drawerCloseIdx = back.indexOf("drawer.closeDrawer(GravityCompat.START)")
        val exitIdx = back.indexOf("onBackPressedDispatcher.onBackPressed()")
        assertTrue("Back обязан закрывать панель поиска", searchIdx >= 0)
        assertTrue("Back обязан закрывать шторку", drawerCheckIdx >= 0 && drawerCloseIdx >= 0)
        assertTrue("Back обязан выходить из экрана последней очередью", exitIdx >= 0)
        assertTrue(
            "Порядок Back: сначала поиск, потом шторка, потом выход",
            searchIdx < drawerCheckIdx && drawerCheckIdx < exitIdx
        )
        assertTrue(
            "Панель поиска проверяется по фактической видимости searchPlate",
            back.contains("searchPlate.visibility == View.VISIBLE")
        )
    }

    // --- Шаг 6: модель/reasoning controls -------------------------------------------------

    @Test
    fun `model chip - click opens picker from live catalog and persists explicit pick`() {
        val chip = chat.substringAfter("modelBtn = Button(this).apply {")
            .substringBefore("chipsRow.addView(modelBtn")
        assertTrue("Чип модели обязан иметь contentDescription «Модель»", chip.contains("contentDescription = \"Модель\""))
        assertTrue("Клик по чипу обязан вызывать chooseModel()", chip.contains("setOnClickListener { chooseModel() }"))

        val choose = chat.substringAfter("private fun chooseModel() {").substringBefore("private fun ")
        assertTrue(
            "Пикер обязан строиться из живого каталога через modelSelection.pickerEntries",
            choose.contains("modelSelection.pickerEntries(")
        )
        assertTrue(
            "Выбор обязан сохраняться через modelSelection.select и применяться к сессии",
            choose.contains("modelSelection.select(") && choose.contains("modelSelection.setSessionModel(")
        )
    }

    @Test
    fun `reasoning chip - hidden until server status, click opens ladder, pick saved only after server confirm`() {
        val chip = chat.substringAfter("reasoningBtn = Button(this).apply {")
            .substringBefore("chipsRow.addView(reasoningBtn")
        assertTrue(
            "Чип reasoning скрыт до серверного статуса (visibility = View.GONE)",
            chip.contains("visibility = View.GONE")
        )
        assertTrue(
            "Клик по чипу обязан вызывать chooseReasoningEffort()",
            chip.contains("setOnClickListener { chooseReasoningEffort() }")
        )

        val update = chat.substringAfter("private fun updateReasoningButton() {").substringBefore("/** Activity display mode")
        assertTrue(
            "Подпись чипа — только reasoningSelection.buttonLabel(); null прячет чип",
            update.contains("reasoningSelection.buttonLabel()") &&
                update.contains("if (label == null) View.GONE else View.VISIBLE")
        )

        val apply = chat.substringAfter("private fun applyReasoningEffort(effort: String?) {")
            .substringBefore("/**\n     * Silent status sync")
        val successIdx = apply.indexOf("is ReasoningStatusResult.Status ->")
        val selectIdx = apply.indexOf("reasoningSelection.select(effort)")
        val failedIdx = apply.indexOf("is ReasoningStatusResult.Failed ->")
        assertTrue("Выбор уровня обязан сохраняться локально", selectIdx >= 0)
        assertTrue("Ответ сервера обязан ветвиться на Status/Failed", successIdx >= 0 && failedIdx >= 0)
        assertTrue(
            "Локальное сохранение выбора — ТОЛЬКО после подтверждения сервера (в Status-ветке)",
            selectIdx > successIdx && selectIdx < failedIdx
        )
    }

    @Test
    fun `startup refresh - reasoning status failure hides chip instead of showing stale ladder`() {
        val refresh = chat.substringAfter("private fun refreshReasoningStatus() {").substringBefore("private fun updateReasoningButton")
        assertTrue(
            "Провал статуса обязан сбрасывать состояние (applyStatus(null))",
            refresh.contains("is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)")
        )
        assertTrue("После любого исхода чип перерисовывается", refresh.contains("updateReasoningButton()"))
        assertNotNull(refresh)
    }
}
