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
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsRetryPolicy
import ru.mars.network.repository.ServerTtsSpeechRequest
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Голосовой профиль `api/tts`, `Retry-After` и ограниченный повтор.
 *
 * Смысл набора: Android раньше слал `{"text":…}` без `engine`/`voice` и получал
 * `piper/ru_RU-dmitri-medium` вместо голоса веба. Здесь фиксируется, что (а) пара
 * engine+voice уходит в тело, (б) `429` от лимитера обрабатывается ОДНИМ ограниченным
 * повтором, а не бесконечными ретраями и не потерей фразы.
 */
class ServerTtsVoiceContractTest {

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    private fun audioResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "audio/wav")
        .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3)))

    // --- профили --------------------------------------------------------------------------

    @Test
    fun `natural svetlana is first in the voice dialog policy`() {
        assertEquals(
            listOf("natural-svetlana", "local-irina"),
            ServerTtsVoices.VOICE_DIALOG.map { it.id },
        )
        val natural = ServerTtsVoices.VOICE_DIALOG.first()
        assertTrue("естественный голос обязан быть помечен natural", natural.natural)
        assertEquals("edge", natural.engine)
        assertEquals("ru-RU-SvetlanaNeural", natural.voice)
        assertEquals("+10%", natural.rate)
        assertTrue("edge требует сети", natural.needsNetwork)
    }

    @Test
    fun `local irina is a fallback and never replaces the natural voice by default`() {
        val fallback = ServerTtsVoices.byId("local-irina")!!
        assertFalse(fallback.natural)
        assertTrue(fallback.isLocal)
        assertEquals("piper", fallback.engine)
        assertEquals("ru_RU-irina-medium", fallback.voice)
        // Резерв не должен стоять первым ни при каких условиях: иначе он станет «навязанным».
        assertFalse(ServerTtsVoices.VOICE_DIALOG.first() === fallback)
    }

    @Test
    fun `unknown profile id is null not a crash`() {
        assertNull(ServerTtsVoices.byId("ru_RU-dmitri-medium"))
        assertNull(ServerTtsVoices.byId(""))
        assertNull(ServerTtsVoices.byId(null))
    }

    @Test
    fun `body carries engine and voice together`() {
        val request = ServerTtsSpeechRequest.of("привет", ServerTtsVoices.NATURAL_SVETLANA)
        val body = ServerTtsContract.buildBody(request)

        assertEquals(
            "{\"text\":\"привет\",\"engine\":\"edge\",\"voice\":\"ru-RU-SvetlanaNeural\",\"rate\":\"+10%\"}",
            body,
        )
    }

    @Test
    fun `null profile keeps the old body so the server default is unchanged`() {
        assertEquals("{\"text\":\"привет\"}", ServerTtsContract.buildBody("привет"))
        assertEquals(
            "{\"text\":\"привет\"}",
            ServerTtsContract.buildBody(ServerTtsSpeechRequest.of("привет", null)),
        )
    }

    @Test
    fun `blank engine or voice never reaches the body`() {
        val body = ServerTtsContract.buildBody(
            ServerTtsSpeechRequest(text = "текст", engine = "  ", voice = "   "),
        )
        assertEquals("{\"text\":\"текст\"}", body)
    }

    @Test
    fun `profile without rate omits the rate field`() {
        val body = ServerTtsContract.buildBody(ServerTtsSpeechRequest.of("текст", ServerTtsVoices.LOCAL_IRINA))
        assertEquals("{\"text\":\"текст\",\"engine\":\"piper\",\"voice\":\"ru_RU-irina-medium\"}", body)
    }

    @Test
    fun `repository sends the explicit voice pair over the wire`() {
        MockWebServer().use { server ->
            server.enqueue(audioResponse())
            val result = runBlocking {
                ServerTtsRepository().synthesize(
                    server.url("/").toString(),
                    credential(),
                    ServerTtsSpeechRequest.of("привет", ServerTtsVoices.NATURAL_SVETLANA),
                )
            }

            assertTrue(result is ServerTtsResult.Audio)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"engine\":\"edge\""))
            assertTrue(body, body.contains("\"voice\":\"ru-RU-SvetlanaNeural\""))
        }
    }

    @Test
    fun `repository still accepts plain text with engine and voice arguments`() {
        MockWebServer().use { server ->
            server.enqueue(audioResponse())
            runBlocking {
                ServerTtsRepository().synthesize(
                    server.url("/").toString(), credential(), "текст",
                    engine = "piper", voice = "ru_RU-irina-medium",
                )
            }
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"engine\":\"piper\""))
            assertTrue(body, body.contains("\"voice\":\"ru_RU-irina-medium\""))
        }
    }

    // --- Retry-After ----------------------------------------------------------------------

    @Test
    fun `retry after seconds is parsed to millis`() {
        assertEquals(2_000L, ServerTtsRetryPolicy.parseRetryAfterMs("2", nowMs = 0L))
        assertEquals(0L, ServerTtsRetryPolicy.parseRetryAfterMs("0", nowMs = 0L))
        assertEquals(1_000L, ServerTtsRetryPolicy.parseRetryAfterMs(" 1 ", nowMs = 0L))
        assertEquals(120_000L, ServerTtsRetryPolicy.parseRetryAfterMs("120", nowMs = 0L))
    }

    @Test
    fun `retry after http date is parsed against the injected clock`() {
        val now = 1_700_000_000_000L
        val header = "Tue, 14 Nov 2023 22:13:25 GMT" // = 1_700_000_005_000 ms
        val parsed = ServerTtsRetryPolicy.parseRetryAfterMs(header, nowMs = now)
        assertEquals(5_000L, parsed)
    }

    @Test
    fun `past retry after date degrades to zero and garbage to null`() {
        val parsed = ServerTtsRetryPolicy.parseRetryAfterMs(
            "Tue, 14 Nov 2023 22:13:25 GMT",
            nowMs = 1_700_000_100_000L,
        )
        assertEquals(0L, parsed)
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs("потом", 0L))
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs(null, 0L))
        assertNull(ServerTtsRetryPolicy.parseRetryAfterMs("  ", 0L))
    }

    @Test
    fun `delay is capped so speech never waits for an absurd Retry-After`() {
        val policy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 2_500L)
        assertEquals(2_500L, policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = 60_000L))
        assertEquals(900L, policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = 900L))
        // Без заголовка работает бэкофф ≈ окно лимитера.
        assertEquals(
            ServerTtsRetryPolicy.DEFAULT_BACKOFF_MS,
            policy.delayBeforeRetryMs(attempt = 1, retryAfterMs = null),
        )
    }

    @Test
    fun `only limiter and server errors are retryable`() {
        val policy = ServerTtsRetryPolicy()
        assertTrue(policy.isRetryable("rate_limited"))
        assertTrue(policy.isRetryable("server_error"))
        // Терминальные ответы — сигнал сменить голос/токен, а не ждать.
        assertFalse(policy.isRetryable("unauthorized"))
        assertFalse(policy.isRetryable("not_found"))
        assertFalse(policy.isRetryable("invalid_response"))
        assertFalse(policy.isRetryable("transport"))
    }

    // --- повтор на 429 --------------------------------------------------------------------

    @Test
    fun `429 is retried once and the second answer wins`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "0")
                    .setBody("{\"error\":\"rate limit exceeded\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertTrue("после 429 должен пройти повтор", result is ServerTtsResult.Audio)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `retry budget is bounded - third 429 is returned as unavailable`() {
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(
                    MockResponse().setResponseCode(429)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":\"rate limit exceeded\"}"),
                )
            }
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals("rate_limited", unavailable.reason)
            assertEquals(429, unavailable.httpCode)
            // Ровно две попытки: третья не уходит, даже если сервер снова ответил бы 429.
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `unavailable carries the parsed retry after for the queue`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "2")
                    .setBody("{\"error\":\"rate limit exceeded\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 1, maxDelayMs = 2_500L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals(2_000L, unavailable.retryAfterMs)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `terminal 401 is not retried`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"unauthorized\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 3, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertEquals("unauthorized", (result as ServerTtsResult.Unavailable).reason)
            assertEquals("401 не повторяется: это токен, а не лимитер", 1, server.requestCount)
        }
    }

    @Test
    fun `unknown voice 400 is not retried`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"unknown voice\"}"),
            )
            server.enqueue(audioResponse())
            val repository = ServerTtsRepository(
                retryPolicy = ServerTtsRetryPolicy(maxAttempts = 2, maxDelayMs = 0L),
            )

            val result = runBlocking {
                repository.synthesize(
                    server.url("/").toString(), credential(),
                    ServerTtsSpeechRequest.of("текст", ServerTtsVoices.LOCAL_IRINA),
                )
            }

            assertEquals("http_400", (result as ServerTtsResult.Unavailable).reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `profile id and voice name are the only strings in the log tag`() {
        val profile: ServerTtsVoiceProfile = ServerTtsVoices.NATURAL_SVETLANA
        assertEquals("natural-svetlana(edge/ru-RU-SvetlanaNeural)", profile.logTag)
        assertFalse(profile.logTag.contains("token"))
    }
}
