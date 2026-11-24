package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прямой нейросетевой синтез Edge: DRM-токен `Sec-MS-GEC` v2 и протокол WebSocket.
 *
 * Проверяется то, что нельзя проверить на устройстве без сети и что ломается МОЛЧА:
 * 1. `Sec-MS-GEC` совпадает с эталоном `rany2/edge-tts` побитово. Неверный токен сервер
 *    отвергает на апгрейде, и приложение просто молчит — без единой ошибки в логах.
 * 2. Текст уезжает в SSML экранированным: `&`/`<` в ответе Марса рвут XML, и сервер
 *    присылает `turn.end` без аудио.
 * 3. Бинарный фрейм разбирается по 2-байтовому префиксу длины заголовка: аудио — только
 *    хвост после `Path:audio`, а не весь фрейм (иначе mp3 начинается с мусора).
 */
class EdgeNeuralTtsClientTest {

    // --- DRM ---------------------------------------------------------------------------

    /**
     * Эталонные векторы сняты с `edge_tts.drm.generate_sec_ms_gec_v2` (edge-tts 7.3.1) —
     * то есть с ТОГО САМОГО клиента, протокол которого мы повторяем.
     *
     * В контракте Волны 20 для `1700000000` указан токен
     * `B5B8D21922D2E1D8-65D1B9…`. Он не может быть верным: соль
     * `1334447340000000006A5AA1D4EAFF4E9FB37E23D68491D6F4` (она в контракте указана
     * правильно) хешируется в `42301B33…` обычным SHA-256, а CS64-половина от неё —
     * `2CD23A2C…`. Обе половины проверены здесь независимо: см. `saltMatchesContract`.
     */
    @Test
    fun `sec ms gec v2 matches the reference client`() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_000L),
        )
        assertEquals(
            "50065ABF67B90F95-49BDAF24D28ECEB38DAEEC84DEAA71E91F92945EA8A47921C58FB4F8F79A4F5E",
            EdgeNeuralTtsClient.generateSecMsGecV2(1_730_000_000L),
        )
        assertEquals(
            "CFFBDCAAAC147868-837FA01B019983EDFC7F904B5A523519A17EDC330D192F78D91210763EC08003",
            EdgeNeuralTtsClient.generateSecMsGecV2(1_763_316_000L),
        )
    }

    @Test
    fun `salt matches the contract vector`() {
        // Соль — единственная часть токена, которую контракт называет явно и верно.
        assertEquals(
            "1334447340000000006A5AA1D4EAFF4E9FB37E23D68491D6F4",
            String(EdgeNeuralTtsClient.saltFor(1_700_000_000L), Charsets.US_ASCII),
        )
        assertEquals(
            "1164447360000000006A5AA1D4EAFF4E9FB37E23D68491D6F4",
            String(EdgeNeuralTtsClient.saltFor(0L), Charsets.US_ASCII),
        )
    }

    @Test
    fun `token is stable inside the five minute window and changes outside it`() {
        // Сервер допускает расхождение часов, но окно — ровно 300 с: внутри него токен
        // ОБЯЗАН быть одинаковым (иначе кэш бессмыслен), на границе — другим.
        // Окно для 1700000000 — [1699999800, 1700000100).
        assertEquals(
            EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_000L),
            EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_099L),
        )
        assertNotEquals(
            EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_000L),
            EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_100L),
        )
    }

    @Test
    fun `token has the 16 plus 64 hex shape and uppercase digits`() {
        val token = EdgeNeuralTtsClient.generateSecMsGecV2(1_700_000_000L)
        val parts = token.split('-')

        assertEquals(2, parts.size)
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        assertTrue("токен обязан быть в верхнем регистре: $token", token == token.uppercase())
    }

    // --- Подготовка текста и SSML -------------------------------------------------------

    @Test
    fun `control characters are stripped but line breaks survive`() {
        val dirty = "при\u0000вет\u0007\nмир\t!\u001F"

        val clean = EdgeNeuralTtsClient.cleanText(dirty)

        assertEquals("привет\nмир\t!", clean)
    }

    @Test
    fun `xml specials are escaped in the right order`() {
        assertEquals(
            "a &amp; b &lt;tag&gt; &apos;x&apos; &quot;y&quot;",
            EdgeNeuralTtsClient.escapeXml("a & b <tag> 'x' \"y\""),
        )
    }

    @Test
    fun `ssml carries the voice and the prosody`() {
        val ssml = EdgeNeuralTtsClient.ssmlFrame(
            text = "привет",
            voice = "ru-RU-SvetlanaNeural",
            rate = "+8%",
            pitch = "+0Hz",
            nowSeconds = 1_700_000_000L,
        )

        assertTrue(ssml.contains("Path:ssml\r\n\r\n"))
        assertTrue(ssml.contains("<voice name='ru-RU-SvetlanaNeural'>"))
        assertTrue(ssml.contains("pitch='+0Hz'"))
        assertTrue(ssml.contains("rate='+8%'"))
        assertTrue(ssml.contains(">привет</prosody>"))
        assertTrue(ssml.contains("xml:lang='ru-RU'"))
    }

    @Test
    fun `ssml falls back to neutral prosody and escapes the text`() {
        val ssml = EdgeNeuralTtsClient.ssmlFrame(
            text = "5 < 7 & 8 > 3",
            voice = "en-US-AvaMultilingualNeural",
            rate = null,
            pitch = null,
            nowSeconds = 1_700_000_000L,
        )

        assertTrue(ssml.contains("rate='+0%'"))
        assertTrue(ssml.contains("pitch='+0Hz'"))
        assertTrue(ssml.contains("5 &lt; 7 &amp; 8 &gt; 3"))
        assertFalse("сырой амперсанд в SSML недопустим", ssml.contains("& 8"))
    }

    @Test
    fun `speech config asks for mp3 and disables word boundaries`() {
        val config = EdgeNeuralTtsClient.speechConfigFrame(1_700_000_000L)

        assertTrue(config.contains("Path:speech.config\r\n\r\n"))
        assertTrue(config.contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
        assertTrue(config.contains("\"wordBoundaryEnabled\":\"false\""))
        assertTrue(config.contains("X-Timestamp:"))
    }

    @Test
    fun `connection id and muid are 32 uppercase hex characters`() {
        val id = EdgeNeuralTtsClient.randomHex32()

        assertEquals(32, id.length)
        assertTrue("только hex: $id", id.all { it in '0'..'9' || it in 'A'..'F' })
        assertNotEquals("два вызова не должны совпасть", id, EdgeNeuralTtsClient.randomHex32())
    }

    // --- Протокол сокета ----------------------------------------------------------------

    @Test
    fun `empty text is rejected without opening a socket`() {
        MockWebServer().use { server ->
            val result = runBlocking {
                EdgeNeuralTtsClient(wssUrl = server.url("/").toString())
                    .synthesize("   \u0000  ", "ru-RU-SvetlanaNeural")
            }

            assertTrue(result is ServerTtsResult.Unavailable)
            assertEquals(
                EdgeNeuralTtsClient.REASON_EMPTY_TEXT,
                (result as ServerTtsResult.Unavailable).reason,
            )
            assertEquals("сокет не должен открываться", 0, server.requestCount)
        }
    }

    @Test
    fun `audio payload after the header is returned as mp3`() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0x20, 0x30)
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        /** Ответ собирается только после `Path:ssml`: до него синтез не начат. */
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!text.contains("Path:ssml")) return
                            webSocket.send(audioFrame(audio).toByteString())
                            webSocket.send("X-RequestId:1\r\nPath:turn.end\r\n")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )

            val result = runBlocking {
                EdgeNeuralTtsClient(wssUrl = server.url("/").toString())
                    .synthesize("привет", "ru-RU-SvetlanaNeural", "+8%", "+0Hz")
            }

            assertTrue("ожидалось аудио, получено $result", result is ServerTtsResult.Audio)
            val bytes = (result as ServerTtsResult.Audio)
            assertEquals("audio/mpeg", bytes.contentType)
            assertArrayEquals(audio, bytes.bytes)
        }
    }

    @Test
    fun `turn end without audio is an unavailable result not silence`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!text.contains("Path:ssml")) return
                            webSocket.send("X-RequestId:1\r\nPath:turn.end\r\n")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )

            val result = runBlocking {
                EdgeNeuralTtsClient(wssUrl = server.url("/").toString())
                    .synthesize("привет", "ru-RU-SvetlanaNeural")
            }

            assertTrue(result is ServerTtsResult.Unavailable)
            assertEquals(
                EdgeNeuralTtsClient.REASON_EMPTY_AUDIO,
                (result as ServerTtsResult.Unavailable).reason,
            )
        }
    }

    @Test
    fun `refused upgrade is an unavailable result not an exception`() {
        MockWebServer().use { server ->
            // Не апгрейд, а обычный 403: так выглядит блокировка провайдера/DRM-отказ.
            server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

            val result = runBlocking {
                EdgeNeuralTtsClient(wssUrl = server.url("/").toString())
                    .synthesize("привет", "ru-RU-SvetlanaNeural")
            }

            assertTrue(result is ServerTtsResult.Unavailable)
            assertEquals(
                EdgeNeuralTtsClient.REASON_TRANSPORT,
                (result as ServerTtsResult.Unavailable).reason,
            )
        }
    }

    @Test
    fun `ssml frame is sent with the requested voice`() {
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            received += text
                            if (!text.contains("Path:ssml")) return
                            webSocket.send(audioFrame(byteArrayOf(1)).toByteString())
                            webSocket.send("X-RequestId:1\r\nPath:turn.end\r\n")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                    },
                ),
            )

            runBlocking {
                EdgeNeuralTtsClient(wssUrl = server.url("/").toString())
                    .synthesize("текст", "en-US-AvaMultilingualNeural", "+5%", "+0Hz")
            }

            assertEquals(2, received.size)
            assertTrue(received[0].contains("Path:speech.config"))
            assertTrue(received[1].contains("Path:ssml"))
            assertTrue(received[1].contains("<voice name='en-US-AvaMultilingualNeural'>"))
            assertTrue(received[1].contains("rate='+5%'"))
        }
    }

    /** Бинарный фрейм сервера: 2 байта длины заголовка (big-endian) + заголовок + аудио. */
    private fun audioFrame(audio: ByteArray): ByteArray {
        val header = "X-RequestId:1\r\nPath:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        val frame = ByteArray(2 + header.size + audio.size)
        frame[0] = ((header.size shr 8) and 0xFF).toByte()
        frame[1] = (header.size and 0xFF).toByte()
        header.copyInto(frame, 2)
        audio.copyInto(frame, 2 + header.size)
        return frame
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "байты аудио разошлись: ${actual.toList()} != ${expected.toList()}",
            expected.toList(),
            actual.toList(),
        )
    }
}
