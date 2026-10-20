package ru.mars.tv

import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Result of one charts fetch or search query for the TV music screen. Produced by
 * [TvMusicGateway]: the production implementation ([RepositoryTvMusicGateway]) maps the
 * real [ru.mars.network.repository.MusicRepository] onto it; unit tests substitute fakes.
 */
sealed interface TvMusicChartsResult {
    /** Tracks arrived (possibly an empty list — the screen then shows the empty state). */
    data class Loaded(val tracks: List<MusicTrack>) : TvMusicChartsResult
    /** Network/parse failure; [message] is safe to surface verbatim on screen. */
    data class Failed(val message: String) : TvMusicChartsResult
}

/**
 * Result of one personal-feed fetch (`GET /api/home`) for the TV music screen. Produced by
 * [TvMusicGateway]: the production implementation ([RepositoryTvMusicGateway]) maps
 * [ru.mars.network.repository.MusicRepository.getHome] onto it; unit tests substitute fakes.
 */
sealed interface TvMusicHomeResult {
    /**
     * Shelves arrived in server order. Empty `items` inside a shelf is a normal server
     * condition and stays as-is — [TvMusicHomePolicy] decides what is renderable.
     */
    data class Loaded(val shelves: List<MusicShelf>) : TvMusicHomeResult
    /** Network/parse failure; [message] is safe to surface verbatim on screen. */
    data class Failed(val message: String) : TvMusicHomeResult
}

/**
 * Data seam between the TV music controller and Mars Music HTTP (`GET /api/charts`,
 * `GET /api/search?q=…`, `GET /api/home`). Blocking by contract: the controller runs it on
 * its background [Executor], exactly like [TvInteractiveGateway] does for the interactive
 * requests.
 */
interface TvMusicGateway {
    fun fetchCharts(): TvMusicChartsResult

    /** Searches the catalog; [query] is already normalized (never blank — see [TvMusicSearchSpec]). */
    fun search(query: String): TvMusicChartsResult

    /**
     * Personal feed (`GET /api/home`, mobile «Для тебя» parity): the server's shelf set
     * («Продолжить слушать», «Новинки», «Для тебя», «Похожие на …»). An empty feed is
     * [TvMusicHomeResult.Loaded] with no renderable shelves — never a failure.
     */
    fun fetchHome(): TvMusicHomeResult
}

/**
 * Playback transport seam. Production is [TvMusicPlayer] — the process-wide object whose
 * MediaPlayer survives Activity recreation and screen changes (music keeps playing after
 * the screen closes; only an explicit [stop] ends it). Tests substitute a fake.
 */
interface TvMusicTransport {
    fun play(track: MusicTrack)

    /**
     * Binds [tracks] as the playback playlist and starts it on [startIndex]; when a
     * track completes the transport auto-advances to its successor ([TvPlaylistPolicy])
     * and goes idle once the playlist is exhausted. Out-of-range is a no-op. The default
     * degrades to a single-track [play], so simple transports stay playlist-agnostic.
     */
    fun playPlaylist(tracks: List<MusicTrack>, startIndex: Int) {
        tracks.getOrNull(startIndex)?.let { play(it) }
    }

    fun stop()
}

/** The four states of the TV music screen: exactly one is rendered at a time. */
sealed interface TvMusicUiState {
    /** Charts/search fetch in flight (initial state and every reload/retry/search). */
    object Loading : TvMusicUiState
    /** Fetch failed; [message] is the user-visible reason plus a retry affordance. */
    data class Error(val message: String) : TvMusicUiState
    /**
     * Fetch succeeded but returned zero tracks. [query] tells the two empties apart:
     * `null` — the charts are empty, non-null — the search found nothing for that query
     * (the screen words them differently; [TvMusicSearchSpec.emptyMessage]).
     */
    data class Empty(val query: String?) : TvMusicUiState {
        /** True when nothing was found by a search (as opposed to empty charts). */
        val isSearch: Boolean get() = query != null
    }
    /** Tracks loaded (charts or search); [tracks] render as D-pad focusable rows. */
    data class Content(val tracks: List<MusicTrack>) : TvMusicUiState
}

