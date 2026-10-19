package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.MusicRepository

/**
 * Контракт поиска альбомов (`GET /api/search?type=albums`) — по живому ответу
 * сервиса, а не по догадке:
 *
 * ```
 * {"success":true,"results":[{"type":"album","browseId":"MPREb_4KOifxmY0Kc",
 *   "title":"двадцать два","artist":"не меняет","year":"2024",
 *   "thumbnail":"/music/api/thumb?url=…"}]}
 * ```
 *
 * Отдельно закреплено, что обложка резолвится от КОРНЯ сайта (`https://marscore.ru`),
 * а не от baseUrl музыкального API: путь уже содержит `/music`, и склейка с baseUrl
 * дала бы `/music/music/api/thumb` (сервер такого маршрута не знает).
 */
class MusicAlbumSearchRepositoryTest {

    /** Фикстура снята координатором с живого сервера (сокращены только URL обложек). */
    private val albumsJson = """
        {"success": true, "results": [
          {"type": "album", "browseId": "MPREb_4KOifxmY0Kc", "title": "двадцать два",
           "artist": "не меняет", "year": "2024",
           "thumbnail": "/music/api/thumb?url=https%3A//yt3.googleusercontent.com/cover=w544-h544-l90-rj"},
          {"type": "album", "browseId": "MPREb_6bnrNH0ZVMS", "title": "я одержим",
           "artist": "не меняет", "year": "2026", "thumbnail": "/music/api/thumb?url=x"}
        ]}
    """.trimIndent()

    private fun repo(server: MockWebServer) = MusicRepository(server.url("/music").toString().removeSuffix("/"))

    @Test
    fun albumSearchMapsLivePayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(albumsJson))
            val albums = repo(server).searchAlbums("не меняет")
            assertEquals(2, albums.size)
            val first = albums.first()
            assertEquals("MPREb_4KOifxmY0Kc", first.browseId)
            assertEquals("двадцать два", first.title)
            assertEquals("не меняет", first.artist)
            assertEquals("2024", first.year)
            assertEquals("MPREb_6bnrNH0ZVMS", albums[1].browseId)
            assertEquals("2026", albums[1].year)
        }
    }

    @Test
    fun requestCarriesQueryAndAlbumType() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(albumsJson))
            repo(server).searchAlbums("не меняет")
            val request = server.takeRequest()
            val url = request.requestUrl!!
            assertEquals("/music/api/search", url.encodedPath)
            assertEquals("не меняет", url.queryParameter("q"))
            // Без type=albums сервер отвечает песнями — список альбомов был бы пуст.
            assertEquals("albums", url.queryParameter("type"))
        }
    }

    @Test
    fun thumbnailResolvesFromSiteOriginNotFromBaseUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(albumsJson))
            val album = repo(server).searchAlbums("не меняет").first()
            // baseUrl теста — mock-сервер, но обложка обязана указывать на боевой origin.
            assertEquals(
                "https://marscore.ru/music/api/thumb?url=https%3A//yt3.googleusercontent.com/cover=w544-h544-l90-rj",
                album.thumbnail,
            )
        }
    }

    @Test
    fun absoluteThumbnailIsKeptAndDangerousSchemeDropped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"results":[
                         {"type":"album","browseId":"MPREb_a","title":"A","artist":"B",
                          "thumbnail":"https://lh3.example/cover"},
                         {"type":"album","browseId":"MPREb_b","title":"C","artist":"D",
                          "thumbnail":"javascript:alert(1)"},
                         {"type":"album","browseId":"MPREb_c","title":"E","artist":"F",
                          "thumbnail":"//cdn.example/cover.jpg"}]}""",
                ),
            )
            val albums = repo(server).searchAlbums("q")
            assertEquals("https://lh3.example/cover", albums[0].thumbnail)
            assertNull("чужая схема не должна попасть в загрузчик картинок", albums[1].thumbnail)
            assertEquals("https://cdn.example/cover.jpg", albums[2].thumbnail)
        }
    }

    @Test
    fun rowsWithoutMpreBrowseIdAndForeignCardsAreDropped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"results":[
                         {"type":"album","browseId":"MPREb_ok","title":"Ok","artist":"A"},
                         {"type":"album","title":"Без id","artist":"A"},
                         {"type":"album","browseId":"VLPLnotAnAlbum","title":"Плейлист","artist":"A"},
                         {"type":"song","browseId":"MPREb_wrongType","title":"Песня","artist":"A"}]}""",
                ),
            )
            val albums = repo(server).searchAlbums("q")
            assertEquals(listOf("MPREb_ok"), albums.map { it.browseId })
        }
    }

    @Test
    fun emptyResultIsEmptyListNotAnError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
            assertTrue(repo(server).searchAlbums("никого").isEmpty())
        }
    }

    @Test
    fun missingOptionalFieldsGetFallbackLabels() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"success":true,"results":[{"browseId":"MPREb_x"}]}"""),
            )
            val album = repo(server).searchAlbums("q").single()
            assertEquals("Без названия", album.title)
            assertEquals("Неизвестный артист", album.artist)
            assertNull(album.year)
            assertNull(album.thumbnail)
        }
    }

    @Test
    fun httpErrorIsAnExceptionNotEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"upstream"}"""))
            val failure = runCatching { repo(server).searchAlbums("q") }.exceptionOrNull()
            assertNotNull("отказ сервера — исключение: экран должен предложить «Повторить»", failure)
            assertTrue(failure!!.message!!.contains("500"))
        }
    }

    @Test
    fun successFalseIsAnException() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"upstream down"}"""))
            val failure = runCatching { repo(server).searchAlbums("q") }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("upstream down", failure!!.message)
        }
    }

    @Test
    fun blankQueryIsRejectedWithoutARequest() = runBlocking {
        MockWebServer().use { server ->
            val failure = runCatching { repo(server).searchAlbums("   ") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
    }
}
