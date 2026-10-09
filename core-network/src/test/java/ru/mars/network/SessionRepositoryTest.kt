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

    /**
     * `GET /api/sessions` provenance fields (Session.compact + state.db source
     * overlay): delegated subagent children carry `source_tag`/`raw_source` ==
     * "subagent", `read_only == true` and `parent_session_id`; [SessionItem.isSubagent]
     * is the drawer's hide-by-default marker (WebUI parity #5244/#5305).
     */
    @Test fun parsesSubagentProvenanceFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "{\"sessions\":[" +
                    "{\"session_id\":\"sub1\",\"title\":\"Subagent Session\",\"source_tag\":\"subagent\",\"raw_source\":\"subagent\",\"session_source\":\"other\",\"source_label\":\"Subagent\",\"read_only\":true,\"parent_session_id\":\"parent1\",\"message_count\":52}," +
                    "{\"session_id\":\"w1\",\"title\":\"Chat\",\"source_tag\":\"webui\",\"raw_source\":\"webui\",\"session_source\":\"webui\",\"source_label\":\"WebUI\",\"read_only\":false}" +
                    "]}"
            ))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val sessions = repo.getSessions(server.url("/").toString(), c)
            assertEquals(2, sessions.size)
            val sub = sessions[0]
            assertTrue(sub.isSubagent)
            assertEquals("subagent", sub.sourceTag)
            assertEquals("subagent", sub.rawSource)
            assertEquals("other", sub.sessionSource)
            assertEquals("Subagent", sub.sourceLabel)
            assertTrue(sub.readOnly)
            assertEquals("parent1", sub.parentSessionId)
            val normal = sessions[1]
            assertTrue(!normal.isSubagent)
            assertNull(normal.parentSessionId)
            assertTrue(!normal.readOnly)
        }
    }

    /** Rows without provenance fields (older servers) are never subagents — backward compatible. */
    @Test fun legacyRowsWithoutSourceAreNotSubagents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sessions\":[{\"session_id\":\"s1\",\"title\":\"Chat\"}]}"))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val sessions = repo.getSessions(server.url("/").toString(), c)
            assertTrue(!sessions.single().isSubagent)
            assertNull(sessions.single().sourceTag)
        }
    }

    /**
     * A row without `id`/`session_id` never becomes a phantom `SessionItem(id = "")`:
     * such an item can't be opened/renamed/deleted, so getSessions drops it (mapNotNull
     * over the nullable sessionItem) and the neighbouring valid rows survive untouched.
     */
    @Test fun dropsRowsWithoutSessionId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "{\"sessions\":[" +
                    "{\"title\":\"Ghost row without any id\",\"message_count\":3}," +
                    "{\"session_id\":\"s1\",\"title\":\"Chat\"}" +
                    "]}"
            ))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val sessions = repo.getSessions(server.url("/").toString(), c)
            assertEquals(listOf("s1"), sessions.map { it.id })
            assertEquals("/api/sessions", server.takeRequest().path)
        }
    }

    /** A whitespace-only id is blank, not an id: the row is dropped, the neighbouring legacy `id` row (with provenance) is kept. */
    @Test fun dropsRowsWithWhitespaceSessionId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "{\"sessions\":[" +
                    "{\"session_id\":\"   \",\"title\":\"Blank id row\"}," +
                    "{\"id\":\"legacy1\",\"title\":\"Legacy Chat\",\"source_tag\":\"webui\",\"read_only\":false}" +
                    "]}"
            ))
            val repo = SessionRepository(); val c = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)
            val sessions = repo.getSessions(server.url("/").toString(), c)
            assertEquals(listOf("legacy1"), sessions.map { it.id })
            // Legacy `id` key and the source/read-only parse still work for the surviving row.
            assertEquals("Legacy Chat", sessions.single().title)
            assertEquals("webui", sessions.single().sourceTag)
            assertTrue(!sessions.single().readOnly)
        }
    }
}
