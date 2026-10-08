package ru.mars.tv

import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Result of one charts fetch for the TV music screen. Produced by [TvMusicGateway]:
 * the production implementation ([RepositoryTvMusicGateway]) maps the real
 * [ru.mars.network.repository.MusicRepository] onto it; unit tests substitute fakes.
 */
sealed interface TvMusicChartsResult {
    /** Charts arrived (possibly an empty list — the screen then shows the empty state). */
    data class Loaded(val tracks: List<MusicTrack>) : TvMusicChartsResult
    /** Network/parse failure; [message] is safe to surface verbatim on screen. */
    data class Failed(val message: String) : TvMusicChartsResult
}

/**
 * Data seam between the TV music controller and Mars Music HTTP (`GET /api/charts`).
 * Blocking by contract: the controller runs it on its background [Executor], exactly
 * like [TvInteractiveGateway] does for the interactive requests.
 */
interface TvMusicGateway {
    fun fetchCharts(): TvMusicChartsResult
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
    /** Charts fetch in flight (initial state and every reload/retry). */
    object Loading : TvMusicUiState
    /** Fetch failed; [message] is the user-visible reason plus a retry affordance. */
    data class Error(val message: String) : TvMusicUiState
    /** Fetch succeeded but the service returned zero tracks. */
    object Empty : TvMusicUiState
    /** Charts loaded; [tracks] render as D-pad focusable rows. */
    data class Content(val tracks: List<MusicTrack>) : TvMusicUiState
}

/**
 * Pure state machine of the TV music screen (TvInteractiveController parity: no Android,
 * no HTTP — [TvMusicGateway] and [TvMusicTransport] are the only I/O seams):
 *
 * - [load] publishes [TvMusicUiState.Loading], fetches the charts on the background
 *   [executor] and maps the outcome to [TvMusicUiState.Content] / [TvMusicUiState.Empty] /
 *   [TvMusicUiState.Error] on the UI thread. Gateway crashes are contained as an Error
 *   state — a broken music backend must never take the screen down.
 * - Every load bumps a generation counter; a result from a superseded load is dropped,
 *   so a slow failure can never flash over fresh content.
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

    /** Generation counter (UI-confined): every [load]/[destroy] invalidates in-flight work. */
    private var generation = 0

    @Volatile
    private var destroyed = false

    /** Loads the charts: publishes Loading, fetches in background, applies the result on UI. */
    fun load() {
        if (destroyed) return
        val gen = ++generation
        setState(TvMusicUiState.Loading)
        executor.execute {
            val result = try {
                gateway.fetchCharts()
            } catch (error: Throwable) {
                // A crashing gateway/stack is a load failure, never a UI crash.
                TvMusicChartsResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            uiDispatcher.dispatch {
                if (destroyed || gen != generation) return@dispatch
                setState(
                    when (result) {
                        is TvMusicChartsResult.Loaded ->
                            if (result.tracks.isEmpty()) TvMusicUiState.Empty
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
