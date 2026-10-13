package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.AutonomyToggleResult
import ru.mars.network.repository.SessionAutonomyRepository

/**
 * Request-level contract tests for the session-autonomy endpoints, replayed with MockWebServer
 * against the bodies hermes-webui `api/routes.py` returns. The legacy wire names (path,
 * `yolo_enabled` field) are asserted VERBATIM here on purpose: they pin the server contract that
 * the isolated [ru.mars.network.repository.SessionModeWire] adapter maps to the neutral domain
 * language. Mirrors the web client behaviour (static/commands.js session-mode command).
 */
class SessionAutonomyRepositoryTest {

    private val repository = SessionAutonomyRepository()

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    // --- GET state -------------------------------------------------------------------------------

    @Test
    fun fetchStateSendsGetWithSessionQueryAndBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"yolo_enabled":true}"""))
            val result = repository.fetchState(server.url("/").toString(), credential(), "sess-1")

            assertEquals(AutonomyLookup.State(true), result)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/session/yolo?session_id=sess-1", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun fetchStateParsesDisabledFlag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"yolo_enabled":false}"""))
            assertEquals(
                AutonomyLookup.State(false),
                repository.fetchState(server.url("/").toString(), credential(), "sess-1"),
            )
        }
    }

    @Test
    fun fetchStateNon2xxIsFailureWithServerMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Missing session_id"}"""))
            val result = repository.fetchState(server.url("/").toString(), credential(), "sess-1")
            assertEquals(AutonomyLookup.Failed("Missing session_id"), result)
        }
    }

    @Test
    fun fetchStateTransportErrorIsFailure() {
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            server.shutdown()
            val result = repository.fetchState(url, credential(), "sess-1")
            assertTrue(result is AutonomyLookup.Failed)
        }
    }

    @Test
    fun blankSessionIdIsRejectedLocallyWithoutRequest() {
        MockWebServer().use { server ->
            assertTrue(repository.fetchState(server.url("/").toString(), credential(), " ") is AutonomyLookup.Failed)
            assertEquals(0, server.requestCount)
        }
    }

    // --- POST toggle ------------------------------------------------------------------------------

    @Test
    fun setEnabledPostsSessionIdAndEnabledWithBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"yolo_enabled":true}"""))
            val result = repository.setEnabled(server.url("/").toString(), credential(), "sess-1", true)

            assertEquals(AutonomyToggleResult.Applied(true), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/yolo", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue(body.contains("\"enabled\":true"))
        }
    }

    @Test
    fun disableSendsEnabledFalseAndAppliesSettledState() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"yolo_enabled":false}"""))
            val result = repository.setEnabled(server.url("/").toString(), credential(), "sess-1", false)

            assertEquals(AutonomyToggleResult.Applied(false), result)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"enabled\":false"))
        }
    }

    @Test
    fun failedEnableKeepsSettledFlagFromErrorBody() {
        // Web parity: the server may fail the pending relay (409 gateway_run_unavailable) while
        // still committing the autonomy flag — the settled state must survive as knownState.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"ok":false,"error":"gateway relay unavailable","code":"gateway_run_unavailable","yolo_enabled":true}""")
            )
            val result = repository.setEnabled(server.url("/").toString(), credential(), "sess-1", true)

            assertEquals(
                AutonomyToggleResult.Failed("gateway relay unavailable", knownState = true),
                result,
            )
        }
    }

    @Test
    fun okFalseBodyWithoutSettledFlagIsPlainFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":false,"error":"YOLO transition already running"}"""))
            val result = repository.setEnabled(server.url("/").toString(), credential(), "sess-1", true)

            assertEquals(AutonomyToggleResult.Failed("YOLO transition already running", knownState = null), result)
        }
    }

    @Test
    fun setEnabledTransportErrorIsFailure() {
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            server.shutdown()
            val result = repository.setEnabled(url, credential(), "sess-1", true)
            assertTrue(result is AutonomyToggleResult.Failed && result.knownState == null)
        }
    }

    @Test
    fun sessionIdIsJsonEscaped() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"yolo_enabled":true}"""))
            repository.setEnabled(server.url("/").toString(), credential(), "se\"ss", true)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"session_id\":\"se\\\"ss\""))
        }
    }
}
