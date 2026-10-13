package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDeepLinkTest {

    @Test
    fun `parses every section root with scheme`() {
        AppDestination.entries.forEach { destination ->
            val link = AppDeepLink.parse("mars://${destination.key}")
            assertEquals(
                "mars://${destination.key} не распознан",
                AppDeepLink.Section(destination),
                link
            )
        }
    }

    @Test
    fun `parses section root without scheme`() {
        assertEquals(
            AppDeepLink.Section(AppDestination.TASKS),
            AppDeepLink.parse("/tasks")
        )
        assertEquals(
            AppDeepLink.Section(AppDestination.KANBAN),
            AppDeepLink.parse("kanban")
        )
    }

    @Test
    fun `parses chat session deep link`() {
        val link = AppDeepLink.parse("mars://chat/session/abc-123")
        assertEquals(AppDeepLink.ChatSession("abc-123"), link)
    }

    @Test
    fun `query and fragment are ignored`() {
        assertEquals(
            AppDeepLink.ChatSession("42"),
            AppDeepLink.parse("mars://chat/session/42?source=notification#top")
        )
        assertEquals(
            AppDeepLink.Section(AppDestination.MUSIC),
            AppDeepLink.parse("mars://music?tab=queue")
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(
            AppDeepLink.Section(AppDestination.CHAT),
            AppDeepLink.parse("  mars://chat  ")
        )
    }

    @Test
    fun `unknown and malformed links return null`() {
        listOf(
            "",
            "   ",
            "mars://",
            "mars://unknown",
            "mars://chat/sessions",
            "mars://chat/session",
            "mars://chat/session/",
            "mars://chat/session/a/b",
            "mars://tasks/session/1",
            "chat/session/x/y",
            "mars://settings/extra/segments/here",
        ).forEach { raw ->
            assertNull("ссылка должна отклоняться: $raw", AppDeepLink.parse(raw))
        }
    }

    @Test
    fun `session links only resolve inside chat`() {
        // Единственная форма с сессией — mars://chat/session/<id>.
        val parsed = AppDeepLink.parse("mars://music/session/1")
        assertNull(parsed)
    }

    @Test
    fun `toNavState maps section to root and session to chat level`() {
        assertEquals(
            AppNavState(AppDestination.KANBAN),
            AppDeepLink.Section(AppDestination.KANBAN).toNavState()
        )
        assertEquals(
            AppNavState(AppDestination.CHAT, ChatLevel.Session("s9")),
            AppDeepLink.ChatSession("s9").toNavState()
        )
    }

    @Test
    fun `every link resolves from initial state in at most two transitions`() {
        val initial = AppNavState.INITIAL
        val samples = AppDestination.entries.map { "mars://${it.key}" } +
            listOf("mars://chat/session/deep-link-id", "/chat/session/x")
        samples.forEach { raw ->
            val link = AppDeepLink.parse(raw)
            assertTrue("не распарсился валидный линк $raw", link != null)
            val transitions = AppNavPolicy.transitionsTo(initial, link!!.toNavState())
            assertTrue(
                "линк $raw требует ${transitions.size} переходов (лимит 2)",
                transitions.size <= AppNavPolicy.MAX_TRANSITIONS
            )
            assertEquals(
                link.toNavState(),
                AppNavPolicy.apply(initial, transitions)
            )
        }
    }

    @Test
    fun `chat root link needs zero transitions from initial state`() {
        val link = AppDeepLink.parse("mars://chat")!!
        assertEquals(
            emptyList<NavTransition>(),
            AppNavPolicy.transitionsTo(AppNavState.INITIAL, link.toNavState())
        )
    }
}
