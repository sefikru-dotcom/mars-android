package ru.mars.mobile

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search history policy: normalize / case-insensitive dedupe / cap of 8 /
 * delete / clear, persistence through the storage port, race guards (a
 * superseded or cancelled run never applies to the UI nor records history)
 * and the overlay visibility rule.
 */
class MusicSearchHistoryTest {

    // --- MusicSearchHistoryPolicy.normalize ---

    @Test fun normalizeTrimsWhitespace() {
        assertEquals("jazz", MusicSearchHistoryPolicy.normalize("  jazz  "))
    }

    @Test fun normalizeRejectsBlankInput() {
        assertNull(MusicSearchHistoryPolicy.normalize(""))
        assertNull(MusicSearchHistoryPolicy.normalize("   "))
        assertNull(MusicSearchHistoryPolicy.normalize(null))
    }

    // --- onSearchSucceeded: newest-first, dedupe, cap ---

    @Test fun successfulSearchGoesToFrontNewestFirst() {
        val h = MusicSearchHistoryPolicy.onSearchSucceeded(listOf("rock"), "jazz")
        assertEquals(listOf("jazz", "rock"), h)
    }

    @Test fun repeatedSearchMovesExistingQueryToFront() {
        val h = MusicSearchHistoryPolicy.onSearchSucceeded(listOf("jazz", "rock", "pop"), "rock")
        assertEquals(listOf("rock", "jazz", "pop"), h)
    }

    @Test fun dedupeIsCaseInsensitiveAndKeepsNewestSpelling() {
        val h = MusicSearchHistoryPolicy.onSearchSucceeded(listOf("JAZZ", "rock"), " jazz ")
        assertEquals(listOf("jazz", "rock"), h)
    }

    @Test fun historyIsCappedAtEightNewest() {
        var h = emptyList<String>()
        (1..10).forEach { h = MusicSearchHistoryPolicy.onSearchSucceeded(h, "q$it") }
        assertEquals(MusicSearchHistoryPolicy.MAX_ENTRIES, h.size)
        assertEquals(listOf("q10", "q9", "q8", "q7", "q6", "q5", "q4", "q3"), h)
    }

    @Test fun blankQueryNeverEntersHistory() {
        val before = listOf("jazz")
        assertEquals(before, MusicSearchHistoryPolicy.onSearchSucceeded(before, "  "))
        assertEquals(before, MusicSearchHistoryPolicy.onSearchSucceeded(before, null))
    }

    // --- delete ---

    @Test fun deleteRemovesEntryCaseInsensitivelyKeepingOrder() {
        val h = MusicSearchHistoryPolicy.delete(listOf("jazz", "ROCK", "pop"), "rock")
        assertEquals(listOf("jazz", "pop"), h)
    }

    @Test fun deleteOfUnknownOrBlankEntryKeepsHistory() {
        val before = listOf("jazz", "rock")
        assertEquals(before, MusicSearchHistoryPolicy.delete(before, "soul"))
        assertEquals(before, MusicSearchHistoryPolicy.delete(before, "  "))
    }

    // --- sanitize (restore path) ---

    @Test fun sanitizeTrimsDropsBlanksDedupesAndCaps() {
        val raw = listOf("  jazz ", "", "Jazz", "rock", "a", "b", "c", "d", "e", "f", "g")
        val h = MusicSearchHistoryPolicy.sanitize(raw)
        assertEquals(listOf("jazz", "rock", "a", "b", "c", "d", "e", "f"), h)
    }

    @Test fun sanitizeIsIdempotent() {
        val once = MusicSearchHistoryPolicy.sanitize(listOf(" jazz ", "JAZZ", "rock"))
        assertEquals(once, MusicSearchHistoryPolicy.sanitize(once))
    }

    // --- MusicSearchHistory (domain class over the storage port) ---

    private class FakeStorage(initial: List<String> = emptyList()) : MusicSearchHistoryStorage {
        var stored: List<String> = initial
        var saveCount = 0
        override fun load(): List<String> = stored
        override fun save(entries: List<String>) {
            stored = entries
            saveCount++
        }
    }

    @Test fun recordSuccessPersistsNewestFirst() {
        val storage = FakeStorage()
        val history = MusicSearchHistory(storage)
        history.recordSuccess("jazz")
        history.recordSuccess("  rock ")
        assertEquals(listOf("rock", "jazz"), history.entries)
        assertEquals(listOf("rock", "jazz"), storage.stored)
        assertEquals(2, storage.saveCount)
    }

    @Test fun restoredHistoryIsSanitized() {
        val history = MusicSearchHistory(FakeStorage(listOf("  jazz ", "JAZZ", "", "rock")))
        assertEquals(listOf("jazz", "rock"), history.entries)
    }

    @Test fun deleteAndClearPersist() {
        val storage = FakeStorage(listOf("jazz", "rock", "pop"))
        val history = MusicSearchHistory(storage)
        history.delete("ROCK")
        assertEquals(listOf("jazz", "pop"), history.entries)
        assertEquals(listOf("jazz", "pop"), storage.stored)
        history.clear()
        assertEquals(emptyList<String>(), history.entries)
        assertEquals(emptyList<String>(), storage.stored)
    }

    @Test fun blankSuccessIsNotPersisted() {
        val storage = FakeStorage(listOf("jazz"))
        val history = MusicSearchHistory(storage)
        history.recordSuccess("   ")
        assertEquals(listOf("jazz"), history.entries)
        assertEquals(listOf("jazz"), storage.stored)
        assertEquals(1, storage.saveCount) // idempotent write-through, list unchanged
    }

    // --- SearchRunGuard (race guards) ---

    @Test fun liveCurrentRunMayApplyAndRecord() {
        assertTrue(SearchRunGuard.mayApplyResult(runId = 3, currentRunId = 3, runCancelled = false))
    }

    @Test fun supersededRunNeverAppliesToUiOrHistory() {
        assertFalse(SearchRunGuard.mayApplyResult(runId = 2, currentRunId = 3, runCancelled = false))
    }

    @Test fun cancelledRunNeverAppliesEvenIfStillCurrent() {
        assertFalse(SearchRunGuard.mayApplyResult(runId = 3, currentRunId = 3, runCancelled = true))
    }

    @Test fun cancellationIsNotTreatedAsSearchError() {
        assertTrue(SearchRunGuard.isCancellation(CancellationException()))
        assertFalse(SearchRunGuard.isCancellation(IOException("network down")))
        assertFalse(SearchRunGuard.isCancellation(null))
    }

    // --- SearchHistoryPanelPolicy (overlay visibility) ---

    @Test fun overlayVisibleOnlyForEmptyFocusedFieldWithHistory() {
        assertTrue(SearchHistoryPanelPolicy.isVisible(fieldText = "", fieldFocused = true, historyEmpty = false))
        assertTrue(SearchHistoryPanelPolicy.isVisible(fieldText = null, fieldFocused = true, historyEmpty = false))
        assertTrue(SearchHistoryPanelPolicy.isVisible(fieldText = "   ", fieldFocused = true, historyEmpty = false))
    }

    @Test fun overlayHiddenForTypedTextNoFocusOrEmptyHistory() {
        assertFalse(SearchHistoryPanelPolicy.isVisible(fieldText = "jazz", fieldFocused = true, historyEmpty = false))
        assertFalse(SearchHistoryPanelPolicy.isVisible(fieldText = "", fieldFocused = false, historyEmpty = false))
        assertFalse(SearchHistoryPanelPolicy.isVisible(fieldText = "", fieldFocused = true, historyEmpty = true))
    }
}
