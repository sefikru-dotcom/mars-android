package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRegeneratePolicyTest {

    private val policy = TurnRegeneratePolicy()

    private fun user(text: String, attachments: List<String> = emptyList()) =
        ChatMessage("user", text, attachments = attachments)

    private fun assistant(text: String = "ответ", status: MessageStatus = MessageStatus.COMPLETE) =
        ChatMessage("assistant", text, status = status)

    // --- lastUserTurn extraction ---

    @Test
    fun `extracts the last user turn with text attachments and index`() {
        val messages = listOf(user("первый"), assistant(), user("второй", listOf("a.png")), assistant())
        val turn = policy.lastUserTurn(messages)
        assertEquals(UserTurn("второй", listOf("a.png"), 2), turn)
    }

    @Test
    fun `trims surrounding whitespace of the extracted prompt`() {
        val turn = policy.lastUserTurn(listOf(user("  привет  \n")))
        assertEquals("привет", turn?.text)
    }

    @Test
    fun `returns null for an empty transcript`() {
        assertNull(policy.lastUserTurn(emptyList()))
    }

    @Test
    fun `returns null when the transcript has no user rows`() {
        assertNull(policy.lastUserTurn(listOf(assistant(), assistant("ещё"))))
    }

    @Test
    fun `skips blank user bubbles and falls back to an earlier turn`() {
        val messages = listOf(user("реальный запрос"), assistant(), user("   "))
        val turn = policy.lastUserTurn(messages)
        assertEquals("реальный запрос", turn?.text)
        assertEquals(0, turn?.messageIndex)
    }

    @Test
    fun `attachment-only turn is retryable despite blank text`() {
        val messages = listOf(user("", listOf("photo.jpg")))
        val turn = policy.lastUserTurn(messages)
        assertEquals("", turn?.text)
        assertEquals(listOf("photo.jpg"), turn?.attachments)
    }

    // --- requestRegenerate decisions ---

    @Test
    fun `grants a retry on a settled transcript and marks the stream active`() {
        val messages = listOf(user("запрос"), assistant())
        assertFalse(policy.streamActive)

        val outcome = policy.requestRegenerate(messages, ChatStatus.RECEIVED)
        assertTrue(outcome is RegenerateOutcome.Retry)
        outcome as RegenerateOutcome.Retry
        assertEquals("запрос", outcome.text)
        assertEquals(emptyList<String>(), outcome.attachments)
        assertTrue(policy.streamActive)
    }

    @Test
    fun `retry carries the attachments of the last user turn`() {
        val outcome = policy.requestRegenerate(
            listOf(user("посмотри", listOf("a.png", "b.png"))), ChatStatus.IDLE,
        )
        assertEquals(RegenerateOutcome.Retry("посмотри", listOf("a.png", "b.png")), outcome)
    }

    @Test
    fun `rejects while the chat status is typing`() {
        val messages = listOf(user("запрос"), assistant("", MessageStatus.STREAMING))
        val outcome = policy.requestRegenerate(messages, ChatStatus.TYPING)
        assertEquals(
            RegenerateOutcome.Rejected(RegenerateRejectReason.ALREADY_STREAMING),
            outcome,
        )
        assertFalse(policy.streamActive)
    }

    @Test
    fun `double trigger is rejected until the turn settles`() {
        val messages = listOf(user("запрос"), assistant())
        assertTrue(policy.requestRegenerate(messages, ChatStatus.RECEIVED) is RegenerateOutcome.Retry)

        val second = policy.requestRegenerate(messages, ChatStatus.IDLE)
        assertEquals(
            RegenerateOutcome.Rejected(RegenerateRejectReason.ALREADY_STREAMING),
            second,
        )

        policy.onTurnSettled()
        assertFalse(policy.streamActive)
        assertTrue(policy.requestRegenerate(messages, ChatStatus.IDLE) is RegenerateOutcome.Retry)
    }

    @Test
    fun `rejects when there is no retryable user turn`() {
        val outcome = policy.requestRegenerate(listOf(assistant()), ChatStatus.IDLE)
        assertEquals(RegenerateOutcome.Rejected(RegenerateRejectReason.NO_USER_TURN), outcome)
        assertFalse(policy.streamActive)
    }

    @Test
    fun `rejection never marks the stream active`() {
        policy.requestRegenerate(emptyList(), ChatStatus.TYPING)
        assertFalse(policy.streamActive)
        policy.requestRegenerate(emptyList(), ChatStatus.IDLE)
        assertFalse(policy.streamActive)
    }

    @Test
    fun `error status is retryable - regenerate is exactly the recovery path`() {
        val outcome = policy.requestRegenerate(listOf(user("упавший запрос")), ChatStatus.ERROR)
        assertEquals(RegenerateOutcome.Retry("упавший запрос", emptyList()), outcome)
    }

    // --- lifecycle ---

    @Test
    fun `onTurnSettled clears the guard`() {
        policy.requestRegenerate(listOf(user("x")), ChatStatus.IDLE)
        assertTrue(policy.streamActive)
        policy.onTurnSettled()
        assertFalse(policy.streamActive)
    }

    @Test
    fun `reset clears the guard on session swap`() {
        policy.requestRegenerate(listOf(user("x")), ChatStatus.IDLE)
        assertTrue(policy.streamActive)
        policy.reset()
        assertFalse(policy.streamActive)
    }

    @Test
    fun `onTurnSettled is idempotent`() {
        policy.onTurnSettled()
        policy.onTurnSettled()
        assertFalse(policy.streamActive)
        assertTrue(
            policy.requestRegenerate(listOf(user("x")), ChatStatus.IDLE) is RegenerateOutcome.Retry
        )
    }
}
