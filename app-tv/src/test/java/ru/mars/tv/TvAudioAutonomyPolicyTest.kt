package ru.mars.tv

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvAudioAutonomyPolicy] — pure JVM, no Android runtime. Pins the
 * audio-focus reaction matrix [TvMusicPlayer] applies on every AudioManager callback
 * (permanent loss stops, transient loss pauses and the returning gain resumes, duck
 * only lowers the volume, a USER pause never auto-resumes), the wake-lock permission
 * gate for network streams, and the safe-position sanitization.
 */
class TvAudioAutonomyPolicyTest {

    // --- constant contract -----------------------------------------------------------

    @Test
    fun `focus constants mirror the AudioManager contract`() {
        // Java static-final ints are inlined at compile time, so this assertion runs on
        // the JVM without loading the framework class — it pins the value identity the
        // player relies on when passing callback codes through verbatim.
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, TvAudioAutonomyPolicy.FOCUS_GAIN)
        assertEquals(AudioManager.AUDIOFOCUS_LOSS, TvAudioAutonomyPolicy.FOCUS_LOSS)
        assertEquals(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS)
        assertEquals(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS_CAN_DUCK,
        )
    }

    // --- focus change matrix ----------------------------------------------------------

    private fun action(
        change: Int,
        isPlaying: Boolean = true,
        pausedByFocusLoss: Boolean = false,
        ducked: Boolean = false,
    ) = TvAudioAutonomyPolicy.onFocusChange(change, isPlaying, pausedByFocusLoss, ducked)

    @Test
    fun `permanent loss stops regardless of the transport state`() {
        for (playing in listOf(true, false)) {
            for (paused in listOf(true, false)) {
                for (ducked in listOf(true, false)) {
                    assertEquals(
                        "playing=$playing paused=$paused ducked=$ducked",
                        TvAudioAutonomyPolicy.Action.STOP,
                        action(TvAudioAutonomyPolicy.FOCUS_LOSS, playing, paused, ducked),
                    )
                }
            }
        }
    }

    @Test
    fun `transient loss pauses a playing transport only`() {
        assertEquals(
            TvAudioAutonomyPolicy.Action.PAUSE,
            action(TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS, isPlaying = true),
        )
        assertEquals(
            "an already-paused transport must not claim a focus-induced pause",
            TvAudioAutonomyPolicy.Action.NONE,
            action(TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS, isPlaying = false),
        )
    }

    @Test
    fun `can-duck lowers the volume of a playing transport only`() {
        assertEquals(
            TvAudioAutonomyPolicy.Action.DUCK,
            action(TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS_CAN_DUCK, isPlaying = true),
        )
        assertEquals(
            TvAudioAutonomyPolicy.Action.NONE,
            action(TvAudioAutonomyPolicy.FOCUS_TRANSIENT_LOSS_CAN_DUCK, isPlaying = false),
        )
    }

    @Test
    fun `gain restores exactly what a focus loss took`() {
        assertEquals(
            TvAudioAutonomyPolicy.Action.RESTORE,
            action(TvAudioAutonomyPolicy.FOCUS_GAIN, isPlaying = false, pausedByFocusLoss = true),
        )
        assertEquals(
            TvAudioAutonomyPolicy.Action.RESTORE,
            action(TvAudioAutonomyPolicy.FOCUS_GAIN, isPlaying = true, ducked = true),
        )
        assertEquals(
            TvAudioAutonomyPolicy.Action.RESTORE,
            action(
                TvAudioAutonomyPolicy.FOCUS_GAIN,
                isPlaying = false,
                pausedByFocusLoss = true,
                ducked = true,
            ),
        )
    }

    @Test
    fun `gain never resumes a pause the user made`() {
        // pausedByFocusLoss=false is the user-pause case: a returning focus event
        // (another app's transient loss ending) must not restart the user's music.
        assertEquals(
            TvAudioAutonomyPolicy.Action.NONE,
            action(TvAudioAutonomyPolicy.FOCUS_GAIN, isPlaying = false),
        )
        assertEquals(
            TvAudioAutonomyPolicy.Action.NONE,
            action(TvAudioAutonomyPolicy.FOCUS_GAIN, isPlaying = true),
        )
    }

    @Test
    fun `unknown focus codes are ignored`() {
        for (code in listOf(0, 42, -99)) {
            assertEquals(
                "code=$code",
                TvAudioAutonomyPolicy.Action.NONE,
                action(code, isPlaying = true, pausedByFocusLoss = true, ducked = true),
            )
        }
    }

    // --- wake mode gate ------------------------------------------------------------------

    @Test
    fun `wake lock is held only while the permission is granted`() {
        assertTrue(TvAudioAutonomyPolicy.shouldHoldWakeLock(wakeLockPermissionGranted = true))
        assertFalse(TvAudioAutonomyPolicy.shouldHoldWakeLock(wakeLockPermissionGranted = false))
    }

    // --- safe position ---------------------------------------------------------------------

    @Test
    fun `negative raw positions clamp to zero and valid ones pass through`() {
        assertEquals(0, TvAudioAutonomyPolicy.sanitizePositionMs(-1))
        assertEquals(0, TvAudioAutonomyPolicy.sanitizePositionMs(Int.MIN_VALUE))
        assertEquals(0, TvAudioAutonomyPolicy.sanitizePositionMs(0))
        assertEquals(1234, TvAudioAutonomyPolicy.sanitizePositionMs(1234))
        assertEquals(Int.MAX_VALUE, TvAudioAutonomyPolicy.sanitizePositionMs(Int.MAX_VALUE))
    }

    // --- playback-start focus gate ---------------------------------------------------------

    @Test
    fun `focus request constants mirror the AudioManager contract`() {
        // Static-final ints inline at compile time (same pattern as the focus-change codes):
        // the player passes the framework verdict through verbatim.
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, TvAudioAutonomyPolicy.FOCUS_REQUEST_GRANTED)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, TvAudioAutonomyPolicy.FOCUS_REQUEST_FAILED)
    }

    @Test
    fun `an explicit focus denial refuses to start playback over another app`() {
        assertFalse(TvAudioAutonomyPolicy.shouldStartPlayback(TvAudioAutonomyPolicy.FOCUS_REQUEST_FAILED))
        assertFalse(TvAudioAutonomyPolicy.shouldStartPlayback(99)) // any other definitive verdict
    }

    @Test
    fun `granted or unverifiable focus starts playback`() {
        assertTrue(TvAudioAutonomyPolicy.shouldStartPlayback(TvAudioAutonomyPolicy.FOCUS_REQUEST_GRANTED))
        // null = focus integration unavailable (no attached context / odd TV stack):
        // the legacy best-effort playback must not go silent just because it cannot ASK.
        assertTrue(TvAudioAutonomyPolicy.shouldStartPlayback(null))
    }
}
