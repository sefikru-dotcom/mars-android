package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvAudioEqualizerPolicy] — pure JVM, no AudioManager, no Equalizer.
 * Covers the preset ring in both directions, per-preset band gains and their alignment
 * with the band grid, preset name parsing, D-pad volume stepping (short/long press,
 * bounds, out-of-range correction, swapped bounds), and mute/unmute level preservation.
 */
class TvAudioEqualizerPolicyTest {

    // --- preset ring ------------------------------------------------------------------------------------

    @Test
    fun `preset ring cycles forward FLAT to BASS_BOOST to VOCAL to ELECTRONIC`() {
        assertEquals(TvEqPreset.BASS_BOOST, TvAudioEqualizerPolicy.nextPreset(TvEqPreset.FLAT))
        assertEquals(TvEqPreset.VOCAL, TvAudioEqualizerPolicy.nextPreset(TvEqPreset.BASS_BOOST))
        assertEquals(TvEqPreset.ELECTRONIC, TvAudioEqualizerPolicy.nextPreset(TvEqPreset.VOCAL))
        assertEquals(TvEqPreset.FLAT, TvAudioEqualizerPolicy.nextPreset(TvEqPreset.ELECTRONIC))
    }

    @Test
    fun `preset ring cycles backward and wraps at FLAT`() {
        assertEquals(TvEqPreset.FLAT, TvAudioEqualizerPolicy.previousPreset(TvEqPreset.BASS_BOOST))
        assertEquals(TvEqPreset.BASS_BOOST, TvAudioEqualizerPolicy.previousPreset(TvEqPreset.VOCAL))
        assertEquals(TvEqPreset.VOCAL, TvAudioEqualizerPolicy.previousPreset(TvEqPreset.ELECTRONIC))
        assertEquals(TvEqPreset.ELECTRONIC, TvAudioEqualizerPolicy.previousPreset(TvEqPreset.FLAT))
    }

    @Test
    fun `full ring in either direction returns to the start`() {
        val count = TvEqPreset.values().size
        var forward = TvEqPreset.VOCAL
        var backward = TvEqPreset.VOCAL
        repeat(count) {
            forward = TvAudioEqualizerPolicy.nextPreset(forward)
            backward = TvAudioEqualizerPolicy.previousPreset(backward)
        }
        assertEquals(TvEqPreset.VOCAL, forward)
        assertEquals(TvEqPreset.VOCAL, backward)
    }

    @Test
    fun `next and previous are inverse operations for every preset`() {
        for (preset in TvEqPreset.values()) {
            assertEquals(preset, TvAudioEqualizerPolicy.previousPreset(TvAudioEqualizerPolicy.nextPreset(preset)))
            assertEquals(preset, TvAudioEqualizerPolicy.nextPreset(TvAudioEqualizerPolicy.previousPreset(preset)))
        }
    }

    // --- band gains ----------------------------------------------------------------------------------------

    @Test
    fun `band grid has five ascending center frequencies`() {
        val bands = TvAudioEqualizerPolicy.BAND_CENTERS_HZ
        assertEquals(5, bands.size)
        assertEquals(listOf(60, 230, 910, 3_600, 14_000), bands)
        assertTrue(bands.zipWithNext().all { (lo, hi) -> lo < hi })
    }

    @Test
    fun `every preset has one gain per band`() {
        for (preset in TvEqPreset.values()) {
            assertEquals(
                "preset=$preset",
                TvAudioEqualizerPolicy.BAND_CENTERS_HZ.size,
                TvAudioEqualizerPolicy.gainsFor(preset).size,
            )
        }
    }

    @Test
    fun `flat preset is all zeros`() {
        assertEquals(listOf(0, 0, 0, 0, 0), TvAudioEqualizerPolicy.gainsFor(TvEqPreset.FLAT))
    }

