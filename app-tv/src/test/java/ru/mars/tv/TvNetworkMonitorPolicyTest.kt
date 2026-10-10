package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvNetworkMonitorPolicy] — pure JVM, no Android, no Connectivity.
 * Covers thresholds per stream kind, the full classify decision surface (idle, network
 * loss precedence, warning/stall boundaries), negative-silence clamping, timestamp-based
 * classification with fallback baseline and clock skew, and the requiresRecovery gate.
 */
class TvNetworkMonitorPolicyTest {

    // --- thresholds ---------------------------------------------------------------------------------

    @Test
    fun `chat and music have distinct stall thresholds`() {
        assertEquals(8_000L, TvNetworkMonitorPolicy.stallThresholdMs(TvStreamKind.CHAT))
        assertEquals(15_000L, TvNetworkMonitorPolicy.stallThresholdMs(TvStreamKind.MUSIC))
        assertTrue(
            "music tolerates longer silence than the chat SSE stream",
            TvNetworkMonitorPolicy.stallThresholdMs(TvStreamKind.MUSIC) >
                TvNetworkMonitorPolicy.stallThresholdMs(TvStreamKind.CHAT),
        )
    }

    @Test
    fun `warning threshold is 60 percent of the stall threshold`() {
        assertEquals(4_800L, TvNetworkMonitorPolicy.warningThresholdMs(TvStreamKind.CHAT))
        assertEquals(9_000L, TvNetworkMonitorPolicy.warningThresholdMs(TvStreamKind.MUSIC))
        for (kind in TvStreamKind.values()) {
            val warn = TvNetworkMonitorPolicy.warningThresholdMs(kind)
            val stall = TvNetworkMonitorPolicy.stallThresholdMs(kind)
            assertTrue("warning must precede stall for $kind", warn < stall)
        }
    }

    // --- classify: idle -------------------------------------------------------------------------------

