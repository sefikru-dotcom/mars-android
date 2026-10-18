package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ContextStatusUpdate

/**
 * Бейдж статуса префилл-контекста (`context_status` side-канала) в ТВ-чате:
 * что показываем, что честно не показываем и как кадр чужой сессии не протекает
 * в текущий диалог.
 */
class TvContextStatusPresenterTest {

    private fun update(
        status: String,
        sessionId: String? = "s1",
        label: String? = null,
        messageCount: Int = 0,
        compacted: Boolean = false,
    ) = ContextStatusUpdate(
        sessionId = sessionId,
        status = status,
        label = label,
        messageCount = messageCount,
        compacted = compacted,
    )

    @Test
    fun `loaded frame becomes a readable badge with the default WebUI label`() {
        val badge = TvContextStatusPresenter.badge(update("loaded"), "s1")
        assertEquals("Контекст загружен · session recall", badge?.text)
        assertEquals(TvContextTone.LOADED, badge?.tone)
    }

    @Test
    fun `explicit label wins over the default`() {
        val badge = TvContextStatusPresenter.badge(update("loaded", label = "  past sessions  "), "s1")
        assertEquals("Контекст загружен · past sessions", badge?.text)
    }

    @Test
    fun `message count and compaction are appended as separate segments`() {
        val badge = TvContextStatusPresenter.badge(
            update("loaded", label = "recall", messageCount = 12, compacted = true),
            "s1",
        )
        assertEquals("Контекст загружен · recall · 12 сообщ. · сжат", badge?.text)
        assertEquals(TvContextTone.COMPACTED, badge?.tone)
    }

    @Test
    fun `error frame is red and says the answer goes without history`() {
        val badge = TvContextStatusPresenter.badge(update("error", label = "recall"), "s1")
        assertEquals("Контекст недоступен · recall", badge?.text)
        assertEquals(TvContextTone.ERROR, badge?.tone)
    }

    @Test
    fun `not_configured and unknown statuses render nothing`() {
        assertNull(TvContextStatusPresenter.badge(update("not_configured"), "s1"))
        assertNull(TvContextStatusPresenter.badge(update("warming"), "s1"))
    }

    @Test
    fun `a frame of another session never paints the current dialog`() {
        assertNull(TvContextStatusPresenter.badge(update("loaded", sessionId = "other"), "s1"))
    }

    @Test
    fun `a frame without session id counts as the current session`() {
        val badge = TvContextStatusPresenter.badge(update("loaded", sessionId = null), "s1")
        assertEquals("Контекст загружен · session recall", badge?.text)
    }

    @Test
    fun `an overlong label is clipped so the badge cannot span the screen`() {
        val long = "a".repeat(80)
        val text = TvContextStatusPresenter.badge(update("loaded", label = long), "s1")?.text.orEmpty()
        assertTrue(text, text.length <= 60)
        assertTrue(text, text.endsWith("…"))
    }

    @Test
    fun `badge lifetime is long enough to read from the sofa`() {
        assertTrue(TvContextStatusPresenter.DISPLAY_DURATION_MS >= 5_000L)
    }
}
