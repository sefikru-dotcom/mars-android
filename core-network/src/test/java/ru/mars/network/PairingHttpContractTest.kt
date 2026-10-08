package ru.mars.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import ru.mars.network.api.MarsApi
import ru.mars.network.model.PairingExchangeRequest
import ru.mars.network.model.PairingStatus
import ru.mars.network.repository.PairingRepository

/**
 * HTTP contract of the v3 device-pairing endpoints, verified against the
 * deployed hermes-webui server (api/routes.py):
 * - `GET /api/v1/auth/device-pairings/status` is identified by `pair_id` ONLY;
 *   the server REJECTS a poll secret on this endpoint with
 *   400 `{"error": "poll_secret is accepted only by exchange"}` when the
 *   `X-Hermes-Poll-Secret` header or a `poll_secret` query parameter is present.
 * - `POST /api/v1/auth/device-pairings/{pair_id}/exchange` is the ONLY endpoint
 *   that accepts the poll secret — as a JSON body field `poll_secret`.
 */
class PairingHttpContractTest {
    @Test
    fun repositoryUsesV3PathsAndNeverSendsPollSecretOnStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                "{\"pair_id\":\"pair-1\",\"status\":\"claimed\"}"
            ))
            val repository = PairingRepository(fixtureUrl(server))

            val result = repository.pollPairingStatus("pair-1", "poll-secret").getOrThrow()
            val request = server.takeRequest()

            assertEquals(PairingStatus.CLAIMED, result.status)
            assertEquals("GET", request.method)
            assertEquals("/api/v1/auth/device-pairings/status?pair_id=pair-1", request.path)
            assertFalse(request.path!!.contains("poll_secret"))
            // P0: the deployed server answers 400 "poll_secret is accepted only by
            // exchange" when the status request carries the header — it must never
            // leave the client on this endpoint.
            assertNull(request.getHeader("X-Hermes-Poll-Secret"))
            assertFalse(request.headers.names().any { it.equals("X-Hermes-Poll-Secret", ignoreCase = true) })
        }
    }

    /** The poll secret stays optional for status: pair_id alone is the full contract. */
    @Test
    fun repositoryPollsStatusWithoutPollSecret() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                "{\"pair_id\":\"pair-1\",\"status\":\"pending\"}"
            ))
            val repository = PairingRepository(fixtureUrl(server))

            val result = repository.pollPairingStatus("pair-1").getOrThrow()
            val request = server.takeRequest()

            assertEquals(PairingStatus.PENDING, result.status)
            assertEquals("/api/v1/auth/device-pairings/status?pair_id=pair-1", request.path)
            assertNull(request.getHeader("X-Hermes-Poll-Secret"))
        }
    }

    /**
     * Regression against the REAL deployed behaviour: routes.py answers
     * 400 `poll_secret is accepted only by exchange` when the status request
     * carries `X-Hermes-Poll-Secret` (header) or `poll_secret` (query).
     */
    @Test
    fun statusPollSucceedsAgainstServerThatRejectsPollSecret() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.startsWith("/api/v1/auth/device-pairings/status") ||
                        path.startsWith("/api/auth/device-pairings/status")
                    ) {
                        if (request.getHeader("X-Hermes-Poll-Secret") != null || path.contains("poll_secret")) {
                            return MockResponse().setResponseCode(400)
                                .setBody("{\"error\":\"poll_secret is accepted only by exchange\"}")
                        }
                        return MockResponse().setResponseCode(200)
                            .setBody("{\"pair_id\":\"pair-1\",\"status\":\"approved\"}")
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val repository = PairingRepository(fixtureUrl(server))

            val result = repository.pollPairingStatus("pair-1", "poll-secret")

            assertTrue(result.toString(), result.isSuccess)
            assertEquals(PairingStatus.APPROVED, result.getOrThrow().status)
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
            // The Retrofit interface must not declare the forbidden header at all.
            assertNull(statusRequest.getHeader("X-Hermes-Poll-Secret"))

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

    /** Exchange keeps the poll secret where the server accepts it: in the JSON body. */
    @Test
    fun repositorySendsPollSecretOnlyInExchangeBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                "{\"access_token\":\"hdev_1\",\"token_type\":\"bearer\",\"expires_in\":600,\"device_id\":\"dev-1\",\"scopes\":[\"chat\"]}"
            ))
            val repository = PairingRepository(fixtureUrl(server))

            repository.exchangePairing("pair-1", "poll-secret").getOrThrow()
            val request = server.takeRequest()

            assertEquals("/api/v1/auth/device-pairings/pair-1/exchange", request.path)
            assertEquals("{\"poll_secret\":\"poll-secret\"}", request.body.readUtf8())
            assertNull(request.getHeader("X-Hermes-Poll-Secret"))
            assertFalse(request.path!!.contains("poll_secret"))
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

                assertTrue(repository.pollPairingStatus("pair-1", "poll-secret").isFailure)
                assertEquals(1, source.requestCount)
                assertEquals(0, target.requestCount)
            }
        }
    }

    private fun fixtureUrl(server: MockWebServer): String =
        server.url("/").newBuilder().host("127.0.0.1").build().toString()
}
