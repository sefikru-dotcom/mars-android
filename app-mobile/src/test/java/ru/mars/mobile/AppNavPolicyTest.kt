package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppNavPolicyTest {

    private val allSections = AppDestination.entries

    /** Все валидные состояния: корень каждого раздела + два диалога внутри CHAT. */
    private val allStates: List<AppNavState> = buildList {
        allSections.forEach { add(AppNavState(it)) }
        add(AppNavState(AppDestination.CHAT, ChatLevel.Session("a")))
        add(AppNavState(AppDestination.CHAT, ChatLevel.Session("b")))
    }

    @Test
    fun `initial state is chat with session list`() {
        assertEquals(AppDestination.CHAT, AppNavState.INITIAL.section)
        assertEquals(ChatLevel.SessionList, AppNavState.INITIAL.chatLevel)
    }

    @Test
    fun `state invariant forbids chat level outside chat`() {
        try {
            AppNavState(AppDestination.MUSIC, ChatLevel.Session("x"))
            fail("ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // контракт
        }
    }

    @Test
    fun `session requires non-blank id`() {
        try {
            ChatLevel.Session(" ")
            fail("ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // контракт
        }
    }

    @Test
    fun `selectSection moves to section root and is idempotent`() {
        val fromSession = AppNavState(AppDestination.CHAT, ChatLevel.Session("a"))
        val kanban = AppNavPolicy.selectSection(fromSession, AppDestination.KANBAN)
        assertEquals(AppNavState(AppDestination.KANBAN), kanban)

        val again = AppNavPolicy.selectSection(kanban, AppDestination.KANBAN)
        assertEquals(kanban, again)

        // Клик по CHAT из открытого диалога возвращает список диалогов.
        val chatRoot = AppNavPolicy.selectSection(fromSession, AppDestination.CHAT)
        assertEquals(ChatLevel.SessionList, chatRoot.chatLevel)
    }

    @Test
    fun `openSession works only inside chat`() {
        allSections.filter { it != AppDestination.CHAT }.forEach { section ->
            val state = AppNavState(section)
            assertEquals(
                "вне CHAT открытие диалога должно быть no-op ($section)",
                state,
                AppNavPolicy.openSession(state, "s1")
            )
        }
    }

    @Test
    fun `openSession rejects blank id`() {
        val chat = AppNavState(AppDestination.CHAT)
        try {
            AppNavPolicy.openSession(chat, " ")
            fail("ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // контракт
        }
    }

    @Test
    fun `openSession replaces current session without growing depth`() {
        val list = AppNavState(AppDestination.CHAT)
        val first = AppNavPolicy.openSession(list, "s1")
        assertEquals(ChatLevel.Session("s1"), first.chatLevel)

        // Повторное открытие того же диалога идемпотентно (дубли входов сокращаются).
        assertEquals(first, AppNavPolicy.openSession(first, "s1"))

        // Другой диалог заменяет текущий, глубина не растёт.
        val second = AppNavPolicy.openSession(first, "s2")
        assertEquals(AppNavState(AppDestination.CHAT, ChatLevel.Session("s2")), second)
    }

    @Test
    fun `child sessions expand on parent card without new nav level`() {
        val parent = AppNavState(AppDestination.CHAT, ChatLevel.Session("p1"))

        val expanded = AppNavPolicy.toggleChildSession(parent, "c1")
        assertEquals("p1", (expanded.chatLevel as ChatLevel.Session).sessionId)
        assertEquals(setOf("c1"), (expanded.chatLevel as ChatLevel.Session).expandedChildIds)

        val expandedTwice = AppNavPolicy.toggleChildSession(expanded, "c2")
        assertEquals(setOf("c1", "c2"), (expandedTwice.chatLevel as ChatLevel.Session).expandedChildIds)

        val collapsed = AppNavPolicy.toggleChildSession(expandedTwice, "c1")
        assertEquals(setOf("c2"), (collapsed.chatLevel as ChatLevel.Session).expandedChildIds)

        // Раскрытие ребёнка не порождает новый уровень навигации.
        assertEquals(parent.section, expanded.section)
        assertTrue(expanded.chatLevel is ChatLevel.Session)
    }

    @Test
    fun `toggleChildSession is no-op outside open session`() {
        val list = AppNavState(AppDestination.CHAT)
        assertEquals(list, AppNavPolicy.toggleChildSession(list, "c1"))
        val music = AppNavState(AppDestination.MUSIC)
        assertEquals(music, AppNavPolicy.toggleChildSession(music, "c1"))
    }

    @Test
    fun `toggleChildSession rejects blank id`() {
        val parent = AppNavState(AppDestination.CHAT, ChatLevel.Session("p1"))
        try {
            AppNavPolicy.toggleChildSession(parent, "")
            fail("ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // контракт
        }
    }

    @Test
    fun `back from session returns to session list`() {
        val session = AppNavState(AppDestination.CHAT, ChatLevel.Session("s1"))
        assertEquals(AppNavState(AppDestination.CHAT), AppNavPolicy.back(session))
    }

    @Test
    fun `back from any root level is null`() {
        allSections.forEach { section ->
            assertNull(
                "back на корне $section должен быть null",
                AppNavPolicy.back(AppNavState(section))
            )
        }
    }

    @Test
    fun `any state reaches any target in at most two transitions`() {
        allStates.forEach { current ->
            allStates.forEach { target ->
                val transitions = AppNavPolicy.transitionsTo(current, target)
                assertTrue(
                    "из $current в $target нужно ${transitions.size} переходов (лимит 2)",
                    transitions.size <= AppNavPolicy.MAX_TRANSITIONS
                )
                assertEquals(
                    "применение переходов из $current не приводит к $target",
                    target,
                    AppNavPolicy.apply(current, transitions)
                )
            }
        }
    }

    @Test
    fun `transitions to current state are empty`() {
        allStates.forEach { state ->
            assertEquals(emptyList<NavTransition>(), AppNavPolicy.transitionsTo(state, state))
        }
    }

    @Test
    fun `worst case deep target needs exactly two transitions`() {
        val from = AppNavState(AppDestination.MUSIC)
        val to = AppNavState(AppDestination.CHAT, ChatLevel.Session("deep"))
        val transitions = AppNavPolicy.transitionsTo(from, to)
        assertEquals(2, transitions.size)
        assertEquals(NavTransition.SelectSection(AppDestination.CHAT), transitions[0])
        assertEquals(NavTransition.OpenSession("deep"), transitions[1])
    }

    @Test
    fun `expansion state does not add transitions`() {
        val plain = AppNavState(AppDestination.CHAT, ChatLevel.Session("s1"))
        val expanded = AppNavPolicy.toggleChildSession(plain, "c1")
        assertEquals(
            emptyList<NavTransition>(),
            AppNavPolicy.transitionsTo(expanded, plain)
        )
    }

    @Test
    fun `logout host is settings only`() {
        assertEquals(AppDestination.SETTINGS, AppNavPolicy.LOGOUT_HOST)
        allSections.forEach { section ->
            assertEquals(
                section == AppDestination.SETTINGS,
                AppNavPolicy.isLogoutAvailable(section)
            )
        }
    }

    @Test
    fun `max transitions contract equals two`() {
        assertEquals(2, AppNavPolicy.MAX_TRANSITIONS)
    }
}
