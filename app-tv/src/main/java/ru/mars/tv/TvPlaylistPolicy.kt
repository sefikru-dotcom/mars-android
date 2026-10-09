package ru.mars.tv

import ru.mars.network.model.MusicTrack

/**
 * Pure continuous-playback policy of the TV music transport (no Android, no I/O —
 * JVM-testable, [TvMusicNowPlayingSpec] parity; decision semantics follow the mobile
 * [ru.mars.network.model.MusicTrack] player: advance on completion, stop when the
 * playlist is exhausted, skip a dead stream but never retry a broken playlist forever).
 *
 * [TvMusicPlayer] owns the state (playlist, current index, consecutive failures) and
 * asks this object for every navigation decision, so the auto-advance rules are covered
 * by unit tests without touching MediaPlayer:
 *
 * - [startIndex]: sanitizes the index a user activation asked for.
 * - [nextIndex]/[nextTrack]: sequential successor; `null` at the end — continuous
 *   playback walks the list once and then goes idle, it does not loop.
 * - [skipAfterFailure]: circuit breaker for dead streams — a failed track is skipped to
 *   its successor until one full pass over the playlist has failed, then playback stops
 *   instead of spinning forever.
 */
object TvPlaylistPolicy {

    /**
     * Playlist index playback should start on: [requestedIndex] when it is valid for a
     * playlist of [playlistSize], `null` otherwise (empty playlist or out-of-range
     * activation — there is simply nothing to play).
     */
    fun startIndex(playlistSize: Int, requestedIndex: Int): Int? =
        if (requestedIndex in 0 until playlistSize) requestedIndex else null

    /**
     * Successor of [currentIndex] in a playlist of [playlistSize]: `currentIndex + 1`
     * while inside the list, `null` when the playlist is exhausted or the current index
     * is invalid (nothing bound / already idle). `null` means "go idle", never "wrap".
     */
    fun nextIndex(playlistSize: Int, currentIndex: Int): Int? {
        if (currentIndex !in 0 until playlistSize) return null
        val next = currentIndex + 1
        return if (next < playlistSize) next else null
    }

    /** Track-level [nextIndex]: the successor track, or `null` when playback must stop. */
    fun nextTrack(tracks: List<MusicTrack>, currentIndex: Int): MusicTrack? =
        nextIndex(tracks.size, currentIndex)?.let { tracks[it] }

    /**
     * True when a track that just failed to stream may be skipped to its successor:
     * [consecutiveFailures] counts the current failure streak (this failure included)
     * and the breaker trips once a full pass over the playlist has failed — parity with
     * the mobile player, so a playlist of dead streams stops instead of cycling.
     * A single-track playlist never "skips": there is no successor to move to.
     */
    fun skipAfterFailure(consecutiveFailures: Int, playlistSize: Int): Boolean =
        consecutiveFailures in 1 until maxOf(playlistSize, 1)
}
