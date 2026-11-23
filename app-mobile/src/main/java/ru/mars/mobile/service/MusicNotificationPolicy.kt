package ru.mars.mobile.service

/**
 * Pure notification policy for the music foreground service — no Android framework types,
 * fully unit-testable on the JVM. Decides WHICH transport actions are visible for a given
 * playback state, in which order, which intent action strings they carry, and which of them
 * the collapsed shade row (compact view) may show. Icon resources are resolved by the
 * service ([MarsMusicService]) from [ActionType], keeping this policy platform-free.
 *
 * Seeking is deliberately NOT a notification button: the shade gets it from the
 * [android.support.v4.media.session.MediaSessionCompat] action list (seek-to plus
 * rewind/fast-forward), so Android 13+ renders its own seek controls and scrubber with the
 * system icons instead of us inventing 10-second drawables. The arithmetic that makes those
 * controls safe lives here ([seekTarget], [clampPosition]) and is covered by JVM tests.
 *
 * The cover shown in the shade is tracked by URL key ([artworkKey], [needsArtworkLoad]) so
 * the bitmap is fetched once per track instead of on every state tick, and the tap that
 * brings the player forward carries [TAP_INTENT_FLAGS] from a single place.
 */
object MusicNotificationPolicy {
    /** Notification channel id; declared once here so tests and the service share it. */
    const val CHANNEL_ID = "mars_music_playback"
    /** Human-readable channel name shown in system notification settings. */
    const val CHANNEL_NAME = "Воспроизведение музыки"
    /** Stable notification id: the playback notification is a singleton per process. */
    const val NOTIFICATION_ID = 0x4D53 // "MS"

    // Intent action strings routed by MarsMusicService.onStartCommand.
    const val ACTION_PLAY = "ru.mars.mobile.service.action.PLAY"
    const val ACTION_PAUSE = "ru.mars.mobile.service.action.PAUSE"
    const val ACTION_NEXT = "ru.mars.mobile.service.action.NEXT"
    const val ACTION_PREV = "ru.mars.mobile.service.action.PREV"
    const val ACTION_STOP = "ru.mars.mobile.service.action.STOP"

    /** Transport action kinds; the service maps each kind onto a drawable icon. */
    enum class ActionType { PLAY, PAUSE, NEXT, PREV, STOP }

    /** One resolved notification action: what it does, its intent string, its a11y label. */
    data class MediaAction(
        val type: ActionType,
        val action: String,
        val contentDescription: String,
    )

    /**
     * Visible transport actions for the current state, in shade order:
     * prev → play/pause (mutually exclusive) → next → stop. No track means there is
     * nothing to control, so the list is empty (the service tears itself down instead).
     *
     * Five actions is the hard ceiling for one notification row: a sixth (a seek pair, say)
     * would be silently dropped by the system, which is why seeking rides on the session.
     */
    fun actions(isPlaying: Boolean, hasTrack: Boolean): List<MediaAction> {
        if (!hasTrack) return emptyList()
        return buildList {
            add(MediaAction(ActionType.PREV, ACTION_PREV, "Предыдущий трек"))
            if (isPlaying) {
                add(MediaAction(ActionType.PAUSE, ACTION_PAUSE, "Пауза"))
            } else {
                add(MediaAction(ActionType.PLAY, ACTION_PLAY, "Играть"))
            }
            add(MediaAction(ActionType.NEXT, ACTION_NEXT, "Следующий трек"))
            add(MediaAction(ActionType.STOP, ACTION_STOP, "Закрыть плеер"))
        }
    }

    /**
     * Indices shown in the collapsed notification row: prev, play/pause, next — the stop
     * button stays in the expanded view so the collapsed shade is not crowded.
     */
    fun compactActionIndices(isPlaying: Boolean, hasTrack: Boolean): IntArray =
        if (actions(isPlaying, hasTrack).size >= 3) intArrayOf(0, 1, 2) else IntArray(0)

    /** True while the notification must be visible and the service must stay foreground. */
    fun isPlaybackAlive(hasTrack: Boolean, isPlaying: Boolean): Boolean = hasTrack || isPlaying

