package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator

/**
 * Unit tests for [FirstSessionFlow] — the pre-send gate that guarantees chat/start is
 * never invoked with a null session id. The creator is a recording fake (clean seam).
 */
class FirstSessionFlowTest {
    private val credential = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)

    private class FakeCreator(private val outcome: CreateSessionOutcome) : SessionCreator {
        var calls = 0
        var lastBaseUrl: String? = null
        var lastCredential: ConsumedDeviceCredential? = null
        var lastArgs: Map<String, Any?>? = null
        override suspend fun createSession(
            baseUrl: String,
            credential: ConsumedDeviceCredential,
            args: Map<String, Any?>
        ): CreateSessionOutcome {
            calls++
            lastBaseUrl = baseUrl
            lastCredential = credential
            lastArgs = args
            return outcome
        }
    }

    @Test fun existingSessionIdIsReusedWithoutCreating() = runBlocking {
        val creator = FakeCreator(CreateSessionOutcome.Success(SessionDetail("new")))
        val result = FirstSessionFlow(creator, "https://base").ensureSession("bound-1", credential)
        assertEquals(EnsureSessionResult.Ready("bound-1"), result)
        assertEquals(0, creator.calls)
    }

    @Test fun nullSessionIdCreatesSessionAndBindsId() = runBlocking {
        val creator = FakeCreator(CreateSessionOutcome.Success(SessionDetail("minted-9")))
        val result = FirstSessionFlow(creator, "https://base").ensureSession(null, credential)
        assertEquals(EnsureSessionResult.Ready("minted-9"), result)
        assertEquals(1, creator.calls)
        assertEquals("https://base", creator.lastBaseUrl)
        assertEquals(credential, creator.lastCredential)
        assertEquals(emptyMap<String, Any?>(), creator.lastArgs)
    }

    @Test fun blankSessionIdIsTreatedAsMissing() = runBlocking {
        val creator = FakeCreator(CreateSessionOutcome.Success(SessionDetail("minted-2")))
        val result = FirstSessionFlow(creator, "https://base").ensureSession("  ", credential)
        assertEquals(EnsureSessionResult.Ready("minted-2"), result)
        assertEquals(1, creator.calls)
    }

    @Test fun creationFailureSurfacesReason() = runBlocking {
        val creator = FakeCreator(CreateSessionOutcome.Failure("HTTP 500"))
        val result = FirstSessionFlow(creator, "https://base").ensureSession(null, credential)
        assertTrue(result is EnsureSessionResult.Failed)
        assertEquals("HTTP 500", (result as EnsureSessionResult.Failed).message)
    }
}
