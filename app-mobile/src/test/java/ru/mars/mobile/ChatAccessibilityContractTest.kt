package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт минимального accessibility-пакета чата (mobile): live-регионы статусных
 * строк, подпись кнопки композера по действию, button-role/состояние раскрывающихся
 * заголовков (thinking/tool/todo) и live-регион errorStrip в approval/clarify.
 *
 * Instrumented-слоя нет, поэтому проводка закреплена как source-контракт (стиль
 * [ChatClickPathContractTest]): тест читает РЕАЛЬНЫЙ production-исходник и проверяет,
 * что каждый a11y-атрибут действительно выставлен в view-коде, а решения о текстах
 * принимает чистая [ChatA11yPolicy] (поведение — в [ChatA11yPolicyTest]).
 * Протокол стрима не затрагивается: контракт фиксирует и сохранность drawer/back/
 * approval-проводки вокруг правок.
 */
class ChatAccessibilityContractTest {

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
     * Секции-расширения Activity после разбиения файла (W2). Вынесенный код — тот же
     * production-код экрана, поэтому проверки доступности читают его вместе с Activity
     * и каркасом: набор требований не сужен, а расширен на новые файлы.
     */
    private val chatSections by lazy {
        listOf(
            "ChatTurnSection.kt",
            "ChatOfflineQueueSection.kt",
            "ChatSessionSection.kt",
            "ChatTranscriptSection.kt",
            "ChatDraftSection.kt",
            "ChatUsageSection.kt",
            "ChatTodoSection.kt",
            "ChatMessageActionsSection.kt",
        ).joinToString("\n") { source("src/main/java/ru/mars/mobile/$it") }
    }


    /** Карточку сообщения собирает вынесенный рендерер — заголовки thinking/tool/группы живут там. */
    private val messageCard by lazy { source("src/main/java/ru/mars/mobile/ChatMessageCardRenderer.kt") }

    /** Каркас экрана (шапка, баннеры, поиск, композер) собирает вынесенный [ChatScreenScaffold]. */
    private val scaffold by lazy { source("src/main/java/ru/mars/mobile/ChatScreenScaffold.kt") }

    /**
     * Исходники UI-слоя экрана чата: Activity (делегат + update*-методы) и каркас (создание
     * view). Live-регионы проверяются теми же регулярками, но читаются оба файла: контракт
     * не должен зависеть от того, в каком файле физически собирается view.
     */
    private val chatUi by lazy { chat + "\n" + scaffold + "\n" + chatSections }


    /**
     * Исходники обоих файлов рендер-слоя транскрипта: Activity (композер, todo-заголовок)
     * + рендерер карточки (thinking/tool/группа инструментов). Проверки те же, но покрывают
     * место, куда код переехал при разбиении ChatActivity.
     */
    private val transcriptSources by lazy { chat + "\n" + messageCard + "\n" + chatSections }
    private val dialog by lazy { source("src/main/java/ru/mars/mobile/MarsDialog.kt") }
    private val interactive by lazy { source("src/main/java/ru/mars/mobile/MarsInteractiveView.kt") }

    private fun liveRegionIn(blockRegex: Regex, src: String, what: String) {
        assertTrue(
            "$what обязан быть polite live region (TalkBack озвучивает смену текста)",
            blockRegex.containsMatchIn(src)
        )
    }

    // --- Live-регионы статусных строк -------------------------------------------------------

