package ru.mars.tv

import android.media.AudioManager
import android.media.MediaPlayer
import ru.mars.network.model.MusicTrack

/**
 * Process-wide (global) TV playback transport: a Kotlin object whose MediaPlayer survives
 * Activity recreation and screen changes (mobile MarsMusicPlayer parity). Music keeps
 * playing after the music screen closes — only an explicit [stop] (or a new [play]/
 * [playPlaylist], which replaces the stream) tears the player down. The controller talks
 * to it through the [TvMusicTransport] seam, so unit tests never touch this Android code.
 *
 * Continuous playback: [playPlaylist] binds a whole list; when a track completes the
 * transport auto-advances to its successor and goes idle once the list is exhausted. A
 * dead stream is skipped to the successor as well, until one full pass over the playlist
 * has failed (circuit breaker) — every navigation decision is delegated to the pure
 * [TvPlaylistPolicy], which is what the unit tests cover.
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

    /** Tracks bound for continuous playback (a single [play] binds a one-track list). */
    @Volatile
    var playlist: List<MusicTrack> = emptyList()
        private set

    /** Playlist position of [currentTrack]; -1 when idle. */
    @Volatile
    var currentIndex: Int = -1
        private set

    private var mediaPlayer: MediaPlayer? = null

    /** Consecutive stream failures; circuit-breaker input for [TvPlaylistPolicy.skipAfterFailure]. */
    private var consecutiveFailures = 0

    /**
     * Screen observer (UI thread only): fired after every transport state transition —
     * play/stop, prepared, completion, auto-advance, stream error — so the music screen
     * can refresh its now-playing line and row highlight while audio state changes
     * asynchronously. Null when no screen is attached; the transport never blocks on
     * observers.
     */
    @Volatile
    var onTransportChanged: (() -> Unit)? = null

    @Synchronized
    override fun play(track: MusicTrack) {
        playPlaylistInternal(listOf(track), 0)
    }

    @Synchronized
    override fun playPlaylist(tracks: List<MusicTrack>, startIndex: Int) {
        val index = TvPlaylistPolicy.startIndex(tracks.size, startIndex) ?: return
        playPlaylistInternal(tracks, index)
    }

    /** Binds the playlist and starts the track at [startIndex] (already sanitized). */
    private fun playPlaylistInternal(tracks: List<MusicTrack>, startIndex: Int) {
        playlist = tracks
        currentIndex = startIndex
        consecutiveFailures = 0
        startCurrentTrack()
    }

    /** (Re)builds the MediaPlayer for [playlist]@[currentIndex]; callers hold the lock. */
    private fun startCurrentTrack() {
        val track = playlist.getOrNull(currentIndex) ?: return
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
                consecutiveFailures = 0
                notifyChanged()
            }
            // Track finished naturally: continue the playlist (TvPlaylistPolicy decides).
            player.setOnCompletionListener { onTrackCompleted() }
            player.setOnErrorListener { _, what, extra ->
                onTrackFailed(what, extra)
                true
            }
            player.setDataSource(streamUrlFor(track.id))
            player.prepareAsync()
        } catch (error: Exception) {
            // A dead stream must not crash the screen: treat it like a player error
            // (onTrackFailed releases the half-built player and decides skip-vs-park).
            isPlaying = false
            onTrackFailed(0, 0, error.message ?: error.javaClass.simpleName)
        }
        notifyChanged()
    }

    /**
     * Auto-advance on natural completion: move to the successor while the playlist has
     * one; when it is exhausted the transport goes fully idle ([stop]) — continuous
     * playback walks the list once, it does not loop.
     */
    @Synchronized
    private fun onTrackCompleted() {
        isPlaying = false
        val next = TvPlaylistPolicy.nextIndex(playlist.size, currentIndex)
        if (next != null) {
            currentIndex = next
            startCurrentTrack()
        } else {
            stopInternal()
        }
    }

    /**
     * Dead-stream handling (mobile parity): record the error, then skip to the successor
     * while [TvPlaylistPolicy.skipAfterFailure] allows it; once a full pass over the
     * playlist has failed (or there is no successor) park the transport on the error so
     * the screen can show «Не играет» instead of retrying forever.
     */
    @Synchronized
    private fun onTrackFailed(what: Int, extra: Int, message: String? = null) {
        isPlaying = false
        lastError = message ?: "Ошибка воспроизведения ($what/$extra)"
        releasePlayer()
        consecutiveFailures++
        val skip = TvPlaylistPolicy.skipAfterFailure(consecutiveFailures, playlist.size) &&
            TvPlaylistPolicy.nextIndex(playlist.size, currentIndex) != null
        if (skip) {
            currentIndex = currentIndex + 1
            startCurrentTrack() // clears lastError for the successor
        } else {
            notifyChanged()
        }
    }

    @Synchronized
    override fun stop() {
        stopInternal()
    }

    /**
     * Pauses rendering while keeping the position and the bound playlist (chat
     * mini-player control): no-op when nothing is playing. [resume] continues from
     * this point; [stop] still releases the player and forgets the playlist.
     */
    @Synchronized
    fun pause() {
        val player = mediaPlayer ?: return
        if (!isPlaying) return
        try {
            player.pause()
            isPlaying = false
            notifyChanged()
        } catch (_: IllegalStateException) {
            // The player slipped into a bad state: keep the flag honest, no crash.
            isPlaying = false
        }
    }

    /**
     * Continues a paused track: no-op while already playing or when no player is
     * bound (idle transport). A start on a not-yet-prepared player is rejected by
     * MediaPlayer and swallowed — the prepared callback starts the audio anyway.
     */
    @Synchronized
    fun resume() {
        val player = mediaPlayer ?: return
        if (isPlaying) return
        try {
            player.start()
            isPlaying = true
            notifyChanged()
        } catch (_: IllegalStateException) {
            isPlaying = false
        }
    }

    /** Play/Pause transport toggle for the chat mini player. */
    @Synchronized
    fun togglePlayPause() {
        if (isPlaying) pause() else resume()
    }

    /** Full idle: releases the player and forgets the playlist. Callers hold the lock. */
    private fun stopInternal() {
        releasePlayer()
        currentTrack = null
        playlist = emptyList()
        currentIndex = -1
        lastError = null
        isPlaying = false
        consecutiveFailures = 0
        notifyChanged()
    }

    /** Observer callback: invoked on the caller/listener thread (main in production). */
    private fun notifyChanged() {
        try {
            onTransportChanged?.invoke()
        } catch (_: Exception) {
            // A broken observer must never take the transport down.
        }
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
