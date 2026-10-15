package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.SessionExportOutcome
import ru.mars.network.repository.SessionMutationOutcome
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

    // --- A2: глобальный серверный поиск ---------------------------------------------------------

    @Test fun globalSearchHitsServerSearchEndpointWithQuery() = runBlocking {
        MockWebServer().use { server ->
            // Реальный ответ GET /api/sessions/search (проверен живым запросом).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"sessions\":[{\"id\":\"s1\",\"title\":\"Отпуск\",\"message_count\":4}]," +
                        "\"query\":\"отпуск\",\"count\":1,\"all_profiles\":false,\"active_profile\":\"default\"}"
                )
            )
            val result = SessionRepository().searchSessionsGlobal(server.url("/").toString(), credential, "отпуск")
            assertEquals(listOf("s1"), result.map { it.id })
            assertEquals(4, result.first().messageCount)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/sessions/search?q=%D0%BE%D1%82%D0%BF%D1%83%D1%81%D0%BA", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
    }

    @Test fun globalSearchAddsAllProfilesFlagWhenAsked() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"sessions\":[],\"count\":0}"))
            SessionRepository().searchSessionsGlobal(server.url("/").toString(), credential, "x", allProfiles = true)
            assertEquals("/api/sessions/search?q=x&all_profiles=1", server.takeRequest().path)
        }
    }

    @Test fun blankGlobalQueryNeverTouchesNetwork() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(SessionRepository().searchSessionsGlobal(server.url("/").toString(), credential, "   ").isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun globalSearchFailureIsEmptyListNotException() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"))
            assertTrue(SessionRepository().searchSessionsGlobal(server.url("/").toString(), credential, "q").isEmpty())
        }
    }

    @Test fun globalSearchParsesPinnedAndArchivedFlags() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"sessions\":[{\"id\":\"s1\",\"title\":\"A\",\"pinned\":true,\"archived\":false}," +
                        "{\"id\":\"s2\",\"title\":\"B\",\"pinned\":false,\"archived\":true}],\"count\":2}"
                )
            )
            val result = SessionRepository().searchSessionsGlobal(server.url("/").toString(), credential, "a")
            assertTrue(result[0].pinned)
            assertFalse(result[0].archived)
            assertTrue(result[1].archived)
        }
    }

    // --- A3: pin / archive / export --------------------------------------------------------------

    @Test fun pinSendsPinnedTrueAndReturnsSettledRow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"ok\":true,\"session\":{\"session_id\":\"s1\",\"title\":\"Работа\",\"pinned\":true,\"archived\":false}}"
                )
            )
            val outcome = SessionRepository().setSessionPinned(server.url("/").toString(), credential, "s1", true)
            val success = outcome as SessionMutationOutcome.Success
            assertEquals("s1", success.session?.id)
            assertTrue(success.session!!.pinned)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/pin", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("{\"session_id\":\"s1\",\"pinned\":true}", request.body.readUtf8())
        }
    }

    @Test fun unpinSendsPinnedFalse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"session\":{\"session_id\":\"s1\",\"pinned\":false}}"))
            SessionRepository().setSessionPinned(server.url("/").toString(), credential, "s1", false)
            assertEquals("{\"session_id\":\"s1\",\"pinned\":false}", server.takeRequest().body.readUtf8())
        }
    }

    @Test fun archiveSendsArchivedFlagToArchiveEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"ok\":true,\"session\":{\"session_id\":\"s9\",\"title\":\"Старое\",\"archived\":true}}"
                )
            )
            val outcome = SessionRepository().setSessionArchived(server.url("/").toString(), credential, "s9", true)
            assertTrue((outcome as SessionMutationOutcome.Success).session!!.archived)
            val request = server.takeRequest()
            assertEquals("/api/session/archive", request.path)
            assertEquals("{\"session_id\":\"s9\",\"archived\":true}", request.body.readUtf8())
        }
    }

    @Test fun mutationFailureCarriesServerMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Pin quota exceeded\"}"))
            val outcome = SessionRepository().setSessionPinned(server.url("/").toString(), credential, "s1", true)
            assertEquals("Pin quota exceeded", (outcome as SessionMutationOutcome.Failure).message)
        }
    }

    @Test fun exportReadsFullSessionDocument() = runBlocking {
        MockWebServer().use { server ->
            // Форма GET /api/session/export: плоский объект сессии, без обёртки "session".
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"session_id\":\"s1\",\"title\":\"Экспорт\",\"workspace\":\"/root/ws\"," +
                        "\"created_workspace\":\"/root/ws\",\"model\":\"m\",\"model_provider\":\"p\"," +
                        "\"messages\":[{\"role\":\"user\",\"text\":\"привет\"},{\"role\":\"assistant\",\"text\":\"ответ\"}]}"
                )
            )
            val outcome = SessionRepository().exportSession(server.url("/").toString(), credential, "s1")
            val detail = (outcome as SessionExportOutcome.Success).detail
            assertEquals("s1", detail.id)
            assertEquals("Экспорт", detail.title)
            assertEquals("/root/ws", detail.workspace)
            assertEquals("m", detail.model)
            assertEquals("p", detail.modelProvider)
            assertEquals(2, detail.messages.size)
            val request = server.takeRequest()
            assertEquals("/api/session/export?session_id=s1", request.path)
        }
    }

    @Test fun exportFailureCarriesServerMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"Session not found\"}"))
            val outcome = SessionRepository().exportSession(server.url("/").toString(), credential, "missing")
            assertEquals("Session not found", (outcome as SessionExportOutcome.Failure).message)
        }
    }
}
