package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты новых сетевых методов [MusicRepository] на MockWebServer: тела ответов
 * списаны дословно с живого server.py (preload / import / export).
 */
class MusicTransferRepositoryTest {

    private fun repo(server: MockWebServer) =
        MusicRepository(server.url("/music").toString().removeSuffix("/"))

    @Test
    fun `preload posts to video id with quality query and parses live answer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"success":true,"preloading":"t42","quality":"standard"}"""),
            )
            val result = repo(server).preload("t42", "standard")

            assertEquals(MusicPreloadResult.Started("t42", "standard"), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/music/api/preload/t42?quality=standard", request.path)
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test
    fun `preload failure is a value not an exception`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"success":false,"error":"Missing video_id"}"""),
            )
            val result = repo(server).preload("", "original")
            assertEquals(MusicPreloadResult.Failed("Missing video_id"), result)
            assertEquals(0, server.requestCount) // пустой id даже не уходит в сеть
        }
        // Отдельный сервер: первый сценарий в сеть не ходил, и его ответ остался бы в очереди.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"success":false,"error":"upstream down"}"""),
            )
            assertEquals(
                MusicPreloadResult.Failed("upstream down"),
                repo(server).preload("t1", "original"),
            )
        }
    }

    @Test
    fun `import playlist parses exact success envelope and count as number`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"id":"7a98df70","title":"Street","count":42}""",
                ),
            )
            val result = repo(server).importPlaylist("https://music.youtube.com/playlist?list=PL123")

            assertEquals(
                MusicPlaylistImportResult.Imported("7a98df70", "Street", 42),
                result,
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/music/api/playlist/import", request.path)
            assertEquals("""{"url":"https://music.youtube.com/playlist?list=PL123"}""", request.body.readUtf8())
        }
    }

    @Test
    fun `import playlist surfaces the server error text verbatim`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"success":false,"error":"Failed to fetch playlist: timeout"}"""),
            )
            val result = repo(server).importPlaylist("VL12345")
            assertEquals(
                MusicPlaylistImportResult.Failed("Failed to fetch playlist: timeout"),
                result,
            )
        }
    }

    @Test
    fun `export playlist downloads raw m3u8 with filename from server`() = runBlocking {
        MockWebServer().use { server ->
            val m3u = "#EXTM3U\n#PLAYLIST:Street\n#EXTINF:158,Artist - Title\nhttps://music.youtube.com/watch?v=t1\n"
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "application/x-mpegurl")
                    .addHeader("Content-Disposition", "attachment; filename=\"playlist_abc12345.m3u8\"")
                    .setBody(m3u),
            )
            val result = repo(server).exportM3u8("abc12345")

            assertEquals(m3u, result.text)
            assertEquals("playlist_abc12345.m3u8", result.fileName)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/music/api/playlist/abc12345/export?format=m3u8", request.path)
        }
    }

    @Test
    fun `export favorites uses its own endpoint and surfaces 404 as error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Disposition", "attachment; filename=\"mars_favorites.m3u8\"")
                    .setBody("#EXTM3U\n"),
            )
            val ok = repo(server).exportM3u8()
            assertEquals("#EXTM3U\n", ok.text)
            assertEquals("mars_favorites.m3u8", ok.fileName)
            assertEquals("/music/api/favorites/export?format=m3u8", server.takeRequest().path)

            server.enqueue(MockResponse().setResponseCode(404).setBody("Playlist not found"))
            val failure = runCatching { repo(server).exportM3u8("missing") }.exceptionOrNull()
            assertTrue(failure?.message == "Playlist not found")
        }
    }
}
