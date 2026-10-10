package ru.mars.mobile.service

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

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { MarsMusicPlayer.play() }
        override fun onPause() { MarsMusicPlayer.pause() }
        override fun onSkipToNext() { MarsMusicPlayer.next() }
        override fun onSkipToPrevious() { MarsMusicPlayer.previous() }
        override fun onStop() {
            MarsMusicPlayer.clearQueue()
            stopPlaybackService()
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
        } else {
            stopPlaybackService()
        }
    }

    /** The shade shows no scrubber; progress ticks must not spam the NotificationManager. */
    override fun onProgress(currentMs: Int, totalMs: Int) = Unit

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

    private fun updateNotification(track: MusicTrack?, isPlaying: Boolean) {
        if (!canPostNotifications()) return // Android 13+ without POST_NOTIFICATIONS granted
        NotificationManagerCompat.from(this)
            .notify(MusicNotificationPolicy.NOTIFICATION_ID, buildNotification(track, isPlaying))
    }

    private fun stopPlaybackService() {
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(track: MusicTrack?, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicActivity::class.java),
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
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, MarsMusicPlayer.currentPosition.toLong(), 1f)
                .build()
        )
        if (track != null) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.thumbnail)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, MarsMusicPlayer.duration.toLong())
                    .build()
            )
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
