package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.mars.network.repository.MusicRepository

/**
 * Contract tests for the stream-URL builder and the manual quality model
 * ([StreamUrlPolicy]/[StreamQuality]) used by [MarsMusicPlayer.streamUrlFor].
 *
 * Pinned contracts:
 * 1. BACKWARD-COMPATIBLE DEFAULT: [StreamQuality.DEFAULT] emits the historical
 *    `{base}/api/stream/{id}` with NO query at all — old servers keep working and the
 *    server-side default is never overridden by the client.
 * 2. EXPLICIT PROFILES: original/standard/economy each add exactly `?quality=<value>`
 *    and nothing else (the server-side streaming API contract of the parent task).
 * 3. PARITY: the default URL equals [MusicRepository.getStreamUrl] byte-for-byte —
 *    mobile, TV and the repository must never drift apart on the wire address.
 * 4. ROBUSTNESS: trailing-slash base normalization (no double slash) and path-segment
 *    percent-encoding (an id can never bend the path or inject a query).
 * 5. FALLBACK: any unknown/blank/garbage quality string falls back to DEFAULT — a bad
 *    setting can only ever mean "server default", never a broken URL.
 */
class StreamUrlPolicyTest {

    private val base = "https://marscore.ru/music"

    // --- 1. backward-compatible default ------------------------------------------

    @Test
    fun `default quality emits the historical url with no query`() {
        assertEquals(
            "https://marscore.ru/music/api/stream/t42",
            StreamUrlPolicy.streamUrl(base, "t42", StreamQuality.DEFAULT)
        )
    }

    @Test
    fun `default quality is the enum fallback and carries no query value`() {
        assertNull(StreamQuality.DEFAULT.queryValue)
        assertEquals(false, StreamQuality.DEFAULT.isExplicit)
    }

    // --- 2. explicit profiles -------------------------------------------------------

    @Test
    fun `each explicit profile appends exactly one quality parameter`() {
        assertEquals(
            "https://marscore.ru/music/api/stream/t1?quality=original",
            StreamUrlPolicy.streamUrl(base, "t1", StreamQuality.ORIGINAL)
        )
        assertEquals(
            "https://marscore.ru/music/api/stream/t1?quality=standard",
            StreamUrlPolicy.streamUrl(base, "t1", StreamQuality.STANDARD)
        )
        assertEquals(
            "https://marscore.ru/music/api/stream/t1?quality=economy",
            StreamUrlPolicy.streamUrl(base, "t1", StreamQuality.ECONOMY)
        )
    }

    @Test
    fun `quality parameter name is pinned`() {
        assertEquals("quality", StreamUrlPolicy.QUALITY_PARAM)
    }

    // --- 3. parity with MusicRepository.getStreamUrl ---------------------------------

    @Test
    fun `default url matches MusicRepository getStreamUrl byte for byte`() {
        val repo = MusicRepository(base)
        for (id in listOf("t42", "abc-123", "x")) {
            assertEquals(
                repo.getStreamUrl(id),
                StreamUrlPolicy.streamUrl(base, id, StreamQuality.DEFAULT)
            )
        }
    }

    @Test
    fun `explicit quality differs from repository url only by the query`() {
        val repo = MusicRepository(base)
        assertEquals(
            repo.getStreamUrl("t42") + "?quality=economy",
            StreamUrlPolicy.streamUrl(base, "t42", StreamQuality.ECONOMY)
        )
    }

    @Test
    fun `trailing slash base is normalized - parity with repository`() {
        val repo = MusicRepository("https://marscore.ru/music/")
        val expected = repo.getStreamUrl("t1")
        assertEquals(expected, StreamUrlPolicy.streamUrl("https://marscore.ru/music/", "t1", StreamQuality.DEFAULT))
        assertEquals("$expected?quality=original", StreamUrlPolicy.streamUrl("https://marscore.ru/music/", "t1", StreamQuality.ORIGINAL))
    }

    // --- 4. robustness -----------------------------------------------------------------

    @Test
    fun `id percent encoding matches the repository mapper`() {
        // MusicRepositoryTest pins getStreamUrl("a b") == …/api/stream/a%20b (OkHttp
        // addPathSegment parity) — the hand-rolled encoder must produce the same bytes.
        val repo = MusicRepository(base)
        assertEquals(repo.getStreamUrl("a b"), StreamUrlPolicy.streamUrl(base, "a b", StreamQuality.DEFAULT))
        assertEquals("https://marscore.ru/music/api/stream/a%20b", StreamUrlPolicy.streamUrl(base, "a b", StreamQuality.DEFAULT))
    }

    @Test
    fun `path and query metacharacters in the id cannot bend the url`() {
        assertEquals(
            "https://marscore.ru/music/api/stream/t%2F42",
            StreamUrlPolicy.streamUrl(base, "t/42", StreamQuality.DEFAULT)
        )
        // A '?' inside the id must not smuggle a second query: it is percent-encoded ('='
        // stays literal — RFC 3986 pchar, OkHttp addPathSegment parity), and the ONLY '?'
        // in the result is the quality separator.
        val url = StreamUrlPolicy.streamUrl(base, "t?x=1", StreamQuality.ECONOMY)
        assertEquals("https://marscore.ru/music/api/stream/t%3Fx=1?quality=economy", url)
        assertEquals(1, url.count { it == '?' })
    }

    @Test
    fun `unreserved characters survive encoding untouched`() {
        assertEquals(
            "https://marscore.ru/music/api/stream/aZ09-._~",
            StreamUrlPolicy.streamUrl(base, "aZ09-._~", StreamQuality.DEFAULT)
        )
    }

    // --- 5. fromQueryValue fallback --------------------------------------------------------

    @Test
    fun `fromQueryValue round trips every explicit profile`() {
        for (quality in StreamQuality.entries) {
            assertSame(quality, StreamQuality.fromQueryValue(quality.queryValue))
        }
    }

    @Test
    fun `fromQueryValue tolerates case and surrounding whitespace`() {
        assertSame(StreamQuality.ECONOMY, StreamQuality.fromQueryValue("ECONOMY"))
        assertSame(StreamQuality.ORIGINAL, StreamQuality.fromQueryValue(" Original "))
    }

    @Test
    fun `fromQueryValue falls back to DEFAULT on null blank or garbage`() {
        assertSame(StreamQuality.DEFAULT, StreamQuality.fromQueryValue(null))
        assertSame(StreamQuality.DEFAULT, StreamQuality.fromQueryValue(""))
        assertSame(StreamQuality.DEFAULT, StreamQuality.fromQueryValue("   "))
        assertSame(StreamQuality.DEFAULT, StreamQuality.fromQueryValue("hd"))
        assertSame(StreamQuality.DEFAULT, StreamQuality.fromQueryValue("original,economy"))
    }
}
