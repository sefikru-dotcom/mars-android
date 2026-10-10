package ru.mars.tv

/**
 * Audio EQ presets of the TV music/chat transport, cycled by the D-pad "EQ" action
 * (see [TvAudioEqualizerPolicy.nextPreset]/[TvAudioEqualizerPolicy.previousPreset]).
 * [labelRu] is the on-screen name shown in the preset row.
 */
enum class TvEqPreset(val labelRu: String) {
    FLAT("Ровный звук"),
    BASS_BOOST("Усиленные басы"),
    VOCAL("Вокал"),
    ELECTRONIC("Электроника"),
}

/** D-pad direction of a volume press (right/up = louder, left/down = quieter). */
enum class TvVolumeDirection { UP, DOWN }

/**
 * Outcome of a volume step: the resulting [volume], whether the request hit a bound
 * ([clamped]), and a stable machine-readable [reason] (`stepped`, `clamped_max`,
 * `clamped_min`, `unchanged`, `range_corrected`) for tests and logs.
 */
data class TvVolumeStepResult(val volume: Int, val clamped: Boolean, val reason: String)

/** Outcome of [TvAudioEqualizerPolicy.toggleMute] with the level to restore on unmute. */
data class TvMuteToggleResult(
    val muted: Boolean,
    val volume: Int,
    val volumeBeforeMute: Int?,
    val reason: String,
)

/**
 * Pure audio policy of the TV transport (no Android, no AudioManager, no Equalizer —
 * the host applies the returned gains/levels; JVM-testable, [TvPlaybackModePolicy] parity).
 *
 * - EQ presets: five fixed bands ([BAND_CENTERS_HZ]) per [TvEqPreset], gains in whole dB;
 *   the preset ring cycles both ways for D-pad left/right on the preset row.
 * - Volume: integer stepping with hard bounds, separate short-press and long-press
 *   increments, and mute/unmute that always remembers the pre-mute level so unmuting
 *   restores what the user was listening at instead of blasting a default.
 */
object TvAudioEqualizerPolicy {

    /** Center frequencies of the 5-band EQ, low to high (Android Equalizer default-ish grid). */
    val BAND_CENTERS_HZ: List<Int> = listOf(60, 230, 910, 3_600, 14_000)

    const val MIN_VOLUME = 0

    /** Typical TV STREAM_MUSIC ceiling; hosts with another max pass it explicitly. */
    const val MAX_VOLUME = 15

    /** Volume delta of one D-pad short press. */
    const val SHORT_PRESS_STEP = 1

    /** Volume delta per tick of a D-pad long press (3x the short step). */
    const val LONG_PRESS_STEP = 3

    /** Level restored on unmute when no pre-mute level was remembered; mid-scale, never max. */
    val DEFAULT_UNMUTE_VOLUME: Int = MAX_VOLUME / 2

    private val PRESET_GAINS_DB: Map<TvEqPreset, List<Int>> = mapOf(
        TvEqPreset.FLAT to listOf(0, 0, 0, 0, 0),
        TvEqPreset.BASS_BOOST to listOf(6, 4, 0, -1, -1),
        TvEqPreset.VOCAL to listOf(-2, 0, 4, 3, 0),
        TvEqPreset.ELECTRONIC to listOf(5, 2, -2, 2, 4),
    )

    /** Per-band gains in dB for [preset], aligned with [BAND_CENTERS_HZ] (same size, low to high). */
    fun gainsFor(preset: TvEqPreset): List<Int> = PRESET_GAINS_DB.getValue(preset)

    /** Successor of [current] in the preset ring: FLAT -> BASS_BOOST -> VOCAL -> ELECTRONIC -> FLAT. */
    fun nextPreset(current: TvEqPreset): TvEqPreset {
        val values = TvEqPreset.values()
        return values[(current.ordinal + 1) % values.size]
    }

    /** Predecessor of [current] in the preset ring (D-pad "left" on the preset row); wraps at FLAT. */
    fun previousPreset(current: TvEqPreset): TvEqPreset {
        val values = TvEqPreset.values()
        return values[(current.ordinal - 1 + values.size) % values.size]
    }

    /** Parses a persisted preset name case-insensitively; null for unknown/blank/null input. */
    fun presetByName(name: String?): TvEqPreset? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return TvEqPreset.values().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Applies [delta] to [currentVolume] within [minVolume]..[maxVolume] (swapped bounds
     * are normalized, never thrown). An out-of-range starting level is corrected into the
     * range first (`range_corrected`); hitting a bound reports `clamped_max`/`clamped_min`;
     * a zero delta on a valid level is `unchanged`.
     */
    fun applyVolumeDelta(
        currentVolume: Int,
        delta: Int,
        minVolume: Int = MIN_VOLUME,
        maxVolume: Int = MAX_VOLUME,
    ): TvVolumeStepResult {
        val lo = minOf(minVolume, maxVolume)
        val hi = maxOf(minVolume, maxVolume)
        val start = currentVolume.coerceIn(lo, hi)
        if (start != currentVolume) {
            return TvVolumeStepResult(start, true, "range_corrected")
        }
        if (delta == 0) return TvVolumeStepResult(start, false, "unchanged")
        val target = start + delta
        val final = target.coerceIn(lo, hi)
        val reason = when {
            final == target -> "stepped"
            final > target -> "clamped_min"
            else -> "clamped_max"
        }
        return TvVolumeStepResult(final, final != target, reason)
    }

    /**
     * Volume after one D-pad press in [direction]: [SHORT_PRESS_STEP] per click,
     * [LONG_PRESS_STEP] per repeat tick while held ([longPress]). Clamps at the bounds.
     */
    fun volumeForDpad(
        currentVolume: Int,
        direction: TvVolumeDirection,
        longPress: Boolean = false,
        minVolume: Int = MIN_VOLUME,
        maxVolume: Int = MAX_VOLUME,
    ): TvVolumeStepResult {
        val step = if (longPress) LONG_PRESS_STEP else SHORT_PRESS_STEP
        val signed = if (direction == TvVolumeDirection.UP) step else -step
        return applyVolumeDelta(currentVolume, signed, minVolume, maxVolume)
    }

    /**
     * Mute toggle that never loses the user's level: muting drops to [minVolume] and
     * remembers the (range-corrected) current level in [TvMuteToggleResult.volumeBeforeMute];
     * unmuting restores that memory (`unmuted_restored`) or, when there is none,
     * [DEFAULT_UNMUTE_VOLUME] (`unmuted_default`) — a safe mid-scale level, never max.
     */
    fun toggleMute(
        currentVolume: Int,
        muted: Boolean,
        volumeBeforeMute: Int? = null,
        minVolume: Int = MIN_VOLUME,
        maxVolume: Int = MAX_VOLUME,
    ): TvMuteToggleResult {
        val lo = minOf(minVolume, maxVolume)
        val hi = maxOf(minVolume, maxVolume)
        return if (!muted) {
            TvMuteToggleResult(true, lo, currentVolume.coerceIn(lo, hi), "muted")
        } else {
            val remembered = volumeBeforeMute?.coerceIn(lo, hi)
            if (remembered != null) {
                TvMuteToggleResult(false, remembered, remembered, "unmuted_restored")
            } else {
                val fallback = DEFAULT_UNMUTE_VOLUME.coerceIn(lo, hi)
                TvMuteToggleResult(false, fallback, fallback, "unmuted_default")
            }
        }
    }
}
