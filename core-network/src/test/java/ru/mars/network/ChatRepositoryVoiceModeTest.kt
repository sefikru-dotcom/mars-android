package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

/**
 * `voice_mode` в теле `POST api/chat/start` — строго opt-in.
 *
 * Требование серверного контракта: поле едет как bool `true` только у голосового хода, а
 * обычный чат обязан остаться БАЙТ-В-БАЙТ прежним (ключа нет вовсе). Поэтому тесты смотрят
 * на фактическое тело запроса, а не на параметр функции.
 */
class ChatRepositoryVoiceModeTest {

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    private fun startResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"stream_id\":\"s-1\",\"session_id\":\"sess-1\",\"voice_mode\":true}")

    /** Ответ на `api/chat/stream` — пустой SSE-поток с одним терминальным кадром. */
    private fun streamResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: done\ndata: {}\n\n")

    private fun send(server: MockWebServer, voiceMode: Boolean): String = runBlocking {
        val repository = ChatRepository()
        runCatching {
            repository.sendMessage(
                endpoint = server.url("/").toString(),
                credential = credential(),
                message = "привет",
                voiceMode = voiceMode,
            ).toList()
        }
        server.takeRequest().body.readUtf8()
    }

    @Test
    fun `voice mode true puts a strict boolean into the body`() {
        MockWebServer().use { server ->
            server.enqueue(startResponse())
            server.enqueue(streamResponse())

            val body = send(server, voiceMode = true)

            assertTrue(body, body.contains("\"voice_mode\":true"))
            assertFalse("bool, не строка", body.contains("\"voice_mode\":\"true\""))
        }
    }

    @Test
    fun `voice mode false omits the key entirely`() {
        MockWebServer().use { server ->
            server.enqueue(startResponse())
            server.enqueue(streamResponse())

            val body = send(server, voiceMode = false)

            assertFalse("обычный чат не должен содержать voice_mode: $body", body.contains("voice_mode"))
            assertTrue(body, body.startsWith("{\"message\":\"привет\""))
        }
    }

    @Test
    fun `default send keeps the plain payload`() {
        MockWebServer().use { server ->
            server.enqueue(startResponse())
            server.enqueue(streamResponse())

            val body = runBlocking {
                val repository = ChatRepository()
                runCatching {
                    repository.sendMessage(
                        endpoint = server.url("/").toString(),
                        credential = credential(),
                        message = "привет",
                    ).toList()
                }
                server.takeRequest().body.readUtf8()
            }

            assertEquals("""{"message":"привет","attachments":[]}""", body)
        }
    }
}
