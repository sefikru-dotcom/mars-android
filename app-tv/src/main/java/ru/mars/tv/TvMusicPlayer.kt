package ru.mars.tv

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ru.mars.network.model.MusicTrack

/**
 * Process-wide (global) TV playback transport: a Kotlin object whose Media3 [ExoPlayer]
 * survives Activity recreation and screen changes (mobile MarsMusicPlayer parity). Music
 * keeps playing after the music screen closes — only an explicit [stop] (or a new [play]/
 * [playPlaylist], which replaces the stream) tears the player down. The controller talks
 * to it through the [TvMusicTransport] seam, so unit tests never touch this Android code.
 *
 * ENGINE — Media3 ExoPlayer (software decode to PCM), NOT android.media.MediaPlayer:
 * live diagnostics on Mi TV (Android 9, MSSP1) showed MediaPlayer routing the AAC stream
 * into AudioFlinger's compressed-offload branch («Offload request, forcing to Direct
 * Output», flags #10 COMPRESS_OFFLOAD, «Frames written» frozen) where the output to
 * Speaker/Bluetooth goes silent while mixer-path system sounds stay audible. ExoPlayer
 * 1.2.1 has no offload route at all: it decodes to PCM and renders through a regular
 * AudioTrack over the same mixer the audible system sounds use. The version is pinned —
 * later Media3 releases added an opt-in audio-offload mode that must not creep in here.
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
 *   an application [Context] — screens call [attachContext]. ExoPlayer additionally
 *   REQUIRES a context to be built; without one the transport parks with an honest
 *   [lastError] (every production screen attaches in onCreate before playback starts).
 * - Network streams hold a PARTIAL_WAKE_LOCK while a player is bound, but only when the
 *   WAKE_LOCK permission is actually granted ([ExoPlayer.setWakeMode] otherwise risks a
 *   SecurityException once rendering starts).
 * - [positionMs] is crash-safe: any engine anomaly yields 0, never an exception.
 *
 * Stream URL contract: `GET {MUSIC_BASE_URL}/api/stream/{id}` — the same wire address the
 * mobile client builds via StreamUrlPolicy. A dead/unreachable stream is recorded in
 * [lastError] and never crashes the app.
 *
 * THREADING: the engine is built on the calling thread's looper (main in production —
 * every call site is a UI handler or the controller's UI-thread dispatch) and its
 * [Player.Listener] callbacks arrive on that same looper, so the [Synchronized] transport
 * methods and the listener never race off-thread; no thread ever sleeps waiting on the engine.
 */
@OptIn(UnstableApi::class)
object TvMusicPlayer : TvMusicTransport {

    /** Track currently bound to the transport (null when idle/stopped). */
    @Volatile
    var currentTrack: MusicTrack? = null
        private set

    /** True while the ExoPlayer engine is actually rendering audio. */
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

    /**
     * The Media3 engine (created per track — see [startCurrentTrack]). `null` while idle;
     * released on stop/auto-advance/skip. ExoPlayer replaces the old MediaPlayer field —
     * deliberately NOT an android.media.MediaPlayer: that engine is what sank the AAC
     * stream into the silent compressed-offload branch on this TV (class KDoc).
     */
    private var player: ExoPlayer? = null

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

