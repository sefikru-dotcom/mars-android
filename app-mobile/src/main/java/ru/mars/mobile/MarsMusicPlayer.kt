package ru.mars.mobile

import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import ru.mars.network.model.MusicTrack

/** Process-scoped player; its MediaPlayer survives Activity recreation and screen changes. */
object MarsMusicPlayer {
    interface MarsMusicListener {
        fun onStateChanged(track: MusicTrack?, isPlaying: Boolean)
        fun onProgress(currentMs: Int, totalMs: Int)
    }

    private const val BASE_URL = "https://marscore.ru/music"
    private var mediaPlayer: MediaPlayer? = null
    private val listeners = LinkedHashSet<MarsMusicListener>()

    /** Session audio effects bound to the current MediaPlayer's audio session. */
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** Pure queue logic (order, shuffle, repeat, growth); the UI mirrors and toggles it. */
    val queueManager = MusicQueueManager()

    /** Pure retry/circuit-breaker decision core (backoff, error classification, skip streaks). */
    private val retryPolicy = PlaybackRetryPolicy()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingRetry: Runnable? = null
    /** Position the next prepared stream must seek to (mid-track retry); 0 = start over. */
    private var retryResumeMs = 0

    @Volatile var currentTrack: MusicTrack? = null
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var currentPosition: Int = 0
        private set
    @Volatile var duration: Int = 0
        private set
    @Volatile var tracks: List<MusicTrack> = emptyList()
        private set
    @Volatile var index: Int = -1
        private set
    @Volatile var isShuffled: Boolean = false
        private set
    @Volatile var repeatMode: MusicQueueManager.RepeatMode = MusicQueueManager.RepeatMode.OFF
        private set
    /** Current sound settings (preset, on/off, Bass Boost); survives track changes. */
    @Volatile var audioFxState: AudioFxState = AudioFxState()
        private set

    private fun setPlaying(value: Boolean) { isPlaying = value }

    private fun syncQueueState() {
        tracks = queueManager.queue
        index = queueManager.currentIndex
        currentTrack = queueManager.currentTrack
        isShuffled = queueManager.isShuffled
        repeatMode = queueManager.repeatMode
    }

    @Synchronized
    fun playTrack(index: Int, queue: List<MusicTrack>) {
        if (index !in queue.indices) return
        retryPolicy.onPlaybackReady()
        queueManager.setQueue(queue, index)
        syncQueueState()
        startCurrentTrack()
    }

    /**
     * Appends [track] to the playback queue. Playback (re)starts on it when the queue was
     * empty OR the previous session finished (exhausted/stopped) — the decision comes from
     * [MusicQueueManager]/[QueueResumePolicy], so a finished queue can never resurrect its
     * stale track and the enqueue is never a silent no-op.
     */
    @Synchronized
    fun enqueue(track: MusicTrack) {
        val shouldStart = queueManager.addToQueue(track)
        syncQueueState()
        if (shouldStart) {
            retryPolicy.onPlaybackReady()
            startCurrentTrack()
        } else {
            notifyState()
        }
    }

    @Synchronized
    fun toggleShuffle(): Boolean {
        val shuffled = queueManager.toggleShuffle()
        syncQueueState()
        notifyState()
        return shuffled
    }

    @Synchronized
    fun cycleRepeatMode(): MusicQueueManager.RepeatMode {
        val mode = queueManager.cycleRepeatMode()
        syncQueueState()
        notifyState()
        return mode
    }

