package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Regression for the «Треки не найдены» masquerade: when /api/charts answers
 * HTTP 200 with {success:false,error:...} (repository.getCharts() throws), the
 * charts tab must fall back to /api/home and, if that also fails, land on
 * Outcome.Failed (error screen + retry) — never on an empty track list.
 */
class MusicChartLoaderTest {

    private val track = MusicTrack("v1", "Song", "Artist", "3:24", "thumb")

    @Test
    fun `charts rejected with success=false at HTTP 200 and failing home is Failed, not empty list`() {
        var homeCalled = false
        val outcome = MusicChartLoader.resolve(
            // MusicRepository.getCharts() surfaces {success:false,error} as an exception.
            Result.failure(MusicChartLoader.ServerRejectedException("upstream down")),
        ) {
            homeCalled = true
            Result.failure(MusicChartLoader.ServerRejectedException("upstream down"))
        }
        assertTrue("home fallback must be consulted", homeCalled)
        assertTrue("expected Failed, got $outcome", outcome is MusicChartLoader.Outcome.Failed)
        assertTrue((outcome as MusicChartLoader.Outcome.Failed).reason.contains("upstream down"))
    }

    @Test
    fun `home fallback payload with success=false is a failure, not empty tracks`() {
        val error = runCatching {
            MusicChartLoader.parseHomePayload("""{"success":false,"error":"rate limited"}""")
        }.exceptionOrNull()
        assertTrue("expected ServerRejectedException, got $error", error is MusicChartLoader.ServerRejectedException)
        assertEquals("rate limited", (error as MusicChartLoader.ServerRejectedException).serverError)
    }

    @Test
    fun `charts failure with healthy home fallback yields home tracks`() {
        val outcome = MusicChartLoader.resolve(Result.failure(IllegalStateException("upstream down"))) {
            Result.success(listOf(track))
        }
        assertEquals(MusicChartLoader.Outcome.Tracks(listOf(track)), outcome)
    }

    @Test
    fun `charts success wins and empty list stays a legitimate empty chart`() {
        var homeCalled = false
        val outcome = MusicChartLoader.resolve(Result.success(emptyList())) {
            homeCalled = true
            Result.success(listOf(track))
        }
        assertTrue("home must not be called when charts succeeded", homeCalled.not())
        // success:true with zero rows is real "no tracks" — «Треки не найдены» is correct here.
        assertEquals(MusicChartLoader.Outcome.Tracks(emptyList()), outcome)
    }

    @Test
    fun `home payload parses tracks and honors explicit success=true`() {
        val parsed = MusicChartLoader.parseHomePayload(
            """{"success":true,"results":[{"id":"v1","title":"Song","artist":"Artist","duration":"3:24","thumbnail":"thumb"}]}""",
        )
        assertEquals(listOf(track), parsed)
    }

    /**
     * Живой ответ сервера (`GET /music/api/search?type=songs`): чарт/главная идут через
     * этот ручной парсер, а не через репозиторий, и до правки трек терял альбом —
     * сервер поле отдаёт, строка на экране показывает только исполнителя.
     */
    @Test
    fun `home payload keeps album name and browseId`() {
        val parsed = MusicChartLoader.parseHomePayload(
            """{"success":true,"results":[{"type":"song","id":"F9P_fIBfenk","title":"станция конечная",
               "artist":"не меняет","duration":"1:31","thumbnail":"/music/api/thumb?v=F9P_fIBfenk",
               "album":{"name":"двадцать два","browseId":"MPREb_4KOifxmY0Kc"}}]}""",
        ).single()
        assertEquals("двадцать два", parsed.albumTitle)
        assertEquals("MPREb_4KOifxmY0Kc", parsed.albumBrowseId)
    }

    @Test
    fun `home payload without album keeps both album fields null`() {
        val parsed = MusicChartLoader.parseHomePayload(
            """{"success":true,"tracks":[{"id":"v1","title":"Song","artist":"Artist","duration":"3:24"}]}""",
        ).single()
        assertEquals(null, parsed.albumTitle)
        assertEquals(null, parsed.albumBrowseId)
    }

    /** `browseId` может быть пустым (сервер не нашёл альбом) — имя всё равно показываем. */
    @Test
    fun `album with empty browseId keeps the name and stays unopenable`() {
        val parsed = MusicChartLoader.parseHomePayload(
            """{"success":true,"results":[{"id":"v1","title":"Song","artist":"Artist","duration":"3:24",
               "album":{"name":"двадцать два","browseId":""}}]}""",
        ).single()
        assertEquals("двадцать два", parsed.albumTitle)
        assertEquals(null, parsed.albumBrowseId)
    }

    /**
     * Старая форма ytmusicapi — голая строка названия. Сервер её нормализует, но
     * парсер обязан понимать и её: иначе сборка сервера без `resolve_album()` снова
     * даст треки без альбома.
     */
    @Test
    fun `album given as a bare string still yields the name`() {
        val parsed = MusicChartLoader.parseHomePayload(
            """{"success":true,"results":[{"id":"v1","title":"Song","artist":"Artist","album":"двадцать два"}]}""",
        ).single()
        assertEquals("двадцать два", parsed.albumTitle)
        assertEquals(null, parsed.albumBrowseId)
    }
}
