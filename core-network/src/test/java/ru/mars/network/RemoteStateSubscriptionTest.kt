package ru.mars.network

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.PlaybackStatus
import ru.mars.network.repository.MusicRepository

/**
 * Wire-level tests for [MusicRepository.subscribeRemoteState] — the persistent Mars
 * Connect stream that feeds the mobile app's RemoteQueueSyncController: which frames
 * become [MusicPlayerState] events, which are ignored, and how the subscription closes.
 */
class RemoteStateSubscriptionTest {

    private fun track(id: String) =
        "{\"id\":\"$id\",\"title\":\"T-$id\",\"artist\":\"A-$id\",\"duration\":\"3:00\"}"

    private fun session(
        trackId: String?,
        queue: List<String>,
        currentIndex: Int,
        isPlaying: Boolean,
        activeDeviceId: String,
        timestamp: Long,
    ): String {
        val trackJson = trackId?.let { track(it) } ?: "null"
        val queueJson = queue.joinToString(prefix = "[", postfix = "]") { track(it) }
        return "\"track\":$trackJson,\"queue\":$queueJson,\"currentIndex\":$currentIndex," +
            "\"currentTime\":12.5,\"volume\":0.7,\"isPlaying\":$isPlaying," +
            "\"activeDeviceId\":\"$activeDeviceId\",\"timestamp\":$timestamp"
    }

    @Test
    fun reportsRestoreThenDeviceUpdatesAndIgnoresBadFrames() {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<MusicPlayerState>()
            val serverGotRegister = LinkedBlockingQueue<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Initial snapshot for the registering client.
                        webSocket.send(
                            "{\"type\":\"SESSION_RESTORE\",\"session\":{${session("t1", listOf("t1", "t2"), 1, true, "tv-1", 1_700_000_000_000)}}}",
                        )
                        // A later global change broadcast.
                        webSocket.send(
                            "{\"type\":\"DEVICES_UPDATE\",\"devices\":[],\"globalSession\":{${session("t2", listOf("t2"), 0, false, "tv-1", 1_700_000_001_000)}}}",
                        )
                        // None of these may produce an event or kill the socket:
                        webSocket.send("{\"type\":\"DEVICES_UPDATE\",, broken json")
                        webSocket.send("{\"type\":\"DEVICES_UPDATE\",\"devices\":[]}") // no session
                        webSocket.send("{\"type\":\"EXEC_CMD\",\"action\":\"next\"}") // foreign frame
                        webSocket.send("{\"type\":\"SESSION_RESTORE\"}") // restore without session
                        // A final good frame proves the reader survived the garbage above.
                        webSocket.send(
                            "{\"type\":\"DEVICES_UPDATE\",\"globalSession\":{${session("t3", listOf("t3"), 0, true, "tv-2", 1_700_000_002_000)}}}",
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverGotRegister.add(text)
                    }
                }),
            )

            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val subscription = repo.subscribeRemoteState { received.add(it) }

            val first = received.poll(10, TimeUnit.SECONDS)
            assertEquals("SESSION_RESTORE must map onto the first event", "t1", first?.track?.id)
            assertEquals(listOf("t1", "t2"), first?.queue?.map { it.id })
            assertEquals(1, first?.currentIndex)
            assertEquals(PlaybackStatus.PLAYING, first?.playback)
            assertEquals("tv-1", first?.activeDeviceId)
            assertEquals(1_700_000_000_000L, first?.timestampMs)

            val second = received.poll(10, TimeUnit.SECONDS)
            assertEquals("DEVICES_UPDATE(globalSession) must map onto an event", "t2", second?.track?.id)
            assertEquals(PlaybackStatus.PAUSED, second?.playback)
            assertEquals(1_700_000_001_000L, second?.timestampMs)

            val third = received.poll(10, TimeUnit.SECONDS)
            assertEquals("t3", third?.track?.id)
            assertEquals("tv-2", third?.activeDeviceId)

            assertNull(
                "bad/foreign frames must not produce extra events",
                received.poll(1, TimeUnit.SECONDS),
            )

            val register = serverGotRegister.poll(10, TimeUnit.SECONDS)
            assertTrue("subscription must REGISTER like every other connect", register?.contains("\"type\":\"REGISTER\"") == true)
            assertTrue(register?.contains("\"id\":\"${repo.deviceId}\"") == true)

            subscription.close()
        }
    }

    @Test
    fun closeStopsDeliveryAndSurvivesAfterSocketDeath() {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<MusicPlayerState>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            "{\"type\":\"SESSION_RESTORE\",\"session\":{${session("t1", listOf("t1"), 0, false, "tv-1", 5)}}}",
                        )
                    }
                }),
            )
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            val subscription = repo.subscribeRemoteState { received.add(it) }
            assertEquals("t1", received.poll(10, TimeUnit.SECONDS)?.track?.id)

            subscription.close()
            // Closing twice or after the socket died must not throw.
            subscription.close()
            assertNull(received.poll(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun throwingReceiverDoesNotKillTheStream() {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<MusicPlayerState>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            "{\"type\":\"SESSION_RESTORE\",\"session\":{${session("boom", listOf("boom"), 0, false, "tv-1", 1)}}}",
                        )
                        webSocket.send(
                            "{\"type\":\"DEVICES_UPDATE\",\"globalSession\":{${session("after", listOf("after"), 0, false, "tv-1", 2)}}}",
                        )
                    }
                }),
            )
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            val subscription = repo.subscribeRemoteState { state ->
                if (state.track?.id == "boom") throw IllegalStateException("bad receiver")
                received.add(state)
            }
            assertEquals("after", received.poll(10, TimeUnit.SECONDS)?.track?.id)
            subscription.close()
        }
    }
}
