package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.SessionRepository

class SessionRepositoryTest {
    @Test fun listsAndLoadsSessionsAndDeletes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sessions\":[{\"session_id\":\"s1\",\"title\":\"Chat\",\"message_count\":2}]}"))
            server.enqueue(MockResponse().setBody("{\"session_id\":\"s1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"Hello\"}]}"))
            server.enqueue(MockResponse().setResponseCode(204))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            assertEquals(listOf(ru.mars.network.model.SessionItem("s1", "Chat", null, null, null, 2)), repo.getSessions(server.url("/").toString(), c))
            assertEquals("Hello", repo.getSessionDetail(server.url("/").toString(), c, "s1")!!.messages.single().content)
            assertTrue(repo.deleteSession(server.url("/").toString(), c, "s1"))
            assertEquals("/api/sessions", server.takeRequest().path)
            assertEquals("/api/session?session_id=s1", server.takeRequest().path)
            assertEquals("DELETE", server.takeRequest().method)
        }
    }
}
