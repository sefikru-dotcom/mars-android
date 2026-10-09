package ru.mars.tv

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
 * Data seam between the TV music controller and Mars Music HTTP (`GET /api/charts`,
 * `GET /api/search?q=…`). Blocking by contract: the controller runs it on its background
 * [Executor], exactly like [TvInteractiveGateway] does for the interactive requests.
 */
interface TvMusicGateway {
    fun fetchCharts(): TvMusicChartsResult

    /** Searches the catalog; [query] is already normalized (never blank — see [TvMusicSearchSpec]). */
    fun search(query: String): TvMusicChartsResult
}

/**
 * Playback transport seam. Production is [TvMusicPlayer] — the process-wide object whose
 * MediaPlayer survives Activity recreation and screen changes (music keeps playing after
 * the screen closes; only an explicit [stop] ends it). Tests substitute a fake.
 */
interface TvMusicTransport {
    fun play(track: MusicTrack)
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
 * Pure state machine of the TV music screen (TvInteractiveController parity: no Android,
 * no HTTP — [TvMusicGateway] and [TvMusicTransport] are the only I/O seams):
 *
 * - [load] (charts) and [search] publish [TvMusicUiState.Loading], fetch on the background
 *   [executor] and map the outcome to [TvMusicUiState.Content] / [TvMusicUiState.Empty] /
 *   [TvMusicUiState.Error] on the UI thread. Gateway crashes are contained as an Error
 *   state — a broken music backend must never take the screen down.
 * - Every request bumps a generation counter; a result from a superseded request is
 *   dropped (latest request wins), so a slow search can never flash over fresh charts
 *   and a stale failure can never overwrite newer content.
 * - [retry] repeats the LAST operation — the same search query or a charts reload —
 *   so the D-pad «Повторить» button recovers exactly what failed.
 * - [clearSearch] returns the screen to the charts without touching the transport:
 *   music that is already playing keeps playing across the list swap.
 * - [onTrackActivated] routes a valid track to the global transport; without loaded
 *   content (Loading/Empty/Error, invalid index) it is a silent no-op — activating a
 *   nonexistent track must not crash.
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
     * The operation [retry] must repeat: `null` — charts ([load]), non-null — the last
     * requested search query. Updated synchronously at request time (UI-confined).
     */
    private var lastQuery: String? = null

    /** Generation counter (UI-confined): every [load]/[destroy] invalidates in-flight work. */
    private var generation = 0

    @Volatile
    private var destroyed = false

    /** Loads the charts: publishes Loading, fetches in background, applies the result on UI. */
    fun load() {
        if (destroyed) return
        lastQuery = null
        fetch(query = null) { gateway.fetchCharts() }
    }

    /**
     * Searches the catalog (IME «search» action or the D-pad «Найти» button). A blank
     * query is a silent no-op ([TvMusicSearchSpec.normalizedQuery]) — the «Чарт» button
     * is the explicit way back to the charts. Supersedes any in-flight request.
     */
    fun search(rawQuery: String) {
        if (destroyed) return
        val query = TvMusicSearchSpec.normalizedQuery(rawQuery) ?: return
        lastQuery = query
        fetch(query = query) { gateway.search(query) }
    }

    /**
     * Returns the screen to the charts (the D-pad «Чарт» button) and supersedes any
     * in-flight search. Deliberately does NOT touch the transport: clearing the list
     * must never stop music that is already playing.
     */
    fun clearSearch() {
        load()
    }

    /** Repeats the last operation after a failure: the same search query, or the charts. */
    fun retry() {
        if (destroyed) return
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
     * D-pad select on a track row: plays it through the global transport. Without loaded
     * content or with an out-of-range index this is a no-op — there is simply no track.
     */
    fun onTrackActivated(index: Int) {
        if (destroyed) return
        val track = (state as? TvMusicUiState.Content)?.tracks?.getOrNull(index) ?: return
        transport.play(track)
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
