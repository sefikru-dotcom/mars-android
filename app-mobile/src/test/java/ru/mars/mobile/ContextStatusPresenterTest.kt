package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ContextStatusUpdate

/**
 * Строка статуса префилл-контекста: WebUI-паритет (static/messages.js, listener
 * `context_status`) — `loaded`/`error` показываются, остальные статусы нет;
 * кадр всегда сверяется с активной сессией.
 */
class ContextStatusPresenterTest {

    private fun update(
        sessionId: String? = "s1",
        status: String = "loaded",
        label: String? = "session recall",
        error: String? = null,
    ) = ContextStatusUpdate(sessionId = sessionId, status = status, label = label, error = error)

    @Test fun `loaded prefill shows the context loaded line`() {
        assertEquals("Контекст загружен: session recall", ContextStatusPresenter.statusLine(update(), "s1"))
    }

    @Test fun `error prefill shows the context unavailable line`() {
        assertEquals(
            "Контекст недоступен: session recall",
            ContextStatusPresenter.statusLine(update(status = "error", error = "script failed"), "s1")
        )
    }

    @Test fun `custom label is preserved verbatim`() {
        assertEquals(
            "Контекст загружен: память профиля",
            ContextStatusPresenter.statusLine(update(label = "память профиля"), "s1")
        )
    }

    @Test fun `blank label falls back to the WebUI default`() {
        assertEquals("Контекст загружен: session recall", ContextStatusPresenter.statusLine(update(label = "   "), "s1"))
        assertEquals("Контекст загружен: session recall", ContextStatusPresenter.statusLine(update(label = null), "s1"))
    }

    @Test fun `not_configured and unknown statuses show nothing`() {
        assertNull(ContextStatusPresenter.statusLine(update(status = "not_configured"), "s1"))
        assertNull(ContextStatusPresenter.statusLine(update(status = "weird"), "s1"))
    }

    @Test fun `foreign session frame never leaks into the active chat`() {
        assertNull(ContextStatusPresenter.statusLine(update(sessionId = "s2"), "s1"))
        assertFalse(ContextStatusPresenter.isForActiveSession(update(sessionId = "s2"), "s1"))
    }

    @Test fun `frame without session_id matches the WebUI fallback`() {
        // WebUI: `(d.session_id || activeSid) !== activeSid` — кадр без session_id считается своим.
        assertTrue(ContextStatusPresenter.isForActiveSession(update(sessionId = null), "s1"))
        assertEquals("Контекст загружен: session recall", ContextStatusPresenter.statusLine(update(sessionId = null), "s1"))
    }

    @Test fun `unbound new chat ignores frames of a concrete session`() {
        // Новый чат ещё не привязан (activeSessionId == null): кадр конкретной сессии — чужой.
        assertNull(ContextStatusPresenter.statusLine(update(sessionId = "s1"), null))
    }
}
