package ru.mars.mobile

/**
 * Pure TalkBack label policy of the music surfaces (no Android imports — JVM-testable).
 *
 * Single source of the action-based a11y strings shared by MusicActivity (thin view
 * layer), its track rows and MiniPlayerPresenter, so the labels can never diverge
 * between the full player, the list rows and the chat mini-player.
 *
 * Convention (parity with the TV transport captions in TvMiniPlayerSpec): the label
 * reads the ACTION the tap performs, not the current state — a playing transport
 * offers «Пауза» (tap pauses), a paused/idle one offers «Воспроизвести» (tap plays).
 */
object MusicA11yPolicy {
    /** Action label while audio is playing: the tap pauses. */
    const val ACTION_PAUSE = "Пауза"

    /** Action label while the transport is paused or idle: the tap starts/resumes playback. */
    const val ACTION_PLAY = "Воспроизвести"

    /** Play/pause action label for the current transport state (main button and row buttons). */
    fun playPauseAction(isPlaying: Boolean): String = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

    /** Shuffle toggle action label: the tap flips the queue shuffle. */
    fun shuffleAction(shuffleOn: Boolean): String =
        if (shuffleOn) "Выключить перемешивание" else "Включить перемешивание"

    /**
     * Repeat action label for the CURRENT mode. The tap cycles OFF→ALL→ONE→OFF
     * (MusicQueueManager.cycleRepeatMode), so the label reads the mode the tap
     * switches TO; from ONE the cycle returns to OFF — «Выключить повтор».
     */
    fun repeatAction(mode: MusicQueueManager.RepeatMode): String = when (mode) {
        MusicQueueManager.RepeatMode.OFF -> "Включить повтор списка"
        MusicQueueManager.RepeatMode.ALL -> "Включить повтор одного трека"
        MusicQueueManager.RepeatMode.ONE -> "Выключить повтор"
    }

    /** Heart (favorite) action label: the tap toggles the track's favorite mark. */
    fun heartAction(isFavorite: Boolean): String =
        if (isFavorite) "Убрать из избранного" else "Добавить в избранное"

    /**
     * Tab pill description: the active tab additionally announces its state
     * («Чарт — активная вкладка») so a TalkBack user knows which tab is open;
     * an inactive tab reads its plain label.
     */
    fun tabDescription(label: String, active: Boolean): String =
        if (active) "$label — активная вкладка" else label
}
