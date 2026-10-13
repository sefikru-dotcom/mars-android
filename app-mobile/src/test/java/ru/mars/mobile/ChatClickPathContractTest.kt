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
    private val sessions by lazy { source("src/main/java/ru/mars/mobile/ChatSessionsActivity.kt") }

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
    fun `drawer - menu button opens the sections drawer`() {
        assertTrue("Кнопка разделов должна быть wired в ChatActivity", chat.contains("openDrawer(GravityCompat.START)"))
        assertTrue("Левый бар должен строиться через AppSectionsBar", chat.contains("AppSectionsBar("))
        assertTrue("Список диалогов должен быть отдельным экраном", chat.contains("ChatSessionsActivity"))
    }

    @Test
    fun `session list - renders through SessionTreePolicy and opens leaf`() {
        assertTrue("ChatSessionsActivity должен быть объявлен", sessions.contains("class ChatSessionsActivity"))
        assertTrue("Список обязан строиться через SessionTreePolicy.tree", sessions.contains("SessionTreePolicy.tree("))
        assertTrue("Дерево обязано использовать visibleRows", sessions.contains("SessionTreePolicy.visibleRows(tree)"))
        assertTrue("Родитель раскрывает детей внутри карточки", sessions.contains("expandedParents.add(session.id)"))
        assertTrue("Leaf открывается через AppNavIntents", sessions.contains("AppNavIntents.openSessionIntent"))
    }

    // --- Шаг 3: поиск в диалогах ---------------------------------------------------------

    @Test
    fun `search - session filter feeds filterQuery and re-renders list`() {
        assertTrue("Фильтр диалогов должен быть в ChatSessionsActivity", sessions.contains("filterQuery"))
        assertTrue("Ввод должен перерисовывать список", sessions.contains("renderSessionList()"))
        assertTrue("Поиск должен попадать в SessionTreePolicy", sessions.contains("query = filterQuery"))
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
    fun `open session - leaf click uses policy and back returns to session list`() {
        assertTrue("Клик по parent должен переключать ветку", sessions.contains("node.hasChildren"))
        assertTrue("Клик по leaf должен открывать ChatActivity", sessions.contains("AppNavIntents.openSessionIntent"))
        assertTrue("ChatActivity должна вести назад в список", chat.contains("navigateToSessionList()"))
    }

    @Test
    fun `open session - navigation uses the real intent bridge`() {
        assertTrue("Intent bridge должен открывать ChatActivity", sessions.contains("openSessionIntent"))
        assertTrue("Intent должен нести session id", sessions.contains("sessionId"))
    }

    // --- Шаг 5: Back-навигация -----------------------------------------------------------

    @Test
    fun `back - policy wires chat to sessions and sessions to sections`() {
        assertTrue("ChatActivity должна использовать NavBackPolicy", chat.contains("NavBackPolicy.resolve"))
        assertTrue("ChatActivity должна вести в список диалогов", chat.contains("GO_TO_SESSION_LIST"))
        assertTrue("Список должен использовать NavBackPolicy", sessions.contains("NavBackPolicy.resolve"))
        assertTrue("Список должен открывать разделы по Back", sessions.contains("Action.OPEN_DRAWER"))
        assertTrue("Повторный Back из открытых разделов должен завершать экран", sessions.contains("Action.SYSTEM_EXIT"))
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
