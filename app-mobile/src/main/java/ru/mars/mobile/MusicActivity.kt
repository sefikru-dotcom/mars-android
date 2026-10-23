package ru.mars.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.mobile.service.MarsMusicService
import ru.mars.network.cache.MusicOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.model.HistoryEntry
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist
import ru.mars.network.repository.AlbumResult
import ru.mars.network.repository.MusicAlbum
import ru.mars.network.repository.MusicAlbumSummary
import ru.mars.network.repository.MusicArtist
import ru.mars.network.repository.MusicRepository
import java.io.Closeable

class MusicActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    internal lateinit var repository: MusicRepository
    /**
     * Domain glue for the real remote music event stream: validates each Mars Connect
     * frame and routes it through [RemoteQueueSyncPolicy] (see [RemoteQueueSyncController]).
     */
    private val remoteSync = RemoteQueueSyncController()
    /** Live Mars Connect subscription; opened in [onCreate], closed in [onDestroy]. */
    private var remoteSubscription: Closeable? = null
    internal lateinit var results: LinearLayout
    internal lateinit var title: TextView
    internal lateinit var artistView: TextView
    internal lateinit var time: TextView
    internal lateinit var seek: SeekBar
    internal lateinit var playButton: ImageButton
    internal lateinit var artwork: ImageView
    internal var tracks = emptyList<MusicTrack>()
    internal var job: Job? = null
    /**
     * Офлайн-снимок вкладок музыки ([MusicOfflineCache]): при сбое сети «Обзор» и
     * «Плейлисты» показывают последний успешный ответ с подписью возраста вместо
     * экрана ошибки. Решение «снимок есть / снимка нет» — чистая
     * [MusicOfflineFallbackPolicy]. Файл стирается на выходе из аккаунта
     * ([AuthExitStores.MUSIC_OFFLINE_PREFS]): снимок чарта = история прослушивания.
     */
    internal val offlineCache by lazy {
        MusicOfflineCache(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(AuthExitStores.MUSIC_OFFLINE_PREFS, Context.MODE_PRIVATE),
            ),
        )
    }
    /** Local history of successful searches (newest first); pure policy in [MusicSearchHistoryPolicy]. */
    internal lateinit var searchHistory: MusicSearchHistory
    /** Monotonic id of the in-flight search run; stale runs are dropped by [SearchRunGuard]. */
    internal var searchRunId = 0
    /** Живые подсказки поиска (GET /api/suggestions); правила запроса/панели в [SearchSuggestionsPolicy]. */
    internal var searchSuggestions = emptyList<String>()
    internal var suggestionsJob: Job? = null
    /** Монотонный id запуска подсказок; устаревшие ответы отбрасывает [SearchRunGuard]. */
    internal var suggestionsRunId = 0

    internal var currentTab = MusicTab.CHART
    internal val favoriteIds = linkedSetOf<String>()
    internal val heartButtons = HashMap<String, MutableList<ImageButton>>()

    internal val trackRows = HashMap<String, MutableList<TrackRowHolder>>()
    internal lateinit var chartTabButton: TextView
    internal lateinit var flowTabButton: TextView
    internal lateinit var forYouTabButton: TextView
    internal lateinit var favoritesTabButton: TextView
    internal lateinit var queueTabButton: TextView
    internal lateinit var playlistsTabButton: TextView
    internal lateinit var historyTabButton: TextView
    /** Server-side playlists of the «Плейлисты» tab, newest first as the service returns them. */
    internal var playlists = emptyList<Playlist>()
    /** Server-side listening history of the «История» tab; also the queue for history playback. */
    internal var historyEntries = emptyList<HistoryEntry>()
    /** Opened playlist: its id/title drive the drill-down header and the tracks shown. */
    internal var openPlaylist: Playlist? = null
    /** In-flight playlists/history fetch, separate from the chart [job] so tabs never cancel each other. */
    internal var libraryJob: Job? = null
    /**
     * Last id handed to /api/history/record. Mars Connect re-broadcasts the same track on
     * every pause/resume/progress frame, so the raw callback would write the history many
     * times a minute; the pure [HistoryRecordPolicy] gates the duplicate writes.
     */
    internal var lastRecordedTrackId: String? = null
    internal lateinit var searchInput: EditText
    internal lateinit var clearSearchBtn: ImageButton
    /** Overlay with recent searches; floats above the results list, never replaces it. */
    internal lateinit var historyOverlay: ScrollView
    internal lateinit var historyPanel: LinearLayout
    internal lateinit var statusText: TextView
    internal lateinit var shuffleBtn: ImageButton
    internal lateinit var repeatBtn: ImageButton
    /** «Радио по треку» in the player bar: appends similar tracks to the queue. */
    internal lateinit var radioBtn: ImageButton
    /** «Текст песни» in the player bar: opens the lyrics dialog for the current track. */
    internal lateinit var lyricsBtn: ImageButton
    /** «Звук» in the header: opens the equalizer/preset/Bass Boost dialog. */
    internal lateinit var eqBtn: ImageButton
    /** Persisted sound settings (preset, on/off, Bass Boost); pure logic in [AudioFxPolicy]. */
    internal lateinit var audioFx: AudioFxSettings
    /** Persisted stream profile; the player owns the live URL/rebind contract. */
    internal lateinit var streamQuality: StreamQualitySettings
    /** Header button for the explicit original/standard/economy picker. */
    internal lateinit var qualityBtn: ImageButton
    /** In-flight radio fetch; guards against double taps (same debounce as [lyricsJob]). */
    internal var radioJob: Job? = null
    /** In-flight lyrics fetch; the dialog body is updated when it completes. */
    internal var lyricsJob: Job? = null
    /**
     * Запрос страницы исполнителя (GET /api/artist/…). Один на диалог: пока он в
     * полёте, повторный тап по имени не запускает второй (см. [MusicArtistPolicy.canStartRequest]).
     */
    internal var artistJob: Job? = null
    /**
     * Запрос альбомов исполнителя (`GET /api/search?type=albums`). Отдельный от
     * [artistJob]: песни и альбомы грузятся параллельно, и медленный поиск не
     * задерживает страницу исполнителя. Гейт тот же — [MusicArtistPolicy.canStartRequest].
     */
    internal var albumsJob: Job? = null
    /** Запрос содержимого альбома (`GET /api/album/{browseId}`); один на диалог. */
    internal var albumJob: Job? = null
    /** True while the user's finger owns the seek thumb: the ticker must not fight the drag. */
    internal var isDraggingSeek = false
    /** Last dragged-to position; applied to the player once, on release (see [SeekDragPolicy]). */
    internal var pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
    /** Running play/pause glyph crossfades, one per button; ended at their final frame in onPause/onStop. */
    internal val iconCrossfades = HashMap<ImageButton, AnimatorSet>()
    private val ticker = object : Runnable {
        override fun run() {
            if (::seek.isInitialized) {
                // Skip thumb/time updates mid-drag, otherwise the 500 ms ticker keeps
                // snapping the thumb back and precise seeking on touch is impossible.
                if (SeekDragPolicy.tickerMayUpdate(isDraggingSeek)) {
                    seek.progress = MarsMusicPlayer.currentPosition
                    time.text = "${fmt(MarsMusicPlayer.currentPosition)} / ${fmt(MarsMusicPlayer.duration)}"
                }
                seek.postDelayed(this, TICKER_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        repository = MusicRepository()
        // Real remote music event stream (Mars Connect): SESSION_RESTORE snapshot plus
        // every later DEVICES_UPDATE broadcast, resolved against the local player state
        // by the pure RemoteQueueSyncPolicy inside remoteSync.
        remoteSubscription = repository.subscribeRemoteState(::onRemoteMusicState)
        searchHistory = MusicSearchHistory(
            PrefsMusicSearchHistoryStorage(getSharedPreferences("mars-music-search-history", MODE_PRIVATE))
        )
        audioFx = AudioFxSettings(
            PrefsAudioFxStorage(getSharedPreferences("mars-music-audiofx", MODE_PRIVATE))
        )
        streamQuality = StreamQualitySettings(
            PrefsStreamQualityStorage(getSharedPreferences("mars-music-preferences", MODE_PRIVATE))
        )
        // The process-scoped player re-applies the saved preset to every fresh audio session.
        MarsMusicPlayer.setAudioFx(audioFx.state)
        MarsMusicPlayer.setStreamQuality(streamQuality.value)
        setContentView(buildView())
        MarsMusicPlayer.registerListener(this)
        requestNotificationPermission()
        seek.post(ticker)
        updateTabStyles()
        updateQueueIcons()
        updateEqIcon()
        updateQualityIcon()
        refreshFavoriteIds()
        loadHome()
    }

    override fun onResume() {
        super.onResume()
        // Single ticker chain: drop stale posts (the initial onCreate one included),
        // then restart the 500 ms progress ticker with an immediate first update.
        if (::seek.isInitialized) {
            seek.removeCallbacks(ticker)
            seek.post(ticker)
        }
    }

    override fun onPause() {
        finishIconCrossfades()
        super.onPause()
    }

    override fun onStop() {
        // No animator or ticker may outlive the visible session (no Activity leak):
        // crossfades snap to their final frame, the progress ticker is detached.
        finishIconCrossfades()
        if (::seek.isInitialized) seek.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        finishIconCrossfades()
        remoteSubscription?.close()
        remoteSubscription = null
        MarsMusicPlayer.unregisterListener(this)
        seek.removeCallbacks(ticker)
        // Ни один фоновый запрос не переживает экран: отменяется весь набор, а не только
        // чартовый job (иначе колбэк приходит в уничтоженную Activity). Состав набора и
        // его отмена закреплены тестом MusicJobSetTest.
        MusicJobSet.cancelAll(
            charts = job,
            library = libraryJob,
            radio = radioJob,
            lyrics = lyricsJob,
            artist = artistJob,
            albums = albumsJob,
            album = albumJob,
            suggestions = suggestionsJob,
        )
        super.onDestroy()
    }

    // Проверки lateinit-полей для секций-расширений (план §4.2): `::x.isInitialized`
    // компилятор разрешает только внутри класса, поэтому хост отдаёт предикаты.
    internal fun isHistoryOverlayReady(): Boolean = ::historyOverlay.isInitialized
    internal fun isSearchHistoryReady(): Boolean = ::searchHistory.isInitialized
    internal fun isStatusTextReady(): Boolean = ::statusText.isInitialized
    internal fun isResultsReady(): Boolean = ::results.isInitialized
    internal fun isPlayerActionBtnsReady(): Boolean = ::radioBtn.isInitialized && ::lyricsBtn.isInitialized
    internal fun isQueueIconBtnsReady(): Boolean = ::shuffleBtn.isInitialized && ::repeatBtn.isInitialized

    /**
     * One frame of the remote music event stream. The repository delivers on its WebSocket
     * reader thread, so the (pure) decision and its application are marshalled to the main
     * thread; the outcome goes through the process-scoped [MarsMusicPlayer], whose
     * listeners refresh this screen, the notification service and the chat mini player
     * exactly as for a local change. Adopting a remote queue never auto-starts audio, and
     * stale frames (own-device echo, out-of-order timestamp) are dropped by [remoteSync].
     */
    private fun onRemoteMusicState(state: MusicPlayerState) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val local = LocalPlaybackSnapshot(
                queue = MarsMusicPlayer.tracks,
                isPlaying = MarsMusicPlayer.isPlaying,
                currentTrackId = MarsMusicPlayer.currentTrack?.id,
                deviceId = repository.deviceId,
            )
            when (val outcome = remoteSync.onRemoteState(state, local)) {
                is RemoteSyncOutcome.Adopted -> MarsMusicPlayer.adoptQueue(outcome.queue, outcome.startIndex)
                is RemoteSyncOutcome.Merged -> outcome.appendedTracks.forEach { MarsMusicPlayer.enqueue(it) }
                is RemoteSyncOutcome.Kept, is RemoteSyncOutcome.IgnoredStale -> Unit
            }
        }
    }

    /** True once [MarsMusicService] was started for the current playback run; resets on stop. */
    internal var musicServiceStarted = false

    /** Android 13+: без POST_NOTIFICATIONS медиа-нотификация в шторке не видна. */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) = renderPlayerState(track, isPlaying)

    override fun onProgress(currentMs: Int, totalMs: Int) = renderProgress(currentMs, totalMs)

    /** Explicit loading placeholder for the chart list (also shown before a retry). */
    internal fun showLoading() {
        showStatus(null)
        resetList()
        results.addView(TextView(this).apply {
            text = "Загружаем чарт…"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
    }

    )

    /**
     * Shared error/retry state for the track list. Clears the previous cards and the
     * [tracks] backing list first, so a failed load can never leave a stale chart on
     * screen that still starts playback on tap; then shows [message] with a retry.
     */
    internal fun showListError(message: String, retryDescription: String, onRetry: () -> Unit) {
        tracks = emptyList()
        resetList()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(32), 0, dp(32))
        }
        box.addView(TextView(this).apply {
            text = message
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
        })
        val retryBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_refresh,
            retryDescription,
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.ACCENT,
            sizeDp = 42
        ).apply {
            setOnClickListener { onRetry() }
        }
        box.addView(retryBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(12) })
        results.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun selectTab(tab: MusicTab) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabStyles()
        loadTab(tab)
    }

    /** Re-loads the content of the currently active tab (used by the clear-search button). */
    internal fun reloadActiveTab() = loadTab(currentTab)

    /**
     * Единственная точка «показать содержимое вкладки»: гасит статус, отменяет прежний
     * запрос и грузит контент. `selectTab` и `reloadActiveTab` различались только тем,
     * откуда берётся [tab], — два одинаковых `when` держали одну и ту же маршрутизацию
     * в двух копиях (план §7 D7).
     */
    private fun loadTab(tab: MusicTab) {
        showStatus(null)
        job?.cancel()
        libraryJob?.cancel()
        when (tab) {
            MusicTab.CHART -> loadHome()
            MusicTab.FLOW -> loadFlow()
            MusicTab.FOR_YOU -> loadForYou()
            MusicTab.FAVORITES -> loadFavorites()
            MusicTab.QUEUE -> renderQueue()
            MusicTab.PLAYLISTS -> loadPlaylists()
            MusicTab.HISTORY -> loadHistory()
        }
    }

    // ------------------------------------------------------------------ библиотека

    /**
     * Renders the [state] of a library section into the results list: loading/error/empty
     * get their own placeholder (the error one carries a retry), [content] fills the rows.
     * A failed fetch never leaves the previous cards on screen.
     */
    internal fun renderLibrary(state: LibraryUiState, onRetry: () -> Unit, content: () -> Unit) {
        tracks = emptyList() // stale chart cards must not stay clickable under a library section
        resetList()
        when (state) {
            LibraryUiState.Loading -> results.addView(libraryPlaceholder("Загружаем…"))
            LibraryUiState.Empty -> results.addView(libraryPlaceholder("Здесь пока пусто"))
            is LibraryUiState.Error -> showListError(
                message = state.message,
                retryDescription = "Повторить загрузку",
                onRetry = onRetry,
            )
            LibraryUiState.Content -> content()
        }
    }

    internal fun libraryPlaceholder(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(32), 0, dp(32))
    }

    /**
     * Section header card: a left caption plus an optional right action pill. Returns
     * null when there is nothing to caption and no action to offer.
     */
    internal fun headerCard(title: String, action: String? = null, onAction: (() -> Unit)? = null): View? {
        if (action == null && title.isBlank()) return null
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 12)
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            row.addView(TextView(this).apply {
                text = action
                textSize = 13f
                setTextColor(MarsTheme.ACCENT_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = MarsTheme.button(this@MusicActivity, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
                isClickable = true
                isFocusable = true
                setOnClickListener { onAction() }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        return row.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    // ------------------------------------------------------------- исполнитель

    /** Clears the rendered list plus per-row caches (hearts, highlighted rows). */
    internal fun resetList() {
        results.removeAllViews()
        heartButtons.clear()
        trackRows.clear()
    }

    /** Row tap: an inactive row starts playback, the active row toggles play/pause. */
    internal fun onTrackRowClicked(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        val state = TrackRowUiState.resolve(track.id, MarsMusicPlayer.currentTrack?.id, MarsMusicPlayer.isPlaying)
        if (state.tapStartsPlayback) MarsMusicPlayer.playTrack(index, tracks) else MarsMusicPlayer.togglePlay()
    }

    internal fun onQueueAddClicked(track: MusicTrack) {
        MarsMusicPlayer.enqueue(track)
        // Real state feedback: a finished/empty session resumes on this very track.
        showStatus(
            if (MarsMusicPlayer.currentTrack?.id == track.id) "Играет: ${track.title}"
            else "Добавлено в очередь: ${track.title}"
        )
    }

    internal fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, (ms / 1000) % 60)
    internal fun dp(v: Int) = MarsTheme.dp(this, v)

}

/**
 * Pure seek-bar drag policy for [MusicActivity]: while the user's finger owns the thumb,
 * the periodic progress ticker and player callbacks must not snap it back (on a touch
 * screen the thumb visibly fights the drag), and the player is seeked exactly once — on
 * release — instead of on every intermediate progress change. Android-free for JVM tests.
 */
object SeekDragPolicy {
    /** Initial/reset value of the pending drag position: nothing has been dragged. */
    const val NO_PENDING_SEEK = -1

    /** The ticker may move the thumb and time label only when no drag is in progress. */
    fun tickerMayUpdate(isDragging: Boolean): Boolean = !isDragging

    /**
     * The position to seek to on release: [pendingMs] clamped into the track, or null when
     * nothing is pending (any negative value) — then the player must not be touched.
     */
    fun pendingSeekTarget(pendingMs: Int, durationMs: Int): Int? =
        if (pendingMs < 0) null else pendingMs.coerceIn(0, durationMs.coerceAtLeast(0))
}

/**
 * Pure policy for the play/pause glyph crossfade in the music flow: alpha only — never
 * scale, never bounce/overshoot, capped at [MarsMotion.fastMs] (120 ms) so the effect
 * stays a discreet micro-feedback, and collapsed to an instant swap under reduced motion.
 * Android-free for JVM tests.
 */
object PlayPauseIconPolicy {
    /**
     * Crossfade duration in ms: [MarsMotion.fastMs] normally, 0 (instant swap) when
     * [reducedMotion] — routed through [MotionPolicy] like every other effect.
     */
    fun durationMs(reducedMotion: Boolean): Int =
        MotionPolicy.durationFor(MotionCue.MICRO_TAP, reducedMotion).coerceIn(0, MarsMotion.fastMs)

    /**
     * Crossfade only a real glyph change on a button that already rendered its first
     * glyph ([currentResId] != null — the first render is instant, there is nothing to
     * fade from) and only while animation is allowed (duration > 0).
     */
    fun shouldCrossfade(currentResId: Int?, targetResId: Int, durationMs: Int): Boolean =
        currentResId != null && currentResId != targetResId && durationMs > 0
}
