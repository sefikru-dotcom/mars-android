package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт лестницы Back двухуровневого навигатора:
 * чат → диалоги → разделы (левый бар) → системный выход.
 */
class NavBackPolicyTest {

    private fun chat(searchOpen: Boolean = false, drawerOpen: Boolean = false) =
        NavBackPolicy.resolve(NavBackPolicy.Level.CHAT, searchOpen, drawerOpen)

    private fun list(searchOpen: Boolean = false, drawerOpen: Boolean = false) =
        NavBackPolicy.resolve(NavBackPolicy.Level.SESSION_LIST, searchOpen, drawerOpen)

    @Test
    fun `chat closes transcript search first`() {
        assertEquals(NavBackPolicy.Action.CLOSE_SEARCH, chat(searchOpen = true))
        assertEquals(NavBackPolicy.Action.CLOSE_SEARCH, chat(searchOpen = true, drawerOpen = true))
    }

    @Test
    fun `chat closes sections drawer before leaving dialog`() {
        // Шторка — оверлей над диалогом: back закрывает её, уровень диалога сохраняется.
        assertEquals(NavBackPolicy.Action.CLOSE_DRAWER, chat(drawerOpen = true))
    }

    @Test
    fun `chat back goes to session list`() {
        assertEquals(NavBackPolicy.Action.GO_TO_SESSION_LIST, chat())
        // Связка с AppNavPolicy: back из диалога — всегда корень CHAT (список диалогов).
        val back = AppNavPolicy.back(AppNavState(AppDestination.CHAT, ChatLevel.Session("a")))
        assertEquals(AppNavState(AppDestination.CHAT), back)
    }

    @Test
    fun `session list back shows sections drawer`() {
        assertEquals(NavBackPolicy.Action.OPEN_DRAWER, list())
    }

    @Test
    fun `sections drawer on root level exits the app`() {
        assertEquals(NavBackPolicy.Action.SYSTEM_EXIT, list(drawerOpen = true))
        // Связка с AppNavPolicy: из корня раздела back отсутствует — выход решает UI-слой.
        assertNull(AppNavPolicy.back(AppNavState.INITIAL))
    }

    @Test
    fun `session list closes search before drawer`() {
        assertEquals(NavBackPolicy.Action.CLOSE_SEARCH, list(searchOpen = true))
        assertEquals(NavBackPolicy.Action.CLOSE_SEARCH, list(searchOpen = true, drawerOpen = true))
    }

    @Test
    fun `full ladder is chat to dialogs to sections to system exit`() {
        // Три последовательных Back от уровня диалога до выхода из приложения.
        val actions = listOf(
            chat(),                 // чат → диалоги
            list(),                 // диалоги → разделы (шторка)
            list(drawerOpen = true) // разделы → системный выход
        )
        assertEquals(NavBackPolicy.backLadder(), actions)
        assertEquals(
            listOf(
                NavBackPolicy.Action.GO_TO_SESSION_LIST,
                NavBackPolicy.Action.OPEN_DRAWER,
                NavBackPolicy.Action.SYSTEM_EXIT,
            ),
            actions,
        )
    }
}