    /**
     * Prepares and starts the current queue track. [isRetry] marks a backoff retry of the
     * SAME track scheduled by [handlePlaybackFailure]: it keeps the per-track retry budget
     * and may resume from [retryResumeMs] instead of restarting the song.
     */
    private fun startCurrentTrack(isRetry: Boolean = false) {
        val track = queueManager.currentTrack ?: return
        cancelPendingRetry()
        if (!isRetry) {
            retryPolicy.onTrackSelected()
            retryResumeMs = 0
        }
        currentPosition = 0
        duration = 0
        setPlaying(false)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            setOnPreparedListener {
                retryPolicy.onPlaybackReady()
                MarsMusicPlayer.duration = it.duration.coerceAtLeast(0)
                val resume = retryResumeMs
                retryResumeMs = 0
                if (resume in 1 until it.duration) {
                    it.seekTo(resume)
                    MarsMusicPlayer.currentPosition = resume
                }
                MarsMusicPlayer.isPlaying = true
                it.start()
                notifyState()
                notifyProgress()
            }
            // Track finished: advance through the queue manager (honors shuffle/repeat).
            setOnCompletionListener { next() }
            setOnErrorListener { _, what, extra ->
                handlePlaybackFailure(track.id, what, extra)
                true
            }
            try {
                setDataSource("$BASE_URL/api/stream/${track.id}")
                prepareAsync()
            } catch (error: Exception) {
                // setDataSource/prepareAsync threw: map onto the same decision path.
                handlePlaybackFailure(track.id, PlaybackRetryPolicy.codeFor(error), 0)
            }
        }
        // Effects attach to the fresh audio session so the saved preset survives track changes.
        mediaPlayer?.let { attachAudioFx(it.audioSessionId) }
        notifyState()
    }

    /**
     * Updates the sound settings (persisted by AudioFxSettings in the UI layer) and applies
     * them to the live session immediately; a fresh MediaPlayer picks them up via
     * [attachAudioFx] on the next track.
     */
    @Synchronized
    fun setAudioFx(state: AudioFxState) {
        audioFxState = AudioFxPolicy.sanitizeState(state)
        applyAudioFx(audioFxState.preset, audioFxState.bassBoost.toShort())
    }

    /**
     * Applies [preset] to the attached [Equalizer] and [bassBoostStrength] (0..1000) to the
     * attached [BassBoost], honoring [audioFxState].enabled. Fully fail-safe: effects are
     * optional hardware, so every call is guarded (try-catch) and each status is checked
     * against [AudioEffect.SUCCESS] — playback never depends on them.
     */
    @Synchronized
    fun applyAudioFx(preset: AudioFxPreset, bassBoostStrength: Short) {
        val enabled = audioFxState.enabled
        equalizer?.let { eq ->
            runCatching {
                if (eq.getEnabled() != enabled && eq.setEnabled(enabled) != AudioEffect.SUCCESS) {
                    return@runCatching
                }
                if (!enabled) return@runCatching
                val range = eq.getBandLevelRange() ?: return@runCatching
                val levels = AudioFxPolicy.bandLevelsMb(
                    preset,
                    eq.getNumberOfBands().toInt(),
                    range[0].toInt(),
                    range[1].toInt(),
                )
                // setBandLevel is void: a rejected band throws and is caught by runCatching.
                for (band in levels.indices) {
                    eq.setBandLevel(band.toShort(), levels[band])
                }
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                val strength = AudioFxPolicy.sanitizeStrength(bassBoostStrength.toInt()).toShort()
                val wanted = enabled && strength > 0 && bb.getStrengthSupported()
                if (bb.getEnabled() != wanted && bb.setEnabled(wanted) != AudioEffect.SUCCESS) {
                    return@runCatching
                }
                if (wanted) bb.setStrength(strength) // void: failure throws into runCatching
            }
        }
    }

    /**
     * Attaches Equalizer + BassBoost to a new MediaPlayer audio session. Construction throws
     * on unsupported hardware/runtime failures — degrade silently, music plays without effects.
     */
    private fun attachAudioFx(sessionId: Int) {
        releaseAudioFx()
        if (sessionId == 0) return // AudioEffect.ERROR_BAD_VALUE: invalid session
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        applyAudioFx(audioFxState.preset, audioFxState.bassBoost.toShort())
    }

    /** Releases session effects alongside the MediaPlayer they are bound to. */
    private fun releaseAudioFx() {
        equalizer?.let { effect -> runCatching { effect.release() } }
        bassBoost?.let { effect -> runCatching { effect.release() } }
        equalizer = null
        bassBoost = null
    }

    @Synchronized fun togglePlay() { if (isPlaying) pause() else play() }

    @Synchronized fun play() {
        val player = mediaPlayer
        if (player == null) {
            // Adopted-but-not-started queue (remote sync): prepare and start the selected
            // track. Unreachable for every pre-existing state — a finished session has no
            // current track and clearQueue() empties it — so local behavior is unchanged.
            if (!queueManager.isFinished && queueManager.currentTrack != null) {
                retryPolicy.onPlaybackReady()
                startCurrentTrack()
            }
            return
        }
        runCatching { player.start(); isPlaying = true; notifyState() }
    }

    @Synchronized fun pause() {
        val player = mediaPlayer ?: return
        runCatching { currentPosition = player.currentPosition; player.pause(); isPlaying = false; notifyState() }
    }

    /** Auto/manual advance: honors shuffle and repeat; stops when the queue is exhausted. */
    @Synchronized fun next() {
        if (queueManager.next() != null) {
            syncQueueState()
            startCurrentTrack()
        } else {
            stopPlayback()
        }
    }

    @Synchronized fun previous() {
        if (queueManager.previous() != null) {
            syncQueueState()
            startCurrentTrack()
        }
        // At the start of the queue (repeat off) the current track simply keeps playing.
    }

    /** Jumps to the queue track at [index] and starts playing it; out-of-range is a no-op. */
    @Synchronized
    fun playQueueAt(index: Int) {
        if (queueManager.playAt(index) == null) return
        retryPolicy.onPlaybackReady()
        syncQueueState()
        startCurrentTrack()
    }

    /**
     * Removes the queue track at [index] and returns it (null when out of range). When the
     * removed track was the current one, playback continues on the successor selected by
     * [MusicQueueManager]; if none remains (queue empty or the session finished) playback stops.
     */
    @Synchronized
    fun removeFromQueue(index: Int): MusicTrack? {
        val wasCurrent = index == queueManager.currentIndex && queueManager.currentTrack != null
        val removed = queueManager.removeAt(index) ?: return null
        syncQueueState()
        if (!wasCurrent) {
            notifyState()
            return removed
        }
        if (queueManager.currentTrack == null || queueManager.isFinished) {
            stopPlayback()
        } else {
            retryPolicy.onPlaybackReady()
            startCurrentTrack()
        }
        return removed
    }

    /** Stops playback and empties the queue; listeners see the cleared state. */
    @Synchronized
    fun clearQueue() {
        cancelPendingRetry()
        mediaPlayer?.release()
        mediaPlayer = null
        releaseAudioFx()
        setPlaying(false)
        currentPosition = 0
        duration = 0
        retryResumeMs = 0
        retryPolicy.onPlaybackReady()
        queueManager.clear()
        syncQueueState()
        notifyState()
        notifyProgress()
    }

    /**
     * Adopts an externally resolved queue (remote sync — see [RemoteQueueSyncController])
     * WITHOUT starting audio: any live player is released, [startIndex] becomes the current
     * track and playback stays stopped — the user presses play to join the session, so the
     * phone never starts sounding on its own because a remote device did. The subsequent
     * [play] prepares the adopted track from scratch. Empty queue or out-of-range index is
     * a no-op (same guard as [playTrack]); local queue ownership is decided by the caller.
     */
    @Synchronized
    fun adoptQueue(queue: List<MusicTrack>, startIndex: Int) {
        if (queue.isEmpty() || startIndex !in queue.indices) return
        cancelPendingRetry()
        mediaPlayer?.release()
        mediaPlayer = null
        releaseAudioFx()
        setPlaying(false)
        currentPosition = 0
        duration = 0
        retryResumeMs = 0
        retryPolicy.onPlaybackReady()
        queueManager.setQueue(queue, startIndex)
        syncQueueState()
        notifyState()
        notifyProgress()
    }

    @Synchronized fun seekTo(ms: Int) {
        val target = ms.coerceAtLeast(0)
        runCatching { mediaPlayer?.seekTo(target); currentPosition = target; notifyProgress() }
    }

    @Synchronized fun setVolume(volume: Float) {
        runCatching { mediaPlayer?.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f)) }
    }

    @Synchronized fun registerListener(listener: MarsMusicListener) {
        listeners.add(listener)
        listener.onStateChanged(currentTrack, isPlaying)
        listener.onProgress(currentPosition, duration)
    }

    @Synchronized fun unregisterListener(listener: MarsMusicListener) { listeners.remove(listener) }

    private fun notifyState() { listeners.toList().forEach { it.onStateChanged(currentTrack, isPlaying) } }
    private fun notifyProgress() {
        val position = runCatching { mediaPlayer?.currentPosition ?: currentPosition }.getOrDefault(currentPosition)
        currentPosition = position
        listeners.toList().forEach { it.onProgress(position, duration) }
    }

    /**
     * Queue exhausted (repeat off) or an unwinnable failure streak: release the player and
     * finish the queue session so the stale track cannot resurface as current; the queue
     * list itself is kept for the UI, and the next [enqueue] resumes playback.
     */
    private fun stopPlayback() {
        cancelPendingRetry()
        mediaPlayer?.release()
        mediaPlayer = null
        releaseAudioFx()
        setPlaying(false)
        currentPosition = 0
        duration = 0
        retryResumeMs = 0
        queueManager.markFinished()
        syncQueueState() // currentTrack <- null (finished), tracks kept
        notifyState()
        notifyProgress()
    }

    /**
     * Stream failure entry point: asks [retryPolicy] (pure, unit-tested) what to do and
     * applies the verdict — backoff retry of the same track (resuming mid-track when the
     * failure happened during playback), skip to the next one, or stop on a full failed
     * pass over the queue.
     */
    @Synchronized
    private fun handlePlaybackFailure(failedId: String, what: Int, extra: Int) {
        setPlaying(false)
        notifyState()
        // A player in the error state may throw on currentPosition; fall back to the last
        // notified progress so a mid-track network drop can resume instead of restarting.
        val position = runCatching { mediaPlayer?.currentPosition ?: currentPosition }
            .getOrDefault(currentPosition)
        when (val decision = retryPolicy.decide(what, extra, queueManager.size, position, duration)) {
            is RetryDecision.RetryTrack -> {
                // Release the broken player now; the retry re-prepares a fresh one.
                retryResumeMs = decision.resumeFromMs
                mediaPlayer?.release()
                mediaPlayer = null
                releaseAudioFx()
                scheduleRetry(decision.delayMs)
            }
            RetryDecision.SkipTrack -> skipFailedTrack(failedId)
            RetryDecision.Stop -> stopPlayback()
        }
    }

    /**
     * Skips a track whose retry budget is spent. Repeat-one must not re-select the same
     * broken stream: navigate once as if repeat were OFF, then restore the user's mode.
     */
    private fun skipFailedTrack(failedId: String) {
        if (queueManager.repeatMode == MusicQueueManager.RepeatMode.ONE && queueManager.currentTrack?.id == failedId) {
            queueManager.setRepeatMode(MusicQueueManager.RepeatMode.OFF)
            val moved = queueManager.next() != null
            queueManager.setRepeatMode(MusicQueueManager.RepeatMode.ONE)
            if (moved) {
                syncQueueState()
                startCurrentTrack()
            } else {
                stopPlayback()
            }
            return
        }
        next()
    }

    /** Posts the backoff retry on the main looper; at most one retry can be pending. */
    private fun scheduleRetry(delayMs: Long) {
        cancelPendingRetry()
        val retry = Runnable {
            synchronized(MarsMusicPlayer) {
                pendingRetry = null
                startCurrentTrack(isRetry = true)
            }
        }
        pendingRetry = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
    }
}
