package ru.mars.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionMessage
import ru.mars.network.repository.AdaptiveSseReconnectPolicy
import ru.mars.network.repository.MidTurnRecoveryCoordinator

/**
 * Unit tests for [TvChatStreamRecoveryLoop] — the SINGLE mid-turn recovery job of the TV
 * chat screen — against the REAL core [MidTurnRecoveryCoordinator] with fake transport,
 * fake connectivity and a recording sleeper (no Android, no sockets, no wall-clock waits).
 * Pins the contract the host ([TvChatActivity]) relies on:
 * - a dropped turn is REATTACHED (attachToStream + Last-Event-ID cursor) and never re-POSTed
 *   (the transport seam has no `sendMessage` at all);
 * - offline parks on the connectivity observer and resumes WITHOUT burning the retry budget;
 * - persistent failures are BOUNDED — give-up arrives after the core budget, no endless loop;
 * - a gone journal (anchored 404) and a turn that settled while away both end in exactly one
 *   history reload; definitive server verdicts (401 auth / 400 business) never reconnect.
 */
class TvChatStreamRecoveryLoopTest {

    /** Scripted transport: records every attach (stream id + cursor) and replays events per outcome. */
    private class FakeTransport(
        var detail: SessionDetail?,
        private val attachScript: MutableList<TvAttachOutcome>,
    ) : TvRecoveryTransport {
        val attachedStreamIds = mutableListOf<String>()
        val attachedCursors = mutableListOf<String?>()
        var detailCalls = 0

        override suspend fun fetchSessionDetail(sessionId: String): SessionDetail? {
            detailCalls++
            return detail
        }

        override suspend fun attachStream(
            streamId: String,
            lastEventId: String?,
            onEvent: (ChatEvent) -> Unit,
        ): TvAttachOutcome {
            attachedStreamIds += streamId
            attachedCursors += lastEventId
            val outcome = attachScript.removeFirstOrNull()
                ?: TvAttachOutcome.Lost("Сетевая ошибка: script exhausted")
            when (outcome) {
                is TvAttachOutcome.Settled -> {
                    onEvent(ChatEvent.StreamStarted(streamId))
                    onEvent(ChatEvent.Chunk("продолжение ответа"))
                    onEvent(ChatEvent.Done)
                }
                is TvAttachOutcome.Gone ->
                    onEvent(ChatEvent.Error(TvStreamReattachPolicy.STREAM_GONE_MESSAGE))
                is TvAttachOutcome.Lost -> onEvent(ChatEvent.Error(outcome.message))
            }
            return outcome
        }
    }

    /** Connectivity fake: offline until the test opens [gate]; never sleeps for real. */
    private class FakeConnectivity(@Volatile var available: Boolean = true) : TvConnectivityProbe {
        val gate = CompletableDeferred<Unit>()
        var parkCalls = 0

        override fun isNetworkAvailable(): Boolean = available

        override suspend fun awaitNetworkAvailable() {
            if (available) return
            parkCalls++
            gate.await()
            available = true
        }
    }

    private open class RecordingHooks : TvChatStreamRecoveryLoop.Hooks {
        val statuses = mutableListOf<String>()
        val attachStarting = mutableListOf<String>()
        val events = mutableListOf<ChatEvent>()
        var reloadedDetail: SessionDetail? = null
        var reloadCalls = 0
        var giveUpReason: String? = null

        override fun onStatus(status: String) { statuses += status }
        override fun onAttachStarting(streamId: String) { attachStarting += streamId }
        override fun onStreamEvent(event: ChatEvent) { events += event }
        override fun onReloadHistory(detail: SessionDetail?) { reloadCalls++; reloadedDetail = detail }
        override fun onGiveUp(reason: String) { giveUpReason = reason }
    }

    /** Immediate clock seam: records the requested backoff instead of sleeping. */
    private class RecordingSleeper {
        val requested = mutableListOf<Long>()
        val fn: suspend (Long) -> Unit = { requested += it }
    }

    private fun liveDetail(streamId: String = "st1") = SessionDetail(
        id = "s1",
        messages = listOf(SessionMessage(role = "user", content = "привет")),
        activeTurn = ActiveTurnInfo(activeStreamId = streamId, isStreaming = true),
    )

    private fun settledDetail() = SessionDetail(
        id = "s1",
        messages = listOf(
            SessionMessage(role = "user", content = "привет"),
            SessionMessage(role = "assistant", content = "готово"),
        ),
    )

