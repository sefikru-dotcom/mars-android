package ru.mars.tv

import android.media.AudioManager
import android.media.MediaPlayer
import ru.mars.network.model.MusicTrack

/**
 * Process-wide (global) TV playback transport: a Kotlin object whose MediaPlayer survives
 * Activity recreation and screen changes (mobile MarsMusicPlayer parity). Music keeps
 * playing after the music screen closes — only an explicit [stop] (or a new [play], which
 * replaces the stream) tears the player down. The controller talks to it through the
 * [TvMusicTransport] seam, so unit tests never touch this Android code.
 *
 * Stream URL contract: `GET {MUSIC_BASE_URL}/api/stream/{id}` — MusicRepository.getStreamUrl
 * parity. A dead/unreachable stream is recorded in [lastError] and never crashes the app.
 */
object TvMusicPlayer : TvMusicTransport {

    /** Track currently bound to the transport (null when idle/stopped). */
    @Volatile
    var currentTrack: MusicTrack? = null
        private set

    /** True while the MediaPlayer is actually rendering audio. */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Human-readable playback error for the screen's now-playing line; null when healthy. */
    @Volatile
    var lastError: String? = null
        private set

    private var mediaPlayer: MediaPlayer? = null

    @Synchronized
    override fun play(track: MusicTrack) {
        releasePlayer()
        currentTrack = track
        lastError = null
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setOnPreparedListener { prepared ->
                prepared.start()
                isPlaying = true
            }
            player.setOnCompletionListener { isPlaying = false }
            player.setOnErrorListener { _, what, extra ->
                isPlaying = false
                lastError = "Ошибка воспроизведения ($what/$extra)"
                true
            }
            player.setDataSource(streamUrlFor(track.id))
            player.prepareAsync()
        } catch (error: Exception) {
            // A dead stream must not crash the screen: park the transport in the idle state.
            isPlaying = false
            lastError = error.message ?: error.javaClass.simpleName
            releasePlayer()
        }
    }

    @Synchronized
    override fun stop() {
        releasePlayer()
        currentTrack = null
        lastError = null
        isPlaying = false
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (_: IllegalStateException) {
                // Player in a bad state: release() below is still safe.
            }
            player.release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    /** Stream URL (MusicRepository.getStreamUrl parity): `{base}/api/stream/{id}`. */
    fun streamUrlFor(trackId: String): String =
        "${RepositoryTvMusicGateway.MUSIC_BASE_URL}/api/stream/$trackId"
}
