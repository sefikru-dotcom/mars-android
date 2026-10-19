package ru.mars.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
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
    fun favoritesUsesFavoritesEnvelopeAndBlankDurationFallsBack() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"favorites\":[{\"id\":\"f1\",\"title\":\"Fav\",\"artist\":\"A\",\"duration\":\"\",\"thumbnail\":\"/music/api/thumb?v=f1\"},{\"id\":\"f2\",\"title\":\"Second\",\"artist\":\"B\",\"duration\":\"4:05\"}]}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val favorites = repo.getFavorites()
            assertEquals(listOf("f1", "f2"), favorites.map { it.id })
            assertEquals("--:--", favorites[0].duration) // live rows may carry ""
            assertEquals("4:05", favorites[1].duration)
            assertEquals("/music/api/favorites", server.takeRequest().path)
        }
    }

    @Test
    fun emptyFavoritesAreANormalEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"favorites\":[]}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            assertEquals(emptyList<MusicTrack>(), repo.getFavorites())
        }
    }

    @Test
    fun flowMapsResultsEnvelope() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[{\"type\":\"song\",\"id\":\"fl1\",\"title\":\"Flow One\",\"artist\":\"A\",\"duration\":\"3:00\"},{\"type\":\"song\",\"id\":\"fl2\",\"title\":\"Flow Two\",\"artist\":\"B\"}]}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val flow = repo.getFlow()
            assertEquals(listOf("fl1", "fl2"), flow.map { it.id })
            assertEquals("3:00", flow[0].duration)
            assertEquals("--:--", flow[1].duration) // missing duration falls back
            assertEquals("/music/api/flow", server.takeRequest().path)
        }
    }

    @Test
    fun flowMapsTracksEnvelopeAndSurfacesFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"tracks\":[{\"id\":\"t1\",\"title\":\"Tracks Key\",\"artist\":\"A\"}]}"))
            server.enqueue(MockResponse().setBody("{\"success\":false,\"error\":\"flow unavailable\"}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            // The `{tracks}` envelope key is the third fallback after results/favorites.
            assertEquals("t1", repo.getFlow().single().id)
            val failure = runCatching { repo.getFlow() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("flow unavailable"))
        }
    }

    @Test
    fun radioUsesVideoIdPathAndMapsTracks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[{\"id\":\"r1\",\"title\":\"Radio One\",\"artist\":\"A\",\"duration\":\"2:30\"},{\"id\":\"r2\",\"title\":\"Radio Two\",\"artist\":\"B\"}]}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val radio = repo.getRadio("v1")
            assertEquals(listOf("r1", "r2"), radio.map { it.id })
            assertEquals("2:30", radio[0].duration)
            assertEquals("/music/api/radio/v1", server.takeRequest().path)
        }
    }

    @Test
    fun radioRejectsBlankVideoId() {
        val error = runBlocking {
            runCatching { MusicRepository("http://localhost/music").getRadio(" ") }.exceptionOrNull()
        }
        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }

    @Test
    fun lyricsParsesAllFieldsAndSendsEncodedQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"lyrics\":\"Line one\\nLine two\",\"synced\":\"[00:12.00] Line one\",\"source\":\"musixmatch\"}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val lyrics = repo.getLyrics("v1", title = "Rock & Roll", artist = "The Band")
            assertEquals(LyricsResult("Line one\nLine two", "[00:12.00] Line one", "musixmatch"), lyrics)
            assertEquals("/music/api/lyrics/v1?title=Rock%20%26%20Roll&artist=The%20Band", server.takeRequest().path)
        }
    }

    @Test
    fun lyricsOmitsNullQueryParamsAndToleratesMissingFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            // No title/artist → no query string at all; empty body → all-null result.
            assertEquals(LyricsResult(null, null, null), repo.getLyrics("v1"))
            assertEquals("/api/lyrics/v1", server.takeRequest().path)
        }
    }

    @Test
    fun lyricsSurfacesHttpAndMalformedErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("not json"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            val httpError = runCatching { repo.getLyrics("v1") }.exceptionOrNull()
            assertNotNull(httpError)
            assertTrue(httpError!!.message!!.contains("404"))
            val malformed = runCatching { repo.getLyrics("v1") }.exceptionOrNull()
            assertTrue("expected IOException for malformed body, got $malformed", malformed is IOException)
            assertTrue(malformed!!.message!!.contains("Malformed"))
        }
    }

    @Test
    fun lyricsRejectsBlankVideoId() {
        val error = runBlocking {
            runCatching { MusicRepository("http://localhost/music").getLyrics("") }.exceptionOrNull()
        }
        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }

    @Test
    fun toggleFavoritePostsBothIdKeysWithMetadataAndParsesFavorited() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":true}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val result = repo.toggleFavorite("v1", MusicTrack("v1", "Song", "Artist", "3:24", "thumb"))
            assertEquals(true, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/music/api/favorite/toggle", request.path)
            val body = request.body.readUtf8()
            // The live service reads "id"; the documented contract says "video_id" — send both.
            assertTrue(body.contains("\"id\":\"v1\""))
            assertTrue(body.contains("\"video_id\":\"v1\""))
            assertTrue(body.contains("\"title\":\"Song\""))
            assertTrue(body.contains("\"artist\":\"Artist\""))
            assertTrue(body.contains("\"duration\":\"3:24\""))
            assertTrue(body.contains("\"thumbnail\":\"thumb\""))
            // A track without an album must not send an album object at all.
            assertFalse(body.contains("\"album\""))
        }
    }

    @Test
    fun toggleFavoriteSendsAlbumAsNameAndBrowseIdWhenTrackCarriesOne() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":true}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val track = MusicTrack(
                id = "v1",
                title = "Song",
                artist = "Artist",
                duration = "3:24",
                thumbnail = "thumb",
                albumTitle = "One-X",
                albumBrowseId = "MPREb_3vrgtNoMEJi",
            )
            assertEquals(true, repo.toggleFavorite("v1", track))
            val body = server.takeRequest().body.readUtf8()
            // Wire shape is the service contract: name + browseId, not albumTitle/albumBrowseId.
            assertTrue(body.contains("\"album\":{\"name\":\"One-X\",\"browseId\":\"MPREb_3vrgtNoMEJi\"}"))
        }
    }

    @Test
    fun toggleFavoriteSendsAlbumWithEmptyBrowseIdWhenOnlyNameIsKnown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":true}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            // /api/album returns the album as a bare string, so a track taken from an album
            // page knows its name but has no browse id. The name must still be stored.
            val track = MusicTrack("v1", "Song", "Artist", albumTitle = "Hajime, Pt. 1")
            assertEquals(true, repo.toggleFavorite("v1", track))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"album\":{\"name\":\"Hajime, Pt. 1\",\"browseId\":\"\"}"))
        }
    }

    @Test
    fun toggleFavoriteOmitsAlbumWhenNameIsBlank() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":true}"))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val track = MusicTrack("v1", "Song", "Artist", albumTitle = "   ", albumBrowseId = "MPREb_x")
            assertEquals(true, repo.toggleFavorite("v1", track))
            val body = server.takeRequest().body.readUtf8()
            // A blank name would be stored as a real album title; better an honest NULL.
            assertFalse(body.contains("\"album\""))
        }
    }

    @Test
    fun toggleFavoriteUnfavoritesAndOmitsMissingMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":false}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            assertEquals(false, repo.toggleFavorite("v1"))
            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("title"))
            assertFalse(body.contains("thumbnail"))
        }
    }

    @Test
    fun toggleFavoriteReturnsNullWhenServerCannotDetermineState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"status\":\"error\"}"))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            assertNull(repo.toggleFavorite("unknown"))
        }
    }

    @Test
    fun toggleFavoriteRejectsBlankIdAndSurfacesHttpErrors() {
        val blankError = runBlocking { runCatching { MusicRepository("http://localhost/music").toggleFavorite(" ") }.exceptionOrNull() }
        assertTrue(blankError is IllegalArgumentException)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val httpError = runBlocking {
                runCatching { MusicRepository(server.url("/").toString().removeSuffix("/")).toggleFavorite("v1") }.exceptionOrNull()
            }
            assertNotNull(httpError)
            assertTrue(httpError!!.message!!.contains("500"))
        }
    }

    /**
     * Regression (locked invariant "URLs never get a double slash"): a trailing-slash
     * base URL must not leak `//` onto the wire. The GET/WS calls build their URLs with
     * `addPathSegments` (which replaces the trailing empty segment), while the
     * `toggleFavorite` POST (`postJson`) and `getStreamUrl` used to concatenate
     * `"$baseUrl/$path"` — with `https://…/music/` that sent
     * `/music//api/favorite/toggle`, which strict routers answer with 404.
     */
    @Test
    fun trailingSlashBaseUrlNeverProducesDoubleSlashes() = runBlocking {
        MockWebServer().use { server ->
            val withSlash = server.url("/music/").toString() // trailing slash kept on purpose
            val withoutSlash = withSlash.removeSuffix("/")
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\",\"favorited\":true}"))
            server.enqueue(MockResponse().setBody("{\"success\":true,\"results\":[]}"))
            val repo = MusicRepository(withSlash)

            assertEquals(true, repo.toggleFavorite("v1"))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/music/api/favorite/toggle", post.path)

            repo.getCharts() // GET seam keeps normalizing (guard against regressions)
            assertEquals("/music/api/charts", server.takeRequest().path)

            assertEquals("$withoutSlash/api/stream/v1", repo.getStreamUrl("v1"))
        }
    }

    /** The stream URL percent-encodes the track id: a malformed id cannot bend the path. */
    @Test
    fun streamUrlEncodesTrackId() {
        val repo = MusicRepository("https://marscore.ru/music")
        assertEquals("https://marscore.ru/music/api/stream/a%20b", repo.getStreamUrl("a b"))
    }

    @Test
    fun webSocketUrlMapsBothSchemes() {
        // Kotlin's String.replaceFirst(String, String) is literal, so the previous
        // "^http" regex pattern silently did nothing; the helper must map explicitly.
        assertEquals(
            "ws://marscore.ru/music/api/connect/ws",
            MusicRepository.webSocketUrl("http://marscore.ru/music/api/connect/ws"),
        )
        assertEquals(
            "wss://marscore.ru/music/api/connect/ws",
            MusicRepository.webSocketUrl("https://marscore.ru/music/api/connect/ws"),
        )
        // Non-http schemes are left untouched.
        assertEquals("ws://h/p", MusicRepository.webSocketUrl("ws://h/p"))
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
    fun statusIgnoresDevicesUpdateAndUsesRealSessionRestore() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    // The live server broadcasts DEVICES_UPDATE (which also carries
                    // globalSession) right after REGISTER; it must not be consumed
                    // as the restore.
                    webSocket.send("{\"type\":\"DEVICES_UPDATE\",\"devices\":[],\"globalSession\":{\"track\":{\"id\":\"stale\",\"title\":\"S\",\"artist\":\"A\"},\"queue\":[],\"currentIndex\":0,\"currentTime\":99.0,\"volume\":1.0,\"isPlaying\":true,\"activeDeviceId\":\"stale-device\"}}")
                    webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"track\":{\"id\":\"real\",\"title\":\"R\",\"artist\":\"A\",\"duration\":\"2:00\"},\"queue\":[],\"currentIndex\":1,\"currentTime\":3.0,\"volume\":0.5,\"isPlaying\":true,\"activeDeviceId\":\"tv-real\"}}")
                }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }))
            val state = MusicRepository(server.url("/").toString().removeSuffix("/")).getStatus()
            assertEquals("real", state.track?.id)
            assertEquals("tv-real", state.activeDeviceId)
            assertEquals(3.0, state.positionSeconds, 0.0)
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
    fun duplicateSessionRestoreProducesSingleControlCommand() = runBlocking {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<String>()
            val serverSawClose = CountDownLatch(1)
            val restore = "{\"type\":\"SESSION_RESTORE\",\"session\":{\"queue\":[],\"activeDeviceId\":\"tv-1\"}}"
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send(restore)
                    webSocket.send(restore) // duplicate frames must be ignored
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { received.offer(text) }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    serverSawClose.countDown()
                    webSocket.close(1000, null)
                }
            }))
            MusicRepository(server.url("/").toString().removeSuffix("/")).next()
            assertTrue(serverSawClose.await(5, TimeUnit.SECONDS))

            val texts = mutableListOf<String>()
            received.drainTo(texts)
            assertEquals("exactly one CONTROL_CMD for duplicated SESSION_RESTORE", 1, texts.count { it.contains("CONTROL_CMD") })
        }
    }

    @Test
    fun commandFailsWithoutActiveDeviceAndNeverBroadcasts() = runBlocking {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<String>()
            val serverDone = CountDownLatch(1)
            // REGISTER отправляет клиент на открытии сокета. Ответ SESSION_RESTORE шлём
            // не сразу, а only после прихода REGISTER: иначе клиент успевал упасть и
            // закрыть сокет раньше, чем его кадр доходил до сервера, и проверка ниже
            // превращалась в гонку (флак на нагруженном раннере).
            val restored = java.util.concurrent.atomic.AtomicBoolean(false)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) = Unit
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    received.offer(text)
                    // Restored session without activeDeviceId — только после REGISTER.
                    if (text.contains("REGISTER") && restored.compareAndSet(false, true)) {
                        webSocket.send("{\"type\":\"SESSION_RESTORE\",\"session\":{\"queue\":[]}}")
                    }
                }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { serverDone.countDown() }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { serverDone.countDown() }
            }))
            val error = runCatching {
                MusicRepository(server.url("/").toString().removeSuffix("/")).play()
            }.exceptionOrNull()

            assertNotNull("command without an active device must fail, not broadcast", error)
            assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
            assertTrue(error!!.message!!.contains("activeDeviceId"))

            serverDone.await(5, TimeUnit.SECONDS)
            val texts = mutableListOf<String>()
            // Сервер отвечает SESSION_RESTORE только после REGISTER, поэтому кадр обязан
            // присутствовать — проверка безусловная и строже прежней.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val frame = received.poll(200, TimeUnit.MILLISECONDS) ?: continue
                texts.add(frame)
                if (texts.any { it.contains("REGISTER") }) {
                    // Даём шанс прийти лишнему CONTROL_CMD, если он вообще отправлялся.
                    received.drainTo(texts)
                    break
                }
            }
            assertTrue("REGISTER must be sent once the socket is open", texts.any { it.contains("REGISTER") })
            received.drainTo(texts)
            assertFalse("no CONTROL_CMD may be sent without an active device", texts.any { it.contains("CONTROL_CMD") })
            assertFalse(texts.any { it.contains("\"targetDeviceId\":\"all\"") })
        }
    }

    @Test
    fun serverCloseBeforeRestoreFailsInsteadOfHanging() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.close(1000, "bye") // closes before SESSION_RESTORE ever arrives
                }
            }))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))
            // The outer withTimeout proves the call completes with an error instead
            // of waiting forever for a restore that will never come.
            val error = runBlocking {
                withTimeout(5_000) { runCatching { repo.getStatus() }.exceptionOrNull() }
            }
            assertNotNull(error)
            assertTrue("expected IOException about closing before restore, got $error", error is IOException)
            assertTrue(error!!.message!!.contains("SESSION_RESTORE"))
        }
    }

    @Test
    fun restoreWaitIsBoundedAndCancellationClosesSocket() {
        MockWebServer().use { server ->
            val serverDone = CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                // Accepts the socket but never sends SESSION_RESTORE.
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { serverDone.countDown() }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { serverDone.countDown() }
            }))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"), restoreTimeoutMs = 300)
            val error = runBlocking { runCatching { repo.getStatus() }.exceptionOrNull() }
            assertNotNull(error)
            assertTrue("expected TimeoutCancellationException, got $error", error is TimeoutCancellationException)
            assertTrue(
                "cancelling the timed-out wait must tear down the socket",
                serverDone.await(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun rejectedControlCommandSendSurfacesAsFailure() {
        val repo = MusicRepository("http://localhost/music")
        val socket = FakeWebSocket(sendResult = false)
        val action = MusicRepository.WsAction.Command("next", "{}")
        val error = runBlocking {
            runCatching {
                suspendCancellableCoroutine<MusicPlayerState> { continuation ->
                    repo.ConnectListener(action, continuation).onMessage(
                        socket,
                        "{\"type\":\"SESSION_RESTORE\",\"session\":{\"queue\":[],\"activeDeviceId\":\"tv-1\"}}",
                    )
                }
            }.exceptionOrNull()
        }
        assertNotNull(error)
        assertTrue("expected IOException from rejected send, got $error", error is IOException)
        assertTrue(error!!.message!!.contains("CONTROL_CMD"))
        assertTrue("failed command must cancel the socket", socket.cancelled)
    }

    @Test
    fun rejectedRegisterSendSurfacesAsFailure() {
        val repo = MusicRepository("http://localhost/music")
        val socket = FakeWebSocket(sendResult = false)
        val error = runBlocking {
            runCatching {
                suspendCancellableCoroutine<MusicPlayerState> { continuation ->
                    repo.ConnectListener(MusicRepository.WsAction.Status, continuation).onOpen(socket, switchingProtocolsResponse())
                }
            }.exceptionOrNull()
        }
        assertNotNull(error)
        assertTrue("expected IOException from rejected REGISTER send, got $error", error is IOException)
        assertTrue(socket.cancelled)
    }

    private class FakeWebSocket(private val sendResult: Boolean) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        var closed: Pair<Int, String?>? = null

        override fun request(): Request = Request.Builder().url("http://fake/api/connect/ws").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean { sent += text; return sendResult }
        override fun send(bytes: ByteString): Boolean = sendResult
        override fun close(code: Int, reason: String?): Boolean { closed = code to reason; return true }
        override fun cancel() { cancelled = true }
    }

    private fun switchingProtocolsResponse(): Response = Response.Builder()
        .request(Request.Builder().url("http://fake/api/connect/ws").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private fun awaitMessage(queue: LinkedBlockingQueue<String>, marker: String): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val text = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (text.contains(marker)) return text
        }
        return null
    }
}
