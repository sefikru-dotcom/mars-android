package ru.mars.mobile

import android.media.AudioManager
import android.media.MediaPlayer
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

    /** Pure queue logic (order, shuffle, repeat, growth); the UI mirrors and toggles it. */
    val queueManager = MusicQueueManager()

    /** Consecutive stream failures; circuit breaker so a broken queue can't retry forever. */
    private var consecutiveFailures = 0

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
        consecutiveFailures = 0
        queueManager.setQueue(queue, index)
        syncQueueState()
        startCurrentTrack()
    }

    /** Appends [track] to the playback queue; playback starts when nothing was queued. */
    @Synchronized
    fun enqueue(track: MusicTrack) {
        val wasEmpty = queueManager.isEmpty
        queueManager.addToQueue(track)
        syncQueueState()
        if (wasEmpty) {
            consecutiveFailures = 0
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

    private fun startCurrentTrack() {
        val track = queueManager.currentTrack ?: return
        currentPosition = 0
        duration = 0
        setPlaying(false)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            setOnPreparedListener {
                consecutiveFailures = 0
                MarsMusicPlayer.duration = it.duration.coerceAtLeast(0)
                MarsMusicPlayer.isPlaying = true
                it.start()
                notifyState()
                notifyProgress()
            }
            // Track finished: advance through the queue manager (honors shuffle/repeat).
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ ->
                handlePlaybackFailure(track.id)
                true
            }
            try {
                setDataSource("$BASE_URL/api/stream/${track.id}")
                prepareAsync()
            } catch (_: Exception) {
                handlePlaybackFailure(track.id)
            }
        }
        notifyState()
    }

    @Synchronized fun togglePlay() { if (isPlaying) pause() else play() }

    @Synchronized fun play() {
        val player = mediaPlayer ?: return
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

    /** Queue exhausted (repeat off) or an unwinnable failure streak: release the player. */
    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        setPlaying(false)
        currentPosition = 0
        duration = 0
        currentTrack = null
        notifyState()
        notifyProgress()
    }

    @Synchronized
    private fun handlePlaybackFailure(failedId: String) {
        setPlaying(false)
        notifyState()
        consecutiveFailures++
        if (consecutiveFailures >= queueManager.size.coerceAtLeast(1)) {
            // A full pass over the queue failed — stop instead of retrying forever.
            consecutiveFailures = 0
            stopPlayback()
            return
        }
        if (queueManager.repeatMode == MusicQueueManager.RepeatMode.ONE && queueManager.currentTrack?.id == failedId) {
            // Repeat-one must not endlessly retry the same broken stream: navigate once
            // as if repeat were OFF, then restore the user's mode.
            queueManager.setRepeatMode(MusicQueueManager.RepeatMode.OFF)
            val moved = queueManager.next() != null
            queueManager.setRepeatMode(MusicQueueManager.RepeatMode.ONE)
            if (moved) {
                syncQueueState()
                startCurrentTrack()
            } else {
                consecutiveFailures = 0
                stopPlayback()
            }
            return
        }
        next()
    }
}
