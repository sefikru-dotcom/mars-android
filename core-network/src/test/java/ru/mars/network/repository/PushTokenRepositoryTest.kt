package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * [PushTokenRepository] — контракт регистрации FCM-токена на сервере Марса
 * (MockWebServer, без реальной сети): POST на единственный путь
 * [PushRegistrationPayload.REGISTER_PATH], device-авторизация `Bearer`
 * (существующий credential из AndroidKeyStore, новая схема не изобретается),
 * тело — ровно [PushRegistrationPayload.body]. Без credential регистрация
 * честно пропускается (Skipped), а не уходит анонимно.
 */
class PushTokenRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PushTokenRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = PushTokenRepository(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful registration posts the exact contract request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val outcome = repository.registerToken(credential, token = "fcm-tok", deviceName = "Pixel 8")

        assertTrue("expected Success, got $outcome", outcome is PushRegisterOutcome.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/push/fcm/register", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        assertTrue(
            "content type must be JSON, got ${recorded.getHeader("Content-Type")}",
            recorded.getHeader("Content-Type")!!.startsWith("application/json"),
        )
        assertEquals(PushRegistrationPayload.body("fcm-tok", "Pixel 8"), recorded.body.readUtf8())
    }

    @Test
    fun `204 without body is still a success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val outcome = repository.registerToken(credential, "fcm-tok", "Pixel 8")
        assertTrue(outcome is PushRegisterOutcome.Success)
    }

    @Test
    fun `http failure carries the server error message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad token"}"""))
        val outcome = repository.registerToken(credential, "fcm-tok", "Pixel 8")
        assertTrue("expected Failure, got $outcome", outcome is PushRegisterOutcome.Failure)
        val message = (outcome as PushRegisterOutcome.Failure).message
        assertTrue("message must mention the HTTP code: $message", message.contains("401"))
        assertTrue("message must carry the server reason: $message", message.contains("bad token"))
    }

    @Test
    fun `no credential skips registration without touching the network`() = runBlocking {
        val outcome = repository.registerToken(null, "fcm-tok", "Pixel 8")
        assertTrue("expected Skipped, got $outcome", outcome is PushRegisterOutcome.Skipped)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank token skips registration without touching the network`() = runBlocking {
        val outcome = repository.registerToken(credential, "   ", "Pixel 8")
        assertTrue(outcome is PushRegisterOutcome.Skipped)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transport failure folds into Failure, never throws`() = runBlocking {
        val broken = PushTokenRepository(baseUrl = "http://127.0.0.1:1/")
        val outcome = broken.registerToken(credential, "fcm-tok", "Pixel 8")
        assertTrue("expected Failure, got $outcome", outcome is PushRegisterOutcome.Failure)
    }
}
