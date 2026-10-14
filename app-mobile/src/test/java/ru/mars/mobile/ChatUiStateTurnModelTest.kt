package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent

/**
 * Модель хода на пузыре ответа (волна C, фича A): основной источник — ранний кадр
 * `context_status` (хост зовёт [ChatUiState.stampTurnModel]), фоллбэк — HTTP-ответ
 * chat/start ([ChatEvent.StreamStarted]). Новый ход начинает с чистого пузыря:
 * прежняя модель в него не протекает, локальный выбор пикера не штампуется никогда.
 */
class ChatUiStateTurnModelTest {

    private fun stateWithOpenTurn(): ChatUiState {
        val state = ChatUiState()
        state.bindSession("s1", null, emptyList())
        state.begin("привет")
        return state
    }

    @Test
    fun `fresh turn bubble carries no model - local picker choice never leaks in`() {
        val state = stateWithOpenTurn()

        val bubble = state.messages.last()
        assertNull(bubble.model)
        assertNull(bubble.modelProvider)
    }

    @Test
    fun `context_status stamp becomes the turn model`() {
        val state = stateWithOpenTurn()

        val changed = state.stampTurnModel("qwen3.8-max", "custom:plusvibe-api")

        assertTrue(changed)
        val bubble = state.messages.last()
        assertEquals("qwen3.8-max", bubble.model)
        assertEquals("custom:plusvibe-api", bubble.modelProvider)
    }

    @Test
    fun `stamp overrides the HTTP start-response fallback`() {
        val state = stateWithOpenTurn()
        state.onEvent(ChatEvent.StreamStarted("stream-1", model = "normalized-alias", provider = "openai"))
        assertEquals("normalized-alias", state.messages.last().model)

        // Кадр context_status — авторитет: перезаписывает фоллбэк.
        state.stampTurnModel("hermes-4", "nous")

        assertEquals("hermes-4", state.messages.last().model)
        assertEquals("nous", state.messages.last().modelProvider)
    }

    @Test
    fun `StreamStarted without effective model leaves the bubble unstamped`() {
        val state = stateWithOpenTurn()

        state.onEvent(ChatEvent.StreamStarted("stream-1", model = null, provider = null))

        assertNull(state.messages.last().model)
        assertNull(state.messages.last().modelProvider)
    }

    @Test
    fun `blank or missing model in the stamp is a no-op`() {
        val state = stateWithOpenTurn()
        state.stampTurnModel("hermes-4", "nous")

        assertFalse(state.stampTurnModel(null, "openai"))
        assertFalse(state.stampTurnModel("   ", "openai"))
        // Прежний штамп не затёрт.
        assertEquals("hermes-4", state.messages.last().model)
        assertEquals("nous", state.messages.last().modelProvider)
    }

    @Test
    fun `repeating the same stamp reports no change`() {
        val state = stateWithOpenTurn()
        assertTrue(state.stampTurnModel("hermes-4", "nous"))

        assertFalse(state.stampTurnModel("hermes-4", "nous"))
    }

    @Test
    fun `stamp without an assistant bubble is dropped`() {
        val state = ChatUiState()
        state.bindSession("s1", null, emptyList())

        assertFalse(state.stampTurnModel("hermes-4", "nous"))
    }

    @Test
    fun `new turn resets the previous turn model`() {
        val state = stateWithOpenTurn()
        state.stampTurnModel("hermes-4", "nous")
        state.onEvent(ChatEvent.Chunk("ответ"))
        state.onEvent(ChatEvent.Done)

        state.begin("следующий вопрос")

        // Пузырь прошлого хода сохранил свою модель, новый — чистый.
        val previous = state.messages[state.messages.size - 3]
        assertEquals("hermes-4", previous.model)
        assertNull(state.messages.last().model)
        assertNull(state.messages.last().modelProvider)
    }

    @Test
    fun `stamp lands on the assistant bubble only`() {
        val state = stateWithOpenTurn()

        state.stampTurnModel("hermes-4", "nous")

        val user = state.messages.first { it.role == "user" }
        assertNull(user.model)
    }
}
