package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.widget.MarsWidgetActionsPolicy
import ru.mars.mobile.widget.MarsWidgetActionsPolicy.WidgetAction
import java.io.File
import java.util.TimeZone

/**
 * Контракт виджетов рабочего стола (волна 17, пункт 4 владельца).
 *
 * Две части, и обе обязательны:
 *
 * 1. **Поведение чистой политики** [MarsWidgetActionsPolicy] — маршрутизация действия
 *    виджета и форматирование подписи последнего диалога. Это обычный JVM-тест: политика
 *    не тянет android-классы именно для того, чтобы «тап по микрофону включает диктовку»
 *    проверялся здесь, а не на телефоне.
 * 2. **Регистрация и проводка** — виджет, которого нет в манифесте, не появится в пикере
 *    виджетов вообще; провайдер без meta-data останется с пустой разметкой; провайдер,
 *    который не читает extra, откроет чат и промолчит. Тест читает реальные
 *    AndroidManifest.xml, res/xml, res/layout, res/drawable и исходники экрана чата —
 *    удаление любой строки проводки ломает контракт.
 */
class MarsWidgetActionsPolicyTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подъём вверх —
        // подстраховка для worktree/CI с другим cwd (тот же приём, что в остальных
        // контрактных тестах проекта).
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

    private fun exists(relative: String): Boolean {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, relative).isFile) return true
            if (File(dir, "app-mobile/$relative").isFile) return true
            dir = dir.parentFile
        }
        return false
    }

    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val quickInfo by lazy { source("src/main/res/xml/mars_quick_widget_info.xml") }
    private val compactInfo by lazy { source("src/main/res/xml/mars_compact_widget_info.xml") }
    private val quickLayout by lazy { source("src/main/res/layout/widget_mars_quick.xml") }
    private val compactLayout by lazy { source("src/main/res/layout/widget_mars_compact.xml") }
    private val strings by lazy { source("src/main/res/values/strings.xml") }
    private val chat by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }
    private val initSection by lazy { source("src/main/java/ru/mars/mobile/ChatInitSection.kt") }
    private val quickProvider by lazy {
        source("src/main/java/ru/mars/mobile/widget/MarsQuickWidgetProvider.kt")
    }
    private val compactProvider by lazy {
        source("src/main/java/ru/mars/mobile/widget/MarsCompactWidgetProvider.kt")
    }

    // --- 1. Контракт действия -------------------------------------------------------------

    @Test
    fun `action contract - extra name and three values are fixed strings`() {
        // Значения — часть межпроцессного контракта: их пишет виджет (процесс лаунчера)
        // и читает ChatActivity (свой процесс). Переименование любой строки = «тап
        // по микрофону открывает чат и молчит».
        assertEquals("extra_widget_action", MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION)
        assertEquals("compose", MarsWidgetActionsPolicy.ACTION_COMPOSE)
        assertEquals("voice", MarsWidgetActionsPolicy.ACTION_VOICE)
        assertEquals("new_chat", MarsWidgetActionsPolicy.ACTION_NEW_CHAT)
        assertEquals("mars_widget_state", MarsWidgetActionsPolicy.STATE_PREFS)
    }

    @Test
    fun `action routing - known values map to actions and everything else is none`() {
        assertEquals(WidgetAction.COMPOSE, MarsWidgetActionsPolicy.actionFor("compose"))
        assertEquals(WidgetAction.VOICE, MarsWidgetActionsPolicy.actionFor("voice"))
        assertEquals(WidgetAction.NEW_CHAT, MarsWidgetActionsPolicy.actionFor("new_chat"))
        // Регистр и пробелы не должны ломать тап: строку пишет XML/inтент, а не человек.
        assertEquals(WidgetAction.VOICE, MarsWidgetActionsPolicy.actionFor("  VOICE "))
        // Обычный запуск (список диалогов, push, deep-link) не «делает сам» ничего.
        assertEquals(WidgetAction.NONE, MarsWidgetActionsPolicy.actionFor(null))
        assertEquals(WidgetAction.NONE, MarsWidgetActionsPolicy.actionFor(""))
        assertEquals(WidgetAction.NONE, MarsWidgetActionsPolicy.actionFor("   "))
        assertEquals(WidgetAction.NONE, MarsWidgetActionsPolicy.actionFor("compose_all"))
        assertEquals(WidgetAction.NONE, MarsWidgetActionsPolicy.actionFor("открыть"))
    }

    @Test
    fun `action routing - reverse mapping matches the forward one`() {
        WidgetAction.values().forEach { action ->
            val raw = MarsWidgetActionsPolicy.extraValue(action)
            if (action == WidgetAction.NONE) {
                assertNull("NONE не имеет строкового значения", raw)
            } else {
                assertEquals(action, MarsWidgetActionsPolicy.actionFor(raw))
            }
        }
    }

    // --- 2. Состояние виджета: дефолты и форматирование -----------------------------------

    @Test
    fun `state - empty prefs become the ready-to-work defaults`() {
        val state = MarsWidgetActionsPolicy.state(sessionId = null, rawTitle = null, rawSnippet = null)
        assertEquals("Готов к работе", state.title)
        assertEquals("Нажмите, чтобы продолжить диалог или задать вопрос", state.snippet)
        assertNull(state.sessionId)
        assertFalse("тап не должен вести в несуществующую сессию", state.hasSession)
        assertEquals(MarsWidgetActionsPolicy.DEFAULT_TITLE, state.title)
        assertEquals(MarsWidgetActionsPolicy.DEFAULT_SNIPPET, state.snippet)
    }

    @Test
    fun `state - blank values fall back instead of rendering an empty card`() {
        val state = MarsWidgetActionsPolicy.state(sessionId = "   ", rawTitle = "  ", rawSnippet = "\n\t ")
        assertNull("пустой id нормализуется в null", state.sessionId)
        assertEquals(MarsWidgetActionsPolicy.DEFAULT_TITLE, state.title)
        assertEquals(MarsWidgetActionsPolicy.DEFAULT_SNIPPET, state.snippet)
    }

    @Test
    fun `state - live session keeps id, title and snippet`() {
        val state = MarsWidgetActionsPolicy.state(
            sessionId = " s-42 ",
            rawTitle = "Виджеты Марса",
            rawSnippet = "Готово: панель и компактная полоса",
            updatedAt = 1_700_000_000_000L,
        )
        assertEquals("s-42", state.sessionId)
        assertTrue(state.hasSession)
        assertEquals("Виджеты Марса", state.title)
        assertEquals("Готово: панель и компактная полоса", state.snippet)
        assertEquals(1_700_000_000_000L, state.updatedAt)
    }

    @Test
    fun `title - one line, markdown stripped, long names cut at a word boundary`() {
        // Переносы строк и лишние пробелы: в виджете ровно одна строка 13sp.
        assertEquals("Мой диалог про Марс", MarsWidgetActionsPolicy.title("  Мой   диалог \n про Марс  "))
        assertEquals("Виджеты Марса", MarsWidgetActionsPolicy.title("## Виджеты **Марса**"))
        // 40 символов при пределе 28: режется по последнему пробелу и получает многоточие.
        assertEquals(
            "Очень длинный заголовок…",
            MarsWidgetActionsPolicy.title("Очень длинный заголовок диалога про Марс"),
        )
        assertEquals(MarsWidgetActionsPolicy.MAX_TITLE_CHARS, 28)
    }

    @Test
    fun `snippet - markdown noise removed and long answers truncated with ellipsis`() {
        val answer = "## Готово\n\n**Виджеты** работают: `MarsQuickWidgetProvider` уже собран."
        assertEquals(
            "Готово Виджеты работают: MarsQuickWidgetProvider уже собран.",
            MarsWidgetActionsPolicy.snippet(answer),
        )
        // Блок кода в превью — мусорные кавычки: снимается вместе с бэктиками.
        assertEquals("code", MarsWidgetActionsPolicy.snippet("```code```"))
        assertEquals(MarsWidgetActionsPolicy.DEFAULT_SNIPPET, MarsWidgetActionsPolicy.snippet("**"))
        // Слово длиннее предела: режем жёстко, но многоточие ставим и длину держим.
        val long = "а".repeat(200)
        val cut = MarsWidgetActionsPolicy.snippet(long)
        assertEquals(MarsWidgetActionsPolicy.MAX_SNIPPET_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
        // Предел держится и на обычном тексте.
        assertTrue(MarsWidgetActionsPolicy.snippet("Слово ".repeat(60)).length <= MarsWidgetActionsPolicy.MAX_SNIPPET_CHARS)
    }

    @Test
    fun `updated label - zero means no label, otherwise local HH mm`() {
        assertEquals("", MarsWidgetActionsPolicy.updatedLabel(0L))
        assertEquals("", MarsWidgetActionsPolicy.updatedLabel(-5L))
        assertEquals("22:13", MarsWidgetActionsPolicy.updatedLabel(1_700_000_000_000L, TimeZone.getTimeZone("GMT")))
    }

    // --- 3. Регистрация в манифесте и ресурсы виджетов ------------------------------------

    @Test
    fun `manifest - both widget receivers are exported and accept APPWIDGET_UPDATE`() {
        val quickBlock = manifest.substringAfter("android:name=\".widget.MarsQuickWidgetProvider\"")
            .substringBefore("</receiver>")
        assertTrue("провайдер панели обязан быть exported: обновление шлёт лаунчер",
            quickBlock.contains("android:exported=\"true\""))
        assertTrue("без APPWIDGET_UPDATE система не доставит обновление",
            quickBlock.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertTrue("meta-data связывает провайдер с разметкой и правилами ресайза",
            quickBlock.contains("android.appwidget.provider"))
        assertTrue(quickBlock.contains("@xml/mars_quick_widget_info"))
        assertTrue("подпись в пикере виджетов", quickBlock.contains("@string/widget_mars_quick_title"))

        val compactBlock = manifest.substringAfter("android:name=\".widget.MarsCompactWidgetProvider\"")
            .substringBefore("</receiver>")
        assertTrue(compactBlock.contains("android:exported=\"true\""))
        assertTrue(compactBlock.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertTrue(compactBlock.contains("android.appwidget.provider"))
        assertTrue(compactBlock.contains("@xml/mars_compact_widget_info"))
        assertTrue(compactBlock.contains("@string/widget_mars_compact_title"))
    }

    @Test
    fun `manifest - both providers are registered exactly once`() {
        assertEquals(
            "дубль receiver'а даёт два виджета в пикере",
            1,
            Regex("android:name=\"\\.widget\\.MarsQuickWidgetProvider\"").findAll(manifest).count(),
        )
        assertEquals(
            1,
            Regex("android:name=\"\\.widget\\.MarsCompactWidgetProvider\"").findAll(manifest).count(),
        )
    }

    @Test
    fun `widget info - panel is resizable 4x1 to 4x3 and never wakes the process on a timer`() {
        assertTrue(quickInfo.contains("android:initialLayout=\"@layout/widget_mars_quick\""))
        assertTrue("превью в пикере — настоящая карточка, а не серая заглушка",
            quickInfo.contains("android:previewLayout=\"@layout/widget_mars_quick\""))
        assertTrue("панель масштабируется по обеим осям",
            quickInfo.contains("android:resizeMode=\"horizontal|vertical\""))
        assertTrue("4x2 по умолчанию", quickInfo.contains("android:targetCellWidth=\"4\"") &&
            quickInfo.contains("android:targetCellHeight=\"2\""))
        assertTrue("4x1 как минимальная высота", quickInfo.contains("android:minResizeHeight=\"56dp\""))
        assertTrue("обновление только по состоянию, не по таймеру",
            quickInfo.contains("android:updatePeriodMillis=\"0\""))
        assertTrue(quickInfo.contains("android:widgetCategory=\"home_screen\""))
        assertTrue(quickInfo.contains("@string/widget_mars_quick_description"))
    }

    @Test
    fun `widget info - compact strip is one cell high and resizes only horizontally`() {
        assertTrue(compactInfo.contains("android:initialLayout=\"@layout/widget_mars_compact\""))
        assertTrue(compactInfo.contains("android:previewLayout=\"@layout/widget_mars_compact\""))
        assertTrue("полоса растягивается только вширь", compactInfo.contains("android:resizeMode=\"horizontal\""))
        assertTrue(compactInfo.contains("android:targetCellHeight=\"1\""))
        assertTrue(compactInfo.contains("android:updatePeriodMillis=\"0\""))
        assertTrue(compactInfo.contains("@string/widget_mars_compact_description"))
    }

    @Test
    fun `layouts - panel carries state block and all four quick actions`() {
        listOf(
            "@+id/widget_mars_root",
            "@+id/widget_mars_dialog",
            "@+id/widget_mars_planet",
            "@+id/widget_mars_session_title",
            "@+id/widget_mars_snippet",
            "@+id/widget_mars_sessions",
            "@+id/widget_mars_input",
            "@+id/widget_mars_mic",
            "@+id/widget_mars_voice_mode",
            "@+id/widget_mars_star",
        ).forEach { id ->
            assertTrue("в разметке панели нет $id", quickLayout.contains(id))
        }
        assertTrue("@drawable/ic_mars", quickLayout.contains("@drawable/ic_mars"))
        assertTrue("@drawable/ic_mic", quickLayout.contains("@drawable/ic_mic"))
        assertTrue("@drawable/ic_nav_local", quickLayout.contains("@drawable/ic_nav_local"))
        assertTrue("@drawable/ic_send", quickLayout.contains("@drawable/ic_send"))
        assertTrue("фон карточки", quickLayout.contains("@drawable/widget_mars_bg"))
        assertTrue("капсула ввода", quickLayout.contains("@drawable/widget_mars_input_pill"))
        assertTrue("тёмный ореол кнопок", quickLayout.contains("@drawable/widget_mars_action_btn"))
        assertTrue("тёмный рубиновый ореол звезды", quickLayout.contains("@drawable/widget_mars_star_btn"))
        assertTrue("превью ответа — 1–2 строки",
            Regex("android:maxLines=\"2\"").containsMatchIn(quickLayout))
    }

    @Test
    fun `layouts - compact strip has planet, input pill, mic and star`() {
        listOf(
            "@+id/widget_compact_root",
            "@+id/widget_compact_planet",
            "@+id/widget_compact_input",
            "@+id/widget_compact_mic",
            "@+id/widget_compact_star",
        ).forEach { id ->
            assertTrue("в разметке полосы нет $id", compactLayout.contains(id))
        }
        assertTrue(compactLayout.contains("@drawable/widget_mars_bg"))
        assertTrue(compactLayout.contains("@drawable/widget_mars_input_pill"))
        assertTrue(compactLayout.contains("@drawable/widget_mars_star_btn"))
    }

    @Test
    fun `resources - every drawable, layout, xml and string the widgets reference exists`() {
        listOf(
            "src/main/res/layout/widget_mars_quick.xml",
            "src/main/res/layout/widget_mars_compact.xml",
            "src/main/res/xml/mars_quick_widget_info.xml",
            "src/main/res/xml/mars_compact_widget_info.xml",
            "src/main/res/drawable/widget_mars_bg.xml",
            "src/main/res/drawable/widget_mars_input_pill.xml",
            "src/main/res/drawable/widget_mars_action_btn.xml",
            "src/main/res/drawable/widget_mars_star_btn.xml",
        ).forEach { path -> assertTrue("нет файла $path", exists(path)) }

        listOf(
            "widget_mars_quick_title",
            "widget_mars_quick_description",
            "widget_mars_compact_title",
            "widget_mars_compact_description",
            "widget_mars_label",
            "widget_mars_sessions",
            "widget_mars_compose_hint",
            "widget_mars_action_voice",
            "widget_mars_action_voice_mode",
            "widget_mars_action_new_chat",
        ).forEach { name ->
            assertTrue("в strings.xml нет $name", strings.contains("name=\"$name\""))
        }
        // Дефолты состояния живут и в ресурсах (initialLayout до первого обновления),
        // и в политике — значения обязаны совпадать, иначе виджет «мигает» текстом.
        assertTrue(strings.contains(">Готов к работе<"))
        assertTrue(strings.contains(">Нажмите, чтобы продолжить диалог или задать вопрос<"))
    }

    // --- 4. Проводка: виджет → ChatActivity ----------------------------------------------

    @Test
    fun `wiring - chat applies each widget action exactly as contracted`() {
        val apply = initSection
            .substringAfter("internal fun ChatActivity.applyWidgetAction(")
            .substringBefore("internal fun ChatActivity.consumeWidgetAction(")
        assertTrue("действие обязано маршрутизироваться политикой, а не сравнением строк",
            apply.contains("MarsWidgetActionsPolicy.actionFor("))
        assertTrue("new_chat обязан начинать чистый диалог", apply.contains("startNewChat()"))
        assertTrue("compose/new_chat обязаны поднимать клавиатуру", apply.contains("focusComposerFromWidget()"))
        assertTrue("voice обязан сразу включать диктовку", apply.contains("startVoiceInput()"))
        assertTrue("запись стартует после построения поля ввода", apply.contains("input.post"))
        assertTrue(apply.contains("WidgetAction.VOICE"))
        assertTrue(apply.contains("WidgetAction.COMPOSE"))
        assertTrue(apply.contains("WidgetAction.NEW_CHAT"))
        assertTrue(apply.contains("WidgetAction.NONE"))
    }

    @Test
    fun `wiring - keyboard is requested with SHOW_IMPLICIT after layout`() {
        val focus = initSection
            .substringAfter("internal fun ChatActivity.focusComposerFromWidget(")
            .substringBefore("internal fun ChatActivity.syncWidgetState(")
        assertTrue(focus.contains("input.requestFocus()"))
        assertTrue("без post() запрос IME теряется: окно ещё без фокуса", focus.contains("input.post"))
        assertTrue(focus.contains("InputMethodManager.SHOW_IMPLICIT"))
        assertTrue(focus.contains("showSoftInput(input"))
    }

    @Test
    fun `wiring - action is consumed once so rotation cannot wipe an open chat`() {
        assertTrue(
            "действие читается и снимается одной функцией",
            initSection.contains("internal fun ChatActivity.consumeWidgetAction()") &&
                initSection.contains("intent?.removeExtra(MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION)"),
        )
        val onCreate = chat.substringAfter("override fun onCreate").substringBefore("override fun onDestroy")
        assertTrue(
            "холодный старт применяет действие только без savedInstanceState",
            onCreate.contains("if (savedInstanceState == null) applyWidgetAction(consumeWidgetAction())"),
        )
        val onNewIntent = chat.substringAfter("override fun onNewIntent").substringBefore("internal inner class")
        assertTrue(
            "повторный тап с рабочего стола по уже открытому чату тоже обязан сработать",
            onNewIntent.contains("applyWidgetAction(consumeWidgetAction())"),
        )
    }

    @Test
    fun `wiring - live state reaches the widget on session change, turn end and pause`() {
        assertTrue("смена диалога обновляет карточку", chat.contains("syncWidgetState()"))
        assertTrue("уход в фон фиксирует последний ответ", initSection.contains("syncWidgetState()"))
        assertTrue(
            "состояние пишется в mars_widget_state через провайдер",
            initSection.contains("MarsQuickWidgetProvider.publishState(") &&
                initSection.contains("uiState.lastAssistantText()"),
        )
        assertTrue(
            "logout не оставляет подпись диалога на домашнем экране",
            initSection.contains("store.read() == null"),
        )
        assertTrue("провайдер умеет перерисовать виджет по сохранённому состоянию",
            quickProvider.contains("fun notifyStateChanged("))
        assertTrue(quickProvider.contains("fun publishState("))
        assertTrue(quickProvider.contains("MarsWidgetActionsPolicy.STATE_PREFS"))
    }

    @Test
    fun `wiring - provider taps route to chat, sessions and voice mode`() {
        assertTrue("блок диалога открывает ИМЕННО его сессию",
            quickProvider.contains("EXTRA_OPEN_SESSION_ID"))
        assertTrue(quickProvider.contains("ChatSessionsActivity"))
        assertTrue("живой голос открывает разговор сразу, а не пустую витрину",
            quickProvider.contains("LocalChatActivity") &&
                quickProvider.contains("EXTRA_OPEN_VOICE_MODE"))
        assertTrue("действие передаётся extra, а не угадывается по классу экрана",
            quickProvider.contains("MarsWidgetActionsPolicy.EXTRA_WIDGET_ACTION"))
        // PendingIntent'ы обязаны быть неизменяемыми: Android 12 иначе бросает исключение.
        assertTrue(quickProvider.contains("PendingIntent.FLAG_IMMUTABLE"))
        // Каждое действие — свой requestCode, иначе тапы схлопываются в один PendingIntent.
        assertTrue(quickProvider.contains("REQUEST_COMPOSE"))
        assertTrue(quickProvider.contains("REQUEST_VOICE"))
        assertTrue(quickProvider.contains("REQUEST_NEW_CHAT"))
        assertTrue(quickProvider.contains("REQUEST_DIALOG"))
        assertTrue(quickProvider.contains("REQUEST_SESSIONS"))
        assertTrue(quickProvider.contains("REQUEST_VOICE_MODE"))
    }

    @Test
    fun `wiring - compact strip exposes the same three actions`() {
        assertTrue(compactProvider.contains("R.layout.widget_mars_compact"))
        assertTrue(compactProvider.contains("MarsWidgetActionsPolicy.ACTION_COMPOSE"))
        assertTrue(compactProvider.contains("MarsWidgetActionsPolicy.ACTION_VOICE"))
        assertTrue(compactProvider.contains("MarsWidgetActionsPolicy.ACTION_NEW_CHAT"))
        assertTrue("планета ведёт в список диалогов", compactProvider.contains("sessionListIntent"))
    }
}
