package ru.mars.mobile

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure playlist/history/translation logic behind the «Плейлисты» and
 * «История» tabs. No Android imports — every function here is exercised on the JVM with
 * fixed timestamps, so the labels are deterministic regardless of the machine's clock.
 */
class MusicLibraryPolicyTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    // ------------------------------------------------------- формат played_at

    @Test
    fun parsePlayedAtReadsTheLiveServerFormat() {
        // Live value from a6_history.json: 2026-10-12 09:37:59 UTC.
        assertEquals(1_791_797_879_000L, MusicLibraryPolicy.parsePlayedAt("2026-10-12 09:37:59"))
        // Seconds are optional in the same format.
        assertEquals(1_791_797_820_000L, MusicLibraryPolicy.parsePlayedAt("2026-10-12 09:37"))
    }

    @Test
    fun parsePlayedAtToleratesMissingOrBrokenValues() {
        assertEquals(MusicLibraryPolicy.UNKNOWN_PLAYED_AT, MusicLibraryPolicy.parsePlayedAt(null))
        assertEquals(MusicLibraryPolicy.UNKNOWN_PLAYED_AT, MusicLibraryPolicy.parsePlayedAt(""))
        assertEquals(MusicLibraryPolicy.UNKNOWN_PLAYED_AT, MusicLibraryPolicy.parsePlayedAt("   "))
        assertEquals(MusicLibraryPolicy.UNKNOWN_PLAYED_AT, MusicLibraryPolicy.parsePlayedAt("не дата"))
        assertEquals(MusicLibraryPolicy.UNKNOWN_PLAYED_AT, MusicLibraryPolicy.parsePlayedAt("12.10.2026"))
    }

    @Test
    fun playedAtLabelSaysTodayAndYesterday() {
        val now = MusicLibraryPolicy.parsePlayedAt("2026-10-12 20:00:00")

        assertEquals("сегодня, 09:37", MusicLibraryPolicy.playedAtLabel("2026-10-12 09:37:59", now, utc))
        assertEquals("вчера, 23:05", MusicLibraryPolicy.playedAtLabel("2026-10-11 23:05:00", now, utc))
    }

    @Test
    fun playedAtLabelGivesMonthAndTimeForOlderRows() {
        val now = MusicLibraryPolicy.parsePlayedAt("2026-10-12 20:00:00")

        // Same year: no year in the label.
        assertEquals("30 сентября, 14:25", MusicLibraryPolicy.playedAtLabel("2026-09-30 14:25:34", now, utc))
        // Another year: the year is spelled out so the row is not ambiguous.
        assertEquals("14 октября 2025, 22:37", MusicLibraryPolicy.playedAtLabel("2025-10-14 22:37:52", now, utc))
    }

    @Test
    fun playedAtLabelNeverLeavesABrokenLine() {
        val now = MusicLibraryPolicy.parsePlayedAt("2026-10-12 20:00:00")

        assertEquals(MusicLibraryPolicy.NO_DATE_LABEL, MusicLibraryPolicy.playedAtLabel(null, now, utc))
        assertEquals(MusicLibraryPolicy.NO_DATE_LABEL, MusicLibraryPolicy.playedAtLabel("мусор", now, utc))
        // An unparsable stamp must not be mistaken for "epoch 0 = today".
        assertEquals(MusicLibraryPolicy.NO_DATE_LABEL, MusicLibraryPolicy.playedAtLabel("1970-01-01 00:00:00", now, utc))
    }

    // ------------------------------------------------- русские числительные

    @Test
    fun playlistCountLabelUsesRussianPluralForms() {
        assertEquals("нет плейлистов", MusicLibraryPolicy.playlistCountLabel(0))
        assertEquals("1 плейлист", MusicLibraryPolicy.playlistCountLabel(1))
        assertEquals("2 плейлиста", MusicLibraryPolicy.playlistCountLabel(2))
        assertEquals("4 плейлиста", MusicLibraryPolicy.playlistCountLabel(4))
        assertEquals("5 плейлистов", MusicLibraryPolicy.playlistCountLabel(5))
        assertEquals("11 плейлистов", MusicLibraryPolicy.playlistCountLabel(11))
        assertEquals("14 плейлистов", MusicLibraryPolicy.playlistCountLabel(14))
        assertEquals("21 плейлист", MusicLibraryPolicy.playlistCountLabel(21))
        assertEquals("22 плейлиста", MusicLibraryPolicy.playlistCountLabel(22))
        assertEquals("25 плейлистов", MusicLibraryPolicy.playlistCountLabel(25))
        assertEquals("101 плейлист", MusicLibraryPolicy.playlistCountLabel(101))
    }

    @Test
    fun trackCountLabelUsesRussianPluralForms() {
        assertEquals("нет треков", MusicLibraryPolicy.trackCountLabel(0))
        assertEquals("1 трек", MusicLibraryPolicy.trackCountLabel(1))
        assertEquals("3 трека", MusicLibraryPolicy.trackCountLabel(3))
        assertEquals("5 треков", MusicLibraryPolicy.trackCountLabel(5))
        assertEquals("12 треков", MusicLibraryPolicy.trackCountLabel(12))
        assertEquals("22 трека", MusicLibraryPolicy.trackCountLabel(22))
    }

    @Test
    fun negativeCountsBehaveLikeEmptyLists() {
        assertEquals("нет плейлистов", MusicLibraryPolicy.playlistCountLabel(-3))
        assertEquals("нет треков", MusicLibraryPolicy.trackCountLabel(-1))
    }

    @Test
    fun playlistSubtitlePairsCountWithCreationDate() {
        // Live row: Street, 2026-09-30 14:25:34, track_count 1.
        assertEquals("1 трек · создан 2026-09-30 14:25:34", MusicLibraryPolicy.playlistSubtitle(1, "2026-09-30 14:25:34"))
        assertEquals("нет треков", MusicLibraryPolicy.playlistSubtitle(0, null))
        // A blank date must not leave a dangling separator.
        assertEquals("2 трека", MusicLibraryPolicy.playlistSubtitle(2, "   "))
    }

    // ------------------------------------------------------ состояния списка

    @Test
    fun loadingBeatsEverythingElse() {
        assertEquals(
            LibraryUiState.Loading,
            MusicLibraryPolicy.state(isLoading = true, error = "сеть", itemCount = 7),
        )
    }

    @Test
    fun errorWinsOverEmptinessSoTheScreenNeverLies() {
        assertEquals(
            LibraryUiState.Error("сервис отказал"),
            MusicLibraryPolicy.state(isLoading = false, error = "сервис отказал", itemCount = 0),
        )
    }

    @Test
    fun blankErrorIsNotAnError() {
        assertEquals(
            LibraryUiState.Empty,
            MusicLibraryPolicy.state(isLoading = false, error = "  ", itemCount = 0),
        )
    }

    @Test
    fun emptyAndContentSplitOnTheCount() {
        assertEquals(LibraryUiState.Empty, MusicLibraryPolicy.state(false, null, 0))
        assertEquals(LibraryUiState.Content, MusicLibraryPolicy.state(false, null, 1))
    }

    // ------------------------------------------------------ ошибки создания

    @Test
    fun createPlaylistErrorTranslatesTheServerWording() {
        // Live 400 body: {"success":false,"error":"Title required"}.
        assertEquals("Укажите название плейлиста", MusicLibraryPolicy.createPlaylistError("Title required"))
        assertEquals("Не удалось создать плейлист — проверьте сеть", MusicLibraryPolicy.createPlaylistError(null))
        assertEquals("Не удалось создать плейлист — проверьте сеть", MusicLibraryPolicy.createPlaylistError(""))
        // An unknown server reason is shown as it is — never swallowed.
        assertEquals("No such playlist", MusicLibraryPolicy.createPlaylistError("No such playlist"))
    }

    @Test
    fun playlistWriteErrorFallsBackOnlyWhenTheServerSaidNothing() {
        assertEquals("Не удалось добавить трек", MusicLibraryPolicy.playlistWriteError(null, "Не удалось добавить трек"))
        assertEquals("Не удалось добавить трек", MusicLibraryPolicy.playlistWriteError("   ", "Не удалось добавить трек"))
        assertEquals("Missing id", MusicLibraryPolicy.playlistWriteError("Missing id", "Не удалось добавить трек"))
    }

    // ------------------------------------------------ запись истории (дедуп)

    @Test
    fun firstTrackIsAlwaysRecorded() {
        assertTrue(HistoryRecordPolicy.shouldRecord("dc0YRAQGAz4", null))
    }

    @Test
    fun repeatedStateFramesDoNotReRecordTheSameTrack() {
        // Mars Connect re-broadcasts on pause/resume/progress: one write per track.
        assertFalse(HistoryRecordPolicy.shouldRecord("dc0YRAQGAz4", "dc0YRAQGAz4"))
        // A genuinely new track is written.
        assertTrue(HistoryRecordPolicy.shouldRecord("8embeaRU9xk", "dc0YRAQGAz4"))
    }

    @Test
    fun blankTrackIdIsNeverRecorded() {
        assertFalse(HistoryRecordPolicy.shouldRecord(null, "dc0YRAQGAz4"))
        assertFalse(HistoryRecordPolicy.shouldRecord("", null))
        assertFalse(HistoryRecordPolicy.shouldRecord("   ", null))
    }

    // ----------------------------------------------------- перевод текста

    @Test
    fun actionLabelDescribesWhatTheTapWillDo() {
        assertEquals("Перевод", LyricsTranslationPolicy.actionLabel(showingTranslation = false))
        assertEquals("Оригинал", LyricsTranslationPolicy.actionLabel(showingTranslation = true))
    }

    @Test
    fun canTranslateOnlyWithARealOriginal() {
        assertTrue(LyricsTranslationPolicy.canTranslate("Тёплое место"))
        assertFalse(LyricsTranslationPolicy.canTranslate(null))
        assertFalse(LyricsTranslationPolicy.canTranslate(""))
        assertFalse(LyricsTranslationPolicy.canTranslate("  \n "))
    }

    @Test
    fun bodySwitchesBetweenOriginalAndTranslation() {
        val original = "Тёплое место"

        assertEquals(original, LyricsTranslationPolicy.bodyFor(original, "Warm place", showingTranslation = false))
        assertEquals("Warm place", LyricsTranslationPolicy.bodyFor(original, "Warm place", showingTranslation = true))
        assertEquals(original, LyricsTranslationPolicy.bodyFor(original, null, showingTranslation = true))
    }

    @Test
    fun emptyTranslationNeverWipesTheOriginal() {
        // Live answer for an empty input is a bare translated:"" — must show the original.
        assertEquals("Тёплое место", LyricsTranslationPolicy.bodyFor("Тёплое место", "", showingTranslation = true))
        assertEquals("Тёплое место", LyricsTranslationPolicy.bodyFor("Тёплое место", "   ", showingTranslation = true))
    }

    @Test
    fun emptyAndFailedTranslationsHaveTheirOwnWording() {
        assertEquals("Перевод не получен — сервис вернул пустой ответ", LyricsTranslationPolicy.emptyTranslationText())
        assertEquals("Перевод недоступен — проверьте сеть", LyricsTranslationPolicy.failureText())
    }
}