    /**
     * The REAL core coordinator with the jitter switched off, so the backoff ladder is
     * asserted exactly (production keeps the default ±20% jitter — the ladder SHAPE and
     * the budget are what the loop contract pins, not the randomizer).
     */
    private fun deterministicCoordinator() = MidTurnRecoveryCoordinator(
        reconnect = AdaptiveSseReconnectPolicy(jitterFraction = 0.0),
    )

    private fun loop(
        transport: TvRecoveryTransport,
        connectivity: TvConnectivityProbe = FakeConnectivity(),
        sleeper: suspend (Long) -> Unit = {},
        coordinator: MidTurnRecoveryCoordinator = deterministicCoordinator(),
        cursorProvider: () -> String? = { null },
    ) = TvChatStreamRecoveryLoop(transport, connectivity, cursorProvider, coordinator, sleeper)

    @Test
    fun `transient drop reattaches and settles - events reach the host once`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf(TvAttachOutcome.Settled))
        val hooks = RecordingHooks()
        val sleeper = RecordingSleeper()

        loop(transport, sleeper = sleeper.fn).run("s1", "st1", "Сетевая ошибка: connection reset", hooks)

        assertEquals(listOf("st1"), transport.attachedStreamIds)
        assertEquals(listOf("st1"), hooks.attachStarting)
        // The reattached journal is delivered to the host verbatim (one bubble, no dupes)
        // and ends with the terminal Done — no give-up, no reload.
        assertTrue(hooks.events.any { it is ChatEvent.Chunk })
        assertTrue(hooks.events.last() is ChatEvent.Done)
        assertEquals(0, hooks.reloadCalls)
        assertNull(hooks.giveUpReason)
        // A recovering stream gets ONE backoff (500 ms, core budget) before the reattach.
        assertEquals(listOf(500L), sleeper.requested)
    }

    @Test
    fun `persistent transport failure is bounded - give-up after the core budget`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf()) // every attach fails
        val hooks = RecordingHooks()
        val sleeper = RecordingSleeper()

        loop(transport, sleeper = sleeper.fn).run("s1", "st1", null, hooks)

        // Core budget (DEFAULT_MAX_RETRIES = 8): then give-up — NOT an endless loop.
        assertEquals(8, transport.attachedStreamIds.size)
        assertEquals("reconnect budget exhausted after 8 attempts", hooks.giveUpReason)
        // The full core ladder (jitter off): 500 ms doubling, capped at 30 s.
        assertEquals(
            listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            sleeper.requested,
        )
        assertEquals(0, hooks.reloadCalls)
    }

    @Test
    fun `offline parks on the observer and resumes without burning attempts`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf(TvAttachOutcome.Settled))
        val connectivity = FakeConnectivity(available = false)
        val hooks = RecordingHooks()

        val job = async { loop(transport, connectivity).run("s1", "st1", "Сетевая ошибка", hooks) }
        // Wait for the park to be announced, then prove no attach happened while offline.
        repeat(500) { if (connectivity.parkCalls == 0) yield() }
        assertEquals(1, connectivity.parkCalls)
        assertEquals(0, transport.attachedStreamIds.size)
        assertTrue(hooks.statuses.contains(TvChatStreamRecoveryLoop.STATUS_PARKED))

        connectivity.gate.complete(Unit) // the observer fires: connectivity is back
        job.await()

        assertEquals(1, transport.attachedStreamIds.size) // parked time burned no attempts
        assertTrue(hooks.statuses.contains(TvChatStreamRecoveryLoop.recoveringStatus(1)))
        assertTrue(hooks.events.last() is ChatEvent.Done)
    }

    @Test
    fun `gone journal (anchored 404) reloads history exactly once`() = runBlocking {
        val transport = FakeTransport(settledDetail(), mutableListOf())
        val hooks = RecordingHooks()

        loop(transport).run("s1", "st1", TvStreamReattachPolicy.STREAM_GONE_MESSAGE, hooks)

        assertEquals(0, transport.attachedStreamIds.size) // a dead journal is never reattached
        assertEquals(1, hooks.reloadCalls)
        assertEquals(settledDetail(), hooks.reloadedDetail)
        assertNull(hooks.giveUpReason)
    }

    @Test
    fun `gone journal discovered mid-attach also reloads instead of reattaching`() = runBlocking {
        // The session is still LIVE when recovery starts, so the loop really attaches;
        // the attach answers 404 (journal gone) and the server has meanwhile persisted
        // the finished turn — the honest end state is ONE reload of that history.
        val transport = FakeTransport(liveDetail(), mutableListOf(TvAttachOutcome.Gone))
        val settled = settledDetail()
        val hooks = RecordingHooks()

        // Flip the fake's detail to the settled history the moment the 404 lands.
        val originalAttach = transport
        val flipping = object : TvRecoveryTransport by originalAttach {
            override suspend fun attachStream(
                streamId: String,
                lastEventId: String?,
                onEvent: (ChatEvent) -> Unit,
            ): TvAttachOutcome {
                val outcome = originalAttach.attachStream(streamId, lastEventId, onEvent)
                if (outcome is TvAttachOutcome.Gone) originalAttach.detail = settled
                return outcome
            }
        }

        loop(flipping).run("s1", "st1", "Сетевая ошибка", hooks)

        assertEquals(1, transport.attachedStreamIds.size) // exactly one attach, then the 404 verdict
        assertEquals(1, hooks.reloadCalls)
        assertEquals(settled, hooks.reloadedDetail)
        assertNull(hooks.giveUpReason)
    }

    @Test
    fun `turn settled while away ends in one history reload`() = runBlocking {
        // First attach fails (transport), but the detail now shows a SETTLED turn:
        // the answer is in persisted history — reload it instead of reattaching forever.
        val detail = settledDetail()
        val transport = object : TvRecoveryTransport {
            var attachCalls = 0
            override suspend fun fetchSessionDetail(sessionId: String): SessionDetail? = detail
            override suspend fun attachStream(
                streamId: String,
                lastEventId: String?,
                onEvent: (ChatEvent) -> Unit,
            ): TvAttachOutcome {
                attachCalls++
                return TvAttachOutcome.Lost("Сетевая ошибка")
            }
        }
        val hooks = RecordingHooks()

        loop(transport).run("s1", "st1", "Сетевая ошибка: timeout", hooks)

        // The loop checks the session detail BEFORE attaching: a settled turn means the
        // answer is already in persisted history — zero attaches, exactly one reload.
        assertEquals(0, transport.attachCalls)
        assertEquals(1, hooks.reloadCalls)
        assertEquals(detail, hooks.reloadedDetail)
        assertNull(hooks.giveUpReason)
    }

    @Test
    fun `auth rejection gives up immediately without any attach`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf())
        val hooks = RecordingHooks()

        loop(transport).run("s1", "st1", "HTTP 401: unauthorized", hooks)

        assertEquals(0, transport.attachedStreamIds.size)
        assertNotNull(hooks.giveUpReason)
        assertTrue(hooks.giveUpReason!!.startsWith("auth rejected"))
    }

    @Test
    fun `definitive business error (HTTP 400) never reconnects`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf())
        val hooks = RecordingHooks()

        loop(transport).run("s1", "st1", "HTTP 400: message rejected", hooks)

        assertEquals(0, transport.attachedStreamIds.size)
        assertNotNull(hooks.giveUpReason)
    }

    @Test
    fun `retryable server verdict (HTTP 502) gets the bounded budget only`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf()) // always fails
        val hooks = RecordingHooks()

        loop(transport).run("s1", "st1", "HTTP 502", hooks)

        // Bounded by the SAME core budget as transport failures — not endless.
        assertEquals(8, transport.attachedStreamIds.size)
        assertEquals("reconnect budget exhausted after 8 attempts", hooks.giveUpReason)
    }

    @Test
    fun `resume cursor is read fresh on every attach attempt`() = runBlocking {
        // The host advances the shared cursor from `onEventId` frames; the loop must read
        // it fresh on EVERY attempt (cursorProvider), so a partially replayed journal is
        // never re-delivered from the beginning.
        var cursor: String? = "st1:10"
        val seen = mutableListOf<String?>()
        val transport = object : TvRecoveryTransport {
            override suspend fun fetchSessionDetail(sessionId: String) = liveDetail()
            override suspend fun attachStream(
                streamId: String,
                lastEventId: String?,
                onEvent: (ChatEvent) -> Unit,
            ): TvAttachOutcome {
                seen += lastEventId
                return if (seen.size == 1) {
                    onEvent(ChatEvent.Chunk("хвост")) // a frame arrived before the drop
                    cursor = "st1:42" // host advanced the cursor from its onEventId
                    TvAttachOutcome.Lost("Сетевая ошибка")
                } else {
                    onEvent(ChatEvent.Done)
                    TvAttachOutcome.Settled
                }
            }
        }

        loop(transport, cursorProvider = { cursor }).run("s1", "st1", "Сетевая ошибка", RecordingHooks())

        assertEquals(listOf("st1:10", "st1:42"), seen)
    }

    @Test
    fun `unreachable detail without a known stream id stays bounded and gives up honestly`() = runBlocking {
        // Server fully unreachable (detail fetch fails) and no stream id was ever seen:
        // there is nothing to attach to and nothing to reload FROM — the loop must stay
        // inside the core budget and end in a give-up, never in an endless poll.
        val transport = FakeTransport(detail = null, mutableListOf())
        val hooks = RecordingHooks()

        loop(transport).run("s1", null, "Сетевая ошибка", hooks)

        assertEquals(0, transport.attachedStreamIds.size)
        assertEquals(0, hooks.reloadCalls)
        assertEquals(8, transport.detailCalls) // one probe per bounded attempt
        assertEquals("reconnect budget exhausted after 8 attempts", hooks.giveUpReason)
    }

    @Test
    fun `a healthy stream resets the budget for the next outage`() = runBlocking {
        val coordinator = deterministicCoordinator()
        // Transport whose attaches stream journalled chunks (the host advances the
        // resume cursor -> the loop sees progress -> `onStreamHealthy` resets the streak);
        // the first attach then drops, the retry settles. Ids come from a shared sequence
        // so every attach proves fresh progress.
        var idSeq = 100
        fun idEmittingTransport(): TvRecoveryTransport {
            var calls = 0
            return object : TvRecoveryTransport {
                override suspend fun fetchSessionDetail(sessionId: String) = liveDetail()
                override suspend fun attachStream(
                    streamId: String,
                    lastEventId: String?,
                    onEvent: (ChatEvent) -> Unit,
                ): TvAttachOutcome {
                    calls++
                    return if (calls == 1) {
                        onEvent(ChatEvent.Chunk("частичный ответ", eventId = "$streamId:${idSeq++}"))
                        onEvent(ChatEvent.Error("Сетевая ошибка"))
                        TvAttachOutcome.Lost("Сетевая ошибка")
                    } else {
                        onEvent(ChatEvent.StreamStarted(streamId))
                        onEvent(ChatEvent.Chunk("продолжение", eventId = "$streamId:${idSeq++}"))
                        onEvent(ChatEvent.Done)
                        TvAttachOutcome.Settled
                    }
                }
            }
        }
        // Host-side cursor advanced by journalled chunks, fed back via cursorProvider.
        var cursor: String? = null
        val cursorHooks = object : RecordingHooks() {
            override fun onStreamEvent(event: ChatEvent) {
                super.onStreamEvent(event)
                if (event is ChatEvent.Chunk && event.eventId != null) cursor = event.eventId
            }
        }
        val sleeper1 = RecordingSleeper()
        loop(idEmittingTransport(), sleeper = sleeper1.fn, coordinator = coordinator, cursorProvider = { cursor })
            .run("s1", "st1", "Сетевая ошибка", cursorHooks)
        assertNotNull(cursor) // journalled frames advanced the host cursor

        // Second outage on the SAME coordinator: without the healthy-stream reset the
        // ladder would continue at 1000/2000 ms; with it, both attempts are back at 500.
        val sleeper2 = RecordingSleeper()
        loop(idEmittingTransport(), sleeper = sleeper2.fn, coordinator = coordinator, cursorProvider = { cursor })
            .run("s1", "st1", "Сетевая ошибка", cursorHooks)

        // Attempt 1: decide -> 500 ms -> attach proves alive (cursor moved) -> reset.
        // Attempt 2: decide starts the ladder over -> 500 ms again -> settles.
        assertEquals(listOf(500L, 500L), sleeper1.requested)
        assertEquals(listOf(500L, 500L), sleeper2.requested)
    }

    @Test
    fun `cancelling the recovery job stops the loop at the next suspension point`() = runBlocking {
        val transport = FakeTransport(liveDetail(), mutableListOf()) // always fails
        val sleeper = CompletableDeferred<Unit>() // backoff never returns until the test says so
        val hooks = RecordingHooks()
        val job = async {
            loop(transport, sleeper = { sleeper.await() }).run("s1", "st1", "Сетевая ошибка", hooks)
        }
        // The loop published its attempt status and is now stuck inside the sleeper —
        // no attach has opened yet.
        repeat(500) { if (hooks.statuses.isEmpty()) yield() }
        assertTrue(hooks.statuses.isNotEmpty())
        assertEquals(0, transport.attachedStreamIds.size)
        // The loop is stuck in the first backoff — the host's cancel (navigation/stop/BACK)
        // must tear it down without ever reaching another attach.
        job.cancel()
        sleeper.complete(Unit)
        job.join()
        assertTrue(transport.attachedStreamIds.size <= 1)
        assertNull(hooks.giveUpReason) // cancelled — not a give-up
    }
}
