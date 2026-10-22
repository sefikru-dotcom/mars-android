package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.VoiceCloseResult
import ru.mars.network.model.VoiceGatewayError
import ru.mars.network.model.VoiceInterruptResult
import ru.mars.network.model.VoiceResult
import ru.mars.network.model.VoiceSession

/**
 * Жизненный цикл серверного голосового канала (`voice_id`) — без сети, на фейковом шлюзе.
 *
 * Проверяется то, что нельзя увидеть на устройстве до живого прогона и что легко сломать
 * рефакторингом:
 * - `voice_id` выдаёт ТОЛЬКО сервер: придуманных идентификаторов в запросах не бывает;
 * - канал создаётся один раз на диалог и переиспользуется (иначе каждый barge — новый поток);
 * - нет сессии/токена — нет и запроса (перебивание остаётся локальным, без «тихого» 400);
 * - 404 снимает привязку, следующее перебивание открывает новый канал;
 * - `close()` идемпотентен и закрывает ровно открытый канал;
 * - второго канала транскрипта нет: у класса нет ни потока событий, ни подписки.
 */
class VoiceInterruptChannelTest {

    private val credential = ConsumedDeviceCredential(
        accessToken = "test-token",
        tokenType = "Bearer",
        deviceId = "device-1",
        scopes = listOf("chat"),
        expiresIn = 3600,
    )

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `voice id is created once per dialog and reused`() = runBlocking {
        val gateway = FakeGateway()
        val channel = channel(gateway)

        assertTrue(channel.interruptNow("session-1"))
        assertTrue(channel.interruptNow("session-1"))

        assertEquals(1, gateway.createCalls)
        assertEquals(2, gateway.interruptCalls)
        // Оба перебивания — по серверному id, а не по выдуманному «session-1».
        assertEquals(listOf("voice-1", "voice-1"), gateway.interruptVoiceIds)
        assertEquals(VoiceInterruptChannel.Binding("voice-1", "session-1"), channel.binding)
        assertEquals(1, channel.createdCount)
        assertEquals(2, channel.interruptedCount)
    }

    @Test
    fun `no session means no network call at all`() = runBlocking {
        val gateway = FakeGateway()
        val channel = channel(gateway)

        channel.interrupt(null)
        channel.interrupt("   ")
        assertFalse(channel.interruptNow(""))

        assertEquals(0, gateway.createCalls)
        assertEquals(0, gateway.interruptCalls)
        assertNull(channel.binding)
        assertEquals(3, channel.failedCount)
    }

    @Test
    fun `missing credential does not create a channel`() = runBlocking {
        val gateway = FakeGateway()
        val channel = channel(gateway, credentialProvider = { null })

        assertFalse(channel.interruptNow("session-1"))

        assertEquals(0, gateway.createCalls)
        assertEquals(0, gateway.interruptCalls)
        assertNull(channel.binding)
        assertEquals(1, channel.failedCount)
    }

    @Test
    fun `failed session creation never falls back to an invented id`() = runBlocking {
        val gateway = FakeGateway(
            createResult = { VoiceResult.Failure(VoiceGatewayError.Http(503, "too many streams")) },
        )
        val channel = channel(gateway)

        assertFalse(channel.interruptNow("session-1"))

        assertEquals(0, gateway.interruptCalls)
        assertNull(channel.binding)
        assertEquals(0, channel.createdCount)
        assertEquals(1, channel.failedCount)
    }

    @Test
    fun `blank voice id from the server is not used`() = runBlocking {
        val gateway = FakeGateway(
            createResult = { VoiceResult.Success(VoiceSession(voiceId = "  ", sessionId = "session-1", expiresInSeconds = 300)) },
        )
        val channel = channel(gateway)

        assertFalse(channel.interruptNow("session-1"))

        assertEquals(0, gateway.interruptCalls)
        assertNull(channel.binding)
        assertEquals(0, channel.createdCount)
    }

    @Test
    fun `interrupt on 404 drops the binding and the next call opens a new channel`() = runBlocking {
        val gateway = FakeGateway(
            interruptResult = { VoiceResult.Failure(VoiceGatewayError.Http(404, "unknown voice")) },
            nextVoiceIds = listOf("voice-1", "voice-2"),
        )
        val channel = channel(gateway)

        assertFalse(channel.interruptNow("session-1"))
        assertNull(channel.binding)

        gateway.interruptResult = { VoiceResult.Success(VoiceInterruptResult("voice-2", interrupted = true, alreadyInterrupted = false)) }
        assertTrue(channel.interruptNow("session-1"))

        assertEquals(2, gateway.createCalls)
        assertEquals(listOf("voice-1", "voice-2"), gateway.interruptVoiceIds)
        assertEquals(2, channel.createdCount)
    }

    @Test
    fun `rate limit keeps the binding alive`() = runBlocking {
        val gateway = FakeGateway(
            interruptResult = { VoiceResult.Failure(VoiceGatewayError.Http(429, "slow down")) },
        )
        val channel = channel(gateway)

        assertFalse(channel.interruptNow("session-1"))

        // 429 — не повод пересоздавать канал: voice_id на сервере жив.
        assertEquals(VoiceInterruptChannel.Binding("voice-1", "session-1"), channel.binding)
        assertEquals(1, channel.failedCount)
    }

