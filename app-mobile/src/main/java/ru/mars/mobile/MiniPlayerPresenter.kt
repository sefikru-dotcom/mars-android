package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/** Presentation data for the ChatActivity mini-player strip, consumed by the (thin) Android layer. */
data class MiniPlayerUiState(
    /** False when there is no current track — the whole strip must be hidden. */
    val visible: Boolean,
    /** Header line: «title · artist» with graceful fallbacks for blank parts. */
    val titleText: String,
    /** True → show the pause icon (tap pauses); false → show the play icon. */
    val showPauseIcon: Boolean,
    /** Playback status announced by the play button («Воспроизведение» / «Пауза»). */
    val statusLabel: String,
) {
    companion object {
        /** State of the hidden strip (no current track). */
        val HIDDEN = MiniPlayerUiState(
            visible = false,
            titleText = "",
            showPauseIcon = false,
            statusLabel = MiniPlayerPresenter.STATUS_PAUSED,
        )
    }
}

/**
 * Pure presentation logic for the ChatActivity mini-player: header formatting and
 * play/pause status. No Android imports — JVM unit-testable; [ChatActivity] only maps
 * the state onto views and forwards taps to [MarsMusicPlayer] (togglePlay/previous/next).
 */
object MiniPlayerPresenter {
    /** Separator between title and artist in the single-line header. */
    const val SEPARATOR = " · "

    /** Fallback header when both title and artist are blank. */
    const val UNKNOWN_TITLE = "Неизвестный трек"

    const val STATUS_PLAYING = "Воспроизведение"
    const val STATUS_PAUSED = "Пауза"

    /**
     * Resolves the strip state for the current player snapshot. A null [track] hides the
     * strip; otherwise the header joins the non-blank, trimmed title/artist parts with
     * [SEPARATOR] (single part when the other is blank, [UNKNOWN_TITLE] when both are).
     */
    fun resolve(track: MusicTrack?, isPlaying: Boolean): MiniPlayerUiState {
        if (track == null) return MiniPlayerUiState.HIDDEN
        return MiniPlayerUiState(
            visible = true,
            titleText = formatTitle(track.title, track.artist),
            showPauseIcon = isPlaying,
            statusLabel = if (isPlaying) STATUS_PLAYING else STATUS_PAUSED,
        )
    }

    /** «title · artist» with blank parts dropped; never returns an empty string. */
    fun formatTitle(title: String?, artist: String?): String {
        val parts = listOfNotNull(title, artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> UNKNOWN_TITLE
            else -> parts.joinToString(SEPARATOR)
        }
    }
}
