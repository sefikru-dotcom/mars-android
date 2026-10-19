package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.MusicAlbumSummary

/**
 * Unit-тесты политики навигации по альбомам. Значения — из живого ответа
 * `GET /api/search?q=не меняет&type=albums`:
 * `{browseId:"MPREb_4KOifxmY0Kc", title:"двадцать два", artist:"не меняет", year:"2024"}`.
 * Сети здесь нет: проверяются правила, а не транспорт (транспорт — в
 * core-network/MusicAlbumSearchRepositoryTest).
 */
class MusicAlbumPolicyTest {

    private fun album(
        browseId: String = "MPREb_4KOifxmY0Kc",
        title: String = "двадцать два",
        artist: String = "не меняет",
        year: String? = "2024",
    ) = MusicAlbumSummary(browseId = browseId, title = title, artist = artist, year = year)

    // ------------------------------------------------------- что открывать

    @Test
    fun requestTargetTrimsAndKeepsMpreBrowseIds() {
        assertEquals("MPREb_4KOifxmY0Kc", MusicAlbumPolicy.requestTarget("  MPREb_4KOifxmY0Kc  "))
        assertEquals("MPREb_6bnrNH0ZVMS", MusicAlbumPolicy.requestTarget("MPREb_6bnrNH0ZVMS"))
    }

    @Test
    fun requestTargetRejectsBlankForeignAndLowercaseIds() {
        assertNull(MusicAlbumPolicy.requestTarget(null))
        assertNull(MusicAlbumPolicy.requestTarget(""))
        assertNull(MusicAlbumPolicy.requestTarget("   "))
        // Имя исполнителя — не browseId: /api/album на него отвечает отказом.
        assertNull(MusicAlbumPolicy.requestTarget("не меняет"))
        // Плейлист/канал тоже не альбом.
        assertNull(MusicAlbumPolicy.requestTarget("VLPLnotAnAlbum"))
        // Сервер сравнивает префикс с учётом регистра — «починить» его клиент не может.
        assertNull(MusicAlbumPolicy.requestTarget("mpreb_4KOifxmY0Kc"))
    }

    // --------------------------------------------------------- слияние выдачи

    @Test
    fun mergeKeepsServerOrderAndDropsDuplicates() {
        val remote = listOf(
            album("MPREb_4KOifxmY0Kc", "двадцать два"),
            album("MPREb_6bnrNH0ZVMS", "я одержим", year = "2026"),
            album("MPREb_4KOifxmY0Kc", "двадцать два (дубль)"),
        )
        val merged = MusicAlbumPolicy.mergeSummaries(remote)
        assertEquals(listOf("MPREb_4KOifxmY0Kc", "MPREb_6bnrNH0ZVMS"), merged.map { it.browseId })
        // Первое вхождение побеждает: дубль не перезаписывает карточку.
        assertEquals("двадцать два", merged.first().title)
    }

    @Test
    fun mergeAppendsAlreadyShownAlbumsAfterTheFreshOnes() {
        val shown = listOf(album("MPREb_old", "старый"))
        val fresh = listOf(album("MPREb_new", "новый"))
        val merged = MusicAlbumPolicy.mergeSummaries(fresh, shown)
        assertEquals(listOf("MPREb_new", "MPREb_old"), merged.map { it.browseId })
        // Тот же альбом в свежей выдаче не дублируется.
        assertEquals(2, MusicAlbumPolicy.mergeSummaries(fresh + shown, shown).size)
    }

    @Test
    fun mergeDropsRowsWithoutBrowseId() {
        val remote = listOf(album("", "мусор"), album("MPREb_4KOifxmY0Kc"))
        assertEquals(listOf("MPREb_4KOifxmY0Kc"), MusicAlbumPolicy.mergeSummaries(remote).map { it.browseId })
    }

    @Test
    fun hasAlbumsIgnoresRowsWithoutBrowseId() {
        assertFalse(MusicAlbumPolicy.hasAlbums(emptyList()))
        assertFalse(MusicAlbumPolicy.hasAlbums(listOf(album(""))))
        assertTrue(MusicAlbumPolicy.hasAlbums(listOf(album())))
    }

    @Test
    fun emptyStateTextSaysThereAreNoAlbums() {
        // Текст пустого состояния — часть политики, а не слоя вью.
        assertEquals("Альбомов нет", MusicAlbumPolicy.EMPTY)
    }

    // -------------------------------------------------------------- подписи

    @Test
    fun tracksLabelUsesRussianPlural() {
        assertEquals("1 трек", MusicAlbumPolicy.tracksLabel(1))
        assertEquals("3 трека", MusicAlbumPolicy.tracksLabel(3))
        assertEquals("5 треков", MusicAlbumPolicy.tracksLabel(5))
        assertEquals("11 треков", MusicAlbumPolicy.tracksLabel(11))
        assertEquals("21 трек", MusicAlbumPolicy.tracksLabel(21))
    }

    @Test
    fun metaJoinsYearAndTrackCount() {
        // Живой ответ: year="2024", trackCount=3.
        assertEquals("2024 · 3 трека", MusicAlbumPolicy.meta("2024", 3))
        assertEquals("2024", MusicAlbumPolicy.meta("2024", 0))
        assertEquals("3 трека", MusicAlbumPolicy.meta(null, 3))
        assertEquals("", MusicAlbumPolicy.meta("   ", 0))
    }

    @Test
    fun subtitleJoinsArtistAndYear() {
        assertEquals("не меняет · 2024", MusicAlbumPolicy.subtitle(album()))
        assertEquals("не меняет", MusicAlbumPolicy.subtitle(album(year = null)))
        assertEquals("2024", MusicAlbumPolicy.subtitle(album(artist = "  ")))
        assertEquals("", MusicAlbumPolicy.subtitle(album(artist = "", year = null)))
    }

    @Test
    fun cardDescriptionReadsTheAction() {
        assertEquals(
            "Открыть альбом: двадцать два — не меняет",
            MusicAlbumPolicy.cardDescription(album()),
        )
        assertEquals(
            "Открыть альбом: двадцать два",
            MusicAlbumPolicy.cardDescription(album(artist = "  ")),
        )
        assertEquals(
            "Открыть альбом: Без названия",
            MusicAlbumPolicy.cardDescription(album(title = " ", artist = "")),
        )
    }

    // ---------------------------------------------------------- состояния

    @Test
    fun failureTextSeparatesRefusalFromNetwork() {
        assertEquals(MusicAlbumPolicy.NOT_FOUND, MusicAlbumPolicy.failureText(notFound = true))
        assertEquals(MusicAlbumPolicy.NETWORK_FAILURE_ALBUM, MusicAlbumPolicy.failureText(notFound = false))
        assertFalse(MusicAlbumPolicy.retryAvailable(notFound = true))
        assertTrue(MusicAlbumPolicy.retryAvailable(notFound = false))
    }
}
