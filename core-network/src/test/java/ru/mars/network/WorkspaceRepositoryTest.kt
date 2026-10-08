package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacePrefix
import ru.mars.network.repository.WorkspaceRepository

class WorkspaceRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    /**
     * Real GET /api/workspaces payload shape (api/routes.py): `workspaces[]`
     * entries of `{path, name}` plus the active `last` path.
     */
    private val realPayload = """
        {
          "workspaces": [
            {"path": "/root/workspace", "name": "Home"},
            {"path": "/opt/kvartal-git", "name": "Квартал"},
            {"path": "/opt/hermes-webui", "name": "hermes-webui"}
          ],
          "last": "/opt/kvartal-git",
          "terminal_remote_backend": false
        }
    """.trimIndent()

    @Test
    fun loadsWorkspaceCatalogWithBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(realPayload))
            val result = WorkspaceRepository().getWorkspaces(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/workspaces", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))

            requireNotNull(result)
            assertEquals("/opt/kvartal-git", result.last)
            assertEquals(3, result.workspaces.size)
            assertEquals(WorkspaceItem("Home", "/root/workspace", isDefault = false), result.workspaces[0])
            // `last` marks the server-active entry as default.
            assertEquals(WorkspaceItem("Квартал", "/opt/kvartal-git", isDefault = true), result.workspaces[1])
            assertFalse(result.workspaces[2].isDefault)
        }
    }

    @Test
    fun httpFailureMapsToNullNotThrow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertNull(WorkspaceRepository().getWorkspaces(server.url("/").toString(), credential))
        }
    }

    @Test
    fun parseFallsBackToPathAsNameAndSkipsBrokenEntries() {
        val parsed = WorkspaceRepository().parse(
            """{"workspaces":[{"path":"/a"},{"name":"no-path"},{"path":"  "},{"path":"/b","name":""}],"last":null}"""
        )
        requireNotNull(parsed)
        assertNull(parsed.last)
        assertEquals(listOf(WorkspaceItem("/a", "/a"), WorkspaceItem("/b", "/b")), parsed.workspaces)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(WorkspaceRepository().parse("not json"))
        assertNull(WorkspaceRepository().parse(""))
    }
}

/**
 * Wire contract pinned against hermes-webui api/streaming.py
 * `_workspace_context_prefix` / `_escape_workspace_prefix_path` and the server
 * regex `^\s*\[Workspace::v1:\s*(?:\\.|[^\]\\])+\]\s*`.
 */
class WorkspacePrefixTest {

    @Test
    fun buildsSentinelWithTrailingNewline() {
        assertEquals("[Workspace::v1: /root/workspace]\n", WorkspacePrefix.build("/root/workspace"))
    }

    @Test
    fun escapesBackslashAndClosingBracketLikeServer() {
        assertEquals("""C:\\tmp\\we\]rd""", WorkspacePrefix.escape("""C:\tmp\we]rd"""))
        assertTrue(WorkspacePrefix.build("""C:\tmp\a]b""").startsWith("[Workspace::v1: C:\\\\tmp\\\\a\\]b]"))
    }

    @Test
    fun appliesPrefixOnlyForConcreteWorkspace() {
        val ws = WorkspaceItem("Home", "/root/workspace")
        assertEquals("[Workspace::v1: /root/workspace]\nпривет", WorkspacePrefix.apply("привет", ws))
        // "server default" leaves the message untouched
        assertEquals("привет", WorkspacePrefix.apply("привет", null))
        assertEquals("привет", WorkspacePrefix.apply("привет", WorkspaceItem("x", "  ")))
    }

    @Test
    fun stripsLeadingSentinelForDisplay() {
        assertEquals("hello", WorkspacePrefix.strip("[Workspace::v1: /root/workspace]\nhello"))
        assertEquals("hello", WorkspacePrefix.strip("[Workspace::v1: C:\\\\tmp\\\\a\\]b]\nhello"))
        assertEquals("hello", WorkspacePrefix.strip("[Workspace: legacy]\nhello"))
        assertEquals("hello", WorkspacePrefix.strip("hello"))
        // only a leading sentinel is stripped — mid-text brackets stay
        assertEquals("a [Workspace::v1: /x] b", WorkspacePrefix.strip("a [Workspace::v1: /x] b"))
    }
}
