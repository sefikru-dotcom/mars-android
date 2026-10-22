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
 * [PushRepository] — фасад токена: регистрация и снятие при logout.
 *
 * Снятие обязано идти на свой путь (`/api/push/fcm/unregister`) с тем же
 * device-Bearer: иначе вышедший из аккаунта телефон продолжает получать пуши
 * прошлого профиля.
 */
class PushRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PushRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = PushRepository(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `unregister posts the token to the unregister path with device auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"removed":1}"""))

        val outcome = repository.unregisterToken(credential, "fcm-tok")

        assertEquals(PushUnregisterOutcome.Success, outcome)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/push/fcm/unregister", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        assertEquals(PushRegistrationPayload.unregisterBody("fcm-tok"), recorded.body.readUtf8())
    }

    @Test
    fun `unregister without a credential skips without network`() = runBlocking {
        val outcome = repository.unregisterToken(null, "fcm-tok")
        assertTrue("expected Skipped, got $outcome", outcome is PushUnregisterOutcome.Skipped)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unregister with a blank token skips without network`() = runBlocking {
        assertTrue(repository.unregisterToken(credential, " ") is PushUnregisterOutcome.Skipped)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unregister http failure carries the code and reason`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val outcome = repository.unregisterToken(credential, "fcm-tok")
        require(outcome is PushUnregisterOutcome.Failure) { "expected Failure, got $outcome" }
        assertTrue(outcome.message.contains("500"))
        assertTrue(outcome.message.contains("boom"))
    }

    @Test
    fun `unregister transport failure never throws`() = runBlocking {
        val broken = PushRepository(baseUrl = "http://127.0.0.1:1/")
        assertTrue(broken.unregisterToken(credential, "fcm-tok") is PushUnregisterOutcome.Failure)
    }

    @Test
    fun `register delegates to the tested register contract`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val outcome = repository.registerToken(credential, "fcm-tok", "Pixel 8")
        assertTrue("expected Success, got $outcome", outcome is PushRegisterOutcome.Success)
        assertEquals("/api/push/fcm/register", server.takeRequest().path)
    }
}
