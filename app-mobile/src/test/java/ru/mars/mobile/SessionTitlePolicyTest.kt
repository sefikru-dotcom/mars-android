package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionTitleUpdate

/**
 * Живой заголовок сессии (O13): кадр `title` приходит посреди хода и адресован
 * конкретной сессии. Политика решает, кому он достаётся, и не даёт опоздавшему
 * кадру переименовать чужой открытый диалог.
 */
class SessionTitlePolicyTest {

    @Test
    fun `frame for the open session renames it`() {
        val decision = SessionTitlePolicy.decide(
            update = SessionTitleUpdate("s-1", "Отладка сети"),
            boundSessionId = "s-1",
            currentSessionId = "s-1",
            currentTitle = "Новый чат",
        )
        assertEquals(SessionTitleDecision("s-1", "Отладка сети"), decision)
    }

    @Test
    fun `blank title is not a title`() {
        assertNull(
            SessionTitlePolicy.decide(SessionTitleUpdate("s-1", "   "), "s-1", "s-1", null)
        )
    }

    @Test
    fun `frame without session id falls back to the stream binding`() {
        val decision = SessionTitlePolicy.decide(
            update = SessionTitleUpdate(null, "План релиза"),
            boundSessionId = "s-2",
            currentSessionId = "s-2",
            currentTitle = null,
        )
        assertEquals(SessionTitleDecision("s-2", "План релиза"), decision)
    }

    @Test
    fun `frame for another session never touches the open one`() {
        assertNull(
            SessionTitlePolicy.decide(SessionTitleUpdate("s-other", "Чужой"), "s-1", "s-1", null)
        )
        // Поток уже перепривязан к другому диалогу — кадр прежнего хода тоже мимо.
        assertNull(
            SessionTitlePolicy.decide(SessionTitleUpdate("s-1", "Старый"), "s-2", "s-2", null)
        )
    }

    @Test
    fun `repeated title does not repaint the header`() {
        assertNull(
            SessionTitlePolicy.decide(SessionTitleUpdate("s-1", "Отладка"), "s-1", "s-1", "Отладка")
        )
        // Пробелы по краям не делают заголовок новым.
        assertNull(
            SessionTitlePolicy.decide(SessionTitleUpdate("s-1", " Отладка "), "s-1", "s-1", "Отладка")
        )
    }

    @Test
    fun `renamed rewrites only the matching row`() {
        val rows = listOf(
            SessionItem(id = "s-1", title = "Старый"),
            SessionItem(id = "s-2", title = "Второй"),
        )
        val renamed = SessionTitlePolicy.renamed(rows, "s-1", "Новый")
        assertEquals("Новый", renamed[0].title)
        assertEquals("Второй", renamed[1].title)
        // Нет такой строки — список возвращается тем же объектом (нет лишней работы).
        assertSame(rows, SessionTitlePolicy.renamed(rows, "s-404", "Новый"))
        // Тот же текст — тоже без копии.
        assertSame(rows, SessionTitlePolicy.renamed(rows, "s-1", "Старый"))
    }
}