/**
 * Which branch the TV music screen shows. «Для тебя» renders the personal feed shelves
 * ([TvMusicHomeState]); «Чарты» renders [TvMusicUiState] (charts *and* search results —
 * a search happens in the catalog branch, so submitting one selects [CHARTS]);
 * «Плейлисты» and «История» render the server library ([TvPlaylistsState] /
 * [TvPlaylistTracksState] / [TvHistoryState], loaded by [TvMusicLibraryController] —
 * mobile MusicActivity tab parity).
 */
enum class TvMusicTab {
    FOR_YOU,
    CHARTS,
    PLAYLISTS,
    HISTORY,
    ;

    /**
     * True for the branches owned by the library controller: this controller only switches
     * to them ([selectTab]) and never fetches — «Повторить» on them repeats the library
     * request, not a charts/search one.
     */
    val isLibrary: Boolean get() = this == PLAYLISTS || this == HISTORY
}

/**
 * State of the «Для тебя» personal feed (`GET /api/home`), independent of the charts list
 * state [TvMusicUiState]. Exactly one value is current at a time:
 *
 * - [Idle] — nothing requested yet (the screen starts on the feed and immediately [Loading]).
 * - [Loading] — fetch in flight on the background executor.
 * - [Failed] — network/parse failure; [Failed.message] is the user-visible reason and
 *   `retry` repeats the FEED (not the charts).
 * - [Empty] — the fetch succeeded but no shelf carries tracks ([TvMusicHomePolicy]); the
 *   screen then falls back to the usual charts, so the empty feed never dead-ends.
 * - [Shelves] — renderable shelves in server order, each becoming a heading plus its
 *   rows; the flattened [TvMusicHomePolicy.playbackQueue] is the playback list.
 */
sealed interface TvMusicHomeState {
    object Idle : TvMusicHomeState
    object Loading : TvMusicHomeState
    data class Failed(val message: String) : TvMusicHomeState
    object Empty : TvMusicHomeState
    data class Shelves(val shelves: List<MusicShelf>) : TvMusicHomeState
}

/**
 * Pure state machine of the TV music screen (TvInteractiveController parity: no Android,
 * no HTTP — [TvMusicGateway] and [TvMusicTransport] are the only I/O seams):
 *
 * - [load] (charts) and [search] publish [TvMusicUiState.Loading], fetch on the background
 *   [executor] and map the outcome to [TvMusicUiState.Content] / [TvMusicUiState.Empty] /
 *   [TvMusicUiState.Error] on the UI thread. [loadHome] does the same for the personal feed
 *   («Для тебя», `GET /api/home`) with its own state ([TvMusicHomeState]) and its own
 *   generation counter, so a late feed answer can never flash over the catalog branch.
 *   Gateway crashes are contained as an Error state — a broken music backend must never
 *   take the screen down.
 * - Every request bumps a generation counter; a result from a superseded request is
 *   dropped (latest request wins), so a slow search can never flash over fresh charts
 *   and a stale failure can never overwrite newer content.
 * - The screen has two branches ([tab]): «Для тебя» renders the feed, «Чарты» renders
 *   charts and search results. A search selects «Чарты» (the catalog is where searching
 *   happens) and an empty feed falls back to it ([TvMusicHomePolicy.tabAfterHomeResult]),
 *   so the personal feed never dead-ends on a blank screen.
 * - [retry] repeats the LAST operation OF THE ACTIVE BRANCH — the same search query, a
 *   charts reload, or the feed again when the feed itself failed
 *   ([TvMusicHomePolicy.retryRepeatsFeed]) — so the D-pad «Повторить» button recovers
 *   exactly what failed.
 * - [clearSearch] returns the screen to the charts without touching the transport:
 *   music that is already playing keeps playing across the list swap.
 * - [onTrackActivated] routes the shown list plus the selected row to the global
 *   transport, which plays the row and auto-advances through the rest (continuous
 *   playlist, [TvPlaylistPolicy]); without loaded content (Loading/Empty/Error, invalid
 *   index) it is a silent no-op — activating a nonexistent track must not crash.
 * - The transport is process-global: [destroy] never stops playback (music outlives the
 *   screen); only an explicit [stopPlayback] does. After [destroy] the controller
 *   publishes no state and performs no action.
 */
