package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.SessionRepository

class SessionRepositoryFeaturesTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    @Test fun renamesSessionWithJsonAndBearer() = runBlocking {
        MockWebServer().use { server ->
            // Real /api/session/rename success payload: {"session": s.compact()}
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"session\":{\"session_id\":\"s/1\",\"title\":\"New title\"}}"))
            assertTrue(SessionRepository().renameSession(server.url("/").toString(), credential, "s/1", "New title"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/rename", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("{\"session_id\":\"s/1\",\"title\":\"New title\"}", request.body.readUtf8())
        }
    }

    @Test fun renameFailureIsReportedAsFalse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"Session not found\"}"))
            assertFalse(SessionRepository().renameSession(server.url("/").toString(), credential, "missing", "x"))
            assertEquals("POST", server.takeRequest().method)
        }
    }

    @Test fun deletesSessionWithPostAndJsonBody() = runBlocking {
        MockWebServer().use { server ->
            // Real /api/session/delete success payload: {"ok": true, "state_db_cleanup_failed": ...}
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"state_db_cleanup_failed\":false}"))
            assertTrue(SessionRepository().deleteSession(server.url("/").toString(), credential, "s1"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/delete", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("{\"session_id\":\"s1\"}", request.body.readUtf8())
        }
    }

    @Test fun searchesSessionTitlesAndPreviewsCaseInsensitively() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sessions\":[{\"id\":\"1\",\"title\":\"Alpha\"},{\"id\":\"2\",\"preview\":\"Beta preview\"}]}"))
            val result = SessionRepository().searchSessions(server.url("/").toString(), credential, "beta")
            assertEquals(listOf("2"), result.map { it.id })
            assertEquals("/api/sessions", server.takeRequest().path)
        }
    }
}
