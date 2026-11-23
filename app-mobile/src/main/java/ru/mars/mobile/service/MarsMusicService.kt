package ru.mars.mobile.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ru.mars.mobile.MarsMusicPlayer
import ru.mars.mobile.MusicActivity
import ru.mars.mobile.R
import ru.mars.mobile.service.MusicNotificationPolicy.ActionType
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Foreground service that keeps [MarsMusicPlayer] mirrored in the notification shade.
 *
 * The service does NOT own playback — the process-scoped player singleton does. The service
 * registers itself as a [MarsMusicPlayer.MarsMusicListener] and rebuilds the notification
 * (plus a [MediaSessionCompat] so Android 13+ renders proper media controls) on every state
 * change. Transport buttons deliver their [MusicNotificationPolicy] action back into
 * [onStartCommand], which routes it to the player. When playback dies (queue cleared /
 * track finished with nothing left) the service removes the notification and stops itself.
 *
 * Started by [MusicActivity] via [start] when a track begins; declared in the manifest with
 * `foregroundServiceType="mediaPlayback"` (required by Android 14 / targetSdk 34).
 */
class MarsMusicService : Service(), MarsMusicPlayer.MarsMusicListener {

    /** True once [ServiceCompat.startForeground] succeeded; guards the FGS promise. */
    private var startedForeground = false
    private var mediaSession: MediaSessionCompat? = null

    /**
     * Which cover the shade currently shows (the policy's artwork key, i.e. the trimmed
     * thumbnail URL). Null means no cover yet; it is compared against the incoming track so
     * a state tick for the same track never re-downloads the art.
     */
    private var artworkKey: String? = null

    /**
     * Single background thread for cover fetches. One is enough — the shade shows one track
     * at a time, and a queue of one keeps a fast skip from spawning parallel downloads.
     */
    private val artworkExecutor: Executor = Executors.newSingleThreadExecutor()

    /**
     * Last position mirrored into the session. The player fires progress far more often
     * than the shade can redraw, so the session is only rewritten on real jumps (a seek or
     * a resume) — see [MusicNotificationPolicy.shouldRefreshSessionPosition].
     */
    private var sessionPositionMs: Int = 0

    /** The seekable player controls Android's shade scrubber and the headset buttons. */
    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { MarsMusicPlayer.play() }
        override fun onPause() { MarsMusicPlayer.pause() }
        override fun onSkipToNext() { MarsMusicPlayer.next() }
        override fun onSkipToPrevious() { MarsMusicPlayer.previous() }
        override fun onStop() {
            MarsMusicPlayer.clearQueue()
            stopPlaybackService()
        }

        /** Shade scrubber: an absolute position chosen by the system. */
        override fun onSeekTo(pos: Long) {
            MarsMusicPlayer.seekTo(
                MusicNotificationPolicy.clampPosition(pos, MarsMusicPlayer.duration)
            )
            refreshAfterSeek()
        }

        /** Rewind button / headset "back": one policy step, never a magic number inline. */
        override fun onRewind() {
            MarsMusicPlayer.seekTo(
                MusicNotificationPolicy.seekTarget(
                    MarsMusicPlayer.currentPosition,
                    -MusicNotificationPolicy.SEEK_STEP_MS,
                    MarsMusicPlayer.duration
                )
            )
            refreshAfterSeek()
        }

