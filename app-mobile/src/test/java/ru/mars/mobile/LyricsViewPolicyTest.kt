package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.LyricsResult

class LyricsViewPolicyTest {

    @Test fun plainLyricsWinAndAreMarkedNotSynced() {
        val state = LyricsViewPolicy.resolve(LyricsResult("Line one\nLine two", "[00:01.00] other", "musixmatch"))
        assertTrue(state is LyricsUiState.Text)
        state as LyricsUiState.Text
        assertEquals("Line one\nLine two", state.body)
        assertFalse(state.synced)
        assertEquals("musixmatch", state.source)
    }

    @Test fun syncedLrcFallsBackWithTimestampsStripped() {
        val state = LyricsViewPolicy.resolve(LyricsResult(null, "[00:12.00] Line one\n[00:15.50] Line two", "lrclib"))
        assertTrue(state is LyricsUiState.Text)
        state as LyricsUiState.Text
        assertEquals("Line one\nLine two", state.body)
        assertTrue(state.synced)
        assertEquals("lrclib", state.source)
    }

    @Test fun blankPlainLyricsFallThroughToSynced() {
        val state = LyricsViewPolicy.resolve(LyricsResult("   \n ", "[01:02.34] Real line", null))
        state as LyricsUiState.Text
        assertEquals("Real line", state.body)
        assertTrue(state.synced)
    }

    @Test fun emptyEverythingIsMissing() {
        assertTrue(LyricsViewPolicy.resolve(null) is LyricsUiState.Missing)
        assertTrue(LyricsViewPolicy.resolve(LyricsResult(null, null, null)) is LyricsUiState.Missing)
        assertTrue(LyricsViewPolicy.resolve(LyricsResult("", "", "src")) is LyricsUiState.Missing)
    }

    @Test fun syncedWithOnlyMetaAndTimeTagsIsMissing() {
        val state = LyricsViewPolicy.resolve(LyricsResult(null, "[ar:Artist]\n[ti:Title]\n[00:00.00]\n", null))
        assertTrue(state is LyricsUiState.Missing)
    }

    @Test fun stripsAllLrcTimestampFormats() {
        val synced = "[00:01] a\n[01:02.3] b\n[01:02.345] c\n[12:34:56] d"
        assertEquals("a\nb\nc\nd", LyricsViewPolicy.stripSyncTags(synced))
    }

    @Test fun stripsLrcMetadataLines() {
        val synced = "[ar:Artist Name]\n[ti:Song]\n[offset:+500]\n[00:01.00] First line"
        assertEquals("First line", LyricsViewPolicy.stripSyncTags(synced).trim().replace(Regex("\n+"), "\n"))
    }

    @Test fun keepsSectionMarkersLikeVerseHeaders() {
        // Квадратные скобки секций («[Припев]») — часть текста, резать их нельзя.
        val result = LyricsResult("[Intro]\nFirst verse\n\n[Chorus]\nLa la", null, null)
        val state = LyricsViewPolicy.resolve(result) as LyricsUiState.Text
        assertEquals("[Intro]\nFirst verse\n\n[Chorus]\nLa la", state.body)
    }

    @Test fun normalizesTrailingSpacesAndBlankRunRuns() {
        val raw = "Line one   \n\n\n\n\nLine two\n\n"
        assertEquals("Line one\n\nLine two", LyricsViewPolicy.normalize(raw))
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("Body", LyricsViewPolicy.normalize("\n\n  Body  \n\n"))
    }

    @Test fun timestampInMiddleOfLineIsAlsoRemoved() {
        assertEquals("duet line", LyricsViewPolicy.stripSyncTags("duet [00:33.00] line").trim())
    }
}