    /**
     * The engine callback, mapped onto the SAME transport transitions MediaPlayer had:
     * STATE_READY == onPrepared+start (playWhenReady is armed before prepare(), so READY
     * means rendering began), STATE_ENDED == onCompletion (auto-advance under
     * [repeatMode]), onPlayerError == onError (record + skip-or-park). One shared
     * listener, detached from each engine before it is released — a dying player can
     * never notify over its successor.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onTrackReady()
                Player.STATE_ENDED -> onTrackCompleted()
                else -> Unit // IDLE/BUFFERING: nothing to transition yet
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // errorCode is Media3's stable classification (I/O, parsing, decoder…);
            // the message is what the screen may surface verbatim.
            onTrackFailed(error.errorCode, 0, error.message ?: error.errorCodeName)
        }
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
     * Binds the application [Context] used for audio-focus integration, the network wake
     * mode and the engine itself (idempotent; called from the music/chat screens'
     * onCreate). Without a context the transport cannot build the ExoPlayer and parks
     * with an honest [lastError] — focus and wake mode are skipped as before.
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

    /** (Re)builds the ExoPlayer for [playlist]@[currentIndex]; callers hold the lock. */
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
        val context = appContext
        if (context == null) {
            // Unlike MediaPlayer, ExoPlayer cannot be built without a Context. Every
            // production screen attaches one in onCreate before playback starts; if it
            // is still missing, park honestly instead of crashing or faking playback.
            isPlaying = false
            lastError = "Плеер не инициализирован — перезапустите приложение"
            notifyChanged()
            return
        }
        try {
            // Media3 1.2.1 DefaultRenderersFactory has no audio-offload route: the sink
            // always renders decoded PCM through a regular AudioTrack (the mixer path
            // that IS audible on this TV) — this is the fix for the offload silence.
            // handleAudioFocus=false: focus stays owned by this transport verbatim
            // (TvAudioAutonomyPolicy), ExoPlayer's built-in focus handling would apply
            // its own divergent duck/pause policy.
            val engine = ExoPlayer.Builder(
                context,
                DefaultMediaSourceFactory(context), // https stream via DefaultHttpDataSource
            ).build()
            player = engine
            engine.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ false,
            )
            // Network stream: keep the CPU awake while rendering (permission-gated).
            applyWakeMode(engine)
            engine.addListener(playerListener)
            engine.setMediaItem(MediaItem.fromUri(streamUrlFor(track.id)))
            // Armed BEFORE prepare(): STATE_READY then means rendering actually started
            // (the old onPrepared -> start() pair collapses into this one flag).
            engine.playWhenReady = true
            engine.prepare()
        } catch (error: Exception) {
            // A dead stream or a broken engine build must not crash the screen: treat it
            // like a player error (onTrackFailed releases the half-built engine and
            // decides skip-vs-park).
            isPlaying = false
            onTrackFailed(0, 0, error.message ?: error.javaClass.simpleName)
        }
        notifyChanged()
    }

    /**
     * STATE_READY under playWhenReady — the onPrepared+start() parity point: rendering
     * has begun, the stream proved reachable, so the failure streak resets.
     */
    @Synchronized
    private fun onTrackReady() {
        isPlaying = true
        consecutiveFailures = 0
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
        val engine = player ?: return
        if (!isPlaying) return
        try {
            engine.pause() // playWhenReady=false — position kept, no engine exception path
            isPlaying = false
            notifyChanged()
        } catch (_: Exception) {
            // The engine slipped into a bad state: keep the flag honest, no crash.
            isPlaying = false
        }
    }

    /**
     * Continues a paused track: no-op while already playing or when no player is
     * bound (idle transport). Re-requests audio focus first — a resume after ANY
     * interruption must not play over whatever grabbed the output; a definitive
     * DENIAL keeps the transport paused (the user's pause state is preserved).
     */
    @Synchronized
    fun resume() {
        val engine = player ?: return
        if (isPlaying) return
        // A resume after ANY interruption must not play over whoever grabbed the output:
        // an explicit focus DENIAL keeps the transport paused (the user's pause state is
        // preserved — nothing is started, nothing is flagged as focus-paused).
        if (!TvAudioAutonomyPolicy.shouldStartPlayback(requestAudioFocus())) return
        pausedByFocusLoss = false
        duckedByFocus = false
        try {
            engine.volume = 1f
        } catch (_: Exception) {
            // Engine in a transitional state: the duck flag is cleared regardless.
        }
        try {
            engine.playWhenReady = true // renders now, or the moment buffering finishes
            isPlaying = true
            notifyChanged()
        } catch (_: Exception) {
            isPlaying = false
        }
    }

    /** Play/Pause transport toggle — the chat mini player and the music screen header share it. */
    @Synchronized
    fun togglePlayPause() {
        if (isPlaying) pause() else resume()
    }

    /**
     * Safe playback position in milliseconds: 0 while nothing is bound, 0 whenever the
     * engine position is unreadable (any anomaly is caught) and never negative
     * ([TvAudioAutonomyPolicy.sanitizePositionMs]) — progress UIs must not crash the
     * transport.
     */
    @Synchronized
    fun positionMs(): Int {
        val engine = player ?: return 0
        return try {
            val raw = engine.currentPosition.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            TvAudioAutonomyPolicy.sanitizePositionMs(raw)
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
                    player?.volume = DUCKED_VOLUME
                } catch (_: Exception) {
                    // Bad engine state: the flag stays honest, resume restores volume.
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
                    player?.volume = 1f
                } catch (_: Exception) {
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
     * Arms the network wake mode on [engine] when allowed: the WAKE_LOCK permission must
     * be granted ([TvAudioAutonomyPolicy.shouldHoldWakeLock] — without it the lock risks
     * a SecurityException once rendering starts), and any framework failure degrades to
     * playing without the lock instead of crashing. ExoPlayer manages the lock lifetime
     * itself: held while the engine loads/renders, released on stop/release.
     */
    private fun applyWakeMode(engine: ExoPlayer) {
        val context = appContext ?: return
        val granted = context.checkCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK) ==
            PackageManager.PERMISSION_GRANTED
        if (!TvAudioAutonomyPolicy.shouldHoldWakeLock(granted)) return
        try {
            engine.setWakeMode(PowerManager.PARTIAL_WAKE_LOCK)
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

    /**
     * Detaches the shared listener and releases the engine. ExoPlayer.release() is
     * state-safe (no MediaPlayer-style IllegalStateException window); the catch is
     * belt-and-braces so teardown can never crash the transport.
     */
    private fun releasePlayer() {
        player?.let { engine ->
            try {
                engine.removeListener(playerListener)
                engine.release()
            } catch (_: Exception) {
                // A dying engine must not take the transport down; the field is dropped below.
            }
        }
        player = null
        isPlaying = false
    }

    /** Stream URL: `{base}/api/stream/{id}` — same wire address as the mobile client. */
    fun streamUrlFor(trackId: String): String =
        "${RepositoryTvMusicGateway.MUSIC_BASE_URL}/api/stream/$trackId"
}
