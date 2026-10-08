package ru.mars.tv

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

/**
 * Contract tests for [RepositoryTvChatControlGateway] — the production TV turn-stop seam
 * (GET /api/chat/cancel via the REAL core-network [ChatRepository]). MockWebServer pins:
 * a server-confirmed cancel maps to true with the right path + auth header, HTTP failure
 * maps to false, and a missing credential maps to false WITHOUT any HTTP call. The
 * gateway must never throw — every outcome lands as an explicit Boolean.
 */
class RepositoryTvChatControlGatewayTest {
    private val credential = ConsumedDeviceCredential("tok", "Bearer", "d", emptyList(), 1)

    private fun gatewayFor(server: MockWebServer, cred: ConsumedDeviceCredential? = credential) =
        RepositoryTvChatControlGateway(ChatRepository(), server.url("/").toString().removeSuffix("/")) { cred }

    @Test fun cancelHitsChatCancelWithStreamIdAndAuthHeader() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"cancelled":["st-1"]}"""))
            assertTrue(gatewayFor(server).cancelStream("st-1"))
            val request = server.takeRequest()
            assertEquals("/api/chat/cancel?stream_id=st-1", request.path)
            assertEquals("Bearer tok", request.getHeader("Authorization"))
        }
    }

    @Test fun httpFailureMapsToFalse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            assertFalse(gatewayFor(server).cancelStream("st-1"))
        }
    }

    @Test fun missingCredentialMapsToFalseWithoutAnyHttpCall() {
        MockWebServer().use { server ->
            assertFalse(gatewayFor(server, cred = null).cancelStream("st-1"))
            assertEquals(0, server.requestCount)
        }
    }
}
