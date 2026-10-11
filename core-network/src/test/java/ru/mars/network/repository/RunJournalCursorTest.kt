package ru.mars.network.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forward-progress comparison of server run-journal event ids (`<stream_id>:<seq>`,
 * routes.py `_chat_event_id`). This is the anti-loop primitive behind
 * [MidTurnRecoveryCoordinator.onStreamFrame]: replayed duplicates must never count
 * as progress, new ids must always count — without ever hard-failing on opaque shapes.
 */
class RunJournalCursorTest {

    @Test
    fun `any id is newer than a missing high-water mark`() {
        assertTrue(RunJournalCursor.isAfter("st-1:1", null))
        assertTrue(RunJournalCursor.isAfter("whatever", null))
        assertFalse(RunJournalCursor.isAfter(null, null))
        assertFalse(RunJournalCursor.isAfter(null, "st-1:1"))
        assertFalse(RunJournalCursor.isAfter("   ", "st-1:1"))
    }

    @Test
    fun `same run compares seq numerically, not lexicographically`() {
        assertTrue(RunJournalCursor.isAfter("st-1:10", "st-1:9"))
        assertFalse(RunJournalCursor.isAfter("st-1:9", "st-1:10"))
        assertFalse(RunJournalCursor.isAfter("st-1:9", "st-1:9")) // replayed duplicate
        assertFalse(RunJournalCursor.isAfter("st-1:3", "st-1:9")) // stale replay
    }

    @Test
    fun `stream ids containing colons split at the LAST colon`() {
        // attachToStream supports ids like `run:42/x?y` — the seq is what follows the last ':'.
        assertTrue(RunJournalCursor.isAfter("run:42/x?y:3", "run:42/x?y:2"))
        assertFalse(RunJournalCursor.isAfter("run:42/x?y:2", "run:42/x?y:3"))
    }

    @Test
    fun `a different run is always newer (new turn or re-bound stream)`() {
        assertTrue(RunJournalCursor.isAfter("st-2:1", "st-1:99"))
    }

    @Test
    fun `opaque ids fall back to inequality - recovery is never blocked`() {
        assertTrue(RunJournalCursor.isAfter("abc", "def"))
        assertFalse(RunJournalCursor.isAfter("abc", "abc"))
        assertTrue(RunJournalCursor.isAfter("abc", "st-1:not-a-number"))
    }

    @Test
    fun `huge seq values do not overflow`() {
        assertTrue(RunJournalCursor.isAfter("st-1:9223372036854775807", "st-1:9223372036854775806"))
        assertFalse(RunJournalCursor.isAfter("st-1:9223372036854775806", "st-1:9223372036854775807"))
        // unparseable magnitude → inequality fallback, still safe
        assertTrue(RunJournalCursor.isAfter("st-1:99999999999999999999999", "st-1:1"))
    }
}
