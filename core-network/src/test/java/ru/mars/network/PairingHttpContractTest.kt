package ru.mars.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import ru.mars.network.api.MarsApi
import ru.mars.network.model.PairingExchangeRequest
import ru.mars.network.model.PairingStatus
import ru.mars.network.repository.PairingRepository

class PairingHttpContractTest {
    @Test
    fun repositoryUsesV3PathsAndNeverSendsPollSecretInStatusUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                "{\"pair_id\":\"pair-1\",\"status\":\"claimed\"}"
            ))
            val repository = PairingRepository(fixtureUrl(server))

            val result = repository.pollPairingStatus("pair-1").getOrThrow()
            val request = server.takeRequest()

            assertEquals(PairingStatus.CLAIMED, result.status)
            assertEquals("GET", request.method)
            assertEquals("/api/v1/auth/device-pairings/status?pair_id=pair-1", request.path)
            assertFalse(request.path!!.contains("poll_secret"))
        }
    }

    @Test
    fun repositoryRevokesDeviceWithBearerAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val repository = PairingRepository(fixtureUrl(server))

            repository.revokeCredential("device-9", "opaque-token").getOrThrow()
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/devices/device-9/revoke", request.path)
            assertEquals("Bearer opaque-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun retrofitApiMatchesV3MethodPathAndBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
                .build()
                .create(MarsApi::class.java)

            api.pollPairingStatus("pair-1")
            val statusRequest = server.takeRequest()
            assertEquals("GET", statusRequest.method)
            assertEquals("/api/v1/auth/device-pairings/status?pair_id=pair-1", statusRequest.path)
            assertFalse(statusRequest.path!!.contains("poll_secret"))

            api.exchangePairing("pair-1", PairingExchangeRequest("poll-secret"))
            val exchangeRequest = server.takeRequest()
            assertEquals("POST", exchangeRequest.method)
            assertEquals("/api/v1/auth/device-pairings/pair-1/exchange", exchangeRequest.path)
            assertEquals("{\"poll_secret\":\"poll-secret\"}", exchangeRequest.body.readUtf8())

            api.revokeCredential("device-9", "Bearer opaque-token")
            val revokeRequest = server.takeRequest()
            assertEquals("POST", revokeRequest.method)
            assertEquals("/api/v1/auth/devices/device-9/revoke", revokeRequest.path)
            assertEquals("Bearer opaque-token", revokeRequest.getHeader("Authorization"))
        }
    }

    @Test
    fun repositoryRejectsRemoteCleartextAndUrlCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingRepository("http://192.0.2.1/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRepository("https://user:pass@example.com/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRepository("http://localhost/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRepository("http://127.0.0.2/")
        }
    }

    @Test
    fun repositoryDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", fixtureUrl(target)))
                val repository = PairingRepository(fixtureUrl(source))

                assertTrue(repository.pollPairingStatus("pair-1").isFailure)
                assertEquals(1, source.requestCount)
                assertEquals(0, target.requestCount)
            }
        }
    }

    private fun fixtureUrl(server: MockWebServer): String =
        server.url("/").newBuilder().host("127.0.0.1").build().toString()
}
