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
            Regex("statusView = TextView\\(this\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chat,
            "statusView"
        )
    }

    @Test
    fun `networkBanner is a polite live region`() {
        liveRegionIn(
            Regex("networkBanner = TextView\\(this\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chat,
            "networkBanner"
        )
    }

    @Test
    fun `searchCounter is a polite live region`() {
        liveRegionIn(
            Regex("searchCounter = TextView\\(this\\)\\.apply \\{[\\s\\S]{0,300}?accessibilityLiveRegion = View\\.ACCESSIBILITY_LIVE_REGION_POLITE"),
            chat,
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
            Regex("ChatA11yPolicy\\.expandableHeaderDescription\\(\\s*thinkingView\\.expanded").containsMatchIn(chat)
        )
        assertTrue(
            "Tool-заголовок обязан получать доступную подпись из ChatA11yPolicy",
            chat.contains("ChatA11yPolicy.expandableHeaderDescription(toolView.expanded, toolView.kindLabel, toolView.toolName, toolView.stateLabel)")
        )
        assertTrue(
            "Todo-заголовок обязан получать доступную подпись из ChatA11yPolicy",
            chat.contains("ChatA11yPolicy.expandableHeaderDescription(todoWidgetExpanded, card.title)")
        )
        // Заголовков со сворачиванием четыре: thinking / tool / todo / группа инструментов.
        // Группа — тоже раскрываемый заголовок, поэтому обязана идти через тот же хелпер;
        // считаем вызовы, чтобы новый сворачиваемый заголовок не проскочил без подписи.
        val calls = Regex("applyExpandableHeaderA11y\\(").findAll(chat).count()
        assertEquals(
            "Хелпер обязан вызываться в 4 заголовках (+1 объявление)",
            5,
            calls
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
}
