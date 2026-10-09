package ru.mars.mobile

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository
import java.net.HttpURLConnection
import java.net.URL

class MusicActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    private lateinit var repository: MusicRepository
    private lateinit var results: LinearLayout
    private lateinit var title: TextView
    private lateinit var artistView: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: ImageButton
    private lateinit var artwork: ImageView
    private var tracks = emptyList<MusicTrack>()
    private var job: Job? = null
    /** Local history of successful searches (newest first); pure policy in [MusicSearchHistoryPolicy]. */
    private lateinit var searchHistory: MusicSearchHistory
    /** Monotonic id of the in-flight search run; stale runs are dropped by [SearchRunGuard]. */
    private var searchRunId = 0

    /** Which pill tab is active: chart, personal flow, favorites, or the live playback queue. */
    private enum class MusicTab { CHART, FLOW, FAVORITES, QUEUE }

    private var currentTab = MusicTab.CHART
    private val favoriteIds = linkedSetOf<String>()
    private val heartButtons = HashMap<String, MutableList<ImageButton>>()

    /** Rendered chart/search row views per track id, so playback state can re-style them live. */
    private class TrackRowHolder(
        val trackId: String,
        val artist: String,
        val duration: String,
        val row: View,
        val titleView: TextView,
        val subTitleView: TextView,
        val playButton: ImageButton,
    )
    private val trackRows = HashMap<String, MutableList<TrackRowHolder>>()
    private lateinit var chartTabButton: TextView
    private lateinit var flowTabButton: TextView
    private lateinit var favoritesTabButton: TextView
    private lateinit var queueTabButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearchBtn: ImageButton
    /** Overlay with recent searches; floats above the results list, never replaces it. */
    private lateinit var historyOverlay: ScrollView
    private lateinit var historyPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    /** «Радио по треку» in the player bar: appends similar tracks to the queue. */
    private lateinit var radioBtn: ImageButton
    /** «Текст песни» in the player bar: opens the lyrics dialog for the current track. */
    private lateinit var lyricsBtn: ImageButton
    /** «Звук» in the header: opens the equalizer/preset/Bass Boost dialog. */
    private lateinit var eqBtn: ImageButton
    /** Persisted sound settings (preset, on/off, Bass Boost); pure logic in [AudioFxPolicy]. */
    private lateinit var audioFx: AudioFxSettings
    /** In-flight radio fetch; guards against double taps (same debounce as [lyricsJob]). */
    private var radioJob: Job? = null
    /** In-flight lyrics fetch; the dialog body is updated when it completes. */
    private var lyricsJob: Job? = null
    /** True while the user's finger owns the seek thumb: the ticker must not fight the drag. */
    private var isDraggingSeek = false
    /** Last dragged-to position; applied to the player once, on release (see [SeekDragPolicy]). */
    private var pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
    private val ticker = object : Runnable {
        override fun run() {
            if (::seek.isInitialized) {
                // Skip thumb/time updates mid-drag, otherwise the 500 ms ticker keeps
                // snapping the thumb back and precise seeking on touch is impossible.
                if (SeekDragPolicy.tickerMayUpdate(isDraggingSeek)) {
                    seek.progress = MarsMusicPlayer.currentPosition
                    time.text = "${fmt(MarsMusicPlayer.currentPosition)} / ${fmt(MarsMusicPlayer.duration)}"
                }
                seek.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        repository = MusicRepository(BASE_URL)
        searchHistory = MusicSearchHistory(
            PrefsMusicSearchHistoryStorage(getSharedPreferences("mars-music-search-history", MODE_PRIVATE))
        )
        audioFx = AudioFxSettings(
            PrefsAudioFxStorage(getSharedPreferences("mars-music-audiofx", MODE_PRIVATE))
        )
        // The process-scoped player re-applies the saved preset to every fresh audio session.
        MarsMusicPlayer.setAudioFx(audioFx.state)
        setContentView(buildView())
        MarsMusicPlayer.registerListener(this)
        seek.post(ticker)
        updateTabStyles()
        updateQueueIcons()
        updateEqIcon()
        refreshFavoriteIds()
        loadHome()
    }

    override fun onDestroy() {
        MarsMusicPlayer.unregisterListener(this)
        seek.removeCallbacks(ticker)
        job?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val backBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_arrow_back,
            "Назад",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT
        ).apply {
            setOnClickListener { finish() }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

        val titleHeader = TextView(this).apply {
            text = "Музыка Марса"
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        header.addView(titleHeader, LinearLayout.LayoutParams(0, -2, 1f))

        eqBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_volume,
            "Эквалайзер и звук",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { onEqClicked() }
        }
        header.addView(eqBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        // Tabs: Чарт / Волна / Избранное / Очередь (pill toggles). Horizontally scrollable:
        // four weighted pills clip «Избранное» on narrow screens, chips must stay whole.
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chartTabButton = tabButton("Чарт") { selectTab(MusicTab.CHART) }
        flowTabButton = tabButton("Волна") { selectTab(MusicTab.FLOW) }
        favoritesTabButton = tabButton("Избранное") { selectTab(MusicTab.FAVORITES) }
        queueTabButton = tabButton("Очередь") { selectTab(MusicTab.QUEUE) }
        tabRow.addView(chartTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        tabRow.addView(flowTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        tabRow.addView(favoritesTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        tabRow.addView(queueTabButton, LinearLayout.LayoutParams(-2, -2))
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, ViewGroup.LayoutParams(-2, -2))
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Transient status line (favorites/queue feedback), hidden by default
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(4), 0, dp(4), dp(8))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        // Search Bar (Floating Capsule)
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 14)
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        searchInput = EditText(this).apply {
            hint = "Поиск трека или исполнителя…"
            setSingleLine()
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 15f
            background = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search(searchInput.text.toString())
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
            // The history overlay follows the focus: shown only while the
            // field is empty and focused (pure SearchHistoryPanelPolicy).
            setOnFocusChangeListener { _, _ -> updateSearchHistoryOverlay() }
        }
        searchContainer.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))

        // Clear-search (visible only while the field has text); reloads the active tab
        clearSearchBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_close,
            "Очистить поиск",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 28
        ).apply {
            visibility = View.GONE
            setOnClickListener {
                searchInput.setText("")
                visibility = View.GONE
                hideKeyboard()
                reloadActiveTab()
            }
        }
        searchContainer.addView(
            clearSearchBtn,
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) }
        )
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearSearchBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                updateSearchHistoryOverlay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val searchBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_search,
            "Найти",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 36
        ).apply {
            setOnClickListener { search(searchInput.text.toString()) }
        }
        searchContainer.addView(searchBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(searchContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Track list; the search-history overlay floats above it (FrameLayout),
        // so showing history never displaces the tab content or the player.
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply {
            addView(results, ViewGroup.LayoutParams(-1, -2))
            isFillViewport = true
        }
        historyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_CARD, radiusDp = 14, strokeColor = MarsTheme.BORDER)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        historyOverlay = ScrollView(this).apply {
            addView(historyPanel, ViewGroup.LayoutParams(-1, -2))
            visibility = View.GONE
            // Swallow taps so they don't fall through to the list underneath.
            isClickable = true
            isFocusable = true
        }
        val listArea = FrameLayout(this).apply {
            addView(listScroll, FrameLayout.LayoutParams(-1, -1))
            addView(historyOverlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        root.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom Player Bar
        val playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_CARD, radiusDp = 18, strokeColor = MarsTheme.BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 8)
            clipToOutline = true
        }
        trackRow.addView(artwork, LinearLayout.LayoutParams(dp(52), dp(52)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            text = "Ничего не играет"
            textSize = 15f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(title)

        artistView = TextView(this).apply {
            text = "Mars Audio"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        info.addView(artistView)

        trackRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        // Per-track actions: «Радио» (append similar tracks) and «Текст песни» (lyrics dialog).
        // Disabled until something is loaded in the player (see updatePlayerActionStates).
        radioBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_radio,
            "Радио по треку",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { onRadioClicked() }
        }
        trackRow.addView(radioBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })

        lyricsBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_lyrics,
            "Текст песни",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { onLyricsClicked() }
        }
        trackRow.addView(lyricsBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })

        playerPanel.addView(trackRow)

        // Progress Seek Bar
        seek = SeekBar(this).apply {
            max = 1
            progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    // During the drag only the time label previews the position; the player
                    // is seeked once on release — no MediaPlayer.seekTo spam mid-gesture.
                    pendingSeekMs = p
                    time.text = "${fmt(p)} / ${fmt(MarsMusicPlayer.duration)}"
                }
                override fun onStartTrackingTouch(b: SeekBar?) { isDraggingSeek = true }
                override fun onStopTrackingTouch(b: SeekBar?) {
                    isDraggingSeek = false
                    SeekDragPolicy.pendingSeekTarget(pendingSeekMs, MarsMusicPlayer.duration)
                        ?.let { MarsMusicPlayer.seekTo(it) }
                    pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
                }
            })
        }
        playerPanel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val timeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        time = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
        }
        timeRow.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        playerPanel.addView(timeRow)

        // Controls (Shuffle, Previous, Play/Pause, Next, Repeat)
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        shuffleBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_shuffle,
            "Перемешать",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 38
        ).apply {
            setOnClickListener { MarsMusicPlayer.toggleShuffle(); updateQueueIcons() }
        }
        controls.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })

        val prevBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_prev,
            "Предыдущий трек",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 42
        ).apply {
            setOnClickListener { MarsMusicPlayer.previous() }
        }
        controls.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        playButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            "Воспроизведение",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 50,
            strokeColor = 0
        ).apply {
            setOnClickListener { MarsMusicPlayer.togglePlay() }
        }
        val playParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        controls.addView(playButton, playParams)

        val nextBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_next,
            "Следующий трек",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 42
        ).apply {
            setOnClickListener { MarsMusicPlayer.next() }
        }
        controls.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        repeatBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_repeat,
            "Повтор",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 38
        ).apply {
            setOnClickListener { MarsMusicPlayer.cycleRepeatMode(); updateQueueIcons() }
        }
        controls.addView(repeatBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })

        playerPanel.addView(controls)
        root.addView(playerPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        return root
    }

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) {
        runOnUiThread {
            title.text = track?.title ?: "Ничего не играет"
            artistView.text = track?.artist ?: "Mars Audio"
            playButton.setImageDrawable(
                ContextCompat.getDrawable(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            )
            seek.max = MarsMusicPlayer.duration.coerceAtLeast(1)
            updateQueueIcons()
            updatePlayerActionStates()
            refreshTrackRows()
            // Keep the queue tab in sync with player state (active track, play/pause,
            // auto-advance): re-render it whenever the queue view is on screen.
            if (currentTab == MusicTab.QUEUE && ::results.isInitialized) renderQueue()
            track?.thumbnail?.let {
                artwork.visibility = View.VISIBLE
                ImageLoader.load(it, artwork)
            } ?: run {
                artwork.visibility = View.GONE
            }
        }
    }

    override fun onProgress(currentMs: Int, totalMs: Int) {
        runOnUiThread {
            seek.max = totalMs.coerceAtLeast(1)
            if (isDraggingSeek) return@runOnUiThread // thumb belongs to the finger, not the player
            seek.progress = currentMs
            time.text = "${fmt(currentMs)} / ${fmt(totalMs)}"
        }
    }

    private fun loadHome() {
        job?.cancel()
        showLoading()
        job = lifecycleScope.launch {
            // Charts go through the repository: {success:false,error} at HTTP 200 is an
            // exception there, so a server rejection can never masquerade as an empty
            // «Треки не найдены» list — both endpoints failing shows the error/retry UI.
            val outcome = withContext(Dispatchers.IO) {
                MusicChartLoader.resolve(runCatching { repository.getCharts() }) {
                    runCatching { MusicChartLoader.parseHomePayload(fetchHomeText()) }
                }
            }
            when (outcome) {
                is MusicChartLoader.Outcome.Tracks -> showTracks(outcome.items)
                is MusicChartLoader.Outcome.Failed -> showHomeError()
            }
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
    private fun showListError(message: String, retryDescription: String, onRetry: () -> Unit) {
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

    /**
     * Runs one search as a guarded run: supersedes the previous job and tags the
     * new one with a monotonic [searchRunId]. Only a live, current run may touch
     * the UI or the history ([SearchRunGuard]) — a cancelled/superseded run never
     * shows an error, never overwrites a stale tab and never enters the history.
     * Only a *successful* search is recorded ([MusicSearchHistory.recordSuccess]).
     */
    private fun search(rawQuery: String) {
        val q = MusicSearchHistoryPolicy.normalize(rawQuery) ?: return
        job?.cancel()
        val runId = ++searchRunId
        showSearchLoading(q)
        job = lifecycleScope.launch {
            val found = try {
                repository.search(q)
            } catch (error: Exception) {
                // Cancellation (new query, tab switch, clear-search) is not a failure.
                if (SearchRunGuard.isCancellation(error)) throw error
                // HTTP/parse failure: the previous (chart) cards must not stay
                // clickable — drop them plus the tracks list and show the same
                // error/retry state, with a retry for this very query.
                if (SearchRunGuard.mayApplyResult(runId, searchRunId, !isActive)) {
                    showStatus(null)
                    showListError(
                        message = "Поиск недоступен — проверьте сеть",
                        retryDescription = "Повторить поиск",
                        onRetry = { search(q) },
                    )
                }
                return@launch
            }
            if (!SearchRunGuard.mayApplyResult(runId, searchRunId, !isActive)) return@launch
            searchHistory.recordSuccess(q)
            showStatus(null)
            showTracks(found)
        }
    }

    /** Explicit «searching» placeholder so the list never looks stale/empty mid-search. */
    private fun showSearchLoading(query: String) {
        showStatus(null)
        resetList()
        results.addView(TextView(this).apply {
            text = "Ищем «$query»…"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
    }

    /**
     * Re-evaluates overlay visibility (pure [SearchHistoryPanelPolicy]): shown
     * only while the search field is empty and focused and the history is not
     * empty; the overlay floats above the list, so tab content and the player
     * stay untouched underneath.
     */
    private fun updateSearchHistoryOverlay() {
        if (!::historyOverlay.isInitialized || !::searchHistory.isInitialized) return
        val visible = SearchHistoryPanelPolicy.isVisible(
            fieldText = searchInput.text?.toString(),
            fieldFocused = searchInput.hasFocus(),
            historyEmpty = searchHistory.entries.isEmpty(),
        )
        if (visible) renderSearchHistory()
        historyOverlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Fills the overlay: header with «Очистить» plus one tappable row per stored query. */
    private fun renderSearchHistory() {
        historyPanel.removeAllViews()
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "История поиска"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "Очистить"
            textSize = 13f
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = MarsTheme.button(this@MusicActivity, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
            isClickable = true
            isFocusable = true
            contentDescription = "Очистить историю поиска"
            setOnClickListener {
                searchHistory.clear()
                showStatus("История поиска очищена")
                updateSearchHistoryOverlay()
            }
        }, LinearLayout.LayoutParams(-2, -2))
        historyPanel.addView(header, LinearLayout.LayoutParams(-1, -2))

        searchHistory.entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(6), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onHistoryEntryClicked(entry) }
            }
            row.addView(TextView(this).apply {
                text = entry
                textSize = 14f
                setTextColor(MarsTheme.TEXT_STRONG)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val deleteBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_trash,
                "Удалить из истории: $entry",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 30
            ).apply {
                setOnClickListener {
                    searchHistory.delete(entry)
                    showStatus("Удалено из истории: $entry")
                    updateSearchHistoryOverlay()
                }
            }
            row.addView(deleteBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
            historyPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Tapping a history row repeats that search: text back into the field, overlay hides. */
    private fun onHistoryEntryClicked(entry: String) {
        searchInput.setText(entry) // the watcher hides the overlay and reveals the clear button
        hideKeyboard()
        search(entry)
    }

    /**
     * Renders a track list (chart/search/favorites/flow rows). [header] is an optional
     * section card placed above the rows — used by «Волна» for its stream action.
     */
    private fun showTracks(items: List<MusicTrack>, header: View? = null) {
        tracks = items
        resetList()
        header?.let { results.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        if (items.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "Треки не найдены"
                setTextColor(MarsTheme.TEXT_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }

        items.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 12)
                setOnClickListener { onTrackRowClicked(i) }
            }

            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
                clipToOutline = true
            }
            val thumb = t.thumbnail
            if (!thumb.isNullOrBlank()) {
                ImageLoader.load(thumb, image)
            }
            row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

            val trackInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            val titleView = TextView(this).apply {
                text = t.title
                textSize = 15f
                setTextColor(MarsTheme.TEXT_STRONG)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            trackInfo.addView(titleView)
            val subTitleView = TextView(this).apply {
                text = "${t.artist} · ${t.duration}"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            trackInfo.addView(subTitleView)
            row.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))

            val playTrackBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_play,
                "Воспроизвести",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = MarsTheme.ACCENT,
                sizeDp = 34
            ).apply {
                setOnClickListener { onTrackRowClicked(i) }
            }
            row.addView(playTrackBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

            // Favorite heart: filled+accent when the track is already in favorites
            val isFav = favoriteIds.contains(t.id)
            val heartBtn = MarsTheme.circleIconButton(
                this,
                if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                "Избранное",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = if (isFav) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED,
                sizeDp = 34
            ).apply {
                setOnClickListener { onHeartClicked(t, this) }
            }
            heartButtons.getOrPut(t.id) { mutableListOf() }.add(heartBtn)
            row.addView(heartBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

            // Quick add to the playback queue
            val enqueueBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_playlist_add,
                "В очередь",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 34
            ).apply {
                setOnClickListener { onQueueAddClicked(t) }
            }
            row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

            results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

            // Register the row so player state changes can highlight the current track live.
            val holder = TrackRowHolder(t.id, t.artist, t.duration, row, titleView, subTitleView, playTrackBtn)
            trackRows.getOrPut(t.id) { mutableListOf() }.add(holder)
            applyTrackRowState(holder)
        }
    }

    private fun tabButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(dp(14), dp(9), dp(14), dp(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateTabStyles() {
        fun style(button: TextView, active: Boolean) {
            button.setTextColor(if (active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
            button.background = if (active) {
                MarsTheme.pill(this, MarsTheme.ACCENT_BG, MarsTheme.ACCENT)
            } else {
                MarsTheme.pill(this, MarsTheme.SURFACE)
            }
        }
        style(chartTabButton, currentTab == MusicTab.CHART)
        style(flowTabButton, currentTab == MusicTab.FLOW)
        style(favoritesTabButton, currentTab == MusicTab.FAVORITES)
        style(queueTabButton, currentTab == MusicTab.QUEUE)
    }

    private fun selectTab(tab: MusicTab) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabStyles()
        showStatus(null)
        job?.cancel()
        when (tab) {
            MusicTab.CHART -> loadHome()
            MusicTab.FLOW -> loadFlow()
            MusicTab.FAVORITES -> loadFavorites()
            MusicTab.QUEUE -> renderQueue()
        }
    }

    /** Re-loads the content of the currently active tab (used by the clear-search button). */
    private fun reloadActiveTab() {
        showStatus(null)
        job?.cancel()
        when (currentTab) {
            MusicTab.CHART -> loadHome()
            MusicTab.FLOW -> loadFlow()
            MusicTab.FAVORITES -> loadFavorites()
            MusicTab.QUEUE -> renderQueue()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /**
     * Renders the live playback queue from [MarsMusicPlayer]: header with a clear action,
     * then one row per queued track. The active track gets an accent border and accent
     * title; tapping a row jumps to it, the trash button removes it from the queue.
     */
    private fun renderQueue() {
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
                iconTint = if (active) Color.WHITE else MarsTheme.ACCENT,
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

    private fun showStatus(message: String?) {
        if (!::statusText.isInitialized) return
        statusText.text = message.orEmpty()
        statusText.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
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
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        playFlowBtn.addView(TextView(this).apply {
            text = "Включить поток"
            textSize = 14f
            setTextColor(Color.WHITE)
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
    private fun onRadioClicked() {
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
    private fun onLyricsClicked() {
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
                        bodyView.text = state.body
                        bodyView.setTextColor(MarsTheme.TEXT_EM)
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

    /** Radio/lyrics act on the current track only: dim and disable both without one. */
    private fun updatePlayerActionStates() {
        if (!::radioBtn.isInitialized || !::lyricsBtn.isInitialized) return
        val hasTrack = MarsMusicPlayer.currentTrack != null
        val busy = radioJob?.isActive == true
        radioBtn.isEnabled = hasTrack && !busy
        radioBtn.alpha = if (hasTrack && !busy) 1f else 0.4f
        lyricsBtn.isEnabled = hasTrack
        lyricsBtn.alpha = if (hasTrack) 1f else 0.4f
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
        }
    }

    /** Clears the rendered list plus per-row caches (hearts, highlighted rows). */
    private fun resetList() {
        results.removeAllViews()
        heartButtons.clear()
        trackRows.clear()
    }

    /** Applies the pure [TrackRowUiState] to a rendered row: accent card, title, play/pause icon. */
    private fun applyTrackRowState(holder: TrackRowHolder) {
        val state = TrackRowUiState.resolve(holder.trackId, MarsMusicPlayer.currentTrack?.id, MarsMusicPlayer.isPlaying)
        holder.row.background = MarsTheme.card(
            this,
            if (state.active) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
            radiusDp = 12,
            strokeColor = if (state.active) MarsTheme.ACCENT else MarsTheme.BORDER,
        )
        holder.titleView.setTextColor(if (state.active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
        holder.titleView.typeface = if (state.active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.subTitleView.text = if (state.active && MarsMusicPlayer.isPlaying) {
            "Сейчас играет · ${holder.artist}"
        } else {
            "${holder.artist} · ${holder.duration}"
        }
        holder.playButton.setImageResource(if (state.showPauseIcon) R.drawable.ic_pause else R.drawable.ic_play)
        holder.playButton.contentDescription = if (state.showPauseIcon) "Пауза" else "Воспроизвести"
    }

    /** Re-styles every rendered chart/search row from the current player state (UI thread). */
    private fun refreshTrackRows() {
        trackRows.values.forEach { holders -> holders.forEach(::applyTrackRowState) }
    }

    /** Row tap: an inactive row starts playback, the active row toggles play/pause. */
    private fun onTrackRowClicked(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        val state = TrackRowUiState.resolve(track.id, MarsMusicPlayer.currentTrack?.id, MarsMusicPlayer.isPlaying)
        if (state.tapStartsPlayback) MarsMusicPlayer.playTrack(index, tracks) else MarsMusicPlayer.togglePlay()
    }

    private fun onHeartClicked(track: MusicTrack, button: ImageButton) {
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

    private fun onQueueAddClicked(track: MusicTrack) {
        MarsMusicPlayer.enqueue(track)
        // Real state feedback: a finished/empty session resumes on this very track.
        showStatus(
            if (MarsMusicPlayer.currentTrack?.id == track.id) "Играет: ${track.title}"
            else "Добавлено в очередь: ${track.title}"
        )
    }

    private fun updateQueueIcons() {
        if (!::shuffleBtn.isInitialized || !::repeatBtn.isInitialized) return
        shuffleBtn.imageTintList = ColorStateList.valueOf(
            if (MarsMusicPlayer.isShuffled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
        )
        val repeatOne = MarsMusicPlayer.repeatMode == MusicQueueManager.RepeatMode.ONE
        repeatBtn.setImageResource(if (repeatOne) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatBtn.imageTintList = ColorStateList.valueOf(
            if (MarsMusicPlayer.repeatMode == MusicQueueManager.RepeatMode.OFF) MarsTheme.TEXT_MUTED else MarsTheme.ACCENT
        )
    }

    /** Raw body of the `/api/home` fallback; typed parsing lives in [MusicChartLoader]. */
    private fun fetchHomeText(): String {
        val c = URL("$BASE_URL/api/home").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect() // also on timeouts/parse failures, not just the happy path
        }
    }

    private fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, (ms / 1000) % 60)
    private fun dp(v: Int) = MarsTheme.dp(this, v)

    /**
     * «Звук»: тёмный обсидиановый [MarsDialog] с тумблером обработки звука, выбором
     * пресета эквалайзера (паритет с WebUI EQ_PRESETS) и ползунком Bass Boost.
     * Каждое изменение сразу уходит в живой плеер ([MarsMusicPlayer.setAudioFx]) и
     * сохраняется через [AudioFxSettings]; тело диалога перерисовывается на месте.
     */
    private fun onEqClicked() {
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

    /** «BASS BOOST · 65%»: hardware scale 0..1000 shown as percent, WebUI-style caption. */
    private fun bassBoostCaption(strength: Int): String =
        "BASS BOOST · ${AudioFxPolicy.sanitizeStrength(strength) / 10}%"

    companion object {
        private const val BASE_URL = "https://marscore.ru/music"
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