        /** Fast-forward button / headset "forward": one policy step. */
        override fun onFastForward() {
            MarsMusicPlayer.seekTo(
                MusicNotificationPolicy.seekTarget(
                    MarsMusicPlayer.currentPosition,
                    MusicNotificationPolicy.SEEK_STEP_MS,
                    MarsMusicPlayer.duration
                )
            )
            refreshAfterSeek()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(sessionCallback)
            isActive = true
        }
        // Last step: registering fires an immediate onStateChanged for the current track.
        MarsMusicPlayer.registerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the startForegroundService() promise first, whatever the action is —
        // Android kills the process if startForeground is not called within ~5 s.
        promoteToForeground()
        when (MusicNotificationPolicy.typeOf(intent?.action)) {
            ActionType.PLAY -> MarsMusicPlayer.play()
            ActionType.PAUSE -> MarsMusicPlayer.pause()
            ActionType.NEXT -> MarsMusicPlayer.next()
            ActionType.PREV -> MarsMusicPlayer.previous()
            ActionType.STOP -> MarsMusicPlayer.clearQueue()
            null -> Unit // plain start: just mirror the current player state
        }
        if (!MusicNotificationPolicy.isPlaybackAlive(
                MarsMusicPlayer.currentTrack != null, MarsMusicPlayer.isPlaying)
        ) {
            stopPlaybackService()
        }
        // No sticky restart: a killed service must not resurrect an empty notification.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents only kills playback when it is already paused;
        // a playing foreground service keeps running (that is the whole point of it).
        if (!MarsMusicPlayer.isPlaying) stopPlaybackService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MarsMusicPlayer.unregisterListener(this)
        mediaSession?.release()
        mediaSession = null
        // Releases the cover-fetch thread; without it a stopped service keeps one alive.
        (artworkExecutor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        artworkKey = null
        startedForeground = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- player listener

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) {
        updateSession(track, isPlaying)
        // Before the first onStartCommand there is no FGS promise to keep: registerListener
        // fires this callback in onCreate, stopping there would race startForegroundService.
        if (!startedForeground) return
        if (MusicNotificationPolicy.isPlaybackAlive(track != null, isPlaying)) {
            updateNotification(track, isPlaying)
            loadArtworkIfNeeded(track)
        } else {
            stopPlaybackService()
        }
    }

    /**
     * Fetches the shade cover off the main thread, once per track. When it lands the
     * notification and the session are rebuilt so both the shade and the lock screen pick
     * up the bitmap; a fetch for a track the user already skipped past is discarded.
     */
    private fun loadArtworkIfNeeded(track: MusicTrack?) {
        if (!MusicNotificationPolicy.needsArtworkLoad(artworkKey, track?.thumbnail)) return
        val key = MusicNotificationPolicy.artworkKey(track?.thumbnail) ?: return
        MusicArtwork.load(key, artworkExecutor) { readyKey ->
            if (readyKey != MusicNotificationPolicy.artworkKey(
                    MarsMusicPlayer.currentTrack?.thumbnail
                )
            ) {
                return@load // stale: the track changed while the cover was downloading
            }
            artworkKey = readyKey
            val current = MarsMusicPlayer.currentTrack ?: return@load
            val playing = MarsMusicPlayer.isPlaying
            updateSession(current, playing)
            if (startedForeground) updateNotification(current, playing)
        }
    }

    /**
     * The transport controls live in the session, so progress never rebuilds the
     * notification. It does keep the session's position honest — but only on real jumps:
     * the player ticks far faster than the shade can redraw, and every rewrite is a binder
     * round-trip, so plain ticks are skipped.
     */
    override fun onProgress(currentMs: Int, totalMs: Int) {
        val session = mediaSession ?: return
        if (!MusicNotificationPolicy.shouldRefreshSessionPosition(sessionPositionMs, currentMs)) {
            return
        }
        sessionPositionMs = currentMs
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(sessionActions())
                .setState(
                    if (MarsMusicPlayer.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    currentMs.toLong(),
                    1f
                )
                .build()
        )
    }

    /** Action mask advertised on the session; seeking is included on purpose. */
    private fun sessionActions(): Long =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP or
            // Seeking rides on the session, not on extra notification buttons:
            // a sixth action would be silently dropped from the shade row.
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD

    // ---------------------------------------------------------------- notification

