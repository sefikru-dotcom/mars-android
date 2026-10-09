package ru.mars.tv

import ru.mars.network.model.MusicTrack

/**
 * Pure presentation rules of the TV chat header's mini player (no Android, no I/O —
 * JVM-testable, [TvMusicNowPlayingSpec] parity). The global transport ([TvMusicPlayer])
 * keeps playing across screens; the chat header only mirrors its state, and every
 * display decision lives here so it is covered by unit tests:
 *
 * - [visible]: the plate is shown only while a track is bound to the transport —
 *   no track, no plate (View.GONE in the screen).
 * - [line]: the compact caption «title — artist» (artist dropped when blank).
 * - [toggleLabel]: the Play/Pause button caption for the current transport state.
 */
object TvMiniPlayerSpec {

    /** True while the mini-player plate should be shown (a track is bound). */
    fun visible(track: MusicTrack?): Boolean = track != null

    /** Plate caption for the bound track; "" when the transport is idle. */
    fun line(track: MusicTrack?): String = when {
        track == null -> ""
        track.artist.isBlank() -> track.title
        else -> "${track.title} — ${track.artist}"
    }

    /** Play/Pause button caption: pause the running audio, resume/play otherwise. */
    fun toggleLabel(isPlaying: Boolean): String = if (isPlaying) "Пауза" else "Играть"
}
