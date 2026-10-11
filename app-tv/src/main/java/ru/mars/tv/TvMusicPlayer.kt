package ru.mars.tv

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import ru.mars.network.model.MusicTrack

/**
 * Process-wide (global) TV playback transport: a Kotlin object whose MediaPlayer survives
 * Activity recreation and screen changes (mobile MarsMusicPlayer parity). Music keeps
 * playing after the music screen closes — only an explicit [stop] (or a new [play]/
 * [playPlaylist], which replaces the stream) tears the player down. The controller talks
 * to it through the [TvMusicTransport] seam, so unit tests never touch this Android code.
 *
 * Continuous playback: [playPlaylist] binds a whole list; when a track completes the
 * transport asks [TvPlaybackModePolicy.advanceOnCompletion] where to go under
 * [repeatMode] — the default OFF reproduces the historical queue semantics exactly
 * (auto-advance to the successor, go idle once the list is exhausted, never loop). A
 * dead stream is skipped to the successor as well, until one full pass over the playlist
 * has failed (circuit breaker) — every navigation decision is delegated to the pure
 * [TvPlaylistPolicy]/[TvPlaybackModePolicy] objects, which is what the unit tests cover.
 *
 * Audio autonomy (decisions: pure [TvAudioAutonomyPolicy], JVM-tested):
 * - Audio focus is requested before every start/resume; a permanent LOSS stops the
 *   transport, a TRANSIENT loss pauses it and the returning GAIN resumes, CAN_DUCK only
 *   lowers the volume. A pause the USER made never auto-resumes. Focus integration needs
 *   an application [Context] — screens call [attachContext]; without one the transport
 *   plays exactly like before (best-effort, never crashes).
 * - Network streams hold a PARTIAL_WAKE_LOCK while a player is bound, but only when the
 *   WAKE_LOCK permission is actually granted (acquiring it otherwise throws
 *   SecurityException at start()).
 * - [positionMs] is crash-safe: invalid MediaPlayer states yield 0, never an exception.
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

    /**
     * Repeat mode of the bound playlist ([TvPlaybackModePolicy]). The default OFF
     * preserves the historical queue semantics: walk the list once, then go idle. The
     * mode survives [stop] as a user preference — the next playlist starts under it.
     */
    @Volatile
    var repeatMode: TvRepeatMode = TvRepeatMode.OFF
        private set

    private var mediaPlayer: MediaPlayer? = null

    /** Consecutive stream failures; circuit-breaker input for [TvPlaylistPolicy.skipAfterFailure]. */
    private var consecutiveFailures = 0

    // --- Audio autonomy state (focus + wake mode); decisions: TvAudioAutonomyPolicy ----

    @Volatile
    private var appContext: Context? = null

    /** Cached focus request: abandonAudioFocusRequest needs the SAME object that was granted. */
    private var focusRequest: AudioFocusRequest? = null

    /** True while a focus-induced pause waits for AUDIOFOCUS_GAIN to resume it. */
    private var pausedByFocusLoss = false

    /** True while playback continues at lowered volume after LOSS_TRANSIENT_CAN_DUCK. */
    private var duckedByFocus = false

    /** Ducking volume for LOSS_TRANSIENT_CAN_DUCK (notification-style interruptions). */
    private const val DUCKED_VOLUME = 0.2f

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onAudioFocusChanged(change)
    }

    private val audioManager: AudioManager?
        get() = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Screen observer (UI thread only): fired after every transport state transition —
     * play/stop, prepared, completion, auto-advance, stream error — so the music screen
     * can refresh its now-playing line and row highlight while audio state changes
     * asynchronously. Null when no screen is attached; the transport never blocks on
     * observers.
     */
    @Volatile
    var onTransportChanged: (() -> Unit)? = null

    /**
     * Binds the application [Context] used for audio-focus integration and the network
     * wake mode (idempotent; called from the music/chat screens' onCreate). Without a
     * context the transport still plays — focus and wake mode are simply skipped.
     */
    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

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

    /**
     * Cycles [repeatMode] OFF -> ALL -> ONE -> OFF ([TvPlaybackModePolicy.nextRepeatMode]),
     * notifies observers and returns the new mode. The mode governs what track
     * completion does next ([TvPlaybackModePolicy.advanceOnCompletion]).
     */
    @Synchronized
    fun cycleRepeatMode(): TvRepeatMode {
        repeatMode = TvPlaybackModePolicy.nextRepeatMode(repeatMode)
        notifyChanged()
        return repeatMode
    }

    /** (Re)builds the MediaPlayer for [playlist]@[currentIndex]; callers hold the lock. */
    private fun startCurrentTrack() {
        val track = playlist.getOrNull(currentIndex) ?: return
        releasePlayer()
        currentTrack = track
        lastError = null
        pausedByFocusLoss = false
        duckedByFocus = false
        // Audio focus gate ([TvAudioAutonomyPolicy.shouldStartPlayback]): an explicit
        // DENIAL means another app owns the output — starting would play over it, so the
        // track parks with an honest error. Without any focus integration (null verdict:
        // no context / odd TV stack) playback proceeds best-effort, as before.
        if (!TvAudioAutonomyPolicy.shouldStartPlayback(requestAudioFocus())) {
            isPlaying = false
            lastError = "Аудиофокус занят другим приложением"
            notifyChanged()
            return
        }
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            // Network stream: keep the CPU awake while rendering (permission-gated).
            applyWakeMode(player)
            player.setOnPreparedListener { prepared ->
                prepared.start()
                isPlaying = true
                consecutiveFailures = 0
                notifyChanged()
            }
            // Track finished naturally: the pure mode policy decides where to go next.
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
     * Auto-advance on natural completion, delegated to the pure
     * [TvPlaybackModePolicy.advanceOnCompletion]: ONE replays the same track, ALL wraps
     * around the list, and the default OFF walks the list once and then goes fully idle
     * ([stopInternal]) — exactly the historical [TvPlaylistPolicy.nextIndex] queue
     * semantics (parity pinned by the unit tests).
     */
    @Synchronized
    private fun onTrackCompleted() {
        isPlaying = false
        val advance = TvPlaybackModePolicy.advanceOnCompletion(playlist.size, currentIndex, repeatMode)
        val target = advance.targetIndex
        if (!advance.shouldStop && target != null) {
            currentIndex = target
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
     * bound (idle transport). Re-requests audio focus first — a resume after ANY
     * interruption must not play over whatever grabbed the output. A start on a
     * not-yet-prepared player is rejected by MediaPlayer and swallowed — the prepared
     * callback starts the audio anyway.
     */
    @Synchronized
    fun resume() {
        val player = mediaPlayer ?: return
        if (isPlaying) return
        // A resume after ANY interruption must not play over whoever grabbed the output:
        // an explicit focus DENIAL keeps the transport paused (the user's pause state is
        // preserved — nothing is started, nothing is flagged as focus-paused).
        if (!TvAudioAutonomyPolicy.shouldStartPlayback(requestAudioFocus())) return
        pausedByFocusLoss = false
        duckedByFocus = false
        try {
            player.setVolume(1f, 1f)
        } catch (_: IllegalStateException) {
            // Player in a transitional state: the duck flag is cleared regardless.
        }
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

    /**
     * Safe playback position in milliseconds: 0 while nothing is bound, 0 whenever
     * [MediaPlayer.getCurrentPosition] throws (device-dependent IllegalStateException in
     * error/transitional states) and never negative ([TvAudioAutonomyPolicy]) — progress
     * UIs must not crash the transport.
     */
    @Synchronized
    fun positionMs(): Int {
        val player = mediaPlayer ?: return 0
        return try {
            TvAudioAutonomyPolicy.sanitizePositionMs(player.currentPosition)
        } catch (_: Exception) {
            0
        }
    }

    // --- Audio focus wiring ------------------------------------------------------------

    /**
     * Focus callback -> pure decision -> transport reaction. LOSS stops for good (another
     * app owns the audio), TRANSIENT pauses and arms the auto-resume, CAN_DUCK lowers the
     * volume, GAIN undoes exactly what the loss took ([TvAudioAutonomyPolicy]).
     */
    @Synchronized
    private fun onAudioFocusChanged(focusChange: Int) {
        val action = TvAudioAutonomyPolicy.onFocusChange(
            focusChange = focusChange,
            isPlaying = isPlaying,
            pausedByFocusLoss = pausedByFocusLoss,
            ducked = duckedByFocus,
        )
        when (action) {
            TvAudioAutonomyPolicy.Action.NONE -> Unit
            TvAudioAutonomyPolicy.Action.PAUSE -> {
                pause()
                pausedByFocusLoss = true
            }
            TvAudioAutonomyPolicy.Action.DUCK -> {
                duckedByFocus = true
                try {
                    mediaPlayer?.setVolume(DUCKED_VOLUME, DUCKED_VOLUME)
                } catch (_: IllegalStateException) {
                    // Bad player state: the flag stays honest, resume restores volume.
                }
            }
            TvAudioAutonomyPolicy.Action.STOP -> {
                pausedByFocusLoss = false
                duckedByFocus = false
                stopInternal() // abandons focus as part of going idle
            }
            TvAudioAutonomyPolicy.Action.RESTORE -> {
                duckedByFocus = false
                try {
                    mediaPlayer?.setVolume(1f, 1f)
                } catch (_: IllegalStateException) {
                    // Player gone: nothing to restore the volume of.
                }
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume() // re-requests focus, notifies observers
                } else {
                    notifyChanged()
                }
            }
        }
    }

    /**
     * Requests AUDIOFOCUS_GAIN for media usage (listener on the main looper) and returns
     * the framework verdict (`AUDIOFOCUS_REQUEST_GRANTED`/`FAILED`), or `null` when focus
     * integration is unavailable (no attached context, no AudioManager, framework
     * exception). Callers gate the START on [TvAudioAutonomyPolicy.shouldStartPlayback]:
     * a definitive denial never plays over another app; `null` keeps the legacy
     * best-effort playback.
     */
    private fun requestAudioFocus(): Int? {
        val manager = audioManager ?: return null
        return try {
            val request = focusRequest ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } catch (_: Exception) {
            null
        }
    }

    /** Gives up the audio focus held for playback (called on every full idle). */
    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
            // Nothing to escalate: the system reclaims focus when we stop rendering.
        }
    }

    /**
     * Arms the network wake mode on [player] when allowed: the WAKE_LOCK permission must
     * be granted ([TvAudioAutonomyPolicy.shouldHoldWakeLock] — without it acquiring the
     * lock throws SecurityException at start()), and any framework failure degrades to
     * playing without the lock instead of crashing.
     */
    private fun applyWakeMode(player: MediaPlayer) {
        val context = appContext ?: return
        val granted = context.checkCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK) ==
            PackageManager.PERMISSION_GRANTED
        if (!TvAudioAutonomyPolicy.shouldHoldWakeLock(granted)) return
        try {
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        } catch (_: Exception) {
            // Wake mode is an optimization; playback continues without it.
        }
    }

    /** Full idle: releases the player, abandons focus and forgets the playlist. Callers hold the lock. */
    private fun stopInternal() {
        releasePlayer()
        abandonAudioFocus()
        pausedByFocusLoss = false
        duckedByFocus = false
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
