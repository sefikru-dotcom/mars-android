package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.HealthLevel
import ru.mars.network.repository.ServerHealthRepository
import java.util.concurrent.TimeUnit

class ServerHealthRepositoryTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Test
    fun healthyServerReportsHealthyWithMeasuredLatency() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/status", request.path)

            assertEquals(HealthLevel.HEALTHY, report.status)
            assertTrue("latency must be non-negative, was ${report.latencyMs}", report.latencyMs >= 0L)
            assertEquals(server.url("/api/status").toString(), report.checkedUrl)
            assertEquals("HTTP 200", report.message)
            assertTrue("timestamp must be recent", report.timestampMs <= System.currentTimeMillis())
            assertTrue("timestamp must be set", report.timestampMs > 0L)
        }
    }

    @Test
    fun customEndpointPathIsRequested() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth(endpointPath = "health/ping")

            assertEquals("/health/ping", server.takeRequest().path)
            assertEquals(HealthLevel.HEALTHY, report.status)
            assertEquals("HTTP 204", report.message)
            assertTrue(report.checkedUrl.endsWith("/health/ping"))
        }
    }

    @Test
    fun leadingSlashInEndpointPathDoesNotDuplicate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            val repo = ServerHealthRepository(client, server.url("/").toString())

            repo.checkHealth(endpointPath = "/api/status")

            assertEquals("/api/status", server.takeRequest().path)
        }
    }

    @Test
    fun serverErrorIsOffline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth()

            assertEquals(HealthLevel.OFFLINE, report.status)
            assertEquals("HTTP 503", report.message)
            assertTrue(report.latencyMs >= 0L)
        }
    }

    @Test
    fun notFoundIsOffline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth()

            assertEquals(HealthLevel.OFFLINE, report.status)
            assertEquals("HTTP 404", report.message)
        }
    }

    @Test
    fun timeoutProducesOfflineReportWithMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("slow")
                    .setBodyDelay(3, TimeUnit.SECONDS),
            )
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth(timeoutMs = 300L)

            assertEquals(HealthLevel.OFFLINE, report.status)
            assertNotNull(report.message)
            assertTrue(report.latencyMs >= 0L)
            assertEquals(server.url("/api/status").toString(), report.checkedUrl)
        }
    }

    @Test
    fun connectionRefusedProducesOfflineReport() = runBlocking {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown()

        val repo = ServerHealthRepository(client, url)
        val report = repo.checkHealth()

        assertEquals(HealthLevel.OFFLINE, report.status)
        assertNotNull("failure report must carry a message", report.message)
        assertTrue(report.checkedUrl.endsWith("/api/status"))
    }

    @Test
    fun slowButSuccessfulResponseWithinTimeoutIsHealthy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("ok")
                    .setBodyDelay(400, TimeUnit.MILLISECONDS),
            )
            val repo = ServerHealthRepository(client, server.url("/").toString())

            val report = repo.checkHealth(timeoutMs = 5_000L)

            // 400 ms delay is normally inside the healthy band, but a loaded CI
            // box may push it into DEGRADED — the invariant is "not OFFLINE".
            assertTrue("expected HEALTHY/DEGRADED, got ${report.status}", report.status != HealthLevel.OFFLINE)
            assertTrue("latency should reflect the delay, was ${report.latencyMs}", report.latencyMs >= 300L)
        }
    }

    @Test
    fun defaultBaseUrlPointsAtProductionApi() {
        val repo = ServerHealthRepository(client)
        // Only verify the default is wired; no network call is made here.
        assertNotNull(repo)
        assertEquals("https://marscore.ru", ru.mars.network.api.MarsApi.BASE_URL)
    }
}
