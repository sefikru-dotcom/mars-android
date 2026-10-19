package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.SessionAttention

/**
 * Состояние серверного хода, которое нельзя переподключить (разрыв L: при
 * `canReattach == false` экран молча терял ход — на ТВ это тупик без подсказки).
 */
class TvTurnStatePolicyTest {

    private fun attention(kind: String, count: Int = 1, severity: String? = null) =
        SessionAttention(kind = kind, count = count, severity = severity)

    @Test
    fun `an idle session shows nothing`() {
        assertNull(TvTurnStatePolicy.notice(null))
        assertNull(TvTurnStatePolicy.notice(ActiveTurnInfo()))
    }

    @Test
    fun `a reattachable turn shows no banner - the status strip already covers it`() {
        val turn = ActiveTurnInfo(
            activeStreamId = "st-1",
            isStreaming = true,
            attention = attention("approval"),
        )
        assertNull(TvTurnStatePolicy.notice(turn))
    }

    @Test
    fun `a pending approval that cannot be reattached is surfaced loudly`() {
        val notice = TvTurnStatePolicy.notice(ActiveTurnInfo(attention = attention("approval")))
        assertNotNull(notice)
        assertEquals(TvTurnStatePolicy.Tone.ATTENTION, notice!!.tone)
        assertTrue(notice.text.contains("Требуется подтверждение"))
        assertTrue("баннер — кнопка, текст говорит, что будет", notice.text.contains(TvTurnStatePolicy.REFRESH_HINT))
    }

    @Test
    fun `a pending clarify is surfaced as a question`() {
        val notice = TvTurnStatePolicy.notice(ActiveTurnInfo(attention = attention("clarify")))!!
        assertEquals(TvTurnStatePolicy.Tone.ATTENTION, notice.tone)
        assertTrue(notice.text.contains("ждёт ответа на вопрос"))
    }

    @Test
    fun `critical attention keeps the severity word`() {
        val notice = TvTurnStatePolicy.notice(
            ActiveTurnInfo(attention = attention("approval", severity = "critical")),
        )!!
        assertTrue(notice.text.startsWith(TvTurnStatePolicy.CRITICAL_PREFIX))
    }

    @Test
    fun `several decisions are counted`() {
        val notice = TvTurnStatePolicy.notice(
            ActiveTurnInfo(attention = attention("approval", count = 3)),
        )!!
        assertTrue(notice.text.contains("Решений: 3"))
    }

    @Test
    fun `an unknown attention kind is quoted, not invented`() {
        val notice = TvTurnStatePolicy.notice(ActiveTurnInfo(attention = attention("escalation")))!!
        assertTrue(notice.text.contains("escalation"))
    }

    @Test
    fun `a live turn without a stream id is reported as unreachable`() {
        val notice = TvTurnStatePolicy.notice(ActiveTurnInfo(isStreaming = true))!!
        assertEquals(TvTurnStatePolicy.Tone.LIVE, notice.tone)
        assertTrue(notice.text.contains(TvTurnStatePolicy.LIVE_TEXT))
    }

    @Test
    fun `a queued message is reported as not started`() {
        val notice = TvTurnStatePolicy.notice(ActiveTurnInfo(hasPendingUserMessage = true))!!
        assertEquals(TvTurnStatePolicy.Tone.QUEUED, notice.tone)
        assertTrue(notice.text.contains(TvTurnStatePolicy.QUEUED_TEXT))
    }

    @Test
    fun `attention outranks streaming and queued`() {
        val notice = TvTurnStatePolicy.notice(
            ActiveTurnInfo(isStreaming = true, hasPendingUserMessage = true, attention = attention("approval")),
        )!!
        assertEquals(TvTurnStatePolicy.Tone.ATTENTION, notice.tone)
    }

    @Test
    fun `watch backoff grows, stays capped and then stops`() {
        val first = TvTurnStatePolicy.watchDelayMs(1)!!
        val second = TvTurnStatePolicy.watchDelayMs(2)!!
        val last = TvTurnStatePolicy.watchDelayMs(TvTurnStatePolicy.MAX_WATCH_ATTEMPTS)!!
        assertTrue(first < second)
        assertTrue(last <= 30_000L)
        assertNull(TvTurnStatePolicy.watchDelayMs(TvTurnStatePolicy.MAX_WATCH_ATTEMPTS + 1))
        assertNull(TvTurnStatePolicy.watchDelayMs(0))
    }
}
