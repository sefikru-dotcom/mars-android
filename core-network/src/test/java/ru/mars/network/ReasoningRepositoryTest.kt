package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ReasoningStatus
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult

/**
 * Request-level contract tests for the reasoning endpoints of hermes-webui
 * (`GET /api/reasoning`, `POST /api/reasoning`), replayed with MockWebServer against
 * payloads captured live from the production WebUI (api/routes.py `_reasoning_status_payload`).
 *
 * Live capture (2026-10-08, pin set extended with the production `agent.reasoning_overrides`
 * entry for the active model, read 2026-10-11):
 * `{"show_reasoning":true,"reasoning_effort":"high","source":"default",
 *   "supported_efforts":["high"],"supports_reasoning_effort":true,
 *   "supports_thinking_toggle":true,
 *   "model_overrides":{"@custom:plusvibe-api:gpt-6-luna":"high","@custom:plusvibe-api:qwen/qwen3.8-max":"high"}}`
 */
class ReasoningRepositoryTest {

    private val repository = ReasoningRepository()

    private val livePayload = """
        {
          "show_reasoning": true,
          "reasoning_effort": "high",
          "source": "default",
          "supported_efforts": ["high"],
          "supports_reasoning_effort": true,
          "supports_thinking_toggle": true,
          "model_overrides": {"@custom:plusvibe-api:gpt-6-luna": "high", "@custom:plusvibe-api:qwen/qwen3.8-max": "high"}
        }
    """.trimIndent()

    // --- GET /api/reasoning -----------------------------------------------------------------------

    @Test
    fun fetchStatusParsesLivePayloadWithBearerAndContextQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            val result = repository.fetchStatus(
                server.url("/").toString(), credential(),
                model = "qwen/qwen3.8-max", provider = "custom:plusvibe-api", sessionId = "sess-1",
            )

