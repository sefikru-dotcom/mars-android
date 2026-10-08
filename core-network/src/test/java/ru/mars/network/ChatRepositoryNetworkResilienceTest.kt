package ru.mars.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import java.util.concurrent.TimeUnit

/**
 * Network-resilience contract of [ChatRepository.sendMessage]: sudden link losses
 * (Wi-Fi <-> LTE handover on POCO F5, DNS failure, TLS failure, server dropping the
 * socket mid-stream) must terminate the flow with a readable [ChatEvent.Error] — the
 * UI must never hang in the waiting state — and a fragmented SSE body must still be
 * parsed without losing any chunk.
 */
class ChatRepositoryNetworkResilienceTest {

    // --- 1. Connection failures surface as a terminal ChatEvent.Error ------------------

    /** Read timeout (radio stalled mid-response / silent agent): SocketTimeoutException. */
    @Test
    fun socketTimeoutEmitsReadableErrorAndCompletesFlow() = runBlocking {
        MockWebServer().use { server ->
            // Server accepts the connection but never answers → client read timeout.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .build()
            val events = ChatRepository(client).sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList() // must COMPLETE, not hang and not throw

            assertEquals(1, events.size)
            val error = events.single() as ChatEvent.Error
            assertTrue(error.message, error.message.contains("время ожидания"))
        }
    }

    /** DNS failure right after a network switch: UnknownHostException. */
    @Test
    fun unknownHostEmitsReadableError() = runBlocking {
        // `.invalid` TLD is guaranteed by RFC 2606 to never resolve.
        val events = ChatRepository().sendMessage(
            "https://mars-server.invalid/chat", credential(), "Hi"
        ).toList()

        assertEquals(1, events.size)
        val error = events.single() as ChatEvent.Error
        assertTrue(error.message, error.message.contains("не удалось определить адрес"))
    }

    /** TLS failure (captive portal / half-open socket after handover): SSLHandshakeException. */
    @Test
    fun sslHandshakeFailureEmitsReadableError() = runBlocking {
        val heldCertificate = HeldCertificate.Builder().commonName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCerts.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("event: chunk\ndata: {}\n\n"))
            // Default OkHttpClient trusts only system CAs → handshake against the
            // self-signed cert fails with SSLHandshakeException.
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList()

            assertEquals(1, events.size)
            val error = events.single() as ChatEvent.Error
            assertTrue(error.message, error.message.contains("SSL"))
        }
    }

    /** Server drops the socket mid-stream (handover kills the in-flight SSE connection). */
    @Test
    fun disconnectDuringStreamBodyEmitsTerminalError() = runBlocking {
        MockWebServer().use { server ->
            val body = buildString {
                repeat(40) { append("event: chunk\ndata: {\"text\":\"t$it\"}\n\n") }
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList() // must COMPLETE with Error, not throw

            val error = events.last() as ChatEvent.Error
            assertTrue(error.message, error.message.isNotBlank())
            // Nothing may be emitted after the terminal error, and the chunks that did
            // arrive before the disconnect must not be lost.
            val chunks = events.filterIsInstance<ChatEvent.Chunk>()
            assertTrue("expected partial chunks before disconnect, got $chunks", chunks.isNotEmpty())
            assertEquals(chunks.map { it.text }, chunks.mapIndexed { i, _ -> "t$i" })
        }
    }

    /** Connection refused (server process gone): still a readable Error, never a throw. */
    @Test
    fun connectionRefusedEmitsReadableError() = runBlocking {
        val port = MockWebServer().use { it.url("/").port } // started + stopped → port is free
        val events = ChatRepository().sendMessage(
            "http://127.0.0.1:$port/chat", credential(), "Hi"
        ).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is ChatEvent.Error)
    }

    // --- 2. Fragmented SSE bodies are parsed without buffer loss ------------------------

    /**
     * Byte-at-a-time throttling forces the transport to fragment frames at every possible
     * boundary: mid-line, between \r and \n of a CRLF terminator, inside multi-byte UTF-8
     * characters. The parser must reassemble everything losslessly.
     */
    @Test
    fun heavilyFragmentedSseBodyIsReassembledLosslessly() = runBlocking {
        MockWebServer().use { server ->
            // Multi-line data frame: one JSON object split across two data: lines — the
            // parser must join them with '\n' so the payload stays parseable.
            val body = "event: chunk\ndata: {\"text\":\"Привет\"}\n\n" +
                "event: chunk\r\ndata: {\"text\":\"мир \uD83D\uDE80\"}\r\n\r\n" +
                "event: chunk\ndata: {\"text\":\ndata: \"joined\"}\n\n" +
                "id: e9\nevent: chunk\ndata: {\"text\":\"tail\"}\n\n" +
                "event: stream_end\ndata: {}\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body)
                    .throttleBody(1, 1, TimeUnit.MILLISECONDS) // 1 byte per packet
            )
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList()

            assertEquals(
                listOf(
                    ChatEvent.Chunk("Привет"),
                    ChatEvent.Chunk("мир \uD83D\uDE80"),
                    ChatEvent.Chunk("joined"),
                    ChatEvent.Chunk("tail", "e9"),
                    ChatEvent.Done,
                ),
                events,
            )
        }
    }

    /** Server closes cleanly right after the final data line, without the terminating blank line. */
    @Test
    fun missingFinalBlankLineStillDeliversLastFrame() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: chunk\ndata: {\"text\":\"A\"}\n\nevent: stream_end\ndata: {}")
            )
            val events = ChatRepository().sendMessage(
                server.url("/chat").toString(), credential(), "Hi"
            ).toList()

            assertEquals(listOf(ChatEvent.Chunk("A"), ChatEvent.Done), events)
        }
    }

    private fun credential() = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600
    )
}
