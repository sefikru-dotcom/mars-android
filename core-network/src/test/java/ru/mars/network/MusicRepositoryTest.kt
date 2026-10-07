package ru.mars.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.PlaybackStatus
import ru.mars.network.repository.MusicRepository

class MusicRepositoryTest {
    @Test
    fun searchUsesEncodedQueryAndMapsTracks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[{\"type\":\"song\",\"id\":\"v1\",\"title\":\"Song\",\"artist\":\"Artist\",\"duration\":\"3:24\",\"thumbnail\":\"thumb\"}]}"))
            val result = MusicRepository(server.url("/").toString().removeSuffix("/")).search("rock & roll")
            assertEquals("Song", result.single().title)
            assertEquals("/api/search?q=rock%20%26%20roll", server.takeRequest().path)
        }
    }

    @Test
    fun streamUrlAndChartsUseMusicBasePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[{\"type\":\"song\",\"id\":\"v1\",\"title\":\"Song\",\"artist\":\"Artist\"}]}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            assertEquals("${server.url("/music").toString().removeSuffix("/")}/api/stream/v1", repo.getStreamUrl("v1"))
            assertEquals("v1", repo.getCharts().single().id)
            assertEquals("/music/api/charts", server.takeRequest().path)
        }
    }

    @Test
    fun chartsRealEnvelopeIsNotEmptyAndFailureSurfaces() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[{\"type\":\"song\",\"id\":\"c1\",\"title\":\"One\",\"artist\":\"A\",\"duration\":\"2:10\"},{\"type\":\"song\",\"id\":\"c2\",\"title\":\"Two\",\"artist\":\"B\"}]}"))
            server.enqueue(MockResponse().setBody("{\"success\":false,\"error\":\"upstream down\"}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            val charts = repo.getCharts()
            assertEquals(listOf("c1", "c2"), charts.map { it.id })
            assertEquals("2:10", charts[0].duration)
            val failure = runCatching { repo.getCharts() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("upstream down"))
        }
    }

    @Test
    fun statusReadsSessionRestore() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"track\":{\"id\":\"v1\",\"title\":\"Song\",\"artist\":\"Artist\",\"duration\":\"3:24\"},\"queue\":[],\"currentIndex\":0,\"currentTime\":12.5,\"volume\":0.7,\"isPlaying\":true,\"activeDeviceId\":\"tv-1\"}}")
                }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    assertEquals(1000, code)
                    webSocket.close(1000, null)
                }
            }))
            val state = MusicRepository(server.url("/").toString().removeSuffix("/")).getStatus()
            assertEquals("v1", state.track?.id)
            assertEquals(PlaybackStatus.PLAYING, state.playback)
            assertEquals(12.5, state.positionSeconds, 0.0)
            assertEquals("tv-1", state.activeDeviceId)
            server.takeRequest() // the initial websocket HTTP upgrade request
            Unit
        }
    }

    @Test
    fun setVolumeSendsControlCommand() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"queue\":[],\"activeDeviceId\":\"tv-1\"}}")
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    if (text.contains("REGISTER")) return
                    assertTrue(text.contains("setVolume"))
                    assertTrue(text.contains("0.4"))
                    webSocket.close(1000, "ok")
                }
            }))
            MusicRepository(server.url("/").toString().removeSuffix("/")).setVolume(0.4)
        }
    }

    @Test
    fun controlCommandTargetsActiveDeviceAndClosesGracefully() = runBlocking {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<String>()
            val serverSawClose = CountDownLatch(1)
            val closeCode = AtomicInteger(-1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"track\":{\"id\":\"v1\",\"title\":\"Song\",\"artist\":\"Artist\",\"duration\":\"3:24\"},\"queue\":[],\"currentIndex\":0,\"currentTime\":1.0,\"volume\":0.8,\"isPlaying\":true,\"activeDeviceId\":\"tv-1\"}}")
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { received.offer(text) }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    closeCode.set(code)
                    serverSawClose.countDown()
                    webSocket.close(1000, null)
                }
            }))
            MusicRepository(server.url("/").toString().removeSuffix("/")).next()

            val controlCmd = awaitMessage(received, "CONTROL_CMD")
            assertNotNull("client never sent CONTROL_CMD", controlCmd)
            assertTrue(controlCmd!!.contains("\"targetDeviceId\":\"tv-1\""))
            assertFalse(controlCmd.contains("\"targetDeviceId\":\"all\""))
            assertTrue(controlCmd.contains("\"action\":\"next\""))

            // Graceful shutdown: server must observe a close frame with code 1000,
            // not an abrupt cancel (which surfaces as onFailure/EOF instead).
            assertTrue("client cancelled instead of closing gracefully", serverSawClose.await(5, TimeUnit.SECONDS))
            assertEquals(1000, closeCode.get())
        }
    }

    @Test
    fun controlCommandFallsBackToAllOnlyWithoutActiveDevice() = runBlocking {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"queue\":[]}}")
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { received.offer(text) }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            MusicRepository(server.url("/").toString().removeSuffix("/")).play()
            val controlCmd = awaitMessage(received, "CONTROL_CMD")
            assertNotNull(controlCmd)
            assertTrue(controlCmd!!.contains("\"targetDeviceId\":\"all\""))
            assertTrue(controlCmd.contains("\"action\":\"playPause\""))
        }
    }

    private fun awaitMessage(queue: LinkedBlockingQueue<String>, marker: String): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val text = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (text.contains(marker)) return text
        }
        return null
    }
}
