package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
    fun statusReadsSessionRestore() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"track\":{\"id\":\"v1\",\"title\":\"Song\",\"artist\":\"Artist\",\"duration\":\"3:24\"},\"queue\":[],\"currentIndex\":0,\"currentTime\":12.5,\"volume\":0.7,\"isPlaying\":true}}")
                }
            }))
            val state = MusicRepository(server.url("/").toString().removeSuffix("/")).getStatus()
            assertEquals("v1", state.track?.id)
            assertEquals(PlaybackStatus.PLAYING, state.playback)
            assertEquals(12.5, state.positionSeconds, 0.0)
            server.takeRequest() // the initial websocket HTTP upgrade request
            Unit
        }
    }

    @Test
    fun setVolumeSendsControlCommand() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
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
}
