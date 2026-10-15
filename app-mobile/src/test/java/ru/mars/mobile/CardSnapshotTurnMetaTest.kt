package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.SubagentStatus

/**
 * Снимок карточки покрывает turn-meta поля (волна C): модель хода и доска
 * субагентов — входы рендера, их изменение обязано делать снимок «грязным»
 * (иначе IncrementalTranscriptPolicy скажет NoChange и подпись/доска залипнут).
 */
class CardSnapshotTurnMetaTest {

    /**
     * Снимок с ФИКСИРОВАННОЙ меткой времени: [ChatMessage.timestamp] по умолчанию
     * берётся из `System.currentTimeMillis()`, поэтому два «одинаковых» сообщения,
     * созданные в разные миллисекунды, давали разные снимки (flaky на CI). Тест про
     * turn-meta поля, а не про время — время здесь константа.
     */
    private fun snapshot(message: ChatMessage, timestamp: Long = 1_700_000_000_000L): CardSnapshot =
        CardSnapshotPolicy.of(message.copy(timestamp = timestamp), previousTimestamp = 0L, chromeEpoch = 1L, streamingFlag = true)

    @Test
    fun `turn model stamp dirties the snapshot`() {
        val before = snapshot(ChatMessage("assistant", text = "ответ"))
        val after = snapshot(ChatMessage("assistant", text = "ответ", model = "hermes-4", modelProvider = "nous"))

        assertNotEquals(before, after)
        // Модель — часть «хрома» карточки: меняется не только markdown-секция.
        assertNotEquals(before.sameChrome(after), true)
        assertEquals("hermes-4", after.model)
        assertEquals("nous", after.modelProvider)
    }

    @Test
    fun `unstamped messages keep null model`() {
        val snap = snapshot(ChatMessage("assistant", text = "ответ"))
        assertNull(snap.model)
        assertNull(snap.modelProvider)
    }

    @Test
    fun `subagent board changes dirty the snapshot`() {
        val empty = snapshot(ChatMessage("assistant", text = "ответ"))
        val running = snapshot(
            ChatMessage(
                "assistant", text = "ответ",
                subagents = mapOf(
                    "c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Running, displayName = "Исследователь"),
                ),
            ),
        )
        val completed = snapshot(
            ChatMessage(
                "assistant", text = "ответ",
                subagents = mapOf(
                    "c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Completed, displayName = "Исследователь"),
                ),
            ),
        )

        assertNotEquals(empty, running)
        assertNotEquals(running, completed)
        assertEquals(empty, snapshot(ChatMessage("assistant", text = "ответ")))
    }

    @Test
    fun `subagent fingerprint reacts to every presented field`() {
        val base = CardSnapshotPolicy.subagentsFingerprint(
            mapOf("c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Running, displayName = "A", model = "m", phase = "шаг")),
        )
        val otherPhase = CardSnapshotPolicy.subagentsFingerprint(
            mapOf("c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Running, displayName = "A", model = "m", phase = "другой шаг")),
        )
        val failed = CardSnapshotPolicy.subagentsFingerprint(
            mapOf("c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Failed, displayName = "A", model = "m", phase = "шаг", failureReason = "timeout")),
        )

        assertNotEquals(base, otherPhase)
        assertNotEquals(base, failed)
        assertEquals(base, CardSnapshotPolicy.subagentsFingerprint(
            mapOf("c1" to SubagentStatus(childId = "c1", state = ru.mars.network.model.SubagentState.Running, displayName = "A", model = "m", phase = "шаг")),
        ))
    }
}