    @Test
    fun `statusView is a polite live region`() {
        liveRegionIn(
            Regex("statusView = TextView\\((?:this|context)\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chatUi,
            "statusView"
        )
    }

    @Test
    fun `networkBanner is a polite live region`() {
        liveRegionIn(
            Regex("networkBanner = TextView\\((?:this|context)\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chatUi,
            "networkBanner"
        )
    }

    @Test
    fun `searchCounter is a polite live region`() {
        liveRegionIn(
            Regex("searchCounter = TextView\\((?:this|context)\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chatUi,
            "searchCounter"
        )
    }

    // --- Кнопка композера: подпись соответствует действию ------------------------------------

    @Test
    fun `sendButton description is refreshed from ChatA11yPolicy in updateControls`() {
        assertTrue(
            "updateControls обязан обновлять contentDescription кнопки через ChatA11yPolicy " +
                "(typing -> «Остановить», idle -> «Отправить»)",
            Regex("private fun updateControls\\(\\)[\\s\\S]{0,600}?sendButton\\.contentDescription = ChatA11yPolicy\\.sendButtonDescription\\(active\\)")
                .containsMatchIn(chat)
        )
    }

    // --- Раскрывающиеся заголовки: button role + состояние ------------------------------------

    @Test
    fun `expandable header helper assigns button role and state description`() {
        assertTrue(
            "В ChatActivity обязан быть хелпер applyExpandableHeaderA11y",
            chat.contains("private fun applyExpandableHeaderA11y(")
        )
        assertTrue(
            "Хелпер обязан назначать button-role через AccessibilityDelegateCompat",
            chat.contains("AccessibilityDelegateCompat()") &&
                chat.contains("info.className = Button::class.java.name")
        )
        assertTrue(
            "Хелпер обязан выставлять stateDescription через ViewCompat",
            chat.contains("ViewCompat.setStateDescription(")
        )
    }

    @Test
    fun `thinking, tool and todo headers are wired through the a11y helper`() {
        assertTrue(
            "Thinking-заголовок обязан получать доступную подпись из ChatA11yPolicy",
            Regex("ChatA11yPolicy\\.expandableHeaderDescription\\(\\s*thinkingView\\.expanded").containsMatchIn(transcriptSources)
        )
        assertTrue(
            "Tool-заголовок обязан получать доступную подпись из ChatA11yPolicy",
            transcriptSources.contains("ChatA11yPolicy.expandableHeaderDescription(toolView.expanded, toolView.kindLabel, toolView.toolName, toolView.stateLabel)")
        )
        assertTrue(
            "Todo-заголовок обязан получать доступную подпись из ChatA11yPolicy",
            transcriptSources.contains("ChatA11yPolicy.expandableHeaderDescription(todoWidgetExpanded, card.title)")
        )
        // Заголовков со сворачиванием четыре: thinking / tool / todo / группа инструментов.
        // Группа — тоже раскрываемый заголовок, поэтому обязана идти через тот же хелпер;
        // считаем вызовы, чтобы новый сворачиваемый заголовок не проскочил без подписи.
        // 6 = 3 вызова в рендерере карточки (thinking/tool/группа) + объявление хелпера
        // + делегат [ChatMessageCardRenderer.Host.a11yHeader] + тело делегата Activity
        // (Activity пробрасывает хелпер в рендерер карточки, сам делегат заголовком не является).
        val calls = Regex("applyExpandableHeaderA11y\\(").findAll(transcriptSources).count()
        assertEquals(
            "Хелпер обязан вызываться в 4 заголовках (+1 объявление +1 делегат хоста)",
            6,
            calls
        )
        // Todo-заголовок после разбиения файла (W2) живёт в ChatTodoSection и попадает в тот
        // же приватный хелпер через делегат Activity: требование «через общий хелпер» сохранено.
        assertTrue(
            "Todo-заголовок обязан идти через общий a11y-хелпер (делегат Activity)",
            transcriptSources.contains("applyExpandableHeaderA11yFromSection(")
        )
    }

    // --- errorStrip в approval/clarify ----------------------------------------------------------

    @Test
    fun `errorStrip is a polite live region`() {
        liveRegionIn(
            Regex("fun errorStrip\\(context: Context\\): TextView \\{[\\s\\S]{0,600}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            dialog,
            "errorStrip"
        )
    }

    @Test
    fun `approval and clarify dialogs still consume MarsDialog errorStrip`() {
        // Правка a11y не должна рвать проводку ошибок approval/clarify.
        val uses = Regex("MarsDialog\\.errorStrip\\(activity\\)").findAll(interactive).count()
        assertEquals("approval и clarify обязаны использовать общий errorStrip", 2, uses)
    }

    // --- Сохранность соседней семантики ----------------------------------------------------------

    @Test
    fun `drawer, back and mini-player semantics are preserved`() {
        assertTrue("Шторка разделов обязана открываться прежним путём", chat.contains("openDrawer(GravityCompat.START)"))
        assertTrue(
            "Back-навигация обязана оставаться на onBackPressedDispatcher",
            chat.contains("onBackPressedDispatcher")
        )
        assertTrue(
            "Кнопка мини-плеера обязана сохранять озвученный статус из MiniPlayerPresenter",
            chat.contains("miniPlayerPlayBtn.contentDescription = state.statusLabel")
        )
    }

    // --- Кольцо контекста, живой заголовок, лента чипов ---------------------------------

    private val contextRingView by lazy { source("src/main/java/ru/mars/mobile/ContextRingView.kt") }

    @Test
    fun `context ring announces fill percent and stays a touch target`() {
        // Кольцо — не картинка: без contentDescription TalkBack прочитает «View».
        assertTrue(
            "Кольцо обязано озвучивать заполнение окна из политики",
            contextRingView.contains("contentDescription = pill.contentDescription")
        )
        // Число в центре — только визуальный акцент; смысл несёт описание.
        assertTrue(
            "Видимый процент обязан совпадать с озвученным (percent из ContextPillView)",
            contextRingView.contains("label = percent.toString()")
        )
        assertTrue(
            "Кольцо обязано иметь минимальный размер 24dp (тап не по 8dp точке)",
            contextRingView.contains("minimumWidth = sizePx.toInt()")
        )
    }

    @Test
    fun `session title in header is a real text view with the session name`() {
        assertTrue(
            "Шапка обязана отдавать title наружу, а не прятать его в локальной переменной",
            scaffold.contains("val sessionTitle: TextView")
        )
        assertTrue(
            "Живой заголовок пишется в шапку одной точкой (текст + видимость)",
            chat.contains("view.text = text") && chat.contains("view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE")
        )
    }

    @Test
    fun `chip captions carry no typographic glyphs`() {
        // «·»/«•» в подписи — это потерянная иконка: на OEM-шрифте глиф подменяется
        // или рендерится эмодзи. Подпись чипа — только текст.
        val banned = listOf('\u00B7', '\u2022')
        val sources = listOf(
            "src/main/java/ru/mars/mobile/QuickChips.kt",
            "src/main/java/ru/mars/mobile/SmartSuggestionsPolicy.kt",
        ).map { source(it) }
        sources.forEach { src ->
            banned.forEach { glyph ->
                assertTrue(
                    "Подписи чипов не должны содержать глиф U+%04X".format(glyph.code),
                    !src.contains(glyph)
                )
            }
        }
    }
}
