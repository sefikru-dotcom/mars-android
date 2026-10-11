package ru.mars.network.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy regression for the shared clients: the timeout split (long SSE read vs
 * bounded REST), the single shared pool/dispatcher, and the deliberate absence of
 * callTimeout/pingInterval (an HTTP ping is NOT an SSE heartbeat — see KDoc).
 */
class HttpClientsTest {

    @Test
    fun `stream read timeout covers long tool-call idle gaps`() {
        // The server writes app-level SSE heartbeat comments every ~5 s while the agent
        // works, but GIL pauses / slow proxies can stretch gaps; >= 60 s keeps a healthy
        // turn alive and still bounds dead-socket detection to ~1.5 min.
        assertTrue(HttpClients.STREAM_READ_TIMEOUT_MS >= 60_000L)
        assertEquals(HttpClients.STREAM_READ_TIMEOUT_MS.toInt(), HttpClients.stream.readTimeoutMillis)
    }

    @Test
    fun `rest client is bounded and tighter than the stream client`() {
        assertTrue(HttpClients.REST_READ_TIMEOUT_MS < HttpClients.STREAM_READ_TIMEOUT_MS)
        assertEquals(HttpClients.CONNECT_TIMEOUT_MS.toInt(), HttpClients.rest.connectTimeoutMillis)
        assertEquals(HttpClients.REST_READ_TIMEOUT_MS.toInt(), HttpClients.rest.readTimeoutMillis)
        assertEquals(HttpClients.WRITE_TIMEOUT_MS.toInt(), HttpClients.rest.writeTimeoutMillis)
        assertTrue(HttpClients.rest.readTimeoutMillis <= 30_000)
    }

    @Test
    fun `both clients share ONE pool and dispatcher`() {
        assertSame(HttpClients.connectionPool, HttpClients.rest.connectionPool)
        assertSame(HttpClients.connectionPool, HttpClients.stream.connectionPool)
        assertSame(HttpClients.dispatcher, HttpClients.rest.dispatcher)
        assertSame(HttpClients.dispatcher, HttpClients.stream.dispatcher)
        // stream and rest are distinct configurations of the same base
        assertTrue(HttpClients.stream.readTimeoutMillis > HttpClients.rest.readTimeoutMillis)
    }

    @Test
    fun `no global callTimeout - SSE calls last a whole turn, uploads are phase-bounded`() {
        assertEquals(0, HttpClients.stream.callTimeoutMillis)
        assertEquals(0, HttpClients.rest.callTimeoutMillis)
    }

    @Test
    fun `pingInterval stays off - it is not an SSE heartbeat and this server is HTTP-1_1`() {
        // OkHttp pings only exist on HTTP/2 and WebSockets; claiming they keep an SSE
        // stream alive would be false. Liveness = server heartbeat comments + readTimeout.
        assertEquals(0, HttpClients.stream.pingIntervalMillis)
        assertEquals(0, HttpClients.rest.pingIntervalMillis)
    }

    @Test
    fun `builders inherit the shared infrastructure and allow test overrides`() {
        val custom = HttpClients.restBuilder()
            .readTimeout(123, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        assertSame(HttpClients.connectionPool, custom.connectionPool)
        assertSame(HttpClients.dispatcher, custom.dispatcher)
        assertEquals(123, custom.readTimeoutMillis)
        // The template itself is untouched by builder overrides.
        assertEquals(HttpClients.REST_READ_TIMEOUT_MS.toInt(), HttpClients.rest.readTimeoutMillis)
        assertSame(HttpClients.connectionPool, HttpClients.streamBuilder().build().connectionPool)
    }
}
