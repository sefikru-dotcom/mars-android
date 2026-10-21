package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Политика голоса: естественный Svetlana — основной, piper irina — только резерв.
 *
 * Требование Стефа: «естественный голос Svetlana — явный voice profile, irina fallback если
 * installed, не навязывать irina вместо желанного веб-голоса». Отсюда два главных теста:
 * порядок кандидатов и то, что `429`/`401` НЕ понижают профиль (иначе первая же очередь фраз
 * навсегда переключила бы диалог на локальный голос).
 */
class SpeechVoiceProfileSelectorTest {

    private var now = 1_000_000L

    private fun selector(
        profiles: List<ServerTtsVoiceProfile> = ServerTtsVoices.VOICE_DIALOG,
        localInstalled: Boolean = true,
    ) = SpeechVoiceProfileSelector(
        profiles = profiles,
        nowMs = { now },
        transientCooldownMs = 45_000L,
        permanentCooldownMs = 600_000L,
        localVoiceInstalled = localInstalled,
    )

    @Test
    fun `natural svetlana is chosen first`() {
        val current = selector().current()!!

        assertEquals("natural-svetlana", current.id)
        assertEquals("edge", current.engine)
        assertEquals("ru-RU-SvetlanaNeural", current.voice)
        assertEquals("+10%", current.rate)
    }

    @Test
    fun `irina is the second candidate and is not forced over svetlana`() {
        val candidates = selector().candidates()

        assertEquals(listOf("natural-svetlana", "local-irina"), candidates.map { it.id })
        assertEquals("piper", candidates[1].engine)
        assertEquals("ru_RU-irina-medium", candidates[1].voice)
    }

    @Test
    fun `not installed local voice is never offered`() {
        val selector = selector(localInstalled = false)

        assertEquals(listOf("natural-svetlana"), selector.candidates().map { it.id })

        selector.setLocalVoiceInstalled(true)
        assertEquals(listOf("natural-svetlana", "local-irina"), selector.candidates().map { it.id })
    }

    @Test
    fun `rate limit does not demote the natural voice`() {
        val selector = selector()
        val natural = ServerTtsVoices.NATURAL_SVETLANA

        val demoted = selector.onUnavailable(
            natural,
            ServerTtsResult.Unavailable("rate_limited", 429, retryAfterMs = 2_000L),
        )

        assertFalse("429 — лимитер, а не отсутствие голоса", demoted)
        assertEquals("natural-svetlana", selector.current()!!.id)
        assertEquals(1, selector.failureCount(natural))
    }

    @Test
    fun `unauthorized does not demote either`() {
        val selector = selector()

        val demoted = selector.onUnavailable(
            ServerTtsVoices.NATURAL_SVETLANA,
            ServerTtsResult.Unavailable("unauthorized", 401),
        )

        assertFalse("токен не связан с голосом", demoted)
        assertEquals("natural-svetlana", selector.current()!!.id)
    }

    @Test
    fun `missing voice on the server demotes for a long time then returns`() {
        val selector = selector()
        val natural = ServerTtsVoices.NATURAL_SVETLANA

        val demoted = selector.onUnavailable(natural, ServerTtsResult.Unavailable("http_400", 400))

        assertTrue(demoted)
        assertEquals("local-irina", selector.current()!!.id)

        // Ровно до истечения долгого кулдауна irina остаётся, после — снова Svetlana.
        now += 599_999L
        assertEquals("local-irina", selector.current()!!.id)
        now += 2L
        assertEquals("natural-svetlana", selector.current()!!.id)
    }

    @Test
    fun `network failure demotes briefly so the natural voice comes back`() {
        val selector = selector()

        selector.onUnavailable(ServerTtsVoices.NATURAL_SVETLANA, ServerTtsResult.Unavailable("transport"))
        assertEquals("local-irina", selector.current()!!.id)

        now += SpeechVoiceProfileSelector.DEFAULT_TRANSIENT_COOLDOWN_MS + 1
        assertEquals("natural-svetlana", selector.current()!!.id)
    }

    @Test
    fun `success clears the demotion and the failure counter`() {
        val selector = selector()
        val natural = ServerTtsVoices.NATURAL_SVETLANA
        selector.onUnavailable(natural, ServerTtsResult.Unavailable("transport"))
        assertEquals(1, selector.failureCount(natural))

        selector.onAudio(natural)

        assertEquals(0, selector.failureCount(natural))
        assertEquals("natural-svetlana", selector.current()!!.id)
    }

    @Test
    fun `both profiles unavailable means no candidate - caller falls back to system tts`() {
        val selector = selector()
        selector.onUnavailable(ServerTtsVoices.NATURAL_SVETLANA, ServerTtsResult.Unavailable("http_404", 404))
        selector.onUnavailable(ServerTtsVoices.LOCAL_IRINA, ServerTtsResult.Unavailable("http_404", 404))

        assertTrue(selector.candidates().isEmpty())
        assertNull("третий голос не выдумывается", selector.current())
    }

    @Test
    fun `reset restores the natural voice`() {
        val selector = selector()
        selector.onUnavailable(ServerTtsVoices.NATURAL_SVETLANA, ServerTtsResult.Unavailable("http_404", 404))
        assertEquals("local-irina", selector.current()!!.id)

        selector.reset()

        assertEquals("natural-svetlana", selector.current()!!.id)
    }
}
