package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.httpErrorCode

/**
 * Регрессия по блокеру «409 unsupported_voice_mode_backend не распознаётся приложением».
 *
 * Реальный отказ `runtime-runner` несёт ДВА поля сразу:
 *
 * ```
 * HTTP 409 {"error": "voice_mode is not supported by the runner backend.",
 *           "code": "unsupported_voice_mode_backend"}
 * ```
 *
 * `describeHttpError` берёт первое непустое поле (`error`) — так и должно быть, это текст для
 * человека. Но приложение классифицировало отказ по подстроке кода в этом тексте, а её там нет:
 * отказ уходил в общий путь «сообщение не дошло» и повторялся из офлайн-очереди.
 *
 * Фикс: машинный код едет отдельным полем [ChatEvent.Error.errorCode] по всему пути
 * (тело → событие → политика приложения). Фикстура здесь — точная копия обоих полей сервера.
 */
class ChatRepositoryVoiceModeRefusalCodeTest {

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    private companion object {
        const val HUMAN_TEXT = "voice_mode is not supported by the runner backend."
        const val CODE = "unsupported_voice_mode_backend"

        /** Ровно тело реального 409: человеческий текст и машинный код вместе. */
        const val SERVER_BODY =
            """{"error":"$HUMAN_TEXT","code":"$CODE"}"""
    }

    @Test
    fun `отказ с двумя полями отдаёт и текст, и структурированный код`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody(SERVER_BODY))

            val events = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Привет", voiceMode = true,
            ).toList()

            val error = events.single() as ChatEvent.Error
            // Текст для человека остаётся серверным и не подменяется кодом.
            assertEquals("HTTP 409: $HUMAN_TEXT", error.message)
            // А классификация получает машинный код, которого в тексте нет.
            assertEquals(CODE, error.errorCode)
            // Отказ до side effects: один POST, ни stream GET, ни второго POST.
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    @Test
    fun `старый контракт с кодом в error сохраняет код отдельным полем`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"error":"$CODE"}"""),
            )

            val error = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Привет", voiceMode = true,
            ).toList().single() as ChatEvent.Error

            assertEquals("HTTP 409: $CODE", error.message)
            // Поля `code` в теле не было — структурированного кода тоже нет.
            assertNull(error.errorCode)
        }
    }

    @Test
    fun `обычная серверная ошибка не получает кода отказа`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(500).setBody("""{"error":"internal error"}"""),
            )

            val error = ChatRepository().sendMessage(
                server.url("/").toString(), credential(), "Привет", voiceMode = true,
            ).toList().single() as ChatEvent.Error

            assertEquals("HTTP 500: internal error", error.message)
            assertNull(error.errorCode)
        }
    }

    @Test
    fun `409 на потоке тоже отдаёт структурированный код`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody(SERVER_BODY))

            val events = ChatRepository()
                .attachToStream(server.url("/").toString(), credential(), "st-1")
                .toList()

            // Перед ошибкой поток объявляет StreamStarted — отказ едет отдельным событием.
            assertEquals(ChatEvent.StreamStarted("st-1"), events.first())
            val error = events.filterIsInstance<ChatEvent.Error>().single()

            assertEquals("HTTP 409: $HUMAN_TEXT", error.message)
            assertEquals(CODE, error.errorCode)
        }
    }

    // ---- сам разбор тела -------------------------------------------------------------------

    @Test
    fun `код читается только из поля code`() {
        assertEquals(CODE, httpErrorCode(SERVER_BODY))
        assertEquals(CODE, httpErrorCode("""{"code":"  $CODE  "}"""))
        assertNull("человеческий текст — не код", httpErrorCode("""{"error":"$HUMAN_TEXT"}"""))
        assertNull(httpErrorCode("""{"code":"   "}"""))
        assertNull(httpErrorCode("""{"code":null}"""))
        assertNull(httpErrorCode("conflict"))
        assertNull(httpErrorCode(null))
        assertTrue(httpErrorCode("""{"error":"x","code":"y"}""") == "y")
    }
}
