package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.VoiceSessionController.State

/**
 * Unit tests for [VoiceSessionController] — the voice-input state machine (IDLE / LISTENING /
 * PROCESSING / ERROR). Pure JVM: a recording fake listener verifies both state transitions and
 * event fan-out, including rejection of events fired against the wrong state.
 */
class VoiceSessionControllerTest {

    private class RecordingListener : VoiceSessionController.Listener {
        val states = mutableListOf<VoiceSessionController.State>()
        val startedCount = mutableListOf<Int>()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onStateChanged(state: VoiceSessionController.State) { states += state }
        override fun onListeningStarted() { startedCount += 1 }
        override fun onResult(text: String) { results += text }
        override fun onError(message: String) { errors += message }
    }

    private val listener = RecordingListener()
    private val controller = VoiceSessionController(listener)

    // --- initial state -------------------------------------------------------------------------

    @Test
    fun `initial state is IDLE with no error and no events`() {
        assertEquals(State.IDLE, controller.state)
        assertNull(controller.lastError)
        assertTrue(listener.states.isEmpty())
    }

    // --- start -----------------------------------------------------------------------------------

    @Test
    fun `start moves IDLE to LISTENING and fires the start event`() {
        assertTrue(controller.start())
        assertEquals(State.LISTENING, controller.state)
        assertEquals(listOf(State.LISTENING), listener.states)
        assertEquals(1, listener.startedCount.size)
    }

    @Test
    fun `start while LISTENING is rejected and fires nothing`() {
        controller.start()
        assertFalse(controller.start())
        assertEquals(State.LISTENING, controller.state)
        assertEquals(1, listener.startedCount.size)
        assertEquals(1, listener.states.size)
    }

    @Test
    fun `start while PROCESSING is rejected`() {
        controller.start()
        controller.onResult("привет")
        assertFalse(controller.start())
        assertEquals(State.PROCESSING, controller.state)
        assertEquals(1, listener.startedCount.size)
    }

    @Test
    fun `start after ERROR restarts the session and clears lastError`() {
        controller.start()
        controller.onError("микрофон занят")
        assertEquals(State.ERROR, controller.state)
        assertTrue(controller.start())
        assertEquals(State.LISTENING, controller.state)
        assertNull(controller.lastError)
    }

    // --- successful recognition --------------------------------------------------------------------

    @Test
    fun `result moves LISTENING to PROCESSING and delivers trimmed text`() {
        controller.start()
        assertTrue(controller.onResult("  проверь сервер  "))
        assertEquals(State.PROCESSING, controller.state)
        assertEquals(listOf("проверь сервер"), listener.results)
    }

    @Test
    fun `processing complete returns to IDLE`() {
        controller.start()
        controller.onResult("текст")
        assertTrue(controller.onProcessingComplete())
        assertEquals(State.IDLE, controller.state)
        assertEquals(
            listOf(State.LISTENING, State.PROCESSING, State.IDLE),
            listener.states,
        )
    }

    @Test
    fun `onProcessingComplete outside PROCESSING is rejected`() {
        assertFalse(controller.onProcessingComplete())
        controller.start()
        assertFalse(controller.onProcessingComplete()) // still LISTENING
        assertEquals(State.LISTENING, controller.state)
    }

    // --- blank / stale results ---------------------------------------------------------------------

    @Test
    fun `blank recognition becomes an error, never an empty message`() {
        controller.start()
        assertFalse(controller.onResult("   "))
        assertEquals(State.ERROR, controller.state)
        assertEquals(VoiceSessionController.EMPTY_RESULT, controller.lastError)
        assertEquals(listOf(VoiceSessionController.EMPTY_RESULT), listener.errors)
        assertTrue("host must not receive an empty result", listener.results.isEmpty())
    }

    @Test
    fun `result in IDLE is ignored as a late recognizer callback`() {
        assertFalse(controller.onResult("запоздалый текст"))
        assertEquals(State.IDLE, controller.state)
        assertTrue(listener.results.isEmpty())
        assertTrue(listener.states.isEmpty())
    }

    @Test
    fun `second result during PROCESSING is ignored`() {
        controller.start()
        controller.onResult("первый")
        assertFalse(controller.onResult("второй"))
        assertEquals(listOf("первый"), listener.results)
    }

    // --- errors --------------------------------------------------------------------------------------

    @Test
    fun `error from LISTENING moves to ERROR and records the message`() {
        controller.start()
        controller.onError("нет микрофона")
        assertEquals(State.ERROR, controller.state)
        assertEquals("нет микрофона", controller.lastError)
        assertEquals(listOf(State.LISTENING, State.ERROR), listener.states)
    }

    @Test
    fun `error from PROCESSING moves to ERROR`() {
        controller.start()
        controller.onResult("текст")
        controller.onError("сеть недоступна")
        assertEquals(State.ERROR, controller.state)
    }

    @Test
    fun `blank error message falls back to the generic text`() {
        controller.start()
        controller.onError("")
        assertEquals(VoiceSessionController.GENERIC_ERROR, controller.lastError)
    }

    @Test
    fun `error while IDLE cannot resurrect a cancelled session`() {
        controller.onError("поздняя ошибка")
        assertEquals(State.IDLE, controller.state)
        assertNull(controller.lastError)
        assertTrue(listener.errors.isEmpty())
    }

    // --- cancel / reset -------------------------------------------------------------------------------

    @Test
    fun `cancel returns to IDLE from LISTENING without delivering a result`() {
        controller.start()
        assertTrue(controller.cancel())
        assertEquals(State.IDLE, controller.state)
        assertTrue(listener.results.isEmpty())
    }

    @Test
    fun `cancel returns to IDLE from PROCESSING and from ERROR`() {
        controller.start()
        controller.onResult("текст")
        assertTrue(controller.cancel())
        assertEquals(State.IDLE, controller.state)

        controller.start()
        controller.onError("сбой")
        assertTrue(controller.cancel())
        assertEquals(State.IDLE, controller.state)
        assertNull("cancel clears lastError", controller.lastError)
    }

    @Test
    fun `cancel in IDLE is a no-op`() {
        assertFalse(controller.cancel())
        assertTrue(listener.states.isEmpty())
    }

    @Test
    fun `reset clears ERROR back to IDLE`() {
        controller.start()
        controller.onError("сбой")
        assertTrue(controller.reset())
        assertEquals(State.IDLE, controller.state)
        assertNull(controller.lastError)
    }

    @Test
    fun `reset outside ERROR is rejected`() {
        assertFalse(controller.reset())
        controller.start()
        assertFalse(controller.reset())
        assertEquals(State.LISTENING, controller.state)
    }

    // --- full lifecycle ----------------------------------------------------------------------------------

    @Test
    fun `full happy-path lifecycle fires events in order`() {
        controller.start()
        controller.onResult("включи музыку")
        controller.onProcessingComplete()
        assertEquals(State.IDLE, controller.state)
        assertEquals(1, listener.startedCount.size)
        assertEquals(listOf("включи музыку"), listener.results)
        assertTrue(listener.errors.isEmpty())
        assertEquals(
            listOf(State.LISTENING, State.PROCESSING, State.IDLE),
            listener.states,
        )
    }

    @Test
    fun `controller works without a listener`() {
        val bare = VoiceSessionController()
        assertTrue(bare.start())
        assertTrue(bare.onResult("текст"))
        assertTrue(bare.onProcessingComplete())
        assertEquals(State.IDLE, bare.state)
    }
}
