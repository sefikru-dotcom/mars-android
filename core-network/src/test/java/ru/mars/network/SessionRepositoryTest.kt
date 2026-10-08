package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.SessionRepository

class SessionRepositoryTest {
    @Test fun listsAndLoadsSessionsAndDeletes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sessions\":[{\"session_id\":\"s1\",\"title\":\"Chat\",\"message_count\":2}]}"))
            server.enqueue(MockResponse().setBody("{\"session_id\":\"s1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"Hello\"}]}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"state_db_cleanup_failed\":false}"))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            assertEquals(listOf(ru.mars.network.model.SessionItem("s1", "Chat", null, null, null, 2)), repo.getSessions(server.url("/").toString(), c))
            assertEquals("Hello", repo.getSessionDetail(server.url("/").toString(), c, "s1")!!.messages.single().content)
            assertTrue(repo.deleteSession(server.url("/").toString(), c, "s1"))
            assertEquals("/api/sessions", server.takeRequest().path)
            assertEquals("/api/session?session_id=s1", server.takeRequest().path)
            val delete = server.takeRequest()
            assertEquals("POST", delete.method)
            assertEquals("/api/session/delete", delete.path)
            assertEquals("Bearer t", delete.getHeader("Authorization"))
            assertEquals("{\"session_id\":\"s1\"}", delete.body.readUtf8())
        }
    }

    /**
     * `GET /api/session` → `session.workspace` (Session.compact()): the LIVE
     * workspace path the client echoes back on the next chat/start. Absent on
     * older payloads → null ("server default") — backward compatible.
     */
    @Test fun detailParsesWorkspacePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "{\"session\":{\"session_id\":\"s1\",\"title\":\"Chat\",\"workspace\":\"/root/workspace\",\"created_workspace\":\"/root\",\"messages\":[]}}"
            ))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val detail = repo.getSessionDetail(server.url("/").toString(), c, "s1")!!
            assertEquals("/root/workspace", detail.workspace)
            assertEquals("s1", detail.id)
        }
    }

    @Test fun detailWithoutWorkspaceKeepsNullDefault() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "{\"session\":{\"session_id\":\"s2\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"Hello\"}]}}"
            ))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val detail = repo.getSessionDetail(server.url("/").toString(), c, "s2")!!
            assertNull(detail.workspace)
            // The rest of the legacy parse is untouched.
            assertEquals("Hello", detail.messages.single().content)
        }
    }
}
