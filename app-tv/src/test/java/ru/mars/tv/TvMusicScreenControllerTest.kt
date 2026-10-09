package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor

/**
 * Unit tests for [TvMusicScreenController] (no Android framework: gateway, transport,
 * executor and ui-dispatcher are clean-seam fakes, TvInteractiveControllerTest parity).
 * Covers the loading/error/empty/content state machine, retry recovery, gateway crash
 * containment, play routing to the global transport, no-track no-op safety, generation
 * invalidation of superseded loads and destroy semantics, plus the search flow: query
 * normalization, search-vs-charts empties, retry repeating the last operation, clear
 * returning to the charts without stopping playback and latest-request-wins.
 */
class TvMusicScreenControllerTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    /** Queued UI-dispatcher fake: results only land when the test drains it (thread-confinement parity). */
    private class QueueUi : TvUiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class FakeGateway : TvMusicGateway {
        val results = ArrayDeque<TvMusicChartsResult>()
        var fallback: TvMusicChartsResult = TvMusicChartsResult.Loaded(emptyList())
        var calls = 0
        var chartCalls = 0
        val queries = mutableListOf<String>()
        override fun fetchCharts(): TvMusicChartsResult {
            calls++
            chartCalls++
            return if (results.isNotEmpty()) results.removeFirst() else fallback
        }

        override fun search(query: String): TvMusicChartsResult {
            calls++
            queries += query
            return if (results.isNotEmpty()) results.removeFirst() else fallback
        }
    }

    private class FakeTransport : TvMusicTransport {
        val played = mutableListOf<MusicTrack>()
        var stops = 0
        override fun play(track: MusicTrack) { played += track }
        override fun stop() { stops++ }
    }

    private fun track(id: String) = MusicTrack(id = id, title = "Track $id", artist = "Artist $id", duration = "3:20")

    private class Harness {
        val gateway = FakeGateway()
        val transport = FakeTransport()
        val executor = QueueExecutor()
        val ui = QueueUi()
        val states = mutableListOf<TvMusicUiState>()
        val controller = TvMusicScreenController(gateway, transport, executor, ui).apply {
            onStateChanged = { states += it }
        }

        fun drain() {
            while (executor.queue.isNotEmpty() || ui.queue.isNotEmpty()) {
                executor.runAll()
                ui.runAll()
            }
        }

        /** Full load round-trip: load -> executor fetch -> ui dispatch. */
        fun load() {
            controller.load()
            drain()
        }

        /** Full search round-trip: search -> executor fetch -> ui dispatch. */
        fun search(query: String) {
            controller.search(query)
            drain()
        }
    }

    // --- state machine --------------------------------------------------------------------------

    @Test
    fun initialStateIsLoadingAndNothingIsFetchedYet() {
        val h = Harness()
        assertEquals(TvMusicUiState.Loading, h.controller.state)
        assertEquals(0, h.gateway.calls)
    }

    @Test
    fun loadPublishesLoadingThenContent() {
        val h = Harness()
        val tracks = listOf(track("a"), track("b"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(tracks)
        h.controller.load()
        assertEquals("loading must show before the fetch lands", TvMusicUiState.Loading, h.controller.state)
        h.drain()
        assertEquals(TvMusicUiState.Content(tracks), h.controller.state)
        assertEquals(listOf(TvMusicUiState.Loading, TvMusicUiState.Content(tracks)), h.states)
        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun chartsFailureSurfacesErrorStateWithMessage() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Failed("HTTP 502")
        h.load()
        assertEquals(TvMusicUiState.Error("HTTP 502"), h.controller.state)
    }

    @Test
    fun blankFailureMessageFallsBackToDefault() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Failed("   ")
        h.load()
        assertEquals(TvMusicUiState.Error(TvMusicScreenController.LOAD_FAILURE), h.controller.state)
    }

    @Test
    fun gatewayCrashIsContainedAsErrorState() {
        val h = Harness()
        val throwing = object : TvMusicGateway {
            override fun fetchCharts(): TvMusicChartsResult = throw IllegalStateException("boom")
            override fun search(query: String): TvMusicChartsResult = throw IllegalStateException("boom")
        }
        val states = mutableListOf<TvMusicUiState>()
        val controller = TvMusicScreenController(throwing, h.transport, h.executor, h.ui).apply {
            onStateChanged = { states += it }
        }
        controller.load()
        h.drain()
        assertTrue("expected Error, got ${controller.state}", controller.state is TvMusicUiState.Error)
        assertTrue(states.none { it is TvMusicUiState.Content })
    }

    @Test
    fun emptyChartsShowEmptyState() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Loaded(emptyList())
        h.load()
        assertEquals(TvMusicUiState.Empty(null), h.controller.state)
        assertTrue(h.transport.played.isEmpty())
    }

    @Test
    fun retryAfterErrorRecoversToContent() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Failed("timeout")
        h.load()
        assertEquals(TvMusicUiState.Error("timeout"), h.controller.state)
        val tracks = listOf(track("a"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(tracks)
        h.controller.retry()
        h.drain()
        assertEquals(TvMusicUiState.Content(tracks), h.controller.state)
        assertEquals(2, h.gateway.calls)
    }

    // --- search ----------------------------------------------------------------------------------

    @Test
    fun searchPublishesLoadingThenContentWithTrimmedQuery() {
        val h = Harness()
        val tracks = listOf(track("s1"), track("s2"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(tracks)
        h.controller.search("  radio  ")
        assertEquals("loading must show before the fetch lands", TvMusicUiState.Loading, h.controller.state)
        h.drain()
        assertEquals(TvMusicUiState.Content(tracks), h.controller.state)
        assertEquals(listOf(TvMusicUiState.Loading, TvMusicUiState.Content(tracks)), h.states)
        assertEquals("query must reach the gateway trimmed", listOf("radio"), h.gateway.queries)
        assertEquals("search must not refetch the charts", 0, h.gateway.chartCalls)
    }

    @Test
    fun blankSearchIsSilentNoop() {
        val h = Harness()
        h.controller.search("   ")
        h.drain()
        assertEquals(TvMusicUiState.Loading, h.controller.state)
        assertTrue(h.states.isEmpty())
        assertEquals(0, h.gateway.calls)
    }

    @Test
    fun emptySearchResultCarriesTheQuery() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Loaded(emptyList())
        h.search("ночь")
        val state = h.controller.state
        assertEquals(TvMusicUiState.Empty("ночь"), state)
        assertTrue("search-empty must be distinguishable from charts-empty", (state as TvMusicUiState.Empty).isSearch)
    }

    @Test
    fun searchFailureSurfacesErrorAndRetryRepeatsTheSameQuery() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Failed("timeout")
        h.search("звёзды")
        assertEquals(TvMusicUiState.Error("timeout"), h.controller.state)
        val tracks = listOf(track("s1"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(tracks)
        h.controller.retry()
        h.drain()
        assertEquals(TvMusicUiState.Content(tracks), h.controller.state)
        assertEquals("retry must repeat the SEARCH, not the charts", listOf("звёзды", "звёзды"), h.gateway.queries)
        assertEquals(0, h.gateway.chartCalls)
    }

    @Test
    fun retryAfterChartsFailureStillReloadsCharts() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Failed("timeout")
        h.load()
        h.gateway.fallback = TvMusicChartsResult.Loaded(listOf(track("c")))
        h.controller.retry()
        h.drain()
        assertTrue(h.controller.state is TvMusicUiState.Content)
        assertEquals(2, h.gateway.chartCalls)
        assertTrue("no search may be invented by retry", h.gateway.queries.isEmpty())
    }

    @Test
    fun clearSearchReturnsToChartsWithoutStoppingPlayback() {
        val h = Harness()
        val charts = listOf(track("c1"), track("c2"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(charts)
        h.load()
        h.controller.onTrackActivated(1) // music is playing from the charts
        val found = listOf(track("s9"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(found)
        h.search("q")
        assertEquals(TvMusicUiState.Content(found), h.controller.state)
        h.gateway.fallback = TvMusicChartsResult.Loaded(charts)
        h.controller.clearSearch()
        h.drain()
        assertEquals(TvMusicUiState.Content(charts), h.controller.state)
        assertEquals("clear must reload the charts", 2, h.gateway.chartCalls)
        assertEquals("clear must never stop the music", 0, h.transport.stops)
        assertEquals(listOf(charts[1]), h.transport.played)
    }

    @Test
    fun newerSearchSupersedesOlderOne() {
        val h = Harness()
        h.gateway.results.addLast(TvMusicChartsResult.Failed("slow first query"))
        val second = listOf(track("s2"))
        h.gateway.results.addLast(TvMusicChartsResult.Loaded(second))
        h.controller.search("first")
        h.controller.search("second")
        h.drain()
        assertEquals(TvMusicUiState.Content(second), h.controller.state)
        assertTrue("the dropped search must not flash an Error", h.states.none { it is TvMusicUiState.Error })
        assertEquals(listOf("first", "second"), h.gateway.queries)
    }

    @Test
    fun clearSupersedesInflightSearch() {
        val h = Harness()
        h.gateway.results.addLast(TvMusicChartsResult.Failed("stale search failure"))
        val charts = listOf(track("c1"))
        h.gateway.results.addLast(TvMusicChartsResult.Loaded(charts))
        h.controller.search("q")
        h.controller.clearSearch()
        h.drain()
        assertEquals(TvMusicUiState.Content(charts), h.controller.state)
        assertTrue("the superseded search must not flash an Error", h.states.none { it is TvMusicUiState.Error })
        assertEquals(1, h.gateway.chartCalls)
    }

    @Test
    fun searchSupersedesInflightChartsLoad() {
        val h = Harness()
        h.gateway.results.addLast(TvMusicChartsResult.Failed("slow charts"))
        val found = listOf(track("s1"))
        h.gateway.results.addLast(TvMusicChartsResult.Loaded(found))
        h.controller.load()
        h.controller.search("q")
        h.drain()
        assertEquals(TvMusicUiState.Content(found), h.controller.state)
        assertTrue(h.states.none { it is TvMusicUiState.Error })
    }

    @Test
    fun trackActivationWorksOnSearchResults() {
        val h = Harness()
        val found = listOf(track("s1"), track("s2"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(found)
        h.search("q")
        h.controller.onTrackActivated(1)
        assertEquals(listOf(found[1]), h.transport.played)
    }

    @Test
    fun destroyedControllerIgnoresSearchClearAndRetry() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Loaded(listOf(track("a")))
        h.controller.load()
        h.controller.destroy()
        val before = h.states.size
        h.controller.search("q")
        h.controller.clearSearch()
        h.controller.retry()
        h.drain()
        assertEquals(before, h.states.size)
        // The pre-destroy charts fetch still runs on the executor, but nothing else may.
        assertEquals("destroyed controller must not start new fetches", 1, h.gateway.calls)
        assertTrue("destroyed controller must not search", h.gateway.queries.isEmpty())
    }

    // --- playback -------------------------------------------------------------------------------

    @Test
    fun trackActivationPlaysThroughTransport() {
        val h = Harness()
        val tracks = listOf(track("a"), track("b"), track("c"))
        h.gateway.fallback = TvMusicChartsResult.Loaded(tracks)
        h.load()
        h.controller.onTrackActivated(2)
        assertEquals(listOf(tracks[2]), h.transport.played)
        h.controller.onTrackActivated(0)
        assertEquals(listOf(tracks[2], tracks[0]), h.transport.played)
    }

    @Test
    fun trackActivationWithoutTrackIsNoopAndNeverCrashes() {
        val h = Harness()
        // Loading state: nothing fetched yet.
        h.controller.onTrackActivated(0)
        // Empty state: the charts came back with zero tracks.
        h.gateway.fallback = TvMusicChartsResult.Loaded(emptyList())
        h.load()
        h.controller.onTrackActivated(0)
        // Error state: no list to play from.
        h.gateway.fallback = TvMusicChartsResult.Failed("down")
        h.load()
        h.controller.onTrackActivated(0)
        // Content state: out-of-range and negative indexes.
        h.gateway.fallback = TvMusicChartsResult.Loaded(listOf(track("a")))
        h.load()
        h.controller.onTrackActivated(5)
        h.controller.onTrackActivated(-1)
        assertTrue("no phantom playback", h.transport.played.isEmpty())
    }

    @Test
    fun stopPlaybackStopsTheGlobalTransport() {
        val h = Harness()
        h.controller.stopPlayback()
        assertEquals(1, h.transport.stops)
    }

    // --- lifecycle guards ------------------------------------------------------------------------

    @Test
    fun destroyIgnoresLateResultsAndFurtherActions() {
        val h = Harness()
        h.gateway.fallback = TvMusicChartsResult.Loaded(listOf(track("a")))
        h.controller.load() // executor work queued, not run
        val before = h.states.size
        h.controller.destroy()
        h.drain()
        assertEquals("no state may be published after destroy", before, h.states.size)
        h.controller.onTrackActivated(0)
        h.controller.stopPlayback()
        h.controller.load()
        h.drain()
        assertEquals(before, h.states.size)
        assertTrue(h.transport.played.isEmpty())
        assertEquals(0, h.transport.stops)
        assertEquals("destroyed controller must not refetch", 1, h.gateway.calls)
    }

    @Test
    fun supersededLoadResultIsDroppedByGenerationGuard() {
        val h = Harness()
        h.gateway.results.addLast(TvMusicChartsResult.Failed("slow failure"))
        val tracks = listOf(track("fresh"))
        h.gateway.results.addLast(TvMusicChartsResult.Loaded(tracks))
        h.controller.load() // first load queued
        h.controller.load() // second load supersedes it before either runs
        h.drain()
        assertEquals(TvMusicUiState.Content(tracks), h.controller.state)
        assertTrue("the dropped load must not flash an Error", h.states.none { it is TvMusicUiState.Error })
    }
}