    @Test
    fun `bass boost lifts the low bands and never lifts the highs`() {
        val gains = TvAudioEqualizerPolicy.gainsFor(TvEqPreset.BASS_BOOST)
        assertEquals(listOf(6, 4, 0, -1, -1), gains)
        assertTrue(gains[0] > gains[1])
        assertTrue("60Hz band is the loudest", gains[0] == gains.maxOrNull()!!)
        assertTrue(gains[3] <= 0 && gains[4] <= 0)
    }

    @Test
    fun `vocal preset peaks in the mid bands`() {
        val gains = TvAudioEqualizerPolicy.gainsFor(TvEqPreset.VOCAL)
        assertEquals(listOf(-2, 0, 4, 3, 0), gains)
        val peakIndex = gains.indexOf(gains.maxOrNull()!!)
        assertTrue("peak sits on the 910Hz or 3.6kHz band", peakIndex in 2..3)
        assertTrue("sub bass is pulled back", gains[0] < 0)
    }

    @Test
    fun `electronic preset is a V shape with lifted extremes`() {
        val gains = TvAudioEqualizerPolicy.gainsFor(TvEqPreset.ELECTRONIC)
        assertEquals(listOf(5, 2, -2, 2, 4), gains)
        assertTrue("low end lifted", gains[0] > 0)
        assertTrue("high end lifted", gains[4] > 0)
        assertTrue("mid is scooped", gains[2] < 0)
    }

    @Test
    fun `all gains stay within a sane plus-minus 12 dB range`() {
        for (preset in TvEqPreset.values()) {
            for (gain in TvAudioEqualizerPolicy.gainsFor(preset)) {
                assertTrue("preset=$preset gain=$gain", gain in -12..12)
            }
        }
    }

    // --- preset name parsing ------------------------------------------------------------------------------

    @Test
    fun `preset name parsing is case-insensitive and trims whitespace`() {
        assertEquals(TvEqPreset.BASS_BOOST, TvAudioEqualizerPolicy.presetByName("bass_boost"))
        assertEquals(TvEqPreset.BASS_BOOST, TvAudioEqualizerPolicy.presetByName("  Bass_Boost "))
        assertEquals(TvEqPreset.FLAT, TvAudioEqualizerPolicy.presetByName("FLAT"))
        assertEquals(TvEqPreset.VOCAL, TvAudioEqualizerPolicy.presetByName("vocal"))
        assertEquals(TvEqPreset.ELECTRONIC, TvAudioEqualizerPolicy.presetByName("Electronic"))
    }

    @Test
    fun `unknown blank or null names parse to null instead of guessing`() {
        assertNull(TvAudioEqualizerPolicy.presetByName(null))
        assertNull(TvAudioEqualizerPolicy.presetByName(""))
        assertNull(TvAudioEqualizerPolicy.presetByName("   "))
        assertNull(TvAudioEqualizerPolicy.presetByName("rock"))
        assertNull(TvAudioEqualizerPolicy.presetByName("FLATT"))
    }

    @Test
    fun `every preset round-trips through its name`() {
        for (preset in TvEqPreset.values()) {
            assertEquals(preset, TvAudioEqualizerPolicy.presetByName(preset.name))
            assertTrue("preset=$preset has a non-blank UI label", preset.labelRu.isNotBlank())
        }
    }

    // --- volume stepping -----------------------------------------------------------------------------------

    @Test
    fun `volume steps up and down inside the range`() {
        val up = TvAudioEqualizerPolicy.applyVolumeDelta(7, 2)
        assertEquals(TvVolumeStepResult(9, false, "stepped"), up)
        val down = TvAudioEqualizerPolicy.applyVolumeDelta(7, -3)
        assertEquals(TvVolumeStepResult(4, false, "stepped"), down)
    }

    @Test
    fun `volume clamps at the max bound`() {
        val at = TvAudioEqualizerPolicy.applyVolumeDelta(15, 1)
        assertEquals(TvVolumeStepResult(15, true, "clamped_max"), at)
        val beyond = TvAudioEqualizerPolicy.applyVolumeDelta(14, 5)
        assertEquals(TvVolumeStepResult(15, true, "clamped_max"), beyond)
    }

