package ru.mars.tv

import ru.mars.network.model.MusicTrack

/**
 * Pure presentation rules of the TV music screen's now-playing surface
 * (no Android, no I/O — JVM-testable, [TvProjectsCardSpec] parity):
 *
 * - [nowPlayingLine]: the honest header line for the global transport state —
 *   empty when idle, «Сейчас играет» only while audio is actually rendering,
 *   an explicit «Не играет» with the transport error, otherwise «Загружаю».
 * - [isCurrent]: whether a list row belongs to the transport's current track
 *   (the row-highlight rule; independent of playing/loading sub-state).
 */
object TvMusicNowPlayingSpec {

    /** Header line for the transport snapshot; "" when no track is bound. */
    fun nowPlayingLine(track: MusicTrack?, isPlaying: Boolean, error: String?): String = when {
        track == null -> ""
        isPlaying -> "Сейчас играет: ${track.title} — ${track.artist}"
        !error.isNullOrBlank() -> "Не играет: ${track.title}. ${error.trim()}"
        else -> "Загружаю: ${track.title}…"
    }

    /** True when the row's track is the transport's current one. */
    fun isCurrent(trackId: String, currentTrackId: String?): Boolean =
        currentTrackId != null && currentTrackId == trackId

    /**
     * Label of the D-pad repeat toggle for one [TvRepeatMode] (the button mirrors the
     * transport preference and cycles it via [TvMusicPlayer.cycleRepeatMode]):
     * OFF reads «выкл», ALL «список» (the whole queue loops), ONE «трек» (the current
     * track replays). Text only — theme colors, no emojis.
     */
    fun repeatButtonLabel(mode: TvRepeatMode): String = when (mode) {
        TvRepeatMode.OFF -> "Повтор: выкл"
        TvRepeatMode.ALL -> "Повтор: список"
        TvRepeatMode.ONE -> "Повтор: трек"
    }
}
