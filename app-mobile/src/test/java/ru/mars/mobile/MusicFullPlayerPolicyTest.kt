package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * JVM-тесты [MusicFullPlayerPolicy]: заголовок/артист/обложка раскрытого плеера и
 * состояние сердечка.
 *
 * Главный регресс — реальный баг веб-версии: раскрытый плеер обновлял метаданные только
 * при открытии, и при автопереходе на следующий трек на большом экране оставались
 * название, артист и обложка предыдущего. Поэтому заголовок выводится ИЗ ПЕРЕДАВАЕМОГО
 * трека, а не из состояния, снятого при открытии: тест `nextTrackReplacesAllMetadata`
 * проверяет именно смену трека.
 */
class MusicFullPlayerPolicyTest {

    private fun track(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist $id",
        album: String? = null,
        thumb: String? = null,
    ) = MusicTrack(id = id, title = title, artist = artist, albumTitle = album, thumbnail = thumb)

    // ---- метаданные ----

    @Test fun emptyPlayerKeepsTheBarWording() {
        assertEquals(MusicFullPlayerPolicy.EMPTY_TITLE, MusicFullPlayerPolicy.title(null))
        assertEquals(MusicFullPlayerPolicy.EMPTY_ARTIST, MusicFullPlayerPolicy.artist(null))
        assertNull(MusicFullPlayerPolicy.artworkUrl(null))
    }

    @Test fun metadataComesFromTheCurrentTrack() {
        val t = track("a", title = "Название", artist = "Исполнитель")
        assertEquals("Название", MusicFullPlayerPolicy.title(t))
        assertEquals("Исполнитель", MusicFullPlayerPolicy.artist(t))
    }

    @Test fun blankTitleFallsBackToEmptyState() {
        // Пустое название не должно оставлять в заголовке пустую строку: там читается
        // «Ничего не играет», иначе вьюха выглядит сломанной.
        assertEquals(MusicFullPlayerPolicy.EMPTY_TITLE, MusicFullPlayerPolicy.title(track("a", title = "  ")))
        assertEquals(MusicFullPlayerPolicy.EMPTY_TITLE, MusicFullPlayerPolicy.title(track("a", title = "")))
    }

    @Test fun albumIsAppendedToArtistLikeTheBar() {
        // Раскрытый плеер и нижняя полоса берут подпись из одной политики: расхождение
        // формата между поверхностями — тот же класс бага, что необновлённый заголовок.
        val t = track("a", artist = "Исполнитель", album = "Альбом")
        assertEquals(
            TrackSubtitlePolicy.subtitle("Исполнитель", "Альбом", null),
            MusicFullPlayerPolicy.artist(t),
        )
        assertTrue(MusicFullPlayerPolicy.artist(t).contains("Альбом"))
    }

    @Test fun nextTrackReplacesAllMetadata() {
        val first = track("a", title = "Первый", artist = "Кто-то", album = "Альбом 1", thumb = "https://x/1.jpg")
        val second = track("b", title = "Второй", artist = "Другой", album = "Альбом 2", thumb = "https://x/2.jpg")

        // Плеер смотрит на ТЕКУЩИЙ трек — оба кадра разные, ничего не «залипает».
        assertEquals("Первый", MusicFullPlayerPolicy.title(first))
        assertEquals("Второй", MusicFullPlayerPolicy.title(second))
        assertEquals("Кто-то · Альбом 1", MusicFullPlayerPolicy.artist(first))
        assertEquals("Другой · Альбом 2", MusicFullPlayerPolicy.artist(second))
        assertTrue(
            "обложка обязана перезагрузиться при смене трека",
            MusicFullPlayerPolicy.shouldReloadArtwork(
                MusicFullPlayerPolicy.artworkUrl(first),
                MusicFullPlayerPolicy.artworkUrl(second),
            ),
        )
    }

    // ---- обложка ----

    @Test fun artworkUrlIsTrimmedAndBlankIsNoArtwork() {
        assertEquals("https://x/1.jpg", MusicFullPlayerPolicy.artworkUrl(track("a", thumb = " https://x/1.jpg ")))
        assertNull(MusicFullPlayerPolicy.artworkUrl(track("a", thumb = null)))
        assertNull(MusicFullPlayerPolicy.artworkUrl(track("a", thumb = "   ")))
    }

    @Test fun sameArtworkIsNotReloaded() {
        // Один альбом, прогресс/pause-resume: загрузчик не дёргается.
        assertFalse(MusicFullPlayerPolicy.shouldReloadArtwork("https://x/1.jpg", "https://x/1.jpg"))
        assertFalse(MusicFullPlayerPolicy.shouldReloadArtwork(null, null))
    }

    @Test fun missingArtworkTogglesVisibility() {
        assertTrue(MusicFullPlayerPolicy.shouldReloadArtwork("https://x/1.jpg", null))
        assertTrue(MusicFullPlayerPolicy.shouldReloadArtwork(null, "https://x/1.jpg"))
    }

    // ---- сердечко ----

    @Test fun heartReflectsActualFavoriteState() {
        val favs = setOf("a")
        val filled = MusicFullPlayerPolicy.heart("a", favs, busy = false)
        assertTrue(filled.filled)
        assertTrue(filled.selected)
        assertTrue(filled.enabled)
        assertEquals(MusicA11yPolicy.heartAction(true), filled.description)

        val empty = MusicFullPlayerPolicy.heart("b", favs, busy = false)
        assertFalse(empty.filled)
        assertFalse(empty.selected)
        assertEquals(MusicA11yPolicy.heartAction(false), empty.description)
    }

    @Test fun heartWithoutTrackIsDisabled() {
        val state = MusicFullPlayerPolicy.heart(null, setOf("a"), busy = false)
        assertFalse("без трека сердце не должно выглядеть нажатым", state.filled)
        assertFalse(state.enabled)
    }

    @Test fun inFlightRequestDisablesButKeepsTheLabelHonest() {
        // Пока запрос в полёте, состояние ещё не изменилось: подпись обязана описывать
        // текущее, а не желаемое — иначе TalkBack объявит действие, которого не было.
        val busy = MusicFullPlayerPolicy.heart("a", setOf("a"), busy = true)
        assertFalse(busy.enabled)
        assertTrue(busy.filled)
        assertEquals(MusicA11yPolicy.heartAction(true), busy.description)
    }

    @Test fun emptyFavoritesSetMeansNothingIsFilled() {
        assertFalse(MusicFullPlayerPolicy.heart("a", emptySet(), busy = false).filled)
    }

    // ---- подпись открытия из нижней полосы ----

    @Test fun openActionNamesTheTrackForTalkBack() {
        assertEquals(
            "пустой плеер остаётся с чистым действием",
            MusicFullPlayerPolicy.ACTION_OPEN,
            MusicFullPlayerPolicy.openAction(null),
        )
        assertEquals(
            MusicFullPlayerPolicy.ACTION_OPEN,
            MusicFullPlayerPolicy.openAction(track("a", title = "  ")),
        )
        assertTrue(
            "название трека обязано попасть в подпись узла, который открывает плеер",
            MusicFullPlayerPolicy.openAction(track("a", title = "Полёт")).contains("Полёт"),
        )
    }

    @Test fun artworkActionNamesTheTrackLikeTheBarOpenLabel() {
        assertEquals(MusicFullPlayerPolicy.ACTION_LYRICS, MusicFullPlayerPolicy.artworkAction(null))
        assertTrue(MusicFullPlayerPolicy.artworkAction(track("a", title = "Полёт")).contains("Полёт"))
    }
}
