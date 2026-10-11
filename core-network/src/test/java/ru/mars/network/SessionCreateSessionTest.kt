package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionRepository

/**
 * Contract tests for POST /api/session/new (verified against api/routes.py
 * `_send_created_session_payload`: 200 `{"session": s.compact()}` with `session_id`).
 * Each test pins the exact wire request — method, path, JSON body, Bearer header —
 * and the parsed outcome for the server response.
 */
class SessionCreateSessionTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    @Test fun createsSessionWithEmptyJsonBodyAndBearer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("{\"session\":{\"session_id\":\"abc123\",\"workspace\":\"/root/workspace\",\"model\":\"@custom:plusvibe-api:gpt-6.1-sol\",\"model_provider\":\"custom:plusvibe-api\"}}")
            )
            val outcome = SessionRepository().createSession(server.url("/").toString(), credential)
            assertTrue(outcome is CreateSessionOutcome.Success)
            assertEquals("abc123", (outcome as CreateSessionOutcome.Success).session.id)
            // The creation response carries the server-resolved workspace — surfaced
            // so the caller can echo it on the first chat/start.
            assertEquals("/root/workspace", outcome.session.workspace)
            assertEquals("@custom:plusvibe-api:gpt-6.1-sol", outcome.session.model)
            assertEquals("custom:plusvibe-api", outcome.session.modelProvider)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/new", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("{}", request.body.readUtf8())
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        }
    }

    @Test fun sendsExplicitArgsAsJsonBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"session\":{\"session_id\":\"s2\"}}"))
            val outcome = SessionRepository().createSession(
                server.url("/").toString(), credential,
                mapOf("worktree" to false, "profile" to "default", "prev_session_id" to "s1"),
            )
            assertEquals("s2", (outcome as CreateSessionOutcome.Success).session.id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/new", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals(
                "{\"worktree\":false,\"profile\":\"default\",\"prev_session_id\":\"s1\"}",
                request.body.readUtf8(),
            )
        }
    }

    @Test fun httpErrorMapsToFailureWithServerMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"Failed to create worktree: boom\"}"))
            val outcome = SessionRepository().createSession(server.url("/").toString(), credential)
            assertTrue(outcome is CreateSessionOutcome.Failure)
            assertEquals("Failed to create worktree: boom", (outcome as CreateSessionOutcome.Failure).message)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/new", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
    }

    @Test fun httpErrorWithoutJsonBodyMapsToStatusFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
            val outcome = SessionRepository().createSession(server.url("/").toString(), credential)
            assertEquals("HTTP 502", (outcome as CreateSessionOutcome.Failure).message)
        }
    }

    @Test fun successBodyWithoutSessionIdIsFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"session\":{}}"))
            val outcome = SessionRepository().createSession(server.url("/").toString(), credential)
            assertTrue(outcome is CreateSessionOutcome.Failure)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    @Test fun unwrappedSessionIdIsAccepted() = runBlocking {
        MockWebServer().use { server ->
            // Defensive parity with getSessionDetail: tolerate a payload without the
            // "session" envelope as long as session_id is present.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"session_id\":\"flat1\"}"))
            val outcome = SessionRepository().createSession(server.url("/").toString(), credential)
            assertEquals("flat1", (outcome as CreateSessionOutcome.Success).session.id)
        }
    }
}
