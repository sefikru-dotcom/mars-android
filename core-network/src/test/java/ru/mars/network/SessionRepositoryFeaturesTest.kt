package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.SessionRepository

class SessionRepositoryFeaturesTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    @Test fun renamesSessionWithJsonAndBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(SessionRepository().renameSession(server.url("/").toString(), credential, "s/1", "New title"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/title", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("{\"session_id\":\"s/1\",\"title\":\"New title\"}", request.body.readUtf8())
        }
    }

    @Test fun searchesSessionTitlesAndPreviewsCaseInsensitively() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sessions\":[{\"id\":\"1\",\"title\":\"Alpha\"},{\"id\":\"2\",\"preview\":\"Beta preview\"}]}"))
            val result = SessionRepository().searchSessions(server.url("/").toString(), credential, "beta")
            assertEquals(listOf("2"), result.map { it.id })
            assertEquals("/api/sessions", server.takeRequest().path)
        }
    }
}
