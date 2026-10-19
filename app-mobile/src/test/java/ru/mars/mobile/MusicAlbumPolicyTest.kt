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

    // ------------------------------------------------------- чужие в выдаче

    @Test
    fun rankPutsOwnAlbumsFirstKeepingServerOrderInsideGroups() {
        // Порядок и состав — как в живой выдаче по «Linkin Park»: своя одна,
        // остальные — трибьюты и каверы.
        val albums = listOf(
            album("MPREb_tribute", "Tribute to Linkin Park", artist = "Various Artists"),
            album("MPREb_hybrid", "Hybrid Theory", artist = "Linkin Park"),
            album("MPREb_mtg", "Mtg Linkin Park", artist = "Mano DJ — Mtg Linkin Park"),
            album("MPREb_meteora", "Meteora", artist = "linkin park"),
        )
        val ranked = MusicAlbumPolicy.rankForArtist(albums, "Linkin Park")
        assertEquals(
            listOf("MPREb_hybrid", "MPREb_meteora", "MPREb_tribute", "MPREb_mtg"),
            ranked.map { it.browseId },
        )
        // Ничего не потеряно и не добавлено: перестановка, а не фильтр.
        assertEquals(albums.size, ranked.size)
        assertEquals(albums.map { it.browseId }.toSet(), ranked.map { it.browseId }.toSet())
    }

    @Test
    fun rankReturnsWholeListWhenNothingMatches() {
        // Живой кейс: запрос `Zemfira` отдаёт альбомы «Земфира» — точных
        // совпадений строки 0. Строгий фильтр показал бы «Альбомов нет».
        val albums = listOf(
            album("MPREb_z1", "ПРОСТИ МЕНЯ МОЯ ЛЮБОВЬ", artist = "Земфира"),
            album("MPREb_z2", "Вендетта", artist = "Земфира"),
            album("MPREb_z3", "Zemfira Tribute", artist = "Various Artists"),
        )
        val ranked = MusicAlbumPolicy.rankForArtist(albums, "Zemfira")
        assertEquals(listOf("MPREb_z1", "MPREb_z2", "MPREb_z3"), ranked.map { it.browseId })
    }

    @Test
    fun rankTreatsCoverWithNameInTheTailAsForeignButKeepsIt() {
        // «Mano DJ — Mtg Linkin Park»: имя исполнителя лишь встречается внутри,
        // альбом не его — но из списка не исчезает.
        val albums = listOf(
            album("MPREb_mtg", "Mtg Linkin Park", artist = "Mano DJ — Mtg Linkin Park"),
            album("MPREb_hybrid", "Hybrid Theory", artist = "Linkin Park"),
        )
        val ranked = MusicAlbumPolicy.rankForArtist(albums, "Linkin Park")
        assertEquals(listOf("MPREb_hybrid", "MPREb_mtg"), ranked.map { it.browseId })
    }

    @Test
    fun rankDoesNotMatchPartialWord() {
        // «Кинотеатр» не «Кино»: совпадение по буквам, а не по слову, — чужой.
        val albums = listOf(
            album("MPREb_other", "Чужое", artist = "Кинотеатр"),
            album("MPREb_kino", "Группа крови", artist = "Кино"),
        )
        assertEquals(
            listOf("MPREb_kino", "MPREb_other"),
            MusicAlbumPolicy.rankForArtist(albums, "Кино").map { it.browseId },
        )
    }

    @Test
    fun rankDoesNotTreatSameFirstNameAsOwn() {
        // Живой замер по `Zemfira`: выдача забита однофамильцами — это РАЗНЫЕ
        // исполнители, и поднимать их наверх нельзя. Настоящая «Земфира» здесь
        // идёт в кириллице, поэтому «своих» не остаётся вовсе и список
        // возвращается в серверном порядке — без «Альбомов нет».
        val albums = listOf(
            album("MPREb_ararat", "Я сильная", artist = "ZEMFIRA ARARAT"),
            album("MPREb_atara", "Не такая, как все", artist = "Zemfira Atara"),
            album("MPREb_mironova", "Želja", artist = "Zemfira Mironova"),
            album("MPREb_real", "ЖИТЬ В ТВОЕЙ ГОЛОВЕ", artist = "Земфира"),
        )
        val ranked = MusicAlbumPolicy.rankForArtist(albums, "Zemfira")
        assertEquals(
            listOf("MPREb_ararat", "MPREb_atara", "MPREb_mironova", "MPREb_real"),
            ranked.map { it.browseId },
        )
        // Ни одна карточка не пропала: это перестановка, а не фильтр.
        assertEquals(albums.size, ranked.size)
    }

    @Test
    fun rankDoesNotTreatTributeBandAsOwn() {
        // «Linkin Park Tribute Band» — продолжение не маркер соавторства, значит чужой.
        val albums = listOf(
            album("MPREb_band", "Tribute", artist = "Linkin Park Tribute Band"),
            album("MPREb_hybrid", "Hybrid Theory", artist = "Linkin Park"),
        )
        assertEquals(
            listOf("MPREb_hybrid", "MPREb_band"),
            MusicAlbumPolicy.rankForArtist(albums, "Linkin Park").map { it.browseId },
        )
    }

    @Test
    fun rankCountsFeatInArtistNameAsOwn() {
        // Обе стороны: на странице «Linkin Park», у альбома «Linkin Park feat. X» —
        // и наоборот, когда страница открыта по полному имени.
        val albums = listOf(
            album("MPREb_tribute", "Tribute", artist = "Various Artists"),
            album("MPREb_collab", "Collision Course", artist = "Linkin Park feat. Jay-Z"),
        )
        assertEquals(
            listOf("MPREb_collab", "MPREb_tribute"),
            MusicAlbumPolicy.rankForArtist(albums, "Linkin Park").map { it.browseId },
        )
        assertEquals(
            listOf("MPREb_collab", "MPREb_tribute"),
            MusicAlbumPolicy.rankForArtist(albums, "Linkin Park feat. Jay-Z").map { it.browseId },
        )
    }

    @Test
    fun rankIgnoresCasePunctuationAndSpaces() {
        val albums = listOf(
            album("MPREb_other", "Чужое", artist = "Volika — Жизнь - кино"),
            album("MPREb_kino", "Группа крови", artist = "  КИНО! "),
        )
        assertEquals(
            listOf("MPREb_kino", "MPREb_other"),
            MusicAlbumPolicy.rankForArtist(albums, "Кино").map { it.browseId },
        )
    }

    @Test
    fun rankLeavesListAloneForBlankArtistName() {
        val albums = listOf(
            album("MPREb_a", "Первый", artist = "Кто-то"),
            album("MPREb_b", "Второй", artist = "Другой"),
        )
        assertEquals(listOf("MPREb_a", "MPREb_b"), MusicAlbumPolicy.rankForArtist(albums, "").map { it.browseId })
        assertEquals(listOf("MPREb_a", "MPREb_b"), MusicAlbumPolicy.rankForArtist(albums, "   ").map { it.browseId })
        assertEquals(albums, MusicAlbumPolicy.rankForArtist(albums, "  "))
    }

    @Test
    fun rankKeepsOrderWhenAllAlbumsAreOwn() {
        val albums = listOf(
            album("MPREb_a", "Первый"),
            album("MPREb_b", "Второй"),
            album("MPREb_c", "Третий"),
        )
        assertEquals(albums, MusicAlbumPolicy.rankForArtist(albums, "не меняет"))
    }

    @Test
    fun rankOfEmptyListIsEmpty() {
        assertEquals(emptyList<MusicAlbumSummary>(), MusicAlbumPolicy.rankForArtist(emptyList(), "Linkin Park"))
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
