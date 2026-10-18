package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * [ChatRepository.transcribePcmWav] — контракт распознавания речи на сервере Марса
 * (MockWebServer, без реальной сети): `POST /api/transcribe`,
 * multipart с полем файла ровно `file`, device-авторизация `Bearer`
 * ([credentialProvider] — тот же credential из AndroidKeyStore), success — HTTP 200
 * и непустой `transcript`. Пустой вход отсекается ДО сети; любой не-200 несёт
 * [TranscribeOutcome.Failure.httpCode].
 */
class ChatRepositoryTranscribeTest {

    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )

    private fun repository(server: MockWebServer, withCredential: Boolean = true) = ChatRepository(
        baseUrl = server.url("/").toString(),
        credentialProvider = { if (withCredential) credential else null },
    )

    @Test
    fun `success posts multipart file and returns the transcript`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"transcript":"привет мир"}"""))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1, 2, 3))

            assertEquals(TranscribeOutcome.Success("привет мир"), outcome)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/transcribe", request.path)
            assertEquals("Bearer native-token", request.getHeader("Authorization"))
            assertTrue(
                "content type must be multipart, got ${request.getHeader("Content-Type")}",
                request.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
            )
            val body = request.body.readUtf8()
            assertTrue("field must be named file: $body", body.contains("""name="file"; filename="voice.wav""""))
            assertTrue("no session_id on the transcribe endpoint", !body.contains("""name="session_id""""))
        }
    }

    @Test
    fun `custom filename is sent in the multipart part`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"transcript":"ok"}"""))

            repository(server).transcribePcmWav(byteArrayOf(4, 5), filename = "clip.wav")

            val body = server.takeRequest().body.readUtf8()
            assertTrue("got $body", body.contains("""name="file"; filename="clip.wav""""))
        }
    }

    @Test
    fun `401 becomes Failure carrying the http code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1))

            assertTrue("expected Failure, got $outcome", outcome is TranscribeOutcome.Failure)
            outcome as TranscribeOutcome.Failure
            assertEquals(401, outcome.httpCode)
            assertTrue("message must mention the code: ${outcome.message}", outcome.message.contains("401"))
        }
    }

    @Test
    fun `non-200 without a JSON body still carries the http code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1))

            assertEquals(TranscribeOutcome.Failure("HTTP 500", 500), outcome)
        }
    }

    @Test
    fun `413 surfaces the server reason and the code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":"File too large (max 20MB)"}"""))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1))

            assertTrue(outcome is TranscribeOutcome.Failure)
            outcome as TranscribeOutcome.Failure
            assertEquals(413, outcome.httpCode)
            assertTrue("message must carry the server reason: ${outcome.message}", outcome.message.contains("File too large"))
        }
    }

    @Test
    fun `empty input fails without touching the network`() = runBlocking {
        MockWebServer().use { server ->
            val outcome = repository(server).transcribePcmWav(ByteArray(0))

            assertTrue("expected Failure, got $outcome", outcome is TranscribeOutcome.Failure)
            assertEquals(0, server.requestCount)
            assertNull((outcome as TranscribeOutcome.Failure).httpCode)
        }
    }

    @Test
    fun `blank filename fails without touching the network`() = runBlocking {
        MockWebServer().use { server ->
            val outcome = repository(server).transcribePcmWav(byteArrayOf(1), filename = "   ")

            assertTrue(outcome is TranscribeOutcome.Failure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `missing credential fails without touching the network`() = runBlocking {
        MockWebServer().use { server ->
            val outcome = repository(server, withCredential = false).transcribePcmWav(byteArrayOf(1))

            assertTrue("expected Failure, got $outcome", outcome is TranscribeOutcome.Failure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `broken JSON on 200 is an explicit Failure with no http code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1))

            assertEquals(TranscribeOutcome.Failure("Malformed transcribe response"), outcome)
            assertNull((outcome as TranscribeOutcome.Failure).httpCode)
        }
    }

    @Test
    fun `empty transcript on 200 is an explicit Failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"transcript":"   "}"""))

            val outcome = repository(server).transcribePcmWav(byteArrayOf(1))

            assertTrue("expected Failure, got $outcome", outcome is TranscribeOutcome.Failure)
            assertNull((outcome as TranscribeOutcome.Failure).httpCode)
        }
    }

    @Test
    fun `transport failure folds into Failure and never throws`() = runBlocking {
        val broken = ChatRepository(
            baseUrl = "http://127.0.0.1:1/",
            credentialProvider = { credential },
        )
        val outcome = broken.transcribePcmWav(byteArrayOf(1))

        assertTrue("expected Failure, got $outcome", outcome is TranscribeOutcome.Failure)
        assertNull((outcome as TranscribeOutcome.Failure).httpCode)
    }
}
