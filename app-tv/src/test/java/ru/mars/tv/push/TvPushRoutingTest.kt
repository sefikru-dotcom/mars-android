package ru.mars.tv.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маршрутизация тапа по push-уведомлению на ТВ (разрыв L: «требуется подтверждение»
 * открывало экран без привязки к диалогу — TvChatActivity не читал интент вовсе).
 */
class TvPushRoutingTest {

    @Test
    fun `explicit extras win over the deep link`() {
        val route = TvPushRouting.resolve(
            extraSessionId = "s-extra",
            extraKind = "approval",
            dataUri = "mars://session/s-link?kind=clarify",
        )
        assertEquals("s-extra", route.sessionId)
        assertEquals("approval", route.kind)
    }

    @Test
    fun `approval push carries the dialog and the decision kind`() {
        val route = TvPushRouting.resolve("s-1", "approval", null)
        assertTrue(route.hasSession)
        assertTrue("approval обязан открывать диалог, а не общий экран", route.isDecision)
    }

    @Test
    fun `clarify push is a decision too`() {
        assertTrue(TvPushRouting.resolve("s-1", "clarify", null).isDecision)
    }

    @Test
    fun `a plain chat push routes the dialog but is not a decision`() {
        val route = TvPushRouting.resolve("s-1", "chat", null)
        assertEquals("s-1", route.sessionId)
        assertFalse(route.isDecision)
    }

    @Test
    fun `kind is normalized to lower case`() {
        assertEquals("approval", TvPushRouting.resolve("s", " APPROVAL ", null).kind)
    }

    @Test
    fun `blank extras never become a session id`() {
        val route = TvPushRouting.resolve("   ", "", null)
        assertNull(route.sessionId)
        assertNull(route.kind)
        assertFalse(route.hasSession)
    }

    @Test
    fun `notification without session routes nowhere - the screen keeps its dialog`() {
        assertEquals(TvPushRoute.NONE, TvPushRouting.resolve(null, null, null))
    }

    @Test
    fun `deep link with a query session id is understood`() {
        assertEquals("s-9", TvPushRouting.parseDeepLink("mars://chat?session_id=s-9&kind=approval").sessionId)
        assertEquals("approval", TvPushRouting.parseDeepLink("mars://chat?session_id=s-9&kind=approval").kind)
    }

    @Test
    fun `deep link path segment is the fallback session id`() {
        assertEquals("s-7", TvPushRouting.parseDeepLink("https://marscore.ru/chat/s-7").sessionId)
        assertEquals("s-8", TvPushRouting.parseDeepLink("mars://session/s-8").sessionId)
    }

    @Test
    fun `a broken url is not a crash and not a guess`() {
        assertEquals(TvPushRoute.NONE, TvPushRouting.parseDeepLink("::не url::"))
        assertEquals(TvPushRoute.NONE, TvPushRouting.parseDeepLink(""))
        assertEquals(TvPushRoute.NONE, TvPushRouting.parseDeepLink(null))
    }

    @Test
    fun `data payload keys are the same as the core push contract`() {
        val route = TvPushRouting.fromData(mapOf("session_id" to "s-3", "kind" to "Approval"))
        assertEquals("s-3", route.sessionId)
        assertEquals("approval", route.kind)
    }

    @Test
    fun `an approval push without a session is not routed to a random dialog`() {
        // Сервер мог прислать kind без session_id: экран НЕ должен открывать «последний»
        // диалог — маршрута нет, значит поведение прежнее.
        val route = TvPushRouting.resolve(null, "approval", null)
        assertFalse(route.hasSession)
        assertNull(route.sessionId)
    }
}
