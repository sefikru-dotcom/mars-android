package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/**
 * Pure motion parameters for the mini-player reveal/hide: a calm fade+slide
 * (alpha + translationY only — no scale, no bounce, no overshoot). The (thin) view
 * layer maps these numbers onto its animator; when [instant] is true it must apply
 * the final state directly and never start an animator at all.
 */
data class MiniPlayerMotion(
    /** Animator duration in ms; 0 when [instant]. */
    val durationMs: Int,
    val alphaFrom: Float,
    val alphaTo: Float,
    /** Vertical offset in dp (the view layer converts to px); the reveal settles at 0. */
    val translationFromDp: Float,
    val translationToDp: Float,
    /** True when motion is suppressed: from == to == the final state. */
    val instant: Boolean,
)

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

/** Direction of the mini-player strip motion for an incoming state. */
enum class MiniPlayerAction { NONE, REVEAL, HIDE }

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

    /** Slide distance of the reveal/hide fade+slide: small, so the strip never «jumps». */
    const val REVEAL_SLIDE_DP = 6f

    /** Hard cap for the strip motion — never heavier than base motion (200 ms). */
    const val MAX_REVEAL_MS = MarsMotion.baseMs

    /** CARD_EXPAND duration, capped at [MAX_REVEAL_MS]; 0 under reduced motion. */
    private fun revealDurationMs(reducedMotion: Boolean): Int =
        MotionPolicy.durationFor(MotionCue.CARD_EXPAND, reducedMotion).coerceIn(0, MAX_REVEAL_MS)

    /**
     * Motion to run for an incoming state, given the strip's INTENDED visibility.
     *
     * The view layer must pass the *intended* flag, never `View.visibility`: during a
     * hide the view is still VISIBLE until the end action lands GONE, so a track
     * arriving inside that window (track→null→track faster than the motion) would be
     * ignored and the hide would finish on a live track. Comparing intent makes the
     * reveal cancel the in-flight hide instead.
     */
    fun motionAction(intendedVisible: Boolean, nextVisible: Boolean): MiniPlayerAction = when {
        nextVisible && !intendedVisible -> MiniPlayerAction.REVEAL
        !nextVisible && intendedVisible -> MiniPlayerAction.HIDE
        else -> MiniPlayerAction.NONE
    }

    /**
     * Motion for the strip appearing: fade+slide in (alpha 0→1, translationY 6dp→0) over
     * [revealDurationMs]. Under reduced motion the result is [MiniPlayerMotion.instant]:
     * from == to == the final (fully visible) state, duration 0 — no animator at all.
     */
    fun showMotion(reducedMotion: Boolean): MiniPlayerMotion {
        val duration = revealDurationMs(reducedMotion)
        return if (duration <= 0) {
            MiniPlayerMotion(0, 1f, 1f, 0f, 0f, instant = true)
        } else {
            MiniPlayerMotion(duration, 0f, 1f, REVEAL_SLIDE_DP, 0f, instant = false)
        }
    }

    /**
     * Motion for the strip disappearing: the mirror of [showMotion] (alpha 1→0,
     * translationY 0→6dp). Under reduced motion: instantly the final hidden state.
     */
    fun hideMotion(reducedMotion: Boolean): MiniPlayerMotion {
        val duration = revealDurationMs(reducedMotion)
        return if (duration <= 0) {
            MiniPlayerMotion(0, 0f, 0f, REVEAL_SLIDE_DP, REVEAL_SLIDE_DP, instant = true)
        } else {
            MiniPlayerMotion(duration, 1f, 0f, 0f, REVEAL_SLIDE_DP, instant = false)
        }
    }

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
