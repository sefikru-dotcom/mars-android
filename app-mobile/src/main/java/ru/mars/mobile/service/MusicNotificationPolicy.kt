package ru.mars.mobile.service

/**
 * Pure notification policy for the music foreground service — no Android framework types,
 * fully unit-testable on the JVM. Decides WHICH transport actions are visible for a given
 * playback state, in which order, which intent action strings they carry, and which of them
 * the collapsed shade row (compact view) may show. Icon resources are resolved by the
 * service ([MarsMusicService]) from [ActionType], keeping this policy platform-free.
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
}
