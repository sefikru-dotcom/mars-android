package ru.mars.network

import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

/**
 * Contract tests for the A6 surfaces of [MusicRepository]: server playlists, listening
 * history and lyrics translation. Every body below is copied VERBATIM from the live
 * service (fixtures a6_playlists.json / a6_history.json / lyrics_kino.json) — the point
 * is to freeze the wire shape, so the fixtures must not be "tidied up".
 */
class MusicLibraryRepositoryTest {

    // --------------------------------------------------------------- playlists

    @Test
    fun playlistsReadLiveEnvelopeAndKeepCounts() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success": true, "playlists": [{"id": "f3583ce8", "title": "probe-1488766", "created_at": "2026-10-14 22:37:52", "track_count": 0}, {"id": "2e0d0d47", "title": "Street", "created_at": "2026-09-30 14:25:34", "track_count": 1}]}""",
                ),
            )
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val playlists = repo.getPlaylists()

            assertEquals(listOf("f3583ce8", "2e0d0d47"), playlists.map { it.id })
            assertEquals("probe-1488766", playlists[0].title)
            assertEquals("2026-10-14 22:37:52", playlists[0].createdAt)
            assertEquals(0, playlists[0].trackCount)
            assertEquals("Street", playlists[1].title)
            assertEquals(1, playlists[1].trackCount)
            // Paths hang off the same baseUrl as api/charts.
            assertEquals("/music/api/playlists", server.takeRequest().path)
        }
    }

    @Test
    fun playlistsFailureIsAnErrorNotAnEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"upstream down"}"""))
            val repo = MusicRepository(server.url("/").toString().removeSuffix("/"))

            val failure = runCatching { repo.getPlaylists() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("upstream down"))
        }
    }

    @Test
    fun createPlaylistSendsOnlyTitleAndReturnsNewId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success": true, "id": "7a98df70", "title": "Street"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val created = repo.createPlaylist("Street")

            assertEquals("7a98df70", created.id)
            assertEquals("Street", created.title)
            assertEquals(0, created.trackCount) // a fresh playlist carries no tracks

            val request = server.takeRequest()
            assertEquals("/music/api/playlist/create", request.path)
            assertEquals("""{"title":"Street"}""", request.body.readUtf8())
        }
    }

    @Test
    fun createPlaylistSurfacesServerTitleRequired() = runBlocking {
        MockWebServer().use { server ->
            // Live 400 body; the user must see this exact text, not "HTTP 400".
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"error":"Title required"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            val failure = runCatching { repo.createPlaylist("   ") }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("Title required", failure!!.message)
        }
    }

    @Test
    fun deletePlaylistUsesDeleteOnThePlaylistPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success": true}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            repo.deletePlaylist("2e0d0d47")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/music/api/playlist/2e0d0d47", request.path)
        }
    }

    @Test
    fun deletePlaylistThrowsServerError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"No such playlist"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            val failure = runCatching { repo.deletePlaylist("deadbeef") }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("No such playlist", failure!!.message)
        }
    }

    @Test
    fun playlistTracksUseResultsEnvelope() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success": true, "results": [{"type": "song", "id": "dc0YRAQGAz4", "title": "Track", "artist": "A", "duration": "2:38", "thumbnail": "/music/api/thumb?v=dc0YRAQGAz4"}]}""",
                ),
            )
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val tracks = repo.getPlaylistTracks("2e0d0d47")

            assertEquals(listOf("dc0YRAQGAz4"), tracks.map { it.id })
            assertEquals("2:38", tracks.single().duration)
            assertEquals("/music/api/playlist/2e0d0d47", server.takeRequest().path)
        }
    }

    @Test
    fun addAndRemoveTrackSendVideoIdUnderTheIdKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success": true}"""))
            server.enqueue(MockResponse().setBody("""{"success": true}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            repo.addTrackToPlaylist("2e0d0d47", "dc0YRAQGAz4")
            repo.removeTrackFromPlaylist("2e0d0d47", "dc0YRAQGAz4")

            val add = server.takeRequest()
            assertEquals("/music/api/playlist/2e0d0d47/add", add.path)
            // The field is `id`, not `videoId` — a body without it is a 400.
            assertEquals("""{"id":"dc0YRAQGAz4"}""", add.body.readUtf8())

            val remove = server.takeRequest()
            assertEquals("/music/api/playlist/2e0d0d47/remove", remove.path)
            assertEquals("""{"id":"dc0YRAQGAz4"}""", remove.body.readUtf8())
        }
    }

    @Test
    fun playlistIdCannotBendThePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success": true}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            repo.deletePlaylist("../../admin")

            val path = server.takeRequest().path!!
            // The id is encoded, so it stays ONE segment: no extra level, no traversal.
            assertTrue(path.startsWith("/music/api/playlist/"))
            val remainder = path.removePrefix("/music/api/playlist/")
            assertEquals(remainder, remainder.trim('/').split('/').single())
            assertFalse(path.contains("../"))
        }
    }

    // ----------------------------------------------------------------- history

    @Test
    fun historyReadsLiveRowsUnderTheIdKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"history": [{"id": "parity_probe_1", "title": "Probe", "artist": "T", "duration": "1:00", "thumbnail": "", "played_at": "2026-10-14 22:18:41"}, {"id": "dc0YRAQGAz4", "title": "\u044f \u0431 \u0437\u0430\u0431\u044b\u043b\u0430", "artist": "mostmayx", "duration": "2:38", "thumbnail": "/music/api/thumb?v=dc0YRAQGAz4", "played_at": "2026-10-12 09:37:59"}]}""",
                ),
            )
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val history = repo.getHistory()

            assertEquals(listOf("parity_probe_1", "dc0YRAQGAz4"), history.map { it.id })
            assertEquals("mostmayx", history[1].artist)
            assertEquals("2:38", history[1].duration)
            assertEquals("/music/api/thumb?v=dc0YRAQGAz4", history[1].thumbnail)
            assertEquals("2026-10-12 09:37:59", history[1].playedAt)
            // An empty thumbnail is legal and must not blank the row's duration.
            assertEquals("1:00", history[0].duration)
            assertEquals("/music/api/history", server.takeRequest().path)
        }
    }

    @Test
    fun historyRowConvertsIntoAPlayableTrack() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"history": [{"id": "8embeaRU9xk", "title": "\u0416\u0438\u0433\u0430\u043d\u0441\u043a\u0430\u044f", "artist": "Jakone", "duration": "2:09", "thumbnail": "/music/api/thumb?v=8embeaRU9xk", "played_at": "2026-10-12 09:35:53"}]}""",
                ),
            )
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val track = repo.getHistory().single().toTrack()

            assertEquals("8embeaRU9xk", track.id)
            assertEquals("Jakone", track.artist)
            assertEquals("2:09", track.duration)
        }
    }

    @Test
    fun recordHistorySendsTheFiveLiveFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status": "ok"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            repo.recordHistory(MusicTrack("dc0YRAQGAz4", "Song", "Artist", "2:38", "/music/api/thumb?v=dc0YRAQGAz4"))

            val request = server.takeRequest()
            assertEquals("/music/api/history/record", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains(""""id":"dc0YRAQGAz4""""))
            assertTrue(body.contains(""""title":"Song""""))
            assertTrue(body.contains(""""artist":"Artist""""))
            assertTrue(body.contains(""""duration":"2:38""""))
            assertTrue(body.contains(""""thumbnail":"/music/api/thumb?v=dc0YRAQGAz4""""))
        }
    }

    @Test
    fun recordHistorySurfacesMissingId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":"error","error":"Missing id"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            val failure = runCatching { repo.recordHistory(MusicTrack("x", "T", "A")) }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("Missing id", failure!!.message)
        }
    }

    // ------------------------------------------------------------------ lyrics

    @Test
    fun translateLyricsPostsTextAndReturnsRussian() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success": true, "translated": "\u0422\u0451\u043f\u043b\u043e\u0435 \u043c\u0435\u0441\u0442\u043e"}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))
            val translated = repo.translateLyrics("Тёплое место")

            assertEquals("Тёплое место", translated)

            val request = server.takeRequest()
            assertEquals("/music/api/lyrics/translate", request.path)
            assertEquals("""{"text":"Тёплое место"}""", request.body.readUtf8())
        }
    }

    @Test
    fun translateLyricsEmptyTextReturnsEmptyStringNotError() = runBlocking {
        MockWebServer().use { server ->
            // The live service answers an empty input with a bare `translated:""`.
            server.enqueue(MockResponse().setBody("""{"translated": ""}"""))
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            assertEquals("", repo.translateLyrics(""))
        }
    }

    @Test
    fun blankIdsAreRejectedLocallyWithoutAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            val repo = MusicRepository(server.url("/music").toString().removeSuffix("/"))

            assertNotNull(runCatching { repo.getPlaylistTracks("") }.exceptionOrNull())
            assertNotNull(runCatching { repo.deletePlaylist("   ") }.exceptionOrNull())
            assertNotNull(runCatching { repo.addTrackToPlaylist("2e0d0d47", "") }.exceptionOrNull())
            assertNotNull(runCatching { repo.removeTrackFromPlaylist("2e0d0d47", "") }.exceptionOrNull())
            assertNotNull(runCatching { repo.recordHistory(MusicTrack("", "T", "A")) }.exceptionOrNull())

            // Nothing reached the wire: the guard fires before the request is built.
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }
}
