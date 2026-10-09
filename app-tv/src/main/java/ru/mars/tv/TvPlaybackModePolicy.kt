package ru.mars.tv

/**
 * Repeat mode of the TV music transport, cycled by the D-pad "mode" action:
 * OFF -> ALL -> ONE -> OFF (see [TvPlaybackModePolicy.nextRepeatMode]).
 */
enum class TvRepeatMode { OFF, ALL, ONE }

/**
 * Decision of [TvPlaybackModePolicy.advanceOnCompletion]:
 * - [targetIndex]: playlist index playback should continue on; `null` when there is none.
 * - [shouldStop]: `true` when the transport must go idle after this decision.
 * - [reason]: stable machine-readable tag of the branch that produced the decision
 *   (`empty_playlist`, `invalid_index`, `repeat_one`, `repeat_all_cycle`, `next_track`,
 *   `playlist_exhausted`) — surfaced in tests and logs instead of magic booleans.
 */
data class TvAdvanceResult(
    val targetIndex: Int?,
    val shouldStop: Boolean,
    val reason: String,
)

/**
 * Pure playback-mode and navigation policy of the TV music transport (no Android, no I/O —
 * JVM-testable, [TvPlaylistPolicy]/[TvMusicNowPlayingSpec] parity). The activities own the
 * state (playlist, current index, repeat mode) and ask this object for every decision, so
 * the D-pad semantics are covered by unit tests without touching MediaPlayer:
 *
 * - [nextRepeatMode]: cycles the repeat mode ring OFF -> ALL -> ONE -> OFF.
 * - [advanceOnCompletion]: what happens when a track finishes — repeat-one keeps the
 *   index, repeat-all wraps around, and OFF walks the list once and then stops.
 * - [advanceOnUserNext]/[advanceOnUserPrevious]: explicit D-pad skips; "next" wraps in
 *   the repeating modes and dies at the end in OFF, "previous" steps back and, at index
 *   zero, wraps to the tail only in repeat-all.
 */
object TvPlaybackModePolicy {

    /**
     * Successor of [current] in the repeat-mode ring: OFF -> ALL -> ONE -> OFF.
     * Pure cycling — the playlist is irrelevant here.
     */
    fun nextRepeatMode(current: TvRepeatMode): TvRepeatMode = when (current) {
        TvRepeatMode.OFF -> TvRepeatMode.ALL
        TvRepeatMode.ALL -> TvRepeatMode.ONE
        TvRepeatMode.ONE -> TvRepeatMode.OFF
    }

    /**
     * Decision when the track at [currentIndex] of a [playlistSize]-track playlist just
     * completed under [repeatMode]:
     * - empty playlist -> stop (`empty_playlist`);
     * - index outside the playlist -> stop (`invalid_index`) instead of guessing;
     * - ONE -> replay the same index (`repeat_one`);
     * - ALL -> successor with wrap-around (`repeat_all_cycle`);
     * - OFF -> successor while one exists (`next_track`), otherwise the playlist is
     *   exhausted and playback stops (`playlist_exhausted`) — it never wraps in OFF.
     */
    fun advanceOnCompletion(playlistSize: Int, currentIndex: Int, repeatMode: TvRepeatMode): TvAdvanceResult {
        if (playlistSize <= 0) return TvAdvanceResult(null, true, "empty_playlist")
        if (currentIndex !in 0 until playlistSize) return TvAdvanceResult(null, true, "invalid_index")
        return when (repeatMode) {
            TvRepeatMode.ONE -> TvAdvanceResult(currentIndex, false, "repeat_one")
            TvRepeatMode.ALL -> TvAdvanceResult((currentIndex + 1) % playlistSize, false, "repeat_all_cycle")
            TvRepeatMode.OFF -> {
                val next = currentIndex + 1
                if (next < playlistSize) TvAdvanceResult(next, false, "next_track")
                else TvAdvanceResult(null, true, "playlist_exhausted")
            }
        }
    }

    /**
     * Index playback should jump to when the user pressed D-pad "next" on [currentIndex]
     * of a [playlistSize]-track playlist under [repeatMode]; `null` means "nothing to
     * skip to" (empty playlist, invalid index, or the end of the list in OFF — an
     * explicit skip in OFF never wraps). ALL and ONE wrap around the list.
     */
    fun advanceOnUserNext(playlistSize: Int, currentIndex: Int, repeatMode: TvRepeatMode): Int? {
        if (playlistSize <= 0) return null
        if (currentIndex !in 0 until playlistSize) return null
        return when (repeatMode) {
            TvRepeatMode.ALL, TvRepeatMode.ONE -> (currentIndex + 1) % playlistSize
            TvRepeatMode.OFF -> {
                val next = currentIndex + 1
                if (next < playlistSize) next else null
            }
        }
    }

    /**
     * Index playback should jump to when the user pressed D-pad "previous" on
     * [currentIndex] of a [playlistSize]-track playlist under [repeatMode]; `null` means
     * "nothing to skip to" (empty playlist or invalid index). Inside the list the step
     * is `currentIndex - 1`; at index zero only ALL wraps to the tail (`playlistSize - 1`),
     * every other mode holds at zero — no endless backwards loop for the user.
     */
    fun advanceOnUserPrevious(playlistSize: Int, currentIndex: Int, repeatMode: TvRepeatMode): Int? {
        if (playlistSize <= 0) return null
        if (currentIndex !in 0 until playlistSize) return null
        if (currentIndex > 0) return currentIndex - 1
        return if (repeatMode == TvRepeatMode.ALL) playlistSize - 1 else 0
    }
}
