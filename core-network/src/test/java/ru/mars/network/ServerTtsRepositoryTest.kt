package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsRetryPolicy

/**
 * Контрактные тесты `POST /api/tts` (A5), снятые с боевого `api/route_tts.py` и
 * воспроизведённые MockWebServer'ом.
 *
 * Ключевой смысл: ЛЮБОЙ отказ (в том числе проверенный живым запросом 401 на
 * device-Bearer) обязан быть [ServerTtsResult.Unavailable], а не исключением —
 * только тогда клиент может молча уйти на системный TTS.
 */
class ServerTtsRepositoryTest {

    private val repository = ServerTtsRepository()

    /**
     * Без повторов и без пауз: этот набор проверяет МАППИНГ кодов, а не поведение лимитера.
     * Повтор на `429`/`5xx` проверяется отдельно (`ServerTtsVoiceContractTest`), где для него
     * есть второй ответ сервера.
     */
    private fun singleShotRepository() = ServerTtsRepository(
        retryPolicy = ServerTtsRetryPolicy(maxAttempts = 1, maxDelayMs = 0L),
    )

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Test
    fun audioResponseBecomesBytesWithContentType() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(okio.Buffer().write(byteArrayOf(0x49, 0x44, 0x33, 0x04)))
            )
            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "привет")
            }

            assertTrue(result is ServerTtsResult.Audio)
            val audio = result as ServerTtsResult.Audio
            assertEquals("audio/mpeg", audio.contentType)
            assertEquals(4, audio.bytes.size)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/tts", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertEquals("{\"text\":\"привет\"}", request.body.readUtf8())
        }
    }

    @Test
    fun unauthorizedIsUnavailableNotAnException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"unauthorized\"}")
            )
            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertTrue(result is ServerTtsResult.Unavailable)
            val unavailable = result as ServerTtsResult.Unavailable
            assertEquals("unauthorized", unavailable.reason)
            assertEquals(401, unavailable.httpCode)
        }
    }

    @Test
    fun validationErrorsMapToTheirHttpCodes() {
        val cases = listOf(
            Triple(400, "text too long (max 5000 characters)", 400),
            Triple(404, "not found", 404),
            Triple(429, "slow down", 429),
            Triple(500, "boom", 500),
        )
        for ((code, body, expectedCode) in cases) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(code)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":\"$body\"}")
                )
                val result = runBlocking {
                    singleShotRepository().synthesize(server.url("/").toString(), credential(), "текст")
                }
                assertTrue("код $code должен быть Unavailable", result is ServerTtsResult.Unavailable)
                assertEquals(expectedCode, (result as ServerTtsResult.Unavailable).httpCode)
            }
        }
    }

    @Test
    fun twoHundredWithoutAudioContentTypeIsInvalidResponse() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":true}")
            )
            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertEquals("invalid_response", (result as ServerTtsResult.Unavailable).reason)
        }
    }

    @Test
    fun emptyAudioBodyIsInvalidResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg"))
            val result = runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "текст")
            }

            assertEquals("invalid_response", (result as ServerTtsResult.Unavailable).reason)
        }
    }

    @Test
    fun transportFailureIsUnavailable() {
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            server.shutdown()
            val result = runBlocking { repository.synthesize(url, credential(), "текст") }

            assertEquals("transport", (result as ServerTtsResult.Unavailable).reason)
        }
    }

    @Test
    fun rateAndPitchRideTheBodyWhenValid() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(okio.Buffer().write(byteArrayOf(1)))
            )
            runBlocking {
                repository.synthesize(
                    server.url("/").toString(), credential(), "текст", rate = "+10%", pitch = "-5Hz",
                )
            }

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"rate\":\"+10%\""))
            assertTrue(body, body.contains("\"pitch\":\"-5Hz\""))
        }
    }

    @Test
    fun invalidProsodyIsOmittedSoTheServerNeverReturnsAnInvalidRateError() {
        assertEquals(null, ServerTtsContract.prosodyOrNull("громко", "%"))
        assertEquals(null, ServerTtsContract.prosodyOrNull("+500%", "%"))
        assertEquals(null, ServerTtsContract.prosodyOrNull("-101Hz", "Hz"))
        assertEquals("+5%", ServerTtsContract.prosodyOrNull(" +5% ", "%"))

        val body = ServerTtsContract.buildBody("текст", rate = "громко", pitch = "нет")
        assertEquals("{\"text\":\"текст\"}", body)
    }

    @Test
    fun sendableGuardMatchesTheServerLimit() {
        assertTrue(ServerTtsContract.isSendable("я".repeat(ServerTtsContract.MAX_TEXT_LENGTH)))
        assertEquals(false, ServerTtsContract.isSendable("я".repeat(ServerTtsContract.MAX_TEXT_LENGTH + 1)))
        assertEquals(false, ServerTtsContract.isSendable("   "))
    }

    @Test
    fun textIsJsonEscaped() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(okio.Buffer().write(byteArrayOf(1)))
            )
            runBlocking {
                repository.synthesize(server.url("/").toString(), credential(), "кавычка \" и перенос\nстроки")
            }

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\\\""))
            assertTrue(body, body.contains("\\n"))
        }
    }
}
