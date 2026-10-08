package ru.mars.mobile

/**
 * Pure decision policy for the queue-exhaustion → enqueue path of the music player.
 *
 * Background: when the last track completes (repeat OFF) or playback is stopped after a
 * failure streak, the session is over but the queue still holds its old tracks. Enqueuing
 * a new track in that state must START playback of the new track instead of silently
 * appending behind a dead session (old behavior: `wasEmpty == false` → append-only, the
 * player stayed released and the stale track resurfaced as "current").
 *
 * Android-free and side-effect-free: [MusicQueueManager] applies the decision, unit tests
 * cover every queue state (empty / active / exhausted / stopped / repeat).
 */
object QueueResumePolicy {

    /**
     * Whether adding tracks to the queue must (re)start playback.
     *
     * @param queueWasEmpty the queue held no tracks before the addition — nothing can play
     *   unless this addition starts it.
     * @param queueFinished the previous session ended (last track completed with repeat OFF,
     *   or playback was stopped): the player is released and no track is current, so an
     *   addition is the only way playback can ever resume.
     *
     * Any other state means an active session (playing or paused mid-queue): the addition
     * is appended to the play order and the current track keeps playing.
     */
    fun shouldStartPlayback(queueWasEmpty: Boolean, queueFinished: Boolean): Boolean =
        queueWasEmpty || queueFinished
}
