package ru.mars.tv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository
import java.net.URLDecoder

/**
 * Contract tests for [RepositoryTvMusicGateway] — the production seam that binds the REAL
 * core-network [MusicRepository] (`GET /api/charts` on the /music base path) into the TV
 * controller's blocking [TvMusicGateway]. Runs against MockWebServer: envelope mapping,
 * HTTP failures, service-level failures and empty responses must all land as explicit
 * [TvMusicChartsResult] values — never as an exception escaping to the controller.
 */
class RepositoryTvMusicGatewayTest {

    private fun gatewayFor(server: MockWebServer): RepositoryTvMusicGateway =
        RepositoryTvMusicGateway(MusicRepository(server.url("/music").toString().removeSuffix("/")))

    @Test
    fun chartsEnvelopeMapsToLoadedTracks() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"results":[
                       {"type":"song","id":"c1","title":"One","artist":"A","duration":"2:10"},
                       {"type":"song","id":"c2","title":"Two","artist":"B"}]}""",
                ),
            )
            val result = gatewayFor(server).fetchCharts()
            assertEquals(
                TvMusicChartsResult.Loaded(
                    listOf(
                        MusicTrack(id = "c1", title = "One", artist = "A", duration = "2:10"),
                        MusicTrack(id = "c2", title = "Two", artist = "B"),
                    ),
                ),
                result,
            )
            assertEquals("/music/api/charts", server.takeRequest().path)
        }
    }

    @Test
    fun emptyChartsResponseMapsToLoadedEmptyList() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
            assertEquals(TvMusicChartsResult.Loaded(emptyList()), gatewayFor(server).fetchCharts())
        }
    }

    @Test
    fun serviceLevelFailureMapsToFailedWithError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"upstream down"}"""))
            val result = gatewayFor(server).fetchCharts()
            assertTrue("expected Failed, got $result", result is TvMusicChartsResult.Failed)
            assertTrue((result as TvMusicChartsResult.Failed).message.contains("upstream down"))
        }
    }

    @Test
    fun httpErrorMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502))
            val result = gatewayFor(server).fetchCharts()
            assertTrue("expected Failed, got $result", result is TvMusicChartsResult.Failed)
            assertTrue((result as TvMusicChartsResult.Failed).message.isNotBlank())
        }
    }

    @Test
    fun ioFailureMapsToFailedNotException() {
        MockWebServer().use { server ->
            val url = server.url("/music").toString().removeSuffix("/")
            server.shutdown() // connection refused -> IOException inside the repository
            val result = RepositoryTvMusicGateway(MusicRepository(url)).fetchCharts()
            assertTrue("expected Failed, got $result", result is TvMusicChartsResult.Failed)
            assertTrue((result as TvMusicChartsResult.Failed).message.isNotBlank())
        }
    }

    @Test
    fun productionFactoryUsesMusicBasePath() {
        // The wiring constant must match mobile parity: BASE_URL + /music (MusicRepository default).
        assertEquals("https://marscore.ru/music", RepositoryTvMusicGateway.MUSIC_BASE_URL)
        // Transport stream URL parity with MusicRepository.getStreamUrl: {base}/api/stream/{id}.
        assertEquals("https://marscore.ru/music/api/stream/t1", TvMusicPlayer.streamUrlFor("t1"))
    }

    @Test
    fun fetchIsBlockingAndSafeToCallTwice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[{"type":"song","id":"x","title":"X","artist":"Y"}]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
            val gateway = gatewayFor(server)
            assertTrue(gateway.fetchCharts() is TvMusicChartsResult.Loaded)
            assertEquals(TvMusicChartsResult.Loaded(emptyList()), gateway.fetchCharts())
        }
    }

    // --- search (GET /api/search?q=…) -------------------------------------------------------------

    @Test
    fun searchEnvelopeMapsToLoadedTracksAndHitsSearchPath() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"results":[
                       {"type":"song","id":"s1","title":"Radio","artist":"A","duration":"3:01"}]}""",
                ),
            )
            val result = gatewayFor(server).search("radio")
            assertEquals(
                TvMusicChartsResult.Loaded(
                    listOf(MusicTrack(id = "s1", title = "Radio", artist = "A", duration = "3:01")),
                ),
                result,
            )
            assertEquals("/music/api/search?q=radio", server.takeRequest().path)
        }
    }

    @Test
    fun searchQueryIsUrlEncoded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
            gatewayFor(server).search("ночь & день")
            val path = server.takeRequest().path!!
            assertTrue("expected the search path, got $path", path.startsWith("/music/api/search?q="))
            assertEquals(
                "ночь & день",
                URLDecoder.decode(path.substringAfter("q="), Charsets.UTF_8.name()),
            )
        }
    }

    @Test
    fun emptySearchResultsMapToLoadedEmptyList() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
            assertEquals(TvMusicChartsResult.Loaded(emptyList()), gatewayFor(server).search("неттакого"))
        }
    }

    @Test
    fun searchHttpErrorMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val result = gatewayFor(server).search("q")
            assertTrue("expected Failed, got $result", result is TvMusicChartsResult.Failed)
            assertTrue((result as TvMusicChartsResult.Failed).message.isNotBlank())
        }
    }

    @Test
    fun blankQueryIsContainedAsFailedNotException() {
        // The controller never sends a blank query (TvMusicSearchSpec), but the seam must
        // still contain the repository's require(query.isNotBlank()) instead of throwing.
        MockWebServer().use { server ->
            val result = gatewayFor(server).search("  ")
            assertTrue("expected Failed, got $result", result is TvMusicChartsResult.Failed)
            assertTrue((result as TvMusicChartsResult.Failed).message.isNotBlank())
            assertEquals("a rejected query must not hit the network", 0, server.requestCount)
        }
    }

    // --- personal feed (GET /api/home → shelf.sections[]) ----------------------------------------

    @Test
    fun homeEnvelopeMapsToLoadedShelvesAndHitsHomePath() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"shelf":{"sections":[
                       {"id":"continue","title":"Продолжить слушать","items":[
                         {"type":"song","id":"h1","title":"One","artist":"A","duration":"2:10"}]},
                       {"id":"new","title":"Новинки","items":[]}]},"generatedAt":1696000000}""",
                ),
            )
            val result = gatewayFor(server).fetchHome()
            assertEquals(
                TvMusicHomeResult.Loaded(
                    listOf(
                        MusicShelf(
                            id = "continue",
                            title = "Продолжить слушать",
                            items = listOf(MusicTrack(id = "h1", title = "One", artist = "A", duration = "2:10")),
                        ),
                        // Пустая полка доезжает как есть — «скрывать ли её» решает TvMusicHomePolicy.
                        MusicShelf(id = "new", title = "Новинки", items = emptyList()),
                    ),
                ),
                result,
            )
            assertEquals("/music/api/home", server.takeRequest().path)
        }
    }

    @Test
    fun homeWithoutSectionsMapsToLoadedEmptyListNotFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"shelf":{"sections":[]}}"""))
            assertEquals(TvMusicHomeResult.Loaded(emptyList()), gatewayFor(server).fetchHome())
        }
    }

    @Test
    fun homeServiceLevelFailureMapsToFailedWithError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"no profile"}"""))
            val result = gatewayFor(server).fetchHome()
            assertTrue("expected Failed, got $result", result is TvMusicHomeResult.Failed)
            assertTrue((result as TvMusicHomeResult.Failed).message.contains("no profile"))
        }
    }

    @Test
    fun homeHttpErrorMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val result = gatewayFor(server).fetchHome()
            assertTrue("expected Failed, got $result", result is TvMusicHomeResult.Failed)
            assertTrue((result as TvMusicHomeResult.Failed).message.isNotBlank())
        }
    }

    // --- lyrics (GET /api/lyrics/{videoId}?title=…&artist=…) --------------------------------------

    @Test
    fun flatLyricsEnvelopeMapsToLoadedAndQueryCarriesTitleArtist() {
        // Живой контракт 13.10 (fixtures/lyrics_kino.json): ПЛОСКИЙ объект без success-обёртки.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"lyrics":"Теплое место, но улицы ждут отпечатков наших ног.","synced":null,"source":"youtube"}""",
                ),
            )
            val track = MusicTrack(id = "kino1", title = "Группа крови", artist = "Кино", duration = "4:41")
            val result = gatewayFor(server).fetchLyrics(track)
            assertEquals(
                TvLyricsResult.Loaded(
                    LyricsResult(
                        lyrics = "Теплое место, но улицы ждут отпечатков наших ног.",
                        synced = null,
                        source = "youtube",
                    ),
                ),
                result,
            )
            // title/artist ОБЯЗАТЕЛЬНЫ живому серверу — без них частая заглушка «Текст не найден.».
            val path = server.takeRequest().path!!
            assertTrue("expected the lyrics path, got $path", path.startsWith("/music/api/lyrics/kino1?"))
            val query = path.substringAfter('?')
            assertEquals(
                "Группа крови",
                URLDecoder.decode(query.substringAfter("title=").substringBefore('&'), Charsets.UTF_8.name()),
            )
            assertEquals(
                "Кино",
                URLDecoder.decode(query.substringAfter("artist="), Charsets.UTF_8.name()),
            )
        }
    }

    @Test
    fun notFoundStubBodyStaysLoadedForThePolicyToJudge() {
        // Шов не трактует заглушку — «Текст не найден.» распознаёт TvLyricsViewPolicy (Missing).
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"lyrics":"Текст не найден.","synced":null,"source":"youtube"}"""))
            val result = gatewayFor(server).fetchLyrics(MusicTrack(id = "x", title = "T", artist = "A"))
            assertTrue("expected Loaded, got $result", result is TvLyricsResult.Loaded)
        }
    }

    @Test
    fun lyricsHttpErrorMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val result = gatewayFor(server).fetchLyrics(MusicTrack(id = "x", title = "T", artist = "A"))
            assertTrue("expected Failed, got $result", result is TvLyricsResult.Failed)
            assertTrue((result as TvLyricsResult.Failed).message.isNotBlank())
        }
    }

    // ------------------------------------------- перевод текста (POST api/lyrics/translate)

    @Test
    fun translatePostsTextBodyAndMapsTranslatedAnswer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"translated":"Warm place"}"""))
            val result = gatewayFor(server).translate("Тёплое место")
            assertEquals(TvLyricsTranslationResult.Translated("Warm place"), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/music/api/lyrics/translate", request.path)
            assertEquals("Тёплое место", JSONObject(request.body.readUtf8()).getString("text"))
        }
    }

    @Test
    fun translateEmptyAnswerStaysTranslatedForTheFlowToJudge() {
        // Сервис на пустой ввод отвечает translated:"" — шов не судит: ветка EMPTY_ANSWER во flow.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"translated":""}"""))
            assertEquals(TvLyricsTranslationResult.Translated(""), gatewayFor(server).translate(""))
        }
    }

    @Test
    fun translateServiceFailureMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"upstream down"}"""))
            val result = gatewayFor(server).translate("текст")
            assertTrue("expected Failed, got $result", result is TvLyricsTranslationResult.Failed)
            assertTrue((result as TvLyricsTranslationResult.Failed).message.isNotBlank())
        }
    }

    @Test
    fun translateHttpErrorMapsToFailedNotException() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val result = gatewayFor(server).translate("текст")
            assertTrue("expected Failed, got $result", result is TvLyricsTranslationResult.Failed)
        }
    }

    @Test
    fun translateIoFailureMapsToFailedNotException() {
        MockWebServer().use { server ->
            val url = server.url("/music").toString().removeSuffix("/")
            server.shutdown()
            val result = RepositoryTvMusicGateway(MusicRepository(url)).translate("текст")
            assertTrue("expected Failed, got $result", result is TvLyricsTranslationResult.Failed)
            assertTrue((result as TvLyricsTranslationResult.Failed).message.isNotBlank())
        }
    }
}
