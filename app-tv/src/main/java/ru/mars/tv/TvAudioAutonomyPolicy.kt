package ru.mars.tv

/**
 * Pure audio-autonomy decisions of the global TV music transport (no Android, no I/O —
 * JVM-testable, [TvPlaybackModePolicy] parity). [TvMusicPlayer] owns the state
 * (isPlaying, paused-by-focus-loss, ducked, permission grants) and asks this object for
 * every reaction, so the AudioManager focus semantics are covered by unit tests without
 * touching a real MediaPlayer:
 *
 * - [onFocusChange]: maps an audio-focus change onto one transport action — a permanent
 *   LOSS stops playback for good, a TRANSIENT loss pauses it, CAN_DUCK only lowers the
 *   volume, and GAIN restores exactly what the focus loss took (never resumes a pause
 *   the USER made).
 * - [shouldHoldWakeLock]: a network stream needs a PARTIAL_WAKE_LOCK so the CPU keeps
 *   fetching while the screen is off — but only when the WAKE_LOCK permission is actually
 *   granted (acquiring without it throws SecurityException at start()).
 * - [sanitizePositionMs]: raw MediaPlayer positions are device-quirky (negative values,
 *   IllegalStateException in error states) — the transport surfaces only safe ones.
 */
object TvAudioAutonomyPolicy {
    /**
     * Audio-focus callback codes, value-identical to the AudioManager constants
     * (`AUDIOFOCUS_GAIN`, `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`,
     * `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`) — duplicated as literals so this policy stays
     * a pure JVM artifact; [TvMusicPlayer] passes the framework values through verbatim
     * and the unit test pins the parity.
     */
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_TRANSIENT_LOSS = -2
    const val FOCUS_TRANSIENT_LOSS_CAN_DUCK = -3

    /**
     * The transport reaction to a focus change:
     * - [NONE]: nothing to do (unknown code, or the state makes the event a no-op).
     * - [PAUSE]: stop rendering but keep the position (transient loss).
     * - [DUCK]: keep playing at lowered volume (notification-style interruption).
     * - [STOP]: permanent loss — another app owns the audio; go fully idle.
     * - [RESTORE]: focus is back — undo the duck and/or resume the focus-induced pause.
     */
    enum class Action { NONE, PAUSE, DUCK, STOP, RESTORE }

    /**
     * Reaction to the focus change [focusChange] for a transport that is currently
     * [isPlaying], was paused by an earlier transient loss ([pausedByFocusLoss]) and/or
     * is ducked ([ducked]):
     *
     * - LOSS -> STOP unconditionally: the loss is permanent, holding a paused player
     *   would squat a session nobody will resume.
     * - TRANSIENT -> PAUSE, but only while actually playing (pausing an already-paused
     *   transport would falsely claim the pause was focus-induced).
     * - CAN_DUCK -> DUCK while playing; a paused transport has nothing to duck.
     * - GAIN -> RESTORE only when a focus loss left something to undo; a pause the USER
     *   made must never auto-resume when some unrelated focus event fires.
     */
    fun onFocusChange(
        focusChange: Int,
        isPlaying: Boolean,
        pausedByFocusLoss: Boolean,
        ducked: Boolean,
    ): Action = when (focusChange) {
        FOCUS_LOSS -> Action.STOP
        FOCUS_TRANSIENT_LOSS -> if (isPlaying) Action.PAUSE else Action.NONE
        FOCUS_TRANSIENT_LOSS_CAN_DUCK -> if (isPlaying) Action.DUCK else Action.NONE
        FOCUS_GAIN -> if (pausedByFocusLoss || ducked) Action.RESTORE else Action.NONE
        else -> Action.NONE
    }

    /**
     * True when a network stream should hold a PARTIAL_WAKE_LOCK
     * (`MediaPlayer.setWakeMode`): the permission must be granted at install time
     * (WAKE_LOCK is a normal permission — granted iff declared in the manifest);
     * acquiring the lock without it throws SecurityException once playback starts, so an
     * ungranted transport must silently skip the wake mode and keep playing.
     */
    fun shouldHoldWakeLock(wakeLockPermissionGranted: Boolean): Boolean = wakeLockPermissionGranted

    /**
     * `AudioManager.requestAudioFocus` result codes, value-identical to the framework
     * constants (`AUDIOFOCUS_REQUEST_GRANTED` / `AUDIOFOCUS_REQUEST_FAILED`) — duplicated
     * as literals so this policy stays a pure JVM artifact.
     */
    const val FOCUS_REQUEST_GRANTED = 1
    const val FOCUS_REQUEST_FAILED = 0

    /**
     * Whether playback may START for one focus-request outcome ([TvMusicPlayer]):
     * - `null` — focus integration is unavailable (no attached context, no AudioManager,
     *   framework exception): keep the legacy best-effort behaviour and play. A transport
     *   that cannot even ASK must not go silent on TVs with odd focus stacks;
     * - GRANTED — we own the output, play;
     * - FAILED (or any other definitive verdict) — another app holds the audio: starting
     *   anyway would play OVER it, so the start is refused. The caller surfaces the
     *   refusal honestly instead of rendering audio nobody asked for.
     */
    fun shouldStartPlayback(focusRequestResult: Int?): Boolean =
        focusRequestResult == null || focusRequestResult == FOCUS_REQUEST_GRANTED

    /**
     * Safe playback position for UI consumption: negative raw values (a known
     * MediaPlayer quirk in transitional states) clamp to zero — a progress render must
     * never see a negative or crash-worthy position.
     */
    fun sanitizePositionMs(rawPositionMs: Int): Int = rawPositionMs.coerceAtLeast(0)
}