    @Test
    fun `volume clamps at the min bound`() {
        val at = TvAudioEqualizerPolicy.applyVolumeDelta(0, -1)
        assertEquals(TvVolumeStepResult(0, true, "clamped_min"), at)
        val beyond = TvAudioEqualizerPolicy.applyVolumeDelta(2, -9)
        assertEquals(TvVolumeStepResult(0, true, "clamped_min"), beyond)
    }

    @Test
    fun `zero delta on a valid level is unchanged`() {
        assertEquals(TvVolumeStepResult(7, false, "unchanged"), TvAudioEqualizerPolicy.applyVolumeDelta(7, 0))
    }

    @Test
    fun `out-of-range starting level is corrected into the range`() {
        val high = TvAudioEqualizerPolicy.applyVolumeDelta(99, 0)
        assertEquals(TvVolumeStepResult(15, true, "range_corrected"), high)
        val low = TvAudioEqualizerPolicy.applyVolumeDelta(-4, 2)
        assertEquals(TvVolumeStepResult(0, true, "range_corrected"), low)
    }

    @Test
    fun `custom bounds are honored and swapped bounds are normalized`() {
        val custom = TvAudioEqualizerPolicy.applyVolumeDelta(28, 4, minVolume = 20, maxVolume = 30)
        assertEquals(TvVolumeStepResult(30, true, "clamped_max"), custom)
        val swapped = TvAudioEqualizerPolicy.applyVolumeDelta(25, 100, minVolume = 30, maxVolume = 20)
        assertEquals("swapped bounds behave like 20..30", 30, swapped.volume)
        assertTrue(swapped.clamped)
    }

    // --- D-pad volume --------------------------------------------------------------------------------------

    @Test
    fun `short press steps by one in each direction`() {
        assertEquals(1, TvAudioEqualizerPolicy.SHORT_PRESS_STEP)
        val up = TvAudioEqualizerPolicy.volumeForDpad(7, TvVolumeDirection.UP)
        assertEquals(TvVolumeStepResult(8, false, "stepped"), up)
        val down = TvAudioEqualizerPolicy.volumeForDpad(7, TvVolumeDirection.DOWN)
        assertEquals(TvVolumeStepResult(6, false, "stepped"), down)
    }

    @Test
    fun `long press steps by three in each direction`() {
        assertEquals(3, TvAudioEqualizerPolicy.LONG_PRESS_STEP)
        val up = TvAudioEqualizerPolicy.volumeForDpad(7, TvVolumeDirection.UP, longPress = true)
        assertEquals(10, up.volume)
        val down = TvAudioEqualizerPolicy.volumeForDpad(7, TvVolumeDirection.DOWN, longPress = true)
        assertEquals(4, down.volume)
    }

    @Test
    fun `long press up against the ceiling clamps instead of overshooting`() {
        val result = TvAudioEqualizerPolicy.volumeForDpad(14, TvVolumeDirection.UP, longPress = true)
        assertEquals(TvVolumeStepResult(15, true, "clamped_max"), result)
    }

    @Test
    fun `long press down against the floor clamps at zero`() {
        val result = TvAudioEqualizerPolicy.volumeForDpad(1, TvVolumeDirection.DOWN, longPress = true)
        assertEquals(TvVolumeStepResult(0, true, "clamped_min"), result)
    }

    @Test
    fun `repeated long-press ticks walk the full range without escaping bounds`() {
        var volume = TvAudioEqualizerPolicy.MIN_VOLUME
        repeat(20) {
            volume = TvAudioEqualizerPolicy.volumeForDpad(volume, TvVolumeDirection.UP, longPress = true).volume
            assertTrue(volume in TvAudioEqualizerPolicy.MIN_VOLUME..TvAudioEqualizerPolicy.MAX_VOLUME)
        }
        assertEquals(TvAudioEqualizerPolicy.MAX_VOLUME, volume)
        repeat(20) {
            volume = TvAudioEqualizerPolicy.volumeForDpad(volume, TvVolumeDirection.DOWN, longPress = true).volume
            assertTrue(volume in TvAudioEqualizerPolicy.MIN_VOLUME..TvAudioEqualizerPolicy.MAX_VOLUME)
        }
        assertEquals(TvAudioEqualizerPolicy.MIN_VOLUME, volume)
    }

