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

    private fun setPlaying(value: Boolean) { isPlaying = value }

    @Synchronized
    fun playTrack(index: Int, queue: List<MusicTrack>) {
        if (index !in queue.indices) return
        tracks = queue.toList()
        this.index = index
        currentTrack = queue[index]
        currentPosition = 0
        setPlaying(false)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            setOnPreparedListener {
                MarsMusicPlayer.duration = it.duration.coerceAtLeast(0)
                MarsMusicPlayer.isPlaying = true
                it.start()
                notifyState()
                notifyProgress()
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ ->
                setPlaying(false)
                notifyState()
                next()
                true
            }
            try {
                setDataSource("$BASE_URL/api/stream/${queue[index].id}")
                prepareAsync()
            } catch (_: Exception) {
                setPlaying(false)
                notifyState()
                next()
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

    @Synchronized fun next() {
        val queue = tracks
        if (queue.isNotEmpty()) playTrack((index + 1).mod(queue.size), queue)
    }

    @Synchronized fun previous() {
        val queue = tracks
        if (queue.isNotEmpty()) playTrack((index - 1 + queue.size).mod(queue.size), queue)
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
}
