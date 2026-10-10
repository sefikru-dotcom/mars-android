package ru.mars.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator

/**
 * Unit tests for [TvChatSessionFlow] — the pure TV-side session gate and stream-state
 * formatter. Pins the WebUI-parity contract: chat/start is never called with a null
 * session id, Thought/ToolCall/ToolResult become a compact status line, ContextUsage
 * becomes the token pill (never a fake percent), and «Новый чат» resets everything.
 */
class TvChatSessionFlowTest {
    private val credential = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)

    private class FakeCreator(private val outcome: CreateSessionOutcome) : SessionCreator {
        var calls = 0
        var lastBaseUrl: String? = null
        var lastCredential: ConsumedDeviceCredential? = null
        var lastArgs: Map<String, Any?>? = null
        var statusWhileCreating: String? = null
        var onCreate: (() -> Unit)? = null
        override suspend fun createSession(
            baseUrl: String,
            credential: ConsumedDeviceCredential,
            args: Map<String, Any?>
        ): CreateSessionOutcome {
            calls++
            lastBaseUrl = baseUrl
            lastCredential = credential
            lastArgs = args
            onCreate?.invoke()
            return outcome
        }
    }

    private fun flowWith(
        outcome: CreateSessionOutcome = CreateSessionOutcome.Success(SessionDetail("minted")),
    ): Pair<TvChatSessionFlow, FakeCreator> {
        val creator = FakeCreator(outcome)
        return TvChatSessionFlow(creator, "https://base") to creator
    }

    // ---- session preflight -------------------------------------------------

    @Test fun `bound session is reused without creating a new one`() = runBlocking {
        val (flow, creator) = flowWith()
        flow.onEvent(ChatEvent.SessionBound("bound-1"))
        val result = flow.ensureSession(credential)
        assertEquals(TvSessionResult.Ready("bound-1"), result)
        assertEquals(0, creator.calls)
        assertEquals("bound-1", flow.sessionId)
        assertTrue(flow.hasSession)
    }

    @Test fun `null session creates one via SessionCreator and binds the id`() = runBlocking {
        val (flow, creator) = flowWith(CreateSessionOutcome.Success(SessionDetail("minted-9")))
        val result = flow.ensureSession(credential)
        assertEquals(TvSessionResult.Ready("minted-9"), result)
        assertEquals("minted-9", flow.sessionId)
        assertEquals(1, creator.calls)
        assertEquals("https://base", creator.lastBaseUrl)
        assertEquals(credential, creator.lastCredential)
        assertEquals(emptyMap<String, Any?>(), creator.lastArgs)
    }

    @Test fun `blank bound session is treated as missing`() = runBlocking {
        val (flow, creator) = flowWith(CreateSessionOutcome.Success(SessionDetail("minted-2")))
        flow.onEvent(ChatEvent.SessionBound("  "))
        val result = flow.ensureSession(credential)
        assertEquals(TvSessionResult.Ready("minted-2"), result)
        assertEquals(1, creator.calls)
        assertEquals("minted-2", flow.sessionId)
        assertTrue(flow.hasSession)
    }

    @Test fun `creation failure surfaces the reason and leaves session unbound`() = runBlocking {
        val (flow, _) = flowWith(CreateSessionOutcome.Failure("HTTP 500"))
        flow.beginTurn()
        val result = flow.ensureSession(credential)
        assertTrue(result is TvSessionResult.Failed)
        assertEquals("HTTP 500", (result as TvSessionResult.Failed).message)
        assertNull(flow.sessionId)
        assertEquals("", flow.statusText)
    }

    @Test fun `preflight shows creating-session status while minting`() = runBlocking {
        val (flow, creator) = flowWith()
        creator.onCreate = { creator.statusWhileCreating = flow.statusText }
        flow.ensureSession(credential)
        assertEquals(TvChatSessionFlow.CREATING_SESSION, creator.statusWhileCreating)
        assertEquals(TvChatSessionFlow.TYPING, flow.statusText)
    }

    @Test fun `beginTurn sets the typing status`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        assertEquals("Марс печатает…", flow.statusText)
    }

    // ---- stream event status line ------------------------------------------

    @Test fun `thought event shows the reasoning status`() {
        assertEquals("Марс размышляет…", TvChatSessionFlow.statusFor(ChatEvent.Thought("hmm")))
    }

    @Test fun `tool call and result events name the tool`() {
        assertEquals(
            "Выполняет: read_file",
            TvChatSessionFlow.statusFor(ChatEvent.ToolCall(id = "1", name = "read_file", arguments = "{}"))
        )
        assertEquals(
            "Выполнил: read_file",
            TvChatSessionFlow.statusFor(ChatEvent.ToolResult(id = "1", name = "read_file", result = "ok"))
        )
    }

    @Test fun `chunk done and error events map to compact statuses`() {
        assertEquals("Марс печатает…", TvChatSessionFlow.statusFor(ChatEvent.Chunk("text")))
        assertEquals("Ответ получен", TvChatSessionFlow.statusFor(ChatEvent.Done))
        assertEquals("Ошибка: boom", TvChatSessionFlow.statusFor(ChatEvent.Error("boom")))
    }

    @Test fun `phase names get human labels`() {
        assertEquals("Выполняет команду…", TvChatSessionFlow.phaseLabel("tool_use"))
        assertEquals("Выполняет команду…", TvChatSessionFlow.phaseLabel("running Command"))
        assertEquals("Анализирует задачу…", TvChatSessionFlow.phaseLabel("thinking"))
        assertEquals("Анализирует задачу…", TvChatSessionFlow.phaseLabel("Reasoning"))
        assertEquals("Марс: streaming", TvChatSessionFlow.phaseLabel("streaming"))
    }

    @Test fun `interactive gates are announced instead of silently ignored`() {
        assertEquals(
            "Ожидает подтверждения",
            TvChatSessionFlow.statusFor(
                ChatEvent.ApprovalRequired(ApprovalPayload(null, "rm -rf", "dangerous"))
            )
        )
        assertEquals(
            "Ожидает уточнения",
            TvChatSessionFlow.statusFor(ChatEvent.ClarifyRequired(ClarifyPayload(null, "what?")))
        )
    }

    @Test fun `status-less events never clobber the current status`() {
        val (flow, _) = flowWith()
        flow.onEvent(ChatEvent.Thought("hmm"))
        flow.onEvent(ChatEvent.SessionBound("s-1"))
        assertEquals("Марс размышляет…", flow.statusText)
        flow.onEvent(ChatEvent.StreamStarted("st-1"))
        assertEquals("Марс размышляет…", flow.statusText)
        flow.onEvent(ChatEvent.ContextUsage(10, 100, 10))
        assertEquals("Марс размышляет…", flow.statusText)
        flow.onEvent(ChatEvent.SteerLeftover("oops"))
        assertEquals("Марс размышляет…", flow.statusText)
    }

    @Test fun `full stream sequence walks the status line and binds state`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        assertEquals("Марс печатает…", flow.statusText)

        flow.onEvent(ChatEvent.SessionBound("s-77"))
        assertEquals("s-77", flow.sessionId)

        flow.onEvent(ChatEvent.Thought("plan the answer"))
        assertEquals("Марс размышляет…", flow.statusText)

        flow.onEvent(ChatEvent.ToolCall("t1", "terminal", "ls"))
        assertEquals("Выполняет: terminal", flow.statusText)

        flow.onEvent(ChatEvent.ToolResult("t1", "terminal", "ok"))
        assertEquals("Выполнил: terminal", flow.statusText)

        flow.onEvent(ChatEvent.ContextUsage(12_000, 128_000, 9))
        assertEquals("9% · 12.0k/128.0k", flow.contextUsageText)

        flow.onEvent(ChatEvent.Chunk("Привет"))
        assertEquals("Марс печатает…", flow.statusText)

        flow.onEvent(ChatEvent.Done)
        assertEquals("Ответ получен", flow.statusText)
    }

    // ---- context usage pill --------------------------------------------------

    @Test fun `context usage is captured and formatted for the pill`() {
        val (flow, _) = flowWith()
        val usage = ChatEvent.ContextUsage(usedTokens = 1_500, maxTokens = 200_000, percent = 1)
        flow.onEvent(usage)
        assertEquals(usage, flow.contextUsage)
        assertEquals("1% · 1.5k/200.0k", flow.contextUsageText)
    }

    @Test fun `unknown context window yields no pill text and no fake percent`() {
        val (flow, _) = flowWith()
        flow.onEvent(ChatEvent.ContextUsage(usedTokens = 10, maxTokens = 0, percent = 0))
        assertNull(flow.contextUsageText)
        assertEquals(0, flow.contextUsage?.maxTokens)
    }

    @Test fun `token formatting matches the WebUI _fmtTokens contract`() {
        assertEquals("0", TvChatSessionFlow.formatTokens(0))
        assertEquals("0", TvChatSessionFlow.formatTokens(-5))
        assertEquals("999", TvChatSessionFlow.formatTokens(999))
        assertEquals("1.0k", TvChatSessionFlow.formatTokens(1_000))
        assertEquals("12.3k", TvChatSessionFlow.formatTokens(12_345))
        assertEquals("1.5M", TvChatSessionFlow.formatTokens(1_500_000))
    }

    // ---- new chat ------------------------------------------------------------

    @Test fun `startNewChat resets session metering and status`() = runBlocking {
        val (flow, _) = flowWith(CreateSessionOutcome.Success(SessionDetail("minted-1")))
        flow.ensureSession(credential)
        flow.onEvent(ChatEvent.ContextUsage(12_000, 128_000, 9))
        flow.onEvent(ChatEvent.Done)
        assertTrue(flow.hasSession)

        flow.startNewChat()

        assertNull(flow.sessionId)
        assertNull(flow.contextUsage)
        assertNull(flow.contextUsageText)
        assertEquals("", flow.statusText)
        assertFalse(flow.hasSession)

        // The next send must mint a fresh session, not reuse the dropped one.
        val result = flow.ensureSession(credential)
        assertEquals(TvSessionResult.Ready("minted-1"), result)
    }

    // ---- turn lifecycle / stop control ---------------------------------------

    @Test fun `beginTurn arms the stop control before the stream id arrives`() {
        val (flow, _) = flowWith()
        assertFalse(flow.canStop)
        flow.beginTurn()
        assertTrue(flow.canStop)
        assertNull(flow.activeStreamId)
    }

    @Test fun `StreamStarted binds the stream id the stop control cancels`() {
        val (flow, _) = flowWith()
        flow.onEvent(ChatEvent.StreamStarted("st-42"))
        assertEquals("st-42", flow.activeStreamId)
        assertTrue(flow.canStop)
    }

    @Test fun `Done disarms the stop control and keeps the completion status`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.onEvent(ChatEvent.StreamStarted("st-1"))
        flow.onEvent(ChatEvent.Done)
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
        assertEquals("Ответ получен", flow.statusText)
    }

    @Test fun `Error disarms the stop control and keeps the error status`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.onEvent(ChatEvent.StreamStarted("st-1"))
        flow.onEvent(ChatEvent.Error("boom"))
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
        assertEquals("Ошибка: boom", flow.statusText)
    }

    @Test fun `endTurn clears turn state without touching the status line`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.onEvent(ChatEvent.StreamStarted("st-1"))
        flow.onEvent(ChatEvent.Thought("hmm"))
        flow.endTurn()
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
        assertEquals("Марс размышляет…", flow.statusText)
    }

    @Test fun `markTurnStopped reports a server-confirmed cancel as stopped`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.onEvent(ChatEvent.StreamStarted("st-1"))
        flow.markTurnStopped(serverCancelled = true)
        assertEquals(TvChatSessionFlow.STOPPED, flow.statusText)
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
    }

    @Test fun `markTurnStopped without server confirmation says so honestly`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.markTurnStopped(serverCancelled = false)
        assertEquals(TvChatSessionFlow.STOP_FAILED, flow.statusText)
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
    }

    @Test fun `startNewChat also resets the turn state`() {
        val (flow, _) = flowWith()
        flow.beginTurn()
        flow.onEvent(ChatEvent.StreamStarted("st-9"))
        flow.startNewChat()
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
        assertEquals("", flow.statusText)
    }

    // ---- restored binding after a technical Activity recreation --------------

    @Test fun `restoreBinding rebinds the session without minting a fresh one`() = runBlocking {
        val (flow, creator) = flowWith()
        flow.restoreBinding(TvChatBinding("restored-1", null))
        assertEquals("restored-1", flow.sessionId)
        assertTrue(flow.hasSession)
        assertFalse("a session-only binding must not arm the stop control", flow.canStop)
        // The next send continues the SAME dialog — no fresh session is created.
        val result = flow.ensureSession(credential)
        assertEquals(TvSessionResult.Ready("restored-1"), result)
        assertEquals(0, creator.calls)
    }

    @Test fun `restoreBinding with a live stream re-arms the stop control`() {
        val (flow, _) = flowWith()
        flow.restoreBinding(TvChatBinding("s-1", "st-7"))
        assertEquals("st-7", flow.activeStreamId)
        assertTrue(flow.canStop)
        // The recreated screen can still stop the orphaned server turn, honestly.
        flow.markTurnStopped(serverCancelled = true)
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
        assertEquals(TvChatSessionFlow.STOPPED, flow.statusText)
    }

    @Test fun `restoreBinding keeps a stream-only binding stoppable without a session`() {
        val (flow, _) = flowWith()
        flow.restoreBinding(TvChatBinding(null, "st-3"))
        assertNull(flow.sessionId)
        assertEquals("st-3", flow.activeStreamId)
        assertTrue(flow.canStop)
    }

    @Test fun `restoreBinding ignores a null binding and blank ids`() {
        val (flow, _) = flowWith()
        flow.restoreBinding(null)
        assertNull(flow.sessionId)
        assertFalse(flow.canStop)
        flow.restoreBinding(TvChatBinding("   ", ""))
        assertNull(flow.sessionId)
        assertFalse(flow.hasSession)
        assertNull(flow.activeStreamId)
        assertFalse(flow.canStop)
    }

    @Test fun `restoreBinding never invents a status line`() {
        val (flow, _) = flowWith()
        flow.restoreBinding(TvChatBinding("s-2", "st-2"))
        // The stop-button state is the honest indicator; the status strip stays hidden.
        assertEquals("", flow.statusText)
    }

    @Test fun `startNewChat after a restore drops the rebound binding`() {
        val (flow, _) = flowWith()
        flow.restoreBinding(TvChatBinding("s-4", "st-4"))
        flow.startNewChat()
        assertNull(flow.sessionId)
        assertFalse(flow.canStop)
        assertNull(flow.activeStreamId)
    }
}