    private fun promoteToForeground() {
        val notification = buildNotification(MarsMusicPlayer.currentTrack, MarsMusicPlayer.isPlaying)
        // ServiceCompat applies FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK on API 34+ and
        // degrades to plain startForeground below — minSdk 28, so one call fits all.
        ServiceCompat.startForeground(
            this,
            MusicNotificationPolicy.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        startedForeground = true
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(track: MusicTrack?, isPlaying: Boolean) {
        if (!canPostNotifications()) return // Android 13+ without POST_NOTIFICATIONS granted
        try {
            NotificationManagerCompat.from(this)
                .notify(MusicNotificationPolicy.NOTIFICATION_ID, buildNotification(track, isPlaying))
        } catch (_: SecurityException) {
            // Best effort when notification permission revoked at runtime
        }
    }

    private fun stopPlaybackService() {
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(track: MusicTrack?, isPlaying: Boolean): Notification {
        // CLEAR_TOP|SINGLE_TOP|NEW_TASK from one place: tapping the shade reuses the open
        // player instead of stacking duplicate activities behind it.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicActivity::class.java)
                .addFlags(MusicNotificationPolicy.TAP_INTENT_FLAGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, MusicNotificationPolicy.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(track?.title ?: "Марс Музыка")
            .setContentText(track?.artist ?: "Mars Audio")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Cover in the shade: the already-fetched bitmap if this track has one, so the very
        // first build is not blocked on the network.
        MusicNotificationPolicy.artworkKey(track?.thumbnail)?.let { key ->
            MusicArtwork.cached(key)?.let { builder.setLargeIcon(it) }
        }

        val actions = MusicNotificationPolicy.actions(isPlaying, track != null)
        actions.forEach { mediaAction ->
            builder.addAction(
                iconFor(mediaAction.type),
                mediaAction.contentDescription,
                actionIntent(mediaAction.type, mediaAction.action)
            )
        }

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(
                *MusicNotificationPolicy.compactActionIndices(isPlaying, track != null)
            )
        mediaSession?.sessionToken?.let { style.setMediaSession(it) }
        return builder.setStyle(style).build()
    }

    private fun actionIntent(type: ActionType, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            type.ordinal,
            Intent(this, MarsMusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun iconFor(type: ActionType): Int = when (type) {
        ActionType.PLAY -> R.drawable.ic_play
        ActionType.PAUSE -> R.drawable.ic_pause
        ActionType.NEXT -> R.drawable.ic_skip_next
        ActionType.PREV -> R.drawable.ic_skip_prev
        ActionType.STOP -> R.drawable.ic_stop
    }

    // ---------------------------------------------------------------- media session

    /**
     * Mirrors player state into the session so Android 13+ builds its shade media controls
     * (and lock-screen/Bluetooth routing) from real metadata and playback state.
     */
    private fun updateSession(track: MusicTrack?, isPlaying: Boolean) {
        val session = mediaSession ?: return
        val state = when {
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            track != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(sessionActions())
                .setState(state, MarsMusicPlayer.currentPosition.toLong(), 1f)
                .build()
        )
        if (track != null) {
            sessionPositionMs = MarsMusicPlayer.currentPosition
            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.thumbnail)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, MarsMusicPlayer.duration.toLong())
            // Decoded cover, when it is already in the cache: lock screen and Bluetooth
            // displays read the bitmap from here, not from the notification builder.
            MusicNotificationPolicy.artworkKey(track.thumbnail)?.let { key ->
                MusicArtwork.cached(key)?.let { art ->
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                }
            }
            session.setMetadata(builder.build())
        }
    }

    /**
     * Rebuilds notification + session after a seek so the shade scrubber and lock screen
     * show the new position immediately instead of waiting for the next state tick.
     */
    private fun refreshAfterSeek() {
        val track = MarsMusicPlayer.currentTrack
        val playing = MarsMusicPlayer.isPlaying
        updateSession(track, playing)
        if (startedForeground && MusicNotificationPolicy.isPlaybackAlive(track != null, playing)) {
            updateNotification(track, playing)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MusicNotificationPolicy.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                MusicNotificationPolicy.CHANNEL_ID,
                MusicNotificationPolicy.CHANNEL_NAME,
                // Low: the channel must not beep on every track change or button press.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением Марс Музыки"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SESSION_TAG = "MarsMusicSession"

        /**
         * Promotes the service to foreground (creating it if needed). Callers must be in a
         * visible app state — Android 12+ forbids starting FGS from the background.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MarsMusicService::class.java)
            )
        }

        /** Explicit teardown; normally the service stops itself when playback dies. */
        fun stop(context: Context) {
            context.stopService(Intent(context, MarsMusicService::class.java))
        }
    }
}
