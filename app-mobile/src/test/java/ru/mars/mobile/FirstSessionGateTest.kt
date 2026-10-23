package ru.mars.mobile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator

/**
 * [FirstSessionGate] — сериализация мятки первой сессии (R5). Создатель — записывающий
 * фейк, поэтому проверяется наблюдаемое поведение: сколько сессий создано, какой id
 * привязан к экрану и что отказ не «залипает».
 */
class FirstSessionGateTest {
    private val credential = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)

    /**
     * [latencyMs] — задержка создания: без неё параллельные вызовы не накладываются и
     * гонка не воспроизводится. [outcome] получает номер вызова (1-based).
     */
    private class FakeCreator(
        private val latencyMs: Long = 0,
        private val outcome: (Int) -> CreateSessionOutcome = {
            CreateSessionOutcome.Success(SessionDetail("minted-$it"))
        },
    ) : SessionCreator {
        var calls = 0

        override suspend fun createSession(
            baseUrl: String,
            credential: ConsumedDeviceCredential,
            args: Map<String, Any?>,
        ): CreateSessionOutcome {
            calls++
            val call = calls
            if (latencyMs > 0) delay(latencyMs)
            return outcome(call)
        }
    }

    private fun gate(creator: SessionCreator) =
        FirstSessionGate(FirstSessionFlow(creator, "https://base"))

    @Test
    fun nullSessionIdMintsSessionAndBindsIt() = runBlocking {
        val creator = FakeCreator()
        var bound: String? = null
        val result = gate(creator).ensure(null, credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Ready("minted-1"), result)
        assertEquals("minted-1", bound)
        assertEquals(1, creator.calls)
    }

    @Test
    fun firstTurnReusesSessionMintedByTheAttachment() = runBlocking {
        // «Скрепка» создала сессию; первый ход приходит с null-адресатом и обязан
        // продолжить ЕЁ, а не создать вторую (иначе вложение остаётся сиротой).
        val creator = FakeCreator()
        val gate = gate(creator)
        var bound: String? = null
        gate.ensure(null, credential, { bound }, { bound = it })
        val turn = gate.ensure(null, credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Ready("minted-1"), turn)
        assertEquals(1, creator.calls)
        assertEquals("minted-1", bound)
    }

    @Test
    fun concurrentEnsureCallsCreateExactlyOneSession() = runBlocking {
        // Двойной тап по «скрепке» или «скрепка + отправка»: обе корутины видят null,
        // но мятка одна — вторая ждёт лок и подхватывает привязанный id.
        val creator = FakeCreator(latencyMs = 40)
        val gate = gate(creator)
        var bound: String? = null
        val results = listOf(
            async { gate.ensure(null, credential, { bound }, { bound = it }) },
            async { gate.ensure(null, credential, { bound }, { bound = it }) },
        ).awaitAll()
        assertEquals(1, creator.calls)
        assertEquals(List(2) { EnsureSessionResult.Ready("minted-1") }, results)
        assertEquals("minted-1", bound)
    }

    @Test
    fun failedMintIsNotBoundAndTheNextAttemptCanRetry() = runBlocking {
        // Отказ не должен оставлять ни «призрачного» id, ни залипшего лока: повтор
        // пользователя снова идёт в создание сессии.
        val creator = FakeCreator(outcome = { call ->
            if (call == 1) CreateSessionOutcome.Failure("HTTP 500")
            else CreateSessionOutcome.Success(SessionDetail("minted-2"))
        })
        val gate = gate(creator)
        var bound: String? = null
        val first = gate.ensure(null, credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Failed("HTTP 500"), first)
        assertNull(bound)
        val second = gate.ensure(null, credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Ready("minted-2"), second)
        assertEquals("minted-2", bound)
        assertEquals(2, creator.calls)
    }

    @Test
    fun explicitRequestedSessionWinsOverBoundOne() = runBlocking {
        // Сообщение очереди своего диалога адресовано конкретной сессии: привязка экрана
        // не должна перехватывать его в текущий диалог.
        val creator = FakeCreator()
        var bound: String? = "bound-1"
        val result = gate(creator).ensure("queued-2", credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Ready("queued-2"), result)
        assertEquals("queued-2", bound)
        assertEquals(0, creator.calls)
    }

    @Test
    fun blankRequestedSessionFallsBackToTheBoundOne() = runBlocking {
        val creator = FakeCreator()
        var bound: String? = "bound-1"
        val result = gate(creator).ensure("  ", credential, { bound }, { bound = it })
        assertEquals(EnsureSessionResult.Ready("bound-1"), result)
        assertEquals(0, creator.calls)
        assertTrue(bound == "bound-1")
    }
}
