package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.AlbumResult
import ru.mars.network.repository.MusicRepository

/**
 * Контракт новых методов страницы исполнителя и альбома. Ответы — фейковые
 * (MockWebServer), форма взята из живого сервиса: `/api/artist` отдаёт объект без
 * конверта `success`, `/api/album` — `{success, album}`, а неверный browseId —
 * `{"error":"Invalid album browseId provided, must start with MPRE."}` с кодом 500.
 */
class MusicArtistRepositoryTest {

    private val artistJson = """
        {"name":"Test","channelId":"UCHBFXJgTyawfZf4AfAsIrQA","radioId":"RDEMVJ",
         "description":null,"subscribers":"19.1K","thumbnail":"https://lh3.example/avatar",
         "songs":[
           {"id":"nJoJT3hx7wM","title":"She Pretty","artist":"Test","duration":"","thumbnail":"/music/api/thumb?v=nJoJT3hx7wM"},
           {"id":"NpyE9fvArpw","title":"Splurge","artist":"Test","duration":"2:41","thumbnail":"/music/api/thumb?v=NpyE9fvArpw"}
         ]}
    """.trimIndent()

    private val albumJson = """
        {"success":true,"album":{"title":"Album","artist":"Test","year":"2024","trackCount":2,
         "duration":"6:10","thumbnail":"https://lh3.example/cover","description":"desc",
         "tracks":[
           {"id":"a1","title":"One","artist":"Test","duration":"3:00","trackNumber":1,"thumbnail":"t1"},
           {"id":"a2","title":"Two","artist":"Test","duration":"3:10","trackNumber":2,"thumbnail":"t2"}
         ]}}
    """.trimIndent()

    private fun repo(server: MockWebServer) = MusicRepository(server.url("/").toString().removeSuffix("/"))

    // ------------------------------------------------------------------ artist

    @Test
    fun artistPageMapsLivePayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(artistJson))
            val artist = repo(server).getArtist("Test")
            assertNotNull(artist)
            assertEquals("Test", artist!!.name)
            assertEquals("UCHBFXJgTyawfZf4AfAsIrQA", artist.channelId)
            assertEquals("19.1K", artist.subscribers)
            assertEquals("https://lh3.example/avatar", artist.thumbnail)
            assertNull(artist.description) // пустой description сервера → null, а не ""
            assertEquals(listOf("nJoJT3hx7wM", "NpyE9fvArpw"), artist.songs.map { it.id })
            // Пустая duration сервера приводится к подписи-фолбэку.
            assertEquals("--:--", artist.songs.first().duration)
            assertEquals("2:41", artist.songs[1].duration)
            assertEquals("/api/artist/Test", server.takeRequest().path)
        }
    }

    @Test
    fun artistNameWithSlashStaysOnePathSegment() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(artistJson))
            repo(server).getArtist("AC/DC")
            // Слэш в имени не должен превращаться в два сегмента пути (иначе 404).
            assertEquals("/api/artist/AC%2FDC", server.takeRequest().path)
        }
    }

    @Test
    fun artistChannelIdIsForwardedAsIs() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(artistJson))
            repo(server).getArtist(" UCHBFXJgTyawfZf4AfAsIrQA ")
            assertEquals("/api/artist/UCHBFXJgTyawfZf4AfAsIrQA", server.takeRequest().path)
        }
    }

    @Test
    fun artistNotFoundIsNullNotAnException() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"Artist not found\"}"))
            assertNull(repo(server).getArtist("nobody"))
        }
    }

    @Test
    fun artistServerErrorSurfaces() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"upstream\"}"))
            val failure = runCatching { repo(server).getArtist("Test") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("500"))
        }
    }

    // ------------------------------------------------------------------- album

    @Test
    fun albumMapsTracksAndHeader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(albumJson))
            val result = repo(server).getAlbum("MPREb_album")
            assertTrue(result is AlbumResult.Loaded)
            val album = (result as AlbumResult.Loaded).album
            assertEquals("MPREb_album", album.browseId)
            assertEquals("Album", album.title)
            assertEquals("Test", album.artist)
            assertEquals("2024", album.year)
            assertEquals(2, album.trackCount)
            assertEquals(listOf("a1", "a2"), album.tracks.map { it.id })
            assertEquals("/api/album/MPREb_album", server.takeRequest().path)
        }
    }

    @Test
    fun albumYearAsNumberAndMissingTrackCountStillParse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    "{\"success\":true,\"album\":{\"title\":\"A\",\"artist\":\"B\",\"year\":1999,\"tracks\":[" +
                        "{\"id\":\"t1\",\"title\":\"One\"},{\"id\":\"t2\",\"title\":\"Two\"}]}}",
                ),
            )
            val album = (repo(server).getAlbum("MPREb_x") as AlbumResult.Loaded).album
            assertEquals("1999", album.year)
            // trackCount в ответе нет — считаем по фактическим трекам, а не печатаем 0.
            assertEquals(2, album.trackCount)
            // Строки без artist/duration получают подписи-фолбэки, а не пустоту.
            assertEquals("Неизвестный артист", album.tracks.first().artist)
            assertEquals("--:--", album.tracks.first().duration)
        }
    }

    @Test
    fun albumNameIsRefusedWithoutARequest() = runBlocking {
        MockWebServer().use { server ->
            val result = repo(server).getAlbum("test")
            assertEquals(AlbumResult.InvalidBrowseId, result)
            // Отказ отсекается до сети: сервер не должен получить ни одного запроса.
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun albumLowercasePrefixIsAlsoRefusedLocally() = runBlocking {
        MockWebServer().use { server ->
            assertEquals(AlbumResult.InvalidBrowseId, repo(server).getAlbum("mpreb_x"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun albumServerBrowseIdRefusalIsTypedNotThrown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(500)
                    .setBody("{\"error\": \"Invalid album browseId provided, must start with MPRE.\"}"),
            )
            assertEquals(AlbumResult.InvalidBrowseId, repo(server).getAlbum("MPREb_broken"))
        }
    }

    @Test
    fun albumTransportFailureIsTypedNotThrown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"YTMusic client unavailable\"}"))
            val result = repo(server).getAlbum("MPREb_x")
            assertTrue(result is AlbumResult.Failed)
            assertEquals("YTMusic client unavailable", (result as AlbumResult.Failed).message)
        }
    }

    @Test
    fun albumSuccessFalseIsTypedFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":false,\"error\":\"upstream down\"}"))
            val result = repo(server).getAlbum("MPREb_x")
            assertEquals(AlbumResult.Failed("upstream down"), result)
        }
    }
}
