package ru.mars.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ru.mars.network.model.MusicTrack

/**
 * Process-scoped player; its engine survives Activity recreation and screen changes.
 *
 * ENGINE — Media3 ExoPlayer 1.2.1 (app-tv/TvMusicPlayer parity), NOT android.media.MediaPlayer:
 * MediaPlayer routes AAC streams through AudioFlinger's compressed-offload branch on several
 * real devices («Offload request, forcing to Direct Output») where output silently dies, and it
 * exposes no usable position while broken. ExoPlayer 1.2.1 has no offload route at all: it
 * software-decodes to PCM and renders through a regular AudioTrack over the audible mixer path.
 * The version is pinned — later Media3 releases added an opt-in audio-offload mode that must
 * not creep in here. Unlike MediaPlayer, ExoPlayer REQUIRES a Context to build: the process
 * attaches one via [attachContext] in `MarsApplication.onCreate` before any playback starts.
 *
 * Everything else is the historical contract: the pure [MusicQueueManager] owns order/shuffle/
 * repeat, the pure [PlaybackRetryPolicy] owns failure recovery (Media3 error codes are mapped
 * into its MediaPlayer-shaped code space via `media3CodeFor`), and [MarsMusicListener] consumers
 * (MusicActivity, ChatActivity mini player, MarsMusicService notification/MediaSession) see the
 * same callbacks as before. [currentPosition] is now LIVE: it reads the engine while one is
 * bound, so the 500 ms UI ticker advances between callbacks instead of showing a stale snapshot.
 *
 * Stream URL: `{BASE_URL}/api/stream/{id}` (MusicRepository.getStreamUrl parity) plus the manual
 * quality profile of [StreamUrlPolicy] — [streamQuality] (`original`/`standard`/`economy`),
 * DEFAULT omits the query parameter entirely (backward-compatible server default). Switching
 * quality mid-track rebinds the stream from the current position without touching the queue.
 */
@OptIn(UnstableApi::class)
object MarsMusicPlayer {
    interface MarsMusicListener {
        fun onStateChanged(track: MusicTrack?, isPlaying: Boolean)
        fun onProgress(currentMs: Int, totalMs: Int)
    }

    private const val BASE_URL = "https://marscore.ru/music"

    /**
     * The Media3 engine (built per track — see [startCurrentTrack]). `null` while idle;
     * released on stop/advance/skip. Deliberately NOT an android.media.MediaPlayer: that
     * engine is the offload-silence trap the class KDoc describes (pinned by
     * MarsMusicPlayerNoMediaPlayerTest).
     */
    private var engine: ExoPlayer? = null

    /** Application context for [ExoPlayer.Builder]; attached once per process (never an Activity). */
    @Volatile
    private var appContext: Context? = null

    private val listeners = LinkedHashSet<MarsMusicListener>()

    /** Session audio effects bound to the current engine's audio session. */
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

    /** Last position snapshot for reads while no engine is bound (paused-released/retrying). */
    @Volatile
    private var lastKnownPositionMs = 0

    /** App volume applied to every (re)built engine; [setVolume] survives track changes. */
    @Volatile
    private var userVolume = 1f

    @Volatile var currentTrack: MusicTrack? = null
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    /**
     * Live playback position in ms: reads the engine while one is bound (crash-safe — any
     * anomaly falls back to [lastKnownPositionMs]), so progress consumers (UI ticker,
     * MediaSession) see real-time positions between [MarsMusicListener.onProgress] events.
     */
    val currentPosition: Int
        get() {
            val player = engine ?: return lastKnownPositionMs
            return runCatching {
                player.currentPosition.takeIf { it != C.TIME_UNSET && it >= 0 }?.toInt()
            }.getOrNull() ?: lastKnownPositionMs
        }

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

    /**
     * Manual stream-quality profile ([StreamQuality]) used for every stream URL built from
     * now on. Default [StreamQuality.DEFAULT] keeps the historical query-free URL — the
     * server picks its own default; no forced downgrade is ever implied by this client.
     */
    @Volatile var streamQuality: StreamQuality = StreamQuality.DEFAULT
        private set

    /**
     * Binds the application context ExoPlayer needs to build. Idempotent; first attach wins.
     * Called from `MarsApplication.onCreate` — the process-wide entry point every playback
     * path (music screen, chat mini player, notification service) starts after.
     */
    fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Stream URL for [trackId] under the current [streamQuality]
     * (`{BASE_URL}/api/stream/{id}[?quality=…]` — MusicRepository.getStreamUrl parity,
     * built by the pure [StreamUrlPolicy]).
     */
    fun streamUrlFor(trackId: String): String =
        StreamUrlPolicy.streamUrl(BASE_URL, trackId, streamQuality)

