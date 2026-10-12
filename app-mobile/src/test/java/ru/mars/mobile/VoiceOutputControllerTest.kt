package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.VoiceOutputController.SpeakOutcome
import ru.mars.mobile.domain.util.SpeechContentFilter

/**
 * Unit tests for [VoiceOutputController] — the voice-OUTPUT policy (which completed answer
 * is spoken, when speech stops, how engine failures degrade). The Android synthesizer is
 * replaced by [FakeEngine]; no robolectric, no sleeps, no flake.
 */
class VoiceOutputControllerTest {

    private class FakeEngine(var acceptSpeak: Boolean = true) : VoiceOutputController.SpeechEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0
        override fun speak(text: String): Boolean {
            if (!acceptSpeak) return false
            spoken += text
            return true
        }
        override fun stop() { stopCount++ }
        override fun shutdown() { shutdownCount++ }
    }

    private val engine = FakeEngine()
    private val persisted = mutableListOf<Boolean>()
    private val controller = VoiceOutputController(
        engine = engine,
        onAutoSpeakChanged = { persisted += it },
    )

    // --- Auto mode off by default: completed answers stay silent ------------------------------

    @Test
    fun `completed answer is NOT spoken while auto mode is off and nothing armed`() {
        controller.onTurnComplete("Готовый ответ")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `intermediate SSE chunks never reach the engine - only turn completion speaks`() {
        controller.setAutoSpeak(true)
        // The controller deliberately has no chunk API; the host feeds ONLY the final text
        // at turn completion. Streamed fragments passed as "not complete" produce nothing.
        controller.onTurnComplete(null)
        controller.onTurnComplete("")
        controller.onTurnComplete("   ")
        assertTrue(engine.spoken.isEmpty())
        controller.onTurnComplete("Финальный ответ")
        assertEquals(listOf("Финальный ответ"), engine.spoken)
    }

    // --- Sticky auto toggle --------------------------------------------------------------------

    @Test
    fun `auto toggle speaks every completed answer and is persisted via callback`() {
        controller.setAutoSpeak(true)
        assertEquals(listOf(true), persisted)
        controller.onTurnComplete("Первый")
        controller.onTurnComplete("Второй")
        assertEquals(listOf("Первый", "Второй"), engine.spoken)
        controller.setAutoSpeak(false)
        assertEquals(listOf(true, false), persisted)
        controller.onTurnComplete("Третий")
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun `disabling auto mode stops speech in progress`() {
        controller.setAutoSpeak(true)
        controller.onTurnComplete("Длинный ответ")
        val stopsBefore = engine.stopCount
        controller.setAutoSpeak(false)
        assertEquals(stopsBefore + 1, engine.stopCount)
    }

    // --- One-shot arm from explicit voice input -------------------------------------------------

    @Test
    fun `voice-input arm speaks exactly the next completed answer`() {
        controller.armSpeakNextAnswer()
        controller.onTurnComplete("Ответ на голосовой вопрос")
        controller.onTurnComplete("Следующий ход")
        assertEquals(listOf("Ответ на голосовой вопрос"), engine.spoken)
    }

    @Test
    fun `arm does not leak across a session switch`() {
        controller.armSpeakNextAnswer()
        controller.onSessionSwitch()
        controller.onTurnComplete("Ответ уже другого диалога")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `arm is dropped when the engine turns out unavailable`() {
        controller.onEngineReady(false)
        controller.armSpeakNextAnswer()
        controller.onTurnComplete("Ответ")
        assertTrue(engine.spoken.isEmpty())
    }

    // --- Markdown / service-block filtering ------------------------------------------------------

    @Test
    fun `spoken text is filtered - code fences and markdown are not read verbatim`() {
        controller.setAutoSpeak(true)
        controller.onTurnComplete(
            "## Заголовок\nСмотри код:\n```kotlin\nfun main() {}\n```\nСсылка: https://marscore.ru/x"
        )
        val spoken = engine.spoken.single()
        assertTrue(spoken.contains(SpeechContentFilter.CODE_PLACEHOLDER))
        assertFalse(spoken.contains("```"))
        assertFalse(spoken.contains("fun main"))
        assertFalse(spoken.contains("https://"))
        assertTrue(spoken.contains("Смотри код"))
    }

    @Test
    fun `code-only answer degrades to the dedicated spoken sentence`() {
        controller.setAutoSpeak(true)
        controller.onTurnComplete("```\nint x = 1;\n```")
        assertEquals(listOf(SpeechContentFilter.CODE_ONLY_REPLY), engine.spoken)
    }

    // --- Lifecycle stops --------------------------------------------------------------------------

    @Test
    fun `new turn, session switch, pause and destroy all stop the engine`() {
        val baseline = engine.stopCount
        controller.onNewTurn()
        controller.onSessionSwitch()
        controller.onPause()
        assertEquals(baseline + 3, engine.stopCount)
        controller.onDestroy()
        assertEquals(baseline + 4, engine.stopCount)
        assertEquals(1, engine.shutdownCount)
    }

    @Test
    fun `paused host does not speak completed answers in background`() {
        controller.setAutoSpeak(true)
        controller.onPause()
        controller.onTurnComplete("Ответ, пришедший в фоне")
        assertTrue(engine.spoken.isEmpty())
        controller.onResume()
        controller.onTurnComplete("Ответ в foreground")
        assertEquals(listOf("Ответ в foreground"), engine.spoken)
    }

    @Test
    fun `arm survives a background completion and fires on the next foreground one`() {
        controller.armSpeakNextAnswer()
        controller.onPause()
        controller.onTurnComplete("Фоновый ответ")
        assertTrue(engine.spoken.isEmpty())
        controller.onResume()
        controller.onTurnComplete("Следующий ответ")
        assertEquals(listOf("Следующий ответ"), engine.spoken)
    }

    // --- Engine absence / failure ------------------------------------------------------------------

    @Test
    fun `engine init failure disables auto and manual speech without throwing`() {
        controller.setAutoSpeak(true)
        controller.onEngineReady(false)
        assertFalse(controller.engineAvailable)
        controller.onTurnComplete("Ответ")
        assertTrue(engine.spoken.isEmpty())
        assertEquals(SpeakOutcome.ENGINE_UNAVAILABLE, controller.speakNow("Текст"))
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `rejected speak marks the engine unavailable for later turns`() {
        controller.setAutoSpeak(true)
        engine.acceptSpeak = false
        assertEquals(SpeakOutcome.ENGINE_UNAVAILABLE, controller.speakNow("Текст"))
        assertFalse(controller.engineAvailable)
        engine.acceptSpeak = true
        controller.onTurnComplete("Поздний ответ")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `engine that throws is contained - output disabled, host unaffected`() {
        val throwing = object : VoiceOutputController.SpeechEngine {
            override fun speak(text: String): Boolean = throw IllegalStateException("binder died")
            override fun stop() = throw IllegalStateException("binder died")
            override fun shutdown() = throw IllegalStateException("binder died")
        }
        val host = VoiceOutputController(throwing)
        host.setAutoSpeak(true)
        host.onTurnComplete("Ответ")
        assertEquals(SpeakOutcome.ENGINE_UNAVAILABLE, host.speakNow("Ещё"))
        host.onNewTurn()
        host.onDestroy()
        assertFalse(host.engineAvailable)
    }

    // --- Manual speak -------------------------------------------------------------------------------

    @Test
    fun `manual speak works with auto mode off and reports blank text`() {
        assertEquals(SpeakOutcome.SPOKEN, controller.speakNow("Прочитай меня"))
        assertEquals(listOf("Прочитай меня"), engine.spoken)
        assertEquals(SpeakOutcome.NOTHING_TO_SPEAK, controller.speakNow("  "))
        assertEquals(SpeakOutcome.NOTHING_TO_SPEAK, controller.speakNow(null))
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun `manual speak flushes previous speech - single utterance semantics`() {
        controller.speakNow("Первое")
        controller.speakNow("Второе")
        assertEquals(listOf("Первое", "Второе"), engine.spoken)
    }
}
