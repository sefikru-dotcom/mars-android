package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit-тесты чистой политики перехода к исполнителю: какой id уходит в
 * `/api/artist`, что делать с повторами и сколько песен показывать, как читать
 * серверных подписчиков («19.1K») и какой текст показывать в каждом состоянии.
 * Сети здесь нет — все значения фиксированы.
 */
class MusicArtistPolicyTest {

    private fun track(id: String, title: String = "Song $id"): MusicTrack =
        MusicTrack(id = id, title = title, artist = "Test")

    // ------------------------------------------------------- что слать в запрос

    @Test
    fun requestTargetTrimsAndKeepsNamesAndChannels() {
        assertEquals("Test", MusicArtistPolicy.requestTarget("  Test  "))
        assertEquals("AC/DC", MusicArtistPolicy.requestTarget("AC/DC"))
        assertEquals("UCHBFXJgTyawfZf4AfAsIrQA", MusicArtistPolicy.requestTarget("UCHBFXJgTyawfZf4AfAsIrQA"))
    }

    @Test
    fun requestTargetRejectsBlankAndAlbumBrowseIds() {
        assertNull(MusicArtistPolicy.requestTarget(null))
        assertNull(MusicArtistPolicy.requestTarget(""))
        assertNull(MusicArtistPolicy.requestTarget("   "))
        // browseId альбома — не исполнитель: /api/artist на него ответит отказом.
        assertNull(MusicArtistPolicy.requestTarget("MPREb_fakefakefake"))
    }

    @Test
    fun canOpenFollowsRequestTarget() {
        assertTrue(MusicArtistPolicy.canOpen("Test"))
        assertFalse(MusicArtistPolicy.canOpen(" "))
        assertFalse(MusicArtistPolicy.canOpen(null))
    }

    @Test
    fun channelIdIsDetectedByPrefixAndLength() {
        assertTrue(MusicArtistPolicy.isChannelId("UCHBFXJgTyawfZf4AfAsIrQA"))
        assertTrue(MusicArtistPolicy.isChannelId("  UCabcdefghijklmnopqrstuv  "))
        assertFalse(MusicArtistPolicy.isChannelId("Test"))
        // Голый префикс — это имя «UC», а не канал.
        assertFalse(MusicArtistPolicy.isChannelId("UC"))
        assertFalse(MusicArtistPolicy.isChannelId(null))
    }

    @Test
    fun initialNameShowsNamesButNotChannelIds() {
        assertEquals("Test", MusicArtistPolicy.initialName("  Test "))
        assertEquals("AC/DC", MusicArtistPolicy.initialName("AC/DC"))
        // channelId — техническая строка, в шапке до ответа её не печатаем.
        assertEquals("", MusicArtistPolicy.initialName("UCHBFXJgTyawfZf4AfAsIrQA"))
        assertEquals("", MusicArtistPolicy.initialName(null))
    }

    // --------------------------------------------------- песни: дубли и предел

    @Test
    fun songsDropBlankIdsAndDuplicatesKeepingFirstOrder() {
        val raw = listOf(
            track("a", "First"),
            track("", "Broken"),
            track("b"),
            track("a", "Duplicate"),
        )
        val songs = MusicArtistPolicy.songs(raw)
        assertEquals(listOf("a", "b"), songs.map { it.id })
        assertEquals("First", songs.first().title)
    }

    @Test
    fun songsRespectTheLimit() {
        val raw = (1..60).map { track("id$it") }
        assertEquals(MusicArtistPolicy.MAX_SONGS, MusicArtistPolicy.songs(raw).size)
        assertEquals(3, MusicArtistPolicy.songs(raw, limit = 3).size)
        assertTrue(MusicArtistPolicy.songs(raw, limit = 0).isEmpty())
        assertTrue(MusicArtistPolicy.songs(raw, limit = -5).isEmpty())
    }

    // ------------------------------------------------- подписчики и счётчики

    @Test
    fun subscribersLabelReadsServerSuffixes() {
        assertEquals("19,1 тыс. подписчиков", MusicArtistPolicy.subscribersLabel("19.1K"))
        assertEquals("1,2 млн подписчиков", MusicArtistPolicy.subscribersLabel("1.2M"))
        assertEquals("2 млрд подписчиков", MusicArtistPolicy.subscribersLabel("2B"))
        // Хвостовой ноль не печатаем.
        assertEquals("2 тыс. подписчиков", MusicArtistPolicy.subscribersLabel("2.0K"))
    }

    @Test
    fun subscribersLabelReadsPlainNumbersWithRussianPlural() {
        assertEquals("1 подписчик", MusicArtistPolicy.subscribersLabel("1"))
        assertEquals("2 подписчика", MusicArtistPolicy.subscribersLabel("2"))
        assertEquals("5 подписчиков", MusicArtistPolicy.subscribersLabel("5"))
        assertEquals("11 подписчиков", MusicArtistPolicy.subscribersLabel("11"))
        assertEquals("21 подписчик", MusicArtistPolicy.subscribersLabel("21"))
        // Разряды разделяются неразрывным пробелом, запятая как в серверной записи.
        assertEquals("1\u00A0234 подписчика", MusicArtistPolicy.subscribersLabel("1,234"))
    }

    @Test
    fun subscribersLabelHidesGarbageInsteadOfPrintingIt() {
        assertNull(MusicArtistPolicy.subscribersLabel(null))
        assertNull(MusicArtistPolicy.subscribersLabel(""))
        assertNull(MusicArtistPolicy.subscribersLabel("   "))
        assertNull(MusicArtistPolicy.subscribersLabel("много"))
        assertNull(MusicArtistPolicy.subscribersLabel("K"))
    }

    @Test
    fun songsLabelUsesRussianPlural() {
        assertEquals("1 песня", MusicArtistPolicy.songsLabel(1))
        assertEquals("3 песни", MusicArtistPolicy.songsLabel(3))
        assertEquals("5 песен", MusicArtistPolicy.songsLabel(5))
        assertEquals("11 песен", MusicArtistPolicy.songsLabel(11))
        assertEquals("21 песня", MusicArtistPolicy.songsLabel(21))
    }

    @Test
    fun subtitleJoinsNameAndSubscribers() {
        assertEquals("Test", MusicArtistPolicy.subtitle("  Test ", null))
        assertEquals("Test · 19,1 тыс. подписчиков", MusicArtistPolicy.subtitle("Test", "19.1K"))
        assertEquals(MusicArtistPolicy.UNKNOWN_ARTIST, MusicArtistPolicy.subtitle("  ", "19.1K"))
    }

    // --------------------------------------------------- состояния диалога

    @Test
    fun onlyOneRequestAtATime() {
        assertTrue(MusicArtistPolicy.canStartRequest(inFlight = false))
        assertFalse(MusicArtistPolicy.canStartRequest(inFlight = true))
    }

    @Test
    fun failureTextSeparatesNotFoundFromNetwork() {
        assertEquals(MusicArtistPolicy.NOT_FOUND, MusicArtistPolicy.failureText(notFound = true))
        assertEquals(MusicArtistPolicy.NETWORK_FAILURE, MusicArtistPolicy.failureText(notFound = false))
    }

    @Test
    fun retryOnlyMakesSenseForNetworkFailures() {
        assertFalse(MusicArtistPolicy.retryAvailable(notFound = true))
        assertTrue(MusicArtistPolicy.retryAvailable(notFound = false))
    }
}