class TvMusicScreenController(
    private val gateway: TvMusicGateway,
    private val transport: TvMusicTransport,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {
    var state: TvMusicUiState = TvMusicUiState.Loading
        private set

    /** State observer (TV music Activity render); called on the UI thread only. */
    var onStateChanged: ((TvMusicUiState) -> Unit)? = null

    /**
     * Active branch of the screen: «Для тебя» (personal feed) or «Чарты» (charts/search).
     * Starts on the feed ([TvMusicHomePolicy.initialTab]); switching is always explicit —
     * a D-pad press on a tab button, a submitted search or the empty-feed fallback.
     */
    var tab: TvMusicTab = TvMusicHomePolicy.initialTab
        private set

    /** Feed state of the «Для тебя» branch — independent of [state], which mirrors the charts. */
    var homeState: TvMusicHomeState = TvMusicHomeState.Idle
        private set

    /** Branch observer (tab buttons restyle); called on the UI thread only. */
    var onTabChanged: ((TvMusicTab) -> Unit)? = null

    /** Feed-state observer (feed branch render); called on the UI thread only. */
    var onHomeStateChanged: ((TvMusicHomeState) -> Unit)? = null

    /**
     * The operation [retry] must repeat: `null` — charts ([load]), non-null — the last
     * requested search query. Updated synchronously at request time (UI-confined).
     */
    private var lastQuery: String? = null

    /** Generation counter (UI-confined): every [load]/[destroy] invalidates in-flight work. */
    private var generation = 0

    /** Feed generation counter (UI-confined): a charts load invalidates an in-flight feed. */
    private var homeGeneration = 0

    @Volatile
    private var destroyed = false

    /** Loads the charts: publishes Loading, fetches in background, applies the result on UI. */
    fun load() {
        if (destroyed) return
        setTab(TvMusicHomePolicy.chartsTab)
        lastQuery = null
        // Каталог — единственная видимая ветка: ответ по ленте в пути больше не актуален.
        homeGeneration++
        fetch(query = null) { gateway.fetchCharts() }
    }

    /**
     * Loads the personal feed («Для тебя», `GET /api/home`) — the screen's startup request.
     * A gateway failure stays on the feed as [TvMusicHomeState.Failed] with a retry that
     * repeats the FEED; a genuinely empty feed ([TvMusicHomeState.Empty]) falls back to the
     * charts so the screen never ends up blank ([TvMusicHomePolicy]).
     */
    fun loadHome() {
        if (destroyed) return
        setTab(TvMusicHomePolicy.initialTab)
        // В ветке ленты «Повторить» повторяет ленту, а не последний поиск по каталогу.
        lastQuery = null
        val gen = ++homeGeneration
        setHomeState(TvMusicHomeState.Loading)
        executor.execute {
            val result = try {
                gateway.fetchHome()
            } catch (error: Throwable) {
                // Сбой шва/разбора — состояние ошибки, а не падение экрана (parity с fetch()).
                TvMusicHomeResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            uiDispatcher.dispatch {
                if (destroyed || gen != homeGeneration) return@dispatch
                applyHomeResult(result)
            }
        }
    }

    /** D-pad switch between the branches: the selected one is always (re)loaded. */
    fun selectTab(next: TvMusicTab) {
        if (destroyed) return
        when (next) {
            TvMusicTab.FOR_YOU -> loadHome()
            TvMusicTab.CHARTS -> load()
            // Библиотеку грузит свой контроллер ([TvMusicLibraryController]): здесь только
            // переключение ветки, но ответы ленты/каталога в пути уже неактуальны.
            TvMusicTab.PLAYLISTS, TvMusicTab.HISTORY -> {
                generation++
                homeGeneration++
                setTab(next)
            }
        }
    }

    /**
     * Searches the catalog (IME «search» action or the D-pad «Найти» button). A blank
     * query is a silent no-op ([TvMusicSearchSpec.normalizedQuery]) — the «Чарты» tab
     * button is the explicit way back to the charts. Supersedes any in-flight request.
     */
    fun search(rawQuery: String) {
        if (destroyed) return
        val query = TvMusicSearchSpec.normalizedQuery(rawQuery) ?: return
        // Поиск живёт в каталоге: результат показывается в ветке чартов, а не в ленте.
        setTab(TvMusicHomePolicy.chartsTab)
        lastQuery = query
        fetch(query = query) { gateway.search(query) }
    }

    /**
     * Returns the screen to the charts (the «Чарты» tab button) and supersedes any
     * in-flight search. Deliberately does NOT touch the transport: clearing the list
     * must never stop music that is already playing.
     */
    fun clearSearch() {
        load()
    }

    /**
     * Repeats the last operation of the ACTIVE branch — the same search query, a charts
     * reload, or the feed itself when the feed is what failed
     * ([TvMusicHomePolicy.retryRepeatsFeed]) — so «Повторить» never silently answers a
     * failed feed with the charts.
     */
    fun retry() {
        if (destroyed) return
        // Библиотечные вкладки повторяет их контроллер: «Повторить» не должно молча
        // перезагружать чарты под вкладкой «Плейлисты».
        if (tab.isLibrary) return
        if (TvMusicHomePolicy.retryRepeatsFeed(tab)) {
            loadHome()
            return
        }
        lastQuery?.let { search(it) } ?: load()
    }

    /** Shared request pipeline: Loading now, gateway on the [executor], result on the UI thread. */
    private fun fetch(query: String?, block: () -> TvMusicChartsResult) {
        val gen = ++generation
        setState(TvMusicUiState.Loading)
        executor.execute {
            val result = try {
                block()
            } catch (error: Throwable) {
                // A crashing gateway/stack is a load failure, never a UI crash.
                TvMusicChartsResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                setState(
                    when (result) {
                        is TvMusicChartsResult.Loaded ->
                            if (result.tracks.isEmpty()) TvMusicUiState.Empty(query)
                            else TvMusicUiState.Content(result.tracks)
                        is TvMusicChartsResult.Failed ->
                            TvMusicUiState.Error(result.message.ifBlank { LOAD_FAILURE })
                    },
                )
            }
        }
    }

    /**
     * D-pad select on a track row: binds the whole shown list as the transport playlist
     * and starts it on [index], so playback continues through the list automatically
     * after this track completes. Without loaded content or with an out-of-range index
     * this is a no-op — there is simply no track.
     */
    fun onTrackActivated(index: Int) {
        if (destroyed) return
        val tracks = (state as? TvMusicUiState.Content)?.tracks ?: return
        if (tracks.getOrNull(index) == null) return
        transport.playPlaylist(tracks, index)
    }

    /**
     * D-pad select on a FEED row: the shelves' flat [TvMusicHomePolicy.playbackQueue] becomes
     * the transport playlist and playback starts on [index] — the row index on screen is the
     * queue position, so the feed keeps playing past the shelf it was started in. Without
     * rendered shelves or with an out-of-range index it is a silent no-op.
     */
    fun onHomeTrackActivated(index: Int) {
        if (destroyed) return
        val shelves = (homeState as? TvMusicHomeState.Shelves)?.shelves ?: return
        val queue = TvMusicHomePolicy.playbackQueue(shelves)
        if (queue.getOrNull(index) == null) return
        transport.playPlaylist(queue, index)
    }

    /** Explicit user stop of the global transport (leaving the screen does NOT stop music). */
    fun stopPlayback() {
        if (destroyed) return
        transport.stop()
    }

    /** Screen teardown: invalidates in-flight loads and silences every later action. */
    fun destroy() {
        destroyed = true
        generation++
        homeGeneration++
    }

    /**
     * Applies one feed answer: state first, then the branch decision. The tab is published
     * BEFORE the state so an empty feed switches the screen to the charts without rendering
     * a feed branch that is about to disappear; the charts reload is the fallback itself
     * ([TvMusicHomePolicy.tabAfterHomeResult]) — an empty feed must never leave a blank screen.
     */
    private fun applyHomeResult(result: TvMusicHomeResult) {
        val next = TvMusicHomePolicy.state(result)
        setTab(TvMusicHomePolicy.tabAfterHomeResult(result))
        setHomeState(next)
        if (next is TvMusicHomeState.Empty) load()
    }

    private fun setTab(next: TvMusicTab) {
        if (tab == next) return
        tab = next
        onTabChanged?.invoke(next)
    }

    private fun setHomeState(next: TvMusicHomeState) {
        homeState = next
        onHomeStateChanged?.invoke(next)
    }

    private fun setState(next: TvMusicUiState) {
        state = next
        onStateChanged?.invoke(next)
    }

    companion object {
        const val LOAD_FAILURE = "Музыка недоступна — попробуйте ещё раз."

        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-music-charts").apply { isDaemon = true }
        }
    }
}
