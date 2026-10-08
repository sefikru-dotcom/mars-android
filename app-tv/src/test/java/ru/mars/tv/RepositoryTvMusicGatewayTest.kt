package ru.mars.tv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

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
}
