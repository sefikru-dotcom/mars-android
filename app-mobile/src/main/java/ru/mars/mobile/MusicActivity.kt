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
    private val offlineCache by lazy {
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

    /** Which pill tab is active: chart, flow, home feed, favorites, queue, server playlists, server history. */
    internal enum class MusicTab { CHART, FLOW, FOR_YOU, FAVORITES, QUEUE, PLAYLISTS, HISTORY }

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
    private var playlists = emptyList<Playlist>()
    /** Server-side listening history of the «История» tab; also the queue for history playback. */
    private var historyEntries = emptyList<HistoryEntry>()
    /** Opened playlist: its id/title drive the drill-down header and the tracks shown. */
    private var openPlaylist: Playlist? = null
    /** In-flight playlists/history fetch, separate from the chart [job] so tabs never cancel each other. */
    private var libraryJob: Job? = null
    /**
     * Last id handed to /api/history/record. Mars Connect re-broadcasts the same track on
     * every pause/resume/progress frame, so the raw callback would write the history many
     * times a minute; the pure [HistoryRecordPolicy] gates the duplicate writes.
     */
    private var lastRecordedTrackId: String? = null
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
    private lateinit var audioFx: AudioFxSettings
    /** Persisted stream profile; the player owns the live URL/rebind contract. */
    private lateinit var streamQuality: StreamQualitySettings
    /** Header button for the explicit original/standard/economy picker. */
    internal lateinit var qualityBtn: ImageButton
    /** In-flight radio fetch; guards against double taps (same debounce as [lyricsJob]). */
    internal var radioJob: Job? = null
    /** In-flight lyrics fetch; the dialog body is updated when it completes. */
    private var lyricsJob: Job? = null
    /**
     * Запрос страницы исполнителя (GET /api/artist/…). Один на диалог: пока он в
     * полёте, повторный тап по имени не запускает второй (см. [MusicArtistPolicy.canStartRequest]).
     */
    private var artistJob: Job? = null
    /**
     * Запрос альбомов исполнителя (`GET /api/search?type=albums`). Отдельный от
     * [artistJob]: песни и альбомы грузятся параллельно, и медленный поиск не
     * задерживает страницу исполнителя. Гейт тот же — [MusicArtistPolicy.canStartRequest].
     */
    private var albumsJob: Job? = null
    /** Запрос содержимого альбома (`GET /api/album/{browseId}`); один на диалог. */
    private var albumJob: Job? = null
    /** True while the user's finger owns the seek thumb: the ticker must not fight the drag. */
    internal var isDraggingSeek = false
    /** Last dragged-to position; applied to the player once, on release (see [SeekDragPolicy]). */
    internal var pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
    /** Running play/pause glyph crossfades, one per button; ended at their final frame in onPause/onStop. */
    private val iconCrossfades = HashMap<ImageButton, AnimatorSet>()
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

    /** System «reduce motion» accessibility setting routed through the pure [MotionPolicy]. */
    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Sets the play/pause glyph of [button] with an alpha-only crossfade (120 ms,
     * [MarsMotion.fastMs] cap, no scale/bounce — pure [PlayPauseIconPolicy] decides).
     * The glyph res id currently shown is remembered in the button's tag: the first
     * render swaps instantly (nothing to fade from) and unchanged glyphs are no-ops,
     * so list refreshes never restart the effect. Reduced motion → instant swap.
     */
    internal fun setPlayPauseGlyph(button: ImageButton, showPause: Boolean) {
        val targetRes = if (showPause) R.drawable.ic_pause else R.drawable.ic_play
        val currentRes = button.tag as? Int
        if (currentRes == targetRes) return // already showing (or crossfading toward) the target
        button.tag = targetRes
        val duration = PlayPauseIconPolicy.durationMs(isReducedMotion())
        val old = currentRes?.let { ContextCompat.getDrawable(this, it)?.mutate() }
        val new = ContextCompat.getDrawable(this, targetRes)?.mutate()
        if (old == null || new == null || !PlayPauseIconPolicy.shouldCrossfade(currentRes, targetRes, duration)) {
            button.setImageResource(targetRes)
            return
        }
        // True crossfade: both glyphs are layered, the new one fades in while the old
        // fades out (alpha only, decelerate curve); the ImageView applies its tint to
        // the layer stack, so both children render in the button's icon color.
        new.alpha = 0
        button.setImageDrawable(LayerDrawable(arrayOf(old, new)))
        val crossfade = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofInt(new, "alpha", 0, 255),
                ObjectAnimator.ofInt(old, "alpha", 255, 0),
            )
            this.duration = duration.toLong()
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Land the final frame: the plain target glyph replaces the two-layer
                    // stack. Skipped when a newer toggle already moved the button on.
                    if (button.tag == targetRes) {
                        new.alpha = 255
                        button.setImageDrawable(new)
                    }
                }
            })
        }
        // One crossfade per button: drop any in-flight one first. Its end listener is
        // guarded by `button.tag`, which now holds the NEW target, so cancelling it is a
        // no-op that just frees the animator — long sessions never accumulate them.
        iconCrossfades.remove(button)?.let { runCatching { it.cancel() } }
        iconCrossfades[button] = crossfade
        crossfade.start()
    }

    /**
     * Ends every tracked glyph crossfade at its final frame (the end listener lands the
     * target glyph) and drops the references — nothing animated outlives onPause/onStop.
     */
    private fun finishIconCrossfades() {
        iconCrossfades.values.forEach { runCatching { it.end() } }
        iconCrossfades.clear()
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

    private fun loadHome() {
        job?.cancel()
        showLoading()
        job = lifecycleScope.launch {
            // Charts go through the repository: {success:false,error} at HTTP 200 is an
            // exception there, so a server rejection can never masquerade as an empty
            // «Треки не найдены» list — both endpoints failing shows the error/retry UI.
            // The /api/home fallback rides the SAME repository transport (OkHttp, shared
            // interceptors/timeouts, `success:false` → exception) instead of a second
            // hand-rolled URL connection; shelves are flattened by [MusicHomePolicy].
            val outcome = withContext(Dispatchers.IO) {
                MusicChartLoader.resolve(runCatching { repository.getCharts() }) {
                    runCatching { repository.getHome() }.map(MusicHomePolicy::playbackQueue)
                }
            }
            when (outcome) {
                is MusicChartLoader.Outcome.Tracks -> {
                    // Снимок пишется только из успешного ответа и только непустого:
                    // кэш должен повторять то, что пользователь реально видел.
                    if (outcome.items.isNotEmpty()) offlineCache.saveChartsSnapshot(outcome.items)
                    showTracks(outcome.items)
                }
                is MusicChartLoader.Outcome.Failed -> showCachedCharts()
            }
        }
    }

    /**
     * Сеть упала на «Обзоре»: если есть годный снимок чарта — рисуем его с явной
     * подписью возраста («из кэша, снимок на 18.10 23:41») вместо экрана ошибки.
     * Нет снимка (или он протух/пуст) — прежнее поведение [showHomeError] с retry.
     */
    private fun showCachedCharts() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = offlineCache.getChartsSnapshot(),
            snapshotTakenAtMs = offlineCache.getTimestamp(),
            nowMs = System.currentTimeMillis(),
        )
        val cached = decision.items
        if (cached == null) {
            showHomeError()
            return
        }
        showStatus(null)
        showTracks(cached, header = offlineNotice(decision.label.orEmpty()))
    }

    /**
     * Подпись офлайн-снимка под заголовком списка: пилюля цвета предупреждения.
     * Обёрнута в контейнер на всю ширину, потому что [showTracks] растягивает header.
     */
    private fun offlineNotice(label: String): View {
        val pill = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(MarsTheme.STATUS_WARNING)
            background = MarsTheme.pill(this@MusicActivity, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(-2, -2))
        }
    }

    /** Explicit loading placeholder for the chart list (also shown before a retry). */
    private fun showLoading() {
        showStatus(null)
        resetList()
        results.addView(TextView(this).apply {
            text = "Загружаем чарт…"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
    }

    /** Chart fetch failed (both endpoints): say so and offer an explicit retry. */
    private fun showHomeError() = showListError(
        message = "Чарт недоступен — проверьте сеть",
        retryDescription = "Повторить загрузку чарта",
        onRetry = { loadHome() },
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
     * «Плейлисты»: server-side playlists of the account. The list itself is one card per
     * playlist (title, «N треков · создан …», «Открыть» and a delete button) plus a
     * «Создать плейлист» card on top; opening one drills into its tracks. All network
     * calls stay in [MusicRepository] — this screen only renders what it returns.
     */
    private fun loadPlaylists() {
        libraryJob?.cancel()
        openPlaylist = null
        renderLibrary(LibraryUiState.Loading, { loadPlaylists() }) {
            playlists.forEach { playlist -> results.addView(playlistCard(playlist)) }
        }
        libraryJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylists() } }
            fetched.onSuccess { list ->
                playlists = list
                if (list.isNotEmpty()) offlineCache.savePlaylistsSnapshot(list)
                renderPlaylistsContent(notice = null)
            }.onFailure {
                // Сбой сети: последний снимок плейлистов лучше пустого экрана с retry.
                // Снимка нет — прежнее поведение (ошибка «Плейлисты недоступны»).
                val decision = MusicOfflineFallbackPolicy.decide(
                    items = offlineCache.getPlaylistsSnapshot(),
                    snapshotTakenAtMs = offlineCache.getPlaylistsTimestamp(),
                    nowMs = System.currentTimeMillis(),
                )
                val cached = decision.items
                if (cached == null) {
                    renderLibrary(LibraryUiState.Error("Плейлисты недоступны — проверьте сеть"), { loadPlaylists() }) {}
                } else {
                    playlists = cached
                    renderPlaylistsContent(notice = decision.label.orEmpty())
                }
            }
        }
    }

    /**
     * Ряды вкладки «Плейлисты»: заголовок со счётчиком, «Создать плейлист» и карточки.
     * [notice] — подпись офлайн-снимка; null для живых данных с сервера.
     */
    private fun renderPlaylistsContent(notice: String?) {
        renderLibrary(MusicLibraryPolicy.state(false, null, playlists.size), { loadPlaylists() }) {
            notice?.let { results.addView(offlineNotice(it)) }
            headerCard(
                title = MusicLibraryPolicy.playlistCountLabel(playlists.size),
                action = "Создать плейлист",
                onAction = { promptCreatePlaylist() },
            )?.let { results.addView(it) }
            playlists.forEach { playlist -> results.addView(playlistCard(playlist)) }
        }
    }

    /**
     * Renders the [state] of a library section into the results list: loading/error/empty
     * get their own placeholder (the error one carries a retry), [content] fills the rows.
     * A failed fetch never leaves the previous cards on screen.
     */
    private fun renderLibrary(state: LibraryUiState, onRetry: () -> Unit, content: () -> Unit) {
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

    private fun libraryPlaceholder(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(32), 0, dp(32))
    }

    /**
     * Section header card: a left caption plus an optional right action pill. Returns
     * null when there is nothing to caption and no action to offer.
     */
    private fun headerCard(title: String, action: String? = null, onAction: (() -> Unit)? = null): View? {
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

    /** One playlist row: title, «N треков · создан …», an open action and a delete button. */
    private fun playlistCard(playlist: Playlist): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { openPlaylistTracks(playlist) }
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        info.addView(TextView(this).apply {
            text = playlist.title
            textSize = 15f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = MusicLibraryPolicy.playlistSubtitle(playlist.trackCount, playlist.createdAt)
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val openBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            "Открыть плейлист ${playlist.title}",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.ACCENT,
            sizeDp = 34
        ).apply {
            setOnClickListener { openPlaylistTracks(playlist) }
        }
        row.addView(openBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val deleteBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_trash,
            "Удалить плейлист ${playlist.title}",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { confirmDeletePlaylist(playlist) }
        }
        row.addView(deleteBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        return row.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
    }

    /** Asks for a title, then creates the playlist on the server and re-renders the list. */
    private fun promptCreatePlaylist() {
        var dialogRef: Dialog? = null
        val field = MarsDialog.textField(this, hint = "Название плейлиста")
        val errorStrip = MarsDialog.errorStrip(this)
        fun submit() {
            val title = field.text?.toString().orEmpty()
            val controls = listOf(field)
            MarsDialog.setResponding(controls, true)
            MarsDialog.setError(errorStrip, null)
            lifecycleScope.launch {
                val created = withContext(Dispatchers.IO) { runCatching { repository.createPlaylist(title) } }
                MarsDialog.setResponding(controls, false)
                created.onSuccess {
                    dialogRef?.dismiss()
                    showStatus("Создан плейлист: ${it.title}")
                    loadPlaylists()
                }.onFailure { error ->
                    // «Title required» is a deliberate refusal about the title, not a network
                    // fault; MusicLibraryPolicy turns it into a phrase the user can act on.
                    MarsDialog.setError(errorStrip, MusicLibraryPolicy.createPlaylistError(error.message))
                }
            }
        }
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Новый плейлист", null))
            root.addView(MarsDialog.paragraph(this, "Плейлист сохраняется на сервере и доступен с любого устройства."))
            root.addView(field)
            root.addView(errorStrip)
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, "Отмена") { dialogRef?.dismiss() },
                    MarsDialog.primaryButton(this, "Создать") { submit() },
                )
            )
            listOf(field)
        }
    }

    /** Destructive confirmation before DELETE — a playlist may hold the only copy of a track. */
    private fun confirmDeletePlaylist(playlist: Playlist) {
        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Удалить плейлист", playlist.title))
            root.addView(
                MarsDialog.paragraph(
                    this,
                    "Плейлист «${playlist.title}» (${MusicLibraryPolicy.trackCountLabel(playlist.trackCount)}) " +
                        "будет удалён на сервере. Действие необратимо.",
                )
            )
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, "Отмена") { dialogRef?.dismiss() },
                    MarsDialog.denyButton(this, "Удалить") {
                        dialogRef?.dismiss()
                        deletePlaylist(playlist)
                    },
                )
            )
            emptyList()
        }
    }

    private fun deletePlaylist(playlist: Playlist) {
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) { runCatching { repository.deletePlaylist(playlist.id) } }
            removed.onSuccess {
                showStatus("Удалён плейлист: ${playlist.title}")
                loadPlaylists()
            }.onFailure { error ->
                showStatus("Не удалось удалить: ${error.message ?: "проверьте сеть"}")
            }
        }
    }

    /** Drill-down: the playlist's tracks, playable like any other list. */
    private fun openPlaylistTracks(playlist: Playlist) {
        libraryJob?.cancel()
        openPlaylist = playlist
        renderLibrary(LibraryUiState.Loading, { openPlaylistTracks(playlist) }) {}
        libraryJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylistTracks(playlist.id) } }
            fetched.onSuccess { items ->
                // showTracks renders the rows AND resets the list, so the header is handed
                // to it instead of being added first (it would be wiped otherwise).
                showTracks(
                    items,
                    headerCard(
                        title = "${playlist.title} · ${MusicLibraryPolicy.trackCountLabel(items.size)}",
                        action = "Плейлисты",
                        onAction = { loadPlaylists() },
                    ),
                    action = RowAction(
                        iconRes = R.drawable.ic_trash,
                        description = "Убрать из плейлиста ${playlist.title}",
                        onTap = { track -> removeFromPlaylist(playlist, track) },
                    ),
                )
            }.onFailure {
                renderLibrary(
                    LibraryUiState.Error("Плейлист недоступен — проверьте сеть"),
                    { openPlaylistTracks(playlist) },
                ) {}
            }
        }
    }

    /** «История»: server-side listening history, newest first, each row playable. */
    private fun loadHistory() {
        libraryJob?.cancel()
        openPlaylist = null
        renderLibrary(LibraryUiState.Loading, { loadHistory() }) {}
        libraryJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getHistory() } }
            fetched.onSuccess { entries ->
                historyEntries = entries
                renderLibrary(MusicLibraryPolicy.state(false, null, entries.size), { loadHistory() }) {
                    if (entries.isEmpty()) {
                        results.addView(libraryPlaceholder("Вы ещё ничего не слушали"))
                        return@renderLibrary
                    }
                    headerCard(title = "Прослушано · ${MusicLibraryPolicy.trackCountLabel(entries.size)}")
                        ?.let { results.addView(it) }
                    val now = System.currentTimeMillis()
                    entries.forEach { entry -> results.addView(historyCard(entry, now)) }
                }
            }.onFailure {
                renderLibrary(LibraryUiState.Error("История недоступна — проверьте сеть"), { loadHistory() }) {}
            }
        }
    }

    /**
     * One history row: cover, title, «исполнитель · длительность», the human played-at
     * label and the same transport/favorite/queue actions as any track row. Tapping it
     * plays that very track (the payload's `id` is the video id).
     */
    private fun historyCard(entry: HistoryEntry, nowMillis: Long): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { playFromHistory(entry) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
            clipToOutline = true
        }
        entry.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, image) }
        row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        info.addView(TextView(this).apply {
            text = entry.title
            textSize = 15f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = "${entry.artist} · ${entry.duration}"
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = MusicLibraryPolicy.playedAtLabel(entry.playedAt, nowMillis)
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val playBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            MusicA11yPolicy.ACTION_PLAY,
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.ACCENT,
            sizeDp = 34
        ).apply {
            setOnClickListener { playFromHistory(entry) }
        }
        row.addView(playBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val enqueueBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_playlist_add,
            "В очередь",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener {
                MarsMusicPlayer.enqueue(entry.toTrack())
                showStatus("Добавлено в очередь: ${entry.title}")
            }
        }
        row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        return row.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
    }

    /**
     * Plays a history entry: the player needs a queue, so the whole history becomes the
     * queue and playback starts at the tapped row (same contract as a chart row).
     */
    private fun playFromHistory(entry: HistoryEntry) {
        val queue = historyEntries.map { it.toTrack() }.ifEmpty { listOf(entry.toTrack()) }
        val index = queue.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } ?: 0
        MarsMusicPlayer.playTrack(index, queue)
    }

    /**
     * «Добавить в плейлист»: long-pressing any track row opens the list of server
     * playlists and the tap adds that track there (POST .../add with the video id).
     */
    internal fun promptAddToPlaylist(track: MusicTrack) {
        var dialogRef: Dialog? = null
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val errorStrip = MarsDialog.errorStrip(this)
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Добавить в плейлист", null))
            root.addView(
                MarsDialog.paragraph(
                    this,
                    "«${track.title}» — ${track.artist}",
                )
            )
            root.addView(body)
            root.addView(errorStrip)
            root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
            emptyList()
        }

        if (playlists.isEmpty()) {
            body.addView(
                MarsDialog.paragraph(this, "Плейлистов пока нет — создайте его в разделе «Плейлисты». Загружаем список.")
            )
        }
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getPlaylists() } }
            body.removeAllViews()
            fetched.onSuccess { list ->
                playlists = list
                if (list.isEmpty()) {
                    body.addView(MarsDialog.paragraph(this@MusicActivity, "Плейлистов пока нет — создайте его в разделе «Плейлисты»."))
                    return@onSuccess
                }
                val buttons = list.map { playlist ->
                    MarsDialog.secondaryButton(
                        this@MusicActivity,
                        "${playlist.title} · ${MusicLibraryPolicy.trackCountLabel(playlist.trackCount)}",
                    ) {
                        addToPlaylist(playlist, track, errorStrip)
                    }
                }
                buttons.forEach { body.addView(it) }
            }.onFailure {
                MarsDialog.setError(errorStrip, "Не удалось получить плейлисты — проверьте сеть")
            }
        }
    }

    private fun addToPlaylist(playlist: Playlist, track: MusicTrack, errorStrip: TextView) {
        MarsDialog.setError(errorStrip, null)
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { repository.addTrackToPlaylist(playlist.id, track.id) }
            }
            added.onSuccess {
                showStatus("Добавлено в «${playlist.title}»: ${track.title}")
            }.onFailure { error ->
                MarsDialog.setError(
                    errorStrip,
                    MusicLibraryPolicy.playlistWriteError(error.message, fallback = "Не удалось добавить в плейлист"),
                )
            }
        }
    }

    /** «Убрать из плейлиста»: the drill-down row action; re-renders the opened playlist. */
    private fun removeFromPlaylist(playlist: Playlist, track: MusicTrack) {
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { repository.removeTrackFromPlaylist(playlist.id, track.id) }
            }
            removed.onSuccess {
                showStatus("Убрано из «${playlist.title}»: ${track.title}")
                openPlaylistTracks(playlist)
            }.onFailure {
                showStatus("Не удалось убрать трек — проверьте сеть")
            }
        }
    }

    /**
     * Records the track now playing on the server (POST /api/history/record), so the
     * «История» tab and every other Mars client see the same list. Only a genuinely new
     * track is recorded — a pause/resume or a re-broadcast of the same id must not spam
     * the history ([HistoryRecordPolicy] decides on the pure id comparison).
     */
    internal fun recordPlayedTrack(track: MusicTrack?) {
        if (track == null) return
        if (!HistoryRecordPolicy.shouldRecord(track.id, lastRecordedTrackId)) return
        lastRecordedTrackId = track.id
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.recordHistory(track) } }
            // A failed write stays silent: playback is what matters, the tab re-fetches.
        }
    }

    /**
     * Renders the live playback queue from [MarsMusicPlayer]: header with a clear action,
     * then one row per queued track. The active track gets an accent border and accent
     * title; tapping a row jumps to it, the trash button removes it from the queue.
     */
    internal fun renderQueue() {
        tracks = emptyList() // stale chart cards must not stay clickable under the queue
        resetList()

        val queue = MarsMusicPlayer.tracks
        if (queue.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "Очередь воспроизведения пуста"
                setTextColor(MarsTheme.TEXT_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }

        // Header: queue size + clear action
        val headerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        headerRow.addView(TextView(this).apply {
            text = "В очереди: ${queue.size}"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val clearQueueBtn = TextView(this).apply {
            text = "Очистить очередь"
            textSize = 13f
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = MarsTheme.button(this@MusicActivity, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                MarsMusicPlayer.clearQueue()
                renderQueue()
            }
        }
        headerRow.addView(clearQueueBtn, LinearLayout.LayoutParams(-2, -2))
        results.addView(headerRow, LinearLayout.LayoutParams(-1, -2))

        val activeIndex = MarsMusicPlayer.index
        queue.forEachIndexed { i, t ->
            val active = i == activeIndex
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = MarsTheme.card(
                    this@MusicActivity,
                    if (active) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
                    radiusDp = 12,
                    strokeColor = if (active) MarsTheme.ACCENT else MarsTheme.BORDER
                )
                setOnClickListener { MarsMusicPlayer.playQueueAt(i) }
            }

            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
                clipToOutline = true
            }
            val thumb = t.thumbnail
            if (!thumb.isNullOrBlank()) ImageLoader.load(thumb, image)
            row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

            val trackInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            trackInfo.addView(TextView(this).apply {
                text = t.title
                textSize = 15f
                setTextColor(if (active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            trackInfo.addView(TextView(this).apply {
                text = if (active && MarsMusicPlayer.isPlaying) "Сейчас играет · ${t.artist}" else "${t.artist} · ${t.duration}"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))

            // Quick jump to this queue position
            val jumpBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_play,
                "Перейти к треку",
                bg = if (active) MarsTheme.ACCENT else MarsTheme.SURFACE_SUBTLE,
                iconTint = if (active) MarsTheme.TEXT_STRONG else MarsTheme.ACCENT,
                sizeDp = 34
            ).apply {
                setOnClickListener { MarsMusicPlayer.playQueueAt(i) }
            }
            row.addView(jumpBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

            // Remove this track from the queue
            val removeBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_trash,
                "Убрать из очереди",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 34
            ).apply {
                setOnClickListener {
                    val removed = MarsMusicPlayer.removeFromQueue(i)
                    if (removed != null) showStatus("Удалено из очереди: ${removed.title}")
                    renderQueue()
                }
            }
            row.addView(removeBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

            results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun loadFavorites() {
        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.getFavorites() } }
            result.onSuccess { favs ->
                favoriteIds.clear()
                favoriteIds += favs.map { it.id }
                if (favs.isEmpty()) {
                    tracks = emptyList()
                    resetList()
                    results.addView(TextView(this@MusicActivity).apply {
                        text = "В избранном пусто — нажмите сердечко на треке"
                        setTextColor(MarsTheme.TEXT_MUTED)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(32), 0, dp(32))
                    })
                } else {
                    showTracks(favs)
                }
            }.onFailure {
                tracks = emptyList()
                resetList()
                showStatus("Избранное недоступно")
            }
        }
    }

    /**
     * Loads the personal stream («Волна»): the server-picked Mars Flow track list with a
     * «Включить поток» primary action that starts playback of the whole stream.
     */
    private fun loadFlow() {
        job?.cancel()
        showStatus(null)
        resetList()
        results.addView(TextView(this).apply {
            text = "Настраиваем волну…"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.getFlow() } }
            result.onSuccess { flow ->
                if (flow.isEmpty()) {
                    tracks = emptyList()
                    resetList()
                    results.addView(TextView(this@MusicActivity).apply {
                        text = "Волна пока пуста — сервер не подобрал треки"
                        setTextColor(MarsTheme.TEXT_MUTED)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(32), 0, dp(32))
                    })
                } else {
                    showTracks(flow, header = flowHeader(flow))
                }
            }.onFailure {
                showListError(
                    message = "Волна недоступна — проверьте сеть",
                    retryDescription = "Повторить загрузку волны",
                    onRetry = { loadFlow() },
                )
            }
        }
    }

    /**
     * «Для тебя»: персональная лента GET /api/home — полки «Продолжить слушать»,
     * «Новинки», «Для тебя», «Похожие на …» (server.py build_personal_shelf).
     * Пустая полка — нормальное состояние сервера и просто не рендерится
     * ([MusicHomePolicy]); полностью пустая лента получает понятную заглушку,
     * сбой сети — ошибку с повтором. Загрузка идёт в [libraryJob], как у остальных
     * библиотечных вкладок, и не пересекается с чартовым [job].
     */
    private fun loadForYou() {
        libraryJob?.cancel()
        openPlaylist = null
        renderLibrary(LibraryUiState.Loading, { loadForYou() }) {}
        libraryJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getHome() } }
            fetched.onSuccess { shelves ->
                val visible = MusicHomePolicy.visibleShelves(shelves)
                if (visible.isEmpty()) {
                    // Все полки пусты — это не ошибка: подсказываем, чем лента наполняется.
                    tracks = emptyList()
                    resetList()
                    results.addView(libraryPlaceholder(getString(R.string.music_for_you_empty)))
                    return@onSuccess
                }
                renderForYouShelves(visible)
            }.onFailure {
                renderLibrary(
                    LibraryUiState.Error(getString(R.string.music_for_you_error)),
                    { loadForYou() },
                ) {}
            }
        }
    }

    /**
     * Рендер полок ленты: заголовок-карточка + вертикальный список строк на каждую полку.
     * Все треки видимых полок складываются в [tracks] одной очередью в порядке рендера
     * ([MusicHomePolicy.playbackQueue]), поэтому сквозной индекс строки совпадает с
     * позицией в очереди и тап запускает воспроизведение ленты с этого трека.
     */
    private fun renderForYouShelves(shelves: List<MusicShelf>) {
        tracks = MusicHomePolicy.playbackQueue(shelves)
        resetList()
        var index = 0
        shelves.forEach { shelf ->
            headerCard(title = shelf.title)?.let { results.addView(it) }
            shelf.items.forEach { t ->
                results.addView(
                    trackRow(t, index++),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
            }
        }
    }

    /** «Волна» section card: title, stream size and the accent «Включить поток» button. */
    private fun flowHeader(items: List<MusicTrack>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 14)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "Волна"
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = "Персональный поток Марса · ${items.size} треков"
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(2), 0, dp(12))
        })
        val playFlowBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = MarsTheme.button(this@MusicActivity, MarsTheme.ACCENT, 12)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "Включить поток"
            setOnClickListener {
                if (tracks.isEmpty()) return@setOnClickListener
                MarsMusicPlayer.playTrack(0, tracks)
                showStatus("Волна включена")
            }
        }
        playFlowBtn.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MusicActivity, R.drawable.ic_play))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        playFlowBtn.addView(TextView(this).apply {
            text = "Включить поток"
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(playFlowBtn, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    /**
     * «Радио по треку»: fetches similar tracks for the current track and appends the
     * deduplicated additions (pure [RadioQueuePolicy]) to the queue — playback continues
     * seamlessly because enqueuing never restarts a track that is already playing.
     */
    internal fun onRadioClicked() {
        val track = MarsMusicPlayer.currentTrack ?: run { showStatus("Сначала включите трек"); return }
        if (radioJob?.isActive == true) return
        radioBtn.isEnabled = false
        radioBtn.alpha = 0.4f
        showStatus("Собираем радио по треку «${track.title}»…")
        radioJob = lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { repository.getRadio(track.id) } }
            fetched.onSuccess { candidates ->
                val additions = RadioQueuePolicy.tracksToAdd(
                    fetched = candidates,
                    queuedIds = MarsMusicPlayer.tracks.map { it.id },
                    currentTrackId = MarsMusicPlayer.currentTrack?.id,
                )
                additions.forEach { MarsMusicPlayer.enqueue(it) }
                showStatus(
                    if (additions.isEmpty()) "Радио: новых похожих треков нет"
                    else "Радио: +${additions.size} треков в очередь"
                )
                if (currentTab == MusicTab.QUEUE) renderQueue()
            }.onFailure {
                showStatus("Радио недоступно — проверьте сеть")
            }
            updatePlayerActionStates()
        }
    }

    /**
     * «Текст песни»: opens the themed lyrics dialog immediately (with a loading body) and
     * fills it when `/api/lyrics` answers. The answer→view mapping is pure
     * ([LyricsViewPolicy]): plain lyrics win, synced LRC loses its timestamps.
     */
    internal fun onLyricsClicked() {
        val track = MarsMusicPlayer.currentTrack ?: run { showStatus("Сначала включите трек"); return }
        if (lyricsJob?.isActive == true) return

        val metaView = TextView(this).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        val bodyView = TextView(this).apply {
            text = "Загружаем текст…"
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        /** Original text as returned by /api/lyrics; null while it has not arrived yet. */
        var original: String? = null
        /** Russian translation, fetched on demand; null until the user asks for it. */
        var translation: String? = null
        var showingTranslation = false
        /** Toggle button, added only once there is a text worth translating. */
        val translateButton = MarsDialog.quietButton(this, LyricsTranslationPolicy.ACTION_TRANSLATE) {}
        translateButton.visibility = View.GONE

        fun applyBody() {
            val text = original
            if (text == null) return
            bodyView.text = LyricsTranslationPolicy.bodyFor(text, translation, showingTranslation)
            translateButton.text = LyricsTranslationPolicy.actionLabel(showingTranslation)
        }

        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Текст песни", null))
            root.addView(TextView(this).apply {
                text = "${track.title} — ${track.artist}"
                textSize = 13f
                setTextColor(MarsTheme.ACCENT_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(10))
            })
            root.addView(metaView)
            root.addView(bodyView)
            translateButton.setOnClickListener {
                if (showingTranslation) {
                    showingTranslation = false
                    applyBody()
                    return@setOnClickListener
                }
                val text = original
                if (!LyricsTranslationPolicy.canTranslate(text)) {
                    showStatus("Перевод недоступен — сначала нужен текст песни")
                    return@setOnClickListener
                }
                if (translation != null) {
                    showingTranslation = true
                    applyBody()
                    return@setOnClickListener
                }
                translation = null
                translateButton.isEnabled = false
                translateButton.alpha = 0.4f
                translateButton.text = "Переводим…"
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { repository.translateLyrics(text.orEmpty()) }
                    }
                    translateButton.isEnabled = true
                    translateButton.alpha = 1f
                    result.onSuccess { translated ->
                        translation = translated
                        showingTranslation = translated.isNotBlank()
                        if (translated.isBlank()) {
                            showStatus(LyricsTranslationPolicy.emptyTranslationText())
                        }
                        applyBody()
                    }.onFailure {
                        translateButton.text = LyricsTranslationPolicy.ACTION_TRANSLATE
                        showStatus(LyricsTranslationPolicy.failureText())
                    }
                }
            }
            root.addView(translateButton)
            root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
            emptyList()
        }

        lyricsJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.getLyrics(track.id, title = track.title, artist = track.artist) }
            }
            result.onSuccess { lyrics ->
                when (val state = LyricsViewPolicy.resolve(lyrics)) {
                    is LyricsUiState.Text -> {
                        original = state.body
                        bodyView.setTextColor(MarsTheme.TEXT_EM)
                        applyBody()
                        translateButton.visibility =
                            if (LyricsTranslationPolicy.canTranslate(state.body)) View.VISIBLE else View.GONE
                        val labels = buildList {
                            if (state.synced) add("синхронизированный текст")
                            state.source?.takeIf { it.isNotBlank() }?.let { add("источник: $it") }
                        }
                        if (labels.isNotEmpty()) {
                            metaView.text = labels.joinToString(" · ")
                            metaView.visibility = View.VISIBLE
                        }
                    }
                    LyricsUiState.Missing -> bodyView.text = "Текст не найден — сервер вернул пустой ответ"
                }
            }.onFailure {
                bodyView.text = "Текст недоступен — проверьте сеть"
            }
        }
    }

    // ------------------------------------------------------------- исполнитель

    /**
     * Тап по имени альбома в строке трека открывает тот же альбомный диалог, что и
     * карточка на вкладке «Обзор».
     *
     * Имя альбома без browseId (обычный случай для треков вне альбома и для выдач,
     * где сервер не смог достать id) — это подпись, а не кнопка: обработчик видит
     * пустой id и просто показывает статус, вместо того чтобы уйти в сеть за
     * заведомо отвергаемым запросом.
     */
    internal fun onAlbumClicked(rawBrowseId: String?) {
        val target = MusicAlbumPolicy.requestTarget(rawBrowseId)
        if (target == null) {
            showStatus(MusicAlbumPolicy.NOT_FOUND)
            return
        }
        if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return
        openAlbumDialog(target)
    }

    /**
     * Тап по имени исполнителя (строка трека или мини-плеер) открывает диалог
     * исполнителя. Пока запрос в полёте, повторный тап игнорируется: второй запрос
     * ничего не добавит, а ответ всё равно перерисует тот же диалог.
     */
    internal fun onArtistClicked(rawName: String?) {
        val target = MusicArtistPolicy.requestTarget(rawName)
        if (target == null) {
            showStatus(MusicArtistPolicy.UNKNOWN_ARTIST)
            return
        }
        if (!MusicArtistPolicy.canStartRequest(artistJob?.isActive == true)) return
        openArtistDialog(target)
    }

    /**
     * Диалог исполнителя: аватар, имя, подписчики и песни из `GET /api/artist/…`
     * (песни приходят в том же ответе — второго запроса нет).
     *
     * Состояния честные: «Загружаем…» → список, либо «не найден» (404 — повтор не
     * поможет, кнопки нет), либо «проверьте сеть» + «Повторить». Повтор стартует
     * только при свободном [artistJob], поэтому серия тапов не плодит запросы.
     *
     * Воспроизведение идёт через общий плеер: тап по песне ставит весь список
     * очередью с этой песни ([MarsMusicPlayer.playTrack]), кнопка «В очередь» —
     * добавляет одну песню к текущей очереди ([MarsMusicPlayer.enqueue]).
     * Мини-плеер, вкладка «Очередь» и кнопки трека работают как раньше.
     */
    private fun openArtistDialog(target: String) {
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 28)
            clipToOutline = true
            visibility = View.GONE
        }
        val nameView = TextView(this).apply {
            text = MusicArtistPolicy.initialName(target)
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subscribersView = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        val statusView = TextView(this).apply {
            text = MusicArtistPolicy.LOADING
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(12), 0, dp(10))
        }
        val songsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val retryBtn = MarsDialog.quietButton(this, MusicArtistPolicy.RETRY) {}
        retryBtn.visibility = View.GONE

        // --- секция «Альбомы»: отдельный запрос к поиску (страница исполнителя
        // альбомов не отдаёт), поэтому у неё свои состояние, ошибка и повтор.
        val albumsTitle = TextView(this).apply {
            text = MusicAlbumPolicy.SECTION_TITLE
            textSize = 13f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(4))
            visibility = View.GONE
        }
        val albumsStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(4), 0, dp(6))
            visibility = View.GONE
        }
        val albumsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val albumsRetry = MarsDialog.quietButton(this, MusicAlbumPolicy.RETRY) {}
        albumsRetry.visibility = View.GONE

        /** Обратная связь по действию — в строке статуса диалога (за ним ничего не видно). */
        fun feedback(message: String) {
            statusView.text = message
            statusView.setTextColor(MarsTheme.ACCENT_TEXT)
        }

        fun load() {
            if (!MusicArtistPolicy.canStartRequest(artistJob?.isActive == true)) return
            statusView.text = MusicArtistPolicy.LOADING
            statusView.setTextColor(MarsTheme.TEXT_MUTED)
            retryBtn.visibility = View.GONE
            songsBox.removeAllViews()

            fun renderFailure(notFound: Boolean) {
                statusView.text = MusicArtistPolicy.failureText(notFound = notFound)
                statusView.setTextColor(MarsTheme.ACCENT_TEXT)
                retryBtn.visibility =
                    if (MusicArtistPolicy.retryAvailable(notFound = notFound)) View.VISIBLE else View.GONE
            }

            fun renderArtist(artist: MusicArtist) {
                nameView.text = artist.name
                MusicArtistPolicy.subscribersLabel(artist.subscribers)?.let { label ->
                    subscribersView.text = label
                    subscribersView.visibility = View.VISIBLE
                }
                artist.thumbnail?.let { url ->
                    avatar.visibility = View.VISIBLE
                    ImageLoader.load(url, avatar)
                }
                val songs = MusicArtistPolicy.songs(artist.songs)
                if (songs.isEmpty()) {
                    statusView.text = MusicArtistPolicy.EMPTY_SONGS
                    statusView.setTextColor(MarsTheme.TEXT_MUTED)
                    return
                }
                statusView.text = MusicArtistPolicy.songsLabel(songs.size)
                statusView.setTextColor(MarsTheme.TEXT_MUTED)
                songs.forEachIndexed { index, song ->
                    songsBox.addView(
                        artistSongRow(song, index, songs, ::feedback),
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                    )
                }
            }

            artistJob = lifecycleScope.launch {
                // Отмена (диалог закрыли) — не «сбой сети»: пробрасываем, чтобы не
                // трогать вьюхи уже закрытого диалога.
                val artist = try {
                    withContext(Dispatchers.IO) { repository.getArtist(target) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    renderFailure(notFound = false)
                    return@launch
                }
                if (artist == null) renderFailure(notFound = true) else renderArtist(artist)
            }
        }

        /**
         * Альбомы исполнителя: `GET /api/search?q=<имя>&type=albums` (страница
         * исполнителя их не отдаёт — проверено на живом сервисе).
         *
         * Показ «Загружаем…» → карточки, «Альбомов нет» или «Проверьте сеть» +
         * «Повторить». Повтор гейтится тем же [MusicArtistPolicy.canStartRequest]:
         * серия тапов по «Повторить» не плодит запросы.
         *
         * [shownAlbums] помнит уже показанные карточки: повторный запрос после
         * сбоя может вернуть неполную выдачу, и список не должен «схлопнуться».
         * Порядок карточек доводит [MusicAlbumPolicy.rankForArtist]: поиск отдаёт
         * релевантную выдачу целиком, поэтому альбомы самого исполнителя идут
         * первыми, чужие — следом и не теряются.
         */
        var shownAlbums = emptyList<MusicAlbumSummary>()

        fun loadAlbums() {
            if (!MusicArtistPolicy.canStartRequest(albumsJob?.isActive == true)) return
            albumsTitle.visibility = View.VISIBLE
            albumsStatus.visibility = View.VISIBLE
            albumsStatus.text = MusicAlbumPolicy.LOADING
            albumsStatus.setTextColor(MarsTheme.TEXT_MUTED)
            albumsRetry.visibility = View.GONE
            albumsBox.removeAllViews()
            albumsJob = lifecycleScope.launch {
                val remote = try {
                    withContext(Dispatchers.IO) { repository.searchAlbums(target) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    albumsStatus.text = MusicAlbumPolicy.NETWORK_FAILURE
                    albumsStatus.setTextColor(MarsTheme.ACCENT_TEXT)
                    albumsRetry.visibility = View.VISIBLE
                    return@launch
                }
                // Поиск отдаёт всю релевантную выдачу, а не альбомы этого
                // исполнителя: свои карточки поднимаем наверх, чужие оставляем
                // следом (и не выбрасываем — иначе латиница/кириллица в имени
                // даёт «Альбомов нет» на корректной выдаче).
                shownAlbums = MusicAlbumPolicy.rankForArtist(
                    MusicAlbumPolicy.mergeSummaries(remote, shownAlbums),
                    target,
                )
                if (!MusicAlbumPolicy.hasAlbums(shownAlbums)) {
                    albumsStatus.text = MusicAlbumPolicy.EMPTY
                    albumsStatus.setTextColor(MarsTheme.TEXT_MUTED)
                    return@launch
                }
                albumsStatus.visibility = View.GONE
                shownAlbums.forEach { album ->
                    albumsBox.addView(
                        albumCard(album) { openAlbumDialog(album.browseId) },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                    )
                }
            }
        }

        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, MusicArtistPolicy.TITLE, null))
            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            identity.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))
            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            names.addView(nameView)
            names.addView(subscribersView)
            identity.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(identity)
            root.addView(statusView)
            root.addView(songsBox, LinearLayout.LayoutParams(-1, -2))
            root.addView(retryBtn)
            root.addView(albumsTitle)
            root.addView(albumsStatus)
            root.addView(albumsBox, LinearLayout.LayoutParams(-1, -2))
            root.addView(albumsRetry)
            root.addView(MarsDialog.quietButton(this, MusicArtistPolicy.CLOSE) { dialogRef?.dismiss() })
            retryBtn.setOnClickListener { load() }
            albumsRetry.setOnClickListener { loadAlbums() }
            emptyList()
        }
        // Закрыли диалог — незавершённые запросы (песни и альбомы) больше не нужны.
        dialogRef?.setOnDismissListener {
            artistJob?.cancel()
            albumsJob?.cancel()
        }
        load()
        loadAlbums()
    }

    /**
     * Строка песни в диалоге исполнителя: обложка, название, длительность.
     * Тап по строке играет список с этой песни, кнопка — добавляет песню в очередь.
     */
    private fun artistSongRow(
        track: MusicTrack,
        index: Int,
        queue: List<MusicTrack>,
        feedback: (String) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 12)
            isClickable = true
            isFocusable = true
            contentDescription = "Играть: ${track.title}"
            setOnClickListener {
                MarsMusicPlayer.playTrack(index, queue)
                feedback("Играет: ${track.title}")
            }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
            clipToOutline = true
        }
        track.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, cover) }
        row.addView(cover, LinearLayout.LayoutParams(dp(40), dp(40)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        info.addView(TextView(this).apply {
            text = track.title
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = track.duration
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val enqueueBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_playlist_add,
            "В очередь: ${track.title}",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 32,
        ).apply {
            setOnClickListener {
                MarsMusicPlayer.enqueue(track)
                feedback("Добавлено в очередь: ${track.title}")
            }
        }
        row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        return row
    }

    /**
     * Карточка альбома в секции «Альбомы»: обложка 48dp, название, исполнитель и год.
     *
     * Треки здесь не тянутся: карточка открывает диалог альбома, поэтому список из
     * N альбомов — это один запрос, а не N запросов за треками.
     */
    private fun albumCard(album: MusicAlbumSummary, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            // TalkBack читает ДЕЙСТВИЕ тапа — как у строк треков («Играть: …»).
            contentDescription = MusicAlbumPolicy.cardDescription(album)
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 6)
            clipToOutline = true
        }
        album.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, cover) }
        row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        info.addView(TextView(this).apply {
            text = album.title
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subtitle = MusicAlbumPolicy.subtitle(album)
        if (subtitle.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /**
     * Диалог альбома: обложка, название, исполнитель, год/число треков и треки из
     * `GET /api/album/{browseId}`.
     *
     * browseId проверяется здесь ещё раз: секция «Альбомы» берёт его из выдачи
     * поиска, но диалог — отдельная точка входа, а сервер на чужой префикс отвечает
     * кодом 500 (`must start with MPRE`). Локальная проверка не даёт показать
     * «проверьте сеть» там, где виноват id.
     *
     * Состояния честные, как у диалога исполнителя: «Загружаем…» → треки, либо
     * «Альбом не найден» (это ответ сервера, повтор не поможет), либо «проверьте
     * сеть» + «Повторить» (повтор гейтится [MusicArtistPolicy.canStartRequest], так
     * что серия тапов не плодит запросы). Тап по треку играет весь альбом с этой
     * песни ([MarsMusicPlayer.playTrack]), «В очередь» — добавляет одну
     * ([MarsMusicPlayer.enqueue]); мини-плеер и вкладка «Очередь» не затрагиваются.
     */
    private fun openAlbumDialog(rawBrowseId: String) {
        val browseId = MusicAlbumPolicy.requestTarget(rawBrowseId)
        if (browseId == null) {
            showStatus(MusicAlbumPolicy.NOT_FOUND)
            return
        }
        if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 8)
            clipToOutline = true
            visibility = View.GONE
        }
        val titleView = TextView(this).apply {
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artistView = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        val metaView = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        val statusView = TextView(this).apply {
            text = MusicAlbumPolicy.LOADING_ALBUM
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, dp(12), 0, dp(10))
        }
        val tracksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val retryBtn = MarsDialog.quietButton(this, MusicAlbumPolicy.RETRY) {}
        retryBtn.visibility = View.GONE

        /** Обратная связь по действию — в строке статуса (за диалогом ничего не видно). */
        fun feedback(message: String) {
            statusView.visibility = View.VISIBLE
            statusView.text = message
            statusView.setTextColor(MarsTheme.ACCENT_TEXT)
        }

        fun load() {
            if (!MusicArtistPolicy.canStartRequest(albumJob?.isActive == true)) return
            statusView.visibility = View.VISIBLE
            statusView.text = MusicAlbumPolicy.LOADING_ALBUM
            statusView.setTextColor(MarsTheme.TEXT_MUTED)
            retryBtn.visibility = View.GONE
            tracksBox.removeAllViews()

            fun renderFailure(notFound: Boolean) {
                statusView.text = MusicAlbumPolicy.failureText(notFound = notFound)
                statusView.setTextColor(MarsTheme.ACCENT_TEXT)
                retryBtn.visibility =
                    if (MusicAlbumPolicy.retryAvailable(notFound = notFound)) View.VISIBLE else View.GONE
            }

            fun renderAlbum(album: MusicAlbum) {
                titleView.text = album.title
                if (album.artist.isNotBlank()) {
                    artistView.text = album.artist
                    artistView.visibility = View.VISIBLE
                }
                val meta = MusicAlbumPolicy.meta(album.year, album.trackCount)
                if (meta.isNotEmpty()) {
                    metaView.text = meta
                    metaView.visibility = View.VISIBLE
                }
                album.thumbnail?.let { url ->
                    cover.visibility = View.VISIBLE
                    ImageLoader.load(url, cover)
                }
                if (album.tracks.isEmpty()) {
                    statusView.text = MusicAlbumPolicy.EMPTY_TRACKS
                    statusView.setTextColor(MarsTheme.TEXT_MUTED)
                    return
                }
                statusView.visibility = View.GONE
                album.tracks.forEachIndexed { index, track ->
                    // Тот же ряд, что в диалоге исполнителя: тап играет альбом с этой
                    // песни, кнопка добавляет одну в очередь.
                    tracksBox.addView(
                        artistSongRow(track, index, album.tracks, ::feedback),
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                    )
                }
            }

            albumJob = lifecycleScope.launch {
                // Отмена (диалог закрыли) — не «сбой сети»: пробрасываем, чтобы не
                // трогать вьюхи уже закрытого диалога.
                val result = try {
                    withContext(Dispatchers.IO) { repository.getAlbum(browseId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    renderFailure(notFound = false)
                    return@launch
                }
                when (result) {
                    is AlbumResult.Loaded -> renderAlbum(result.album)
                    AlbumResult.InvalidBrowseId -> renderFailure(notFound = true)
                    is AlbumResult.Failed -> renderFailure(notFound = false)
                }
            }
        }

        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, MusicAlbumPolicy.TITLE, null))
            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            identity.addView(cover, LinearLayout.LayoutParams(dp(96), dp(96)))
            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            names.addView(titleView)
            names.addView(artistView)
            names.addView(metaView)
            identity.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(identity)
            root.addView(statusView)
            root.addView(tracksBox, LinearLayout.LayoutParams(-1, -2))
            root.addView(retryBtn)
            root.addView(MarsDialog.quietButton(this, MusicAlbumPolicy.CLOSE) { dialogRef?.dismiss() })
            retryBtn.setOnClickListener { load() }
            emptyList()
        }
        // Закрыли диалог — незавершённый запрос больше не нужен.
        dialogRef?.setOnDismissListener { albumJob?.cancel() }
        load()
    }

    /** Prefetches favorite ids so hearts render filled on the charts/search rows. */
    private fun refreshFavoriteIds() {
        lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) { runCatching { repository.getFavorites() }.getOrNull() }
                ?: return@launch
            favoriteIds.clear()
            favoriteIds += favs.map { it.id }
            favoriteIds.toList().forEach { refreshHeart(it) }
        }
    }

    private fun refreshHeart(id: String) {
        val filled = favoriteIds.contains(id)
        heartButtons[id]?.forEach { button ->
            button.setImageResource(if (filled) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            button.imageTintList = ColorStateList.valueOf(if (filled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED)
            // MUS-M3: the heart announces the ACTION the tap performs on this track
            button.contentDescription = MusicA11yPolicy.heartAction(filled)
        }
    }

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

    internal fun onHeartClicked(track: MusicTrack, button: ImageButton) {
        button.isEnabled = false // debounce double taps while the request is in flight
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.toggleFavorite(track.id, track) }.getOrNull()
            }
            button.isEnabled = true
            when (result) {
                true -> {
                    favoriteIds += track.id
                    refreshHeart(track.id)
                    showStatus("Добавлено в избранное: ${track.title}")
                }
                false -> {
                    favoriteIds -= track.id
                    refreshHeart(track.id)
                    showStatus("Удалено из избранного: ${track.title}")
                    if (currentTab == MusicTab.FAVORITES) {
                        val rest = tracks.filterNot { it.id == track.id }
                        if (rest.isEmpty()) loadFavorites() else showTracks(rest)
                    }
                }
                null -> showStatus("Избранное недоступно")
            }
        }
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

    /**
     * «Звук»: тёмный обсидиановый [MarsDialog] с тумблером обработки звука, выбором
     * пресета эквалайзера (паритет с WebUI EQ_PRESETS) и ползунком Bass Boost.
     * Каждое изменение сразу уходит в живой плеер ([MarsMusicPlayer.setAudioFx]) и
     * сохраняется через [AudioFxSettings]; тело диалога перерисовывается на месте.
     */
    internal fun onEqClicked() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Звук", audioFx.state.preset.title))
            renderEqBody(body)
            root.addView(body, LinearLayout.LayoutParams(-1, -2))
            root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
            emptyList()
        }
    }

    /** Fills the dialog body: on/off toggle, preset stack, Bass Boost slider. */
    private fun renderEqBody(body: LinearLayout) {
        body.removeAllViews()
        val state = audioFx.state

        body.addView(MarsDialog.caption(this, "Обработка звука"))
        val toggle = MarsDialog.pill(
            this,
            if (state.enabled) "Включена" else "Выключена",
            bg = if (state.enabled) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
            fg = if (state.enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED,
            border = if (state.enabled) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
        ) {
            audioFx.setEnabled(!state.enabled)
            onAudioFxChanged()
            renderEqBody(body)
        }
        body.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        body.addView(MarsDialog.caption(this, "Пресет эквалайзера"))
        AudioFxPreset.values().forEach { preset ->
            val selected = state.preset == preset
            val pill = MarsDialog.pill(
                this,
                preset.title,
                bg = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                fg = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
                border = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
            ) {
                audioFx.setPreset(preset)
                onAudioFxChanged()
                renderEqBody(body)
            }
            body.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val bassLabel = TextView(this).apply {
            text = bassBoostCaption(state.bassBoost)
            textSize = 10f
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        body.addView(bassLabel, LinearLayout.LayoutParams(-1, -2))
        val bassSeek = SeekBar(this).apply {
            max = AudioFxPolicy.MAX_BASS_BOOST
            progress = state.bassBoost
            progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            isEnabled = state.enabled
            alpha = if (state.enabled) 1f else 0.4f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    audioFx.setBassBoost(value)
                    bassLabel.text = bassBoostCaption(audioFx.state.bassBoost)
                    onAudioFxChanged()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        body.addView(bassSeek, LinearLayout.LayoutParams(-1, -2))
    }

    /** Pushes the persisted state into the live player and refreshes the header icon. */
    private fun onAudioFxChanged() {
        MarsMusicPlayer.setAudioFx(audioFx.state)
        updateEqIcon()
    }

    /** Header «Звук» glows ruby while sound processing is enabled. */
    private fun updateEqIcon() {
        if (!::eqBtn.isInitialized || !::audioFx.isInitialized) return
        eqBtn.imageTintList = ColorStateList.valueOf(
            if (audioFx.state.enabled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
        )
    }

    /** Opens the explicit original/standard/economy stream profile picker. */
    internal fun onQualityClicked() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Качество потока", streamQuality.value.label))
            renderQualityBody(body)
            root.addView(body, LinearLayout.LayoutParams(-1, -2))
            root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
            emptyList()
        }
    }

    /** Renders selectable quality pills and applies a choice without rebuilding the screen. */
    private fun renderQualityBody(body: LinearLayout) {
        body.removeAllViews()
        val selected = streamQuality.value
        body.addView(MarsDialog.caption(this, "Профиль аудиопотока"))
        StreamQuality.selectable.forEach { quality ->
            val isSelected = quality == selected
            val pill = MarsDialog.pill(
                this,
                quality.label,
                bg = if (isSelected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                fg = if (isSelected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
                border = if (isSelected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
            ) {
                streamQuality.set(quality)
                MarsMusicPlayer.setStreamQuality(quality)
                updateQualityIcon()
                renderQualityBody(body)
            }
            body.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    /** Highlights the active explicit quality profile in the header. */
    private fun updateQualityIcon() {
        if (!::qualityBtn.isInitialized || !::streamQuality.isInitialized) return
        qualityBtn.imageTintList = ColorStateList.valueOf(
            if (streamQuality.value.isExplicit) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
        )
        qualityBtn.contentDescription = "Качество потока: ${streamQuality.value.label}"
    }

    /** «BASS BOOST · 65%»: hardware scale 0..1000 shown as percent, WebUI-style caption. */
    private fun bassBoostCaption(strength: Int): String =
        "BASS BOOST · ${AudioFxPolicy.sanitizeStrength(strength) / 10}%"

    // Карта секций (W1): каркас/шапка/вкладки/статус — MusicShellSection.kt; строка
    // поиска, подсказки и история — MusicSearchSection.kt; панель плеера и отрисовка
    // состояния — MusicPlayerBarSection.kt; список треков и строка трека —
    // MusicTrackListSection.kt (+ MusicTrackSubtitleBinder.kt); отменяемый набор
    // запросов — MusicJobSet.kt. В хосте остались поля, lifecycle, диспетчер вкладок
    // ([loadTab]) и геттеры готовности видов.
    companion object {
        /** Request code for the Android 13+ POST_NOTIFICATIONS runtime prompt. */
        private const val REQUEST_NOTIFICATIONS = 0x4D54
        /** Progress ticker period while the screen is visible (thumb + time label). */
        private const val TICKER_INTERVAL_MS = 500L
    }
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