    @Test
    fun `close is idempotent and closes exactly the open channel`() = runBlocking {
        val gateway = FakeGateway()
        val channel = channel(gateway)
        channel.interruptNow("session-1")

        channel.close()
        channel.close()

        assertEquals(1, gateway.closeCalls)
        assertEquals(listOf("voice-1"), gateway.closedVoiceIds)
        assertNull(channel.binding)
        assertEquals(1, channel.closedCount)
    }

    @Test
    fun `close without a channel does nothing`() {
        val gateway = FakeGateway()
        val channel = channel(gateway)

        channel.close()

        assertEquals(0, gateway.closeCalls)
        assertEquals(0, channel.closedCount)
    }

    @Test
    fun `switching dialog closes the old channel before a new one is created`() = runBlocking {
        val gateway = FakeGateway(nextVoiceIds = listOf("voice-1", "voice-2"))
        val channel = channel(gateway)
        channel.interruptNow("session-1")

        channel.bindSession("session-2")

        assertEquals(1, gateway.closeCalls)
        assertEquals(listOf("voice-1"), gateway.closedVoiceIds)
        assertNull(channel.binding)

        channel.interruptNow("session-2")
        assertEquals(listOf("voice-1", "voice-2"), gateway.interruptVoiceIds)
        assertEquals(VoiceInterruptChannel.Binding("voice-2", "session-2"), channel.binding)
    }

    @Test
    fun `binding the same dialog keeps the channel`() = runBlocking {
        val gateway = FakeGateway()
        val channel = channel(gateway)
        channel.interruptNow("session-1")

        channel.bindSession("session-1")

        assertEquals(0, gateway.closeCalls)
        assertEquals(VoiceInterruptChannel.Binding("voice-1", "session-1"), channel.binding)
    }

    @Test
    fun `server side debounce is counted as already interrupted, not as an error`() = runBlocking {
        val gateway = FakeGateway(
            interruptResult = { VoiceResult.Success(VoiceInterruptResult("voice-1", interrupted = false, alreadyInterrupted = true)) },
        )
        val channel = channel(gateway)

        assertTrue(channel.interruptNow("session-1"))

        assertEquals(1, channel.alreadyInterruptedCount)
        assertEquals(0, channel.interruptedCount)
        assertEquals(0, channel.failedCount)
    }

    @Test
    fun `channel exposes no event stream - transcript stays on the chat SSE`() {
        // Второй поток событий дал бы два источника правды об одном ходе (и 409 на сервере).
        val suspicious = VoiceInterruptChannel::class.java.methods
            .map { it.name.lowercase() }
            .filter { name -> name.contains("stream") || name.contains("events") || name.contains("subscribe") }
        assertEquals(emptyList<String>(), suspicious)
    }

    private fun channel(
        gateway: VoiceControlGateway,
        credentialProvider: () -> ConsumedDeviceCredential? = { credential },
    ) = VoiceInterruptChannel(
        gateway = gateway,
        credentialProvider = credentialProvider,
        scope = scope,
        baseUrl = "https://example.invalid",
    )

    /** Фейковый шлюз: считает вызовы и отдаёт заранее заданные исходы. */
    private class FakeGateway(
        var createResult: (() -> VoiceResult<VoiceSession>)? = null,
        var interruptResult: (() -> VoiceResult<VoiceInterruptResult>)? = null,
        var closeResult: (() -> VoiceResult<VoiceCloseResult>)? = null,
        private val nextVoiceIds: List<String> = listOf("voice-1"),
    ) : VoiceControlGateway {

        var createCalls = 0
            private set
        var interruptCalls = 0
            private set
        var closeCalls = 0
            private set
        val interruptVoiceIds: MutableList<String> = ArrayList()
        val closedVoiceIds: MutableList<String> = ArrayList()

        override suspend fun createSession(
            sessionId: String,
            credential: ConsumedDeviceCredential,
            baseUrl: String,
        ): VoiceResult<VoiceSession> {
            val index = createCalls
            createCalls++
            createResult?.let { return it() }
            val voiceId = nextVoiceIds.getOrElse(index) { nextVoiceIds.last() }
            return VoiceResult.Success(VoiceSession(voiceId = voiceId, sessionId = sessionId, expiresInSeconds = 300))
        }

        override suspend fun interrupt(
            voiceId: String,
            sessionId: String,
            credential: ConsumedDeviceCredential,
            baseUrl: String,
        ): VoiceResult<VoiceInterruptResult> {
            interruptCalls++
            interruptVoiceIds += voiceId
            interruptResult?.let { return it() }
            return VoiceResult.Success(VoiceInterruptResult(voiceId, interrupted = true, alreadyInterrupted = false))
        }

        override suspend fun close(
            voiceId: String,
            sessionId: String,
            credential: ConsumedDeviceCredential,
            baseUrl: String,
        ): VoiceResult<VoiceCloseResult> {
            closeCalls++
            closedVoiceIds += voiceId
            closeResult?.let { return it() }
            return VoiceResult.Success(VoiceCloseResult(voiceId, closed = true))
        }
    }
}