    /**
     * Switches the manual quality profile. Takes effect on the next track start when idle;
     * while a track is bound the stream is rebound from the CURRENT position, preserving
     * play/pause state, queue and retry budget — a quality switch never loses the song.
     */
    @Synchronized
    fun setStreamQuality(quality: StreamQuality) {
        if (streamQuality == quality) return
        streamQuality = quality
        if (engine == null) return // applies on the next start / pending retry
        val resume = currentPosition
        val wasPlaying = isPlaying
        startCurrentTrack(isRetry = true, resumeOverrideMs = resume, autoPlay = wasPlaying)
    }

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
     * Builds a fresh engine and starts the current queue track. [isRetry] marks a backoff
     * retry of the SAME track scheduled by [handlePlaybackFailure]: it keeps the per-track
     * retry budget and resumes from [retryResumeMs] instead of restarting the song.
     * [resumeOverrideMs] (quality rebind) and [autoPlay] (stay paused across a rebind)
     * take precedence over the retry-resume defaults.
     */
    private fun startCurrentTrack(
        isRetry: Boolean = false,
        resumeOverrideMs: Int? = null,
        autoPlay: Boolean = true,
    ) {
        val track = queueManager.currentTrack ?: return
        cancelPendingRetry()
        if (!isRetry) {
            retryPolicy.onTrackSelected()
            retryResumeMs = 0
        }
        val startMs = (resumeOverrideMs ?: retryResumeMs).coerceAtLeast(0)
        retryResumeMs = 0
        lastKnownPositionMs = 0
        duration = 0
        setPlaying(false)
        releaseEngine()
        val context = appContext
        if (context == null) {
            // Unlike MediaPlayer, ExoPlayer cannot be built without a Context.
            // MarsApplication.onCreate attaches one before any UI exists; reaching this
            // branch means the process started abnormally — park honestly, never crash.
            notifyState()
            return
        }
        try {
            // Media3 1.2.1 DefaultRenderersFactory has no audio-offload route: the sink
            // always renders decoded PCM through a regular AudioTrack (the mixer path).
            // handleAudioFocus=false: the historical mobile transport never managed audio
            // focus, and this swap must not change observable playback behavior.
            val player = ExoPlayer.Builder(
                context,
                DefaultMediaSourceFactory(context), // https stream via DefaultHttpDataSource
            ).build()
            engine = player
            player.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ false,
            )
            player.volume = userVolume
            // Queue order/shuffle/repeat lives in MusicQueueManager (one item at a time),
            // so the engine's own playlist logic must stay out of it.
            player.repeatMode = Player.REPEAT_MODE_OFF
            // Network stream: keep the CPU awake while rendering (permission-gated).
            applyWakeMode(player)
            player.addListener(engineListener)
            // Start position rides on setMediaItem: the retry/quality rebind resumes
            // mid-track without a second seek once READY arrives.
            player.setMediaItem(MediaItem.fromUri(streamUrlFor(track.id)), startMs.toLong())
            // Armed BEFORE prepare(): STATE_READY then means rendering actually started
            // (the old onPrepared -> start() pair collapses into this one flag).
            player.playWhenReady = autoPlay
            player.prepare()
            // Effects attach to the fresh audio session so the saved preset survives track
            // changes (ExoPlayer generates the session id at build time).
            attachAudioFx(player.audioSessionId)
        } catch (error: Exception) {
            // Engine build/bind threw: map onto the same decision path as a stream error.
            releaseEngine()
            handlePlaybackFailure(track.id, PlaybackRetryPolicy.codeFor(error), 0)
            return
        }
        notifyState()
    }

    /** Media3 engine events; callbacks arrive on the looper the engine was built on (main). */
    private val engineListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onTrackReady()
                // Track finished: advance through the queue manager (honors shuffle/repeat).
                Player.STATE_ENDED -> onTrackEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val failedId = currentTrack?.id ?: return
            handlePlaybackFailure(failedId, PlaybackRetryPolicy.media3CodeFor(error.errorCode), 0)
        }
    }

    /**
     * STATE_READY under playWhenReady — the onPrepared+start() parity point: the stream
     * proved reachable (failure history resets), duration is known and the fresh audio
     * session gets the persisted sound preset.
     */
    @Synchronized
    private fun onTrackReady() {
        val player = engine ?: return
        retryPolicy.onPlaybackReady()
        duration = runCatching { player.duration }
            .getOrNull()
            ?.takeIf { it != C.TIME_UNSET }
            ?.toInt()
            ?.coerceAtLeast(0) ?: 0
        setPlaying(player.playWhenReady)
        attachAudioFx(player.audioSessionId)
        notifyState()
        notifyProgress()
    }

    @Synchronized
    private fun onTrackEnded() {
        setPlaying(false)
        next()
    }

    /**
     * Arms the network wake mode on [player] when allowed: the WAKE_LOCK permission must be
     * granted (without it setWakeMode risks a SecurityException once rendering starts), and
     * any framework failure degrades to playing without the lock. ExoPlayer manages the lock
     * lifetime itself: held while the engine loads/renders, released on stop/release.
     */
    private fun applyWakeMode(player: ExoPlayer) {
        val context = appContext ?: return
        val granted = context.checkCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        runCatching { player.setWakeMode(PowerManager.PARTIAL_WAKE_LOCK) }
    }

    /** Tears the engine down (listener first, so a dying engine cannot call back). */
    private fun releaseEngine() {
        val player = engine ?: return
        engine = null
        runCatching {
            player.removeListener(engineListener)
            player.release()
        }
    }

    /**
     * Updates the sound settings (persisted by AudioFxSettings in the UI layer) and applies
     * them to the live session immediately; a fresh engine picks them up via
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
     * Attaches Equalizer + BassBoost to a new engine audio session. Construction throws
     * on unsupported hardware/runtime failures — degrade silently, music plays without effects.
     */
    private fun attachAudioFx(sessionId: Int) {
        releaseAudioFx()
        if (sessionId == 0) return // AudioEffect.ERROR_BAD_VALUE: invalid session
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        applyAudioFx(audioFxState.preset, audioFxState.bassBoost.toShort())
    }

    /** Releases session effects alongside the engine they are bound to. */
    private fun releaseAudioFx() {
        equalizer?.let { effect -> runCatching { effect.release() } }
        bassBoost?.let { effect -> runCatching { effect.release() } }
        equalizer = null
        bassBoost = null
    }

    @Synchronized fun togglePlay() { if (isPlaying) pause() else play() }

    @Synchronized fun play() {
        val player = engine
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
        runCatching {
            // After a natural end (repeat off, queue exhausted mid-session) a fresh press
            // restarts the song; MediaPlayer.start() did the same from PlaybackCompleted.
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = true // renders now, or the moment buffering finishes
            isPlaying = true
            notifyState()
        }
    }

    @Synchronized fun pause() {
        val player = engine ?: return
        runCatching {
            lastKnownPositionMs = currentPosition
            player.playWhenReady = false // position kept, no engine exception path
            isPlaying = false
            notifyState()
        }
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
        releaseEngine()
        releaseAudioFx()
        setPlaying(false)
        lastKnownPositionMs = 0
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
     * WITHOUT starting audio: any live engine is released, [startIndex] becomes the current
     * track and playback stays stopped — the user presses play to join the session, so the
     * phone never starts sounding on its own because a remote device did. The subsequent
     * [play] prepares the adopted track from scratch. Empty queue or out-of-range index is
     * a no-op (same guard as [playTrack]); local queue ownership is decided by the caller.
     */
    @Synchronized
    fun adoptQueue(queue: List<MusicTrack>, startIndex: Int) {
        if (queue.isEmpty() || startIndex !in queue.indices) return
        cancelPendingRetry()
        releaseEngine()
        releaseAudioFx()
        setPlaying(false)
        lastKnownPositionMs = 0
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
        runCatching {
            engine?.seekTo(target.toLong()) // buffered or pending — engine owns the clamp
            lastKnownPositionMs = target
            notifyProgress()
        }
    }

    /** App volume 0..1; persists across track changes (every rebuilt engine gets it). */
    @Synchronized fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        userVolume = clamped
        runCatching { engine?.volume = clamped }
    }

    @Synchronized fun registerListener(listener: MarsMusicListener) {
        listeners.add(listener)
        listener.onStateChanged(currentTrack, isPlaying)
        listener.onProgress(currentPosition, duration)
    }

    @Synchronized fun unregisterListener(listener: MarsMusicListener) { listeners.remove(listener) }

    private fun notifyState() { listeners.toList().forEach { it.onStateChanged(currentTrack, isPlaying) } }
    private fun notifyProgress() {
        val position = currentPosition // live engine read, crash-safe
        lastKnownPositionMs = position
        listeners.toList().forEach { it.onProgress(position, duration) }
    }

    /**
     * Queue exhausted (repeat off) or an unwinnable failure streak: release the engine and
     * finish the queue session so the stale track cannot resurface as current; the queue
     * list itself is kept for the UI, and the next [enqueue] resumes playback.
     */
    private fun stopPlayback() {
        cancelPendingRetry()
        releaseEngine()
        releaseAudioFx()
        setPlaying(false)
        lastKnownPositionMs = 0
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
        // A broken engine may report a bogus position; the crash-safe [currentPosition]
        // getter falls back to the last notified progress so a mid-track network drop can
        // resume instead of restarting.
        val position = currentPosition
        when (val decision = retryPolicy.decide(what, extra, queueManager.size, position, duration)) {
            is RetryDecision.RetryTrack -> {
                // Release the broken engine now; the retry builds a fresh one.
                retryResumeMs = decision.resumeFromMs
                releaseEngine()
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