    // --- mute toggle -----------------------------------------------------------------------------------------

    @Test
    fun `muting drops to the floor and remembers the level`() {
        val result = TvAudioEqualizerPolicy.toggleMute(currentVolume = 11, muted = false)
        assertEquals(TvMuteToggleResult(true, 0, 11, "muted"), result)
    }

    @Test
    fun `muting stores a range-corrected memory`() {
        val result = TvAudioEqualizerPolicy.toggleMute(currentVolume = 99, muted = false)
        assertEquals(15, result.volumeBeforeMute)
        assertTrue(result.muted)
    }

    @Test
    fun `unmuting restores the remembered level`() {
        val result = TvAudioEqualizerPolicy.toggleMute(currentVolume = 0, muted = true, volumeBeforeMute = 11)
        assertEquals(TvMuteToggleResult(false, 11, 11, "unmuted_restored"), result)
    }

    @Test
    fun `unmuting coerces an out-of-range memory into bounds`() {
        val result = TvAudioEqualizerPolicy.toggleMute(currentVolume = 0, muted = true, volumeBeforeMute = 42)
        assertEquals(15, result.volume)
        assertEquals("unmuted_restored", result.reason)
    }

    @Test
    fun `unmuting without a memory falls back to mid-scale never max`() {
        val result = TvAudioEqualizerPolicy.toggleMute(currentVolume = 0, muted = true, volumeBeforeMute = null)
        assertEquals(TvAudioEqualizerPolicy.DEFAULT_UNMUTE_VOLUME, result.volume)
        assertEquals("unmuted_default", result.reason)
        assertFalse(result.muted)
        assertTrue(
            "fallback must stay below the ceiling",
            result.volume < TvAudioEqualizerPolicy.MAX_VOLUME,
        )
    }

    @Test
    fun `mute and unmute round-trip preserves the listening level`() {
        val level = 9
        val muted = TvAudioEqualizerPolicy.toggleMute(currentVolume = level, muted = false)
        val restored = TvAudioEqualizerPolicy.toggleMute(
            currentVolume = muted.volume, muted = muted.muted, volumeBeforeMute = muted.volumeBeforeMute,
        )
        assertEquals(level, restored.volume)
        assertFalse(restored.muted)
    }

    @Test
    fun `mute toggle honors custom bounds`() {
        val result = TvAudioEqualizerPolicy.toggleMute(
            currentVolume = 25, muted = false, minVolume = 20, maxVolume = 30,
        )
        assertEquals(20, result.volume)
        assertEquals(25, result.volumeBeforeMute)
    }

    // --- constants and data classes --------------------------------------------------------------------------

    @Test
    fun `policy constants are coherent for a D-pad UI`() {
        assertTrue(TvAudioEqualizerPolicy.MIN_VOLUME < TvAudioEqualizerPolicy.MAX_VOLUME)
        assertTrue(
            "long press must be coarser than short press",
            TvAudioEqualizerPolicy.LONG_PRESS_STEP > TvAudioEqualizerPolicy.SHORT_PRESS_STEP,
        )
        assertTrue(TvAudioEqualizerPolicy.SHORT_PRESS_STEP > 0)
        assertEquals(7, TvAudioEqualizerPolicy.DEFAULT_UNMUTE_VOLUME)
    }

    @Test
    fun `step and mute results expose equality`() {
        val step = TvVolumeStepResult(9, false, "stepped")
        assertEquals(step, TvVolumeStepResult(9, false, "stepped"))
        assertEquals(step, step.copy())
        val mute = TvMuteToggleResult(true, 0, 11, "muted")
        assertEquals(mute, TvMuteToggleResult(true, 0, 11, "muted"))
        assertEquals(mute, mute.copy())
    }
}
