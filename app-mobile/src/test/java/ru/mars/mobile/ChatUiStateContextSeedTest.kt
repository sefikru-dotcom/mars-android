package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ChatEvent

/**
 * Кольцо заполнения контекста при ОТКРЫТИИ диалога (#ring-seed).
 *
 * Раньше [ChatUiState.bindSession] обнулял `contextUsage`, а refill давали только
 * живые `metering`-кадры текущего хода. Открытый старый диалог показывал пустое
 * кольцо до первого хода — владелец видел «непонятно, сколько уже занято».
 * Теперь холодный замер из `GET /api/session` вливается прямо в bindSession.
 */
class ChatUiStateContextSeedTest {

    private val usage = ChatEvent.ContextUsage(usedTokens = 160_000, maxTokens = 200_000, percent = 80)

    @Test
    fun `cold context snapshot from session detail lands in state`() {
        val state = ChatUiState()
        state.bindSession("s1", "Старый диалог", emptyList(), contextUsage = usage)
        assertEquals(usage, state.contextUsage)
    }

    @Test
    fun `missing snapshot keeps ring hidden instead of fake zero`() {
        val state = ChatUiState()
        state.bindSession("s1", "Диалог", emptyList())
        assertNull(state.contextUsage)
    }

    @Test
    fun `binding another session replaces the snapshot`() {
        val state = ChatUiState()
        state.bindSession("s1", "A", emptyList(), contextUsage = usage)
        assertEquals(usage, state.contextUsage)

        state.bindSession("s2", "B", emptyList())
        assertNull("чужой замер не должен протекать в другой диалог", state.contextUsage)

        val other = ChatEvent.ContextUsage(1_000, 200_000, 1)
        state.bindSession("s3", "C", emptyList(), contextUsage = other)
        assertEquals(other, state.contextUsage)
    }

    @Test
    fun `live metering frame still overrides the cold snapshot`() {
        val state = ChatUiState()
        state.bindSession("s1", "Диалог", emptyList(), contextUsage = usage)
        val fresh = ChatEvent.ContextUsage(40_000, 200_000, 20)
        state.onEvent(fresh)
        assertEquals(fresh, state.contextUsage)
    }

    @Test
    fun `new session clears the snapshot`() {
        val state = ChatUiState()
        state.bindSession("s1", "Диалог", emptyList(), contextUsage = usage)
        state.startNewSession()
        assertNull(state.contextUsage)
    }
}