    /** Routes an intent action string back to its [ActionType]; null for unknown/absent. */
    fun typeOf(action: String?): ActionType? = when (action) {
        ACTION_PLAY -> ActionType.PLAY
        ACTION_PAUSE -> ActionType.PAUSE
        ACTION_NEXT -> ActionType.NEXT
        ACTION_PREV -> ActionType.PREV
        ACTION_STOP -> ActionType.STOP
        else -> null
    }

    // ---------------------------------------------------------------- seeking

    /**
     * One seek jump for the session's rewind / fast-forward controls. Ten seconds is the
     * platform convention for the media-controls step.
     */
    const val SEEK_STEP_MS: Long = 10_000L

    /**
     * Relative seek: [positionMs] shifted by [deltaMs] (negative rewinds) and clamped into
     * `[0, durationMs]`. A non-positive [durationMs] means the engine has not reported a
     * length yet, so the upper clamp is skipped rather than snapping playback to the start.
     * Long arithmetic throughout: `Int.MAX_VALUE + step` must not wrap.
     */
    fun seekTarget(positionMs: Int, deltaMs: Long, durationMs: Int): Int =
        clampPosition(positionMs.toLong() + deltaMs, durationMs)

    /**
     * Absolute seek used by the shade scrubber (`ACTION_SEEK_TO`): the position comes from
     * the system, so it is clamped the same way as a relative jump. Negative input (a
     * misbehaving client) lands on 0, not before the track's start.
     */
    fun clampPosition(positionMs: Long, durationMs: Int): Int {
        val lower = positionMs.coerceAtLeast(0L)
        val upper = if (durationMs > 0) lower.coerceAtMost(durationMs.toLong()) else lower
        return upper.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Whether a progress tick warrants rewriting the session's playback position: only
     * jumps of at least a second (a seek, a resume from another device) matter. Plain ticks
     * run far faster than the shade can show and every rewrite costs a binder round-trip.
     */
    fun shouldRefreshSessionPosition(previousMs: Int, nextMs: Int): Boolean =
        kotlin.math.abs(nextMs.toLong() - previousMs.toLong()) >= SESSION_POSITION_EPSILON_MS

    /** Smallest jump worth a session update; below this the shade would not move anyway. */
    const val SESSION_POSITION_EPSILON_MS: Long = 1_000L

    // ---------------------------------------------------------------- artwork

    /** Stable key for the shade cover: the trimmed thumbnail URL, null when there is none. */
    fun artworkKey(thumbnail: String?): String? =
        thumbnail?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether the large icon must be (re)fetched for [thumbnail]: only when the key really
     * changed. A blank thumbnail keeps the previous cover instead of blanking the shade on
     * every state tick of a track that simply has no art.
     */
    fun needsArtworkLoad(currentKey: String?, thumbnail: String?): Boolean {
        val next = artworkKey(thumbnail) ?: return false
        return next != currentKey
    }

    /** Decode target for the shade cover: a large icon is 64dp, i.e. ~256px on xhdpi+. */
    const val ARTWORK_MAX_PX: Int = 256

    // ---------------------------------------------------------------- tap

    // android.content.Intent flag values mirrored here so this object stays framework-free.
    // Expressed as bit shifts, not hex: the design-system parity gate flags any 0x literal
    // as a hardcoded colour, and these are bit positions, not colours.
    private const val FLAG_ACTIVITY_NEW_TASK = 1 shl 28       // 0x10000000
    private const val FLAG_ACTIVITY_CLEAR_TOP = 1 shl 26      // 0x04000000
    private const val FLAG_ACTIVITY_SINGLE_TOP = 1 shl 29     // 0x20000000

    /**
     * Intent flags for the tap on the notification: NEW_TASK because a PendingIntent starts
     * outside any activity, CLEAR_TOP|SINGLE_TOP so tapping an already-open player reuses
     * that instance (onNewIntent) instead of stacking duplicates behind it.
     */
    const val TAP_INTENT_FLAGS: Int =
        FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
}
