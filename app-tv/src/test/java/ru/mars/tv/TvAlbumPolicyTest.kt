package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.MusicAlbum
import ru.mars.network.model.MusicTrack

/**
 * Unit-тесты [TvAlbumPolicy] — чистых правил перехода «трек → альбом» на TV.
 *
 * Данные — из живого контракта волны (`wave1910b-common.md`): трек
 * `F9P_fIBfenk` / «не меняет» / «двадцать два» / `MPREb_4KOifxmY0Kc`, а не выдуманные
 * значения. Покрыто: префикс `MPRE` (включая регистр и пробелы), подпись строки с
 * альбомом и без (никаких висящих « · »), мета диалога без «null», склонение счётчика
 * треков, выбор «повторять или нет» и запрос воспроизведения с границами диапазона.
 */
class TvAlbumPolicyTest {

    private val openableId = "MPREb_4KOifxmY0Kc"

    private fun track(
        id: String = "F9P_fIBfenk",
        title: String = "станция конечная",
        artist: String = "не меняет",
        duration: String = "1:31",
        albumTitle: String? = "двадцать два",
        albumBrowseId: String? = openableId,
    ) = MusicTrack(
        id = id,
        title = title,
        artist = artist,
        duration = duration,
        albumTitle = albumTitle,
        albumBrowseId = albumBrowseId,
    )

    private fun album(tracks: List<MusicTrack> = listOf(track())) = MusicAlbum(
        browseId = openableId,
        title = "двадцать два",
        artist = "не меняет",
        year = "2024",
        tracks = tracks,
    )

    // --- requestTarget / openable -------------------------------------------------

    @Test
    fun `MPRE browseId passes through trimmed`() {
        assertEquals(openableId, TvAlbumPolicy.requestTarget("  $openableId  "))
    }

    @Test
    fun `empty, blank and non-MPRE ids are not openable`() {
        assertNull(TvAlbumPolicy.requestTarget(null))
        assertNull(TvAlbumPolicy.requestTarget(""))
        assertNull(TvAlbumPolicy.requestTarget("   "))
        // video id трека и id плейлиста — не альбом: сервер ответил бы 500.
        assertNull(TvAlbumPolicy.requestTarget("F9P_fIBfenk"))
        assertNull(TvAlbumPolicy.requestTarget("PL123"))
        // Регистр важен: сервер сверяет префикс как есть.
        assertNull(TvAlbumPolicy.requestTarget("mpreb_4KOifxmY0Kc"))
    }

    @Test
    fun `openable follows the track's browseId, not its album title`() {
        assertTrue(TvAlbumPolicy.openable(track()))
        // Имя есть, id нет — переходить некуда (строка остаётся подписью).
        assertFalse(TvAlbumPolicy.openable(track(albumBrowseId = null)))
        assertFalse(TvAlbumPolicy.openable(track(albumBrowseId = "")))
        assertFalse(TvAlbumPolicy.openable(null))
    }

    // --- trackLabel ---------------------------------------------------------------

    @Test
    fun `track label carries album between artist and duration`() {
        assertEquals(
            "станция конечная — не меняет · двадцать два · 1:31",
            TvAlbumPolicy.trackLabel(track()),
        )
    }

    @Test
    fun `track without album keeps the previous label`() {
        assertEquals(
            "станция конечная — не меняет · 1:31",
            TvAlbumPolicy.trackLabel(track(albumTitle = null, albumBrowseId = null)),
        )
    }

    @Test
    fun `blank album and placeholder duration leave no dangling separator`() {
        assertEquals(
            "станция конечная — не меняет",
            TvAlbumPolicy.trackLabel(
                track(albumTitle = "   ", albumBrowseId = null, duration = "--:--"),
            ),
        )
        assertEquals(
            "станция конечная — не меняет · двадцать два",
            TvAlbumPolicy.trackLabel(track(duration = "")),
        )
        assertFalse(TvAlbumPolicy.trackLabel(track(duration = "")).contains("·  ·"))
    }

    // --- диалог: мета, строки, счётчик --------------------------------------------

    @Test
    fun `dialog meta skips missing year and empty track list`() {
        assertEquals(
            "не меняет · 2024 · 1 трек",
            TvAlbumPolicy.dialogMeta(album()),
        )
        val bare = album().copy(year = null, tracks = emptyList())
        assertEquals("не меняет", TvAlbumPolicy.dialogMeta(bare))
        assertFalse(TvAlbumPolicy.dialogMeta(bare).contains("null"))
    }

    @Test
    fun `track count declines in Russian`() {
        assertEquals("1 трек", TvAlbumPolicy.trackCount(1))
        assertEquals("2 трека", TvAlbumPolicy.trackCount(2))
        assertEquals("4 трека", TvAlbumPolicy.trackCount(4))
        assertEquals("5 треков", TvAlbumPolicy.trackCount(5))
        assertEquals("11 треков", TvAlbumPolicy.trackCount(11))
        assertEquals("12 треков", TvAlbumPolicy.trackCount(12))
        assertEquals("21 трек", TvAlbumPolicy.trackCount(21))
        assertEquals("22 трека", TvAlbumPolicy.trackCount(22))
        assertEquals("0 треков", TvAlbumPolicy.trackCount(0))
    }

    @Test
    fun `track line inside album shows duration only when it is real`() {
        assertEquals("станция конечная · 1:31", TvAlbumPolicy.trackLine(track()))
        assertEquals("станция конечная", TvAlbumPolicy.trackLine(track(duration = "--:--")))
    }

    // --- отказ и повтор -----------------------------------------------------------

    @Test
    fun `not found is terminal, network failure is retryable`() {
        assertEquals(TvAlbumPolicy.NOT_FOUND, TvAlbumPolicy.failureText(notFound = true))
        assertEquals(TvAlbumPolicy.LOAD_FAILURE, TvAlbumPolicy.failureText(notFound = false))
        assertFalse(TvAlbumPolicy.retryVisible(notFound = true))
        assertTrue(TvAlbumPolicy.retryVisible(notFound = false))
    }

    // --- воспроизведение ----------------------------------------------------------

    @Test
    fun `play request uses the whole album from the chosen index`() {
        val tracks = listOf(track(id = "a"), track(id = "b"), track(id = "c"))
        val request = TvAlbumPolicy.playRequest(album(tracks), 1)
        assertEquals(3, request?.tracks?.size)
        assertEquals(1, request?.startIndex)
        assertEquals("b", request?.tracks?.get(1)?.id)
    }

    @Test
    fun `play request outside the album is null instead of the first track`() {
        val single = album(listOf(track()))
        assertNull(TvAlbumPolicy.playRequest(single, 1))
        assertNull(TvAlbumPolicy.playRequest(single, -1))
        assertNull(TvAlbumPolicy.playRequest(album(emptyList()), 0))
    }
}
