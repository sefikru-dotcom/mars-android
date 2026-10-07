package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.InteractiveRespondResult
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup

/**
 * Request-level contract tests against the live hermes-webui endpoints
 * (`POST /api/approval/respond`, `POST /api/clarify/respond`, `GET /api/{approval,clarify}/pending`),
 * replayed with MockWebServer. Bodies are matched against what static/messages.js sends.
 */
class InteractiveRequestsRepositoryTest {

    private val repository = InteractiveRequestsRepository()

    // --- approval respond ----------------------------------------------------------------------

    @Test
    fun approvalRespondSendsChoiceAndIdWithBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"choice":"once"}"""))
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "appr-9", command = "rm -rf /tmp/x", description = ""),
                ApprovalChoice.ONCE,
            )

            assertEquals(InteractiveRespondResult.Accepted(), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/approval/respond", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue(body.contains("\"choice\":\"once\""))
            assertTrue(body.contains("\"approval_id\":\"appr-9\""))
            assertFalse(body.contains("run_id"))
            assertFalse(body.contains("mirror_token"))
        }
    }

    @Test
    fun approvalForwardsGatewayTripleOnlyWhenComplete() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(
                    approvalId = "ga-1", command = "kubectl delete pod", description = "",
                    runId = "run-42", mirrorToken = "tok",
                ),
                ApprovalChoice.DENY,
            )
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"run_id\":\"run-42\""))
            assertTrue(body.contains("\"mirror_token\":\"tok\""))
            assertTrue(body.contains("\"choice\":\"deny\""))
        }
    }

    @Test
    fun approvalOmitsPartialGatewayTripleToAvoidServerRejection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "ga-2", command = "c", description = "", runId = "run-42"),
                ApprovalChoice.SESSION,
            )
            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("run_id"))
            assertFalse(body.contains("mirror_token"))
            assertTrue(body.contains("\"choice\":\"session\""))
        }
    }

    @Test
    fun approvalStaleClearedSurfacesQueueRefreshFlag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"stale_cleared":true}"""))
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "gone", command = "c", description = ""),
                ApprovalChoice.ONCE,
            )
            assertEquals(InteractiveRespondResult.Accepted(staleCleared = true), result)
        }
    }

    @Test
    fun approvalOkFalseIsRejectedWithServerError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":false,"error":"Invalid choice"}"""))
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "a", command = "c", description = ""),
                ApprovalChoice.ALWAYS,
            )
            val rejected = result as InteractiveRespondResult.Rejected
            assertEquals("Invalid choice", rejected.message)
            assertEquals(200, rejected.httpStatus)
        }
    }

    @Test
    fun approvalGatewayRelayFailureCarriesCode() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"ok":false,"error":"Gateway run unavailable","code":"gateway_run_unavailable"}""")
            )
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "a", command = "c", description = "", runId = "r", mirrorToken = "t"),
                ApprovalChoice.ONCE,
            )
            val rejected = result as InteractiveRespondResult.Rejected
            assertEquals(409, rejected.httpStatus)
            assertEquals("gateway_run_unavailable", rejected.code)
        }
    }

    @Test
    fun approval401SurfacesAsRejectedWithoutTokenInMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "a", command = "c", description = ""),
                ApprovalChoice.ONCE,
            )
            val rejected = result as InteractiveRespondResult.Rejected
            assertEquals(401, rejected.httpStatus)
            assertFalse(rejected.message.contains("native-token"))
        }
    }

    @Test
    fun approvalValidationErrorsAreLocalAndSendNothing() {
        MockWebServer().use { server ->
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "",
                ApprovalPayload(approvalId = "a", command = "c", description = ""),
                ApprovalChoice.ONCE,
            )
            val rejected = result as InteractiveRespondResult.Rejected
            assertEquals(0, rejected.httpStatus)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun approvalYoloFlagOnlyWhenRequested() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"yolo_enabled":true}"""))
            val result = repository.respondApproval(
                server.url("/").toString(), credential(), "sess-1",
                ApprovalPayload(approvalId = "a", command = "c", description = ""),
                ApprovalChoice.ONCE, yolo = true,
            )
            assertTrue(server.takeRequest().body.readUtf8().contains("\"yolo\":true"))
            assertEquals(InteractiveRespondResult.Accepted(yoloEnabled = true), result)
        }
    }

    // --- clarify respond -------------------------------------------------------------------------

    @Test
    fun clarifyRespondSendsResponseAndIdWithBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val result = repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = "cl-7", question = "Какой профиль?"),
                "  default  ",
            )

            assertEquals(InteractiveRespondResult.Accepted(), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/clarify/respond", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_id\":\"sess-1\""))
            assertTrue(body.contains("\"response\":\"default\""))
            assertTrue(body.contains("\"clarify_id\":\"cl-7\""))
        }
    }

    @Test
    fun clarifyWithoutKnownIdSendsEmptyClarifyId() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = null, question = "q"), "answer",
            )
            assertTrue(server.takeRequest().body.readUtf8().contains("\"clarify_id\":\"\""))
        }
    }

    @Test
    fun clarifyEscapesQuotesAndNewlinesInFreeText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = "cl-1", question = "q"),
                "строка \"в кавычках\"\nи перевод",
            )
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\\\"в кавычках\\\""))
            assertTrue(body.contains("\\n"))
        }
    }

    @Test
    fun clarifyStaleConflictIsTerminal() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"ok":false,"stale":true,"error":"Prompt expired"}""")
            )
            val result = repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = "cl-old", question = "q"), "late answer",
            )
            val stale = result as InteractiveRespondResult.Stale
            assertEquals("Prompt expired", stale.message)
        }
    }

    @Test
    fun clarifyBlankResponseIsRejectedLocally() {
        MockWebServer().use { server ->
            val result = repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = "cl-1", question = "q"), "   ",
            )
            assertTrue(result is InteractiveRespondResult.Rejected)
            assertEquals(0, (result as InteractiveRespondResult.Rejected).httpStatus)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun clarifyNetworkFailureIsNetworkError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
            val result = repository.respondClarify(
                server.url("/").toString(), credential(), "sess-1",
                ClarifyPayload(clarifyId = "cl-1", question = "q"), "answer",
            )
            assertTrue(result is InteractiveRespondResult.NetworkError)
            assertFalse((result as InteractiveRespondResult.NetworkError).message.contains("native-token"))
        }
    }

    // --- pending GETs ----------------------------------------------------------------------------

    @Test
    fun fetchPendingApprovalParsesHeadAndCount() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"pending":{"approval_id":"appr-1","command":"rm x","description":"",
                       "pattern_keys":["terminal:rm"],"allow_permanent":true,"allow_session":true,
                       "pending_count":1},"pending_count":4}"""
                )
            )
            val found = repository.fetchPendingApproval(server.url("/").toString(), credential(), "sess-1")
                as PendingApprovalLookup.Found
            assertEquals("appr-1", found.payload.approvalId)
            assertTrue(found.payload.allowPermanent)
            assertEquals(4, found.payload.pendingCount)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/approval/pending?session_id=sess-1", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun fetchPendingApprovalEmptyWhenNoHead() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pending":null,"pending_count":0}"""))
            assertEquals(
                PendingApprovalLookup.Empty,
                repository.fetchPendingApproval(server.url("/").toString(), credential(), "sess-1"),
            )
        }
    }

    @Test
    fun fetchPendingApprovalHttpFailurePropagates() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
            val failed = repository.fetchPendingApproval(server.url("/").toString(), credential(), "sess-1")
                as PendingApprovalLookup.Failed
            assertEquals("Unauthorized", failed.message)
        }
    }

    @Test
    fun fetchPendingClarifyParsesEntry() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"pending":{"clarify_id":"cl-3","question":"Какой профиль?",
                       "choices_offered":["default","office"],"session_id":"sess-1","timeout_seconds":120}}"""
                )
            )
            val found = repository.fetchPendingClarify(server.url("/").toString(), credential(), "sess-1")
                as PendingClarifyLookup.Found
            assertEquals("cl-3", found.payload.clarifyId)
            assertEquals(listOf("default", "office"), found.payload.choices)
            val request = server.takeRequest()
            assertEquals("/api/clarify/pending?session_id=sess-1", request.path)
        }
    }

    @Test
    fun fetchPendingClarifyEmptyAndFailed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pending":null}"""))
            assertEquals(
                PendingClarifyLookup.Empty,
                repository.fetchPendingClarify(server.url("/").toString(), credential(), "sess-1"),
            )
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val failed = repository.fetchPendingClarify(server.url("/").toString(), credential(), "sess-1")
                as PendingClarifyLookup.Failed
            assertEquals("HTTP 500", failed.message)
        }
    }

    @Test
    fun invalidBaseUrlIsLocalRejection() {
        val result = repository.respondApproval(
            "not a url", credential(), "sess-1",
            ApprovalPayload(approvalId = "a", command = "c", description = ""),
            ApprovalChoice.ONCE,
        )
        val rejected = result as InteractiveRespondResult.Rejected
        assertEquals(0, rejected.httpStatus)
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )
}
