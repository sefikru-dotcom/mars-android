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
import ru.mars.network.repository.SessionRepository

/**
 * A14/A15 contract tests against MockWebServer: the usage endpoint, the composer
 * draft write, and the detail read path (`composer_draft`) that A15 depends on.
 */
class SessionUsageAndDraftRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    @Test fun usageRequestCarriesSessionIdAndParsesEnvelope() = runBlocking {
        MockWebServer().use { server ->
            // Real /api/session/usage success payload.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"ok\":true,\"usage\":{\"total_tokens\":1234,\"prompt_tokens\":1000," +
                        "\"completion_tokens\":234,\"cost\":0.042}}",
                ),
            )
            val usage = SessionRepository().getSessionUsage(server.url("/").toString(), credential, "s/1")
            assertEquals(1234, usage?.totalTokens)
            assertEquals(1000, usage?.promptTokens)
            assertEquals(234, usage?.completionTokens)
            assertEquals(0.042, usage?.cost!!, 1e-9)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/session/usage?session_id=s%2F1", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
    }

    @Test fun usageWithoutPriceKeepsCostNullInsteadOfZero() = runBlocking {
        MockWebServer().use { server ->
            // Provider without a price table: cost is absent (or explicitly null).
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"usage\":{\"total_tokens\":10,\"cost\":null}}"))
            val usage = SessionRepository().getSessionUsage(server.url("/").toString(), credential, "s1")
            assertNull(usage?.cost)
            assertEquals(10, usage?.totalTokens)
            assertFalse(usage!!.hasCost)
            assertTrue(usage.hasTokens)
        }
    }

    @Test fun usageAcceptsAliasFieldNamesAndNumericStrings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    "{\"ok\":true,\"usage\":{\"total_tokens\":\"300\",\"input_tokens\":\"200\"," +
                        "\"output_tokens\":\"100\",\"estimated_cost\":\"0.5\"}}",
                ),
            )
            val usage = SessionRepository().getSessionUsage(server.url("/").toString(), credential, "s1")
            assertEquals(300, usage?.totalTokens)
            assertEquals(200, usage?.promptTokens)
            assertEquals(100, usage?.completionTokens)
            assertEquals(0.5, usage?.cost!!, 1e-9)
        }
    }

    @Test fun usageWithoutUsageBlockIsUnavailableNotZero() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":false,\"error\":\"session not found\"}"))
            assertNull(SessionRepository().getSessionUsage(server.url("/").toString(), credential, "gone"))
        }
    }

    @Test fun blankSessionIdSkipsTheRequest() = runBlocking {
        MockWebServer().use { server ->
            assertNull(SessionRepository().getSessionUsage(server.url("/").toString(), credential, "  "))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun draftWritePostsSessionIdAndText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            assertTrue(
                SessionRepository().saveComposerDraft(
                    server.url("/").toString(), credential, "s/1", "черновик с \"кавычками\"\nи переводом строки",
                ),
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/draft", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            // Текст должен быть JSON-экранирован, а не подставлен сырым.
            assertTrue(body, body.contains("\"session_id\":\"s/1\""))
            assertTrue(body, body.contains("\\\"кавычками\\\""))
            assertTrue(body, body.contains("\\n"))
        }
    }

    @Test fun draftWriteReportsFailureAndNeverThrows() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            assertFalse(SessionRepository().saveComposerDraft(server.url("/").toString(), credential, "s1", "x"))
        }
    }

    @Test fun blankSessionIdIsNotWritten() = runBlocking {
        MockWebServer().use { server ->
            assertFalse(SessionRepository().saveComposerDraft(server.url("/").toString(), credential, "", "x"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun sessionDetailExposesComposerDraftFromItsBody() = runBlocking {
        MockWebServer().use { server ->
            // Read path for A15: the draft rides on the DETAIL payload (object form).
            server.enqueue(
                MockResponse().setBody(
                    "{\"session\":{\"session_id\":\"s1\",\"title\":\"T\"," +
                        "\"composer_draft\":{\"text\":\"недописанное\",\"files\":[]}}}",
                ),
            )
            val detail = SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")
            assertEquals("недописанное", detail?.composerDraft)
        }
    }

    @Test fun sessionDetailAcceptsPlainStringDraftAndIgnoresBlank() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"session\":{\"session_id\":\"s1\",\"composer_draft\":\"текст\"}}"))
            assertEquals(
                "текст",
                SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")?.composerDraft,
            )
            server.enqueue(MockResponse().setBody("{\"session\":{\"session_id\":\"s1\",\"composer_draft\":\"   \"}}"))
            assertNull(SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")?.composerDraft)
            // Отсутствие поля (старый сервер) — тоже null, а не пустая строка.
            server.enqueue(MockResponse().setBody("{\"session\":{\"session_id\":\"s1\"}}"))
            assertNull(SessionRepository().getSessionDetail(server.url("/").toString(), credential, "s1")?.composerDraft)
        }
    }
}