    @Test
    fun `inactive stream is healthy no matter the network or silence`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = false, networkAvailable = false,
            msSinceLastEvent = Long.MAX_VALUE, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.HEALTHY, actual.health)
        assertEquals("idle", actual.reason)
        assertFalse(actual.requiresRecovery)
    }

    // --- classify: network loss -----------------------------------------------------------------------

    @Test
    fun `network loss beats the silence measurement for both kinds`() {
        for (kind in TvStreamKind.values()) {
            val actual = TvNetworkMonitorPolicy.classify(
                streamActive = true, networkAvailable = false,
                msSinceLastEvent = 10L, kind = kind,
            )
            assertEquals("kind=$kind", TvStreamHealth.NETWORK_LOST, actual.health)
            assertEquals("kind=$kind", "network_lost", actual.reason)
            assertTrue(actual.requiresRecovery)
        }
    }

    // --- classify: silence bands ----------------------------------------------------------------------

    @Test
    fun `silence below the warning threshold is healthy`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = true, networkAvailable = true,
            msSinceLastEvent = 4_799L, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.HEALTHY, actual.health)
        assertEquals("events_flowing", actual.reason)
    }

    @Test
    fun `silence exactly at the warning threshold escalates to warning`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = true, networkAvailable = true,
            msSinceLastEvent = 4_800L, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.STALL_WARNING, actual.health)
        assertEquals("stall_warning", actual.reason)
        assertFalse("a warning alone must not trigger recovery", actual.requiresRecovery)
    }

    @Test
    fun `silence between warning and stall stays a warning`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = true, networkAvailable = true,
            msSinceLastEvent = 7_999L, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.STALL_WARNING, actual.health)
    }

    @Test
    fun `silence exactly at the stall threshold is stalled`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = true, networkAvailable = true,
            msSinceLastEvent = 8_000L, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.STALLED, actual.health)
        assertEquals("stall_threshold_reached", actual.reason)
        assertTrue(actual.requiresRecovery)
    }

    @Test
    fun `music kind applies its own longer bands`() {
        // 8s of silence: stalled for chat, still a warning for music (warn=9s, stall=15s).
        val chat = TvNetworkMonitorPolicy.classify(true, true, 8_000L, TvStreamKind.CHAT)
        val music = TvNetworkMonitorPolicy.classify(true, true, 8_000L, TvStreamKind.MUSIC)
        assertEquals(TvStreamHealth.STALLED, chat.health)
        assertEquals(TvStreamHealth.HEALTHY, music.health)

        val musicWarn = TvNetworkMonitorPolicy.classify(true, true, 9_000L, TvStreamKind.MUSIC)
        assertEquals(TvStreamHealth.STALL_WARNING, musicWarn.health)

        val musicStall = TvNetworkMonitorPolicy.classify(true, true, 15_000L, TvStreamKind.MUSIC)
        assertEquals(TvStreamHealth.STALLED, musicStall.health)
    }

    @Test
    fun `negative silence is clamped to zero instead of guessing`() {
        val actual = TvNetworkMonitorPolicy.classify(
            streamActive = true, networkAvailable = true,
            msSinceLastEvent = -5_000L, kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.HEALTHY, actual.health)
        assertEquals("events_flowing", actual.reason)
    }

    // --- classifyByTimestamps -------------------------------------------------------------------------

    @Test
    fun `timestamps fall back to stream start when no event arrived yet`() {
        val actual = TvNetworkMonitorPolicy.classifyByTimestamps(
            lastEventAtMs = null, streamStartedAtMs = 1_000L, nowMs = 1_000L + 8_000L,
            kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.STALLED, actual.health)
    }

    @Test
    fun `timestamps prefer the last event over the stream start`() {
        val actual = TvNetworkMonitorPolicy.classifyByTimestamps(
            lastEventAtMs = 50_000L, streamStartedAtMs = 0L, nowMs = 51_000L,
            kind = TvStreamKind.CHAT,
        )
        assertEquals("only 1s since the last event", TvStreamHealth.HEALTHY, actual.health)
    }

    @Test
    fun `backwards clock skew clamps to healthy instead of negative silence`() {
        val actual = TvNetworkMonitorPolicy.classifyByTimestamps(
            lastEventAtMs = 10_000L, streamStartedAtMs = 0L, nowMs = 9_000L,
            kind = TvStreamKind.CHAT,
        )
        assertEquals(TvStreamHealth.HEALTHY, actual.health)
    }

    // --- requiresRecovery -------------------------------------------------------------------------------

    @Test
    fun `only stalled and network lost require recovery`() {
        assertFalse(TvNetworkMonitorPolicy.requiresRecovery(TvStreamHealth.HEALTHY))
        assertFalse(TvNetworkMonitorPolicy.requiresRecovery(TvStreamHealth.STALL_WARNING))
        assertTrue(TvNetworkMonitorPolicy.requiresRecovery(TvStreamHealth.STALLED))
        assertTrue(TvNetworkMonitorPolicy.requiresRecovery(TvStreamHealth.NETWORK_LOST))
    }

    @Test
    fun `result property mirrors the standalone gate for every health`() {
        for (health in TvStreamHealth.values()) {
            val result = TvStreamHealthResult(health, "x")
            assertEquals(
                "health=$health",
                TvNetworkMonitorPolicy.requiresRecovery(health),
                result.requiresRecovery,
            )
        }
    }

    // --- data class -----------------------------------------------------------------------------------

    @Test
    fun `health result exposes its fields and equality`() {
        val r = TvStreamHealthResult(TvStreamHealth.STALLED, "stall_threshold_reached")
        assertEquals(TvStreamHealth.STALLED, r.health)
        assertEquals("stall_threshold_reached", r.reason)
        assertEquals(r, TvStreamHealthResult(TvStreamHealth.STALLED, "stall_threshold_reached"))
        assertEquals(r, r.copy())
    }
}
