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
 * - [transportControls]: the header transport-control state (visibility of the
 *   «Пауза/Играть» + «Остановить» pair and the toggle caption/icon) — pause/resume
 *   on the music screen itself, so the user is never forced to destroy the playlist
 *   binding («Остановить») just to quiet the audio. Values are delegated to
 *   [TvMiniPlayerSpec] — the chat mini player and this screen speak with one voice.
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

    /**
     * Header transport controls of the music screen for one transport snapshot.
     * [visible] governs the «Пауза/Играть» + «Остановить» pair (shown only while a
     * track is bound — an idle transport has nothing to pause or stop);
     * [playPauseLabel]/[showPauseIcon] describe the toggle: while audio renders the
     * button offers «Пауза» with the pause icon, otherwise «Играть» with the play
     * icon (paused, preparing or failed track alike — the click resumes/retries via
     * [TvMusicPlayer.togglePlayPause]).
     */
    data class TransportControls(
        val visible: Boolean,
        val playPauseLabel: String,
        val showPauseIcon: Boolean,
    )

    /**
     * Pure decision for the music-screen header transport pair. Delegates the values
     * to [TvMiniPlayerSpec] (single source of truth shared with the chat mini player —
     * the captions must never diverge between the two surfaces).
     */
    fun transportControls(track: MusicTrack?, isPlaying: Boolean): TransportControls =
        TransportControls(
            visible = TvMiniPlayerSpec.visible(track),
            playPauseLabel = TvMiniPlayerSpec.toggleLabel(isPlaying),
            showPauseIcon = isPlaying,
        )
}
