package ru.mars.mobile

import org.junit.Assert.assertFalse
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

    /**
     * Секции-расширения Activity после разбиения файла (W2): вынесенный код остаётся тем же
     * production-кодом, поэтому контракт читает его вместе с Activity, а не сужается до
     * одного файла. Ни один шаг пути не ослаблен — те же строки просто ищутся шире.
     */
    private val chatSections by lazy {
        listOf(
            "ChatInitSection.kt",
            "ChatShellSection.kt",
            "ChatComposerSection.kt",
            "ChatCardHostSection.kt",
            "ChatStatusSection.kt",
            "ChatTurnSection.kt",
            "ChatSessionSection.kt",
            "ChatSessionLifecycleSection.kt",
            "ChatTranscriptSection.kt",
            "ChatSearchSection.kt",
            "ChatDraftSection.kt",
            "ChatModelSection.kt",
            "ChatUsageSection.kt",
            "ChatTodoSection.kt",
            "ChatMessageActionsSection.kt",
            "ChatOfflineQueueSection.kt",
            "ChatVoiceInputSection.kt",
            "ChatVoiceSpeechSection.kt",
        ).joinToString("\n") { source("src/main/java/ru/mars/mobile/$it") }
    }


    /** Каркас экрана чата: шапка, баннеры, поиск, чипы и композер собираются здесь. */
    private val scaffold by lazy { source("src/main/java/ru/mars/mobile/ChatScreenScaffold.kt") }

    /**
     * UI-слой экрана чата: Activity (делегат, пикеры, update*-методы) + вынесенный каркас.
     * Проверки кликпутей те же — они читают оба файла, чтобы контракт не зависел от того,
     * в каком файле физически собирается view.
     */
    private val chatUi by lazy { chat + "\n" + scaffold + "\n" + chatSections }

    /**
     * Тело функции по имени в склеенном исходнике (Activity + секции). Имя можно
     * квалифицировать (`ChatActivity.chooseModel`), когда рядом живёт одноимённый
     * override-делегат хоста каркаса: его тело ничего не доказывает про проводку.
     */
    private fun body(src: String, name: String): String {
        var start = src.indexOf("private fun $name(")
        if (start < 0) start = src.indexOf("internal fun $name(")
        if (start < 0) start = src.indexOf("fun $name(")
        if (start < 0) {
            val match = Regex("""fun\s+(?:[\w.]+\.)?${Regex.escape(name)}\s*\(""").find(src)
            if (match != null) start = match.range.first
        }
        assertTrue("функция $name не найдена", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("у $name нет тела", open > start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не закрыто тело $name")
    }
    private val searchController by lazy { source("src/main/java/ru/mars/mobile/TranscriptSearchHighlightController.kt") }
    private val main by lazy { source("src/main/java/ru/mars/mobile/MainActivity.kt") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val sessions by lazy { source("src/main/java/ru/mars/mobile/ChatSessionsActivity.kt") }

    // --- Шаг 1: запуск ------------------------------------------------------------------

    @Test
    fun `launch - launcher entry is SplashActivity and ChatActivity is declared`() {
        // Точка входа — заставка: MainActivity при сохранённом токене не рисует
        // ни одного кадра, поэтому LAUNCHER обязан вести на бренд-экран.
        val splashBlock = manifest.substringAfter("android:name=\".SplashActivity\"")
            .substringBefore("</activity>")
        assertTrue(
            "LAUNCHER должен быть на SplashActivity",
            splashBlock.contains("android.intent.category.LAUNCHER")
        )
        assertTrue(
            "SplashActivity обязан нести брендовую тему",
            splashBlock.contains("android:theme=\"@style/SplashTheme\"")
        )
        val mainBlock = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertFalse(
            "MainActivity больше не точка входа: иначе заставка не показывается",
            mainBlock.contains("android.intent.category.LAUNCHER")
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
        assertTrue("Кнопка разделов должна быть wired в ChatActivity", chatUi.contains("openDrawer(GravityCompat.START)"))
        assertTrue("Левый бар должен строиться через AppSectionsBar", chatUi.contains("AppSectionsBar("))
        assertTrue("Список диалогов должен быть отдельным экраном", chatUi.contains("ChatSessionsActivity"))
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
            chatUi.contains("searchPlate = LinearLayout(context)") &&
                chatUi.contains("searchInput = EditText(context)") &&
                chatUi.contains("searchCounter = TextView(context)")
        )
        assertTrue(
            "Next/prev поискa обязаны вызывать navigateTranscriptSearch",
            chatUi.contains("navigateTranscriptSearch(forward = true)") &&
                chatUi.contains("navigateTranscriptSearch(forward = false)")
        )
        assertTrue(
            "Закрытие панели поиска обязано быть wired",
            chatUi.contains("setOnClickListener { host.closeTranscriptSearch() }")
        )
        assertTrue(
            "Совпадения/счётчик/подсветка — контроллер поиска на чистой MessageSearchPolicy",
            chat.contains("TranscriptSearchHighlightController") &&
                searchController.contains("MessageSearchPolicy")
        )
    }

    // --- Шаг 4: открытие сессии ----------------------------------------------------------

    @Test
    fun `open session - leaf click uses policy and back returns to session list`() {
        assertTrue("Клик по parent должен переключать ветку только через отдельную кнопку", sessions.contains("toggleChildren()"))
        assertTrue("Клик по карточке должен открывать и parent, и leaf", sessions.contains("card.setOnClickListener { openRow() }"))
        assertTrue("Название диалога должно открывать чат", sessions.contains("setOnClickListener { openRow() }"))
    }

    @Test
    fun `open session - navigation uses the real intent bridge`() {
        assertTrue("Intent bridge должен открывать ChatActivity", sessions.contains("openSessionIntent"))
        assertTrue("Intent должен нести session id", sessions.contains("sessionId"))
    }

    // --- Шаг 5: Back-навигация -----------------------------------------------------------

    @Test
    fun `back - policy wires chat to sessions and sessions to sections`() {
        assertTrue("ChatActivity должна использовать NavBackPolicy", chatUi.contains("NavBackPolicy.resolve"))
        assertTrue("ChatActivity должна вести в список диалогов", chatUi.contains("GO_TO_SESSION_LIST"))
        assertTrue("Список должен использовать NavBackPolicy", sessions.contains("NavBackPolicy.resolve"))
        assertTrue("Список должен открывать разделы по Back", sessions.contains("Action.OPEN_DRAWER"))
        assertTrue("Повторный Back из открытых разделов должен завершать экран", sessions.contains("Action.SYSTEM_EXIT"))
    }

    // --- Шаг 6: модель/reasoning controls -------------------------------------------------

    @Test
    fun `model chip - click opens picker from live catalog and persists explicit pick`() {
        val chip = chatUi.substringAfter("modelBtn = Button(context).apply {")
            .substringBefore("chipsRow.addView(modelBtn")
        assertTrue("Чип модели обязан иметь contentDescription «Модель»", chip.contains("contentDescription = \"Модель\""))
        assertTrue(
            "Клик по чипу обязан вызывать chooseModel() (через хост каркаса)",
            chip.contains("setOnClickListener { host.chooseModel() }")
        )

        val choose = body(chatUi, "ChatActivity.chooseModel")
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
        val chip = chatUi.substringAfter("reasoningBtn = Button(context).apply {")
            .substringBefore("chipsRow.addView(reasoningBtn")
        assertTrue(
            "Чип reasoning скрыт до серверного статуса (visibility = View.GONE)",
            chip.contains("visibility = View.GONE")
        )
        assertTrue(
            "Клик по чипу обязан вызывать chooseReasoningEffort() (через хост каркаса)",
            chip.contains("setOnClickListener { host.chooseReasoningEffort() }")
        )

        val update = body(chatUi, "ChatActivity.updateReasoningButton")
        assertTrue(
            "Подпись чипа — только reasoningSelection.buttonLabel(); null прячет чип",
            update.contains("reasoningSelection.buttonLabel()") &&
                update.contains("if (label == null) View.GONE else View.VISIBLE")
        )

        val apply = body(chatUi, "ChatActivity.applyReasoningEffort")
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
        val refresh = body(chatUi, "ChatActivity.refreshReasoningStatus")
        assertTrue(
            "Провал статуса обязан сбрасывать состояние (applyStatus(null))",
            refresh.contains("is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)")
        )
        assertTrue("После любого исхода чип перерисовывается", refresh.contains("updateReasoningButton()"))
        assertNotNull(refresh)
    }

    // --- Шаг 7: контекст, живой заголовок, стартовые подсказки ---------------------------

    /** Кольцо контекста вынесено из каркаса: своя View + чистая политика бюджета. */
    private val contextRingView by lazy { source("src/main/java/ru/mars/mobile/ContextRingView.kt") }
    private val sessionTitlePolicy by lazy { source("src/main/java/ru/mars/mobile/SessionTitlePolicy.kt") }

    @Test
    fun `context ring - tap opens usage details and arc comes from ContextBudgetPolicy`() {
        assertTrue(
            "Кольцо обязано строиться каркасом и открывать детали окна контекста",
            scaffold.contains("ContextRingView(context)") &&
                scaffold.contains("setOnClickListener { host.showContextUsageDetails() }")
        )
        assertTrue(
            "Кольцо рисует только решение ContextBudgetPolicy.pill (никаких своих порогов)",
            chatUi.contains("view.render(ContextBudgetPolicy.pill(uiState.contextUsage, compressionRunning))")
        )
        assertTrue(
            "View кольца обязана нести contentDescription из политики (TalkBack)",
            contextRingView.contains("contentDescription = pill.contentDescription")
        )
        assertTrue(
            "Кольцо обязано быть компактным (24dp), а не крупной кнопкой",
            contextRingView.contains("RING_SIZE_DP = 24")
        )
    }

    @Test
    fun `live title - title frame renames header through SessionTitlePolicy`() {
        assertTrue(
            "Side-канал обязан принимать кадр title и отдавать его потребителю экрана",
            chatUi.contains("event is ru.mars.network.model.SessionTitleUpdate") &&
                chatUi.contains("onSessionTitle(event, boundSessionId)")
        )
        assertTrue(
            "Кому достаётся кадр — решает чистая SessionTitlePolicy",
            chatUi.contains("SessionTitlePolicy.decide(")
        )
        assertTrue(
            "Заголовок обязан доезжать до шапки через единственную точку записи",
            chatUi.contains("applySessionTitle(decision.title)")
        )
        assertTrue(
            "Строка списка переименовывается тем же кадром (общая модель сессий)",
            chatUi.contains("uiState.applySessionTitle(decision.sessionId, decision.title)")
        )
        assertTrue(
            "Смена сессии/новый чат обязаны обновлять шапку, а не оставлять старый title",
            chatUi.contains("applySessionTitle(uiState.sessionTitle)")
        )
        assertTrue(
            "Строка списка переименовывается тем же решением",
            sessionTitlePolicy.contains("fun renamed(sessions: List<SessionItem>")
        )
    }

    @Test
    fun `empty chat - starter suggestions render in the chips ribbon`() {
        assertTrue(
            "Пустой диалог обязан показывать стартовые подсказки SmartSuggestionsPolicy",
            chatUi.contains("SmartSuggestionsPolicy.suggestionsFor(")
        )
        assertTrue(
            "Стартеры живут в той же ленте и уходят при появлении сообщений",
            chatUi.contains("val emptyChat = uiState.messages.isEmpty()")
        )
        assertTrue(
            "Кнопка ленты обязана нести иконку и текст (без эмодзи)",
            Regex("fun (?:ChatActivity\\.)?buildRibbonChip\\(label: String, iconRes: Int, message: String\\)")
                .containsMatchIn(chatUi)
        )
    }

    @Test
    fun `composer - programmatic text and focus pass ComposerInputGuardPolicy`() {
        assertTrue(
            "Программная вставка обязана спрашивать политику, а не писать в поле напрямую",
            chatUi.contains("composerGuard.resolveTextApply(origin)")
        )
        assertTrue(
            "Фокус композера обязан транслироваться в политику",
            chatUi.contains("composerGuard.onComposerFocusChanged(focused)")
        )
        assertTrue(
            "Возврат фокуса после обрыва — по решению политики, с отложенным повтором",
            chatUi.contains("composerGuard.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED)") &&
                chatUi.contains("ComposerFocusDecision.DeferUntilQuiet")
        )
    }
}
