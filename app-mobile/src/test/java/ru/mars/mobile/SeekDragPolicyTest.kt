package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SeekDragPolicy]: the seek thumb belongs to the finger while dragging —
 * the progress ticker must pause — and the player is seeked exactly once, on release,
 * with the target clamped into the track.
 */
class SeekDragPolicyTest {

    @Test fun tickerUpdatesThumbOnlyOutsideDrag() {
        assertTrue(SeekDragPolicy.tickerMayUpdate(isDragging = false))
        assertFalse(SeekDragPolicy.tickerMayUpdate(isDragging = true))
    }

    @Test fun noPendingSeekNeverTouchesPlayer() {
        assertNull(SeekDragPolicy.pendingSeekTarget(SeekDragPolicy.NO_PENDING_SEEK, durationMs = 180_000))
        assertNull(SeekDragPolicy.pendingSeekTarget(pendingMs = -42, durationMs = 180_000))
    }

    @Test fun draggedPositionPassesThroughInsideTrack() {
        assertEquals(75_000, SeekDragPolicy.pendingSeekTarget(pendingMs = 75_000, durationMs = 180_000))
        assertEquals(0, SeekDragPolicy.pendingSeekTarget(pendingMs = 0, durationMs = 180_000))
        assertEquals(180_000, SeekDragPolicy.pendingSeekTarget(pendingMs = 180_000, durationMs = 180_000))
    }

    @Test fun dragPastTheEndClampsToDuration() {
        assertEquals(180_000, SeekDragPolicy.pendingSeekTarget(pendingMs = 250_000, durationMs = 180_000))
    }

    @Test fun unknownOrZeroDurationCollapsesToZeroNotNegative() {
        assertEquals(0, SeekDragPolicy.pendingSeekTarget(pendingMs = 90_000, durationMs = 0))
        assertEquals(0, SeekDragPolicy.pendingSeekTarget(pendingMs = 90_000, durationMs = -1))
    }

    @Test fun sentinelIsNegativeSoItCanNeverBeMistakenForAPosition() {
        assertTrue(SeekDragPolicy.NO_PENDING_SEEK < 0)
    }
}