            assertEquals(
                ReasoningStatusResult.Status(
                    ReasoningStatus(
                        showReasoning = true,
                        reasoningEffort = "high",
                        source = "default",
                        supportedEfforts = listOf("high"),
                        supportsReasoningEffort = true,
                        supportsThinkingToggle = true,
                    )
                ),
                result,
            )
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val path = request.path!!
            assertTrue("path must carry model: $path", path.startsWith("/api/reasoning?"))
            assertTrue(path.contains("model=qwen%2Fqwen3.8-max") || path.contains("model=qwen/qwen3.8-max"))
            assertTrue(path.contains("provider=custom%3Aplusvibe-api") || path.contains("provider=custom:plusvibe-api"))
            assertTrue(path.contains("session_id=sess-1"))
        }
    }

    @Test
    fun fetchStatusWithoutContextOmitsQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            repository.fetchStatus(server.url("/").toString(), credential())

            assertEquals("/api/reasoning", server.takeRequest().path)
        }
    }

    @Test
    fun fetchStatusToleratesThinkingToggleOnlyModel() {
        MockWebServer().use { server ->
            // ZAI GLM gate: supports_thinking_toggle=true but NO effort ladder.
            server.enqueue(
                MockResponse().setBody(
                    """{"show_reasoning":true,"reasoning_effort":null,"source":"default","supported_efforts":[],"supports_reasoning_effort":false,"supports_thinking_toggle":true,"model_overrides":{}}"""
                )
            )
            val result = repository.fetchStatus(server.url("/").toString(), credential())

            assertEquals(
                ReasoningStatusResult.Status(
                    ReasoningStatus(
                        showReasoning = true,
                        reasoningEffort = null,
                        source = "default",
                        supportedEfforts = emptyList(),
                        supportsReasoningEffort = false,
                        supportsThinkingToggle = true,
                    )
                ),
                result,
            )
        }
    }

    @Test
    fun fetchStatusMapsHttpErrorToFailedWithServerMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"Unknown profile"}"""))
            val result = repository.fetchStatus(server.url("/").toString(), credential())

            assertEquals(ReasoningStatusResult.Failed("Unknown profile"), result)
        }
    }

    @Test
    fun fetchStatusMapsHttpErrorWithoutBodyToFailedWithStatusLine() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val result = repository.fetchStatus(server.url("/").toString(), credential())

            val failed = result as ReasoningStatusResult.Failed
            assertTrue(failed.message.contains("HTTP 401"))
        }
    }

    @Test
    fun fetchStatusMapsNetworkErrorToFailedAndNeverLeaksToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val result = repository.fetchStatus(server.url("/").toString(), credential())

            val failed = result as ReasoningStatusResult.Failed
            assertTrue(failed.message.isNotEmpty())
            assertTrue(!failed.message.contains("native-token"))
        }
    }

    // --- POST /api/reasoning ----------------------------------------------------------------------

    /**
     * Per-model pin parity (static/ui.js composer chip): the live config pins
     * `@custom:plusvibe-api:qwen/qwen3.8-max`, so setEffort must preflight the GET status,
     * detect the pin and POST `{scope:"model", model:<pinned key verbatim>}` — a global write
     * would be silently overridden by the pin at runtime (session > model > global).
     */
    @Test
    fun setEffortTargetsExistingPinWithScopeModelWhenModelIsPinned() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload)) // preflight GET status
            server.enqueue(MockResponse().setBody(livePayload)) // POST answer
            val result = repository.setEffort(
                server.url("/").toString(), credential(), effort = "low",
                model = "qwen/qwen3.8-max", provider = "custom:plusvibe-api", sessionId = "sess-1",
            )

            assertTrue(result is ReasoningStatusResult.Status)
            val preflight = server.takeRequest()
            assertEquals("GET", preflight.method)
            assertTrue(preflight.path!!.startsWith("/api/reasoning?"))
            assertEquals("Bearer native-token", preflight.getHeader("Authorization"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/reasoning", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"effort\":\"low\""))
            // The EXISTING override key verbatim — no near-duplicate spelling is created.
            assertTrue(body.contains("\"model\":\"@custom:plusvibe-api:qwen/qwen3.8-max\""))
            assertTrue(body.contains("\"scope\":\"model\""))
            assertTrue(body.contains("\"provider\":\"custom:plusvibe-api\""))
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
        }
    }

    /** Default (effort:null → `""`) on a pinned model clears THAT pin (scope=model), web parity. */
    @Test
    fun setEffortDefaultClearsPinViaScopeModel() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            server.enqueue(MockResponse().setBody(livePayload))
            repository.setEffort(
                server.url("/").toString(), credential(), effort = null,
                model = "qwen/qwen3.8-max", provider = "custom:plusvibe-api",
            )

            server.takeRequest() // preflight
            val body = server.takeRequest().body.readUtf8()
            assertTrue("body=$body", body.contains("\"effort\":\"\""))
            assertTrue("body=$body", body.contains("\"scope\":\"model\""))
            assertTrue(body.contains("\"model\":\"@custom:plusvibe-api:qwen/qwen3.8-max\""))
        }
    }

    /** Spelling tolerance: a bare-tail model id matches the full-form `@custom:` pin key. */
    @Test
    fun setEffortMatchesPinByBareModelTail() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            server.enqueue(MockResponse().setBody(livePayload))
            repository.setEffort(server.url("/").toString(), credential(), effort = "high", model = "qwen3.8-max")

            server.takeRequest()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"scope\":\"model\""))
            assertTrue(body.contains("\"model\":\"@custom:plusvibe-api:qwen/qwen3.8-max\""))
        }
    }

    /** Unpinned model: legacy global body, no `scope` key (wire shape unchanged). */
    @Test
    fun setEffortWithoutPinPostsLegacyGlobalBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"show_reasoning":true,"reasoning_effort":"high","source":"default","supported_efforts":["high"],"supports_reasoning_effort":true,"supports_thinking_toggle":true,"model_overrides":{}}"""))
            server.enqueue(MockResponse().setBody(livePayload))
            val result = repository.setEffort(
                server.url("/").toString(), credential(), effort = "low",
                model = "qwen/qwen3.8-max", provider = "custom:plusvibe-api", sessionId = "sess-1",
            )

            assertTrue(result is ReasoningStatusResult.Status)
            assertEquals("GET", server.takeRequest().method)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"effort\":\"low\""))
            assertTrue(body.contains("\"model\":\"qwen/qwen3.8-max\""))
            assertTrue(body.contains("\"provider\":\"custom:plusvibe-api\""))
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue("unpinned model must not carry scope: $body", !body.contains("scope"))
        }
    }

    /** A failed preflight (HTTP 500) never blocks the user action: legacy POST still fires. */
    @Test
    fun setEffortPreflightHttpErrorFallsBackToLegacyPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            server.enqueue(MockResponse().setBody(livePayload))
            val result = repository.setEffort(
                server.url("/").toString(), credential(), effort = "low", model = "qwen/qwen3.8-max",
            )

            assertTrue(result is ReasoningStatusResult.Status)
            assertEquals("GET", server.takeRequest().method)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"effort\":\"low\""))
            assertTrue(body.contains("\"model\":\"qwen/qwen3.8-max\""))
            assertTrue(!body.contains("scope"))
        }
    }

    /** A transport-dead preflight socket degrades to the legacy POST on the retry connection. */
    @Test
    fun setEffortPreflightNetworkErrorFallsBackToLegacyPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(livePayload))
            val result = repository.setEffort(
                server.url("/").toString(), credential(), effort = "high", model = "gpt-6-luna",
            )

            assertTrue(result is ReasoningStatusResult.Status)
            assertEquals("GET", server.takeRequest().method)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"effort\":\"high\""))
            assertTrue(!body.contains("scope"))
        }
    }

    @Test
    fun setEffortWithoutModelSkipsPreflightAndPostsLegacyBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            val result = repository.setEffort(
                server.url("/").toString(), credential(), effort = "low",
                provider = "custom:plusvibe-api", sessionId = "sess-1",
            )

            assertTrue(result is ReasoningStatusResult.Status)
            // Exactly ONE request: without a model there is nothing to pin-check.
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/reasoning", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"effort\":\"low\""))
            assertTrue(body.contains("\"provider\":\"custom:plusvibe-api\""))
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue(!body.contains("\"model\""))
            assertTrue(!body.contains("scope"))
            assertEquals(0, server.requestCount - 1)
        }
    }

    @Test
    fun setEffortNullSendsEmptyStringDefaultLikeWebuiChip() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(livePayload))
            repository.setEffort(server.url("/").toString(), credential(), effort = null)

            val body = server.takeRequest().body.readUtf8()
            // WebUI parity (static/ui.js reasoning-option handler): the Default option
            // posts effort:'' which clears the override; the key must be present.
            assertTrue("default must reach the wire as \"effort\":\"\": $body", body.contains("\"effort\":\"\""))
            assertTrue(!body.contains("model"))
        }
    }

    @Test
    fun setEffortMapsHttp400ToFailedWithServerMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"effort must be a non-empty string"}"""))
            val result = repository.setEffort(server.url("/").toString(), credential(), effort = "ultra")

            assertEquals(ReasoningStatusResult.Failed("effort must be a non-empty string"), result)
        }
    }

    @Test
    fun setEffortMapsNetworkErrorToFailed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val result = repository.setEffort(server.url("/").toString(), credential(), effort = "high")

            assertTrue(result is ReasoningStatusResult.Failed)
        }
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token",
        tokenType = "Bearer",
        expiresIn = 3600,
        deviceId = "dev-1",
        scopes = listOf("chat"),
    )
}
